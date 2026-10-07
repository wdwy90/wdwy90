package com.wdwy90.pullupmenu.core

/** Menu items under one title: a category ("Burgers"), or what one car row opens (see [CarMenu]). */
data class ItemGroup(val title: String, val items: List<PriceItem>)

/** Groups a chain's items by category. Pure logic. */
object ItemGroups {
    /** Items grouped by category, in the order each category first appears. */
    fun byCategory(items: List<PriceItem>): List<ItemGroup> =
        items.groupBy { it.category ?: "Menu" }.map { (title, list) -> ItemGroup(title, list) }
}
