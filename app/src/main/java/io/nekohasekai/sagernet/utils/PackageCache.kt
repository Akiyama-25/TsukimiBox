package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.plugin.Plugins
import java.util.concurrent.atomic.AtomicBoolean

object PackageCache {

    const val ROOT_PACKAGE_NAME = "root"
    const val ROOT_UID = 0

    lateinit var installedPackages: Map<String, PackageInfo>
    lateinit var installedPluginPackages: Map<String, PackageInfo>
    lateinit var installedApps: Map<String, ApplicationInfo>
    lateinit var packageMap: Map<String, Int>
    val uidMap = HashMap<Int, HashSet<String>>()
    val loaded = Mutex(true)
    var registerd = AtomicBoolean(false)

    // called from init (suspend)
    fun register() {
        if (registerd.getAndSet(true)) return
        reload()
        app.listenForPackageChanges(false) {
            reload()
            labelMap.clear()
        }
        loaded.unlock()
    }

    private fun createRootApplicationInfo(): ApplicationInfo {
        return ApplicationInfo().apply {
            packageName = ROOT_PACKAGE_NAME
            uid = ROOT_UID
            flags = ApplicationInfo.FLAG_SYSTEM
            nonLocalizedLabel = try {
                app.getString(R.string.root_superuser_label)
            } catch (_: Exception) {
                "Root (Superuser)"
            }
        }
    }

    private fun createRootPackageInfo(rootAppInfo: ApplicationInfo): PackageInfo {
        return PackageInfo().apply {
            packageName = ROOT_PACKAGE_NAME
            applicationInfo = rootAppInfo
            requestedPermissions = arrayOf(Manifest.permission.INTERNET)
        }
    }

    @SuppressLint("InlinedApi")
    fun reload() {
        val rawPackageInfo = app.packageManager.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    or PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_PROVIDERS
                    or PackageManager.GET_META_DATA
        )

        val rootAppInfo = createRootApplicationInfo()
        val rootPkgInfo = createRootPackageInfo(rootAppInfo)

        installedPackages = rawPackageInfo.filter {
            when (it.packageName) {
                "android" -> true
                else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
            }
        }.associateBy { it.packageName }.toMutableMap().apply {
            put(ROOT_PACKAGE_NAME, rootPkgInfo)
        }

        installedPluginPackages = rawPackageInfo.filter {
            Plugins.isExe(it)
        }.associateBy { it.packageName }

        val installed = app.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        installedApps = installed.associateBy { it.packageName }.toMutableMap().apply {
            put(ROOT_PACKAGE_NAME, rootAppInfo)
        }
        packageMap = installed.associate { it.packageName to it.uid }.toMutableMap().apply {
            put(ROOT_PACKAGE_NAME, ROOT_UID)
        }
        uidMap.clear()
        for (info in installed) {
            val uid = info.uid
            uidMap.getOrPut(uid) { HashSet() }.add(info.packageName)
        }
        uidMap.getOrPut(ROOT_UID) { HashSet() }.add(ROOT_PACKAGE_NAME)
    }

    operator fun get(uid: Int) = uidMap[uid]
    operator fun get(packageName: String) = packageMap[packageName]

    fun awaitLoadSync() {
        if (::packageMap.isInitialized) {
            return
        }
        if (!registerd.get()) {
            register()
            return
        }
        runBlocking {
            loaded.withLock {
                // just await
            }
        }
    }

    private val labelMap = mutableMapOf<String, String>()
    fun loadLabel(packageName: String): String {
        if (packageName == ROOT_PACKAGE_NAME || packageName == "uid_0") {
            return try {
                app.getString(R.string.root_superuser_label)
            } catch (_: Exception) {
                "Root (Superuser)"
            }
        }
        var label = labelMap[packageName]
        if (label != null) return label
        val info = try {
            if (::installedApps.isInitialized) installedApps[packageName] else null
        } catch (_: Exception) {
            null
        } ?: try {
            app.packageManager.getApplicationInfo(packageName, 0)
        } catch (_: Exception) {
            null
        } ?: return packageName
        label = info.loadLabel(app.packageManager).toString()
        labelMap[packageName] = label
        return label
    }

    fun loadIcon(
        pm: PackageManager = app.packageManager,
        packageName: String,
        appInfo: ApplicationInfo? = null
    ): Drawable {
        if (packageName == ROOT_PACKAGE_NAME || packageName == "uid_0" || appInfo?.uid == ROOT_UID) {
            return AppCompatResources.getDrawable(app, R.drawable.ic_baseline_security_24)
                ?: pm.defaultActivityIcon
        }
        return try {
            appInfo?.loadIcon(pm) ?: pm.getApplicationIcon(packageName)
        } catch (_: Exception) {
            pm.defaultActivityIcon
        }
    }

}