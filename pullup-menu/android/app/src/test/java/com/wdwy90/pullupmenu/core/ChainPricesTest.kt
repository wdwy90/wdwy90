package com.wdwy90.pullupmenu.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChainPricesTest {
    // Tests run with the module dir as working directory.
    private val json = File("../../shared/chain_prices.json").readText()
    private val prices = ChainPrices(json)

    @Test fun matchesLikeChainMenus() {
        assertEquals("Taco Bell", prices.forPlace("Taco Bell Cantina")?.chain)
        assertEquals("McDonald's", prices.forPlace("McDonald’s")?.chain)
        assertEquals("Checkers", prices.forPlace("Rally's")?.chain)
        assertNull(prices.forPlace("Supersonic Car Wash"))
        assertNull(prices.forPlace("Joe's Burgers"))
    }

    @Test fun demoChainHasPrices() {
        val chain = prices.demoChainName()
        assertNotNull(chain)
        val list = prices.forPlace("$chain (demo)")
        assertNotNull(list)
        assertTrue(list!!.items.isNotEmpty())
    }

    @Test fun everyItemHasADollarPrice() {
        val chains = JSONObject(json).getJSONArray("chains")
        for (i in 0 until chains.length()) {
            val c = chains.getJSONObject(i)
            val list = prices.forPlace(c.getString("chain"))
            assertNotNull("${c.getString("chain")} should match its own name", list)
            assertTrue(list!!.items.size in 1..6)
            assertTrue(list.checked.isNotBlank())
            assertTrue(list.sourceUrl.startsWith("https://"))
            for (item in list.items) {
                assertTrue("${item.name}: ${item.price}", item.price.startsWith("$"))
                assertTrue(item.name.isNotBlank())
            }
            val items = c.getJSONArray("items")
            for (j in 0 until items.length()) {
                val src = items.getJSONObject(j).getJSONObject("source")
                assertTrue(src.getString("url").startsWith("https://"))
            }
        }
    }

    @Test fun disclaimer() {
        val list = prices.forPlace("Subway")!!
        assertEquals("Typical prices. They vary by location. Checked Oct 6, 2026.", list.disclaimer)
    }

    @Test fun everyPriceChainHasAMenuLink() {
        val menus = ChainMenus(File("../../shared/chain_menus.json").readText())
        val chains = JSONObject(json).getJSONArray("chains")
        for (i in 0 until chains.length()) {
            val m = chains.getJSONObject(i).getJSONArray("match")
            for (j in 0 until m.length()) {
                val pattern = m.getString(j)
                assertNotNull("menu link for $pattern", menus.menuUrlFor(pattern))
                assertNotNull("prices for $pattern", prices.forPlace(pattern))
            }
        }
        assertNotNull(menus.menuUrlFor(prices.demoChainName()!!))
    }

    @Test fun parsingRules() {
        val item = { n: Int -> """{"name":"Item $n","price":"$$n"}""" }
        val p = ChainPrices(
            """
            {"checked":"Oct 6, 2026","chains":[
              {"chain":"Empty","match":["empty"],"sourceName":"a.com","sourceUrl":"https://a.com","items":[]},
              {"chain":"Big","match":["big"],"sourceName":"b.com","sourceUrl":"https://b.com",
               "checked":"Jan 1, 2026",
               "items":[{"name":"Item 0","price":"$0","note":""},${(1..7).joinToString(",") { item(it) }}]}
            ]}
            """.trimIndent()
        )
        assertEquals("Big", p.demoChainName())
        assertNull(p.forPlace("Empty"))
        val big = p.forPlace("Big")!!
        assertEquals(6, big.items.size)
        assertEquals("Jan 1, 2026", big.checked)
        assertNull(big.items[0].note)
    }
}
