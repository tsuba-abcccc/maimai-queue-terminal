package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteQueueOperationsTest {
    @Test
    fun onlineJoinCreatesPendingRegistrationAtWaitingTail() {
        val existing = registration(1, "现场玩家")
        val state = state(
            machineA = MachineQueue(playing = listOf(existing), playingStartedAtMillis = 1_000L),
            nextKey = 2
        )

        val result = decideRemoteQueueOperation(joinCommand(), state, appliedAtMillis = 5_000L)

        assertTrue(result is RemoteQueueOperationDecision.Apply)
        result as RemoteQueueOperationDecision.Apply
        val joined = result.state.queues.getValue("A").waiting.single()
        assertEquals("资料玩家", joined.displayId)
        assertTrue(joined.requiresOnSiteCheckIn)
        assertEquals(5_000L, joined.createdAtMillis)
        assertEquals(
            5_000L + ONLINE_REGISTRATION_CHECK_IN_TIMEOUT_MILLIS,
            joined.onSiteCheckInDeadlineMillis
        )
        assertEquals(joinCommand().commandId, joined.originatingCommandId)
        assertEquals(3, result.state.nextRegistrationKey)
        assertEquals(1, result.updatedProfile?.usageCount)
    }

    @Test
    fun onlineJoinDoesNotAdvanceWaitingPlayersWhenPlayingPositionWasLeftEmpty() {
        val waiting = registration(1, "现场玩家")
        val result = decideRemoteQueueOperation(
            joinCommand(),
            state(machineA = MachineQueue(waiting = listOf(waiting)), nextKey = 2)
        ) as RemoteQueueOperationDecision.Apply

        val queue = result.state.queues.getValue("A")
        assertTrue(queue.playing.isEmpty())
        assertEquals(listOf(1, 2), queue.waiting.map { it.key })
        assertTrue(queue.waiting.last().requiresOnSiteCheckIn)
    }

    @Test
    fun repeatedJoinCommandIsRecognizedWithoutAddingAgain() {
        val command = joinCommand()
        val pending = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id,
            requiresOnSiteCheckIn = true,
            originatingCommandId = command.commandId
        )

        val result = decideRemoteQueueOperation(
            command,
            state(machineA = MachineQueue(waiting = listOf(pending)), nextKey = 3).copy(
                allowOnlineRegistration = false
            )
        )

        assertTrue(result is RemoteQueueOperationDecision.AlreadyApplied)
    }

    @Test
    fun repeatedJoinRepairsUsageAfterQueueWasSavedBeforeTheProfile() {
        val first = decideRemoteQueueOperation(
            joinCommand(),
            state(),
            appliedAtMillis = 5_000L
        ) as RemoteQueueOperationDecision.Apply

        val replay = decideRemoteQueueOperation(joinCommand(), first.state)
            as RemoteQueueOperationDecision.AlreadyApplied

        assertEquals(1, replay.updatedProfile?.usageCount)
        assertEquals(5_000L, replay.updatedProfile?.lastUsedAtMillis)
        assertEquals(1, first.state.queues.getValue("A").registrationCount)
    }

    @Test
    fun appliedJoinReceiptPreventsRecreatingARegistrationThatAlreadyLeft() {
        val command = joinCommand()

        val result = decideRemoteQueueOperation(
            command = command,
            state = state(machineA = MachineQueue(), nextKey = 3),
            appliedRegistrationCommandIds = setOf(command.commandId)
        )

        assertTrue(result is RemoteQueueOperationDecision.AlreadyApplied)
        assertTrue(
            (result as RemoteQueueOperationDecision.AlreadyApplied).detail
                .contains("当前已不在队列中")
        )
    }

    @Test
    fun pendingRegistrationCanOnlyLeaveRemotely() {
        val pending = pendingRegistration()
        val current = state(machineA = MachineQueue(waiting = listOf(pending)), nextKey = 3)
        val defer = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.DEFER_ONE_ROUND, pending),
            current
        )
        val leave = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.LEAVE_QUEUE, pending),
            current
        )

        assertTrue(defer is RemoteQueueOperationDecision.Reject)
        assertTrue(leave is RemoteQueueOperationDecision.Apply)
        assertEquals(0, (leave as RemoteQueueOperationDecision.Apply)
            .state.queues.getValue("A").registrationCount)
    }

    @Test
    fun transferUsesExplicitTargetAndIsIdempotentAfterLostAcknowledgement() {
        val registration = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val command = operationCommand(
            RemoteQueueOperation.TRANSFER_MACHINE,
            registration,
            targetMachineId = "B"
        )
        val applied = decideRemoteQueueOperation(
            command,
            state(machineA = MachineQueue(waiting = listOf(registration)), nextKey = 3)
        ) as RemoteQueueOperationDecision.Apply
        val repeated = decideRemoteQueueOperation(command, applied.state)

        assertEquals(0, applied.state.queues.getValue("A").registrationCount)
        assertEquals(1, applied.state.queues.getValue("B").registrationCount)
        assertTrue(repeated is RemoteQueueOperationDecision.AlreadyApplied)
    }

    @Test
    fun machinesCAndDSupportJoinTransferCapacityStatusAndDuplicateChecks() {
        val emptyQueues = linkedMapOf(
            "A" to MachineQueue(),
            "B" to MachineQueue(),
            "C" to MachineQueue(),
            "D" to MachineQueue()
        )
        val joined = decideRemoteQueueOperation(
            joinCommand().copy(machineId = "C"),
            stateWithQueues(emptyQueues),
            appliedAtMillis = 5_000L
        ) as RemoteQueueOperationDecision.Apply
        assertTrue(joined.state.queues.getValue("C").waiting.single().requiresOnSiteCheckIn)
        assertTrue(joined.changedMachineIds == setOf("C"))

        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val source = stateWithQueues(
            emptyQueues + ("C" to MachineQueue(waiting = listOf(player))),
            nextKey = 3
        )
        val transferred = decideRemoteQueueOperation(
            operationCommand(
                RemoteQueueOperation.TRANSFER_MACHINE,
                player,
                targetMachineId = "D"
            ).copy(machineId = "C"),
            source
        ) as RemoteQueueOperationDecision.Apply
        assertTrue(transferred.state.queues.getValue("C").allRegistrations.isEmpty())
        assertEquals(listOf(player.key), transferred.state.queues.getValue("D").waiting.map { it.key })
        assertEquals(setOf("C", "D"), transferred.changedMachineIds)

        val stoppedStatuses = emptyQueues.keys.associateWith { machineId ->
            if (machineId == "D") {
                MachineStatus().stop(MachineStopReason.MAINTENANCE, 4_000L)
            } else {
                MachineStatus()
            }
        }
        val stoppedTransfer = decideRemoteQueueOperation(
            operationCommand(
                RemoteQueueOperation.TRANSFER_MACHINE,
                player,
                targetMachineId = "D"
            ).copy(machineId = "C"),
            source.copy(machineStatuses = stoppedStatuses)
        )
        assertTrue(stoppedTransfer is RemoteQueueOperationDecision.Reject)

        val duplicateJoin = decideRemoteQueueOperation(
            joinCommand().copy(machineId = "A"),
            transferred.state
        )
        assertTrue(duplicateJoin is RemoteQueueOperationDecision.Reject)

        val unknownMachine = decideRemoteQueueOperation(
            joinCommand().copy(machineId = "E"),
            stateWithQueues(emptyQueues)
        )
        assertTrue(unknownMachine is RemoteQueueOperationDecision.Reject)
    }

    @Test
    fun persistedReceiptPreventsAQueueOperationFromBeingReappliedAfterStateChanges() {
        val registration = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val command = operationCommand(
            RemoteQueueOperation.TRANSFER_MACHINE,
            registration,
            targetMachineId = "B"
        )
        val changedSinceFirstExecution = state(
            machineA = MachineQueue(),
            machineB = MachineQueue(),
            nextKey = 3
        ).copy(
            playerProfiles = emptyList(),
            oneBotSyncEnabled = false
        )

        val repeated = decideRemoteQueueOperation(
            command = command,
            state = changedSinceFirstExecution,
            appliedRegistrationCommandIds = setOf(command.commandId)
        )

        assertTrue(repeated is RemoteQueueOperationDecision.AlreadyApplied)
        assertTrue(
            (repeated as RemoteQueueOperationDecision.AlreadyApplied).detail
                .contains("不会重复处理")
        )
    }

    @Test
    fun transferAndExitExplainEveryFixedPairSideEffect() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val partner = registration(3, "固定搭档")
        val pairedQueue = MachineQueue(waiting = listOf(player, partner)).let { queue ->
            queue.applyFriendPair(requireNotNull(queue.planFriendPair(player.key, partner.key)))
        }.deferOneRound(player.key)
        val pairedPlayer = pairedQueue.waiting.first { it.key == player.key }
        val transferred = decideRemoteQueueOperation(
            operationCommand(
                RemoteQueueOperation.TRANSFER_MACHINE,
                pairedPlayer,
                targetMachineId = "B"
            ),
            state(machineA = pairedQueue, nextKey = 4)
        ) as RemoteQueueOperationDecision.Apply

        assertTrue(transferred.detail.contains("转入登记不再暂缓"))
        assertTrue(transferred.detail.contains("留在原机台的登记仍会暂缓一次"))
        assertTrue(transferred.detail.contains("固定组合已解除"))
        assertEquals(
            QueueAbsenceStatus.NONE,
            transferred.state.queues.getValue("B").waiting.single().absenceStatus
        )
        assertEquals(
            PlayPreference.OPEN_TO_JOIN,
            transferred.state.queues.getValue("B").waiting.single().preference
        )
        assertEquals(
            PlayPreference.OPEN_TO_JOIN,
            transferred.state.queues.getValue("A").waiting.single().preference
        )
        assertEquals(
            QueueAbsenceStatus.DEFER_ONE_ROUND,
            transferred.state.queues.getValue("A").waiting.single().absenceStatus
        )

        val playingQueue = pairedQueue.copy(
            playing = pairedQueue.waiting,
            waiting = emptyList(),
            playingStartedAtMillis = 1_000L
        )
        val playingPlayer = playingQueue.playing.first { it.key == player.key }
        val exited = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.LEAVE_QUEUE, playingPlayer),
            state(machineA = playingQueue, nextKey = 4)
        ) as RemoteQueueOperationDecision.Apply

        assertTrue(exited.detail.contains("对方保留原位"))
        assertTrue(exited.detail.contains("对方仍保持暂缓一次"))
        assertTrue(exited.detail.contains("游玩位置中的空缺不会自动"))
    }

    @Test
    fun transferAndPreferenceMessagesKeepTemporaryAwayAndSelectedPreferenceExplicit() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val partner = registration(3, "固定搭档")
        val pairedAwayQueue = MachineQueue(waiting = listOf(player, partner)).let { queue ->
            queue.applyFriendPair(requireNotNull(queue.planFriendPair(player.key, partner.key)))
        }.temporarilyLeave(player.key)
        val pairedPlayer = pairedAwayQueue.waiting.first { it.key == player.key }

        val transferred = decideRemoteQueueOperation(
            operationCommand(
                RemoteQueueOperation.TRANSFER_MACHINE,
                pairedPlayer,
                targetMachineId = "B"
            ),
            state(machineA = pairedAwayQueue, nextKey = 4)
        ) as RemoteQueueOperationDecision.Apply
        val transferredRegistration = transferred.state.queues.getValue("B").waiting.single()

        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, transferredRegistration.absenceStatus)
        assertEquals(0, transferredRegistration.temporaryAwaySkippedTurns)
        assertTrue(transferred.detail.contains("两份登记的暂时离开状态"))
        assertTrue(transferred.detail.contains("返回后仍需手动取消"))

        val changedPreference = decideRemoteQueueOperation(
            operationCommand(
                RemoteQueueOperation.CHANGE_PLAY_PREFERENCE,
                pairedPlayer,
                preference = PlayPreference.SOLO
            ),
            state(machineA = pairedAwayQueue, nextKey = 4)
        ) as RemoteQueueOperationDecision.Apply

        assertTrue(changedPreference.detail.contains("本次游玩偏好已改为“单人游玩”"))
        assertTrue(changedPreference.detail.contains("玩家资料中的默认偏好没有改变"))
        assertTrue(changedPreference.detail.contains("暂时离开状态"))
        assertTrue(changedPreference.state.queues.getValue("A").waiting.all {
            it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
        })
    }

    @Test
    fun joinRevalidatesCurrentCapacityAndFeatureSwitches() {
        val full = MachineQueue(waiting = (1..20).map { registration(it, "玩家$it") })
        val capacity = decideRemoteQueueOperation(joinCommand(), state(machineA = full, nextKey = 21))
        val closed = decideRemoteQueueOperation(
            joinCommand(),
            state().copy(acceptingNewRegistrations = false)
        )
        val reopened = decideRemoteQueueOperation(
            joinCommand(),
            state().copy(acceptingNewRegistrations = true)
        )
        val disabled = decideRemoteQueueOperation(
            joinCommand(),
            state().copy(websiteRemoteEnabled = false)
        )
        val onlineRegistrationDisabled = decideRemoteQueueOperation(
            joinCommand(),
            state().copy(allowOnlineRegistration = false)
        )

        assertTrue(capacity is RemoteQueueOperationDecision.Reject)
        assertEquals(
            "现场当前没有使用登记排队，暂不能线上加入排队。",
            (closed as RemoteQueueOperationDecision.Reject).detail
        )
        assertTrue(reopened is RemoteQueueOperationDecision.Apply)
        assertTrue(disabled is RemoteQueueOperationDecision.Reject)
        assertEquals(
            "现场规则暂不允许线上登记。",
            (onlineRegistrationDisabled as RemoteQueueOperationDecision.Reject).detail
        )
    }

    @Test
    fun checkInOnlyChangesEligibilityAndDoesNotMoveRegistration() {
        val pending = pendingRegistration()
        val queue = MachineQueue(waiting = listOf(pending))

        val checkedIn = queue.checkIn(pending.key)

        assertEquals(listOf(pending.key), checkedIn.waiting.map { it.key })
        assertTrue(checkedIn.playing.isEmpty())
        assertFalse(checkedIn.waiting.single().requiresOnSiteCheckIn)
    }

    @Test
    fun fixedPairAbsenceOperationsAlwaysApplyToBothRegistrations() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val partner = registration(3, "固定搭档")
        val pairedQueue = MachineQueue(waiting = listOf(player, partner)).let { queue ->
            queue.applyFriendPair(requireNotNull(queue.planFriendPair(player.key, partner.key)))
        }
        var current = state(machineA = pairedQueue, nextKey = 4)

        val deferred = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.DEFER_ONE_ROUND, player),
            current
        ) as RemoteQueueOperationDecision.Apply
        assertEquals(
            setOf(QueueAbsenceStatus.DEFER_ONE_ROUND),
            deferred.state.queues.getValue("A").waiting.map { it.absenceStatus }.toSet()
        )
        assertEquals("固定组合的两份登记已同时暂缓一次。", deferred.detail)
        current = deferred.state

        val deferCancelled = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND, player),
            current
        ) as RemoteQueueOperationDecision.Apply
        assertEquals(
            setOf(QueueAbsenceStatus.NONE),
            deferCancelled.state.queues.getValue("A").waiting.map { it.absenceStatus }.toSet()
        )
        assertEquals("固定组合的两份登记已同时取消暂缓一次。", deferCancelled.detail)
        current = deferCancelled.state

        val temporarilyAway = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.TEMPORARILY_LEAVE, player),
            current
        ) as RemoteQueueOperationDecision.Apply
        val awayRegistrations = temporarilyAway.state.queues.getValue("A").waiting
        assertEquals(
            setOf(QueueAbsenceStatus.TEMPORARILY_AWAY),
            awayRegistrations.map { it.absenceStatus }.toSet()
        )
        assertEquals(setOf(0), awayRegistrations.map { it.temporaryAwaySkippedTurns }.toSet())
        assertEquals("固定组合的两份登记已同时设为暂时离开。", temporarilyAway.detail)
        current = temporarilyAway.state

        val leaveCancelled = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE, player),
            current
        ) as RemoteQueueOperationDecision.Apply
        val restoredRegistrations = leaveCancelled.state.queues.getValue("A").waiting
        assertEquals(
            setOf(QueueAbsenceStatus.NONE),
            restoredRegistrations.map { it.absenceStatus }.toSet()
        )
        assertEquals(setOf(0), restoredRegistrations.map { it.temporaryAwaySkippedTurns }.toSet())
        assertEquals("固定组合的两份登记已同时取消暂时离开。", leaveCancelled.detail)
    }

    @Test
    fun repeatedFixedPairAbsenceOperationReportsTheWholeGroup() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val partner = registration(3, "固定搭档")
        val pairedQueue = MachineQueue(waiting = listOf(player, partner)).let { queue ->
            queue.applyFriendPair(requireNotNull(queue.planFriendPair(player.key, partner.key)))
        }
        val current = state(machineA = pairedQueue, nextKey = 4)

        val result = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE, player),
            current
        ) as RemoteQueueOperationDecision.AlreadyApplied

        assertEquals("固定组合的两份登记已经取消暂时离开。", result.detail)
    }

    @Test
    fun stoppedMachineRejectsRemoteChangesButKeepsIdempotentResults() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id,
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val stopped = state(machineA = MachineQueue(waiting = listOf(player)), nextKey = 3)
            .copy(
                machineStatuses = mapOf(
                    "A" to MachineStatus().stop(MachineStopReason.MAINTENANCE, 1_000L),
                    "B" to MachineStatus()
                )
            )

        val repeated = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.DEFER_ONE_ROUND, player),
            stopped
        )
        val leave = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.LEAVE_QUEUE, player),
            stopped
        )
        assertTrue(repeated is RemoteQueueOperationDecision.AlreadyApplied)
        assertEquals(
            "机台 A 已停止使用，恢复正常使用后才能操作这份登记。",
            (leave as RemoteQueueOperationDecision.Reject).detail
        )
    }

    @Test
    fun remoteMutationsProduceExactlyTheSameQueueAsDirectEngineActions() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val scenarios = listOf(
            RemoteQueueOperation.DEFER_ONE_ROUND to
                QueueAction.DeferOneRound("A", player.key),
            RemoteQueueOperation.TEMPORARILY_LEAVE to
                QueueAction.TemporarilyLeave("A", player.key),
            RemoteQueueOperation.CHANGE_PLAY_PREFERENCE to
                QueueAction.ChangePreference("A", player.key, PlayPreference.SOLO),
            RemoteQueueOperation.LEAVE_QUEUE to
                QueueAction.RemoveRegistrations("A", setOf(player.key))
        )

        scenarios.forEach { (operation, action) ->
            val initial = state(
                machineA = MachineQueue(waiting = listOf(player)),
                nextKey = 3
            )
            val command = operationCommand(
                operation = operation,
                registration = player,
                preference = PlayPreference.SOLO.takeIf {
                    operation == RemoteQueueOperation.CHANGE_PLAY_PREFERENCE
                }
            )
            val remote = decideRemoteQueueOperation(
                command = command,
                state = initial,
                appliedAtMillis = 8_000L
            ) as RemoteQueueOperationDecision.Apply
            val direct = initial.executeQueueAction(
                action = action,
                origin = QueueActionOrigin.QQ_BOT,
                atMillis = 8_000L
            ) as QueueActionExecution.Applied

            assertEquals(operation.name, direct.state.queues, remote.state.queues)
        }
    }

    @Test
    fun remoteCancellationActionsProduceExactlyTheDirectEngineState() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val scenarios = listOf(
            Triple(
                RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND,
                player.copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND),
                QueueAction.CancelDeferOneRound("A", player.key)
            ),
            Triple(
                RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE,
                player.copy(absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY),
                QueueAction.CancelTemporaryLeave("A", player.key)
            )
        )

        scenarios.forEach { (operation, registration, action) ->
            val initial = state(
                machineA = MachineQueue(waiting = listOf(registration)),
                nextKey = 3
            )
            val remote = decideRemoteQueueOperation(
                operationCommand(operation, registration),
                initial,
                appliedAtMillis = 8_000L
            ) as RemoteQueueOperationDecision.Apply
            val direct = initial.executeQueueAction(
                action = action,
                origin = QueueActionOrigin.QQ_BOT,
                atMillis = 8_000L
            ) as QueueActionExecution.Applied

            assertEquals(operation.name, direct.state.queues, remote.state.queues)
        }
    }

    @Test
    fun delayedRemoteMutationRejectsAChangedPositionPairOrAbsenceState() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val expected = operationCommand(RemoteQueueOperation.LEAVE_QUEUE, player).copy(
            expectedPosition = RemoteRegistrationPosition.WAITING,
            expectedAbsenceStatus = QueueAbsenceStatus.NONE,
            expectedTemporaryAwaySkippedTurns = 0,
            expectedPendingCheckIn = false
        )

        val movedToPlaying = decideRemoteQueueOperation(
            expected,
            state(machineA = MachineQueue(playing = listOf(player)), nextKey = 3)
        ) as RemoteQueueOperationDecision.Reject
        assertTrue(movedToPlaying.detail.contains("所在位置已经变化"))

        val partner = registration(3, "固定搭档")
        val pairedQueue = MachineQueue(waiting = listOf(player, partner)).let { queue ->
            queue.applyFriendPair(requireNotNull(queue.planFriendPair(player.key, partner.key)))
        }
        val paired = decideRemoteQueueOperation(
            expected,
            state(machineA = pairedQueue, nextKey = 4)
        ) as RemoteQueueOperationDecision.Reject
        assertTrue(paired.detail.contains("固定组合状态已经变化"))

        val deferred = decideRemoteQueueOperation(
            expected.copy(
                operation = RemoteQueueOperation.CHANGE_PLAY_PREFERENCE,
                preference = PlayPreference.SOLO
            ),
            state(
                machineA = MachineQueue(
                    waiting = listOf(player.copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND))
                ),
                nextKey = 3
            )
        ) as RemoteQueueOperationDecision.Reject
        assertTrue(deferred.detail.contains("登记状态已经变化"))
    }

    private fun state(
        machineA: MachineQueue = MachineQueue(),
        machineB: MachineQueue = MachineQueue(),
        nextKey: Int = 1
    ) = stateWithQueues(
        queues = linkedMapOf("A" to machineA, "B" to machineB),
        nextKey = nextKey
    )

    private fun stateWithQueues(
        queues: Map<String, MachineQueue>,
        nextKey: Int = 1
    ) = RemoteQueueExecutionState(
        queueId = QUEUE_ID,
        queues = queues,
        machineStatuses = queues.keys.associateWith { MachineStatus() },
        playerProfiles = listOf(profile()),
        nextRegistrationKey = nextKey,
        acceptingNewRegistrations = true,
        websiteRemoteEnabled = true,
        oneBotSyncEnabled = true,
        allowOnlineRegistration = true,
        allowDeferOneRound = true,
        allowTemporaryLeave = true
    )

    private fun profile() = PlayerProfile(
        id = PROFILE_ID,
        nickname = "资料玩家",
        gender = PlayerGender.UNDISCLOSED,
        defaultPreference = ProfilePlayPreference.OPEN_TO_JOIN,
        qqNumber = "12345678",
        createdAtMillis = 500L,
        updatedAtMillis = 500L
    )

    private fun registration(key: Int, name: String) = Registration(
        key = key,
        displayId = name,
        preference = PlayPreference.OPEN_TO_JOIN,
        createdAtMillis = 500L
    )

    private fun pendingRegistration() = registration(2, "资料玩家").copy(
        isTemporary = false,
        playerProfileId = PROFILE_ID,
        requiresOnSiteCheckIn = true,
        originatingCommandId = COMMAND_ID
    )

    private fun joinCommand() = RemoteQueueOperationCommand(
        commandId = COMMAND_ID,
        createdAtMillis = 2_000L,
        queueId = QUEUE_ID,
        profileId = PROFILE_ID,
        actorQq = "12345678",
        operation = RemoteQueueOperation.JOIN_QUEUE,
        source = RemoteQueueOperationSource.WEBSITE_REMOTE,
        machineId = "A",
        preference = PlayPreference.OPEN_TO_JOIN
    )

    private fun operationCommand(
        operation: RemoteQueueOperation,
        registration: Registration,
        targetMachineId: String? = null,
        preference: PlayPreference? = null
    ) = RemoteQueueOperationCommand(
        commandId = "00000000-0000-0000-0000-000000000499",
        createdAtMillis = 2_000L,
        queueId = QUEUE_ID,
        profileId = PROFILE_ID,
        actorQq = "12345678",
        operation = operation,
        source = RemoteQueueOperationSource.QQ_BOT,
        machineId = "A",
        targetMachineId = targetMachineId,
        preference = preference,
        registrationId = publicRegistrationId(QUEUE_ID, registration.key)
    )

    private companion object {
        const val QUEUE_ID = "00000000-0000-0000-0000-000000000001"
        const val PROFILE_ID = "00000000-0000-0000-0000-000000000901"
        const val COMMAND_ID = "00000000-0000-0000-0000-000000000401"
    }
}
