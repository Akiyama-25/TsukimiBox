package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import java.util.concurrent.atomic.AtomicInteger

class SagerConnection(
    private var connectionId: Int,
    private var listenForDeath: Boolean = false
) {

    companion object {
        val serviceClass
            get() = when (DataStore.serviceMode) {
                Key.MODE_PROXY -> ProxyService::class
                Key.MODE_VPN -> VpnService::class
                Key.MODE_ROOT -> ProxyService::class
                else -> throw UnknownError()
            }.java

        const val CONNECTION_ID_SHORTCUT = 0
        const val CONNECTION_ID_TILE = 1
        const val CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND = 2
        const val CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND = 3
        const val CONNECTION_ID_RESTART_BG = 4

        var restartingApp = false
    }

    interface Callback {
        // smaller ISagerNetServiceCallback

        fun cbSpeedUpdate(stats: SpeedDisplayData) {}
        fun cbTrafficUpdate(data: TrafficData) {}
        fun cbSelectorUpdate(id: Long) {}

        fun stateChanged(state: BaseService.State, profileName: String?, msg: String?)

        fun missingPlugin(profileName: String, pluginName: String) {}

        fun onServiceConnected(service: ISagerNetService)

        /**
         * Different from Android framework, this method will be called even when you call `detachService`.
         */
        fun onServiceDisconnected() {}
        fun onBinderDied() {}
    }

    @Volatile
    private var connectionActive = false
    @Volatile
    private var bindingPending = false
    private var callbackRegistered = false
    @Volatile
    private var callback: Callback? = null
    private val connectionGen = AtomicInteger(0)
    private var activeServiceConnection: ServiceConnection? = null
    private var activeDeathRecipient: IBinder.DeathRecipient? = null
    private var boundServiceClass: Class<*>? = null

    private val serviceCallback = object : ISagerNetServiceCallback.Stub() {

        override fun stateChanged(state: Int, profileName: String?, msg: String?) {
            if (state < 0) return // skip private
            val s = BaseService.State.values()[state]
            val gen = connectionGen.get()
            val cb = callback ?: return
            runOnMainDispatcher {
                // Ignore stale callbacks posted by a previous service after disconnect/rebind
                if (!connectionActive || gen != connectionGen.get()) return@runOnMainDispatcher
                DataStore.serviceState = s
                cb.stateChanged(s, profileName, msg)
            }
        }

        override fun cbSpeedUpdate(stats: SpeedDisplayData) {
            val gen = connectionGen.get()
            val cb = callback ?: return
            runOnMainDispatcher {
                if (!connectionActive || gen != connectionGen.get()) return@runOnMainDispatcher
                cb.cbSpeedUpdate(stats)
            }
        }

        override fun cbTrafficUpdate(stats: TrafficData) {
            val gen = connectionGen.get()
            val cb = callback ?: return
            runOnMainDispatcher {
                if (!connectionActive || gen != connectionGen.get()) return@runOnMainDispatcher
                cb.cbTrafficUpdate(stats)
            }
        }

        override fun cbSelectorUpdate(id: Long) {
            val gen = connectionGen.get()
            val cb = callback ?: return
            runOnMainDispatcher {
                if (!connectionActive || gen != connectionGen.get()) return@runOnMainDispatcher
                cb.cbSelectorUpdate(id)
            }
        }

        override fun missingPlugin(profileName: String, pluginName: String) {
            val gen = connectionGen.get()
            val cb = callback ?: return
            runOnMainDispatcher {
                if (!connectionActive || gen != connectionGen.get()) return@runOnMainDispatcher
                cb.missingPlugin(profileName, pluginName)
            }
        }

    }

    private var binder: IBinder? = null

    var service: ISagerNetService? = null

    fun updateConnectionId(id: Int): Boolean {
        connectionId = id
        val s = service ?: return false
        return try {
            s.registerCallback(serviceCallback, id)
            callbackRegistered = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Verifies that the active binding points to the current service class and is still alive,
     * refreshing the callback registration and returning the live service state.
     * If the binding is missing, dead, or points to a stale service class, reconnects cleanly.
     */
    fun syncOrReconnect(context: Context, callback: Callback, id: Int): BaseService.State? {
        connectionId = id
        val s = service
        val b = binder
        val expectedClass = serviceClass
        if (connectionActive && boundServiceClass == expectedClass) {
            if (bindingPending) {
                return null
            }
            if (s != null && b != null && b.isBinderAlive) {
                try {
                    s.registerCallback(serviceCallback, id)
                    callbackRegistered = true
                    return BaseService.State.values()[s.state]
                } catch (_: Exception) {
                    // Fall through to reconnect
                }
            }
        }
        disconnect(context)
        connect(context, callback)
        return null
    }

    private fun handleServiceConnected(gen: Int, binder: IBinder) {
        if (!connectionActive || gen != connectionGen.get()) return
        bindingPending = false
        this.binder = binder
        val service = ISagerNetService.Stub.asInterface(binder)!!
        this.service = service
        try {
            if (listenForDeath) {
                val recipient = IBinder.DeathRecipient {
                    handleBinderDied(gen)
                }
                activeDeathRecipient = recipient
                binder.linkToDeath(recipient, 0)
            }
            service.registerCallback(serviceCallback, connectionId)
            callbackRegistered = true
        } catch (e: RemoteException) {
            e.printStackTrace()
            handleBinderDied(gen)
            return
        }
        callback?.onServiceConnected(service)
    }

    private fun handleServiceDisconnected(gen: Int) {
        if (!connectionActive || gen != connectionGen.get()) return
        bindingPending = false
        unregisterCallback()
        callback?.onServiceDisconnected()
        service = null
        binder = null
        activeDeathRecipient = null
    }

    private fun handleBinderDied(gen: Int) {
        if (gen != connectionGen.get()) return
        bindingPending = false
        service = null
        callbackRegistered = false
        if (!restartingApp) {
            val cb = callback ?: return
            runOnMainDispatcher {
                if (gen != connectionGen.get()) return@runOnMainDispatcher
                cb.onBinderDied()
            }
        }
    }

    private fun unregisterCallback() {
        val service = service
        if (service != null && callbackRegistered) try {
            service.unregisterCallback(serviceCallback)
        } catch (_: RemoteException) {
        }
        callbackRegistered = false
    }

    fun connect(context: Context, callback: Callback?) {
        if (connectionActive) return
        connectionActive = true
        bindingPending = true
        val gen = connectionGen.incrementAndGet()
        check(this.callback == null)
        this.callback = callback
        val targetClass = serviceClass
        boundServiceClass = targetClass
        val sc = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                handleServiceConnected(gen, service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handleServiceDisconnected(gen)
            }

            override fun onBindingDied(name: ComponentName?) {
                handleBinderDied(gen)
            }
        }
        activeServiceConnection = sc
        val intent = Intent(context, targetClass).setAction(Action.SERVICE)
        val bound = try {
            context.bindService(intent, sc, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        if (!bound) {
            connectionActive = false
            bindingPending = false
            activeServiceConnection = null
            boundServiceClass = null
            this.callback = null
        }
    }

    fun disconnect(context: Context) {
        connectionGen.incrementAndGet()
        bindingPending = false
        unregisterCallback()
        val sc = activeServiceConnection
        activeServiceConnection = null
        if (connectionActive && sc != null) try {
            context.unbindService(sc)
        } catch (_: IllegalArgumentException) {
        }   // ignore
        connectionActive = false
        val recipient = activeDeathRecipient
        activeDeathRecipient = null
        if (listenForDeath && recipient != null) try {
            binder?.unlinkToDeath(recipient, 0)
        } catch (_: NoSuchElementException) {
        }
        boundServiceClass = null
        binder = null
        service = null
        callback = null
    }
}
