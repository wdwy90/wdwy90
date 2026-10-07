package com.wdwy90.pullupmenu.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.CarModel
import com.wdwy90.pullupmenu.core.CarModel.Lookup
import com.wdwy90.pullupmenu.core.CarModel.Mark
import com.wdwy90.pullupmenu.core.DriveWatcher
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.MenuRepository.State
import com.wdwy90.pullupmenu.core.Prefs
import kotlinx.coroutines.launch

/**
 * Root car screen: whether Pull Up Menu is watching for a drive-thru, what the last check found, and
 * "Check now". The row title only changes with the mode (see [CarModel.home]); the live status is in
 * the row text, icon and button, so status updates are refreshes and never use up a screen.
 */
class HomeScreen(ctx: CarContext, private val session: MenuSession) : Screen(ctx) {

    init {
        lifecycleScope.launch { MenuRepository.state.collect { invalidate() } }
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val state = MenuRepository.state.value
        val home = CarModel.home(session.permissionMissing, Prefs.autoDetectFlow(carContext).value, lookup(state))
        val row = Row.Builder().setTitle(home.title).setImage(mark(home.mark), Row.IMAGE_TYPE_ICON)
        home.lines.forEach { row.addText(it) }

        val pane = Pane.Builder()
            .addRow(row.build())
            .addAction(
                CarUi.action(carContext, home.check, R.drawable.ic_my_location, primary = true) {
                    DriveWatcher.checkNow(carContext)
                }
            )
        if (home.showRestaurant && state is State.Found) {
            pane.addAction(
                CarUi.action(carContext, "Show restaurant", R.drawable.ic_restaurant) {
                    screenManager.push(RestaurantScreen(carContext, state.restaurant))
                }
            )
        }
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("Demo")
                    .setOnClickListener { MenuRepository.showDemo(carContext) }
                    .build()
            )
            .build()
        return PaneTemplate.Builder(pane.build())
            .setTitle("Pull Up Menu")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(strip)
            .build()
    }

    private fun lookup(state: State): Lookup = when (state) {
        State.Idle -> Lookup.Idle
        State.Searching -> Lookup.Searching
        is State.Found -> Lookup.Found(state.restaurant.name.ifBlank { "Restaurant" }, fromGoogle = !state.restaurant.isDemo)
        State.NothingNearby -> Lookup.NothingNearby
        is State.Error -> Lookup.Failed(state.message)
    }

    private fun mark(mark: Mark): CarIcon = when (mark) {
        Mark.READY, Mark.SEARCHING -> CarUi.icon(carContext, R.drawable.ic_my_location, CarColor.PRIMARY)
        Mark.OFF -> CarUi.icon(carContext, R.drawable.ic_my_location)
        Mark.FOUND -> CarUi.icon(carContext, R.drawable.ic_restaurant, CarColor.PRIMARY)
        Mark.NOTHING -> CarUi.icon(carContext, R.drawable.ic_place)
        Mark.PROBLEM -> CarUi.icon(carContext, R.drawable.ic_alert, CarColor.YELLOW)
    }
}
