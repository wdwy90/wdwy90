package com.wdwy90.pullupmenu.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.wdwy90.pullupmenu.core.Prefs

/**
 * Widget trampoline with no UI (translucent). Starting the location foreground service from here is
 * allowed because this activity is the visible, top activity while onCreate runs.
 */
class StartWatchingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        when {
            ArrivalService.running.value -> {
                ArrivalService.stop(this)
                Toast.makeText(app, "Stopped watching for drive-thrus", Toast.LENGTH_SHORT).show()
            }
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED -> try {
                Prefs.setAutoDetect(this, true)
                ArrivalService.start(this)
                Toast.makeText(app, "Watching for drive-thrus", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                openMain()
            }
            else -> openMain()
        }
        // Also fixes a stale "Watching" label after the process was killed without callbacks.
        DriveWidget.refresh(this)
        finish()
    }

    /** Own task (this trampoline has an empty taskAffinity), so MainActivity shows up in Recents. */
    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
