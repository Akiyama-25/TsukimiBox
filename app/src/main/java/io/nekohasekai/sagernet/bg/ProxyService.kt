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
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(
            this,
            profileName,
            if (DataStore.serviceMode == Key.MODE_ROOT) "service-root" else "service-proxy",
            true
        )

    override var wakeLock: PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:proxy")
            .apply { acquire() }
    }

    override suspend fun startProcesses() {
        if (DataStore.serviceMode == Key.MODE_ROOT) {
            RootRouteController.prepareRootEnvironment(DataStore.ipv6Mode != IPv6Mode.DISABLE)
        }
        super.startProcesses()
    }

    override fun killProcesses() {
        if (DataStore.serviceMode == Key.MODE_ROOT) {
            RootRouteController.cleanupAll()
        }
        super.killProcesses()
    }

    override fun onDestroy() {
        if (DataStore.serviceMode == Key.MODE_ROOT) {
            RootRouteController.cleanupAll()
        }
        super.onDestroy()
        data.binder.close()
    }

    override fun onBind(intent: Intent) = super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)
}
