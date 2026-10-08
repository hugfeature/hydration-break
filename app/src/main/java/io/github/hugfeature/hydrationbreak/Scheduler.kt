package io.github.hugfeature.hydrationbreak

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

object Scheduler {
    const val WATER = 1
    const val BREAK = 2
    private const val PREF = "reminder_settings"
    fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun validKind(kind: Int) = kind == WATER || kind == BREAK
    fun enabled(context: Context, kind: Int) = prefs(context).getBoolean("enabled_$kind", true)
    fun minutes(context: Context, kind: Int) = prefs(context).getInt("minutes_$kind", 60)
    fun start(context: Context) = prefs(context).getInt("start", 9)
    fun end(context: Context) = prefs(context).getInt("end", 18)
    fun saveWindow(context: Context, from: Int, to: Int): Boolean {
        if (from !in 0..23 || to !in 0..23 || from >= to) return false
        prefs(context).edit().putInt("start", from).putInt("end", to).apply()
        rescheduleAll(context)
        return true
    }
    private fun pending(context: Context, kind: Int): PendingIntent =
        PendingIntent.getBroadcast(context, kind, Intent(context, ReminderReceiver::class.java).putExtra("kind", kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun rescheduleAll(context: Context, preserveFuture: Boolean = false) {
        schedule(context, WATER, preserveFuture); schedule(context, BREAK, preserveFuture)
    }
    fun nextTrigger(now: Calendar, interval: Int, from: Int, to: Int): Long? {
        if (from !in 0..23 || to !in 0..23 || from >= to) return null
        val next = (now.clone() as Calendar).apply { add(Calendar.MINUTE, interval.coerceIn(15, 240)) }
        if (next.get(Calendar.HOUR_OF_DAY) < from) {
            next.set(Calendar.HOUR_OF_DAY, from); next.set(Calendar.MINUTE, 0)
            next.set(Calendar.SECOND, 0); next.set(Calendar.MILLISECOND, 0)
        } else if (next.get(Calendar.HOUR_OF_DAY) >= to) {
            next.add(Calendar.DAY_OF_YEAR, 1)
            next.set(Calendar.HOUR_OF_DAY, from); next.set(Calendar.MINUTE, 0)
            next.set(Calendar.SECOND, 0); next.set(Calendar.MILLISECOND, 0)
        }
        return next.timeInMillis
    }
    fun schedule(context: Context, kind: Int, preserveFuture: Boolean = false) {
        if (!validKind(kind)) return
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = pending(context, kind)
        manager.cancel(operation)
        val settings = prefs(context)
        val saved = settings.getLong("next_$kind", 0)
        settings.edit().remove("next_$kind").remove("error_$kind").apply()
        if (!enabled(context, kind)) return
        val now = Calendar.getInstance()
        val calculated = nextTrigger(now, minutes(context, kind), start(context), end(context)) ?: return
        val trigger = if (preserveFuture && saved > now.timeInMillis) saved else calculated
        try {
            // Inexact alarm, allowed during idle but still subject to system throttling.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
            settings.edit().putLong("next_$kind", trigger).apply()
        } catch (error: SecurityException) {
            Log.e("Reminder", "Alarm scheduling denied", error)
            settings.edit().putString("error_$kind", "系统拒绝安排提醒，请重新打开应用重试").apply()
        }
    }
}
