package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object RootRouteController {

    // Place UID=0 takeover rule at 12500 (after Android netd oif/explicit rules at 10000-11000
    // and right alongside Android's own VPN rules at 12000) so netd physical DNS is never hijacked.
    private const val RULE_PREF = 12500
    private const val LEGACY_RULE_PREF = 9990
    const val ROOT_PID_FILE_NAME = "root_singbox.pid"

    private val active = AtomicBoolean(false)
    private val rootModeActive = AtomicBoolean(false)

    fun getSuBinary(): String {
        val candidates = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su"
        )
        for (candidate in candidates) {
            val file = File(candidate)
            if (file.exists() && file.canExecute()) {
                return candidate
            }
        }
        return "su"
    }

    fun runSuScript(script: String, timeoutSec: Long = 8L): Boolean {
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
                writer.write(script)
                writer.newLine()
                writer.write("exit 0\n")
                writer.flush()
            }

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Logs.w("RootRouteController: su ($suBin) timed out after ${timeoutSec}s; output=${outputBuf.toString().trim()}")
                return false
            }
            try {
                readerThread.join(500)
            } catch (_: InterruptedException) {
            }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                true
            } else {
                Logs.w("RootRouteController: su ($suBin) exited with code $exitCode; output=${outputBuf.toString().trim()}")
                false
            }
        } catch (e: Exception) {
            Logs.w("RootRouteController: su ($suBin) execution failed: ${e.message}")
            false
        }
    }

    private fun defaultPidFilePath(): String? = try {
        File(File(SagerNet.application.cacheDir, "tmpcfg"), ROOT_PID_FILE_NAME).absolutePath
    } catch (_: Throwable) {
        null
    }

    private fun buildKillRootCoreCommands(pidFilePath: String? = null): String = buildString {
        // Fast, bounded O(1) termination using PID file + single pidof fallback (zero /proc loops)
        if (!pidFilePath.isNullOrBlank()) {
            append("if [ -f '$pidFilePath' ]; then\n")
            append("  pf_pid=\$(cat '$pidFilePath' 2>/dev/null)\n")
            append("  if [ -n \"\$pf_pid\" ] && kill -0 \"\$pf_pid\" 2>/dev/null; then\n")
            append("    kill -TERM \"\$pf_pid\" 2>/dev/null || true\n")
            append("    for i in 1 2 3 4; do kill -0 \"\$pf_pid\" 2>/dev/null || break; sleep 0.1; done\n")
            append("    kill -KILL \"\$pf_pid\" 2>/dev/null || true\n")
            append("  fi\n")
            append("  rm -f '$pidFilePath' 2>/dev/null || true\n")
            append("fi\n")
        }
        append("stray_pids=\$(pidof libsingbox.so 2>/dev/null || true)\n")
        append("if [ -n \"\$stray_pids\" ]; then\n")
        append("  kill -TERM \$stray_pids 2>/dev/null || true\n")
        append("  sleep 0.2\n")
        append("  kill -KILL \$stray_pids 2>/dev/null || true\n")
        append("fi\n")
    }

    private fun buildCleanupCommands(includeTunAndCore: Boolean, pidFilePath: String? = null): String = buildString {
        if (includeTunAndCore) {
            append(buildKillRootCoreCommands(pidFilePath))
        }

        // Remove UID=0 VPN takeover rules (current and legacy pref)
        append("ip -4 rule del pref $RULE_PREF 2>/dev/null || true\n")
        append("ip -6 rule del pref $RULE_PREF 2>/dev/null || true\n")
        append("ip -4 rule del pref $LEGACY_RULE_PREF 2>/dev/null || true\n")
        append("ip -6 rule del pref $LEGACY_RULE_PREF 2>/dev/null || true\n")

        // Remove only actually-present sing-tun auto_route ip rules (pref 9000..9015 and 32768)
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

        if (includeTunAndCore) {
            append("ip link set dev tun0 down 2>/dev/null || true\n")
            append("ip link delete dev tun0 2>/dev/null || true\n")
        }

        append("ip route flush cache 2>/dev/null || true\n")
    }

    suspend fun prepareRootEnvironment(ipv6Enabled: Boolean, mtu: Int = 1400): Boolean =
        withContext(Dispatchers.IO) {
            rootModeActive.set(true)
            val script = buildString {
                // Clean up any stale root core / rules / tun0 from a previous run
                append(buildCleanupCommands(includeTunAndCore = true, pidFilePath = defaultPidFilePath()))
                // Ensure TUN character device exists (Android uses /dev/tun or /dev/net/tun)
                append("if [ ! -c /dev/tun ] && [ ! -c /dev/net/tun ]; then ")
                append("mkdir -p /dev/net 2>/dev/null || true; ")
                append("mknod /dev/net/tun c 10 200 2>/dev/null || true; ")
                append("chmod 666 /dev/net/tun 2>/dev/null || true; ")
                append("fi\n")
                append("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true\n")
                if (ipv6Enabled) {
                    append("echo 1 > /proc/sys/net/ipv6/conf/all/forwarding 2>/dev/null || true\n")
                }
                append("ip route flush cache 2>/dev/null || true\n")
            }
            val ok = runSuScript(script)
            if (ok) {
                Logs.d("RootRouteController: prepared Root environment successfully (mtu=$mtu)")
            } else {
                Logs.w("RootRouteController: failed to prepare Root environment (root permission denied?)")
            }
            ok
        }

    fun enableRootRouting(ipv6Enabled: Boolean, privateVlan4Client: String) {
        runOnDefaultDispatcher {
            val script = buildString {
                append("TUN_DEV=\$(ip -4 -o addr show 2>/dev/null | awk '/")
                append(privateVlan4Client.replace(".", "\\."))
                append("/{print \$2; exit}'); ")
                append("[ -z \"\$TUN_DEV\" ] && TUN_DEV=\"tun0\"; ")
                append("TUN_TABLE=\$(ip -4 rule show 2>/dev/null | awk '/uidrange/ && !/lookup main/ && !/lookup local_network/ && !/unreachable/{for(i=1;i<=NF;i++) if(\$i==\"lookup\") {print \$(i+1); exit}}'); ")
                append("[ -z \"\$TUN_TABLE\" ] && TUN_TABLE=\$(ip -4 route show table all dev \"\$TUN_DEV\" 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(\$i==\"table\" && \$(i+1)!=\"local\" && \$(i+1)!=\"main\" && \$(i+1)~\"^[0-9]+\$\") {print \$(i+1); exit}}'); ")
                append("ip -4 rule del pref $LEGACY_RULE_PREF 2>/dev/null || true; ")
                append("ip -6 rule del pref $LEGACY_RULE_PREF 2>/dev/null || true; ")
                append("ip -4 rule del pref $RULE_PREF 2>/dev/null || true; ")
                append("ip -6 rule del pref $RULE_PREF 2>/dev/null || true; ")
                append("if [ -n \"\$TUN_TABLE\" ] && echo \"\$TUN_TABLE\" | grep -Eq '^[0-9]+$'; then ")
                // Mask 0x30000 excludes BOTH explicitlySelected (0x10000) and protectedFromVpn (0x20000)
                // so Android netd physical-network DNS lookups never loop into the VPN tunnel.
                append("ip -4 rule add from all fwmark 0x0/0x30000 uidrange 0-0 lookup \"\$TUN_TABLE\" pref $RULE_PREF 2>/dev/null || true; ")
                if (ipv6Enabled) {
                    append("ip -6 rule add from all fwmark 0x0/0x30000 uidrange 0-0 lookup \"\$TUN_TABLE\" pref $RULE_PREF 2>/dev/null || true; ")
                }
                append("ip route flush cache 2>/dev/null || true; ")
                append("fi")
            }
            if (runSuScript(script)) {
                active.set(true)
                Logs.d("RootRouteController: injected UID=0 policy routing rule (pref $RULE_PREF)")
            }
        }
    }

    fun disableRootRouting() {
        if (!active.getAndSet(false)) return
        runOnDefaultDispatcher {
            val cleanupScript = "ip -4 rule del pref $RULE_PREF 2>/dev/null || true; " +
                    "ip -6 rule del pref $RULE_PREF 2>/dev/null || true; " +
                    "ip -4 rule del pref $LEGACY_RULE_PREF 2>/dev/null || true; " +
                    "ip -6 rule del pref $LEGACY_RULE_PREF 2>/dev/null || true; " +
                    "ip route flush cache 2>/dev/null || true"
            runSuScript(cleanupScript)
            Logs.d("RootRouteController: cleaned up UID=0 policy routing rule (pref $RULE_PREF)")
        }
    }

    fun stopRootCoreAndCleanup(sync: Boolean = false) {
        active.set(false)
        rootModeActive.set(false)
        val pidFile = defaultPidFilePath()
        val action = {
            runSuScript(buildCleanupCommands(includeTunAndCore = true, pidFilePath = pidFile))
            Logs.d("RootRouteController: stopped root core and performed complete route/iptables cleanup")
        }
        if (sync) {
            action()
        } else {
            runOnDefaultDispatcher { action() }
        }
    }

    fun cleanupAll() {
        stopRootCoreAndCleanup(sync = false)
    }
}
