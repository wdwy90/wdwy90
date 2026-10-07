package com.wdwy90.pullupmenu.phone

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.AbsListView
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil

/**
 * What a person would see as broken on a laid-out screen: a view pushed past its parent's edge,
 * text cut off, shortened with "…" or broken in the middle of a word, text running into other
 * text, a button label squeezed onto a second line and tap targets under 48 dp. A scrolling
 * container's content may run past it in the direction it scrolls, and slide under a bar that
 * floats over it.
 */
internal object LayoutAudit {

    /**
     * @param wrappingButtons centered button labels may take two lines (the largest text sizes).
     *   Start-aligned ones (links) always may.
     * @param mayShorten ids of views that shorten long text with "…" by design.
     */
    fun problems(root: View, wrappingButtons: Boolean = false, mayShorten: Set<Int> = emptySet()): List<String> {
        val audit = Audit(wrappingButtons, mayShorten, minTap = 48 * root.resources.displayMetrics.density - 1)
        audit.visit(root)
        for (i in audit.texts.indices) for (j in i + 1 until audit.texts.size) {
            val (a, boxA) = audit.texts[i]
            val (b, boxB) = audit.texts[j]
            if (scroller(a) === scroller(b) && Rect.intersects(boxA, boxB)) audit.found += "${name(a)} runs into ${name(b)}"
        }
        return audit.found
    }

    private class Audit(val wrappingButtons: Boolean, val mayShorten: Set<Int>, val minTap: Float) {
        val found = ArrayList<String>()
        /** Each shown text's visible box on screen, to find text running into other text. */
        val texts = ArrayList<Pair<TextView, Rect>>()

        fun visit(v: View) {
            if (v.visibility != View.VISIBLE || v.alpha == 0f) return
            if (v.isClickable && v.isEnabled && (v.width < minTap || v.height < minTap)) {
                found += "${name(v)} is ${v.width}x${v.height} px, under 48 dp to tap"
            }
            if (v is TextView) text(v)
            if (v !is ViewGroup || v is WebView) return
            val sideways = v is HorizontalScrollView
            val down = v is ScrollView || v is AbsListView
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                if (c.visibility != View.VISIBLE || c.alpha == 0f) continue
                if (!sideways && (c.left < -1 || c.right > v.width + 1)) {
                    found += "${name(c)} is cut off at the side: ${c.left}..${c.right} px inside 0..${v.width} of ${name(v)}"
                }
                if (!down && (c.top < -1 || c.bottom > v.height + 1)) {
                    found += "${name(c)} is cut off at the bottom: ${c.top}..${c.bottom} px inside 0..${v.height} of ${name(v)}"
                }
                visit(c)
            }
        }

        private fun text(t: TextView) {
            val text = t.text?.toString().orEmpty()
            val wide = t.width - t.totalPaddingLeft - t.totalPaddingRight
            if (text.isEmpty()) {
                // A one-line field doesn't wrap its hint: a long hint is cut off.
                val hint = t.hint?.toString().orEmpty()
                if (hint.isNotEmpty() && (t.isSingleLine || t.maxLines == 1) && t.paint.measureText(hint) > wide + 1) {
                    found += "${name(t)} hint \"$hint\" is cut off"
                }
                return
            }
            if (t.width <= 0 || t.height <= 0) {
                found += "${name(t)} is squeezed to ${t.width}x${t.height} px"
                return
            }
            val layout = t.layout ?: return
            val editable = t is EditText
            val tall = t.height - t.extendedPaddingTop - t.extendedPaddingBottom
            var shortened = false
            var widest = 0f
            var left = Float.MAX_VALUE
            var right = 0f
            for (line in 0 until layout.lineCount) {
                if (layout.getEllipsisCount(line) > 0) shortened = true
                // Without the spaces a line ends with: they may hang past the edge.
                widest = maxOf(widest, layout.getLineMax(line))
                left = minOf(left, layout.getLineLeft(line))
                right = maxOf(right, layout.getLineRight(line))
                val end = layout.getLineEnd(line)
                if (line < layout.lineCount - 1 && end in 1 until text.length &&
                    text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
                ) {
                    val start = layout.getLineStart(line)
                    found += "${name(t)} breaks a word across lines: \"${text.substring(start, end)}\" / \"${text.substring(end).take(20)}\""
                }
            }
            if (shortened && t.id !in mayShorten) found += "${name(t)} is shortened with …"
            if (!editable && widest > wide + 1) found += "${name(t)} is cut off: ${ceil(widest).toInt()} px of text in $wide px"
            if (!editable && layout.height > tall + 1) found += "${name(t)} is cut off: ${layout.height} px of text in $tall px"
            val centered = t.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL
            if (!wrappingButtons && centered && t is Button && t !is CompoundButton && layout.lineCount > 1) {
                found += "${name(t)} label wraps onto ${layout.lineCount} lines"
            }

            val at = IntArray(2)
            t.getLocationInWindow(at)
            val x = at[0] + t.totalPaddingLeft - t.scrollX
            val y = at[1] + t.totalPaddingTop - t.scrollY
            val box = Rect(x + left.toInt(), y, x + ceil(right).toInt(), y + layout.height)
            val visible = Rect()
            if (t.getGlobalVisibleRect(visible) && box.intersect(visible)) texts += t to box
        }
    }

    /** The scrolling container the view moves with, if any. */
    private fun scroller(v: View): View? {
        var p = v.parent as? View
        while (p != null && p !is ScrollView && p !is HorizontalScrollView && p !is AbsListView) p = p.parent as? View
        return p
    }

    /** The view's id (or class and text), and the id of the nearest parent that has one. */
    fun name(v: View): String {
        val self = idName(v) ?: v.javaClass.simpleName
        val label = (v as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }?.let { " \"${it.take(32)}\"" } ?: ""
        var p = v.parent as? View
        while (p != null && idName(p) == null) p = p.parent as? View
        val inside = p?.let { " in ${idName(it)}" } ?: ""
        return self + label + inside
    }

    private fun idName(v: View): String? =
        if (v.id == View.NO_ID) null else runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
}
