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

/** Short text list of typical prices for the chain, plus a "prices vary" footer row. */
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
        prices.items.take(minOf(5, listLimit - 1).coerceAtLeast(0)).forEach { item ->
            val text = listOfNotNull(item.price.ifBlank { null }, item.note?.ifBlank { null })
                .joinToString(" · ")
            list.addItem(
                Row.Builder().setTitle(item.name.ifBlank { "Item" })
                    .apply { if (text.isNotEmpty()) addText(text) }
                    .build()
            )
        }
        val source = listOfNotNull("Checked ${prices.checked}", prices.sourceName.ifBlank { null })
            .joinToString(" · ")
        list.addItem(Row.Builder().setTitle("Prices vary by location").addText(source).build())

        return ListTemplate.Builder()
            .setTitle("${prices.chain} prices")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
