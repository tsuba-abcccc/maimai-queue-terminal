package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Test

class EstimatedWaitFormattingTest {
    @Test
    fun zeroMinuteEstimateUsesUncertainSoonWording() {
        assertEquals("预计很快可以游玩", formatJoinWaitEstimate(0L))
        assertEquals("预计很快可以游玩", formatPositionWaitEstimate(0L))
    }

    @Test
    fun knownAndMissingEstimatesKeepTheirExistingMeaning() {
        assertEquals("约 7 分钟", formatJoinWaitEstimate(7L))
        assertEquals("约 7 分钟后可以游玩", formatPositionWaitEstimate(7L))
        assertEquals("暂时无法估算", formatJoinWaitEstimate(null))
        assertEquals("暂时无法估算", formatPositionWaitEstimate(null))
    }
}
