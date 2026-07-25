package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        titleOverride: String? = null,
        publicEventTypeOverride: PublicQueueEventType? = null
    ) = createQueueAuditLog(
        category = AuditLogCategory.MACHINE_A,
        machineLabel = "机台 A",
        before = before,
        after = after,
        titleOverride = titleOverride,
        publicEventTypeOverride = publicEventTypeOverride,
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
    fun fixedPairLogUsesTheEffectivePreferenceInsteadOfItsInternalOpenState() {
        val first = registration(1, "小雨").copy(preference = PlayPreference.SOLO)
        val second = registration(2, "青空").copy(preference = PlayPreference.SOLO)
        val before = MachineQueue(waiting = listOf(first, second))
        val after = MachineQueue(
            waiting = listOf(
                first.copy(
                    preference = PlayPreference.OPEN_TO_JOIN,
                    fixedPartnerKey = second.key
                ),
                second.copy(
                    preference = PlayPreference.OPEN_TO_JOIN,
                    fixedPartnerKey = first.key
                )
            )
        )

        val log = requireNotNull(
            createQueueAuditLog(
                category = AuditLogCategory.MACHINE_A,
                machineLabel = "左侧 · 机台 A",
                before = before,
                after = after
            )
        )

        assertEquals("左侧 · 机台 A · 固定组合已修改", log.title)
        assertTrue(log.detail.contains("“小雨”改为与朋友共同游玩"))
        assertTrue(log.detail.contains("“青空”改为与朋友共同游玩"))
        assertFalse(log.detail.contains("允许他人加入"))
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
    fun noShowActionTitlesDescribeTheChosenHandling() {
        val original = registration(1, "未到场玩家")
        val deferred = original.copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
            noShowCount = 1,
            lastNoShowActionWasDefer = true
        )
        val movedToTail = original.copy(
            noShowCount = 1,
            lastNoShowActionWasDefer = false
        )

        val deferredLog = queueLog(
            before = MachineQueue(waiting = listOf(original)),
            after = MachineQueue(waiting = listOf(deferred)),
            publicEventTypeOverride = PublicQueueEventType.NO_SHOW_DEFERRED
        )!!
        val movedLog = queueLog(
            before = MachineQueue(waiting = listOf(original, registration(2))),
            after = MachineQueue(waiting = listOf(registration(2), movedToTail)),
            publicEventTypeOverride = PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL
        )!!
        val removedLog = queueLog(
            before = MachineQueue(waiting = listOf(original)),
            after = MachineQueue(),
            publicEventTypeOverride = PublicQueueEventType.NO_SHOW_REMOVED
        )!!

        assertEquals("机台 A · 未到场 · 已暂缓一轮", deferredLog.title)
        assertEquals("机台 A · 未到场 · 已移至队尾", movedLog.title)
        assertEquals("机台 A · 未到场 · 已移除登记", removedLog.title)
        assertTrue(removedLog.detail.contains("“未到场玩家”本次未到场，登记已移除"))
    }

    @Test
    fun completedRoundReportsClearedNoShowRecordWithoutReplacingPlayingEvent() {
        val completed = registration(1, "曾未到场玩家").copy(
            noShowCount = 2,
            lastNoShowActionWasDefer = true
        )
        val next = registration(2, "下一位")
        val before = MachineQueue(
            playing = listOf(completed),
            waiting = listOf(next),
            playingStartedAtMillis = 500L
        )

        val log = queueLog(before, before.finishRound(atMillis = 900L))!!

        assertEquals("机台 A · 游玩位置已更新", log.title)
        assertEquals(PublicQueueEventType.PLAYING_CHANGED, log.publicEventType)
        assertTrue(log.detail.contains("“曾未到场玩家”正常完成游玩，未到场记录已清除"))
        assertTrue(1 in log.affectedRegistrationKeys)
    }

    @Test
    fun operationSourceIsKeptOnQueueAndPlayerProfileLogs() {
        val queueLog = createQueueAuditLog(
            category = AuditLogCategory.MACHINE_A,
            machineLabel = "机台 A",
            before = MachineQueue(),
            after = MachineQueue(waiting = listOf(registration(1))),
            source = AuditLogSource.QQ_BOT,
            timestampMillis = 1_000L
        )
        val profile = PlayerProfile(
            id = "profile-1",
            nickname = "玩家",
            gender = PlayerGender.UNDISCLOSED,
            defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
            createdAtMillis = 100L,
            updatedAtMillis = 100L
        )

        assertEquals(AuditLogSource.QQ_BOT, queueLog?.source)
        assertEquals(
            AuditLogSource.SYSTEM_AUTOMATIC,
            createPlayerProfileAuditLog(
                before = null,
                after = profile,
                source = AuditLogSource.SYSTEM_AUTOMATIC,
                timestampMillis = 1_000L
            ).source
        )
    }

    @Test
    fun machineTransferCreatesOnePublicEventWithAllConsequences() {
        val transferred = registration(1, "小雨").copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
            fixedPartnerKey = 2
        )
        val releasedPartner = registration(2, "青空").copy(fixedPartnerKey = 1)

        val log = requireNotNull(
            createMachineTransferAuditLog(
                category = AuditLogCategory.MACHINE_A,
                sourceMachineLabel = "左侧 · 机台 A",
                destinationMachineLabel = "右侧 · 机台 B",
                registrations = listOf(transferred),
                releasedPartnerRegistrations = listOf(releasedPartner)
            )
        )

        assertEquals("登记已转至右侧 · 机台 B", log.title)
        assertEquals(PublicQueueEventType.REGISTRATION_UPDATED, log.publicEventType)
        assertEquals(listOf(1, 2), log.affectedRegistrationKeys)
        assertTrue(log.detail.contains("从左侧 · 机台 A 转至右侧 · 机台 B"))
        assertTrue(log.detail.contains("暂缓一轮状态已解除"))
        assertTrue(log.detail.contains("原固定组合已解除"))
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
        assertEquals("机台 A · 暂时离开已达轮空上限", removedLog.title)
    }

    @Test
    fun claimingARegistrationTakesPriorityOverTheAccompanyingNicknameChange() {
        val temporary = registration(1, "临时昵称").copy(isTemporary = true)
        val claimed = temporary.copy(
            displayId = "资料昵称",
            isTemporary = false,
            playerProfileId = "profile-1"
        )

        val log = queueLog(
            before = MachineQueue(waiting = listOf(temporary)),
            after = MachineQueue(waiting = listOf(claimed))
        )!!

        assertEquals("机台 A · 登记已认领", log.title)
        assertTrue(log.detail.contains("“临时昵称”更名为“资料昵称”"))
        assertTrue(log.detail.contains("“资料昵称”已认领登记"))
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
        assertTrue(editedLog.detail.contains("QQ 号已更新"))
        assertTrue(!editedLog.detail.contains("12345678"))
    }
}
