package com.wdwy90.pullupmenu.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import com.wdwy90.pullupmenu.core.CarModel
import com.wdwy90.pullupmenu.core.ItemGroup
import com.wdwy90.pullupmenu.core.PriceList
import com.wdwy90.pullupmenu.core.Restaurant

/**
 * Finds an item by name, opened from the search button on the categories. The host offers the
 * keyboard only when parked and voice input while driving; updates as the text changes are
 * refreshes, so searching never uses up a screen. Results are names only, like the lists.
 */
class MenuSearchScreen(
    ctx: CarContext,
    private val restaurant: Restaurant,
    private val prices: PriceList,
    private val groups: List<ItemGroup>,
) : Screen(ctx), ShowsRestaurant {
    override val restaurantId: String get() = restaurant.id

    private var query = ""

    private val callback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) = show(searchText)
        override fun onSearchSubmitted(searchText: String) = show(searchText)
    }

    private fun show(text: String) {
        if (text == query) return
        query = text
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val results = CarModel.search(groups, query, CarUi.listLimit(carContext))
        val list = ItemList.Builder()
        results.items.forEach { item ->
            list.addItem(
                Row.Builder().setTitle(item.name.ifBlank { "Item" })
                    .apply { item.category?.let { addText(it) } }
                    .build()
            )
        }
        if (results.more > 0) {
            list.addItem(Row.Builder().setTitle("${results.more} more matches").addText("Add a word to narrow it down").build())
        }
        if (results.items.isEmpty()) {
            list.setNoItemsMessage(if (query.isBlank()) "Say or type an item name" else "No items match \"${query.trim()}\"")
        }
        return SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search ${prices.chain} menu")
            .setItemList(list.build())
            .build()
    }
}
