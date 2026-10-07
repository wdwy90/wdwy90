package com.wdwy90.pullupmenu.phone

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
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
 */
abstract class ThemedActivity : ComponentActivity() {

    private var appliedTheme = -1

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        appliedTheme = Prefs.theme(newBase)
        val night = when (appliedTheme) {
            Prefs.THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            Prefs.THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            else -> return
        }
        val base = newBase.resources.configuration.uiMode
        applyOverrideConfiguration(Configuration().apply {
            uiMode = (base and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // Theme changed on the Settings screen while this one was in the back stack.
        if (appliedTheme != Prefs.theme(this)) recreate()
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

/** False when the user turned animations off (Remove animations / animator duration scale 0). */
fun motionEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()
