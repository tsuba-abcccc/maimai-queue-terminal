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
        assertEquals("暂时无法估算", formatPositionWaitEstimate(null, uncertain = true))
        assertEquals(
            "受系统在两台机台之间安排登记的影响，等待时间可能不准确，请以现场实际情况为准。",
            UNCERTAIN_WAIT_NOTE
        )
    }

    @Test
    fun aPositionShowsItsRoundedWaitInTheMachinesOrder() {
        val estimates = WaitEstimator.Result(
            registrations = mapOf(
                1 to WaitEstimator.Estimate(12 * MINUTE + 20_000L, uncertain = false),
                2 to WaitEstimator.Estimate((32.6 * MINUTE).toLong(), uncertain = false),
                3 to WaitEstimator.Estimate(34 * MINUTE, uncertain = true)
            ),
            newRegistrationWaitMillis = emptyMap()
        )
        val positions = listOf(setOf(1), setOf(2), setOf(3))
        assertEquals(ShownWait(12L, uncertain = false), shownPositionWait(estimates, positions, setOf(1)))
        // 32.6 would be shown as 35, but never later than the position behind it.
        assertEquals(ShownWait(30L, uncertain = false), shownPositionWait(estimates, positions, setOf(2)))
        assertEquals(ShownWait(30L, uncertain = true), shownPositionWait(estimates, positions, setOf(3)))
        assertEquals(null, shownPositionWait(estimates, positions, setOf(9)))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
