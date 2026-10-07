package com.wdwy90.pullupmenu.car

import android.text.Spanned
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.testing.TestCarContext
import com.wdwy90.pullupmenu.core.CarMenu
import com.wdwy90.pullupmenu.core.ChainPrices
import com.wdwy90.pullupmenu.core.ItemGroup
import com.wdwy90.pullupmenu.core.ItemGroups
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.MenuRepository.State
import com.wdwy90.pullupmenu.core.Restaurant
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * Builds every car screen's template in every state. The car library checks templates against
 * Android Auto's rules as they're built (rows, images, text spans, actions per template), and a
 * broken rule would crash the car screen; here it fails the test instead. Made-up places only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarScreensTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var car: TestCarContext

    @Before
    fun setUp() {
        car = TestCarContext.createCarContext(app)
        setState(State.Idle)
    }

    @After
    fun tearDown() {
        CarUi.listLimitOverride = null
        setState(State.Idle)
    }

    @Test
    fun homeBuildsInEveryState() {
        val session = MenuSession()
        val states = listOf(
            State.Idle, State.Searching, State.NothingNearby, State.Error("No internet connection. Try again."),
            State.Found(place("Burger King"), emptyList(), 0L, auto = true),
        )
        for (s in states) {
            setState(s)
            val t = HomeScreen(car, session).onGetTemplate() as PaneTemplate
            assertEquals("Pull Up Menu", t.title.toString())
            assertEquals(1, t.pane.rows.size)
            assertTrue(t.pane.actions.size in 1..2)
        }
    }

    @Test
    fun cardShowsOpenStatusInColourAndViewMenuFirst() {
        val bk = place("Burger King")
        setState(State.Found(bk, listOf(place("Taco Bell", id = "tb")), System.currentTimeMillis(), auto = true))
        val t = RestaurantScreen(car, bk).onGetTemplate() as PaneTemplate
        assertEquals("Burger King", t.title.toString())
        assertEquals(listOf("1200 N Main St", "4.1 · Fast food restaurant", "Menu ready"), t.pane.rows.map { it.title.toString() })
        val status = t.pane.rows[0].texts[0].toCharSequence() as Spanned
        assertEquals("Open now · 260 ft away", status.toString())
        assertEquals(1, status.getSpans(0, status.length, ForegroundCarColorSpan::class.java).size)
        assertEquals("View menu", t.pane.actions[0].title.toString())
        assertEquals("Not here?", t.actionStrip!!.actions.single().title.toString())
    }

    @Test
    fun cardWithoutAMenuStillBuilds() {
        val joe = place("Joe's Burger Shack").copy(prices = null)
        val t = RestaurantScreen(car, joe).onGetTemplate() as PaneTemplate
        assertEquals("Menu unavailable", t.pane.rows[2].title.toString())
        assertEquals(listOf("Open on phone"), t.pane.actions.map { it.title.toString() })
    }

    @Test
    fun everyChainsMenuBuildsOnASmallAndARoomyHost() {
        for (limit in listOf(6, 100)) {
            CarUi.listLimitOverride = limit
            for (name in chainNames()) {
                val r = place(name)
                val prices = r.prices!!
                walk(r, ItemGroups.byCategory(prices.items), "${prices.chain} menu", 1)
            }
        }
    }

    @Test
    fun theMenuOpensOnCategories() {
        CarUi.listLimitOverride = 100
        val r = place("Burger King")
        val t = MenuScreen(car, r, r.prices!!).onGetTemplate() as ListTemplate
        val rows = t.singleList!!.items.map { it as Row }
        assertEquals(ItemGroups.byCategory(r.prices!!.items).map { it.title }, rows.map { it.title.toString() })
        assertTrue(rows.all { it.isBrowsable && it.image != null })
        assertEquals("9 items", rows[0].texts[0].toString())
        assertEquals(1, t.actionStrip!!.actions.size) // search
    }

    @Test
    fun searchBuildsBeforeAndAfterTyping() {
        val r = place("McDonald's")
        val screen = MenuSearchScreen(car, r, r.prices!!, ItemGroups.byCategory(r.prices!!.items))
        val empty = screen.onGetTemplate() as SearchTemplate
        assertTrue(empty.itemList!!.items.isEmpty())
        empty.searchCallbackDelegate.sendSearchTextChanged("chicken", object : OnDoneCallback {})
        shadowOf(Looper.getMainLooper()).idle()
        val found = screen.onGetTemplate() as SearchTemplate
        assertTrue(found.itemList!!.items.isNotEmpty())
    }

    @Test
    fun chooserKeepsTheGoogleCreditWithinTheLimit() {
        CarUi.listLimitOverride = 6
        val others = (1..8).map { place("Place $it", id = "p$it") }
        val t = ChooserScreen(car, "bk", others).onGetTemplate() as ListTemplate
        val rows = t.singleList!!.items.map { it as Row }
        assertEquals(6, rows.size)
        assertEquals("Info from Google Maps", rows.last().title.toString())
    }

    /** Builds a menu screen and every screen its rows open, like a driver tapping through all of it. */
    private fun walk(r: Restaurant, groups: List<ItemGroup>, title: String, level: Int) {
        val t = MenuScreen(car, r, r.prices!!, groups, title, level).onGetTemplate() as ListTemplate
        val page = CarMenu.page(groups, CarUi.listLimit(car), CarMenu.LEVELS - level + 1)
        assertTrue(t.singleList!!.items.size <= CarUi.listLimit(car))
        if (page is CarMenu.Page.Rows) page.entries.forEach { walk(r, it.groups, it.title, level + 1) }
    }

    private fun place(name: String, id: String = "bk") = Restaurant(
        id = id, name = name, address = "1200 N Main St", lat = 39.8, lng = -89.6, rating = 4.1,
        category = "Fast food restaurant", photos = emptyList(), websiteUri = null, mapsUri = null,
        distanceMeters = 80.0, prices = ChainPrices.get(app).forPlace(name), openNow = true,
    )

    private fun chainNames(): List<String> {
        val arr = JSONObject(app.assets.open("chain_menus.json").bufferedReader().readText()).getJSONArray("chains")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setState(s: State) {
        val f = MenuRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (f.get(MenuRepository) as MutableStateFlow<State>).value = s
    }
}
