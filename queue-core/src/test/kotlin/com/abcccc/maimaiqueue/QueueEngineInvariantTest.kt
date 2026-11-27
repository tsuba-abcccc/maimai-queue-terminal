package com.abcccc.maimaiqueue

import kotlin.random.Random
import org.junit.Assert.fail
import org.junit.Test

/**
 * Long random sequences of the actions the terminal, the website and the Bot can send, on two
 * machines for two players (a pair) and one for a single player. After every applied action the
 * state must still be one the rules allow; a rejected action must change nothing. The seed is
 * fixed, so a failure is reproducible from the printed step.
 */
class QueueEngineInvariantTest {
    private val policy = QueueEnginePolicy(
        machineCapacities = mapOf("A" to 2, "B" to 2, "C" to 1),
        machinePartners = mapOf("A" to "B", "B" to "A"),
        requireOperationalForPlayerActions = true,
        pairedArrangementRules = PairedArrangementRules()
    )
    private val machines = listOf("A", "B", "C")

    /** A registration as the terminal creates it for [machineId]. */
    private fun registration(key: Int, random: Random, atMillis: Long, machineId: String): Registration {
        val onePlayer = policy.machineCapacity(machineId) == 1
        val preference = if (!onePlayer && random.nextDouble() < 0.6) PlayPreference.OPEN_TO_JOIN else PlayPreference.SOLO
        val pending = random.nextDouble() < 0.1
        return Registration(
            key = key,
            displayId = "玩家$key",
            preference = preference,
            isTemporary = random.nextBoolean(),
            createdAtMillis = atMillis,
            requiresOnSiteCheckIn = pending,
            onSiteCheckInStartedAtMillis = atMillis.takeIf { pending },
            wasOnlineRegistration = pending,
            allowsPassing = !onePlayer && preference == PlayPreference.SOLO && random.nextDouble() < 0.4
        )
    }

    private fun randomAction(state: QueueEngineState, random: Random, nextKey: () -> Int, atMillis: Long): QueueAction {
        val machineId = machines.random(random)
        val queue = state.queue(machineId) ?: MachineQueue()
        val waitingKeys = queue.waiting.map(Registration::key)
        val anyKey = (queue.playing + queue.waiting).map(Registration::key)
        fun someWaiting() = waitingKeys.randomOrNull(random) ?: -1
        fun someKey() = anyKey.randomOrNull(random) ?: -1
        return when (random.nextInt(24)) {
            0, 1, 2, 3 -> QueueAction.AddRegistrations(
                machineId,
                List(random.nextInt(1, 3)) { registration(nextKey(), random, atMillis, machineId) },
                RegistrationPlacement.entries.filter { it != RegistrationPlacement.STAGED_WAITING }.random(random)
            )
            4, 5 -> QueueAction.FinishRound(machineId)
            6 -> QueueAction.EndRoundOnly(machineId)
            7 -> QueueAction.EnterPlayingPosition(machineId)
            8 -> QueueAction.DeferOneRound(machineId, someWaiting())
            9 -> QueueAction.SkipOneTurn(machineId, someWaiting())
            10 -> QueueAction.TemporarilyLeave(machineId, someWaiting())
            11 -> listOf(
                QueueAction.CancelDeferOneRound(machineId, someWaiting()),
                QueueAction.CancelSkipOneTurn(machineId, someWaiting()),
                QueueAction.CancelTemporaryLeave(machineId, someWaiting())
            ).random(random)
            12 -> QueueAction.ChangePreference(machineId, someKey(), PlayPreference.entries.random(random))
            13 -> QueueAction.SetAllowsPassing(machineId, someKey(), random.nextBoolean())
            14 -> QueueAction.CreateFixedPair(machineId, someWaiting(), someWaiting())
            15 -> QueueAction.LeaveQueue(machineId, someKey())
            16 -> QueueAction.TransferRegistrations(machineId, machines.filter { it != machineId }.random(random), setOf(someWaiting()))
            17 -> QueueAction.MarkNoShow(machineId, setOf(someKey()), NoShowResolution.entries.random(random))
            18 -> QueueAction.CheckIn(machineId, someWaiting())
            19 -> {
                val count = queue.waitingPositions().size
                QueueAction.MoveWaitingPosition(machineId, random.nextInt(count.coerceAtLeast(1)), random.nextInt(count.coerceAtLeast(1)))
            }
            20 -> QueueAction.ReturnPlayingToWaitingFront(machineId, queue.playing.map(Registration::key).shuffled(random).take(1).toSet())
            21 -> QueueAction.MoveWaitingRegistrationIntoCurrentRound(machineId, someWaiting())
            22 -> QueueAction.RemoveCurrentRoundAndStartNext(machineId)
            else -> QueueAction.SetPairedMachineQueueing(machineId, someWaiting(), random.nextBoolean())
        }
    }

    /** What every reachable state must satisfy; null when it does. */
    private fun violation(state: QueueEngineState): String? {
        // The records of the two machines of a pair name only registrations that still exist.
        val existing = state.allRegistrations.map(Registration::key).toSet()
        state.pairedMachineOrders.forEach { (machineId, order) ->
            order.firstOrNull { it !in existing }?.let { return "$machineId: shared order names $it, which is gone" }
            if (order.toSet().size != order.size) return "$machineId: shared order repeats a key: $order"
        }
        state.pairedArrangements.keys.firstOrNull { it !in existing }?.let { return "arrangement record for $it, which is gone" }
        val seen = mutableMapOf<Int, String>()
        for ((machineId, queue) in state.queues) {
            val capacity = policy.machineCapacity(machineId)
            if (queue.playing.size > capacity) return "$machineId: ${queue.playing.size} playing, capacity $capacity"
            val all = queue.playing + queue.waiting
            for (registration in all) {
                seen.put(registration.key, machineId)?.let { other ->
                    return "registration ${registration.key} both at $other and $machineId"
                }
                val partnerKey = registration.fixedPartnerKey
                if (partnerKey != null) {
                    val partner = all.firstOrNull { it.key == partnerKey }
                        ?: return "$machineId: ${registration.key}'s fixed partner $partnerKey is not on the same machine"
                    if (partner.fixedPartnerKey != registration.key) {
                        return "$machineId: fixed pair ${registration.key}/$partnerKey not mutual"
                    }
                    if ((registration in queue.playing) != (partner in queue.playing)) {
                        return "$machineId: fixed pair ${registration.key}/$partnerKey split between playing and waiting"
                    }
                }
                // The choice belongs to 单人游玩 on its own, or to a fixed pair as one choice.
                if (registration.allowsPassing && partnerKey == null && registration.preference != PlayPreference.SOLO) {
                    return "$machineId: ${registration.key} lets others pass but is ${registration.preference} without a partner"
                }
                if (partnerKey != null && all.firstOrNull { it.key == partnerKey }?.allowsPassing != registration.allowsPassing) {
                    return "$machineId: fixed pair ${registration.key}/$partnerKey differs in letting others pass"
                }
                if (capacity == 1 && (registration.preference != PlayPreference.SOLO || partnerKey != null || registration.allowsPassing)) {
                    return "$machineId (one player): ${registration.key} is ${registration.preference}, partner $partnerKey, passing ${registration.allowsPassing}"
                }
                if (registration in queue.playing &&
                    (registration.absenceStatus != QueueAbsenceStatus.NONE || registration.requiresOnSiteCheckIn)
                ) {
                    return "$machineId: ${registration.key} plays while ${registration.absenceStatus}, pending ${registration.requiresOnSiteCheckIn}"
                }
                if (registration.passedOverKeys.isNotEmpty() && registration !in queue.playing) {
                    return "$machineId: waiting ${registration.key} still records passes ${registration.passedOverKeys}"
                }
                registration.passedOverKeys.firstOrNull { passed -> queue.waiting.none { it.key == passed } }?.let { gone ->
                    return "$machineId: ${registration.key} records passing $gone, which no longer waits there"
                }
                if (registration.passedOverKeys.isNotEmpty() && queue.playing.size < 2) {
                    return "$machineId: ${registration.key} plays alone but records passes ${registration.passedOverKeys}"
                }
            }
            // Who is treated as next is who the round start would let in.
            val previewed = queue.nextPlayingPositionPreview()?.nextRegistrations.orEmpty().map(Registration::key)
            val next = queue.nextWaitingGroup().map(Registration::key)
            if (previewed != next) return "$machineId: next group $next, but the round start lets in $previewed"
            // The positions on screen hold every waiting registration exactly once.
            val positioned = queue.waitingPositions().flatten().map(Registration::key)
            if (positioned.sorted() != queue.waiting.map(Registration::key).sorted()) {
                return "$machineId: positions $positioned do not match waiting ${queue.waiting.map(Registration::key)}"
            }
            // A fixed pair waits side by side.
            queue.waiting.forEachIndexed { index, registration ->
                val partnerKey = registration.fixedPartnerKey ?: return@forEachIndexed
                val partnerIndex = queue.waiting.indexOfFirst { it.key == partnerKey }
                if (partnerIndex >= 0 && kotlin.math.abs(partnerIndex - index) != 1) {
                    return "$machineId: fixed pair ${registration.key}/$partnerKey not adjacent in ${queue.waiting.map(Registration::key)}"
                }
            }
        }
        return null
    }

    /** Every present waiting registration gets a wait, and the shown waits never go backwards. */
    private fun estimateProblem(state: QueueEngineState, now: Long): String? {
        val planned = PairedMachineForecast.RoundDurations(12 * 60_000L, 15 * 60_000L)
        val result = WaitEstimator.estimate(state, policy, state.queues.keys.associateWith { planned }, now)
        for ((machineId, queue) in state.queues) {
            queue.waiting.filter { it.absenceStatus != QueueAbsenceStatus.TEMPORARILY_AWAY }.forEach { registration ->
                if (result.registrations[registration.key] == null) {
                    return "$machineId: no wait for ${registration.key} (${registration.absenceStatus}) in " +
                        "${queue.waiting.map { "${it.key}:${it.absenceStatus}:${it.requiresOnSiteCheckIn}" }}, " +
                        "playing ${queue.playing.map(Registration::key)} since ${queue.playingStartedAtMillis}, operational ${policy.isOperational(machineId)}"
                }
            }
            // At this machine rounding never reverses two positions: one that really waits no
            // longer than a position behind it is not shown later than that one. (A position
            // behind that really waits less, because somebody ahead of it will move away, may
            // show a shorter time.)
            val here = queue.waitingPositions().map { position -> result.hereEstimate(position.map(Registration::key)) }
            val shown = WaitEstimator.displayMinutesInOrder(here, here)
            for (later in shown.indices) {
                val laterShown = shown[later] ?: continue
                val laterWait = here[later]?.waitMillis ?: continue
                for (earlier in 0 until later) {
                    val earlierShown = shown[earlier] ?: continue
                    val earlierWait = here[earlier]?.waitMillis ?: continue
                    if (earlierWait <= laterWait && earlierShown > laterShown) {
                        return "$machineId: shown waits $shown reverse positions ${earlier + 1} and ${later + 1}"
                    }
                }
            }
        }
        return null
    }

    /**
     * Moving one of the positions as shown moves only its registrations: everybody else keeps
     * their real order, whatever the screen makes of the queue (passes, deferrals, pending
     * online registrations).
     */
    private fun shownReorderProblem(state: QueueEngineState, random: Random): String? {
        for ((machineId, queue) in state.queues) {
            val shown = queue.shownWaitingPositionKeys()
            if (shown.flatten().sorted() != queue.waiting.map(Registration::key).sorted()) {
                return "$machineId: shown positions $shown do not hold the waiting registrations once each"
            }
            if (queue.withShownPositionsReordered(shown) != queue) return "$machineId: the same shown order changed the queue"
            if (shown.size < 2) continue
            val source = random.nextInt(shown.size)
            val destination = (source + 1 + random.nextInt(shown.size - 1)) % shown.size
            val desired = shown.toMutableList().apply { add(destination, removeAt(source)) }
            val moved = shown[source].toSet()
            val result = queue.withShownPositionMoved(shown.map { it.toSet() }, source, destination)
            // A whole new order that amounts to this one move gives the same queue; two
            // neighbours changing places can be read either way round.
            val reordered = queue.withShownPositionsReordered(desired)
                ?: return "$machineId: moving shown position $source to $destination was refused"
            val otherWayRound = queue.withShownPositionMoved(shown.map { it.toSet() }, destination, source)
            if (reordered != result && !(kotlin.math.abs(source - destination) == 1 && reordered == otherWayRound)) {
                return "$machineId: a new order for one move ($source to $destination of $shown) gave " +
                    "${reordered.waiting.map(Registration::key)}, the move itself ${result.waiting.map(Registration::key)}"
            }
            val before = queue.waiting.map(Registration::key)
            val after = result.waiting.map(Registration::key)
            if (after.sorted() != before.sorted()) return "$machineId: a shown reorder lost or added registrations"
            if (after.filter { it !in moved } != before.filter { it !in moved }) {
                return "$machineId: moving $moved also reordered others: $before -> $after; shown $shown, wanted $desired"
            }
            if (after.filter { it in moved } != before.filter { it in moved }) {
                return "$machineId: moving $moved changed their own order: $before -> $after"
            }
            if (result.playing != queue.playing) return "$machineId: a shown reorder touched the playing position"
        }
        return null
    }

    @Test
    fun randomActionSequencesKeepTheQueueValid() {
        var applied = 0
        repeat(300) { run ->
            val random = Random(20261127 + run)
            var key = 1
            var state = QueueEngineState(linkedMapOf("A" to MachineQueue(), "B" to MachineQueue(), "C" to MachineQueue()))
            var now = 1_000_000L
            val history = mutableListOf<String>()
            repeat(120) { step ->
                now += random.nextLong(5_000L, 6 * 60_000L)
                val action = randomAction(state, random, { key++ }, now)
                val execution = QueueEngine.execute(state, action, QueueActionContext(atMillis = now, policy = policy))
                history += action.toString().take(160)
                if (execution is QueueActionExecution.Applied) {
                    applied++
                    state = execution.state
                    violation(state)?.let { problem ->
                        fail("run $run step $step: $problem\nlast actions:\n" + history.takeLast(6).joinToString("\n"))
                    }
                    // The slower checks every tenth step: the engine's own and the wait estimates.
                    if (step % 10 == 0) {
                        state.invariantViolations().firstOrNull()?.let { problem ->
                            fail("run $run step $step: engine: $problem\nlast actions:\n" + history.takeLast(6).joinToString("\n"))
                        }
                        shownReorderProblem(state, random)?.let { problem -> fail("run $run step $step: $problem") }
                        // A sample: one estimate with a pair of machines takes some 25 ms.
                        if (step % 30 == 0 && run % 3 == 0) {
                            estimateProblem(state, now)?.let { problem -> fail("run $run step $step: $problem") }
                        }
                    }
                }
            }
        }
        if (applied < 5_000) fail("only $applied actions applied; the generator no longer exercises the engine")
    }
}
