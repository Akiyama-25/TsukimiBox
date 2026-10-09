package io.nekohasekai.sagernet.bg.proto

import android.net.TrafficStats
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.traffic.TrafficMonitorManager
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.*

class TrafficLooper
    (
    val data: BaseService.Data, private val sc: CoroutineScope
) {

    private var job: Job? = null
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data

    suspend fun stop() {
        job?.cancel()
        TrafficMonitorManager.flush()
        // finally traffic post
        if (!DataStore.profileTrafficStatistics) return
        val traffic = mutableMapOf<Long, TrafficData>()
        data.proxy?.config?.trafficMap?.forEach { (_, ents) ->
            for (ent in ents) {
                val item = idMap[ent.id] ?: return@forEach
                ent.rx = item.rx
                ent.tx = item.tx
                ProfileManager.updateProfile(ent) // update DB
                traffic[ent.id] = TrafficData(
                    id = ent.id,
                    rx = ent.rx,
                    tx = ent.tx,
                )
            }
        }
        data.binder.broadcast { b ->
            for (t in traffic) {
                b.cbTrafficUpdate(t.value)
            }
        }
        Logs.d("finally traffic post done")
    }

    fun start() {
        job = sc.launch { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    fun selectMain(id: Long) {
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            // post traffic when switch
            if (DataStore.profileTrafficStatistics) {
                data.proxy?.config?.trafficMap?.get(tag)?.firstOrNull()?.let {
                    it.rx = rx
                    it.tx = tx
                    runOnDefaultDispatcher {
                        ProfileManager.updateProfile(it) // update DB
                    }
                }
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            ignore = false
        }
    }

    private suspend fun loop() {
        val delayMs = DataStore.speedInterval.toLong()
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics
        if (delayMs == 0L) return

        var trafficUpdater: TrafficUpdater? = null
        var proxy: ProxyInstance?

        // for display
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)

        while (sc.isActive) {
            proxy = data.proxy
            if (proxy == null) {
                delay(delayMs)
                continue
            }

            if (trafficUpdater == null) {
                if (!proxy.isInitialized()) continue
                idMap.clear()
                idMap[-1] = itemBypass
                //
                val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                proxy.config.trafficMap.forEach { (tag, ents) ->
                    tags.add(tag)
                    for (ent in ents) {
                        val item = TrafficUpdater.TrafficLooperData(
                            tag = tag,
                            rx = ent.rx,
                            tx = ent.tx,
                            rxBase = ent.rx,
                            txBase = ent.tx,
                            ignore = proxy.config.selectorGroupId >= 0L,
                        )
                        idMap[ent.id] = item
                        tagMap[tag] = item
                        Logs.d("traffic count $tag to ${ent.id}")
                    }
                }
                if (proxy.config.selectorGroupId >= 0L) {
                    selectMain(proxy.config.mainEntId)
                }
                //
                trafficUpdater = TrafficUpdater(
                    box = proxy.box, items = idMap.values.toList()
                )
                proxy.box.setV2rayStats(tags.joinToString("\n"))
            }

            val diffs = trafficUpdater.updateAll()
            if (!sc.isActive) return

            var deltaRxProxy = 0L
            var deltaTxProxy = 0L
            var deltaRxDirect = 0L
            var deltaTxDirect = 0L
            diffs.forEach { (tag, diff) ->
                if (tag == TAG_BYPASS) {
                    deltaRxDirect += diff.rx
                    deltaTxDirect += diff.tx
                } else {
                    deltaRxProxy += diff.rx
                    deltaTxProxy += diff.tx
                }
            }
            if (deltaRxProxy > 0 || deltaTxProxy > 0 || deltaRxDirect > 0 || deltaTxDirect > 0) {
                TrafficMonitorManager.onTrafficDelta(deltaRxProxy, deltaTxProxy, deltaRxDirect, deltaTxDirect)
            }
            if (deltaRxProxy > 0 || deltaTxProxy > 0) {
                distributeAppTraffic(deltaRxProxy, deltaTxProxy)
            }

            // add all non-bypass to "main"
            var mainTxRate = 0L
            var mainRxRate = 0L
            var mainTx = 0L
            var mainRx = 0L
            tagMap.forEach { (_, it) ->
                if (!it.ignore) {
                    mainTxRate += it.txRate
                    mainRxRate += it.rxRate
                }
                mainTx += it.tx - it.txBase
                mainRx += it.rx - it.rxBase
            }

            // speed
            val speed = SpeedDisplayData(
                mainTxRate,
                mainRxRate,
                if (showDirectSpeed) itemBypass.txRate else 0L,
                if (showDirectSpeed) itemBypass.rxRate else 0L,
                mainTx,
                mainRx
            )

            // broadcast (MainActivity)
            if (data.state == BaseService.State.Connected
                && data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        b.cbSpeedUpdate(speed)
                        if (profileTrafficStatistics) {
                            idMap.forEach { (id, item) ->
                                b.cbTrafficUpdate(
                                    TrafficData(id = id, rx = item.rx, tx = item.tx) // display
                                )
                            }
                        }
                    }
                }
            }

            // ServiceNotification
            data.notification?.apply {
                if (listenPostSpeed) postNotificationSpeedUpdate(speed)
            }

            delay(delayMs)
        }
    }

    private val lastUidRxMap = HashMap<Int, Long>()
    private val lastUidTxMap = HashMap<Int, Long>()

    private fun distributeAppTraffic(deltaRxProxy: Long, deltaTxProxy: Long) {
        val myUid = android.os.Process.myUid()
        val recentUids = TrafficMonitorManager.getRecentActiveUids(15000L).filter { it != myUid && it > 1000 }

        // Find candidate UIDs
        val candidateUids = if (recentUids.isNotEmpty()) {
            recentUids
        } else {
            lastUidRxMap.keys.filter { it != myUid && it > 1000 }
        }

        val uidRxDeltas = mutableMapOf<Int, Long>()
        val uidTxDeltas = mutableMapOf<Int, Long>()

        for (uid in candidateUids) {
            val rxNow = TrafficStats.getUidRxBytes(uid)
            val txNow = TrafficStats.getUidTxBytes(uid)
            if (rxNow < 0 || txNow < 0) continue

            val lastRx = lastUidRxMap[uid] ?: rxNow
            val lastTx = lastUidTxMap[uid] ?: txNow
            lastUidRxMap[uid] = rxNow
            lastUidTxMap[uid] = txNow

            val diffRx = rxNow - lastRx
            val diffTx = txNow - lastTx
            if (diffRx > 0) uidRxDeltas[uid] = diffRx
            if (diffTx > 0) uidTxDeltas[uid] = diffTx
        }

        // Clean stale UIDs
        if (lastUidRxMap.size > 200) {
            val valid = candidateUids.toSet()
            lastUidRxMap.entries.removeIf { it.key !in valid }
            lastUidTxMap.entries.removeIf { it.key !in valid }
        }

        // Distribute RX proxy traffic only
        if (deltaRxProxy > 0) {
            val totalUidRx = uidRxDeltas.values.sum()
            if (totalUidRx > 0) {
                for ((uid, diffRx) in uidRxDeltas) {
                    val allocatedRx = (diffRx.toDouble() / totalUidRx.toDouble() * deltaRxProxy).toLong()
                    if (allocatedRx > 0) {
                        val pkg = resolvePackageName(uid)
                        TrafficMonitorManager.onAppTrafficDelta(pkg, uid, allocatedRx, 0L)
                    }
                }
            } else if (candidateUids.isNotEmpty()) {
                val targetUid = candidateUids.first()
                val pkg = resolvePackageName(targetUid)
                TrafficMonitorManager.onAppTrafficDelta(pkg, targetUid, deltaRxProxy, 0L)
            }
        }

        // Distribute TX proxy traffic only
        if (deltaTxProxy > 0) {
            val totalUidTx = uidTxDeltas.values.sum()
            if (totalUidTx > 0) {
                for ((uid, diffTx) in uidTxDeltas) {
                    val allocatedTx = (diffTx.toDouble() / totalUidTx.toDouble() * deltaTxProxy).toLong()
                    if (allocatedTx > 0) {
                        val pkg = resolvePackageName(uid)
                        TrafficMonitorManager.onAppTrafficDelta(pkg, uid, 0L, allocatedTx)
                    }
                }
            } else if (candidateUids.isNotEmpty()) {
                val targetUid = candidateUids.first()
                val pkg = resolvePackageName(targetUid)
                TrafficMonitorManager.onAppTrafficDelta(pkg, targetUid, 0L, deltaTxProxy)
            }
        }
    }

    private fun resolvePackageName(uid: Int): String {
        val pkgs = PackageCache.uidMap[uid]
        if (!pkgs.isNullOrEmpty()) {
            return pkgs.first()
        }
        val systemPkgs = app.packageManager.getPackagesForUid(uid)
        return systemPkgs?.firstOrNull() ?: "uid_$uid"
    }
}