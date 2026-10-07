package com.wdwy90.pullupmenu.phone

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.wdwy90.pullupmenu.R
import kotlin.math.abs

/** A frame as tall as it is wide (photo grid tiles). */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/**
 * A bar that floats over a scroll view (the restaurant screen's tab bar). Taps go to its own
 * children as usual, but a vertical drag that starts on it is handed to [scrollTarget], so the
 * content scrolls as if the bar were part of it. Touches never fall through to the content below.
 */
class ScrollForwardingLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** A sibling in the same parent, which gets the drags. */
    var scrollTarget: View? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    /** Where the gesture started, in the parent's coordinates: the bar itself moves as the content scrolls. */
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var forwarding = false
    /** A child (the chip row) claimed the gesture, e.g. for a sideways scroll. */
    private var childClaimed = false

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        childClaimed = disallowIntercept
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = track(ev)

    @SuppressLint("ClickableViewAccessibility") // not clickable: it only passes drags on to the content
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val wasForwarding = forwarding
        track(ev)
        if (wasForwarding) forward(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            forwarding = false
        }
        return true // the empty parts of the bar still block the content underneath
    }

    /** Watches the gesture; returns true once it has become a vertical drag handed to the target. */
    private fun track(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x + left + translationX
                downY = ev.y + top + translationY
                downTime = ev.downTime
                forwarding = false
                childClaimed = false
            }
            MotionEvent.ACTION_MOVE -> if (!forwarding && !childClaimed && targetVisible()) {
                val dx = abs(ev.x + left + translationX - downX)
                val dy = abs(ev.y + top + translationY - downY)
                if (dy > slop && dy > dx) {
                    forwarding = true
                    // Replay the press where it started, so the target sees the whole drag.
                    pressAtStart(ev)?.let { down ->
                        scrollTarget?.dispatchTouchEvent(down)
                        down.recycle()
                    }
                    forward(ev)
                }
            }
        }
        return forwarding
    }

    private fun targetVisible() = scrollTarget?.visibility == View.VISIBLE

    /** An ACTION_DOWN for [ev]'s pointer where the gesture started, in the target's coordinates. */
    private fun pressAtStart(ev: MotionEvent): MotionEvent? {
        val target = scrollTarget ?: return null
        val pointer = MotionEvent.PointerProperties().also { ev.getPointerProperties(0, it) }
        val coords = MotionEvent.PointerCoords().also { ev.getPointerCoords(0, it) }
        coords.x = downX - target.left - target.translationX
        coords.y = downY - target.top - target.translationY
        return MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 1, arrayOf(pointer), arrayOf(coords),
            ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision, ev.deviceId, ev.edgeFlags,
            ev.source, ev.flags,
        )
    }

    /** Passes one of this bar's events on to the target, moved into the target's coordinates. */
    private fun forward(ev: MotionEvent) {
        val target = scrollTarget ?: return
        if (!targetVisible()) return
        val e = MotionEvent.obtain(ev)
        e.offsetLocation(
            left + translationX - target.left - target.translationX,
            top + translationY - target.top - target.translationY,
        )
        target.dispatchTouchEvent(e)
        e.recycle()
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
