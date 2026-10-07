package com.wdwy90.pullupmenu.car

import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.CarMenu
import com.wdwy90.pullupmenu.core.CarModel
import com.wdwy90.pullupmenu.core.ItemGroup
import com.wdwy90.pullupmenu.core.ItemGroups
import com.wdwy90.pullupmenu.core.PriceItem
import com.wdwy90.pullupmenu.core.PriceList
import com.wdwy90.pullupmenu.core.Restaurant
import com.wdwy90.pullupmenu.phone.CategoryIcons

/**
 * The menu on the car screen, categories first: the first list has one row per category (with how
 * many items it has), and a category opens onto its own items. [CarMenu] plans each screen within
 * the host's row limit and three lists in a row; on a host with a small limit a long category opens
 * into parts and neighbouring categories may share a row. These lists take the restaurant card's
 * place (see [RestaurantScreen.openMenu]), so Back from the categories brings the card back.
 */
class MenuScreen(
    ctx: CarContext,
    private val restaurant: Restaurant,
    private val prices: PriceList,
    private val groups: List<ItemGroup> = ItemGroups.byCategory(prices.items),
    private val title: String = "${prices.chain} menu",
    /** 1 for the categories; each row opened goes one deeper, [CarMenu.LEVELS] at most. */
    private val level: Int = 1,
) : Screen(ctx), ShowsRestaurant {
    override val restaurantId: String get() = restaurant.id

    private val isTop get() = level == 1

    /** Worked out once: the screen never changes while it's up. */
    private val page: CarMenu.Page by lazy {
        CarMenu.page(groups, CarUi.listLimit(carContext), CarMenu.LEVELS - level + 1)
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
        val list = ItemList.Builder()
        when (val p = page) {
            is CarMenu.Page.Rows -> p.entries.forEach { list.addItem(entryRow(it)) }
            is CarMenu.Page.Items -> {
                // Several small categories on one list: say which each item is from.
                val mixed = groups.count { it.items.isNotEmpty() } > 1
                p.items.forEach { list.addItem(itemRow(it, mixed)) }
                // Only for a menu too long for three lists, which no chain has (CarMenuTest).
                if (p.more > 0) {
                    list.addItem(Row.Builder().setTitle("${CarModel.items(p.more)} more").addText("See them on your phone when parked").build())
                }
                if (p.items.isEmpty()) list.setNoItemsMessage("No items on this menu")
            }
        }
        val template = ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
        if (isTop) {
            template.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(CarUi.icon(carContext, R.drawable.ic_search))
                            .setOnClickListener { screenManager.push(MenuSearchScreen(carContext, restaurant, prices, groups)) }
                            .build()
                    )
                    .build()
            )
        }
        return template.build()
    }

    private fun entryRow(e: CarMenu.Entry): Row {
        val icon = when (e.kind) {
            CarMenu.Kind.COMBINED -> R.drawable.ic_menu_book
            else -> CategoryIcons.iconFor(e.groups.first().title)
        }
        return Row.Builder()
            .setTitle(e.title)
            .setImage(CarUi.icon(carContext, icon, CarColor.PRIMARY), Row.IMAGE_TYPE_ICON)
            .addText(CarModel.entryText(e))
            .setBrowsable(true)
            .setOnClickListener {
                screenManager.push(MenuScreen(carContext, restaurant, prices, e.groups, e.title, level + 1))
            }
            .build()
    }

    private fun itemRow(item: PriceItem, mixed: Boolean): Row {
        val text = listOfNotNull(if (mixed) item.category else null, item.note?.ifBlank { null }).joinToString(" · ")
        return Row.Builder().setTitle(item.name.ifBlank { "Item" })
            .apply { if (text.isNotEmpty()) addText(text) }
            .build()
    }
}
