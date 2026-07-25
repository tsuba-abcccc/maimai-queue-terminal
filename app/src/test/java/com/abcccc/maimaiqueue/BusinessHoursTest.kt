package com.abcccc.maimaiqueue

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessHoursTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun disabledHoursDoNotChangeExistingQueueState() {
        val status = evaluateBusinessHours(
            BusinessHoursSettings(enabled = false),
            timestamp(2026, 7, 25, 23, 30)
        )

        assertEquals(BusinessHoursStatus.Disabled, status)
    }

    @Test
    fun ordinaryScheduleStaysOpenWithoutTheLegacyPreClosingWarning() {
        val settings = BusinessHoursSettings(
            enabled = true,
            defaultHours = DailyBusinessHours(10 * 60, 22 * 60)
        )
        val status = evaluateBusinessHours(settings, timestamp(2026, 7, 25, 21, 45))

        assertFalse(status.outsideBusinessHours)
        assertFalse(status.closingSoon)
        assertFalse(status.closingGracePeriod)
        assertNotNull(status.activeClosingAtMillis)
    }

    @Test
    fun closingBoundaryIsOutsideAndHasStableOccurrenceId() {
        val settings = BusinessHoursSettings(
            enabled = true,
            defaultHours = DailyBusinessHours(10 * 60, 22 * 60)
        )
        val atClose = timestamp(2026, 7, 25, 22, 0)
        val first = evaluateBusinessHours(settings, atClose)
        val second = evaluateBusinessHours(settings, atClose + 10_000L)

        assertTrue(first.outsideBusinessHours)
        assertFalse(first.closingSoon)
        assertTrue(first.closingGracePeriod)
        assertEquals(atClose + CLOSING_GRACE_MILLIS, first.registrationClosesAtMillis)
        assertEquals(first.mostRecentClosingOccurrenceId, second.mostRecentClosingOccurrenceId)
        assertEquals(atClose, first.mostRecentClosingAtMillis)
    }

    @Test
    fun closingGraceEndsExactlyTwentyMinutesAfterClosing() {
        val settings = BusinessHoursSettings(
            enabled = true,
            defaultHours = DailyBusinessHours(10 * 60, 22 * 60)
        )
        val atClose = timestamp(2026, 7, 25, 22, 0)

        val duringGrace = evaluateBusinessHours(
            settings,
            atClose + CLOSING_GRACE_MILLIS - 1L,
            zone
        )
        val afterGrace = evaluateBusinessHours(
            settings,
            atClose + CLOSING_GRACE_MILLIS,
            zone
        )

        assertTrue(duringGrace.closingGracePeriod)
        assertFalse(afterGrace.closingGracePeriod)
        assertNull(afterGrace.registrationClosesAtMillis)
    }

    @Test
    fun estimatedWaitWarnsOnlyWhenItExtendsPastClosing() {
        val settings = BusinessHoursSettings(
            enabled = true,
            defaultHours = DailyBusinessHours(10 * 60, 22 * 60)
        )
        val now = timestamp(2026, 7, 25, 21, 40)
        val status = evaluateBusinessHours(settings, now, zone)

        assertFalse(estimatedWaitExtendsPastClosing(status, now, 20L))
        assertTrue(estimatedWaitExtendsPastClosing(status, now, 21L))
        assertFalse(estimatedWaitExtendsPastClosing(status, now, null))
    }

    @Test
    fun closingGraceWaitsForAQueueButClosesAsSoonAsItBecomesEmpty() {
        val settings = BusinessHoursSettings(
            enabled = true,
            defaultHours = DailyBusinessHours(10 * 60, 22 * 60)
        )
        val atClose = timestamp(2026, 7, 25, 22, 0)
        val duringGrace = evaluateBusinessHours(settings, atClose + 5 * 60_000L, zone)

        assertNull(
            businessHoursCloseTrigger(
                status = duringGrace,
                nowMillis = atClose + 5 * 60_000L,
                registrationCount = 1,
                lastHandledOccurrenceId = null
            )
        )
        assertEquals(
            BusinessHoursCloseTrigger.QUEUE_EMPTY_DURING_GRACE,
            businessHoursCloseTrigger(
                status = duringGrace,
                nowMillis = atClose + 5 * 60_000L,
                registrationCount = 0,
                lastHandledOccurrenceId = null
            )
        )
    }

    @Test
    fun closingGraceExpiresOnceForEachClosingOccurrence() {
        val settings = BusinessHoursSettings(
            enabled = true,
            defaultHours = DailyBusinessHours(10 * 60, 22 * 60)
        )
        val atClose = timestamp(2026, 7, 25, 22, 0)
        val afterGrace = atClose + CLOSING_GRACE_MILLIS
        val status = evaluateBusinessHours(settings, afterGrace, zone)

        assertEquals(
            BusinessHoursCloseTrigger.GRACE_PERIOD_EXPIRED,
            businessHoursCloseTrigger(status, afterGrace, 4, null)
        )
        assertNull(
            businessHoursCloseTrigger(
                status,
                afterGrace,
                4,
                status.mostRecentClosingOccurrenceId
            )
        )
    }

    @Test
    fun overnightScheduleRemainsOpenAfterMidnight() {
        val settings = BusinessHoursSettings(
            enabled = true,
            defaultHours = DailyBusinessHours(18 * 60, 2 * 60)
        )
        val status = evaluateBusinessHours(settings, timestamp(2026, 7, 26, 1, 30))

        assertFalse(status.outsideBusinessHours)
        assertEquals(timestamp(2026, 7, 26, 2, 0), status.activeClosingAtMillis)
    }

    @Test
    fun weeklyScheduleUsesTheOpeningDayForOvernightIntervals() {
        val defaultHours = DailyBusinessHours(10 * 60, 22 * 60)
        val fridayLate = DailyBusinessHours(18 * 60, 2 * 60)
        val settings = BusinessHoursSettings(
            enabled = true,
            useWeeklySchedule = true,
            defaultHours = defaultHours,
            weeklyHours = defaultWeeklyBusinessHours() + (DayOfWeek.FRIDAY to fridayLate)
        )

        assertFalse(
            evaluateBusinessHours(settings, timestamp(2026, 7, 25, 1, 30)).outsideBusinessHours
        )
        assertTrue(
            evaluateBusinessHours(settings, timestamp(2026, 7, 25, 3, 0)).outsideBusinessHours
        )
    }

    @Test
    fun exactOpeningAndClosingTimesAreHandledWithoutGaps() {
        val settings = BusinessHoursSettings(
            enabled = true,
            defaultHours = DailyBusinessHours(10 * 60, 22 * 60)
        )

        assertFalse(evaluateBusinessHours(settings, timestamp(2026, 7, 25, 10, 0)).outsideBusinessHours)
        assertTrue(evaluateBusinessHours(settings, timestamp(2026, 7, 25, 22, 0)).outsideBusinessHours)
        assertNull(evaluateBusinessHours(BusinessHoursSettings(), timestamp(2026, 7, 25, 10, 0)).activeClosingAtMillis)
    }

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
