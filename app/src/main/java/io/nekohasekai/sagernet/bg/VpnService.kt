package io.nekohasekai.sagernet.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.Subnet
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(),
    BaseService.Interface {

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val PRIVATE_VLAN4_PREFIX = 30
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val FAKEDNS_VLAN6_CLIENT = "fc00::"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"

    }

    var conn: ParcelFileDescriptor? = null

    private var metered = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        DataStore.vpnService = this
        super.startProcesses() // launch proxy instance
    }

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
            .apply { acquire() }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun killProcesses() {
        RootRouteController.disableRootRouting()
        conn?.close()
        conn = null
        super.killProcesses()
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (prepare(this) != null) {
                runCatching {
                    startActivity(
                        Intent(
                            this, VpnRequestActivity::class.java
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    Logs.w("Failed to start VpnRequestActivity from background: ${it.message}")
                }
            } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        // Satisfy startForeground() before stopping (using SPECIAL_USE when OP_ACTIVATE_VPN may be absent)
        data.notification = ServiceNotification(
            this,
            "",
            "service-vpn",
            foregroundServiceType = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else null
        )
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException : NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    inner class EmptyWhitelistException : IllegalStateException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() =
            "Per-app VPN whitelist has no valid allowed apps (would create a global self-loop)"
    }

    @Suppress("DEPRECATION")
    private fun queryAllNetworkPackages(): List<PackageInfo> {
        val pm = packageManager
        val pkgFlags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_PERMISSIONS
        val packages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(pkgFlags.toLong()))
            } else {
                pm.getInstalledPackages(pkgFlags)
            }
        } catch (e: Exception) {
            Logs.w("Failed to get installed packages with permissions: ${e.message}")
            emptyList()
        }
        return packages.filter {
            when (it.packageName) {
                packageName -> false
                "android" -> true
                else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
            }
        }
    }

    fun startVpn(tunOptionsJson: String, tunPlatformOptionsJson: String): Int {
        // address & route & MTU ...... use NB4A GUI config
        val builder = Builder().setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(DataStore.effectiveTunMtu)
        val ipv6Mode = DataStore.ipv6Mode

        // address & DNS (kept strictly consistent with ConfigBuilder tun-in addresses)
        if (ipv6Mode != IPv6Mode.ONLY) {
            builder.addAddress(PRIVATE_VLAN4_CLIENT, PRIVATE_VLAN4_PREFIX)
            builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        }
        if (ipv6Mode != IPv6Mode.DISABLE) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
            builder.addDnsServer(PRIVATE_VLAN6_ROUTER)
        }

        // route
        if (DataStore.bypassLan) {
            if (ipv6Mode != IPv6Mode.ONLY) {
                resources.getStringArray(R.array.bypass_private_route).forEach {
                    val subnet = Subnet.fromString(it)!!
                    builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
                }
                builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
                builder.addRoute(FAKEDNS_VLAN4_CLIENT, 15)
            }
            // https://issuetracker.google.com/issues/149636790
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("2000::", 3)
                builder.addRoute(PRIVATE_VLAN6_ROUTER, 128)
                builder.addRoute(FAKEDNS_VLAN6_CLIENT, 18)
            }
        } else {
            if (ipv6Mode != IPv6Mode.ONLY) {
                builder.addRoute("0.0.0.0", 0)
            }
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("::", 0)
            }
        }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

        // app route & Root (UID=0) takeover logic
        val packageName = packageName
        val proxyApps = DataStore.proxyApps
        val forceRootTakeover = DataStore.rootTrafficTakeover
        var bypass = DataStore.bypass
        val hasFakeTcp = data.proxy?.config?.trafficMap?.values?.any { chain ->
            chain.any { it.hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP }
        } == true
        if (forceRootTakeover && hasFakeTcp) {
            Logs.w("Hysteria FakeTCP plugin runs as root; suppressing rootTrafficTakeover to prevent self-loop")
        }
        val effectiveRootTakeover = forceRootTakeover && !hasFakeTcp
        val needBypassRootUid = !effectiveRootTakeover && hasFakeTcp

        if (proxyApps || needBypassRootUid) {
            val individual = mutableSetOf<String>()
            val allNetworkPackages by lazy { queryAllNetworkPackages() }
            val allApps by lazy { allNetworkPackages.map { it.packageName } }

            if (proxyApps) {
                individual.addAll(
                    DataStore.individual.split('\n').map { it.trim() }.filter { it.isNotBlank() }
                )
                individual.remove(PackageCache.ROOT_PACKAGE_NAME)

                // Protected Whitelist -> Blacklist inversion when UID=0 takeover is enabled:
                // keeps UID=0 in Android's native SECURE_VPN range (fixing netd DNS & UID=0 lookup)
                // while guarding sharedUserId peers so allowed UIDs are never inadvertently excluded.
                if (!bypass && effectiveRootTakeover) {
                    val pkgList = allNetworkPackages
                    if (pkgList.isNotEmpty()) {
                        val allowedUids = pkgList.mapNotNull { pkg ->
                            if (pkg.packageName in individual) pkg.applicationInfo?.uid else null
                        }.toHashSet()
                        val disallowed = pkgList.filter { pkg ->
                            val uid = pkg.applicationInfo?.uid ?: -1
                            pkg.packageName !in individual && (uid < 0 || uid !in allowedUids)
                        }.map { it.packageName }
                        individual.clear()
                        individual.addAll(disallowed)
                        bypass = true
                        Logs.d("Inverted VPN whitelist to blacklist (${individual.size} disallowed packages) for UID=0 takeover")
                    } else {
                        Logs.w("Skipping whitelist->blacklist inversion because package list is empty; relying on RootRouteController")
                    }
                } else if (bypass && needBypassRootUid) {
                    val allowed = allApps.toMutableList()
                    allowed.removeAll(individual)
                    individual.clear()
                    individual.addAll(allowed)
                    bypass = false
                }
            } else {
                individual.addAll(allApps)
                bypass = false
            }

            individual.remove(PackageCache.ROOT_PACKAGE_NAME)
            if (bypass) {
                // In Blacklist (Disallow) mode, always exclude TsukimiBox itself from VPN tunnel.
                individual.add(packageName)
            } else {
                // In Whitelist (Allow) mode, ensure TsukimiBox itself is never allowed.
                individual.remove(packageName)
                if (individual.isEmpty()) {
                    throw EmptyWhitelistException()
                }
            }

            val added = mutableListOf<String>()
            individual.forEach {
                try {
                    if (bypass) {
                        builder.addDisallowedApplication(it)
                    } else {
                        builder.addAllowedApplication(it)
                    }
                    added.add(it)
                } catch (ex: PackageManager.NameNotFoundException) {
                    Logs.w(ex)
                }
            }

            if (!bypass && added.isEmpty()) {
                throw EmptyWhitelistException()
            }

            if (bypass) {
                Logs.d("Add bypass: ${added.joinToString(", ")}")
            } else {
                Logs.d("Add allow: ${added.joinToString(", ")}")
            }
        } else {
            // Global VPN mode: always exclude TsukimiBox itself so UI / direct DoH never loop into TUN.
            try {
                builder.addDisallowedApplication(packageName)
                Logs.d("Add self bypass (global mode): $packageName")
            } catch (ex: PackageManager.NameNotFoundException) {
                Logs.w(ex)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DataStore.appendHttpProxy) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.mixedPort))
        }

        metered = DataStore.meteredNetwork
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)
        conn = builder.establish() ?: throw NullConnectionException()

        if (effectiveRootTakeover) {
            RootRouteController.enableRootRouting(
                ipv6Mode = ipv6Mode,
                privateVlan4Client = PRIVATE_VLAN4_CLIENT,
                privateVlan6Client = PRIVATE_VLAN6_CLIENT
            )
        } else {
            RootRouteController.disableRootRouting()
        }

        return conn!!.fd
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val network = SagerNet.underlyingNetwork
            if (network != null) {
                val networks = arrayOf(network)
                builder?.setUnderlyingNetworks(networks) ?: setUnderlyingNetworks(networks)
            } else if (builder == null) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        RootRouteController.cleanupSyncBounded(1500L)
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}
