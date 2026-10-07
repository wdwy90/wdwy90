package com.wdwy90.pullupmenu.phone

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.ChainMenus
import com.wdwy90.pullupmenu.core.ChainPrices
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.PlacePhoto
import com.wdwy90.pullupmenu.core.Prefs
import com.wdwy90.pullupmenu.core.Restaurant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * Every phone screen on small phones and with large text (Android's Font size and Display size
 * settings), laid out for real (Robolectric) and checked with [LayoutAudit]: nothing cut off, no
 * word broken across lines, no text running into other text, tap targets of 48 dp. Sample data
 * only: a made-up Burger King with a long Google-style name, generated photos and a made-up key.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SmallScreensTest {
    private val app get() = RuntimeEnvironment.getApplication()

    /** A 6.1-inch phone's width (most Galaxy S models), text at 130%. */
    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun smallPhoneWithLargeText() = auditEveryScreen(fontScale = 1.3f)

    /** The same phone with Display size at its largest. */
    @Test
    @Config(qualifiers = "w320dp-h693dp-xxhdpi")
    fun largestDisplaySizeWithLargeText() = auditEveryScreen(fontScale = 1.3f)

    /** Text at 200%, Android's largest: a button label may take two lines. */
    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun smallPhoneWithLargestText() = auditEveryScreen(fontScale = 2f, wrappingButtons = true)

    @Test
    @Config(qualifiers = "w320dp-h693dp-xxhdpi")
    fun largestDisplaySizeAndText() = auditEveryScreen(fontScale = 2f, wrappingButtons = true)

    /** The Galaxy S26 Ultra's width. */
    @Test
    @Config(qualifiers = "w412dp-h891dp-xxxhdpi")
    fun bigPhoneWithLargestText() = auditEveryScreen(fontScale = 2f, wrappingButtons = true)

    private fun auditEveryScreen(fontScale: Float, wrappingButtons: Boolean = false) {
        RuntimeEnvironment.setFontScale(fontScale)
        // Built at run time so no key-shaped literal sits in the source.
        Prefs.setApiKey(app, "AIza" + "A".repeat(31) + "WXYZ")
        val problems = ArrayList<String>()
        fun check(screen: String, root: View) {
            idle(Duration.ofMillis(300))
            // The top bar's title is one line by design; the full name is in the header below it.
            problems += LayoutAudit.problems(root, wrappingButtons, mayShorten = setOf(R.id.top_title))
                .map { "$screen: $it" }
        }
        fun checkScreen(screen: String, a: Activity) = check(screen, a.window.decorView)

        setState(MenuRepository.State.Idle)
        checkScreen("Home, location not allowed yet", launch(MainActivity::class.java))
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        checkScreen("Home, ready", launch(MainActivity::class.java))
        setState(MenuRepository.State.Searching)
        checkScreen("Home, searching", launch(MainActivity::class.java))
        setState(MenuRepository.State.Error("Couldn't get your location. Try again in a moment."))
        checkScreen("Home, error", launch(MainActivity::class.java))
        val place = showSample()
        checkScreen("Home, restaurant found", launch(MainActivity::class.java))

        val settings = launch(SettingsActivity::class.java)
        checkScreen("Settings", settings)
        settings.findViewById<View>(R.id.change_key).performClick()
        settings.findViewById<EditText>(R.id.api_key).setText("not a key")
        settings.findViewById<View>(R.id.save_key).performClick()
        checkScreen("Settings, changing the key", settings)

        val screen = launch(RestaurantActivity::class.java)
        checkScreen("Restaurant, items", screen)
        // Categories start collapsed: open one so the item rows are audited too.
        sectionHeader(screen, 0).performClick()
        idle(Duration.ofMillis(500))
        checkScreen("Restaurant, a category open", screen)
        screen.findViewById<EditText>(R.id.search).setText("zzz")
        idle(Duration.ofMillis(400))
        checkScreen("Restaurant, no items match", screen)
        screen.findViewById<EditText>(R.id.search).setText("")
        screen.findViewById<View>(R.id.tab_photos).performClick()
        idle(Duration.ofMillis(800))
        checkScreen("Restaurant, photos", screen)
        screen.findViewById<View>(R.id.tab_menu).performClick()
        idle()
        val web = screen.findViewById<FrameLayout>(R.id.web_container).getChildAt(0) as WebView
        shadowOf(web).webViewClient.onReceivedError(web, request(place.menuUrl!!), null)
        checkScreen("Restaurant, menu page failed", screen)
        screen.findViewById<View>(R.id.tab_items).performClick()
        idle()
        screen.findViewById<View>(R.id.hero).performClick()
        idle(Duration.ofMillis(800))
        val viewer = ShadowDialog.getLatestDialog()
        assertNotNull("photo viewer", viewer?.window)
        check("Photo viewer", viewer.window!!.decorView)
        viewer.dismiss()

        // The longest item and category names in the data (Dairy Queen: 52-character items), opened.
        showSample(sample().copy(
            id = "long-names", name = "Dairy Queen Grill & Chill - Springfield Plaza",
            menuUrl = ChainMenus.get(app).menuUrlFor("Dairy Queen"), prices = ChainPrices.get(app).forPlace("Dairy Queen"),
        ))
        val long = launch(RestaurantActivity::class.java)
        long.findViewById<ViewGroup>(R.id.chips).getChildAt(2).performClick() // Chicken Baskets
        idle(Duration.ofMillis(500))
        checkScreen("Restaurant, long names open", long)

        // The longest single word in any category name ("ButterBurgers", Culver's), opened.
        showSample(sample().copy(
            id = "long-words", name = "Culver's",
            menuUrl = ChainMenus.get(app).menuUrlFor("Culver's"), prices = ChainPrices.get(app).forPlace("Culver's"),
        ))
        val words = launch(RestaurantActivity::class.java)
        sectionHeader(words, 0).performClick() // ButterBurgers
        idle(Duration.ofMillis(500))
        checkScreen("Restaurant, long words open", words)

        showSample(sample().copy(id = "other-place", name = "Joe's Diner and Drive-In", menuUrl = null, prices = null))
        val other = launch(RestaurantActivity::class.java)
        checkScreen("Restaurant without an item list", other)

        MenuRepository.showDemo(app)
        checkScreen("Demo restaurant", launch(RestaurantActivity::class.java))

        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Shows [r] (Google-sourced, not the demo) through the public chooser path, its photos already loaded. */
    private fun showSample(r: Restaurant = sample()): Restaurant {
        val dm = app.resources.displayMetrics
        // RestaurantActivity's photo width, so the screen finds these instead of downloading.
        val width = minOf(dm.widthPixels, dm.heightPixels).coerceIn(480, 1200)
        r.photos.forEachIndexed { i, ph ->
            val bmp = Bitmap.createBitmap(width, width * 3 / 4, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(listOf(Color.DKGRAY, Color.GRAY, Color.BLUE, Color.RED)[i % 4])
            photoCache().put("${ph.name}@$width", bmp)
        }
        MenuRepository.showDemo(app)
        MenuRepository.choose(app, r)
        return r
    }

    private fun sample(): Restaurant {
        val name = "Burger King - 1200 North Main Street"
        return Restaurant(
            id = "sample-place",
            name = name,
            address = "1200 North Main Street, Springfield, IL 62701",
            lat = 39.8,
            lng = -89.6,
            rating = 4.1,
            category = "Fast food restaurant",
            photos = (1..4).map {
                PlacePhoto("places/sample/photos/$it", "Sample Photographer $it", "https://example.com/p$it", "https://maps.google.com/?q=$it")
            },
            websiteUri = "https://www.bk.com",
            mapsUri = "https://maps.google.com/?cid=1",
            menuUrl = ChainMenus.get(app).menuUrlFor(name),
            prices = ChainPrices.get(app).forPlace(name),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun setState(s: MenuRepository.State) {
        val f = MenuRepository::class.java.getDeclaredField("_state")
        f.isAccessible = true
        (f.get(MenuRepository) as MutableStateFlow<MenuRepository.State>).value = s
    }

    @Suppress("UNCHECKED_CAST")
    private fun photoCache(): LruCache<String, Bitmap> {
        val f = MenuRepository::class.java.getDeclaredField("photoCache")
        f.isAccessible = true
        return f.get(MenuRepository) as LruCache<String, Bitmap>
    }

    private fun <T : Activity> launch(screen: Class<T>): T {
        val a = Robolectric.buildActivity(screen).setup().get()
        idle(Duration.ofMillis(300))
        return a
    }

    private fun sectionHeader(a: Activity, n: Int): View =
        (a.findViewById<ViewGroup>(R.id.items_list).getChildAt(n) as ViewGroup).getChildAt(0)

    private fun idle(d: Duration? = null) {
        val looper = shadowOf(Looper.getMainLooper())
        if (d != null) looper.idleFor(d) else looper.idle()
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
