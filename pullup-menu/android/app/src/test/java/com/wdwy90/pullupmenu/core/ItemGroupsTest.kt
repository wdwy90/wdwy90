package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ItemGroupsTest {
    private fun items(category: String, n: Int) = (1..n).map { PriceItem("$category $it", null, category = category) }

    /**
     * Opens every section like a driver would, three screens deep at most like ItemListScreen (whose
     * first screen keeps one row for the source line). Returns the item names reached, how many items
     * were left off for the phone, and the deepest screen.
     */
    private fun walk(groups: List<ItemGroup>, limit: Int, depth: Int = 1): Triple<List<String>, Int, Int> {
        val rows = if (depth == 1) limit - 1 else limit
        return when (val page = ItemGroups.page(groups, rows, last = depth >= 3)) {
            is ItemGroups.Page.Items -> {
                val rowCount = page.items.size + if (page.more > 0) 1 else 0
                assertTrue("$rowCount rows > $rows", rowCount <= rows)
                Triple(page.items.map { it.name }, page.more, depth)
            }
            is ItemGroups.Page.Sections -> {
                assertTrue("${page.groups.size} rows > $rows", page.groups.size <= rows)
                val results = page.groups.map { walk(listOf(it), limit, depth + 1) }
                Triple(results.flatMap { it.first }, results.sumOf { it.second }, results.maxOf { it.third })
            }
        }
    }

    @Test fun smallListShowsItemsDirectly() {
        assertNull(ItemGroups.children(ItemGroups.byCategory(items("Burgers", 5)), 6))
    }

    @Test fun categoriesBecomeRows() {
        val all = items("Burgers", 4) + items("Sides", 3) + items("Drinks", 2)
        val kids = ItemGroups.children(ItemGroups.byCategory(all), 6)!!
        assertEquals(listOf("Burgers", "Sides", "Drinks"), kids.map { it.title })
    }

    @Test fun bigCategoryIsSplitIntoPages() {
        val kids = ItemGroups.children(ItemGroups.byCategory(items("Burgers", 14)), 6)!!
        assertEquals(listOf("Burgers 1 of 3", "Burgers 2 of 3", "Burgers 3 of 3"), kids.map { it.title })
        assertEquals(listOf(4, 5, 5), kids.map { it.items.size })
    }

    @Test fun manyCategoriesAreJoined() {
        val all = (1..13).flatMap { items("C$it", 3) }
        val kids = ItemGroups.children(ItemGroups.byCategory(all), 6)!!
        assertTrue(kids.size <= 6)
        assertEquals("C1, C2, C3", kids[0].title)
        assertEquals(all.map { it.name }, kids.flatMap { g -> g.items.map { it.name } })
    }

    @Test fun categoryOrderIsKeptAndRepeatsMerge() {
        val all = items("A", 1) + items("B", 1) + items("A", 1).map { it.copy(name = "A late") }
        assertEquals(listOf("A", "B"), ItemGroups.byCategory(all).map { it.title })
        assertEquals(2, ItemGroups.byCategory(all)[0].items.size)
    }

    @Test fun hugeCategoryStillFitsThreeScreens() {
        val (reached, more, depth) = walk(ItemGroups.byCategory(items("Tacos", 150)), 6)
        assertEquals(150, reached.size)
        assertEquals(0, more)
        assertTrue("needs $depth screens", depth <= 3)
    }

    @Test fun theThirdScreenNeverOpensSections() {
        // 37 burgers next to another category would need a fourth screen of 6 rows; the car allows
        // three lists, so the third one lists what fits and says how many more are on the phone.
        val all = items("Burgers", 37) + items("Sides", 1)
        val (reached, more, depth) = walk(ItemGroups.byCategory(all), 6)
        assertEquals(3, depth)
        assertTrue(more > 0)
        assertEquals(all.size, reached.size + more)
    }

    @Test fun lastScreenListsItemsThatFit() {
        val page = ItemGroups.page(ItemGroups.byCategory(items("Burgers", 6)), 6, last = true)
        assertEquals(ItemGroups.Page.Items(items("Burgers", 6)), page)
    }

    @Test fun lastScreenSaysHowManyAreLeftOff() {
        val page = ItemGroups.page(ItemGroups.byCategory(items("Burgers", 9)), 6, last = true) as ItemGroups.Page.Items
        assertEquals(5, page.items.size) // and the "4 more items" row makes 6
        assertEquals(4, page.more)
    }

    @Test fun everyChainIsFullyReachableWithinThreeScreens() {
        val prices = ChainPrices(File("../../shared/chain_prices.json").readText())
        val names = org.json.JSONObject(File("../../shared/chain_menus.json").readText()).getJSONArray("chains")
        for (limit in listOf(6, 10, 100)) {
            for (i in 0 until names.length()) {
                val list = prices.forPlace(names.getJSONObject(i).getString("name"))!!
                val groups = ItemGroups.byCategory(list.items)
                val (reached, more, depth) = walk(groups, limit)
                assertEquals("${list.chain} @ $limit", groups.flatMap { g -> g.items.map { it.name } }, reached)
                assertEquals("${list.chain} @ $limit leaves items off", 0, more)
                assertTrue("${list.chain} @ $limit needs $depth screens", depth <= 3)
            }
        }
    }
}
