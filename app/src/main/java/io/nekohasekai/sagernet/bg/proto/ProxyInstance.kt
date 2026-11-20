package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.RootRouteController
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.JavaUtil
import java.io.File

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    override val isRootDaemon: Boolean
        get() = notTmp && DataStore.serviceMode == Key.MODE_ROOT

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
            configFile.writeText(config.config)
            cacheFiles.add(configFile)

            val assetsDir = SagerNet.application.externalAssets.absolutePath
            val workDir = SagerNet.application.noBackupFilesDir.absolutePath
            val suBin = RootRouteController.getSuBinary()
            val shellCmd = "exec '${singBoxBin.absolutePath}' run -c '${configFile.absolutePath}' -a '$assetsDir' -w '$workDir'"

            Logs.i("Starting root sing-box AutoRedirect core via $suBin")
            processes.start(listOf(suBin, "-c", shellCmd))
            return
        }

        box.setAsMain()
        super.launch() // start box
        runOnDefaultDispatcher {
            looper = service?.let { TrafficLooper(it.data, this) }
            looper?.start()
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
