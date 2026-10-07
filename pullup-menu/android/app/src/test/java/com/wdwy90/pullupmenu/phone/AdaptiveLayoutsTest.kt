package com.wdwy90.pullupmenu.phone

import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.wdwy90.pullupmenu.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [FlowRow] and [ColumnGrid] on their own, at exact pixel sizes (mdpi: 1 dp is 1 px): lines and
 * gaps, a spread line, row heights, dropping to one column instead of breaking a word, and
 * Android's measure cache.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "mdpi")
class AdaptiveLayoutsTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun flowRowKeepsChildrenOnOneLineWhenTheyFit() {
        val row = FlowRow(ctx)
        val a = box(row, 120, 40)
        val b = box(row, 100, 48)
        layOut(row, 300)
        assertEquals(48, row.height)
        assertBounds(a, 0, 4, 120, 44) // centered on the line
        assertBounds(b, 128, 0, 228, 48) // 8 dp after the first
    }

    @Test
    fun flowRowMovesWhatDoesNotFitToTheNextLine() {
        val row = FlowRow(ctx)
        val a = box(row, 120, 40)
        val b = box(row, 100, 48)
        layOut(row, 200)
        assertEquals(40 + 8 + 48, row.height)
        assertBounds(a, 0, 0, 120, 40)
        assertBounds(b, 0, 48, 100, 96)
    }

    @Test
    fun flowRowTakesOnlyTheWidthItNeeds() {
        val row = FlowRow(ctx)
        box(row, 120, 40)
        box(row, 100, 40)
        row.measure(MeasureSpec.makeMeasureSpec(300, MeasureSpec.AT_MOST), ANY)
        assertEquals(228, row.measuredWidth)
    }

    @Test
    fun spreadLinePutsTheLastChildAtTheEndAndSkipsGoneOnes() {
        val attrs = Robolectric.buildAttributeSet().addAttribute(R.attr.flowSpread, "true").build()
        val row = FlowRow(ctx, attrs)
        val label = box(row, 120, 40)
        box(row, 500, 40).visibility = View.GONE
        val value = box(row, 100, 40)
        layOut(row, 300)
        assertEquals(40, row.height)
        assertBounds(label, 0, 0, 120, 40)
        assertBounds(value, 200, 0, 300, 40)
    }

    @Test
    fun columnGridLinesUpCellsAndEvensOutEachRow() {
        val grid = ColumnGrid(ctx)
        val cells = listOf(50, 80, 30, 20).map { h -> View(ctx).apply { minimumHeight = h }.also { grid.addView(it) } }
        layOut(grid, 300)
        assertEquals(2, grid.columnsShown)
        // Columns (300 - 12) / 2 = 144 px wide, 12 dp apart, each row as tall as its tallest cell.
        assertBounds(cells[0], 0, 0, 144, 80)
        assertBounds(cells[1], 156, 0, 300, 80)
        assertBounds(cells[2], 0, 92, 144, 122)
        assertBounds(cells[3], 156, 92, 300, 122)
        assertEquals(122, grid.height)
    }

    @Test
    fun columnGridDropsToOneColumnRatherThanBreakAWord() {
        val grid = ColumnGrid(ctx)
        val texts = CREDITS.map { cell(grid, it) }
        val width = onlyFullWidthFits(texts[2])
        layOut(grid, width)
        assertEquals(1, grid.columnsShown)
        assertEquals(width, texts[2].width)
        assertFalse(grid.breaksAWord())
    }

    @Test
    fun columnGridStaysOneColumnWhenAnotherCellChanges() {
        // Android can answer measure() from its cache for a cell that hasn't asked for layout,
        // without laying its text out at the new width. A photo arriving in another cell must not
        // switch the grid to two columns with a word broken in half.
        val grid = ColumnGrid(ctx)
        val texts = CREDITS.map { cell(grid, it) }
        val width = onlyFullWidthFits(texts[2])
        layOut(grid, width)
        assertEquals(1, grid.columnsShown)

        (texts[0].parent as ViewGroup).getChildAt(0).requestLayout() // the first photo arrives
        layOut(grid, width)
        assertEquals(1, grid.columnsShown)
        assertFalse(grid.breaksAWord())

        (texts[3].parent as ViewGroup).getChildAt(0).requestLayout() // and the last
        layOut(grid, width)
        assertEquals(1, grid.columnsShown)
        assertFalse(grid.breaksAWord())
    }

    private fun box(parent: ViewGroup, w: Int, h: Int) = View(ctx).also { parent.addView(it, ViewGroup.LayoutParams(w, h)) }

    /** A photo cell like the restaurant screen's: a picture, then its credit. Returns the credit. */
    private fun cell(grid: ColumnGrid, credit: String): TextView {
        val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cell.addView(View(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40))
        val text = TextView(ctx).apply { text = credit }
        cell.addView(text)
        grid.addView(cell)
        return text
    }

    /** A grid width where [LONG_WORD] fits a full-width cell but not a half-width one. */
    private fun onlyFullWidthFits(t: TextView): Int = (t.paint.measureText(LONG_WORD) * 1.5f).toInt() + 12

    private fun layOut(v: View, width: Int) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), ANY)
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun assertBounds(v: View, l: Int, t: Int, r: Int, b: Int) =
        assertEquals("$l,$t,$r,$b", "${v.left},${v.top},${v.right},${v.bottom}")

    private companion object {
        const val LONG_WORD = "Supercalifragilistic"
        val CREDITS = listOf("Photo: Ann Lee", "Photo: Bo Diaz", "Photo: $LONG_WORD", "Photo: Cy Park")
        val ANY = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    }
}
