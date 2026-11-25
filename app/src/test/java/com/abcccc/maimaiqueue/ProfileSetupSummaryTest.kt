package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSetupSummaryTest {
    @Test
    fun notificationSummaryListsTheChosenKindsInSwitchOrder() {
        assertEquals(
            "已开启：队列状态变化；线上登记与签到；暂缓一次、暂时离开和未到场",
            queueNotificationSummary(QueueNotificationPreferences())
        )
        assertEquals(
            "已开启：游玩位置变化；机台及营业状态",
            queueNotificationSummary(
                QueueNotificationPreferences(
                    queueChanges = false,
                    playingPosition = true,
                    onlineCheckIn = false,
                    absence = false,
                    machineStatus = true
                )
            )
        )
    }

    @Test
    fun notificationSummarySaysWhenNothingIsSent() {
        assertEquals("已关闭", queueNotificationSummary(QueueNotificationPreferences(enabled = false)))
        assertEquals(
            "已开启，未选择任何通知内容",
            queueNotificationSummary(
                QueueNotificationPreferences(
                    queueChanges = false,
                    playingPosition = false,
                    onlineCheckIn = false,
                    absence = false,
                    machineStatus = false
                )
            )
        )
    }
}
