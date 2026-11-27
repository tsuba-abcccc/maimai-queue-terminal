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
    fun closingWarningStartsExactlyThirtyMinutesBeforeClosing() {
        val settings = BusinessHoursSettings(
            enabled = true,
            defaultHours = DailyBusinessHours(10 * 60, 22 * 60)
        )
        val beforeWarning = evaluateBusinessHours(
            settings,
            timestamp(2026, 7, 25, 21, 29),
            zone
        )
        val atWarning = evaluateBusinessHours(
            settings,
            timestamp(2026, 7, 25, 21, 30),
            zone
        )
        val beforeClosing = evaluateBusinessHours(
            settings,
            timestamp(2026, 7, 25, 21, 59),
            zone
        )

        assertFalse(beforeWarning.closingSoon)
        assertTrue(atWarning.closingSoon)
        assertTrue(beforeClosing.closingSoon)
        assertFalse(atWarning.outsideBusinessHours)
        assertFalse(atWarning.closingGracePeriod)
        assertNotNull(atWarning.activeClosingAtMillis)
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
    fun handledClosingOccurrenceDoesNotBlockAManuallyReopenedQueue() {
        val settings = BusinessHoursSettings(
            enabled = true,
            defaultHours = DailyBusinessHours(10 * 60, 22 * 60)
        )
        val status = evaluateBusinessHours(settings, timestamp(2026, 7, 25, 22, 5), zone)

        assertTrue(hasUnhandledClosingOccurrence(status, null))
        assertTrue(isActiveClosingGracePeriod(status, null))
        assertFalse(
            hasUnhandledClosingOccurrence(status, status.mostRecentClosingOccurrenceId)
        )
        assertFalse(
            isActiveClosingGracePeriod(status, status.mostRecentClosingOccurrenceId)
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

    @Test
    fun venueSettingsRoundTripPreservesWeeklySchedule() {
        val settings = BusinessHoursSettings(
            enabled = true,
            useWeeklySchedule = true,
            defaultHours = DailyBusinessHours(9 * 60, 23 * 60),
            weeklyHours = defaultWeeklyBusinessHours() +
                (DayOfWeek.FRIDAY to DailyBusinessHours(10 * 60, 2 * 60))
        )

        val restored = settings.toVenueSettingsJson().toBusinessHoursSettingsOrNull()

        assertEquals(settings.normalized(), restored)
    }

    /**
     * Maintainer, 2026-11-27: a tablet switched off before closing and on again the next day
     * starts a new queue when nobody answers the restore dialog. Restarting during the same
     * business day, or late at night after closing, still continues the queue.
     */
    @Test
    fun aQueueFromAnEarlierBusinessDayIsRecognised() {
        val hours = BusinessHoursSettings(enabled = true, defaultHours = DailyBusinessHours(10 * 60, 22 * 60))
        fun earlier(saved: Long, now: Long) = savedQueueIsFromEarlierDay(hours, saved, now, zone)
        // Switched off before closing, on again the next day within business hours.
        assertTrue(earlier(timestamp(2026, 11, 26, 21, 30), timestamp(2026, 11, 27, 11, 0)))
        // A restart during the same business day.
        assertFalse(earlier(timestamp(2026, 11, 26, 15, 0), timestamp(2026, 11, 26, 15, 5)))
        // After closing but within the grace period: players may still be finishing.
        assertFalse(earlier(timestamp(2026, 11, 26, 21, 50), timestamp(2026, 11, 26, 22, 10)))
        // After the grace period of that closing.
        assertTrue(earlier(timestamp(2026, 11, 26, 21, 50), timestamp(2026, 11, 26, 22, 25)))
        // Saved after closing, restarted before the next opening: nothing has begun or ended since.
        assertFalse(earlier(timestamp(2026, 11, 26, 22, 30), timestamp(2026, 11, 27, 9, 30)))
        // ... and after it.
        assertTrue(earlier(timestamp(2026, 11, 26, 22, 30), timestamp(2026, 11, 27, 10, 0)))
        // Review 2026-11-27: registrations taken shortly before the doors open belong to the day
        // that then begins; a restart just after opening keeps them.
        assertFalse(earlier(timestamp(2026, 11, 27, 9, 55), timestamp(2026, 11, 27, 10, 2)))
        assertFalse(earlier(timestamp(2026, 11, 27, 4, 30), timestamp(2026, 11, 27, 10, 30)))
        // The middle of the closed hours (22:00 to 10:00) is 4:00.
        assertTrue(earlier(timestamp(2026, 11, 27, 3, 30), timestamp(2026, 11, 27, 10, 30)))
        // Saved long ago.
        assertTrue(earlier(timestamp(2026, 10, 1, 12, 0), timestamp(2026, 11, 27, 12, 0)))

        // Overnight hours (12:00 to 02:00): a restart after midnight is the same business day.
        val overnight = BusinessHoursSettings(enabled = true, defaultHours = DailyBusinessHours(12 * 60, 2 * 60))
        assertFalse(savedQueueIsFromEarlierDay(overnight, timestamp(2026, 11, 26, 23, 50), timestamp(2026, 11, 27, 0, 30), zone))
        assertTrue(savedQueueIsFromEarlierDay(overnight, timestamp(2026, 11, 26, 23, 50), timestamp(2026, 11, 27, 13, 0), zone))
    }

    @Test
    fun withoutBusinessHoursADayBeginsAtFive() {
        val none = BusinessHoursSettings(enabled = false)
        fun earlier(saved: Long, now: Long) = savedQueueIsFromEarlierDay(none, saved, now, zone)
        assertTrue(earlier(timestamp(2026, 11, 26, 21, 30), timestamp(2026, 11, 27, 11, 0)))
        // Past midnight but before five: still the same night.
        assertFalse(earlier(timestamp(2026, 11, 26, 23, 50), timestamp(2026, 11, 27, 0, 30)))
        assertFalse(earlier(timestamp(2026, 11, 26, 15, 0), timestamp(2026, 11, 26, 15, 5)))
        assertTrue(earlier(timestamp(2026, 11, 27, 4, 50), timestamp(2026, 11, 27, 5, 10)))
        // A clock set back never counts as another day.
        assertFalse(earlier(timestamp(2026, 11, 27, 12, 0), timestamp(2026, 11, 27, 11, 0)))
    }

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
