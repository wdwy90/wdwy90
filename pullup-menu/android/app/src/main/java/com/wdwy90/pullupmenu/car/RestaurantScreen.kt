package com.wdwy90.pullupmenu.car

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.Restaurant
import com.wdwy90.pullupmenu.phone.RestaurantActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Car screen: the restaurant card. Name, rating/category, address, at most one photo and the
 * Google attribution. "Items" opens a short item list; the full menu is on the phone.
 */
class RestaurantScreen(ctx: CarContext, private val restaurant: Restaurant) : Screen(ctx) {
    val restaurantId: String get() = restaurant.id

    private val apiLevel = ctx.carAppApiLevel

    /** Pane images need car API 4; below that we don't even fetch the photo. */
    private val photo = restaurant.photos.firstOrNull()?.takeIf { apiLevel >= CarAppApiLevels.LEVEL_4 }
    private var image: CarIcon? = null
    private var loading = photo != null

    private val maxRows: Int by lazy {
        if (apiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
        } else 4
    }

    init {
        val p = photo
        if (p != null) {
            lifecycleScope.launch {
                // The fetch is blocking I/O, so wait on it with a timeout rather than wrapping it:
                // that way the card shows after at most 4 s even if the download is still going.
                val fetch = async { fetchPhoto(p.name) }
                val bmp = withTimeoutOrNull(4_000) { fetch.await() }
                if (bmp == null) fetch.cancel()
                image = bmp?.let { CarIcon.Builder(IconCompat.createWithBitmap(it)).build() }
                loading = false
                invalidate() // loading -> content is a refresh; the card is never refreshed again for a late photo
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        if (loading) {
            pane.setLoading(true) // a loading pane must have no rows
        } else {
            rows().take(maxRows.coerceAtLeast(1)).forEach { pane.addRow(it) }
            image?.let { pane.setImage(it) } // only set when apiLevel >= 4
        }
        restaurant.prices?.let { prices ->
            pane.addAction(
                Action.Builder().setTitle("Items")
                    .setIcon(icon(R.drawable.ic_menu))
                    .apply { if (apiLevel >= CarAppApiLevels.LEVEL_4) setFlags(Action.FLAG_PRIMARY) }
                    .setOnClickListener { screenManager.push(ItemListScreen(carContext, prices)) }
                    .build()
            )
        }
        pane.addAction(
            Action.Builder().setTitle("Menu on phone")
                .setIcon(icon(R.drawable.ic_open_in_new))
                .setOnClickListener(ParkedOnlyOnClickListener.create { openMenuOnPhone() })
                .build()
        )

        val template = PaneTemplate.Builder(pane.build())
            .setTitle(restaurant.name.ifBlank { "Restaurant" })
            .setHeaderAction(Action.BACK)
        val others = otherNearby()
        if (others.isNotEmpty()) {
            template.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder().setTitle("Not here?")
                            .setOnClickListener { screenManager.push(ChooserScreen(carContext, others)) }
                            .build()
                    )
                    .build()
            )
        }
        return template.build()
    }

    private fun rows(): List<Row> {
        val detail = listOfNotNull(
            restaurant.rating?.let { "★ %.1f".format(it) },
            restaurant.category?.ifBlank { null } ?: "Fast food",
            if (restaurant.isDemo) "Demo" else null,
        ).joinToString(" · ")
        val info = Row.Builder().setTitle(detail)
            .setImage(icon(if (restaurant.rating != null) R.drawable.ic_star else R.drawable.ic_restaurant), Row.IMAGE_TYPE_ICON)
            .apply { if (restaurant.address.isNotBlank()) addText(restaurant.address) }
            .build()
        val credit = Row.Builder().setTitle(if (restaurant.isDemo) "Sample data" else "Info from Google Maps").apply {
            val author = photo?.authorName
            if (image != null && !author.isNullOrBlank()) addText("Photo by $author")
        }.build()
        return listOf(info, credit)
    }

    /** Monochrome icon; the host picks a color that contrasts with its day or night theme. */
    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(CarColor.DEFAULT).build()

    private suspend fun fetchPhoto(name: String): Bitmap? =
        try {
            MenuRepository.photo(carContext, name, 480)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** Runs only while parked (ParkedOnlyOnClickListener). The toast is shown even if the launch is blocked. */
    private fun openMenuOnPhone() {
        try {
            carContext.startActivity(
                Intent(carContext, RestaurantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
            )
        } catch (e: Exception) { // ActivityNotFoundException, SecurityException
        }
        CarToast.makeText(carContext, "Menu is on your phone. Look at it only when parked.", CarToast.LENGTH_LONG)
            .show()
    }

    private fun otherNearby(): List<Restaurant> =
        (MenuRepository.state.value as? MenuRepository.State.Found)
            ?.let { s -> (listOf(s.restaurant) + s.others).filter { it.id != restaurant.id } }
            .orEmpty()
}
