package com.wdwy90.pullupmenu.car

import android.graphics.Bitmap
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.Restaurant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Car screen: restaurant name + a grid of its food/menu photos. */
class RestaurantScreen(ctx: CarContext, private val restaurant: Restaurant) : Screen(ctx) {
    private var photos: List<Bitmap>? = null

    private val maxItems: Int =
        if (ctx.carAppApiLevel >= 2) {
            ctx.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        } else 6

    init {
        lifecycleScope.launch {
            val names = restaurant.photoNames.take(maxItems)
            photos = withTimeoutOrNull(15_000) {
                names.map { n -> async { MenuRepository.photo(carContext, n, 480) } }
                    .awaitAll().filterNotNull()
            } ?: emptyList()
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val title = restaurant.name
        val subtitle = listOfNotNull(
            restaurant.category,
            restaurant.rating?.let { "★ %.1f".format(it) },
        ).joinToString(" · ")

        val strip = ActionStrip.Builder().apply {
            if (otherNearby().isNotEmpty()) {
                addAction(
                    Action.Builder().setTitle("Not here?")
                        .setOnClickListener {
                            screenManager.push(ChooserScreen(carContext, otherNearby()))
                        }.build()
                )
            }
        }

        val loaded = photos
        if (loaded != null && loaded.isEmpty()) {
            return MessageTemplate.Builder(
                "No photos found for $title. The menu link is on your phone." +
                    if (subtitle.isNotEmpty()) "\n$subtitle" else ""
            ).setTitle(title).setHeaderAction(Action.BACK).build()
        }

        val grid = GridTemplate.Builder()
            .setTitle(if (subtitle.isEmpty()) title else "$title  ·  $subtitle")
            .setHeaderAction(Action.BACK)
        if (otherNearby().isNotEmpty()) grid.setActionStrip(strip.build())

        if (loaded == null) {
            grid.setLoading(true)
        } else {
            val items = ItemList.Builder()
            loaded.forEachIndexed { i, bmp ->
                items.addItem(
                    GridItem.Builder()
                        .setTitle("Photo ${i + 1}")
                        .setImage(
                            CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build(),
                            GridItem.IMAGE_TYPE_LARGE,
                        )
                        .build()
                )
            }
            grid.setSingleList(items.build())
        }
        return grid.build()
    }

    private fun otherNearby(): List<Restaurant> =
        (MenuRepository.state.value as? MenuRepository.State.Found)
            ?.let { s -> (listOf(s.restaurant) + s.others).filter { it.id != restaurant.id } }
            .orEmpty()
}
