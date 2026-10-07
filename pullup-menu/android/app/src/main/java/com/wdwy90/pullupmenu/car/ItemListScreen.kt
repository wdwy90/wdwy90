package com.wdwy90.pullupmenu.car

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

/**
 * The chain's full item list on the car screen. Android Auto limits rows per list and screens
 * per task, so a screen shows either the items themselves or menu sections to open
 * (see [ItemGroups]); every item is reachable within three screens.
 */
class ItemListScreen(
    ctx: CarContext,
    private val prices: PriceList,
    private val groups: List<ItemGroup> = ItemGroups.byCategory(prices.items),
    private val title: String = "${prices.chain} menu",
) : Screen(ctx) {

    private val isTop get() = groups.sumOf { it.items.size } == prices.items.size

    private val listLimit: Int by lazy {
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } else 6
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        // The top screen keeps one row for the source line.
        val rows = if (isTop) listLimit - 1 else listLimit
        val list = ItemList.Builder()
        val children = ItemGroups.children(groups, rows)
        if (children == null) {
            groups.flatMap { it.items }.forEach { item ->
                list.addItem(
                    Row.Builder().setTitle(item.name.ifBlank { "Item" })
                        .apply { item.note?.ifBlank { null }?.let { addText(it) } }
                        .build()
                )
            }
        } else {
            children.forEach { group ->
                val preview = group.items.take(3).joinToString(", ") { it.name }
                list.addItem(
                    Row.Builder().setTitle(group.title)
                        .addText("${group.items.size} items · $preview")
                        .setBrowsable(true)
                        .setOnClickListener {
                            screenManager.push(ItemListScreen(carContext, prices, listOf(group), group.title))
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
}
