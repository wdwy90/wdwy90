package com.wdwy90.pullupmenu.core

import kotlin.math.ceil

/**
 * Plans the menu on the car screen: categories first, then the items of the one category chosen.
 * Pure logic, so it can be unit tested.
 *
 * Android Auto caps the rows a list may show (`rows`: 6 at least, usually far more) and how many
 * screens a task may stack. The menu gets three lists in a row ([LEVELS]): the categories, one
 * category's items, and (only on a host with a small limit) a part of a long category. When the
 * limit allows, the menu shows every category on one screen and each category shows all its items.
 * With a small limit, a long category opens into parts ("Burgers 1 of 3") and, when a chain has
 * more categories than rows, neighbouring categories share a row ("Hot Coffee, Cold Coffee").
 * At the limit of 6 every chain still fits with nothing left off (CarMenuTest).
 */
object CarMenu {

    /** List screens the menu may stack: categories, a category, a part of one. */
    const val LEVELS = 3

    /** What one car screen shows. */
    sealed interface Page {
        /** Rows that each open another screen. */
        data class Rows(val entries: List<Entry>) : Page

        /** The items themselves; [more] didn't fit on the last screen allowed (they're on the phone). */
        data class Items(val items: List<PriceItem>, val more: Int = 0) : Page
    }

    enum class Kind {
        /** One whole category. */
        CATEGORY,

        /** A run of one long category's items ("Burgers 1 of 3"). */
        PART,

        /** Several small categories next to each other in the menu, sharing a row. */
        COMBINED,
    }

    /** A row that opens another screen showing [groups]. */
    data class Entry(val title: String, val groups: List<ItemGroup>, val kind: Kind) {
        val itemCount: Int get() = groups.sumOf { it.items.size }
    }

    /**
     * The screen for [groups] with room for [rows] rows, when [levels] list screens may still be
     * stacked (this one included). One category opens straight onto its items when they fit.
     */
    fun page(groups: List<ItemGroup>, rows: Int, levels: Int): Page {
        val r = rows.coerceAtLeast(2)
        val nonEmpty = groups.filter { it.items.isNotEmpty() }
        if (levels <= 1 || nonEmpty.isEmpty()) return itemsPage(nonEmpty.flatMap { it.items }, r)
        if (nonEmpty.size == 1) {
            val g = nonEmpty[0]
            return if (g.items.size <= r) Page.Items(g.items) else Page.Rows(parts(g, r, levels - 1))
        }
        return Page.Rows(entries(nonEmpty, r, levels))
    }

    /** Short title for several categories sharing a row: "Sides, Drinks", "Sides, Drinks + 2 more". */
    fun combinedTitle(groups: List<ItemGroup>): String =
        if (groups.size <= 3) groups.joinToString(", ") { it.title }
        else groups.take(2).joinToString(", ") { it.title } + " + ${groups.size - 2} more"

    private fun itemsPage(items: List<PriceItem>, r: Int): Page.Items {
        if (items.size <= r) return Page.Items(items)
        val shown = items.take(r - 1) // the last row says how many more
        return Page.Items(shown, more = items.size - shown.size)
    }

    /** Items that [levels] list screens of [r] rows can reach. */
    private fun capacity(r: Int, levels: Int): Long {
        var c = 1L
        repeat(levels.coerceAtLeast(1)) { c *= r }
        return c
    }

    /** Parts a category of [n] items needs so each fits in [below] more screens. */
    private fun partsNeeded(n: Int, r: Int, below: Int): Int =
        ceil(n.toDouble() / capacity(r, below)).toInt().coerceAtLeast(1)

    /** [g] cut into even parts, as few as fit [below] more screens and never more than [r]. */
    private fun parts(g: ItemGroup, r: Int, below: Int): List<Entry> {
        val n = g.items.size
        val count = partsNeeded(n, r, below).coerceIn(2, r)
        return (0 until count).map { i ->
            val items = g.items.subList(i * n / count, (i + 1) * n / count)
            Entry("${g.title} ${i + 1} of $count", listOf(ItemGroup(g.title, items)), Kind.PART)
        }
    }

    private fun entries(groups: List<ItemGroup>, r: Int, levels: Int): List<Entry> {
        val below = levels - 1
        if (groups.size <= r) {
            // One row per category. On the screen just above the last one, a category too long for
            // a screen of its own is opened into parts here instead, when there's room for them.
            if (below == 1 && groups.any { it.items.size > r }) {
                val needed = groups.sumOf { partsNeeded(it.items.size, r, 1) }
                if (needed <= r) {
                    return groups.flatMap { if (it.items.size <= r) listOf(category(it)) else parts(it, r, 1) }
                }
            }
            return groups.map { category(it) }
        }
        // More categories than rows: neighbours share a row.
        return combine(groups, r, below).map { run -> if (run.size == 1) category(run[0]) else Entry(combinedTitle(run), run, Kind.COMBINED) }
    }

    private fun category(g: ItemGroup) = Entry(g.title, listOf(g), Kind.CATEGORY)

    /** Items that showing [groups] in [levels] list screens of [r] rows would leave off. */
    private fun lost(groups: List<ItemGroup>, r: Int, levels: Int): Int =
        when (val p = page(groups, r, levels)) {
            is Page.Items -> p.more
            is Page.Rows -> p.entries.sumOf { lost(it.groups, r, levels - 1) }
        }

    /**
     * [groups] cut into runs of neighbours, one row each: as many runs as there are rows, each run
     * small enough that the screens below it reach every item, and as few categories sharing a row
     * as possible (then the most even split). Falls back to even runs by item count if no cut
     * reaches everything (a limit far below Android Auto's minimum).
     */
    private fun combine(groups: List<ItemGroup>, r: Int, below: Int): List<List<ItemGroup>> {
        val n = groups.size
        val runs = minOf(r, n)
        val fits = Array(n) { i -> BooleanArray(n + 1) { j -> j > i && lost(groups.subList(i, j), r, below) == 0 } }
        // best[k][i]: the best way to cut groups[i..] into k runs, as (largest run, sum of squared item counts).
        data class Cost(val widest: Int, val spread: Long, val cuts: List<Int>)
        val best = Array(runs + 1) { arrayOfNulls<Cost>(n + 1) }
        best[0][n] = Cost(0, 0, emptyList())
        for (k in 1..runs) {
            for (i in n - 1 downTo 0) {
                var pick: Cost? = null
                for (j in i + 1..n) {
                    val rest = best[k - 1][j] ?: continue
                    if (!fits[i][j]) continue
                    val items = groups.subList(i, j).sumOf { it.items.size }.toLong()
                    val c = Cost(maxOf(j - i, rest.widest), rest.spread + items * items, listOf(j) + rest.cuts)
                    if (pick == null || c.widest < pick.widest || (c.widest == pick.widest && c.spread < pick.spread)) pick = c
                }
                best[k][i] = pick
            }
        }
        val plan = best[runs][0] ?: return evenRuns(groups, r)
        var from = 0
        return plan.cuts.map { to -> groups.subList(from, to).also { from = to } }
    }

    /** At most [max] runs of neighbours with about the same number of items each. */
    private fun evenRuns(groups: List<ItemGroup>, max: Int): List<List<ItemGroup>> {
        val total = groups.sumOf { it.items.size }.toDouble()
        val out = ArrayList<List<ItemGroup>>(max)
        var run = ArrayList<ItemGroup>()
        var done = 0
        groups.forEachIndexed { i, g ->
            run += g
            done += g.items.size
            val left = groups.size - i - 1 // categories after this one
            val slots = max - out.size - 1 // runs still free after this one
            if (left > 0 && slots > 0 && (left <= slots || done >= total * (out.size + 1) / max)) {
                out += run
                run = ArrayList()
            }
        }
        if (run.isNotEmpty()) out += run
        return out
    }
}
