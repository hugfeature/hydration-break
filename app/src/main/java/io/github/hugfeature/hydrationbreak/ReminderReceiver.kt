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

object ReminderNotifications {
    fun blockedReason(context: Context): String? {
        val manager = context.getSystemService(NotificationManager::class.java)
        if ((Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) || !manager.areNotificationsEnabled()) return "通知权限未开启"
        if (manager.getNotificationChannel("reminder")?.importance == NotificationManager.IMPORTANCE_NONE) return "健康提醒通知类别已关闭"
        return null
    }
    fun post(context: Context, kind: Int): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("reminder", "健康提醒", NotificationManager.IMPORTANCE_DEFAULT))
        blockedReason(context)?.let { return it }
        val settings = Scheduler.prefs(context)
        val moveIndex = OfficeExercises.nextSelected(context, settings.getInt("exercise_next", 0))
        val open = Intent(context, MainActivity::class.java)
        if (kind == Scheduler.BREAK && moveIndex != null) open.putExtra(OfficeExercises.EXTRA_MOVE, moveIndex)
        val title = when (kind) {
            Scheduler.WATER -> "喝点水吧"
            Scheduler.BREAK -> if (moveIndex != null) "运动一下 · ${OfficeExercises.at(moveIndex).title}" else "休息一下吧"
            else -> "提醒测试"
        }
        val body = when (kind) {
            Scheduler.WATER -> "喝几口水，补充水分。"
            Scheduler.BREAK -> if (moveIndex != null) "${OfficeExercises.at(moveIndex).steps} 不适即停。" else "暂时放下工作，换个舒适姿势。你可以在动作库中选择适合的动作。"
            else -> "这是测试通知；不会改变喝水和运动的提醒计划。"
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "通知权限未开启"
        return try {
            manager.notify(kind, android.app.Notification.Builder(context, "reminder")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(body).setAutoCancel(true)
                .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                .setContentIntent(PendingIntent.getActivity(context, kind, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .build())
            if (kind == Scheduler.BREAK && moveIndex != null) settings.edit().putInt("exercise_next", (moveIndex + 1) % OfficeExercises.moves.size).apply()
            "已交给系统通知服务（请查看通知栏）"
        } catch (error: SecurityException) {
            android.util.Log.w("Reminder", "Notification permission revoked", error)
            "系统拒绝通知，请检查通知权限"
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getIntExtra("kind", -1)
        if (!Scheduler.validKind(kind) && kind != Scheduler.TEST) return
        val settings = Scheduler.prefs(context)
        val received = System.currentTimeMillis()
        val due = settings.getLong("next_$kind", 0)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val result = when {
            kind == Scheduler.TEST -> ReminderNotifications.post(context, kind)
            !Scheduler.enabled(context, kind) -> "提醒已关闭"
            hour < Scheduler.start(context) || hour >= Scheduler.end(context) -> "到达时已超出提醒时段，本次跳过"
            else -> ReminderNotifications.post(context, kind)
        }
        settings.edit().putLong("due_$kind", due).putLong("received_$kind", received).putString("result_$kind", result).apply()
        if (kind == Scheduler.TEST) settings.edit().remove("next_$kind").apply()
        else Scheduler.schedule(context, kind)
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            Scheduler.rescheduleAll(context, preserveFuture = intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED)
        }
    }
}
