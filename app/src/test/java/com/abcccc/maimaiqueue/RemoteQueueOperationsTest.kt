package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteQueueOperationsTest {
    @Test
    fun managementQueueActionRejectsWhenTerminalPolicyRevisionChanged() {
        val current = state().copy(
            machineStableIds = mapOf(
                "A" to "10000000000000000000000000000001",
                "B" to "10000000000000000000000000000002"
            ),
            managementPolicyRevision = 4L
        )
        val command = ManagementQueueActionCommand(
            commandId = "00000000-0000-0000-0000-000000000490",
            createdAtMillis = 2_000L,
            queueId = QUEUE_ID,
            queueRevision = 1L,
            machineConfigurationRevision = 1L,
            expectedPolicyRevision = 3L,
            action = ManagementQueueAction.ADD_TEMPORARY_REGISTRATION,
            machineId = "A",
            machineStableId = "10000000000000000000000000000001",
            expectedPlayingRegistrationIds = emptyList(),
            expectedWaitingPositionRegistrationIds = emptyList(),
            displayId = "管理临时登记"
        )

        val result = decideManagementQueueAction(command, current)

        assertTrue(result is ManagementQueueActionDecision.Reject)
        assertEquals(
            "终端敏感策略已经更新，请刷新管理后台后再操作。",
            (result as ManagementQueueActionDecision.Reject).detail
        )
        assertTrue(current.queues.values.all { it.allRegistrations.isEmpty() })
    }

    @Test
    fun terminalRegistrationInputsAreNormalizedBeforeSinglePlayerCapacityValidation() {
        val action = QueueAction.AddRegistrations(
            machineId = "A",
            registrations = listOf(
                registration(1, "临时玩家"),
                registration(2, "资料玩家").copy(
                    fixedPartnerKey = 1,
                    preference = PlayPreference.OPEN_TO_JOIN
                )
            ),
            placement = RegistrationPlacement.WAITING_TAIL
        )

        val normalized = action.normalizedForMachineCapacities(mapOf("A" to 1))
            as QueueAction.AddRegistrations

        assertEquals(listOf(1, 2), normalized.registrations.map { it.key })
        assertTrue(normalized.registrations.all { it.preference == PlayPreference.SOLO })
        assertTrue(normalized.registrations.all { it.fixedPartnerKey == null })
    }

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
        assertTrue(joined.wasOnlineRegistration)
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
    fun staleMachineConfigurationRevisionRejectsRemoteCommandsBeforeApplyingThem() {
        val current = state().copy(machineConfigurationRevision = 8L)

        val result = decideRemoteQueueOperation(
            joinCommand().copy(machineConfigurationRevision = 7L),
            current
        )

        assertTrue(result is RemoteQueueOperationDecision.Reject)
        assertEquals(
            "机台配置已经更新，请重新查询后再操作。",
            (result as RemoteQueueOperationDecision.Reject).detail
        )
        assertTrue(current.queues.values.all { it.allRegistrations.isEmpty() })
    }

    @Test
    fun pendingTransferCannotTargetAReassignedMachineIdentifier() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val current = state(
            machineA = MachineQueue(waiting = listOf(player)),
            nextKey = 3
        ).copy(machineConfigurationRevision = 8L)
        val commandConfirmedBeforeMachineDeletion = operationCommand(
            RemoteQueueOperation.TRANSFER_MACHINE,
            player,
            targetMachineId = "B"
        ).copy(machineConfigurationRevision = 7L)

        val result = decideRemoteQueueOperation(
            commandConfirmedBeforeMachineDeletion,
            current
        )

        assertTrue(result is RemoteQueueOperationDecision.Reject)
        assertEquals(
            "机台配置已经更新，请重新查询后再操作。",
            (result as RemoteQueueOperationDecision.Reject).detail
        )
        assertEquals(listOf(player.key), current.queues.getValue("A").waiting.map { it.key })
        assertTrue(current.queues.getValue("B").allRegistrations.isEmpty())
    }

    @Test
    fun stableMachineIdentityRejectsAReassignedSourceOrTransferTarget() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val current = state(
            machineA = MachineQueue(waiting = listOf(player)),
            nextKey = 3
        ).copy(
            machineStableIds = mapOf(
                "A" to "20000000000000000000000000000001",
                "B" to "20000000000000000000000000000002"
            ),
            machineConfigurationRevision = 8L
        )
        val sourceChanged = operationCommand(
            RemoteQueueOperation.LEAVE_QUEUE,
            player
        ).copy(
            machineStableId = "10000000000000000000000000000001",
            machineConfigurationRevision = 8L
        )
        val targetChanged = operationCommand(
            RemoteQueueOperation.TRANSFER_MACHINE,
            player,
            targetMachineId = "B"
        ).copy(
            machineStableId = current.machineStableIds.getValue("A"),
            targetMachineStableId = "10000000000000000000000000000002",
            machineConfigurationRevision = 8L
        )

        val sourceResult = decideRemoteQueueOperation(sourceChanged, current)
        val targetResult = decideRemoteQueueOperation(targetChanged, current)

        assertEquals(
            "所选机台已经变化，请重新查询后再操作。",
            (sourceResult as RemoteQueueOperationDecision.Reject).detail
        )
        assertEquals(
            "要转入的机台已经变化，请重新查询后再操作。",
            (targetResult as RemoteQueueOperationDecision.Reject).detail
        )
        assertEquals(listOf(player.key), current.queues.getValue("A").waiting.map { it.key })
        assertTrue(current.queues.getValue("B").allRegistrations.isEmpty())
    }

    @Test
    fun onlineJoinToSinglePlayerMachineForcesSoloWithoutChangingProfileDefault() {
        val current = state().copy(
            machineCapacities = mapOf("A" to 1, "B" to 2),
            machineConfigurationRevision = 4L
        )

        val result = decideRemoteQueueOperation(
            joinCommand().copy(machineConfigurationRevision = 4L),
            current,
            appliedAtMillis = 5_000L
        ) as RemoteQueueOperationDecision.Apply

        val registration = result.state.queues.getValue("A").waiting.single()
        assertEquals(PlayPreference.SOLO, registration.preference)
        assertEquals(ProfilePlayPreference.OPEN_TO_JOIN, current.playerProfiles.single().defaultPreference)
        assertTrue(result.detail.contains("仅能容纳一人游玩"))
        assertTrue(result.detail.contains("本次已使用“单人游玩”"))
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
    fun singlePlayerMachineRejectsPreferenceChangesAndFixedPairTransfers() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val singlePlayerRegistration = player.copy(preference = PlayPreference.SOLO)
        val preferenceResult = decideRemoteQueueOperation(
            operationCommand(
                RemoteQueueOperation.CHANGE_PLAY_PREFERENCE,
                singlePlayerRegistration,
                preference = PlayPreference.OPEN_TO_JOIN
            ),
            state(
                machineA = MachineQueue(waiting = listOf(singlePlayerRegistration)),
                nextKey = 3
            ).copy(
                machineCapacities = mapOf("A" to 1, "B" to 2)
            )
        )

        val partner = registration(3, "固定搭档")
        val pairedQueue = MachineQueue(waiting = listOf(player, partner)).let { queue ->
            queue.applyFriendPair(requireNotNull(queue.planFriendPair(player.key, partner.key)))
        }
        val pairedPlayer = pairedQueue.waiting.first { it.key == player.key }
        val transferResult = decideRemoteQueueOperation(
            operationCommand(
                RemoteQueueOperation.TRANSFER_MACHINE,
                pairedPlayer,
                targetMachineId = "B"
            ),
            state(machineA = pairedQueue, nextKey = 4).copy(
                machineCapacities = mapOf("A" to 2, "B" to 1)
            )
        )

        assertTrue(preferenceResult is RemoteQueueOperationDecision.Reject)
        assertEquals(
            "机台 A 仅能容纳一人游玩，本次登记不能更改游玩偏好。",
            (preferenceResult as RemoteQueueOperationDecision.Reject).detail
        )
        assertTrue(transferResult is RemoteQueueOperationDecision.Reject)
        assertEquals(listOf(player.key, partner.key), pairedQueue.waiting.map { it.key })
    }

    @Test
    fun transferToSinglePlayerMachineUsesSoloAndExplainsTheResult() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id,
            preference = PlayPreference.OPEN_TO_JOIN
        )
        val result = decideRemoteQueueOperation(
            operationCommand(
                RemoteQueueOperation.TRANSFER_MACHINE,
                player,
                targetMachineId = "B"
            ),
            state(machineA = MachineQueue(waiting = listOf(player)), nextKey = 3).copy(
                machineCapacities = mapOf("A" to 2, "B" to 1)
            )
        ) as RemoteQueueOperationDecision.Apply

        assertEquals(
            PlayPreference.SOLO,
            result.state.queues.getValue("B").waiting.single().preference
        )
        assertTrue(result.detail.contains("本次登记已改为“单人游玩”"))
        assertTrue(result.detail.contains("默认游玩偏好不会改变"))
        assertTrue(result.detail.contains("转回支持共同游玩的机台时"))
    }

    @Test
    fun transferToSinglePlayerMachineDoesNotClaimAnExistingSoloPreferenceChanged() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id,
            preference = PlayPreference.SOLO
        )
        val result = decideRemoteQueueOperation(
            operationCommand(
                RemoteQueueOperation.TRANSFER_MACHINE,
                player,
                targetMachineId = "B"
            ),
            state(machineA = MachineQueue(waiting = listOf(player)), nextKey = 3).copy(
                machineCapacities = mapOf("A" to 2, "B" to 1)
            )
        ) as RemoteQueueOperationDecision.Apply

        assertEquals(
            PlayPreference.SOLO,
            result.state.queues.getValue("B").waiting.single().preference
        )
        assertTrue(result.detail.contains("本次登记继续使用“单人游玩”"))
        assertFalse(result.detail.contains("本次登记已改为“单人游玩”"))
        assertTrue(result.detail.contains("默认游玩偏好不会改变"))
        assertTrue(result.detail.contains("仍会保持“单人游玩”"))
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
        assertTrue(transferred.detail.contains("留在原机台的登记仍将暂缓一次"))
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
        assertTrue(exited.detail.contains("另一份登记仍将暂缓一次"))
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
        assertTrue(changedPreference.detail.contains("玩家资料中的默认游玩偏好没有改变"))
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
            "现场当前未使用登记排队，暂不能线上加入排队。",
            (closed as RemoteQueueOperationDecision.Reject).detail
        )
        assertTrue(reopened is RemoteQueueOperationDecision.Apply)
        assertTrue(disabled is RemoteQueueOperationDecision.Reject)
        assertEquals(
            "系统规则不允许线上登记。",
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
        assertEquals("固定组合的两份登记将同时暂缓一次。", deferred.detail)
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
    fun skippingOneTurnCanBeSetAndCancelledRemotely() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val initial = state(
            machineA = MachineQueue(
                playing = listOf(registration(1, "游玩中")),
                waiting = listOf(player, registration(3, "其他玩家")),
                playingStartedAtMillis = 1_000L
            ),
            nextKey = 4
        )
        val skipped = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.SKIP_ONE_TURN, player),
            initial
        ) as RemoteQueueOperationDecision.Apply
        assertEquals(
            QueueAbsenceStatus.SKIP_ONE_TURN,
            skipped.state.queues.getValue("A").waiting.first { it.key == 2 }.absenceStatus
        )
        assertEquals(
            "登记将轮空一次。下一次轮到时会移至队尾，计一次轮空，随后自动解除。",
            skipped.detail
        )
        assertTrue(
            decideRemoteQueueOperation(
                operationCommand(RemoteQueueOperation.SKIP_ONE_TURN, player),
                skipped.state
            ) is RemoteQueueOperationDecision.AlreadyApplied
        )
        val cancelled = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.CANCEL_SKIP_ONE_TURN, player),
            skipped.state
        ) as RemoteQueueOperationDecision.Apply
        assertEquals(
            QueueAbsenceStatus.NONE,
            cancelled.state.queues.getValue("A").waiting.first { it.key == 2 }.absenceStatus
        )
        assertEquals("登记已取消轮空一次。", cancelled.detail)
    }

    @Test
    fun aUsedDeferralIsRefusedButSkippingIsNot() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id,
            deferOneRoundUsed = true
        )
        val current = state(
            machineA = MachineQueue(
                playing = listOf(registration(1, "游玩中")),
                waiting = listOf(player),
                playingStartedAtMillis = 1_000L
            ),
            nextKey = 3
        )
        assertEquals(
            DEFER_ONE_ROUND_USED_REASON,
            (decideRemoteQueueOperation(
                operationCommand(RemoteQueueOperation.DEFER_ONE_ROUND, player),
                current
            ) as RemoteQueueOperationDecision.Reject).detail
        )
        assertTrue(
            decideRemoteQueueOperation(
                operationCommand(RemoteQueueOperation.SKIP_ONE_TURN, player),
                current
            ) is RemoteQueueOperationDecision.Apply
        )
    }

    @Test
    fun aCheckedInOnlineRegistrationThatHasNotPlayedIsPostponedOnlyByTheManagementApp() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id,
            wasOnlineRegistration = true
        )
        val current = state(
            machineA = MachineQueue(
                playing = listOf(registration(1, "游玩中")),
                waiting = listOf(player),
                playingStartedAtMillis = 1_000L
            ),
            nextKey = 3
        )
        listOf(
            RemoteQueueOperation.DEFER_ONE_ROUND,
            RemoteQueueOperation.SKIP_ONE_TURN,
            RemoteQueueOperation.TEMPORARILY_LEAVE
        ).forEach { operation ->
            assertEquals(
                operation.name,
                ONLINE_REGISTRATION_POSTPONE_REASON,
                (decideRemoteQueueOperation(operationCommand(operation, player), current)
                    as RemoteQueueOperationDecision.Reject).detail
            )
            assertTrue(
                operation.name,
                decideRemoteQueueOperation(
                    operationCommand(operation, player).copy(
                        source = RemoteQueueOperationSource.MANAGEMENT_APP
                    ),
                    current
                ) is RemoteQueueOperationDecision.Apply
            )
        }
    }

    @Test
    fun registrationInThePlayingPositionCanNeitherDeferNorLeaveTemporarily() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val next = registration(3, "下一位").copy(preference = PlayPreference.SOLO)
        val initial = state(
            machineA = MachineQueue(
                playing = listOf(player),
                waiting = listOf(next),
                playingStartedAtMillis = 1_000L
            ),
            nextKey = 4
        )

        val deferred = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.DEFER_ONE_ROUND, player).copy(
                expectedPosition = RemoteRegistrationPosition.PLAYING,
                expectedAbsenceStatus = QueueAbsenceStatus.NONE,
                expectedTemporaryAwaySkippedTurns = 0,
                expectedPendingCheckIn = false
            ),
            initial,
            appliedAtMillis = 8_000L
        ) as RemoteQueueOperationDecision.Reject
        assertEquals(
            "处于游玩位置的登记不能暂缓一次，请先在现场终端撤回至等待顺序。",
            deferred.detail
        )

        val temporarilyAway = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.TEMPORARILY_LEAVE, player).copy(
                expectedPosition = RemoteRegistrationPosition.PLAYING,
                expectedAbsenceStatus = QueueAbsenceStatus.NONE,
                expectedTemporaryAwaySkippedTurns = 0,
                expectedPendingCheckIn = false
            ),
            initial,
            appliedAtMillis = 9_000L
        ) as RemoteQueueOperationDecision.Reject
        assertEquals(
            "处于游玩位置的登记不能暂时离开，请先在现场终端撤回至等待顺序。",
            temporarilyAway.detail
        )
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
    fun missedTurnsKeptAfterComingBackDoNotMakeRemoteCommandsLookStale() {
        // The player came back after two missed turns; the terminal keeps that count until
        // the registration has played, but tells the server nothing of it.
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id,
            temporaryAwaySkippedTurns = 2
        )
        assertEquals(0, player.publishedTemporaryAwaySkippedTurns)
        assertEquals(
            2,
            player.copy(absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY)
                .publishedTemporaryAwaySkippedTurns
        )
        val current = state(
            machineA = MachineQueue(
                playing = listOf(registration(1, "游玩中")),
                waiting = listOf(player),
                playingStartedAtMillis = 1_000L
            ),
            nextKey = 3
        )

        val left = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.TEMPORARILY_LEAVE, player).copy(
                expectedPosition = RemoteRegistrationPosition.WAITING,
                expectedAbsenceStatus = QueueAbsenceStatus.NONE,
                expectedTemporaryAwaySkippedTurns = 0,
                expectedPendingCheckIn = false
            ),
            current,
            appliedAtMillis = 8_000L
        ) as RemoteQueueOperationDecision.Apply

        val away = left.state.queues.getValue("A").waiting.single()
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, away.absenceStatus)
        assertEquals(2, away.temporaryAwaySkippedTurns)
    }

    @Test
    fun stoppedMachineStillLetsARegistrationLeaveOrMoveAwayRemotely() {
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
        assertTrue(repeated is RemoteQueueOperationDecision.AlreadyApplied)

        val leave = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.LEAVE_QUEUE, player),
            stopped
        ) as RemoteQueueOperationDecision.Apply
        assertTrue(leave.state.queues.getValue("A").allRegistrations.isEmpty())

        val moved = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.TRANSFER_MACHINE, player, targetMachineId = "B"),
            stopped
        ) as RemoteQueueOperationDecision.Apply
        assertTrue(moved.state.queues.getValue("A").allRegistrations.isEmpty())
        assertEquals(listOf(player.key), moved.state.queues.getValue("B").waiting.map { it.key })

        // Everything else waits until the machine runs again.
        val cancel = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND, player),
            stopped
        )
        assertEquals(
            "机台 A 已停止使用，恢复正常使用前只能退出排队或转至其他机台。",
            (cancel as RemoteQueueOperationDecision.Reject).detail
        )
    }

    @Test
    fun aRegistrationCannotBeMovedRemotelyToAStoppedMachine() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = profile().id
        )
        val targetStopped = state(machineA = MachineQueue(waiting = listOf(player)), nextKey = 3)
            .copy(
                machineStatuses = mapOf(
                    "A" to MachineStatus(),
                    "B" to MachineStatus().stop(MachineStopReason.MAINTENANCE, 1_000L)
                )
            )

        val moved = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.TRANSFER_MACHINE, player, targetMachineId = "B"),
            targetStopped
        )

        assertEquals(
            "机台 B 已停止使用，暂不能转入。",
            (moved as RemoteQueueOperationDecision.Reject).detail
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

    @Test
    fun verifiedWebsiteAccountOperationUsesStableProfileIdentityWhileQqSyncs() {
        val player = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = PROFILE_ID
        )
        val command = operationCommand(
            operation = RemoteQueueOperation.LEAVE_QUEUE,
            registration = player
        ).copy(
            actorQq = "87654321",
            source = RemoteQueueOperationSource.WEBSITE_REMOTE,
            profileIdentityVerified = true
        )

        assertTrue(
            decideRemoteQueueOperation(
                command,
                state(machineA = MachineQueue(waiting = listOf(player)), nextKey = 3)
            ) is RemoteQueueOperationDecision.Apply
        )
        assertTrue(
            decideRemoteQueueOperation(
                command.copy(profileIdentityVerified = false),
                state(machineA = MachineQueue(waiting = listOf(player)), nextKey = 3)
            ) is RemoteQueueOperationDecision.Reject
        )
        assertTrue(
            decideRemoteQueueOperation(
                joinCommand().copy(
                    actorQq = "87654321",
                    profileIdentityVerified = true
                ),
                state()
            ) is RemoteQueueOperationDecision.Reject
        )
    }

    @Test
    fun managementCheckInWorksWhenWebsiteAndBotRemoteTogglesAreOff() {
        val pending = pendingRegistration()
        val command = operationCommand(RemoteQueueOperation.CHECK_IN, pending).copy(
            source = RemoteQueueOperationSource.MANAGEMENT_APP,
            expectedPosition = RemoteRegistrationPosition.WAITING,
            expectedFixedPairId = null,
            expectedAbsenceStatus = QueueAbsenceStatus.NONE,
            expectedTemporaryAwaySkippedTurns = 0,
            expectedPendingCheckIn = true,
            machineConfigurationRevision = 1L
        )
        val result = decideRemoteQueueOperation(
            command,
            state(machineA = MachineQueue(waiting = listOf(pending))).copy(
                websiteRemoteEnabled = false,
                oneBotSyncEnabled = false
            )
        )

        assertTrue(result is RemoteQueueOperationDecision.Apply)
        result as RemoteQueueOperationDecision.Apply
        assertFalse(result.state.queues.getValue("A").waiting.single().requiresOnSiteCheckIn)
        assertTrue(result.action is QueueAction.CheckIn)
    }

    @Test
    fun managementCheckInIsAlreadyAppliedWhenTheRegistrationIsNoLongerPending() {
        val checkedIn = pendingRegistration().copy(requiresOnSiteCheckIn = false)
        val command = operationCommand(RemoteQueueOperation.CHECK_IN, checkedIn).copy(
            source = RemoteQueueOperationSource.MANAGEMENT_APP,
            expectedPosition = RemoteRegistrationPosition.WAITING,
            expectedFixedPairId = null,
            expectedAbsenceStatus = QueueAbsenceStatus.NONE,
            expectedTemporaryAwaySkippedTurns = 0,
            expectedPendingCheckIn = true,
            machineConfigurationRevision = 1L
        )

        val result = decideRemoteQueueOperation(
            command,
            state(machineA = MachineQueue(waiting = listOf(checkedIn)), nextKey = 3)
        )

        assertTrue(result is RemoteQueueOperationDecision.AlreadyApplied)
    }

    @Test
    fun managementCreateRegistrationIsImmediateAndDoesNotRequireAQq() {
        val command = RemoteQueueOperationCommand(
            commandId = "00000000-0000-0000-0000-000000000498",
            createdAtMillis = 2_000L,
            queueId = QUEUE_ID,
            profileId = PROFILE_ID,
            actorQq = "",
            operation = RemoteQueueOperation.CREATE_REGISTRATION,
            source = RemoteQueueOperationSource.MANAGEMENT_APP,
            machineId = "A",
            preference = PlayPreference.OPEN_TO_JOIN,
            machineConfigurationRevision = 1L
        )
        val result = decideRemoteQueueOperation(
            command,
            state().copy(
                playerProfiles = listOf(profile().copy(qqNumber = null))
            )
        )

        assertTrue(result is RemoteQueueOperationDecision.Apply)
        result as RemoteQueueOperationDecision.Apply
        val registration = result.state.queues.getValue("A").allRegistrations.single()
        assertFalse(registration.requiresOnSiteCheckIn)
        assertEquals(PROFILE_ID, registration.playerProfileId)
        assertEquals(2, result.state.nextRegistrationKey)
    }

    @Test
    fun onlyAJoinFromTheWebsiteWithTheBoxOnRecordsThatTheExplanationWasShown() {
        fun join(requested: Boolean?, source: RemoteQueueOperationSource) =
            decideRemoteQueueOperation(
                joinCommand().copy(alsoQueuesAtPairedMachine = requested, source = source),
                pairedState().copy(playerProfiles = listOf(profile()))
            ) as RemoteQueueOperationDecision.Apply

        assertTrue(
            join(true, RemoteQueueOperationSource.WEBSITE_REMOTE)
                .updatedProfile!!.pairedMachineQueueingIntroduced
        )
        assertFalse(
            join(false, RemoteQueueOperationSource.WEBSITE_REMOTE)
                .updatedProfile!!.pairedMachineQueueingIntroduced
        )
        // The Bot has nothing to show, so it records nothing.
        assertFalse(
            join(true, RemoteQueueOperationSource.QQ_BOT)
                .updatedProfile!!.pairedMachineQueueingIntroduced
        )
    }

    @Test
    fun onlineJoinFollowsTheTickBoxOfThatOneRegistration() {
        fun join(requested: Boolean?, paired: Boolean = true) = decideRemoteQueueOperation(
            joinCommand().copy(alsoQueuesAtPairedMachine = requested),
            pairedState(paired = paired).copy(playerProfiles = listOf(profile()))
        )

        fun RemoteQueueOperationDecision.joined(): Registration =
            (this as RemoteQueueOperationDecision.Apply).state.queues.getValue("A")
                .waiting.single { it.playerProfileId == PROFILE_ID }

        val ticked = join(requested = true).joined()
        assertTrue(ticked.alsoQueuesAtPairedMachine)
        // Until it has checked in, the registration holds no place on the other machine.
        assertTrue(ticked.requiresOnSiteCheckIn)
        assertFalse(ticked.holdsPairedMachinePlace)

        assertFalse(join(requested = false).joined().alsoQueuesAtPairedMachine)
        // A client that does not mention it gets a registration that stays at its machine.
        assertFalse(join(requested = null).joined().alsoQueuesAtPairedMachine)
        assertFalse(join(requested = null, paired = false).joined().alsoQueuesAtPairedMachine)

        // Ticked for a machine without a pair.
        assertEquals(
            "一对机台的设置已经变化，请重新确认后再加入。",
            (join(requested = true, paired = false) as RemoteQueueOperationDecision.Reject).detail
        )
    }

    @Test
    fun managementCreatedRegistrationIsNotTakenByTheIdlePairedMachineWithoutAnAnnouncement() {
        val command = RemoteQueueOperationCommand(
            commandId = "00000000-0000-0000-0000-000000000497",
            createdAtMillis = 2_000L,
            queueId = QUEUE_ID,
            profileId = PROFILE_ID,
            actorQq = "",
            operation = RemoteQueueOperation.CREATE_REGISTRATION,
            source = RemoteQueueOperationSource.MANAGEMENT_APP,
            machineId = "A",
            preference = PlayPreference.OPEN_TO_JOIN,
            machineConfigurationRevision = 1L,
            alsoQueuesAtPairedMachine = true
        )
        val result = decideRemoteQueueOperation(
            command,
            pairedState(
                machineA = MachineQueue(
                    playing = listOf(registration(7, "机台甲玩家")),
                    playingStartedAtMillis = 1_000L
                )
            ).copy(
                playerProfiles = listOf(profile()),
                nextRegistrationKey = 8
            )
        ) as RemoteQueueOperationDecision.Apply

        // Machine B stands empty, but nothing has announced an arrangement yet: the new
        // registration waits at machine A and only holds its place at machine B.
        val created = result.state.queues.getValue("A").waiting.single()
        assertEquals(PROFILE_ID, created.playerProfileId)
        assertTrue(created.alsoQueuesAtPairedMachine)
        assertTrue(result.state.queues.getValue("B").playing.isEmpty())
        assertEquals(listOf(created.key), result.state.pairedMachineOrders["B"])
        assertEquals(setOf("A"), result.changedMachineIds)
        assertFalse(result.detail.contains("自动转至"))
        assertEquals(9, result.state.nextRegistrationKey)
    }

    @Test
    fun remoteCommandSwitchesQueueingAtTwoMachines() {
        val waiting = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = PROFILE_ID
        )
        val busyQueues = pairedState(
            machineA = MachineQueue(
                playing = listOf(registration(1, "机台甲玩家")),
                waiting = listOf(waiting),
                playingStartedAtMillis = 1_000L
            ),
            machineB = MachineQueue(
                playing = listOf(registration(3, "机台乙玩家")),
                playingStartedAtMillis = 1_000L
            )
        )
        fun switch(
            enabled: Boolean?,
            state: RemoteQueueExecutionState = busyQueues,
            target: Registration = waiting
        ) = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.SET_PAIRED_MACHINE_QUEUEING, target)
                .copy(alsoQueuesAtPairedMachine = enabled),
            state
        )

        val enabled = switch(true) as RemoteQueueOperationDecision.Apply
        val switchedOn = enabled.state.queues.getValue("A").waiting.single()
        assertTrue(switchedOn.alsoQueuesAtPairedMachine)
        // Turned on while it was already waiting: no place on B before it has played a round.
        assertTrue(switchedOn.skipsPairedMachineThisRound)
        assertEquals(emptyList<Int>(), enabled.state.pairedMachineOrders["B"].orEmpty())
        assertEquals(
            "已允许系统在 机台 A 和 机台 B 之间安排这份登记。" +
                "这份登记游玩一轮之前，不会被安排到 机台 B。",
            enabled.detail
        )

        val disabled = switch(false, enabled.state, enabled.state.queues.getValue("A").waiting.single())
            as RemoteQueueOperationDecision.Apply
        assertFalse(disabled.state.queues.getValue("A").waiting.single().alsoQueuesAtPairedMachine)
        assertEquals(emptyList<Int>(), disabled.state.pairedMachineOrders["B"].orEmpty())
        assertEquals(
            "已取消系统在 机台 A 和 机台 B 之间的安排，这份登记只在 机台 A 排队。",
            disabled.detail
        )

        assertEquals(
            "这份登记已经只在当前机台排队。",
            (switch(false) as RemoteQueueOperationDecision.AlreadyApplied).detail
        )
        assertEquals(
            "这份登记已经允许系统在两台机台之间安排。",
            (
                switch(true, enabled.state, enabled.state.queues.getValue("A").waiting.single())
                    as RemoteQueueOperationDecision.AlreadyApplied
                ).detail
        )
        assertEquals(
            "未能确定是否允许系统在两台机台之间安排登记，请重新操作。",
            (switch(null) as RemoteQueueOperationDecision.Reject).detail
        )
        assertEquals(
            "系统规则不允许自动在两台机台之间安排登记。",
            (
                switch(true, busyQueues.copy(machinePartners = emptyMap()))
                    as RemoteQueueOperationDecision.Reject
                ).detail
        )

        // Even with machine B standing empty, a registration that is already waiting is not
        // moved there: switching it on only counts after it has played a round.
        val idlePartner = pairedState(
            machineA = MachineQueue(
                playing = listOf(registration(1, "机台甲玩家")),
                waiting = listOf(waiting),
                playingStartedAtMillis = 1_000L
            )
        )
        val stayed = switch(true, idlePartner) as RemoteQueueOperationDecision.Apply
        assertTrue(stayed.state.queues.getValue("B").playing.isEmpty())
        assertEquals(2, stayed.state.queues.getValue("A").waiting.single().key)
        assertTrue(stayed.detail.contains("游玩一轮之前，不会被安排到 机台 B"))
    }

    @Test
    fun registrationArrangedBetweenTwoMachinesCanStillBeMovedToTheOtherOneByHand() {
        val waiting = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = PROFILE_ID,
            alsoQueuesAtPairedMachine = true
        )
        val state = pairedState(
            machineA = MachineQueue(
                playing = listOf(registration(1, "机台甲玩家")),
                waiting = listOf(waiting),
                playingStartedAtMillis = 1_000L
            ),
            machineB = MachineQueue(
                playing = listOf(registration(3, "机台乙玩家")),
                waiting = listOf(registration(4, "机台乙等待玩家")),
                playingStartedAtMillis = 1_000L
            )
        )

        val result = decideRemoteQueueOperation(
            operationCommand(
                RemoteQueueOperation.TRANSFER_MACHINE,
                waiting,
                targetMachineId = "B"
            ),
            state
        ) as RemoteQueueOperationDecision.Apply

        // It joins the tail of machine B and takes a new place at the tail of machine A.
        assertEquals(listOf(4, 2), result.state.queues.getValue("B").waiting.map { it.key })
        assertTrue(result.state.queues.getValue("B").waiting.last().alsoQueuesAtPairedMachine)
        assertEquals(listOf(2), result.state.pairedMachineOrders["A"])
    }

    @Test
    fun managementActionsHandleQueueingAtTwoMachines() {
        val waiting = registration(2, "资料玩家").copy(
            isTemporary = false,
            playerProfileId = PROFILE_ID
        )
        val stableIds = mapOf(
            "A" to "10000000000000000000000000000001",
            "B" to "10000000000000000000000000000002"
        )
        val busy = pairedState(
            machineA = MachineQueue(
                playing = listOf(registration(1, "机台甲玩家")),
                waiting = listOf(waiting),
                playingStartedAtMillis = 1_000L
            ),
            machineB = MachineQueue(
                playing = listOf(registration(3, "机台乙玩家")),
                playingStartedAtMillis = 1_000L
            )
        ).copy(machineStableIds = stableIds, nextRegistrationKey = 4)

        fun command(
            action: ManagementQueueAction,
            state: RemoteQueueExecutionState,
            registrationKeys: List<Int> = emptyList(),
            alsoQueuesAtPairedMachine: Boolean? = null,
            profileId: String? = null,
            targetMachineId: String? = null
        ) = ManagementQueueActionCommand(
            commandId = "00000000-0000-0000-0000-000000000480",
            createdAtMillis = 2_000L,
            queueId = QUEUE_ID,
            queueRevision = 1L,
            machineConfigurationRevision = 1L,
            action = action,
            machineId = "A",
            machineStableId = stableIds.getValue("A"),
            expectedPlayingRegistrationIds = state.queues.getValue("A").playing.map {
                publicRegistrationId(QUEUE_ID, it.key)
            },
            expectedWaitingPositionRegistrationIds = state.queues.getValue("A")
                .waitingPositions().map { position ->
                    position.map { publicRegistrationId(QUEUE_ID, it.key) }
                },
            registrationIds = registrationKeys.map { publicRegistrationId(QUEUE_ID, it) },
            profileId = profileId,
            preference = PlayPreference.OPEN_TO_JOIN.takeIf { profileId != null },
            targetMachineId = targetMachineId,
            targetMachineStableId = targetMachineId?.let(stableIds::getValue),
            alsoQueuesAtPairedMachine = alsoQueuesAtPairedMachine
        )

        val enabled = decideManagementQueueAction(
            command(
                ManagementQueueAction.SET_PAIRED_MACHINE_QUEUEING,
                busy,
                registrationKeys = listOf(2),
                alsoQueuesAtPairedMachine = true
            ),
            busy
        ) as ManagementQueueActionDecision.Apply
        assertTrue(enabled.state.queues.getValue("A").waiting.single().alsoQueuesAtPairedMachine)
        assertTrue(enabled.state.queues.getValue("A").waiting.single().skipsPairedMachineThisRound)
        assertEquals(emptyList<Int>(), enabled.state.pairedMachineOrders["B"].orEmpty())
        assertEquals("已更改是否允许系统在两台机台之间安排。", enabled.detail)

        // Asking for the state it already has changes nothing and is not an error.
        val repeated = decideManagementQueueAction(
            command(
                ManagementQueueAction.SET_PAIRED_MACHINE_QUEUEING,
                enabled.state,
                registrationKeys = listOf(2),
                alsoQueuesAtPairedMachine = true
            ),
            enabled.state
        )
        assertEquals(
            "这份登记已经允许系统在两台机台之间安排。",
            (repeated as ManagementQueueActionDecision.AlreadyApplied).detail
        )

        val missingChoice = decideManagementQueueAction(
            command(
                ManagementQueueAction.SET_PAIRED_MACHINE_QUEUEING,
                busy,
                registrationKeys = listOf(2)
            ),
            busy
        )
        assertEquals(
            "未能确定是否允许系统在两台机台之间安排登记，请重新操作。",
            (missingChoice as ManagementQueueActionDecision.Reject).detail
        )

        val unpaired = busy.copy(machinePartners = emptyMap())
        val withoutPair = decideManagementQueueAction(
            command(
                ManagementQueueAction.SET_PAIRED_MACHINE_QUEUEING,
                unpaired,
                registrationKeys = listOf(2),
                alsoQueuesAtPairedMachine = true
            ),
            unpaired
        )
        assertEquals(
            "系统规则不允许自动在两台机台之间安排登记。",
            (withoutPair as ManagementQueueActionDecision.Reject).detail
        )

        // Such a registration may still be moved to the other machine by hand; the choice
        // stays on.
        val transfer = decideManagementQueueAction(
            command(
                ManagementQueueAction.TRANSFER_REGISTRATIONS,
                enabled.state,
                registrationKeys = listOf(2),
                targetMachineId = "B"
            ),
            enabled.state
        ) as ManagementQueueActionDecision.Apply
        assertTrue(transfer.state.queues.getValue("A").waiting.none { it.key == 2 })
        assertTrue(transfer.state.queues.getValue("B").waiting.last().alsoQueuesAtPairedMachine)

        // A registration created from a profile follows the profile's setting.
        val emptyA = pairedState(
            machineA = MachineQueue(
                playing = listOf(registration(1, "机台甲玩家")),
                playingStartedAtMillis = 1_000L
            ),
            machineB = MachineQueue(
                playing = listOf(registration(3, "机台乙玩家")),
                playingStartedAtMillis = 1_000L
            )
        ).copy(
            machineStableIds = stableIds,
            nextRegistrationKey = 4,
            playerProfiles = listOf(profile())
        )
        val created = decideManagementQueueAction(
            command(
                ManagementQueueAction.ADD_PROFILE_REGISTRATION,
                emptyA,
                alsoQueuesAtPairedMachine = true,
                profileId = PROFILE_ID
            ),
            emptyA
        ) as ManagementQueueActionDecision.Apply
        assertTrue(created.state.queues.getValue("A").waiting.single().alsoQueuesAtPairedMachine)
        assertEquals(listOf(4), created.state.pairedMachineOrders["B"])
    }

    @Test
    fun managementActionsFollowThePlayingPositionAndStoppedMachineRules() {
        val stableIds = mapOf(
            "A" to "10000000000000000000000000000001",
            "B" to "10000000000000000000000000000002"
        )
        val running = state(
            machineA = MachineQueue(
                playing = listOf(registration(1, "游玩中")),
                waiting = listOf(registration(2, "等待中")),
                playingStartedAtMillis = 1_000L
            ),
            nextKey = 3
        ).copy(machineStableIds = stableIds)

        fun command(
            action: ManagementQueueAction,
            state: RemoteQueueExecutionState,
            registrationKey: Int,
            targetMachineId: String? = null
        ) = ManagementQueueActionCommand(
            commandId = "00000000-0000-0000-0000-000000000481",
            createdAtMillis = 2_000L,
            queueId = QUEUE_ID,
            queueRevision = 1L,
            machineConfigurationRevision = 1L,
            action = action,
            machineId = "A",
            machineStableId = stableIds.getValue("A"),
            expectedPlayingRegistrationIds = state.queues.getValue("A").playing.map {
                publicRegistrationId(QUEUE_ID, it.key)
            },
            expectedWaitingPositionRegistrationIds = state.queues.getValue("A")
                .waitingPositions().map { position ->
                    position.map { publicRegistrationId(QUEUE_ID, it.key) }
                },
            registrationIds = listOf(publicRegistrationId(QUEUE_ID, registrationKey)),
            targetMachineId = targetMachineId,
            targetMachineStableId = targetMachineId?.let(stableIds::getValue)
        )

        // A registration in the playing position can neither defer nor leave temporarily.
        assertEquals(
            "处于游玩位置的登记不能暂缓一次，请先撤回至等待顺序。",
            (decideManagementQueueAction(
                command(ManagementQueueAction.DEFER_ONE_ROUND, running, 1),
                running
            ) as ManagementQueueActionDecision.Reject).detail
        )
        assertEquals(
            "处于游玩位置的登记不能暂时离开，请先撤回至等待顺序。",
            (decideManagementQueueAction(
                command(ManagementQueueAction.TEMPORARILY_LEAVE, running, 1),
                running
            ) as ManagementQueueActionDecision.Reject).detail
        )

        // On a stopped machine a registration can still be removed or moved away.
        val stopped = running.copy(
            machineStatuses = mapOf(
                "A" to MachineStatus().stop(MachineStopReason.MAINTENANCE, 1_500L),
                "B" to MachineStatus()
            )
        )
        val removed = decideManagementQueueAction(
            command(ManagementQueueAction.REMOVE_REGISTRATIONS, stopped, 2),
            stopped
        ) as ManagementQueueActionDecision.Apply
        assertTrue(removed.state.queues.getValue("A").waiting.isEmpty())
        val moved = decideManagementQueueAction(
            command(ManagementQueueAction.TRANSFER_REGISTRATIONS, stopped, 2, targetMachineId = "B"),
            stopped
        ) as ManagementQueueActionDecision.Apply
        assertEquals(listOf(2), moved.state.queues.getValue("B").waiting.map { it.key })
        assertTrue(
            decideManagementQueueAction(
                command(ManagementQueueAction.DEFER_ONE_ROUND, stopped, 2),
                stopped
            ) is ManagementQueueActionDecision.Reject
        )
    }

    @Test
    fun managementCanOnlyRemoveACheckedInOnlineRegistrationThatMissesItsFirstTurn() {
        val stableIds = mapOf(
            "A" to "10000000000000000000000000000001",
            "B" to "10000000000000000000000000000002"
        )
        val online = registration(2, "线上玩家").copy(
            isTemporary = false,
            playerProfileId = PROFILE_ID,
            wasOnlineRegistration = true
        )
        val current = state(
            machineA = MachineQueue(
                playing = listOf(registration(1, "游玩中")),
                waiting = listOf(online, registration(3, "等待中")),
                playingStartedAtMillis = 1_000L
            ),
            nextKey = 4
        ).copy(machineStableIds = stableIds)

        fun command(action: ManagementQueueAction, resolution: NoShowResolution? = null) = decideManagementQueueAction(
            ManagementQueueActionCommand(
                commandId = "00000000-0000-0000-0000-000000000482",
                createdAtMillis = 2_000L,
                queueId = QUEUE_ID,
                queueRevision = 1L,
                machineConfigurationRevision = 1L,
                action = action,
                machineId = "A",
                machineStableId = stableIds.getValue("A"),
                expectedPlayingRegistrationIds = current.queues.getValue("A").playing.map {
                    publicRegistrationId(QUEUE_ID, it.key)
                },
                expectedWaitingPositionRegistrationIds = current.queues.getValue("A")
                    .waitingPositions().map { position ->
                        position.map { publicRegistrationId(QUEUE_ID, it.key) }
                    },
                registrationIds = listOf(publicRegistrationId(QUEUE_ID, online.key)),
                noShowResolution = resolution
            ),
            current
        )

        fun noShow(resolution: NoShowResolution) =
            command(ManagementQueueAction.MARK_NO_SHOW, resolution)

        listOf(NoShowResolution.DEFER_ONE_ROUND, NoShowResolution.MOVE_TO_TAIL).forEach { resolution ->
            assertEquals(
                "线上登记第一次轮到时未到场，只能移除登记。",
                (noShow(resolution) as ManagementQueueActionDecision.Reject).detail
            )
        }
        val removed = noShow(NoShowResolution.REMOVE) as ManagementQueueActionDecision.Apply
        assertTrue(removed.state.queues.getValue("A").allRegistrations.none { it.key == online.key })

        // The rule is about how a recorded no-show is settled. The separate controls for
        // deferring such a registration or setting it temporarily away are not touched by it.
        val deferred = command(ManagementQueueAction.DEFER_ONE_ROUND) as ManagementQueueActionDecision.Apply
        assertEquals(
            QueueAbsenceStatus.DEFER_ONE_ROUND,
            deferred.state.queues.getValue("A").waiting.first { it.key == online.key }.absenceStatus
        )
        val away = command(ManagementQueueAction.TEMPORARILY_LEAVE) as ManagementQueueActionDecision.Apply
        assertEquals(
            QueueAbsenceStatus.TEMPORARILY_AWAY,
            away.state.queues.getValue("A").waiting.first { it.key == online.key }.absenceStatus
        )
    }

    /** Machines A and B form a pair unless [paired] is false. */
    private fun pairedState(
        machineA: MachineQueue = MachineQueue(),
        machineB: MachineQueue = MachineQueue(),
        paired: Boolean = true
    ): RemoteQueueExecutionState {
        val partners = if (paired) mapOf("A" to "B", "B" to "A") else emptyMap()
        val settled = QueueEngine.settlePairedMachines(
            state = QueueEngineState(linkedMapOf("A" to machineA, "B" to machineB)),
            policy = QueueEnginePolicy(machinePartners = partners),
            atMillis = 1_500L
        )
        return stateWithQueues(queues = settled.queues).copy(
            pairedMachineOrders = settled.pairedMachineOrders,
            machinePartners = partners
        )
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
