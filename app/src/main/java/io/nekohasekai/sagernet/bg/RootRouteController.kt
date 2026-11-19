package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object RootRouteController {

    private const val RULE_PREF = 9990
    private val active = AtomicBoolean(false)
    private val rootModeActive = AtomicBoolean(false)

    private fun getSuBinary(): String {
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

    private fun runSuScript(script: String): Boolean {
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

    suspend fun prepareRootEnvironment(ipv6Enabled: Boolean, mtu: Int = 1400): Boolean =
        withContext(Dispatchers.IO) {
            rootModeActive.set(true)
            val script = buildString {
                append("mkdir -p /dev/net 2>/dev/null || true\n")
                append("[ ! -c /dev/net/tun ] && mknod /dev/net/tun c 10 200 2>/dev/null || true\n")
                append("chmod 666 /dev/net/tun 2>/dev/null || true\n")
                append("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true\n")
                if (ipv6Enabled) {
                    append("echo 1 > /proc/sys/net/ipv6/conf/all/forwarding 2>/dev/null || true\n")
                }
                append("ip tuntap add dev tun0 mode tun 2>/dev/null || true\n")
                append("ip link set dev tun0 mtu $mtu 2>/dev/null || true\n")
                append("ip addr add ${VpnService.PRIVATE_VLAN4_CLIENT}/${VpnService.PRIVATE_VLAN4_PREFIX} dev tun0 2>/dev/null || true\n")
                if (ipv6Enabled) {
                    append("ip -6 addr add ${VpnService.FAKEDNS_VLAN6_CLIENT}1/18 dev tun0 2>/dev/null || true\n")
                }
                append("ip link set dev tun0 up 2>/dev/null || true\n")
                append("ip -4 rule del pref $RULE_PREF 2>/dev/null || true\n")
                append("ip -6 rule del pref $RULE_PREF 2>/dev/null || true\n")
                append("ip route flush cache 2>/dev/null || true\n")
            }
            val ok = runSuScript(script)
            if (ok) {
                Logs.d("RootRouteController: prepared Root environment successfully")
            } else {
                Logs.w("RootRouteController: failed to prepare Root environment")
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
                append("[ -z \"\$TUN_TABLE\" ] && TUN_TABLE=\$(ip -4 route show table all dev \"\$TUN_DEV\" 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(\$i==\"table\" && \$(i+1)!=\"local\" && \$(i+1)!=\"main\") {print \$(i+1); exit}}'); ")
                append("[ -z \"\$TUN_TABLE\" ] && TUN_TABLE=\"\$TUN_DEV\"; ")
                append("ip -4 rule del pref $RULE_PREF 2>/dev/null || true; ")
                append("ip -6 rule del pref $RULE_PREF 2>/dev/null || true; ")
                append("if ! ip -4 rule add from all fwmark 0x0/0x20000 uidrange 0-0 lookup \"\$TUN_TABLE\" pref $RULE_PREF 2>/dev/null; then ")
                append("ip -4 rule add from all uidrange 0-0 lookup \"\$TUN_TABLE\" pref $RULE_PREF 2>/dev/null || true; ")
                append("fi; ")
                if (ipv6Enabled) {
                    append("if ! ip -6 rule add from all fwmark 0x0/0x20000 uidrange 0-0 lookup \"\$TUN_TABLE\" pref $RULE_PREF 2>/dev/null; then ")
                    append("ip -6 rule add from all uidrange 0-0 lookup \"\$TUN_TABLE\" pref $RULE_PREF 2>/dev/null || true; ")
                    append("fi; ")
                }
                append("ip route flush cache 2>/dev/null || true")
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
                    "ip route flush cache 2>/dev/null || true"
            runSuScript(cleanupScript)
            Logs.d("RootRouteController: cleaned up UID=0 policy routing rule (pref $RULE_PREF)")
        }
    }

    fun cleanupAll() {
        active.set(false)
        rootModeActive.set(false)
        runOnDefaultDispatcher {
            val cleanupScript = buildString {
                // ip rules
                append("ip -4 rule del pref $RULE_PREF 2>/dev/null || true\n")
                append("ip -6 rule del pref $RULE_PREF 2>/dev/null || true\n")
                append("ip -4 rule del pref 9000 2>/dev/null || true\n")
                append("ip -6 rule del pref 9000 2>/dev/null || true\n")

                // nftables sing-box tables
                append("nft delete table inet sing-box 2>/dev/null || true\n")
                append("nft delete table ip sing-box 2>/dev/null || true\n")
                append("nft delete table ip6 sing-box 2>/dev/null || true\n")

                // iptables & ip6tables chains
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

                // tun0 interface
                append("ip link set dev tun0 down 2>/dev/null || true\n")
                append("ip link delete dev tun0 2>/dev/null || true\n")

                // flush cache
                append("ip route flush cache 2>/dev/null || true\n")
            }
            runSuScript(cleanupScript)
            Logs.d("RootRouteController: performed complete root and route cleanup")
        }
    }
}
