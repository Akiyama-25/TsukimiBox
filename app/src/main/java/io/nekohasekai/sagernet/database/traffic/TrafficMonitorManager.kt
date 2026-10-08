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

    fun getSlotTimestamp(timeMs: Long): Long = timeMs - (timeMs % SLOT_DURATION_MS)

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

    suspend fun flush() {
        mutex.withLock {
            flushLocked()
        }
    }

    private fun flushLocked() {
        if (currentSlot == 0L) return
        if (pendingRxProxy == 0L && pendingTxProxy == 0L && pendingRxDirect == 0L && pendingTxDirect == 0L) {
            lastFlushTime = System.currentTimeMillis()
            return
        }

        val slot = currentSlot
        val cal = Calendar.getInstance().apply { timeInMillis = slot }
        val date = dateFormat.format(cal.time)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        val rxP = pendingRxProxy
        val txP = pendingTxProxy
        val rxD = pendingRxDirect
        val txD = pendingTxDirect

        pendingRxProxy = 0L
        pendingTxProxy = 0L
        pendingRxDirect = 0L
        pendingTxDirect = 0L
        lastFlushTime = System.currentTimeMillis()

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
                TrafficDatabase.trafficDao.clearAll()
            }
        }
    }
}
