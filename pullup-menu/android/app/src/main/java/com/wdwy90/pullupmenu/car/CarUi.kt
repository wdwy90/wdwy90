package com.wdwy90.pullupmenu.car

import android.text.SpannableString
import android.text.Spanned
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.OnClickListener
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import com.wdwy90.pullupmenu.core.CarModel

/** A car screen about one restaurant: it closes when that visit ends or another place is found. */
interface ShowsRestaurant {
    val restaurantId: String
}

/** Small helpers the car screens share. */
internal object CarUi {

    /** Lets tests and screenshot runs try a host with another list limit. */
    @VisibleForTesting
    var listLimitOverride: Int? = null

    /**
     * Rows a list may show on this host. Android Auto guarantees at least 6 (all that API level 1
     * hosts allow) and usually allows far more; the library takes 100 at most.
     */
    fun listLimit(ctx: CarContext): Int =
        listLimitOverride ?: limit(ctx, ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 6).coerceIn(6, 100)

    /** Rows a pane may show on this host (4 on API level 1 hosts). */
    fun paneLimit(ctx: CarContext): Int = limit(ctx, ConstraintManager.CONTENT_LIMIT_TYPE_PANE, 4).coerceAtLeast(1)

    /** The host's limit, or the API level 1 one if the host is too old to say or doesn't answer. */
    private fun limit(ctx: CarContext, type: Int, oldest: Int): Int =
        if (ctx.carAppApiLevel < CarAppApiLevels.LEVEL_2) oldest
        else try {
            ctx.getCarService(ConstraintManager::class.java).getContentLimit(type)
        } catch (e: RuntimeException) { // the host went away mid-call
            oldest
        }

    /** Primary buttons and pane images need car API 4. */
    fun level4(ctx: CarContext): Boolean = ctx.carAppApiLevel >= CarAppApiLevels.LEVEL_4

    /**
     * A monochrome icon. [CarColor.DEFAULT] lets the host pick a colour that contrasts with its
     * day or night background; [CarColor.PRIMARY] is the app's accent (androidx.car.app.theme),
     * which the host also only uses where it has enough contrast.
     */
    fun icon(ctx: CarContext, @DrawableRes res: Int, tint: CarColor = CarColor.DEFAULT): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(ctx, res)).setTint(tint).build()

    fun action(
        ctx: CarContext,
        title: String,
        @DrawableRes icon: Int,
        primary: Boolean = false,
        onClick: OnClickListener,
    ): Action = Action.Builder()
        .setTitle(title)
        .setIcon(icon(ctx, icon))
        .apply { if (primary && level4(ctx)) setFlags(Action.FLAG_PRIMARY) }
        .setOnClickListener(onClick)
        .build()

    /** A line of row text, with Google's open/closed answer in the host's standard green or red. */
    fun text(line: CarModel.Line): CharSequence {
        if (line.parts.all { it.tone == CarModel.Tone.PLAIN }) return line.text
        val s = SpannableString(line.text)
        var at = 0
        for (p in line.parts) {
            val color = when (p.tone) {
                CarModel.Tone.GOOD -> CarColor.GREEN
                CarModel.Tone.BAD -> CarColor.RED
                CarModel.Tone.PLAIN -> null
            }
            if (color != null) s.setSpan(ForegroundCarColorSpan.create(color), at, at + p.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            at += p.text.length
        }
        return s
    }
}
