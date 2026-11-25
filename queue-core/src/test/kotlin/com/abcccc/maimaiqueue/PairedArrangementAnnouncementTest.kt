package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the maintainer set for arranging a registration to the other machine of its pair:
 * it is announced by a preview first and only happens after the preview has been shown for
 * twelve minutes without a break, while the queues are kept up normally.
 *
 * In every scenario machines A and B form a pair, hold one player per round and a round is
 * expected to take ten minutes. Registration 9 is the one that lets the system arrange it.
 */
class PairedArrangementAnnouncementTest {
    private val minute = 60_000L
    private val start = 1_000_000_000L
    private val rules = PairedArrangementRules()
    private val policy = QueueEnginePolicy(
        machinePartners = mapOf("A" to "B", "B" to "A"),
        machineCapacities = mapOf("A" to 1, "B" to 1),
        requireOperationalForPlayerActions = true,
        pairedArrangementRules = rules
    )
    private val durations = listOf("A", "B").associateWith {
        PairedMachineForecast.RoundDurations(soloMillis = 10 * minute, sharedMillis = 10 * minute)
    }

    private fun registration(key: Int, arranged: Boolean = false) = Registration(
        key = key,
        displayId = "玩家$key",
        preference = PlayPreference.SOLO,
        isTemporary = false,
        createdAtMillis = key * 1_000L,
        alsoQueuesAtPairedMachine = arranged
    )

    /**
     * Machine A's round began [elapsedOnA] minutes ago with registration 1; 2, 3 and 9 wait
     * there. Machine B's round began [elapsedOnB] minutes ago with registration 5.
     */
    private fun scenario(
        elapsedOnA: Int,
        elapsedOnB: Int,
        waitingOnA: List<Int> = listOf(2, 3, 9),
        waitingOnB: List<Int> = emptyList(),
        usedPolicy: QueueEnginePolicy = policy
    ): QueueEngineState = QueueEngine.settlePairedMachines(
        QueueEngineState(
            linkedMapOf(
                "A" to MachineQueue(
                    playing = listOf(registration(1)),
                    waiting = waitingOnA.map { registration(it, arranged = it == 9) },
                    playingStartedAtMillis = start - elapsedOnA * minute
                ),
                "B" to MachineQueue(
                    playing = listOf(registration(5)),
                    waiting = waitingOnB.map { registration(it) },
                    playingStartedAtMillis = start - elapsedOnB * minute
                )
            )
        ),
        usedPolicy,
        start
    )

    private fun apply(
        state: QueueEngineState,
        action: QueueAction,
        atMinute: Int,
        usedPolicy: QueueEnginePolicy = policy
    ): QueueEngineState {
        val execution = QueueEngine.execute(
            state,
            action,
            QueueActionContext(atMillis = start + atMinute * minute, policy = usedPolicy)
        )
        assertTrue("动作应成功：$action，实际为 $execution", execution is QueueActionExecution.Applied)
        return (execution as QueueActionExecution.Applied).state.also { result ->
            assertTrue(result.invariantViolations().joinToString(), result.invariantViolations().isEmpty())
        }
    }

    private fun refresh(
        state: QueueEngineState,
        atMinute: Int,
        usedPolicy: QueueEnginePolicy = policy
    ): PairedArrangementRefresh =
        QueueEngine.refreshPairedArrangements(state, usedPolicy, durations, start + atMinute * minute)

    private fun QueueEngineState.playing(machineId: String) = queue(machineId)!!.playing.map { it.key }
    private fun QueueEngineState.waiting(machineId: String) = queue(machineId)!!.waiting.map { it.key }
    private fun QueueEngineState.previewSince(key: Int) = pairedArrangements[key]?.previewSinceMillis

    @Test
    fun withoutAnAnnouncementARegistrationIsNotArranged() {
        var state = scenario(elapsedOnA = 6, elapsedOnB = 0)
        assertEquals(listOf(9), state.pairedMachineOrders["B"])
        // Machine B ends two rounds; nobody has seen a preview, so 9 stays where it is.
        state = apply(state, QueueAction.FinishRound("B"), atMinute = 10)
        state = apply(state, QueueAction.FinishRound("B"), atMinute = 20)
        assertEquals(listOf(5), state.playing("B"))
        assertTrue(9 in state.waiting("A"))
        assertEquals("它在另一机台的先后仍然保留", listOf(9), state.pairedMachineOrders["B"])
    }

    @Test
    fun previewAppearsWithAClearLeadAndTheArrangementWaitsTwelveMinutes() {
        // Own machine: 24 minutes. Other machine, announced from now on: 20 minutes.
        var state = scenario(elapsedOnA = 6, elapsedOnB = 0)
        // 6 joins machine B after 9 has taken its place there.
        state = apply(
            state,
            QueueAction.AddRegistrations(
                "B",
                listOf(registration(6)),
                RegistrationPlacement.ADVANCE_IF_UNAMBIGUOUS
            ),
            atMinute = 0
        )
        assertEquals(listOf(9, 6), state.pairedMachineOrders["B"])

        val first = refresh(state, atMinute = 0)
        state = first.state
        assertEquals(start, state.previewSince(9))
        assertTrue(first.notices.isEmpty())

        state = apply(state, QueueAction.FinishRound("A"), atMinute = 4)
        state = refresh(state, atMinute = 4).state
        assertEquals("预览没有中断", start, state.previewSince(9))

        // Five minutes of preview: the player is told once, with the earliest moment.
        val told = refresh(state, atMinute = 5)
        state = told.state
        assertEquals(
            listOf(PairedArrangementNotice(9, "A", "B", start + 12 * minute)),
            told.notices
        )
        assertTrue(refresh(state, atMinute = 6).notices.isEmpty())

        // Ten minutes in, machine B is reached: too early, so 9 is passed over this time...
        state = apply(state, QueueAction.FinishRound("B"), atMinute = 10)
        assertEquals(listOf(6), state.playing("B"))
        assertTrue(9 in state.waiting("A"))
        // ...and keeps its position ahead of the registration that went back to the tail.
        assertEquals(listOf(9, 5), state.pairedMachineOrders["B"])

        state = apply(state, QueueAction.FinishRound("A"), atMinute = 14)
        state = refresh(state, atMinute = 14).state
        assertEquals(start, state.previewSince(9))

        // Twenty minutes in the preview has run long enough: 9 is arranged to machine B.
        state = apply(state, QueueAction.FinishRound("B"), atMinute = 20)
        assertEquals(listOf(9), state.playing("B"))
        assertFalse(9 in state.waiting("A"))
        assertEquals(
            PairedArrangementRecord(arrangedFromMachineId = "A", aheadKeysAtOrigin = emptyList()),
            state.pairedArrangements[9]
        )
    }

    @Test
    fun outlookTellsThePlayerFromWhenOnTheRegistrationMayBeArranged() {
        var state = scenario(elapsedOnA = 6, elapsedOnB = 0)
        assertNull("没有预览时没有可说的", QueueEngine.pairedArrangementOutlook(state, policy, 9))

        state = refresh(state, atMinute = 0).state
        assertEquals(
            PairedArrangementOutlook(notBeforeMillis = start + 12 * minute),
            QueueEngine.pairedArrangementOutlook(state, policy, 9)
        )
        // Machine B's round runs past twenty minutes: the count is held, so no time is promised.
        state = refresh(state, atMinute = 21).state
        assertEquals(
            PairedArrangementOutlook(notBeforeMillis = null),
            QueueEngine.pairedArrangementOutlook(state, policy, 9)
        )
        assertNull(
            "没有这套规则时不预告",
            QueueEngine.pairedArrangementOutlook(
                state,
                policy.copy(pairedArrangementRules = null),
                9
            )
        )
    }

    @Test
    fun timeInWhichTheTerminalDidNotRunDoesNotCountTowardsAnAnnouncement() {
        val records = mapOf(
            9 to PairedArrangementRecord(
                previewSinceMillis = start,
                noticedAtMillis = start + 5 * minute
            ),
            8 to PairedArrangementRecord(
                previewSinceMillis = start,
                previewPausedAtMillis = start + 2 * minute
            ),
            7 to PairedArrangementRecord(arrangedFromMachineId = "A", aheadKeysAtOrigin = listOf(2))
        )
        // Saved at minute 6, started again at minute 16: ten minutes without a preview.
        val resumed = QueueEngine.pairedArrangementsAfterDowntime(
            records,
            stoppedAtMillis = start + 6 * minute,
            nowMillis = start + 16 * minute
        )
        assertEquals(start + 10 * minute, resumed[9]?.previewSinceMillis)
        assertEquals("已经发出的通知不重复", start + 5 * minute, resumed[9]?.noticedAtMillis)
        assertEquals(start + 10 * minute, resumed[8]?.previewSinceMillis)
        assertEquals(start + 12 * minute, resumed[8]?.previewPausedAtMillis)
        assertEquals(records[7], resumed[7])
        // A clock that went backwards changes nothing.
        assertEquals(
            records,
            QueueEngine.pairedArrangementsAfterDowntime(records, start + 6 * minute, start)
        )
    }

    @Test
    fun undoingARoundBringsTheAnnouncementBack() {
        var state = refresh(scenario(elapsedOnA = 6, elapsedOnB = 0), atMinute = 0).state
        val beforeRound = state
        // Fourteen minutes in the announcement has run long enough: 9 is arranged to B.
        state = apply(state, QueueAction.FinishRound("B"), atMinute = 14)
        assertEquals(listOf(9), state.playing("B"))
        assertNull(state.previewSince(9))

        state = apply(
            state,
            QueueAction.RestoreSnapshot(
                machineId = "B",
                expectedCurrentQueue = state.queue("B")!!,
                restoredQueue = beforeRound.queue("B")!!,
                alsoRestoredQueues = mapOf(
                    "A" to QueueAction.RestoredMachineQueue(
                        expectedCurrentQueue = state.queue("A")!!,
                        restoredQueue = beforeRound.queue("A")!!
                    )
                ),
                restoredPairedMachineOrders = beforeRound.pairedMachineOrders,
                restoredPairedArrangements = beforeRound.pairedArrangements
            ),
            atMinute = 15
        )
        assertEquals(listOf(5), state.playing("B"))
        assertTrue(9 in state.waiting("A"))
        assertEquals("预览从原来的时刻算起", start, state.previewSince(9))
    }

    @Test
    fun absentArrivalsAreToldApartFromOrdinaryNoShows() {
        val state = QueueEngine.settlePairedMachines(
            QueueEngineState(
                queues = linkedMapOf(
                    "A" to MachineQueue(
                        playing = listOf(registration(1)),
                        waiting = listOf(registration(2)),
                        playingStartedAtMillis = start
                    ),
                    "B" to MachineQueue(
                        playing = listOf(registration(9, arranged = true)),
                        waiting = listOf(registration(5)),
                        playingStartedAtMillis = start
                    )
                ),
                pairedArrangements = mapOf(
                    9 to PairedArrangementRecord(arrangedFromMachineId = "A")
                )
            ),
            policy,
            start
        )
        assertEquals(mapOf(9 to "A"), QueueEngine.absentArrivalOrigins(state, policy, "B", setOf(9)))
        // Registration 1 plays at its own machine: the ordinary rules apply to it.
        assertTrue(QueueEngine.absentArrivalOrigins(state, policy, "A", setOf(1)).isEmpty())
        // Machine A is out of operation: 9 cannot go back.
        val stoppedA = policy.copy(
            machineStatuses = mapOf("A" to MachineStatus().stop(MachineStopReason.MAINTENANCE, start))
        )
        assertTrue(QueueEngine.absentArrivalOrigins(state, stoppedA, "B", setOf(9)).isEmpty())
    }

    @Test
    fun refreshingAgainAtTheSameMomentChangesNothing() {
        // The terminal refreshes after every change of the queues, its own included.
        listOf(0, 5, 12, 21).fold(scenario(elapsedOnA = 6, elapsedOnB = 0)) { state, atMinute ->
            val once = refresh(state, atMinute)
            val twice = refresh(once.state, atMinute)
            assertEquals("第 $atMinute 分钟", once.state, twice.state)
            assertTrue("通知只发一次", twice.notices.isEmpty())
            once.state
        }
    }

    @Test
    fun transferByHandStartsTheAnnouncementAfresh() {
        // 9 waits at machine A and its preview at machine B has been shown for 13 minutes.
        var state = QueueEngine.settlePairedMachines(
            QueueEngineState(
                queues = linkedMapOf(
                    "A" to MachineQueue(
                        playing = listOf(registration(1)),
                        waiting = listOf(registration(9, arranged = true)),
                        playingStartedAtMillis = start
                    ),
                    "B" to MachineQueue(
                        playing = listOf(registration(5)),
                        playingStartedAtMillis = start
                    )
                ),
                pairedArrangements = mapOf(
                    9 to PairedArrangementRecord(
                        previewSinceMillis = start - 13 * minute,
                        noticedAtMillis = start - 8 * minute
                    )
                )
            ),
            policy,
            start
        )
        assertEquals(start - 13 * minute, state.previewSince(9))

        // Its player has it moved to machine B by hand.
        state = apply(
            state,
            QueueAction.TransferRegistrations("A", "B", setOf(9)),
            atMinute = 0
        )
        assertEquals(listOf(9), state.waiting("B"))
        assertEquals("它在原机台的先后从队尾重新取得", listOf(9), state.pairedMachineOrders["A"])
        // That preview announced a move to machine B. Nothing has announced a move back to A.
        assertNull(state.pairedArrangements[9])

        // Machine A ends its round with nobody of its own waiting: 9 is not taken back at once.
        state = apply(state, QueueAction.FinishRound("A"), atMinute = 1)
        assertEquals(listOf(9), state.waiting("B"))
        assertFalse(9 in state.playing("A"))
    }

    @Test
    fun previewNeedsThreeMinutesToAppearAndBreaksAtOneMinute() {
        // The lead of the other machine is 10 + elapsedOnB - elapsedOnA minutes.
        val twoMinutes = scenario(elapsedOnA = 8, elapsedOnB = 0)
        assertNull("两分钟不足以出现预览", refresh(twoMinutes, atMinute = 0).state.previewSince(9))

        val alreadyShown = twoMinutes.copy(
            pairedArrangements = mapOf(9 to PairedArrangementRecord(previewSinceMillis = start))
        )
        assertEquals(
            "已经显示的预览在两分钟时保留",
            start,
            refresh(alreadyShown, atMinute = 0).state.previewSince(9)
        )

        val oneMinute = scenario(elapsedOnA = 9, elapsedOnB = 0).copy(
            pairedArrangements = mapOf(9 to PairedArrangementRecord(previewSinceMillis = start))
        )
        assertNull("只早一分钟不算更早", refresh(oneMinute, atMinute = 0).state.previewSince(9))

        val fourMinutes = scenario(elapsedOnA = 6, elapsedOnB = 0)
        assertEquals(start, refresh(fourMinutes, atMinute = 0).state.previewSince(9))
    }

    @Test
    fun countIsPausedWhileARoundIsOverdue() {
        // Machine B's round has been running for 21 minutes: its estimate cannot be trusted.
        var state = scenario(elapsedOnA = 6, elapsedOnB = 21)
        state = refresh(state, atMinute = 0).state
        assertEquals(start, state.previewSince(9))
        assertEquals(start, state.pairedArrangements[9]?.previewPausedAtMillis)

        // Someone ends that round; a minute later the count goes on, a minute poorer.
        state = apply(state, QueueAction.FinishRound("B"), atMinute = 0)
        state = refresh(state, atMinute = 1).state
        assertEquals(start + minute, state.previewSince(9))
        assertNull(state.pairedArrangements[9]?.previewPausedAtMillis)
    }

    @Test
    fun emptyMachineAlsoWaitsForTheAnnouncementEvenForANewRegistration() {
        var state = QueueEngine.settlePairedMachines(
            QueueEngineState(
                linkedMapOf(
                    "A" to MachineQueue(
                        playing = listOf(registration(1)),
                        waiting = listOf(registration(2)),
                        playingStartedAtMillis = start
                    ),
                    "B" to MachineQueue()
                )
            ),
            policy,
            start
        )
        state = apply(
            state,
            QueueAction.AddRegistrations(
                "A",
                listOf(registration(9, arranged = true)),
                RegistrationPlacement.ADVANCE_IF_UNAMBIGUOUS
            ),
            atMinute = 0
        )
        // Machine B stands empty, yet the new registration is not moved there at once.
        assertTrue(state.playing("B").isEmpty())
        assertEquals(listOf(2, 9), state.waiting("A"))

        state = refresh(state, atMinute = 0).state
        assertEquals(start, state.previewSince(9))
        state = apply(state, QueueAction.FinishRound("A"), atMinute = 10)
        state = refresh(state, atMinute = 11).state
        assertTrue("还不满十二分钟", state.playing("B").isEmpty())

        // Twelve minutes after the preview appeared the empty machine takes it.
        state = refresh(state, atMinute = 12).state
        assertEquals(listOf(9), state.playing("B"))
        assertEquals("A", state.pairedArrangements[9]?.arrangedFromMachineId)
    }

    @Test
    fun registrationOnAStoppedMachineIsAnnouncedAndThenArranged() {
        val stoppedA = policy.copy(
            machineStatuses = mapOf("A" to MachineStatus().stop(MachineStopReason.MAINTENANCE, start))
        )
        var state = QueueEngine.settlePairedMachines(
            QueueEngineState(
                linkedMapOf(
                    "A" to MachineQueue(waiting = listOf(registration(9, arranged = true))),
                    "B" to MachineQueue(
                        playing = listOf(registration(5)),
                        playingStartedAtMillis = start
                    )
                )
            ),
            stoppedA,
            start
        )
        // Its own machine never reaches it, so the preview appears whatever the lead.
        state = refresh(state, atMinute = 0, usedPolicy = stoppedA).state
        assertEquals(start, state.previewSince(9))
        state = apply(state, QueueAction.FinishRound("B"), atMinute = 10, usedPolicy = stoppedA)
        assertEquals(listOf(5), state.playing("B"))
        state = apply(state, QueueAction.FinishRound("B"), atMinute = 20, usedPolicy = stoppedA)
        assertEquals(listOf(9), state.playing("B"))
    }

    @Test
    fun arrangedRegistrationThatDoesNotShowUpGoesBackToItsOldPlace() {
        val arrived = registration(9, arranged = true)
        var state = QueueEngine.settlePairedMachines(
            QueueEngineState(
                queues = linkedMapOf(
                    "A" to MachineQueue(
                        playing = listOf(registration(1)),
                        waiting = listOf(registration(2), registration(3)),
                        playingStartedAtMillis = start
                    ),
                    "B" to MachineQueue(
                        playing = listOf(arrived),
                        waiting = listOf(registration(5)),
                        playingStartedAtMillis = start
                    )
                ),
                // When it was arranged, 2 stood ahead of it at machine A and 3 behind it.
                pairedArrangements = mapOf(
                    9 to PairedArrangementRecord(
                        arrangedFromMachineId = "A",
                        aheadKeysAtOrigin = listOf(2)
                    )
                )
            ),
            policy,
            start
        )
        assertEquals("A", state.pairedArrangements[9]?.arrangedFromMachineId)

        state = apply(
            state,
            QueueAction.MarkNoShow("B", setOf(9), NoShowResolution.MOVE_TO_TAIL),
            atMinute = 1
        )
        // Back at machine A, between the registration that was ahead of it and the one behind.
        assertEquals(listOf(2, 9, 3), state.waiting("A"))
        val back = state.queue("A")!!.waiting[1]
        assertFalse("这项选择自动关闭", back.alsoQueuesAtPairedMachine)
        assertEquals("不记未到场", 0, back.noShowCount)
        assertNull(state.pairedArrangements[9])
        // Machine B goes on with its own line.
        assertEquals(listOf(5), state.playing("B"))
    }

    @Test
    fun arrangedRegistrationThatCannotGoBackFollowsTheOrdinaryNoShowRule() {
        val stoppedA = policy.copy(
            machineStatuses = mapOf("A" to MachineStatus().stop(MachineStopReason.MAINTENANCE, start))
        )
        var state = QueueEngine.settlePairedMachines(
            QueueEngineState(
                queues = linkedMapOf(
                    "A" to MachineQueue(waiting = listOf(registration(2))),
                    "B" to MachineQueue(
                        playing = listOf(registration(9, arranged = true)),
                        waiting = listOf(registration(5)),
                        playingStartedAtMillis = start
                    )
                ),
                pairedArrangements = mapOf(
                    9 to PairedArrangementRecord(arrangedFromMachineId = "A")
                )
            ),
            stoppedA,
            start
        )
        state = apply(
            state,
            QueueAction.MarkNoShow("B", setOf(9), NoShowResolution.MOVE_TO_TAIL),
            atMinute = 1,
            usedPolicy = stoppedA
        )
        assertEquals(listOf(2), state.waiting("A"))
        assertEquals(listOf(9), state.waiting("B"))
        assertEquals(1, state.queue("B")!!.waiting.single().noShowCount)
    }

    @Test
    fun withoutTheRulesNothingIsAnnouncedAndArrangingIsImmediate() {
        val immediate = policy.copy(pairedArrangementRules = null)
        var state = scenario(elapsedOnA = 6, elapsedOnB = 0, usedPolicy = immediate)
        assertEquals(state, refresh(state, atMinute = 0, usedPolicy = immediate).state)
        state = apply(state, QueueAction.FinishRound("B"), atMinute = 10, usedPolicy = immediate)
        assertEquals(listOf(9), state.playing("B"))
        assertTrue(state.pairedArrangements.isEmpty())
    }
}
