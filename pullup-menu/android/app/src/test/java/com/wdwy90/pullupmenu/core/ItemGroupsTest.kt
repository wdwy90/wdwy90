package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** How the car plans these groups onto its screens is tested in CarMenuTest. */
class ItemGroupsTest {
    private fun items(category: String, n: Int) = (1..n).map { PriceItem("$category $it", null, category = category) }

    @Test fun categoryOrderIsKeptAndRepeatsMerge() {
        val all = items("A", 1) + items("B", 1) + items("A", 1).map { it.copy(name = "A late") }
        assertEquals(listOf("A", "B"), ItemGroups.byCategory(all).map { it.title })
        assertEquals(2, ItemGroups.byCategory(all)[0].items.size)
    }

    @Test fun itemsWithoutACategoryGoUnderMenu() {
        val groups = ItemGroups.byCategory(listOf(PriceItem("Plain", null)))
        assertEquals(listOf("Menu"), groups.map { it.title })
    }
}
