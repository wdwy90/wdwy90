package com.wdwy90.pullupmenu.phone

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.ChainPrices
import com.wdwy90.pullupmenu.core.ItemGroup
import com.wdwy90.pullupmenu.core.ItemGroups
import com.wdwy90.pullupmenu.core.ItemSearch
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.PlacePhoto
import com.wdwy90.pullupmenu.core.PriceItem
import com.wdwy90.pullupmenu.core.Restaurant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

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

    // Items tab
    private var groups: List<ItemGroup> = emptyList()
    private var query = ""
    private val collapsed = HashSet<String>()
    private val sections = LinkedHashMap<String, View>()
    private val chipFor = HashMap<String, TextView>()
    private var currentSection: String? = null
    private var searchJob: Job? = null
    private var settingSearchText = false

    private var heroJob: Job? = null
    private var heroPulse: ValueAnimator? = null
    private var viewer: PhotoViewer? = null

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
    private lateinit var sticky: View
    private lateinit var stickySpacer: View
    private lateinit var topTitle: TextView
    private lateinit var nameView: TextView
    private lateinit var itemsContent: LinearLayout
    private lateinit var itemsList: LinearLayout
    private lateinit var photosContent: View
    private lateinit var menuPanel: View
    private lateinit var menuMessageCard: View
    private lateinit var menuMessage: TextView
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var search: EditText
    private lateinit var searchSummary: TextView

    /** Back walks the menu page's history while the Menu tab is shown. */
    private val webBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
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
        nameView = findViewById(R.id.name)
        itemsContent = findViewById(R.id.items_content)
        itemsList = findViewById(R.id.items_list)
        photosContent = findViewById(R.id.photos_content)
        menuPanel = findViewById(R.id.menu_panel)
        menuMessageCard = findViewById(R.id.menu_message_card)
        menuMessage = findViewById(R.id.menu_message)
        chipsScroll = findViewById(R.id.chips_scroll)
        chips = findViewById(R.id.chips)
        search = findViewById(R.id.search)
        searchSummary = findViewById(R.id.search_summary)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.hero).clipToOutline = true
        tabBar = TabBar(findViewById(R.id.tabs)) { i -> selectTab(Tab.entries[i], animate = true) }

        // The tab bar floats above the scroll view; the spacer keeps its place in the content.
        // Taps on the bar's empty space must not reach the content hidden underneath it.
        swallowTouches(sticky)
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
        if (motionEnabled()) {
            itemsContent.layoutTransition = LayoutTransition().apply { enableOnlyChanging() }
            itemsList.layoutTransition = LayoutTransition().apply { enableOnlyChanging() }
        }
        showEmpty()

        lifecycleScope.launch {
            MenuRepository.state.collect { s ->
                if (s is MenuRepository.State.Found) show(s.restaurant)
                else if (s !is MenuRepository.State.Searching && shown == null) showEmpty()
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
        viewer?.dismiss()
        heroPulse?.cancel()
        destroyWebView()
        super.onDestroy()
    }

    // ---- Restaurant ----

    private fun showEmpty() {
        findViewById<View>(R.id.empty_state).visibility = View.VISIBLE
        mainScroll.visibility = View.GONE
        menuPanel.visibility = View.GONE
        sticky.visibility = View.GONE
        topTitle.alpha = 0f
    }

    private fun show(r: Restaurant) {
        if (r.id == shown?.id) return
        shown = r
        viewer?.dismiss()
        viewer = null
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
        findViewById<View>(R.id.header_actions).visibility =
            if (navigate.visibility == View.GONE && maps.visibility == View.GONE) View.GONE else View.VISIBLE
        // The demo is sample data, not Google data: no Google Maps attribution for it.
        findViewById<View>(R.id.attribution).visibility = if (r.isDemo) View.GONE else View.VISIBLE

        showHero(r)
        setUpItems(r)
        setUpMenu(r)
        findViewById<GridLayout>(R.id.photo_grid).removeAllViews()
        photosLoadedFor = null
        mainScroll.scrollTo(0, 0)
        selectTab(if (r.prices != null) Tab.ITEMS else Tab.MENU, animate = false)
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
    }

    private fun showHero(r: Restaurant) {
        heroJob?.cancel()
        heroPulse?.cancel()
        val hero = findViewById<View>(R.id.hero)
        val image = findViewById<ImageView>(R.id.hero_image)
        val placeholder = findViewById<View>(R.id.hero_placeholder)
        val credit = findViewById<TextView>(R.id.hero_credit)
        hero.alpha = 1f
        image.setImageDrawable(null)
        image.visibility = View.INVISIBLE
        placeholder.visibility = View.VISIBLE
        credit.visibility = View.GONE
        hero.setOnClickListener(null)
        hero.isClickable = false
        hero.contentDescription = null
        hero.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        val ph = r.photos.firstOrNull() ?: return
        heroPulse = hero.loadingPulse()
        heroJob = lifecycleScope.launch {
            val bmp = loadPhoto(ph)
            if (shown?.id != r.id) return@launch
            heroPulse?.cancel()
            hero.alpha = 1f
            if (bmp == null) return@launch
            image.setImageBitmap(bmp)
            image.fadeIn()
            placeholder.visibility = View.GONE
            setCredit(credit, ph)
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
            sticky.elevation = dp(3).toFloat()
            topTitle.alpha = if (shown != null) 1f else 0f
            return
        }
        val y = mainScroll.scrollY
        val offset = (stickySpacer.top - y).coerceAtLeast(0)
        sticky.translationY = offset.toFloat()
        sticky.elevation = if (offset == 0 && y > 0) dp(3).toFloat() else 0f
        val nameBottom = nameView.topIn(mainContent) + nameView.height
        topTitle.alpha = ((y - nameBottom + dp(16)) / dp(24).toFloat()).coerceIn(0f, 1f)
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
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
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
        collapsed.clear()
        currentSection = null

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
        val transition = itemsList.layoutTransition
        itemsList.layoutTransition = null // rebuilding: no per-card animations
        itemsList.removeAllViews()
        sections.clear()

        if (groups.isEmpty()) {
            itemsList.addView(noItemsCard())
            renderChips(emptyList())
            itemsList.layoutTransition = transition
            return
        }
        val searching = !ItemSearch.isBlank(query)
        val visible = ItemSearch.filter(groups, query)
        val total = groups.sumOf { it.items.size }
        val count = visible.sumOf { it.items.size }
        searchSummary.text = when {
            !searching -> "$total items · ${groups.size} categories"
            count == 0 -> "No items match “${query.trim()}”."
            else -> "$count of $total items match"
        }
        for (g in visible) {
            // Search results are always open; otherwise sections remember being collapsed.
            val card = sectionCard(g, expanded = searching || g.title !in collapsed, searching)
            itemsList.addView(card)
            sections[g.title] = card
        }
        renderChips(visible)
        itemsList.layoutTransition = transition
    }

    private fun sectionCard(g: ItemGroup, expanded: Boolean, searching: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            // The list fades in and out; the card and the cards below it resize smoothly.
            if (motionEnabled()) layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(200)
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), dp(8), dp(12), dp(8))
            setBackgroundResource(R.drawable.bg_row)
            isClickable = true
            isFocusable = true
        }
        header.addView(styled(R.style.Text_Title, g.title).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(styled(R.style.Text_Label, g.items.size.toString()).apply {
            setBackgroundResource(R.drawable.bg_count)
            setPadding(dp(10), dp(2), dp(10), dp(2))
        })
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_down)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
            scaleType = ImageView.ScaleType.CENTER
            rotation = if (expanded) 180f else 0f
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) }
        }
        header.addView(chevron)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        g.items.forEachIndexed { i, item ->
            if (i > 0) body.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1) / 2 + 1)
                    .apply { marginStart = dp(20); marginEnd = dp(20) }
            })
            body.addView(itemRow(item))
        }
        card.addView(header)
        card.addView(body)

        val label = "${g.title}, ${g.items.size} ${if (g.items.size == 1) "item" else "items"}"
        header.contentDescription = label
        ViewCompat.setAccessibilityHeading(header, true)
        ViewCompat.setStateDescription(header, if (expanded) "Expanded" else "Collapsed")
        header.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            if (!searching) {
                if (open) collapsed.remove(g.title) else collapsed.add(g.title)
            }
            ViewCompat.setStateDescription(header, if (open) "Expanded" else "Collapsed")
            chevron.animate().rotation(if (open) 180f else 0f)
                .setDuration(if (motionEnabled()) 200 else 0).setInterpolator(EASE).start()
        }
        return card
    }

    private fun itemRow(item: PriceItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            // One screen-reader stop per item, name and note together.
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = listOfNotNull(item.name, item.note).joinToString(". ")
        }
        row.addView(styled(R.style.Text_Body, item.name).apply {
            textSize = 16f
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

    /** Scrolls a section to just under the pinned tab bar, opening it if it was collapsed. */
    private fun jumpTo(title: String) {
        val card = sections[title] ?: return
        val body = (card as ViewGroup).getChildAt(1)
        if (body.visibility != View.VISIBLE) card.getChildAt(0).performClick()
        hideKeyboard()
        mainScroll.post {
            val target = (card.topIn(mainContent) - sticky.height - dp(4)).coerceAtLeast(0)
            if (motionEnabled()) mainScroll.smoothScrollTo(0, target) else mainScroll.scrollTo(0, target)
            markChip(title)
        }
    }

    /** Highlights the chip of the section at the top of the screen. */
    private fun trackSection() {
        if (tab != Tab.ITEMS || sections.isEmpty() || chipFor.isEmpty()) return
        val line = mainScroll.scrollY + sticky.height + dp(24)
        var current = sections.keys.first()
        for ((title, card) in sections) {
            if (card.topIn(mainContent) <= line) current = title else break
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

        menuMessage.text = "No in-app menu for this restaurant yet. Try these:"
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
        w.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled") // chain menu pages need JS; no JavascriptInterface is exposed
    private fun setUpWebView(w: WebView) {
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.setGeolocationEnabled(false)
        w.settings.allowContentAccess = false
        w.settings.allowFileAccess = false
        w.setBackgroundColor(getColor(R.color.bg))
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
                    menuMessageCard.visibility = View.VISIBLE
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

    // ---- Photos ----

    private fun showPhotos(r: Restaurant) {
        photosLoadedFor = r.id
        val grid = findViewById<GridLayout>(R.id.photo_grid)
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
        val gap = dp(6)
        photos.forEachIndexed { i, ph ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    setMargins(gap, gap, gap, gap)
                }
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
            val credit = styled(R.style.Text_Secondary, "").apply {
                textSize = 12f
                minHeight = dp(32)
                gravity = android.view.Gravity.CENTER_VERTICAL
                maxLines = 2
            }
            setCredit(credit, ph)
            cell.addView(tile)
            cell.addView(credit)
            grid.addView(cell)

            val pulse = tile.loadingPulse()
            lifecycleScope.launch {
                val bmp = loadPhoto(ph)
                if (shown?.id != r.id) return@launch
                pulse?.cancel()
                tile.alpha = 1f
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

    private fun openViewer(r: Restaurant, index: Int) {
        val photos = r.photos.take(MAX_PHOTOS)
        if (photos.isEmpty()) return
        viewer?.dismiss()
        viewer = PhotoViewer(this, lifecycleScope, r, photos, photoWidth) { open(it) }.also { it.show(index) }
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

    private suspend fun loadPhoto(ph: PlacePhoto) = try {
        MenuRepository.photo(this, ph.name, photoWidth)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ---- Helpers ----

    @SuppressLint("ClickableViewAccessibility") // consumes leftover touches only; nothing to click
    private fun swallowTouches(view: View) = view.setOnTouchListener { _, _ -> true }

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

    private fun LayoutTransition.enableOnlyChanging() {
        enableTransitionType(LayoutTransition.CHANGING)
        disableTransitionType(LayoutTransition.CHANGE_APPEARING)
        disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
        setDuration(200)
    }

    private companion object {
        /** Photo loads are billed one by one, so the phone shows at most this many. */
        const val MAX_PHOTOS = 4
    }
}
