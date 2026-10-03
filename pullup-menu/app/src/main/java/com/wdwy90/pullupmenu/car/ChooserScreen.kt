package com.wdwy90.pullupmenu.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.Restaurant
import kotlin.math.roundToInt

/** Strip mall case: let the driver pick which of the nearby places they're at. */
class ChooserScreen(ctx: CarContext, private val places: List<Restaurant>) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        places.take(6).forEach { r ->
            val detail = listOfNotNull(
                "${(r.distanceMeters * 3.281).roundToInt()} ft",
                r.category,
            ).joinToString(" · ")
            list.addItem(
                Row.Builder().setTitle(r.name).addText(detail)
                    .setOnClickListener { MenuRepository.choose(r) }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle("Which one?")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
