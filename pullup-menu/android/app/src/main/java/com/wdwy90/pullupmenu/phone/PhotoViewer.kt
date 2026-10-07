package com.wdwy90.pullupmenu.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.PlacePhoto
import com.wdwy90.pullupmenu.core.Restaurant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Full-screen photo viewer: swipe or use the arrows to move between photos. Each photo shows its
 * photographer credit and a link to the photo on Google Maps, as Google requires.
 * Photos come from [MenuRepository.photo] at the same size as the grid, so they are usually cached.
 */
@SuppressLint("ClickableViewAccessibility") // performClick is called; TalkBack uses the arrows and actions
class PhotoViewer(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val restaurant: Restaurant,
    private val photos: List<PlacePhoto>,
    private val widthPx: Int,
    private val open: (String) -> Unit,
) {
    private val dialog = Dialog(activity, R.style.Theme_PullUp_Viewer).apply { setContentView(R.layout.dialog_photo) }
    private val root: View = dialog.findViewById(R.id.viewer_root)
    private val image: ImageView = root.findViewById(R.id.viewer_image)
    private val progress: ProgressBar = root.findViewById(R.id.viewer_progress)
    private val counter: TextView = root.findViewById(R.id.viewer_counter)
    private val credit: TextView = root.findViewById(R.id.viewer_credit)
    private val maps: TextView = root.findViewById(R.id.viewer_maps)
    private val prev: View = root.findViewById(R.id.viewer_prev)
    private val next: View = root.findViewById(R.id.viewer_next)
    private val chrome: List<View> = listOf(
        root.findViewById(R.id.viewer_top), root.findViewById(R.id.viewer_bottom), prev, next,
    )

    private var index = 0
    private var load: Job? = null
    private var chromeVisible = true

    init {
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowCompat.getInsetsController(w, root).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(w, root).isAppearanceLightNavigationBars = false
        }
        val top: View = root.findViewById(R.id.viewer_top)
        val bottom: View = root.findViewById(R.id.viewer_bottom)
        val topPad = top.paddingTop
        val bottomPad = bottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top.setPadding(top.paddingLeft, topPad + bars.top, top.paddingRight, top.paddingBottom)
            bottom.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, bottomPad + bars.bottom)
            root.setPadding(bars.left, 0, bars.right, 0)
            insets
        }

        root.findViewById<View>(R.id.viewer_close).setOnClickListener { dialog.dismiss() }
        prev.setOnClickListener { go(-1) }
        next.setOnClickListener { go(+1) }

        val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleChrome()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (abs(velocityX) < 600 || abs(velocityX) < abs(velocityY)) return false
                go(if (velocityX < 0) +1 else -1)
                return true
            }
        })
        image.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            gestures.onTouchEvent(event)
        }
        image.setOnClickListener { } // performClick target, for accessibility services
        ViewCompat.replaceAccessibilityAction(image, AccessibilityActionCompat.ACTION_SCROLL_FORWARD, "Next photo") { _, _ ->
            go(+1)
            true
        }
        ViewCompat.replaceAccessibilityAction(image, AccessibilityActionCompat.ACTION_SCROLL_BACKWARD, "Previous photo") { _, _ ->
            go(-1)
            true
        }
        dialog.setOnDismissListener { load?.cancel() }
    }

    fun show(start: Int) {
        index = start.coerceIn(0, photos.lastIndex)
        dialog.show()
        bind(direction = 0)
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun go(step: Int) {
        val target = index + step
        if (target !in photos.indices) return
        index = target
        bind(direction = step)
    }

    private fun bind(direction: Int) {
        val ph = photos[index]
        counter.text = "${index + 1} of ${photos.size}"
        prev.visibility = if (chromeVisible && index > 0) View.VISIBLE else View.INVISIBLE
        next.visibility = if (chromeVisible && index < photos.lastIndex) View.VISIBLE else View.INVISIBLE

        // Google requires the photographer's name, linked to their profile when there is one.
        val author = ph.authorName
        credit.text = if (author != null) "Photo: $author" else "Photo from Google Maps"
        val authorUri = ph.authorUri
        if (author != null && authorUri != null) {
            credit.paintFlags = credit.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            credit.setOnClickListener { open(authorUri) }
        } else {
            credit.paintFlags = credit.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            credit.setOnClickListener(null)
            credit.isClickable = false
        }
        val mapsUri = ph.mapsUri ?: restaurant.mapsUri
        maps.visibility = if (mapsUri != null) View.VISIBLE else View.GONE
        maps.setOnClickListener { mapsUri?.let(open) }
        image.contentDescription =
            "Photo ${index + 1} of ${photos.size} of ${restaurant.name}" + (author?.let { ", by $it" } ?: "")

        load?.cancel()
        val shownIndex = index
        load = scope.launch {
            val cached = loadBitmap(ph)
            if (shownIndex != index) return@launch
            progress.visibility = View.GONE
            if (cached == null) {
                image.setImageDrawable(null)
                counter.text = "${index + 1} of ${photos.size} · couldn't load this photo"
                return@launch
            }
            slideIn(cached, direction)
        }
        // Only show the spinner if the photo isn't instantly available.
        if (load?.isCompleted == false) progress.visibility = View.VISIBLE
    }

    private suspend fun loadBitmap(ph: PlacePhoto) = try {
        MenuRepository.photo(activity, ph.name, widthPx)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun slideIn(bitmap: Bitmap, direction: Int) {
        image.animate().cancel()
        if (direction == 0 || !motionEnabled()) {
            image.translationX = 0f
            image.alpha = 1f
            image.setImageBitmap(bitmap)
            return
        }
        val shift = image.width * 0.2f * direction
        image.setImageBitmap(bitmap)
        image.translationX = shift
        image.alpha = 0f
        image.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(EASE).start()
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        for (v in chrome) {
            val target = if (chromeVisible) 1f else 0f
            if (chromeVisible) v.visibility = View.VISIBLE
            if (motionEnabled()) {
                v.animate().alpha(target).setDuration(180).withEndAction {
                    if (!chromeVisible) v.visibility = View.INVISIBLE
                }.start()
            } else {
                v.alpha = target
                if (!chromeVisible) v.visibility = View.INVISIBLE
            }
        }
        if (chromeVisible) bindArrows()
    }

    private fun bindArrows() {
        prev.visibility = if (index > 0) View.VISIBLE else View.INVISIBLE
        next.visibility = if (index < photos.lastIndex) View.VISIBLE else View.INVISIBLE
    }
}
