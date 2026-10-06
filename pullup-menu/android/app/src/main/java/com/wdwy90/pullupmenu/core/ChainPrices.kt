package com.wdwy90.pullupmenu.core

import android.content.Context
import org.json.JSONObject

data class PriceItem(val name: String, val price: String, val note: String? = null)

data class PriceList(
    val chain: String,
    /** Most useful first, at most 6. */
    val items: List<PriceItem>,
    /** Display date, e.g. "Oct 6, 2026". */
    val checked: String,
    val sourceName: String,
    val sourceUrl: String,
) {
    val disclaimer: String get() = "Typical prices. They vary by location. Checked $checked."
}

/** Typical advertised prices for fast-food chains (shared/chain_prices.json). */
class ChainPrices(json: String) {
    private data class Chain(val patterns: List<String>, val list: PriceList)

    private val chains: List<Chain> = JSONObject(json).let { root ->
        val checked = root.optString("checked")
        val arr = root.getJSONArray("chains")
        (0 until arr.length()).mapNotNull { i ->
            val c = arr.getJSONObject(i)
            val m = c.getJSONArray("match")
            val itemsJson = c.optJSONArray("items")
            val items = if (itemsJson == null) emptyList() else (0 until itemsJson.length()).map { j ->
                val o = itemsJson.getJSONObject(j)
                PriceItem(
                    name = o.getString("name"),
                    price = o.getString("price"),
                    note = o.optString("note").takeIf { it.isNotBlank() },
                )
            }
            if (items.isEmpty()) return@mapNotNull null
            Chain(
                (0 until m.length()).map { m.getString(it) },
                PriceList(
                    chain = c.getString("chain"),
                    items = items.take(MAX_ITEMS),
                    checked = c.optString("checked").ifBlank { checked },
                    sourceName = c.getString("sourceName"),
                    sourceUrl = c.getString("sourceUrl"),
                ),
            )
        }
    }

    /** Same matching rules as [ChainMenus.menuUrlFor]. */
    fun forPlace(placeName: String): PriceList? {
        val padded = " ${ChainMenus.normalize(placeName)} "
        return chains.firstOrNull { c -> c.patterns.any { padded.contains(" $it ") } }?.list
    }

    /** First chain that has prices, used for the demo card. */
    fun demoChainName(): String? = chains.firstOrNull()?.list?.chain

    companion object {
        private const val MAX_ITEMS = 6

        @Volatile private var instance: ChainPrices? = null

        fun get(ctx: Context): ChainPrices = instance ?: synchronized(this) {
            instance ?: ChainPrices(
                ctx.assets.open("chain_prices.json").bufferedReader().use { it.readText() }
            ).also { instance = it }
        }
    }
}
