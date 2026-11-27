package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedMachineForecastTest {
    private val policy = QueueEnginePolicy(
        machinePartners = mapOf("A" to "B", "B" to "A"),
        requireOperationalForPlayerActions = true
    )
    private val tenMinutes = PairedMachineForecast.RoundDurations(
        soloMillis = 10 * MINUTE,
        sharedMillis = 14 * MINUTE
    )
    private val durations = mapOf("A" to tenMinutes, "B" to tenMinutes, "C" to tenMinutes)

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

    /**
     * Maintainer, 2026-11-27: where a registration that lets others pass and a preview of the
     * other machine compete for one position, the preview comes first and nobody passes: “这种处理
     * 方式是合理的……目前可以作为实际逻辑应用”. The home screen shows it so, and the round that
     * starts when the registration arrives does the same.
     */
    @Test
    fun anArrivingRegistrationTakesThePlaceAPassWouldHaveTaken() {
        fun at(key: Int, createdAt: Long, preference: PlayPreference, passable: Boolean = false, paired: Boolean = false) =
            Registration(
                key = key,
                displayId = "玩家$key",
                preference = preference,
                // Only a registration of a player profile can be arranged between two machines.
                isTemporary = !paired,
                createdAtMillis = createdAt,
                alsoQueuesAtPairedMachine = paired,
                allowsPassing = passable
            )
        val state = QueueEngine.settlePairedMachines(
            QueueEngineState(
                linkedMapOf(
                    "A" to MachineQueue(
                        playing = listOf(at(8, 500L, PlayPreference.SOLO)),
                        waiting = listOf(at(4, 1_500L, PlayPreference.OPEN_TO_JOIN, paired = true)),
                        playingStartedAtMillis = NOW
                    ),
                    "B" to MachineQueue(
                        playing = listOf(at(9, 400L, PlayPreference.SOLO)),
                        waiting = listOf(
                            at(1, 1_000L, PlayPreference.OPEN_TO_JOIN),
                            at(2, 2_000L, PlayPreference.SOLO, passable = true),
                            at(3, 3_000L, PlayPreference.OPEN_TO_JOIN)
                        ),
                        playingStartedAtMillis = NOW - 9 * MINUTE
                    )
                ),
                // 4 waits at A and holds a place at B between 1 and 2.
                pairedMachineOrders = mapOf("B" to listOf(1, 4, 2, 3))
            ),
            policy,
            NOW
        )
        // Without 4, 3 would pass 2 to play with 1.
        assertEquals(mapOf(3 to listOf(2)), state.queue("B")!!.projectedPasses())
        val forecast = PairedMachineForecast.forecast(state, policy, durations, NOW)
        assertEquals(setOf(4), forecast.arrivingKeys(state, "B"))
        val (shown, previewKeys) = PairedMachineForecast.displayQueue(state, policy, "B", forecast)
        assertEquals(setOf(4), previewKeys)
        val shownPositions = shown.waitingProjection(includeCommonPlayPreview = false).positions
            .map { position -> position.registrations.map(Registration::key) }
        assertEquals(listOf(listOf(1, 4), listOf(2), listOf(3)), shownPositions)
        assertEquals(emptyMap<Int, List<Int>>(), shown.projectedPasses())

        // B's round ends: 4 arrives and plays with 1; 2 is not passed.
        val finished = QueueEngine.execute(state, QueueAction.FinishRound("B"), QueueActionContext(atMillis = NOW + MINUTE, policy = policy))
        val machineB = (finished as QueueActionExecution.Applied).state.queue("B")!!
        assertEquals(setOf(1, 4), machineB.playing.map(Registration::key).toSet())
        assertTrue(machineB.playing.all { it.passedOverKeys.isEmpty() })
        assertEquals(listOf(2, 3), machineB.waiting.map(Registration::key).take(2))
    }

    private fun stateOf(vararg queues: Pair<String, MachineQueue>): QueueEngineState =
        QueueEngine.settlePairedMachines(QueueEngineState(linkedMapOf(*queues)), policy, NOW)

    @Test
    fun registrationIsExpectedOnTheMachineWhoseRoundEndsFirst() {
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(3, paired = true), registration(4)),
                playingStartedAtMillis = NOW
            ),
            "B" to MachineQueue(
                playing = listOf(registration(2)),
                playingStartedAtMillis = NOW - 6 * MINUTE
            )
        )
        val forecast = PairedMachineForecast.forecast(state, policy, durations, NOW)

        // Machine B ends in 4 minutes, machine A in 10.
        assertEquals(PairedMachineForecast.FirstTurn("B", 4 * MINUTE), forecast.firstTurns[3])
        // Registration 4 moves up on machine A once registration 3 has gone.
        assertEquals(PairedMachineForecast.FirstTurn("A", 10 * MINUTE), forecast.firstTurns[4])
        assertEquals(setOf(3), forecast.arrivingKeys(state, "B"))
        assertEquals(setOf(3), forecast.leavingKeys(state, "A"))
        assertTrue(forecast.arrivingKeys(state, "A").isEmpty())

        val (displayQueue, previewKeys) =
            PairedMachineForecast.displayQueue(state, policy, "B", forecast)
        assertEquals(listOf(3), displayQueue.waiting.map { it.key })
        assertEquals(setOf(3), previewKeys)
        // The forecast never changes the real queues.
        assertEquals(listOf(3, 4), state.queue("A")!!.waiting.map { it.key })
    }

    @Test
    fun waitMinutesUseTheEarliestExpectedTurnAndRoundUp() {
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(3, paired = true), registration(4)),
                playingStartedAtMillis = NOW - 30_000L
            ),
            "B" to MachineQueue(
                playing = listOf(registration(2)),
                playingStartedAtMillis = NOW - 6 * MINUTE
            ),
            "C" to MachineQueue(waiting = emptyList())
        )
        val forecast = PairedMachineForecast.forecast(state, policy, durations, NOW)

        assertEquals(setOf("A", "B"), forecast.machineIds)
        assertEquals(4L, forecast.waitMinutes(listOf(3)))
        // Nine and a half minutes are shown as ten.
        assertEquals(10L, forecast.waitMinutes(listOf(4)))
        assertEquals(4L, forecast.waitMinutes(listOf(4, 3)))
        assertEquals(null, forecast.waitMinutes(listOf(1)))
        assertEquals(null, forecast.waitMinutes(emptyList()))
        assertEquals("B", PairedMachineForecast.pairedMachineId(state, policy, "A"))
        assertEquals(null, PairedMachineForecast.pairedMachineId(state, policy, "C"))
    }

    @Test
    fun newRegistrationWaitsBehindPlacesHeldByTheOtherMachine() {
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(
                    registration(3, paired = true),
                    registration(4, paired = true)
                ),
                playingStartedAtMillis = NOW
            ),
            "B" to MachineQueue(
                playing = listOf(registration(2)),
                playingStartedAtMillis = NOW - 6 * MINUTE
            ),
            "C" to MachineQueue(waiting = emptyList())
        )

        // B is reached in 4 minutes by registration 3 and in 14 by registration 4 or 1; a new
        // registration on B waits behind the places both of them hold there.
        val onB = PairedMachineForecast.newRegistrationWaitMillis(state, policy, durations, NOW, "B")
        val forecast = PairedMachineForecast.forecast(state, policy, durations, NOW)
        assertEquals(PairedMachineForecast.FirstTurn("B", 4 * MINUTE), forecast.firstTurns[3])
        assertTrue("新登记应排在已有位置之后", onB!! > 4 * MINUTE)
        // The estimate never changes the real state.
        assertEquals(emptyList<Registration>(), state.queue("B")!!.waiting)
        assertEquals(null, PairedMachineForecast.newRegistrationWaitMillis(state, policy, durations, NOW, "C"))

        val full = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = (3..21).map { registration(it) },
                playingStartedAtMillis = NOW
            ),
            "B" to MachineQueue(waiting = emptyList())
        )
        assertEquals(null, PairedMachineForecast.newRegistrationWaitMillis(full, policy, durations, NOW, "A"))
        // An empty machine takes a new registration at once.
        val idle = stateOf(
            "A" to MachineQueue(waiting = emptyList()),
            "B" to MachineQueue(waiting = emptyList())
        )
        assertEquals(0L, PairedMachineForecast.newRegistrationWaitMillis(idle, policy, durations, NOW, "A"))
    }

    @Test
    fun registrationWithoutPlaceIsNotShownAsPreview() {
        // Registration 3 defers one round, so it holds no place on B yet. Once A has passed it
        // the deferral is used up, it takes a place on B and may well play there first.
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(
                    registration(3, paired = true)
                        .copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND),
                    registration(4),
                    registration(5)
                ),
                playingStartedAtMillis = NOW - 9 * MINUTE
            ),
            "B" to MachineQueue(
                playing = listOf(registration(2)),
                playingStartedAtMillis = NOW - 6 * MINUTE
            )
        )
        assertEquals(emptyList<Int>(), state.pairedMachineOrders["B"].orEmpty())
        val forecast = PairedMachineForecast.forecast(state, policy, durations, NOW)

        assertEquals(PairedMachineForecast.FirstTurn("B", 4 * MINUTE), forecast.firstTurns[3])
        assertEquals(setOf(3), forecast.arrivingKeys(state, "B"))

        val (displayQueue, previewKeys) =
            PairedMachineForecast.displayQueue(state, policy, "B", forecast)
        // Whatever the forecast expects, B shows only registrations that hold a place there.
        assertEquals(state.queue("B")!!.waiting, displayQueue.waiting)
        assertTrue(previewKeys.isEmpty())
    }

    @Test
    fun registrationStaysWhenItsOwnMachineIsReachedFirst() {
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(3, paired = true)),
                playingStartedAtMillis = NOW - 8 * MINUTE
            ),
            "B" to MachineQueue(
                playing = listOf(registration(2)),
                waiting = listOf(registration(5)),
                playingStartedAtMillis = NOW - 6 * MINUTE
            )
        )
        val forecast = PairedMachineForecast.forecast(state, policy, durations, NOW)
        assertEquals(PairedMachineForecast.FirstTurn("A", 2 * MINUTE), forecast.firstTurns[3])
        assertEquals(PairedMachineForecast.FirstTurn("B", 4 * MINUTE), forecast.firstTurns[5])
        assertTrue(forecast.arrivingKeys(state, "B").isEmpty())
        assertEquals(
            state.queue("B")!!.waiting,
            PairedMachineForecast.displayQueue(state, policy, "B", forecast).first.waiting
        )
    }

    @Test
    fun placeBehindOtherRegistrationsWaitsForThem() {
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(6), registration(7), registration(3, paired = true)),
                playingStartedAtMillis = NOW
            ),
            "B" to MachineQueue(
                playing = listOf(registration(2)),
                waiting = listOf(registration(5)),
                playingStartedAtMillis = NOW
            )
        )
        val forecast = PairedMachineForecast.forecast(state, policy, durations, NOW)
        // On A registration 3 would wait three rounds; on B only two.
        assertEquals(PairedMachineForecast.FirstTurn("B", 20 * MINUTE), forecast.firstTurns[3])
        assertEquals(setOf(3), forecast.arrivingKeys(state, "B"))
        val (displayQueue, _) = PairedMachineForecast.displayQueue(state, policy, "B", forecast)
        assertEquals("预览显示在它持有的位置上", listOf(5, 3), displayQueue.waiting.map { it.key })
    }

    @Test
    fun unsignedOnlineRegistrationIsNotExpectedToMove() {
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(3, paired = true, pendingCheckIn = true)),
                playingStartedAtMillis = NOW
            ),
            "B" to MachineQueue(
                playing = listOf(registration(2)),
                playingStartedAtMillis = NOW - 9 * MINUTE
            )
        )
        val forecast = PairedMachineForecast.forecast(state, policy, durations, NOW)
        assertEquals(PairedMachineForecast.FirstTurn("A", 10 * MINUTE), forecast.firstTurns[3])
        assertTrue(forecast.arrivingKeys(state, "B").isEmpty())
    }

    @Test
    fun machinesWithoutAPairHaveNoForecast() {
        val state = QueueEngineState(
            linkedMapOf(
                "A" to MachineQueue(
                    playing = listOf(registration(1)),
                    waiting = listOf(registration(3, paired = true)),
                    playingStartedAtMillis = NOW
                ),
                "B" to MachineQueue()
            )
        )
        val forecast = PairedMachineForecast.forecast(state, QueueEnginePolicy(), durations, NOW)
        assertTrue(forecast.firstTurns.isEmpty())
    }

    @Test
    fun lonePlayerWhoKeepsThePositionDoesNotStallTheForecast() {
        // Machine B has a single registration that plays again and again; machine A's waiting
        // registration must still get a forecast.
        val state = stateOf(
            "A" to MachineQueue(
                playing = listOf(registration(1)),
                waiting = listOf(registration(6), registration(3)),
                playingStartedAtMillis = NOW
            ),
            "B" to MachineQueue(
                playing = listOf(registration(2)),
                playingStartedAtMillis = NOW
            )
        )
        val forecast = PairedMachineForecast.forecast(state, policy, durations, NOW)
        assertEquals(PairedMachineForecast.FirstTurn("A", 10 * MINUTE), forecast.firstTurns[6])
        assertEquals(PairedMachineForecast.FirstTurn("A", 20 * MINUTE), forecast.firstTurns[3])
    }

    private companion object {
        const val MINUTE = 60_000L
        const val NOW = 10_000_000L
    }
}
