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
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.R
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

    /** Action flags (primary button styling) need car API 4. */
    private val primaryActions = ctx.carAppApiLevel >= CarAppApiLevels.LEVEL_4

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
            autoDetect -> "Pull into a fast-food drive-thru lane and the restaurant card appears."
            else -> "In the drive-thru lane, tap Check now. Auto-detect is in Settings on your phone."
        }
        val statusIcon = when {
            session.permissionMissing -> R.drawable.ic_info
            state is State.Found -> R.drawable.ic_restaurant
            else -> R.drawable.ic_my_location
        }
        val row = Row.Builder().setTitle(title)
            .setImage(icon(statusIcon), Row.IMAGE_TYPE_ICON)
            .apply { if (detail.isNotBlank()) addText(detail) }
            .build()

        val pane = Pane.Builder()
            .addRow(row)
            .addAction(
                Action.Builder().setTitle("Check now")
                    .setIcon(icon(R.drawable.ic_my_location))
                    .apply { if (primaryActions) setFlags(Action.FLAG_PRIMARY) }
                    .setOnClickListener { DriveWatcher.checkNow(carContext) }
                    .build()
            )
        if (state is State.Found) {
            pane.addAction(
                Action.Builder().setTitle("Show details")
                    .setIcon(icon(R.drawable.ic_menu))
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

    /** Monochrome icon; the host picks a color that contrasts with its day or night theme. */
    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(CarColor.DEFAULT).build()
}
