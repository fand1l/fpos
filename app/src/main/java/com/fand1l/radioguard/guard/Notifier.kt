package com.fand1l.radioguard.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.fand1l.radioguard.MainActivity
import com.fand1l.radioguard.R

class Notifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private var shownAlarm: Pair<String, String>? = null

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                context.getString(R.string.channel_alarm),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun status(text: String): Notification = Notification.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(R.drawable.ic_stat_guard)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .build()

    fun showStatus(text: String) {
        manager.notify(ID_STATUS, status(text))
    }

    /** [ongoing] = false для тривоги, яка лишається після зупинки служби: її можна змахнути. */
    fun showAlarm(title: String, text: String, ongoing: Boolean = true) {
        val alarm = title to text
        if (alarm == shownAlarm) return
        // Нова тривога має прозвучати, а не тихо замінити стару.
        manager.cancel(ID_ALARM)
        shownAlarm = alarm
        manager.notify(
            ID_ALARM,
            Notification.Builder(context, CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_stat_guard)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(ongoing)
                .setContentIntent(openApp())
                .build(),
        )
    }

    fun clearAlarm() {
        if (shownAlarm == null) return
        shownAlarm = null
        manager.cancel(ID_ALARM)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ID_STATUS = 1
        private const val ID_ALARM = 2
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALARM = "alarm"
    }
}
