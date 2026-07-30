package com.abcccc.maimaiqueue

enum class PlayPreference {
    SOLO,
    OPEN_TO_JOIN
}

enum class QueueAbsenceStatus {
    NONE,
    DEFER_ONE_ROUND,
    TEMPORARILY_AWAY
}

enum class PlayerGender {
    MALE,
    FEMALE,
    UNDISCLOSED
}

enum class MachineStopReason {
    NETWORK_DISCONNECTED,
    MAINTENANCE,
    NOT_POWERED_ON,
    OTHER
}

const val MAX_MACHINE_STOP_REASON_DETAIL_CHARACTERS = 40

data class MachineStatus(
    val stopReason: MachineStopReason? = null,
    val stopReasonDetail: String? = null,
    val stoppedAtMillis: Long? = null
) {
    val isOperational: Boolean
        get() = stopReason == null

    fun stop(
        reason: MachineStopReason,
        atMillis: Long = System.currentTimeMillis(),
        reasonDetail: String? = null
    ): MachineStatus = if (isOperational) {
        copy(
            stopReason = reason,
            stopReasonDetail = normalizeMachineStopReasonDetail(reason, reasonDetail),
            stoppedAtMillis = atMillis
        )
    } else {
        this
    }

    fun restore(): MachineStatus = MachineStatus()
}

fun normalizeMachineStopReasonDetail(
    reason: MachineStopReason?,
    detail: String?
): String? = if (reason == MachineStopReason.OTHER) {
    detail?.filterNot { it.isISOControl() }
        ?.trim()
        ?.takeCodePoints(MAX_MACHINE_STOP_REASON_DETAIL_CHARACTERS)
        ?.takeIf { it.isNotEmpty() }
} else {
    null
}

private fun String.takeCodePoints(maximum: Int): String =
    if (codePointCount(0, length) <= maximum) {
        this
    } else {
        substring(0, offsetByCodePoints(0, maximum))
    }

data class Registration(
    val key: Int,
    val displayId: String,
    val preference: PlayPreference,
    val absenceStatus: QueueAbsenceStatus = QueueAbsenceStatus.NONE,
    val temporaryAwaySkippedTurns: Int = 0,
    val isTemporary: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastPlayedAtMillis: Long? = null,
    val noShowCount: Int = 0,
    val lastNoShowActionWasDefer: Boolean = false,
    val fixedPartnerKey: Int? = null,
    val gender: PlayerGender? = null,
    val playerProfileId: String? = null,
    val requiresOnSiteCheckIn: Boolean = false,
    val originatingCommandId: String? = null
) {
    val canEnterPlayingPosition: Boolean
        get() = absenceStatus == QueueAbsenceStatus.NONE && !requiresOnSiteCheckIn

    val onSiteCheckInDeadlineMillis: Long?
        get() = if (requiresOnSiteCheckIn) {
            createdAtMillis + ONLINE_REGISTRATION_CHECK_IN_TIMEOUT_MILLIS
        } else {
            null
        }

    fun hasExpiredOnSiteCheckIn(atMillis: Long): Boolean =
        onSiteCheckInDeadlineMillis?.let { atMillis >= it } == true
}

const val ONLINE_REGISTRATION_CHECK_IN_TIMEOUT_MILLIS = 30L * 60L * 1_000L

data class FriendPairPlan(
    val originalWaiting: List<Registration>,
    val waiting: List<Registration>,
    val firstRegistration: Registration,
    val secondRegistration: Registration,
    val pairPositionIndex: Int,
    val movedBackRegistrations: List<Registration>,
    val delayedOtherRegistrations: List<Registration>
)

data class NextPlayingPositionPreview(
    val nominalRegistrations: List<Registration>,
    val nextRegistrations: List<Registration>,
    val unavailableRegistrations: List<Registration>
) {
    val changedByAvailability: Boolean
        get() = unavailableRegistrations.isNotEmpty() &&
            nominalRegistrations.map { it.key }.toSet() != nextRegistrations.map { it.key }.toSet()
}

data class MachineQueue(
    val playing: List<Registration> = emptyList(),
    val waiting: List<Registration> = emptyList(),
    val playingStartedAtMillis: Long? = null
) {
    val registrationCount: Int
        get() = playing.size + waiting.size

    val allRegistrations: List<Registration>
        get() = playing + waiting

    fun containsId(displayId: String, exceptKey: Int? = null): Boolean {
        val normalizedId = displayId.trim()
        if (normalizedId.isBlank()) return false
        return allRegistrations.any {
            it.key != exceptKey && it.displayId.trim().equals(normalizedId, ignoreCase = true)
        }
    }

    fun waitingPositions(): List<List<Registration>> = groupIntoPositions(waiting)

    fun firstAvailableWaitingPositionIndex(): Int? = waitingPositions().indexOfFirst { position ->
        position.all { it.canEnterPlayingPosition }
    }.takeIf { it >= 0 }

    fun nextPlayingPositionPreview(): NextPlayingPositionPreview? {
        return RoundPlanner.enterPlayingPosition(this).preview
    }

    /** Previews the next group after the current players return to the waiting tail. */
    fun nextPlayingPositionPreviewAfterRoundEnd(): NextPlayingPositionPreview? =
        RoundPlanner.finishRound(this).preview

    /** Previews the next group after the current playing registrations are removed. */
    fun nextPlayingPositionPreviewAfterCurrentRoundRemoved(): NextPlayingPositionPreview? =
        RoundPlanner.removeCurrentRoundAndStartNext(this).preview

    fun canMarkNoShow(registrationKey: Int): Boolean {
        val registration = allRegistrations.firstOrNull { it.key == registrationKey }
            ?: return false
        if (!registration.canEnterPlayingPosition) return false
        return playing.any { it.key == registrationKey } ||
            waitingPositions().getOrNull(firstAvailableWaitingPositionIndex() ?: -1)
                ?.any { it.key == registrationKey } == true
    }

    fun join(registration: Registration): MachineQueue {
        val accepted = acceptUniqueRegistrations(listOf(registration)).firstOrNull() ?: return this
        return copy(waiting = waiting + accepted).advanceIfNeeded()
    }

    fun joinAll(registrations: List<Registration>): MachineQueue {
        val accepted = acceptUniqueRegistrations(registrations)
        if (accepted.isEmpty()) return this
        return copy(waiting = waiting + accepted).advanceIfNeeded()
    }

    /** Adds a registration to the waiting order while a multi-step preference flow is unfinished. */
    fun stageWaiting(registration: Registration): MachineQueue {
        val accepted = acceptUniqueRegistrations(listOf(registration)).firstOrNull() ?: return this
        return copy(waiting = waiting + accepted)
    }

    /**
     * Receives registrations transferred from the other machine. They always
     * enter at the waiting tail and never advance into an empty playing position.
     */
    fun receiveAtWaitingTail(registrations: List<Registration>): MachineQueue {
        val accepted = acceptUniqueRegistrations(registrations)
        if (accepted.isEmpty()) return this
        return copy(
            waiting = sanitizeFriendPairs(
                waiting + accepted.map { registration ->
                    if (registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND) {
                        registration.copy(absenceStatus = QueueAbsenceStatus.NONE)
                    } else {
                        registration
                    }
                }
            )
        )
    }

    /**
     * A completed player remains registered and returns to the end of this machine's queue.
     * Players who shared a round keep their relative order.
     */
    fun finishRound(atMillis: Long = System.currentTimeMillis()): MachineQueue {
        return RoundPlanner.finishRound(this).execute(atMillis)
    }

    fun endRoundWithoutStartingNext(atMillis: Long = System.currentTimeMillis()): MachineQueue {
        if (playing.isEmpty()) return this
        val returned = playing.map {
            it.copy(
                absenceStatus = QueueAbsenceStatus.NONE,
                temporaryAwaySkippedTurns = 0,
                noShowCount = 0,
                lastNoShowActionWasDefer = false,
                lastPlayedAtMillis = atMillis
            )
        }
        return copy(
            playing = emptyList(),
            waiting = waiting + returned,
            playingStartedAtMillis = null
        )
    }

    fun removeCurrentRoundAndStartNext(
        atMillis: Long = System.currentTimeMillis()
    ): MachineQueue {
        return RoundPlanner.removeCurrentRoundAndStartNext(this).execute(atMillis)
    }

    fun restartPlayingTimer(atMillis: Long = System.currentTimeMillis()): MachineQueue =
        if (playing.isEmpty()) this else copy(playingStartedAtMillis = atMillis)

    /**
     * Corrects an erroneous placement in the playing position. The registrations
     * return to the front of the waiting order and the next group is not advanced.
     */
    fun returnPlayingRegistrationsToWaitingFront(registrationKeys: Set<Int>): MachineQueue {
        val returned = playing.filter { it.key in registrationKeys }
        if (returned.isEmpty()) return this
        val remainingPlaying = playing.filterNot { it.key in registrationKeys }
        val sanitizedPlaying = sanitizeFriendPairs(remainingPlaying)
        return copy(
            playing = sanitizedPlaying,
            waiting = sanitizeFriendPairs(returned + waiting),
            playingStartedAtMillis = if (sanitizedPlaying.isEmpty()) null else playingStartedAtMillis
        )
    }

    /** Corrects a missing player in an ongoing round without restarting the round timer. */
    fun moveFirstWaitingRegistrationIntoCurrentRound(registrationKey: Int): MachineQueue {
        val currentPlayer = playing.singleOrNull() ?: return this
        val firstWaitingPosition = waitingPositions()
            .getOrNull(firstAvailableWaitingPositionIndex() ?: -1)
            ?: return this
        val joiningPlayer = firstWaitingPosition.firstOrNull { it.key == registrationKey } ?: return this
        if (!joiningPlayer.canEnterPlayingPosition) return this
        val remainingWaiting = sanitizeFriendPairs(waiting.filterNot { it.key == registrationKey })
        return copy(
            playing = listOf(
                currentPlayer.copy(
                    preference = PlayPreference.OPEN_TO_JOIN,
                    fixedPartnerKey = null
                ),
                joiningPlayer.copy(
                    preference = PlayPreference.OPEN_TO_JOIN,
                    absenceStatus = QueueAbsenceStatus.NONE,
                    temporaryAwaySkippedTurns = 0,
                    fixedPartnerKey = null
                )
            ),
            waiting = remainingWaiting
        )
    }

    /**
     * Replays several missed round-end operations at once. The selected waiting
     * position becomes the current round, while every earlier completed position
     * returns to the waiting tail in the same order in which it played.
     */
    fun advanceToWaitingPosition(
        registrationKeys: Set<Int>,
        atMillis: Long = System.currentTimeMillis()
    ): MachineQueue {
        if (playing.isEmpty() || registrationKeys.isEmpty()) return this

        val positions = waitingPositions()
        val targetIndex = positions.indexOfFirst { position ->
            position.size == registrationKeys.size && position.all { it.key in registrationKeys }
        }
        if (targetIndex <= 0) return this
        if (positions[targetIndex].any { !it.canEnterPlayingPosition }) return this

        val targetPosition = positions[targetIndex].map {
            it.copy(
                absenceStatus = QueueAbsenceStatus.NONE,
                temporaryAwaySkippedTurns = 0
            )
        }
        val completedRegistrations = playing.map { registration ->
            registration.copy(
                absenceStatus = QueueAbsenceStatus.NONE,
                temporaryAwaySkippedTurns = 0,
                noShowCount = 0,
                lastNoShowActionWasDefer = false,
                lastPlayedAtMillis = atMillis
            )
        }.toMutableList()
        val retainedUnavailableRegistrations = mutableListOf<Registration>()
        val temporarilyAwayRegistrations = mutableListOf<Registration>()

        positions.take(targetIndex).forEach { position ->
            // Reaching a later position means every earlier pending online
            // registration has already missed its opportunity and must leave.
            val remainingPosition = position.filterNot { it.requiresOnSiteCheckIn }
            if (remainingPosition.isEmpty()) return@forEach

            val hasTemporarilyAway = remainingPosition.any {
                it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
            }
            val hasOneRoundDeferral = remainingPosition.any {
                it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
            }
            when {
                hasTemporarilyAway -> {
                    val isFixedPair = remainingPosition.size == 2 &&
                        remainingPosition[0].fixedPartnerKey == remainingPosition[1].key &&
                        remainingPosition[1].fixedPartnerKey == remainingPosition[0].key
                    if (isFixedPair) {
                        val skippedTurns = remainingPosition.maxOf { it.temporaryAwaySkippedTurns }
                        if (skippedTurns < 3) {
                            temporarilyAwayRegistrations += remainingPosition.map {
                                it.copy(
                                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                                    temporaryAwaySkippedTurns = skippedTurns + 1
                                )
                            }
                        }
                    } else {
                        remainingPosition.forEach { registration ->
                            when (registration.absenceStatus) {
                                QueueAbsenceStatus.TEMPORARILY_AWAY -> {
                                    if (registration.temporaryAwaySkippedTurns < 3) {
                                        temporarilyAwayRegistrations += registration.copy(
                                            temporaryAwaySkippedTurns =
                                                registration.temporaryAwaySkippedTurns + 1
                                        )
                                    }
                                }
                                QueueAbsenceStatus.DEFER_ONE_ROUND -> {
                                    temporarilyAwayRegistrations += registration.copy(
                                        absenceStatus = QueueAbsenceStatus.NONE,
                                        temporaryAwaySkippedTurns = 0
                                    )
                                }
                                QueueAbsenceStatus.NONE -> temporarilyAwayRegistrations += registration
                            }
                        }
                    }
                }
                hasOneRoundDeferral -> {
                    retainedUnavailableRegistrations += remainingPosition.map { registration ->
                        if (registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND) {
                            registration.copy(absenceStatus = QueueAbsenceStatus.NONE)
                        } else {
                            registration
                        }
                    }
                }
                else -> completedRegistrations += remainingPosition.map { registration ->
                    registration.copy(
                        absenceStatus = QueueAbsenceStatus.NONE,
                        temporaryAwaySkippedTurns = 0,
                        noShowCount = 0,
                        lastNoShowActionWasDefer = false,
                        lastPlayedAtMillis = atMillis
                    )
                }
            }
        }
        val positionsStillWaiting = positions.drop(targetIndex + 1).flatten()

        return copy(
            playing = targetPosition,
            waiting = sanitizeFriendPairs(
                retainedUnavailableRegistrations +
                    positionsStillWaiting +
                    completedRegistrations +
                    temporarilyAwayRegistrations
            ),
            playingStartedAtMillis = atMillis
        )
    }

    /** Skips exactly one opportunity while preserving the registration's physical order. */
    fun deferOneRound(registrationKey: Int): MachineQueue {
        val affectedKeys = fixedGroupKeys(registrationKey)
        if (
            affectedKeys.isEmpty() ||
            allRegistrations.any { it.key in affectedKeys && it.requiresOnSiteCheckIn }
        ) return this
        val movedFromPlaying = playing.filter { it.key in affectedKeys }
        if (movedFromPlaying.isNotEmpty()) {
            val remainingPlayers = sanitizeFriendPairs(playing.filterNot { it.key in affectedKeys })
            val returnedToFront = movedFromPlaying.map {
                it.copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
                    temporaryAwaySkippedTurns = 0
                )
            }
            val updated = copy(
                playing = remainingPlayers,
                waiting = sanitizeFriendPairs(returnedToFront + waiting),
                playingStartedAtMillis = if (remainingPlayers.isEmpty()) null else playingStartedAtMillis
            )
            return if (remainingPlayers.isEmpty()) {
                updated.advanceIfNeeded()
            } else {
                updated
            }
        }

        return copy(
            waiting = waiting.map { registration ->
                if (registration.key in affectedKeys) {
                    registration.copy(
                        absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
                        temporaryAwaySkippedTurns = 0
                    )
                } else {
                    registration
                }
            }
        )
    }

    fun cancelDeferOneRound(registrationKey: Int): MachineQueue {
        val affectedKeys = fixedGroupKeys(registrationKey)
        return transformRegistrations(affectedKeys) { registration ->
            if (registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND) {
                registration.copy(absenceStatus = QueueAbsenceStatus.NONE)
            } else {
                registration
            }
        }
    }

    /** Keeps skipping turns until manually cancelled, rotating to the tail after each skipped turn. */
    fun temporarilyLeave(registrationKey: Int): MachineQueue {
        val affectedKeys = fixedGroupKeys(registrationKey)
        if (
            affectedKeys.isEmpty() ||
            allRegistrations.any { it.key in affectedKeys && it.requiresOnSiteCheckIn }
        ) return this
        val movedFromPlaying = playing.filter { it.key in affectedKeys }
        if (movedFromPlaying.isNotEmpty()) {
            val remainingPlayers = sanitizeFriendPairs(playing.filterNot { it.key in affectedKeys })
            val movedToTail = movedFromPlaying.map {
                it.copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                    temporaryAwaySkippedTurns = 1
                )
            }
            val updated = copy(
                playing = remainingPlayers,
                waiting = sanitizeFriendPairs(waiting + movedToTail),
                playingStartedAtMillis = if (remainingPlayers.isEmpty()) null else playingStartedAtMillis
            )
            return if (remainingPlayers.isEmpty()) {
                updated.advanceIfNeeded(skippedThisOpportunity = affectedKeys)
            } else {
                updated
            }
        }

        return copy(
            waiting = waiting.map { registration ->
                if (registration.key in affectedKeys) {
                    registration.copy(
                        absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                        temporaryAwaySkippedTurns = 0
                    )
                } else {
                    registration
                }
            }
        )
    }

    fun cancelTemporaryLeave(registrationKey: Int): MachineQueue {
        val affectedKeys = fixedGroupKeys(registrationKey)
        return transformRegistrations(affectedKeys) { registration ->
            if (registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY) {
                registration.copy(
                    absenceStatus = QueueAbsenceStatus.NONE,
                    temporaryAwaySkippedTurns = 0
                )
            } else {
                registration
            }
        }
    }

    fun changePreference(registrationKey: Int, preference: PlayPreference): MachineQueue {
        if (allRegistrations.any { it.key == registrationKey && it.requiresOnSiteCheckIn }) {
            return this
        }
        val partnerKey = allRegistrations.firstOrNull { it.key == registrationKey }?.fixedPartnerKey
        val transform: (Registration) -> Registration = {
            when (it.key) {
                registrationKey -> it.copy(preference = preference, fixedPartnerKey = null)
                partnerKey -> it.copy(preference = PlayPreference.OPEN_TO_JOIN, fixedPartnerKey = null)
                else -> it
            }
        }
        return copy(playing = playing.map(transform), waiting = waiting.map(transform))
    }

    fun planFriendPair(firstKey: Int, secondKey: Int): FriendPairPlan? {
        if (firstKey == secondKey) return null
        val originalPositions = waitingPositions()
        val originalPositionByKey = buildMap {
            originalPositions.forEachIndexed { positionIndex, registrations ->
                registrations.forEach { put(it.key, positionIndex) }
            }
        }
        val firstOriginal = waiting.firstOrNull { it.key == firstKey } ?: return null
        val secondOriginal = waiting.firstOrNull { it.key == secondKey } ?: return null
        if (firstOriginal.requiresOnSiteCheckIn || secondOriginal.requiresOnSiteCheckIn) return null
        val firstPosition = originalPositionByKey[firstKey] ?: return null
        val secondPosition = originalPositionByKey[secondKey] ?: return null
        val pairKeys = setOf(firstKey, secondKey)

        val clearedWaiting = waiting.map { registration ->
            if (
                registration.key in pairKeys ||
                registration.fixedPartnerKey?.let { it in pairKeys } == true
            ) {
                registration.copy(
                    preference = if (registration.key in pairKeys) {
                        PlayPreference.OPEN_TO_JOIN
                    } else {
                        registration.preference
                    },
                    fixedPartnerKey = null
                )
            } else {
                registration
            }
        }
        val pairAbsenceStatus = when {
            listOf(firstOriginal, secondOriginal).any {
                it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
            } -> QueueAbsenceStatus.TEMPORARILY_AWAY
            listOf(firstOriginal, secondOriginal).any {
                it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
            } -> QueueAbsenceStatus.DEFER_ONE_ROUND
            else -> QueueAbsenceStatus.NONE
        }
        val pairSkippedTurns = if (pairAbsenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY) {
            maxOf(firstOriginal.temporaryAwaySkippedTurns, secondOriginal.temporaryAwaySkippedTurns)
        } else {
            0
        }
        val first = clearedWaiting.first { it.key == firstKey }.copy(
            absenceStatus = pairAbsenceStatus,
            temporaryAwaySkippedTurns = pairSkippedTurns,
            fixedPartnerKey = secondKey
        )
        val second = clearedWaiting.first { it.key == secondKey }.copy(
            absenceStatus = pairAbsenceStatus,
            temporaryAwaySkippedTurns = pairSkippedTurns,
            fixedPartnerKey = firstKey
        )
        val orderedPair = if (firstPosition <= secondPosition) listOf(first, second) else listOf(second, first)
        if (firstPosition == secondPosition) {
            val fixedWaiting = clearedWaiting.map {
                when (it.key) {
                    firstKey -> first
                    secondKey -> second
                    else -> it
                }
            }
            return FriendPairPlan(
                originalWaiting = waiting,
                waiting = fixedWaiting,
                firstRegistration = firstOriginal,
                secondRegistration = secondOriginal,
                pairPositionIndex = firstPosition,
                movedBackRegistrations = emptyList(),
                delayedOtherRegistrations = emptyList()
            )
        }

        data class PairingJob(
            val registrations: List<Registration>,
            val deadline: Int,
            val order: Int,
            val canShare: Boolean
        )

        val jobs = mutableListOf<PairingJob>()
        var order = 0
        originalPositions.forEachIndexed { positionIndex, originalGroup ->
            val remaining = originalGroup.mapNotNull { original ->
                clearedWaiting.firstOrNull { it.key == original.key && it.key !in pairKeys }
            }
            val isFixedGroup = remaining.size == 2 &&
                remaining[0].fixedPartnerKey == remaining[1].key &&
                remaining[1].fixedPartnerKey == remaining[0].key
            if (isFixedGroup) {
                jobs += PairingJob(remaining, positionIndex, order++, canShare = false)
            } else {
                remaining.forEach { registration ->
                    jobs += PairingJob(
                        listOf(registration),
                        positionIndex,
                        order++,
                        canShare = registration.canEnterPlayingPosition &&
                            registration.preference == PlayPreference.OPEN_TO_JOIN &&
                            registration.fixedPartnerKey == null
                    )
                }
            }
        }

        val pending = jobs.sortedWith(compareBy<PairingJob> { it.deadline }.thenBy { it.order })
            .toMutableList()
        val scheduled = mutableListOf<Pair<PairingJob, PairingJob?>>()
        while (pending.isNotEmpty()) {
            val firstJob = pending.removeAt(0)
            val partnerIndex = if (firstJob.canShare) pending.indexOfFirst { it.canShare } else -1
            val partnerJob = if (partnerIndex >= 0) pending.removeAt(partnerIndex) else null
            scheduled += firstJob to partnerJob
        }

        val preferredPairIndex = maxOf(firstPosition, secondPosition).coerceAtMost(scheduled.size)
        fun delayedAt(insertionIndex: Int): List<Registration> = buildList {
            scheduled.forEachIndexed { scheduledIndex, (job, partnerJob) ->
                val finalIndex = scheduledIndex + if (scheduledIndex >= insertionIndex) 1 else 0
                (job.registrations + partnerJob?.registrations.orEmpty()).forEach { registration ->
                    val originalIndex = originalPositionByKey[registration.key] ?: finalIndex
                    if (finalIndex > originalIndex) add(registration)
                }
            }
        }

        val pairPositionIndex = preferredPairIndex
        val delayedOthers = delayedAt(pairPositionIndex)

        val scheduledGroups = scheduled.map { (job, partnerJob) ->
            job.registrations + partnerJob?.registrations.orEmpty()
        }.toMutableList()
        scheduledGroups.add(pairPositionIndex, orderedPair)
        val newWaiting = scheduledGroups.flatten()
        val movedBack = orderedPair.filter { registration ->
            pairPositionIndex > (originalPositionByKey[registration.key] ?: pairPositionIndex)
        }
        return FriendPairPlan(
            originalWaiting = waiting,
            waiting = newWaiting,
            firstRegistration = firstOriginal,
            secondRegistration = secondOriginal,
            pairPositionIndex = pairPositionIndex,
            movedBackRegistrations = movedBack,
            delayedOtherRegistrations = delayedOthers.distinctBy { it.key }
        )
    }

    fun applyFriendPair(plan: FriendPairPlan): MachineQueue {
        if (
            plan.delayedOtherRegistrations.isNotEmpty() ||
            !registrationsHaveSameQueueState(waiting, plan.originalWaiting)
        ) return this

        val currentByKey = waiting.associateBy(Registration::key)
        val rebasedWaiting = plan.waiting.map { planned ->
            val current = currentByKey[planned.key] ?: return this
            current.copy(
                preference = planned.preference,
                absenceStatus = planned.absenceStatus,
                temporaryAwaySkippedTurns = planned.temporaryAwaySkippedTurns,
                fixedPartnerKey = planned.fixedPartnerKey
            )
        }
        return copy(waiting = sanitizeFriendPairs(rebasedWaiting))
    }

    fun createFriendPair(registrationKey: Int, friend: Registration): MachineQueue {
        val registration = waiting.firstOrNull { it.key == registrationKey } ?: return this
        if (registration.requiresOnSiteCheckIn) return this
        val normalizedFriendId = friend.displayId.trim()
        if (normalizedFriendId.isBlank() || containsId(normalizedFriendId)) return this
        val previousPartnerKey = registration.fixedPartnerKey
        val remaining = waiting.filterNot { it.key == registrationKey }.map {
            if (it.key == previousPartnerKey) {
                it.copy(preference = PlayPreference.OPEN_TO_JOIN, fixedPartnerKey = null)
            } else {
                it
            }
        }
        val first = registration.copy(
            preference = PlayPreference.OPEN_TO_JOIN,
            absenceStatus = registration.absenceStatus,
            temporaryAwaySkippedTurns = registration.temporaryAwaySkippedTurns,
            fixedPartnerKey = friend.key
        )
        val second = friend.copy(
            displayId = normalizedFriendId,
            preference = PlayPreference.OPEN_TO_JOIN,
            absenceStatus = registration.absenceStatus,
            temporaryAwaySkippedTurns = registration.temporaryAwaySkippedTurns,
            fixedPartnerKey = registration.key
        )
        return copy(waiting = remaining + first + second)
    }

    fun rename(registrationKey: Int, newDisplayId: String): MachineQueue {
        val normalizedId = newDisplayId.trim()
        if (
            normalizedId.isBlank() ||
            containsId(normalizedId, exceptKey = registrationKey) ||
            allRegistrations.any { it.key == registrationKey && it.requiresOnSiteCheckIn }
        ) return this
        return copy(
            playing = playing.map {
                if (it.key == registrationKey) it.copy(displayId = normalizedId) else it
            },
            waiting = waiting.map {
                if (it.key == registrationKey) it.copy(displayId = normalizedId) else it
            }
        )
    }

    /** Keeps active registrations linked to a player profile in step with its visible details. */
    fun syncPlayerProfileDetails(
        playerProfileId: String,
        playerNickname: String,
        gender: PlayerGender
    ): MachineQueue {
        val normalizedNickname = playerNickname.trim()
        if (playerProfileId.isBlank() || normalizedNickname.isBlank()) return this
        val linkedKeys = allRegistrations
            .filter { it.playerProfileId == playerProfileId }
            .map { it.key }
            .toSet()
        if (linkedKeys.isEmpty()) return this
        if (containsId(normalizedNickname) && allRegistrations.any {
                it.key !in linkedKeys && it.displayId.trim().equals(normalizedNickname, ignoreCase = true)
            }) return this
        val transform: (Registration) -> Registration = { registration ->
            if (registration.key in linkedKeys) {
                registration.copy(
                    displayId = normalizedNickname,
                    gender = gender,
                    isTemporary = false
                )
            } else {
                registration
            }
        }
        return copy(
            playing = playing.map(transform),
            waiting = waiting.map(transform)
        )
    }

    private fun acceptUniqueRegistrations(registrations: List<Registration>): List<Registration> {
        val usedIds = allRegistrations
            .map { it.displayId.trim().lowercase() }
            .toMutableSet()
        val usedKeys = allRegistrations.mapTo(mutableSetOf()) { it.key }
        return buildList {
            registrations.forEach { registration ->
                val normalizedId = registration.displayId.trim()
                val normalizedKey = normalizedId.lowercase()
                if (
                    registration.key > 0 &&
                    normalizedId.isNotBlank() &&
                    registration.key !in usedKeys &&
                    normalizedKey !in usedIds
                ) {
                    usedKeys += registration.key
                    usedIds += normalizedKey
                    add(registration.copy(displayId = normalizedId))
                }
            }
        }
    }

    fun claim(registrationKey: Int): MachineQueue =
        if (allRegistrations.any { it.key == registrationKey && it.requiresOnSiteCheckIn }) {
            this
        } else copy(
            playing = playing.map {
                if (it.key == registrationKey) it.copy(isTemporary = false) else it
            },
            waiting = waiting.map {
                if (it.key == registrationKey) it.copy(isTemporary = false) else it
            }
        )

    fun claimWithPlayerProfile(
        registrationKey: Int,
        playerProfileId: String,
        playerNickname: String,
        gender: PlayerGender,
        preferenceOverride: PlayPreference? = null
    ): MachineQueue {
        val registration = allRegistrations.firstOrNull { it.key == registrationKey }
            ?: return this
        val normalizedNickname = playerNickname.trim()
        if (
            !registration.isTemporary ||
            registration.requiresOnSiteCheckIn ||
            playerProfileId.isBlank() ||
            normalizedNickname.isBlank() ||
            containsId(normalizedNickname, exceptKey = registrationKey)
        ) return this
        val claimed = copy(
            playing = playing.map {
                if (it.key == registrationKey) {
                    it.copy(
                        displayId = normalizedNickname,
                        isTemporary = false,
                        gender = gender,
                        playerProfileId = playerProfileId
                    )
                } else {
                    it
                }
            },
            waiting = waiting.map {
                if (it.key == registrationKey) {
                    it.copy(
                        displayId = normalizedNickname,
                        isTemporary = false,
                        gender = gender,
                        playerProfileId = playerProfileId
                    )
                } else {
                    it
                }
            }
        )
        return preferenceOverride?.let {
            claimed.changePreference(registrationKey, it)
        } ?: claimed
    }

    fun markNoShowDeferOneRound(
        registrationKey: Int,
        startNextWhenPlayingBecomesEmpty: Boolean = true
    ): MachineQueue {
        val affectedKeys = fixedGroupKeys(registrationKey)
        if (affectedKeys.isEmpty() || affectedKeys.any { !canMarkNoShow(it) }) return this
        val updated = copy(
            playing = playing.map {
                if (it.key in affectedKeys) {
                    it.copy(
                        noShowCount = it.noShowCount + 1,
                        lastNoShowActionWasDefer = true
                    )
                } else it
            },
            waiting = waiting.map {
                if (it.key in affectedKeys) {
                    it.copy(
                        noShowCount = it.noShowCount + 1,
                        lastNoShowActionWasDefer = true
                    )
                } else it
            }
        )
        val deferred = updated.deferRegistrationsOneRound(affectedKeys)
        return if (startNextWhenPlayingBecomesEmpty && deferred.playing.isEmpty()) {
            deferred.advanceIfNeeded()
        } else {
            deferred
        }
    }

    fun markNoShowMoveToEnd(
        registrationKeys: Set<Int>,
        startNextWhenPlayingBecomesEmpty: Boolean = true
    ): MachineQueue {
        if (registrationKeys.isEmpty() || registrationKeys.any { !canMarkNoShow(it) }) {
            return this
        }
        val moved = allRegistrations.filter { it.key in registrationKeys }.map {
            it.copy(
                absenceStatus = if (
                    it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
                ) {
                    QueueAbsenceStatus.TEMPORARILY_AWAY
                } else {
                    QueueAbsenceStatus.NONE
                },
                temporaryAwaySkippedTurns = if (
                    it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
                ) {
                    it.temporaryAwaySkippedTurns
                } else {
                    0
                },
                noShowCount = it.noShowCount + 1,
                lastNoShowActionWasDefer = false
            )
        }
        val remainingPlaying = playing.filterNot { it.key in registrationKeys }
        val sanitizedPlaying = sanitizeFriendPairs(remainingPlaying)
        val updated = copy(
            playing = sanitizedPlaying,
            waiting = sanitizeFriendPairs(waiting.filterNot { it.key in registrationKeys } + moved),
            playingStartedAtMillis = if (sanitizedPlaying.isEmpty()) null else playingStartedAtMillis
        )
        return if (startNextWhenPlayingBecomesEmpty && updated.playing.isEmpty()) {
            updated.advanceIfNeeded()
        } else {
            updated
        }
    }

    fun markNoShowGroupDeferOneRound(
        registrationKeys: Set<Int>,
        startNextWhenPlayingBecomesEmpty: Boolean = true
    ): MachineQueue {
        if (registrationKeys.isEmpty() || registrationKeys.any { !canMarkNoShow(it) }) {
            return this
        }
        val affectedKeys = registrationKeys
            .filterTo(mutableSetOf()) { key -> allRegistrations.any { it.key == key } }
        if (affectedKeys.isEmpty()) return this
        val updated = transformRegistrations(affectedKeys) {
            it.copy(
                noShowCount = it.noShowCount + 1,
                lastNoShowActionWasDefer = true
            )
        }
        val deferred = updated.deferRegistrationsOneRound(affectedKeys)
        return if (startNextWhenPlayingBecomesEmpty && deferred.playing.isEmpty()) {
            deferred.advanceIfNeeded()
        } else {
            deferred
        }
    }

    fun markNoShowAndRemove(
        registrationKeys: Set<Int>,
        startNextWhenPlayingBecomesEmpty: Boolean = true
    ): MachineQueue {
        if (registrationKeys.isEmpty() || registrationKeys.any { !canMarkNoShow(it) }) {
            return this
        }
        val updated = removeAll(registrationKeys)
        return if (startNextWhenPlayingBecomesEmpty && updated.playing.isEmpty()) {
            updated.advanceIfNeeded()
        } else {
            updated
        }
    }

    fun remove(registrationKey: Int): MachineQueue =
        removeAll(setOf(registrationKey))

    fun expiredOnlineRegistrationKeys(atMillis: Long): Set<Int> = waiting
        .asSequence()
        .filter { it.hasExpiredOnSiteCheckIn(atMillis) }
        .mapTo(mutableSetOf()) { it.key }

    /** Removes timed-out online registrations without starting an intentionally empty round. */
    fun removeExpiredOnlineRegistrations(atMillis: Long): MachineQueue {
        val expiredKeys = expiredOnlineRegistrationKeys(atMillis)
        return if (expiredKeys.isEmpty()) this else removeAll(expiredKeys)
    }

    fun checkIn(registrationKey: Int): MachineQueue = transformRegistrations(setOf(registrationKey)) {
        if (it.requiresOnSiteCheckIn) it.copy(requiresOnSiteCheckIn = false) else it
    }

    fun removeAll(registrationKeys: Set<Int>): MachineQueue {
        val remainingPlaying = playing.filterNot { it.key in registrationKeys }
        return copy(
            playing = sanitizeFriendPairs(remainingPlaying.map {
                if (it.fixedPartnerKey?.let { partnerKey -> partnerKey in registrationKeys } == true) {
                    it.copy(preference = PlayPreference.OPEN_TO_JOIN, fixedPartnerKey = null)
                } else it
            }),
            waiting = sanitizeFriendPairs(waiting.filterNot { it.key in registrationKeys }.map {
                if (it.fixedPartnerKey?.let { partnerKey -> partnerKey in registrationKeys } == true) {
                    it.copy(preference = PlayPreference.OPEN_TO_JOIN, fixedPartnerKey = null)
                } else it
            }),
            playingStartedAtMillis = if (remainingPlaying.isEmpty()) null else playingStartedAtMillis
        )
    }

    fun moveWaitingPosition(sourceIndex: Int, destinationIndex: Int): MachineQueue {
        val positions = waitingPositions().toMutableList()
        if (
            sourceIndex !in positions.indices ||
            destinationIndex !in positions.indices ||
            sourceIndex == destinationIndex
        ) return this

        val movedPosition = positions.removeAt(sourceIndex)
        positions.add(destinationIndex, movedPosition)
        return copy(waiting = positions.flatten())
    }

    fun enterPlayingPosition(): MachineQueue =
        RoundPlanner.enterPlayingPosition(this).execute()

    fun replaceOrder(registrations: List<Registration>): MachineQueue {
        val currentKeys = allRegistrations.map { it.key }
        val proposedKeys = registrations.map { it.key }
        if (proposedKeys.size != currentKeys.size || proposedKeys.toSet() != currentKeys.toSet()) {
            return this
        }
        if (proposedKeys == currentKeys) return this

        // A reorder proposal carries identity and order only. Registration details may have
        // changed while its confirmation was open, for example through a QQ profile update.
        val currentRegistrationsByKey = allRegistrations.associateBy { it.key }
        val reorderedCurrentRegistrations = proposedKeys.map(currentRegistrationsByKey::getValue)

        // Reordering waiting registrations must not move the physically active
        // players or restart their timer. An intentionally empty playing
        // position must remain empty as well.
        if (playing.isEmpty()) {
            return copy(waiting = sanitizeFriendPairs(reorderedCurrentRegistrations))
        }
        val currentPlayingOrder = playing.map { it.key }
        val proposedPlayingOrder = proposedKeys.take(playing.size)
        if (proposedPlayingOrder != currentPlayingOrder) return this
        val currentPlayingKeys = currentPlayingOrder.toSet()
        return copy(
            waiting = sanitizeFriendPairs(
                reorderedCurrentRegistrations.filter { it.key !in currentPlayingKeys }
            )
        )
    }

    private fun advanceIfNeeded(
        atMillis: Long = System.currentTimeMillis(),
        skippedThisOpportunity: Set<Int> = emptySet()
    ): MachineQueue = RoundPlanner.advance(this, skippedThisOpportunity).execute(atMillis)

    private fun fixedGroupKeys(registrationKey: Int): Set<Int> {
        val registration = allRegistrations.firstOrNull { it.key == registrationKey }
            ?: return emptySet()
        val partnerKey = registration.fixedPartnerKey
        val partner = partnerKey?.let { key -> allRegistrations.firstOrNull { it.key == key } }
        return if (partner != null && partner.fixedPartnerKey == registrationKey) {
            setOf(registrationKey, partner.key)
        } else {
            setOf(registrationKey)
        }
    }

    private fun transformRegistrations(
        registrationKeys: Set<Int>,
        transform: (Registration) -> Registration
    ): MachineQueue = copy(
        playing = playing.map { if (it.key in registrationKeys) transform(it) else it },
        waiting = waiting.map { if (it.key in registrationKeys) transform(it) else it }
    )

    private fun deferRegistrationsOneRound(registrationKeys: Set<Int>): MachineQueue {
        if (registrationKeys.isEmpty()) return this
        val movedFromPlaying = playing.filter { it.key in registrationKeys }
        if (movedFromPlaying.isNotEmpty()) {
            val remainingPlayers = sanitizeFriendPairs(
                playing.filterNot { it.key in registrationKeys }
            )
            return copy(
                playing = remainingPlayers,
                waiting = sanitizeFriendPairs(
                    movedFromPlaying.map {
                        it.copy(
                            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
                            temporaryAwaySkippedTurns = 0
                        )
                    } + waiting
                ),
                playingStartedAtMillis = if (remainingPlayers.isEmpty()) {
                    null
                } else {
                    playingStartedAtMillis
                }
            )
        }
        return transformRegistrations(registrationKeys) {
            it.copy(
                absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
                temporaryAwaySkippedTurns = 0
            )
        }
    }
}

fun MachineQueue.hasSameQueueOperationState(other: MachineQueue): Boolean =
    playingStartedAtMillis == other.playingStartedAtMillis &&
        registrationsHaveSameQueueState(playing, other.playing) &&
        registrationsHaveSameQueueState(waiting, other.waiting)

fun registrationsHaveSameQueueState(
    first: List<Registration>,
    second: List<Registration>
): Boolean = first.size == second.size && first.zip(second).all { (left, right) ->
    left.key == right.key &&
        left.preference == right.preference &&
        left.absenceStatus == right.absenceStatus &&
        left.temporaryAwaySkippedTurns == right.temporaryAwaySkippedTurns &&
        left.createdAtMillis == right.createdAtMillis &&
        left.lastPlayedAtMillis == right.lastPlayedAtMillis &&
        left.noShowCount == right.noShowCount &&
        left.lastNoShowActionWasDefer == right.lastNoShowActionWasDefer &&
        left.fixedPartnerKey == right.fixedPartnerKey &&
        left.requiresOnSiteCheckIn == right.requiresOnSiteCheckIn
}

fun groupIntoPositions(registrations: List<Registration>): List<List<Registration>> {
    val positions = mutableListOf<MutableList<Registration>>()
    var pendingOpenPositionIndex: Int? = null
    var index = 0

    fun flushPendingOpen() {
        pendingOpenPositionIndex = null
    }

    while (index < registrations.size) {
        val first = registrations[index]
        val partnerIndex = first.fixedPartnerKey?.let { partnerKey ->
            (index + 1).takeIf { candidateIndex ->
                registrations.getOrNull(candidateIndex)?.key == partnerKey &&
                    registrations[candidateIndex].fixedPartnerKey == first.key
            }
        }
        val isFixedPair = partnerIndex != null
        val group = if (isFixedPair) {
            listOf(first, registrations[partnerIndex!!])
        } else {
            listOf(first)
        }
        val isUnavailable = group.any { !it.canEnterPlayingPosition }

        if (isUnavailable) {
            // Temporarily unavailable registrations remain visible, but never
            // consume the open-player slot used to form a shared position.
            val canShareUnavailable = !isFixedPair &&
                first.preference == PlayPreference.OPEN_TO_JOIN &&
                positions.lastOrNull()?.let { previous ->
                    previous.size == 1 &&
                        previous.first().fixedPartnerKey == null &&
                        previous.first().preference == PlayPreference.OPEN_TO_JOIN &&
                        previous.first().absenceStatus == first.absenceStatus &&
                        previous.first().requiresOnSiteCheckIn == first.requiresOnSiteCheckIn &&
                        (first.absenceStatus != QueueAbsenceStatus.TEMPORARILY_AWAY ||
                            previous.first().temporaryAwaySkippedTurns == first.temporaryAwaySkippedTurns)
                } == true
            if (canShareUnavailable) {
                positions.last() += first
            } else {
                positions += group.toMutableList()
            }
            index = if (isFixedPair) partnerIndex!! + 1 else index + 1
            continue
        }

        if (isFixedPair) {
            flushPendingOpen()
            positions += group.toMutableList()
            index = partnerIndex!! + 1
            continue
        }

        when (first.preference) {
            PlayPreference.SOLO -> {
                flushPendingOpen()
                positions += mutableListOf(first)
            }
            PlayPreference.OPEN_TO_JOIN -> {
                val pendingIndex = pendingOpenPositionIndex
                if (pendingIndex == null) {
                    positions += mutableListOf(first)
                    pendingOpenPositionIndex = positions.lastIndex
                } else {
                    positions[pendingIndex] += first
                    flushPendingOpen()
                }
            }
        }
        index++
    }
    return positions.map { it.toList() }
}

fun sanitizeFriendPairs(registrations: List<Registration>): List<Registration> {
    val indexByKey = registrations.mapIndexed { index, registration -> registration.key to index }.toMap()
    val registrationByKey = registrations.associateBy { it.key }
    return registrations.mapIndexed { index, registration ->
        val partnerKey = registration.fixedPartnerKey
        val partner = partnerKey?.let { registrationByKey[it] }
        val partnerIndex = partnerKey?.let { indexByKey[it] }
        val validPair = partner != null &&
            partner.fixedPartnerKey == registration.key &&
            !registration.requiresOnSiteCheckIn &&
            !partner.requiresOnSiteCheckIn &&
            partner.requiresOnSiteCheckIn == registration.requiresOnSiteCheckIn &&
            partnerIndex != null &&
            kotlin.math.abs(partnerIndex - index) == 1
        if (partnerKey != null && !validPair) {
            registration.copy(
                preference = PlayPreference.OPEN_TO_JOIN,
                fixedPartnerKey = null
            )
        } else {
            registration
        }
    }
}
