package com.wdwy90.pullupmenu.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.Restaurant
import kotlinx.coroutines.launch
import java.net.URLEncoder

/** Phone screen: full photo gallery plus links to the actual menu. */
class RestaurantActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_restaurant)
        lifecycleScope.launch {
            MenuRepository.state.collect { s ->
                if (s is MenuRepository.State.Found) show(s.restaurant)
                else if (s !is MenuRepository.State.Searching) {
                    findViewById<TextView>(R.id.name).text = "No restaurant detected yet"
                }
            }
        }
    }

    private var shownId: String? = null

    private fun show(r: Restaurant) {
        if (r.id == shownId) return
        shownId = r.id
        findViewById<TextView>(R.id.name).text = r.name
        findViewById<TextView>(R.id.details).text = listOfNotNull(
            r.category, r.rating?.let { "★ %.1f".format(it) }, r.address,
        ).joinToString("  ·  ")

        // Google Maps' place page has the Menu tab (with dish photos) when Google has one.
        findViewById<Button>(R.id.open_maps_menu).apply {
            isEnabled = r.mapsUri != null
            setOnClickListener { open(r.mapsUri!!) }
        }
        findViewById<Button>(R.id.open_website).apply {
            visibility = if (r.websiteUri != null) View.VISIBLE else View.GONE
            setOnClickListener { open(r.websiteUri!!) }
        }
        findViewById<Button>(R.id.search_menu).setOnClickListener {
            open("https://www.google.com/search?q=" +
                URLEncoder.encode("${r.name} ${r.address} menu", "UTF-8"))
        }

        val gallery = findViewById<LinearLayout>(R.id.gallery)
        gallery.removeAllViews()
        val width = resources.displayMetrics.widthPixels
        for (name in r.photoNames) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            gallery.addView(iv)
            lifecycleScope.launch {
                val bmp = MenuRepository.photo(this@RestaurantActivity, name, width.coerceAtMost(1200))
                if (bmp != null) iv.setImageBitmap(bmp) else gallery.removeView(iv)
            }
        }
        if (r.photoNames.isEmpty()) {
            gallery.addView(TextView(this).apply { text = "No photos available for this place." })
        }
    }

    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
