package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Test

class EstimatedWaitFormattingTest {
    @Test
    fun zeroMinuteEstimateUsesUncertainSoonWording() {
        assertEquals("很快可以游玩", formatJoinWaitEstimate(0L))
        assertEquals("预计很快可以游玩", formatPositionWaitEstimate(0L))
    }

    @Test
    fun knownAndMissingEstimatesKeepTheirExistingMeaning() {
        assertEquals("约 7 分钟", formatJoinWaitEstimate(7L))
        assertEquals("约 7 分钟后可以游玩", formatPositionWaitEstimate(7L))
        assertEquals("暂时无法估算", formatJoinWaitEstimate(null))
        assertEquals("暂时无法估算", formatPositionWaitEstimate(null))
    }

    @Test
    fun anUncertainEstimateIsTheEarliestAndSaysSo() {
        assertEquals("最快约 25 分钟后可以游玩", formatPositionWaitEstimate(25L, uncertain = true))
        assertEquals("可能很快可以游玩", formatPositionWaitEstimate(0L, uncertain = true))
        // Far from the wait if nobody is moved (24 minutes or more): 最快 stays, and that other
        // time is written on its own line with its condition. Not a range: the wait will be
        // near one of the two.
        assertEquals(
            "系统未在两台机台之间安排登记时，这份登记约 55 分钟后可以游玩。",
            noArrangementWaitNote("这份登记", 55L)
        )
        assertEquals(
            "系统未在两台机台之间安排登记时，这个位置约 40 分钟后可以游玩。",
            noArrangementWaitNote("这个位置", 40L)
        )
        assertEquals(null, noArrangementWaitNote("这份登记", null))
        assertEquals("暂时无法估算", formatPositionWaitEstimate(null, uncertain = true))
        assertEquals(
            "受系统在两台机台之间安排登记的影响，等待时间可能不准确，请以现场实际情况为准。",
            UNCERTAIN_WAIT_NOTE
        )
    }

    @Test
    fun aRegistrationThatMayMoveKeepsItsOwnWaitButNotForThePositionsBeforeIt() {
        // 3 may be arranged to the other machine: 8 minutes there, 40 here.
        val estimates = WaitEstimator.Result(
            registrations = mapOf(
                1 to WaitEstimator.Estimate(12 * MINUTE, uncertain = false),
                2 to WaitEstimator.Estimate(27 * MINUTE, uncertain = false),
                3 to WaitEstimator.Estimate(8 * MINUTE, uncertain = true)
            ),
            newRegistrationWaitMillis = emptyMap(),
            here = mapOf(3 to WaitEstimator.Estimate(40 * MINUTE, uncertain = true))
        )
        val positions = listOf(setOf(1), setOf(2), setOf(3))
        assertEquals(ShownWait(12L, uncertain = false), shownPositionWait(estimates, positions, setOf(1)))
        assertEquals(ShownWait(27L, uncertain = false), shownPositionWait(estimates, positions, setOf(2)))
        // Its own menu tells its own, earlier time.
        assertEquals(ShownWait(8L, uncertain = true), shownPositionWait(estimates, positions, setOf(3)))
    }

    @Test
    fun aPositionShowsItsRoundedWaitInTheMachinesOrder() {
        val estimates = WaitEstimator.Result(
            registrations = mapOf(
                1 to WaitEstimator.Estimate(12 * MINUTE + 20_000L, uncertain = false),
                2 to WaitEstimator.Estimate((47.6 * MINUTE).toLong(), uncertain = false),
                3 to WaitEstimator.Estimate(49 * MINUTE, uncertain = true)
            ),
            newRegistrationWaitMillis = emptyMap()
        )
        val positions = listOf(setOf(1), setOf(2), setOf(3))
        assertEquals(ShownWait(12L, uncertain = false), shownPositionWait(estimates, positions, setOf(1)))
        // 47.6 would be shown as 50, but never later than the position behind it (45 as an
        // earliest time).
        assertEquals(ShownWait(45L, uncertain = false), shownPositionWait(estimates, positions, setOf(2)))
        assertEquals(ShownWait(45L, uncertain = true), shownPositionWait(estimates, positions, setOf(3)))
        assertEquals(null, shownPositionWait(estimates, positions, setOf(9)))
    }

    @Test
    fun theEarlierTimeForMissingCheckInsIsToldOnlyWhenItShowsEarlier() {
        assertEquals(
            "前方的线上登记未按时签到时，这份登记最快约 10 分钟后可以游玩。",
            onlineMissingWaitNote("这份登记", 10 * MINUTE + 40_000L, 25L)
        )
        assertEquals(
            "前方的线上登记未按时签到时，这个位置可能很快可以游玩。",
            onlineMissingWaitNote("这个位置", 20_000L, 9L)
        )
        // Rounded down as an earliest time is; nothing when it does not show earlier.
        assertEquals(45L, onlineMissingWaitMinutes(49 * MINUTE, 50L))
        // Above two hours both would read 2 小时以上: nothing to tell.
        assertEquals(null, onlineMissingWaitMinutes(125 * MINUTE, 150L))
        assertEquals(null, onlineMissingWaitMinutes(25 * MINUTE, 25L))
        assertEquals(null, onlineMissingWaitNote("这份登记", null, 25L))
        assertEquals(null, onlineMissingWaitNote("这份登记", 10 * MINUTE, null))
    }

    private companion object {
        const val MINUTE = 60_000L
    }

    @Test
    fun joiningWithTheTwoMachineTickBoxShowsTheEarlierMachineAsTheEarliest() {
        assertEquals("最快约 12 分钟", formatJoinWaitEstimate(25L, pairedEarliestMinutes = 12L))
        assertEquals("可能很快可以游玩", formatJoinWaitEstimate(25L, pairedEarliestMinutes = 0L))
        assertEquals("最快约 12 分钟", formatJoinWaitEstimate(null, pairedEarliestMinutes = 12L))
        // The other machine is not earlier, or the tick box is off: the middle estimate as before.
        assertEquals("约 25 分钟", formatJoinWaitEstimate(25L, pairedEarliestMinutes = 25L))
        assertEquals("约 25 分钟", formatJoinWaitEstimate(25L, pairedEarliestMinutes = 40L))
        assertEquals("约 25 分钟", formatJoinWaitEstimate(25L, pairedEarliestMinutes = null))
        assertEquals("很快可以游玩", formatJoinWaitEstimate(0L, pairedEarliestMinutes = 0L))
        assertEquals("暂时无法估算", formatJoinWaitEstimate(null, pairedEarliestMinutes = null))
    }

    @Test
    fun aPositionEarlierThanOneAheadOfItSaysWhy() {
        // X-B1 (review 2026-11-27): position 4 is sure to be arranged to the other machine, so
        // position 5 passes position 3 and plays with position 2.
        val minute = 60_000L
        fun estimate(minutes: Long) = WaitEstimator.Estimate(minutes * minute, uncertain = false)
        val positions = listOf(setOf(1), setOf(2), setOf(3), setOf(4), setOf(5))
        val result = WaitEstimator.Result(
            registrations = mapOf(1 to estimate(10), 2 to estimate(25), 3 to estimate(40), 4 to estimate(41), 5 to estimate(25)),
            newRegistrationWaitMillis = emptyMap(),
            moveConfidence = mapOf(4 to 1.0)
        )
        assertEquals(
            "受系统在两台机台之间安排登记的影响，这个位置预计早于前方的部分位置游玩。",
            earlierThanAheadNote("这个位置", result, positions, setOf(5), 25L)
        )
        assertEquals(
            "受系统在两台机台之间安排登记的影响，这份登记预计早于前方的部分登记游玩。",
            earlierThanAheadNote("这份登记", result, positions, setOf(5), 25L)
        )
        // In order: nothing to say.
        assertEquals(null, earlierThanAheadNote("这个位置", result, positions, setOf(3), 40L))
        // No registration ahead may be arranged away: the cause is not there, nothing is said.
        val noMove = result.copy(moveConfidence = emptyMap())
        assertEquals(null, earlierThanAheadNote("这个位置", noMove, positions, setOf(5), 25L))
    }

    @Test
    fun aWaitOfAnHourOrMoreIsWrittenInHoursAndAboveTwoHoursWithoutANumber() {
        // Maintainer, 2026-11-28: from 60 minutes on X 小时 X 分钟, no zero minutes; above 120
        // minutes 2 小时以上.
        assertEquals("约 45 分钟后可以游玩", formatPositionWaitEstimate(45L))
        assertEquals("约 1 小时后可以游玩", formatPositionWaitEstimate(60L))
        assertEquals("约 1 小时 15 分钟后可以游玩", formatPositionWaitEstimate(75L))
        assertEquals("最快约 1 小时 30 分钟后可以游玩", formatPositionWaitEstimate(90L, uncertain = true))
        assertEquals("约 2 小时后可以游玩", formatPositionWaitEstimate(120L))
        assertEquals("2 小时以上后可以游玩", formatPositionWaitEstimate(121L))
        assertEquals("2 小时以上后可以游玩", formatPositionWaitEstimate(146L, uncertain = true))
        assertEquals("约 1 小时 5 分钟", formatJoinWaitEstimate(65L))
        assertEquals("2 小时以上", formatJoinWaitEstimate(160L))
        assertEquals(
            "系统未在两台机台之间安排登记时，这份登记 2 小时以上后可以游玩。",
            noArrangementWaitNote("这份登记", 150L)
        )
        assertEquals(
            "系统未在两台机台之间安排登记时，这个位置约 1 小时 40 分钟后可以游玩。",
            noArrangementWaitNote("这个位置", 100L)
        )
    }
}

