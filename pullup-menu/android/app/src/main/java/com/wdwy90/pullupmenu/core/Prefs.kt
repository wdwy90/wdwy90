package com.wdwy90.pullupmenu.core

import android.content.Context
import com.wdwy90.pullupmenu.BuildConfig

object Prefs {
    private const val FILE = "settings"
    private const val KEY_API = "places_api_key"
    private const val KEY_RADIUS = "search_radius_m"

    fun apiKey(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_API, null)
            ?.takeIf { it.isNotBlank() } ?: BuildConfig.PLACES_API_KEY

    fun setApiKey(ctx: Context, key: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_API, key.trim()).apply()

    /** How close (meters) the restaurant must be; covers a drive-thru lane wrapping the building. */
    fun radiusMeters(ctx: Context): Double =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_RADIUS, 60).toDouble()
}
