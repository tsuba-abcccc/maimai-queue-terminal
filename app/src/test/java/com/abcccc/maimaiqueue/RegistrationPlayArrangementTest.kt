package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationPlayArrangementTest {
    @Test
    fun realPlayingPairReportsOnlyTheOtherCurrentPlayer() {
        val first = registration(1)
        val second = registration(2)
        val queue = MachineQueue(
            playing = listOf(first, second),
            waiting = listOf(registration(3)),
            playingStartedAtMillis = 100L
        )

        val arrangement = arrangement(queue, first.key)

        assertTrue(arrangement.isPlayingPosition)
        assertEquals(second.displayId, arrangement.playingPartnerDisplayId)
        assertNull(arrangement.waitingPartnerDisplayId)
        assertNull(arrangement.commonPlayPreviewDisplayId)
    }

    @Test
    fun ordinaryWaitingRegistrationNeverInheritsTheCurrentPlayingPlayer() {
        val current = registration(1)
        val waiting = registration(2, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(waiting),
            playingStartedAtMillis = 100L
        )

        val arrangement = arrangement(queue, waiting.key)

        assertFalse(arrangement.isPlayingPosition)
        assertNull(arrangement.playingPartnerDisplayId)
    }

    @Test
    fun deferredWaitingRegistrationUsesOnlyItsProjectedArrangement() {
        val current = registration(1)
        val deferred = registration(2).copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(deferred, registration(3), registration(4, PlayPreference.SOLO)),
            playingStartedAtMillis = 100L
        )
        val projectedPosition = queue.waitingProjection().positions.single { position ->
            position.registrations.any { it.key == deferred.key }
        }

        val arrangement = arrangement(queue, deferred.key)

        assertFalse(arrangement.isPlayingPosition)
        assertNull(arrangement.playingPartnerDisplayId)
        assertEquals(
            projectedPosition.registrations.firstOrNull { it.key != deferred.key }?.displayId,
            arrangement.waitingPartnerDisplayId
        )
        assertEquals(
            projectedPosition.commonPlayPreview?.displayId,
            arrangement.commonPlayPreviewDisplayId
        )
    }

    @Test
    fun pendingCheckInRegistrationRemainsAWaitingArrangement() {
        val pending = registration(2).copy(
            requiresOnSiteCheckIn = true,
            onSiteCheckInStartedAtMillis = 1_000L
        )
        val queue = MachineQueue(
            playing = listOf(registration(1)),
            waiting = listOf(pending, registration(3)),
            playingStartedAtMillis = 100L
        )

        val arrangement = arrangement(queue, pending.key)

        assertFalse(arrangement.isPlayingPosition)
        assertNull(arrangement.playingPartnerDisplayId)
        assertEquals("玩家-3", arrangement.waitingPartnerDisplayId)
    }

    @Test
    fun waitingPairRemainsDistinctFromCurrentPlaying() {
        val current = registration(1)
        val firstWaiting = registration(2)
        val secondWaiting = registration(3)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(firstWaiting, secondWaiting),
            playingStartedAtMillis = 100L
        )

        val paired = arrangement(queue, firstWaiting.key)

        assertNull(paired.playingPartnerDisplayId)
        assertEquals(secondWaiting.displayId, paired.waitingPartnerDisplayId)
        assertNull(paired.commonPlayPreviewDisplayId)
    }

    @Test
    fun commonPlayPreviewRemainsDistinctFromARealWaitingPartner() {
        val current = registration(1)
        val waiting = registration(2)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(waiting),
            playingStartedAtMillis = 100L
        )

        val previewed = arrangement(queue, waiting.key)

        assertNull(previewed.playingPartnerDisplayId)
        assertNull(previewed.waitingPartnerDisplayId)
        assertEquals(current.displayId, previewed.commonPlayPreviewDisplayId)
    }

    private fun arrangement(
        queue: MachineQueue,
        registrationKey: Int
    ): RegistrationPlayArrangement = requireNotNull(
        registrationPlayArrangement(
            queue = queue,
            registrationKey = registrationKey,
            includeCommonPlayPreview = true
        )
    )

    private fun registration(
        key: Int,
        preference: PlayPreference = PlayPreference.OPEN_TO_JOIN
    ) = Registration(
        key = key,
        displayId = "玩家-$key",
        preference = preference,
        createdAtMillis = 100L
    )
}
