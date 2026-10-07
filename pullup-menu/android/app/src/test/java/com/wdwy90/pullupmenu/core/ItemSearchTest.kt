package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemSearchTest {
    private fun item(name: String, category: String, note: String? = null) = PriceItem(name, null, note, category)

    private val groups = ItemGroups.byCategory(
        listOf(
            item("Whopper", "Burgers"),
            item("Spicy Chicken Sandwich", "Chicken"),
            item("Chicken Fries", "Chicken"),
            item("Pumpkin Spice Shake", "Drinks", "Seasonal, may not be available now"),
            item("McDonald's Classic", "Burgers"),
        )
    )

    private fun names(q: String) = ItemSearch.filter(groups, q).flatMap { g -> g.items.map { it.name } }

    @Test fun blankQueryKeepsEverything() {
        assertEquals(groups, ItemSearch.filter(groups, ""))
        assertEquals(groups, ItemSearch.filter(groups, "   "))
        assertEquals(groups, ItemSearch.filter(groups, "'!"))
        assertTrue(ItemSearch.isBlank(" - "))
    }

    @Test fun matchesNamesCaseInsensitively() {
        assertEquals(listOf("Whopper"), names("WHOP"))
    }

    @Test fun categoryKeepsWholeSection() {
        assertEquals(listOf("Spicy Chicken Sandwich", "Chicken Fries"), names("chicken"))
    }

    @Test fun everyWordMustMatch() {
        assertEquals(listOf("Spicy Chicken Sandwich"), names("spicy chicken"))
        assertEquals(emptyList<String>(), names("spicy burger"))
    }

    @Test fun matchesNotes() {
        assertEquals(listOf("Pumpkin Spice Shake"), names("seasonal"))
    }

    @Test fun ignoresApostrophes() {
        assertEquals(listOf("McDonald's Classic"), names("mcdonalds"))
        assertEquals(listOf("McDonald's Classic"), names("McDonald’s"))
    }

    @Test fun keepsOrderAndDropsEmptyGroups() {
        val result = ItemSearch.filter(groups, "s")
        assertEquals(listOf("Burgers", "Chicken", "Drinks"), result.map { it.title })
        assertEquals(listOf("Chicken", "Drinks"), ItemSearch.filter(groups, "sp").map { it.title })
    }

    @Test fun realChainsAreSearchable() {
        val json = java.io.File("../../shared/chain_prices.json").readText()
        val list = ChainPrices(json).forPlace("Burger King")!!
        val all = ItemGroups.byCategory(list.items)
        assertTrue(ItemSearch.filter(all, "whopper").isNotEmpty())
        assertEquals(list.items.size, ItemSearch.filter(all, "").sumOf { it.items.size })
    }
}
