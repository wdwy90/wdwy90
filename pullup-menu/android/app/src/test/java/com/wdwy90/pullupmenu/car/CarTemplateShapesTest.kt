package com.wdwy90.pullupmenu.car

import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.core.graphics.drawable.IconCompat
import org.junit.Test

/**
 * The car library checks templates against Android Auto's rules when they are built, and a
 * violation crashes the car screen. These build the same shapes the car screens use (icon rows,
 * icon + title actions, a primary action), so a rule the screens break fails here first.
 */
class CarTemplateShapesTest {

    private fun icon(): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(null, "com.wdwy90.pullupmenu", 0x7f080001))
            .setTint(CarColor.DEFAULT)
            .build()

    private fun action(title: String, primary: Boolean = false) = Action.Builder()
        .setTitle(title)
        .setIcon(icon())
        .apply { if (primary) setFlags(Action.FLAG_PRIMARY) }
        .build()

    @Test fun homePane() {
        val row = Row.Builder().setTitle("Watching for drive-thrus")
            .setImage(icon(), Row.IMAGE_TYPE_ICON)
            .addText("Pull into a fast-food drive-thru lane and the restaurant card appears.")
            .build()
        val pane = Pane.Builder()
            .addRow(row)
            .addAction(action("Check now", primary = true))
            .addAction(action("Show details"))
            .build()
        PaneTemplate.Builder(pane).setTitle("Pull Up Menu").setHeaderAction(Action.APP_ICON).build()
    }

    @Test fun restaurantPane() {
        val info = Row.Builder().setTitle("★ 4.2 · Fast food restaurant")
            .setImage(icon(), Row.IMAGE_TYPE_ICON)
            .addText("123 Main St")
            .build()
        val credit = Row.Builder().setTitle("Info from Google Maps").addText("Photo by Someone").build()
        val pane = Pane.Builder()
            .addRow(info)
            .addRow(credit)
            .addAction(action("Items", primary = true))
            .addAction(action("Menu on phone"))
            .build()
        PaneTemplate.Builder(pane).setTitle("Burger King").setHeaderAction(Action.BACK).build()
    }
}
