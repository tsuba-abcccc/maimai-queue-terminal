package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRiskSequenceTest {
    private fun registration(
        key: Int,
        preference: PlayPreference = PlayPreference.OPEN_TO_JOIN,
        pendingCheckIn: Boolean = false
    ) = Registration(
        key = key,
        displayId = "玩家$key",
        preference = preference,
        isTemporary = false,
        createdAtMillis = key * 1_000L,
        requiresOnSiteCheckIn = pendingCheckIn,
        onSiteCheckInStartedAtMillis = if (pendingCheckIn) key * 1_000L else null
    )

    private fun apply(
        state: QueueEngineState,
        action: QueueAction,
        atMillis: Long = 100_000L
    ): QueueEngineState {
        val execution = QueueEngine.execute(
            state = state,
            action = action,
            context = QueueActionContext(atMillis = atMillis)
        )
        assertTrue("动作应成功：$action，实际为 $execution", execution is QueueActionExecution.Applied)
        return (execution as QueueActionExecution.Applied).state.also { result ->
            assertTrue(result.invariantViolations().joinToString(), result.invariantViolations().isEmpty())
        }
    }

    @Test
    fun fixedPairCanBeTransferredSplitExitedAndTransferredAgainWithoutGhostRegistrations() {
        val pairQueue = MachineQueue(
            waiting = listOf(registration(1), registration(2))
        ).let { queue ->
            queue.applyFriendPair(requireNotNull(queue.planFriendPair(1, 2)))
        }.deferOneRound(1)
        var state = QueueEngineState(
            linkedMapOf(
                "A" to pairQueue,
                "B" to MachineQueue(waiting = listOf(registration(3)))
            )
        )

        state = apply(
            state,
            QueueAction.TransferRegistrations("A", "B", setOf(1, 2))
        )
        assertTrue(state.queue("A")!!.allRegistrations.isEmpty())
        assertEquals(setOf(QueueAbsenceStatus.NONE), state.queue("B")!!.waiting
            .filter { it.key in setOf(1, 2) }
            .map(Registration::absenceStatus)
            .toSet())
        assertEquals(2, state.queue("B")!!.waiting.first { it.key == 1 }.fixedPartnerKey)

        state = apply(state, QueueAction.RemoveRegistrations("B", setOf(1)))
        val formerPartner = state.queue("B")!!.waiting.first { it.key == 2 }
        assertEquals(null, formerPartner.fixedPartnerKey)
        assertEquals(PlayPreference.OPEN_TO_JOIN, formerPartner.preference)

        state = apply(
            state,
            QueueAction.TransferRegistrations("B", "A", setOf(2))
        )
        state = apply(state, QueueAction.RemoveRegistrations("A", setOf(2)))

        assertEquals(listOf(3), state.allRegistrations.map(Registration::key))
        assertEquals(1, state.allRegistrations.map(Registration::key).distinct().size)
    }

    @Test
    fun leavingOneCurrentPlayerThenFinishingProcessesEveryAvailabilityStateExactlyOnce() {
        val pending = registration(3, pendingCheckIn = true)
        val deferred = registration(4).copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND)
        val away = registration(5, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
        )
        var state = QueueEngineState.single(
            "A",
            MachineQueue(
                playing = listOf(registration(1), registration(2)),
                waiting = listOf(pending, deferred, away, registration(6, PlayPreference.SOLO)),
                playingStartedAtMillis = 10_000L
            )
        )

        state = apply(state, QueueAction.RemoveRegistrations("A", setOf(1)))
        val afterExit = state.queue("A")!!
        assertEquals(listOf(2), afterExit.playing.map(Registration::key))
        assertEquals(10_000L, afterExit.playingStartedAtMillis)

        state = apply(state, QueueAction.FinishRound("A"), atMillis = 120_000L)
        val afterFinish = state.queue("A")!!
        assertEquals(listOf(6), afterFinish.playing.map(Registration::key))
        assertFalse(afterFinish.allRegistrations.any { it.key == 3 })
        assertEquals(
            QueueAbsenceStatus.NONE,
            afterFinish.waiting.first { it.key == 4 }.absenceStatus
        )
        assertEquals(
            QueueAbsenceStatus.TEMPORARILY_AWAY,
            afterFinish.waiting.first { it.key == 5 }.absenceStatus
        )
        assertEquals(1, afterFinish.waiting.first { it.key == 5 }.temporaryAwaySkippedTurns)
        assertEquals(120_000L, afterFinish.waiting.first { it.key == 2 }.lastPlayedAtMillis)
    }

    @Test
    fun confirmationPlanRejectsCheckInOrRotationThatHappensWhileItIsOpen() {
        val pending = registration(2, PlayPreference.SOLO, pendingCheckIn = true)
        val source = QueueEngineState.single(
            "A",
            MachineQueue(
                playing = listOf(registration(1, PlayPreference.SOLO)),
                waiting = listOf(pending, registration(3, PlayPreference.SOLO)),
                playingStartedAtMillis = 10_000L
            )
        )
        val finishPlan = QueueEngine.plan(source, QueueAction.FinishRound("A"))

        val checkedIn = apply(source, QueueAction.CheckIn("A", 2))
        val afterCheckIn = finishPlan.applyTo(checkedIn)
        assertEquals(
            QueueActionFailureCode.STALE_STATE,
            (afterCheckIn as QueueActionExecution.Rejected).failure.code
        )
        assertEquals(listOf(1), checkedIn.queue("A")!!.playing.map(Registration::key))

        val rotated = apply(source, QueueAction.FinishRound("A"), atMillis = 110_000L)
        val afterRotation = finishPlan.applyTo(rotated)
        assertEquals(
            QueueActionFailureCode.STALE_STATE,
            (afterRotation as QueueActionExecution.Rejected).failure.code
        )
        assertEquals(listOf(3), rotated.queue("A")!!.playing.map(Registration::key))
    }

    @Test
    fun registrationKeepsOneIdentityAcrossAbsenceTransferRotationAndUndo() {
        var state = QueueEngineState(
            linkedMapOf(
                "A" to MachineQueue(),
                "B" to MachineQueue()
            )
        )
        state = apply(
            state,
            QueueAction.AddRegistrations(
                "A",
                listOf(registration(1, PlayPreference.SOLO)),
                RegistrationPlacement.AUTO_ADVANCE
            ),
            atMillis = 10_000L
        )
        state = apply(
            state,
            QueueAction.AddRegistrations(
                "A",
                listOf(registration(2, PlayPreference.SOLO)),
                RegistrationPlacement.WAITING_TAIL
            )
        )
        state = apply(state, QueueAction.DeferOneRound("A", 2))
        state = apply(state, QueueAction.CancelDeferOneRound("A", 2))
        state = apply(state, QueueAction.TemporarilyLeave("A", 2))
        state = apply(state, QueueAction.CancelTemporaryLeave("A", 2))
        state = apply(state, QueueAction.TransferRegistrations("A", "B", setOf(2)))
        state = apply(
            state,
            QueueAction.AddRegistrations(
                "B",
                listOf(registration(3, PlayPreference.SOLO)),
                RegistrationPlacement.WAITING_TAIL
            )
        )
        state = apply(state, QueueAction.EnterPlayingPosition("B"), atMillis = 20_000L)

        val beforeFinish = state.queue("B")!!
        state = apply(state, QueueAction.FinishRound("B"), atMillis = 30_000L)
        val afterFinish = state.queue("B")!!
        assertEquals(listOf(3), afterFinish.playing.map(Registration::key))
        assertEquals(listOf(2), afterFinish.waiting.map(Registration::key))

        state = apply(
            state,
            QueueAction.RestoreSnapshot(
                machineId = "B",
                expectedCurrentQueue = afterFinish,
                restoredQueue = beforeFinish
            )
        )

        assertEquals(beforeFinish, state.queue("B"))
        assertEquals(listOf(1), state.queue("A")!!.playing.map(Registration::key))
        assertEquals(setOf(1, 2, 3), state.allRegistrations.map(Registration::key).toSet())
        assertEquals(3, state.allRegistrations.size)
    }

    @Test
    fun stoppedPendingCheckInRestartsItsWindowThenLeavesByTurnOrTimeout() {
        val pending = registration(2, PlayPreference.SOLO, pendingCheckIn = true)
        val available = registration(3, PlayPreference.SOLO)
        val source = QueueEngineState.single(
            "A",
            MachineQueue(
                playing = listOf(registration(1, PlayPreference.SOLO)),
                waiting = listOf(pending, available),
                playingStartedAtMillis = 5_000L
            )
        )
        val stoppedPolicy = QueueEnginePolicy(
            machineStatuses = mapOf(
                "A" to MachineStatus().stop(MachineStopReason.MAINTENANCE, 50_000L)
            ),
            requireOperationalForPlayerActions = true
        )
        val rejectedCheckIn = QueueEngine.execute(
            source,
            QueueAction.CheckIn("A", pending.key),
            QueueActionContext(
                atMillis = 60_000L,
                origin = QueueActionOrigin.ON_SITE_TERMINAL,
                policy = stoppedPolicy
            )
        )
        assertEquals(
            QueueActionFailureCode.MACHINE_STOPPED,
            (rejectedCheckIn as QueueActionExecution.Rejected).failure.code
        )

        val restarted = QueueEngine.execute(
            source,
            QueueAction.RestartMachineTimers("A"),
            QueueActionContext(
                atMillis = 100_000L,
                origin = QueueActionOrigin.SYSTEM,
                policy = stoppedPolicy
            )
        ) as QueueActionExecution.Applied
        val restartedQueue = restarted.state.queue("A")!!
        assertEquals(100_000L, restartedQueue.playingStartedAtMillis)
        assertEquals(
            100_000L,
            restartedQueue.waiting.first { it.key == pending.key }.onSiteCheckInStartedAtMillis
        )

        val restoredPolicy = stoppedPolicy.copy(
            machineStatuses = mapOf("A" to MachineStatus())
        )
        val reachedTurn = QueueEngine.execute(
            restarted.state,
            QueueAction.FinishRound("A"),
            QueueActionContext(atMillis = 110_000L, policy = restoredPolicy)
        ) as QueueActionExecution.Applied
        assertFalse(reachedTurn.state.allRegistrations.any { it.key == pending.key })
        assertEquals(
            listOf(available.key),
            reachedTurn.state.queue("A")!!.playing.map(Registration::key)
        )

        val timedOut = QueueEngine.execute(
            restarted.state,
            QueueAction.RemoveExpiredOnlineRegistrations("A"),
            QueueActionContext(
                atMillis = 100_000L + ONLINE_REGISTRATION_CHECK_IN_TIMEOUT_MILLIS,
                origin = QueueActionOrigin.SYSTEM,
                policy = restoredPolicy
            )
        ) as QueueActionExecution.Applied
        assertFalse(timedOut.state.allRegistrations.any { it.key == pending.key })
        assertEquals(listOf(1), timedOut.state.queue("A")!!.playing.map(Registration::key))
        assertEquals(listOf(available.key), timedOut.state.queue("A")!!.waiting.map(Registration::key))
    }

    @Test
    fun comingBackAndLeavingAgainDoesNotStartTheMissedTurnsAnew() {
        var state = QueueEngineState.single(
            "A",
            MachineQueue(
                playing = listOf(registration(1, PlayPreference.SOLO)),
                waiting = listOf(
                    registration(9, PlayPreference.SOLO).copy(
                        absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                        temporaryAwaySkippedTurns = 3
                    ),
                    registration(2, PlayPreference.SOLO)
                ),
                playingStartedAtMillis = 1L
            )
        )

        state = apply(state, QueueAction.CancelTemporaryLeave("A", 9))
        assertEquals(3, state.queue("A")!!.waiting.first { it.key == 9 }.temporaryAwaySkippedTurns)
        state = apply(state, QueueAction.TemporarilyLeave("A", 9))
        assertEquals(3, state.queue("A")!!.waiting.first { it.key == 9 }.temporaryAwaySkippedTurns)

        // It is reached for the fourth time while away and leaves the queue.
        state = apply(state, QueueAction.FinishRound("A"))
        assertTrue(state.queue("A")!!.allRegistrations.none { it.key == 9 })
    }

    @Test
    fun missedTurnsAndNoShowsAreBothClearedByPlayingARoundAndByNothingElse() {
        var state = QueueEngineState.single(
            "A",
            MachineQueue(
                playing = listOf(registration(1, PlayPreference.SOLO)),
                waiting = listOf(
                    registration(9, PlayPreference.SOLO).copy(
                        absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                        temporaryAwaySkippedTurns = 2,
                        noShowCount = 1
                    ),
                    registration(2, PlayPreference.SOLO)
                ),
                playingStartedAtMillis = 1L
            )
        )
        fun player() = state.queue("A")!!.allRegistrations.first { it.key == 9 }

        state = apply(state, QueueAction.CancelTemporaryLeave("A", 9))
        state = apply(state, QueueAction.DeferOneRound("A", 9))
        state = apply(state, QueueAction.CancelDeferOneRound("A", 9))
        assertEquals(2, player().temporaryAwaySkippedTurns)
        assertEquals(1, player().noShowCount)

        // Entering the playing position clears neither; being taken back out keeps both.
        state = apply(state, QueueAction.FinishRound("A"))
        assertEquals(listOf(9), state.queue("A")!!.playing.map { it.key })
        assertEquals(2, player().temporaryAwaySkippedTurns)
        assertEquals(1, player().noShowCount)
        state = apply(state, QueueAction.ReturnPlayingToWaitingFront("A", setOf(9)))
        assertEquals(2, player().temporaryAwaySkippedTurns)
        assertEquals(1, player().noShowCount)

        // The end of a round it played clears both.
        state = apply(state, QueueAction.EnterPlayingPosition("A"))
        state = apply(state, QueueAction.FinishRound("A"))
        assertEquals(0, player().temporaryAwaySkippedTurns)
        assertEquals(0, player().noShowCount)
    }

    @Test
    fun aCheckedInOnlineRegistrationThatMissesItsFirstTurnCanOnlyBeRemoved() {
        var state = QueueEngineState.single(
            "A",
            MachineQueue(
                playing = listOf(registration(1, PlayPreference.SOLO)),
                waiting = listOf(
                    registration(9, PlayPreference.SOLO, pendingCheckIn = true),
                    registration(2, PlayPreference.SOLO)
                ),
                playingStartedAtMillis = 1L
            )
        )
        // Anybody on site can do this for the player.
        state = apply(state, QueueAction.CheckIn("A", 9))
        assertTrue(state.queue("A")!!.waiting.first().mustBeRemovedWhenAbsent)

        listOf(NoShowResolution.DEFER_ONE_ROUND, NoShowResolution.MOVE_TO_TAIL).forEach { resolution ->
            val execution = QueueEngine.execute(
                state,
                QueueAction.MarkNoShow("A", setOf(9), resolution),
                QueueActionContext(atMillis = 100_000L)
            )
            assertEquals(
                QueueActionFailureCode.NO_SHOW_REMOVAL_ONLY,
                (execution as QueueActionExecution.Rejected).failure.code
            )
        }
        val removed = apply(state, QueueAction.MarkNoShow("A", setOf(9), NoShowResolution.REMOVE))
        assertTrue(removed.queue("A")!!.allRegistrations.none { it.key == 9 })

        // Once it has played a round the ordinary choices are back.
        state = apply(state, QueueAction.FinishRound("A"))
        assertEquals(listOf(9), state.queue("A")!!.playing.map { it.key })
        state = apply(state, QueueAction.FinishRound("A"))
        state = apply(state, QueueAction.FinishRound("A"))
        assertEquals(9, state.queue("A")!!.waiting.first().key)
        assertFalse(state.queue("A")!!.waiting.first().mustBeRemovedWhenAbsent)
        state = apply(state, QueueAction.MarkNoShow("A", setOf(9), NoShowResolution.MOVE_TO_TAIL))
        assertEquals(1, state.queue("A")!!.waiting.last().noShowCount)
    }

    @Test
    fun registrationsMadeOnSiteKeepEveryNoShowChoiceOnTheirFirstTurn() {
        val state = QueueEngineState.single(
            "A",
            MachineQueue(
                playing = listOf(registration(1, PlayPreference.SOLO)),
                waiting = listOf(registration(9, PlayPreference.SOLO), registration(2, PlayPreference.SOLO)),
                playingStartedAtMillis = 1L
            )
        )
        val deferred = apply(state, QueueAction.MarkNoShow("A", setOf(9), NoShowResolution.DEFER_ONE_ROUND))
        assertEquals(QueueAbsenceStatus.DEFER_ONE_ROUND, deferred.queue("A")!!.waiting.first().absenceStatus)
        val moved = apply(state, QueueAction.MarkNoShow("A", setOf(9), NoShowResolution.MOVE_TO_TAIL))
        assertEquals(9, moved.queue("A")!!.waiting.last().key)
    }

    @Test
    fun deferringAFixedPairIsRefusedWhileOneOfTheTwoHasToBeRemovedWhenAbsent() {
        val online = registration(9).copy(fixedPartnerKey = 8, wasOnlineRegistration = true)
        val partner = registration(8).copy(fixedPartnerKey = 9)
        val state = QueueEngineState.single(
            "A",
            MachineQueue(
                playing = listOf(registration(1, PlayPreference.SOLO)),
                waiting = listOf(partner, online, registration(2, PlayPreference.SOLO)),
                playingStartedAtMillis = 1L
            )
        )

        // Marked for the partner alone, a deferral would still defer both.
        val deferred = QueueEngine.execute(
            state,
            QueueAction.MarkNoShow("A", setOf(8), NoShowResolution.DEFER_ONE_ROUND),
            QueueActionContext(atMillis = 100_000L)
        )
        assertEquals(
            QueueActionFailureCode.NO_SHOW_REMOVAL_ONLY,
            (deferred as QueueActionExecution.Rejected).failure.code
        )
        val group = QueueEngine.execute(
            state,
            QueueAction.MarkNoShow("A", setOf(8, 9), NoShowResolution.DEFER_GROUP_ONE_ROUND),
            QueueActionContext(atMillis = 100_000L)
        )
        assertEquals(
            QueueActionFailureCode.NO_SHOW_REMOVAL_ONLY,
            (group as QueueActionExecution.Rejected).failure.code
        )
        // The partner, who registered on site, can still be moved to the tail alone.
        val moved = apply(state, QueueAction.MarkNoShow("A", setOf(8), NoShowResolution.MOVE_TO_TAIL))
        assertEquals(8, moved.queue("A")!!.waiting.last().key)
    }
}
