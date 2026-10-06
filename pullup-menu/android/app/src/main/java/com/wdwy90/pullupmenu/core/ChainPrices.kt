package com.wdwy90.pullupmenu.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PriceItem(
    val name: String,
    /** Display price like "$1.99", or null when the chain doesn't publish one. */
    val price: String?,
    val note: String? = null,
    /** Menu section like "Burgers"; null for the advertised-price items listed first. */
    val category: String? = null,
)

data class PriceList(
    val chain: String,
    /** Items with published prices first, then the rest of the official menu. */
    val items: List<PriceItem>,
    /** Display date, e.g. "Oct 6, 2026". */
    val checked: String,
    val sourceName: String,
    val sourceUrl: String,
    /** Where the unpriced menu items came from, when that differs from the price source. */
    val menuSourceName: String? = null,
    val menuSourceUrl: String? = null,
) {
    val hasPrices: Boolean get() = items.any { it.price != null }

    val disclaimer: String
        get() = if (hasPrices) "Typical prices. They vary by location. Checked $checked."
        else "Menu items from $sourceName. Prices vary by store. Checked $checked."
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
            val priced = items(c.optJSONArray("items"))
            val seen = priced.map { ChainMenus.normalize(it.name) }.toHashSet()
            val menu = items(c.optJSONArray("menuItems")).filter { seen.add(ChainMenus.normalize(it.name)) }
            val all = priced + menu
            if (all.isEmpty()) return@mapNotNull null
            Chain(
                (0 until m.length()).map { m.getString(it) },
                PriceList(
                    chain = c.getString("chain"),
                    items = all.take(MAX_ITEMS),
                    checked = c.optString("checked").ifBlank { checked },
                    sourceName = c.optString("sourceName").ifBlank { c.getString("menuSourceName") },
                    sourceUrl = c.optString("sourceUrl").ifBlank { c.getString("menuSourceUrl") },
                    menuSourceName = c.optString("menuSourceName").takeIf { it.isNotBlank() && priced.isNotEmpty() },
                    menuSourceUrl = c.optString("menuSourceUrl").takeIf { it.isNotBlank() && priced.isNotEmpty() },
                ),
            )
        }
    }

    private fun items(arr: JSONArray?): List<PriceItem> =
        if (arr == null) emptyList() else (0 until arr.length()).map { j ->
            val o = arr.getJSONObject(j)
            PriceItem(
                name = o.getString("name"),
                price = o.optString("price").takeIf { it.isNotBlank() },
                note = o.optString("note").takeIf { it.isNotBlank() },
                category = o.optString("category").takeIf { it.isNotBlank() },
            )
        }

    /** Same matching rules as [ChainMenus.menuUrlFor]. */
    fun forPlace(placeName: String): PriceList? {
        val padded = " ${ChainMenus.normalize(placeName)} "
        return chains.firstOrNull { c -> c.patterns.any { padded.contains(" $it ") } }?.list
    }

    /** First chain in the file, used for the demo card. */
    fun demoChainName(): String? = chains.firstOrNull()?.list?.chain

    companion object {
        private const val MAX_ITEMS = 150

        @Volatile private var instance: ChainPrices? = null

        fun get(ctx: Context): ChainPrices = instance ?: synchronized(this) {
            instance ?: ChainPrices(
                ctx.assets.open("chain_prices.json").bufferedReader().use { it.readText() }
            ).also { instance = it }
        }
    }
}
