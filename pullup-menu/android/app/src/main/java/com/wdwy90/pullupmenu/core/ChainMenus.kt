package com.wdwy90.pullupmenu.core

import android.content.Context
import org.json.JSONObject

/** Maps fast-food chain names to their official online menu (shared/chain_menus.json). */
class ChainMenus(json: String) {
    private data class Chain(val patterns: List<String>, val menuUrl: String)

    private val chains: List<Chain> = JSONObject(json).getJSONArray("chains").let { arr ->
        (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val m = c.getJSONArray("match")
            Chain((0 until m.length()).map { m.getString(it) }, c.getString("menuUrl"))
        }
    }

    fun menuUrlFor(placeName: String): String? {
        val padded = " ${normalize(placeName)} "
        return chains.firstOrNull { c -> c.patterns.any { padded.contains(" $it ") } }?.menuUrl
    }

    companion object {
        /** "McDonald's" -> "mcdonalds", "Chick-fil-A #123" -> "chickfila 123". */
        fun normalize(s: String): String = s.lowercase()
            .replace(Regex("['’.\\-]"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        @Volatile private var instance: ChainMenus? = null

        fun get(ctx: Context): ChainMenus = instance ?: synchronized(this) {
            instance ?: ChainMenus(
                ctx.assets.open("chain_menus.json").bufferedReader().use { it.readText() }
            ).also { instance = it }
        }
    }
}
