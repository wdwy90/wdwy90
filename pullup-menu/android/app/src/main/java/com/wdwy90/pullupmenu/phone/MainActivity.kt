package com.wdwy90.pullupmenu.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.DriveWatcher
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.MenuRepository.State
import com.wdwy90.pullupmenu.core.Prefs
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Set when the user asked to start phone watching but location permission was still missing. */
    private var startAfterGrant = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
            if (startAfterGrant && hasLocation() && Prefs.autoDetect(this)) startWatching()
            startAfterGrant = false
        }

    private lateinit var status: TextView
    private lateinit var permButton: Button
    private lateinit var autoSwitch: Switch
    private lateinit var autoOptions: View
    private lateinit var phoneWatch: Button
    private lateinit var carBanner: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        permButton = findViewById(R.id.grant_permissions)
        autoSwitch = findViewById(R.id.auto_detect)
        autoOptions = findViewById(R.id.auto_detect_options)
        phoneWatch = findViewById(R.id.phone_watch)
        carBanner = findViewById(R.id.car_banner)

        // Key UI only for builds without a built-in Places key.
        findViewById<View>(R.id.api_key_section).visibility =
            if (Prefs.hasBuiltInKey()) View.GONE else View.VISIBLE
        val keyField = findViewById<EditText>(R.id.api_key)
        if (!Prefs.hasBuiltInKey()) keyField.setText(Prefs.apiKey(this))
        findViewById<Button>(R.id.save_key).setOnClickListener {
            Prefs.setApiKey(this, keyField.text.toString())
            Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
            refresh()
        }
        permButton.setOnClickListener { permissionLauncher.launch(requiredPermissions()) }

        val autoOn = Prefs.autoDetect(this)
        autoSwitch.isChecked = autoOn
        autoOptions.visibility = if (autoOn) View.VISIBLE else View.GONE
        autoSwitch.setOnCheckedChangeListener { _, on ->
            autoOptions.visibility = if (on) View.VISIBLE else View.GONE
            // Ignore programmatic syncs (e.g. the widget turned it on while we were paused).
            if (on == Prefs.autoDetect(this)) return@setOnCheckedChangeListener
            Prefs.setAutoDetect(this, on)
            if (on) requestOrStartWatching() else ArrivalService.stop(this)
        }

        phoneWatch.setOnClickListener {
            if (ArrivalService.running.value) ArrivalService.stop(this) else requestOrStartWatching()
        }

        carBanner.isChecked = Prefs.carBanner(this)
        carBanner.setOnCheckedChangeListener { _, on -> Prefs.setCarBanner(this, on) }

        findViewById<Button>(R.id.check_now).setOnClickListener {
            if (!hasLocation()) {
                permissionLauncher.launch(requiredPermissions())
            } else {
                DriveWatcher.checkNow(this)
            }
        }
        findViewById<Button>(R.id.demo).setOnClickListener { MenuRepository.showDemo(this) }

        lifecycleScope.launch {
            Prefs.autoDetectFlow(this@MainActivity).collect { on ->
                if (autoSwitch.isChecked != on) autoSwitch.isChecked = on
                autoOptions.visibility = if (on) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            ArrivalService.running.collect { running ->
                phoneWatch.text =
                    if (running) "Stop watching on this phone" else "Start watching on this phone"
            }
        }

        // Only auto-open the menu for a *new* detection, not one we've already shown.
        // Keyed on id + time so a second "Try a demo" (same id) opens it again.
        val current = MenuRepository.state.value as? State.Found
        var openedFor = current?.let { it.restaurant.id to it.atMs }
        lifecycleScope.launch {
            MenuRepository.state.collect { s ->
                status.text = when (s) {
                    State.Idle -> "Ready."
                    State.Searching -> "Looking up where you are…"
                    is State.Found ->
                        if (s.restaurant.isDemo) "Demo: ${s.restaurant.name}."
                        else "You're at ${s.restaurant.name}."
                    State.NothingNearby -> "No fast food within ${Prefs.radiusMeters(this@MainActivity).toInt()} m."
                    is State.Error -> s.message
                }
                val key = (s as? State.Found)?.let { it.restaurant.id to it.atMs }
                if (key != null && key != openedFor &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    openedFor = key
                    startActivity(Intent(this@MainActivity, RestaurantActivity::class.java))
                }
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // The process may have been killed (Task Manager "Stop") without the widget hearing about it.
        DriveWidget.refresh(this)
    }

    private fun refresh() {
        permButton.isEnabled = !hasLocation()
        permButton.text = if (hasLocation()) "Permissions granted" else "Grant location & notifications"
    }

    private fun requestOrStartWatching() {
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
