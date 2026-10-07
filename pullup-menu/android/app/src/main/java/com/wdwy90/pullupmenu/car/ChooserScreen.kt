package com.wdwy90.pullupmenu.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.wdwy90.pullupmenu.core.CarModel
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.Restaurant

/** Strip mall case: the other places close by, nearest first, to pick the one the car is at. */
class ChooserScreen(
    ctx: CarContext,
    /** The card this was opened from. */
    override val restaurantId: String,
    private val places: List<Restaurant>,
) : Screen(ctx), ShowsRestaurant {

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        // The last row is the Google Maps credit for these names.
        places.take(CarUi.listLimit(carContext) - 1).forEach { r ->
            val detail = listOfNotNull("${CarModel.distance(r.distanceMeters)} away", r.category?.ifBlank { null })
                .joinToString(" · ")
            list.addItem(
                Row.Builder().setTitle(r.name.ifBlank { "Restaurant" }).addText(detail)
                    .setOnClickListener {
                        // Open its card ourselves (the session only opens new finds), then record the pick.
                        screenManager.popToRoot()
                        screenManager.push(RestaurantScreen(carContext, r))
                        MenuRepository.choose(carContext, r)
                    }
                    .build()
            )
        }
        list.addItem(Row.Builder().setTitle("Info from Google Maps").build())
        return ListTemplate.Builder()
            .setTitle("Which one are you at?")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
