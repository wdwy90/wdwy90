package com.wdwy90.pullupmenu.phone

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.wdwy90.pullupmenu.R

/** 2x1 home-screen widget: one tap starts or stops phone watching (via [StartWatchingActivity]). */
class DriveWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetManager.updateAppWidget(appWidgetIds, views(context))
    }

    companion object {
        /** Redraws every placed instance; a no-op when none are placed. */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return // null on devices without widgets
            try {
                mgr.updateAppWidget(ComponentName(ctx, DriveWidget::class.java), views(ctx))
            } catch (e: RuntimeException) {
                // Widget host unavailable; the label catches up on the next refresh.
            }
        }

        private fun views(ctx: Context): RemoteViews {
            val watching = ArrivalService.running.value
            val tap = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, StartWatchingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return RemoteViews(ctx.packageName, R.layout.widget_drive).apply {
                setTextViewText(
                    R.id.widget_label,
                    ctx.getString(if (watching) R.string.widget_watching else R.string.widget_idle),
                )
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
        }
    }
}
