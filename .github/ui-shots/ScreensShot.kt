package com.wdwy90.pullupmenu.shots

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.ScrollView
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.ChainMenus
import com.wdwy90.pullupmenu.core.ChainPrices
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.PlacePhoto
import com.wdwy90.pullupmenu.core.Prefs
import com.wdwy90.pullupmenu.core.Restaurant
import com.wdwy90.pullupmenu.phone.MainActivity
import com.wdwy90.pullupmenu.phone.RestaurantActivity
import com.wdwy90.pullupmenu.phone.SettingsActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

/**
 * Renders the real phone screens (real activities, layouts, themes) to PNGs for a visual review.
 * Sample data only: a made-up Burger King location and generated gradient "photos".
 * Lives on the ui-shots branch only; not part of the app's build.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ScreensShot {
    private val app get() = RuntimeEnvironment.getApplication()
    private val out = File(System.getProperty("shots.dir") ?: "build/shots").apply { mkdirs() }
    private var suffix = ""

    @Test fun dark() = run(Prefs.THEME_DARK, "dark")

    @Test fun light() = run(Prefs.THEME_LIGHT, "light")

    @Test fun largeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        run(Prefs.THEME_DARK, "dark-large-text", short = true)
    }

    private fun run(theme: Int, suffix: String, short: Boolean = false) {
        this.suffix = suffix
        Prefs.setTheme(app, theme)
        setState(MenuRepository.State.Idle)

        step("01-home-permission") {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            capture(a.window, it)
        }
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        step("02-home-ready") {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            capture(a.window, it)
        }
        val r = sample()
        Prefs.setLastDetected(app, "Burger King", System.currentTimeMillis() - 3 * 60_000)
        setState(MenuRepository.State.Found(r, emptyList(), System.currentTimeMillis(), auto = true))
        step("03-home-found") {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            capture(a.window, it)
        }
        step("04-settings") {
            val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
            capture(a.window, it)
            captureFull(a, R.id.scroll, "05-settings-full")
        }
        if (short) return
        step("06-restaurant") {
            val c = Robolectric.buildActivity(RestaurantActivity::class.java).setup()
            val a = c.get()
            capture(a.window, it)
            val scroll = a.findViewById<ScrollView>(R.id.main_scroll)
            scroll.scrollTo(0, dp(a, 560))
            capture(a.window, "07-restaurant-scrolled")
            a.findViewById<EditText>(R.id.search).setText("chicken")
            idle(Duration.ofMillis(400))
            capture(a.window, "08-restaurant-search")
            a.findViewById<EditText>(R.id.search).setText("")
            idle(Duration.ofMillis(400))
            a.findViewById<View>(R.id.tab_photos).performClick()
            idle(Duration.ofMillis(800))
            capture(a.window, "09-restaurant-photos")
            a.findViewById<View>(R.id.tab_menu).performClick()
            idle(Duration.ofMillis(800))
            capture(a.window, "10-restaurant-menu")
            a.findViewById<View>(R.id.tab_items).performClick()
            idle(Duration.ofMillis(800))
            scroll.scrollTo(0, 0)
            a.findViewById<View>(R.id.hero).performClick()
            idle(Duration.ofMillis(800))
            val dialog: Dialog? = ShadowDialog.getLatestDialog()
            if (dialog?.window != null) capture(dialog.window!!, "11-photo-viewer")
        }
        step("12-demo") {
            MenuRepository.showDemo(app)
            val a = Robolectric.buildActivity(RestaurantActivity::class.java).setup().get()
            capture(a.window, it)
        }
    }

    /** Runs one screen; a failure is recorded as a text file instead of stopping the other screens. */
    private fun step(name: String, block: (String) -> Unit) {
        try {
            block(name)
        } catch (t: Throwable) {
            File(out, "$name-$suffix.FAILED.txt").writeText(t.stackTraceToString())
        }
    }

    private fun sample(): Restaurant {
        val photos = (1..4).map { i ->
            PlacePhoto("places/sample/photos/$i", "Sample Photographer $i", "https://example.com/p$i", "https://maps.google.com/?q=$i")
        }
        val width = minOf(app.resources.displayMetrics.widthPixels, app.resources.displayMetrics.heightPixels).coerceIn(480, 1200)
        val colors = listOf(0xFF8E3B1F.toInt(), 0xFF2F5D50.toInt(), 0xFF3B4A8E.toInt(), 0xFF7A6A2F.toInt())
        photos.forEachIndexed { i, ph -> putPhoto("${ph.name}@$width", fakePhoto(width, if (i % 2 == 0) width * 3 / 4 else width * 4 / 3, colors[i], i + 1)) }
        return Restaurant(
            id = "sample-place",
            name = "Burger King",
            address = "1200 Main St, Springfield, IL 62701",
            lat = 39.8,
            lng = -89.6,
            rating = 4.1,
            category = "Fast food restaurant",
            photos = photos,
            websiteUri = "https://www.bk.com",
            mapsUri = "https://maps.google.com/?cid=1",
            menuUrl = ChainMenus.get(app).menuUrlFor("Burger King"),
            prices = ChainPrices.get(app).forPlace("Burger King"),
        )
    }

    private fun fakePhoto(w: Int, h: Int, color: Int, n: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), color, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.WHITE
        p.textSize = w / 12f
        c.drawText("Sample photo $n", w / 12f, h / 2f, p)
        return b
    }

    @Suppress("UNCHECKED_CAST")
    private fun setState(s: MenuRepository.State) {
        val f = MenuRepository::class.java.getDeclaredField("_state")
        f.isAccessible = true
        (f.get(MenuRepository) as MutableStateFlow<MenuRepository.State>).value = s
    }

    @Suppress("UNCHECKED_CAST")
    private fun putPhoto(key: String, b: Bitmap) {
        val f = MenuRepository::class.java.getDeclaredField("photoCache")
        f.isAccessible = true
        (f.get(MenuRepository) as LruCache<String, Bitmap>).put(key, b)
    }

    private fun idle(d: Duration? = null) {
        val looper = shadowOf(Looper.getMainLooper())
        if (d != null) looper.idleFor(d) else looper.idle()
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun capture(window: Window, name: String) {
        idle(Duration.ofMillis(300))
        val view = window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var ok = false
        try {
            PixelCopy.request(window, bmp, { r -> ok = r == PixelCopy.SUCCESS }, Handler(Looper.getMainLooper()))
            idle()
        } catch (t: Throwable) {
            ok = false
        }
        if (!ok) view.draw(Canvas(bmp))
        save(bmp, name + if (ok) "" else "-sw")
    }

    /** The whole scrollable page, drawn in software (no elevation shadows). */
    private fun captureFull(a: Activity, scrollId: Int, name: String) {
        val scroll = a.findViewById<ScrollView>(scrollId)
        val content = scroll.getChildAt(0)
        val w = scroll.width
        content.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val h = content.measuredHeight
        content.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        a.window.decorView.background?.let { it.setBounds(0, 0, w, h); it.draw(c) }
        content.draw(c)
        save(bmp, name)
    }

    private fun save(bmp: Bitmap, name: String) {
        File(out, "$name-$suffix.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
