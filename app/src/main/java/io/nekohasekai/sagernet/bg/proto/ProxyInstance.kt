package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.RootRouteController
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.JavaUtil
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    // Latch launch mode at construction time so switching serviceMode while running
    // still tears down root core cleanly in close().
    private val launchedInRootMode = DataStore.serviceMode == Key.MODE_ROOT

    override val isRootDaemon: Boolean
        get() = notTmp && launchedInRootMode

    @Volatile
    var activeMixedPort: Int = DataStore.mixedPort

    @Volatile
    var activeClashApiPort: Int = 0

    private fun isTcpPortAvailable(host: String, port: Int): Boolean {
        return try {
            ServerSocket(port, 1, InetAddress.getByName(host)).use { true }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isTcpPortListening(host: String, port: Int, timeoutMs: Int = 100): Boolean {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun prepareRootConfigText(): String {
        val bindHost = if (DataStore.allowAccess) "0.0.0.0" else LOCALHOST
        val preferredPort = DataStore.mixedPort
        activeMixedPort = preferredPort

        var available = isTcpPortAvailable(bindHost, preferredPort)
        if (!available) {
            // Brief wait in case a previous service/process is still releasing the port
            try {
                Thread.sleep(350)
            } catch (_: InterruptedException) {
            }
            available = isTcpPortAvailable(bindHost, preferredPort)
        }
        val needMixedPatch = !available
        val clashApiWanted = DataStore.enableClashAPI
        val clashApiBusy = clashApiWanted && !isTcpPortAvailable(LOCALHOST, 9090)
        activeClashApiPort = if (clashApiWanted && !clashApiBusy) 9090 else 0
        if (!needMixedPatch && !clashApiBusy) {
            return config.config
        }

        val fallbackPort = if (needMixedPatch) mkPort() else preferredPort
        activeMixedPort = fallbackPort
        if (needMixedPatch) {
            Logs.w("Root mode: mixedPort $preferredPort is already in use; falling back to $fallbackPort")
        }
        if (clashApiBusy) {
            Logs.w("Root mode: Clash API port 9090 is already in use; disabling clash_api for this session")
        }
        return try {
            val rootObj = JSONObject(config.config)
            if (needMixedPatch) {
                val inbounds = rootObj.optJSONArray("inbounds")
                if (inbounds != null) {
                    for (i in 0 until inbounds.length()) {
                        val inbound = inbounds.optJSONObject(i) ?: continue
                        if (inbound.optString("tag") == "mixed-in") {
                            inbound.put("listen_port", fallbackPort)
                        }
                    }
                }
            }
            if (clashApiBusy) {
                rootObj.optJSONObject("experimental")?.remove("clash_api")
            }
            rootObj.toString()
        } catch (e: Exception) {
            Logs.w("Failed to patch busy ports in root config: ${e.message}")
            config.config
        }
    }

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        //
        if (notTmp) Logs.d(config.config)
        if (notTmp && BuildConfig.DEBUG) Logs.d(JavaUtil.gson.toJson(config.trafficMap))
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
        pluginConfigs.forEach { (_, plugin) ->
            val (_, content) = plugin
            Logs.d(content)
        }
    }

    override suspend fun loadConfig() {
        if (isRootDaemon) {
            // Root AutoRedirect runs standalone libsingbox.so as UID 0 via su (no in-process libcore box)
            return
        }
        super.loadConfig()
    }

    override fun launch() {
        if (isRootDaemon) {
            super.launch() // starts any external plugins; skips in-process box.start()

            val singBoxBin = File(SagerNet.application.applicationInfo.nativeLibraryDir, "libsingbox.so")
            if (!singBoxBin.exists()) {
                throw IllegalStateException("Root core binary not found: ${singBoxBin.absolutePath}")
            }

            val cacheDir = File(SagerNet.application.cacheDir, "tmpcfg")
            cacheDir.mkdirs()
            val configFile = File(cacheDir, "root_singbox.json")
            configFile.writeText(prepareRootConfigText())
            cacheFiles.add(configFile)

            val pidFile = File(cacheDir, RootRouteController.ROOT_PID_FILE_NAME)
            runCatching { pidFile.delete() }
            val assetsDir = SagerNet.application.externalAssets.absolutePath
            val rootWorkDir = File(SagerNet.application.noBackupFilesDir, "root_core").apply {
                mkdirs()
            }.absolutePath
            val myUid = android.os.Process.myUid()
            val suBin = RootRouteController.getSuBinary()
            val shellCmd = buildString {
                append("echo \$\$ > '").append(pidFile.absolutePath).append("'; ")
                append("exec '").append(singBoxBin.absolutePath).append("' run ")
                append("-c '").append(configFile.absolutePath).append("' ")
                append("-a '").append(assetsDir).append("' ")
                append("-w '").append(rootWorkDir).append("' ")
                append("-uid ").append(myUid).append(' ')
                append("-gid ").append(myUid)
            }

            val ipv6Enabled = DataStore.ipv6Mode != IPv6Mode.DISABLE
            val tunMtu = DataStore.effectiveTunMtu
            Logs.i("Starting root sing-box AutoRedirect core via $suBin (mixedPort=$activeMixedPort)")
            processes.start(
                cmd = listOf(suBin, "-c", shellCmd),
                onBeforeRestartCallback = {
                    RootRouteController.prepareRootEnvironment(ipv6Enabled, tunMtu)
                }
            )
            return
        }

        box.setAsMain()
        super.launch() // start box
        runOnDefaultDispatcher {
            looper = service?.let { TrafficLooper(it.data, this) }
            looper?.start()
        }
    }

    suspend fun awaitRootReady(timeoutMs: Long = 2000L) {
        if (!isRootDaemon) return
        val pidFile = File(File(SagerNet.application.cacheDir, "tmpcfg"), RootRouteController.ROOT_PID_FILE_NAME)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                pidFile.exists() && isTcpPortListening(LOCALHOST, activeMixedPort, 80)
            }
            if (ready) return
            kotlinx.coroutines.delay(60)
        }
    }

    fun resetRootUpstreamConnections(): Boolean {
        val port = activeClashApiPort
        if (port <= 0) return false
        return try {
            val url = java.net.URL("http://$LOCALHOST:$port/connections")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 1500
                readTimeout = 1500
                val secret = DataStore.clashApiSecret
                if (secret.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $secret")
                }
            }
            try {
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            false
        }
    }

    override fun close() {
        if (isRootDaemon) {
            RootRouteController.stopRootCoreAndCleanup(sync = true)
        }
        super.close()
        runBlocking {
            looper?.stop()
            looper = null
        }
    }
}
