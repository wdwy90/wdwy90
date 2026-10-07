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
    private const val KEY_THEME = "theme"
    private const val KEY_SYSTEM_NIGHT = "system_night_mode"
    private const val KEY_LAST_CHAIN = "last_detected_chain"
    private const val KEY_LAST_AT = "last_detected_at"
    /** Held a Google place name in early 1.8 builds; Google's terms don't allow storing those. */
    private const val KEY_LAST_NAME_REMOVED = "last_detected_name"

    const val THEME_SYSTEM = 0
    const val THEME_DARK = 1
    const val THEME_LIGHT = 2

    @Volatile private var autoDetect: MutableStateFlow<Boolean>? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_API, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.PLACES_API_KEY

    fun setApiKey(ctx: Context, key: String) =
        prefs(ctx).edit().putString(KEY_API, key.trim()).apply()

    /** True when the user saved their own key (it overrides the built-in one). */
    fun hasUserKey(ctx: Context): Boolean = !prefs(ctx).getString(KEY_API, null).isNullOrBlank()

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

    /** Phone theme. Dark by default (the app is mostly used in a car at any time of day). */
    fun theme(ctx: Context): Int = prefs(ctx).getInt(KEY_THEME, THEME_DARK)

    fun setTheme(ctx: Context, theme: Int) = prefs(ctx).edit().putInt(KEY_THEME, theme).apply()

    /** The UiModeManager night mode last handed to Android 12+ for this app, or -1. */
    fun systemNightMode(ctx: Context): Int = prefs(ctx).getInt(KEY_SYSTEM_NIGHT, -1)

    fun setSystemNightMode(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt(KEY_SYSTEM_NIGHT, mode).apply()

    /** The last real (not demo) detection: its chain, when it's one with an item list, and the time. */
    data class LastDetected(val chain: String?, val atMs: Long)

    /**
     * Only the chain name from the app's own data and the time are kept. Google's terms don't allow
     * storing place details such as names, so a place outside the chain list stays anonymous.
     */
    fun lastDetected(ctx: Context): LastDetected? {
        val p = prefs(ctx)
        if (p.contains(KEY_LAST_NAME_REMOVED)) p.edit().remove(KEY_LAST_NAME_REMOVED).apply()
        if (!p.contains(KEY_LAST_AT)) return null
        return LastDetected(p.getString(KEY_LAST_CHAIN, null)?.ifBlank { null }, p.getLong(KEY_LAST_AT, 0L))
    }

    fun setLastDetected(ctx: Context, chain: String?, atMs: Long) =
        prefs(ctx).edit().putString(KEY_LAST_CHAIN, chain.orEmpty()).putLong(KEY_LAST_AT, atMs).apply()
}
