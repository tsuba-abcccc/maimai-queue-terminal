package com.abcccc.maimaiqueue

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the engine (every machine keeps its own order; a registration that queues at both
 * machines holds a place in the other one) against [SharedQueueReference] (one line for both
 * machines). The maintainer requires the two to give the same results. Where they do not, the
 * case is written down here as a test of its own, so that each difference is a decision that
 * was looked at, not an accident.
 */
class SharedQueueEquivalenceTest {

    // ---- the steps both models are driven with ---------------------------------------------

    private sealed interface Step {
        data class Join(
            val key: Int,
            val machine: String,
            val both: Boolean,
            val preference: PlayPreference = PlayPreference.SOLO,
            val pendingCheckIn: Boolean = false
        ) : Step

        data class Finish(val machine: String) : Step
        data class Defer(val key: Int) : Step
        data class CancelDefer(val key: Int) : Step
        data class Leave(val key: Int) : Step
        data class ComeBack(val key: Int) : Step
        data class CheckIn(val key: Int) : Step
        data class SetBoth(val key: Int, val both: Boolean) : Step
        data class Quit(val key: Int) : Step
        data class MovePosition(val machine: String, val from: Int, val to: Int) : Step
        data class FixedPair(val first: Int, val second: Int) : Step
        data class ChangePreference(val key: Int, val preference: PlayPreference) : Step
        data class EndRoundOnly(val machine: String) : Step
        data class EnterPlayingPosition(val machine: String) : Step
        data class RemoveRoundAndStartNext(val machine: String) : Step
        data class NoShow(val key: Int, val resolution: NoShowResolution) : Step
        data class ReturnToFront(val machine: String) : Step
    }

    private class Models(capacity: Int, maxRegistrationsPerMachine: Int = 20) {
        val policy = QueueEnginePolicy(
            machinePartners = mapOf("A" to "B", "B" to "A"),
            machineCapacities = mapOf("A" to capacity, "B" to capacity),
            maxRegistrationsPerMachine = maxRegistrationsPerMachine,
            requireOperationalForPlayerActions = true
        )
        var engine: QueueEngineState = QueueEngine.settlePairedMachines(
            QueueEngineState(linkedMapOf("A" to MachineQueue(), "B" to MachineQueue())),
            policy,
            0L
        )
        // The shared line has no limit per machine; that limit is a difference of its own.
        var shared = SharedQueueReference(
            listOf("A", "B"),
            policy.copy(maxRegistrationsPerMachine = 1_000)
        )
        var clock = 1_000L

        private fun engineMachineOf(key: Int): String? =
            engine.queues.entries.firstOrNull { (_, queue) ->
                queue.allRegistrations.any { it.key == key }
            }?.key

        private fun runEngine(action: QueueAction): Boolean {
            val execution = QueueEngine.execute(
                engine,
                action,
                QueueActionContext(atMillis = clock, policy = policy)
            )
            val applied = execution as? QueueActionExecution.Applied ?: return false
            engine = applied.state
            return true
        }

        /** Applies [step] to both models. Returns false when the engine does not accept it. */
        fun apply(step: Step): Boolean {
            clock += 1_000L
            fun keyed(key: Int, action: (String) -> QueueAction, homeOnly: Boolean = false): Boolean {
                val engineMachine = engineMachineOf(key) ?: return false
                if (!runEngine(action(engineMachine))) return false
                val sharedMachine = shared.machineOf(key) ?: engineMachine
                acceptedByShared = shared.execute(
                    sharedMachine,
                    action(sharedMachine),
                    clock,
                    homeOnly = homeOnly
                )
                return true
            }
            acceptedByShared = true
            return when (step) {
                is Step.Join -> {
                    val registration = Registration(
                        key = step.key,
                        displayId = "玩家${step.key}",
                        preference = step.preference,
                        isTemporary = false,
                        createdAtMillis = clock,
                        requiresOnSiteCheckIn = step.pendingCheckIn,
                        onSiteCheckInStartedAtMillis = if (step.pendingCheckIn) clock else null,
                        alsoQueuesAtPairedMachine = step.both
                    )
                    val accepted = runEngine(
                        QueueAction.AddRegistrations(
                            step.machine,
                            listOf(registration),
                            RegistrationPlacement.ADVANCE_IF_UNAMBIGUOUS
                        )
                    )
                    if (accepted) {
                        acceptedByShared = shared.join(step.machine, registration, step.both, clock)
                    }
                    accepted
                }
                is Step.Finish -> {
                    val accepted = runEngine(QueueAction.FinishRound(step.machine))
                    if (accepted) {
                        acceptedByShared = shared.execute(
                            step.machine,
                            QueueAction.FinishRound(step.machine),
                            clock
                        )
                    }
                    accepted
                }
                is Step.Defer -> keyed(step.key, { QueueAction.DeferOneRound(it, step.key) })
                is Step.CancelDefer ->
                    keyed(step.key, { QueueAction.CancelDeferOneRound(it, step.key) })
                is Step.Leave -> keyed(step.key, { QueueAction.TemporarilyLeave(it, step.key) })
                is Step.ComeBack ->
                    keyed(step.key, { QueueAction.CancelTemporaryLeave(it, step.key) })
                is Step.CheckIn -> keyed(step.key, { QueueAction.CheckIn(it, step.key) })
                is Step.Quit ->
                    keyed(step.key, { QueueAction.RemoveRegistrations(it, setOf(step.key)) })
                is Step.SetBoth -> {
                    val machine = engineMachineOf(step.key) ?: return false
                    val accepted = runEngine(
                        QueueAction.SetPairedMachineQueueing(machine, step.key, step.both)
                    )
                    if (accepted) {
                        acceptedByShared = shared.setBothMachines(step.key, step.both, clock)
                    }
                    accepted
                }
                is Step.MovePosition -> {
                    val action = QueueAction.MoveWaitingPosition(step.machine, step.from, step.to)
                    val accepted = runEngine(action)
                    if (accepted) {
                        acceptedByShared = shared.execute(step.machine, action, clock, homeOnly = true)
                    }
                    accepted
                }
                is Step.FixedPair -> keyed(
                    step.first,
                    { QueueAction.CreateFixedPair(it, step.first, step.second) },
                    homeOnly = true
                )
                is Step.ChangePreference -> keyed(
                    step.key,
                    { QueueAction.ChangePreference(it, step.key, step.preference) }
                )
                is Step.NoShow -> keyed(
                    step.key,
                    { QueueAction.MarkNoShow(it, setOf(step.key), step.resolution) }
                )
                is Step.EndRoundOnly -> onMachine(QueueAction.EndRoundOnly(step.machine))
                is Step.EnterPlayingPosition ->
                    onMachine(QueueAction.EnterPlayingPosition(step.machine))
                is Step.RemoveRoundAndStartNext ->
                    onMachine(QueueAction.RemoveCurrentRoundAndStartNext(step.machine))
                is Step.ReturnToFront -> {
                    val keys = engine.queue(step.machine)!!.playing.mapTo(mutableSetOf()) { it.key }
                    onMachine(QueueAction.ReturnPlayingToWaitingFront(step.machine, keys))
                }
            }
        }

        private fun onMachine(action: QueueAction): Boolean {
            val accepted = runEngine(action)
            if (accepted) acceptedByShared = shared.execute(action.machineId, action, clock)
            return accepted
        }

        var acceptedByShared = true

        fun describeEngine(): String = buildString {
            listOf("A", "B").forEach { machineId ->
                val queue = engine.queue(machineId)!!
                append("$machineId 游玩 ${queue.playing.map { it.key }} ")
                append("等待 ${queue.waiting.map { it.key }} ")
                append("本机台的顺序（含另一机台的位置） ${engine.pairedMachineOrders[machineId].orEmpty()}　")
            }
        }

        /**
         * The first way in which the two models disagree: who is playing now, what each
         * machine's board shows, or who plays in the coming rounds. Null when they agree.
         */
        fun difference(lookAheadRounds: Int = 8): String? {
            if (!acceptedByShared) return "引擎接受了这一步，共用一条队的模型不接受"
            listOf("A", "B").forEach { machineId ->
                val enginePlaying = engine.queue(machineId)!!.playing.map { it.key }
                if (enginePlaying != shared.playingKeys(machineId)) {
                    return "机台 $machineId 正在游玩：引擎 $enginePlaying，共用一条队 ${shared.playingKeys(machineId)}"
                }
            }
            listOf("A", "B").forEach { machineId ->
                val engineBoard = engine.queue(machineId)!!.waiting.map { it.key }
                if (engineBoard != shared.homeWaitingKeys(machineId)) {
                    return "机台 $machineId 的登记板：引擎 $engineBoard，" +
                        "共用一条队 ${shared.homeWaitingKeys(machineId)}"
                }
            }
            // Orders that differ further back only show once the rounds reach them.
            listOf(listOf("A", "B"), listOf("B", "A")).forEach { turnOrder ->
                var engineAhead = engine
                val sharedAhead = shared.copy()
                var aheadClock = clock
                repeat(lookAheadRounds) { round ->
                    val machineId = turnOrder[round % 2]
                    aheadClock += 1_000L
                    val execution = QueueEngine.execute(
                        engineAhead,
                        QueueAction.FinishRound(machineId),
                        QueueActionContext(atMillis = aheadClock, policy = policy)
                    )
                    if (execution is QueueActionExecution.Applied) {
                        engineAhead = execution.state
                        sharedAhead.execute(machineId, QueueAction.FinishRound(machineId), aheadClock)
                    }
                    listOf("A", "B").forEach { checked ->
                        val enginePlaying = engineAhead.queue(checked)!!.playing.map { it.key }
                        if (enginePlaying != sharedAhead.playingKeys(checked)) {
                            return "按 ${turnOrder.joinToString("、")} 的顺序再结束 ${round + 1} 轮后，" +
                                "机台 $checked 上游玩的是：引擎 $enginePlaying，" +
                                "共用一条队 ${sharedAhead.playingKeys(checked)}"
                        }
                    }
                }
            }
            return null
        }
    }

    private class Divergence(val steps: List<Step>, val description: String, val trace: String)

    /** Runs [steps] on fresh models and reports the first disagreement, if there is one. */
    private fun firstDivergence(steps: List<Step>, capacity: Int): Divergence? {
        val models = Models(capacity)
        val trace = StringBuilder()
        val applied = mutableListOf<Step>()
        steps.forEach { step ->
            if (!models.apply(step)) return@forEach
            applied += step
            trace.appendLine("$step")
            trace.appendLine("    现有实现　　 ${models.describeEngine()}")
            trace.appendLine("    共用一条队　 ${models.shared.describe()}")
            models.difference()?.let { description ->
                return Divergence(applied.toList(), description, trace.toString())
            }
        }
        return null
    }

    /** Drops every step that is not needed for the two models to disagree. */
    private fun shrink(divergence: Divergence, capacity: Int): Divergence {
        var current = divergence
        var changed = true
        while (changed) {
            changed = false
            for (index in current.steps.indices.reversed()) {
                val candidate = current.steps.toMutableList().also { it.removeAt(index) }
                val result = firstDivergence(candidate, capacity) ?: continue
                current = result
                changed = true
                break
            }
        }
        return current
    }

    private enum class Kind {
        JOIN, FINISH, QUIT, PREFERENCE, END_ONLY, ENTER, REMOVE_ROUND,
        DEFER, LEAVE, CHECK_IN, SET_BOTH, MOVE, FIXED_PAIR, NO_SHOW, RETURN_FRONT
    }

    private fun randomSteps(
        random: Random,
        length: Int,
        kinds: Set<Kind>,
        capacity: Int,
        maximumRegistrations: Int = 9
    ): List<Step> {
        var nextKey = 1
        val alive = mutableListOf<Int>()
        fun machine() = if (random.nextBoolean()) "A" else "B"
        return buildList {
            repeat(length) {
                val kind = kinds.random(random)
                val anyKey = alive.randomOrNull(random)
                val step: Step? = when (kind) {
                    Kind.JOIN -> if (alive.size < maximumRegistrations) {
                        Step.Join(
                            key = nextKey,
                            machine = machine(),
                            both = random.nextInt(4) != 0,
                            preference = if (capacity == 1 || random.nextBoolean()) {
                                PlayPreference.SOLO
                            } else {
                                PlayPreference.OPEN_TO_JOIN
                            },
                            pendingCheckIn = Kind.CHECK_IN in kinds && random.nextInt(4) == 0
                        ).also { alive += nextKey++ }
                    } else {
                        null
                    }
                    Kind.FINISH -> Step.Finish(machine())
                    Kind.QUIT -> anyKey?.let { key ->
                        alive -= key
                        Step.Quit(key)
                    }
                    Kind.DEFER -> anyKey?.let {
                        if (random.nextInt(4) == 0) Step.CancelDefer(it) else Step.Defer(it)
                    }
                    Kind.LEAVE -> anyKey?.let {
                        if (random.nextInt(3) == 0) Step.ComeBack(it) else Step.Leave(it)
                    }
                    Kind.CHECK_IN -> anyKey?.let { Step.CheckIn(it) }
                    Kind.SET_BOTH -> anyKey?.let { Step.SetBoth(it, random.nextBoolean()) }
                    Kind.MOVE -> Step.MovePosition(machine(), random.nextInt(4), random.nextInt(4))
                    Kind.PREFERENCE -> anyKey?.takeIf { capacity == 2 }?.let {
                        Step.ChangePreference(
                            it,
                            if (random.nextBoolean()) PlayPreference.SOLO else PlayPreference.OPEN_TO_JOIN
                        )
                    }
                    Kind.END_ONLY -> Step.EndRoundOnly(machine())
                    Kind.ENTER -> Step.EnterPlayingPosition(machine())
                    Kind.REMOVE_ROUND -> Step.RemoveRoundAndStartNext(machine())
                    Kind.NO_SHOW -> anyKey?.let {
                        Step.NoShow(
                            it,
                            listOf(
                                NoShowResolution.DEFER_ONE_ROUND,
                                NoShowResolution.MOVE_TO_TAIL,
                                NoShowResolution.REMOVE
                            ).random(random)
                        )
                    }
                    Kind.RETURN_FRONT -> Step.ReturnToFront(machine())
                    Kind.FIXED_PAIR -> if (alive.size >= 2) {
                        val first = alive.random(random)
                        val second = alive.filter { it != first }.random(random)
                        Step.FixedPair(first, second)
                    } else {
                        null
                    }
                }
                step?.let(::add)
            }
        }
    }

    /** Searches random sequences for a disagreement and returns the smallest one found. */
    private fun search(kinds: Set<Kind>, seeds: Int, length: Int): Pair<Divergence, Int>? {
        var best: Pair<Divergence, Int>? = null
        repeat(seeds) { seed ->
            val capacity = if (seed % 3 == 0) 1 else 2
            val steps = randomSteps(Random(seed), length, kinds, capacity)
            val found = firstDivergence(steps, capacity) ?: return@repeat
            val smallest = shrink(found, capacity)
            if (best == null || smallest.steps.size < best!!.first.steps.size) {
                best = smallest to capacity
            }
        }
        return best
    }

    private fun report(title: String, found: Pair<Divergence, Int>?): String = buildString {
        appendLine("==== $title ====")
        if (found == null) {
            appendLine("没有发现结果不同的情况。")
        } else {
            val (divergence, capacity) = found
            appendLine("游玩容量 $capacity。最小的例子（${divergence.steps.size} 步）：")
            append(divergence.trace)
            appendLine("不同之处：${divergence.description}")
        }
    }

    // ---- where the two must agree ----------------------------------------------------------

    // Joining twice as often as the rest keeps the queues from running empty.
    private val agreeingKinds = setOf(Kind.JOIN, Kind.FINISH, Kind.QUIT)
    private val operatorRoundKinds = setOf(Kind.PREFERENCE, Kind.END_ONLY, Kind.ENTER, Kind.REMOVE_ROUND)

    @Test
    fun joiningPlayingAndLeavingGiveTheSameResultsAsOneSharedLine() {
        var playedOnTheOtherMachine = 0
        var restrictedToOneMachine = 0
        repeat(600) { seed ->
            val capacity = if (seed % 3 == 0) 1 else 2
            val steps = randomSteps(Random(seed), 70, agreeingKinds, capacity)
            val divergence = firstDivergence(steps, capacity)?.let { shrink(it, capacity) }
            assertNull(
                divergence?.let { "种子 $seed，游玩容量 $capacity：${it.description}\n${it.trace}" },
                divergence
            )
            // The comparison only means something if registrations really change machines
            // and if some of them refuse to.
            val models = Models(capacity)
            steps.forEach { step ->
                val shownOn = models.engine.queues.flatMap { (machineId, queue) ->
                    queue.waiting.map { it.key to machineId }
                }.toMap()
                if (!models.apply(step)) return@forEach
                if (step is Step.Join && !step.both) restrictedToOneMachine++
                playedOnTheOtherMachine += models.engine.queues.entries.sumOf { (machineId, queue) ->
                    queue.playing.count { shownOn[it.key]?.let { before -> before != machineId } == true }
                }
            }
        }
        assertTrue("换机台游玩的次数太少：$playedOnTheOtherMachine", playedOnTheOtherMachine > 1_000)
        assertTrue("只排一台的登记太少：$restrictedToOneMachine", restrictedToOneMachine > 1_000)
    }

    // ---- exploration: prints the smallest example of each kind of difference ----------------

    @Test
    fun operatorActionsOnRoundsGiveTheSameResultsAsOneSharedLine() {
        // Changing a preference, ending a round without starting the next, entering the
        // playing position and removing the current round do not touch the order.
        repeat(300) { seed ->
            val capacity = if (seed % 3 == 0) 1 else 2
            val steps = randomSteps(Random(seed), 70, agreeingKinds + operatorRoundKinds, capacity)
            val divergence = firstDivergence(steps, capacity)?.let { shrink(it, capacity) }
            assertNull(
                divergence?.let { "种子 $seed，游玩容量 $capacity：${it.description}\n${it.trace}" },
                divergence
            )
        }
    }

    // ---- the known differences, one small example each -------------------------------------
    //
    // Every test below shows one situation in which the engine and the shared line give
    // different results, and states both results. They are not approvals of the engine's
    // behaviour: each one is a question for the maintainer. When a decision changes the
    // engine, the expected result on the engine's side of that test changes with it.
    //
    // In all of them registration 1 only accepts machine A and is playing there, registration
    // 2 only accepts machine B and is playing there, and everyone plays alone.

    private fun models(vararg steps: Step, maxRegistrationsPerMachine: Int = 20): Models =
        Models(capacity = 2, maxRegistrationsPerMachine = maxRegistrationsPerMachine).also { models ->
            models.run(Step.Join(1, "A", both = false), Step.Join(2, "B", both = false), *steps)
        }

    private fun Models.run(vararg steps: Step) {
        steps.forEach { step ->
            assertTrue("两种方案都应接受这一步：$step", apply(step) && acceptedByShared)
        }
    }

    private fun Models.enginePlaying(machineId: String): List<Int> =
        engine.queue(machineId)!!.playing.map { it.key }

    private fun Models.engineBoard(machineId: String): List<Int> =
        engine.queue(machineId)!!.waiting.map { it.key }

    @Test
    fun difference1_deferOneRound() {
        // 3, 4 and 5 arrive in that order; all three accept both machines. 3 defers one round.
        val models = models(
            Step.Join(3, "B", both = true),
            Step.Join(4, "A", both = true),
            Step.Join(5, "A", both = true),
            Step.Defer(3),
            Step.Finish("A")
        )
        // Machine A's round ends first and passes 3 over in both models: 4 plays on A.
        assertEquals(listOf(4), models.enginePlaying("A"))
        assertEquals(listOf(4), models.shared.playingKeys("A"))

        models.run(Step.Finish("B"))
        // Shared line: being passed over by machine A was the one round 3 deferred, so 3 plays.
        assertEquals(listOf(3), models.shared.playingKeys("B"))
        // Engine: only 3's own machine counts. Machine B passes 3 over as well, and 5 plays.
        assertEquals(listOf(5), models.enginePlaying("B"))
    }

    @Test
    fun difference2_temporarilyAway() {
        // 3 accepts both machines and leaves temporarily; 5 only accepts machine B.
        val models = models(
            Step.Join(3, "B", both = true),
            Step.Join(4, "A", both = true),
            Step.Join(5, "B", both = false),
            Step.Leave(3),
            Step.Finish("A")
        )
        assertEquals(listOf(4), models.enginePlaying("A"))
        assertEquals(listOf(4), models.shared.playingKeys("A"))
        // Shared line: machine A's round reached 3 while it was away. That counts as one
        // missed turn and moves 3 to the end of the line.
        assertEquals(1, models.shared.registration(3)!!.temporaryAwaySkippedTurns)
        // Engine: a round of the other machine does not touch a registration that is away.
        assertEquals(0, models.engine.queue("B")!!.waiting.first { it.key == 3 }.temporaryAwaySkippedTurns)

        models.run(Step.ComeBack(3), Step.Finish("B"))
        assertEquals(listOf(5), models.shared.playingKeys("B"))
        assertEquals(listOf(3), models.enginePlaying("B"))
    }

    @Test
    fun difference3_onlineRegistrationBeforeCheckIn() {
        // 3 was created online for machine B, accepts both machines and has not checked in.
        val models = models(
            Step.Join(3, "B", both = true, pendingCheckIn = true),
            Step.Join(4, "A", both = true),
            Step.Finish("A")
        )
        assertEquals(listOf(4), models.enginePlaying("A"))
        assertEquals(listOf(4), models.shared.playingKeys("A"))
        // Shared line: machine A reached 3 before it had checked in, so 3 left the queue.
        assertEquals(emptyList<Int>(), models.shared.homeWaitingKeys("B"))
        // Engine: only its own machine can reach it; it is still waiting to check in.
        assertEquals(listOf(3), models.engineBoard("B"))
    }

    @Test
    fun difference4_turningQueueingAtBothMachinesOnLater() {
        // 3 arrives before 4 but only accepts machine A at first; 4 accepts both from the start.
        val models = models(
            Step.Join(3, "A", both = false),
            Step.Join(4, "B", both = true),
            Step.SetBoth(3, true),
            Step.Finish("B")
        )
        // Shared line: 3 is ahead of 4 in the line, so 3 plays on B.
        assertEquals(listOf(3), models.shared.playingKeys("B"))
        // Engine, decided by the maintainer: turned on while 3 was already waiting, it only
        // counts after 3 has played a round. 3 has no place on B, so 4 plays on B.
        assertEquals(listOf(4), models.enginePlaying("B"))
        assertFalse(3 in models.engine.pairedMachineOrders.getValue("B"))
        assertEquals(listOf(3), models.engineBoard("A"))
    }

    @Test
    fun difference5_operatorPutsPlayersBackAtTheFront() {
        // 3 and 4 accept both machines; 3 is playing on A when the operator puts it back at
        // the front of the waiting order.
        val models = Models(capacity = 2).also {
            it.run(
                Step.Join(2, "B", both = false),
                Step.Join(3, "A", both = true),
                Step.Join(4, "A", both = true),
                Step.ReturnToFront("A")
            )
        }
        assertEquals(listOf(3, 4), models.engineBoard("A"))
        assertEquals(listOf(3, 4), models.shared.homeWaitingKeys("A"))

        // Engine, decided by the maintainer: a registration taken back out of the playing
        // position queues at its own machine only for this round.
        assertFalse(3 in models.engine.pairedMachineOrders.getValue("B"))
        assertTrue(4 in models.engine.pairedMachineOrders.getValue("B"))

        models.run(Step.Finish("B"))
        // Shared line: 3 is back at the front of the line, on both machines.
        assertEquals(listOf(3), models.shared.playingKeys("B"))
        // Engine: 3 is first on machine A again and has no place on B, so 4 plays on B.
        assertEquals(listOf(4), models.enginePlaying("B"))
    }

    @Test
    fun difference6_operatorReordersOneBoard() {
        // 3 and 4 are shown on machine B and accept both machines. The operator swaps them.
        val models = models(
            Step.Join(3, "B", both = true),
            Step.Join(4, "B", both = true),
            Step.MovePosition("B", from = 0, to = 1)
        )
        assertEquals(listOf(4, 3), models.engineBoard("B"))
        assertEquals(listOf(4, 3), models.shared.homeWaitingKeys("B"))

        // Engine, decided by the maintainer: the registration that was moved, 3, queues at
        // its own machine only for this round; 4 was not moved and keeps its place on A.
        assertEquals(listOf(4), models.engine.pairedMachineOrders.getValue("A"))

        models.run(Step.Finish("A"))
        // Both: 4 is the first registration machine A may take.
        assertEquals(listOf(4), models.shared.playingKeys("A"))
        assertEquals(listOf(4), models.enginePlaying("A"))

        models.run(Step.Finish("A"))
        // Shared line: there is one order, and 3 is next in it for machine A as well.
        assertEquals(listOf(3), models.shared.playingKeys("A"))
        // Engine: 3 has no place on A this round, so machine A goes on with its own line.
        assertEquals(listOf(1), models.enginePlaying("A"))
        assertEquals(listOf(3), models.engineBoard("B"))
    }

    @Test
    fun difference7_fixedPair() {
        // 3 and 4 accept both machines and are then made a fixed pair on machine A.
        val models = models(
            Step.Join(3, "A", both = true, preference = PlayPreference.OPEN_TO_JOIN),
            Step.Join(4, "A", both = true, preference = PlayPreference.OPEN_TO_JOIN),
            Step.FixedPair(3, 4),
            Step.Finish("B")
        )
        // Shared line: the pair is one unit and goes to whichever machine is free first.
        assertEquals(listOf(3, 4), models.shared.playingKeys("B"))
        // Engine: a fixed pair only queues at its own machine; 2 keeps playing on B.
        assertEquals(listOf(2), models.enginePlaying("B"))
    }

    @Test
    fun difference8_limitOfRegistrationsPerMachine() {
        // Machine A may hold three registrations and holds three. 3 is shown on machine B,
        // accepts both machines and arrived before 4 and 5.
        val models = models(
            Step.Join(3, "B", both = true),
            Step.Join(4, "A", both = false),
            Step.Join(5, "A", both = false),
            Step.Finish("A"),
            maxRegistrationsPerMachine = 3
        )
        // Shared line: 3 is first in the line and plays on A.
        assertEquals(listOf(3), models.shared.playingKeys("A"))
        // Engine: a machine that is full takes no registration from the other machine.
        assertEquals(listOf(4), models.enginePlaying("A"))
    }

    /**
     * Not a check: with SHARED_QUEUE_EXPLORE=true in the environment it searches for the
     * smallest example of each kind of difference and writes them to
     * build/shared-queue-differences.txt for the maintainer to look at.
     */
    @Test
    fun writeDifferencesForDiscussion() {
        if (System.getenv("SHARED_QUEUE_EXPLORE") != "true") return
        val base = agreeingKinds
        val explorations = listOf(
            "暂缓一次" to base + Kind.DEFER,
            "暂时离开" to base + Kind.LEAVE,
            "线上登记待签到" to base + Kind.CHECK_IN,
            "中途开启或取消同时排队" to base + Kind.SET_BOTH,
            "调整等待位置" to base + Kind.MOVE,
            "固定组合" to base + Kind.FIXED_PAIR,
            "更改偏好、仅结束本轮、进入游玩位置、移除本轮" to base + operatorRoundKinds,
            "未到场处理" to base + Kind.NO_SHOW,
            "把游玩中的登记放回队首" to base + Kind.RETURN_FRONT
        )
        val output = File("build/shared-queue-differences.txt")
        output.parentFile.mkdirs()
        output.writeText(
            explorations.joinToString("\n") { (title, kinds) ->
                report(title, search(kinds, seeds = 400, length = 60))
            }
        )
    }
}
