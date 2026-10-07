package com.wdwy90.pullupmenu.phone

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.DriveWatcher
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.MenuRepository.State
import com.wdwy90.pullupmenu.core.Prefs
import com.wdwy90.pullupmenu.core.StatusModel
import com.wdwy90.pullupmenu.core.StatusModel.Phase
import com.wdwy90.pullupmenu.core.StatusModel.Tone
import kotlinx.coroutines.launch

/** Phone dashboard: detection status, Android Auto connection, and quick actions. */
class MainActivity : ThemedActivity() {

    /** Set when the user asked to start phone watching but location permission was still missing. */
    private var startAfterGrant = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            render()
            if (startAfterGrant && hasLocation() && Prefs.autoDetect(this)) startWatching()
            startAfterGrant = false
        }

    private var carConnected = false
    /** Set in onResume: the lifecycle only reports RESUMED after onResume returns. */
    private var resumed = false
    private var pulse: AnimatorSet? = null

    private lateinit var pill: View
    private lateinit var pillDot: View
    private lateinit var pillText: TextView
    private lateinit var ring: View
    private lateinit var dot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var statusAction: Button
    private lateinit var statusAttribution: View
    private lateinit var actionMenu: View
    private lateinit var actionDrive: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets(findViewById(R.id.root))

        pill = findViewById(R.id.status_pill)
        pillDot = findViewById(R.id.pill_dot)
        pillText = findViewById(R.id.pill_text)
        ring = findViewById(R.id.indicator_ring)
        dot = findViewById(R.id.indicator_dot)
        statusTitle = findViewById(R.id.status_title)
        statusBody = findViewById(R.id.status_body)
        statusAction = findViewById(R.id.status_action)
        statusAttribution = findViewById(R.id.status_attribution)

        fact(R.id.fact_car, R.drawable.ic_car, "Android Auto")
        fact(R.id.fact_auto, R.drawable.ic_bolt, "Auto-detect")
        fact(R.id.fact_current, R.drawable.ic_place, "Current")
        fact(R.id.fact_last, R.drawable.ic_my_location, "Last detected")

        action(R.id.action_detect, R.drawable.ic_my_location, "Detect My Restaurant", "Check where you are now") {
            detect()
        }
        actionMenu = action(R.id.action_menu, R.drawable.ic_menu, "Open Current Menu", "") { openMenu() }
        actionDrive = action(R.id.action_drive, R.drawable.ic_car, "Drive Mode", "") { toggleDriveMode() }
        action(R.id.action_settings, R.drawable.ic_settings, "Settings", "Detection, key, theme") { openSettings() }

        findViewById<View>(R.id.settings_button).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.demo).setOnClickListener { MenuRepository.showDemo(this) }

        CarConnection(this).type.observe(this) { type ->
            carConnected = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
                type == CarConnection.CONNECTION_TYPE_NATIVE
            render()
        }
        lifecycleScope.launch { Prefs.autoDetectFlow(this@MainActivity).collect { render() } }
        lifecycleScope.launch { ArrivalService.running.collect { render() } }
        lifecycleScope.launch { DriveWatcher.watching.collect { render() } }

        // Only auto-open the menu for a *new* detection, not one we've already shown.
        // Keyed on id + time so a second "Try a demo" (same id) opens it again.
        val current = MenuRepository.state.value as? State.Found
        var openedFor = current?.let { it.restaurant.id to it.atMs }
        lifecycleScope.launch {
            MenuRepository.state.collect { s ->
                render()
                val key = (s as? State.Found)?.let { it.restaurant.id to it.atMs }
                if (key != null && key != openedFor &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    openedFor = key
                    startActivity(Intent(this@MainActivity, RestaurantActivity::class.java))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        render()
        // The process may have been killed (Task Manager "Stop") without the widget hearing about it.
        DriveWidget.refresh(this)
    }

    override fun onPause() {
        resumed = false
        stopPulse()
        super.onPause()
    }

    // ---- Rendering ----

    private fun render() {
        val s = MenuRepository.state.value
        val found = s as? State.Found
        val autoDetect = Prefs.autoDetect(this)
        val driveMode = ArrivalService.running.value
        val gpsWatching = DriveWatcher.watching.value
        val status = StatusModel.of(
            StatusModel.Input(
                phase = when (s) {
                    State.Idle -> Phase.IDLE
                    State.Searching -> Phase.SEARCHING
                    is State.Found -> if (s.restaurant.isDemo) Phase.DEMO else Phase.FOUND
                    State.NothingNearby -> Phase.NOTHING_NEARBY
                    is State.Error -> Phase.ERROR
                },
                hasLocation = hasLocation(),
                hasKey = Prefs.apiKey(this).isNotBlank(),
                autoDetect = autoDetect,
                watching = gpsWatching,
                restaurantName = found?.restaurant?.name,
                hasMenu = found?.restaurant?.let { it.prices != null || it.menuUrl != null } ?: false,
                errorMessage = (s as? State.Error)?.message,
                radiusMeters = Prefs.radiusMeters(this).toInt(),
            )
        )

        val (soft, strong) = when (status.tone) {
            Tone.NEUTRAL -> R.drawable.bg_pill_neutral to R.color.text_secondary
            Tone.ACTIVE -> R.drawable.bg_pill_accent to R.color.accent_text
            Tone.SUCCESS -> R.drawable.bg_pill_success to R.color.success
            Tone.WARNING -> R.drawable.bg_pill_warning to R.color.warning
        }
        val strongTint = ColorStateList.valueOf(getColor(strong))
        pill.setBackgroundResource(soft)
        pillDot.backgroundTintList = strongTint
        pillText.text = status.pill
        pill.contentDescription = "Status: ${status.pill}"
        dot.backgroundTintList = strongTint
        ring.backgroundTintList = strongTint
        statusTitle.text = status.title
        statusBody.text = status.body
        statusAction.visibility = if (status.action != null) View.VISIBLE else View.GONE
        statusAction.text = status.actionLabel
        statusAction.setOnClickListener {
            when (status.action) {
                StatusModel.Action.GRANT_LOCATION -> permissionLauncher.launch(requiredPermissions())
                StatusModel.Action.OPEN_SETTINGS -> openSettings()
                StatusModel.Action.OPEN_MENU -> openMenu()
                StatusModel.Action.DETECT -> detect()
                null -> Unit
            }
        }
        if (status.animated && motionEnabled() && resumed) {
            startPulse()
        } else {
            stopPulse()
        }

        setFact(R.id.fact_car, if (carConnected) "Connected" else "Not connected")
        setFact(
            R.id.fact_auto,
            when {
                !autoDetect -> "Off"
                driveMode -> "On · Drive Mode"
                gpsWatching -> "On · watching"
                else -> "On"
            }
        )
        setFact(R.id.fact_current, found?.restaurant?.name ?: "None")
        setFact(
            R.id.fact_last,
            Prefs.lastDetected(this)?.let { last ->
                "${last.chain ?: "A restaurant"} · ${StatusModel.ago(last.atMs, System.currentTimeMillis())}"
            } ?: "None yet"
        )
        // A real place's name and details come from Google Maps; the demo is the app's own sample.
        statusAttribution.visibility =
            if (found != null && !found.restaurant.isDemo) View.VISIBLE else View.GONE

        setAction(actionMenu, found?.restaurant?.name ?: "Nothing detected yet", enabled = found != null)
        setAction(actionDrive, if (driveMode) "On · tap to stop" else "Watch on this phone", enabled = true)
        actionDrive.isSelected = driveMode
    }

    private fun startPulse() {
        if (pulse?.isRunning == true) return
        val scaleX = ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.45f, 1f)
        val scaleY = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.45f, 1f)
        val fade = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.45f, 0f)
        for (a in listOf(scaleX, scaleY, fade)) {
            a.repeatCount = ValueAnimator.INFINITE
            a.duration = 1400
        }
        pulse = AnimatorSet().apply {
            playTogether(scaleX, scaleY, fade)
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        ring.scaleX = 1f
        ring.scaleY = 1f
        ring.alpha = 0.18f
    }

    private fun fact(id: Int, icon: Int, label: String) {
        val row = findViewById<View>(id)
        row.findViewById<ImageView>(R.id.fact_icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.fact_label).text = label
    }

    private fun setFact(id: Int, value: String) {
        val row = findViewById<View>(id)
        row.findViewById<TextView>(R.id.fact_value).text = value
        row.contentDescription = "${row.findViewById<TextView>(R.id.fact_label).text}: $value"
    }

    private fun action(id: Int, icon: Int, title: String, subtitle: String, onClick: () -> Unit): View {
        val tile = findViewById<View>(id)
        tile.findViewById<ImageView>(R.id.action_icon).setImageResource(icon)
        tile.findViewById<TextView>(R.id.action_title).text = title
        setAction(tile, subtitle, enabled = true)
        tile.setOnClickListener { onClick() }
        return tile
    }

    private fun setAction(tile: View, subtitle: String, enabled: Boolean) {
        val title = tile.findViewById<TextView>(R.id.action_title)
        tile.findViewById<TextView>(R.id.action_subtitle).text = subtitle
        // Stays tappable when "disabled" so it can explain why; only looks dimmed.
        tile.findViewById<View>(R.id.action_icon).alpha = if (enabled) 1f else 0.5f
        title.alpha = if (enabled) 1f else 0.6f
        tile.contentDescription = "${title.text}. $subtitle"
    }

    // ---- Actions ----

    private fun detect() {
        if (!hasLocation()) {
            permissionLauncher.launch(requiredPermissions())
        } else {
            DriveWatcher.checkNow(this)
        }
    }

    private fun openMenu() {
        if (MenuRepository.state.value is State.Found) {
            startActivity(Intent(this, RestaurantActivity::class.java))
        } else {
            Toast.makeText(this, "Nothing detected yet. Tap Detect My Restaurant in the lane.", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /** Drive Mode = phone watching (for drives without Android Auto). Same as the widget. */
    private fun toggleDriveMode() {
        if (ArrivalService.running.value) {
            ArrivalService.stop(this)
            return
        }
        if (!Prefs.autoDetect(this)) Prefs.setAutoDetect(this, true)
        if (hasLocation()) {
            startWatching()
        } else {
            startAfterGrant = true
            permissionLauncher.launch(requiredPermissions())
        }
    }

    /** Called only while this activity is visible, so the location foreground service may start. */
    private fun startWatching() {
        try {
            ArrivalService.start(this)
            Toast.makeText(this, "Drive Mode on. It stops by itself after 15 minutes parked.", Toast.LENGTH_SHORT)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start watching. Try again.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
