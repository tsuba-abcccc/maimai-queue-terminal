package com.abcccc.maimaiqueue

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The wait estimates shown to players (docs/releases/wait-estimate-2026-11.md).
 *
 * The future is replayed many times with the real queue rules. In each replay every uncertain
 * thing takes one possible value, such as how long each round lasts. Pending online
 * registrations count as checking in, as the waiting positions on screen are grouped; a second
 * set of replays without them gives [Result.earlierIfOnlineMissing]. A registered player is
 * shown an early value of the results, because
 * they use it to decide when to be back; a new registration is shown the middle value, because
 * it only helps to choose a machine.
 *
 * Moves between the two machines of a pair are decided apart. A natural replay of both machines
 * gives every registration that may be arranged to the other machine a confidence: the share of
 * replays in which it really is. For the estimates of registered players:
 * - a move away counts once its preview is shown or its confidence is above
 *   [Settings.certainAtMost];
 * - a move here counts only from [Settings.certainAtLeast];
 * - in between the estimate is the earlier one and is marked uncertain.
 *
 * The estimates are for display only: who is arranged where is decided by the queue rules alone.
 */
object WaitEstimator {
    data class Settings(
        /** Replays per estimate. */
        val runs: Int = 100,
        /** The value shown to registered players (maintainer, 2026-11-25: 30th percentile). */
        val registeredPercentile: Double = 0.30,
        /** The value shown before joining: the middle one. */
        val newRegistrationPercentile: Double = 0.50,
        /** A move that happens in at least this share of replays counts as certain. */
        val certainAtLeast: Double = 0.75,
        /** A move that happens in at most this share counts as certainly not happening. */
        val certainAtMost: Double = 0.25,
        /** A round lasts from this much less than planned... */
        val shorterByMillis: Long = 2L * 60_000L,
        /** ...to this much more (maintainer: solo 10–15, shared 13–18 minutes). */
        val longerByMillis: Long = 3L * 60_000L,
        /**
         * Share of pending online registrations that check in. All of them (maintainer,
         * 2026-11-26): the waiting positions on screen are grouped as if they do, so the
         * estimates agree with the positions. [Result.earlierIfOnlineMissing] tells how much
         * earlier a registration may play when they do not.
         */
        val checkInShare: Double = 1.0,
        /**
         * The earlier wait for when the pending online registrations ahead do not check in is
         * told only when it is at least this much earlier; a smaller difference is within how
         * much a round's length varies anyway.
         */
        val onlineMissingNoteAtLeastMillis: Long = 5L * 60_000L
    )

    data class Estimate(val waitMillis: Long, val uncertain: Boolean)

    data class Result(
        /** By registration key: waiting registrations whose turn can be estimated. */
        val registrations: Map<Int, Estimate>,
        /** By machine: the middle wait of a registration that would join now. */
        val newRegistrationWaitMillis: Map<String, Long>,
        /** By registration key: how often a registration was arranged to the other machine. */
        val moveConfidence: Map<Int, Double> = emptyMap(),
        /**
         * By registration key: the wait when none of the pending online registrations checks
         * in, for registrations that would then play clearly earlier
         * ([Settings.onlineMissingNoteAtLeastMillis]). Absent for the others.
         */
        val earlierIfOnlineMissing: Map<Int, Long> = emptyMap(),
        /**
         * By registration key: the wait to play at the machine where the registration waits now.
         * It differs from [registrations] only for a registration that may be arranged to the
         * other machine of a pair, whose own estimate also counts that machine and can be much
         * earlier. Absent where the two are the same.
         */
        val here: Map<Int, Estimate> = emptyMap(),
        /**
         * By registration key, for uncertain estimates only: the wait at the registration's own
         * machine if nobody is moved between the two machines. The estimate itself is the
         * earliest time; this is the other one. Where the two lie far apart both are shown,
         * each by itself, instead of 最快 ([rangeUpperMinutes]): the wait will be near one of
         * them, not anywhere in between.
         */
        val ifNobodyMoves: Map<Int, Long> = emptyMap()
    ) {
        /** The wait of the first of [registrationKeys] to play if nobody is moved; null if unknown. */
        fun waitIfNobodyMoves(registrationKeys: Collection<Int>): Long? =
            registrationKeys.mapNotNull { ifNobodyMoves[it] }.minOrNull()

        /**
         * The earlier wait of a whole position when the pending online registrations ahead do
         * not check in: only when every one of [registrationKeys] would play clearly earlier.
         */
        fun earlierPositionWaitIfOnlineMissing(registrationKeys: Collection<Int>): Long? {
            if (registrationKeys.isEmpty()) return null
            return registrationKeys.map { earlierIfOnlineMissing[it] ?: return null }.minOrNull()
        }

        /**
         * When the first of [registrationKeys] is expected to play at the machine where they
         * wait now. It differs from [estimate] only where one of them may be arranged to the
         * other machine of a pair. The positions before them are never shown later than this
         * ([displayMinutesInOrder]); a registration's own earlier time at the other machine must
         * not pull them down (reported 2026-11-26: every position after A1 showed the same 15
         * minutes).
         *
         * [arrivingHere] are registrations of the other machine shown as expected to play at
         * this one first (a preview): their time here is their own estimate, not the one at the
         * machine where they wait (review 2026-11-27).
         */
        fun hereEstimate(registrationKeys: Collection<Int>, arrivingHere: Set<Int> = emptySet()): Estimate? {
            val estimates = registrationKeys.mapNotNull {
                if (it in arrivingHere) registrations[it] else here[it] ?: registrations[it]
            }
            val earliest = estimates.minByOrNull(Estimate::waitMillis) ?: return null
            return earliest.copy(uncertain = estimates.any(Estimate::uncertain))
        }

        /** The estimate of the first of [registrationKeys] to play; uncertain if any of them is. */
        fun estimate(registrationKeys: Collection<Int>): Estimate? {
            val estimates = registrationKeys.mapNotNull { registrations[it] }
            val earliest = estimates.minByOrNull(Estimate::waitMillis) ?: return null
            return earliest.copy(uncertain = estimates.any(Estimate::uncertain))
        }
    }

    val EMPTY = Result(emptyMap(), emptyMap())

    /**
     * From how many minutes between the earliest time and the time if nobody is moved both
     * are shown for an uncertain wait (maintainer, 2026-11-28: “24分钟以上算太多”). Below
     * that, 最快 with the earliest time says enough.
     */
    const val RANGE_GAP_MINUTES = 24L

    /**
     * The second time to show under a wait shown as [shownMinutes] and marked uncertain, with
     * its condition (系统未在两台机台之间安排登记时): [waitIfNobodyMovesMillis] in whole
     * minutes, when it lies at least [RANGE_GAP_MINUTES] later. A shown wait of 0 (可能很快)
     * counts too. Null when the wait is to be shown as before.
     */
    fun rangeUpperMinutes(shownMinutes: Long?, uncertain: Boolean, waitIfNobodyMovesMillis: Long?): Long? {
        if (!uncertain || shownMinutes == null || shownMinutes < 0L || waitIfNobodyMovesMillis == null) return null
        // Both would read 2 小时以上.
        if (shownMinutes > LONG_WAIT_MINUTES) return null
        val upper = displayMinutes(waitIfNobodyMovesMillis, roundDown = false)
        return upper.takeIf { it - shownMinutes >= RANGE_GAP_MINUTES }
    }

    fun estimate(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        durations: Map<String, PairedMachineForecast.RoundDurations>,
        nowMillis: Long,
        settings: Settings = Settings()
    ): Result {
        val result = estimateOnce(state, policy, durations, nowMillis, settings)
        val anyPending = state.queues.values.any { queue -> queue.waiting.any(Registration::requiresOnSiteCheckIn) }
        if (!anyPending || settings.checkInShare <= 0.0) return result
        // The same replays with no pending online registration checking in: each is then removed
        // at its turn, and the registrations behind it may play a round earlier.
        val missing = estimateOnce(state, policy, durations, nowMillis, settings.copy(checkInShare = 0.0))
        val earlier = result.registrations.mapNotNull { (key, estimate) ->
            val withoutThem = missing.registrations[key]?.waitMillis ?: return@mapNotNull null
            (key to withoutThem).takeIf {
                estimate.waitMillis - withoutThem >= settings.onlineMissingNoteAtLeastMillis
            }
        }.toMap()
        return result.copy(earlierIfOnlineMissing = earlier)
    }

    private fun estimateOnce(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        durations: Map<String, PairedMachineForecast.RoundDurations>,
        nowMillis: Long,
        settings: Settings
    ): Result {
        if (state.queues.isEmpty() || settings.runs <= 0) return EMPTY
        val normalized = PairedMachineQueues.normalize(state, policy)
        val timing = Timing(policy, durations, settings)
        val partners = normalized.queues.keys.associateWith { machineId ->
            PairedMachineQueues.activePartner(normalized, policy, machineId)
        }
        val pairedIds = partners.filterValues { it != null }.keys.toList()

        // A registration that would join now, one per machine. It is replayed on its own: an
        // open one at the tail can join the last waiting registration in a shared round, which
        // makes that round longer for anybody who plays after it, such as a deferred registration.
        val newcomers = newcomers(normalized, policy, nowMillis)

        val natural = if (pairedIds.isEmpty()) {
            null
        } else {
            NaturalReplay(normalized, policy, timing, settings, nowMillis, pairedIds, emptyMap()).run()
        }
        val confidence = natural?.moveConfidence(normalized, partners).orEmpty()

        fun previewShown(key: Int): Boolean =
            normalized.pairedArrangements[key]?.previewSinceMillis != null

        fun countedAway(key: Int): Boolean =
            previewShown(key) || (confidence[key] ?: 0.0) > settings.certainAtMost

        fun countedHere(key: Int): Boolean = (confidence[key] ?: 0.0) >= settings.certainAtLeast

        fun uncertainAway(key: Int): Boolean =
            countedAway(key) && (confidence[key] ?: 0.0) < settings.certainAtLeast

        fun uncertainHere(key: Int): Boolean {
            val share = confidence[key] ?: 0.0
            return share > settings.certainAtMost && share < settings.certainAtLeast
        }

        val holdersByMachine = normalized.queues.mapValues { (machineId, queue) ->
            if (partners[machineId] == null) {
                emptyList()
            } else {
                queue.waiting.filter(Registration::holdsPairedMachinePlace)
            }
        }

        /** A machine's waiting order with the places of the other machine, as registrations. */
        fun fullOrder(machineId: String): List<Registration> {
            val queue = normalized.queue(machineId) ?: return emptyList()
            val partner = partners[machineId] ?: return queue.waiting
            val places = holdersByMachine[partner].orEmpty()
            if (places.isEmpty()) return queue.waiting
            val byKey = (queue.waiting + places).associateBy(Registration::key)
            return PairedMachineQueues.reconcileOrder(
                previousOrder = normalized.pairedMachineOrders[machineId],
                homeKeys = queue.waiting.map(Registration::key),
                placeKeys = places.map(Registration::key)
            ).mapNotNull { byKey[it] }
        }

        fun isOwn(machineId: String, key: Int): Boolean =
            normalized.queue(machineId)?.waiting?.any { it.key == key } == true

        /** The order the lean-early replay of [machineId] uses, before any extra registration. */
        fun leanOrder(machineId: String): List<Registration> = fullOrder(machineId).filter { registration ->
            if (isOwn(machineId, registration.key)) {
                !(registration.holdsPairedMachinePlace && countedAway(registration.key))
            } else {
                countedHere(registration.key)
            }
        }

        /** Whether a move that is not certain lies ahead of [key] in [machineId]'s order. */
        fun uncertainAhead(machineId: String, key: Int): Boolean {
            for (registration in fullOrder(machineId)) {
                if (registration.key == key) return false
                val own = isOwn(machineId, registration.key)
                if (own && registration.holdsPairedMachinePlace && uncertainAway(registration.key)) return true
                if (!own && uncertainHere(registration.key)) return true
            }
            return false
        }

        /** Replays [machineId] alone with [order] as its waiting order. */
        fun replay(machineId: String, order: List<Registration>, targets: Set<Int>): Map<Int, LongArray> {
            val queue = normalized.queue(machineId) ?: return emptyMap()
            return MachineReplay(
                machineId = machineId,
                queue = queue.copy(waiting = order),
                capacity = policy.machineCapacity(machineId),
                timing = timing,
                settings = settings,
                nowMillis = nowMillis
            ).run(targets)
        }

        fun withInserted(machineId: String, base: List<Registration>, registration: Registration): List<Registration> {
            val full = fullOrder(machineId)
            val index = full.indexOfFirst { it.key == registration.key }
            if (index < 0) return base + registration
            val before = full.take(index).mapTo(mutableSetOf(), Registration::key)
            val insertAt = base.indexOfFirst { it.key !in before }.takeIf { it >= 0 } ?: base.size
            return base.take(insertAt) + registration + base.drop(insertAt)
        }

        val estimates = mutableMapOf<Int, Estimate>()
        val hereEstimates = mutableMapOf<Int, Estimate>()
        val newcomerWaits = mutableMapOf<String, Long>()
        val baseReplays = mutableMapOf<String, Map<Int, LongArray>>()

        normalized.queues.keys.filter(policy::isOperational).forEach { machineId ->
            val order = leanOrder(machineId)
            val times = replay(machineId, order, order.mapTo(mutableSetOf(), Registration::key))
            baseReplays[machineId] = times
            val queue = normalized.queue(machineId) ?: return@forEach
            queue.waiting.forEach { registration ->
                if (registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY) return@forEach
                if (registration.holdsPairedMachinePlace && countedAway(registration.key)) return@forEach
                val wait = percentile(times[registration.key], settings.registeredPercentile) ?: return@forEach
                estimates[registration.key] = Estimate(wait, uncertainAhead(machineId, registration.key))
            }
            // Without a pair there is nothing to move, so the replay for a newcomer is this one.
            val newcomer = newcomers[machineId]?.takeIf { partners[machineId] == null }
            if (newcomer != null) {
                percentile(
                    replay(machineId, queue.waiting + newcomer, setOf(newcomer.key))[newcomer.key],
                    settings.newRegistrationPercentile
                )?.let { newcomerWaits[machineId] = it }
            }
        }

        // Registrations that count as moving away: their own estimate looks at both machines.
        normalized.queues.forEach { (machineId, queue) ->
            val partner = partners[machineId] ?: return@forEach
            queue.waiting.forEach { registration ->
                if (!registration.holdsPairedMachinePlace || !countedAway(registration.key)) return@forEach
                val key = registration.key
                val share = confidence[key] ?: 0.0
                val homeTimes = if (policy.isOperational(machineId)) {
                    replay(machineId, withInserted(machineId, leanOrder(machineId), registration), setOf(key))[key]
                } else {
                    null
                }
                // The other machine cannot receive it before its preview has been shown for the
                // announced time, however soon a round ends there; told an earlier time, the
                // player would wait for a move the rules do not make (review 2026-11-27).
                val earliestArrangementMillis = policy.pairedArrangementRules?.let { rules ->
                    val record = normalized.pairedArrangements[key]
                    val since = record?.previewSinceMillis
                    if (since != null && record.previewPausedAtMillis == null) {
                        (since + rules.leadMillis - nowMillis).coerceAtLeast(0L)
                    } else {
                        rules.leadMillis
                    }
                } ?: 0L
                val awayTimes = if (policy.isOperational(partner)) {
                    (
                        baseReplays[partner]?.get(key)
                            ?: replay(partner, withInserted(partner, leanOrder(partner), registration), setOf(key))[key]
                        )?.let { times ->
                            if (earliestArrangementMillis <= 0L) {
                                times
                            } else {
                                LongArray(times.size) { maxOf(times[it], earliestArrangementMillis) }
                            }
                        }
                } else {
                    null
                }
                // Its time at this machine, for the position it shares here. Uncertain: it may leave.
                percentile(homeTimes, settings.registeredPercentile)
                    ?.let { hereEstimates[key] = Estimate(it, uncertain = true) }
                val estimate = if (share >= settings.certainAtLeast && awayTimes != null) {
                    percentile(awayTimes, settings.registeredPercentile)
                        ?.let { Estimate(it, uncertainAhead(partner, key)) }
                } else {
                    percentile(earlierOfEach(homeTimes, awayTimes), settings.registeredPercentile)
                        ?.let { Estimate(it, uncertain = true) }
                }
                estimate?.let { estimates[key] = it }
            }
        }

        // On a machine of a pair the newcomer is replayed with both machines, moves included.
        newcomers.filterKeys { it in pairedIds && policy.isOperational(it) }.forEach { (machineId, newcomer) ->
            NaturalReplay(normalized, policy, timing, settings, nowMillis, pairedIds, mapOf(machineId to newcomer))
                .run()
                .newcomerWaits(settings.newRegistrationPercentile)[machineId]
                ?.let { newcomerWaits[machineId] = it }
        }
        // The other end of an uncertain estimate: this machine's own queue as it stands, with
        // nobody moved away and nobody arriving. One plain replay per machine, kept simple on
        // purpose (maintainer, 2026-11-28: “估计时间要注意避免过拟合，覆盖常用场景为主”).
        val ifNobodyMoves = mutableMapOf<Int, Long>()
        normalized.queues.forEach { (machineId, queue) ->
            if (partners[machineId] == null || !policy.isOperational(machineId)) return@forEach
            val uncertainKeys = queue.waiting
                .filter { estimates[it.key]?.uncertain == true }
                .mapTo(mutableSetOf(), Registration::key)
            if (uncertainKeys.isEmpty()) return@forEach
            val times = replay(machineId, queue.waiting, uncertainKeys)
            uncertainKeys.forEach { key ->
                percentile(times[key], settings.registeredPercentile)?.let { ifNobodyMoves[key] = it }
            }
        }
        return Result(estimates, newcomerWaits, confidence, here = hereEstimates, ifNobodyMoves = ifNobodyMoves)
    }

    /**
     * Above this many minutes a wait is shown as 2 小时以上, without a number (maintainer,
     * 2026-11-28). [displayMinutes] then gives a value above it, so that comparisons still work.
     */
    const val LONG_WAIT_MINUTES = 120L

    /**
     * Whole minutes as shown, by the maintainer's steps (2026-11-28: “45分钟以内都精确到1分钟，
     * 45-90分钟精确到5分钟，90-120分钟精确到10分钟。120分钟以上一律显示‘2小时以上’”): 1 up to
     * 45, 5 below 90, 10 up to 120. Above [LONG_WAIT_MINUTES] the minutes rounded up are
     * returned, which every client shows as 2 小时以上. The step is chosen by the wait itself.
     */
    fun displayMinutes(waitMillis: Long, roundDown: Boolean): Long {
        val minutes = waitMillis.coerceAtLeast(0L) / 60_000.0
        if (minutes > LONG_WAIT_MINUTES) return ceil(minutes).toLong()
        val step = when {
            minutes <= 45.0 -> 1.0
            minutes < 90.0 -> 5.0
            else -> 10.0
        }
        val steps = if (roundDown) floor(minutes / step) else floor(minutes / step + 0.5)
        return (steps * step).toLong()
    }

    /**
     * The minutes shown for the positions of one machine, in their displayed order. An uncertain
     * estimate is rounded down, a certain one to the nearest step; a position is then never shown
     * later than one behind it, so rounding cannot make the order look reversed.
     */
    fun displayMinutesInOrder(
        estimates: List<Estimate?>,
        /** The waits at this machine (Result.hereEstimate); the same as [estimates] by default. */
        hereEstimates: List<Estimate?> = estimates
    ): List<Long?> {
        val limits = laterLimits(hereEstimates)
        return estimates.mapIndexed { index, estimate ->
            estimate?.let { displayMinutes(it.waitMillis, roundDown = it.uncertain) }
                ?.let { value -> limits[index]?.let { minOf(value, it) } ?: value }
        }
    }

    /**
     * For each position, the wait it is shown with at most, so that rounding cannot make the
     * order look reversed: the least shown wait at this machine of itself and of the positions
     * behind it whose wait is not really shorter. A time at the other machine of a pair does not
     * count.
     *
     * A position behind whose wait really is shorter limits nothing. That is no effect of
     * rounding: the estimate counts on somebody ahead moving to the other machine (shown as
     * 最快 while that is uncertain, as a plain time once it is taken as certain), and then a
     * registration behind may well play before registrations shown ahead of it, for example by
     * sharing a round with one whose partner has left. Such a time used to pull every position
     * ahead down to itself, so that several positions in a row showed the same minutes, 8 to 16
     * minutes too early (review 2026-11-27, and the second simulation of 2026-11-28).
     */
    fun laterLimits(hereEstimates: List<Estimate?>): List<Long?> =
        hereEstimates.indices.map { index ->
            val own = hereEstimates[index]
            var limit: Long? = null
            for (later in index until hereEstimates.size) {
                val estimate = hereEstimates[later] ?: continue
                val counts = later == index || (own != null && estimate.waitMillis >= own.waitMillis)
                if (!counts) continue
                val value = displayMinutes(estimate.waitMillis, roundDown = estimate.uncertain)
                limit = limit?.let { minOf(value, it) } ?: value
            }
            limit
        }

    private fun earlierOfEach(first: LongArray?, second: LongArray?): LongArray? {
        if (first == null) return second
        if (second == null) return first
        return LongArray(first.size) { index ->
            val a = first[index]
            val b = second.getOrElse(index) { NO_TURN }
            when {
                a == NO_TURN -> b
                b == NO_TURN -> a
                else -> minOf(a, b)
            }
        }
    }

    /** The value at [fraction] of the replays that reached a turn; null when none did. */
    private fun percentile(times: LongArray?, fraction: Double): Long? {
        val reached = times?.filter { it != NO_TURN }?.sorted() ?: return null
        if (reached.isEmpty()) return null
        val index = (ceil(fraction * reached.size).toInt() - 1).coerceIn(0, reached.lastIndex)
        return reached[index]
    }

    private fun newcomers(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        nowMillis: Long
    ): Map<String, Registration> {
        val usedKeys = state.allRegistrations.mapTo(mutableSetOf(), Registration::key)
        var nextKey = (usedKeys.maxOrNull() ?: 0) + 1
        return state.queues.filter { (machineId, queue) ->
            policy.isOperational(machineId) && queue.registrationCount < policy.maxRegistrationsPerMachine
        }.mapValues { (machineId, _) ->
            Registration(
                key = nextKey++,
                displayId = "$NEWCOMER_ID$machineId",
                preference = if (policy.machineCapacity(machineId) == 1) {
                    PlayPreference.SOLO
                } else {
                    PlayPreference.OPEN_TO_JOIN
                },
                createdAtMillis = nowMillis
            )
        }
    }

    /** Round lengths: a triangle from planned − shorter through planned to planned + longer. */
    private class Timing(
        private val policy: QueueEnginePolicy,
        private val durations: Map<String, PairedMachineForecast.RoundDurations>,
        private val settings: Settings
    ) {
        fun plannedMillis(machineId: String, players: Int): Long {
            val duration = durations[machineId]
                ?: PairedMachineForecast.RoundDurations(DEFAULT_SOLO_MILLIS, DEFAULT_SHARED_MILLIS)
            val planned = if (policy.machineCapacity(machineId) == 1 || players <= 1) {
                duration.soloMillis
            } else {
                duration.sharedMillis
            }
            return planned.coerceAtLeast(MINIMUM_ROUND_MILLIS)
        }

        private fun bounds(machineId: String, players: Int): Triple<Double, Double, Double> {
            val planned = plannedMillis(machineId, players).toDouble()
            val shortest = (planned - settings.shorterByMillis).coerceIn(MINIMUM_ROUND_MILLIS.toDouble(), planned)
            return Triple(shortest, planned, planned + settings.longerByMillis)
        }

        /** A whole round of [players] players. */
        fun roundMillis(machineId: String, players: Int, unit: Double): Long {
            val (a, c, b) = bounds(machineId, players)
            return triangular(a, c, b, unit).toLong()
        }

        /** What is left of a round of [players] players that has run for [elapsedMillis]. */
        fun remainingMillis(machineId: String, players: Int, elapsedMillis: Long, unit: Double): Long {
            val (a, c, b) = bounds(machineId, players)
            val elapsed = elapsedMillis.coerceAtLeast(0L).toDouble()
            if (elapsed >= b) return 0L
            val alreadyPast = triangularCdf(a, c, b, elapsed)
            val total = triangular(a, c, b, alreadyPast + unit * (1.0 - alreadyPast))
            return (total - elapsed).coerceAtLeast(0.0).toLong()
        }

        private fun triangular(a: Double, c: Double, b: Double, u: Double): Double {
            if (b <= a) return a
            val peak = (c - a) / (b - a)
            return if (u < peak) {
                a + sqrt(u * (b - a) * (c - a))
            } else {
                b - sqrt((1.0 - u).coerceAtLeast(0.0) * (b - a) * (b - c))
            }
        }

        private fun triangularCdf(a: Double, c: Double, b: Double, x: Double): Double = when {
            x <= a -> 0.0
            x >= b -> 1.0
            x <= c -> (x - a) * (x - a) / ((b - a) * (c - a))
            else -> 1.0 - (b - x) * (b - x) / ((b - a) * (b - c))
        }
    }

    /**
     * Fixed random numbers: the same replay, machine and group of players always get the same
     * value, so recomputing every half minute moves an estimate only when the queue or the clock
     * really moved.
     */
    private object Draw {
        fun round(run: Int, machineId: String, playerKeys: List<Int>, occurrence: Int): Double =
            unit(run.toLong(), ROUND_TAG, machineId.hashCode().toLong(), keysHash(playerKeys), occurrence.toLong())

        fun currentRound(run: Int, machineId: String, playerKeys: List<Int>, startedAtMillis: Long?): Double =
            unit(run.toLong(), CURRENT_TAG, machineId.hashCode().toLong(), keysHash(playerKeys), startedAtMillis ?: 0L)

        fun checksIn(run: Int, registrationKey: Int, share: Double): Boolean =
            unit(run.toLong(), CHECK_IN_TAG, registrationKey.toLong()) < share

        private fun keysHash(keys: List<Int>): Long =
            keys.sorted().fold(KEYS_SEED) { hash, key -> mix(hash xor key.toLong()) }

        private fun unit(vararg parts: Long): Double {
            var hash = UNIT_SEED
            parts.forEach { hash = mix(hash xor it) }
            return (hash ushr 11) * (1.0 / (1L shl 53))
        }

        // SplitMix64 finaliser.
        private fun mix(value: Long): Long {
            var z = value + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        private const val ROUND_TAG = 1L
        private const val CURRENT_TAG = 2L
        private const val CHECK_IN_TAG = 3L
        private const val KEYS_SEED = 0x51A7E5L
        private const val UNIT_SEED = 0x2545F4914F6CDD1DL
    }

    /** Pending online registrations as they turn out in [run]. */
    private fun MachineQueue.withCheckIns(run: Int, settings: Settings, keepsNoPlace: Boolean): MachineQueue =
        copy(
            waiting = waiting.map { registration ->
                if (registration.requiresOnSiteCheckIn && Draw.checksIn(run, registration.key, settings.checkInShare)) {
                    // Like the forecast: a registration that checks in later holds no place at
                    // the other machine until it really has.
                    registration.copy(
                        requiresOnSiteCheckIn = false,
                        alsoQueuesAtPairedMachine = registration.alsoQueuesAtPairedMachine && !keepsNoPlace
                    )
                } else {
                    registration
                }
            }
        )

    /** One machine on its own, as [estimatedMinutesUntilPlaying] in the app replays it. */
    private class MachineReplay(
        private val machineId: String,
        private val queue: MachineQueue,
        private val capacity: Int,
        private val timing: Timing,
        private val settings: Settings,
        private val nowMillis: Long
    ) {
        fun run(targets: Set<Int>): Map<Int, LongArray> {
            val times = targets.associateWith { LongArray(settings.runs) { NO_TURN } }
            if (targets.isEmpty()) return times
            val single = if (capacity == 1) singlePlayerQueue(queue) else queue
            repeat(settings.runs) { run ->
                var simulated = single.withCheckIns(run, settings, keepsNoPlace = false)
                val occurrences = mutableMapOf<List<Int>, Int>()
                var waitMillis = 0L
                if (simulated.playing.isNotEmpty()) {
                    val keys = simulated.playing.map(Registration::key)
                    val elapsed = simulated.playingStartedAtMillis?.let { nowMillis - it } ?: 0L
                    waitMillis += timing.remainingMillis(
                        machineId,
                        simulated.playing.size,
                        elapsed,
                        Draw.currentRound(run, machineId, keys, simulated.playingStartedAtMillis)
                    )
                    simulated = simulated.finishRound(nowMillis + waitMillis).filledAgain()
                } else {
                    simulated = simulated.enterPlayingPosition().filledAgain()
                }
                val found = mutableSetOf<Int>()
                var steps = 0
                while (steps++ < single.registrationCount * 4 + 8) {
                    simulated.playing.forEach { registration ->
                        if (registration.key in targets && found.add(registration.key)) {
                            times.getValue(registration.key)[run] = waitMillis
                        }
                    }
                    if (found.size == targets.size || simulated.playing.isEmpty()) break
                    val keys = simulated.playing.map(Registration::key).sorted()
                    val occurrence = occurrences.merge(keys, 1, Int::plus) ?: 1
                    waitMillis += timing.roundMillis(
                        machineId,
                        simulated.playing.size,
                        Draw.round(run, machineId, keys, occurrence)
                    )
                    simulated = simulated.finishRound(nowMillis + waitMillis).filledAgain()
                }
            }
            return times
        }

        /**
         * An opportunity that a 暂缓一次 or 轮空一次 used up with nobody else to play leaves the
         * playing position empty; the next one, taken at once, lets the registration play. Without
         * this a lone deferred registration got no wait at all.
         */
        private fun MachineQueue.filledAgain(): MachineQueue {
            var queue = this
            var attempts = 0
            while (queue.playing.isEmpty() && queue.waiting.isNotEmpty() && attempts++ < 3) {
                val next = queue.enterPlayingPosition()
                if (next == queue) break
                queue = next
            }
            return queue
        }

        private fun singlePlayerQueue(queue: MachineQueue): MachineQueue {
            fun solo(registration: Registration) =
                registration.copy(preference = PlayPreference.SOLO, fixedPartnerKey = null)
            val playing = queue.playing.take(1).map(::solo)
            return queue.copy(
                playing = playing,
                waiting = queue.playing.drop(1).map(::solo) + queue.waiting.map(::solo),
                playingStartedAtMillis = queue.playingStartedAtMillis.takeIf { playing.isNotEmpty() }
            )
        }
    }

    /**
     * Both machines of every pair together, with moves happening as the rules let them. The
     * previews that the rules wait for are kept up as time passes in the replay. Deciding a
     * preview the way the terminal does would need a whole forecast at every step of every
     * replay, so a quicker comparison is used: how long each machine needs, at its planned
     * round length, until it reaches the registration's position.
     */
    private class NaturalReplay(
        private val state: QueueEngineState,
        private val policy: QueueEnginePolicy,
        private val timing: Timing,
        private val settings: Settings,
        private val nowMillis: Long,
        private val pairedIds: List<String>,
        private val newcomers: Map<String, Registration>
    ) {
        /** Per run: registration key to the machine and wait of its first turn. */
        private val turns = mutableListOf<Map<Int, PairedMachineForecast.FirstTurn>>()

        fun run(): NaturalReplay {
            repeat(settings.runs) { run -> turns += replay(run) }
            return this
        }

        fun moveConfidence(
            state: QueueEngineState,
            partners: Map<String, String?>
        ): Map<Int, Double> = buildMap {
            state.queues.forEach { (machineId, queue) ->
                val partner = partners[machineId] ?: return@forEach
                queue.waiting.filter(Registration::holdsPairedMachinePlace).forEach { registration ->
                    val reached = turns.mapNotNull { it[registration.key] }
                    put(
                        registration.key,
                        if (reached.isEmpty()) 0.0 else reached.count { it.machineId == partner }.toDouble() / reached.size
                    )
                }
            }
        }

        fun newcomerWaits(fraction: Double): Map<String, Long> =
            newcomers.filterKeys { it in pairedIds }.mapNotNull { (machineId, newcomer) ->
                val waits = LongArray(turns.size) { run -> turns[run][newcomer.key]?.waitMillis ?: NO_TURN }
                percentile(waits, fraction)?.let { machineId to it }
            }.toMap()

        private fun replay(run: Int): Map<Int, PairedMachineForecast.FirstTurn> {
            var simulated = PairedMachineQueues.normalize(
                state.copy(
                    queues = state.queues.mapValues { (machineId, queue) ->
                        if (machineId !in pairedIds) {
                            queue
                        } else {
                            val newcomer = newcomers[machineId]
                            val withNewcomer = if (newcomer != null) queue.stageWaiting(newcomer) else queue
                            withNewcomer.withCheckIns(run, settings, keepsNoPlace = true)
                        }
                    }
                ),
                policy
            )
            val targets = pairedIds.flatMapTo(mutableSetOf()) { machineId ->
                simulated.queue(machineId)?.waiting.orEmpty().map(Registration::key)
            }
            val firstTurns = linkedMapOf<Int, PairedMachineForecast.FirstTurn>()
            val roundEndsAt = mutableMapOf<String, Long>()
            val occurrences = mutableMapOf<Pair<String, List<Int>>, Int>()

            pairedIds.forEach { machineId ->
                val queue = simulated.queue(machineId) ?: return@forEach
                if (queue.playing.isNotEmpty() && policy.isOperational(machineId)) {
                    val keys = queue.playing.map(Registration::key)
                    roundEndsAt[machineId] = timing.remainingMillis(
                        machineId,
                        queue.playing.size,
                        queue.playingStartedAtMillis?.let { nowMillis - it } ?: 0L,
                        Draw.currentRound(run, machineId, keys, queue.playingStartedAtMillis)
                    )
                }
            }

            fun recordNewRounds(before: QueueEngineState, atMillis: Long, steppedMachineId: String?) {
                pairedIds.forEach { machineId ->
                    val previousPlaying = before.queue(machineId)?.playing.orEmpty().map(Registration::key)
                    val playing = simulated.queue(machineId)?.playing.orEmpty()
                    if (playing.isEmpty()) {
                        roundEndsAt.remove(machineId)
                        return@forEach
                    }
                    if (
                        machineId != steppedMachineId &&
                        playing.map(Registration::key) == previousPlaying &&
                        machineId in roundEndsAt
                    ) {
                        return@forEach
                    }
                    playing.forEach { registration ->
                        if (registration.key in targets && registration.key !in firstTurns) {
                            firstTurns[registration.key] = PairedMachineForecast.FirstTurn(machineId, atMillis)
                        }
                    }
                    val keys = playing.map(Registration::key).sorted()
                    val occurrence = occurrences.merge(machineId to keys, 1, Int::plus) ?: 1
                    roundEndsAt[machineId] = atMillis +
                        timing.roundMillis(machineId, playing.size, Draw.round(run, machineId, keys, occurrence))
                }
            }

            val wakeUps = sortedSetOf<Long>()
            fun scheduleWakeUps() {
                val rules = policy.pairedArrangementRules ?: return
                simulated.pairedArrangements.values.forEach { record ->
                    val since = record.previewSinceMillis ?: return@forEach
                    if (record.previewPausedAtMillis != null) return@forEach
                    val at = (since + rules.leadMillis - nowMillis).coerceAtLeast(0L)
                    wakeUps += at
                }
            }

            fun afterChange(before: QueueEngineState, atMillis: Long, steppedMachineId: String?) {
                recordNewRounds(before, atMillis, steppedMachineId)
                val previewed = updatePreviews(simulated, nowMillis + atMillis)
                if (previewed != simulated) {
                    val beforePreview = simulated
                    simulated = previewed
                    recordNewRounds(beforePreview, atMillis, null)
                }
                scheduleWakeUps()
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
                afterChange(before, atMillis, machineId)
                return true
            }

            pairedIds.forEach { machineId ->
                val queue = simulated.queue(machineId) ?: return@forEach
                if (queue.playing.isEmpty() && policy.isOperational(machineId)) step(machineId, 0L)
            }
            scheduleWakeUps()

            val maximumSteps = targets.size * 4 + pairedIds.size * 4 + 24
            repeat(maximumSteps) {
                val stillExpected = pairedIds.flatMapTo(mutableSetOf()) { machineId ->
                    simulated.queue(machineId)?.waiting.orEmpty()
                        .filter { it.key in targets && it.absenceStatus != QueueAbsenceStatus.TEMPORARILY_AWAY }
                        .map(Registration::key)
                }
                if (firstTurns.keys.containsAll(stillExpected)) return firstTurns
                val next = roundEndsAt.minWithOrNull(compareBy({ it.value }, { it.key }))
                val wakeUp = wakeUps.firstOrNull()?.takeIf { next == null || it < next.value }
                if (wakeUp != null) {
                    wakeUps.remove(wakeUp)
                    pairedIds.forEach { machineId ->
                        val queue = simulated.queue(machineId) ?: return@forEach
                        if (queue.playing.isEmpty() && policy.isOperational(machineId)) step(machineId, wakeUp)
                    }
                    return@repeat
                }
                if (next == null) return firstTurns
                if (!step(next.key, next.value)) roundEndsAt.remove(next.key)
            }
            return firstTurns
        }

        /**
         * Starts, keeps or breaks previews at [atMillis] as the terminal would, judged by how long
         * each machine needs at its planned round length until it reaches the registration.
         */
        private fun updatePreviews(current: QueueEngineState, atMillis: Long): QueueEngineState {
            val rules = policy.pairedArrangementRules ?: return current
            val records = current.pairedArrangements.toMutableMap()
            pairedIds.forEach { homeId ->
                val partnerId = PairedMachineQueues.activePartner(current, policy, homeId) ?: return@forEach
                val home = current.queue(homeId) ?: return@forEach
                val partner = current.queue(partnerId) ?: return@forEach
                home.waiting.filter(Registration::holdsPairedMachinePlace).forEach { candidate ->
                    val record = records[candidate.key] ?: PairedArrangementRecord()
                    val running = record.previewSinceMillis != null
                    val partnerReach = reachMillis(partnerId, partner, partnerOrder(current, partnerId, partner, home), candidate.key, atMillis)
                    val homeReach = if (policy.isOperational(homeId)) {
                        reachMillis(homeId, home, home.waiting, candidate.key, atMillis)
                    } else {
                        null
                    }
                    val margin = if (running) rules.breakMarginMillis else rules.appearMarginMillis
                    val shown = policy.isOperational(partnerId) && partnerReach != null &&
                        (homeReach == null || homeReach - partnerReach > margin)
                    if (!shown) {
                        val cleared = record.copy(previewSinceMillis = null, previewPausedAtMillis = null)
                        if (cleared == PairedArrangementRecord()) records.remove(candidate.key) else records[candidate.key] = cleared
                        return@forEach
                    }
                    val paused = PairedMachineQueues.roundIsOverdue(partner, rules, atMillis) ||
                        (policy.isOperational(homeId) && PairedMachineQueues.roundIsOverdue(home, rules, atMillis))
                    var since = record.previewSinceMillis ?: atMillis
                    var pausedAt = record.previewPausedAtMillis
                    if (paused) {
                        pausedAt = pausedAt ?: atMillis
                    } else if (pausedAt != null) {
                        since += atMillis - pausedAt
                        pausedAt = null
                    }
                    records[candidate.key] = record.copy(previewSinceMillis = since, previewPausedAtMillis = pausedAt)
                }
            }
            if (records == current.pairedArrangements) return current
            val announced = PairedMachineQueues.normalize(current.copy(pairedArrangements = records), policy)
            return PairedMachineQueues.fillIdleMachines(announced, policy, atMillis)
        }

        private fun partnerOrder(
            state: QueueEngineState,
            partnerId: String,
            partner: MachineQueue,
            home: MachineQueue
        ): List<Registration> {
            val places = home.waiting.filter(Registration::holdsPairedMachinePlace)
            val byKey = (partner.waiting + places).associateBy(Registration::key)
            return PairedMachineQueues.reconcileOrder(
                previousOrder = state.pairedMachineOrders[partnerId],
                homeKeys = partner.waiting.map(Registration::key),
                placeKeys = places.map(Registration::key)
            ).mapNotNull { byKey[it] }
        }

        /** Planned time until [machineId] reaches the position of [key] in [order]. */
        private fun reachMillis(
            machineId: String,
            queue: MachineQueue,
            order: List<Registration>,
            key: Int,
            atMillis: Long
        ): Long? {
            val positions = groupIntoPositions(order)
            val index = positions.indexOfFirst { position -> position.any { it.key == key } }
            if (index < 0) return null
            var millis = if (queue.playing.isEmpty()) {
                0L
            } else {
                val elapsed = queue.playingStartedAtMillis?.let { atMillis - it } ?: 0L
                (timing.plannedMillis(machineId, queue.playing.size) - elapsed).coerceAtLeast(0L)
            }
            positions.take(index).forEach { position ->
                millis += timing.plannedMillis(machineId, position.size)
            }
            return millis
        }
    }

    private const val NO_TURN = -1L
    private const val NEWCOMER_ID = "预计新增登记·"
    private const val MINIMUM_ROUND_MILLIS = 60_000L
    private const val DEFAULT_SOLO_MILLIS = 12L * 60_000L
    private const val DEFAULT_SHARED_MILLIS = 15L * 60_000L
}
