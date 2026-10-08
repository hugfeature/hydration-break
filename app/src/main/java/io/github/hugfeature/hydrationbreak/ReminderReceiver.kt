package io.github.hugfeature.hydrationbreak

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getIntExtra("kind", -1)
        if (!Scheduler.validKind(kind)) return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (Scheduler.enabled(context, kind) && hour >= Scheduler.start(context) && hour < Scheduler.end(context)) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("reminder", "健康提醒", NotificationManager.IMPORTANCE_DEFAULT))
            if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                val isWater = kind == Scheduler.WATER
                val notification = android.app.Notification.Builder(context, "reminder")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(if (isWater) "喝点水吧" else "起来活动一下")
                    .setContentText(if (isWater) "喝几口水，补充水分。" else "离开屏幕，活动身体、远眺 1–2 分钟。")
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(context, kind, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    .build()
                try {
                    if (nm.areNotificationsEnabled()) nm.notify(kind, notification)
                } catch (error: SecurityException) {
                    // Permission can be revoked between checking and posting. Keep scheduling.
                    android.util.Log.w("Reminder", "Notification permission revoked", error)
                }
            }
        }
        Scheduler.schedule(context, kind)
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            Scheduler.rescheduleAll(context)
        }
    }
}
