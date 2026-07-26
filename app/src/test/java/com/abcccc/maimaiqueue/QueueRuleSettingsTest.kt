package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRuleSettingsTest {
    @Test
    fun queueRuleSettingsUseStableDefaultMachineRemarks() {
        val settings = QueueRuleSettings()

        assertEquals(true, settings.websiteSyncEnabled)
        assertEquals(true, settings.allowOnlineRegistration)
        assertEquals("左侧", settings.machineARemark)
        assertEquals("右侧", settings.machineBRemark)
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
}
