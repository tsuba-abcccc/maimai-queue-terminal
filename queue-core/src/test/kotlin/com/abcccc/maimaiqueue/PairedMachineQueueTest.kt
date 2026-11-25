package com.abcccc.maimaiqueue

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedMachineQueueTest {
    private val pairedPolicy = QueueEnginePolicy(
        machinePartners = mapOf("A" to "B", "B" to "A"),
        requireOperationalForPlayerActions = true
    )

    private fun registration(
        key: Int,
        preference: PlayPreference = PlayPreference.SOLO,
        paired: Boolean = false,
        pendingCheckIn: Boolean = false
    ) = Registration(
        key = key,
        displayId = "玩家$key",
        preference = preference,
        isTemporary = false,
        createdAtMillis = key * 1_000L,
        requiresOnSiteCheckIn = pendingCheckIn,
        onSiteCheckInStartedAtMillis = if (pendingCheckIn) key * 1_000L else null,
        alsoQueuesAtPairedMachine = paired
    )

    private fun stateOf(
        vararg queues: Pair<String, MachineQueue>,
        policy: QueueEnginePolicy = pairedPolicy
    ): QueueEngineState = QueueEngine.settlePairedMachines(
        state = QueueEngineState(linkedMapOf(*queues)),
        policy = policy,
        atMillis = 50_000L
    ).also(::assertConsistent)

    private fun assertConsistent(state: QueueEngineState) {
        assertTrue(state.invariantViolations().joinToString(), state.invariantViolations().isEmpty())
        val keys = state.allRegistrations.map(Registration::key)
        assertEquals("同一份登记不能出现在两处：$keys", keys.distinct(), keys)
    }

    private fun apply(
        state: QueueEngineState,
        action: QueueAction,
        policy: QueueEnginePolicy = pairedPolicy,
        atMillis: Long = 100_000L
    ): QueueEngineState {
        val execution = QueueEngine.execute(
            state = state,
            action = action,
            context = QueueActionContext(atMillis = atMillis, policy = policy)
        )
        assertTrue("动作应成功：$action，实际为 $execution", execution is QueueActionExecution.Applied)
        return (execution as QueueActionExecution.Applied).state.also { result ->
            assertConsistent(result)
            assertEquals(
                "引擎产生的状态应当已经整理完毕",
                result,
                QueueEngine.settlePairedMachines(result, policy, atMillis)
            )
        }
    }

    private fun failureOf(
        state: QueueEngineState,
        action: QueueAction,
        policy: QueueEnginePolicy = pairedPolicy
    ): QueueActionFailureCode? = when (
        val execution = QueueEngine.execute(
            state = state,
            action = action,
            context = QueueActionContext(atMillis = 100_000L, policy = policy)
        )
    ) {
        is QueueActionExecution.Applied -> null
        is QueueActionExecution.Rejected -> execution.failure.code
    }

    private fun QueueEngineState.playingKeys(machineId: String): List<Int> =
        queue(machineId)!!.playing.map(Registration::key)

    private fun QueueEngineState.waitingKeys(machineId: String): List<Int> =
        queue(machineId)!!.waiting.map(Registration::key)

    private fun QueueEngineState.order(machineId: String): List<Int> =
        pairedMachineOrders[machineId].orEmpty()

    // ---- the maintainer's example -------------------------------------------------------

    @Test
    fun threeSoloPlayersTakeTurnsOnWhicheverMachineIsReachedFirst() {
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(3, paired = true)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(playing = listOf(registration(2)), playingStartedAtMillis = 10_000L)
        )
        assertEquals(listOf(3), state.order("B"))

        // Machine B is reached first: the registration really moves there.
        state = apply(state, QueueAction.FinishRound("B"))
        assertEquals(listOf(3), state.playingKeys("B"))
        assertEquals(listOf(2), state.waitingKeys("B"))
        assertEquals(emptyList<Int>(), state.waitingKeys("A"))
        assertEquals(listOf(1), state.playingKeys("A"))
        assertTrue(state.queue("B")!!.playing.single().alsoQueuesAtPairedMachine)
        assertEquals("进入游玩位置后两个位置都消失", emptyList<Int>(), state.order("A"))

        // Its round ends: it re-queues at the tail of both machines.
        state = apply(state, QueueAction.FinishRound("B"))
        assertEquals(listOf(2), state.playingKeys("B"))
        assertEquals(listOf(3), state.waitingKeys("B"))
        assertEquals(listOf(3), state.order("A"))

        // Machine A is reached first this time.
        state = apply(state, QueueAction.FinishRound("A"))
        assertEquals(listOf(3), state.playingKeys("A"))
        assertEquals(listOf(1), state.waitingKeys("A"))
        assertEquals(emptyList<Int>(), state.waitingKeys("B"))
    }

    @Test
    fun registrationStaysOnItsOwnMachineWhenThatMachineIsReachedFirst() {
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(3, paired = true)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(playing = listOf(registration(2)), playingStartedAtMillis = 10_000L)
        )
        state = apply(state, QueueAction.FinishRound("A"))
        assertEquals(listOf(3), state.playingKeys("A"))
        assertEquals(listOf(1), state.waitingKeys("A"))
        assertEquals(emptyList<Int>(), state.order("B"))
        assertEquals(listOf(2), state.playingKeys("B"))
    }

    // ---- where the place sits -----------------------------------------------------------

    @Test
    fun placeIsTakenAtTheTailAndLaterArrivalsQueueBehindIt() {
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(registration(1, paired = true)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(9)),
                waiting = listOf(registration(5)),
                playingStartedAtMillis = 10_000L
            )
        )
        assertEquals(listOf(5, 1), state.order("B"))

        state = apply(
            state,
            QueueAction.AddRegistrations("B", listOf(registration(6)), RegistrationPlacement.AUTO_ADVANCE)
        )
        assertEquals("后来的登记排在另一机台的位置之后", listOf(5, 1, 6), state.order("B"))

        state = apply(state, QueueAction.FinishRound("B"))
        assertEquals(listOf(5), state.playingKeys("B"))
        assertEquals(listOf(1, 6, 9), state.order("B"))

        state = apply(state, QueueAction.FinishRound("B"))
        assertEquals("轮到另一机台的位置时登记移过来", listOf(1), state.playingKeys("B"))
        assertEquals(listOf(6, 9, 5), state.waitingKeys("B"))
        assertEquals(emptyList<Int>(), state.waitingKeys("A"))
    }

    @Test
    fun reorderingOwnRegistrationsKeepsTheNumberAheadOfAPlace() {
        // [10, place, 11, 12] and the machine moves 10 behind 12: one registration stays ahead.
        assertEquals(
            listOf(11, 99, 12, 10),
            PairedMachineQueues.reconcileOrder(
                previousOrder = listOf(10, 99, 11, 12),
                homeKeys = listOf(11, 12, 10),
                placeKeys = listOf(99)
            )
        )
        // A registration ahead of the place leaves: the place moves up.
        assertEquals(
            listOf(99, 11, 12),
            PairedMachineQueues.reconcileOrder(
                previousOrder = listOf(10, 99, 11, 12),
                homeKeys = listOf(11, 12),
                placeKeys = listOf(99)
            )
        )
        // New arrivals and newly taken places go to the end, in that order of appearance.
        assertEquals(
            listOf(10, 99, 11, 13, 98),
            PairedMachineQueues.reconcileOrder(
                previousOrder = listOf(10, 99, 11),
                homeKeys = listOf(10, 11, 13),
                placeKeys = listOf(99, 98)
            )
        )
        // A registration deliberately returned to the very front stays ahead of a leading place.
        assertEquals(
            listOf(20, 99, 10),
            PairedMachineQueues.reconcileOrder(
                previousOrder = listOf(99, 10),
                homeKeys = listOf(20, 10),
                placeKeys = listOf(99),
                frontReturnedKeys = setOf(20)
            )
        )
        // A place that is no longer held disappears.
        assertEquals(
            listOf(10, 11),
            PairedMachineQueues.reconcileOrder(
                previousOrder = listOf(10, 99, 11),
                homeKeys = listOf(10, 11),
                placeKeys = emptyList()
            )
        )
    }

    // ---- states in which a registration queues at its own machine only -------------------

    @Test
    fun deferredAwayAndUnsignedRegistrationsHoldNoPlaceOnTheOtherMachine() {
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(
                    registration(1, paired = true),
                    registration(2, paired = true),
                    registration(3, paired = true, pendingCheckIn = true)
                ),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(playing = listOf(registration(9)), playingStartedAtMillis = 10_000L)
        )
        assertEquals("待签到的登记没有另一机台的位置", listOf(1, 2), state.order("B"))

        state = apply(state, QueueAction.DeferOneRound("A", 1))
        assertEquals(listOf(2), state.order("B"))

        state = apply(state, QueueAction.TemporarilyLeave("A", 2))
        assertEquals(emptyList<Int>(), state.order("B"))

        // Back to normal: the place is taken again, at the tail.
        state = apply(
            state,
            QueueAction.AddRegistrations("B", listOf(registration(6)), RegistrationPlacement.AUTO_ADVANCE)
        )
        state = apply(state, QueueAction.CancelDeferOneRound("A", 1))
        assertEquals(listOf(6, 1), state.order("B"))
        state = apply(state, QueueAction.CheckIn("A", 3))
        assertEquals(listOf(6, 1, 3), state.order("B"))
        state = apply(state, QueueAction.CancelTemporaryLeave("A", 2))
        assertEquals(listOf(6, 1, 3, 2), state.order("B"))
        assertTrue(state.queue("A")!!.waiting.all(Registration::alsoQueuesAtPairedMachine))
    }

    @Test
    fun fixedPairTurnsQueueingAtBothMachinesOff() {
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(
                    registration(1, PlayPreference.OPEN_TO_JOIN, paired = true),
                    registration(2, PlayPreference.OPEN_TO_JOIN)
                ),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(playing = listOf(registration(9)), playingStartedAtMillis = 10_000L)
        )
        assertEquals(listOf(1), state.order("B"))
        state = apply(state, QueueAction.CreateFixedPair("A", 1, 2))
        assertEquals(emptyList<Int>(), state.order("B"))
        assertFalse(state.queue("A")!!.waiting.any(Registration::alsoQueuesAtPairedMachine))
        assertEquals(
            QueueActionFailureCode.INVALID_FIXED_PAIR,
            failureOf(state, QueueAction.SetPairedMachineQueueing("A", 1, true))
        )
    }

    // ---- shared rounds -------------------------------------------------------------------

    @Test
    fun openRegistrationsOfBothMachinesCanShareARound() {
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(registration(1, PlayPreference.OPEN_TO_JOIN, paired = true)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(9)),
                waiting = listOf(registration(5, PlayPreference.OPEN_TO_JOIN)),
                playingStartedAtMillis = 10_000L
            )
        )
        state = apply(state, QueueAction.FinishRound("B"))
        assertEquals(listOf(5, 1), state.playingKeys("B"))
        assertEquals(emptyList<Int>(), state.waitingKeys("A"))
    }

    // ---- an empty machine ----------------------------------------------------------------

    @Test
    fun emptyMachineTakesTheFirstPlaceWithoutWaitingForAnOperator() {
        var state = stateOf(
            "A" to MachineQueue(playing = listOf(registration(8)), playingStartedAtMillis = 10_000L),
            "B" to MachineQueue()
        )
        state = apply(
            state,
            QueueAction.AddRegistrations(
                "A",
                listOf(registration(1, paired = true)),
                RegistrationPlacement.AUTO_ADVANCE
            )
        )
        assertEquals(listOf(1), state.playingKeys("B"))
        assertEquals(100_000L, state.queue("B")!!.playingStartedAtMillis)
        assertEquals(emptyList<Int>(), state.waitingKeys("A"))
    }

    @Test
    fun machineLeftEmptyOnPurposeIsNotStartedByTheOtherMachine() {
        var state = stateOf(
            "A" to MachineQueue(playing = listOf(registration(8)), playingStartedAtMillis = 10_000L),
            "B" to MachineQueue(waiting = listOf(registration(5)))
        )
        state = apply(
            state,
            QueueAction.AddRegistrations(
                "A",
                listOf(registration(1, paired = true)),
                RegistrationPlacement.AUTO_ADVANCE
            )
        )
        assertEquals(emptyList<Int>(), state.playingKeys("B"))
        assertEquals(listOf(5, 1), state.order("B"))
        assertEquals(listOf(1), state.waitingKeys("A"))
    }

    @Test
    fun turningQueueingOnAndOffTakesAndReleasesThePlace() {
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(registration(1)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(9)),
                waiting = listOf(registration(5)),
                playingStartedAtMillis = 10_000L
            )
        )
        assertEquals(listOf(5), state.order("B"))

        // Turned on while 1 is already waiting: no place on B before 1 has played a round.
        state = apply(state, QueueAction.SetPairedMachineQueueing("A", 1, true))
        assertEquals(listOf(5), state.order("B"))
        val waiting = state.queue("A")!!.waiting.single()
        assertTrue(waiting.alsoQueuesAtPairedMachine)
        assertTrue(waiting.skipsPairedMachineThisRound)

        // Turning it off again leaves a registration that queues at its own machine only.
        val turnedOff = apply(state, QueueAction.SetPairedMachineQueueing("A", 1, false))
        assertFalse(turnedOff.queue("A")!!.waiting.single().alsoQueuesAtPairedMachine)
        assertEquals(
            QueueActionFailureCode.NO_STATE_CHANGE,
            failureOf(turnedOff, QueueAction.SetPairedMachineQueueing("A", 1, false))
        )

        // Still on when 1 has played its round: from then on it has a place on B.
        state = apply(state, QueueAction.FinishRound("A"), atMillis = 110_000L)
        assertEquals(listOf(1), state.playingKeys("A"))
        assertEquals(listOf(5), state.order("B"))
        state = apply(state, QueueAction.FinishRound("A"), atMillis = 120_000L)
        assertEquals(listOf(8), state.playingKeys("A"))
        assertEquals(listOf(5, 1), state.order("B"))
    }

    @Test
    fun turningItOnWhilePlayingCountsFromTheEndOfThatRound() {
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(2)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(9)),
                waiting = listOf(registration(5)),
                playingStartedAtMillis = 10_000L
            )
        )
        state = apply(state, QueueAction.SetPairedMachineQueueing("A", 1, true))
        assertFalse(state.queue("A")!!.playing.single().skipsPairedMachineThisRound)
        state = apply(state, QueueAction.FinishRound("A"), atMillis = 110_000L)
        assertEquals(listOf(2), state.playingKeys("A"))
        assertEquals(listOf(5, 1), state.order("B"))
    }

    // ---- order changed by hand: the place at the other machine is given up for the round --

    @Test
    fun reorderedKeysNamesTheRegistrationsThatWereMoved() {
        fun moved(before: List<Int>, after: List<Int>) = PairedMachineQueues.reorderedKeys(before, after)
        // One registration dragged past several others: only that one was moved.
        assertEquals(setOf(5), moved(listOf(1, 2, 3, 4, 5), listOf(5, 1, 2, 3, 4)))
        assertEquals(setOf(1), moved(listOf(1, 2, 3, 4), listOf(2, 3, 4, 1)))
        // Two positions exchanged: both were moved, the ones between them were not.
        assertEquals(setOf(1, 4), moved(listOf(1, 2, 3, 4), listOf(4, 2, 3, 1)))
        // Two neighbours change places: nothing tells which one was dragged, so both count.
        assertEquals(setOf(1, 2), moved(listOf(1, 2), listOf(2, 1)))
        assertEquals(setOf(2, 3), moved(listOf(1, 2, 3, 4), listOf(1, 3, 2, 4)))
        // Arrivals and departures are not moves.
        assertEquals(emptySet<Int>(), moved(listOf(1, 2, 3), listOf(1, 3, 9)))
        assertEquals(emptySet<Int>(), moved(listOf(1, 2, 3), listOf(1, 2, 3)))
        assertEquals(emptySet<Int>(), moved(emptyList(), listOf(1)))
    }

    @Test
    fun registrationMovedByHandQueuesAtItsOwnMachineOnlyUntilItHasPlayed() {
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(
                    registration(1, paired = true),
                    registration(2, paired = true),
                    registration(6, paired = true),
                    registration(3, paired = true),
                    registration(4)
                ),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(9)),
                waiting = listOf(registration(5)),
                playingStartedAtMillis = 10_000L
            )
        )
        assertEquals(listOf(5, 1, 2, 6, 3), state.order("B"))

        // 3 is dragged to the front of machine A, 4 to the second place.
        val waitingByKey = state.queue("A")!!.allRegistrations.associateBy(Registration::key)
        state = apply(
            state,
            QueueAction.ReplaceOrder("A", listOf(8, 3, 4, 1, 2, 6).map(waitingByKey::getValue))
        )
        assertEquals(listOf(3, 4, 1, 2, 6), state.waitingKeys("A"))
        // 3 gave up its place on B; 1, 2 and 6 were not moved and keep theirs in the same order.
        assertEquals(listOf(5, 1, 2, 6), state.order("B"))
        val afterMove = state.queue("A")!!.waiting.associateBy(Registration::key)
        assertTrue(afterMove.getValue(3).skipsPairedMachineThisRound)
        assertTrue("玩家的选择仍然保留", afterMove.getValue(3).alsoQueuesAtPairedMachine)
        assertFalse(afterMove.getValue(1).skipsPairedMachineThisRound)
        // 4 was moved as well, but it never queued at both machines: nothing to give up.
        assertFalse(afterMove.getValue(4).skipsPairedMachineThisRound)

        // Turning the choice off and on again does not bring the place back in this round.
        state = apply(state, QueueAction.SetPairedMachineQueueing("A", 3, false))
        state = apply(state, QueueAction.SetPairedMachineQueueing("A", 3, true))
        assertEquals(listOf(5, 1, 2, 6), state.order("B"))

        // Machine B is reached first, twice: it never takes 3.
        state = apply(state, QueueAction.FinishRound("B"), atMillis = 110_000L)
        assertEquals(listOf(5), state.playingKeys("B"))
        state = apply(state, QueueAction.FinishRound("B"), atMillis = 120_000L)
        assertEquals(listOf(1), state.playingKeys("B"))
        assertEquals(listOf(3, 4, 2, 6), state.waitingKeys("A"))

        // 3 plays on its own machine. After that round it queues at both machines again.
        state = apply(state, QueueAction.FinishRound("A"), atMillis = 130_000L)
        assertEquals(listOf(3), state.playingKeys("A"))
        assertFalse(state.queue("A")!!.playing.single().skipsPairedMachineThisRound)
        state = apply(state, QueueAction.FinishRound("A"), atMillis = 140_000L)
        assertEquals(listOf(4), state.playingKeys("A"))
        assertTrue(3 in state.waitingKeys("A"))
        assertTrue("游玩一轮后重新在另一机台排队", 3 in state.order("B"))
    }

    @Test
    fun declaredMovedRegistrationDecidesBetweenTwoNeighbours() {
        val initial = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(registration(1, paired = true), registration(2, paired = true)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(9)),
                playingStartedAtMillis = 10_000L
            )
        )
        val byKey = initial.queue("A")!!.allRegistrations.associateBy(Registration::key)
        val swapped = listOf(8, 2, 1).map(byKey::getValue)

        // The terminal knows that 2 was dragged: only 2 gives up its place.
        val declared = apply(
            initial,
            QueueAction.ReplaceOrder("A", swapped, movedRegistrationKeys = setOf(2))
        )
        assertEquals(listOf(1), declared.order("B"))

        // An order that arrives without that information: both changed places, both give up.
        val undeclared = apply(initial, QueueAction.ReplaceOrder("A", swapped))
        assertEquals(emptyList<Int>(), undeclared.order("B"))
    }

    @Test
    fun movingOneWaitingPositionGivesUpThePlaceOfThatPositionOnly() {
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(
                    registration(1, paired = true),
                    registration(2, paired = true),
                    registration(3, paired = true)
                ),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(9)),
                playingStartedAtMillis = 10_000L
            )
        )
        state = apply(state, QueueAction.MoveWaitingPosition("A", 0, 1))
        assertEquals(listOf(2, 1, 3), state.waitingKeys("A"))
        assertEquals(listOf(2, 3), state.order("B"))
    }

    @Test
    fun registrationTakenBackOutOfThePlayingPositionKeepsOnlyItsOwnMachineForThisRound() {
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1, paired = true)),
                waiting = listOf(registration(2, paired = true)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(9)),
                waiting = listOf(registration(5)),
                playingStartedAtMillis = 10_000L
            )
        )
        state = apply(state, QueueAction.ReturnPlayingToWaitingFront("A", setOf(1)))
        assertEquals(listOf(1, 2), state.waitingKeys("A"))
        assertEquals(listOf(5, 2), state.order("B"))
        assertTrue(state.queue("A")!!.waiting.first().skipsPairedMachineThisRound)

        // Back in the playing position the round it gave the place up for is over.
        state = apply(state, QueueAction.EnterPlayingPosition("A"))
        assertEquals(listOf(1), state.playingKeys("A"))
        assertFalse(state.queue("A")!!.playing.single().skipsPairedMachineThisRound)
    }

    // ---- machines out of operation and limits --------------------------------------------

    @Test
    fun stoppedMachineIsNotStartedButItsRegistrationsCanPlayOnTheOtherMachine() {
        val stoppedA = pairedPolicy.copy(
            machineStatuses = mapOf("A" to MachineStatus().stop(MachineStopReason.MAINTENANCE, 1_000L))
        )
        var state = stateOf(
            "A" to MachineQueue(waiting = listOf(registration(1, paired = true))),
            "B" to MachineQueue(playing = listOf(registration(9)), playingStartedAtMillis = 10_000L),
            policy = stoppedA
        )
        assertEquals("停止使用的机台不会自行开始", emptyList<Int>(), state.playingKeys("A"))
        state = apply(state, QueueAction.FinishRound("B"), policy = stoppedA)
        assertEquals(listOf(1), state.playingKeys("B"))
        assertEquals(emptyList<Int>(), state.waitingKeys("A"))

        val stoppedB = pairedPolicy.copy(
            machineStatuses = mapOf("B" to MachineStatus().stop(MachineStopReason.MAINTENANCE, 1_000L))
        )
        val idleB = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(registration(1, paired = true)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(),
            policy = stoppedB
        )
        assertEquals(emptyList<Int>(), idleB.playingKeys("B"))
        assertEquals(listOf(1), idleB.waitingKeys("A"))
    }

    @Test
    fun fullMachineDoesNotReceiveRegistrationsOfTheOtherMachine() {
        val full = (100 until 119).map { registration(it) }
        var state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(registration(1, paired = true)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(9)),
                waiting = full,
                playingStartedAtMillis = 10_000L
            )
        )
        assertEquals(20, state.queue("B")!!.registrationCount)
        repeat(25) { state = apply(state, QueueAction.FinishRound("B")) }
        assertEquals(20, state.queue("B")!!.registrationCount)
        assertEquals(listOf(1), state.waitingKeys("A"))
    }

    @Test
    fun machinesOfDifferentCapacityAreNotTreatedAsAPair() {
        val mismatched = pairedPolicy.copy(machineCapacities = mapOf("A" to 1, "B" to 2))
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(registration(1, paired = true)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(),
            policy = mismatched
        )
        assertTrue(state.pairedMachineOrders.isEmpty())
        assertFalse(state.queue("A")!!.waiting.single().alsoQueuesAtPairedMachine)
        assertEquals(
            QueueActionFailureCode.PAIRED_MACHINE_QUEUE_UNAVAILABLE,
            failureOf(state, QueueAction.SetPairedMachineQueueing("A", 1, true), mismatched)
        )
    }

    // ---- moving by hand ------------------------------------------------------------------

    @Test
    fun manualTransferWithinThePairGoesToTheTailOfBothAndTransferElsewhereEndsTheChoice() {
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                waiting = listOf(registration(1, paired = true), registration(2)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(9)),
                waiting = listOf(registration(5)),
                playingStartedAtMillis = 10_000L
            ),
            "C" to MachineQueue(playing = listOf(registration(7)), playingStartedAtMillis = 10_000L)
        )
        assertEquals(listOf(5, 1), state.order("B"))

        // Moved by hand to the other machine of the pair: tail there, and a place at the tail
        // of the machine it came from. The choice stays on.
        val withinPair = apply(state, QueueAction.TransferRegistrations("A", "B", setOf(1)))
        assertEquals(listOf(5, 1), withinPair.waitingKeys("B"))
        assertEquals(listOf(2, 1), withinPair.order("A"))
        assertEquals(listOf(5, 1), withinPair.order("B"))
        val moved = withinPair.queue("B")!!.waiting.last()
        assertTrue(moved.alsoQueuesAtPairedMachine)
        assertFalse(moved.skipsPairedMachineThisRound)

        // Moved anywhere else, the choice ends.
        val elsewhere = apply(state, QueueAction.TransferRegistrations("A", "C", setOf(1)))
        assertEquals(listOf(1), elsewhere.waitingKeys("C"))
        assertFalse(elsewhere.queue("C")!!.waiting.single().alsoQueuesAtPairedMachine)
        assertEquals(listOf(5), elsewhere.order("B"))
    }

    @Test
    fun temporaryRegistrationIsNeverArrangedBetweenTwoMachines() {
        val temporary = registration(1).copy(isTemporary = true)
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(8)),
                // Stored with the choice on, as an older version might have left it.
                waiting = listOf(temporary.copy(alsoQueuesAtPairedMachine = true)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(playing = listOf(registration(9)), playingStartedAtMillis = 10_000L)
        )
        assertFalse(state.queue("A")!!.waiting.single().alsoQueuesAtPairedMachine)
        assertEquals(emptyList<Int>(), state.order("B"))
        assertEquals(
            QueueActionFailureCode.PAIRED_MACHINE_QUEUE_NEEDS_PLAYER_PROFILE,
            failureOf(state, QueueAction.SetPairedMachineQueueing("A", 1, true))
        )
    }

    // ---- confirmations -------------------------------------------------------------------

    @Test
    fun confirmationBecomesStaleWhenTheOtherMachineChanges() {
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(3, paired = true)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(playing = listOf(registration(2)), playingStartedAtMillis = 10_000L)
        )
        val context = QueueActionContext(atMillis = 100_000L, policy = pairedPolicy)
        val plan = QueueEngine.plan(state, QueueAction.FinishRound("A"), context)
        val changed = apply(state, QueueAction.FinishRound("B"))
        val execution = plan.applyTo(changed, pairedPolicy, 100_000L)
        assertEquals(
            QueueActionFailureCode.STALE_STATE,
            (execution as QueueActionExecution.Rejected).failure.code
        )
    }

    @Test
    fun undoRestoresBothMachinesAndThePlacesHeldAcrossThem() {
        val before = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(3, paired = true), registration(4)),
                playingStartedAtMillis = 10_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(2)),
                waiting = listOf(registration(5, paired = true)),
                playingStartedAtMillis = 10_000L
            )
        )
        assertEquals(listOf(3, 4, 5), before.order("A"))
        // Registration 5 plays on B; registration 3 keeps waiting on A with its place on B.
        var after = apply(before, QueueAction.FinishRound("B"))
        assertEquals(listOf(5), after.playingKeys("B"))
        after = apply(after, QueueAction.FinishRound("B"))
        assertEquals("机台 B 轮到另一机台的位置", listOf(3), after.playingKeys("B"))
        val beforeSecondRound = apply(before, QueueAction.FinishRound("B"))

        val restored = apply(
            after,
            QueueAction.RestoreSnapshot(
                machineId = "B",
                expectedCurrentQueue = after.queue("B")!!,
                restoredQueue = beforeSecondRound.queue("B")!!,
                alsoRestoredQueues = mapOf(
                    "A" to QueueAction.RestoredMachineQueue(
                        expectedCurrentQueue = after.queue("A")!!,
                        restoredQueue = beforeSecondRound.queue("A")!!
                    )
                ),
                restoredPairedMachineOrders = beforeSecondRound.pairedMachineOrders
            )
        )
        assertEquals(beforeSecondRound, restored)

        // The other machine changed in the meantime: the undo is refused instead of guessing.
        val moved = apply(after, QueueAction.FinishRound("A"))
        assertEquals(
            QueueActionFailureCode.STALE_STATE,
            failureOf(
                moved,
                QueueAction.RestoreSnapshot(
                    machineId = "B",
                    expectedCurrentQueue = moved.queue("B")!!,
                    restoredQueue = beforeSecondRound.queue("B")!!,
                    alsoRestoredQueues = mapOf(
                        "A" to QueueAction.RestoredMachineQueue(
                            expectedCurrentQueue = after.queue("A")!!,
                            restoredQueue = beforeSecondRound.queue("A")!!
                        )
                    ),
                    restoredPairedMachineOrders = beforeSecondRound.pairedMachineOrders
                )
            )
        )
    }

    @Test
    fun machinesWithoutAPairBehaveExactlyAsBefore() {
        val unpaired = QueueEnginePolicy(requireOperationalForPlayerActions = true)
        val queue = MachineQueue(
            playing = listOf(registration(1)),
            waiting = listOf(registration(3, paired = true)),
            playingStartedAtMillis = 10_000L
        )
        val state = QueueEngineState(linkedMapOf("A" to queue, "B" to MachineQueue()))
        val result = apply(state, QueueAction.FinishRound("A"), policy = unpaired)
        assertTrue(result.pairedMachineOrders.isEmpty())
        assertNull(result.allRegistrations.firstOrNull(Registration::alsoQueuesAtPairedMachine))
        assertEquals(listOf(3), result.playingKeys("A"))
        assertEquals(emptyList<Int>(), result.playingKeys("B"))
    }

    // ---- long random sequences -----------------------------------------------------------

    @Test
    fun randomSequencesNeverBreakTheQueuesOrDuplicateARegistration() {
        var movedThroughAPlace = 0
        repeat(60) { seed ->
            val random = Random(seed)
            val capacity = if (seed % 3 == 0) 1 else 2
            val policy = pairedPolicy.copy(
                machineCapacities = mapOf("A" to capacity, "B" to capacity, "C" to 2)
            )
            var state = stateOf(
                "A" to MachineQueue(),
                "B" to MachineQueue(),
                "C" to MachineQueue(),
                policy = policy
            )
            var nextKey = 1
            repeat(260) { step ->
                val machineId = listOf("A", "B", "C").random(random)
                val queue = state.queue(machineId)!!
                val anyKey = queue.allRegistrations.randomOrNull(random)?.key
                val waitingKey = queue.waiting.randomOrNull(random)?.key
                val action: QueueAction? = when (random.nextInt(14)) {
                    0, 1, 2 -> {
                        val preference = if (capacity == 1 && machineId != "C" || random.nextBoolean()) {
                            PlayPreference.SOLO
                        } else {
                            PlayPreference.OPEN_TO_JOIN
                        }
                        QueueAction.AddRegistrations(
                            machineId,
                            listOf(
                                registration(
                                    key = nextKey++,
                                    preference = preference,
                                    paired = random.nextInt(3) != 0,
                                    pendingCheckIn = random.nextInt(8) == 0
                                )
                            ),
                            RegistrationPlacement.AUTO_ADVANCE
                        )
                    }
                    3, 4, 5 -> QueueAction.FinishRound(machineId)
                    6 -> QueueAction.EnterPlayingPosition(machineId)
                    7 -> anyKey?.let { QueueAction.DeferOneRound(machineId, it) }
                    8 -> anyKey?.let { QueueAction.TemporarilyLeave(machineId, it) }
                    9 -> waitingKey?.let {
                        if (random.nextBoolean()) {
                            QueueAction.CancelDeferOneRound(machineId, it)
                        } else {
                            QueueAction.CancelTemporaryLeave(machineId, it)
                        }
                    }
                    10 -> anyKey?.let { QueueAction.RemoveRegistrations(machineId, setOf(it)) }
                    11 -> anyKey?.let {
                        QueueAction.SetPairedMachineQueueing(machineId, it, random.nextBoolean())
                    }
                    12 -> waitingKey?.let { QueueAction.CheckIn(machineId, it) }
                    else -> waitingKey?.let {
                        val destination = listOf("A", "B", "C").filter { id -> id != machineId }
                            .random(random)
                        QueueAction.TransferRegistrations(machineId, destination, setOf(it))
                    }
                }
                if (action != null) {
                    val execution = QueueEngine.execute(
                        state,
                        action,
                        QueueActionContext(atMillis = 100_000L + step, policy = policy)
                    )
                    if (execution is QueueActionExecution.Applied) {
                        if (action !is QueueAction.TransferRegistrations) {
                            val before = state.queues.flatMap { (id, q) ->
                                q.allRegistrations.map { it.key to id }
                            }.toMap()
                            movedThroughAPlace += execution.state.queues.entries.sumOf { (id, q) ->
                                q.allRegistrations.count { before[it.key]?.let { old -> old != id } == true }
                            }
                        }
                        state = execution.state
                        val label = "种子 $seed 第 $step 步 $action"
                        assertTrue(
                            "$label：${state.invariantViolations()}",
                            state.invariantViolations().isEmpty()
                        )
                        val keys = state.allRegistrations.map(Registration::key)
                        assertEquals(label, keys.distinct(), keys)
                        assertEquals(
                            "$label：状态应当已经整理完毕",
                            state,
                            QueueEngine.settlePairedMachines(state, policy, 100_000L + step)
                        )
                        assertTrue(
                            "$label：单台机台不能超过 20 份登记",
                            state.queues.values.all { it.registrationCount <= 20 }
                        )
                        assertTrue(
                            "$label：没有配对的机台不应出现同时排队的登记",
                            state.queue("C")!!.allRegistrations.none(Registration::alsoQueuesAtPairedMachine)
                        )
                    }
                }
            }
        }
        assertTrue("随机序列应当多次经由另一机台的位置换机台：$movedThroughAPlace", movedThroughAPlace > 200)
    }
}
