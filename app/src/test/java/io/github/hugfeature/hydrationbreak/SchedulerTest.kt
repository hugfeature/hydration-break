package io.github.hugfeature.hydrationbreak

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SchedulerTest {
    private fun at(hour: Int, minute: Int = 0, second: Int = 0) = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        clear(); set(2026, Calendar.OCTOBER, 8, hour, minute, second)
    }
    @Test fun intervalDoesNotRoundDown() {
        assertEquals(at(11, 10, 45).timeInMillis, Scheduler.nextTrigger(at(10, 10, 45), 60, 9, 18))
    }
    @Test fun beforeWindowStartsAtNine() {
        assertEquals(at(9).timeInMillis, Scheduler.nextTrigger(at(6), 60, 9, 18))
    }
    @Test fun endIsExclusive() {
        val tomorrow = at(9).apply { add(Calendar.DAY_OF_YEAR, 1) }
        assertEquals(tomorrow.timeInMillis, Scheduler.nextTrigger(at(17), 60, 9, 18))
    }
    @Test fun midnightRolloverDoesNotSkipDay() {
        val tomorrow = at(9).apply { add(Calendar.DAY_OF_YEAR, 1) }
        assertEquals(tomorrow.timeInMillis, Scheduler.nextTrigger(at(23, 45), 60, 9, 18))
    }
    @Test fun invalidWindowRejected() {
        assertNull(Scheduler.nextTrigger(at(10), 60, 18, 9))
        assertNull(Scheduler.nextTrigger(at(10), 60, 9, 9))
        assertNull(Scheduler.nextTrigger(at(10), 60, -1, 18))
    }
}
