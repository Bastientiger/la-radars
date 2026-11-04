package com.laradars.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen button that starts or stops driving mode. */
class ToggleWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            if (TrackerState.current.running) TrackingService.stop(context)
            else context.startActivity(
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_START_TRIP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_TOGGLE = "com.laradars.app.WIDGET_TOGGLE"

        fun build(context: Context): RemoteViews {
            val running = TrackerState.current.running
            val views = RemoteViews(context.packageName, R.layout.widget_toggle)
            views.setTextViewText(R.id.widgetText, context.getString(if (running) R.string.widget_stop else R.string.widget_start))
            views.setInt(R.id.widgetRoot, "setBackgroundResource", if (running) R.drawable.widget_bg_on else R.drawable.widget_bg_off)
            val pi = PendingIntent.getBroadcast(
                context, 20, Intent(context, ToggleWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)
            return views
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ToggleWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, build(context))
        }
    }
}
