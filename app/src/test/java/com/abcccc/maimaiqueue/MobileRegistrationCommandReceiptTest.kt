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

    @Test
    fun recordingAResultKeepsTheOriginalOutcomeForReliableRedelivery() {
        val first = TerminalCommandReceipt(
            commandId = "00000000-0000-0000-0000-000000000001",
            applied = true,
            detail = "登记已切换机台。"
        )
        val rejected = TerminalCommandReceipt(
            commandId = "00000000-0000-0000-0000-000000000002",
            applied = false,
            detail = "机台已停止使用。"
        )
        val replacement = first.copy(detail = "终端不会重复执行。")

        val updated = appendRecentCommandReceipt(
            existing = listOf(first, rejected),
            receipt = replacement,
            maximumSize = 2
        )

        assertEquals(listOf(rejected, replacement), updated)
        assertEquals(false, updated.first().applied)
        assertEquals("机台已停止使用。", updated.first().detail)
    }
}
