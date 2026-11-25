package com.abcccc.maimaiqueue

/**
 * Predicts, for machines that share registrations as a pair, on which machine and after how long
 * each waiting registration will first play.
 *
 * The prediction replays the real queue rules round by round, using each machine's planned round
 * length to decide which machine reaches a registration first. It is used for display and for
 * wait estimates only. Where a registration actually plays is decided when a machine is really
 * reached, never by this forecast.
 */
object PairedMachineForecast {
    data class RoundDurations(val soloMillis: Long, val sharedMillis: Long)

    data class FirstTurn(
        /** The machine on which the registration is expected to play first. */
        val machineId: String,
        /** Expected wait from now, in milliseconds. */
        val waitMillis: Long
    )

    data class Result(
        val firstTurns: Map<Int, FirstTurn>,
        /** The machines this forecast covers: those that belong to a usable pair. */
        val machineIds: Set<String> = emptySet()
    ) {
        /**
         * Whole minutes, rounded up, until the first of [registrationKeys] is expected to play
         * on either machine of its pair; null when the forecast has no turn for any of them.
         */
        fun waitMinutes(registrationKeys: Collection<Int>): Long? =
            registrationKeys.mapNotNull { firstTurns[it]?.waitMillis }
                .minOrNull()
                ?.let { (it + 59_999L) / 60_000L }

        /** Registrations of the other machine that are expected to play on [machineId] first. */
        fun arrivingKeys(state: QueueEngineState, machineId: String): Set<Int> {
            val homeKeys = state.queue(machineId)?.allRegistrations
                ?.mapTo(mutableSetOf()) { it.key }
                ?: return emptySet()
            return firstTurns.filterTo(linkedMapOf()) { (key, turn) ->
                turn.machineId == machineId && key !in homeKeys
            }.keys
        }

        /** Registrations waiting on [machineId] that are expected to play on the other machine. */
        fun leavingKeys(state: QueueEngineState, machineId: String): Set<Int> =
            state.queue(machineId)?.waiting.orEmpty()
                .filter { firstTurns[it.key]?.machineId?.let { target -> target != machineId } == true }
                .mapTo(linkedSetOf()) { it.key }
    }

    val EMPTY = Result(emptyMap())

    fun forecast(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        durations: Map<String, RoundDurations>,
        nowMillis: Long
    ): Result {
        val pairedMachineIds = state.queues.keys.filter { machineId ->
            PairedMachineQueues.activePartner(state, policy, machineId) != null
        }
        if (pairedMachineIds.isEmpty()) return EMPTY

        // Like the single-machine estimate, the forecast assumes that an online registration
        // will check in before its turn on its own machine. It holds no place on the other
        // machine until it really has checked in, so it is not predicted to move.
        var simulated = PairedMachineQueues.normalize(
            state.copy(
                queues = state.queues.mapValues { (machineId, queue) ->
                    if (machineId !in pairedMachineIds) {
                        queue
                    } else {
                        queue.copy(
                            waiting = queue.waiting.map { registration ->
                                if (registration.requiresOnSiteCheckIn) {
                                    registration.copy(
                                        requiresOnSiteCheckIn = false,
                                        alsoQueuesAtPairedMachine = false
                                    )
                                } else {
                                    registration
                                }
                            }
                        )
                    }
                }
            ),
            policy
        )

        fun roundMillis(machineId: String, playing: List<Registration>): Long {
            val duration = durations[machineId] ?: RoundDurations(DEFAULT_SOLO_MILLIS, DEFAULT_SHARED_MILLIS)
            return if (policy.machineCapacity(machineId) == 1 || playing.size <= 1) {
                duration.soloMillis
            } else {
                duration.sharedMillis
            }.coerceAtLeast(1L)
        }

        val targetKeys = pairedMachineIds.flatMapTo(mutableSetOf()) { machineId ->
            simulated.queue(machineId)?.waiting.orEmpty().map { it.key }
        }
        val firstTurns = linkedMapOf<Int, FirstTurn>()
        // When each machine's current round is expected to end; absent while it stands empty.
        val roundEndsAt = mutableMapOf<String, Long>()
        pairedMachineIds.forEach { machineId ->
            val queue = simulated.queue(machineId) ?: return@forEach
            if (queue.playing.isNotEmpty() && policy.isOperational(machineId)) {
                val elapsed = queue.playingStartedAtMillis
                    ?.let { (nowMillis - it).coerceAtLeast(0L) }
                    ?: 0L
                roundEndsAt[machineId] = (roundMillis(machineId, queue.playing) - elapsed)
                    .coerceAtLeast(0L)
            }
        }

        fun recordNewRounds(before: QueueEngineState, atMillis: Long, steppedMachineId: String) {
            pairedMachineIds.forEach { machineId ->
                val previousPlaying = before.queue(machineId)?.playing.orEmpty().map { it.key }
                val playing = simulated.queue(machineId)?.playing.orEmpty()
                if (playing.isEmpty()) {
                    roundEndsAt.remove(machineId)
                    return@forEach
                }
                // The machine whose round just ended always starts a new one, even with the
                // same players; another machine is only affected when its players changed.
                if (
                    machineId != steppedMachineId &&
                    playing.map { it.key } == previousPlaying &&
                    machineId in roundEndsAt
                ) {
                    return@forEach
                }
                playing.forEach { registration ->
                    if (registration.key in targetKeys && registration.key !in firstTurns) {
                        firstTurns[registration.key] = FirstTurn(machineId, atMillis)
                    }
                }
                roundEndsAt[machineId] = atMillis + roundMillis(machineId, playing)
            }
        }

        fun step(machineId: String, atMillis: Long): Boolean {
            val execution = QueueEngine.execute(
                state = simulated,
                action = QueueAction.FinishRound(machineId),
                context = QueueActionContext(
                    atMillis = nowMillis + atMillis,
                    origin = QueueActionOrigin.SYSTEM,
                    policy = policy
                )
            )
            val applied = execution as? QueueActionExecution.Applied ?: return false
            val before = simulated
            simulated = applied.state
            recordNewRounds(before, atMillis, machineId)
            return true
        }

        // A machine that stands empty although its own players could enter is assumed to start
        // now, as the single-machine estimate does.
        pairedMachineIds.forEach { machineId ->
            val queue = simulated.queue(machineId) ?: return@forEach
            if (queue.playing.isEmpty() && policy.isOperational(machineId)) step(machineId, 0L)
        }

        // With the announcement rules a machine that stands empty does not take a registration of
        // the other machine at once, but at the moment its announcement has run long enough.
        // Those moments are looked at as well, since no round ends on an empty machine.
        val wakeUpTimes = policy.pairedArrangementRules?.let { rules ->
            simulated.pairedArrangements.values
                .mapNotNull { record ->
                    record.previewSinceMillis
                        ?.takeIf { record.previewPausedAtMillis == null }
                        ?.let { (it + rules.leadMillis - nowMillis).coerceAtLeast(0L) }
                }
                .toSortedSet()
        } ?: sortedSetOf()

        val coveredMachineIds = pairedMachineIds.toSet()
        val maximumSteps = targetKeys.size * 4 + pairedMachineIds.size * 4 + wakeUpTimes.size + 8
        repeat(maximumSteps) {
            if (firstTurns.keys.containsAll(waitingKeysStillExpected(simulated, pairedMachineIds, targetKeys))) {
                return Result(firstTurns, coveredMachineIds)
            }
            val next = roundEndsAt.minWithOrNull(compareBy({ it.value }, { it.key }))
            val wakeUp = wakeUpTimes.firstOrNull()?.takeIf { next == null || it < next.value }
            if (wakeUp != null) {
                wakeUpTimes.remove(wakeUp)
                pairedMachineIds.forEach { machineId ->
                    val queue = simulated.queue(machineId) ?: return@forEach
                    if (queue.playing.isEmpty() && policy.isOperational(machineId)) {
                        step(machineId, wakeUp)
                    }
                }
                return@repeat
            }
            if (next == null) return Result(firstTurns, coveredMachineIds)
            if (!step(next.key, next.value)) {
                // Nothing can follow on this machine; it stays as it is.
                roundEndsAt.remove(next.key)
            }
        }
        return Result(firstTurns, coveredMachineIds)
    }

    /** The other machine of [machineId]'s usable pair, or null when it has none. */
    fun pairedMachineId(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        machineId: String
    ): String? = PairedMachineQueues.activePartner(state, policy, machineId)

    /**
     * Expected wait, in milliseconds, of a registration that would join [machineId] now without
     * queueing at the other machine. Null when the machine has no usable pair, is full, or the
     * forecast finds no turn for it (for example while the machine is stopped).
     */
    fun newRegistrationWaitMillis(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        durations: Map<String, RoundDurations>,
        nowMillis: Long,
        machineId: String
    ): Long? {
        val queue = state.queue(machineId) ?: return null
        if (PairedMachineQueues.activePartner(state, policy, machineId) == null) return null
        if (queue.registrationCount >= policy.maxRegistrationsPerMachine) return null
        val usedKeys = state.allRegistrations.mapTo(mutableSetOf()) { it.key }
        var previewKey = 1
        while (previewKey in usedKeys) previewKey++
        var previewId = NEW_REGISTRATION_PREVIEW_ID
        while (state.queues.values.any { it.containsId(previewId) }) previewId += "_"
        val preview = Registration(
            key = previewKey,
            displayId = previewId,
            preference = if (policy.machineCapacity(machineId) == 1) {
                PlayPreference.SOLO
            } else {
                PlayPreference.OPEN_TO_JOIN
            },
            createdAtMillis = nowMillis
        )
        val withPreview = state.copy(
            queues = state.queues + (machineId to queue.stageWaiting(preview))
        )
        return forecast(withPreview, policy, durations, nowMillis)
            .firstTurns[previewKey]
            ?.waitMillis
    }

    /**
     * The queue to hand to the single-machine display projection for [machineId]: its own
     * registrations plus, at the place they hold, the registrations of the other machine that
     * the forecast expects here. Returns the queue and the keys of those extra registrations.
     */
    fun displayQueue(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        machineId: String,
        forecast: Result
    ): Pair<MachineQueue, Set<Int>> {
        val queue = state.queue(machineId) ?: return MachineQueue() to emptySet()
        val partner = PairedMachineQueues.activePartner(state, policy, machineId)
            ?: return queue to emptySet()
        val arriving = forecast.arrivingKeys(state, machineId)
        if (arriving.isEmpty()) return queue to emptySet()
        val homeByKey = queue.waiting.associateBy(Registration::key)
        val placeByKey = state.queue(partner)?.waiting.orEmpty()
            .filter { it.key in arriving }
            .associateBy(Registration::key)
        val order = state.pairedMachineOrders[machineId] ?: queue.waiting.map(Registration::key)
        val waiting = order.mapNotNull { key -> homeByKey[key] ?: placeByKey[key] }
        // A registration that is expected here but holds no place yet (for example while it
        // defers one round) has no position to be shown at, so it is not a preview either.
        val shownKeys = waiting.mapNotNullTo(linkedSetOf()) { registration ->
            registration.key.takeIf { it in placeByKey }
        }
        if (shownKeys.isEmpty()) return queue to emptySet()
        return queue.copy(waiting = waiting) to shownKeys
    }

    private fun waitingKeysStillExpected(
        state: QueueEngineState,
        pairedMachineIds: List<String>,
        targetKeys: Set<Int>
    ): Set<Int> = pairedMachineIds.flatMapTo(mutableSetOf()) { machineId ->
        state.queue(machineId)?.waiting.orEmpty()
            .filter {
                it.key in targetKeys &&
                    it.absenceStatus != QueueAbsenceStatus.TEMPORARILY_AWAY
            }
            .map { it.key }
    }

    private const val NEW_REGISTRATION_PREVIEW_ID = "预计新增登记"
    private const val DEFAULT_SOLO_MILLIS = 12L * 60_000L
    private const val DEFAULT_SHARED_MILLIS = 15L * 60_000L
}
