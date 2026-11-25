package com.abcccc.maimaiqueue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStatusTest {
    @Test
    fun batteryNeedsAttentionOnlyWhenLowAndNotCharging() {
        assertTrue(TerminalBatteryStatus(level = LOW_BATTERY_PERCENT, charging = false).low)
        assertTrue(TerminalBatteryStatus(level = 5, charging = false).low)
        assertFalse(TerminalBatteryStatus(level = 5, charging = true).low)
        assertFalse(TerminalBatteryStatus(level = LOW_BATTERY_PERCENT + 1, charging = false).low)
        assertFalse(TerminalBatteryStatus(level = null, charging = false).low)
    }

    @Test
    fun syncNeedsAttentionWhenRetryingOrIncompleteButNotWhenSwitchedOff() {
        assertTrue(QueueCloudSyncStatus(QueueCloudSyncPhase.WAITING_TO_RETRY).needsAttention())
        assertTrue(QueueCloudSyncStatus(QueueCloudSyncPhase.NOT_CONFIGURED).needsAttention())
        listOf(
            QueueCloudSyncPhase.DISABLED,
            QueueCloudSyncPhase.CONFIGURED,
            QueueCloudSyncPhase.SYNCING,
            QueueCloudSyncPhase.SYNCED
        ).forEach { phase ->
            assertFalse(phase.name, QueueCloudSyncStatus(phase).needsAttention())
        }
    }
}
