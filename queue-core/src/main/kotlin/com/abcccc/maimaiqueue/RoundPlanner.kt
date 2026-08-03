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

        val nominalRegistrations = nominalFirstPosition(queue)
        val nextRegistrations = opportunityPositions(queue, skippedThisOpportunity)
            .firstOrNull { position -> position.all { it.canEnterPlayingPosition } }
            .orEmpty()
        val unavailableRegistrations = queue.waiting
            .take(opportunityEndIndex(queue, nominalRegistrations, nextRegistrations) + 1)
            .filterNot { it.canEnterPlayingPosition }

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

        val positions = opportunityPositions(queue, skippedThisOpportunity)
        val nextPlayers = positions.firstOrNull { position ->
            position.all { it.canEnterPlayingPosition }
        }.orEmpty()
        val nextPlayerKeys = nextPlayers.mapTo(mutableSetOf()) { it.key }
        val opportunityEndIndex = opportunityEndIndex(
            queue = queue,
            nominalRegistrations = nominalFirstPosition(queue),
            nextRegistrations = nextPlayers
        )
        val crossedUnavailableKeys = queue.waiting
            .take(opportunityEndIndex + 1)
            .filter {
                !it.canEnterPlayingPosition &&
                    it.key !in skippedThisOpportunity
            }
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
                        retained += registration.copy(absenceStatus = QueueAbsenceStatus.NONE)
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
     * One opportunity ends after both its nominal group and the group that can actually play.
     * An unavailable second open registration still belongs to the first nominal two-player
     * opportunity even when the available player enters the playing position alone.
     */
    private fun opportunityEndIndex(
        queue: MachineQueue,
        nominalRegistrations: List<Registration>,
        nextRegistrations: List<Registration>
    ): Int {
        if (nextRegistrations.isEmpty()) return queue.waiting.lastIndex

        fun lastIndexOf(registrations: List<Registration>): Int = registrations
            .maxOfOrNull { registration ->
                queue.waiting.indexOfFirst { it.key == registration.key }
            }
            ?: -1

        return maxOf(
            lastIndexOf(nominalRegistrations),
            lastIndexOf(nextRegistrations)
        ).coerceAtLeast(0)
    }
}
