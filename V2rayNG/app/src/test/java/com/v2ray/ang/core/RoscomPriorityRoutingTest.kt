package com.v2ray.ang.core

import com.google.gson.JsonArray
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
          },
          "inbounds": [
            {"tag":"mixed","port":10808,"protocol":"mixed"}
          ]
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
        val youtube = domainRules.first { rule ->
            rule.get("outboundTag").asString == "proxy" &&
                rule.getAsJsonArray("domain").any { it.asString.contains("youtube") }
        }
        assertTrue(youtube.stringArray("inboundTag").contains("mixed"))
        assertFalse(youtube.stringArray("inboundTag").contains(RoscomPriorityRouting.INBOUND_WHITELIST))
        val sber = domainRules.first { rule ->
            rule.get("outboundTag").asString == RoscomPriorityRouting.OUTBOUND_WHITELIST_PROXY &&
                rule.getAsJsonArray("domain").any { it.asString == "domain:sberbank.ru" }
        }
        assertEquals(listOf(RoscomPriorityRouting.INBOUND_WHITELIST), sber.stringArray("inboundTag"))
        assertEquals(RoscomPriorityRouting.OUTBOUND_WHITELIST_PROXY, sber.get("outboundTag").asString)
        val whitelistBalancer = runtime.getAsJsonObject("routing")
            .getAsJsonArray("balancers")
            .map { it.asJsonObject }
            .first { it.get("tag").asString == RoscomPriorityRouting.BALANCER_WHITELIST }
        assertEquals(
            listOf("route-p0005-b"),
            whitelistBalancer.getAsJsonArray("selector").map { it.asString },
        )
        assertTrue(
            runtime.getAsJsonArray("outbounds").any {
                it.asJsonObject.get("tag").asString == RoscomPriorityRouting.OUTBOUND_WHITELIST_PROXY
            },
        )
        val whitelistInbound = runtime.getAsJsonArray("inbounds").map { it.asJsonObject }
            .first { it.get("tag").asString == RoscomPriorityRouting.INBOUND_WHITELIST }
        assertEquals(10818, whitelistInbound.get("port").asInt)
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

        val bootstrapIndex = rules.indexOfFirst { element ->
            val rule = element.asJsonObject
            rule.has("domain") &&
                rule.getAsJsonArray("domain").any { it.asString == "domain:cdn.jsdelivr.net" } &&
                rule.get("outboundTag").asString == "proxy"
        }
        val adsBlockIndex = rules.indexOfFirst { element ->
            val rule = element.asJsonObject
            rule.has("domain") &&
                rule.getAsJsonArray("domain").any { it.asString.contains("category-ads") } &&
                rule.get("outboundTag").asString == "block"
        }
        assertTrue(bootstrapIndex >= 0)
        assertTrue(adsBlockIndex >= 0)
        assertTrue(bootstrapIndex < adsBlockIndex)
    }

    @Test
    fun injectsGlobalQuicRejectWhenSubscriptionOmitsIt() {
        val withoutQuicBlock = source.deepCopy()
        val rules = withoutQuicBlock.getAsJsonObject("routing").getAsJsonArray("rules")
        val filtered = JsonArray()
        rules.forEach { element ->
            val rule = element.asJsonObject
            if (rule.stringOrNull("network") == "udp" &&
                rule.stringOrNull("port") == "443" &&
                rule.stringOrNull("outboundTag") == "block"
            ) {
                return@forEach
            }
            filtered.add(element)
        }
        withoutQuicBlock.getAsJsonObject("routing").add("rules", filtered)

        val plan = PriorityFailoverConfig.detect(withoutQuicBlock)!!
        val runtime = PriorityFailoverConfig.activate(withoutQuicBlock, plan, activeIndex = 0)
        val runtimeRules = runtime.getAsJsonObject("routing").getAsJsonArray("rules")

        val quicBlockCount = runtimeRules.count { element ->
            val rule = element.asJsonObject
            rule.stringOrNull("network") == "udp" &&
                rule.stringOrNull("port") == "443" &&
                rule.stringOrNull("outboundTag") == "block" &&
                !rule.has("domain") &&
                !rule.has("ip") &&
                !rule.has("inboundTag")
        }
        assertEquals(1, quicBlockCount)
    }

    @Test
    fun keepsSeparateWhitelistTableForP5WithoutMergingIntoFull() {
        val plan = PriorityFailoverConfig.detect(source)!!
        val runtime = PriorityFailoverConfig.activate(source, plan, activeIndex = 1)

        val rules = runtime.getAsJsonObject("routing").getAsJsonArray("rules")
        val domainRules = rules.mapNotNull { it.asJsonObject.takeIf { rule -> rule.has("domain") } }
        val whitelistDirect = domainRules.first { rule ->
            rule.get("outboundTag").asString == "direct" &&
                rule.stringArray("inboundTag").contains(RoscomPriorityRouting.INBOUND_WHITELIST)
        }
        assertTrue(whitelistDirect.getAsJsonArray("domain").any { it.asString.contains("whitelist") })
        assertTrue(whitelistDirect.getAsJsonArray("domain").none { it.asString.contains("category-ru") })
        val fullDirect = domainRules.first { rule ->
            rule.get("outboundTag").asString == "direct" &&
                rule.stringArray("inboundTag").contains("mixed")
        }
        assertTrue(fullDirect.getAsJsonArray("domain").any { it.asString.contains("category-ru") })
        assertTrue(domainRules.any { rule ->
            rule.get("outboundTag").asString == RoscomPriorityRouting.OUTBOUND_WHITELIST_PROXY &&
                rule.getAsJsonArray("domain").any { it.asString == "domain:sberbank.ru" } &&
                rule.stringArray("inboundTag") == listOf(RoscomPriorityRouting.INBOUND_WHITELIST)
        })
        assertEquals(
            listOf("route-p0000-a"),
            runtime.getAsJsonObject("routing")
                .getAsJsonArray("balancers")[0].asJsonObject
                .getAsJsonArray("selector").map { it.asString },
        )
        val remoteDns = runtime.getAsJsonObject("dns").getAsJsonArray("servers")[0].asJsonObject
        assertTrue(remoteDns.getAsJsonArray("domains").any { it.asString == "domain:tbank.ru" })
        assertTrue(remoteDns.getAsJsonArray("domains").any { it.asString.contains("youtube") })
    }

    @Test
    fun tunSocksPortFollowsP0VsP5Mode() {
        assertEquals(10808, RoscomPriorityRouting.tunSocksPort(RoscomPriorityRouting.Mode.FULL, 10808))
        assertEquals(10818, RoscomPriorityRouting.tunSocksPort(RoscomPriorityRouting.Mode.WHITELIST, 10808))
    }

    private fun com.google.gson.JsonObject.stringArray(name: String): List<String> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            it.takeIf { element -> element.isJsonPrimitive && element.asJsonPrimitive.isString }?.asString
        }.orEmpty()

    private fun com.google.gson.JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
