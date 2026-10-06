package com.wdwy90.pullupmenu.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wdwy90.pullupmenu.R
import com.wdwy90.pullupmenu.car.MenuCarAppService
import com.wdwy90.pullupmenu.phone.RestaurantActivity

object Notifier {
    const val CHANNEL_ARRIVAL = "arrival"
    const val CHANNEL_WATCHING = "watching"
    /** Foreground-service notification id (ArrivalService). */
    const val ID_WATCHING = 1
    /** Data URI scheme of the car-banner intent ("pullupmenu://restaurant/<id>"); MenuSession opens the card. */
    const val CAR_INTENT_SCHEME = "pullupmenu"
    private const val ID_ARRIVAL = 1001
    private const val REQ_PHONE_CARD = 0
    private const val REQ_CAR_CARD = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ARRIVAL, "Drive-thru arrivals", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCHING, "Watching location", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /**
     * Phone notification: "You're at X. Tap for the full menu". With [carBanner] it is also
     * extended for the car screen; tapping it there opens the car app on the restaurant card.
     */
    fun arrival(ctx: Context, r: Restaurant, carBanner: Boolean) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(ctx)
        val tap = PendingIntent.getActivity(
            ctx, REQ_PHONE_CARD, Intent(ctx, RestaurantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ARRIVAL)
            .setSmallIcon(R.drawable.ic_menu)
            .setContentTitle("You're at ${r.name}")
            .setContentText(if (r.menuUrl != null) "Tap for the full menu" else "Tap to see the menu and photos")
            .setContentIntent(tap)
            .setAutoCancel(true)
        if (carBanner) {
            try {
                // Only the car host can fire this; from the phone shade the normal content intent is used.
                val carIntent = Intent(Intent.ACTION_VIEW)
                    .setComponent(ComponentName(ctx, MenuCarAppService::class.java))
                    .setData(Uri.parse("$CAR_INTENT_SCHEME://restaurant/${Uri.encode(r.id)}"))
                val carTap = CarPendingIntent.getCarApp(
                    ctx, REQ_CAR_CARD, carIntent, PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.extend(
                    CarAppExtender.Builder()
                        .setContentTitle("You're at ${r.name}")
                        .setContentText("Tap for the restaurant card")
                        .setSmallIcon(R.drawable.ic_menu)
                        .setContentIntent(carTap)
                        .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                        .build()
                )
                CarNotificationManager.from(ctx).notify(ID_ARRIVAL, builder)
                return
            } catch (e: RuntimeException) {
                // Fall back to a phone-only notification (never via CarNotificationManager,
                // which would extend it for the car anyway).
            }
        }
        NotificationManagerCompat.from(ctx).notify(ID_ARRIVAL, builder.build())
    }

    fun cancelArrival(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID_ARRIVAL)
    }
}
