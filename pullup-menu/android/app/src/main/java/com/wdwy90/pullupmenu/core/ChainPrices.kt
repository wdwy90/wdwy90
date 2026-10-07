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
    /** The chain's menu items, grouped by category. */
    val items: List<PriceItem>,
    /** Display date, e.g. "Oct 6, 2026". */
    val checked: String,
    val sourceName: String,
    val sourceUrl: String,
) {
    val disclaimer: String
        get() = "Menu items from $sourceName. Availability varies by location. Checked $checked."
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
            // The Items tab lists names only, so every chain looks the same. Prices stay in the
            // data file but aren't shown. Advertised deals are used only when a chain has no menu list.
            val menu = items(c.optJSONArray("menuItems")).ifEmpty { items(c.optJSONArray("items")) }
            val seen = HashSet<String>()
            val all = menu.filter { seen.add(ChainMenus.normalize(it.name)) }.map { it.copy(price = null) }
            if (all.isEmpty()) return@mapNotNull null
            Chain(
                (0 until m.length()).map { m.getString(it) },
                PriceList(
                    chain = c.getString("chain"),
                    items = all.take(MAX_ITEMS),
                    checked = c.optString("checked").ifBlank { checked },
                    sourceName = c.optString("menuSourceName").ifBlank { c.getString("sourceName") },
                    sourceUrl = c.optString("menuSourceUrl").ifBlank { c.getString("sourceUrl") },
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

    /** Number of chains with an item list. */
    val chainCount: Int get() = chains.size

    /** When the menu data was last checked (the file's date; chains don't override it today). */
    val checked: String get() = chains.firstOrNull()?.list?.checked.orEmpty()

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
