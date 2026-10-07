package com.wdwy90.pullupmenu.core

/** Search for the phone's Items tab. Pure, so it can be unit tested. */
object ItemSearch {

    /**
     * [groups] narrowed to the items matching [query]. Every word of the query must appear in the
     * item's name, its note ("Seasonal, ...") or its category, so "chicken" keeps the whole Chicken
     * section and "spicy chicken" only the spicy items. Case, apostrophes and punctuation are ignored.
     * Groups left empty are dropped; order is kept.
     */
    fun filter(groups: List<ItemGroup>, query: String): List<ItemGroup> {
        val words = words(query)
        if (words.isEmpty()) return groups
        return groups.mapNotNull { g ->
            val category = ChainMenus.normalize(g.title)
            val items = g.items.filter { item ->
                val text = "${ChainMenus.normalize(item.name)} ${ChainMenus.normalize(item.note.orEmpty())} $category"
                words.all { text.contains(it) }
            }
            if (items.isEmpty()) null else ItemGroup(g.title, items)
        }
    }

    fun isBlank(query: String): Boolean = words(query).isEmpty()

    private fun words(query: String): List<String> =
        ChainMenus.normalize(query).split(' ').filter { it.isNotEmpty() }
}
