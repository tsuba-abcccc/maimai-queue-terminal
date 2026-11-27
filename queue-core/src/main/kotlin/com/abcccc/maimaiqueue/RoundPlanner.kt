package com.abcccc.maimaiqueue

enum class RoundAction {
    ENTER_PLAYING_POSITION,
    FINISH_ROUND_AND_START_NEXT,
    END_ROUND_ONLY,
    REMOVE_CURRENT_ROUND_AND_START_NEXT
}

class RoundPlan internal constructor(
    val action: RoundAction,
    val sourceQueue: MachineQueue,
    val preview: NextPlayingPositionPreview?,
    private val resultTemplate: MachineQueue,
    private val completedRegistrationKeys: Set<Int>,
    private val startsPlayingPosition: Boolean
) {
    /**
     * Applies execution timestamps without recalculating the planned queue structure.
     * The plan remains immutable and can therefore be used by both UI copy and execution.
     */
    fun execute(atMillis: Long = System.currentTimeMillis()): MachineQueue {
        val completed = if (completedRegistrationKeys.isEmpty()) {
            resultTemplate
        } else {
            val applyCompletionTime: (Registration) -> Registration = { registration ->
                if (registration.key in completedRegistrationKeys) {
                    registration.copy(lastPlayedAtMillis = atMillis)
                } else {
                    registration
                }
            }
            resultTemplate.copy(
                playing = resultTemplate.playing.map(applyCompletionTime),
                waiting = resultTemplate.waiting.map(applyCompletionTime)
            )
        }
        return if (startsPlayingPosition) {
            completed.copy(playingStartedAtMillis = atMillis)
        } else {
            completed
        }
    }

    /** Returns null when the confirmation was made for an older queue snapshot. */
    fun applyTo(
        currentQueue: MachineQueue,
        atMillis: Long = System.currentTimeMillis()
    ): MachineQueue? = if (currentQueue == sourceQueue) execute(atMillis) else null
}

object RoundPlanner {
    /**
     * The waiting registrations that would enter an empty playing position now: the first group
     * that can play, with passing only where the check before a pass allows it. It can differ
     * from the first available entry of [MachineQueue.waitingPositions], which always shows the
     * passing the registrations allow (review 2026-11-27).
     */
    fun nextWaitingGroup(queue: MachineQueue): List<Registration> =
        if (queue.waiting.isEmpty()) {
            emptyList()
        } else {
            firstPlayableGroup(queue, groupingForThisOpportunity(queue, emptySet()), emptySet())
        }

    fun enterPlayingPosition(queue: MachineQueue): RoundPlan =
        create(queue, RoundAction.ENTER_PLAYING_POSITION)

    fun finishRound(queue: MachineQueue): RoundPlan =
        create(queue, RoundAction.FINISH_ROUND_AND_START_NEXT)

    fun endRoundOnly(queue: MachineQueue): RoundPlan =
        create(queue, RoundAction.END_ROUND_ONLY)

    fun removeCurrentRoundAndStartNext(queue: MachineQueue): RoundPlan =
        create(queue, RoundAction.REMOVE_CURRENT_ROUND_AND_START_NEXT)

    /**
     * Builds the queue presentation without mutating physical waiting order.
     *
     * Pending online registrations are grouped as if they will check in before their turn.
     * A one-time deferral is placed at its first predicted playing turn, while the actual
     * registration retains its deferral state and physical position.
     */
    fun waitingProjection(
        queue: MachineQueue,
        includeCommonPlayPreview: Boolean = true
    ): WaitingQueueProjection {
        if (queue.waiting.isEmpty()) return WaitingQueueProjection(emptyList())

        val originalByKey = queue.allRegistrations.associateBy { it.key }
        val waitingIndexByKey = queue.waiting.mapIndexed { index, registration ->
            registration.key to index
        }.toMap()
        val schedule = projectedFirstPlayingTurns(queue)
        val scheduledKeysInOrder = schedule.entries
            .sortedWith(
                compareBy<Map.Entry<Int, ProjectedPlayingTurn>>(
                    { it.value.turnIndex },
                    { it.value.orderInTurn },
                    { waitingIndexByKey[it.key] ?: Int.MAX_VALUE }
                )
            )
            .map { it.key }
        val scheduledKeySet = scheduledKeysInOrder.toSet()
        var scheduledIndex = 0
        val reorderedWaiting = queue.waiting.map { registration ->
            if (registration.key in scheduledKeySet) {
                originalByKey.getValue(scheduledKeysInOrder[scheduledIndex++])
            } else {
                registration
            }
        }
        val projectedIndexByKey = reorderedWaiting.mapIndexed { index, registration ->
            registration.key to index
        }.toMap()

        // Registrations belong to the same visible position only when their first predicted
        // playing turn is the same. Regrouping the reordered list directly is not sufficient:
        // a player who returns from an earlier turn is a preview partner, not a second real
        // registration in the deferred player's position.
        val scheduledPositions = schedule.entries
            .groupBy { it.value.turnIndex }
            .values
            .map { entries ->
                val registrations = entries
                    .sortedWith(
                        compareBy<Map.Entry<Int, ProjectedPlayingTurn>>(
                            { it.value.orderInTurn },
                            { waitingIndexByKey[it.key] ?: Int.MAX_VALUE }
                        )
                    )
                    .map { originalByKey.getValue(it.key) }
                val commonPlayPreview = if (
                    includeCommonPlayPreview && registrations.size == 1
                ) {
                    val registration = registrations.single()
                    entries.first().value.participantKeys
                        .singleOrNull { it != registration.key }
                        ?.let(originalByKey::get)
                        ?.takeIf { partner ->
                            registration.preference == PlayPreference.OPEN_TO_JOIN &&
                                registration.fixedPartnerKey == null &&
                                partner.preference == PlayPreference.OPEN_TO_JOIN &&
                                partner.fixedPartnerKey == null
                        }
                } else {
                    null
                }
                AnchoredWaitingPosition(
                    anchorIndex = registrations.minOf { projectedIndexByKey.getValue(it.key) },
                    projection = WaitingPositionProjection(
                        registrations = registrations,
                        commonPlayPreview = commonPlayPreview
                    )
                )
            }

        // Temporarily-away registrations, and a deferred registration for which no future
        // opportunity currently exists, retain their physical grouping and relative anchor.
        val unscheduledPositions = queue.waitingPositions().mapNotNull { physicalPosition ->
            val registrations = physicalPosition.filter { it.key !in scheduledKeySet }
            if (registrations.isEmpty()) {
                null
            } else {
                AnchoredWaitingPosition(
                    anchorIndex = registrations.minOf { projectedIndexByKey.getValue(it.key) },
                    projection = WaitingPositionProjection(registrations = registrations)
                )
            }
        }

        return WaitingQueueProjection(
            positions = (scheduledPositions + unscheduledPositions)
                .sortedBy(AnchoredWaitingPosition::anchorIndex)
                .map(AnchoredWaitingPosition::projection)
        )
    }

    internal fun advance(
        queue: MachineQueue,
        skippedThisOpportunity: Set<Int>
    ): RoundPlan = create(
        queue = queue,
        action = RoundAction.ENTER_PLAYING_POSITION,
        skippedThisOpportunity = skippedThisOpportunity
    )

    private data class ProjectedPlayingTurn(
        val turnIndex: Int,
        val orderInTurn: Int,
        val participantKeys: List<Int>
    )

    private data class AnchoredWaitingPosition(
        val anchorIndex: Int,
        val projection: WaitingPositionProjection
    )

    /**
     * For every waiting registration the number of the round in which it first plays, counted
     * from now, as the home page projects it (pending online registrations check in in time).
     * A registration that will not play, such as one that is temporarily away, has no entry.
     */
    internal fun firstPlayingRounds(queue: MachineQueue): Map<Int, Int> =
        projectedFirstPlayingTurns(queue).mapValues { (_, turn) -> turn.turnIndex }

    private fun projectedFirstPlayingTurns(
        queue: MachineQueue
    ): Map<Int, ProjectedPlayingTurn> {
        val targetKeys = queue.waiting.mapTo(linkedSetOf()) { it.key }
        if (targetKeys.isEmpty()) return emptyMap()

        // The presentation assumes pending online registrations complete check-in. Execution
        // keeps the real flag and still removes a registration that reaches its turn unsigned.
        var simulatedQueue = queue.copy(
            waiting = queue.waiting.map { registration ->
                if (registration.requiresOnSiteCheckIn) {
                    registration.copy(requiresOnSiteCheckIn = false)
                } else {
                    registration
                }
            }
        )
        val scheduled = mutableMapOf<Int, ProjectedPlayingTurn>()
        val maximumTurns = targetKeys.size * 4 + queue.playing.size * 2 + 8

        repeat(maximumTurns) { turnIndex ->
            val plan = when {
                simulatedQueue.playing.isNotEmpty() -> finishRound(simulatedQueue)
                simulatedQueue.firstAvailableWaitingPositionIndex() != null ->
                    enterPlayingPosition(simulatedQueue)
                else -> return scheduled
            }
            val nextQueue = plan.execute(atMillis = turnIndex + 1L)
            val participantKeys = nextQueue.playing.map { it.key }
            nextQueue.playing.forEachIndexed { orderInTurn, registration ->
                if (registration.key in targetKeys && registration.key !in scheduled) {
                    scheduled[registration.key] = ProjectedPlayingTurn(
                        turnIndex = turnIndex,
                        orderInTurn = orderInTurn,
                        participantKeys = participantKeys
                    )
                }
            }
            simulatedQueue = nextQueue
            if (scheduled.keys.containsAll(targetKeys.filter { key ->
                    queue.waiting.first { it.key == key }.absenceStatus !=
                        QueueAbsenceStatus.TEMPORARILY_AWAY
                })) {
                return scheduled
            }
        }
        return scheduled
    }

    private fun create(
        queue: MachineQueue,
        action: RoundAction,
        skippedThisOpportunity: Set<Int> = emptySet()
    ): RoundPlan {
        val completedRegistrationKeys = when (action) {
            RoundAction.FINISH_ROUND_AND_START_NEXT,
            RoundAction.END_ROUND_ONLY -> queue.playing.mapTo(mutableSetOf()) { it.key }
            else -> emptySet()
        }
        val preparedQueue = when (action) {
            RoundAction.ENTER_PLAYING_POSITION -> queue
            RoundAction.FINISH_ROUND_AND_START_NEXT,
            RoundAction.END_ROUND_ONLY -> queue.endRoundWithoutStartingNext(atMillis = 0L)
            RoundAction.REMOVE_CURRENT_ROUND_AND_START_NEXT -> {
                if (queue.playing.isEmpty()) {
                    queue
                } else {
                    queue.removeAll(queue.playing.mapTo(mutableSetOf()) { it.key })
                }
            }
        }
        val preview = nextPlayingPositionPreview(preparedQueue, skippedThisOpportunity)
        val shouldAdvance = action != RoundAction.END_ROUND_ONLY &&
            !(action == RoundAction.REMOVE_CURRENT_ROUND_AND_START_NEXT && queue.playing.isEmpty())
        val resultTemplate = if (shouldAdvance) {
            advanceQueue(
                queue = preparedQueue,
                atMillis = 0L,
                skippedThisOpportunity = skippedThisOpportunity
            )
        } else {
            preparedQueue
        }
        val startsPlayingPosition = shouldAdvance &&
            preparedQueue.playing.isEmpty() &&
            resultTemplate.playing.isNotEmpty()

        return RoundPlan(
            action = action,
            sourceQueue = queue,
            preview = preview,
            resultTemplate = resultTemplate,
            completedRegistrationKeys = completedRegistrationKeys,
            startsPlayingPosition = startsPlayingPosition
        )
    }

    private fun nextPlayingPositionPreview(
        queue: MachineQueue,
        skippedThisOpportunity: Set<Int> = emptySet()
    ): NextPlayingPositionPreview? {
        if (queue.waiting.isEmpty()) return null

        val withoutPassing = queue.withoutPassing()
        val nominalRegistrations = nominalFirstPosition(withoutPassing)
        val nextRegistrations = firstPlayableGroup(
            queue,
            groupingForThisOpportunity(queue, skippedThisOpportunity),
            skippedThisOpportunity
        )
        val reachedRegistrations = firstPlayableGroup(queue, withoutPassing, skippedThisOpportunity)
        val unavailableRegistrations = unavailableReachedBy(queue, withoutPassing, reachedRegistrations)

        return NextPlayingPositionPreview(
            nominalRegistrations = nominalRegistrations,
            nextRegistrations = nextRegistrations,
            unavailableRegistrations = unavailableRegistrations,
            skippedThisOpportunityRegistrations = queue.waiting.filter {
                it.key in skippedThisOpportunity
            }
        )
    }

    private fun advanceQueue(
        queue: MachineQueue,
        atMillis: Long,
        skippedThisOpportunity: Set<Int>
    ): MachineQueue {
        if (queue.playing.isNotEmpty() || queue.waiting.isEmpty()) return queue
        return advanceQueueGroupedBy(
            queue,
            groupingForThisOpportunity(queue, skippedThisOpportunity),
            atMillis,
            skippedThisOpportunity
        )
    }

    /**
     * The queue whose grouping decides who plays at this opportunity: [queue] itself, with the
     * passing its registrations allow, or the same queue without any passing. A pair formed by
     * passing is used only when it makes nobody enter a playing position in a later round than
     * without it (maintainer, 2026-11-26: “应该加上保护，不然开启了这个功能的人会吃亏”). Passing
     * changes who pairs with whom further back, so without this check a registration behind
     * could, rarely, lose its partner and wait a round longer.
     */
    private fun groupingForThisOpportunity(
        queue: MachineQueue,
        skippedThisOpportunity: Set<Int>
    ): MachineQueue {
        val withoutPassing = queue.withoutPassing()
        if (withoutPassing === queue) return queue
        // The answer depends only on the order and states of the waiting registrations and on
        // who skips this opportunity, not on names or times; wait estimates replay the same
        // states many times with different times.
        val cacheKey = queue.waiting.map(::PassingStateItem) to skippedThisOpportunity
        // The replays start from an empty playing position, as when this group enters it; with
        // somebody still playing they would not advance at all and always say yes (review
        // 2026-11-27). So the answer also depends on nothing outside the key.
        val passes = synchronized(passingDecisions) { passingDecisions[cacheKey] }
            ?: passingDelaysNobody(
                queue.copy(playing = emptyList(), playingStartedAtMillis = null),
                withoutPassing.copy(playing = emptyList(), playingStartedAtMillis = null),
                skippedThisOpportunity
            ).also { decided ->
                synchronized(passingDecisions) { passingDecisions[cacheKey] = decided }
            }
        return if (passes) queue else withoutPassing
    }

    /** What of a waiting registration the decision to pass depends on. */
    private data class PassingStateItem(
        val key: Int,
        val preference: PlayPreference,
        val absenceStatus: QueueAbsenceStatus,
        val temporaryAwaySkippedTurns: Int,
        val fixedPartnerKey: Int?,
        val requiresOnSiteCheckIn: Boolean,
        val allowsPassing: Boolean,
        val deferOneRoundUsed: Boolean,
        val noShowCount: Int,
        val lastNoShowActionWasDefer: Boolean,
        val wasOnlineRegistration: Boolean
    ) {
        constructor(registration: Registration) : this(
            registration.key,
            registration.preference,
            registration.absenceStatus,
            registration.temporaryAwaySkippedTurns,
            registration.fixedPartnerKey,
            registration.requiresOnSiteCheckIn,
            registration.allowsPassing,
            registration.deferOneRoundUsed,
            registration.noShowCount,
            registration.lastNoShowActionWasDefer,
            registration.wasOnlineRegistration
        )
    }

    /** Recent answers of [groupingForThisOpportunity], least recently used dropped first. */
    private val passingDecisions =
        object : LinkedHashMap<Pair<List<PassingStateItem>, Set<Int>>, Boolean>(64, .75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Pair<List<PassingStateItem>, Set<Int>>, Boolean>?
            ) = size > 512
        }

    private fun passingDelaysNobody(
        queue: MachineQueue,
        withoutPassing: MachineQueue,
        skippedThisOpportunity: Set<Int>
    ): Boolean {
        val withPassing = firstPlayableGroup(queue, queue, skippedThisOpportunity)
        val plain = firstPlayableGroup(queue, withoutPassing, skippedThisOpportunity)
        if (withPassing.map(Registration::key) == plain.map(Registration::key)) return true
        val passedFirstRounds = firstRoundsFrom(
            advanceQueueGroupedBy(queue, queue, 0L, skippedThisOpportunity)
        )
        val plainFirstRounds = firstRoundsFrom(
            advanceQueueGroupedBy(withoutPassing, withoutPassing, 0L, skippedThisOpportunity)
        )
        return plainFirstRounds.none { (key, round) ->
            (passedFirstRounds[key] ?: Int.MAX_VALUE) > round
        }
    }

    /**
     * The group entering the playing position, with the later of two registrations recording
     * the registrations it passed over in real order (only those that let others pass), and
     * every other entering registration's record cleared.
     */
    private fun recordPasses(queue: MachineQueue, group: List<Registration>): List<Registration> {
        val indexByKey = queue.waiting.mapIndexed { index, registration -> registration.key to index }.toMap()
        val ordered = group.sortedBy { indexByKey[it.key] ?: Int.MAX_VALUE }
        val passed = if (ordered.size == 2) {
            val from = indexByKey[ordered[0].key]
            val to = indexByKey[ordered[1].key]
            if (from != null && to != null && to > from + 1) {
                queue.waiting.subList(from + 1, to).filter(::isPassedOver).map(Registration::key)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        return group.map { registration ->
            val record = if (passed.isNotEmpty() && registration.key == ordered.last().key) passed else emptyList()
            if (registration.passedOverKeys == record) registration else registration.copy(passedOverKeys = record)
        }
    }

    /** The first group of [grouping] that can play now, as the registrations of [queue]. */
    private fun firstPlayableGroup(
        queue: MachineQueue,
        grouping: MachineQueue,
        skippedThisOpportunity: Set<Int>
    ): List<Registration> {
        val originalByKey = queue.waiting.associateBy(Registration::key)
        return opportunityPositions(grouping, skippedThisOpportunity)
            .firstOrNull { position -> position.all { it.canEnterPlayingPosition } }
            .orEmpty()
            .map { originalByKey.getValue(it.key) }
    }

    /**
     * The round in which each registration first plays from [started] on (0 for those playing
     * in it), with nobody passing in the later rounds. These replays never pass, so they never
     * run this check again themselves.
     */
    private fun firstRoundsFrom(started: MachineQueue): Map<Int, Int> {
        var queue = started.copy(
            playing = started.playing.map { it.copy(allowsPassing = false) },
            waiting = started.waiting.map { it.copy(allowsPassing = false) }
        )
        val allKeys = queue.allRegistrations.mapTo(mutableSetOf(), Registration::key)
        val rounds = mutableMapOf<Int, Int>()
        queue.playing.forEach { rounds[it.key] = 0 }
        var atMillis = 1L
        for (round in 1..(allKeys.size * 3 + 6)) {
            if (rounds.keys.containsAll(allKeys)) break
            atMillis += 15L * 60_000L
            queue = queue.finishRound(atMillis)
            if (queue.playing.isEmpty() && queue.waiting.isEmpty()) break
            queue.playing.forEach { rounds.putIfAbsent(it.key, round) }
        }
        return rounds
    }

    private fun advanceQueueGroupedBy(
        queue: MachineQueue,
        grouping: MachineQueue,
        atMillis: Long,
        skippedThisOpportunity: Set<Int>
    ): MachineQueue {
        if (queue.playing.isNotEmpty() || queue.waiting.isEmpty()) return queue

        val nextPlayers = recordPasses(queue, firstPlayableGroup(queue, grouping, skippedThisOpportunity))
        val nextPlayerKeys = nextPlayers.mapTo(mutableSetOf()) { it.key }
        // Which registrations this opportunity reaches is decided as if nobody let others pass.
        // A pair that forms across a registration that lets others pass does not reach, and so
        // does not use up, the 暂缓一次, 轮空一次 or 暂时离开 of the registrations it passes over;
        // otherwise such a registration would play earlier and push another one a round back
        // (docs/releases/solo-pass-plan-2026-11.md).
        val withoutPassing = queue.withoutPassing()
        val reachedPlayers = firstPlayableGroup(queue, withoutPassing, skippedThisOpportunity)
        val crossedUnavailableKeys = unavailableReachedBy(queue, withoutPassing, reachedPlayers)
            .filter { it.key !in skippedThisOpportunity }
            .mapTo(mutableSetOf()) { it.key }

        val retained = mutableListOf<Registration>()
        val movedToTail = mutableListOf<Registration>()
        var waitingIndex = 0
        while (waitingIndex < queue.waiting.size) {
            val first = queue.waiting[waitingIndex]
            val second = queue.waiting.getOrNull(waitingIndex + 1)
            val isFixedPair = second != null &&
                first.fixedPartnerKey == second.key &&
                second.fixedPartnerKey == first.key
            val group = if (isFixedPair) listOf(first, second!!) else listOf(first)
            waitingIndex += group.size

            val remainingGroup = group.filterNot { it.key in nextPlayerKeys }
            if (remainingGroup.isEmpty()) continue

            val consumesOpportunity = group.any { it.key in crossedUnavailableKeys }
            if (!consumesOpportunity) {
                retained += remainingGroup
                continue
            }

            // 轮空一次 of a fixed pair: both go to the tail together, counting one skipped turn
            // as 暂时离开 does; with three already counted they are removed instead.
            val fixedPairSkipsOneTurn = isFixedPair && group.any {
                it.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN
            }
            if (fixedPairSkipsOneTurn) {
                val skippedTurns = group.maxOf { it.temporaryAwaySkippedTurns }
                if (skippedTurns < 3) {
                    movedToTail += group.map {
                        it.copy(
                            absenceStatus = QueueAbsenceStatus.NONE,
                            temporaryAwaySkippedTurns = skippedTurns + 1
                        )
                    }
                }
                continue
            }
            val fixedPairIsTemporarilyAway = isFixedPair && group.any {
                it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
            }
            if (fixedPairIsTemporarilyAway) {
                val skippedTurns = group.maxOf { it.temporaryAwaySkippedTurns }
                if (skippedTurns < 3) {
                    movedToTail += group.map {
                        it.copy(
                            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                            temporaryAwaySkippedTurns = skippedTurns + 1
                        )
                    }
                }
                continue
            }

            remainingGroup.forEach { registration ->
                when {
                    registration.key !in crossedUnavailableKeys -> retained += registration
                    registration.requiresOnSiteCheckIn -> {
                        // A pending online registration leaves when its opportunity is reached.
                    }
                    registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY -> {
                        if (registration.temporaryAwaySkippedTurns < 3) {
                            movedToTail += registration.copy(
                                temporaryAwaySkippedTurns =
                                    registration.temporaryAwaySkippedTurns + 1
                            )
                        }
                    }
                    registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND -> {
                        // Used now: no second deferral before the registration has played.
                        retained += registration.copy(
                            absenceStatus = QueueAbsenceStatus.NONE,
                            deferOneRoundUsed = true
                        )
                    }
                    registration.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN -> {
                        if (registration.temporaryAwaySkippedTurns < 3) {
                            movedToTail += registration.copy(
                                absenceStatus = QueueAbsenceStatus.NONE,
                                temporaryAwaySkippedTurns = registration.temporaryAwaySkippedTurns + 1
                            )
                        }
                    }
                    else -> retained += registration
                }
            }
        }

        return queue.copy(
            playing = nextPlayers,
            waiting = sanitizeFriendPairs(retained + movedToTail),
            playingStartedAtMillis = if (nextPlayers.isEmpty()) null else atMillis
        )
    }

    /** The queue with every registration's choice to let others pass turned off. */
    private fun MachineQueue.withoutPassing(): MachineQueue =
        if (waiting.none(Registration::allowsPassing)) {
            this
        } else {
            copy(waiting = waiting.map { it.copy(allowsPassing = false) })
        }

    private fun nominalFirstPosition(queue: MachineQueue): List<Registration> =
        groupIntoPositions(
            queue.waiting.map { registration ->
                registration.copy(
                    absenceStatus = QueueAbsenceStatus.NONE,
                    temporaryAwaySkippedTurns = 0,
                    requiresOnSiteCheckIn = false
                )
            }
        ).firstOrNull().orEmpty()

    private fun opportunityPositions(
        queue: MachineQueue,
        skippedThisOpportunity: Set<Int>
    ): List<List<Registration>> {
        if (skippedThisOpportunity.isEmpty()) return queue.waitingPositions()

        val originalByKey = queue.waiting.associateBy { it.key }
        return groupIntoPositions(
            queue.waiting.map { registration ->
                if (registration.key in skippedThisOpportunity && registration.canEnterPlayingPosition) {
                    registration.copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND)
                } else {
                    registration
                }
            }
        ).map { position -> position.map { originalByKey.getValue(it.key) } }
    }

    /**
     * The registrations that cannot play now and whose turn this opportunity is, in waiting
     * order: it is they whose 暂缓一次, 轮空一次 or 暂时离开 is used, and a pending online
     * registration among them leaves.
     *
     * A turn is a registration's own when it would itself have entered the playing position
     * (maintainer, 2026-11-27: “只有登记自己本该进入游玩位置的那一轮，才算轮到它”):
     * - it stands ahead of the first registration that enters, so the round went past it; or
     * - it would have shared the round with that registration: it belongs to the position the
     *   entering registration would form if everybody behind it could play.
     *
     * Two open registrations that pair across a postponed or pending registration do not reach
     * it. It used to count as reached, so a 暂缓一次 could be used without the player gaining a
     * round, and a pending registration left a round before the position shown for it.
     *
     * When nobody can enter, every waiting registration that cannot play has been reached.
     */
    private fun unavailableReachedBy(
        queue: MachineQueue,
        withoutPassing: MachineQueue,
        enteringRegistrations: List<Registration>
    ): List<Registration> {
        if (enteringRegistrations.isEmpty()) {
            return queue.waiting.filterNot { it.canEnterPlayingPosition }
        }
        val enteringKeys = enteringRegistrations.mapTo(mutableSetOf(), Registration::key)
        val firstEnteringIndex = queue.waiting.indexOfFirst { it.key in enteringKeys }
        if (firstEnteringIndex < 0) return queue.waiting.filterNot { it.canEnterPlayingPosition }
        val sharingKeys = groupIntoPositions(
            withoutPassing.waiting.drop(firstEnteringIndex).map { registration ->
                registration.copy(
                    absenceStatus = QueueAbsenceStatus.NONE,
                    temporaryAwaySkippedTurns = 0,
                    requiresOnSiteCheckIn = false
                )
            }
        ).firstOrNull().orEmpty().mapTo(mutableSetOf(), Registration::key)
        return queue.waiting.filterIndexed { index, registration ->
            !registration.canEnterPlayingPosition &&
                (index < firstEnteringIndex || registration.key in sharingKeys)
        }
    }
}
