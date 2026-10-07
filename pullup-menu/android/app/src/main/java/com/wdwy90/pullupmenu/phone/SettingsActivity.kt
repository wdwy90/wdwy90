package com.wdwy90.pullupmenu.phone

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.BuildConfig
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.ChainPrices
import com.wdwy90.pullupmenu.core.KeyMask
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.Prefs
import kotlinx.coroutines.launch

/**
 * Settings: detection switches, permissions, Android Auto status, the Places API key (masked; never
 * shown in full), theme, and troubleshooting facts.
 */
class SettingsActivity : ThemedActivity() {

    /** Set when the user asked to start phone watching but location permission was still missing. */
    private var startAfterGrant = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            renderPermissions()
            if (startAfterGrant && hasLocation() && Prefs.autoDetect(this)) startWatching()
            startAfterGrant = false
            renderSwitches()
        }

    private lateinit var autoSwitch: Switch
    private lateinit var autoOptions: View
    private lateinit var carBanner: Switch
    private lateinit var driveMode: Switch
    private lateinit var keyEditor: View
    private lateinit var keyField: EditText
    private lateinit var keyError: TextView
    private lateinit var changeKey: Button
    private lateinit var removeKey: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyInsets(findViewById(R.id.root))
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        setUpDetection()
        setUpPermissions()
        setUpKey()
        setUpTheme()
        setUpTroubleshooting()

        fact(R.id.fact_car, R.drawable.ic_car, "Status")
        CarConnection(this).type.observe(this) { type ->
            setFact(
                R.id.fact_car,
                when (type) {
                    CarConnection.CONNECTION_TYPE_PROJECTION -> "Connected"
                    CarConnection.CONNECTION_TYPE_NATIVE -> "Built-in car screen"
                    else -> "Not connected"
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have changed in system settings.
        renderPermissions()
        renderSwitches()
    }

    override fun onStop() {
        // Never keep a typed key around once the screen is hidden.
        closeKeyEditor()
        super.onStop()
    }

    // ---- Detection ----

    private fun setUpDetection() {
        autoSwitch = findViewById(R.id.auto_detect)
        autoOptions = findViewById(R.id.auto_detect_options)
        carBanner = findViewById(R.id.car_banner)
        driveMode = findViewById(R.id.drive_mode)

        renderSwitches()
        autoSwitch.setOnCheckedChangeListener { _, on ->
            autoOptions.visibility = if (on) View.VISIBLE else View.GONE
            // Ignore programmatic syncs (e.g. the widget turned it on while we were paused).
            if (on == Prefs.autoDetect(this)) return@setOnCheckedChangeListener
            Prefs.setAutoDetect(this, on)
            if (on) requestOrStartWatching() else ArrivalService.stop(this)
        }
        carBanner.setOnCheckedChangeListener { _, on -> Prefs.setCarBanner(this, on) }
        driveMode.setOnCheckedChangeListener { _, on ->
            if (on == ArrivalService.running.value) return@setOnCheckedChangeListener
            if (on) requestOrStartWatching() else ArrivalService.stop(this)
            // Shows the real state: stays off if permission is still missing or the start failed.
            renderSwitches()
        }

        lifecycleScope.launch { Prefs.autoDetectFlow(this@SettingsActivity).collect { renderSwitches() } }
        lifecycleScope.launch { ArrivalService.running.collect { renderSwitches() } }
    }

    private fun renderSwitches() {
        val on = Prefs.autoDetect(this)
        setQuietly(autoSwitch, on)
        autoOptions.visibility = if (on) View.VISIBLE else View.GONE
        setQuietly(carBanner, Prefs.carBanner(this))
        setQuietly(driveMode, ArrivalService.running.value)
    }

    /** Sets a switch without the change looking like a user tap (listeners compare with the saved state). */
    private fun setQuietly(button: CompoundButton, checked: Boolean) {
        if (button.isChecked != checked) button.isChecked = checked
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

    // ---- Permissions ----

    private fun setUpPermissions() {
        fact(R.id.fact_location, R.drawable.ic_my_location, "Location")
        fact(R.id.fact_notifications, R.drawable.ic_info, "Notifications")
        findViewById<Button>(R.id.grant_permissions).setOnClickListener {
            permissionLauncher.launch(requiredPermissions())
        }
        findViewById<Button>(R.id.app_settings).setOnClickListener {
            open(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
    }

    private fun renderPermissions() {
        val fine = hasLocation()
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val notifications = NotificationManagerCompat.from(this).areNotificationsEnabled()
        setFact(
            R.id.fact_location,
            when {
                fine -> "Allowed"
                coarse -> "Approximate only"
                else -> "Not allowed"
            }
        )
        setFact(R.id.fact_notifications, if (notifications) "On" else "Off")
        findViewById<View>(R.id.grant_permissions).visibility =
            if (fine && notifications) View.GONE else View.VISIBLE
    }

    // ---- Places API key ----

    private fun setUpKey() {
        keyEditor = findViewById(R.id.key_editor)
        keyField = findViewById(R.id.api_key)
        keyError = findViewById(R.id.key_error)
        changeKey = findViewById(R.id.change_key)
        removeKey = findViewById(R.id.remove_key)
        fact(R.id.fact_key_source, R.drawable.ic_key, "Source")
        fact(R.id.fact_key, R.drawable.ic_info, "Key")

        changeKey.setOnClickListener { openKeyEditor() }
        findViewById<Button>(R.id.cancel_key).setOnClickListener { closeKeyEditor() }
        findViewById<Button>(R.id.save_key).setOnClickListener { saveKey() }
        removeKey.setOnClickListener { confirmRemoveKey() }
        renderKey()
    }

    private fun renderKey() {
        val userKey = Prefs.hasUserKey(this)
        val key = Prefs.apiKey(this)
        setFact(
            R.id.fact_key_source,
            when {
                userKey -> "Your key"
                Prefs.hasBuiltInKey() -> "Built into this app"
                else -> "Not set"
            }
        )
        // Only the last four characters, never the whole key.
        setFact(R.id.fact_key, KeyMask.mask(key))
        findViewById<View>(R.id.fact_key).contentDescription =
            if (key.isBlank()) "Key: not set" else "Key: hidden, ends in ${key.takeLast(4).toCharArray().joinToString(" ")}"
        changeKey.text = if (key.isBlank()) "Add key" else "Change key"
        removeKey.visibility = if (userKey) View.VISIBLE else View.GONE
    }

    private fun openKeyEditor() {
        keyField.text.clear()
        keyError.visibility = View.GONE
        keyEditor.visibility = View.VISIBLE
        changeKey.visibility = View.GONE
        keyField.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(keyField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeKeyEditor() {
        if (!::keyField.isInitialized) return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(keyField.windowToken, 0)
        keyField.text.clear()
        keyError.visibility = View.GONE
        keyEditor.visibility = View.GONE
        changeKey.visibility = View.VISIBLE
    }

    private fun saveKey() {
        val typed = keyField.text.toString().trim()
        if (!KeyMask.looksValid(typed)) {
            keyError.text = "That doesn't look like a Places API key. Keys start with \"AIza\" and are 39 characters."
            keyError.visibility = View.VISIBLE // a polite live region, so TalkBack reads it
            return
        }
        Prefs.setApiKey(this, typed)
        closeKeyEditor()
        renderKey()
        Toast.makeText(this, "Key saved", Toast.LENGTH_SHORT).show()
    }

    private fun confirmRemoveKey() {
        val message = if (Prefs.hasBuiltInKey()) {
            "Pull Up Menu will go back to the key built into the app."
        } else {
            "Detection stops working until you add a key again."
        }
        AlertDialog.Builder(this)
            .setTitle("Remove your key?")
            .setMessage(message)
            .setPositiveButton("Remove") { _, _ ->
                Prefs.setApiKey(this, "")
                renderKey()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Theme ----

    private fun setUpTheme() {
        val group = findViewById<RadioGroup>(R.id.theme_group)
        group.check(
            when (Prefs.theme(this)) {
                Prefs.THEME_LIGHT -> R.id.theme_light
                Prefs.THEME_SYSTEM -> R.id.theme_system
                else -> R.id.theme_dark
            }
        )
        group.setOnCheckedChangeListener { _, id ->
            val theme = when (id) {
                R.id.theme_light -> Prefs.THEME_LIGHT
                R.id.theme_system -> Prefs.THEME_SYSTEM
                else -> Prefs.THEME_DARK
            }
            if (theme == Prefs.theme(this)) return@setOnCheckedChangeListener
            Prefs.setTheme(this, theme)
            recreate()
        }
    }

    // ---- Troubleshooting ----

    private fun setUpTroubleshooting() {
        val prices = ChainPrices.get(this)
        fact(R.id.fact_version, R.drawable.ic_info, "Version")
        fact(R.id.fact_data, R.drawable.ic_menu, "Menu data")
        fact(R.id.fact_radius, R.drawable.ic_place, "Search radius")
        setFact(R.id.fact_version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        setFact(R.id.fact_data, "${prices.chainCount} chains · ${prices.checked}")
        setFact(R.id.fact_radius, "${Prefs.radiusMeters(this).toInt()} m")
        findViewById<Button>(R.id.demo).setOnClickListener {
            MenuRepository.showDemo(this)
            startActivity(Intent(this, RestaurantActivity::class.java))
        }
    }

    // ---- Helpers ----

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

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
