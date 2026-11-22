package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.IPv6Mode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRouteControllerScriptTest {

    @Test
    fun enableScript_usesIifLoFwmark30000AndSymbolicTables() {
        val script = RootRouteController.buildEnableScript(
            ipv6Mode = IPv6Mode.PREFER,
            privateVlan4Client = "172.19.0.1",
            privateVlan6Client = "fdfe:dcba:9876::1"
        )
        assertTrue(script.contains("ip -4 rule add from all iif lo fwmark 0x0/0x30000 uidrange 0-0 lookup \"\$TUN_TABLE\" pref 12500"))
        assertTrue(script.contains("ip -6 rule add from all iif lo fwmark 0x0/0x30000 uidrange 0-0 lookup \"\$TUN_TABLE\" pref 12500"))
        assertTrue(script.contains("while ip -4 rule del pref 12500"))
        assertTrue(script.contains("while ip -6 rule del pref 9990"))
        assertTrue(script.contains("ip -6 route flush cache"))
        assertFalse("Must accept symbolic table names like tun0", script.contains("grep -Eq '^[0-9]+$'"))
        assertFalse("Must not force exit 0", script.endsWith("exit 0") || script.endsWith("exit 0\n"))
        assertTrue("Must exclude dummy tables", script.contains("dummy.*"))
        assertTrue("Must exclude *_local tables", script.contains(".*_local"))
    }

    @Test
    fun enableScript_ipv6DisableAddsUnreachableRule() {
        val script = RootRouteController.buildEnableScript(
            ipv6Mode = IPv6Mode.DISABLE,
            privateVlan4Client = "172.19.0.1",
            privateVlan6Client = "fdfe:dcba:9876::1"
        )
        assertTrue(script.contains("ip -4 rule add from all iif lo fwmark 0x0/0x30000 uidrange 0-0 lookup \"\$TUN_TABLE\" pref 12500"))
        assertTrue(script.contains("ip -6 rule add from all iif lo fwmark 0x0/0x30000 uidrange 0-0 unreachable pref 12500"))
    }

    @Test
    fun enableScript_ipv6OnlyProbesV6AndSkipsV4Rule() {
        val script = RootRouteController.buildEnableScript(
            ipv6Mode = IPv6Mode.ONLY,
            privateVlan4Client = "172.19.0.1",
            privateVlan6Client = "fdfe:dcba:9876::1"
        )
        assertTrue(script.contains("ip -6 -o addr show"))
        assertTrue(script.contains("ip -6 route show table all dev"))
        assertFalse(script.contains("ip -4 rule add"))
        assertTrue(script.contains("ip -6 rule add from all iif lo fwmark 0x0/0x30000 uidrange 0-0 lookup \"\$TUN_TABLE\" pref 12500"))
    }

    @Test
    fun disableAndCleanupScripts_loopDeletesAndFlushBothFamilies() {
        val disable = RootRouteController.buildDisableScript()
        assertTrue(disable.contains("while ip -4 rule del pref 12500"))
        assertTrue(disable.contains("while ip -6 rule del pref 12500"))
        assertTrue(disable.contains("ip -4 route flush cache"))
        assertTrue(disable.contains("ip -6 route flush cache"))

        val cleanup = RootRouteController.buildCleanupCommands(includeTunAndCore = true, pidFilePath = "/tmp/root.pid")
        assertTrue(cleanup.contains("1 2 3 4 5 6 7 8 9 10 11 12 13 14 15"))
        assertTrue(cleanup.contains("ip6tables -t nat -F sing-box-output"))
        assertTrue(cleanup.contains("ip link delete dev tun0"))
    }
}
