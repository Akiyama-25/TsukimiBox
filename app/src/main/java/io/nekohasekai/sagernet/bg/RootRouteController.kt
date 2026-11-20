package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object RootRouteController {

    // Place UID=0 takeover rule at 12500 (after Android netd oif/explicit rules at 10000-11000
    // and right alongside Android's own VPN rules at 12000) so netd physical DNS is never hijacked.
    private const val RULE_PREF = 12500
    private const val LEGACY_RULE_PREF = 9990
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

    fun runSuScript(script: String): Boolean {
        val suBin = getSuBinary()
        return try {
            val process = ProcessBuilder(suBin)
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(script)
                writer.newLine()
                writer.write("exit\n")
                writer.flush()
            }
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                true
            } else {
                Logs.w("RootRouteController: su ($suBin) exited with code $exitCode")
                false
            }
        } catch (e: Exception) {
            try {
                val process = ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start()
                val exitCode = process.waitFor()
                exitCode == 0
            } catch (e2: Exception) {
                Logs.w("RootRouteController: su execution failed: ${e2.message}")
                false
            }
        }
    }

    private fun buildCleanupCommands(includeTunAndCore: Boolean): String = buildString {
        if (includeTunAndCore) {
            // Gracefully stop any running root sing-box core so it tears down its own routes/rules
            append("pkill -TERM -f libsingbox.so 2>/dev/null || killall -TERM libsingbox.so 2>/dev/null || true\n")
            append("for i in 1 2 3 4 5; do pgrep -f libsingbox.so >/dev/null 2>&1 || break; sleep 0.15; done\n")
            append("pkill -KILL -f libsingbox.so 2>/dev/null || killall -KILL libsingbox.so 2>/dev/null || true\n")
        }

        // Remove UID=0 VPN takeover rules (current and legacy pref)
        append("ip -4 rule del pref $RULE_PREF 2>/dev/null || true\n")
        append("ip -6 rule del pref $RULE_PREF 2>/dev/null || true\n")
        append("ip -4 rule del pref $LEGACY_RULE_PREF 2>/dev/null || true\n")
        append("ip -6 rule del pref $LEGACY_RULE_PREF 2>/dev/null || true\n")

        // Remove sing-tun auto_route ip rules (default pref range 9000..9015 and fallback 32768)
        append("for p in 9000 9001 9002 9003 9004 9005 9006 9007 9008 9009 9010 9011 9012 9013 9014 9015 32768; do ")
        append("while ip -4 rule del pref \$p 2>/dev/null; do :; done; ")
        append("while ip -6 rule del pref \$p 2>/dev/null; do :; done; ")
        append("done\n")
        append("ip -4 route flush table 2022 2>/dev/null || true\n")
        append("ip -6 route flush table 2022 2>/dev/null || true\n")

        // nftables sing-box tables (if any)
        append("nft delete table inet sing-box 2>/dev/null || true\n")
        append("nft delete table ip sing-box 2>/dev/null || true\n")
        append("nft delete table ip6 sing-box 2>/dev/null || true\n")

        // iptables & ip6tables mangle chains
        append("iptables -t mangle -D PREROUTING -j SING_BOX 2>/dev/null || true\n")
        append("iptables -t mangle -D OUTPUT -j SING_BOX_LOCAL 2>/dev/null || true\n")
        append("iptables -t mangle -F SING_BOX 2>/dev/null || true\n")
        append("iptables -t mangle -X SING_BOX 2>/dev/null || true\n")
        append("iptables -t mangle -F SING_BOX_LOCAL 2>/dev/null || true\n")
        append("iptables -t mangle -X SING_BOX_LOCAL 2>/dev/null || true\n")

        append("ip6tables -t mangle -D PREROUTING -j SING_BOX 2>/dev/null || true\n")
        append("ip6tables -t mangle -D OUTPUT -j SING_BOX_LOCAL 2>/dev/null || true\n")
        append("ip6tables -t mangle -F SING_BOX 2>/dev/null || true\n")
        append("ip6tables -t mangle -X SING_BOX 2>/dev/null || true\n")
        append("ip6tables -t mangle -F SING_BOX_LOCAL 2>/dev/null || true\n")
        append("ip6tables -t mangle -X SING_BOX_LOCAL 2>/dev/null || true\n")

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
                append(buildCleanupCommands(includeTunAndCore = true))
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
        val action = {
            runSuScript(buildCleanupCommands(includeTunAndCore = true))
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
