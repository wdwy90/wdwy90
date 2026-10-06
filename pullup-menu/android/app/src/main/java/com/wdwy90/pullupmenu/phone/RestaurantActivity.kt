package com.wdwy90.pullupmenu.phone

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.Restaurant
import kotlinx.coroutines.launch
import java.net.URLEncoder

/** Phone screen: the chain's item list, the chain's menu page in-app, and Google photos with credits. */
class RestaurantActivity : ComponentActivity() {

    private enum class Tab { PRICES, MENU, PHOTOS }

    private var shown: Restaurant? = null
    private var tab = Tab.MENU
    private var web: WebView? = null
    private var photosLoadedFor: String? = null
    /** Restaurant whose menu page crashed the WebView renderer; not reloaded until another one is shown. */
    private var renderGoneFor: String? = null

    private lateinit var tabPrices: Button
    private lateinit var tabMenu: Button
    private lateinit var tabPhotos: Button
    private lateinit var menuMessage: TextView

    /** Back walks the menu page's history while the Menu tab is shown. */
    private val webBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            web?.goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_restaurant)
        onBackPressedDispatcher.addCallback(this, webBack)

        tabPrices = findViewById(R.id.tab_prices)
        tabMenu = findViewById(R.id.tab_menu)
        tabPhotos = findViewById(R.id.tab_photos)
        menuMessage = findViewById(R.id.menu_message)
        tabPrices.setOnClickListener { selectTab(Tab.PRICES) }
        tabMenu.setOnClickListener { selectTab(Tab.MENU) }
        tabPhotos.setOnClickListener { selectTab(Tab.PHOTOS) }
        findViewById<View>(R.id.tabs).visibility = View.GONE

        lifecycleScope.launch {
            MenuRepository.state.collect { s ->
                if (s is MenuRepository.State.Found) show(s.restaurant)
                else if (s !is MenuRepository.State.Searching && shown == null) {
                    findViewById<TextView>(R.id.name).text = "No fast-food place detected yet"
                }
            }
        }
        // An auto-detected card withdrawn as a red light: close it here too (no replay, so only live withdrawals).
        lifecycleScope.launch {
            MenuRepository.dismissed.collect { id -> if (id == shown?.id) finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyWebView()
        super.onDestroy()
    }

    private fun show(r: Restaurant) {
        if (r.id == shown?.id) return
        shown = r
        findViewById<TextView>(R.id.name).text = r.name
        findViewById<TextView>(R.id.details).text = listOfNotNull(
            if (r.isDemo) "Demo" else null,
            r.category, r.rating?.let { "★ %.1f".format(it) }, r.address,
        ).joinToString("  ·  ")
        // The demo is sample data, not Google data: no Google Maps attribution for it.
        findViewById<View>(R.id.attribution).visibility = if (r.isDemo) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tabs).visibility = View.VISIBLE

        showPrices(r)
        setUpMenu(r)
        findViewById<LinearLayout>(R.id.photos_list).removeAllViews()
        photosLoadedFor = null
        selectTab(if (r.prices != null) Tab.PRICES else Tab.MENU)
    }

    private fun selectTab(t: Tab) {
        tab = t
        findViewById<View>(R.id.prices_panel).visibility = if (t == Tab.PRICES) View.VISIBLE else View.GONE
        findViewById<View>(R.id.menu_panel).visibility = if (t == Tab.MENU) View.VISIBLE else View.GONE
        findViewById<View>(R.id.photos_panel).visibility = if (t == Tab.PHOTOS) View.VISIBLE else View.GONE
        for ((button, forTab) in listOf(tabPrices to Tab.PRICES, tabMenu to Tab.MENU, tabPhotos to Tab.PHOTOS)) {
            button.isSelected = forTab == t
            button.alpha = if (forTab == t) 1f else 0.6f
        }
        val r = shown
        if (r != null) {
            // Created only when needed: saves WebView start-up, and photo requests are billed per load.
            if (t == Tab.MENU && r.menuUrl != null && web == null && renderGoneFor != r.id) loadMenu(r.menuUrl)
            if (t == Tab.PHOTOS && photosLoadedFor != r.id) showPhotos(r)
        }
        updateBack()
    }

    // ---- Prices ----

    private fun showPrices(r: Restaurant) {
        val list = findViewById<LinearLayout>(R.id.prices_list)
        list.removeAllViews()
        val p = r.prices
        if (p == null) {
            list.addView(text("No item list for this restaurant yet. Try the Menu tab."))
            return
        }
        list.addView(text("${p.chain} menu items").apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        var section: String? = null
        for (item in p.items) {
            if (item.category != null && item.category != section) {
                section = item.category
                list.addView(text(item.category).apply {
                    textSize = 15f
                    alpha = 0.7f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(20), 0, 0)
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            }
            row.addView(text(item.name).apply {
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            list.addView(row)
            item.note?.let { list.addView(text(it).apply { alpha = 0.7f }) }
        }
        list.addView(text(p.disclaimer).apply {
            alpha = 0.7f
            setPadding(0, dp(20), 0, 0)
        })
        list.addView(link("Source: ${p.sourceName}") { open(p.sourceUrl) })
    }

    // ---- Menu ----

    private fun setUpMenu(r: Restaurant) {
        destroyWebView()
        val menuUrl = r.menuUrl
        val browser = findViewById<Button>(R.id.open_browser)
        val maps = findViewById<Button>(R.id.open_maps_menu)
        val website = findViewById<Button>(R.id.open_website)
        val search = findViewById<Button>(R.id.search_menu)

        menuMessage.text = "No in-app menu for this restaurant yet. Try these:"
        menuMessage.visibility = if (menuUrl == null) View.VISIBLE else View.GONE
        browser.visibility = if (menuUrl != null) View.VISIBLE else View.GONE
        browser.setOnClickListener { menuUrl?.let { open(web?.url ?: it) } }

        // Google Maps' place page has a Menu tab (with dish photos) when Google has one.
        maps.text = if (menuUrl != null) "Maps" else "Menu (Maps)"
        maps.isEnabled = r.mapsUri != null
        maps.setOnClickListener { r.mapsUri?.let { open(it) } }

        website.visibility = if (menuUrl == null && r.websiteUri != null) View.VISIBLE else View.GONE
        website.setOnClickListener { r.websiteUri?.let { open(it) } }

        search.text = if (menuUrl != null) "Search" else "Search menu"
        search.setOnClickListener {
            val q = if (r.isDemo) "${r.name.removeSuffix(" (demo)")} menu" else "${r.name} ${r.address} menu"
            open("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
        }
    }

    private fun loadMenu(url: String) {
        // Throws while the WebView provider is being updated; fall back to the buttons then.
        val w = try {
            WebView(this)
        } catch (e: Exception) {
            null
        }
        if (w == null) {
            menuMessage.text = "The menu can't load inside the app right now. Open it in your browser."
            menuMessage.visibility = View.VISIBLE
            return
        }
        setUpWebView(w)
        findViewById<FrameLayout>(R.id.web_container).addView(
            w, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        web = w
        w.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled") // chain menu pages need JS; no JavascriptInterface is exposed
    private fun setUpWebView(w: WebView) {
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.setGeolocationEnabled(false)
        w.settings.allowContentAccess = false
        w.settings.allowFileAccess = false
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                when (uri.scheme?.lowercase()) {
                    "http", "https" -> return false // stay in the WebView
                }
                // tel:, mailto:, market: etc. open outside; ignore odd schemes from ad iframes.
                if (request.isForMainFrame) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (e: ActivityNotFoundException) {
                        // Nothing can handle it; stay on the page.
                    }
                }
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                updateBack()
            }

            // The default (false) kills our whole process, taking the car screen and watching with it.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === web) {
                    renderGoneFor = shown?.id
                    destroyWebView()
                    menuMessage.text = "The menu can't load inside the app right now. Open it in your browser."
                    menuMessage.visibility = View.VISIBLE
                }
                return true
            }
        }
    }

    private fun destroyWebView() {
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
        updateBack()
    }

    private fun updateBack() {
        webBack.isEnabled = tab == Tab.MENU && web?.canGoBack() == true
    }

    // ---- Photos ----

    private fun showPhotos(r: Restaurant) {
        photosLoadedFor = r.id
        val list = findViewById<LinearLayout>(R.id.photos_list)
        list.removeAllViews()
        val photos = r.photos.take(4)
        if (photos.isEmpty()) {
            list.addView(text("No photos available for this place."))
            return
        }
        val width = resources.displayMetrics.widthPixels.coerceAtMost(1200)
        for (ph in photos) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Photo of ${r.name}"
                // Lets people see the source photo on Google Maps (its own page when Places gives one).
                (ph.mapsUri ?: r.mapsUri)?.let { u -> setOnClickListener { open(u) } }
            }
            // Google requires the photographer's name (and profile link) with each photo.
            val author = ph.authorName
            val credit = if (author != null) {
                val uri = ph.authorUri
                if (uri != null) link("Photo: $author") { open(uri) } else text("Photo: $author")
            } else {
                text("Photo from Google Maps")
            }
            credit.setPadding(0, dp(4), 0, dp(20))
            list.addView(iv)
            list.addView(credit)
            lifecycleScope.launch {
                val bmp = MenuRepository.photo(this@RestaurantActivity, ph.name, width)
                if (shown?.id != r.id) return@launch
                if (bmp != null) {
                    iv.setImageBitmap(bmp)
                } else {
                    list.removeView(iv)
                    list.removeView(credit)
                    if (list.childCount == 0) list.addView(text("No photos available for this place."))
                }
            }
        }
    }

    // ---- Helpers ----

    private fun text(s: String) = TextView(this).apply { text = s }

    private fun link(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s
        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show()
        }
    }
}
