package io.github.hugfeature.hydrationbreak

import android.app.NotificationManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReminderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings get() = Scheduler.prefs(context)
    @Before fun reset() {
        settings.edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
    @Test fun independentSettingsAndCancellation() {
        settings.edit().putInt("minutes_1", 15).putInt("minutes_2", 120).putInt("start", 0).putInt("end", 23).commit()
        Scheduler.rescheduleAll(context)
        val rest = settings.getLong("next_2", 0)
        assertTrue(rest > settings.getLong("next_1", 0))
        settings.edit().putBoolean("enabled_1", false).commit()
        Scheduler.schedule(context, Scheduler.WATER)
        assertEquals(0L, settings.getLong("next_1", 0))
        assertEquals(rest, settings.getLong("next_2", 0))
    }
    @Test fun invalidWindowLeavesScheduleUntouched() {
        Scheduler.rescheduleAll(context)
        val before = settings.all
        assertFalse(Scheduler.saveWindow(context, 18, 9))
        assertEquals(before, settings.all)
        assertTrue(Scheduler.saveWindow(context, 8, 20))
        assertEquals(8, Scheduler.start(context))
        assertEquals(20, Scheduler.end(context))
    }
    @Test fun reopeningPreservesDueTime() {
        Scheduler.rescheduleAll(context)
        val water = settings.getLong("next_1", 0)
        Scheduler.rescheduleAll(context, preserveFuture = true)
        assertTrue(water > 0)
        assertEquals(water, settings.getLong("next_1", 0))
    }
    @Test fun bootHandlerHonorsDisabledReminder() {
        settings.edit().putBoolean("enabled_1", false).commit()
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(0L, settings.getLong("next_1", 0))
        assertTrue(settings.getLong("next_2", 0) > System.currentTimeMillis())
    }
    @Test fun unknownKindIgnored() {
        ReminderReceiver().onReceive(context, Intent().putExtra("kind", 99))
        assertFalse(settings.contains("next_99"))
    }
    @Test fun receiverPostsAndReschedulesWhenAllowed() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        settings.edit().putInt("start", 0).putInt("end", 23).commit()
        // CI sets the device clock to noon; invoke the real receiver, not a timed delivery.
        ReminderReceiver().onReceive(context, Intent().putExtra("kind", Scheduler.WATER))
        assertTrue(settings.getLong("next_1", 0) > System.currentTimeMillis())
        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (manager.activeNotifications.none { it.id == Scheduler.WATER } && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(50)
        }
        assertTrue("Notification not posted within 5 seconds", manager.activeNotifications.any { it.id == Scheduler.WATER })
    }
    @Test fun scheduledTestActuallyDeliversWhileIdle() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        // Use an invalid regular window to prove this explicitly requested test bypasses it.
        settings.edit().putInt("start", 18).putInt("end", 9).commit()
        try {
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            shell("dumpsys battery unplug")
            shell("dumpsys deviceidle enable")
            shell("dumpsys deviceidle force-idle")
            assertTrue("Device did not enter Doze", shell("dumpsys deviceidle").contains("mState=IDLE"))
            assertTrue(Scheduler.scheduleTest(context, 1000))
            val manager = context.getSystemService(NotificationManager::class.java)
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
            while ((manager.activeNotifications.none { it.id == Scheduler.TEST } || settings.getLong("received_3", 0) == 0L || settings.contains("next_3")) && android.os.SystemClock.elapsedRealtime() < deadline) {
                android.os.SystemClock.sleep(100)
            }
            assertTrue("No actual alarm delivery within 120 seconds", settings.getLong("received_3", 0) > 0)
            assertTrue("No notification after alarm delivery", manager.activeNotifications.any { it.id == Scheduler.TEST })
            assertEquals(0L, settings.getLong("next_3", 0))
            assertFalse(settings.contains("next_1"))
            assertFalse(settings.contains("next_2"))
        } finally {
            shell("dumpsys deviceidle unforce")
            shell("dumpsys battery reset")
            shell("input keyevent KEYCODE_WAKEUP")
            shell("input keyevent 82")
        }
    }

}
