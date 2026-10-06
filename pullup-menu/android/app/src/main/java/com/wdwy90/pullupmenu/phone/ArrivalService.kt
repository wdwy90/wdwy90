package com.wdwy90.pullupmenu.phone

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.core.DriveEndDetector
import com.wdwy90.pullupmenu.core.DriveWatcher
import com.wdwy90.pullupmenu.core.Notifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Phone watching for drives without Android Auto (auto-detect ON): shares [DriveWatcher] with the car,
 * and stops by itself after 15 minutes parked or 4 hours.
 * Only ever started from a visible activity (switch, button, widget trampoline).
 */
class ArrivalService : LifecycleService() {
    private var holding = false
    private val driveEnd = DriveEndDetector()

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        // Must come first: startForegroundService() requires startForeground() before any stopSelf().
        try {
            ServiceCompat.startForeground(
                this, Notifier.ID_WATCHING, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            // SecurityException (no location permission, API 34+) or
            // ForegroundServiceStartNotAllowedException (API 31+, an IllegalStateException).
            stopSelf()
            return
        }
        try {
            DriveWatcher.acquire(this, DriveWatcher.OWNER_PHONE)
            holding = true
        } catch (e: SecurityException) {
            stopSelf()
            return
        }
        _running.value = true
        DriveWidget.refresh(this)
        // Start the clocks now so watching still stops after 15 min / 4 h if no fix ever arrives.
        driveEnd.start(System.currentTimeMillis())

        // One clock for both: the device clock (not Location.time, which is GPS time).
        lifecycleScope.launch {
            DriveWatcher.fixes.collect {
                if (driveEnd.onSample(it.latitude, it.longitude, System.currentTimeMillis())) stopSelf()
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(TICK_MS)
                if (driveEnd.onTick(System.currentTimeMillis())) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        // Never restart from the background: a sticky restart could not start a location FGS.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (holding) DriveWatcher.release(DriveWatcher.OWNER_PHONE)
        holding = false
        _running.value = false
        DriveWidget.refresh(this)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        // Explicit intent; the service only stops itself, it never starts an activity (no trampoline).
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ArrivalService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Notifier.CHANNEL_WATCHING)
            .setSmallIcon(R.drawable.ic_menu)
            .setContentTitle("Watching for drive-thrus")
            .setContentText("Stops by itself after 15 minutes parked.")
            .setContentIntent(open)
            .addAction(R.drawable.ic_menu, "Stop", stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.wdwy90.pullupmenu.action.STOP_WATCHING"
        private const val TICK_MS = 60_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        /** Call only while an activity of ours is visible (or from a widget/notification tap). */
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ArrivalService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ArrivalService::class.java))
        }
    }
}
