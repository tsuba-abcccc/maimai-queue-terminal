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
    fun joinRevalidatesCurrentCapacityAndFeatureSwitches() {
        val full = MachineQueue(waiting = (1..20).map { registration(it, "玩家$it") })
        val capacity = decideRemoteQueueOperation(joinCommand(), state(machineA = full, nextKey = 21))
        val disabled = decideRemoteQueueOperation(
            joinCommand(),
            state().copy(websiteRemoteEnabled = false)
        )
        val onlineRegistrationDisabled = decideRemoteQueueOperation(
            joinCommand(),
            state().copy(allowOnlineRegistration = false)
        )

        assertTrue(capacity is RemoteQueueOperationDecision.Reject)
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
        assertEquals("固定组合的两份登记已同时暂缓一轮。", deferred.detail)
        current = deferred.state

        val deferCancelled = decideRemoteQueueOperation(
            operationCommand(RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND, player),
            current
        ) as RemoteQueueOperationDecision.Apply
        assertEquals(
            setOf(QueueAbsenceStatus.NONE),
            deferCancelled.state.queues.getValue("A").waiting.map { it.absenceStatus }.toSet()
        )
        assertEquals("固定组合的两份登记已同时取消暂缓一轮。", deferCancelled.detail)
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

    private fun state(
        machineA: MachineQueue = MachineQueue(),
        machineB: MachineQueue = MachineQueue(),
        nextKey: Int = 1
    ) = RemoteQueueExecutionState(
        queueId = QUEUE_ID,
        queues = linkedMapOf("A" to machineA, "B" to machineB),
        machineStatuses = mapOf("A" to MachineStatus(), "B" to MachineStatus()),
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
        targetMachineId: String? = null
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
        registrationId = publicRegistrationId(QUEUE_ID, registration.key)
    )

    private companion object {
        const val QUEUE_ID = "00000000-0000-0000-0000-000000000001"
        const val PROFILE_ID = "00000000-0000-0000-0000-000000000901"
        const val COMMAND_ID = "00000000-0000-0000-0000-000000000401"
    }
}
