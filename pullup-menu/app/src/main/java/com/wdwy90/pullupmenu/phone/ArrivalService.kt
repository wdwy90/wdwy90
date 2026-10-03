package com.wdwy90.pullupmenu.phone

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.LocationWatcher
import com.wdwy90.pullupmenu.core.Notifier

/**
 * Phone-only "drive mode" for when you're not plugged into Android Auto:
 * keeps watching location and notifies you when you pull up to a restaurant.
 * (On Android Auto the car screen session does this itself.)
 */
class ArrivalService : LifecycleService() {
    private lateinit var watcher: LocationWatcher

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, Notifier.CHANNEL_WATCHING)
            .setSmallIcon(R.drawable.ic_menu)
            .setContentTitle("Watching for restaurants")
            .setContentText("You'll get the menu when you pull up somewhere.")
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        watcher = LocationWatcher(this)
        try {
            watcher.start()
            running = true
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        watcher.stop()
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set
    }
}
