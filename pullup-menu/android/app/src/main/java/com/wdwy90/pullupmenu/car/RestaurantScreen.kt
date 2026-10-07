package com.wdwy90.pullupmenu.car

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.CarModel
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.MenuRepository.State
import com.wdwy90.pullupmenu.core.PriceList
import com.wdwy90.pullupmenu.core.Restaurant
import com.wdwy90.pullupmenu.phone.RestaurantActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The restaurant card, so the driver can tell at a glance that this is the right place: its name,
 * where it is and whether Google says it's open, its rating and type with the Google credit, one
 * storefront photo when Google has one, and whether its menu is here. "View menu" opens the
 * categories ([MenuScreen]); "Not here?" lists the other places close by.
 */
class RestaurantScreen(ctx: CarContext, restaurant: Restaurant) : Screen(ctx), ShowsRestaurant {
    override val restaurantId: String = restaurant.id

    /** Kept up to date with Google's latest open/closed answer while the card is up. */
    private var restaurant = restaurant
    private var checkedMs: Long? = null
    private var others: List<Restaurant> = emptyList()

    /** Pane images need car API 4; below that the photo isn't even fetched. */
    private val photo = restaurant.photos.firstOrNull()?.takeIf { CarUi.level4(ctx) && !restaurant.isDemo }
    private var image: CarIcon? = null

    /** A spinner for a moment while the photo loads, so the card doesn't jump right after it opens. */
    private var loading = photo != null

    init {
        lifecycleScope.launch { MenuRepository.state.collect { follow(it) } }
        val p = photo
        if (p != null) {
            lifecycleScope.launch {
                // The fetch is blocking I/O, so wait on it with a timeout rather than wrapping it.
                val fetch = async { fetchPhoto(p.name) }
                val early = withTimeoutOrNull(PHOTO_WAIT_MS) { fetch.await() }
                image = early?.let { carIcon(it) }
                loading = false
                invalidate() // loading -> content counts as a refresh
                if (early == null) {
                    // A photo that comes later is added in place: the rows stay as they are.
                    fetch.await()?.let { late ->
                        image = carIcon(late)
                        invalidate()
                    }
                }
            }
        }
    }

    /** Takes in a newer answer from Google for this place (a background check while it's on screen). */
    private fun follow(s: State) {
        val found = s as? State.Found ?: return
        val all = listOf(found.restaurant) + found.others
        val now = all.firstOrNull { it.id == restaurantId } ?: return
        val fresh = restaurant.copy(openNow = now.openNow, businessStatus = now.businessStatus)
        val nearby = all.filter { it.id != restaurantId }
        if (fresh == restaurant && found.checkedMs == checkedMs && nearby == others) return
        restaurant = fresh
        checkedMs = found.checkedMs
        others = nearby
        invalidate()
        // Google's answer goes stale: take the open/closed line down when it does.
        val shownUntil = found.checkedMs + CarModel.STATUS_FRESH_MS
        lifecycleScope.launch {
            delay((shownUntil - System.currentTimeMillis()).coerceAtLeast(0) + 1_000)
            if (checkedMs == found.checkedMs) invalidate()
        }
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        if (loading) {
            pane.setLoading(true) // a loading pane has no rows
        } else {
            val rows = CarModel.card(
                restaurant, checkedMs, System.currentTimeMillis(),
                showDistance = others.isNotEmpty(),
                photoShown = image != null,
                photoAuthor = photo?.authorName,
            )
            rows.take(CarUi.paneLimit(carContext)).forEach { pane.addRow(row(it)) }
            image?.let { pane.setImage(it) }
        }
        val prices = restaurant.prices?.takeIf { it.items.isNotEmpty() }
        if (prices != null) {
            pane.addAction(CarUi.action(carContext, "View menu", R.drawable.ic_menu_book, primary = true) { openMenu(prices) })
        }
        pane.addAction(
            Action.Builder().setTitle("Open on phone")
                .setIcon(CarUi.icon(carContext, R.drawable.ic_open_in_new))
                .apply { if (prices == null && CarUi.level4(carContext)) setFlags(Action.FLAG_PRIMARY) }
                .setOnClickListener(ParkedOnlyOnClickListener.create { openOnPhone() })
                .build()
        )

        val template = PaneTemplate.Builder(pane.build())
            .setTitle(restaurant.name.ifBlank { "Restaurant" })
            .setHeaderAction(Action.BACK)
        if (others.isNotEmpty()) {
            template.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder().setTitle("Not here?")
                            .setOnClickListener { screenManager.push(ChooserScreen(carContext, restaurantId, others)) }
                            .build()
                    )
                    .build()
            )
        }
        return template.build()
    }

    private fun row(r: CarModel.CardRow): Row {
        val icon = when (r.icon) {
            CarModel.Icon.PLACE -> CarUi.icon(carContext, R.drawable.ic_place, CarColor.PRIMARY)
            CarModel.Icon.STAR -> CarUi.icon(carContext, R.drawable.ic_star, CarColor.YELLOW)
            CarModel.Icon.RESTAURANT -> CarUi.icon(carContext, R.drawable.ic_restaurant, CarColor.PRIMARY)
            CarModel.Icon.MENU -> CarUi.icon(carContext, R.drawable.ic_menu_book, CarColor.PRIMARY)
            CarModel.Icon.NO_MENU -> CarUi.icon(carContext, R.drawable.ic_menu_book)
        }
        return Row.Builder().setTitle(r.title)
            .setImage(icon, Row.IMAGE_TYPE_ICON)
            .apply { r.lines.forEach { addText(CarUi.text(it)) } }
            .build()
    }

    /**
     * Android Auto allows five screens per task, and only a pane-type one (like this card) as the
     * fifth. The menu goes up to three lists deep, so it takes the card's place rather than going on
     * top of it: Home and three lists make four. Back from the categories brings the card back.
     */
    private fun openMenu(prices: PriceList) {
        screenManager.popToRoot()
        screenManager.push(MenuScreen(carContext, restaurant, prices))
    }

    private fun carIcon(bmp: Bitmap): CarIcon = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()

    private suspend fun fetchPhoto(name: String): Bitmap? =
        try {
            MenuRepository.photo(carContext, name, PHOTO_PX)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** Runs only while parked (ParkedOnlyOnClickListener). The toast is shown even if the launch is blocked. */
    private fun openOnPhone() {
        try {
            carContext.startActivity(
                Intent(carContext, RestaurantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
            )
        } catch (e: Exception) { // ActivityNotFoundException, SecurityException
        }
        CarToast.makeText(carContext, "Opened on your phone. ${CarModel.PHONE_WHEN_PARKED}", CarToast.LENGTH_LONG).show()
    }

    private companion object {
        const val PHOTO_WAIT_MS = 1_500L
        const val PHOTO_PX = 480
    }
}
