package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 暂缓一次, 轮空一次 and 暂时离开 as the maintainer set them on 2026-11-25. */
class PostponeRulesTest {
    private val policy = QueueEnginePolicy(requireOperationalForPlayerActions = true)

    private fun registration(
        key: Int,
        absence: QueueAbsenceStatus = QueueAbsenceStatus.NONE,
        skippedTurns: Int = 0,
        deferUsed: Boolean = false,
        onlineNotPlayed: Boolean = false,
        fixedPartnerKey: Int? = null
    ) = Registration(
        key = key,
        displayId = "玩家$key",
        preference = PlayPreference.SOLO,
        absenceStatus = absence,
        temporaryAwaySkippedTurns = skippedTurns,
        createdAtMillis = key * 1_000L,
        deferOneRoundUsed = deferUsed,
        wasOnlineRegistration = onlineNotPlayed,
        fixedPartnerKey = fixedPartnerKey
    )

    private fun stateOf(queue: MachineQueue) = QueueEngineState(linkedMapOf("A" to queue))

    private fun execute(
        state: QueueEngineState,
        action: QueueAction,
        origin: QueueActionOrigin = QueueActionOrigin.ON_SITE_TERMINAL
    ) = QueueEngine.execute(state, action, QueueActionContext(atMillis = NOW, origin = origin, policy = policy))

    private fun failureOf(execution: QueueActionExecution) =
        (execution as? QueueActionExecution.Rejected)?.failure?.code

    @Test
    fun skippingOneTurnGoesToTheTailAndCountsATurn() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, QueueAbsenceStatus.SKIP_ONE_TURN, skippedTurns = 1),
                registration(2),
                registration(3)
            )
        ).enterPlayingPosition()

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertEquals(listOf(3, 1), queue.waiting.map { it.key })
        val skipped = queue.waiting.last()
        assertEquals(QueueAbsenceStatus.NONE, skipped.absenceStatus)
        assertEquals(2, skipped.temporaryAwaySkippedTurns)
    }

    @Test
    fun withThreeSkippedTurnsTheNextTurnRemovesTheRegistration() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, QueueAbsenceStatus.SKIP_ONE_TURN, skippedTurns = 3),
                registration(2)
            )
        ).enterPlayingPosition()

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertTrue(queue.waiting.isEmpty())
    }

    @Test
    fun aFixedPairSkipsTogether() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, QueueAbsenceStatus.SKIP_ONE_TURN, fixedPartnerKey = 2),
                registration(2, QueueAbsenceStatus.SKIP_ONE_TURN, fixedPartnerKey = 1),
                registration(3),
                registration(4)
            )
        ).enterPlayingPosition()

        assertEquals(listOf(3), queue.playing.map { it.key })
        assertEquals(listOf(4, 1, 2), queue.waiting.map { it.key })
        assertTrue(queue.waiting.drop(1).all { it.temporaryAwaySkippedTurns == 1 })
    }

    @Test
    fun skippingCanBeSetAndCancelled() {
        val state = stateOf(MachineQueue(playing = listOf(registration(9)), waiting = listOf(registration(1), registration(2))))
        val set = execute(state, QueueAction.SkipOneTurn("A", 1)) as QueueActionExecution.Applied
        assertEquals(QueueAbsenceStatus.SKIP_ONE_TURN, set.state.queue("A")!!.waiting.first().absenceStatus)
        assertEquals(
            QueueActionFailureCode.WRONG_ABSENCE_STATE,
            failureOf(execute(set.state, QueueAction.DeferOneRound("A", 1)))
        )
        val cancelled = execute(set.state, QueueAction.CancelSkipOneTurn("A", 1)) as QueueActionExecution.Applied
        assertEquals(QueueAbsenceStatus.NONE, cancelled.state.queue("A")!!.waiting.first().absenceStatus)
    }

    @Test
    fun deferringOnceIsMarkedAndClearedOnlyByPlaying() {
        // The deferral is used when its opportunity is skipped.
        var queue = MachineQueue(
            waiting = listOf(registration(1, QueueAbsenceStatus.DEFER_ONE_ROUND), registration(2), registration(3))
        ).enterPlayingPosition()
        val deferred = queue.waiting.first { it.key == 1 }
        assertTrue(deferred.deferOneRoundUsed)
        assertEquals(QueueAbsenceStatus.NONE, deferred.absenceStatus)

        assertEquals(
            QueueActionFailureCode.DEFER_ONE_ROUND_ALREADY_USED,
            failureOf(execute(stateOf(queue), QueueAction.DeferOneRound("A", 1)))
        )
        // 轮空一次 is still there.
        assertTrue(execute(stateOf(queue), QueueAction.SkipOneTurn("A", 1)) is QueueActionExecution.Applied)

        // After playing a round the mark is gone.
        queue = queue.finishRound(NOW)
        assertEquals(listOf(1), queue.playing.map { it.key })
        queue = queue.finishRound(NOW + 1)
        assertFalse(queue.allRegistrations.first { it.key == 1 }.deferOneRoundUsed)
    }

    @Test
    fun aNoShowCannotBeSettledByDeferringASecondTime() {
        val state = stateOf(MachineQueue(waiting = listOf(registration(1, deferUsed = true), registration(2))))
        assertEquals(
            QueueActionFailureCode.DEFER_ONE_ROUND_ALREADY_USED,
            failureOf(
                execute(state, QueueAction.MarkNoShow("A", setOf(1), NoShowResolution.DEFER_ONE_ROUND))
            )
        )
        assertTrue(
            execute(state, QueueAction.MarkNoShow("A", setOf(1), NoShowResolution.MOVE_TO_TAIL))
                is QueueActionExecution.Applied
        )
    }

    @Test
    fun aCheckedInOnlineRegistrationThatHasNotPlayedCannotBePostponed() {
        val state = stateOf(
            MachineQueue(
                playing = listOf(registration(9)),
                waiting = listOf(registration(1, onlineNotPlayed = true), registration(2))
            )
        )
        listOf(
            QueueAction.DeferOneRound("A", 1),
            QueueAction.SkipOneTurn("A", 1),
            QueueAction.TemporarilyLeave("A", 1)
        ).forEach { action ->
            assertEquals(
                action.toString(),
                QueueActionFailureCode.ONLINE_REGISTRATION_NOT_PLAYED,
                failureOf(execute(state, action))
            )
            assertEquals(
                QueueActionFailureCode.ONLINE_REGISTRATION_NOT_PLAYED,
                failureOf(execute(state, action, QueueActionOrigin.WEBSITE))
            )
            // The management app may.
            assertTrue(
                action.toString(),
                execute(state, action, QueueActionOrigin.MANAGEMENT_APP) is QueueActionExecution.Applied
            )
        }
    }

    private companion object {
        const val NOW = 10_000_000L
    }
}
