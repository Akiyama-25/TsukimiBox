package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import java.util.concurrent.atomic.AtomicBoolean

object RootRouteController {

    private const val RULE_PREF = 9990
    private val active = AtomicBoolean(false)
    private val rootModeActive = AtomicBoolean(false)

    fun prepareRootEnvironment(ipv6Enabled: Boolean) {
        rootModeActive.set(true)
        runOnDefaultDispatcher {
            try {
                val script = buildString {
                    append("mkdir -p /dev/net 2>/dev/null || true; ")
                    append("[ ! -c /dev/net/tun ] && mknod /dev/net/tun c 10 200 2>/dev/null || true; ")
                    append("chmod 666 /dev/net/tun 2>/dev/null || true; ")
                    append("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true; ")
                    if (ipv6Enabled) {
                        append("echo 1 > /proc/sys/net/ipv6/conf/all/forwarding 2>/dev/null || true; ")
                    }
                    append("ip -4 rule del pref $RULE_PREF 2>/dev/null || true; ")
                    append("ip -6 rule del pref $RULE_PREF 2>/dev/null || true; ")
                    append("ip route flush cache 2>/dev/null || true")
                }
                val process = ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start()
                val exitCode = process.waitFor()
                Logs.d("RootRouteController: prepared Root environment (code=$exitCode)")
            } catch (e: Exception) {
                Logs.w("RootRouteController: failed to prepare Root environment: ${e.message}")
            }
        }
    }

    fun enableRootRouting(ipv6Enabled: Boolean, privateVlan4Client: String) {
        runOnDefaultDispatcher {
            try {
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

                val process = ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    active.set(true)
                    Logs.d("RootRouteController: injected UID=0 policy routing rule (pref $RULE_PREF)")
                } else {
                    Logs.w("RootRouteController: su command exited with code $exitCode")
                }
            } catch (e: Exception) {
                Logs.w("RootRouteController: failed to inject UID=0 policy routing rule: ${e.message}")
            }
        }
    }

    fun disableRootRouting() {
        if (!active.getAndSet(false)) return
        runOnDefaultDispatcher {
            try {
                val cleanupScript = "ip -4 rule del pref $RULE_PREF 2>/dev/null || true; " +
                        "ip -6 rule del pref $RULE_PREF 2>/dev/null || true; " +
                        "ip route flush cache 2>/dev/null || true"
                val process = ProcessBuilder("su", "-c", cleanupScript)
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
                Logs.d("RootRouteController: cleaned up UID=0 policy routing rule (pref $RULE_PREF)")
            } catch (e: Exception) {
                Logs.w("RootRouteController: failed to clean up UID=0 policy routing rule: ${e.message}")
            }
        }
    }

    fun cleanupAll() {
        active.set(false)
        rootModeActive.set(false)
        runOnDefaultDispatcher {
            try {
                val cleanupScript = buildString {
                    // ip rules
                    append("ip -4 rule del pref $RULE_PREF 2>/dev/null || true; ")
                    append("ip -6 rule del pref $RULE_PREF 2>/dev/null || true; ")
                    append("ip -4 rule del pref 9000 2>/dev/null || true; ")
                    append("ip -6 rule del pref 9000 2>/dev/null || true; ")

                    // nftables sing-box tables
                    append("nft delete table inet sing-box 2>/dev/null || true; ")
                    append("nft delete table ip sing-box 2>/dev/null || true; ")
                    append("nft delete table ip6 sing-box 2>/dev/null || true; ")

                    // iptables & ip6tables chains
                    append("iptables -t mangle -D PREROUTING -j SING_BOX 2>/dev/null || true; ")
                    append("iptables -t mangle -D OUTPUT -j SING_BOX_LOCAL 2>/dev/null || true; ")
                    append("iptables -t mangle -F SING_BOX 2>/dev/null || true; ")
                    append("iptables -t mangle -X SING_BOX 2>/dev/null || true; ")
                    append("iptables -t mangle -F SING_BOX_LOCAL 2>/dev/null || true; ")
                    append("iptables -t mangle -X SING_BOX_LOCAL 2>/dev/null || true; ")

                    append("ip6tables -t mangle -D PREROUTING -j SING_BOX 2>/dev/null || true; ")
                    append("ip6tables -t mangle -D OUTPUT -j SING_BOX_LOCAL 2>/dev/null || true; ")
                    append("ip6tables -t mangle -F SING_BOX 2>/dev/null || true; ")
                    append("ip6tables -t mangle -X SING_BOX 2>/dev/null || true; ")
                    append("ip6tables -t mangle -F SING_BOX_LOCAL 2>/dev/null || true; ")
                    append("ip6tables -t mangle -X SING_BOX_LOCAL 2>/dev/null || true; ")

                    // tun0 interface
                    append("ip link set dev tun0 down 2>/dev/null || true; ")
                    append("ip link delete dev tun0 2>/dev/null || true; ")

                    // flush cache
                    append("ip route flush cache 2>/dev/null || true")
                }
                val process = ProcessBuilder("su", "-c", cleanupScript)
                    .redirectErrorStream(true)
                    .start()
                val exit = process.waitFor()
                Logs.d("RootRouteController: performed complete root and route cleanup (code=$exit)")
            } catch (e: Exception) {
                Logs.w("RootRouteController: failed to clean up root rules: ${e.message}")
            }
        }
    }
}
