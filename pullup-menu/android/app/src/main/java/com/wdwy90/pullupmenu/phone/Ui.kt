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
 * The manifest sends UI mode changes to [onConfigurationChanged] (configChanges="uiMode") instead of
 * recreating the screen, so it's recreated only when light/dark really changes: not when the phone
 * enters car mode, nor when Android 12+ applies the app's own night mode ([syncSystemNightMode]).
 */
abstract class ThemedActivity : ComponentActivity() {

    private var appliedTheme = -1
    /** Light or dark as drawn: the [Configuration.UI_MODE_NIGHT_MASK] bits. */
    private var shownNight = Configuration.UI_MODE_NIGHT_UNDEFINED
    private var recreating = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        appliedTheme = Prefs.theme(newBase)
        val night = when (appliedTheme) {
            Prefs.THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            Prefs.THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            else -> return
        }
        // Night bits only; the UI mode type (normal, car, ...) still follows the device.
        applyOverrideConfiguration(Configuration().apply { uiMode = night })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        shownNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        syncSystemNightMode(this)
    }

    override fun onResume() {
        super.onResume()
        // Theme changed on the Settings screen while this one was in the back stack.
        if (appliedTheme != Prefs.theme(this)) recreateOnce()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // newConfig includes the Dark/Light override, so this only fires on "Match system".
        if ((newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) != shownNight) recreateOnce()
    }

    /** Recreates the screen in the current theme (once, however many reasons arrive together). */
    protected fun recreateOnce() {
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
 * a dark app (or the reverse). Android 10 and 11 have no such setting.
 */
@SuppressLint("WrongConstant") // mode is always one of the UiModeManager.MODE_NIGHT_ constants below
fun syncSystemNightMode(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val mode = when (Prefs.theme(context)) {
        Prefs.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
        Prefs.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
        else -> UiModeManager.MODE_NIGHT_AUTO // follow the system
    }
    if (Prefs.systemNightMode(context) == mode) return
    val uiModeManager = context.getSystemService(UiModeManager::class.java) ?: return
    try {
        uiModeManager.setApplicationNightMode(mode)
        Prefs.setSystemNightMode(context, mode)
    } catch (e: RuntimeException) {
        // Not saved, so it's tried again on the next screen.
    }
}

/** False when the user turned animations off (Remove animations / animator duration scale 0). */
fun motionEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()
