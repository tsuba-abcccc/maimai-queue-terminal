package com.abcccc.maimaiqueue

/**
 * A reference model used only by tests: the two machines of a pair share ONE line.
 *
 * Everyone waits in a single order. A registration either accepts both machines or only one.
 * Whenever a machine chooses its next group it looks at the line with the registrations that
 * do not accept it left out, and applies the ordinary single-machine round rules to what
 * remains. Players who finish a round go to the end of the line.
 *
 * The production engine does not work like this: every machine keeps its own waiting order and
 * a registration that queues at both machines holds a place in the other machine's order. The
 * maintainer requires both to give the same results; this model is the yardstick for that, see
 * [SharedQueueEquivalenceTest].
 *
 * To compare nothing but the way the order is kept, the model reuses the real single-machine
 * rules ([QueueEngine] without any pair) on each machine's view of the line.
 */
internal class SharedQueueReference private constructor(
    private val machineIds: List<String>,
    private val policy: QueueEnginePolicy,
    private var playing: Map<String, List<Entry>>,
    private var startedAt: Map<String, Long?>,
    private var line: List<Entry>
) {
    constructor(machineIds: List<String>, policy: QueueEnginePolicy) : this(
        machineIds = machineIds,
        policy = policy.copy(machinePartners = emptyMap()),
        playing = machineIds.associateWith { emptyList() },
        startedAt = machineIds.associateWith { null },
        line = emptyList()
    )

    /**
     * [home] is the machine whose board shows the registration: the one it was created on, or
     * the one it last played on. [bothMachines] is false for a registration that only accepts
     * its home machine.
     */
    data class Entry(val registration: Registration, val home: String, val bothMachines: Boolean) {
        val key: Int get() = registration.key
    }

    fun copy(): SharedQueueReference =
        SharedQueueReference(machineIds, policy, playing, startedAt, line)

    fun playingKeys(machineId: String): List<Int> = playing.getValue(machineId).map(Entry::key)

    /** The registrations shown on [machineId]'s board, in order. */
    fun homeWaitingKeys(machineId: String): List<Int> =
        line.filter { it.home == machineId }.map(Entry::key)

    /** Everything [machineId] may choose from, in the order of the line. */
    fun viewKeys(machineId: String): List<Int> =
        line.filter { accepts(it, machineId) }.map(Entry::key)

    fun lineKeys(): List<Int> = line.map(Entry::key)

    fun registration(key: Int): Registration? =
        (line + playing.values.flatten()).firstOrNull { it.key == key }?.registration

    /** The machine a registration is shown on: where it plays, or its home while it waits. */
    fun machineOf(key: Int): String? =
        playing.entries.firstOrNull { (_, entries) -> entries.any { it.key == key } }?.key
            ?: line.firstOrNull { it.key == key }?.home

    fun describe(): String = buildString {
        machineIds.forEach { machineId ->
            append("$machineId 游玩 ${playingKeys(machineId)}　")
        }
        append("总队列 ")
        append(
            line.joinToString(prefix = "[", postfix = "]") { entry ->
                val scope = if (entry.bothMachines) "两台" else "仅${entry.home}"
                "${entry.key}($scope·显示在${entry.home}${stateSuffix(entry.registration)})"
            }
        )
    }

    private fun stateSuffix(registration: Registration): String = buildString {
        if (registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND) append("·暂缓一次")
        if (registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY) append("·暂时离开")
        if (registration.requiresOnSiteCheckIn) append("·待签到")
        if (registration.fixedPartnerKey != null) append("·固定组合")
    }

    private fun accepts(entry: Entry, machineId: String): Boolean {
        fun alone(candidate: Entry) = candidate.home == machineId || candidate.bothMachines
        if (!alone(entry)) return false
        // A fixed pair is one unit: it can only go where both of its members accept.
        val partnerKey = entry.registration.fixedPartnerKey ?: return true
        val partner = line.firstOrNull { it.key == partnerKey } ?: return true
        return alone(partner)
    }

    /** Adds a new registration through the ordinary join rules of [machineId]. */
    fun join(
        machineId: String,
        registration: Registration,
        bothMachines: Boolean,
        atMillis: Long
    ): Boolean = execute(
        machineId = machineId,
        action = QueueAction.AddRegistrations(
            machineId,
            listOf(registration.copy(alsoQueuesAtPairedMachine = false)),
            RegistrationPlacement.ADVANCE_IF_UNAMBIGUOUS
        ),
        atMillis = atMillis,
        newcomers = mapOf(registration.key to bothMachines)
    )

    /** Changes which machines a registration accepts. Its place in the line does not change. */
    fun setBothMachines(key: Int, bothMachines: Boolean, atMillis: Long): Boolean {
        var changed = false
        fun update(entry: Entry): Entry =
            if (entry.key == key && entry.bothMachines != bothMachines) {
                changed = true
                entry.copy(bothMachines = bothMachines)
            } else {
                entry
            }
        line = line.map(::update)
        playing = playing.mapValues { (_, entries) -> entries.map(::update) }
        if (changed) fillIdleMachines(atMillis)
        return changed
    }

    /**
     * Runs [action] with the real single-machine rules on [machineId]'s view of the line and
     * writes the outcome back. With [homeOnly] the action only sees the registrations shown on
     * that machine's board, as an operator who rearranges that board does.
     */
    fun execute(
        machineId: String,
        action: QueueAction,
        atMillis: Long,
        homeOnly: Boolean = false,
        newcomers: Map<Int, Boolean> = emptyMap()
    ): Boolean {
        // Players who are put back in front of the others go to the front of the whole line.
        // In every other case players who come back from the playing position go to its end.
        val playingHere = playing.getValue(machineId).mapTo(mutableSetOf(), Entry::key)
        val returnsToFront = when (action) {
            is QueueAction.ReturnPlayingToWaitingFront -> true
            is QueueAction.DeferOneRound -> action.registrationKey in playingHere
            is QueueAction.MarkNoShow ->
                action.resolution == NoShowResolution.DEFER_ONE_ROUND ||
                    action.resolution == NoShowResolution.DEFER_GROUP_ONE_ROUND
            else -> false
        }
        val visible = line.filter { if (homeOnly) it.home == machineId else accepts(it, machineId) }
        val view = MachineQueue(
            playing = playing.getValue(machineId).map(Entry::registration),
            waiting = visible.map(Entry::registration),
            playingStartedAtMillis = startedAt.getValue(machineId)
        )
        val execution = QueueEngine.execute(
            state = QueueEngineState.single(machineId, view),
            action = action,
            context = QueueActionContext(atMillis = atMillis, policy = policy)
        )
        val result = (execution as? QueueActionExecution.Applied)?.state?.queue(machineId)
            ?: return false
        writeBack(machineId, visible, result, newcomers, returnsToFront)
        fillIdleMachines(atMillis)
        return true
    }

    private fun writeBack(
        machineId: String,
        visible: List<Entry>,
        result: MachineQueue,
        newcomers: Map<Int, Boolean>,
        returnsToFront: Boolean = false
    ) {
        val known = (playing.getValue(machineId) + visible).associateBy(Entry::key)
        fun entryFor(registration: Registration, nowPlayingHere: Boolean): Entry {
            val before = known[registration.key]
            return Entry(
                registration = registration,
                // Entering the playing position makes this machine the registration's home.
                home = if (nowPlayingHere || before == null) machineId else before.home,
                bothMachines = before?.bothMachines ?: newcomers.getValue(registration.key)
            )
        }
        val wasPlayingHere = playing.getValue(machineId).mapTo(mutableSetOf(), Entry::key)

        val visibleKeys = visible.map(Entry::key)
        val resultKeys = result.waiting.map(Registration::key)
        val resultByKey = result.waiting.associateBy(Registration::key)
        // Registrations that come back from the playing position or have just joined go to
        // the end of the line. A waiting registration that the rules placed behind them was
        // sent to the end as well, as happens to one that is away when its turn comes.
        val firstArrival = resultKeys.indexOfFirst { it !in visibleKeys }
        val tailFrom = if (returnsToFront || firstArrival < 0) resultKeys.size else firstArrival
        val survivors = resultKeys.take(tailFrom).filter { it in visibleKeys }
        // Survivors that kept their relative order stay exactly where they are in the line.
        val settled = longestKeptOrder(survivors, visibleKeys).toSet()

        val departed = visibleKeys.filter { it !in resultByKey }.toSet()
        val replaced = resultKeys.filter { it in visibleKeys && it !in settled }.toSet()
        val updated = line
            .filter { it.key !in departed && it.key !in replaced }
            .map { entry ->
                resultByKey[entry.key]?.let { entryFor(it, nowPlayingHere = false) } ?: entry
            }
            .toMutableList()

        // Everything else is put where the single-machine rules put it: in front of the first
        // settled registration means the front of the line, behind the last one means the end
        // of the line, anything in between follows its predecessor.
        val firstSettled = resultKeys.indexOfFirst { it in settled }
        val lastSettled = resultKeys.indexOfLast { it in settled }
        var frontInsertions = 0
        resultKeys.forEachIndexed { index, key ->
            if (key in settled) return@forEachIndexed
            val registration = resultByKey.getValue(key)
            val entry = entryFor(registration, nowPlayingHere = false).let { created ->
                // A registration that returns from the playing position is shown here.
                if (key in wasPlayingHere) created.copy(home = machineId) else created
            }
            when {
                index >= tailFrom -> updated += entry
                firstSettled < 0 && returnsToFront -> updated.add(frontInsertions++, entry)
                firstSettled < 0 || index > lastSettled -> updated += entry
                index < firstSettled -> updated.add(frontInsertions++, entry)
                else -> {
                    val predecessor = resultKeys[index - 1]
                    updated.add(updated.indexOfFirst { it.key == predecessor } + 1, entry)
                }
            }
        }

        line = updated
        playing = playing + (
            machineId to result.playing.map { entryFor(it, nowPlayingHere = true) }
            )
        startedAt = startedAt + (machineId to result.playingStartedAtMillis)
    }

    /**
     * The longest part of [after] that is still in the order it had in [before]; among equally
     * long ones, the one that keeps the earliest elements of [after].
     */
    private fun longestKeptOrder(after: List<Int>, before: List<Int>): List<Int> {
        if (after.isEmpty()) return emptyList()
        val position = before.withIndex().associate { (index, key) -> key to index }
        val length = IntArray(after.size) { 1 }
        val next = IntArray(after.size) { -1 }
        for (index in after.indices.reversed()) {
            for (later in index + 1 until after.size) {
                if (
                    position.getValue(after[later]) > position.getValue(after[index]) &&
                    length[later] + 1 > length[index]
                ) {
                    length[index] = length[later] + 1
                    next[index] = later
                }
            }
        }
        var current = after.indices.maxWith(compareBy<Int> { length[it] }.thenByDescending { it })
        return buildList {
            while (current >= 0) {
                add(after[current])
                current = next[current]
            }
        }
    }

    /**
     * Mirrors the engine's rule for a machine that stands empty: it takes the first group of
     * the line that accepts it, unless registrations shown on its own board could enter, in
     * which case the empty playing position is an operator's deliberate choice.
     */
    private fun fillIdleMachines(atMillis: Long) {
        repeat(machineIds.size * 2 + 2) {
            val machineId = machineIds.firstOrNull { candidate ->
                if (playing.getValue(candidate).isNotEmpty() || !policy.isOperational(candidate)) {
                    return@firstOrNull false
                }
                val own = MachineQueue(
                    waiting = line.filter { it.home == candidate }.map(Entry::registration)
                )
                val view = MachineQueue(
                    waiting = line.filter { accepts(it, candidate) }.map(Entry::registration)
                )
                own.firstAvailableWaitingPositionIndex() == null &&
                    view.firstAvailableWaitingPositionIndex() != null
            } ?: return
            val visible = line.filter { accepts(it, machineId) }
            val result = RoundPlanner.enterPlayingPosition(
                MachineQueue(waiting = visible.map(Entry::registration))
            ).execute(atMillis)
            writeBack(machineId, visible, result, emptyMap())
        }
    }
}
