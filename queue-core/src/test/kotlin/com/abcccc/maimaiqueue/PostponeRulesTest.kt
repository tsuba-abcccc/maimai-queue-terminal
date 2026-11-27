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

    // ---- whose turn a round is (maintainer, 2026-11-27) -----------------------------------

    private fun open(key: Int, absence: QueueAbsenceStatus = QueueAbsenceStatus.NONE) =
        registration(key, absence).copy(preference = PlayPreference.OPEN_TO_JOIN)

    private fun MachineQueue.playingKeys() = playing.map(Registration::key)

    @Test
    fun deferringAlwaysBuysARoundEvenWhenTwoOthersPairAcrossIt() {
        // Without the deferral: 1 plays alone, then 2, then 3, then 4.
        val queue = MachineQueue(
            playing = listOf(registration(9)),
            waiting = listOf(open(1), registration(2, QueueAbsenceStatus.DEFER_ONE_ROUND), open(3), registration(4)),
            playingStartedAtMillis = 1L
        )
        // 1 and 3 pair across 2. That round was not the turn of 2, so its deferral is unused.
        val first = queue.finishRound(NOW)
        assertEquals(listOf(1, 3), first.playingKeys())
        val waitingTwo = first.waiting.first()
        assertEquals(2, waitingTwo.key)
        assertEquals(QueueAbsenceStatus.DEFER_ONE_ROUND, waitingTwo.absenceStatus)
        assertFalse(waitingTwo.deferOneRoundUsed)
        // The next round is its turn: it lets 4 go first, and plays the round after.
        val second = first.finishRound(NOW + 1)
        assertEquals(listOf(4), second.playingKeys())
        assertTrue(second.waiting.first().let { it.key == 2 && it.deferOneRoundUsed })
        assertEquals(listOf(2), second.finishRound(NOW + 2).playingKeys())
    }

    @Test
    fun aPostponedRegistrationThatWouldHaveSharedTheRoundIsReached() {
        // 1 and 2 would have played together; 2 defers, so 1 plays with 4 across 2 and 3.
        val queue = MachineQueue(
            playing = listOf(registration(9)),
            waiting = listOf(
                open(1),
                open(2, QueueAbsenceStatus.DEFER_ONE_ROUND),
                open(3, QueueAbsenceStatus.DEFER_ONE_ROUND),
                open(4),
                registration(5)
            ),
            playingStartedAtMillis = 1L
        )
        val first = queue.finishRound(NOW)
        assertEquals(listOf(1, 4), first.playingKeys())
        // It was the turn of 2, not yet that of 3.
        assertTrue(first.waiting[0].let { it.key == 2 && it.deferOneRoundUsed })
        assertTrue(
            first.waiting[1].let {
                it.key == 3 && it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND && !it.deferOneRoundUsed
            }
        )
        // Each of them plays exactly one round later than without the deferral.
        val second = first.finishRound(NOW + 1)
        assertEquals(listOf(2), second.playingKeys())
        assertTrue(second.waiting.first().let { it.key == 3 && it.deferOneRoundUsed })
        assertEquals(listOf(3), second.finishRound(NOW + 2).playingKeys())
    }

    @Test
    fun aPendingOnlineRegistrationStaysUntilItsOwnTurn() {
        val pending = registration(2).copy(
            requiresOnSiteCheckIn = true,
            onSiteCheckInStartedAtMillis = NOW,
            wasOnlineRegistration = true
        )
        val queue = MachineQueue(
            playing = listOf(registration(9)),
            waiting = listOf(open(1), pending, open(3), registration(4)),
            playingStartedAtMillis = 1L
        )
        // The home page shows 2 in the second position.
        val shown = queue.waitingProjection(includeCommonPlayPreview = false).positions
        assertEquals(1, shown.indexOfFirst { position -> position.registrations.any { it.key == 2 } })
        val first = queue.finishRound(NOW)
        assertEquals(listOf(1, 3), first.playingKeys())
        assertTrue("轮到之前不移除", first.waiting.first().let { it.key == 2 && it.requiresOnSiteCheckIn })
        // Its own turn: still not checked in, it leaves and 4 plays.
        val second = first.finishRound(NOW + 1)
        assertEquals(listOf(4), second.playingKeys())
        assertTrue(second.allRegistrations.none { it.key == 2 })
    }

    @Test
    fun missedTurnsSharedByAFixedPairDoNotStayWithThePartnerAfterTheRelease() {
        var state = stateOf(
            MachineQueue(
                playing = listOf(registration(9)),
                waiting = listOf(open(1).copy(temporaryAwaySkippedTurns = 2), open(2), registration(3)),
                playingStartedAtMillis = 1L
            )
        )
        fun waiting(key: Int) = state.queue("A")!!.waiting.first { it.key == key }
        state = (execute(state, QueueAction.CreateFixedPair("A", 1, 2)) as QueueActionExecution.Applied).state
        // As a pair the two share the larger count.
        assertEquals(listOf(2, 2), listOf(waiting(1), waiting(2)).map(Registration::temporaryAwaySkippedTurns))

        // The pair misses a turn together.
        state = (execute(state, QueueAction.SkipOneTurn("A", 1)) as QueueActionExecution.Applied).state
        state = (execute(state, QueueAction.FinishRound("A")) as QueueActionExecution.Applied).state
        assertEquals(listOf(3, 3), listOf(waiting(1), waiting(2)).map(Registration::temporaryAwaySkippedTurns))

        // Released, each counts its own: 1 had two and one more, 2 only the one they shared.
        state = (
            execute(state, QueueAction.ChangePreference("A", 1, PlayPreference.SOLO)) as QueueActionExecution.Applied
            ).state
        assertEquals(3, waiting(1).temporaryAwaySkippedTurns)
        assertEquals(1, waiting(2).temporaryAwaySkippedTurns)
        assertEquals(0, waiting(2).skippedTurnsCarriedFromPartner)
    }
}
