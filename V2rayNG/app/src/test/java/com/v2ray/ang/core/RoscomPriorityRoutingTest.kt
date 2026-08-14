package com.v2ray.ang.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoscomPriorityRoutingTest {
    private val source = JsonParser.parseString(
        """
        {
          "dns": {
            "hosts": {"example.com": "1.2.3.4"},
            "servers": [
              {
                "address": "https://8.8.8.8/dns-query",
                "domains": ["geosite:old-proxy"]
              },
              {
                "address": "https://77.88.8.8/dns-query",
                "domains": ["geosite:old-direct"]
              },
              "https://8.8.8.8/dns-query"
            ]
          },
          "outbounds": [
            {"tag":"proxy","protocol":"loopback","settings":{"inboundTag":"auto-proxy-in"}},
            {"tag":"direct","protocol":"freedom","settings":{}},
            {"tag":"block","protocol":"blackhole","settings":{}},
            {"tag":"route-p0000-a","protocol":"vless","settings":{}},
            {"tag":"route-p0005-b","protocol":"vless","settings":{}},
            {"tag":"chain-s0001","protocol":"loopback","settings":{"inboundTag":"chain-in-s0001"}}
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [
              {
                "type":"field",
                "domain":["geosite:old-block"],
                "outboundTag":"block"
              },
              {
                "type":"field",
                "domain":["geosite:old-direct"],
                "outboundTag":"direct"
              },
              {"type":"field","network":"udp","port":"443","outboundTag":"block"},
              {"type":"field","inboundTag":["auto-proxy-in"],"balancerTag":"tier-s0000"},
              {"type":"field","inboundTag":["chain-in-s0001"],"balancerTag":"tier-s0001"},
              {"type":"field","network":"tcp,udp","outboundTag":"proxy"}
            ],
            "balancers": [
              {"tag":"tier-s0000","selector":["route-p0000-a"],"fallbackTag":"chain-s0001","strategy":{"type":"leastLoad"}},
              {"tag":"tier-s0001","selector":["route-p0005-b"],"fallbackTag":"route-p0000-a","strategy":{"type":"leastLoad"}}
            ]
          },
          "burstObservatory":{
            "subjectSelector":["route-"],
            "pingConfig":{
              "destination":"https://www.gstatic.com/generate_204",
              "interval":"20s"
            }
          }
        }
        """.trimIndent()
    ).asJsonObject

    @Test
    fun mapsPriorityBoundaryToRoutingMode() {
        assertEquals(RoscomPriorityRouting.Mode.FULL, RoscomPriorityRouting.modeForRouteTag("route-p0004-x"))
        assertEquals(RoscomPriorityRouting.Mode.WHITELIST, RoscomPriorityRouting.modeForRouteTag("route-p0005-x"))
        assertEquals(RoscomPriorityRouting.Mode.WHITELIST, RoscomPriorityRouting.modeForRouteTag("route-p0012-y"))
        assertEquals(
            RoscomPriorityRouting.Mode.FULL,
            RoscomPriorityRouting.profileModeForRouteTag("route-p0004-x"),
        )
        assertEquals(
            RoscomPriorityRouting.Mode.WHITELIST,
            RoscomPriorityRouting.profileModeForRouteTag("route-p0005-x"),
        )
    }

    @Test
    fun appliesFullProfileOnPrimaryRoutesAndKeepsBalancerRules() {
        val plan = PriorityFailoverConfig.detect(source)!!
        val runtime = PriorityFailoverConfig.activate(source, plan, activeIndex = 0)

        val rules = runtime.getAsJsonObject("routing").getAsJsonArray("rules")
        val domainRules = rules.mapNotNull { it.asJsonObject.takeIf { rule -> rule.has("domain") } }
        assertTrue(domainRules.any { rule ->
            rule.get("outboundTag").asString == "direct" &&
                rule.getAsJsonArray("domain").any { it.asString.contains("category-ru") }
        })
        assertTrue(domainRules.any { rule ->
            rule.get("outboundTag").asString == "proxy" &&
                rule.getAsJsonArray("domain").any { it.asString.contains("youtube") }
        })
        assertTrue(domainRules.any { rule ->
            rule.get("outboundTag").asString == "block" &&
                rule.getAsJsonArray("domain").any { it.asString.contains("category-ads") }
        })
        assertFalse(domainRules.any { rule ->
            rule.getAsJsonArray("domain").any { it.asString == "geosite:old-direct" }
        })
        assertTrue(
            rules.any {
                it.asJsonObject.stringArray("inboundTag").contains("auto-proxy-in")
            },
        )
        assertEquals(
            "IPIfNonMatch",
            runtime.getAsJsonObject("routing").get("domainStrategy").asString,
        )
        assertEquals(
            "213.24.64.175",
            runtime.getAsJsonObject("dns").getAsJsonObject("hosts").get("lkfl2.nalog.ru").asString,
        )
        assertEquals(
            listOf("route-p0000-a"),
            runtime.getAsJsonObject("routing")
                .getAsJsonArray("balancers")[0].asJsonObject
                .getAsJsonArray("selector").map { it.asString },
        )
        assertEquals(
            "random",
            runtime.getAsJsonObject("routing")
                .getAsJsonArray("balancers")[0].asJsonObject
                .getAsJsonObject("strategy").get("type").asString,
        )
        val inboundIndex = rules.indexOfFirst { element ->
            element.asJsonObject.stringArray("inboundTag").contains("auto-proxy-in")
        }
        val metaQuicIndex = rules.indexOfFirst { element ->
            val rule = element.asJsonObject
            rule.stringOrNull("network") == "udp" &&
                rule.stringOrNull("port") == "443" &&
                rule.stringOrNull("outboundTag") == "proxy" &&
                rule.has("ip") &&
                rule.getAsJsonArray("ip").any { it.asString == "geoip:facebook" }
        }
        val quicBlockIndex = rules.indexOfFirst { element ->
            val rule = element.asJsonObject
            rule.stringOrNull("network") == "udp" &&
                rule.stringOrNull("port") == "443" &&
                rule.stringOrNull("outboundTag") == "block"
        }
        val youtubeIndex = rules.indexOfFirst { element ->
            val rule = element.asJsonObject
            rule.has("domain") &&
                rule.getAsJsonArray("domain").any { it.asString.contains("youtube") }
        }
        assertTrue(inboundIndex >= 0)
        assertTrue(metaQuicIndex >= 0)
        assertTrue(quicBlockIndex >= 0)
        assertTrue(youtubeIndex >= 0)
        assertTrue(inboundIndex < metaQuicIndex)
        assertTrue(metaQuicIndex < quicBlockIndex)
        assertTrue(quicBlockIndex < youtubeIndex)
    }

    @Test
    fun appliesWhitelistProfileFromP5WithoutResettingSelectedRoute() {
        val plan = PriorityFailoverConfig.detect(source)!!
        val runtime = PriorityFailoverConfig.activate(source, plan, activeIndex = 1)

        val rules = runtime.getAsJsonObject("routing").getAsJsonArray("rules")
        val domainRules = rules.mapNotNull { it.asJsonObject.takeIf { rule -> rule.has("domain") } }
        assertTrue(domainRules.any { rule ->
            rule.get("outboundTag").asString == "direct" &&
                rule.getAsJsonArray("domain").any { it.asString.contains("whitelist") } &&
                rule.getAsJsonArray("domain").none { it.asString.contains("category-ru") }
        })
        assertTrue(domainRules.any { rule ->
            rule.get("outboundTag").asString == "proxy" &&
                rule.getAsJsonArray("domain").any { it.asString == "domain:sberbank.ru" }
        })
        assertFalse(domainRules.any { rule ->
            rule.get("outboundTag").asString == "block"
        })
        assertEquals(
            listOf("route-p0005-b"),
            runtime.getAsJsonObject("routing")
                .getAsJsonArray("balancers")[0].asJsonObject
                .getAsJsonArray("selector").map { it.asString },
        )
        val remoteDns = runtime.getAsJsonObject("dns").getAsJsonArray("servers")[0].asJsonObject
        assertTrue(remoteDns.getAsJsonArray("domains").any { it.asString == "domain:tbank.ru" })
    }

    private fun com.google.gson.JsonObject.stringArray(name: String): List<String> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            it.takeIf { element -> element.isJsonPrimitive && element.asJsonPrimitive.isString }?.asString
        }.orEmpty()

    private fun com.google.gson.JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
