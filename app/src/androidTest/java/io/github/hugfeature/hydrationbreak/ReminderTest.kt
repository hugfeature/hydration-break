package io.github.hugfeature.hydrationbreak

import android.app.NotificationManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

class ReminderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings get() = Scheduler.prefs(context)
    @After fun cancelScheduledWork() {
        val manager = context.getSystemService(android.app.AlarmManager::class.java)
        for (kind in listOf(Scheduler.WATER, Scheduler.BREAK, Scheduler.TEST)) {
            val operation = android.app.PendingIntent.getBroadcast(context, kind,
                Intent(context, ReminderReceiver::class.java).putExtra("kind", kind),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            manager.cancel(operation)
        }
    }
    @Before fun reset() {
        cancelScheduledWork()
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
    @Test fun reopeningDoesNotPostponeOverdueReminder() {
        val overdue = System.currentTimeMillis() - 60_000
        settings.edit().putInt("start", 0).putInt("end", 23).putLong("next_1", overdue).commit()
        instrumentation.runOnMainSync {
            Scheduler.schedule(context, Scheduler.WATER, preserveFuture = true)
            assertEquals(overdue, settings.getLong("next_1", 0))
            cancelScheduledWork()
        }
    }
    @Test fun unchangedWindowDoesNotResetCountdown() {
        Scheduler.rescheduleAll(context)
        val before = settings.getLong("next_1", 0)
        assertTrue(Scheduler.saveWindow(context, Scheduler.start(context), Scheduler.end(context)))
        assertEquals(before, settings.getLong("next_1", 0))
    }
    @Test fun regularRemindersDeliverWhileLockedAfterSettingsChange() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        assertTrue(Scheduler.exactAllowed(context))
        settings.edit().putInt("minutes_1", 15).putInt("minutes_2", 15).commit()
        assertTrue(Scheduler.saveWindow(context, 0, 23))
        val due = settings.getLong("next_1", 0)
        try {
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            shell("dumpsys battery unplug")
            shell("cmd power set-mode 1")
            shell("dumpsys deviceidle force-idle")
            assertTrue(shell("dumpsys deviceidle").contains("mState=IDLE"))
            val deadline = android.os.SystemClock.elapsedRealtime() + 29 * 60_000
            while ((settings.getLong("received_1", 0) == 0L || settings.getLong("received_2", 0) == 0L) &&
                android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(1000)
            for (kind in listOf(Scheduler.WATER, Scheduler.BREAK)) {
                val received = settings.getLong("received_$kind", 0)
                assertTrue("Regular reminder $kind missed its cycle", received >= due && received - due < 15 * 60_000)
                assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == kind })
            }
            assertTrue("Device left idle before evidence collection", shell("dumpsys deviceidle").contains("mState=IDLE"))
        } finally {
            shell("dumpsys deviceidle unforce")
            shell("cmd power set-mode 0")
            shell("dumpsys battery reset")
            shell("input keyevent KEYCODE_WAKEUP")
            shell("input keyevent 82")
        }
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
    @Test fun exerciseSelectionIsRespected() {
        settings.edit().putStringSet("exercise_ids", setOf("wrist")).commit()
        assertEquals("wrist", OfficeExercises.at(OfficeExercises.nextSelected(context, 0)!!).id)
        settings.edit().putStringSet("exercise_ids", emptySet()).commit()
        assertNull(OfficeExercises.nextSelected(context, 0))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        assertTrue(ReminderNotifications.post(context, Scheduler.BREAK).startsWith("已交给"))
    }
    @Test fun exerciseNotificationRotatesAndOpensMatchingInstructions() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        val manager = context.getSystemService(NotificationManager::class.java)
        for (index in 0..OfficeExercises.moves.size) {
            ReminderNotifications.post(context, Scheduler.BREAK)
            val expected = OfficeExercises.at(index)
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            var notification = manager.activeNotifications.firstOrNull { it.id == Scheduler.BREAK }?.notification
            while (notification?.extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()?.contains(expected.steps) != true && android.os.SystemClock.elapsedRealtime() < deadline) {
                android.os.SystemClock.sleep(50)
                notification = manager.activeNotifications.firstOrNull { it.id == Scheduler.BREAK }?.notification
            }
            assertTrue(notification?.extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()?.contains(expected.steps) == true)
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                notification!!.contentIntent.send()
                val activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertNotNull("Notification should open the app", activity)
                assertEquals(index % OfficeExercises.moves.size, activity!!.intent.getIntExtra(OfficeExercises.EXTRA_MOVE, -1))
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            } finally { instrumentation.removeMonitor(monitor) }
        }
        val next = settings.getInt("exercise_next", -1)
        ReminderNotifications.post(context, Scheduler.WATER)
        assertEquals(next, settings.getInt("exercise_next", -1))
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
