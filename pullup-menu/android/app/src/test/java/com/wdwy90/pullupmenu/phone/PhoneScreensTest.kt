package com.wdwy90.pullupmenu.phone

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.notification.CarAppExtender
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.ChainMenus
import com.wdwy90.pullupmenu.core.ChainPrices
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.Notifier
import com.wdwy90.pullupmenu.core.Prefs
import com.wdwy90.pullupmenu.core.Restaurant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The phone screens with their real layouts, themes and activities (Robolectric): sizes that only
 * show up once laid out, theme changes, the API key never on screen in full, Google attribution,
 * touch targets, the Drive Mode switch, the Menu tab's failed-load state and going back to ready
 * after driving away. Sample data only: a made-up Burger King location. Small phones and large
 * text: [SmallScreensTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class PhoneScreensTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun allowLocation() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @Test
    fun quickActionTilesAreFullSizeAndEven() {
        for (theme in listOf(Prefs.THEME_DARK, Prefs.THEME_LIGHT)) {
            Prefs.setTheme(app, theme)
            assertTilesLaidOut(launch(MainActivity::class.java))
        }
    }

    @Test
    fun screensLayOutWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        assertTilesLaidOut(launch(MainActivity::class.java))
        launch(SettingsActivity::class.java)
        showSample()
        val a = launch(RestaurantActivity::class.java)
        assertTrue(a.findViewById<View>(R.id.items_content).isShown)
    }

    @Test
    fun themeChoiceAppliesToEveryScreen() {
        for ((theme, night) in listOf(Prefs.THEME_DARK to true, Prefs.THEME_LIGHT to false)) {
            Prefs.setTheme(app, theme)
            for (screen in listOf(MainActivity::class.java, SettingsActivity::class.java, RestaurantActivity::class.java)) {
                val a = launch(screen)
                val mode = a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                assertEquals("${screen.simpleName}, theme $theme", night, mode == Configuration.UI_MODE_NIGHT_YES)
            }
        }
    }

    @Test
    @Config(qualifiers = "+night")
    fun matchSystemWaitsForTheSystemInsteadOfRecreatingTwice() {
        // Android 12+ after an earlier run handed Dark to the system, which now draws it: nothing forced.
        Prefs.setTheme(app, Prefs.THEME_DARK)
        Prefs.setSystemNightMode(app, UiModeManager.MODE_NIGHT_YES)
        val screen = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val dark = screen.get()
        dark.findViewById<View>(R.id.theme_system).performClick()
        idle()
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, shadowOf(uiModeManager()).applicationNightMode)
        // Recreating here would race the system's own change and recreate the screen twice.
        assertSame(dark, screen.get())

        // The system's change arrives (the phone is in light mode): one recreate, in light.
        RuntimeEnvironment.setQualifiers("+notnight")
        screen.configurationChange()
        idle()
        val light = screen.get()
        assertNotSame(dark, light)
        assertFalse(isNight(light))
        idle()
        assertSame(light, screen.get())
    }

    @Test
    @Config(qualifiers = "+night")
    fun forcedThemeChangeRecreatesOnceInTheNewTheme() {
        // First run: nothing handed to the system yet, so the screen forces Dark itself.
        Prefs.setTheme(app, Prefs.THEME_DARK)
        val screen = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val dark = screen.get()
        assertTrue(isNight(dark))
        dark.findViewById<View>(R.id.theme_light).performClick()
        idle()
        // Light at once, although the system still draws night (its change hasn't arrived).
        val light = screen.get()
        assertNotSame(dark, light)
        assertFalse(isNight(light))
        assertEquals(UiModeManager.MODE_NIGHT_NO, shadowOf(uiModeManager()).applicationNightMode)
        idle()
        assertSame(light, screen.get())
    }

    @Test
    fun settingsNeverShowsTheWholeKey() {
        // Built at run time so no key-shaped literal sits in the source.
        val key = "AIza" + "A".repeat(31) + "WXYZ"
        Prefs.setApiKey(app, key)
        val a = launch(SettingsActivity::class.java)
        assertTrue(screenText(a).none { it.contains(key.dropLast(4)) })
        assertTrue(screenText(a).any { it.endsWith("WXYZ") })

        // The editor starts empty, is a password field, and saving doesn't put the key on screen.
        a.findViewById<View>(R.id.change_key).performClick()
        val field = a.findViewById<EditText>(R.id.api_key)
        assertEquals("", field.text.toString())
        assertTrue(field.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0)
        val newKey = "AIza" + "B".repeat(31) + "QRST"
        field.setText(newKey)
        a.findViewById<View>(R.id.save_key).performClick()
        idle()
        assertEquals(newKey, Prefs.apiKey(app))
        assertEquals("", field.text.toString())
        assertTrue(screenText(a).none { it.contains(newKey.dropLast(4)) })
        assertTrue(screenText(a).any { it.endsWith("QRST") })
    }

    @Test
    fun driveModeSwitchStaysOnWhileTheServiceStarts() {
        Prefs.setAutoDetect(app, true)
        try {
            val a = launch(SettingsActivity::class.java)
            val driveMode = a.findViewById<CompoundButton>(R.id.drive_mode)
            assertFalse(driveMode.isChecked)
            driveMode.performClick()
            idle()
            assertEquals(ArrivalService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
            // Robolectric never creates the service, so this is the switch before the service is up.
            assertTrue("Drive Mode flicked back off", driveMode.isChecked)
        } finally {
            Prefs.setAutoDetect(app, false)
            // ArrivalService.onDestroy would turn this off again; the service never ran here.
            ArrivalService::class.java.getDeclaredField("_running").run {
                isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (get(null) as MutableStateFlow<Boolean>).value = false
            }
        }
    }

    @Test
    fun drivingAwayGoesBackToReadyQuietly() {
        Prefs.setApiKey(app, "AIza" + "A".repeat(31) + "WXYZ")
        val place = showSample()
        Notifier.arrival(app, place, carBanner = false)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertEquals(1, notifications.allNotifications.size)
        val restaurant = launch(RestaurantActivity::class.java)
        val home = launch(MainActivity::class.java)
        val left = ArrayList<String>()
        val watch = CoroutineScope(Dispatchers.Unconfined).launch { MenuRepository.left.collect { left += it } }
        try {
            val visit = MenuRepository.state.value as MenuRepository.State.Found
            MenuRepository.visitOver(app, visit)
            idle()
            assertEquals(MenuRepository.State.Idle, MenuRepository.state.value)
            // The car pops its card for this restaurant, and the arrival notification goes.
            assertEquals(listOf(place.id), left)
            assertTrue(notifications.allNotifications.isEmpty())
            // Someone reading the menu on the phone keeps it.
            assertFalse(restaurant.isFinishing)
            // Home is ready for the next drive-thru and doesn't claim nothing was ever found.
            assertEquals("Ready to detect", home.findViewById<TextView>(R.id.status_title).text.toString())
            val tile = home.findViewById<View>(R.id.action_menu)
            assertEquals("No restaurant right now", tile.findViewById<TextView>(R.id.action_subtitle).text.toString())

            // A visit that's already over (or replaced) doesn't end anything else.
            MenuRepository.showDemo(app)
            MenuRepository.visitOver(app, visit)
            MenuRepository.visitOver(app, MenuRepository.state.value as MenuRepository.State.Found)
            assertTrue(MenuRepository.state.value is MenuRepository.State.Found)
            assertEquals(listOf(place.id), left)
        } finally {
            watch.cancel()
        }
    }

    @Test
    fun everyTappableViewIsAtLeast48dp() {
        showSample()
        val small = ArrayList<String>()
        for (screen in listOf(MainActivity::class.java, SettingsActivity::class.java, RestaurantActivity::class.java)) {
            val a = launch(screen)
            val min = dp(a, 48) - 1
            for (v in descendants(a.window.decorView)) {
                if (!v.isClickable || !v.isShown) continue
                if (v.width < min || v.height < min) small += "${screen.simpleName}: ${describe(v)} is ${v.width}x${v.height}px"
            }
        }
        assertTrue(small.joinToString("\n"), small.isEmpty())
    }

    @Test
    fun restaurantShowsItemsSearchAndTabs() {
        showSample()
        val a = launch(RestaurantActivity::class.java)
        assertTrue(a.findViewById<View>(R.id.items_content).isShown)
        val summary = a.findViewById<TextView>(R.id.search_summary)
        assertTrue(summary.text.toString(), summary.text.matches(Regex("\\d+ items · \\d+ categories")))

        a.findViewById<EditText>(R.id.search).setText("whopper")
        idle(Duration.ofMillis(400))
        assertTrue(summary.text.toString(), summary.text.matches(Regex("\\d+ of \\d+ items match")))

        a.findViewById<View>(R.id.tab_menu).performClick()
        idle()
        assertTrue(a.findViewById<View>(R.id.menu_panel).isShown)
        assertFalse(a.findViewById<View>(R.id.main_scroll).isShown)

        a.findViewById<View>(R.id.tab_photos).performClick()
        idle()
        assertTrue(a.findViewById<View>(R.id.photos_content).isShown)
        assertEquals("No photos available for this place.", a.findViewById<TextView>(R.id.photos_message).text.toString())
    }

    @Test
    fun googleDataIsAttributedAndTheDemoIsNot() {
        showSample()
        val a = launch(RestaurantActivity::class.java)
        assertTrue(a.findViewById<View>(R.id.attribution).isShown)
        // The Menu tab hides the header, so the place name and its attribution move to the top bar.
        a.findViewById<View>(R.id.tab_menu).performClick()
        idle()
        val top = a.findViewById<View>(R.id.top_attribution)
        assertTrue(top.isShown && top.alpha == 1f)

        MenuRepository.showDemo(app)
        val demo = launch(RestaurantActivity::class.java)
        assertEquals(View.GONE, demo.findViewById<View>(R.id.attribution).visibility)
        assertEquals(View.GONE, demo.findViewById<View>(R.id.top_attribution).visibility)
    }

    @Test
    fun selectedTabIsMarkedWithEnoughContrast() {
        showSample()
        for (theme in listOf(Prefs.THEME_DARK, Prefs.THEME_LIGHT)) {
            Prefs.setTheme(app, theme)
            val a = launch(RestaurantActivity::class.java)
            // The pill is faint against the track; the accent mark under the label carries the state.
            val indicator = a.findViewById<View>(R.id.tab_indicator).background as LayerDrawable
            assertEquals(2, indicator.numberOfLayers)
            val ratio = contrast(a.getColor(R.color.accent_text), a.getColor(R.color.tab_indicator))
            assertTrue("theme $theme: mark contrast $ratio", ratio >= 3.0)
            // The selected chip's text on its tonal fill, over the bar it sits on: body-text contrast.
            val fill = over(a.getColor(R.color.accent_soft), a.getColor(R.color.bg))
            val chip = contrast(a.getColor(R.color.chip_text_selected), fill)
            assertTrue("theme $theme: selected chip contrast $chip", chip >= 4.5)
        }
    }

    @Test
    fun quickActionTileNamesTheChainNotTheGooglePlace() {
        showSample("Burger King - 1200 Main St")
        val a = launch(MainActivity::class.java)
        val tile = a.findViewById<View>(R.id.action_menu)
        assertEquals("Burger King", tile.findViewById<TextView>(R.id.action_subtitle).text.toString())
        // The place name itself shows only in the status card, next to its Google Maps credit.
        assertTrue(a.findViewById<View>(R.id.status_attribution).isShown)
    }

    @Test
    fun carBannerCreditsGoogleMaps() {
        Notifier.arrival(app, sample(), carBanner = true)
        val n = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals("Google Maps", n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        val car = CarAppExtender(n).contentText.toString()
        assertTrue(car, car.endsWith("Google Maps"))
    }

    @Test
    fun failedMenuPageShowsRetryInsteadOfTheBrowserError() {
        val url = showSample().menuUrl
        assertNotNull(url)
        val a = launch(RestaurantActivity::class.java)
        a.findViewById<View>(R.id.tab_menu).performClick()
        idle()
        val web = a.findViewById<FrameLayout>(R.id.web_container).getChildAt(0) as WebView
        val client = shadowOf(web).webViewClient
        val progress = a.findViewById<View>(R.id.menu_progress)
        val card = a.findViewById<View>(R.id.menu_message_card)
        val retry = a.findViewById<View>(R.id.menu_retry)
        assertTrue(progress.isShown)

        // A failed image or script doesn't count; a failed page does.
        client.onReceivedError(web, request(url!!, mainFrame = false), null)
        assertEquals(View.VISIBLE, web.visibility)
        client.onReceivedError(web, request(url, mainFrame = true), null)
        assertEquals(View.INVISIBLE, web.visibility)
        assertTrue(card.isShown && retry.isShown)
        assertFalse(progress.isShown)

        // Try again: the card goes, progress shows, and the page shows once it has loaded.
        retry.performClick()
        assertFalse(card.isShown)
        assertTrue(progress.isShown)
        shadowOf(web).webChromeClient.onProgressChanged(web, 60)
        assertTrue(progress.isShown)
        client.onPageFinished(web, url)
        assertEquals(View.VISIBLE, web.visibility)
        assertFalse(progress.isShown)
    }

    // ---- Helpers ----

    private fun assertTilesLaidOut(a: Activity) {
        val tiles = listOf(R.id.action_detect, R.id.action_menu, R.id.action_drive, R.id.action_settings)
            .map { a.findViewById<View>(it) }
        for (t in tiles) {
            val title = t.findViewById<TextView>(R.id.action_title).text
            assertTrue("$title tile is ${t.width}x${t.height}px", t.height >= dp(a, 128) && t.width >= dp(a, 120))
            assertTrue(title.isNotBlank())
        }
        assertEquals(tiles[0].height, tiles[1].height)
        assertEquals(tiles[2].height, tiles[3].height)
        assertTrue(abs(tiles[0].width - tiles[1].width) <= 1)
    }

    /** Shows a made-up Burger King (Google-sourced, not the demo) through the public chooser path. */
    private fun showSample(name: String = "Burger King"): Restaurant {
        MenuRepository.showDemo(app)
        val r = sample(name)
        MenuRepository.choose(app, r)
        return r
    }

    private fun sample(name: String = "Burger King") = Restaurant(
        id = "test-place",
        name = name,
        address = "1200 Main St, Springfield, IL 62701",
        lat = 39.8,
        lng = -89.6,
        rating = 4.1,
        category = "Fast food restaurant",
        photos = emptyList(),
        websiteUri = null,
        mapsUri = "https://maps.google.com/?cid=1",
        menuUrl = ChainMenus.get(app).menuUrlFor(name),
        prices = ChainPrices.get(app).forPlace(name),
    )

    private fun <T : Activity> launch(screen: Class<T>): T {
        val a = Robolectric.buildActivity(screen).setup().get()
        idle(Duration.ofMillis(300))
        return a
    }

    private fun idle(d: Duration? = null) {
        val looper = shadowOf(Looper.getMainLooper())
        if (d != null) looper.idleFor(d) else looper.idle()
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun isNight(a: Activity) =
        (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun uiModeManager() = app.getSystemService(UiModeManager::class.java)

    /** A translucent color composited over an opaque one. */
    private fun over(top: Int, under: Int): Int {
        val alpha = (top ushr 24) / 255.0
        fun mix(shift: Int) = ((top shr shift and 0xFF) * alpha + (under shr shift and 0xFF) * (1 - alpha)).roundToInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** WCAG contrast ratio of two opaque colors. */
    private fun contrast(a: Int, b: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        fun luminance(c: Int) =
            0.2126 * channel(c shr 16 and 0xFF) + 0.7152 * channel(c shr 8 and 0xFF) + 0.0722 * channel(c and 0xFF)
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun descendants(v: View): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { descendants(g.getChildAt(it)) } }
            ?: emptyList())

    /** Everything a person or a screen reader could get from the screen: texts and descriptions. */
    private fun screenText(a: Activity): List<String> = descendants(a.window.decorView).flatMap { v ->
        listOfNotNull((v as? TextView)?.text?.toString(), v.contentDescription?.toString())
    }

    private fun describe(v: View): String {
        val id = if (v.id != View.NO_ID) runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() else null
        return id ?: "${v.javaClass.simpleName} \"${(v as? TextView)?.text ?: v.contentDescription ?: ""}\""
    }

    private fun request(url: String, mainFrame: Boolean) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
