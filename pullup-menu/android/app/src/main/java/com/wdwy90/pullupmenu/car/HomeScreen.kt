package com.wdwy90.pullupmenu.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.core.DriveWatcher
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.MenuRepository.State
import com.wdwy90.pullupmenu.core.Prefs
import kotlinx.coroutines.launch

/**
 * Root car screen: auto-detect status, "Check now", and "Show details" for the last stop.
 * The row title only changes with the mode (permission / auto-detect on / off); live status goes in
 * the row text, so status updates count as refreshes and not as new template steps.
 */
class HomeScreen(ctx: CarContext, private val session: MenuSession) : Screen(ctx) {

    init {
        lifecycleScope.launch { MenuRepository.state.collect { invalidate() } }
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val state = MenuRepository.state.value
        val autoDetect = Prefs.autoDetectFlow(carContext).value
        val title = when {
            session.permissionMissing -> "Location permission needed"
            autoDetect -> "Watching for drive-thrus"
            else -> "Auto-detect is off"
        }
        val detail = when {
            session.permissionMissing -> "Open Pull Up Menu on your phone and allow location."
            state is State.Searching -> "Looking up where you are."
            state is State.Found -> "Last stop: ${state.restaurant.name}"
            state is State.NothingNearby ->
                if (autoDetect) "No fast food here. I'll check again at your next stop." else "No fast food here."
            state is State.Error -> state.message
            autoDetect -> "Pull into a fast-food drive-thru line and the restaurant card appears."
            else -> "In the drive-thru line, tap Check now. You can turn on auto-detect in the phone app."
        }
        val row = Row.Builder().setTitle(title).apply { if (detail.isNotBlank()) addText(detail) }.build()

        val pane = Pane.Builder()
            .addRow(row)
            .addAction(
                Action.Builder().setTitle("Check now")
                    .setOnClickListener { DriveWatcher.checkNow(carContext) }
                    .build()
            )
        if (state is State.Found) {
            pane.addAction(
                Action.Builder().setTitle("Show details")
                    .setOnClickListener {
                        screenManager.push(RestaurantScreen(carContext, state.restaurant))
                    }
                    .build()
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
}
