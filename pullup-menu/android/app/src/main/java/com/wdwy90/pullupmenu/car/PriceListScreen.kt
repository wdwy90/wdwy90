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
import com.wdwy90.pullupmenu.core.PriceList

/** Short list of the chain's menu items, plus a footer row pointing to the full list on the phone. */
class PriceListScreen(ctx: CarContext, private val prices: PriceList) : Screen(ctx) {

    private val listLimit: Int by lazy {
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } else 6
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        // The car shows only a few rows; the full list is on the phone.
        prices.items.take(minOf(5, listLimit - 1).coerceAtLeast(0)).forEach { item ->
            val text = item.note?.ifBlank { null }.orEmpty()
            list.addItem(
                Row.Builder().setTitle(item.name.ifBlank { "Item" })
                    .apply { if (text.isNotEmpty()) addText(text) }
                    .build()
            )
        }
        val source = listOfNotNull("Checked ${prices.checked}", prices.sourceName.ifBlank { null })
            .joinToString(" · ")
        list.addItem(Row.Builder().setTitle("Full list on your phone").addText(source).build())

        return ListTemplate.Builder()
            .setTitle("${prices.chain} menu items")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
