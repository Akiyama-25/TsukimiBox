package moe.matsuri.nb4a

import io.nekohasekai.sagernet.IPv6Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SingBoxOptionsUtilTest {

    @Test
    fun resolveServerDomainStrategy_respectsAllIpv6Modes() {
        assertEquals("ipv4_only", SingBoxOptionsUtil.resolveServerDomainStrategy("auto", IPv6Mode.DISABLE))
        assertEquals("prefer_ipv4", SingBoxOptionsUtil.resolveServerDomainStrategy("auto", IPv6Mode.ENABLE))
        assertEquals("prefer_ipv6", SingBoxOptionsUtil.resolveServerDomainStrategy("auto", IPv6Mode.PREFER))
        assertEquals("ipv6_only", SingBoxOptionsUtil.resolveServerDomainStrategy("auto", IPv6Mode.ONLY))
        assertEquals("ipv4_only", SingBoxOptionsUtil.resolveServerDomainStrategy("ipv4_only", IPv6Mode.ONLY))
    }

    @Test
    fun makeSingBoxRule_preservesRuleSetWhenAddingDomainsAndIpsTogether() {
        val rule = SingBoxOptions.Rule_DefaultOptions()
        rule.makeSingBoxRule(listOf("geosite:google", "full:example.com"), isIP = false)
        rule.makeSingBoxRule(listOf("geoip:us", "1.1.1.1/32"), isIP = true)

        assertNotNull(rule.rule_set)
        assertEquals(listOf("geosite:google", "geoip:us"), rule.rule_set)
        assertEquals(listOf("example.com"), rule.domain)
        assertEquals(listOf("1.1.1.1/32"), rule.ip_cidr)
    }
}
