package com.wdwy90.pullupmenu.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.wdwy90.pullupmenu.core.LocationWatcher
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.MenuRepository.State
import com.wdwy90.pullupmenu.core.Prefs
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private lateinit var status: TextView
    private lateinit var permButton: Button
    private lateinit var driveSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val keyField = findViewById<EditText>(R.id.api_key)
        status = findViewById(R.id.status)
        permButton = findViewById(R.id.grant_permissions)
        driveSwitch = findViewById(R.id.drive_mode)

        keyField.setText(Prefs.apiKey(this))
        findViewById<Button>(R.id.save_key).setOnClickListener {
            Prefs.setApiKey(this, keyField.text.toString())
            Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
            refresh()
        }
        permButton.setOnClickListener { permissionLauncher.launch(requiredPermissions()) }

        findViewById<Button>(R.id.check_now).setOnClickListener {
            if (!hasLocation()) {
                permissionLauncher.launch(requiredPermissions())
            } else {
                LocationWatcher(this).checkNow()
            }
        }

        driveSwitch.isChecked = ArrivalService.running
        driveSwitch.setOnCheckedChangeListener { _, on ->
            val i = Intent(this, ArrivalService::class.java)
            if (on && hasLocation()) ContextCompat.startForegroundService(this, i)
            else {
                stopService(i)
                driveSwitch.isChecked = false
            }
        }

        // Only auto-open the menu for a *new* detection, not one we've already shown.
        var openedFor = (MenuRepository.state.value as? State.Found)?.restaurant?.id
        lifecycleScope.launch {
            MenuRepository.state.collect { s ->
                status.text = when (s) {
                    State.Idle -> "Ready."
                    State.Searching -> "Looking up where you are…"
                    is State.Found -> "You're at ${s.restaurant.name}."
                    State.NothingNearby -> "No restaurant within ${Prefs.radiusMeters(this@MainActivity).toInt()} m."
                    is State.Error -> s.message
                }
                if (s is State.Found && s.restaurant.id != openedFor &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    openedFor = s.restaurant.id
                    startActivity(Intent(this@MainActivity, RestaurantActivity::class.java))
                }
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        permButton.isEnabled = !hasLocation()
        permButton.text = if (hasLocation()) "Permissions granted" else "Grant location & notifications"
        driveSwitch.isChecked = ArrivalService.running
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
