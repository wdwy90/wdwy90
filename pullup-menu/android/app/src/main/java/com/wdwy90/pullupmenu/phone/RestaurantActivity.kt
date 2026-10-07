package com.wdwy90.pullupmenu.phone

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.ChainPrices
import com.wdwy90.pullupmenu.core.ItemGroup
import com.wdwy90.pullupmenu.core.ItemGroups
import com.wdwy90.pullupmenu.core.ItemSearch
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.PlacePhoto
import com.wdwy90.pullupmenu.core.PriceItem
import com.wdwy90.pullupmenu.core.Restaurant
import com.wdwy90.pullupmenu.core.WebLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Phone screen for the detected restaurant: a header (photo, name, rating, address, shortcuts) and
 * three tabs. Items: the chain's item list by category, searchable, with category chips. Menu: the
 * chain's menu page in-app. Photos: Google photos with credits and a full-screen viewer.
 * The tab bar scrolls with the header and then pins to the top.
 */
class RestaurantActivity : ThemedActivity() {

    private enum class Tab { ITEMS, MENU, PHOTOS }

    private var shown: Restaurant? = null
    private var tab = Tab.MENU
    private var web: WebView? = null
    private var photosLoadedFor: String? = null
    /** Restaurant whose menu page crashed the WebView renderer; not reloaded until another one is shown. */
    private var renderGoneFor: String? = null
    /** The menu page (or the page it navigated to) failed to load; the WebView is hidden behind a retry card. */
    private var menuLoadFailed = false

    // Items tab
    private var groups: List<ItemGroup> = emptyList()
    private var query = ""
    /** The one category open while not searching (accordion); null when all are collapsed, the default. */
    private var expanded: String? = null
    private val sections = LinkedHashMap<String, Section>()
    private val chipFor = HashMap<String, TextView>()
    private var currentSection: String? = null
    private var scrollAnimator: ValueAnimator? = null
    private var pinned = false

    /** A category card on the Items tab: its header row, the item list under it and the chevron. */
    private class Section(val card: View, val header: View, val body: View, val chevron: ImageView, val badge: TextView)
    private var searchJob: Job? = null
    private var settingSearchText = false

    private var heroJob: Job? = null
    private val photoJobs = ArrayList<Job>()
    private var viewer: PhotoViewer? = null
    private var chooser: AlertDialog? = null
    /**
     * Photos of the shown restaurant downloaded so far. Each load is billed, so the header, grid and
     * viewer reuse these rather than asking again (the app-wide cache may have dropped them).
     */
    private val bitmaps = HashMap<String, Bitmap>()

    /**
     * Photo download width. One size for the header, grid and viewer, so each photo is fetched (and
     * billed) once; the shorter screen side keeps it the same after rotation.
     */
    private val photoWidth: Int by lazy {
        val dm = resources.displayMetrics
        minOf(dm.widthPixels, dm.heightPixels).coerceIn(480, 1200)
    }

    private lateinit var tabBar: TabBar
    private lateinit var mainScroll: ScrollView
    private lateinit var mainContent: View
    private lateinit var sticky: ScrollForwardingLayout
    private lateinit var stickySpacer: View
    private lateinit var topTitle: TextView
    private lateinit var topAttribution: TextView
    private lateinit var nameView: TextView
    private lateinit var itemsContent: LinearLayout
    private lateinit var itemsList: LinearLayout
    private lateinit var photosContent: View
    private lateinit var menuPanel: View
    private lateinit var menuMessageCard: View
    private lateinit var menuMessage: TextView
    private lateinit var menuRetry: Button
    private lateinit var menuProgress: ProgressBar
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var search: EditText
    private lateinit var searchSummary: TextView

    /** Back walks the menu page's history while the Menu tab is shown. */
    private val webBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (menuLoadFailed) startMenuLoad()
            web?.goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_restaurant)
        applyInsets(findViewById(R.id.root))
        onBackPressedDispatcher.addCallback(this, webBack)

        mainScroll = findViewById(R.id.main_scroll)
        mainContent = findViewById(R.id.main_content)
        sticky = findViewById(R.id.sticky)
        stickySpacer = findViewById(R.id.sticky_spacer)
        topTitle = findViewById(R.id.top_title)
        topAttribution = findViewById(R.id.top_attribution)
        nameView = findViewById(R.id.name)
        itemsContent = findViewById(R.id.items_content)
        itemsList = findViewById(R.id.items_list)
        photosContent = findViewById(R.id.photos_content)
        menuPanel = findViewById(R.id.menu_panel)
        menuMessageCard = findViewById(R.id.menu_message_card)
        menuMessage = findViewById(R.id.menu_message)
        menuRetry = findViewById(R.id.menu_retry)
        menuProgress = findViewById(R.id.menu_progress)
        chipsScroll = findViewById(R.id.chips_scroll)
        chips = findViewById(R.id.chips)
        search = findViewById(R.id.search)
        searchSummary = findViewById(R.id.search_summary)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.not_here).setOnClickListener { chooseAnother() }
        menuRetry.setOnClickListener {
            startMenuLoad()
            web?.reload()
        }
        findViewById<View>(R.id.hero).clipToOutline = true
        tabBar = TabBar(findViewById(R.id.tabs)) { i -> selectTab(Tab.entries[i], animate = true) }

        // The tab bar floats above the scroll view; the spacer keeps its place in the content.
        // Drags that start on the bar scroll the content; taps never reach the content underneath.
        sticky.scrollTarget = mainScroll
        sticky.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) sticky.post { syncStickyHeight() }
        }
        mainContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSticky() }
        mainScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateSticky()
            trackSection()
        }
        // Screen readers: header, then the tab bar, then the tab's content.
        sticky.accessibilityTraversalAfter = R.id.header
        itemsContent.accessibilityTraversalAfter = R.id.sticky
        photosContent.accessibilityTraversalAfter = R.id.sticky
        menuPanel.accessibilityTraversalAfter = R.id.sticky

        setUpSearch()
        showEmpty()

        lifecycleScope.launch {
            MenuRepository.state.collect { s ->
                if (s is MenuRepository.State.Found) show(s.restaurant)
                else if (s !is MenuRepository.State.Searching && shown == null) showEmpty()
                updateNotHere()
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
        chooser?.dismiss()
        viewer?.dismiss()
        destroyWebView()
        super.onDestroy()
    }

    // ---- Restaurant ----

    private fun showEmpty() {
        findViewById<View>(R.id.empty_state).visibility = View.VISIBLE
        mainScroll.visibility = View.GONE
        menuPanel.visibility = View.GONE
        sticky.visibility = View.GONE
        setTopTitleAlpha(0f)
    }

    private fun show(r: Restaurant) {
        if (r.id == shown?.id) return
        shown = r
        viewer?.dismiss()
        viewer = null
        cancelPhotoLoads()
        bitmaps.clear()
        findViewById<View>(R.id.empty_state).visibility = View.GONE
        sticky.visibility = View.VISIBLE

        nameView.text = r.name
        topTitle.text = r.name
        val monogram = findViewById<TextView>(R.id.monogram)
        val initial = r.name.removeSuffix(" (demo)").firstOrNull { it.isLetterOrDigit() }
        monogram.text = initial?.uppercase().orEmpty()
        monogram.visibility = if (initial != null) View.VISIBLE else View.GONE

        showMeta(r)
        findViewById<TextView>(R.id.address).apply {
            text = r.address
            visibility = if (r.address.isBlank()) View.GONE else View.VISIBLE
        }

        val navigate = findViewById<Button>(R.id.navigate)
        val maps = findViewById<Button>(R.id.open_maps)
        navigate.visibility = if (r.isDemo) View.GONE else View.VISIBLE
        navigate.setOnClickListener { navigateTo(r) }
        maps.visibility = if (r.mapsUri != null) View.VISIBLE else View.GONE
        maps.setOnClickListener { r.mapsUri?.let { open(it) } }
        updateNotHere()
        // The demo is sample data, not Google data: no Google Maps attribution for it.
        findViewById<View>(R.id.attribution).visibility = if (r.isDemo) View.GONE else View.VISIBLE
        topAttribution.visibility = if (r.isDemo) View.GONE else View.VISIBLE

        showHero(r)
        setUpItems(r)
        setUpMenu(r)
        findViewById<ColumnGrid>(R.id.photo_grid).removeAllViews()
        photosLoadedFor = null
        mainScroll.scrollTo(0, 0)
        selectTab(if (r.prices != null) Tab.ITEMS else Tab.MENU, animate = false)
    }

    /** The other places found around the car with the one shown (nearest first), while it's the current find. */
    private fun otherNearby(): List<Restaurant> {
        val s = MenuRepository.state.value as? MenuRepository.State.Found ?: return emptyList()
        return if (s.restaurant.id == shown?.id) s.others else emptyList()
    }

    /** "Not here?" shows only when there's another place to pick. */
    private fun updateNotHere() {
        val notHere = findViewById<View>(R.id.not_here)
        notHere.isGone = otherNearby().isEmpty()
        val actions = findViewById<ViewGroup>(R.id.header_actions)
        actions.isVisible = (0 until actions.childCount).any { actions.getChildAt(it).isVisible }
        if (notHere.isGone) chooser?.dismiss()
    }

    /** Strip mall case: pick which of the places found nearby the car is at, as on the car screen. */
    private fun chooseAnother() {
        val others = otherNearby()
        if (others.isEmpty()) return
        val labels = others.map { r -> "${r.name.ifBlank { "Restaurant" }} · ${(r.distanceMeters * 3.281).roundToInt()} ft" }
        // The names are Google Maps data: credited under the title.
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
            addView(TextView(this@RestaurantActivity).apply {
                setTextAppearance(R.style.Text_Title)
                text = "Which one are you at?"
            })
            addView(TextView(this@RestaurantActivity).apply {
                text = getString(R.string.google_maps)
                textSize = 12f
                setTextColor(getColor(R.color.gmp_attribution))
                setSingleLine(true)
            })
        }
        chooser?.dismiss()
        chooser = AlertDialog.Builder(this)
            .setCustomTitle(title)
            .setItems(labels.toTypedArray()) { _, i -> MenuRepository.choose(this, others[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMeta(r: Restaurant) {
        val meta = SpannableStringBuilder()
        val spoken = ArrayList<String>()
        r.rating?.let { rating ->
            meta.append("★", ForegroundColorSpan(getColor(R.color.star)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            meta.append(" %.1f".format(rating))
            spoken += "Rated %.1f out of 5".format(rating)
        }
        val category = r.category?.ifBlank { null } ?: "Fast food"
        if (meta.isNotEmpty()) meta.append("  ·  ")
        meta.append(category)
        spoken += category
        if (r.isDemo) {
            meta.append("  ·  Demo")
            spoken += "Demo"
        }
        val view = findViewById<TextView>(R.id.meta)
        view.text = meta
        view.contentDescription = spoken.joinToString(", ")

        // Open or closed only when Google said so, and how far the car was from the door when it was found.
        val status = SpannableStringBuilder()
        val statusSpoken = ArrayList<String>()
        r.openStatus?.let { label ->
            val color = getColor(if (label == "Open now") R.color.success else R.color.warning)
            status.append(label, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            status.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            statusSpoken += label
        }
        if (!r.isDemo && r.distanceMeters > 0) {
            val feet = (r.distanceMeters * 3.281).roundToInt()
            if (status.isNotEmpty()) status.append("  ·  ")
            status.append("$feet ft")
            statusSpoken += "$feet feet away"
        }
        findViewById<TextView>(R.id.status).apply {
            text = status
            contentDescription = statusSpoken.joinToString(", ")
            isGone = status.isEmpty()
        }
    }

    private fun showHero(r: Restaurant) {
        heroJob?.cancel()
        val hero = findViewById<View>(R.id.hero)
        val image = findViewById<ImageView>(R.id.hero_image)
        val placeholder = findViewById<View>(R.id.hero_placeholder)
        val scrim = findViewById<View>(R.id.hero_scrim)
        val credit = findViewById<TextView>(R.id.hero_credit)
        hero.alpha = 1f
        image.setImageDrawable(null)
        image.visibility = View.INVISIBLE
        placeholder.visibility = View.VISIBLE
        scrim.visibility = View.GONE
        credit.visibility = View.GONE
        hero.setOnClickListener(null)
        hero.isClickable = false
        hero.contentDescription = null
        hero.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        // No photo from Google: no empty frame either; the name row carries the identity.
        val ph = r.photos.firstOrNull()
        hero.isGone = ph == null
        if (ph == null) return
        heroJob = lifecycleScope.launch {
            // Photo loads are billed: one found while this screen is in the background waits until it's seen.
            lifecycle.withStarted {}
            val pulse = hero.loadingPulse()
            val bmp = try {
                loadPhoto(ph)
            } finally {
                pulse?.cancel()
                hero.alpha = 1f
            }
            if (bmp == null || shown?.id != r.id) return@launch
            image.setImageBitmap(bmp)
            image.fadeIn()
            placeholder.visibility = View.GONE
            setCredit(credit, ph)
            scrim.visibility = View.VISIBLE
            credit.visibility = View.VISIBLE
            hero.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            hero.contentDescription = "Photo of ${r.name}. Opens full screen."
            hero.setOnClickListener { openViewer(r, 0) }
        }
    }

    private fun navigateTo(r: Restaurant) {
        // Google Maps URLs open the Maps app when it's installed, otherwise the browser.
        val url = "https://www.google.com/maps/dir/?api=1&destination=${r.lat},${r.lng}" +
            "&destination_place_id=" + URLEncoder.encode(r.id, "UTF-8") + "&travelmode=driving"
        open(url)
    }

    // ---- Tabs ----

    private fun selectTab(t: Tab, animate: Boolean) {
        val wasPinned = tab != Tab.MENU && mainScroll.scrollY > stickySpacer.top
        tab = t
        tabBar.select(t.ordinal, animate)
        itemsContent.visibility = if (t == Tab.ITEMS) View.VISIBLE else View.GONE
        photosContent.visibility = if (t == Tab.PHOTOS) View.VISIBLE else View.GONE
        mainScroll.visibility = if (t == Tab.MENU) View.GONE else View.VISIBLE
        menuPanel.visibility = if (t == Tab.MENU) View.VISIBLE else View.GONE
        chipsScroll.visibility = if (t == Tab.ITEMS && chips.childCount >= 2) View.VISIBLE else View.GONE
        if (t != Tab.ITEMS) hideKeyboard()
        // Keep the tab bar where it was: a new tab starts right under the pinned bar.
        if (wasPinned && t != Tab.MENU) mainScroll.post { mainScroll.scrollTo(0, stickySpacer.top) }

        val r = shown
        if (r != null) {
            // Created only when needed: saves WebView start-up, and photo requests are billed per load.
            if (t == Tab.MENU && r.menuUrl != null && web == null && renderGoneFor != r.id) loadMenu(r.menuUrl)
            if (t == Tab.PHOTOS && photosLoadedFor != r.id) showPhotos(r)
        }
        updateBack()
        updateSticky()
    }

    private fun syncStickyHeight() {
        val h = sticky.height
        if (stickySpacer.layoutParams.height != h) {
            stickySpacer.layoutParams = stickySpacer.layoutParams.apply { height = h }
        }
        if (menuPanel.paddingTop != h) menuPanel.setPadding(0, h, 0, 0)
        updateSticky()
    }

    /** Moves the floating tab bar with the content until it reaches the top, then pins it. */
    private fun updateSticky() {
        if (tab == Tab.MENU || mainScroll.visibility != View.VISIBLE) {
            sticky.translationY = 0f
            setPinned(true)
            setTopTitleAlpha(if (shown != null) 1f else 0f)
            return
        }
        val y = mainScroll.scrollY
        val offset = (stickySpacer.top - y).coerceAtLeast(0)
        sticky.translationY = offset.toFloat()
        setPinned(offset == 0 && y > 0)
        val nameBottom = nameView.topIn(mainContent) + nameView.height
        setTopTitleAlpha(((y - nameBottom + dp(16)) / dp(24).toFloat()).coerceIn(0f, 1f))
    }

    /** A pinned bar gets a shadow (light theme) and a hairline (both themes) along its bottom edge. */
    private fun setPinned(pinned: Boolean) {
        if (pinned == this.pinned) return
        this.pinned = pinned
        val hairline = findViewById<View>(R.id.sticky_hairline)
        val ms = if (motionEnabled()) 150L else 0L
        hairline.animate().alpha(if (pinned) 1f else 0f).setDuration(ms).start()
        sticky.animate().z(if (pinned) dp(3).toFloat() else 0f).setDuration(ms).start()
    }

    /**
     * The top bar's name is Google data too, so its Google Maps attribution shows with it. Screen
     * readers get the name from the header, except on the Menu tab, which hides the header.
     */
    private fun setTopTitleAlpha(alpha: Float) {
        topTitle.alpha = alpha
        topAttribution.alpha = alpha
        val important = if (tab == Tab.MENU && alpha > 0f) {
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        topTitle.importantForAccessibility = important
        topAttribution.importantForAccessibility = important
    }

    private fun updateBack() {
        webBack.isEnabled = tab == Tab.MENU && web?.canGoBack() == true
    }

    // ---- Items ----

    private fun setUpSearch() {
        val clear = findViewById<View>(R.id.search_clear)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val show = !s.isNullOrEmpty()
                if (show && clear.visibility != View.VISIBLE && motionEnabled()) {
                    clear.alpha = 0f
                    clear.scaleX = 0.8f
                    clear.scaleY = 0.8f
                    clear.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(EASE).start()
                }
                clear.visibility = if (show) View.VISIBLE else View.GONE
                if (settingSearchText) return
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(150) // filter once typing pauses
                    query = s?.toString().orEmpty()
                    renderItems()
                }
            }
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) hideKeyboard()
            false
        }
        clear.setOnClickListener {
            search.text.clear()
            search.requestFocus()
        }
    }

    private fun setUpItems(r: Restaurant) {
        searchJob?.cancel()
        settingSearchText = true
        search.text.clear()
        settingSearchText = false
        query = ""
        expanded = null // a new restaurant starts with every category collapsed
        currentSection = null
        scrollAnimator?.cancel()

        val p = r.prices
        val searchBox = findViewById<View>(R.id.search_box)
        val footer = findViewById<View>(R.id.items_footer)
        if (p == null) {
            groups = emptyList()
            searchBox.visibility = View.GONE
            footer.visibility = View.GONE
            searchSummary.visibility = View.GONE
            renderItems()
            return
        }
        groups = ItemGroups.byCategory(p.items)
        searchBox.visibility = View.VISIBLE
        footer.visibility = View.VISIBLE
        searchSummary.visibility = View.VISIBLE
        findViewById<TextView>(R.id.disclaimer).text = p.disclaimer
        findViewById<Button>(R.id.source).apply {
            text = "Source: ${p.sourceName}"
            setOnClickListener { open(p.sourceUrl) }
        }
        renderItems()
    }

    private fun renderItems() {
        itemsList.removeAllViews()
        sections.clear()

        if (groups.isEmpty()) {
            itemsList.addView(noItemsCard())
            renderChips(emptyList())
            return
        }
        val searching = !ItemSearch.isBlank(query)
        val visible = ItemSearch.filter(groups, query)
        if (!searching && groups.size == 1) expanded = groups[0].title
        val total = groups.sumOf { it.items.size }
        val count = visible.sumOf { it.items.size }
        searchSummary.text = when {
            !searching -> "$total items · ${groups.size} categories"
            count == 0 -> "No items match “${query.trim()}”."
            else -> "$count of $total items match"
        }
        for (g in visible) {
            // Search results are always open, so the matches show; otherwise only the one open category.
            val section = sectionCard(g, open = searching || g.title == expanded)
            itemsList.addView(section.card)
            sections[g.title] = section
        }
        renderChips(visible)
    }

    /**
     * A category card: a row with the category's name and item count that opens the items under it.
     * One category is open at a time (tapping another closes the first), so a long menu stays a
     * short list of categories.
     */
    private fun sectionCard(g: ItemGroup, open: Boolean): Section {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_section)
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setBackgroundResource(R.drawable.bg_row)
            isClickable = true
            isFocusable = true
        }
        header.addView(ImageView(this).apply {
            setImageResource(CategoryIcons.iconFor(g.title))
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
            setBackgroundResource(R.drawable.bg_icon_tile)
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
        })
        header.addView(styled(R.style.Text_Title, g.title).apply {
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val badge = styled(R.style.Text_Label, g.items.size.toString()).apply {
            setPadding(dp(10), dp(3), dp(10), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        }
        header.addView(badge)
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_down)
            scaleType = ImageView.ScaleType.CENTER
            rotation = if (open) 180f else 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) }
        }
        header.addView(chevron)
        markOpen(badge, chevron, open)

        // The rows keep their size while the body animates open or closed (RevealLayout clips them).
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(6))
        }
        g.items.forEachIndexed { i, item ->
            if (i > 0) rows.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1) / 2 + 1)
                    .apply { marginStart = dp(16); marginEnd = dp(16) }
            })
            rows.addView(itemRow(item))
        }
        val body = RevealLayout(this).apply {
            visibility = if (open) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(rows)
        }
        card.addView(header)
        card.addView(body)

        header.contentDescription = "${g.title}, ${g.items.size} ${if (g.items.size == 1) "item" else "items"}"
        ViewCompat.setAccessibilityHeading(header, true)
        ViewCompat.setStateDescription(header, if (open) "Expanded" else "Collapsed")
        header.setOnClickListener { toggle(g.title, reveal = true) }
        return Section(card, header, body, chevron, badge)
    }

    /** The open category's count and chevron take the accent; the others stay quiet. */
    private fun markOpen(badge: TextView, chevron: ImageView, open: Boolean) {
        badge.setBackgroundResource(if (open) R.drawable.bg_count_active else R.drawable.bg_count)
        badge.setTextColor(getColor(if (open) R.color.accent_text else R.color.text_secondary))
        chevron.imageTintList = ColorStateList.valueOf(getColor(if (open) R.color.accent_text else R.color.text_secondary))
    }

    /**
     * Opens or closes a category. Outside a search, opening one closes the one that was open and,
     * with [reveal], scrolls so the opened category shows under the tab bar. During a search every
     * matching category starts open and tapping only folds that one.
     */
    private fun toggle(title: String, reveal: Boolean) {
        val s = sections[title] ?: return
        val searching = !ItemSearch.isBlank(query)
        val open = s.body.visibility != View.VISIBLE
        if (!searching) {
            if (open) {
                expanded?.let { other -> if (other != title) sections[other]?.let { setOpen(it, false) } }
                expanded = title
            } else if (expanded == title) {
                expanded = null
            }
        }
        val bodyHeight = setOpen(s, open)
        if (open && reveal) {
            // Scroll just enough: a header under the bar comes down to it, a long category scrolls
            // until its items fit (never past its header), a short one near the top stays put.
            val viewport = mainScroll.height
            val cardHeight = s.header.height + bodyHeight + dp(8)
            scrollToSection(s) { top, from ->
                val alignTop = top - sticky.height - dp(8)
                if (alignTop < from) alignTop else minOf(maxOf(from, top + cardHeight - viewport), alignTop)
            }
        }
    }

    /** Animates the body open or closed and returns the body's full height. */
    private fun setOpen(s: Section, open: Boolean): Int {
        ViewCompat.setStateDescription(s.header, if (open) "Expanded" else "Collapsed")
        markOpen(s.badge, s.chevron, open)
        s.chevron.animate().rotation(if (open) 180f else 0f)
            .setDuration(if (motionEnabled()) 260 else 0).setInterpolator(EASE).start()
        s.card.animate().translationZ(if (open) dp(1).toFloat() else 0f)
            .setDuration(if (motionEnabled()) 260 else 0).start()
        return if (open) s.body.expandHeight() else { s.body.collapseHeight(); 0 }
    }

    private fun itemRow(item: PriceItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(44)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            // One screen-reader stop per item, name and note together.
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = listOfNotNull(item.name, item.note).joinToString(". ")
        }
        row.addView(styled(R.style.Text_Body, item.name).apply {
            if (!ItemSearch.isBlank(query)) text = highlighted(item.name)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        // Seasonal, limited-time or location-specific items are flagged in the data.
        item.note?.let { note ->
            row.addView(styled(R.style.Tag, note).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        return row
    }

    /** The item's name with the search words it contains in bold (words matched through its note or category get no mark). */
    private fun highlighted(name: String): CharSequence {
        val out = SpannableStringBuilder(name)
        val lower = name.lowercase()
        for (word in query.lowercase().split(' ').filter { it.isNotBlank() }) {
            var at = lower.indexOf(word)
            while (at >= 0) {
                out.setSpan(StyleSpan(Typeface.BOLD), at, at + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                at = lower.indexOf(word, at + word.length)
            }
        }
        return out
    }

    private fun noItemsCard(): View {
        val r = shown
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            elevation = dp(1).toFloat()
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        card.addView(styled(R.style.Text_Title, "No item list for this restaurant yet"))
        val chains = ChainPrices.get(this).chainCount
        card.addView(styled(R.style.Text_Secondary, "Pull Up Menu has item lists for $chains chains. This place isn't one of them yet, but its menu may be online.").apply {
            setPadding(0, dp(8), 0, 0)
        })
        val button = Button(this, null, 0, R.style.Button_Primary).apply {
            text = if (r?.menuUrl != null) "Open the menu page" else "Find the menu"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            setOnClickListener { selectTab(Tab.MENU, animate = true) }
        }
        card.addView(button)
        return card
    }

    private fun renderChips(visible: List<ItemGroup>) {
        chips.removeAllViews()
        chipFor.clear()
        if (visible.size >= 2) {
            for (g in visible) {
                val chip = styled(R.style.Chip, g.title).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }
                    contentDescription = "Jump to ${g.title}"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { jumpTo(g.title) }
                }
                chips.addView(chip)
                chipFor[g.title] = chip
            }
        }
        chipsScroll.visibility = if (tab == Tab.ITEMS && chips.childCount >= 2) View.VISIBLE else View.GONE
        currentSection = null
        mainScroll.post { trackSection() }
    }

    /** Opens a category (closing the open one) and scrolls it to just under the pinned tab bar. */
    private fun jumpTo(title: String) {
        val s = sections[title] ?: return
        hideKeyboard()
        if (s.body.visibility != View.VISIBLE) toggle(title, reveal = false)
        scrollToSection(s) { top, _ -> top - sticky.height - dp(8) }
        markChip(title)
    }

    /**
     * Scrolls to the section: [target] maps the card's top (in content coordinates) and the scroll
     * position the scroll started from to the wanted position. It is re-read every frame, because a
     * category closing above the section moves the section up while the scroll is under way.
     */
    private fun scrollToSection(s: Section, target: (top: Int, from: Int) -> Int) {
        scrollAnimator?.cancel()
        val from = mainScroll.scrollY
        fun wanted() = target(s.card.topIn(mainContent), from).coerceAtLeast(0)
        if (!motionEnabled()) {
            mainScroll.post { mainScroll.scrollTo(0, wanted()) }
            return
        }
        scrollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = EASE
            addUpdateListener { a -> mainScroll.scrollTo(0, (from + (wanted() - from) * a.animatedFraction).roundToInt()) }
            start()
        }
    }

    /** Highlights the chip of the open category while it is on screen, else of the one at the top. */
    private fun trackSection() {
        if (tab != Tab.ITEMS || sections.isEmpty() || chipFor.isEmpty()) return
        val top = mainScroll.scrollY + sticky.height
        val bottom = mainScroll.scrollY + mainScroll.height
        val open = expanded?.let { title -> sections[title]?.let { title to it } }
        if (open != null && ItemSearch.isBlank(query)) {
            val cardTop = open.second.card.topIn(mainContent)
            if (cardTop < bottom && cardTop + open.second.card.height > top) {
                markChip(open.first)
                return
            }
        }
        val line = top + dp(24)
        var current = sections.keys.first()
        for ((title, s) in sections) {
            if (s.card.topIn(mainContent) <= line) current = title else break
        }
        markChip(current)
    }

    private fun markChip(title: String) {
        if (title == currentSection) return
        currentSection = title
        for ((t, chip) in chipFor) chip.isSelected = t == title
        val chip = chipFor[title] ?: return
        val left = chip.left - dp(20)
        val right = chip.right + dp(20) - chipsScroll.width
        val x = chipsScroll.scrollX
        val target = when {
            left < x -> left
            right > x -> right
            else -> return
        }.coerceAtLeast(0)
        if (motionEnabled()) chipsScroll.smoothScrollTo(target, 0) else chipsScroll.scrollTo(target, 0)
    }

    // ---- Menu ----

    private fun setUpMenu(r: Restaurant) {
        destroyWebView()
        val menuUrl = r.menuUrl
        val browser = findViewById<Button>(R.id.open_browser)
        val maps = findViewById<Button>(R.id.open_maps_menu)
        val website = findViewById<Button>(R.id.open_website)
        val searchButton = findViewById<Button>(R.id.search_menu)

        menuLoadFailed = false
        menuRetry.visibility = View.GONE
        menuMessage.text = "No in-app menu for this restaurant yet. Try the buttons below."
        menuMessageCard.visibility = if (menuUrl == null) View.VISIBLE else View.GONE
        browser.visibility = if (menuUrl != null) View.VISIBLE else View.GONE
        browser.setOnClickListener { menuUrl?.let { open(web?.url ?: it) } }

        // Google Maps' place page has a Menu tab (with dish photos) when Google has one.
        maps.text = if (menuUrl != null) "Maps" else "Menu (Maps)"
        maps.isEnabled = r.mapsUri != null
        maps.alpha = if (r.mapsUri != null) 1f else 0.4f
        maps.setOnClickListener { r.mapsUri?.let { open(it) } }

        website.visibility = if (menuUrl == null && r.websiteUri != null) View.VISIBLE else View.GONE
        website.setOnClickListener { r.websiteUri?.let { open(it) } }

        searchButton.text = if (menuUrl != null) "Search" else "Search menu"
        searchButton.setOnClickListener {
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
            menuMessageCard.visibility = View.VISIBLE
            return
        }
        setUpWebView(w)
        findViewById<FrameLayout>(R.id.web_container).addView(
            w, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        web = w
        startMenuLoad()
        w.loadUrl(url)
    }

    /** Before a (re)load: progress shows; the WebView stays hidden if the last load failed, until a page loads. */
    private fun startMenuLoad() {
        menuLoadFailed = false
        menuRetry.visibility = View.GONE
        if (web != null) menuMessageCard.visibility = View.GONE
        menuProgress.progress = 0
        menuProgress.visibility = View.VISIBLE
    }

    private fun showMenuFailed() {
        menuLoadFailed = true
        web?.visibility = View.INVISIBLE // not the browser's error page
        menuProgress.visibility = View.GONE
        val itemsHint = if (shown?.prices != null) " The Items tab works offline." else ""
        menuMessage.text = "The menu page didn't load. Check your connection and try again, or open it in your " +
            "browser.$itemsHint"
        menuRetry.visibility = View.VISIBLE
        menuMessageCard.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled") // chain menu pages need JS; no JavascriptInterface is exposed
    private fun setUpWebView(w: WebView) {
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.setGeolocationEnabled(false)
        w.settings.allowContentAccess = false
        w.settings.allowFileAccess = false
        // In the dark theme, menu pages without a dark style of their own are darkened (Android 13+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) w.settings.isAlgorithmicDarkeningAllowed = true
        w.setBackgroundColor(getColor(R.color.bg))
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                when (uri.scheme?.lowercase()) {
                    "http", "https" -> {
                        // A link the user taps to another website (an ad, a delivery service) opens in the
                        // browser, so it never looks like this restaurant's menu. Redirects, page scripts
                        // and the chain's own site stay here.
                        val elsewhere = request.isForMainFrame && request.hasGesture() && !request.isRedirect &&
                            !WebLinks.sameSite(uri.host, view.url?.toUri()?.host) &&
                            !WebLinks.sameSite(uri.host, shown?.menuUrl?.toUri()?.host)
                        return elsewhere && openOutside(uri)
                    }
                }
                // tel:, mailto:, market: etc. open outside; ignore odd schemes from ad iframes.
                if (request.isForMainFrame) openOutside(uri)
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                updateBack()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError?) {
                if (view === web && request.isForMainFrame) showMenuFailed()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view !== web) return
                menuProgress.visibility = View.GONE
                if (!menuLoadFailed) view.visibility = View.VISIBLE
            }

            // The default (false) kills our whole process, taking the car screen and watching with it.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === web) {
                    renderGoneFor = shown?.id
                    destroyWebView()
                    menuLoadFailed = false
                    menuRetry.visibility = View.GONE
                    menuMessage.text = "The menu can't load inside the app right now. Open it in your browser."
                    menuMessageCard.visibility = View.VISIBLE
                }
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== web || menuLoadFailed) return
                menuProgress.setProgress(newProgress, motionEnabled())
                menuProgress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            // Page dialogs stay suppressed, as they are without a WebChromeClient.
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult,
            ): Boolean {
                result.cancel()
                return true
            }

            override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.confirm()
                return true
            }
        }
    }

    /** Opens [uri] in another app (the browser, the dialer...). False when nothing can. */
    private fun openOutside(uri: Uri): Boolean =
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }

    private fun destroyWebView() {
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
        menuProgress.visibility = View.GONE
        updateBack()
    }

    // ---- Photos ----

    private fun showPhotos(r: Restaurant) {
        photosLoadedFor = r.id
        cancelPhotoLoads()
        val grid = findViewById<ColumnGrid>(R.id.photo_grid)
        val message = findViewById<TextView>(R.id.photos_message)
        grid.removeAllViews()
        val photos = r.photos.take(MAX_PHOTOS)
        if (photos.isEmpty()) {
            message.text = if (r.isDemo) {
                "The demo has no photos. Real places show up to $MAX_PHOTOS photos from Google Maps."
            } else {
                "No photos available for this place."
            }
            return
        }
        message.text = "Photos from Google Maps. Tap a photo to see it full screen."
        photos.forEachIndexed { i, ph ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val tile = SquareFrameLayout(this).apply {
                setBackgroundResource(R.drawable.bg_photo_placeholder)
                clipToOutline = true
                contentDescription = "Photo ${i + 1} of ${photos.size} of ${r.name}" +
                    (ph.authorName?.let { ", by $it" } ?: "") + ". Opens full screen."
                setOnClickListener { openViewer(r, i) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            tile.addView(image, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            // The whole credit, however long: Google requires the photographer's name.
            val credit = styled(R.style.Text_Secondary, "").apply {
                textSize = 12f
                minHeight = dp(48) // often a link to the photographer's profile
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            setCredit(credit, ph)
            cell.addView(tile)
            cell.addView(credit)
            grid.addView(cell)

            val pulse = tile.loadingPulse()
            photoJobs += lifecycleScope.launch {
                val bmp = try {
                    loadPhoto(ph)
                } finally {
                    pulse?.cancel()
                    tile.alpha = 1f
                }
                if (shown?.id != r.id) return@launch
                if (bmp != null) {
                    image.setImageBitmap(bmp)
                    image.fadeIn()
                } else {
                    grid.removeView(cell)
                    if (grid.childCount == 0) message.text = "No photos available for this place."
                }
            }
        }
    }

    private fun cancelPhotoLoads() {
        photoJobs.forEach { it.cancel() }
        photoJobs.clear()
    }

    private fun openViewer(r: Restaurant, index: Int) {
        val photos = r.photos.take(MAX_PHOTOS)
        if (photos.isEmpty()) return
        viewer?.dismiss()
        viewer = PhotoViewer(this, lifecycleScope, r, photos, load = ::loadPhoto, open = ::open)
            .also { it.show(index) }
    }

    /** Google requires the photographer's name (and profile link) with each photo. */
    private fun setCredit(view: TextView, ph: PlacePhoto) {
        val author = ph.authorName
        val uri = ph.authorUri
        view.text = if (author != null) "Photo: $author" else "Photo from Google Maps"
        if (author != null && uri != null) {
            view.paintFlags = view.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            view.setOnClickListener { open(uri) }
        } else {
            view.paintFlags = view.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            view.setOnClickListener(null)
            view.isClickable = false
        }
    }

    /** A photo of the shown restaurant, downloaded at most once while it's shown; null if it can't load. */
    private suspend fun loadPhoto(ph: PlacePhoto): Bitmap? {
        bitmaps[ph.name]?.let { return it }
        val forId = shown?.id
        val bmp = try {
            MenuRepository.photo(this, ph.name, photoWidth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (bmp != null && shown?.id == forId) bitmaps[ph.name] = bmp
        return bmp
    }

    // ---- Helpers ----

    private fun styled(style: Int, s: String) = TextView(this, null, 0, style).apply { text = s }

    private fun hideKeyboard() {
        if (!search.hasFocus()) return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /** Photo loads are billed one by one, so the phone shows at most this many. */
        const val MAX_PHOTOS = 4
    }
}
