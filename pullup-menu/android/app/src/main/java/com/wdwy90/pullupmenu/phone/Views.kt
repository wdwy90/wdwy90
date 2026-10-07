package com.wdwy90.pullupmenu.phone

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.wdwy90.pullupmenu.R

/** A frame as tall as it is wide (photo grid tiles). */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/** Material "emphasized" easing. */
val EASE: PathInterpolator get() = PathInterpolator(0.2f, 0f, 0f, 1f)

/**
 * The segmented Items / Menu / Photos bar ([R.layout.view_tabs]). The indicator slides behind the
 * selected tab, or jumps there when animations are off.
 */
class TabBar(private val track: View, onSelect: (Int) -> Unit) {
    private val indicator: View = track.findViewById(R.id.tab_indicator)
    private val tabs: List<TextView> =
        listOf(R.id.tab_items, R.id.tab_menu, R.id.tab_photos).map { track.findViewById(it) }
    private var selected = -1

    init {
        tabs.forEachIndexed { i, tab -> tab.setOnClickListener { onSelect(i) } }
        // Tabs are laid out after the first select(), and again on rotation.
        track.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) track.post { place(animate = false) }
        }
    }

    fun select(index: Int, animate: Boolean) {
        selected = index
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }
        place(animate)
    }

    private fun place(animate: Boolean) {
        val tab = tabs.getOrNull(selected) ?: return
        if (tab.width == 0) return
        val inset = (indicator.layoutParams as ViewGroup.MarginLayoutParams).leftMargin
        val width = tab.width - 2 * inset
        if (indicator.layoutParams.width != width) {
            indicator.layoutParams = indicator.layoutParams.apply { this.width = width }
        }
        // The indicator sits at the track's left edge (plus its margin); tab.left is relative to the
        // row of tabs, which fills the track, so this works for right-to-left layouts too.
        val x = tab.left.toFloat()
        indicator.animate().cancel()
        if (animate && motionEnabled() && indicator.isLaidOut) {
            indicator.animate().translationX(x).setDuration(260).setInterpolator(EASE).start()
        } else {
            indicator.translationX = x
        }
    }
}

/** A gentle "loading" pulse, or nothing when animations are off. Cancel it and reset alpha when done. */
fun View.loadingPulse(): ValueAnimator? {
    if (!motionEnabled()) return null
    return ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.55f).apply {
        duration = 750
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        start()
    }
}

/** Fades a freshly loaded image in (instantly when animations are off). */
fun View.fadeIn() {
    visibility = View.VISIBLE
    if (!motionEnabled()) {
        alpha = 1f
        return
    }
    alpha = 0f
    animate().alpha(1f).setDuration(220).start()
}

/** This view's top edge in [ancestor]'s coordinates (ignoring scroll and translation). */
fun View.topIn(ancestor: View): Int {
    var y = 0
    var v: View = this
    while (v !== ancestor) {
        y += v.top
        v = v.parent as? View ?: break
    }
    return y
}
