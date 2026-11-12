package com.fand1l.radioguard.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.fand1l.radioguard.App
import com.fand1l.radioguard.MainActivity
import com.fand1l.radioguard.R
import com.fand1l.radioguard.guard.GuardService
import com.fand1l.radioguard.guard.GuardSummary
import com.fand1l.radioguard.guard.QuickAction
import com.fand1l.radioguard.guard.QuickState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GuardWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TURN_ON) {
            // Запуск служби дозволений саме зараз, поки триває обробка дотику.
            GuardService.enable(context)
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext as App
        val pending = goAsync()
        app.appScope.launch {
            try {
                render(app, app.quickStates.first())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_TURN_ON = "com.fand1l.radioguard.action.WIDGET_TURN_ON"

        fun render(context: Context, state: QuickState) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, GuardWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(context, state))
        }

        fun views(context: Context, state: QuickState): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_guard).apply {
                setTextViewText(R.id.widget_status, context.getString(statusText(state)))
                setInt(R.id.widget_root, "setBackgroundResource", background(state.summary))
                setOnClickPendingIntent(R.id.widget_root, clickIntent(context, state.action))
            }

        private fun statusText(state: QuickState): Int = when (state.summary) {
            GuardSummary.OFF -> if (state.shizukuReady) R.string.quick_off_hint else R.string.quick_no_shizuku
            GuardSummary.STARTING -> R.string.quick_starting
            GuardSummary.OK -> R.string.quick_ok
            GuardSummary.VIOLATION -> R.string.quick_violation
            GuardSummary.NO_SHELL -> R.string.quick_no_shell
        }

        private fun background(summary: GuardSummary): Int = when (summary) {
            GuardSummary.OFF, GuardSummary.STARTING -> R.drawable.widget_bg_off
            GuardSummary.OK -> R.drawable.widget_bg_ok
            GuardSummary.VIOLATION, GuardSummary.NO_SHELL -> R.drawable.widget_bg_alarm
        }

        private fun clickIntent(context: Context, action: QuickAction): PendingIntent {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return when (action) {
                QuickAction.TURN_ON -> PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, GuardWidget::class.java).setAction(ACTION_TURN_ON),
                    flags,
                )
                QuickAction.OPEN_APP -> MainActivity.pendingIntent(context, confirmOff = false)
                QuickAction.CONFIRM_OFF -> MainActivity.pendingIntent(context, confirmOff = true)
            }
        }
    }
}
