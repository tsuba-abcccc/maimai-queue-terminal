package com.abcccc.maimaiqueue

/**
 * Rules for a registration that queues at both machines of a configured pair.
 *
 * There is always exactly one registration. It lives in the queue of its current machine and,
 * while it is able to play, also holds a place in the paired machine's order. Whichever machine
 * reaches it first gets it: reaching the place on the paired machine really moves the
 * registration there.
 *
 * The place on the paired machine is not a second registration. It is stored as the
 * registration's key inside [QueueEngineState.pairedMachineOrders], which lists, for each paired
 * machine, its own waiting registrations interleaved with the places held by registrations of the
 * other machine.
 */
internal object PairedMachineQueues {

    /** The other machine of the pair, or null when the pair is absent, asymmetric or unusable. */
    fun activePartner(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        machineId: String
    ): String? {
        val partner = policy.machinePartners[machineId] ?: return null
        if (partner == machineId) return null
        if (policy.machinePartners[partner] != machineId) return null
        if (state.queue(machineId) == null || state.queue(partner) == null) return null
        // Moving between machines of different capacity would rewrite the play preference.
        if (policy.machineCapacity(machineId) != policy.machineCapacity(partner)) return null
        return partner
    }

    /**
     * Brings flags and orders in line with the queues. Every state produced by the engine has
     * passed through this function, so it is a fixed point: normalizing twice changes nothing.
     *
     * [frontReturnedKeys] names registrations that an operator deliberately put back at the very
     * front of [frontReturnedMachineId]; they stay ahead of places held by the other machine.
     */
    fun normalize(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        frontReturnedMachineId: String? = null,
        frontReturnedKeys: Set<Int> = emptySet(),
        sentBackKeys: Map<String, Set<Int>> = emptyMap()
    ): QueueEngineState {
        val partners = state.queues.keys.associateWith { activePartner(state, policy, it) }

        // A registration can only be arranged between two machines while its machine is part
        // of a usable pair, it is not bound to a fixed partner and it belongs to a profile.
        val queues = state.queues.mapValues { (machineId, queue) ->
            val paired = partners[machineId] != null
            val playingKeys = queue.playing.mapTo(mutableSetOf(), Registration::key)
            queue.mapRegistrations { registration ->
                // Only a registration of a player profile can be arranged between two machines:
                // a temporary one cannot be told that it is about to be moved.
                val queuesAtBoth = registration.alsoQueuesAtPairedMachine &&
                    paired &&
                    registration.fixedPartnerKey == null &&
                    !registration.isTemporary
                // Entering a playing position ends the round a place was given up for.
                val skipsThisRound = registration.skipsPairedMachineThisRound &&
                    registration.key !in playingKeys
                if (
                    queuesAtBoth != registration.alsoQueuesAtPairedMachine ||
                    skipsThisRound != registration.skipsPairedMachineThisRound
                ) {
                    registration.copy(
                        alsoQueuesAtPairedMachine = queuesAtBoth,
                        skipsPairedMachineThisRound = skipsThisRound
                    )
                } else {
                    registration
                }
            }
        }

        val orders = buildMap {
            queues.forEach { (machineId, queue) ->
                val partner = partners[machineId] ?: return@forEach
                val partnerQueue = queues.getValue(partner)
                put(
                    machineId,
                    reconcileOrder(
                        previousOrder = state.pairedMachineOrders[machineId],
                        homeKeys = queue.waiting.map(Registration::key),
                        placeKeys = partnerQueue.waiting
                            .filter(Registration::holdsPairedMachinePlace)
                            .map(Registration::key),
                        frontReturnedKeys = if (machineId == frontReturnedMachineId) {
                            frontReturnedKeys
                        } else {
                            emptySet()
                        },
                        fixedPartners = fixedPartnersOf(queue.waiting),
                        sentBackKeys = sentBackKeys[machineId].orEmpty()
                    )
                )
            }
        }
        val arrangements = pruneArrangements(state.pairedArrangements, queues, partners)
        return if (
            queues == state.queues &&
            orders == state.pairedMachineOrders &&
            arrangements == state.pairedArrangements
        ) {
            state
        } else {
            state.copy(
                queues = queues,
                pairedMachineOrders = orders,
                pairedArrangements = arrangements
            )
        }
    }

    /**
     * Keeps of every record only what still applies. A waiting registration that holds a place
     * at the other machine keeps its preview; a playing registration keeps where it was arranged
     * from, until that round is over; everything else is forgotten.
     */
    private fun pruneArrangements(
        arrangements: Map<Int, PairedArrangementRecord>,
        queues: Map<String, MachineQueue>,
        partners: Map<String, String?>
    ): Map<Int, PairedArrangementRecord> {
        if (arrangements.isEmpty()) return arrangements
        val empty = PairedArrangementRecord()
        val kept = linkedMapOf<Int, PairedArrangementRecord>()
        queues.forEach { (machineId, queue) ->
            val paired = partners[machineId] != null
            queue.playing.forEach { registration ->
                val record = arrangements[registration.key] ?: return@forEach
                if (paired && record.arrangedFromMachineId == partners[machineId]) {
                    kept[registration.key] = PairedArrangementRecord(
                        arrangedFromMachineId = record.arrangedFromMachineId,
                        aheadKeysAtOrigin = record.aheadKeysAtOrigin
                    )
                }
            }
            queue.waiting.forEach { registration ->
                val record = arrangements[registration.key] ?: return@forEach
                if (!paired || !registration.alsoQueuesAtPairedMachine) return@forEach
                val waitingRecord = if (registration.holdsPairedMachinePlace) {
                    record.copy(arrangedFromMachineId = null, aheadKeysAtOrigin = emptyList())
                } else {
                    // No place, no preview; but the player is still told only once per wait.
                    PairedArrangementRecord(noticedAtMillis = record.noticedAtMillis)
                }
                if (waitingRecord != empty) kept[registration.key] = waitingRecord
            }
        }
        return if (kept == arrangements) arrangements else kept
    }

    /**
     * The waiting registrations of [machineId] named by [registrationKeys] give up their place at
     * the paired machine until they have played a round. Registrations that do not queue at both
     * machines are left as they are: they have no such place.
     */
    fun giveUpPlacesForThisRound(
        state: QueueEngineState,
        machineId: String,
        registrationKeys: Set<Int>
    ): QueueEngineState {
        if (registrationKeys.isEmpty()) return state
        val queue = state.queue(machineId) ?: return state
        val waiting = queue.waiting.map { registration ->
            if (
                registration.key in registrationKeys &&
                registration.alsoQueuesAtPairedMachine &&
                !registration.skipsPairedMachineThisRound
            ) {
                registration.copy(skipsPairedMachineThisRound = true)
            } else {
                registration
            }
        }
        return if (waiting == queue.waiting) state else state.replace(machineId, queue.copy(waiting = waiting))
    }

    /**
     * The registrations that were moved when a waiting order changed from [before] to [after]:
     * everything outside the largest group that kept its order. Dragging one registration past
     * several others names only that one. Where more than one such group exists, as when two
     * neighbours change places, only the registrations all of them share count as not moved.
     */
    fun reorderedKeys(before: List<Int>, after: List<Int>): Set<Int> {
        val formerIndex = before.withIndex().associate { (index, key) -> key to index }
        val kept = after.filter { it in formerIndex }
        if (kept.isEmpty()) return emptySet()
        val rank = kept.map(formerIndex::getValue)
        val longestEndingAt = IntArray(rank.size) { 1 }
        val longestStartingAt = IntArray(rank.size) { 1 }
        for (index in rank.indices) {
            for (earlier in 0 until index) {
                if (rank[earlier] < rank[index]) {
                    longestEndingAt[index] = maxOf(longestEndingAt[index], longestEndingAt[earlier] + 1)
                }
            }
        }
        for (index in rank.indices.reversed()) {
            for (later in index + 1 until rank.size) {
                if (rank[later] > rank[index]) {
                    longestStartingAt[index] =
                        maxOf(longestStartingAt[index], longestStartingAt[later] + 1)
                }
            }
        }
        val longest = longestEndingAt.max()
        val onSomeLongest = rank.indices.filter {
            longestEndingAt[it] + longestStartingAt[it] - 1 == longest
        }
        val candidatesAtLevel = onSomeLongest.groupingBy { longestEndingAt[it] }.eachCount()
        val notMoved = onSomeLongest
            .filter { candidatesAtLevel[longestEndingAt[it]] == 1 }
            .mapTo(mutableSetOf()) { kept[it] }
        return kept.filterNotTo(mutableSetOf()) { it in notMoved }
    }

    /**
     * Rebuilds one machine's combined order.
     *
     * A place held by the other machine remembers how many of this machine's registrations are
     * ahead of it. Registrations leaving from ahead of it move it forward; reordering among this
     * machine's registrations does not change that count; registrations that arrive later stay
     * behind it. Places taken now go to the end.
     *
     * The two registrations of a fixed pair ([fixedPartners], each key to its partner) always
     * play together, so no place can be reached between them: a place that would stand there,
     * as when a registration is created for a friend behind it, stands directly behind the pair.
     */
    fun reconcileOrder(
        previousOrder: List<Int>?,
        homeKeys: List<Int>,
        placeKeys: List<Int>,
        frontReturnedKeys: Set<Int> = emptySet(),
        fixedPartners: Map<Int, Int> = emptyMap(),
        sentBackKeys: Set<Int> = emptySet()
    ): List<Int> {
        val homeSet = homeKeys.toSet()
        val placeSet = placeKeys.toSet()
        // A registration that a round or a rule sent back behind others (see [overtakenKeys])
        // counts like one that has just arrived: it no longer stands ahead of any place.
        val previous = previousOrder.orEmpty()
            .filter { (it in homeSet || it in placeSet) && it !in sentBackKeys }
            .distinct()
        val previousHome = previous.filterTo(linkedSetOf()) { it in homeSet }

        // Surviving home registrations in their new order; a place sits right after the r-th one.
        val survivingHome = homeKeys.filter { it in previousHome }
        val frontCount = homeKeys.takeWhile { it in frontReturnedKeys && it !in previousHome }.size

        val placesAfterHomeIndex = mutableMapOf<Int, MutableList<Int>>()
        var homeAhead = 0
        previous.forEach { key ->
            if (key in homeSet) {
                homeAhead++
            } else {
                val anchorIndex = if (homeAhead == 0) {
                    frontCount - 1
                } else {
                    homeKeys.indexOf(survivingHome[homeAhead - 1])
                }
                placesAfterHomeIndex.getOrPut(anchorIndex) { mutableListOf() } += key
            }
        }

        val known = previous.toSet()
        return buildList {
            placesAfterHomeIndex[-1]?.let(::addAll)
            // Places kept back until the second registration of a fixed pair has been listed.
            var heldBack = emptyList<Int>()
            homeKeys.forEachIndexed { index, key ->
                add(key)
                val places = heldBack + placesAfterHomeIndex[index].orEmpty()
                val nextIsFixedPartner = fixedPartners[key] != null &&
                    fixedPartners[key] == homeKeys.getOrNull(index + 1)
                if (nextIsFixedPartner) {
                    heldBack = places
                } else {
                    addAll(places)
                    heldBack = emptyList()
                }
            }
            placeKeys.forEach { key -> if (key !in known) add(key) }
        }
    }

    /** Every waiting registration of a fixed pair with the key of its partner. */
    private fun fixedPartnersOf(waiting: List<Registration>): Map<Int, Int> {
        if (waiting.none { it.fixedPartnerKey != null }) return emptyMap()
        return buildMap {
            waiting.forEach { registration ->
                registration.fixedPartnerKey?.let { put(registration.key, it) }
            }
        }
    }

    /**
     * The waiting registrations that others overtook between [before] and [after]: those with
     * a registration now ahead of them that waited behind them before. That is what happens to
     * a registration that goes to the tail at its turn (轮空一次, 暂时离开), that is moved to the
     * tail as not present, or that moves back to form a fixed pair.
     *
     * A place held by the other machine must not follow such a registration back: the place
     * would end up behind registrations that began to wait after it (review 2026-11-27).
     */
    fun overtakenKeys(before: List<Int>, after: List<Int>): Set<Int> {
        val formerIndex = before.withIndex().associate { (index, key) -> key to index }
        val common = after.filter { it in formerIndex }
        val overtaken = mutableSetOf<Int>()
        var latestFormerIndexAhead = -1
        common.forEach { key ->
            val former = formerIndex.getValue(key)
            if (former < latestFormerIndexAhead) overtaken += key
            latestFormerIndexAhead = maxOf(latestFormerIndexAhead, former)
        }
        return overtaken
    }

    /** A machine queue whose waiting order also contains the places held by the other machine. */
    class StagedRound(
        val machineId: String,
        val partnerMachineId: String,
        val queue: MachineQueue,
        /** The places that take part in this choice. */
        val placeKeys: Set<Int>,
        /** The machine's whole order, including the places that are passed over this time. */
        val fullOrder: List<Int>
    )

    /**
     * Prepares [baseQueue] (the machine's current queue, possibly after a preparatory step of the
     * running action) for choosing the next round. Returns null when the machine has no usable
     * pair or no place of the other machine can take part.
     */
    fun stageRound(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        machineId: String,
        baseQueue: MachineQueue,
        frontReturnedKeys: Set<Int> = emptySet(),
        atMillis: Long
    ): StagedRound? {
        val partner = activePartner(state, policy, machineId) ?: return null
        val partnerQueue = state.queue(partner) ?: return null
        val room = policy.maxRegistrationsPerMachine - baseQueue.registrationCount
        if (room <= 0) return null
        val holders = partnerQueue.waiting.filter(Registration::holdsPairedMachinePlace)
        if (holders.isEmpty()) return null

        val homeByKey = baseQueue.waiting.associateBy(Registration::key)
        val holderByKey = holders.associateBy(Registration::key)
        val order = reconcileOrder(
            previousOrder = state.pairedMachineOrders[machineId],
            homeKeys = baseQueue.waiting.map(Registration::key),
            placeKeys = holders.map(Registration::key),
            frontReturnedKeys = frontReturnedKeys,
            fixedPartners = fixedPartnersOf(baseQueue.waiting)
        )
        // A place only takes part once its registration may really be arranged now. One that
        // may not is passed over this time and keeps its position in the order. A full machine
        // cannot receive more registrations than it has room for.
        val admitted = order
            .filter { key ->
                key in holderByKey && canBeArrangedNow(
                    state = state,
                    policy = policy,
                    registrationKey = key,
                    receivingQueue = baseQueue,
                    homeMachineId = partner,
                    atMillis = atMillis
                )
            }
            .take(room)
            .toSet()
        if (admitted.isEmpty()) return null
        val waiting = order.mapNotNull { key ->
            homeByKey[key] ?: holderByKey[key]?.takeIf { key in admitted }
        }
        return StagedRound(
            machineId = machineId,
            partnerMachineId = partner,
            queue = baseQueue.copy(waiting = waiting),
            placeKeys = admitted,
            fullOrder = order
        )
    }

    /** Whether the round playing in [queue] has run longer than the rules take as normal. */
    fun roundIsOverdue(
        queue: MachineQueue?,
        rules: PairedArrangementRules,
        atMillis: Long
    ): Boolean = queue != null &&
        queue.playing.isNotEmpty() &&
        queue.playingStartedAtMillis?.let { atMillis - it > rules.overdueRoundMillis } == true

    /**
     * Whether a registration that holds a place here may be arranged to this machine at
     * [atMillis]. Without rules it always may. With rules its preview must have been shown long
     * enough without a break, and the queues must be kept up normally: the round on the
     * receiving machine is not overdue, and neither is the one on its own machine unless that
     * machine is out of operation altogether.
     */
    private fun canBeArrangedNow(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        registrationKey: Int,
        receivingQueue: MachineQueue,
        homeMachineId: String,
        atMillis: Long
    ): Boolean {
        val rules = policy.pairedArrangementRules ?: return true
        val record = state.pairedArrangements[registrationKey] ?: return false
        val since = record.previewSinceMillis ?: return false
        if (record.previewPausedAtMillis != null) return false
        if (atMillis - since < rules.leadMillis) return false
        if (roundIsOverdue(receivingQueue, rules, atMillis)) return false
        if (
            policy.isOperational(homeMachineId) &&
            roundIsOverdue(state.queue(homeMachineId), rules, atMillis)
        ) return false
        return true
    }

    /**
     * Writes the outcome of a staged round back: registrations of the other machine that entered
     * the playing position now belong to this machine and leave the other machine's queue.
     */
    fun settleRound(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        staged: StagedRound,
        result: MachineQueue
    ): QueueEngineState {
        val arrivedKeys = result.playing
            .mapNotNullTo(mutableSetOf()) { it.key.takeIf { key -> key in staged.placeKeys } }
        val machineQueue = result.copy(
            waiting = result.waiting.filterNot { it.key in staged.placeKeys }
        )
        val formerPartnerQueue = state.queue(staged.partnerMachineId)
        val partnerQueue = formerPartnerQueue?.let { queue ->
            if (arrivedKeys.isEmpty()) queue else queue.removeAll(arrivedKeys)
        }
        val queues = buildMap {
            putAll(state.queues)
            put(staged.machineId, machineQueue)
            if (partnerQueue != null) put(staged.partnerMachineId, partnerQueue)
        }
        // Places that were not reached keep the position the round logic left them in; places
        // that were passed over this time are put back where they stood.
        val takingPart = staged.queue.waiting.mapTo(mutableSetOf(), Registration::key)
        val keptOrder = result.waiting.map(Registration::key)
        val machineOrder = weaveBack(
            previousOrder = staged.fullOrder,
            keptOrder = keptOrder,
            passedOver = staged.fullOrder.filterNotTo(mutableSetOf()) { it in takingPart },
            sentBack = overtakenKeys(staged.queue.waiting.map(Registration::key), keptOrder)
        )
        // With the announcement rules a registration that was arranged here remembers where it
        // came from, in case it does not show up.
        val arrangements = if (policy.pairedArrangementRules == null || arrivedKeys.isEmpty()) {
            state.pairedArrangements
        } else {
            val formerWaitingKeys = formerPartnerQueue?.waiting.orEmpty().map(Registration::key)
            state.pairedArrangements + arrivedKeys.associateWith { key ->
                PairedArrangementRecord(
                    arrangedFromMachineId = staged.partnerMachineId,
                    aheadKeysAtOrigin = formerWaitingKeys.takeWhile { it != key }
                )
            }
        }
        return state.copy(
            queues = queues,
            pairedMachineOrders = state.pairedMachineOrders + (staged.machineId to machineOrder),
            pairedArrangements = arrangements
        )
    }

    /**
     * [keptOrder] with the keys of [passedOver] put back: each one directly behind the nearest
     * key that stood ahead of it in [previousOrder] and is still there, or at the front. A key
     * of [sentBack] went behind others in this round; nothing is put back behind it.
     */
    private fun weaveBack(
        previousOrder: List<Int>,
        keptOrder: List<Int>,
        passedOver: Set<Int>,
        sentBack: Set<Int> = emptySet()
    ): List<Int> {
        if (passedOver.isEmpty()) return keptOrder
        val result = keptOrder.toMutableList()
        val kept = keptOrder.toSet()
        var anchor: Int? = null
        var insertedAfterAnchor = 0
        previousOrder.forEach { key ->
            when (key) {
                in kept -> if (key !in sentBack) {
                    anchor = key
                    insertedAfterAnchor = 0
                }
                in passedOver -> {
                    val anchorKey = anchor
                    val base = if (anchorKey == null) 0 else result.indexOf(anchorKey) + 1
                    result.add(base + insertedAfterAnchor, key)
                    insertedAfterAnchor++
                }
            }
        }
        return result
    }

    /** What [returnAbsentArrivals] did. */
    class ReturnedArrivals(val state: QueueEngineState, val returnedKeys: Set<Int>)

    /**
     * Sends registrations that the system arranged to [machineId] and that did not show up
     * back to the machine they came from: behind the last of the registrations that were ahead
     * of them there and are still waiting, or to the front. Being arranged was the system's
     * doing, so it is not counted as a no-show, and the choice to be arranged ends: the player
     * turns it on again if they want. A registration that cannot go back, because that machine
     * is out of operation, full or no longer its pair, is left for the ordinary no-show rules.
     */
    fun returnAbsentArrivals(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        machineId: String,
        registrationKeys: Set<Int>
    ): ReturnedArrivals {
        if (state.pairedArrangements.isEmpty()) return ReturnedArrivals(state, emptySet())
        var current = state
        val returned = mutableSetOf<Int>()
        state.queue(machineId)?.playing.orEmpty()
            .filter { it.key in registrationKeys }
            .forEach { registration ->
                val record = current.pairedArrangements[registration.key] ?: return@forEach
                val origin = record.arrangedFromMachineId ?: return@forEach
                val originQueue = current.queue(origin) ?: return@forEach
                if (activePartner(current, policy, machineId) != origin) return@forEach
                if (!policy.isOperational(origin)) return@forEach
                if (originQueue.registrationCount >= policy.maxRegistrationsPerMachine) return@forEach
                val behindAhead = originQueue.waiting
                    .indexOfLast { it.key in record.aheadKeysAtOrigin } + 1
                // A fixed pair formed there in the meantime stays together: go behind it.
                val splitsFixedPair = behindAhead in 1 until originQueue.waiting.size &&
                    originQueue.waiting[behindAhead - 1].fixedPartnerKey ==
                    originQueue.waiting[behindAhead].key
                val insertAt = if (splitsFixedPair) behindAhead + 1 else behindAhead
                val back = registration.copy(
                    alsoQueuesAtPairedMachine = false,
                    skipsPairedMachineThisRound = false
                )
                current = current.copy(
                    queues = current.queues +
                        (machineId to current.queues.getValue(machineId).removeAll(setOf(registration.key))) +
                        (
                            origin to originQueue.copy(
                                waiting = originQueue.waiting.toMutableList().apply { add(insertAt, back) }
                            )
                            ),
                    pairedArrangements = current.pairedArrangements - registration.key
                )
                returned += registration.key
            }
        return ReturnedArrivals(current, returned)
    }

    /** True when [result] still carries the places that [staged] added to the waiting order. */
    fun isStagedResult(staged: StagedRound, result: MachineQueue): Boolean =
        staged.placeKeys.all { key -> result.allRegistrations.any { it.key == key } }

    /**
     * An operational machine of a pair that stands empty for lack of its own players takes the
     * first place held by the other machine. A machine that has players of its own who could
     * enter is left alone: its empty playing position is then a deliberate state.
     */
    fun fillIdleMachines(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        atMillis: Long
    ): QueueEngineState {
        var current = state
        repeat(current.queues.size * 2 + 2) {
            val machineId = current.queues.keys.firstOrNull { candidate ->
                val queue = current.queues.getValue(candidate)
                queue.playing.isEmpty() &&
                    policy.isOperational(candidate) &&
                    queue.firstAvailableWaitingPositionIndex() == null &&
                    stageRound(current, policy, candidate, queue, atMillis = atMillis)
                        ?.queue?.firstAvailableWaitingPositionIndex() != null
            } ?: return current
            val staged = stageRound(
                current,
                policy,
                machineId,
                current.queues.getValue(machineId),
                atMillis = atMillis
            ) ?: return current
            // Nobody of this machine can play, and nobody started a round here: a registration
            // of the other machine only takes the empty playing position. That is not a turn of
            // the registrations waiting here. Their 暂缓一次, 轮空一次 and 暂时离开 stay as they
            // are, and a pending online registration keeps its check-in time (maintainer,
            // 2026-11-27: “另一机台的登记来填空位，不算本机台登记的一轮”).
            val untouched = current.queues.getValue(machineId).waiting
                .filterNot { it.canEnterPlayingPosition }
                .mapTo(mutableSetOf(), Registration::key)
            val result = RoundPlanner.advance(staged.queue, untouched).execute(atMillis)
            val settled = normalize(settleRound(current, policy, staged, result), policy)
            if (settled == current) return current
            current = settled
        }
        return current
    }

    /** Structural problems in the stored orders; empty when no pair is in use. */
    fun invariantViolations(state: QueueEngineState): List<String> = buildList {
        if (state.pairedMachineOrders.isEmpty()) return@buildList
        val waitingKeysByMachine = state.queues.mapValues { (_, queue) ->
            queue.waiting.map(Registration::key)
        }
        val registrationsByKey = state.allRegistrations.associateBy(Registration::key)
        state.pairedMachineOrders.forEach { (machineId, order) ->
            val homeKeys = waitingKeysByMachine[machineId]
            if (homeKeys == null) {
                add("同时排队的顺序不能指向不存在的机台")
                return@forEach
            }
            if (order.distinct().size != order.size) {
                add("同时排队的顺序不能重复包含同一份登记")
            }
            val homeSet = homeKeys.toSet()
            if (order.filter { it in homeSet } != homeKeys) {
                add("同时排队的顺序必须与本机台的等待顺序一致")
            }
            homeKeys.zipWithNext().forEach { (first, second) ->
                if (
                    registrationsByKey[first]?.fixedPartnerKey == second &&
                    order.indexOf(second) - order.indexOf(first) != 1
                ) {
                    add("另一机台的位置不能位于固定组合的两份登记之间")
                }
            }
            order.filterNot { it in homeSet }.forEach { key ->
                val registration = registrationsByKey[key]
                val waitsElsewhere = waitingKeysByMachine.any { (otherMachineId, keys) ->
                    otherMachineId != machineId && key in keys
                }
                if (registration == null || !waitsElsewhere) {
                    add("另一机台的位置必须对应一份正在另一机台等待的登记")
                } else if (!registration.holdsPairedMachinePlace) {
                    add("不能同时排队的登记不应在另一机台保留位置")
                }
            }
        }
    }
}

/** Whether this registration currently holds a place in the paired machine's order. */
val Registration.holdsPairedMachinePlace: Boolean
    get() = alsoQueuesAtPairedMachine &&
        !skipsPairedMachineThisRound &&
        canEnterPlayingPosition &&
        fixedPartnerKey == null

private fun MachineQueue.mapRegistrations(transform: (Registration) -> Registration): MachineQueue {
    val mappedPlaying = playing.map(transform)
    val mappedWaiting = waiting.map(transform)
    return if (mappedPlaying == playing && mappedWaiting == waiting) {
        this
    } else {
        copy(playing = mappedPlaying, waiting = mappedWaiting)
    }
}
