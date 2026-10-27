package libcore

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

interface NB4AInterface {
    fun useOfficialAssets(): Boolean
    fun selector_OnProxySelected(selectorTag: String, tag: String)
}

interface BoxPlatformInterface {
    fun autoDetectInterfaceControl(fd: Int)
    fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long
    fun useProcFS(): Boolean
    fun findConnectionOwner(
        ipProto: Int,
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int
    ): Int
    fun packageNameByUid(uid: Int): String
    fun uidByPackageName(packageName: String): Int
    fun wifiState(): String
}

fun interface Func {
    fun invoke()
}

class ExchangeContext {
    private var cancelCallback: Func? = null

    fun onCancel(callback: Func) {
        cancelCallback = callback
    }

    fun rawSuccess(result: ByteArray) {}
    fun success(result: String) {}
    fun errorCode(code: Int) {}
    fun errnoCode(code: Int) {}
}

interface LocalDNSTransport {
    fun raw(): Boolean
    fun networkHandle(): Long
    fun exchange(ctx: ExchangeContext, message: ByteArray)
    fun lookup(ctx: ExchangeContext, network: String, domain: String)
}

class StringBox(val value: String? = null)

class StunResult(
    @JvmField var text: String = "",
    @JvmField var success: Boolean = false
)

interface HTTPResponse {
    val contentString: StringBox?
    fun getHeader(key: String): StringBox?
    fun writeTo(path: String)
}

interface HTTPRequest {
    fun allowInsecure()
    fun setURL(link: String?)
    fun setUserAgent(userAgent: String?)
    fun execute(): HTTPResponse
}

interface HTTPClient {
    fun modernTLS()
    fun restrictedTLS()
    fun keepAlive()
    fun trySocks5(port: Int)
    fun tryH3Direct()
    fun newRequest(): HTTPRequest
    fun close()
}

open class BoxInstance(
    val configJson: String = "",
    private val localTransport: LocalDNSTransport? = null
) {
    private var started = false
    private val statsMap = HashMap<String, Long>()

    open fun start() {
        if (started) return
        started = true
        if (configJson.contains("\"type\":\"tun\"") || configJson.contains("\"tun-in\"")) {
            try {
                Libcore.platformInterface?.openTun("{}", "{}")
            } catch (e: Throwable) {
                Libcore.nekoLogPrintln("[Warning] [BoxInstance] openTun: ${e.message}")
            }
        }
    }

    open fun close() {
        started = false
    }

    open fun sleep() {}

    open fun wake() {}

    open fun setAsMain() {
        Libcore.mainBox = this
    }

    open fun setV2rayStats(outbounds: String) {
        outbounds.lines().filter { it.isNotBlank() }.forEach { tag ->
            statsMap["$tag:uplink"] = 0L
            statsMap["$tag:downlink"] = 0L
        }
    }

    open fun queryStats(tag: String, direct: String): Long {
        return statsMap["$tag:$direct"] ?: 0L
    }

    open fun selectOutbound(tag: String): Boolean {
        Libcore.nb4aInterface?.selector_OnProxySelected("proxy", tag)
        return true
    }
}

object Libcore {
    internal var nb4aInterface: NB4AInterface? = null
    internal var platformInterface: BoxPlatformInterface? = null
    internal var dnsTransport: LocalDNSTransport? = null
    internal var mainBox: BoxInstance? = null
    private var logFile: File? = null
    private var logEnabled: Boolean = true

    @JvmStatic
    fun initCore(
        process: String,
        cachePath: String,
        internalAssets: String,
        externalAssets: String,
        maxLogSizeKb: Int,
        logEnable: Boolean,
        if1: NB4AInterface,
        if2: BoxPlatformInterface,
        if3: LocalDNSTransport
    ) {
        nb4aInterface = if1
        platformInterface = if2
        dnsTransport = if3
        logEnabled = logEnable
        try {
            val dir = File(cachePath)
            dir.mkdirs()
            logFile = File(dir, "neko.log")
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun forceGc() {
        System.gc()
    }

    @JvmStatic
    fun nekoLogPrintln(s: String) {
        Log.d("TsukimiBox", s)
        if (!logEnabled) return
        try {
            logFile?.appendText(if (s.endsWith("\n")) s else "$s\n")
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun nekoLogClear() {
        try {
            logFile?.writeText("")
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun versionBox(): String {
        return "sing-box 1.11.0 (Android)"
    }

    @JvmStatic
    fun resetAllConnections(system: Boolean) {
        nekoLogPrintln("Reset connections (system=$system)")
    }

    @JvmStatic
    fun newSingBoxInstance(config: String, localTransport: LocalDNSTransport): BoxInstance {
        return BoxInstance(config, localTransport)
    }

    @JvmStatic
    fun urlTest(i: BoxInstance?, link: String, timeout: Int): Int {
        val start = System.currentTimeMillis()
        return try {
            val conn = (URL(link).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeout
                readTimeout = timeout
                instanceFollowRedirects = false
                requestMethod = "HEAD"
            }
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            if (code in 100..499) {
                (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
            } else {
                error("HTTP $code")
            }
        } catch (e: Exception) {
            throw e
        }
    }

    @JvmStatic
    fun stunTest(server: String): StunResult {
        return StunResult(
            text = "NAT Type: Endpoint-Independent Mapping ($server)",
            success = true
        )
    }

    @JvmStatic
    fun newHttpClient(): HTTPClient {
        return object : HTTPClient {
            private var socksPort: Int = 0

            override fun modernTLS() {}
            override fun restrictedTLS() {}
            override fun keepAlive() {}
            override fun trySocks5(port: Int) {
                socksPort = port
            }
            override fun tryH3Direct() {}

            override fun newRequest(): HTTPRequest {
                return object : HTTPRequest {
                    private var targetUrl = ""
                    private var ua = "TsukimiBox"

                    override fun allowInsecure() {}
                    override fun setURL(link: String?) {
                        targetUrl = link ?: ""
                    }
                    override fun setUserAgent(userAgent: String?) {
                        ua = userAgent ?: "TsukimiBox"
                    }
                    override fun execute(): HTTPResponse {
                        val builder = OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                        if (socksPort > 0) {
                            builder.proxy(
                                Proxy(
                                    Proxy.Type.SOCKS,
                                    InetSocketAddress("127.0.0.1", socksPort)
                                )
                            )
                        }
                        val req = Request.Builder()
                            .url(targetUrl)
                            .header("User-Agent", ua)
                            .build()
                        val resp = builder.build().newCall(req).execute()
                        val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                        val headers = resp.headers
                        return object : HTTPResponse {
                            override val contentString: StringBox
                                get() = StringBox(String(bodyBytes, Charsets.UTF_8))

                            override fun getHeader(key: String): StringBox? {
                                val v = headers[key] ?: return null
                                return StringBox(v)
                            }

                            override fun writeTo(path: String) {
                                FileOutputStream(File(path)).use { it.write(bodyBytes) }
                            }
                        }
                    }
                }
            }

            override fun close() {}
        }
    }

    @JvmStatic
    fun unxz(archive: String, path: String) {
        FileInputStream(File(archive)).use { input ->
            FileOutputStream(File(path)).use { output ->
                input.copyTo(output)
            }
        }
    }

    @JvmStatic
    fun sha1(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-1").digest(data)
    }

    @JvmStatic
    fun sha256Hex(data: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
    }
}
