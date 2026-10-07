package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ItemGroupsTest {
    private fun items(category: String, n: Int) = (1..n).map { PriceItem("$category $it", null, category = category) }

    /** Opens every group like a driver would; returns the leaf item names and the deepest level. */
    /** The first screen keeps one row for the source line, like ItemListScreen. */
    private fun walk(groups: List<ItemGroup>, limit: Int, depth: Int = 1): Pair<List<String>, Int> {
        val rows = if (depth == 1) limit - 1 else limit
        val kids = ItemGroups.children(groups, rows)
            ?: return groups.flatMap { g -> g.items.map { it.name } }.also { assertTrue(it.size <= rows) } to depth
        assertTrue("${kids.size} rows > $rows", kids.size <= rows)
        val results = kids.map { walk(listOf(it), limit, depth + 1) }
        return results.flatMap { it.first } to results.maxOf { it.second }
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
        val (reached, depth) = walk(ItemGroups.byCategory(items("Tacos", 150)), 6)
        assertEquals(150, reached.size)
        assertTrue("needs $depth screens", depth <= 3)
    }

    @Test fun everyChainIsFullyReachableWithinThreeScreens() {
        val prices = ChainPrices(File("../../shared/chain_prices.json").readText())
        val names = org.json.JSONObject(File("../../shared/chain_menus.json").readText()).getJSONArray("chains")
        for (limit in listOf(6, 10, 100)) {
            for (i in 0 until names.length()) {
                val list = prices.forPlace(names.getJSONObject(i).getString("name"))!!
                val groups = ItemGroups.byCategory(list.items)
                val (reached, depth) = walk(groups, limit)
                assertEquals("${list.chain} @ $limit", groups.flatMap { g -> g.items.map { it.name } }, reached)
                assertTrue("${list.chain} @ $limit needs $depth screens", depth <= 3)
            }
        }
    }
}
