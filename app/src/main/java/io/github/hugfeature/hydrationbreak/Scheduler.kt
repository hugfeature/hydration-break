package io.github.hugfeature.hydrationbreak

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object Scheduler {
    const val WATER = 1
    const val BREAK = 2
    private const val PREF = "reminder_settings"
    fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun enabled(context: Context, kind: Int) = prefs(context).getBoolean("enabled_$kind", true)
    fun minutes(context: Context, kind: Int) = prefs(context).getInt("minutes_$kind", if (kind == WATER) 60 else 60)
    fun start(context: Context) = prefs(context).getInt("start", 9)
    fun end(context: Context) = prefs(context).getInt("end", 18)
    private fun pending(context: Context, kind: Int): PendingIntent =
        PendingIntent.getBroadcast(context, kind, Intent(context, ReminderReceiver::class.java).putExtra("kind", kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun rescheduleAll(context: Context) { schedule(context, WATER); schedule(context, BREAK) }
    fun schedule(context: Context, kind: Int) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pending(context, kind))
        if (!enabled(context, kind)) return
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            add(Calendar.MINUTE, minutes(context, kind).coerceIn(15, 240))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val from = start(context)
        val to = end(context)
        if (from >= to) return
        if (next.get(Calendar.HOUR_OF_DAY) < from) {
            next.set(Calendar.HOUR_OF_DAY, from); next.set(Calendar.MINUTE, 0)
        } else if (next.get(Calendar.HOUR_OF_DAY) >= to) {
            next.add(Calendar.DAY_OF_YEAR, 1)
            next.set(Calendar.HOUR_OF_DAY, from); next.set(Calendar.MINUTE, 0)
        }
        // Inexact, battery-conscious alarm: Android may defer delivery in Doze.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending(context, kind))
    }
}
