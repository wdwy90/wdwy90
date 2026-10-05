package com.wdwy90.pullupmenu.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.phone.RestaurantActivity

object Notifier {
    const val CHANNEL_ARRIVAL = "arrival"
    const val CHANNEL_WATCHING = "watching"
    private const val ID_ARRIVAL = 1001

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ARRIVAL, "Drive-thru arrivals", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCHING, "Watching location", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** Phone notification: "You're at X — tap for the menu". */
    fun arrival(ctx: Context, r: Restaurant) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(ctx)
        val tap = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, RestaurantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ARRIVAL)
            .setSmallIcon(R.drawable.ic_menu)
            .setContentTitle("You're at ${r.name}")
            .setContentText(if (r.menuUrl != null) "Tap for the full menu" else "Tap to see the menu and photos")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_ARRIVAL, n)
    }
}
