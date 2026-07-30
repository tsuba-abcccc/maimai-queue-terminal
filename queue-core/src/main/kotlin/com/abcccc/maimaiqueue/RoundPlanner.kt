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

    internal fun advance(
        queue: MachineQueue,
        skippedThisOpportunity: Set<Int>
    ): RoundPlan = create(
        queue = queue,
        action = RoundAction.ENTER_PLAYING_POSITION,
        skippedThisOpportunity = skippedThisOpportunity
    )

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
        val preview = nextPlayingPositionPreview(preparedQueue)
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

    private fun nextPlayingPositionPreview(queue: MachineQueue): NextPlayingPositionPreview? {
        if (queue.waiting.isEmpty()) return null

        val nominalRegistrations = groupIntoPositions(
            queue.waiting.map { registration ->
                registration.copy(
                    absenceStatus = QueueAbsenceStatus.NONE,
                    temporaryAwaySkippedTurns = 0,
                    requiresOnSiteCheckIn = false
                )
            }
        ).firstOrNull().orEmpty()
        val nextRegistrations = queue.waitingPositions()
            .getOrNull(queue.firstAvailableWaitingPositionIndex() ?: -1)
            .orEmpty()
        val lastNextRegistrationIndex = nextRegistrations
            .maxOfOrNull { next -> queue.waiting.indexOfFirst { it.key == next.key } }
            ?: queue.waiting.lastIndex
        val unavailableRegistrations = queue.waiting
            .take(lastNextRegistrationIndex + 1)
            .filterNot { it.canEnterPlayingPosition }

        return NextPlayingPositionPreview(
            nominalRegistrations = nominalRegistrations,
            nextRegistrations = nextRegistrations,
            unavailableRegistrations = unavailableRegistrations
        )
    }

    private fun advanceQueue(
        queue: MachineQueue,
        atMillis: Long,
        skippedThisOpportunity: Set<Int>
    ): MachineQueue {
        if (queue.playing.isNotEmpty() || queue.waiting.isEmpty()) return queue

        val positions = queue.waitingPositions()
        val nextPlayers = positions.firstOrNull { position ->
            position.all { it.canEnterPlayingPosition }
        }.orEmpty()
        val nextPlayerKeys = nextPlayers.mapTo(mutableSetOf()) { it.key }
        val opportunityEndIndex = if (nextPlayers.isEmpty()) {
            queue.waiting.lastIndex
        } else {
            nextPlayers.maxOf { next -> queue.waiting.indexOfFirst { it.key == next.key } }
        }
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
}
