package com.abcccc.maimaiqueue

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 单人游玩 registration that lets others pass (docs/releases/solo-pass-plan-2026-11.md): the
 * open registrations around it pair across it, the real order does not change, and nobody,
 * the passed registration included, enters a playing position in a later round because of it.
 */
class PassingSoloRoundsTest {
    private fun registration(
        key: Int,
        open: Boolean,
        passable: Boolean = false,
        absence: QueueAbsenceStatus = QueueAbsenceStatus.NONE,
        partner: Int? = null,
        pendingCheckIn: Boolean = false
    ) = Registration(
        key = key,
        displayId = "玩家$key",
        preference = if (open || partner != null) PlayPreference.OPEN_TO_JOIN else PlayPreference.SOLO,
        absenceStatus = absence,
        isTemporary = true,
        createdAtMillis = key * 1_000L,
        fixedPartnerKey = partner,
        allowsPassing = passable,
        requiresOnSiteCheckIn = pendingCheckIn,
        onSiteCheckInStartedAtMillis = if (pendingCheckIn) 1_000L else null,
        wasOnlineRegistration = pendingCheckIn
    )

    private fun keys(positions: List<List<Registration>>) = positions.map { position -> position.map(Registration::key) }

    @Test
    fun theRegistrationsAroundAPassableSoloPairAcrossIt() {
        val waiting = listOf(registration(1, open = true), registration(2, open = false, passable = true), registration(3, open = true))
        val queue = MachineQueue(waiting = waiting)
        assertEquals(listOf(listOf(1, 3), listOf(2)), keys(queue.waitingPositions()))
        // The real order is unchanged.
        assertEquals(listOf(1, 2, 3), queue.waiting.map(Registration::key))
        // Without the choice nobody passes.
        val closed = MachineQueue(waiting = waiting.map { it.copy(allowsPassing = false) })
        assertEquals(listOf(listOf(1), listOf(2), listOf(3)), keys(closed.waitingPositions()))
    }

    @Test
    fun aSoloThatDoesNotAllowItStopsThePassing() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, open = true),
                registration(2, open = false, passable = true),
                registration(3, open = false),
                registration(4, open = true)
            )
        )
        assertEquals(listOf(listOf(1), listOf(2), listOf(3), listOf(4)), keys(queue.waitingPositions()))
    }

    @Test
    fun aFixedPairThatHasNotAllowedItIsNotPassedAndNeverPassesAcrossASolo() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, open = true),
                registration(2, open = false, passable = true),
                registration(3, open = true, partner = 4),
                registration(4, open = true, partner = 3),
                registration(5, open = true)
            )
        )
        assertEquals(listOf(listOf(1), listOf(2), listOf(3, 4), listOf(5)), keys(queue.waitingPositions()))
    }

    @Test
    fun theChoiceMeansNothingForAnOpenRegistration() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, open = true),
                registration(2, open = true).copy(allowsPassing = true),
                registration(3, open = true)
            )
        )
        assertEquals(listOf(listOf(1, 2), listOf(3)), keys(queue.waitingPositions()))
    }

    private val policy = QueueEnginePolicy(requireOperationalForPlayerActions = true)

    private fun execute(queue: MachineQueue, action: QueueAction) =
        QueueEngine.execute(QueueEngineState(linkedMapOf("A" to queue)), action, QueueActionContext(atMillis = 5_000L, policy = policy))

    private fun queueAfter(queue: MachineQueue, action: QueueAction): MachineQueue =
        (execute(queue, action) as QueueActionExecution.Applied).state.queue("A")!!

    // ---- a fixed pair that lets others pass (fixed-pair-pass-plan-2026-11.md) ----------------

    private fun passablePair(first: Int, second: Int) = listOf(
        registration(first, open = true, partner = second).copy(allowsPassing = true),
        registration(second, open = true, partner = first).copy(allowsPassing = true)
    )

    @Test
    fun aFixedPairThatAllowsItIsPassedLikeASolo() {
        val queue = MachineQueue(
            playing = listOf(registration(9, open = false)),
            waiting = listOf(registration(1, open = true)) + passablePair(3, 4) +
                registration(5, open = true) + registration(6, open = false),
            playingStartedAtMillis = 1_000L
        )
        assertEquals(listOf(listOf(1, 5), listOf(3, 4), listOf(6)), keys(queue.waitingPositions()))
        // The real order is unchanged, and the home page names both passed registrations.
        assertEquals(listOf(1, 3, 4, 5, 6), queue.waiting.map(Registration::key))
        assertEquals(mapOf(5 to listOf(3, 4)), queue.projectedPasses())

        val first = queue.finishRound(2_000_000L)
        assertEquals(listOf(1, 5), first.playing.map(Registration::key))
        assertEquals(listOf(3, 4), first.playing.first { it.key == 5 }.passedOverKeys)
        // The pair plays in the round it would have played in anyway, still as a pair.
        val second = first.finishRound(3_000_000L)
        assertEquals(listOf(3, 4), second.playing.map(Registration::key))
        assertEquals(listOf<Int?>(4, 3), second.playing.map(Registration::fixedPartnerKey))
    }

    @Test
    fun aPassCanCrossSeveralSolosAndFixedPairsThatAllowIt() {
        val queue = MachineQueue(
            waiting = listOf(registration(1, open = true), registration(2, open = false, passable = true)) +
                passablePair(3, 4) + registration(5, open = true)
        )
        assertEquals(listOf(listOf(1, 5), listOf(2), listOf(3, 4)), keys(queue.waitingPositions()))
        assertEquals(mapOf(5 to listOf(2, 3, 4)), queue.projectedPasses())
    }

    @Test
    fun aFixedPairHasOneChoiceForBoth() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, open = true),
                registration(3, open = true, partner = 4),
                registration(4, open = true, partner = 3),
                registration(5, open = true)
            )
        )
        fun MachineQueue.choices() = waiting.filter { it.key == 3 || it.key == 4 }.map(Registration::allowsPassing)

        // Either of the two can change it; it always applies to both.
        val on = queueAfter(queue, QueueAction.SetAllowsPassing("A", 4, true))
        assertEquals(listOf(true, true), on.choices())
        assertTrue(on.invariantViolations().isEmpty())
        assertEquals(listOf(listOf(1, 5), listOf(3, 4)), keys(on.waitingPositions()))
        assertEquals(listOf(false, false), queueAfter(on, QueueAction.SetAllowsPassing("A", 3, false)).choices())

        // The choice was made by two: it ends with the pair, even for one who then plays alone.
        val released = queueAfter(on, QueueAction.ChangePreference("A", 3, PlayPreference.SOLO))
        assertEquals(listOf(false, false), released.choices())
        assertEquals(listOf<Int?>(null, null), released.waiting.filter { it.key == 3 || it.key == 4 }.map(Registration::fixedPartnerKey))
    }

    @Test
    fun theChoiceCanBeMadeWhenThePairIsFormed() {
        val queue = MachineQueue(
            waiting = listOf(registration(1, open = true), registration(2, open = true), registration(3, open = true))
        )
        val formed = queueAfter(queue, QueueAction.CreateFixedPair("A", 2, 3, allowsPassing = true))
        assertEquals(listOf(true, true), formed.waiting.filter { it.fixedPartnerKey != null }.map(Registration::allowsPassing))

        val withFriend = queueAfter(
            queue,
            QueueAction.CreateFixedPairWithRegistration("A", 3, registration(8, open = true), allowsPassing = true)
        )
        assertEquals(listOf(3, 8), withFriend.waiting.filter { it.allowsPassing }.map(Registration::key))
        // Without the answer a new pair does not let anybody pass.
        val plain = queueAfter(queue, QueueAction.CreateFixedPair("A", 2, 3))
        assertTrue(plain.waiting.none(Registration::allowsPassing))
    }

    @Test
    fun onlyASoloRegistrationOrAFixedPairCanLetOthersPass() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, open = true),
                registration(2, open = false),
                registration(3, open = true, partner = 4),
                registration(4, open = true, partner = 3)
            )
        )
        assertTrue(queueAfter(queue, QueueAction.SetAllowsPassing("A", 2, true)).waiting[1].allowsPassing)
        val rejected = execute(queue, QueueAction.SetAllowsPassing("A", 1, true)) as QueueActionExecution.Rejected
        assertEquals(QueueActionFailureCode.PASSING_NEEDS_SOLO, rejected.failure.code)
        // Turning it off is always possible.
        val on = queueAfter(queue, QueueAction.SetAllowsPassing("A", 2, true))
        assertEquals(false, queueAfter(on, QueueAction.SetAllowsPassing("A", 2, false)).waiting[1].allowsPassing)
    }

    @Test
    fun choosingAnotherPreferenceOrAFixedPairEndsTheChoice() {
        val queue = MachineQueue(
            waiting = listOf(registration(1, open = true), registration(2, open = false, passable = true), registration(3, open = true))
        )
        assertEquals(false, queue.changePreference(2, PlayPreference.OPEN_TO_JOIN).waiting[1].allowsPassing)
        assertTrue(queue.changePreference(2, PlayPreference.SOLO).waiting[1].allowsPassing)
        val paired = queue.createFriendPair(2, registration(9, open = true))
        assertEquals(false, paired.allRegistrations.first { it.key == 2 }.allowsPassing)
    }

    @Test
    fun movingToAMachineForOnePlayerEndsTheChoice() {
        val source = MachineQueue(
            waiting = listOf(registration(1, open = true), registration(2, open = false, passable = true))
        )
        val state = QueueEngineState(linkedMapOf("A" to source, "B" to MachineQueue()))
        val context = QueueActionContext(
            atMillis = 5_000L,
            policy = QueueEnginePolicy(machineCapacities = mapOf("A" to 2, "B" to 1))
        )
        val moved = QueueEngine.execute(state, QueueAction.TransferRegistrations("A", "B", setOf(2)), context)
            as QueueActionExecution.Applied
        assertEquals(false, moved.state.queue("B")!!.allRegistrations.single().allowsPassing)
        // A machine changed to take one player ends it as well.
        assertEquals(false, QueueEngine.normalizeQueueForCapacity(source, 1).allRegistrations.any { it.allowsPassing })
    }

    @Test
    fun theRoundRecordsWhomItPassedUntilItEnds() {
        val queue = MachineQueue(
            playing = listOf(registration(9, open = false)),
            waiting = listOf(
                registration(1, open = true),
                registration(2, open = false, passable = true),
                registration(3, open = true),
                registration(4, open = false)
            ),
            playingStartedAtMillis = 1_000L
        )
        assertEquals(mapOf(3 to listOf(2)), queue.projectedPasses())
        val started = queue.finishRound(2_000_000L)
        assertEquals(listOf(1, 3), started.playing.map(Registration::key))
        assertEquals(listOf(2), started.playing.first { it.key == 3 }.passedOverKeys)
        assertTrue(started.playing.first { it.key == 1 }.passedOverKeys.isEmpty())
        // The real order is unchanged: the passed registration is next.
        assertEquals(2, started.waiting.first().key)
        val next = started.finishRound(3_000_000L)
        assertEquals(listOf(2), next.playing.map(Registration::key))
        assertTrue(next.allRegistrations.all { it.passedOverKeys.isEmpty() })
    }

    /**
     * Review 2026-11-27: the check before a pass was once remembered from a moment somebody
     * played, when it could not see any round change and always said yes; later, with the
     * playing position empty and the same registrations waiting, 101 passed 102 although that
     * made 106 a round later. The keys are this test's own, so no other test answers for it.
     */
    @Test
    fun aCheckMadeWhileSomebodyPlaysDoesNotDecideTheNextPass() {
        val waiting = listOf(
            registration(101, open = true),
            registration(102, open = false, passable = true),
            registration(103, open = true),
            registration(104, open = true),
            registration(105, open = true, absence = QueueAbsenceStatus.DEFER_ONE_ROUND),
            registration(106, open = false)
        )
        val queue = MachineQueue(playing = listOf(registration(109, open = false)), waiting = waiting, playingStartedAtMillis = 1_000L)
        // What the home page asks while 109 plays.
        queue.nextPlayingPositionPreview()
        // 109 leaves the queue; the next group enters.
        val state = QueueEngine.execute(
            QueueEngineState.single("A", queue),
            QueueAction.LeaveQueue("A", 109),
            QueueActionContext(atMillis = 2_000L)
        ).let { (it as QueueActionExecution.Applied).state }
        val entered = QueueEngine.execute(state, QueueAction.EnterPlayingPosition("A"), QueueActionContext(atMillis = 3_000L))
            .let { (it as QueueActionExecution.Applied).state.queue("A")!! }
        assertEquals(listOf(101), entered.playing.map(Registration::key))
        val without = MachineQueue(waiting = waiting.map { it.copy(allowsPassing = false) })
        val roundsWithout = firstRounds(without)
        val roundsWith = firstRounds(MachineQueue(waiting = waiting))
        roundsWithout.forEach { (key, round) -> assertTrue("registration $key", (roundsWith[key] ?: Int.MAX_VALUE) <= round) }
    }

    /**
     * Review 2026-11-27: a plan for a fixed pair, made before a player ticked or unticked the
     * choice, was still applied and turned the choice back. Now it is out of date.
     */
    @Test
    fun aPlanForAFixedPairMadeBeforeTheChoiceChangedIsOutOfDate() {
        val queue = MachineQueue(
            playing = listOf(registration(209, open = false)),
            waiting = listOf(
                registration(201, open = true),
                registration(202, open = false),
                registration(203, open = true),
                registration(204, open = true),
                registration(205, open = true)
            ),
            playingStartedAtMillis = 1_000L
        )
        val plan = queue.planFriendPair(201, 205)!!
        val state = QueueEngine.execute(
            QueueEngineState.single("A", queue),
            QueueAction.SetAllowsPassing("A", 202, true),
            QueueActionContext(atMillis = 2_000L)
        ).let { (it as QueueActionExecution.Applied).state }
        val stale = QueueEngine.execute(
            state,
            QueueAction.CreateFixedPair("A", 201, 205, expectedPlan = plan),
            QueueActionContext(atMillis = 3_000L)
        )
        assertEquals(QueueActionFailureCode.STALE_STATE, (stale as QueueActionExecution.Rejected).failure.code)
        assertEquals(true, state.queue("A")!!.waiting.first { it.key == 202 }.allowsPassing)
    }

    private fun applied(state: QueueEngineState, action: QueueAction, atMillis: Long): QueueEngineState =
        when (val execution = QueueEngine.execute(state, action, QueueActionContext(atMillis = atMillis))) {
            is QueueActionExecution.Applied -> execution.state
            is QueueActionExecution.Rejected -> throw AssertionError("$action rejected: ${execution.failure}")
        }

    /**
     * Review 2026-11-27: where the check refuses a pass, the registration the pass would have
     * paired was still treated as next: it could be marked as not present and removed, or moved
     * into the current round, although the next group is somebody else.
     */
    @Test
    fun onlyTheGroupThatReallyPlaysNextCountsAsNext() {
        val queue = MachineQueue(
            playing = listOf(registration(309, open = false)),
            waiting = listOf(
                registration(301, open = true),
                registration(302, open = false, passable = true),
                registration(303, open = true),
                registration(304, open = true),
                registration(305, open = true, absence = QueueAbsenceStatus.DEFER_ONE_ROUND),
                registration(306, open = false)
            ),
            playingStartedAtMillis = 1_000L
        )
        // The positions on screen show the pass the registrations allow; the check refuses it.
        assertEquals(listOf(301, 303), queue.waitingPositions().first().map(Registration::key))
        assertEquals(listOf(301), queue.nextPlayingPositionPreview()!!.nextRegistrations.map(Registration::key))
        assertEquals(listOf(301), queue.nextWaitingGroup().map(Registration::key))
        assertTrue(queue.canMarkNoShow(301))
        assertEquals(false, queue.canMarkNoShow(303))
        val state = QueueEngineState.single("A", queue)
        val context = QueueActionContext(atMillis = 2_000L)
        assertTrue(QueueEngine.execute(state, QueueAction.MarkNoShow("A", setOf(303), NoShowResolution.REMOVE), context) is QueueActionExecution.Rejected)
        assertTrue(QueueEngine.execute(state, QueueAction.MoveWaitingRegistrationIntoCurrentRound("A", 303), context) is QueueActionExecution.Rejected)
        assertEquals(listOf(309, 301), applied(state, QueueAction.MoveWaitingRegistrationIntoCurrentRound("A", 301), 2_000L).queue("A")!!.playing.map(Registration::key))
    }

    /**
     * Review 2026-11-27: a registration that cannot play at that moment would be passed with
     * or without its choice, so it is not told that it was.
     */
    @Test
    fun aRegistrationThatCannotPlayIsNotToldItWasPassed() {
        val queue = MachineQueue(
            playing = listOf(registration(409, open = false)),
            waiting = listOf(
                registration(401, open = true),
                registration(402, open = false, passable = true, absence = QueueAbsenceStatus.DEFER_ONE_ROUND),
                registration(403, open = true)
            ),
            playingStartedAtMillis = 1_000L
        )
        assertEquals(emptyMap<Int, List<Int>>(), queue.projectedPasses())
        val started = queue.finishRound(2_000_000L)
        assertEquals(listOf(401, 403), started.playing.map(Registration::key))
        assertTrue(started.playing.all { it.passedOverKeys.isEmpty() })
    }

    /**
     * Reported 2026-11-27: “将越过” was missing when the solo passed was an online registration
     * still to check in. The home page counts such a registration as checking in, so the pass is
     * shown; when the round starts with it still pending it is removed instead of passed.
     */
    @Test
    fun aPendingRegistrationIsShownAsPassed() {
        val queue = MachineQueue(
            playing = listOf(registration(609, open = false)),
            waiting = listOf(
                registration(601, open = true),
                registration(602, open = false, passable = true, pendingCheckIn = true),
                registration(603, open = true)
            ),
            playingStartedAtMillis = 1_000L
        )
        assertEquals(mapOf(603 to listOf(602)), queue.projectedPasses())
        val started = queue.finishRound(2_000_000L)
        assertEquals(listOf(601, 603), started.playing.map(Registration::key))
        // The round was not the turn of 602: it keeps waiting for its check-in. A pending
        // registration is not recorded as passed, because it may still leave unplayed.
        assertTrue(started.waiting.first().let { it.key == 602 && it.requiresOnSiteCheckIn })
        assertTrue(started.playing.all { it.passedOverKeys.isEmpty() })
        // Its own turn is the next round; still not checked in, it leaves then.
        val next = started.finishRound(3_000_000L)
        assertTrue(next.allRegistrations.none { it.key == 602 })
    }

    @Test
    fun aRegistrationThatPlaysFirstIsNotShownAsPassed() {
        // 801 postponed its turn by one round, so 802 plays first and then 801 with 803.
        val queue = MachineQueue(
            playing = listOf(registration(809, open = false)),
            waiting = listOf(
                registration(801, open = true, absence = QueueAbsenceStatus.DEFER_ONE_ROUND),
                registration(802, open = false, passable = true),
                registration(803, open = true)
            ),
            playingStartedAtMillis = 1_000L
        )
        val shown = queue.waitingProjection(includeCommonPlayPreview = false).positions
            .map { position -> position.registrations.map(Registration::key) }
        assertEquals(listOf(listOf(802), listOf(801, 803)), shown)
        assertEquals(emptyMap<Int, List<Int>>(), queue.projectedPasses())
        assertEquals(listOf(802), queue.finishRound(2_000_000L).playing.map(Registration::key))
    }

    /**
     * Reported 2026-11-27: next to a pending online registration the positions on the home page
     * (which count it as checking in) matched none of the real positions, so they had no drag
     * handle. The home page now drags the positions it shows, whatever the real grouping.
     */
    @Test
    fun thePositionsShownCanBeMovedWhateverTheRealGrouping() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(701, open = true),
                registration(702, open = true, pendingCheckIn = true),
                registration(703, open = true),
                registration(704, open = false)
            )
        )
        val shown = queue.waitingProjection(includeCommonPlayPreview = false).positions.map { p -> p.registrations.map(Registration::key) }
        assertEquals(listOf(listOf(701, 702), listOf(703), listOf(704)), shown)
        // The real grouping keeps the pending registration apart.
        assertEquals(listOf(listOf(701, 703), listOf(702), listOf(704)), keys(queue.waitingPositions()))
        // 704 before the shown position of 701 and 702; the pending one stays with its partner.
        val moved = queue.moveWaitingRegistrations(setOf(704), setOf(701, 702), beforeTarget = true)
        assertEquals(listOf(704, 701, 702, 703), moved.waiting.map(Registration::key))
        assertEquals(true, moved.waiting.first { it.key == 702 }.requiresOnSiteCheckIn)
        // The shown position of 701 and 702 after 703: both move, in their order.
        val back = queue.moveWaitingRegistrations(setOf(701, 702), setOf(703), beforeTarget = false)
        assertEquals(listOf(703, 701, 702, 704), back.waiting.map(Registration::key))
        // Nothing to do, or nothing sensible asked: unchanged.
        assertEquals(queue, queue.moveWaitingRegistrations(setOf(701), setOf(701, 702), beforeTarget = true))
        assertEquals(queue, queue.moveWaitingRegistrations(setOf(999), setOf(701), beforeTarget = true))
    }

    /**
     * Found 2026-11-27: a management app rearranges the positions as shown and used to write
     * that order back as the real one. With a pass on screen that put the registration passing
     * for real ahead of the one it passes, which the rules never do. Only what was moved moves.
     */
    @Test
    fun reorderingTheShownPositionsKeepsTheRealOrderOfTheRest() {
        val queue = MachineQueue(
            playing = listOf(registration(809, open = false)),
            waiting = listOf(
                registration(801, open = true),
                registration(802, open = false, passable = true),
                registration(803, open = true),
                registration(804, open = false),
                registration(805, open = false)
            ),
            playingStartedAtMillis = 1_000L
        )
        assertEquals(listOf(listOf(801, 803), listOf(802), listOf(804), listOf(805)), queue.shownWaitingPositionKeys())
        // The last two positions change places; 802 stays ahead of 803 in the real order.
        val swapped = queue.withShownPositionsReordered(
            listOf(listOf(801, 803), listOf(802), listOf(805), listOf(804))
        )!!
        assertEquals(listOf(801, 802, 803, 805, 804), swapped.waiting.map(Registration::key))
        // The pair that passes moves to the end as a whole; nothing else moves.
        val pairLast = queue.withShownPositionsReordered(
            listOf(listOf(802), listOf(804), listOf(805), listOf(801, 803))
        )!!
        assertEquals(listOf(802, 804, 805, 801, 803), pairLast.waiting.map(Registration::key))
        // A position to the front.
        val first = queue.withShownPositionsReordered(
            listOf(listOf(805), listOf(801, 803), listOf(802), listOf(804))
        )!!
        assertEquals(listOf(805, 801, 802, 803, 804), first.waiting.map(Registration::key))
        // The same order changes nothing; anything that is not these positions is refused.
        assertEquals(queue, queue.withShownPositionsReordered(queue.shownWaitingPositionKeys()))
        assertEquals(null, queue.withShownPositionsReordered(listOf(listOf(801), listOf(803), listOf(802), listOf(804), listOf(805))))
        assertEquals(null, queue.withShownPositionsReordered(listOf(listOf(801, 803), listOf(802), listOf(804))))
    }

    /**
     * Review 2026-11-27: when the registration it paired with left the playing position, the one
     * left there still said it had passed somebody, and later seemed to have passed them to play
     * with whoever joined it.
     */
    @Test
    fun aRegistrationLeftAloneInThePlayingPositionNoLongerRecordsAPass() {
        val queue = MachineQueue(
            playing = listOf(registration(509, open = false)),
            waiting = listOf(
                registration(501, open = true),
                registration(502, open = false, passable = true),
                registration(503, open = true),
                registration(504, open = true)
            ),
            playingStartedAtMillis = 1_000L
        )
        val started = applied(QueueEngineState.single("A", queue), QueueAction.FinishRound("A"), 2_000_000L)
        assertEquals(listOf(502), started.queue("A")!!.playing.first { it.key == 503 }.passedOverKeys)
        val alone = applied(started, QueueAction.ReturnPlayingToWaitingFront("A", setOf(501)), 2_100_000L).queue("A")!!
        assertEquals(listOf(503), alone.playing.map(Registration::key))
        assertTrue(alone.allRegistrations.all { it.passedOverKeys.isEmpty() })
        val left = applied(started, QueueAction.LeaveQueue("A", 501), 2_100_000L).queue("A")!!
        assertTrue(left.allRegistrations.all { it.passedOverKeys.isEmpty() })
    }

    /**
     * Review 2026-11-27: the passed registration left the queue while the pair that passed it
     * played; the record went on naming it, the snapshot with it, and the server refused every
     * upload until the round ended.
     */
    @Test
    fun aPassedRegistrationThatLeavesIsDroppedFromTheRecord() {
        val queue = MachineQueue(
            playing = listOf(registration(909, open = false)),
            waiting = listOf(
                registration(901, open = true),
                registration(902, open = false, passable = true),
                registration(906, open = false, passable = true),
                registration(903, open = true)
            ),
            playingStartedAtMillis = 1_000L
        )
        val started = applied(QueueEngineState.single("A", queue), QueueAction.FinishRound("A"), 2_000_000L)
        assertEquals(listOf(902, 906), started.queue("A")!!.playing.first { it.key == 903 }.passedOverKeys)
        // One of the two leaves: the other is still told as passed.
        val oneLeft = applied(started, QueueAction.LeaveQueue("A", 902), 2_100_000L)
        assertEquals(listOf(906), oneLeft.queue("A")!!.playing.first { it.key == 903 }.passedOverKeys)
        // The other is removed: nothing is left to tell.
        val bothGone = applied(oneLeft, QueueAction.RemoveRegistrations("A", setOf(906)), 2_200_000L)
        assertTrue(bothGone.queue("A")!!.allRegistrations.all { it.passedOverKeys.isEmpty() })
    }

    /** The round in which each registration first enters the playing position. */
    private fun firstRounds(start: MachineQueue): Map<Int, Int> {
        val rounds = mutableMapOf<Int, Int>()
        var queue = start
        var now = 1_000_000L
        val all = start.allRegistrations.map(Registration::key).toSet()
        for (round in 1..(all.size * 4 + 8)) {
            now += 15 * 60_000L
            queue = queue.finishRound(now)
            queue.playing.forEach { rounds.putIfAbsent(it.key, round) }
            if (rounds.keys.containsAll(all)) break
        }
        return rounds
    }

    private fun randomAbsence(random: Random) = when {
        random.nextDouble() < 0.08 -> QueueAbsenceStatus.DEFER_ONE_ROUND
        random.nextDouble() < 0.05 -> QueueAbsenceStatus.SKIP_ONE_TURN
        random.nextDouble() < 0.04 -> QueueAbsenceStatus.TEMPORARILY_AWAY
        else -> QueueAbsenceStatus.NONE
    }

    /**
     * The shortest queue the random search found in which passing, without the check before
     * each pass, made somebody a round later: 1 rejoined at the tail and passed 6 to pair with
     * 3, which later took 5's partner. With the check, 1 does not pass there.
     */
    @Test
    fun aPassThatWouldCostSomebodyARoundDoesNotHappen() {
        val waiting = listOf(
            registration(2, open = false, absence = QueueAbsenceStatus.SKIP_ONE_TURN),
            registration(3, open = true),
            registration(4, open = true, absence = QueueAbsenceStatus.DEFER_ONE_ROUND),
            registration(5, open = true, absence = QueueAbsenceStatus.SKIP_ONE_TURN),
            registration(6, open = false, passable = true)
        )
        val withPassing = MachineQueue(playing = listOf(registration(1, open = true)), waiting = waiting, playingStartedAtMillis = 1_000L)
        val without = withPassing.copy(waiting = waiting.map { it.copy(allowsPassing = false) })
        val roundsWith = firstRounds(withPassing)
        firstRounds(without).forEach { (key, round) ->
            assertTrue("registration $key", (roundsWith[key] ?: Int.MAX_VALUE) <= round)
        }
    }

    /**
     * Nobody enters a playing position in a later round because others may pass, whatever
     * states the queue holds (暂缓一次, 轮空一次, 暂时离开, 待签到, fixed pairs): before a pair forms by
     * passing, the rounds after it are replayed with and without the pass.
     */
    @Test
    fun nobodyEntersAPlayingPositionInALaterRound() {
        val random = Random(20261126)
        var scenarios = 0
        var passed = 0
        repeat(3_000) {
            var key = 1
            val playing = listOf(registration(key++, open = true))
            val waiting = mutableListOf<Registration>()
            val count = random.nextInt(3, 14)
            while (waiting.size < count) {
                if (random.nextDouble() < 0.15 && waiting.size + 2 <= count) {
                    val first = key++
                    val second = key++
                    // Half of the fixed pairs let others pass, like half of the solos.
                    val pairPasses = random.nextBoolean()
                    waiting += registration(first, open = true, partner = second).copy(allowsPassing = pairPasses)
                    waiting += registration(second, open = true, partner = first).copy(allowsPassing = pairPasses)
                    continue
                }
                val open = random.nextDouble() < 0.6
                val absence = randomAbsence(random)
                waiting += registration(
                    key++,
                    open = open,
                    passable = !open && random.nextDouble() < 0.5,
                    absence = absence,
                    pendingCheckIn = absence == QueueAbsenceStatus.NONE && random.nextDouble() < 0.06
                )
            }
            val withPassing = MachineQueue(playing = playing, waiting = waiting, playingStartedAtMillis = 1_000L)
            val without = withPassing.copy(waiting = waiting.map { it.copy(allowsPassing = false) })
            if (withPassing.waitingPositions() == without.waitingPositions()) return@repeat
            scenarios++
            val roundsWith = firstRounds(withPassing)
            val roundsWithout = firstRounds(without)
            roundsWithout.forEach { (registrationKey, roundWithout) ->
                val roundWith = roundsWith[registrationKey]
                assertTrue(
                    "registration $registrationKey: round $roundWith with passing, $roundWithout without; " +
                        "queue ${waiting.map { "${it.key}${if (it.preference == PlayPreference.SOLO) "S" else "O"}" +
                            (if (it.allowsPassing) "p" else "") + (if (it.absenceStatus != QueueAbsenceStatus.NONE) "-${it.absenceStatus}" else "") }}",
                    roundWith != null && roundWith <= roundWithout
                )
            }
            if (roundsWith.any { (registrationKey, round) -> round < (roundsWithout[registrationKey] ?: Int.MAX_VALUE) }) passed++
        }
        // The random queues really exercise the rule, and passing still helps in most of them.
        assertTrue("scenarios $scenarios", scenarios > 1_000)
        assertTrue("earlier $passed", passed > 500)
    }
}
