package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditLogsTest {
    private fun registration(
        key: Int,
        name: String = "玩家-$key",
        preference: PlayPreference = PlayPreference.OPEN_TO_JOIN
    ) = Registration(
        key = key,
        displayId = name,
        preference = preference,
        createdAtMillis = 100L + key
    )

    private fun queueLog(
        before: MachineQueue,
        after: MachineQueue,
        titleOverride: String? = null
    ) = createQueueAuditLog(
        category = AuditLogCategory.MACHINE_A,
        machineLabel = "机台 A",
        before = before,
        after = after,
        titleOverride = titleOverride,
        timestampMillis = 1_000L
    )

    @Test
    fun unchangedQueueDoesNotCreateLog() {
        val queue = MachineQueue(waiting = listOf(registration(1)))

        assertNull(queueLog(queue, queue))
    }

    @Test
    fun firstRegistrationIsReportedAsAddedEvenWhenItStartsPlaying() {
        val added = registration(1, "小明")
        val log = queueLog(
            before = MachineQueue(),
            after = MachineQueue(playing = listOf(added), playingStartedAtMillis = 500L)
        )!!

        assertEquals("机台 A · 新增登记", log.title)
        assertTrue(log.detail.contains("新增 “小明”"))
        assertEquals(1_000L, log.timestampMillis)
    }

    @Test
    fun registrationFieldChangesAreDescribed() {
        val original = registration(1, "原昵称")
        val changed = original.copy(
            displayId = "新昵称",
            preference = PlayPreference.SOLO,
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
            noShowCount = 1
        )
        val log = queueLog(
            before = MachineQueue(waiting = listOf(original)),
            after = MachineQueue(waiting = listOf(changed))
        )!!

        assertTrue(log.detail.contains("“原昵称”更名为“新昵称”"))
        assertTrue(log.detail.contains("“新昵称”改为单人游玩"))
        assertTrue(log.detail.contains("“新昵称”已暂缓一轮"))
        assertTrue(log.detail.contains("第 1 次未到场"))
    }

    @Test
    fun noShowOnlyChangeUsesNoShowTitle() {
        val original = registration(1, "玩家")
        val changed = original.copy(noShowCount = 1)
        val log = queueLog(
            before = MachineQueue(waiting = listOf(original)),
            after = MachineQueue(waiting = listOf(changed))
        )!!

        assertEquals("机台 A · 未到场状态已更新", log.title)
        assertTrue(log.detail.contains("“玩家”已记录第 1 次未到场"))
    }

    @Test
    fun temporaryAwaySkipAndAutomaticExitAreExplained() {
        val away = registration(1, "暂离玩家").copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 3
        )
        val skippedLog = queueLog(
            before = MachineQueue(waiting = listOf(away.copy(temporaryAwaySkippedTurns = 2))),
            after = MachineQueue(waiting = listOf(away))
        )!!
        val removedLog = queueLog(
            before = MachineQueue(waiting = listOf(away)),
            after = MachineQueue()
        )!!

        assertTrue(skippedLog.detail.contains("已轮空 3 次"))
        assertTrue(removedLog.detail.contains("第四次轮到"))
        assertTrue(removedLog.detail.contains("自动退出排队"))
        assertEquals(PublicQueueEventType.TEMPORARY_AWAY_EXPIRED, removedLog.publicEventType)
    }

    @Test
    fun profileGenderChangesAreRecordedAsARegistrationDetailUpdate() {
        val original = registration(1).copy(
            playerProfileId = "profile-1",
            gender = PlayerGender.UNDISCLOSED,
            isTemporary = false
        )
        val changed = original.copy(gender = PlayerGender.FEMALE)

        val log = queueLog(
            before = MachineQueue(waiting = listOf(original)),
            after = MachineQueue(waiting = listOf(changed))
        )!!

        assertEquals("机台 A · 登记资料已更新", log.title)
        assertTrue(log.detail.contains("性别标识已更新"))
    }

    @Test
    fun orderChangesAndExplicitActionTitlesArePreserved() {
        val first = registration(1)
        val second = registration(2)
        val log = queueLog(
            before = MachineQueue(waiting = listOf(first, second)),
            after = MachineQueue(waiting = listOf(second, first)),
            titleOverride = "机台 A 的等待顺序已调整"
        )!!

        assertEquals("机台 A 的等待顺序已调整", log.title)
        assertTrue(log.detail.contains("登记顺序已调整"))
    }

    @Test
    fun playerProfileCreationAndEditingAreReported() {
        val original = PlayerProfile(
            id = "profile-1",
            nickname = "小红",
            gender = PlayerGender.FEMALE,
            defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
            createdAtMillis = 100L,
            updatedAtMillis = 100L
        )
        val createdLog = createPlayerProfileAuditLog(null, original, timestampMillis = 200L)
        val editedLog = createPlayerProfileAuditLog(
            original,
            original.copy(
                nickname = "小虹",
                defaultPreference = ProfilePlayPreference.OPEN_TO_JOIN,
                qqNumber = "12345678",
                updatedAtMillis = 300L
            ),
            timestampMillis = 300L
        )

        assertEquals("新建玩家资料", createdLog.title)
        assertTrue(createdLog.detail.contains("“小红”"))
        assertEquals("编辑玩家资料", editedLog.title)
        assertTrue(editedLog.detail.contains("昵称由“小红”改为“小虹”"))
        assertTrue(editedLog.detail.contains("默认偏好改为允许他人加入"))
        assertTrue(editedLog.detail.contains("联系方式已更新"))
        assertTrue(!editedLog.detail.contains("12345678"))
    }
}
