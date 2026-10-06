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
            assertTrue(list!!.items.size in 1..150)
            assertTrue(list.checked.isNotBlank())
            assertTrue(list.sourceUrl.startsWith("https://"))
            for (item in list.items) {
                item.price?.let { assertTrue("${item.name}: $it", it.startsWith("$")) }
                assertTrue(item.name.isNotBlank())
            }
            val items = c.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val src = items.getJSONObject(j).getJSONObject("source")
                assertTrue(src.getString("url").startsWith("https://"))
            }
        }
    }

    @Test fun menuItemsFollowPricedItems() {
        val p = ChainPrices(
            """
            {"checked":"Oct 6, 2026","chains":[
              {"chain":"Both","match":["both"],"sourceName":"news.com","sourceUrl":"https://news.com",
               "menuSourceName":"both.com","menuSourceUrl":"https://both.com/menu",
               "items":[{"name":"Big Burger","price":"$5"}],
               "menuItems":[{"name":"Big Burger","category":"Burgers"},{"name":"Fries","category":"Sides"}]},
              {"chain":"Names","match":["names"],"menuSourceName":"names.com","menuSourceUrl":"https://names.com",
               "menuItems":[{"name":"Taco","category":"Tacos"}]}
            ]}
            """.trimIndent()
        )
        val both = p.forPlace("Both")!!
        assertEquals(listOf("Big Burger", "Fries"), both.items.map { it.name })
        assertEquals("$5", both.items[0].price)
        assertNull(both.items[1].price)
        assertEquals("Sides", both.items[1].category)
        assertTrue(both.hasPrices)
        assertEquals("both.com", both.menuSourceName)
        val names = p.forPlace("Names")!!
        assertEquals("names.com", names.sourceName)
        assertNull(names.menuSourceName)
        assertTrue(!names.hasPrices)
        assertEquals("Menu items from names.com. Prices vary by store. Checked Oct 6, 2026.", names.disclaimer)
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
        assertEquals(8, big.items.size)
        assertEquals("Jan 1, 2026", big.checked)
        assertNull(big.items[0].note)
    }
}
