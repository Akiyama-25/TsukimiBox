package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore

class ProxyService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag: String get() = "SagerNetProxyService"

    @Volatile
    private var startedInRootMode = false

    override fun createNotification(profileName: String): ServiceNotification {
        val channel = if (startedInRootMode || DataStore.serviceMode == Key.MODE_ROOT) {
            "service-root"
        } else {
            "service-proxy"
        }
        return ServiceNotification(this, profileName, channel, true)
    }

    override var wakeLock: PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:proxy")
            .apply { acquire() }
    }

    override suspend fun startProcesses() {
        val rootMode = data.proxy?.isRootDaemon ?: (DataStore.serviceMode == Key.MODE_ROOT)
        startedInRootMode = rootMode
        if (rootMode) {
            val ok = RootRouteController.prepareRootEnvironment(
                DataStore.ipv6Mode != IPv6Mode.DISABLE,
                DataStore.effectiveTunMtu
            )
            if (!ok) {
                startedInRootMode = false
                throw IllegalStateException("Root permission (su) is required for Root AutoRedirect mode")
            }
        }
        super.startProcesses()
        if (rootMode) {
            data.proxy?.awaitRootReady()
        }
    }

    override fun killProcesses() {
        val wasRootMode = startedInRootMode || (data.proxy?.isRootDaemon == true)
        super.killProcesses()
        if (wasRootMode) {
            RootRouteController.stopRootCoreAndCleanup(sync = false)
            startedInRootMode = false
        }
    }

    override fun onDestroy() {
        val wasRootMode = startedInRootMode || RootRouteController.isAnyRootStateActive()
        if (wasRootMode) {
            RootRouteController.cleanupSyncBounded(1500L)
            startedInRootMode = false
        }
        super.onDestroy()
        data.binder.close()
    }

    override fun onBind(intent: Intent) = super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            data.notification = ServiceNotification(this, "", "service-proxy", true)
            stopRunner()
            return START_NOT_STICKY
        }
        startedInRootMode = DataStore.serviceMode == Key.MODE_ROOT
        return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
    }
}
