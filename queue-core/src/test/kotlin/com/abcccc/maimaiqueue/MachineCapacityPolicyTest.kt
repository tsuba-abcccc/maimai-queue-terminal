package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineCapacityPolicyTest {
    private fun registration(
        key: Int,
        preference: PlayPreference = PlayPreference.OPEN_TO_JOIN,
        fixedPartnerKey: Int? = null
    ) = Registration(
        key = key,
        displayId = "玩家$key",
        preference = preference,
        fixedPartnerKey = fixedPartnerKey,
        createdAtMillis = key * 1_000L
    )

    private val singlePlayerPolicy = QueueEnginePolicy(
        machineCapacities = mapOf("A" to 1, "B" to 1)
    )

    @Test
    fun singlePlayerMachineAcceptsOnlySoloRegistrations() {
        val state = QueueEngineState.single("A", MachineQueue())
        val openResult = QueueEngine.execute(
            state,
            QueueAction.AddRegistrations(
                "A",
                listOf(registration(1)),
                RegistrationPlacement.WAITING_TAIL
            ),
            QueueActionContext(policy = singlePlayerPolicy)
        )
        val soloResult = QueueEngine.execute(
            state,
            QueueAction.AddRegistrations(
                "A",
                listOf(registration(1, PlayPreference.SOLO)),
                RegistrationPlacement.AUTO_ADVANCE
            ),
            QueueActionContext(atMillis = 10_000L, policy = singlePlayerPolicy)
        )

        assertEquals(
            QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY,
            (openResult as QueueActionExecution.Rejected).failure.code
        )
        assertTrue(soloResult is QueueActionExecution.Applied)
        assertEquals(
            listOf(1),
            (soloResult as QueueActionExecution.Applied).state.queue("A")!!.playing.map { it.key }
        )
    }

    @Test
    fun singlePlayerMachineRejectsPreferencePairAndSecondPlayingActions() {
        val first = registration(1, PlayPreference.SOLO)
        val second = registration(2, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(first),
            waiting = listOf(second),
            playingStartedAtMillis = 1_000L
        )
        val state = QueueEngineState.single("A", queue)
        val actions = listOf<QueueAction>(
            QueueAction.ChangePreference("A", 1, PlayPreference.OPEN_TO_JOIN),
            QueueAction.CreateFixedPair("A", 1, 2),
            QueueAction.CreateFixedPairWithRegistration("A", 2, registration(3)),
            QueueAction.MoveWaitingRegistrationIntoCurrentRound("A", 2)
        )

        actions.forEach { action ->
            val result = QueueEngine.execute(
                state,
                action,
                QueueActionContext(policy = singlePlayerPolicy)
            )
            assertEquals(
                "$action 应由容量策略拒绝",
                QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY,
                (result as QueueActionExecution.Rejected).failure.code
            )
        }
    }

    @Test
    fun transferToSinglePlayerMachineForcesSoloButDoesNotSplitFixedPair() {
        val open = registration(1)
        val pairFirst = registration(2, fixedPartnerKey = 3)
        val pairSecond = registration(3, fixedPartnerKey = 2)
        val initial = QueueEngineState(
            mapOf(
                "A" to MachineQueue(waiting = listOf(open, pairFirst, pairSecond)),
                "B" to MachineQueue()
            )
        )
        val policy = QueueEnginePolicy(machineCapacities = mapOf("A" to 2, "B" to 1))

        val transferred = QueueEngine.execute(
            initial,
            QueueAction.TransferRegistrations("A", "B", setOf(1)),
            QueueActionContext(policy = policy)
        ) as QueueActionExecution.Applied
        val pairResult = QueueEngine.execute(
            initial,
            QueueAction.TransferRegistrations("A", "B", setOf(2, 3)),
            QueueActionContext(policy = policy)
        )

        assertEquals(PlayPreference.SOLO, transferred.state.queue("B")!!.waiting.single().preference)
        assertNull(transferred.state.queue("B")!!.waiting.single().fixedPartnerKey)
        assertEquals(
            QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY,
            (pairResult as QueueActionExecution.Rejected).failure.code
        )
    }

    @Test
    fun transferBackToSharedMachineKeepsTheSinglePlayerPreference() {
        val initial = QueueEngineState(
            mapOf(
                "A" to MachineQueue(waiting = listOf(registration(1))),
                "B" to MachineQueue()
            )
        )
        val policy = QueueEnginePolicy(machineCapacities = mapOf("A" to 2, "B" to 1))
        val transferredToSinglePlayerMachine = QueueEngine.execute(
            initial,
            QueueAction.TransferRegistrations("A", "B", setOf(1)),
            QueueActionContext(policy = policy)
        ) as QueueActionExecution.Applied
        val transferredBack = QueueEngine.execute(
            transferredToSinglePlayerMachine.state,
            QueueAction.TransferRegistrations("B", "A", setOf(1)),
            QueueActionContext(policy = policy)
        ) as QueueActionExecution.Applied

        assertTrue(transferredBack.state.queue("B")!!.allRegistrations.isEmpty())
        assertEquals(
            PlayPreference.SOLO,
            transferredBack.state.queue("A")!!.waiting.single().preference
        )
        assertNull(transferredBack.state.queue("A")!!.waiting.single().fixedPartnerKey)
    }

    @Test
    fun reorderingSinglePlayerQueueChangesOnlyTheWaitingOrder() {
        val first = registration(1, PlayPreference.SOLO)
        val second = registration(2, PlayPreference.SOLO)
        val third = registration(3, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(first),
            waiting = listOf(second, third),
            playingStartedAtMillis = 1_000L
        )
        val movedPosition = QueueEngine.execute(
            QueueEngineState.single("A", queue),
            QueueAction.MoveWaitingPosition("A", 0, 1),
            QueueActionContext(policy = singlePlayerPolicy)
        ) as QueueActionExecution.Applied
        val replacedOrder = QueueEngine.execute(
            QueueEngineState.single("A", queue),
            QueueAction.ReplaceOrder("A", listOf(first, third, second)),
            QueueActionContext(policy = singlePlayerPolicy)
        ) as QueueActionExecution.Applied

        listOf(movedPosition.state.queue("A")!!, replacedOrder.state.queue("A")!!).forEach {
            reordered ->
            assertEquals(listOf(first.key), reordered.playing.map { it.key })
            assertEquals(listOf(third.key, second.key), reordered.waiting.map { it.key })
            assertTrue(reordered.allRegistrations.all { it.preference == PlayPreference.SOLO })
            assertTrue(reordered.allRegistrations.all { it.fixedPartnerKey == null })
        }
    }

    @Test
    fun restoringLegacyQueueNormalizesSinglePlayerMachineState() {
        val fixedFirst = registration(1, fixedPartnerKey = 2)
        val fixedSecond = registration(2, fixedPartnerKey = 1)
        val current = MachineQueue()
        val restored = MachineQueue(
            playing = listOf(fixedFirst, fixedSecond),
            waiting = listOf(registration(3)),
            playingStartedAtMillis = 2_000L
        )

        val result = QueueEngine.execute(
            QueueEngineState.single("A", current),
            QueueAction.RestoreSnapshot(
                machineId = "A",
                expectedCurrentQueue = current,
                restoredQueue = restored
            ),
            QueueActionContext(policy = singlePlayerPolicy)
        ) as QueueActionExecution.Applied
        val queue = result.state.queue("A")!!

        assertEquals(listOf(1), queue.playing.map { it.key })
        assertEquals(listOf(2, 3), queue.waiting.map { it.key })
        assertTrue(queue.allRegistrations.all { it.preference == PlayPreference.SOLO })
        assertTrue(queue.allRegistrations.all { it.fixedPartnerKey == null })
        assertEquals(emptyList<String>(), queue.invariantViolations())
    }

    @Test
    fun claimingTemporaryRegistrationAlwaysUsesSoloOnSinglePlayerMachine() {
        val temporary = registration(1).copy(isTemporary = true)
        val result = QueueEngine.execute(
            QueueEngineState.single("A", MachineQueue(waiting = listOf(temporary))),
            QueueAction.ClaimWithPlayerProfile(
                machineId = "A",
                registrationKey = temporary.key,
                playerProfileId = "profile-1",
                nickname = "资料玩家",
                gender = PlayerGender.UNDISCLOSED,
                preferenceOverride = null
            ),
            QueueActionContext(policy = singlePlayerPolicy)
        ) as QueueActionExecution.Applied

        val claimed = result.state.queue("A")!!.waiting.single()
        assertEquals(PlayPreference.SOLO, claimed.preference)
        assertEquals("profile-1", claimed.playerProfileId)
        assertTrue(!claimed.isTemporary)
    }

    @Test
    fun singlePlayerRoundAlwaysAdvancesExactlyOneRegistration() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 1_000L
        )

        val result = QueueEngine.execute(
            QueueEngineState.single("A", queue),
            QueueAction.FinishRound("A"),
            QueueActionContext(atMillis = 2_000L, policy = singlePlayerPolicy)
        ) as QueueActionExecution.Applied
        val advanced = result.state.queue("A")!!

        assertEquals(listOf(2), advanced.playing.map { it.key })
        assertEquals(listOf(3, 1), advanced.waiting.map { it.key })
        assertTrue(advanced.allRegistrations.all { it.preference == PlayPreference.SOLO })
    }

    @Test
    fun singlePlayerRoundRemovesUnsignedRegistrationWhenItsTurnArrives() {
        val pending = registration(2, PlayPreference.SOLO).copy(
            requiresOnSiteCheckIn = true,
            onSiteCheckInStartedAtMillis = 1_000L
        )
        val signedIn = registration(3, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(pending, signedIn),
            playingStartedAtMillis = 1_000L
        )

        val result = QueueEngine.execute(
            QueueEngineState.single("A", queue),
            QueueAction.FinishRound("A"),
            QueueActionContext(atMillis = 2_000L, policy = singlePlayerPolicy)
        ) as QueueActionExecution.Applied
        val advanced = result.state.queue("A")!!

        assertEquals(listOf(signedIn.key), advanced.playing.map { it.key })
        assertTrue(advanced.allRegistrations.none { it.key == pending.key })
    }

    @Test
    fun singlePlayerRoundHandlesDeferralAndTemporaryLeaveIndependently() {
        val deferred = registration(2, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val temporarilyAway = registration(3, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 1
        )
        val available = registration(4, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(deferred, temporarilyAway, available),
            playingStartedAtMillis = 1_000L
        )

        val result = QueueEngine.execute(
            QueueEngineState.single("A", queue),
            QueueAction.FinishRound("A"),
            QueueActionContext(atMillis = 2_000L, policy = singlePlayerPolicy)
        ) as QueueActionExecution.Applied
        val advanced = result.state.queue("A")!!
        val deferredAfter = advanced.allRegistrations.single { it.key == deferred.key }
        val awayAfter = advanced.allRegistrations.single { it.key == temporarilyAway.key }

        assertEquals(listOf(available.key), advanced.playing.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, deferredAfter.absenceStatus)
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, awayAfter.absenceStatus)
        assertEquals(2, awayAfter.temporaryAwaySkippedTurns)
    }
}
