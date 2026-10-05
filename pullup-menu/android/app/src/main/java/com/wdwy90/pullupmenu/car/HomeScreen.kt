package com.wdwy90.pullupmenu.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.MenuRepository.State
import kotlinx.coroutines.launch

/** Root car screen: shows watcher status and a "Check now" button. */
class HomeScreen(ctx: CarContext, private val session: MenuSession) : Screen(ctx) {

    init {
        lifecycleScope.launch { MenuRepository.state.collect { invalidate() } }
    }

    override fun onGetTemplate(): Template {
        val state = MenuRepository.state.value
        val (title, detail) = when {
            session.permissionMissing ->
                "Location permission needed" to "Open Pull Up Menu on your phone and allow location."
            state is State.Searching -> "Looking up where you are…" to null
            state is State.Found -> "Last stop: ${state.restaurant.name}" to state.restaurant.address
            state is State.NothingNearby -> "No fast food here" to "I'll check again at your next stop."
            state is State.Error -> "Couldn't look up fast food" to state.message
            else -> "Watching for drive-thrus" to "Get in line at a fast-food drive-thru — the menu will appear."
        }
        val row = Row.Builder().setTitle(title).apply { detail?.let { addText(it) } }.build()

        val pane = Pane.Builder()
            .addRow(row)
            .addAction(
                Action.Builder().setTitle("Check now")
                    .setOnClickListener { session.watcher.checkNow() }
                    .build()
            )
        if (state is State.Found) {
            pane.addAction(
                Action.Builder().setTitle("Show menu")
                    .setOnClickListener {
                        screenManager.push(RestaurantScreen(carContext, state.restaurant))
                    }
                    .build()
            )
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle("Pull Up Menu")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
