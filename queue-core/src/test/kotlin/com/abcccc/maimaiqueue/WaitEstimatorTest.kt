package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scenarios of docs/releases/wait-estimate-2026-11.md, section 三. */
class WaitEstimatorTest {
    private val single = QueueEnginePolicy(requireOperationalForPlayerActions = true)
    private val paired = QueueEnginePolicy(
        machinePartners = mapOf("A" to "B", "B" to "A"),
        requireOperationalForPlayerActions = true,
        pairedArrangementRules = PairedArrangementRules()
    )
    private val planned = PairedMachineForecast.RoundDurations(soloMillis = 12 * MINUTE, sharedMillis = 15 * MINUTE)
    private val durations = mapOf("A" to planned, "B" to planned)

    private fun registration(
        key: Int,
        preference: PlayPreference = PlayPreference.SOLO,
        paired: Boolean = false,
        pendingCheckIn: Boolean = false,
        absence: QueueAbsenceStatus = QueueAbsenceStatus.NONE
    ) = Registration(
        key = key,
        displayId = "玩家$key",
        preference = preference,
        absenceStatus = absence,
        isTemporary = false,
        createdAtMillis = key * 1_000L,
        requiresOnSiteCheckIn = pendingCheckIn,
        onSiteCheckInStartedAtMillis = if (pendingCheckIn) NOW else null,
        wasOnlineRegistration = pendingCheckIn,
        alsoQueuesAtPairedMachine = paired
    )

    private fun singleState(queue: MachineQueue) = QueueEngineState(linkedMapOf("A" to queue))

    private fun estimate(state: QueueEngineState, policy: QueueEnginePolicy = single) =
        WaitEstimator.estimate(state, policy, durations, NOW)

    private fun minutes(millis: Long?) = millis?.let { it / MINUTE.toDouble() }

    /**
     * Reported 2026-11-26: with a pair of machines on, every position after A1 showed the same
     * 15 minutes. A registration near the back that may be arranged to machine B had its own
     * early time there, which became its position's time and, as no position is shown later than
     * the one behind it, the time of every position before it.
     */
    /** Found by the random check of 2026-11-27: nobody playing, one registration set to 暂缓一次. */
    @Test
    fun aLoneDeferredRegistrationStillGetsAWait() {
        val result = estimate(
            singleState(
                MachineQueue(waiting = listOf(registration(1, absence = QueueAbsenceStatus.DEFER_ONE_ROUND)))
            )
        )
        assertNotNull(result.registrations[1])
    }

    @Test
    fun aRegistrationThatMayMoveDoesNotPullTheWaitsBeforeItDown() {
        val open = PlayPreference.OPEN_TO_JOIN
        val a = MachineQueue(
            playing = listOf(registration(1, open)),
            waiting = (2..8).map { registration(it, open) } + registration(9, open, paired = true),
            playingStartedAtMillis = NOW
        )
        val b = MachineQueue(
            playing = listOf(registration(50, open)),
            waiting = listOf(registration(51, open), registration(52, open)),
            playingStartedAtMillis = NOW
        )
        val result = estimate(pairedState(a, b), paired)
        val positions = a.waitingProjection(includeCommonPlayPreview = false).positions
            .map { position -> position.registrations.map(Registration::key) }
        val shown = WaitEstimator.displayMinutesInOrder(
            positions.map(result::estimate),
            positions.map(result::hereEstimate)
        )
        // The positions before it keep their own, growing waits...
        shown.dropLast(1).zipWithNext().forEach { (earlier, later) -> assertTrue("$shown", earlier!! < later!!) }
        // ...while its own position tells the earlier time it may have at machine B.
        assertTrue("$shown", shown.last()!! < shown[shown.size - 2]!!)
        // The registration itself may still play earlier at machine B.
        assertTrue(result.registrations.getValue(9).waitMillis < result.here.getValue(9).waitMillis)
    }

    @Test
    fun theNextRegistrationIsShownSlightlyBeforeThePlannedEnd() {
        val result = estimate(
            singleState(
                MachineQueue(
                    playing = listOf(registration(1)),
                    waiting = listOf(registration(2)),
                    playingStartedAtMillis = NOW
                )
            )
        )
        val next = result.registrations.getValue(2)
        // A round lasts 10 to 15 minutes, most often 12; the 30th percentile is about 11.7.
        assertTrue("${minutes(next.waitMillis)}", next.waitMillis in 11 * MINUTE..12 * MINUTE)
        assertFalse(next.uncertain)
    }

    @Test
    fun registrationsFurtherBackLeanEarlierThanThePlan() {
        val result = estimate(
            singleState(
                MachineQueue(
                    playing = listOf(registration(1)),
                    waiting = (2..6).map { registration(it) },
                    playingStartedAtMillis = NOW
                )
            )
        )
        val last = result.registrations.getValue(6).waitMillis
        // Five rounds ahead. A round lasts 10 to 15 minutes, 12 most often, so on average about
        // 12.3: five rounds take about 61.7 minutes in the middle. The 30th percentile lies
        // below that, and can lie slightly above the plain plan of 60 minutes.
        assertTrue("${minutes(last)}", last in 58 * MINUTE until 61 * MINUTE + 40_000L)
        val waits = (2..6).map { result.registrations.getValue(it).waitMillis }
        assertEquals(waits.sorted(), waits)
    }

    @Test
    fun aNewRegistrationGetsTheMiddleValue() {
        val result = estimate(
            singleState(
                MachineQueue(
                    playing = listOf(registration(1)),
                    waiting = (2..4).map { registration(it) },
                    playingStartedAtMillis = NOW
                )
            )
        )
        val newcomer = result.newRegistrationWaitMillis.getValue("A")
        val last = result.registrations.getValue(4).waitMillis
        // Four rounds ahead of it: planned 48 minutes; the middle value is near the plan and
        // later than the early value shown to the last registration, which is one round sooner.
        assertTrue("${minutes(newcomer)}", newcomer in 46 * MINUTE..50 * MINUTE)
        assertTrue(newcomer > last)
    }

    @Test
    fun theNewcomerDoesNotChangeTheWaitsOfThoseAlreadyWaiting() {
        // The deferred registration plays after registration 2. A newcomer that is open to
        // others would share registration 2's round and make it longer; it must not be counted.
        val result = estimate(
            singleState(
                MachineQueue(
                    waiting = listOf(
                        registration(1, PlayPreference.OPEN_TO_JOIN, absence = QueueAbsenceStatus.DEFER_ONE_ROUND),
                        registration(2, PlayPreference.OPEN_TO_JOIN)
                    )
                )
            )
        )
        assertEquals(0L, result.registrations.getValue(2).waitMillis)
        val deferred = result.registrations.getValue(1).waitMillis
        // One round of registration 2 alone: 10 to 15 minutes, not a shared round of 13 to 18.
        assertTrue("${minutes(deferred)}", deferred in 11 * MINUTE..12 * MINUTE)
    }

    @Test
    fun aRoundPastItsPlanEndsWithinThreeMoreMinutes() {
        fun nextWait(startedMinutesAgo: Long) = estimate(
            singleState(
                MachineQueue(
                    playing = listOf(registration(1)),
                    waiting = listOf(registration(2)),
                    playingStartedAtMillis = NOW - startedMinutesAgo * MINUTE
                )
            )
        ).registrations.getValue(2).waitMillis

        assertTrue(nextWait(14) <= MINUTE)
        assertEquals(0L, nextWait(20))
    }

    @Test
    fun aPendingOnlineRegistrationCountsAsCheckingInAndTheEarlierCaseIsToldApart() {
        fun estimateWith(pending: Boolean) = estimate(
            singleState(
                MachineQueue(
                    playing = listOf(registration(1)),
                    waiting = listOf(registration(2, pendingCheckIn = pending), registration(3)),
                    playingStartedAtMillis = NOW
                )
            )
        )

        val withPending = estimateWith(pending = true)
        val checkedIn = estimateWith(pending = false)
        // The positions on screen assume the online registration checks in; so does the estimate.
        assertEquals(checkedIn.registrations.getValue(3), withPending.registrations.getValue(3))
        // The pending registration itself is told its turn for when it does check in.
        assertNotNull(withPending.registrations[2])
        // If it does not, the one behind plays about a round earlier, and that is told apart.
        val earlier = withPending.earlierIfOnlineMissing.getValue(3)
        val difference = withPending.registrations.getValue(3).waitMillis - earlier
        assertTrue("${minutes(difference)}", difference in 9 * MINUTE..16 * MINUTE)
        assertFalse(2 in withPending.earlierIfOnlineMissing)
        assertTrue(checkedIn.earlierIfOnlineMissing.isEmpty())
    }

    @Test
    fun aSmallDifferenceIsNotToldAsAnEarlierCase() {
        // The pending registration and the one behind it share a round either way: if the
        // pending one does not come, the other still plays in the same round.
        val result = estimate(
            singleState(
                MachineQueue(
                    playing = listOf(registration(1)),
                    waiting = listOf(
                        registration(2, preference = PlayPreference.OPEN_TO_JOIN),
                        registration(3, preference = PlayPreference.OPEN_TO_JOIN, pendingCheckIn = true),
                        registration(4)
                    ),
                    playingStartedAtMillis = NOW
                )
            )
        )
        assertFalse(2 in result.earlierIfOnlineMissing)
    }

    @Test
    fun aTemporarilyAwayRegistrationHasNoEstimateAndBlocksNobody() {
        val result = estimate(
            singleState(
                MachineQueue(
                    playing = listOf(registration(1)),
                    waiting = listOf(
                        registration(2, absence = QueueAbsenceStatus.TEMPORARILY_AWAY),
                        registration(3)
                    ),
                    playingStartedAtMillis = NOW
                )
            )
        )
        assertNull(result.registrations[2])
        assertTrue(result.registrations.getValue(3).waitMillis <= 12 * MINUTE)
    }

    @Test
    fun aStoppedMachineHasNoEstimates() {
        val stopped = single.copy(machineStatuses = mapOf("A" to MachineStatus().stop(MachineStopReason.MAINTENANCE, NOW)))
        val result = estimate(
            singleState(MachineQueue(playing = listOf(registration(1)), waiting = listOf(registration(2)), playingStartedAtMillis = NOW)),
            stopped
        )
        assertTrue(result.registrations.isEmpty())
        assertTrue(result.newRegistrationWaitMillis.isEmpty())
    }

    @Test
    fun theSameQueueAlwaysGivesTheSameEstimates() {
        val state = singleState(
            MachineQueue(
                playing = listOf(registration(1)),
                waiting = (2..8).map { registration(it, PlayPreference.OPEN_TO_JOIN) },
                playingStartedAtMillis = NOW - 3 * MINUTE
            )
        )
        assertEquals(estimate(state), estimate(state))
    }

    // ---- A pair of machines ----

    private fun pairedState(a: MachineQueue, b: MachineQueue, orders: Map<String, List<Int>> = emptyMap(), records: Map<Int, PairedArrangementRecord> = emptyMap()) =
        PairedMachineQueues.normalize(
            QueueEngineState(linkedMapOf("A" to a, "B" to b), pairedMachineOrders = orders, pairedArrangements = records),
            paired
        )

    @Test
    fun aMoveAwayWithAPreviewCountsAsGone() {
        // Registration 3 has been announced at machine B for 13 minutes; B ends within 5.
        val state = pairedState(
            a = MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(3, paired = true), registration(4)),
                playingStartedAtMillis = NOW
            ),
            b = MachineQueue(playing = listOf(registration(2)), playingStartedAtMillis = NOW - 10 * MINUTE),
            records = mapOf(3 to PairedArrangementRecord(previewSinceMillis = NOW - 13 * MINUTE))
        )
        val result = estimate(state, paired)

        assertTrue(result.moveConfidence.getValue(3) >= 0.75)
        val mover = result.registrations.getValue(3)
        assertTrue("${minutes(mover.waitMillis)}", mover.waitMillis <= 5 * MINUTE)
        assertFalse(mover.uncertain)
        // Registration 4 only waits for the round now playing on machine A.
        val behind = result.registrations.getValue(4)
        assertTrue("${minutes(behind.waitMillis)}", behind.waitMillis < 13 * MINUTE)
        assertFalse(behind.uncertain)
    }

    @Test
    fun aCertainMoveHereCountsAndAnUncertainOneIsMarked() {
        // Registration 3 of machine B holds a place ahead of registration 5 on machine A.
        fun result(previewMinutesAgo: Long?, aStartedMinutesAgo: Long) = estimate(
            pairedState(
                a = MachineQueue(
                    playing = listOf(registration(1)),
                    waiting = listOf(registration(5)),
                    playingStartedAtMillis = NOW - aStartedMinutesAgo * MINUTE
                ),
                b = MachineQueue(
                    playing = listOf(registration(2)),
                    waiting = listOf(registration(3, paired = true)),
                    playingStartedAtMillis = NOW
                ),
                orders = mapOf("A" to listOf(3, 5)),
                records = previewMinutesAgo?.let {
                    mapOf(3 to PairedArrangementRecord(previewSinceMillis = NOW - it * MINUTE))
                }.orEmpty()
            ),
            paired
        )

        listOf(13L to 10L, 3L to 3L, 0L to 0L, null to 0L).forEach { (previewMinutesAgo, aStartedMinutesAgo) ->
            val outcome = result(previewMinutesAgo, aStartedMinutesAgo)
            val share = outcome.moveConfidence.getValue(3)
            val behind = outcome.registrations.getValue(5)
            val aRemainingAtMost = (15 - aStartedMinutesAgo) * MINUTE
            when {
                share >= 0.75 -> {
                    // Counted: registration 5 waits for registration 3's round as well.
                    assertTrue("$share ${minutes(behind.waitMillis)}", behind.waitMillis > aRemainingAtMost - 3 * MINUTE)
                    assertFalse(behind.uncertain)
                }
                share > 0.25 -> {
                    assertTrue(behind.uncertain)
                    assertTrue(behind.waitMillis <= aRemainingAtMost)
                }
                else -> {
                    assertFalse(behind.uncertain)
                    assertTrue(behind.waitMillis <= aRemainingAtMost)
                }
            }
        }
        // With machine A ending well before machine B and the announcement over, the move is
        // as good as certain.
        assertTrue(result(13L, 10L).moveConfidence.getValue(3) >= 0.75)
        // Announced 3 minutes ago, both machines ending about together: it may go either way,
        // so registration 5 is shown the earlier time and marked.
        val open = result(3L, 3L)
        assertTrue(open.moveConfidence.getValue(3) in 0.26..0.74)
        assertTrue(open.registrations.getValue(5).uncertain)
        // Not announced and unlikely: certainly not coming, not marked.
        assertTrue(result(null, 0L).moveConfidence.getValue(3) <= 0.25)
    }

    @Test
    fun theNewRegistrationOfAPairedMachineIsItsOwnMachinesMiddleValue() {
        val state = pairedState(
            a = MachineQueue(
                playing = listOf(registration(1)),
                waiting = (3..8).map { registration(it) },
                playingStartedAtMillis = NOW
            ),
            b = MachineQueue(playing = listOf(registration(2)), playingStartedAtMillis = NOW)
        )
        val result = estimate(state, paired)
        val onA = result.newRegistrationWaitMillis.getValue("A")
        val onB = result.newRegistrationWaitMillis.getValue("B")
        // Not the smaller of the two machines: joining machine A means waiting for its queue.
        assertTrue("${minutes(onA)} ${minutes(onB)}", onA > onB + 30 * MINUTE)
    }

    // ---- Display ----

    @Test
    fun minutesAreRoundedByTheMaintainersSteps() {
        fun shown(minutes: Double, down: Boolean) = WaitEstimator.displayMinutes((minutes * MINUTE).toLong(), down)
        // 2026-11-28: 1 up to 45, 5 below 90, 10 up to 120, above that 2 小时以上.
        assertEquals(0L, shown(0.4, false))
        assertEquals(44L, shown(43.6, false))
        assertEquals(43L, shown(43.6, true))
        assertEquals(45L, shown(44.6, false))
        assertEquals(45L, shown(46.0, false))
        assertEquals(50L, shown(48.0, false))
        assertEquals(45L, shown(48.0, true))
        assertEquals(90L, shown(89.0, false))
        assertEquals(90L, shown(94.0, false))
        assertEquals(100L, shown(96.0, false))
        assertEquals(90L, shown(96.0, true))
        assertEquals(120L, shown(118.0, false))
        assertEquals(120L, shown(120.0, true))
        // Above 120 minutes the value stays above 120, whatever the direction.
        assertTrue(shown(120.5, true) > WaitEstimator.LONG_WAIT_MINUTES)
        assertTrue(shown(146.0, false) > WaitEstimator.LONG_WAIT_MINUTES)
    }

    @Test
    fun aPositionIsNeverShownLaterThanOneBehindIt() {
        val shown = WaitEstimator.displayMinutesInOrder(
            listOf(
                // In the 5-minute band (2026-11-28 steps): 47.6 would read 50, 49 as an
                // earliest time 45.
                WaitEstimator.Estimate((47.6 * MINUTE).toLong(), uncertain = false),
                WaitEstimator.Estimate(49 * MINUTE, uncertain = true),
                null,
                WaitEstimator.Estimate(55 * MINUTE, uncertain = false)
            )
        )
        assertEquals(listOf(45L, 45L, null, 55L), shown)
    }

    @Test
    fun anEarliestTimeBehindDoesNotPullThePositionsAheadDown() {
        // The last registration may play in 16 minutes if the one in the second position moves
        // to the other machine; the positions between them keep their own times.
        val shown = WaitEstimator.displayMinutesInOrder(
            listOf(
                WaitEstimator.Estimate(4 * MINUTE, uncertain = false),
                WaitEstimator.Estimate(15 * MINUTE, uncertain = true),
                WaitEstimator.Estimate(31 * MINUTE, uncertain = false),
                WaitEstimator.Estimate(46 * MINUTE, uncertain = true),
                WaitEstimator.Estimate(16 * MINUTE, uncertain = true)
            )
        )
        assertEquals(listOf(4L, 15L, 31L, 45L, 16L), shown)
    }

    @Test
    fun aShorterTimeBehindDoesNotPullThePositionsAheadDownEvenWhenItIsCertain() {
        // The registration in the fifth position is taken to leave for certain; the one behind
        // it then shares a round with the third and plays in 25 minutes. The fourth and fifth
        // keep their own 40 minutes.
        val shown = WaitEstimator.displayMinutesInOrder(
            listOf(
                WaitEstimator.Estimate(10 * MINUTE, uncertain = false),
                WaitEstimator.Estimate(25 * MINUTE, uncertain = false),
                WaitEstimator.Estimate(25 * MINUTE, uncertain = false),
                WaitEstimator.Estimate((40.4 * MINUTE).toLong(), uncertain = false),
                WaitEstimator.Estimate((40.6 * MINUTE).toLong(), uncertain = true),
                WaitEstimator.Estimate((25.1 * MINUTE).toLong(), uncertain = false)
            )
        )
        assertEquals(listOf(10L, 25L, 25L, 40L, 40L, 25L), shown)
    }

    @Test
    fun anUncertainWaitFarFromTheTimeIfNobodyMovesIsShownAsARange() {
        // 最快 16 分钟, 55 minutes if nobody is moved: far apart, so a range.
        assertEquals(55L, WaitEstimator.rangeUpperMinutes(16L, uncertain = true, waitIfNobodyMovesMillis = 55 * MINUTE))
        // Exactly 24 minutes apart, as shown, counts; less does not (37 minutes is shown as 35).
        assertEquals(40L, WaitEstimator.rangeUpperMinutes(16L, uncertain = true, waitIfNobodyMovesMillis = 40 * MINUTE))
        assertEquals(null, WaitEstimator.rangeUpperMinutes(16L, uncertain = true, waitIfNobodyMovesMillis = 37 * MINUTE))
        // A certain wait is one number; so is "可能很快可以游玩".
        assertEquals(null, WaitEstimator.rangeUpperMinutes(16L, uncertain = false, waitIfNobodyMovesMillis = 55 * MINUTE))
        // 可能很快 with a far later time if nobody is moved: that time is shown too.
        assertEquals(55L, WaitEstimator.rangeUpperMinutes(0L, uncertain = true, waitIfNobodyMovesMillis = 55 * MINUTE))
        assertEquals(null, WaitEstimator.rangeUpperMinutes(0L, uncertain = true, waitIfNobodyMovesMillis = 20 * MINUTE))
        assertEquals(null, WaitEstimator.rangeUpperMinutes(16L, uncertain = true, waitIfNobodyMovesMillis = null))
    }

    @Test
    fun theTimeIfNobodyMovesIsGivenForUncertainEstimatesOnly() {
        val open = PlayPreference.OPEN_TO_JOIN
        val a = MachineQueue(
            playing = listOf(registration(1, open)),
            waiting = (2..8).map { registration(it, open) } + registration(9, open, paired = true),
            playingStartedAtMillis = NOW
        )
        val b = MachineQueue(
            playing = listOf(registration(50, open)),
            waiting = listOf(registration(51, open), registration(52, open)),
            playingStartedAtMillis = NOW
        )
        val result = estimate(pairedState(a, b), paired)
        result.registrations.forEach { (key, estimate) ->
            val other = result.ifNobodyMoves[key]
            if (estimate.uncertain) {
                assertTrue("registration $key", other != null && other >= estimate.waitMillis - MINUTE)
            } else {
                assertEquals("registration $key", null, other)
            }
        }
        // 9 may play much earlier at machine B. While that is uncertain, the time if it is
        // not moved is its turn at machine A, which is later.
        val nine = result.registrations.getValue(9)
        if (nine.uncertain) assertTrue(result.ifNobodyMoves.getValue(9) > nine.waitMillis)
    }

    @Test
    fun aBusyPairIsEstimatedQuickly() {
        val state = pairedState(
            a = MachineQueue(
                playing = listOf(registration(1, PlayPreference.OPEN_TO_JOIN), registration(2, PlayPreference.OPEN_TO_JOIN)),
                waiting = (3..12).map { registration(it, PlayPreference.OPEN_TO_JOIN, paired = it % 3 == 0) },
                playingStartedAtMillis = NOW - 4 * MINUTE
            ),
            b = MachineQueue(
                playing = listOf(registration(20)),
                waiting = (21..23).map { registration(it, paired = it == 22) },
                playingStartedAtMillis = NOW - 9 * MINUTE
            )
        )
        estimate(state, paired)
        val started = System.nanoTime()
        val result = estimate(state, paired)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        println("WaitEstimator: busy pair estimated in $elapsedMillis ms; confidence ${result.moveConfidence}")
        assertEquals(10 + 3, result.registrations.size)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val NOW = 10_000_000L
    }
}
