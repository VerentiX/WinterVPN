package com.v2ray.ang.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityFailoverConfigTest {
    private val source = JsonParser.parseString(
        """
        {
          "outbounds": [
            {"tag":"route-p0000-a","protocol":"vless","settings":{}},
            {"tag":"route-p0000-c","protocol":"vless","settings":{}},
            {"tag":"route-p0001-b","protocol":"vless","settings":{}},
            {"tag":"chain-s0001","protocol":"loopback","settings":{"inboundTag":"chain-in-s0001"}}
          ],
          "routing": {
            "rules": [
              {"type":"field","inboundTag":["auto-proxy-in"],"balancerTag":"tier-s0000"},
              {"type":"field","inboundTag":["chain-in-s0001"],"balancerTag":"tier-s0001"}
            ],
            "balancers": [
              {"tag":"tier-s0000","selector":["route-p0000-a","route-p0000-c"],"fallbackTag":"chain-s0001","strategy":{"type":"leastLoad"}},
              {"tag":"tier-s0001","selector":["route-p0001-b"],"fallbackTag":"route-p0000-a","strategy":{"type":"leastLoad"}}
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
    fun detectsMultipleRoutesInOneTierAndActivatesSelectedRoute() {
        val plan = PriorityFailoverConfig.detect(source)!!
        assertEquals(
            listOf("route-p0000-a", "route-p0000-c", "route-p0001-b"),
            plan.routes,
        )
        assertEquals(listOf("route-p0000-a", "route-p0000-c"), plan.tiers[0].routes)
        assertEquals(0, plan.tiers[0].priority)
        assertEquals(1, plan.tiers[1].priority)
        assertEquals("https://www.gstatic.com/generate_204", plan.probeUrl)
        assertEquals(0, plan.tierIndexForRoute(1))
        assertEquals(1, plan.tierIndexForRoute(2))

        val runtime = PriorityFailoverConfig.activate(
            source,
            plan,
            1,
            mapOf(
                "route-p0000-a" to 21001,
                "route-p0000-c" to 21002,
                "route-p0001-b" to 21003,
            ),
        )
        val root = runtime.getAsJsonObject("routing").getAsJsonArray("balancers")[0].asJsonObject
        assertEquals(listOf("route-p0000-c"), root.getAsJsonArray("selector").map { it.asString })
        assertEquals("random", root.getAsJsonObject("strategy").get("type").asString)
        assertFalse(root.has("fallbackTag"))
        val second = runtime.getAsJsonObject("routing").getAsJsonArray("balancers")[1].asJsonObject
        assertEquals("random", second.getAsJsonObject("strategy").get("type").asString)
        assertFalse(second.has("fallbackTag"))
        assertFalse(runtime.has("burstObservatory"))
        assertTrue(source.has("burstObservatory"))
        val probeInbound = runtime.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals("priority-probe-in-0", probeInbound.get("tag").asString)
        assertEquals(21001, probeInbound.get("port").asInt)
        val probeRule = runtime.getAsJsonObject("routing").getAsJsonArray("rules")[0].asJsonObject
        assertEquals("route-p0000-a", probeRule.get("outboundTag").asString)
    }

    @Test
    fun picksLowestLatencyInsideBestAvailablePriorityTier() {
        val plan = PriorityFailoverConfig.detect(source)!!

        assertEquals(
            1,
            PriorityFailoverConfig.chooseBestRoute(
                plan,
                mapOf(
                    0 to 120L,
                    1 to 45L,
                    2 to 10L,
                ),
            ),
        )
    }

    @Test
    fun fallsBackToNextPriorityWhenWholePreferredTierIsUnavailable() {
        val plan = PriorityFailoverConfig.detect(source)!!

        assertEquals(2, PriorityFailoverConfig.chooseBestRoute(plan, mapOf(2 to 75L)))
        assertNull(PriorityFailoverConfig.chooseBestRoute(plan, emptyMap()))
    }

    @Test
    fun ignoresOrdinaryConfigWithoutBurstObservatory() {
        val ordinary = source.deepCopy().apply { remove("burstObservatory") }
        assertNull(PriorityFailoverConfig.detect(ordinary))
    }
}
