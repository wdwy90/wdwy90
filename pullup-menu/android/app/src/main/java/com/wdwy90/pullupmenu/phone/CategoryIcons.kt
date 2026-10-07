package com.wdwy90.pullupmenu.phone

import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.ChainMenus

/**
 * A generic icon for a menu category, picked from the words in its name ("Burgers", "Hot Coffee and
 * Espresso", "Fries & Sides"). The word that comes first in the name wins ("Chicken & Fish" is
 * chicken, "Sides & Sweets" sides). Nothing here is specific to a chain, and a name that matches
 * no group gets a plain plate, so the list never says more than the data does.
 */
object CategoryIcons {
    private class Group(val icon: Int, vararg val words: String)

    private val groups = listOf(
        Group(
            R.drawable.ic_cat_coffee,
            "coffee", "latte", "espresso", "cafe", "brew", "tea", "frapp", "macchiato", "cappuccino",
            "mocha", "americano", "chai", "cocoa", "hot chocolate", "matcha",
        ),
        Group(
            R.drawable.ic_cat_breakfast,
            "breakfast", "biscuit", "egg", "pancake", "hash", "morning", "croissant", "bagel", "waffle",
        ),
        Group(
            R.drawable.ic_cat_dessert,
            "dessert", "sweet", "treat", "ice cream", "cone", "sundae", "blizzard", "cake", "cookie",
            "pie", "donut", "doughnut", "bakery", "pastry", "muffin", "brownie", "cinnamon", "churro",
            "parfait", "custard", "concrete", "frosty",
        ),
        Group(
            R.drawable.ic_cat_drink,
            "drink", "beverage", "bevi", "shake", "smoothie", "slush", "lemonade", "juice", "soda",
            "float", "freeze", "refresher", "cooler", "water", "milk", "sip",
        ),
        Group(R.drawable.ic_cat_fish, "fish", "seafood", "shrimp"),
        Group(
            R.drawable.ic_cat_chicken,
            "chicken", "nugget", "tender", "wing", "strip", "crispy", "popcorn", "finger",
        ),
        Group(
            R.drawable.ic_cat_burger,
            "burger", "whopper", "slider", "patty", "beef", "sandwich", "sub", "wrap", "melt",
            "hoagie", "panini", "dog", "hotdog", "footlong", "cheesesteak", "roast",
        ),
        Group(
            R.drawable.ic_cat_taco,
            "taco", "burrito", "quesadilla", "nacho", "chalupa", "gordita", "fajita", "enchilada",
            "mexican", "tostada",
        ),
        Group(R.drawable.ic_cat_salad, "salad", "veggie", "vegetable", "bowl", "greens", "soup"),
        Group(
            R.drawable.ic_cat_sides,
            "fries", "side", "fixin", "chips", "ring", "potato", "tots", "corn", "slaw", "mac", "bread",
            "roll", "appetizer", "extra", "sauce", "dip", "bite",
        ),
        Group(
            R.drawable.ic_cat_meal,
            "kid", "happy", "family", "combo", "meal", "box", "bucket", "pack", "value", "deal",
            "bundle", "platter", "feast", "tailgate", "shareable",
        ),
        Group(
            R.drawable.ic_cat_star,
            "favorite", "signature", "special", "popular", "featured", "new", "seasonal", "secret",
            "limited", "collection",
        ),
    )

    fun iconFor(category: String): Int {
        val name = ChainMenus.normalize(category)
        val tokens = name.split(' ').filter { it.isNotEmpty() }
        var best: Group? = null
        var bestAt = Int.MAX_VALUE
        for (g in groups) {
            val at = g.words.minOf { w -> firstMatch(name, tokens, w) }
            if (at < bestAt) {
                best = g
                bestAt = at
            }
        }
        return best?.icon ?: R.drawable.ic_cat_plate
    }

    /** Index of the first word of [name] that is [word], its plural or a compound ending in it. */
    private fun firstMatch(name: String, tokens: List<String>, word: String): Int {
        if (' ' in word) {
            val at = name.indexOf(word)
            return if (at < 0) Int.MAX_VALUE else name.substring(0, at).count { it == ' ' }
        }
        tokens.forEachIndexed { i, t ->
            val singular = t.removeSuffix("s")
            if (t.startsWith(word) || singular.startsWith(word)) return i
            if (word.length >= 4 && (t.endsWith(word) || singular.endsWith(word))) return i
        }
        return Int.MAX_VALUE
    }
}
