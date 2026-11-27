package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Test

/** Moving a whole waiting position changes the place of its registrations only. */
class WaitingPositionMoveTest {
    private fun registration(key: Int, open: Boolean, absence: QueueAbsenceStatus = QueueAbsenceStatus.NONE) =
        Registration(
            key = key,
            displayId = "玩家$key",
            preference = if (open) PlayPreference.OPEN_TO_JOIN else PlayPreference.SOLO,
            absenceStatus = absence,
            isTemporary = true,
            createdAtMillis = key * 1_000L
        )

    // 2 and 4 pair across 3, which will defer one round: the positions are 2+4 | 3 | 5 | 6
    // while the real order is 2, 3, 4, 5, 6.
    private val interleaved = MachineQueue(
        playing = listOf(registration(1, open = true)),
        waiting = listOf(
            registration(2, open = true),
            registration(3, open = true, absence = QueueAbsenceStatus.DEFER_ONE_ROUND),
            registration(4, open = true),
            registration(5, open = false),
            registration(6, open = false)
        ),
        playingStartedAtMillis = 1L
    )

    private fun keys(queue: MachineQueue) = queue.waiting.map(Registration::key)
    private fun positionOf(queue: MachineQueue, key: Int) =
        queue.waitingPositions().indexOfFirst { position -> position.any { it.key == key } }

    @Test
    fun anUnrelatedMoveKeepsTheRealOrderOfAnInterleavedPosition() {
        assertEquals(listOf(listOf(2, 4), listOf(3), listOf(5), listOf(6)),
            interleaved.waitingPositions().map { position -> position.map(Registration::key) })
        val moved = interleaved.moveWaitingPosition(positionOf(interleaved, 6), positionOf(interleaved, 5))
        // Only 6 moved; 3 stays between 2 and 4.
        assertEquals(listOf(2, 3, 4, 6, 5), keys(moved))
    }

    @Test
    fun anInterleavedPositionMovesWithoutTakingWhatLiesBetween() {
        val later = interleaved.moveWaitingPosition(positionOf(interleaved, 2), positionOf(interleaved, 5))
        assertEquals(listOf(3, 5, 2, 4, 6), keys(later))
        val earlier = interleaved.moveWaitingPosition(positionOf(interleaved, 6), positionOf(interleaved, 2))
        assertEquals(listOf(6, 2, 3, 4, 5), keys(earlier))
    }

    @Test
    fun contiguousPositionsMoveAsBefore() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, open = true),
                registration(2, open = true),
                registration(3, open = false),
                registration(4, open = true),
                registration(5, open = true)
            )
        )
        assertEquals(listOf(4, 5, 1, 2, 3), keys(queue.moveWaitingPosition(2, 0)))
        assertEquals(listOf(3, 1, 2, 4, 5), keys(queue.moveWaitingPosition(0, 1)))
        assertEquals(listOf(3, 4, 5, 1, 2), keys(queue.moveWaitingPosition(0, 2)))
    }
}
