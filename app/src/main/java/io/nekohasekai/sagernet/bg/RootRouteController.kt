package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

object RootRouteController {

    // Place UID=0 takeover rule at 12500 (after AOSP VPN_OUTPUT_TO_LOCAL=12000 and before
    // SECURE_VPN=13000) with iif lo and fwmark 0x0/0x30000 so forwarding/hotspot/AVF packets
    // and netd explicitlySelected/protectedFromVpn sockets are never hijacked.
    internal const val RULE_PREF = 12500
    internal const val LEGACY_RULE_PREF = 9990
    const val ROOT_PID_FILE_NAME = "root_singbox.pid"

    private const val SU_PATH_EXPORT =
        "export PATH=\"/system/bin:/system/xbin:/product/bin:/vendor/bin:/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk:\$PATH\""

    private const val EXIT_ERR_NO_TUN_DEV = 2
    private const val EXIT_ERR_NO_TUN_TABLE = 3

    private val RETRY_DELAYS_MS = longArrayOf(50L, 100L, 200L, 400L, 800L)

    data class SuScriptResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false
    ) {
        val success: Boolean get() = !timedOut && exitCode == 0
    }

    private data class EnableConfig(
        val ipv6Mode: Int,
        val privateVlan4Client: String,
        val privateVlan6Client: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val generation = AtomicLong(0L)
    private val active = AtomicBoolean(false)
    private val rootModeActive = AtomicBoolean(false)

    @Volatile
    private var lastEnableConfig: EnableConfig? = null

    fun isAnyRootStateActive(): Boolean = active.get() || rootModeActive.get()

    fun getSuBinary(): String {
        // KernelSU Next intercepts /system/bin/su via su_compat and does not create /data/adb/ksu/bin/su.
        val candidates = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/product/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su"
        )
        for (candidate in candidates) {
            try {
                val file = File(candidate)
                if (file.exists() && file.canExecute()) {
                    return candidate
                }
            } catch (_: Throwable) {
            }
        }
        return "su"
    }

    fun runSuScriptWithResult(script: String, timeoutSec: Long = 8L): SuScriptResult {
        val suBin = getSuBinary()
        return try {
            val process = ProcessBuilder(suBin)
                .redirectErrorStream(true)
                .start()

            // Always drain stdout/stderr concurrently to prevent pipe buffer deadlock
            val outputBuf = StringBuilder()
            val readerThread = thread(start = true, isDaemon = true, name = "su-stdout") {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(1024)
                        var n: Int
                        while (reader.read(buf).also { n = it } != -1) {
                            if (outputBuf.length < 4096) {
                                outputBuf.append(buf, 0, n)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            process.outputStream.bufferedWriter().use { writer ->
                writer.write(SU_PATH_EXPORT)
                writer.newLine()
                writer.write(script)
                writer.newLine()
                writer.flush()
            }

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                val out = outputBuf.toString().trim()
                Logs.w("RootRouteController: su ($suBin) timed out after ${timeoutSec}s; output=$out")
                return SuScriptResult(-1, out, timedOut = true)
            }
            try {
                readerThread.join(500)
            } catch (_: InterruptedException) {
            }
            val exitCode = process.exitValue()
            val out = outputBuf.toString().trim()
            if (exitCode != 0) {
                Logs.w("RootRouteController: su ($suBin) exited with code $exitCode; output=$out")
            }
            SuScriptResult(exitCode, out)
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            Logs.w("RootRouteController: su ($suBin) execution failed: $msg")
            SuScriptResult(-1, msg)
        }
    }

    fun runSuScript(script: String, timeoutSec: Long = 8L): Boolean {
        return runSuScriptWithResult(script, timeoutSec).success
    }

    private fun defaultPidFilePath(): String? = try {
        File(File(SagerNet.application.cacheDir, "tmpcfg"), ROOT_PID_FILE_NAME).absolutePath
    } catch (_: Throwable) {
        null
    }

    internal fun buildKillRootCoreCommands(pidFilePath: String? = null): String = buildString {
        if (!pidFilePath.isNullOrBlank()) {
            append("if [ -f '$pidFilePath' ]; then\n")
            append("  pf_pid=\$(cat '$pidFilePath' 2>/dev/null)\n")
            append("  if [ -n \"\$pf_pid\" ] && kill -0 \"\$pf_pid\" 2>/dev/null; then\n")
            append("    kill -TERM \"\$pf_pid\" 2>/dev/null || true\n")
            append("    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do kill -0 \"\$pf_pid\" 2>/dev/null || break; sleep 0.1; done\n")
            append("    kill -KILL \"\$pf_pid\" 2>/dev/null || true\n")
            append("  fi\n")
            append("  rm -f '$pidFilePath' 2>/dev/null || true\n")
            append("fi\n")
        }
        append("stray_pids=\$(pidof libsingbox.so 2>/dev/null || true)\n")
        append("if [ -n \"\$stray_pids\" ]; then\n")
        append("  kill -TERM \$stray_pids 2>/dev/null || true\n")
        append("  for i in 1 2 3 4 5; do stray_pids=\$(pidof libsingbox.so 2>/dev/null || true); [ -z \"\$stray_pids\" ] && break; sleep 0.1; done\n")
        append("  [ -n \"\$stray_pids\" ] && kill -KILL \$stray_pids 2>/dev/null || true\n")
        append("fi\n")
    }

    internal fun buildDisableScript(): String = buildString {
        append("while ip -4 rule del pref $RULE_PREF 2>/dev/null; do :; done\n")
        append("while ip -6 rule del pref $RULE_PREF 2>/dev/null; do :; done\n")
        append("while ip -4 rule del pref $LEGACY_RULE_PREF 2>/dev/null; do :; done\n")
        append("while ip -6 rule del pref $LEGACY_RULE_PREF 2>/dev/null; do :; done\n")
        append("ip -4 route flush cache 2>/dev/null || true\n")
        append("ip -6 route flush cache 2>/dev/null || true\n")
    }

    internal fun buildCleanupCommands(includeTunAndCore: Boolean, pidFilePath: String? = null): String = buildString {
        if (includeTunAndCore) {
            append(buildKillRootCoreCommands(pidFilePath))
        }

        append(buildDisableScript())

        if (includeTunAndCore) {
            // Remove sing-tun auto_route ip rules (pref 9000..9015 and fallback 32768)
            append("for p in \$(ip -4 rule show 2>/dev/null | awk -F: '\$1>=9000 && \$1<=9015 || \$1==32768 {print \$1}'); do ip -4 rule del pref \"\$p\" 2>/dev/null || true; done\n")
            append("for p in \$(ip -6 rule show 2>/dev/null | awk -F: '\$1>=9000 && \$1<=9015 || \$1==32768 {print \$1}'); do ip -6 rule del pref \"\$p\" 2>/dev/null || true; done\n")
            append("ip -4 route flush table 2022 2>/dev/null || true\n")
            append("ip -6 route flush table 2022 2>/dev/null || true\n")

            // nat table cleanup (sing-tun auto_redirect on android)
            append("iptables -t nat -D OUTPUT -j sing-box-output 2>/dev/null || true\n")
            append("iptables -t nat -F sing-box-output 2>/dev/null || true\n")
            append("iptables -t nat -X sing-box-output 2>/dev/null || true\n")
            append("ip6tables -t nat -D OUTPUT -j sing-box-output 2>/dev/null || true\n")
            append("ip6tables -t nat -F sing-box-output 2>/dev/null || true\n")
            append("ip6tables -t nat -X sing-box-output 2>/dev/null || true\n")

            append("ip link set dev tun0 down 2>/dev/null || true\n")
            append("ip link delete dev tun0 2>/dev/null || true\n")
        }

        append("ip -4 route flush cache 2>/dev/null || true\n")
        append("ip -6 route flush cache 2>/dev/null || true\n")
    }

    internal fun buildEnableScript(
        ipv6Mode: Int,
        privateVlan4Client: String,
        privateVlan6Client: String = VpnService.PRIVATE_VLAN6_CLIENT
    ): String = buildString {
        val findTunCmd = if (ipv6Mode == IPv6Mode.ONLY) {
            "ip -6 -o addr show 2>/dev/null | awk -v target='$privateVlan6Client' '{iface=\$2; split(iface,a,\"@\"); if(a[1] ~ /^tun/) {split(\$4,ip,\"/\"); if(ip[1]==target) {print a[1]; exit}}}'"
        } else {
            "ip -4 -o addr show 2>/dev/null | awk -v target='$privateVlan4Client' '{iface=\$2; split(iface,a,\"@\"); if(a[1] ~ /^tun/) {split(\$4,ip,\"/\"); if(ip[1]==target) {print a[1]; exit}}}'"
        }
        val routeAwk =
            "awk '{tbl=\"\"; for(i=1;i<=NF;i++) if(\$i==\"table\") {tbl=\$(i+1); break} if(tbl!=\"\" && tbl!=\"local\" && tbl!=\"main\" && tbl!=\"default\" && tbl!=\"unspec\" && tbl!=\"local_network\" && tbl!~\"^dummy\" && tbl!~\"_local\$\"){if(\$1==\"default\"){print tbl; found=1; exit} if(fb==\"\") fb=tbl}} END{if(!found && fb!=\"\") print fb}'"

        append("TUN_DEV=\$($findTunCmd)\n")
        append("if [ -z \"\$TUN_DEV\" ]; then echo 'ERR:NO_TUN_DEV'; exit $EXIT_ERR_NO_TUN_DEV; fi\n")

        if (ipv6Mode == IPv6Mode.ONLY) {
            append("TUN_TABLE=\$(ip -6 route show table all dev \"\$TUN_DEV\" 2>/dev/null | $routeAwk)\n")
        } else {
            append("TUN_TABLE=\$(ip -4 route show table all dev \"\$TUN_DEV\" 2>/dev/null | $routeAwk)\n")
            if (ipv6Mode != IPv6Mode.DISABLE) {
                append("[ -z \"\$TUN_TABLE\" ] && TUN_TABLE=\$(ip -6 route show table all dev \"\$TUN_DEV\" 2>/dev/null | $routeAwk)\n")
            }
        }

        append("if [ -z \"\$TUN_TABLE\" ] || echo \"\$TUN_TABLE\" | grep -Eq '^(local|main|default|unspec|local_network|dummy.*|.*_local)\$'; then\n")
        append("  echo \"ERR:NO_TUN_TABLE dev=\$TUN_DEV table=\$TUN_TABLE\"\n")
        append("  exit $EXIT_ERR_NO_TUN_TABLE\n")
        append("fi\n")

        append("while ip -4 rule del pref $LEGACY_RULE_PREF 2>/dev/null; do :; done\n")
        append("while ip -6 rule del pref $LEGACY_RULE_PREF 2>/dev/null; do :; done\n")
        append("while ip -4 rule del pref $RULE_PREF 2>/dev/null; do :; done\n")
        append("while ip -6 rule del pref $RULE_PREF 2>/dev/null; do :; done\n")

        if (ipv6Mode != IPv6Mode.ONLY) {
            append("if ! ip -4 rule add from all iif lo fwmark 0x0/0x30000 uidrange 0-0 lookup \"\$TUN_TABLE\" pref $RULE_PREF; then\n")
            append("  echo \"ERR:IP4_RULE_ADD table=\$TUN_TABLE\"\n")
            append("  exit 4\n")
            append("fi\n")
        }

        when (ipv6Mode) {
            IPv6Mode.DISABLE -> {
                // Prevent UID=0 from escaping via physical IPv6 GUA (RFC 6724) when VPN disables IPv6
                append("ip -6 rule add from all iif lo fwmark 0x0/0x30000 uidrange 0-0 unreachable pref $RULE_PREF 2>/dev/null || true\n")
            }

            IPv6Mode.ONLY -> {
                append("if ! ip -6 rule add from all iif lo fwmark 0x0/0x30000 uidrange 0-0 lookup \"\$TUN_TABLE\" pref $RULE_PREF; then\n")
                append("  echo \"ERR:IP6_RULE_ADD table=\$TUN_TABLE\"\n")
                append("  exit 5\n")
                append("fi\n")
            }

            else -> {
                append("ip -6 rule add from all iif lo fwmark 0x0/0x30000 uidrange 0-0 lookup \"\$TUN_TABLE\" pref $RULE_PREF 2>/dev/null || echo \"WARN:IP6_RULE_ADD table=\$TUN_TABLE\"\n")
            }
        }

        append("ip -4 route flush cache 2>/dev/null || true\n")
        append("ip -6 route flush cache 2>/dev/null || true\n")
        append("echo \"OK:dev=\$TUN_DEV table=\$TUN_TABLE ipv6=$ipv6Mode\"\n")
    }

    suspend fun prepareRootEnvironment(ipv6Enabled: Boolean, mtu: Int = 1400): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                rootModeActive.set(true)
                val script = buildString {
                    append(buildCleanupCommands(includeTunAndCore = true, pidFilePath = defaultPidFilePath()))
                    append("if [ ! -c /dev/tun ] && [ ! -c /dev/net/tun ]; then\n")
                    append("  mkdir -p /dev/net 2>/dev/null || true\n")
                    append("  mknod /dev/net/tun c 10 200 2>/dev/null || true\n")
                    append("  chmod 666 /dev/net/tun 2>/dev/null || true\n")
                    append("fi\n")
                    append("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true\n")
                    if (ipv6Enabled) {
                        append("echo 1 > /proc/sys/net/ipv6/conf/all/forwarding 2>/dev/null || true\n")
                    }
                    append("ip -4 route flush cache 2>/dev/null || true\n")
                    append("ip -6 route flush cache 2>/dev/null || true\n")
                }
                val res = runSuScriptWithResult(script)
                if (res.success) {
                    Logs.i("RootRouteController: prepared Root environment (mtu=$mtu)")
                } else {
                    rootModeActive.set(false)
                    Logs.w("RootRouteController: failed to prepare Root environment (exit=${res.exitCode}, out=${res.output})")
                }
                res.success
            }
        }

    fun enableRootRouting(
        ipv6Mode: Int,
        privateVlan4Client: String,
        privateVlan6Client: String = VpnService.PRIVATE_VLAN6_CLIENT
    ) {
        val myGen = generation.incrementAndGet()
        val cfg = EnableConfig(ipv6Mode, privateVlan4Client, privateVlan6Client)
        lastEnableConfig = cfg
        scope.launch {
            applyEnableWithRetry(myGen, cfg)
        }
    }

    fun enableRootRouting(ipv6Enabled: Boolean, privateVlan4Client: String) {
        enableRootRouting(
            ipv6Mode = if (ipv6Enabled) IPv6Mode.ENABLE else IPv6Mode.DISABLE,
            privateVlan4Client = privateVlan4Client,
            privateVlan6Client = VpnService.PRIVATE_VLAN6_CLIENT
        )
    }

    fun resyncIfEnabled() {
        val cfg = lastEnableConfig ?: return
        if (!active.get()) return
        val myGen = generation.incrementAndGet()
        scope.launch {
            applyEnableWithRetry(myGen, cfg)
        }
    }

    private suspend fun applyEnableWithRetry(myGen: Long, cfg: EnableConfig) {
        val script = buildEnableScript(cfg.ipv6Mode, cfg.privateVlan4Client, cfg.privateVlan6Client)
        val totalAttempts = RETRY_DELAYS_MS.size + 1
        for (attempt in 0 until totalAttempts) {
            if (generation.get() != myGen) return
            val result = mutex.withLock {
                if (generation.get() != myGen) return@withLock null
                val res = runSuScriptWithResult(script)
                if (res.success) {
                    active.set(true)
                    Logs.i("RootRouteController: enabled UID=0 policy routing (${res.output})")
                }
                res
            } ?: return

            if (result.success) return

            val retryable = !result.timedOut &&
                (result.exitCode == EXIT_ERR_NO_TUN_DEV || result.exitCode == EXIT_ERR_NO_TUN_TABLE)
            if (!retryable || attempt >= RETRY_DELAYS_MS.size) {
                Logs.w("RootRouteController: enableRootRouting failed after ${attempt + 1} attempt(s): exit=${result.exitCode}, out=${result.output}")
                return
            }
            delay(RETRY_DELAYS_MS[attempt])
        }
    }

    fun disableRootRouting(sync: Boolean = false) {
        generation.incrementAndGet()
        lastEnableConfig = null
        val hadActive = active.getAndSet(false)
        val action: suspend () -> Unit = {
            mutex.withLock {
                active.set(false)
                val res = runSuScriptWithResult(buildDisableScript())
                if (res.success) {
                    if (hadActive) {
                        Logs.i("RootRouteController: cleaned up UID=0 policy routing rule (pref $RULE_PREF)")
                    }
                } else {
                    Logs.w("RootRouteController: cleanup failed (exit=${res.exitCode}, out=${res.output})")
                }
            }
        }
        if (sync) {
            runBlocking { action() }
        } else {
            scope.launch { action() }
        }
    }

    fun stopRootCoreAndCleanup(sync: Boolean = false) {
        generation.incrementAndGet()
        lastEnableConfig = null
        active.set(false)
        rootModeActive.set(false)
        val pidFile = defaultPidFilePath()
        val action: suspend () -> Unit = {
            mutex.withLock {
                active.set(false)
                rootModeActive.set(false)
                val res = runSuScriptWithResult(buildCleanupCommands(includeTunAndCore = true, pidFilePath = pidFile))
                if (res.success) {
                    Logs.i("RootRouteController: stopped root core and cleaned up routes/iptables")
                } else {
                    Logs.w("RootRouteController: root core cleanup exited with ${res.exitCode}: ${res.output}")
                }
            }
        }
        if (sync) {
            runBlocking { action() }
        } else {
            scope.launch { action() }
        }
    }

    fun cleanupSyncBounded(timeoutMs: Long = 2000L) {
        if (!isAnyRootStateActive()) return
        val wasRootMode = rootModeActive.get()
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                if (wasRootMode) {
                    stopRootCoreAndCleanup(sync = true)
                } else {
                    disableRootRouting(sync = true)
                }
            }
        }
    }

    fun cleanupAll() {
        stopRootCoreAndCleanup(sync = true)
    }
}
