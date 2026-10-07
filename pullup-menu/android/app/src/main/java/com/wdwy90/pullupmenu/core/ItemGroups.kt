package com.wdwy90.pullupmenu.core

/** A set of menu items shown as one row on the car screen ("Burgers", "Sides, Drinks", "Burgers 1 of 3"). */
data class ItemGroup(val title: String, val items: List<PriceItem>)

/**
 * Splits a chain's item list into car-screen pages. Android Auto caps how many rows a list may
 * show ([limit]) and how many screens deep an app may go, so every screen gets at most [limit]
 * rows: either items (when they fit) or groups to drill into. With a limit of 6 (5 on the first
 * screen) every chain's list is reachable within three screens (ItemGroupsTest checks each one);
 * [page] cuts a list short on the last screen allowed rather than going deeper. Pure logic.
 */
object ItemGroups {
    /** What one car screen shows: sections to open, or items. */
    sealed interface Page {
        data class Sections(val groups: List<ItemGroup>) : Page
        /** [more] items didn't fit on the last screen allowed (they're on the phone). */
        data class Items(val items: List<PriceItem>, val more: Int = 0) : Page
    }

    /**
     * The screen for [groups] with room for [rows] rows. On the [last] screen the host allows there
     * are no sections to open: items that don't fit are left off, one row saying how many.
     */
    fun page(groups: List<ItemGroup>, rows: Int, last: Boolean): Page {
        val children = children(groups, rows)
        if (children != null && !last) return Page.Sections(children)
        val items = groups.flatMap { it.items }
        if (items.size <= rows) return Page.Items(items)
        val shown = items.take((rows - 1).coerceAtLeast(0))
        return Page.Items(shown, more = items.size - shown.size)
    }

    /** Items grouped by category, in the order each category first appears. */
    fun byCategory(items: List<PriceItem>): List<ItemGroup> =
        items.groupBy { it.category ?: "Menu" }.map { (title, list) -> ItemGroup(title, list) }

    /**
     * What one screen shows for [groups]: null means the items fit, so list them directly;
     * otherwise at most [limit] groups to drill into.
     */
    fun children(groups: List<ItemGroup>, limit: Int): List<ItemGroup>? {
        val max = limit.coerceAtLeast(2)
        if (groups.sumOf { it.items.size } <= max) return null
        val parts = if (groups.size == 1) split(groups[0], max) else groups
        return merge(parts, max)
    }

    /** One group cut into at most [max] even pages: "Burgers 1 of 3", ... A page bigger than [max] is split again a level down. */
    private fun split(group: ItemGroup, max: Int): List<ItemGroup> {
        val n = group.items.size
        val count = minOf(max, (n + max - 1) / max)
        val pages = (0 until count).map { i -> group.items.subList(i * n / count, (i + 1) * n / count) }
        return pages.mapIndexed { i, page -> ItemGroup("${group.title} ${i + 1} of ${pages.size}", page) }
    }

    /** Neighbouring groups joined until there are at most [max], keeping sizes even. */
    private fun merge(groups: List<ItemGroup>, max: Int): List<ItemGroup> {
        if (groups.size <= max) return groups
        val total = groups.sumOf { it.items.size }
        val out = ArrayList<ItemGroup>(max)
        var bucket = ArrayList<ItemGroup>()
        var done = 0
        for (g in groups) {
            bucket += g
            done += g.items.size
            // Close the bucket once it holds its share of the items; the last bucket takes the rest.
            if (out.size < max - 1 && done >= total.toDouble() * (out.size + 1) / max) {
                out += join(bucket)
                bucket = ArrayList()
            }
        }
        if (bucket.isNotEmpty()) out += join(bucket)
        return out
    }

    private fun join(groups: List<ItemGroup>): ItemGroup =
        if (groups.size == 1) groups[0]
        else ItemGroup(groups.joinToString(", ") { it.title }, groups.flatMap { it.items })
}
