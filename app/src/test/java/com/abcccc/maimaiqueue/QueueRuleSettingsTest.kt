package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRuleSettingsTest {
    @Test
    fun queueRuleSettingsUseStableDefaultMachineRemarks() {
        val settings = QueueRuleSettings()

        assertEquals(true, settings.websiteSyncEnabled)
        assertEquals(true, settings.allowOnlineRegistration)
        assertEquals(true, settings.showCommonPlayPreview)
        assertEquals(2, settings.configuredMachineCount)
        assertEquals(listOf(MachineId.A, MachineId.B), settings.configuredMachineIds)
        assertEquals("左侧", settings.machineARemark)
        assertEquals("右侧", settings.machineBRemark)
        assertEquals("中间左侧", settings.machineRemark(MachineId.C))
        assertEquals("中间右侧", settings.machineRemark(MachineId.D))
    }

    @Test
    fun configuredMachineIdsAreAlwaysContiguousFromAThroughD() {
        assertEquals(listOf(MachineId.A), configuredMachineIds(0))
        assertEquals(listOf(MachineId.A), configuredMachineIds(1))
        assertEquals(listOf(MachineId.A, MachineId.B), configuredMachineIds(2))
        assertEquals(listOf(MachineId.A, MachineId.B, MachineId.C), configuredMachineIds(3))
        assertEquals(MachineId.entries, configuredMachineIds(4))
        assertEquals(MachineId.entries, configuredMachineIds(5))
    }

    @Test
    fun machineRemarkNormalizationTrimsAndFallsBackForLegacyMissingValues() {
        assertEquals("入口侧", normalizeMachineRemark("  入口侧  ", DEFAULT_MACHINE_A_REMARK))
        assertEquals("左侧", normalizeMachineRemark(null, DEFAULT_MACHINE_A_REMARK))
        assertEquals("左侧", normalizeMachineRemark("   ", DEFAULT_MACHINE_A_REMARK))
    }

    @Test
    fun machineRemarkLengthUsesVisibleUnicodeCharacters() {
        assertEquals(
            "一二三四五六七八",
            limitMachineRemarkLength("一二三四五六七八九")
        )
        assertEquals(
            "A区入口侧",
            limitMachineRemarkLength("A区入口侧")
        )
    }

    @Test
    fun queueSyncEndpointAcceptsSiteRootAndCanonicalEndpoint() {
        assertEquals(
            "https://example.com/api/queue-status",
            normalizeQueueSyncEndpoint(" https://example.com/ ")
        )
        assertEquals(
            "https://example.com/api/queue-status",
            normalizeQueueSyncEndpoint("https://example.com/api/queue-status/")
        )
        assertEquals(
            "https://example.com/custom/api/queue-status",
            normalizeQueueSyncEndpoint("https://example.com/custom/api/queue-status")
        )
    }

    @Test
    fun queueSyncEndpointRejectsUnsafeOrIncompleteAddresses() {
        assertNull(normalizeQueueSyncEndpoint("http://example.com"))
        assertNull(normalizeQueueSyncEndpoint("https://example.com/api/queue-status?debug=1"))
        assertNull(normalizeQueueSyncEndpoint("https:///api/queue-status"))
        assertNull(normalizeQueueSyncEndpoint("example.com"))
    }

    @Test
    fun queueSyncTokenUsesUtf8ByteMinimumAndCharacterMaximum() {
        assertFalse(isValidQueueSyncToken("a".repeat(31)))
        assertTrue(isValidQueueSyncToken("a".repeat(32)))
        assertTrue(isValidQueueSyncToken("中".repeat(11)))
        assertFalse(isValidQueueSyncToken("a".repeat(MAX_QUEUE_SYNC_TOKEN_CHARACTERS + 1)))
    }

    @Test
    fun runtimeSettingsNormalizeConnectionAndDisableInvalidSync() {
        val valid = normalizeQueueRuleSettingsForRuntime(
            settings = QueueRuleSettings(
                queueSyncEndpoint = "https://example.com/",
                queueSyncToken = "a".repeat(32)
            ),
            cloudSyncAvailable = true
        )
        assertEquals("https://example.com/api/queue-status", valid.queueSyncEndpoint)
        assertTrue(valid.websiteSyncEnabled)
        assertTrue(valid.oneBotSyncEnabled)

        val invalid = normalizeQueueRuleSettingsForRuntime(
            settings = QueueRuleSettings(
                queueSyncEndpoint = "https://example.com",
                queueSyncToken = "short"
            ),
            cloudSyncAvailable = true
        )
        assertFalse(invalid.websiteSyncEnabled)
        assertFalse(invalid.oneBotSyncEnabled)
    }

    @Test
    fun localRuntimeAlwaysClearsCloudConnection() {
        val settings = normalizeQueueRuleSettingsForRuntime(
            settings = QueueRuleSettings(
                queueSyncEndpoint = "https://example.com/api/queue-status",
                queueSyncToken = "a".repeat(32)
            ),
            cloudSyncAvailable = false
        )

        assertFalse(settings.websiteSyncEnabled)
        assertFalse(settings.oneBotSyncEnabled)
        assertEquals("", settings.queueSyncEndpoint)
        assertEquals("", settings.queueSyncToken)
    }
}
