package com.abcccc.maimaiqueue

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Forming a fixed pair concerns the two who form it: everybody else keeps their order. */
class FriendPairOrderTest {
    private val open = PlayPreference.OPEN_TO_JOIN
    private val solo = PlayPreference.SOLO

    private fun registration(key: Int, preference: PlayPreference) = Registration(
        key = key,
        displayId = "玩家$key",
        preference = preference,
        isTemporary = false,
        createdAtMillis = key * 1_000L
    )

    private fun MachineQueue.positionKeys(): List<List<Int>> =
        waitingPositions().map { position -> position.map(Registration::key) }

    private fun MachineQueue.paired(firstKey: Int, secondKey: Int): MachineQueue {
        val execution = QueueEngine.execute(
            QueueEngineState.single("A", this),
            QueueAction.CreateFixedPair("A", firstKey, secondKey),
            QueueActionContext(atMillis = 5_000L)
        )
        assertTrue("$execution", execution is QueueActionExecution.Applied)
        return (execution as QueueActionExecution.Applied).state.queue("A")!!
    }

    @Test
    fun anOpenRegistrationAtTheTailDoesNotPassSoloRegistrationsWhenTwoOthersPairUp() {
        val queue = MachineQueue(
            playing = listOf(registration(10, solo)),
            waiting = listOf(
                registration(1, open),
                registration(2, solo),
                registration(3, solo),
                registration(4, solo),
                registration(5, solo),
                registration(6, open)
            ),
            playingStartedAtMillis = 1L
        )

        val result = queue.paired(4, 5)

        // 6 used to be moved up beside 1, past 2 and 3.
        assertEquals(
            listOf(listOf(1), listOf(2), listOf(3), listOf(4, 5), listOf(6)),
            result.positionKeys()
        )
    }

    @Test
    fun twoRegistrationsSharingAPositionAcrossAPassedOneBecomeAPairWhereTheyAreShown() {
        val queue = MachineQueue(
            playing = listOf(registration(10, solo)),
            waiting = listOf(
                registration(1, open),
                registration(2, solo).copy(allowsPassing = true),
                registration(3, open),
                registration(4, solo)
            ),
            playingStartedAtMillis = 1L
        )
        assertEquals(listOf(listOf(1, 3), listOf(2), listOf(4)), queue.positionKeys())

        // This used to change nothing at all: the two were taken to wait side by side.
        val result = queue.paired(1, 3)

        assertEquals(listOf(1, 3, 2, 4), result.waiting.map(Registration::key))
        assertEquals(listOf(listOf(1, 3), listOf(2), listOf(4)), result.positionKeys())
        assertEquals(listOf<Int?>(3, 1), result.waiting.take(2).map(Registration::fixedPartnerKey))
        assertTrue(result.invariantViolations().joinToString(), result.invariantViolations().isEmpty())
    }

    @Test
    fun aPairDoesNotCostAPostponedRegistrationBetweenThemARound() {
        // 2 lets one group go first: 1 and 3 play together, then 2.
        val queue = MachineQueue(
            playing = listOf(registration(10, solo)),
            waiting = listOf(
                registration(1, open),
                registration(2, open).copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND),
                registration(3, open),
                registration(4, solo)
            ),
            playingStartedAtMillis = 1L
        )
        val plan = queue.planFriendPair(1, 3)!!
        assertTrue(plan.delayedOtherRegistrations.isEmpty())
        assertTrue("两人的游玩轮次不变", plan.movedBackRegistrations.isEmpty())

        // Side by side ahead of 2 they would play, and 2 would then let yet another group
        // go first. The pair stands behind 2, which is passed by it exactly as before.
        val result = queue.paired(1, 3)
        assertEquals(listOf(2, 1, 3, 4), result.waiting.map(Registration::key))
        val first = result.finishRound(2_000_000L)
        assertEquals(listOf(1, 3), first.playing.map(Registration::key))
        val second = first.finishRound(3_000_000L)
        assertEquals(listOf(2), second.playing.map(Registration::key))
    }

    @Test
    fun aPairIsNeverPlacedBetweenTheTwoOfAnotherPair() {
        val random = Random(20261127)
        repeat(3_000) {
            val waiting = mutableListOf<Registration>()
            var key = 1
            while (waiting.size < 9) {
                if (random.nextInt(4) == 0 && waiting.size <= 7) {
                    waiting += registration(key, open).copy(fixedPartnerKey = key + 1)
                    waiting += registration(key + 1, open).copy(fixedPartnerKey = key)
                    key += 2
                } else {
                    val absence = when (random.nextInt(8)) {
                        0 -> QueueAbsenceStatus.DEFER_ONE_ROUND
                        1 -> QueueAbsenceStatus.SKIP_ONE_TURN
                        else -> QueueAbsenceStatus.NONE
                    }
                    val preference = if (random.nextBoolean()) open else solo
                    waiting += registration(key++, preference).copy(
                        absenceStatus = absence,
                        allowsPassing = preference == solo && random.nextBoolean()
                    )
                }
            }
            val queue = MachineQueue(waiting = waiting)
            val candidates = waiting.filter { it.absenceStatus == QueueAbsenceStatus.NONE }
            if (candidates.size < 2) return@repeat
            val (a, b) = candidates.shuffled(random)
            val plan = queue.planFriendPair(a.key, b.key) ?: return@repeat
            val pairKeys = setOf(a.key, b.key)
            waiting.filter { it.fixedPartnerKey != null && it.key !in pairKeys && it.fixedPartnerKey !in pairKeys }
                .forEach { member ->
                    val index = plan.waiting.indexOfFirst { it.key == member.key }
                    val partnerIndex = plan.waiting.indexOfFirst { it.key == member.fixedPartnerKey }
                    assertEquals(
                        "${waiting.map { it.key to it.fixedPartnerKey }} + pair $pairKeys",
                        1,
                        kotlin.math.abs(index - partnerIndex)
                    )
                }
        }
    }

    @Test
    fun registrationsThatLoseTheirPartnerToThePairAreGroupedAgainInTheirOwnOrder() {
        val queue = MachineQueue(
            waiting = (1..4).map { registration(it, open) }
        )
        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), queue.positionKeys())

        val plan = requireNotNull(queue.planFriendPair(1, 3))
        val result = queue.paired(1, 3)

        // 2 and 4 are left without a partner; they play together, and no later than before.
        assertEquals(listOf(listOf(2, 4), listOf(1, 3)), result.positionKeys())
        assertEquals(emptyList<Registration>(), plan.delayedOtherRegistrations)
        assertEquals(listOf(1), plan.movedBackRegistrations.map(Registration::key))
    }

    @Test
    fun thePairGoesBehindRatherThanLettingAnotherRegistrationPassASoloOne() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, open),
                registration(2, open),
                registration(3, solo),
                registration(4, open),
                registration(5, open)
            )
        )
        assertEquals(listOf(listOf(1, 2), listOf(3), listOf(4, 5)), queue.positionKeys())

        val plan = requireNotNull(queue.planFriendPair(2, 5))
        val result = queue.paired(2, 5)

        // 4 does not join 1 across the solo registration 3. It keeps its round, and the pair
        // follows it.
        assertEquals(listOf(listOf(1), listOf(3), listOf(4), listOf(2, 5)), result.positionKeys())
        assertEquals(emptyList<Registration>(), plan.delayedOtherRegistrations)
        assertEquals(setOf(2, 5), plan.movedBackRegistrations.mapTo(mutableSetOf(), Registration::key))
    }

    @Test
    fun noPairingEverChangesTheOrderOfTheOthersOrMakesThemPlayLater() {
        val random = Random(20261124)
        var applied = 0
        repeat(4_000) {
            val size = random.nextInt(2, 13)
            val waiting = (1..size).map { key ->
                val registration = registration(key, if (random.nextBoolean()) open else solo)
                when (random.nextInt(8)) {
                    0 -> registration.copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND)
                    1 -> registration.copy(absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY)
                    else -> registration
                }
            }
            val queue = MachineQueue(waiting = waiting)
            val first = random.nextInt(1, size + 1)
            val second = (1..size).filterNot { it == first }.random(random)
            val plan = queue.planFriendPair(first, second) ?: return@repeat
            if (plan.delayedOtherRegistrations.isNotEmpty()) return@repeat
            val result = queue.applyFriendPair(plan)
            if (result == queue) return@repeat
            applied++

            val pairKeys = setOf(first, second)
            assertEquals(
                "$waiting with $first and $second",
                waiting.map(Registration::key).filterNot { it in pairKeys },
                result.waiting.map(Registration::key).filterNot { it in pairKeys }
            )
            val positionBefore = queue.positionKeys().withIndex()
                .flatMap { (index, keys) -> keys.map { it to index } }.toMap()
            val positionAfter = result.positionKeys().withIndex()
                .flatMap { (index, keys) -> keys.map { it to index } }.toMap()
            positionAfter.filterKeys { it !in pairKeys }.forEach { (key, index) ->
                assertTrue(
                    "$waiting with $first and $second: $key plays later",
                    index <= positionBefore.getValue(key)
                )
            }
            assertTrue(result.invariantViolations().joinToString(), result.invariantViolations().isEmpty())
        }
        assertTrue("only $applied pairings were applied", applied > 1_000)
    }
}
