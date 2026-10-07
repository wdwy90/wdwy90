package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CarMenuTest {
    private fun items(category: String, n: Int) = (1..n).map { PriceItem("$category $it", null, category = category) }

    private data class Walk(val reached: List<String>, val left: Int, val depth: Int, val screens: Int)

    /** Opens every row like a driver would, [CarMenu.LEVELS] list screens deep at most. */
    private fun walk(groups: List<ItemGroup>, rows: Int, level: Int = 1): Walk =
        when (val page = CarMenu.page(groups, rows, CarMenu.LEVELS - level + 1)) {
            is CarMenu.Page.Items -> {
                val rowCount = page.items.size + if (page.more > 0) 1 else 0
                assertTrue("$rowCount rows > $rows", rowCount <= rows)
                Walk(page.items.map { it.name }, page.more, level, 1)
            }
            is CarMenu.Page.Rows -> {
                assertTrue("${page.entries.size} rows > $rows", page.entries.size <= rows)
                assertTrue("a level-$level screen opens rows past the last level", level < CarMenu.LEVELS)
                val below = page.entries.map { walk(it.groups, rows, level + 1) }
                Walk(below.flatMap { it.reached }, below.sumOf { it.left }, below.maxOf { it.depth }, 1 + below.sumOf { it.screens })
            }
        }

    private fun chains(): List<Pair<String, List<ItemGroup>>> {
        val prices = ChainPrices(File("../../shared/chain_prices.json").readText())
        val names = org.json.JSONObject(File("../../shared/chain_menus.json").readText()).getJSONArray("chains")
        val out = (0 until names.length()).map { i ->
            val list = prices.forPlace(names.getJSONObject(i).getString("name"))!!
            list.chain to ItemGroups.byCategory(list.items)
        }
        assertTrue(out.size >= 30)
        return out
    }

    @Test fun categoriesComeFirstEvenWhenEveryItemWouldFit() {
        // A host that allows 100 rows must still open on the categories, not on every item.
        val all = items("Burgers", 9) + items("Sides", 5) + items("Drinks", 7)
        val page = CarMenu.page(ItemGroups.byCategory(all), 100, CarMenu.LEVELS) as CarMenu.Page.Rows
        assertEquals(listOf("Burgers", "Sides", "Drinks"), page.entries.map { it.title })
        assertEquals(listOf(9, 5, 7), page.entries.map { it.itemCount })
        assertTrue(page.entries.all { it.kind == CarMenu.Kind.CATEGORY })
    }

    @Test fun aCategoryOpensOntoOnlyItsOwnItems() {
        val all = items("Burgers", 9) + items("Sides", 5)
        val top = CarMenu.page(ItemGroups.byCategory(all), 100, 3) as CarMenu.Page.Rows
        val burgers = CarMenu.page(top.entries[0].groups, 100, 2) as CarMenu.Page.Items
        assertEquals(items("Burgers", 9).map { it.name }, burgers.items.map { it.name })
        assertEquals(0, burgers.more)
    }

    @Test fun aLoneCategoryOpensStraightOntoItsItems() {
        val page = CarMenu.page(ItemGroups.byCategory(items("Menu", 8)), 20, 3)
        assertTrue(page is CarMenu.Page.Items)
    }

    @Test fun aLongCategoryOpensIntoPartsOnASmallHost() {
        val page = CarMenu.page(listOf(ItemGroup("Burgers", items("Burgers", 14))), 6, 2) as CarMenu.Page.Rows
        assertEquals(listOf("Burgers 1 of 3", "Burgers 2 of 3", "Burgers 3 of 3"), page.entries.map { it.title })
        assertEquals(listOf(4, 5, 5), page.entries.map { it.itemCount })
        assertTrue(page.entries.all { it.kind == CarMenu.Kind.PART && it.groups.single().title == "Burgers" })
    }

    @Test fun moreCategoriesThanRowsShareRowsInOrder() {
        val all = (1..13).flatMap { items("C$it", 3) }
        val page = CarMenu.page(ItemGroups.byCategory(all), 6, 3) as CarMenu.Page.Rows
        assertEquals(6, page.entries.size)
        assertEquals(all.map { it.name }, page.entries.flatMap { e -> e.groups.flatMap { g -> g.items.map { it.name } } })
        val combined = page.entries.first { it.kind == CarMenu.Kind.COMBINED }
        assertEquals(CarMenu.combinedTitle(combined.groups), combined.title)
    }

    @Test fun combinedTitlesStayShort() {
        val g = (1..5).map { ItemGroup("C$it", items("C$it", 1)) }
        assertEquals("C1, C2", CarMenu.combinedTitle(g.take(2)))
        assertEquals("C1, C2, C3", CarMenu.combinedTitle(g.take(3)))
        assertEquals("C1, C2 + 3 more", CarMenu.combinedTitle(g))
    }

    @Test fun emptyCategoriesAreLeftOut() {
        val page = CarMenu.page(listOf(ItemGroup("Empty", emptyList()), ItemGroup("Sides", items("Sides", 2))), 6, 3)
        assertTrue(page is CarMenu.Page.Items)
    }

    @Test fun everyChainShowsEachCategoryOnceAndEveryItemOnAnyHost() {
        for ((name, groups) in chains()) {
            for (rows in listOf(6, 7, 8, 10, 12, 20, 25, 100)) {
                val w = walk(groups, rows)
                val expected = groups.flatMap { g -> g.items.map { it.name } }
                assertEquals("$name at $rows rows: items left off", 0, w.left)
                assertEquals("$name at $rows rows", expected, w.reached)
                assertTrue("$name at $rows rows needs ${w.depth} screens", w.depth <= CarMenu.LEVELS)
                val top = CarMenu.page(groups, rows, CarMenu.LEVELS)
                if (groups.size > 1 && rows >= groups.size) {
                    // Room for every category: one row each, in the chain's order.
                    top as CarMenu.Page.Rows
                    assertEquals("$name at $rows rows", groups.map { it.title }, top.entries.map { it.title })
                }
                if (rows >= 25) {
                    // A roomy host (Android Auto usually is): category, then all of its items on one screen.
                    assertEquals("$name at $rows rows", 2, w.depth)
                }
            }
        }
    }
}
