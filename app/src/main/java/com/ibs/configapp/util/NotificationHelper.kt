package com.ibs.configapp.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ibs.configapp.DeviceManagementInfoActivity
import com.ibs.configapp.R

object NotificationHelper {
    const val CHANNEL_ID = "ibs_finance_fg_silent"
    const val FCM_CHANNEL_ID = "ibs_fcm_channel"
    const val NOTIFICATION_ID = 1001
    const val FOREGROUND_ID = 1001
    const val ALERT_NOTIFICATION_ID = 1002

    fun getDisplayDealerName(context: Context): String {
        val cachedName = PrefsHelper.getDealerName(context)
        if (cachedName.isNotBlank()) return cachedName
        val dealerId = PrefsHelper.getDealerId(context)
        if (!dealerId.isNullOrBlank()) return dealerId
        return context.getString(R.string.device_management_dealer_fallback)
    }

    fun getNotificationTitle(context: Context): String =
        context.getString(R.string.device_management_title)

    fun getNotificationBody(context: Context): String {
        val dealerName = getDisplayDealerName(context)
        return context.getString(R.string.device_management_notification_body, dealerName)
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)

        val financeChannel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.device_management_title),
            NotificationManager.IMPORTANCE_NONE
        ).apply {
            description = context.getString(R.string.device_management_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            enableVibration(false)
            setVibrationPattern(null)
            setSound(null, null)
            setBypassDnd(false)
        }
        nm.createNotificationChannel(financeChannel)

        val fcmChannel = NotificationChannel(
            FCM_CHANNEL_ID,
            context.getString(R.string.fcm_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.fcm_channel_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            setVibrationPattern(null)
            setSound(null, null)
        }
        nm.createNotificationChannel(fcmChannel)
    }

    private fun buildContentIntent(context: Context): PendingIntent {
        val intent = Intent(context, DeviceManagementInfoActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun buildProtectionNotification(context: Context): Notification {
        createChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(" ")
            .setContentText(" ")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(false)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setVibrate(null)
            .setSound(null)
            .setDefaults(0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    fun buildMinimalNotification(context: Context): Notification {
        createChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(" ")
            .setContentText(" ")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(false)
            .setAutoCancel(false)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setVibrate(null)
            .setSound(null)
            .setDefaults(0)
            .build()
    }

    fun showAlertNotification(context: Context, title: String, message: String) {
        createChannel(context)
        val notification = NotificationCompat.Builder(context, FCM_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(context))
            .setVibrate(null)
            .setSound(null)
            .setDefaults(0)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(ALERT_NOTIFICATION_ID, notification)
    }
}
