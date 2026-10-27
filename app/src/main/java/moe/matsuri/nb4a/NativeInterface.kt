package moe.matsuri.nb4a

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.NB4AInterface
import java.net.InetSocketAddress

class NativeInterface : BoxPlatformInterface, NB4AInterface {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        DataStore.vpnService?.protect(fd)
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        var uid = try {
            SagerNet.connectivity.getConnectionOwnerUid(
                ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
            )
        } catch (_: Throwable) {
            -1
        }
        if (uid < 0) {
            uid = resolveUidFromProcNet(ipProto, srcPort)
        }
        if (uid >= 0) {
            io.nekohasekai.sagernet.database.traffic.TrafficMonitorManager.recordUidConnection(uid)
        }
        return uid
    }

    private fun resolveUidFromProcNet(ipProto: Int, srcPort: Int): Int {
        if (srcPort <= 0) return -1
        val portHex = String.format("%04X", srcPort)
        val files = when (ipProto) {
            6 -> arrayOf("/proc/net/tcp", "/proc/net/tcp6")
            17 -> arrayOf("/proc/net/udp", "/proc/net/udp6")
            else -> return -1
        }
        for (path in files) {
            try {
                java.io.File(path).bufferedReader().useLines { lines ->
                    for (line in lines.drop(1)) {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size > 7) {
                            val localAddr = parts[1]
                            if (localAddr.endsWith(":$portHex", ignoreCase = true)) {
                                val parsedUid = parts[7].toIntOrNull()
                                if (parsedUid != null && parsedUid >= 0) {
                                    return parsedUid
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return -1
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid == PackageCache.ROOT_UID) {
            return PackageCache.ROOT_PACKAGE_NAME
        }
        if (uid < 0) {
            return "unknown"
        }
        if (uid <= 1000) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        val systemPkgs = try {
            app.packageManager.getPackagesForUid(uid)
        } catch (_: Throwable) {
            null
        }
        return systemPkgs?.firstOrNull() ?: "uid_$uid"
    }

    override fun uidByPackageName(packageName: String): Int {
        if (packageName == PackageCache.ROOT_PACKAGE_NAME) return PackageCache.ROOT_UID
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: -1
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                val id = data.proxy!!.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                // traffic & title
                data.proxy?.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

}
