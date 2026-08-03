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
        assertTrue(log.detail.contains("“新昵称”已暂缓一次"))
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

        assertEquals("机台 A · 未到场 · 已暂缓一次", deferredLog.title)
        assertEquals("机台 A · 未到场 · 已移至队尾", movedLog.title)
        assertEquals("机台 A · 未到场 · 已移除登记", removedLog.title)
        assertTrue(removedLog.detail.contains("“未到场玩家”本次未到场，登记已移除"))
    }

    @Test
    fun completedRoundSeparatesClearedNoShowRecordFromPlayingNotification() {
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

        val logs = createQueueAuditLogs(
            category = AuditLogCategory.MACHINE_A,
            machineLabel = "机台 A",
            before = before,
            after = before.finishRound(atMillis = 900L),
            classifyAvailabilityOutcomes = true,
            semanticAction = QueueAction.FinishRound("A")
        )
        val playingLog = logs.single {
            it.publicEventType == PublicQueueEventType.PLAYING_CHANGED
        }
        val clearedLog = logs.single {
            it.title.endsWith("未到场记录已清除")
        }

        assertEquals(
            setOf(PublicQueueNotificationCategory.PLAYING_POSITION),
            playingLog.notificationCategories
        )
        assertFalse(playingLog.detail.contains("未到场记录已清除"))
        assertFalse(playingLog.detail.contains("登记顺序已调整为"))
        assertEquals(
            setOf(PublicQueueNotificationCategory.ABSENCE),
            clearedLog.notificationCategories
        )
        assertEquals(listOf(completed.key), clearedLog.affectedRegistrationKeys)
        assertTrue(clearedLog.detail.contains("“曾未到场玩家”正常完成本次游玩"))
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
        val releasedPartner = registration(2, "青空").copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
            fixedPartnerKey = 1
        )

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
        assertTrue(log.detail.contains("转入登记不再暂缓"))
        assertTrue(log.detail.contains("留在原机台的登记仍会暂缓一次"))
        assertTrue(log.detail.contains("原固定组合已解除"))

        val temporarilyAwayLog = requireNotNull(
            createMachineTransferAuditLog(
                category = AuditLogCategory.MACHINE_A,
                sourceMachineLabel = "左侧 · 机台 A",
                destinationMachineLabel = "右侧 · 机台 B",
                registrations = listOf(
                    transferred.copy(
                        absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                        temporaryAwaySkippedTurns = 2
                    )
                )
            )
        )
        assertTrue(temporarilyAwayLog.detail.contains("暂时离开状态和已轮空 2 次会在转入后保留"))
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
    fun manualExitAtTheTemporaryAwayLimitIsNotReportedAsAutomaticExpiry() {
        val away = registration(1, "主动退出玩家").copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 3
        )
        val log = queueLog(
            before = MachineQueue(waiting = listOf(away)),
            after = MachineQueue(),
            publicEventTypeOverride = PublicQueueEventType.REGISTRATION_REMOVED
        )!!

        assertEquals(PublicQueueEventType.REGISTRATION_REMOVED, log.publicEventType)
        assertEquals("机台 A · 移除登记", log.title)
        assertTrue(log.detail.contains("移除 “主动退出玩家”"))
        assertFalse(log.detail.contains("第四次轮到"))
        assertFalse(log.detail.contains("自动退出"))
    }

    @Test
    fun onlineCheckInRemovalLogsDistinguishTimeoutFromMissedOpportunity() {
        val pending = registration(1, "线上玩家").copy(requiresOnSiteCheckIn = true)
        val currentPlayer = registration(2, "本轮玩家", PlayPreference.SOLO)
        val timeoutLog = queueLog(
            before = MachineQueue(waiting = listOf(pending)),
            after = MachineQueue(),
            publicEventTypeOverride = PublicQueueEventType.ONLINE_CHECK_IN_TIMED_OUT
        )!!
        val missedLog = queueLog(
            before = MachineQueue(
                playing = listOf(currentPlayer),
                waiting = listOf(pending)
            ),
            after = MachineQueue(),
            publicEventTypeOverride = PublicQueueEventType.ONLINE_CHECK_IN_MISSED
        )!!

        assertEquals("机台 A · 线上登记签到超时", timeoutLog.title)
        assertTrue(timeoutLog.detail.contains("本次 30 分钟签到时限内完成现场签到"))
        assertFalse(timeoutLog.detail.contains("创建线上登记后"))
        assertEquals("机台 A · 未签到登记已自动移除", missedLog.title)
        assertTrue(missedLog.detail.contains("“线上玩家”轮到进入游玩位置时"))
        assertTrue(missedLog.detail.contains("移除 “本轮玩家”"))
        assertFalse(missedLog.detail.contains("“本轮玩家”轮到进入游玩位置时"))
        assertEquals(
            setOf(
                PublicQueueNotificationCategory.ONLINE_CHECK_IN,
                PublicQueueNotificationCategory.PLAYING_POSITION,
                PublicQueueNotificationCategory.QUEUE_CHANGES
            ),
            missedLog.notificationCategories
        )
    }

    @Test
    fun missedOnlineCheckInAndTemporaryAwayExpiryAreBothExplained() {
        val pending = registration(1, "线上玩家").copy(requiresOnSiteCheckIn = true)
        val away = registration(2, "暂离玩家").copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 3
        )
        val next = registration(3, "下一位", PlayPreference.SOLO)
        val before = MachineQueue(waiting = listOf(pending, away, next))
        val after = MachineQueue(playing = listOf(next), playingStartedAtMillis = 900L)

        val log = queueLog(
            before = before,
            after = after,
            publicEventTypeOverride = PublicQueueEventType.ONLINE_CHECK_IN_MISSED
        )!!

        assertTrue(log.detail.contains("“线上玩家”轮到进入游玩位置时尚未完成现场签到"))
        assertTrue(log.detail.contains("“暂离玩家”在暂时离开期间第四次轮到"))
        assertFalse(log.detail.contains("移除 “暂离玩家”"))
        assertEquals(
            setOf(
                PublicQueueNotificationCategory.ONLINE_CHECK_IN,
                PublicQueueNotificationCategory.PLAYING_POSITION,
                PublicQueueNotificationCategory.ABSENCE,
                PublicQueueNotificationCategory.QUEUE_CHANGES
            ),
            log.notificationCategories
        )
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
    fun fixedPairChangesNameTheRelationshipAndEachResultingPreference() {
        val first = registration(1, "小雨")
        val second = registration(2, "青空")
        val paired = MachineQueue(waiting = listOf(first, second)).let { queue ->
            queue.applyFriendPair(requireNotNull(queue.planFriendPair(first.key, second.key)))
        }
        val released = paired.changePreference(first.key, PlayPreference.SOLO)

        val establishedLog = requireNotNull(queueLog(MachineQueue(waiting = listOf(first, second)), paired))
        val releasedLog = requireNotNull(queueLog(paired, released))

        assertTrue(establishedLog.detail.contains("“小雨”已与“青空”建立固定组合"))
        assertTrue(releasedLog.detail.contains("“小雨”的固定组合已解除，当前游玩偏好为单人游玩"))
        assertTrue(releasedLog.detail.contains("“青空”的固定组合已解除，当前游玩偏好为允许他人加入"))
        assertFalse(releasedLog.detail.contains("关系已变更"))
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

    @Test
    fun mixedAutomaticOutcomesUseSeparateTitlesAndRecipients() {
        val current = registration(9, "本轮玩家").copy(preference = PlayPreference.SOLO)
        val pending = registration(1, "未签到玩家").copy(
            preference = PlayPreference.SOLO,
            requiresOnSiteCheckIn = true
        )
        val away = registration(2, "暂离玩家").copy(
            preference = PlayPreference.SOLO,
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 3
        )
        val deferred = registration(3, "暂缓玩家").copy(
            preference = PlayPreference.SOLO,
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val next = registration(4, "下一位玩家").copy(preference = PlayPreference.SOLO)
        val before = MachineQueue(
            playing = listOf(current),
            waiting = listOf(pending, away, deferred, next),
            playingStartedAtMillis = 100L
        )
        val after = RoundPlanner.finishRound(before).execute(5_000L)

        val logs = createQueueAuditLogs(
            category = AuditLogCategory.MACHINE_A,
            machineLabel = "机台 A",
            before = before,
            after = after,
            classifyAvailabilityOutcomes = true,
            semanticAction = QueueAction.FinishRound("A")
        )

        val pendingLog = logs.single { it.publicEventType == PublicQueueEventType.ONLINE_CHECK_IN_MISSED }
        val awayLog = logs.single { it.publicEventType == PublicQueueEventType.TEMPORARY_AWAY_EXPIRED }
        val deferredLog = logs.single {
            it.title.endsWith("暂缓一次已完成")
        }
        assertEquals(listOf(pending.key), pendingLog.affectedRegistrationKeys)
        assertEquals(listOf(away.key), awayLog.affectedRegistrationKeys)
        assertEquals(listOf(deferred.key), deferredLog.affectedRegistrationKeys)
        assertFalse(awayLog.title.contains("未签到"))
        assertFalse(pendingLog.title.contains("暂时离开"))
    }

    @Test
    fun immediatelyConsumedDeferStillProducesAnActionLog() {
        val first = registration(1, "小雨")
        val second = registration(2, "青空")
        val before = MachineQueue(
            playing = listOf(first, second),
            playingStartedAtMillis = 100L
        )
        val action = QueueAction.DeferOneRound("A", first.key)
        val after = (QueueEngine.execute(
            QueueEngineState.single("A", before),
            action,
            QueueActionContext(atMillis = 6_000L)
        ) as QueueActionExecution.Applied).state.queue("A")!!

        val logs = createQueueAuditLogs(
            category = AuditLogCategory.MACHINE_A,
            machineLabel = "机台 A",
            before = before,
            after = after,
            classifyAvailabilityOutcomes = true,
            semanticAction = action
        )
        val deferLog = logs.single { it.title.endsWith("暂缓一次已执行") }

        assertEquals(listOf(first.key), deferLog.affectedRegistrationKeys)
        assertTrue(deferLog.detail.contains("已跳过当前游玩机会"))
        assertTrue(deferLog.detail.contains("暂缓状态已随即解除"))
    }

    @Test
    fun immediatelyConsumedNoShowDeferUsesCompletedOutcomeTitle() {
        val absent = registration(1, "未到场玩家", PlayPreference.SOLO)
        val next = registration(2, "下一位玩家", PlayPreference.SOLO)
        val before = MachineQueue(
            playing = listOf(absent),
            waiting = listOf(next),
            playingStartedAtMillis = 100L
        )
        val action = QueueAction.MarkNoShow(
            machineId = "A",
            registrationKeys = setOf(absent.key),
            resolution = NoShowResolution.DEFER_ONE_ROUND
        )
        val after = (QueueEngine.execute(
            QueueEngineState.single("A", before),
            action,
            QueueActionContext(atMillis = 6_500L)
        ) as QueueActionExecution.Applied).state.queue("A")!!

        val noShowLog = createQueueAuditLogs(
            category = AuditLogCategory.MACHINE_A,
            machineLabel = "机台 A",
            before = before,
            after = after,
            publicEventTypeOverride = PublicQueueEventType.NO_SHOW_DEFERRED,
            affectedRegistrationKeysOverride = setOf(absent.key),
            classifyAvailabilityOutcomes = true,
            semanticAction = action
        ).single { it.publicEventType == PublicQueueEventType.NO_SHOW_DEFERRED }

        assertEquals("机台 A · 未到场 · 本次机会已跳过", noShowLog.title)
        assertTrue(noShowLog.detail.contains("本次机会已跳过"))
        assertTrue(noShowLog.detail.contains("暂缓状态已自动解除"))
    }

    @Test
    fun undoRestoreDescribesRestoredRegistrationsInsteadOfNewOnes() {
        val restored = registration(1, "小雨").copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 3
        )
        val log = createQueueAuditLog(
            category = AuditLogCategory.MACHINE_A,
            machineLabel = "机台 A",
            before = MachineQueue(),
            after = MachineQueue(waiting = listOf(restored)),
            titleOverride = "撤销：本轮已结束",
            publicEventTypeOverride = PublicQueueEventType.QUEUE_RESTORED
        )!!

        assertTrue(log.detail.contains("撤销操作后恢复"))
        assertFalse(log.detail.contains("新增"))
    }

    @Test
    fun noShowTailLogOnlyMentionsAutomaticArrangementWhenItActuallyRuns() {
        val absent = registration(1, "小雨", PlayPreference.SOLO)
        val next = registration(2, "青空", PlayPreference.SOLO)
        val before = MachineQueue(playing = listOf(absent), waiting = listOf(next))
        val after = before.markNoShowMoveToEnd(
            registrationKeys = setOf(absent.key),
            startNextWhenPlayingBecomesEmpty = false
        )

        val manualLog = createQueueAuditLogs(
            category = AuditLogCategory.MACHINE_A,
            machineLabel = "机台 A",
            before = before,
            after = after,
            publicEventTypeOverride = PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL,
            affectedRegistrationKeysOverride = setOf(absent.key),
            classifyAvailabilityOutcomes = true,
            semanticAction = QueueAction.MarkNoShow(
                machineId = "A",
                registrationKeys = setOf(absent.key),
                resolution = NoShowResolution.MOVE_TO_TAIL,
                startNextWhenPlayingBecomesEmpty = false
            )
        ).single()

        assertTrue(manualLog.detail.contains("等待工作人员安排"))
        assertFalse(manualLog.detail.contains("本次自动安排"))
    }

    @Test
    fun noShowTargetAndAutomaticallyRemovedPendingRegistrationUseSeparateEvents() {
        val absent = registration(1, "未到场玩家", PlayPreference.SOLO)
        val pending = registration(2, "未签到玩家", PlayPreference.SOLO).copy(
            requiresOnSiteCheckIn = true
        )
        val next = registration(3, "下一位玩家", PlayPreference.SOLO)
        val before = MachineQueue(waiting = listOf(absent, pending, next))
        val action = QueueAction.MarkNoShow(
            machineId = "A",
            registrationKeys = setOf(absent.key),
            resolution = NoShowResolution.MOVE_TO_TAIL,
            startNextWhenPlayingBecomesEmpty = true
        )
        val after = (QueueEngine.execute(
            QueueEngineState.single("A", before),
            action,
            QueueActionContext(atMillis = 7_000L)
        ) as QueueActionExecution.Applied).state.queue("A")!!

        val logs = createQueueAuditLogs(
            category = AuditLogCategory.MACHINE_A,
            machineLabel = "机台 A",
            before = before,
            after = after,
            publicEventTypeOverride = PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL,
            affectedRegistrationKeysOverride = setOf(absent.key, pending.key),
            classifyAvailabilityOutcomes = true,
            semanticAction = action
        )

        val noShowLog = logs.single {
            it.publicEventType == PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL
        }
        val pendingLog = logs.single {
            it.publicEventType == PublicQueueEventType.ONLINE_CHECK_IN_MISSED
        }
        assertEquals(listOf(absent.key), noShowLog.affectedRegistrationKeys)
        assertEquals(listOf(pending.key), pendingLog.affectedRegistrationKeys)
        assertTrue(noShowLog.detail.contains("未到场玩家"))
        assertFalse(noShowLog.detail.contains("未签到玩家"))
        assertTrue(pendingLog.detail.contains("未签到玩家"))
        assertFalse(pendingLog.detail.contains("未到场玩家"))
        assertEquals(listOf(next.key), after.playing.map(Registration::key))
    }
}
