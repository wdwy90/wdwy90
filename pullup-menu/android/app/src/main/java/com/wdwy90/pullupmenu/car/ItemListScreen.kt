package com.wdwy90.pullupmenu.car

import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import com.wdwy90.pullupmenu.core.ItemGroup
import com.wdwy90.pullupmenu.core.ItemGroups
import com.wdwy90.pullupmenu.core.PriceList
import com.wdwy90.pullupmenu.core.Restaurant

/**
 * The chain's full item list on the car screen. Android Auto limits rows per list, and allows five
 * templates per task with only a pane-type one as the fifth, so these lists replace the restaurant
 * card (see [RestaurantScreen]) and go at most [MAX_LEVELS] deep: task steps 2 to 4. A screen shows
 * either the items themselves or menu sections to open (see [ItemGroups]); every chain's list fits
 * within three screens (ItemGroupsTest). Back from the first list brings the card back.
 */
class ItemListScreen(
    ctx: CarContext,
    private val restaurant: Restaurant,
    private val prices: PriceList,
    private val groups: List<ItemGroup> = ItemGroups.byCategory(prices.items),
    private val title: String = "${prices.chain} menu",
    /** 1 for the first list, which took the card's place; each section opened goes one deeper. */
    private val level: Int = 1,
) : Screen(ctx) {
    val restaurantId: String get() = restaurant.id

    private val isTop get() = level == 1

    private val listLimit: Int by lazy {
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } else 6
    }

    init {
        if (isTop) {
            // Only active while this list is on top (started), so Back on a deeper list just closes that list.
            carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    screenManager.pop()
                    screenManager.push(RestaurantScreen(carContext, restaurant))
                }
            })
        }
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        // The top screen keeps one row for the source line.
        val rows = if (isTop) listLimit - 1 else listLimit
        val list = ItemList.Builder()
        when (val page = ItemGroups.page(groups, rows, last = level >= MAX_LEVELS)) {
            is ItemGroups.Page.Items -> {
                page.items.forEach { item ->
                    list.addItem(
                        Row.Builder().setTitle(item.name.ifBlank { "Item" })
                            .apply { item.note?.ifBlank { null }?.let { addText(it) } }
                            .build()
                    )
                }
                // Only for a list too long for three screens, which no chain has (ItemGroupsTest).
                if (page.more > 0) {
                    list.addItem(Row.Builder().setTitle("${page.more} more items").addText("On your phone, when parked").build())
                }
            }
            is ItemGroups.Page.Sections -> page.groups.forEach { group ->
                val preview = group.items.take(3).joinToString(", ") { it.name }
                list.addItem(
                    Row.Builder().setTitle(group.title)
                        .addText("${group.items.size} items · $preview")
                        .setBrowsable(true)
                        .setOnClickListener {
                            screenManager.push(
                                ItemListScreen(carContext, restaurant, prices, listOf(group), group.title, level + 1)
                            )
                        }
                        .build()
                )
            }
        }
        if (isTop) {
            val source = listOfNotNull("Checked ${prices.checked}", prices.sourceName.ifBlank { null })
                .joinToString(" · ")
            list.addItem(Row.Builder().setTitle("Availability varies by location").addText(source).build())
        }

        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    companion object {
        /** Home, then three lists: the fifth template of a task may not be a list. */
        const val MAX_LEVELS = 3
    }
}
