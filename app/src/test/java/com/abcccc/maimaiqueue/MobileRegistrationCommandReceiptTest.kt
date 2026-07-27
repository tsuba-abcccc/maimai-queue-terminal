package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileRegistrationCommandReceiptTest {
    @Test
    fun recordingMovesExistingIdToTheEndAndKeepsTheConfiguredLimit() {
        val first = "00000000-0000-0000-0000-000000000001"
        val second = "00000000-0000-0000-0000-000000000002"
        val third = "00000000-0000-0000-0000-000000000003"

        val updated = appendRecentCommandId(
            existing = listOf(first, second, third),
            commandId = first,
            maximumSize = 2
        )

        assertEquals(listOf(third, first), updated)
    }
}
