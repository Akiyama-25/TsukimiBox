package io.nekohasekai.sagernet.database.traffic

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.*

object TrafficMonitorManager {

    const val SLOT_DURATION_MS = 15 * 60 * 1000L // 15 minutes = 900,000 ms
    const val RETENTION_DAYS = 7

    private val mutex = Mutex()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private var currentSlot: Long = 0L
    private var pendingRxProxy: Long = 0L
    private var pendingTxProxy: Long = 0L
    private var pendingRxDirect: Long = 0L
    private var pendingTxDirect: Long = 0L
    private var lastFlushTime: Long = 0L
    private var lastDateString: String = ""

    data class AppPendingDelta(
        val packageName: String,
        val uid: Int,
        var rxProxy: Long,
        var txProxy: Long,
    )

    data class UidConnectionStat(
        var lastSeen: Long,
        var count: Int,
    )

    private val pendingAppTraffic = mutableMapOf<String, AppPendingDelta>()
    private val recentUidStats = Collections.synchronizedMap(LinkedHashMap<Int, UidConnectionStat>())

    fun getSlotTimestamp(timeMs: Long): Long = timeMs - (timeMs % SLOT_DURATION_MS)

    fun recordUidConnection(uid: Int) {
        if (uid <= 1000) return
        val now = System.currentTimeMillis()
        synchronized(recentUidStats) {
            val stat = recentUidStats.getOrPut(uid) { UidConnectionStat(now, 0) }
            stat.lastSeen = now
            stat.count = (stat.count + 1).coerceAtMost(500)
        }
    }

    fun getCandidateUidWeights(
        excludeUid: Int,
        withinMs: Long = 15000L,
        maxFallbackMs: Long = 300000L
    ): Map<Int, Double> {
        val now = System.currentTimeMillis()
        synchronized(recentUidStats) {
            val it = recentUidStats.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (now - entry.value.lastSeen > maxFallbackMs) {
                    it.remove()
                }
            }

            val validEntries = recentUidStats.filter { (uid, _) ->
                uid != excludeUid && uid > 1000
            }
            if (validEntries.isEmpty()) return emptyMap()

            val recentEntries = validEntries.filter { (_, stat) -> now - stat.lastSeen <= withinMs }
            val targetEntries = if (recentEntries.isNotEmpty()) recentEntries else {
                val sorted = validEntries.entries.sortedByDescending { it.value.lastSeen }
                sorted.take(3).associate { it.key to it.value }
            }

            val result = mutableMapOf<Int, Double>()
            for ((uid, stat) in targetEntries) {
                result[uid] = stat.count.coerceAtLeast(1).toDouble()
                if (stat.count > 1) {
                    stat.count = (stat.count * 0.9).toInt().coerceAtLeast(1)
                }
            }
            return result
        }
    }

    fun getRecentActiveUids(withinMs: Long = 15000L): Set<Int> {
        val now = System.currentTimeMillis()
        synchronized(recentUidStats) {
            return recentUidStats.filter { (uid, stat) ->
                now - stat.lastSeen <= withinMs && uid > 1000
            }.keys.toSet()
        }
    }

    fun onTrafficDelta(rxProxy: Long, txProxy: Long, rxDirect: Long, txDirect: Long) {
        if (rxProxy <= 0 && txProxy <= 0 && rxDirect <= 0 && txDirect <= 0) return

        val now = System.currentTimeMillis()
        val slot = getSlotTimestamp(now)

        runOnDefaultDispatcher {
            mutex.withLock {
                if (currentSlot == 0L) {
                    currentSlot = slot
                } else if (currentSlot != slot) {
                    // Slot rolled over, flush previous slot
                    flushLocked()
                    currentSlot = slot
                }

                pendingRxProxy += rxProxy
                pendingTxProxy += txProxy
                pendingRxDirect += rxDirect
                pendingTxDirect += txDirect

                // Check 00:00 midnight rollover & 7-day retention
                val todayStr = dateFormat.format(Date(now))
                if (lastDateString.isEmpty()) {
                    lastDateString = todayStr
                    purgeOldRecordsLocked(now)
                } else if (lastDateString != todayStr) {
                    lastDateString = todayStr
                    flushLocked()
                    purgeOldRecordsLocked(now)
                }

                // Flush every 5 seconds if traffic accumulated
                if (now - lastFlushTime >= 5000L) {
                    flushLocked()
                }
            }
        }
    }

    fun onAppTrafficDelta(packageName: String, uid: Int, rxProxy: Long, txProxy: Long) {
        if (rxProxy <= 0 && txProxy <= 0) return

        val now = System.currentTimeMillis()
        val slot = getSlotTimestamp(now)

        runOnDefaultDispatcher {
            mutex.withLock {
                if (currentSlot == 0L) {
                    currentSlot = slot
                } else if (currentSlot != slot) {
                    flushLocked()
                    currentSlot = slot
                }

                val key = "$slot:$packageName"
                val existing = pendingAppTraffic[key]
                if (existing != null) {
                    existing.rxProxy += rxProxy
                    existing.txProxy += txProxy
                } else {
                    pendingAppTraffic[key] = AppPendingDelta(packageName, uid, rxProxy, txProxy)
                }

                if (now - lastFlushTime >= 5000L) {
                    flushLocked()
                }
            }
        }
    }

    suspend fun flush() {
        mutex.withLock {
            flushLocked()
        }
    }

    private fun flushLocked() {
        if (currentSlot == 0L) return
        val hasSlotTraffic = pendingRxProxy > 0L || pendingTxProxy > 0L || pendingRxDirect > 0L || pendingTxDirect > 0L
        val hasAppTraffic = pendingAppTraffic.isNotEmpty()

        if (!hasSlotTraffic && !hasAppTraffic) {
            lastFlushTime = System.currentTimeMillis()
            return
        }

        val slot = currentSlot
        val cal = Calendar.getInstance().apply { timeInMillis = slot }
        val date = dateFormat.format(cal.time)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        if (hasSlotTraffic) {
            val rxP = pendingRxProxy
            val txP = pendingTxProxy
            val rxD = pendingRxDirect
            val txD = pendingTxDirect

            pendingRxProxy = 0L
            pendingTxProxy = 0L
            pendingRxDirect = 0L
            pendingTxDirect = 0L

            try {
                TrafficDatabase.trafficDao.addDelta(
                    timestamp = slot,
                    date = date,
                    hour = hour,
                    minute = minute,
                    rxProxy = rxP,
                    txProxy = txP,
                    rxDirect = rxD,
                    txDirect = txD,
                )
            } catch (e: Exception) {
                Logs.w("Failed to flush traffic records: ${e.message}")
            }
        }

        if (hasAppTraffic) {
            val appDeltas = pendingAppTraffic.values.toList()
            pendingAppTraffic.clear()
            for (delta in appDeltas) {
                try {
                    TrafficDatabase.appTrafficDao.addDelta(
                        timestamp = slot,
                        date = date,
                        packageName = delta.packageName,
                        uid = delta.uid,
                        rxProxy = delta.rxProxy,
                        txProxy = delta.txProxy,
                    )
                } catch (e: Exception) {
                    Logs.w("Failed to flush app traffic record for ${delta.packageName}: ${e.message}")
                }
            }
        }

        lastFlushTime = System.currentTimeMillis()
    }

    private fun purgeOldRecordsLocked(now: Long) {
        try {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -(RETENTION_DAYS - 1)) // keep today + prior 6 days = 7 days
            }
            val cutoff = cal.timeInMillis
            TrafficDatabase.trafficDao.deleteOlderThan(cutoff)
            TrafficDatabase.appTrafficDao.deleteOlderThan(cutoff)
            Logs.d("Purged traffic records older than $cutoff (${dateFormat.format(cal.time)})")
        } catch (e: Exception) {
            Logs.w("Failed to purge old traffic records: ${e.message}")
        }
    }

    fun purgeOldRecords() {
        runOnDefaultDispatcher {
            mutex.withLock {
                purgeOldRecordsLocked(System.currentTimeMillis())
            }
        }
    }

    fun clearAll() {
        runOnDefaultDispatcher {
            mutex.withLock {
                pendingRxProxy = 0L
                pendingTxProxy = 0L
                pendingRxDirect = 0L
                pendingTxDirect = 0L
                pendingAppTraffic.clear()
                recentUidStats.clear()
                TrafficDatabase.trafficDao.clearAll()
                TrafficDatabase.appTrafficDao.clearAll()
            }
        }
    }
}
