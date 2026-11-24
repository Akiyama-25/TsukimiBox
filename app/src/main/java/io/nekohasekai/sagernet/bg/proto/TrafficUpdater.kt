package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.fmt.LOCALHOST
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

class TrafficUpdater(
    private val box: libcore.BoxInstance? = null,
    private val rootStatsPort: Int = 0,
    val items: List<TrafficLooperData>, // contain "bypass"
) {

    class TrafficLooperData(
        // Don't associate proxyEntity
        var tag: String,
        var tx: Long = 0,
        var rx: Long = 0,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
        var ignore: Boolean = false,
    )

    private fun updateOne(
        item: TrafficLooperData,
        rootSnapshot: Map<String, Pair<Long, Long>>?,
    ): TrafficLooperData {
        // last update
        val now = System.currentTimeMillis()
        val interval = now - item.lastUpdate
        item.lastUpdate = now
        if (interval <= 0) return item.apply {
            rxRate = 0
            txRate = 0
        }

        // query
        val tx: Long
        val rx: Long
        if (rootSnapshot != null) {
            val pair = rootSnapshot[item.tag]
            tx = pair?.first ?: 0L
            rx = pair?.second ?: 0L
        } else {
            tx = box?.queryStats(item.tag, "uplink") ?: 0L
            rx = box?.queryStats(item.tag, "downlink") ?: 0L
        }

        // add diff
        item.rx += rx
        item.tx += tx
        item.rxRate = rx * 1000 / interval
        item.txRate = tx * 1000 / interval

        // return diff
        return TrafficLooperData(
            tag = item.tag,
            rx = rx,
            tx = tx,
            rxRate = item.rxRate,
            txRate = item.txRate,
        )
    }

    private suspend fun fetchRootSnapshot(): Map<String, Pair<Long, Long>> {
        if (rootStatsPort <= 0) return emptyMap()
        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("http://$LOCALHOST:$rootStatsPort/stats")
                conn = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 800
                    readTimeout = 800
                    useCaches = false
                }
                if (conn.responseCode !in 200..299) return@withContext emptyMap()
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val out = HashMap<String, Pair<Long, Long>>(json.length())
                val keys = json.keys()
                while (keys.hasNext()) {
                    val tag = keys.next()
                    val obj = json.optJSONObject(tag) ?: continue
                    out[tag] = obj.optLong("tx", 0L) to obj.optLong("rx", 0L)
                }
                out
            } catch (_: Throwable) {
                emptyMap()
            } finally {
                conn?.disconnect()
            }
        }
    }

    suspend fun updateAll(): Map<String, TrafficLooperData> {
        val rootSnapshot = if (box == null && rootStatsPort > 0) fetchRootSnapshot() else null
        val updated = mutableMapOf<String, TrafficLooperData>() // diffs
        items.forEach { item ->
            if (item.ignore) return@forEach
            var diff = updated[item.tag]
            // query a tag only once
            if (diff == null) {
                diff = updateOne(item, rootSnapshot)
                updated[item.tag] = diff
            } else {
                item.rx += diff.rx
                item.tx += diff.tx
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
            }
        }
//        Logs.d(JavaUtil.gson.toJson(items))
//        Logs.d(JavaUtil.gson.toJson(updated))
        return updated
    }
}
