package com.wdwy90.pullupmenu.phone

import com.wdwy90.pullupmenu.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** Category icons come from words in the category name; unknown names get the plain plate. */
class CategoryIconsTest {
    private fun check(expected: Int, vararg names: String) {
        for (n in names) assertEquals(n, expected, CategoryIcons.iconFor(n))
    }

    @Test fun wordsPickTheIcon() {
        check(R.drawable.ic_cat_burger, "Burgers", "Hamburgers", "Steakburgers", "Sandwiches & Wraps", "Hot Dogs", "Snack Wrap")
        check(R.drawable.ic_cat_chicken, "Chicken", "McNuggets & McCrispy Strips", "Tenders & Nuggets", "Classic Wings")
        check(R.drawable.ic_cat_coffee, "McCafe", "Hot Coffee and Espresso", "Tea and Matcha", "Frappuccinos")
        check(R.drawable.ic_cat_drink, "Drinks", "Beverages", "Shakes", "Milkshakes", "Refreshers", "Frozen Drinks")
        check(R.drawable.ic_cat_dessert, "Desserts", "Sweets & Treats", "Blizzard Treats", "Frozen Custard", "Bakery")
        check(R.drawable.ic_cat_breakfast, "Breakfast", "Biscuits", "Made from Scratch Biscuits")
        check(R.drawable.ic_cat_sides, "Fries", "Fries & Sides", "Sides & Sweets", "Sauces", "Extras")
        check(R.drawable.ic_cat_taco, "Tacos", "Burritos", "Quesadillas, Nachos & Salads")
        check(R.drawable.ic_cat_salad, "Salads", "Bowls", "Soups")
        check(R.drawable.ic_cat_fish, "Fish", "Seafood")
        check(R.drawable.ic_cat_meal, "Happy Meal", "Kids Meals", "Combos", "Value Menu", "Family Meals", "8 Piece Meal", "Team Meals")
        check(R.drawable.ic_cat_star, "Favorites", "Seasonal", "What's New", "Not So Secret Menu")
    }

    @Test fun firstWordWins() {
        check(R.drawable.ic_cat_chicken, "Chicken & Fish", "Chicken & Hot Dogs")
        check(R.drawable.ic_cat_sides, "Sides & Desserts", "Snacks, Sides & Salads")
        check(R.drawable.ic_cat_burger, "Burgers & Fries")
        check(R.drawable.ic_cat_dessert, "Desserts & Shakes")
    }

    @Test fun unknownNamesGetThePlate() {
        check(R.drawable.ic_cat_plate, "Entrees", "Lunch", "Chill Stop", "Zalads", "Menu", "")
        // A chain's own product names are not food words: they get the plain icon too.
        check(R.drawable.ic_cat_plate, "Frosty", "Freddy's Bevies", "Substitutions")
    }

    @Test fun shortWordsMatchWholeWordsOnly() {
        check(R.drawable.ic_cat_coffee, "Tea", "Teas", "McCafe")
        check(R.drawable.ic_cat_meal, "Pieces & Buckets", "Boxes")
        check(R.drawable.ic_cat_plate, "Team", "Piecework")
    }
}
