package com.wdwy90.pullupmenu.phone

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.ChainMenus
import com.wdwy90.pullupmenu.core.ChainPrices
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.Prefs
import com.wdwy90.pullupmenu.core.Restaurant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The Items tab's category list: every category starts collapsed, one opens at a time, a search
 * shows its matches open and clearing it restores the list, a new restaurant starts over, and the
 * header's status line shows only what Google said. Sample data only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class RestaurantItemsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun everyCategoryStartsCollapsedAndOneOpensAtATime() {
        show(sample("Burger King"))
        val a = launch()
        val cards = cards(a)
        assertEquals(5, cards.size)
        for (c in cards) {
            assertEquals(View.GONE, body(c).visibility)
            assertEquals("Collapsed", ViewCompat.getStateDescription(header(c)).toString())
        }
        assertTrue(a.findViewById<TextView>(R.id.search_summary).text.matches(Regex("\\d+ items · \\d+ categories")))

        header(cards[0]).performClick()
        idle()
        assertEquals(View.VISIBLE, body(cards[0]).visibility)
        assertEquals("Expanded", ViewCompat.getStateDescription(header(cards[0])).toString())
        assertTrue("items laid out", body(cards[0]).height > 0)

        header(cards[2]).performClick()
        idle()
        assertEquals(View.VISIBLE, body(cards[2]).visibility)
        assertEquals(View.GONE, body(cards[0]).visibility)
        assertEquals("Collapsed", ViewCompat.getStateDescription(header(cards[0])).toString())

        header(cards[2]).performClick()
        idle()
        assertEquals(View.GONE, body(cards[2]).visibility)
    }

    @Test
    fun aTapWhileACategoryIsClosingReopensIt() {
        show(sample("Burger King"))
        val a = launch()
        val cards = cards(a)
        header(cards[1]).performClick()
        idle()
        header(cards[1]).performClick() // closing...
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(View.VISIBLE, body(cards[1]).visibility) // still animating closed
        header(cards[1]).performClick() // ...changed my mind
        idle()
        assertEquals(View.VISIBLE, body(cards[1]).visibility)
        assertEquals("Expanded", ViewCompat.getStateDescription(header(cards[1])).toString())
        assertTrue(body(cards[1]).height > 0)
        assertEquals(1f, body(cards[1]).alpha, 0.001f)
    }

    @Test
    fun categoryRowsTellScreenReadersWhatATapDoes() {
        show(sample("Burger King"))
        val a = launch()
        val cards = cards(a)
        assertEquals("Expand", clickLabel(header(cards[0])))
        assertEquals(false, header(cards[0]).isActivated)
        // Item rows are screen-reader stops, not keyboard stops.
        header(cards[0]).performClick()
        idle()
        assertEquals("Collapse", clickLabel(header(cards[0])))
        assertEquals(true, header(cards[0]).isActivated)
        val rows = (body(cards[0]) as ViewGroup).getChildAt(0) as ViewGroup
        val row = rows.getChildAt(0)
        assertEquals(false, row.isFocusable)
        assertEquals(true, ViewCompat.isScreenReaderFocusable(row))
        // Chips read as buttons.
        val chip = a.findViewById<LinearLayout>(R.id.chips).getChildAt(0)
        val info = AccessibilityNodeInfoCompat.wrap(chip.createAccessibilityNodeInfo())
        assertEquals(android.widget.Button::class.java.name, info.className)
    }

    @Test
    fun searchOpensMatchesAndClearingRestoresTheList() {
        show(sample("Burger King"))
        val a = launch()
        header(cards(a)[1]).performClick() // Chicken & Fish open before searching
        idle()

        a.findViewById<EditText>(R.id.search).setText("whopper")
        idle()
        val matches = cards(a)
        assertEquals(1, matches.size)
        assertEquals(View.VISIBLE, body(matches[0]).visibility)
        // Folding a match only folds that one.
        header(matches[0]).performClick()
        idle()
        assertEquals(View.GONE, body(matches[0]).visibility)

        a.findViewById<EditText>(R.id.search).setText("")
        idle()
        val cards = cards(a)
        assertEquals(5, cards.size)
        assertEquals(listOf(View.GONE, View.VISIBLE, View.GONE, View.GONE, View.GONE), cards.map { body(it).visibility })
    }

    @Test
    fun chipOpensItsCategoryAndANewRestaurantStartsCollapsed() {
        show(sample("Burger King"))
        val a = launch()
        val chips = a.findViewById<LinearLayout>(R.id.chips)
        assertEquals(5, chips.childCount)
        chips.getChildAt(3).performClick()
        idle()
        assertEquals(listOf(View.GONE, View.GONE, View.GONE, View.VISIBLE, View.GONE), cards(a).map { body(it).visibility })
        assertTrue(chips.getChildAt(3).isSelected)

        MenuRepository.choose(app, sample("McDonald's", id = "another-place"))
        idle()
        val cards = cards(a)
        assertEquals(10, cards.size)
        assertTrue(cards.all { body(it).visibility == View.GONE })
        assertEquals(10, chips.childCount)
    }

    @Test
    fun statusLineShowsOnlyWhatGoogleSaid() {
        show(sample("Burger King").copy(openNow = true, distanceMeters = 30.0))
        val a = launch()
        val status = a.findViewById<TextView>(R.id.status)
        assertTrue(status.isShown)
        assertEquals("Open now\u00A0\u00A0·\u00A0\u00A098 ft", status.text.toString())
        assertEquals("Open now, 98 feet away", status.contentDescription.toString())

        MenuRepository.choose(app, sample("Burger King", id = "closed").copy(businessStatus = "CLOSED_TEMPORARILY"))
        idle()
        assertEquals("Temporarily closed", status.text.toString())

        MenuRepository.choose(app, sample("Burger King", id = "no-hours"))
        idle()
        assertEquals(View.GONE, status.visibility)

        MenuRepository.showDemo(app)
        idle()
        assertEquals(View.GONE, status.visibility)
    }

    private fun cards(a: Activity): List<ViewGroup> {
        val list = a.findViewById<LinearLayout>(R.id.items_list)
        return (0 until list.childCount).map { list.getChildAt(it) as ViewGroup }
    }

    private fun header(card: ViewGroup): View = card.getChildAt(0)
    /** The label a screen reader reads for a tap ("double-tap to <label>"). */
    private fun clickLabel(v: View): String? {
        val info = AccessibilityNodeInfoCompat.wrap(v.createAccessibilityNodeInfo())
        return info.actionList.firstOrNull { it.id == AccessibilityNodeInfoCompat.ACTION_CLICK }?.label?.toString()
    }
    private fun body(card: ViewGroup): View = card.getChildAt(1)

    private fun launch(): RestaurantActivity {
        val a = Robolectric.buildActivity(RestaurantActivity::class.java).setup().get()
        idle()
        return a
    }

    private fun show(r: Restaurant) {
        Prefs.setApiKey(app, "AIza" + "A".repeat(31) + "WXYZ")
        MenuRepository.showDemo(app)
        MenuRepository.choose(app, r)
    }

    private fun sample(chain: String, id: String = "test-place") = Restaurant(
        id = id,
        name = chain,
        address = "1200 Main St, Springfield, IL 62701",
        lat = 39.8,
        lng = -89.6,
        rating = 4.1,
        category = "Fast food restaurant",
        photos = emptyList(),
        websiteUri = null,
        mapsUri = "https://maps.google.com/?cid=1",
        menuUrl = ChainMenus.get(app).menuUrlFor(chain),
        prices = ChainPrices.get(app).forPlace(chain),
    )

    /** Lets the search debounce, the open/close animations and any posted layout run. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
}
