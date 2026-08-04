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
}
