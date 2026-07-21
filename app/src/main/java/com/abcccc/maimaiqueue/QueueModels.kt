package com.abcccc.maimaiqueue

enum class PlayPreference {
    SOLO,
    OPEN_TO_JOIN
}

enum class MachineStopReason {
    NOT_POWERED_ON,
    NETWORK_DISCONNECTED,
    OTHER
}

data class MachineStatus(
    val stopReason: MachineStopReason? = null,
    val stoppedAtMillis: Long? = null
) {
    val isOperational: Boolean
        get() = stopReason == null

    fun stop(
        reason: MachineStopReason,
        atMillis: Long = System.currentTimeMillis()
    ): MachineStatus = if (isOperational) {
        copy(stopReason = reason, stoppedAtMillis = atMillis)
    } else {
        this
    }

    fun restore(): MachineStatus = MachineStatus()
}

data class Registration(
    val key: Int,
    val displayId: String,
    val preference: PlayPreference,
    val deferredOnce: Boolean = false,
    val isTemporary: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastPlayedAtMillis: Long? = null,
    val noShowCount: Int = 0,
    val lastNoShowActionWasDefer: Boolean = false,
    val fixedPartnerKey: Int? = null,
    val gender: PlayerGender? = null,
    val playerProfileId: String? = null
)

data class FriendPairPlan(
    val waiting: List<Registration>,
    val firstRegistration: Registration,
    val secondRegistration: Registration,
    val pairPositionIndex: Int,
    val movedBackRegistrations: List<Registration>,
    val delayedOtherRegistrations: List<Registration>
)

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

    fun canMarkNoShow(registrationKey: Int): Boolean =
        playing.any { it.key == registrationKey } ||
            waitingPositions().firstOrNull()?.any { it.key == registrationKey } == true

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
                waiting + accepted.map { it.copy(deferredOnce = false) }
            )
        )
    }

    /**
     * A completed player remains registered and returns to the end of this machine's queue.
     * Players who shared a round keep their relative order.
     */
    fun finishRound(atMillis: Long = System.currentTimeMillis()): MachineQueue {
        return endRoundWithoutStartingNext(atMillis).advanceIfNeeded(atMillis)
    }

    fun endRoundWithoutStartingNext(atMillis: Long = System.currentTimeMillis()): MachineQueue {
        if (playing.isEmpty()) return this
        val returned = playing.map {
            it.copy(deferredOnce = false, lastPlayedAtMillis = atMillis)
        }
        return copy(
            playing = emptyList(),
            waiting = waiting + returned,
            playingStartedAtMillis = null
        )
    }

    fun restartPlayingTimer(atMillis: Long = System.currentTimeMillis()): MachineQueue =
        if (playing.isEmpty()) this else copy(playingStartedAtMillis = atMillis)

    /** Removes every registration in the current round, then advances the waiting order. */
    fun removeCurrentRoundAndAdvance(atMillis: Long = System.currentTimeMillis()): MachineQueue {
        if (playing.isEmpty()) return this
        return copy(
            playing = emptyList(),
            waiting = sanitizeFriendPairs(waiting),
            playingStartedAtMillis = null
        ).advanceIfNeeded(atMillis)
    }

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
        val firstWaitingPosition = waitingPositions().firstOrNull() ?: return this
        val joiningPlayer = firstWaitingPosition.firstOrNull { it.key == registrationKey } ?: return this
        val remainingWaiting = sanitizeFriendPairs(waiting.filterNot { it.key == registrationKey })
        return copy(
            playing = listOf(
                currentPlayer.copy(
                    preference = PlayPreference.OPEN_TO_JOIN,
                    fixedPartnerKey = null
                ),
                joiningPlayer.copy(
                    preference = PlayPreference.OPEN_TO_JOIN,
                    deferredOnce = false,
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

        val targetPosition = positions[targetIndex].map {
            it.copy(deferredOnce = false)
        }
        val completedRegistrations = (listOf(playing) + positions.take(targetIndex))
            .flatten()
            .map {
                it.copy(
                    deferredOnce = false,
                    lastPlayedAtMillis = atMillis
                )
            }
        val positionsStillWaiting = positions.drop(targetIndex + 1).flatten()

        return copy(
            playing = targetPosition,
            waiting = sanitizeFriendPairs(positionsStillWaiting + completedRegistrations),
            playingStartedAtMillis = atMillis
        )
    }

    /** A deferral skips one opportunity; it does not remove the registration. */
    fun defer(registrationKey: Int): MachineQueue {
        val playingRegistration = playing.firstOrNull { it.key == registrationKey }
        if (playingRegistration != null) {
            val remainingPlayers = sanitizeFriendPairs(playing.filterNot { it.key == registrationKey })
            val deferredRegistration = playingRegistration.copy(deferredOnce = true)
            return copy(
                playing = remainingPlayers,
                waiting = sanitizeFriendPairs(waiting + deferredRegistration),
                playingStartedAtMillis = if (remainingPlayers.isEmpty()) null else playingStartedAtMillis
            ).advanceIfNeeded()
        }

        return copy(
            waiting = waiting.map {
                if (it.key == registrationKey) it.copy(deferredOnce = true) else it
            }
        )
    }

    fun cancelDefer(registrationKey: Int): MachineQueue =
        copy(
            playing = playing.map {
                if (it.key == registrationKey) it.copy(deferredOnce = false) else it
            },
            waiting = waiting.map {
                if (it.key == registrationKey) it.copy(deferredOnce = false) else it
            }
        )

    fun changePreference(registrationKey: Int, preference: PlayPreference): MachineQueue {
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
        val first = clearedWaiting.first { it.key == firstKey }.copy(fixedPartnerKey = secondKey)
        val second = clearedWaiting.first { it.key == secondKey }.copy(fixedPartnerKey = firstKey)
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
                        canShare = !registration.deferredOnce &&
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
            waiting = newWaiting,
            firstRegistration = firstOriginal,
            secondRegistration = secondOriginal,
            pairPositionIndex = pairPositionIndex,
            movedBackRegistrations = movedBack,
            delayedOtherRegistrations = delayedOthers.distinctBy { it.key }
        )
    }

    fun applyFriendPair(plan: FriendPairPlan): MachineQueue =
        if (plan.delayedOtherRegistrations.isNotEmpty()) this
        else copy(waiting = sanitizeFriendPairs(plan.waiting))

    fun createFriendPair(registrationKey: Int, friend: Registration): MachineQueue {
        val registration = waiting.firstOrNull { it.key == registrationKey } ?: return this
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
            deferredOnce = false,
            fixedPartnerKey = friend.key
        )
        val second = friend.copy(
            displayId = normalizedFriendId,
            preference = PlayPreference.OPEN_TO_JOIN,
            deferredOnce = false,
            fixedPartnerKey = registration.key
        )
        return copy(waiting = remaining + first + second)
    }

    fun rename(registrationKey: Int, newDisplayId: String): MachineQueue {
        val normalizedId = newDisplayId.trim()
        if (normalizedId.isBlank() || containsId(normalizedId, exceptKey = registrationKey)) return this
        return copy(
            playing = playing.map {
                if (it.key == registrationKey) it.copy(displayId = normalizedId) else it
            },
            waiting = waiting.map {
                if (it.key == registrationKey) it.copy(displayId = normalizedId) else it
            }
        )
    }

    private fun acceptUniqueRegistrations(registrations: List<Registration>): List<Registration> {
        val usedIds = allRegistrations
            .map { it.displayId.trim().lowercase() }
            .toMutableSet()
        return buildList {
            registrations.forEach { registration ->
                val normalizedId = registration.displayId.trim()
                if (normalizedId.isNotBlank() && usedIds.add(normalizedId.lowercase())) {
                    add(registration.copy(displayId = normalizedId))
                }
            }
        }
    }

    fun claim(registrationKey: Int): MachineQueue =
        copy(
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

    fun markNoShowDeferred(registrationKey: Int): MachineQueue {
        val updated = copy(
            playing = playing.map {
                if (it.key == registrationKey) {
                    it.copy(
                        noShowCount = it.noShowCount + 1,
                        lastNoShowActionWasDefer = true
                    )
                } else it
            },
            waiting = waiting.map {
                if (it.key == registrationKey) {
                    it.copy(
                        noShowCount = it.noShowCount + 1,
                        lastNoShowActionWasDefer = true
                    )
                } else it
            }
        )
        return updated.defer(registrationKey)
    }

    fun markNoShowMoveToEnd(registrationKeys: Set<Int>): MachineQueue {
        val moved = allRegistrations.filter { it.key in registrationKeys }.map {
            it.copy(
                deferredOnce = false,
                noShowCount = it.noShowCount + 1,
                lastNoShowActionWasDefer = false
            )
        }
        val remainingPlaying = playing.filterNot { it.key in registrationKeys }
        val sanitizedPlaying = sanitizeFriendPairs(remainingPlaying)
        return copy(
            playing = sanitizedPlaying,
            waiting = sanitizeFriendPairs(waiting.filterNot { it.key in registrationKeys } + moved),
            playingStartedAtMillis = if (sanitizedPlaying.isEmpty()) null else playingStartedAtMillis
        )
    }

    fun markNoShowGroupDeferred(registrationKeys: Set<Int>): MachineQueue {
        val inPlaying = playing.any { it.key in registrationKeys }
        val transform: (Registration) -> Registration = {
            if (it.key in registrationKeys) {
                it.copy(
                    deferredOnce = true,
                    noShowCount = it.noShowCount + 1,
                    lastNoShowActionWasDefer = true
                )
            } else it
        }
        val updatedPlaying = playing.map(transform)
        val updatedWaiting = waiting.map(transform)
        if (!inPlaying) return copy(waiting = updatedWaiting)

        val moved = updatedPlaying.filter { it.key in registrationKeys }
        val remainingPlaying = sanitizeFriendPairs(updatedPlaying.filterNot { it.key in registrationKeys })
        return copy(
            playing = remainingPlaying,
            waiting = sanitizeFriendPairs(updatedWaiting + moved),
            playingStartedAtMillis = if (remainingPlaying.isEmpty()) null else playingStartedAtMillis
        )
    }

    fun remove(registrationKey: Int): MachineQueue =
        removeAll(setOf(registrationKey))

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

    fun swapWaitingPosition(
        positionIndex: Int,
        direction: Int,
        makeSoloRegistrationKeys: Set<Int> = emptySet()
    ): MachineQueue {
        val positions = waitingPositions().toMutableList()
        val destination = positionIndex + direction
        if (positionIndex !in positions.indices || destination !in positions.indices) return this
        val moved = positions[positionIndex]
        positions[positionIndex] = positions[destination]
        positions[destination] = moved
        return copy(
            waiting = positions.flatten().map {
                if (it.key in makeSoloRegistrationKeys) {
                    it.copy(preference = PlayPreference.SOLO)
                } else it
            }
        )
    }

    fun enterPlayingPosition(): MachineQueue = advanceIfNeeded()

    fun replaceOrder(registrations: List<Registration>): MachineQueue {
        if (registrations.map { it.key } == allRegistrations.map { it.key }) return this
        return MachineQueue(waiting = sanitizeFriendPairs(registrations)).advanceIfNeeded()
    }

    private fun advanceIfNeeded(atMillis: Long = System.currentTimeMillis()): MachineQueue {
        if (playing.isNotEmpty() || waiting.isEmpty()) return this

        var pending = waiting
        var positionsRemaining = groupIntoPositions(pending).size
        while (positionsRemaining > 0) {
            val firstPosition = groupIntoPositions(pending).firstOrNull() ?: break
            if (firstPosition.none { it.deferredOnce }) break
            val deferredKeys = firstPosition.map { it.key }.toSet()
            pending = pending.filterNot { it.key in deferredKeys } +
                firstPosition.map { it.copy(deferredOnce = false) }
            positionsRemaining--
        }
        val nextPlayers = groupIntoPositions(pending).firstOrNull() ?: return this
        val nextPlayerKeys = nextPlayers.map { it.key }.toSet()

        return copy(
            playing = nextPlayers,
            waiting = pending.filterNot { it.key in nextPlayerKeys },
            playingStartedAtMillis = atMillis
        )
    }
}

fun groupIntoPositions(registrations: List<Registration>): List<List<Registration>> {
    val positions = mutableListOf<List<Registration>>()
    var index = 0
    while (index < registrations.size) {
        val first = registrations[index]
        val second = registrations.getOrNull(index + 1)
        val isFixedPair =
            second != null &&
                first.fixedPartnerKey == second.key &&
                second.fixedPartnerKey == first.key
        val canShare = isFixedPair ||
            !first.deferredOnce &&
                second != null &&
                !second.deferredOnce &&
                first.preference == PlayPreference.OPEN_TO_JOIN &&
                second.preference == PlayPreference.OPEN_TO_JOIN &&
                first.fixedPartnerKey == null &&
                second.fixedPartnerKey == null

        if (canShare) {
            positions += listOf(first, second)
            index += 2
        } else {
            positions += listOf(first)
            index++
        }
    }
    return positions
}

private fun sanitizeFriendPairs(registrations: List<Registration>): List<Registration> {
    val indexByKey = registrations.mapIndexed { index, registration -> registration.key to index }.toMap()
    val registrationByKey = registrations.associateBy { it.key }
    return registrations.mapIndexed { index, registration ->
        val partnerKey = registration.fixedPartnerKey
        val partner = partnerKey?.let { registrationByKey[it] }
        val partnerIndex = partnerKey?.let { indexByKey[it] }
        val validPair = partner != null &&
            partner.fixedPartnerKey == registration.key &&
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
