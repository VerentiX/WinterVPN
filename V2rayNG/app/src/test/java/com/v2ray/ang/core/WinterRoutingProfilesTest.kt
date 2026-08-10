package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WinterRoutingProfilesTest {
    @Test
    fun parsesBase64HappDualProfiles() {
        val payload = """
            {
              "whitelistMinPriority": 5,
              "default": {
                "Name": "RoscomVPN",
                "RouteOrder": "block-proxy-direct",
                "DomainStrategy": "IPIfNonMatch",
                "RemoteDNSDomain": "https://8.8.8.8/dns-query",
                "DomesticDNSDomain": "https://77.88.8.8/dns-query",
                "DnsHosts": {"lkfl2.nalog.ru": "213.24.64.175"},
                "DirectSites": ["geosite:private", "geosite:category-ru"],
                "DirectIp": ["geoip:private"],
                "ProxySites": ["geosite:youtube"],
                "ProxyIp": [],
                "BlockSites": ["geosite:category-ads"],
                "BlockIp": []
              },
              "whitelist": {
                "Name": "RoscomVPN Whitelist",
                "RouteOrder": "block-proxy-direct",
                "DomainStrategy": "IPIfNonMatch",
                "RemoteDNSDomain": "https://8.8.8.8/dns-query",
                "DomesticDNSDomain": "https://77.88.8.8/dns-query",
                "DnsHosts": {},
                "DirectSites": ["geosite:private", "geosite:whitelist"],
                "DirectIp": ["geoip:whitelist"],
                "ProxySites": ["domain:sberbank.ru"],
                "ProxyIp": [],
                "BlockSites": [],
                "BlockIp": []
              }
            }
        """.trimIndent()
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        val bundle = WinterRoutingProfiles.parseHeader("base64:$encoded")
        assertNotNull(bundle)
        assertEquals(5, bundle!!.whitelistMinPriority)
        assertEquals("RoscomVPN", bundle.defaultProfile.name)
        assertTrue(bundle.defaultProfile.directSites.any { it.contains("category-ru") })
        assertEquals("RoscomVPN Whitelist", bundle.whitelistProfile.name)
        assertTrue(bundle.whitelistProfile.proxySites.contains("domain:sberbank.ru"))
    }
}
