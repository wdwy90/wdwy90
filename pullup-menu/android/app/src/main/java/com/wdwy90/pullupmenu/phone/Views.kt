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
import kotlin.math.roundToInt

/** A frame as tall as it is wide (photo grid tiles). */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/**
 * Lays its children out left to right and starts a new line whenever the next one doesn't fit, so
 * on small phones or with large text a button or a value moves down a line instead of being
 * squeezed, cut off or broken mid-word. With `flowSpread`, the children on a line are pushed to its
 * two ends (a label at the start, its value at the end). `flowGap` and `flowLineGap` space the
 * children; their own margins are ignored.
 */
class FlowRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    private val gap: Int
    private val lineGap: Int
    private val spread: Boolean

    // Not a scrolling container: show a press on a child right away.
    override fun shouldDelayChildPressedState() = false

    init {
        val default = (8 * resources.displayMetrics.density).roundToInt()
        val a = context.obtainStyledAttributes(attrs, R.styleable.FlowRow)
        gap = a.getDimensionPixelSize(R.styleable.FlowRow_flowGap, default)
        lineGap = a.getDimensionPixelSize(R.styleable.FlowRow_flowLineGap, default)
        spread = a.getBoolean(R.styleable.FlowRow_flowSpread, false)
        a.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // A wrap_content child is measured at most as wide as the row: longer text wraps inside it.
            if (child.visibility != GONE) measureChild(child, widthMeasureSpec, heightMeasureSpec)
        }
        val room = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        }
        var widest = 0
        var height = 0
        var lines = 0
        forEachLine(room) { _, _, _, lineWidth, tallest ->
            widest = maxOf(widest, lineWidth)
            height += tallest
            lines++
        }
        height += lineGap * (lines - 1).coerceAtLeast(0)
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // The same lines as measured: this width holds the widest of them.
        val room = width - paddingLeft - paddingRight
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var y = paddingTop
        forEachLine(room) { start, end, count, lineWidth, tallest ->
            val extra = if (spread && count > 1) (room - lineWidth).coerceAtLeast(0) / (count - 1) else 0
            var x = 0
            var placed = 0
            for (i in start until end) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                placed++
                // On a spread line the last child ends right at the end (no rounding left over).
                if (extra > 0 && placed == count) x = room - child.measuredWidth
                val left = if (rtl) paddingLeft + room - x - child.measuredWidth else paddingLeft + x
                val top = y + (tallest - child.measuredHeight) / 2
                child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
                x += child.measuredWidth + gap + extra
            }
            y += tallest + lineGap
        }
    }

    /**
     * Breaks the shown children, as measured, into lines at most [room] px wide (at least one child
     * on each) and calls [action] for each line with its children's indexes ([start] up to [end],
     * gone ones included), how many of them are shown, and its width and height. Inline and without
     * collections, so measuring and laying out allocate nothing.
     */
    private inline fun forEachLine(
        room: Int,
        action: (start: Int, end: Int, count: Int, lineWidth: Int, tallest: Int) -> Unit,
    ) {
        var start = 0
        var count = 0
        var used = 0
        var tallest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (count > 0 && used + gap + child.measuredWidth > room) {
                action(start, i, count, used, tallest)
                count = 0
            }
            if (count == 0) {
                start = i
                used = child.measuredWidth
                tallest = child.measuredHeight
            } else {
                used += gap + child.measuredWidth
                tallest = maxOf(tallest, child.measuredHeight)
            }
            count++
        }
        if (count > 0) action(start, childCount, count, used, tallest)
    }
}

/**
 * Equal columns (`android:columnCount` across, `gridGap` apart) with the cells of a row stretched to
 * the same height. Drops to one column when a word in a cell wouldn't fit its column (small phones,
 * large text), so text is never broken mid-word. For a parent that limits its width; the cells'
 * own margins are ignored.
 */
class ColumnGrid @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    private val columns: Int
    private val gap: Int

    /** Columns in the current layout: the set number, or 1 when a word wouldn't fit. */
    var columnsShown: Int
        private set

    // Not a scrolling container: show a press on a child right away.
    override fun shouldDelayChildPressedState() = false

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.ColumnGrid)
        columns = a.getInt(R.styleable.ColumnGrid_android_columnCount, 2).coerceAtLeast(1)
        gap = a.getDimensionPixelSize(R.styleable.ColumnGrid_gridGap, (12 * resources.displayMetrics.density).roundToInt())
        a.recycle()
        columnsShown = columns
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val room = (available - paddingLeft - paddingRight).coerceAtLeast(0)
        columnsShown = if (columns > 1 && aWordBreaksAt(columnWidth(room, columns))) 1 else columns
        val cellSpec = MeasureSpec.makeMeasureSpec(columnWidth(room, columnsShown), MeasureSpec.EXACTLY)
        var height = paddingTop + paddingBottom
        var rows = 0
        forEachRow { start, end ->
            var tallest = 0
            for (i in start until end) {
                val cell = getChildAt(i)
                if (cell.visibility == GONE) continue
                cell.measure(cellSpec, ANY_HEIGHT)
                tallest = maxOf(tallest, cell.measuredHeight)
            }
            for (i in start until end) {
                val cell = getChildAt(i)
                if (cell.visibility != GONE && cell.measuredHeight != tallest) {
                    cell.measure(cellSpec, MeasureSpec.makeMeasureSpec(tallest, MeasureSpec.EXACTLY))
                }
            }
            height += tallest
            rows++
        }
        height += gap * (rows - 1).coerceAtLeast(0)
        setMeasuredDimension(resolveSize(available, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cellWidth = columnWidth(width - paddingLeft - paddingRight, columnsShown)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var y = paddingTop
        forEachRow { start, end ->
            var column = 0
            var tallest = 0
            for (i in start until end) {
                val cell = getChildAt(i)
                if (cell.visibility == GONE) continue
                val x = column++ * (cellWidth + gap)
                val left = if (rtl) width - paddingRight - x - cellWidth else paddingLeft + x
                cell.layout(left, y, left + cell.measuredWidth, y + cell.measuredHeight)
                tallest = maxOf(tallest, cell.measuredHeight)
            }
            y += tallest + gap
        }
    }

    /** Calls [action] for each row of [columnsShown] shown cells with their indexes, [start] up to [end] (gone ones included). */
    private inline fun forEachRow(action: (start: Int, end: Int) -> Unit) {
        var start = 0
        var count = 0
        for (i in 0 until childCount) {
            if (getChildAt(i).visibility == GONE) continue
            if (count == 0) start = i
            if (++count == columnsShown) {
                action(start, i + 1)
                count = 0
            }
        }
        if (count > 0) action(start, childCount)
    }

    private fun columnWidth(room: Int, n: Int) = ((room - gap * (n - 1)) / n).coerceAtLeast(0)

    /** True if a shown cell, [cellWidth] px wide, would break a word across lines. */
    private fun aWordBreaksAt(cellWidth: Int): Boolean {
        val spec = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val cell = getChildAt(i)
            if (cell.visibility == GONE) continue
            // A cell that hasn't asked for layout may take its size from Android's measure cache and
            // keep its text laid out for another width: lay it out at this one before reading it.
            cell.forceRemeasure()
            cell.measure(spec, ANY_HEIGHT)
            if (cell.breaksAWord()) return true
        }
        return false
    }
}

private val ANY_HEIGHT = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

/** Makes this view, and the views shown inside it, measure for real next time instead of from the cache. */
private fun View.forceRemeasure() {
    forceLayout()
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.GONE) child.forceRemeasure()
        }
    }
}

/** True if this view's text (or text inside it), as last measured, breaks a word across two lines. */
fun View.breaksAWord(): Boolean {
    if (visibility == View.GONE) return false
    if (this is TextView) {
        val layout = layout ?: return false
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) return true
        }
    } else if (this is ViewGroup) {
        for (i in 0 until childCount) if (getChildAt(i).breaksAWord()) return true
    }
    return false
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
