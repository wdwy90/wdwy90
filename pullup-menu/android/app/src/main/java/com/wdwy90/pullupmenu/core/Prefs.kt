package com.wdwy90.pullupmenu.core

import android.content.Context
import android.content.SharedPreferences
import com.wdwy90.pullupmenu.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Prefs {
    private const val FILE = "settings"
    private const val KEY_API = "places_api_key"
    private const val KEY_RADIUS = "search_radius_m"
    private const val KEY_AUTO_DETECT = "auto_detect"
    private const val KEY_CAR_BANNER = "car_banner"

    @Volatile private var autoDetect: MutableStateFlow<Boolean>? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_API, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.PLACES_API_KEY

    fun setApiKey(ctx: Context, key: String) =
        prefs(ctx).edit().putString(KEY_API, key.trim()).apply()

    /** True when the build has a Places key baked in, so the app doesn't ask for one. */
    fun hasBuiltInKey(): Boolean = BuildConfig.PLACES_API_KEY.isNotBlank()

    /** How close (meters) the restaurant must be; covers a drive-thru lane wrapping the building. */
    fun radiusMeters(ctx: Context): Double =
        prefs(ctx).getInt(KEY_RADIUS, 45).toDouble()

    /** "Auto-detect drive-thrus" switch. Off by default: only manual checks. */
    fun autoDetect(ctx: Context): Boolean = autoDetectState(ctx).value

    fun setAutoDetect(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_DETECT, on).apply()
        autoDetectState(ctx).value = on
    }

    fun autoDetectFlow(ctx: Context): StateFlow<Boolean> = autoDetectState(ctx)

    private fun autoDetectState(ctx: Context): MutableStateFlow<Boolean> =
        autoDetect ?: synchronized(this) {
            autoDetect ?: MutableStateFlow(prefs(ctx).getBoolean(KEY_AUTO_DETECT, false))
                .also { autoDetect = it }
        }

    /** "Car banner (experimental)": show the arrival notification on the car screen. */
    fun carBanner(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CAR_BANNER, true)

    fun setCarBanner(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_CAR_BANNER, on).apply()
}
