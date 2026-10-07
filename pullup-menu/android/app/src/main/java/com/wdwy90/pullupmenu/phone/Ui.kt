package com.wdwy90.pullupmenu.phone

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wdwy90.pullupmenu.core.Prefs

/**
 * Base for the app's phone screens: applies the theme preference (System / Dark / Light) and draws
 * edge to edge with the content kept clear of the status and navigation bars.
 *
 * Dark and Light are drawn one of two ways. On Android 12+, once [syncSystemNightMode] has handed the
 * app's night mode to the system, the system draws them, and a theme change arrives as a
 * configuration change. Otherwise (Android 10 and 11, or until the system has it) the screen forces
 * them with an override configuration, and a theme change recreates it.
 *
 * The manifest sends UI mode changes to [onConfigurationChanged] (configChanges="uiMode") instead of
 * recreating the screen, so it's recreated only when light/dark really changes: not when the phone
 * enters car mode, nor when Android 12+ applies a night mode the screen already forces.
 */
abstract class ThemedActivity : ComponentActivity() {

    private var appliedTheme = -1
    /** True when this screen forces light/dark itself instead of taking it from the system. */
    private var forcedNight = false
    /** Light or dark as drawn: the [Configuration.UI_MODE_NIGHT_MASK] bits. */
    private var shownNight = Configuration.UI_MODE_NIGHT_UNDEFINED
    private var recreating = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        appliedTheme = Prefs.theme(newBase)
        val night = nightFor(appliedTheme) ?: return
        if (systemShowsTheme(newBase, appliedTheme)) {
            if ((newBase.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == night) return
            // Not drawn that way yet: the change is still on its way, or the system lost the app's
            // night mode. Force it here, and onCreate hands it to the system again.
            Prefs.setSystemNightMode(newBase, -1)
        }
        // Night bits only; the UI mode type (normal, car, ...) still follows the device.
        applyOverrideConfiguration(Configuration().apply { uiMode = night })
        forcedNight = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        shownNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        syncSystemNightMode(this)
    }

    override fun onResume() {
        super.onResume()
        when {
            // Theme changed on the Settings screen while this one was in the back stack.
            appliedTheme != Prefs.theme(this) -> followTheme()
            // Left to the system, which still hasn't drawn it: force it after all.
            !forcedNight && nightFor(appliedTheme).let { it != null && it != shownNight } -> recreateOnce()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A forced Dark/Light is part of newConfig, so only light/dark that the system draws changes here.
        if ((newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) != shownNight) recreateOnce()
    }

    /**
     * Shows the theme preference after it changed. When the system draws the new theme and this screen
     * doesn't force its own, light/dark arrives as a configuration change, and [onConfigurationChanged]
     * recreates the screen if it really changes; recreating now as well would race that change and
     * could recreate the screen twice. Otherwise the screen is recreated in the new theme.
     */
    protected fun followTheme() {
        val theme = Prefs.theme(this)
        if (!forcedNight && systemShowsTheme(this, theme)) appliedTheme = theme else recreateOnce()
    }

    /** Recreates the screen in the current theme (once, however many reasons arrive together). */
    private fun recreateOnce() {
        if (recreating) return
        recreating = true
        recreate()
    }

    /** Pads [view] by the system bars (and keyboard) on the given sides, on top of its own padding. */
    protected fun applyInsets(view: View, top: Boolean = true, bottom: Boolean = true) {
        val l = view.paddingLeft
        val r = view.paddingRight
        val t = view.paddingTop
        val b = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(
                l + bars.left,
                t + if (top) bars.top else 0,
                r + bars.right,
                b + if (bottom) bars.bottom else 0,
            )
            insets
        }
    }

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/**
 * Android 12+ keeps a night mode per app and also uses it for the splash screen, which is drawn
 * before any app code runs. Keeping it in step with the theme preference avoids a light splash before
 * a dark app (or the reverse), and lets the system draw Dark and Light ([ThemedActivity]). Android 10
 * and 11 have no such setting.
 */
@SuppressLint("WrongConstant") // mode is always one of the UiModeManager.MODE_NIGHT_ constants
fun syncSystemNightMode(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val mode = systemNightModeFor(Prefs.theme(context))
    if (Prefs.systemNightMode(context) == mode) return
    val uiModeManager = context.getSystemService(UiModeManager::class.java) ?: return
    try {
        uiModeManager.setApplicationNightMode(mode)
        Prefs.setSystemNightMode(context, mode)
    } catch (e: RuntimeException) {
        // Not saved, so it's tried again on the next screen.
    }
}

/** The [Configuration.UI_MODE_NIGHT_MASK] bits that draw [theme], or null for Match system. */
private fun nightFor(theme: Int): Int? = when (theme) {
    Prefs.THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
    Prefs.THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
    else -> null
}

/** The Android 12+ app night mode that draws [theme]. */
private fun systemNightModeFor(theme: Int): Int = when (theme) {
    Prefs.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
    Prefs.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
    else -> UiModeManager.MODE_NIGHT_AUTO // follow the system
}

/**
 * True when the system itself draws [theme]: Match system always (as far as the app knows), and Dark
 * or Light on Android 12+ once [syncSystemNightMode] has handed them over.
 */
private fun systemShowsTheme(context: Context, theme: Int): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Prefs.systemNightMode(context) == systemNightModeFor(theme)
    } else {
        theme == Prefs.THEME_SYSTEM
    }

/** False when the user turned animations off (Remove animations / animator duration scale 0). */
fun motionEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()
