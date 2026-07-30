package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundPlannerTest {
    @Test
    fun finishPlanPreviewMatchesExecutedPlayersAcrossMixedAvailabilityStates() {
        val current = registration(9, PlayPreference.SOLO)
        val pendingCheckIn = registration(1, PlayPreference.SOLO).copy(
            requiresOnSiteCheckIn = true
        )
        val deferred = registration(2, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val temporarilyAway = registration(3, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
        )
        val available = registration(4, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(pendingCheckIn, deferred, temporarilyAway, available),
            playingStartedAtMillis = 100L
        )

        val plan = RoundPlanner.finishRound(queue)
        val result = plan.execute(atMillis = 5_000L)

        assertEquals(listOf(4), plan.preview?.nextRegistrations?.map { it.key })
        assertEquals(listOf(4), result.playing.map { it.key })
        assertTrue(result.allRegistrations.none { it.key == 1 })
        assertEquals(QueueAbsenceStatus.NONE, result.waiting.first { it.key == 2 }.absenceStatus)
        assertEquals(1, result.waiting.first { it.key == 3 }.temporaryAwaySkippedTurns)
        assertEquals(5_000L, result.waiting.first { it.key == 9 }.lastPlayedAtMillis)
        assertEquals(5_000L, result.playingStartedAtMillis)
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun removalPlanPreviewMatchesExecutedPlayers() {
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(2, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 100L
        )

        val plan = RoundPlanner.removeCurrentRoundAndStartNext(queue)
        val result = plan.execute(atMillis = 6_000L)

        assertEquals(listOf(2), plan.preview?.nextRegistrations?.map { it.key })
        assertEquals(listOf(2), result.playing.map { it.key })
        assertTrue(result.allRegistrations.none { it.key == 9 })
        assertEquals(QueueAbsenceStatus.NONE, result.waiting.single().absenceStatus)
        assertEquals(6_000L, result.playingStartedAtMillis)
    }

    @Test
    fun endOnlyUsesConfirmationTimeWithoutStartingAnotherRound() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO)),
            playingStartedAtMillis = 100L
        )

        val result = RoundPlanner.endRoundOnly(queue).execute(atMillis = 7_000L)

        assertTrue(result.playing.isEmpty())
        assertEquals(listOf(2, 1), result.waiting.map { it.key })
        assertEquals(7_000L, result.waiting.last().lastPlayedAtMillis)
        assertNull(result.playingStartedAtMillis)
    }

    @Test
    fun stalePlanCannotOverwriteAChangedQueue() {
        val registration = registration(1, PlayPreference.SOLO)
        val queue = MachineQueue(waiting = listOf(registration))
        val plan = RoundPlanner.enterPlayingPosition(queue)
        val changedQueue = queue.copy(
            waiting = listOf(registration.copy(displayId = "更新后的昵称"))
        )

        assertNull(plan.applyTo(changedQueue, atMillis = 8_000L))
    }

    @Test
    fun temporarilyAwayTurnIsConsumedOnlyOnceByOnePlan() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                ),
                registration(2, PlayPreference.SOLO)
            )
        )
        val plan = RoundPlanner.enterPlayingPosition(queue)

        val result = plan.execute(atMillis = 9_000L)

        assertEquals(1, result.waiting.single().temporaryAwaySkippedTurns)
        assertNull(plan.applyTo(result, atMillis = 10_000L))
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun fixedPairRemainsTheSameGroupInPreviewAndExecution() {
        val first = registration(1).copy(fixedPartnerKey = 2)
        val second = registration(2).copy(fixedPartnerKey = 1)
        val queue = MachineQueue(
            waiting = listOf(
                registration(3, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                first,
                second
            )
        )

        val plan = RoundPlanner.enterPlayingPosition(queue)
        val result = plan.execute(atMillis = 11_000L)

        assertEquals(listOf(1, 2), plan.preview?.nextRegistrations?.map { it.key })
        assertEquals(listOf(1, 2), result.playing.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, result.waiting.single().absenceStatus)
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun noAvailableGroupStillAppliesEachUnavailableRule() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(requiresOnSiteCheckIn = true),
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                )
            )
        )

        val plan = RoundPlanner.enterPlayingPosition(queue)
        val result = plan.execute(atMillis = 12_000L)

        assertTrue(plan.preview?.nextRegistrations?.isEmpty() == true)
        assertEquals(setOf(1, 2), plan.preview?.unavailableRegistrations?.map { it.key }?.toSet())
        assertTrue(result.playing.isEmpty())
        assertEquals(listOf(2), result.waiting.map { it.key })
        assertEquals(1, result.waiting.single().temporaryAwaySkippedTurns)
        assertNull(result.playingStartedAtMillis)
    }

    @Test
    fun removalWithoutCurrentRoundDoesNotUnexpectedlyAdvance() {
        val queue = MachineQueue(waiting = listOf(registration(1, PlayPreference.SOLO)))

        val result = RoundPlanner.removeCurrentRoundAndStartNext(queue).execute(13_000L)

        assertEquals(queue, result)
    }

    private fun registration(
        key: Int,
        preference: PlayPreference = PlayPreference.OPEN_TO_JOIN
    ): Registration = Registration(
        key = key,
        displayId = "玩家-$key",
        preference = preference,
        createdAtMillis = 100L
    )
}
