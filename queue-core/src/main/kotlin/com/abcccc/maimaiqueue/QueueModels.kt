package com.abcccc.maimaiqueue

enum class PlayPreference {
    SOLO,
    OPEN_TO_JOIN
}

enum class QueueAbsenceStatus {
    NONE,
    /** 将暂缓一次: the next opportunity is skipped, the place kept. */
    DEFER_ONE_ROUND,
    TEMPORARILY_AWAY,
    /**
     * 将轮空一次 (maintainer, 2026-11-25): at the next opportunity the registration goes to the
     * tail, which counts one skipped turn like [TEMPORARILY_AWAY], and the state ends.
     */
    SKIP_ONE_TURN
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
    /**
     * How often the registration was passed over while temporarily away. It is kept when the
     * player comes back and counts on if they leave again; like [noShowCount] it is only
     * cleared once the registration has played a round.
     */
    val temporaryAwaySkippedTurns: Int = 0,
    /**
     * How many of [temporaryAwaySkippedTurns] are not this registration's own: a fixed pair
     * shares one count, the larger of the two, and this is what the registration took over
     * from its partner when the pair formed. When the pair is released the registration goes
     * back to its own count (review 2026-11-27: a player with three missed turns left them
     * with a partner who had none, and that partner was then removed at the first 轮空一次).
     */
    val skippedTurnsCarriedFromPartner: Int = 0,
    val isTemporary: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastPlayedAtMillis: Long? = null,
    val noShowCount: Int = 0,
    val lastNoShowActionWasDefer: Boolean = false,
    val fixedPartnerKey: Int? = null,
    val gender: PlayerGender? = null,
    val playerProfileId: String? = null,
    val requiresOnSiteCheckIn: Boolean = false,
    val onSiteCheckInStartedAtMillis: Long? = null,
    val originatingCommandId: String? = null,
    /** The player chose to queue at both machines of a configured pair with this registration. */
    val alsoQueuesAtPairedMachine: Boolean = false,
    /**
     * 暂缓一次 has been used (an opportunity was skipped for it) and the registration has not
     * played a round since; it cannot be deferred again until it has (maintainer, 2026-11-25).
     */
    val deferOneRoundUsed: Boolean = false,
    /**
     * The registration has no place at the paired machine for the round it is waiting for:
     * its place in the waiting order was changed by hand, it was taken back out of the playing
     * position, or the paired machine was turned on while it was already waiting. It queues at
     * its current machine only until it next enters a playing position;
     * [alsoQueuesAtPairedMachine] keeps the player's choice for the rounds after that.
     */
    val skipsPairedMachineThisRound: Boolean = false,
    /**
     * The registration was made online and had to be checked in on site. Checking in clears
     * [requiresOnSiteCheckIn]; this stays, so that the first turn of such a registration can
     * still be told apart, see [mustBeRemovedWhenAbsent].
     */
    val wasOnlineRegistration: Boolean = false,
    /**
     * A 单人游玩 registration, or the two registrations of a fixed pair, let the registrations
     * around them pass: an open registration ahead that has no partner may pair with the first
     * open registration behind (docs/releases/solo-pass-plan-2026-11.md and
     * fixed-pair-pass-plan-2026-11.md). The real waiting order does not change; only the
     * grouping into positions does. A fixed pair has one choice: both registrations carry the
     * same value. It means nothing for an open registration on its own, see [letsOthersPass].
     */
    val allowsPassing: Boolean = false,
    /**
     * While this registration is in the playing position: the registrations it passed over to
     * play in this round, in their real order (see [allowsPassing]). Set when the round
     * starts and cleared when the registration leaves the playing position.
     */
    val passedOverKeys: List<Int> = emptyList()
) {
    val canEnterPlayingPosition: Boolean
        get() = absenceStatus == QueueAbsenceStatus.NONE && !requiresOnSiteCheckIn

    /** Whether the registrations around this one (and its fixed partner) may pair across it. */
    val letsOthersPass: Boolean
        get() = allowsPassing && (fixedPartnerKey != null || preference == PlayPreference.SOLO)

    /**
     * Anybody on site can check an online registration in, so the check-in alone does not show
     * that the player has arrived. Until such a registration has played a round, a no-show can
     * therefore only be settled by removing it: neither deferring nor moving it to the tail
     * keeps a place for somebody who was never there.
     */
    val mustBeRemovedWhenAbsent: Boolean
        get() = wasOnlineRegistration && !requiresOnSiteCheckIn && lastPlayedAtMillis == null

    val onSiteCheckInDeadlineMillis: Long?
        get() = if (requiresOnSiteCheckIn) {
            (onSiteCheckInStartedAtMillis ?: createdAtMillis) +
                ONLINE_REGISTRATION_CHECK_IN_TIMEOUT_MILLIS
        } else {
            null
        }

    val hasRestartedOnSiteCheckInWindow: Boolean
        get() = requiresOnSiteCheckIn &&
            onSiteCheckInStartedAtMillis?.let { it != createdAtMillis } == true

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
    val unavailableRegistrations: List<Registration>,
    val skippedThisOpportunityRegistrations: List<Registration> = emptyList()
) {
    val changedByAvailability: Boolean
        get() = (unavailableRegistrations.isNotEmpty() ||
            skippedThisOpportunityRegistrations.isNotEmpty()) &&
            nominalRegistrations.map { it.key }.toSet() != nextRegistrations.map { it.key }.toSet()
}

/** A read-only waiting position used by queue surfaces. */
data class WaitingPositionProjection(
    val registrations: List<Registration>,
    val commonPlayPreview: Registration? = null
)

data class WaitingQueueProjection(
    val positions: List<WaitingPositionProjection>
) {
    fun positionIndexOf(registrationKey: Int): Int? = positions.indexOfFirst { position ->
        position.registrations.any { it.key == registrationKey }
    }.takeIf { it >= 0 }
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

    /**
     * Returns the queue as players should currently see it. The result is derived only:
     * saving, undo, reordering, and round execution continue to use [waiting].
     */
    fun waitingProjection(
        includeCommonPlayPreview: Boolean = true
    ): WaitingQueueProjection = RoundPlanner.waitingProjection(
        queue = this,
        includeCommonPlayPreview = includeCommonPlayPreview
    )

    fun firstAvailableWaitingPositionIndex(): Int? = waitingPositions().indexOfFirst { position ->
        position.all { it.canEnterPlayingPosition }
    }.takeIf { it >= 0 }

    /**
     * Who enters the playing position next, as [RoundPlanner] decides it (empty when nobody
     * can). Use this, not an entry of [waitingPositions], wherever it matters who is next: a
     * pass the check before it refuses is still shown in [waitingPositions].
     */
    fun nextWaitingGroup(): List<Registration> = RoundPlanner.nextWaitingGroup(this)

    fun nextPlayingPositionPreview(): NextPlayingPositionPreview? {
        return RoundPlanner.enterPlayingPosition(this).preview
    }

    /** Previews the next group after the current players return to the waiting tail. */
    fun nextPlayingPositionPreviewAfterRoundEnd(): NextPlayingPositionPreview? =
        RoundPlanner.finishRound(this).preview

    /** Previews the next group after the current playing registrations are removed. */
    fun nextPlayingPositionPreviewAfterCurrentRoundRemoved(): NextPlayingPositionPreview? =
        RoundPlanner.removeCurrentRoundAndStartNext(this).preview

    /**
     * Whether [registrationKey] may be recorded as absent: it plays now, or it is in the next
     * group to enter. [nextGroupKeys] is that group as the engine sees it on a machine of a pair
     * (QueueEngine.nextWaitingGroup), where a registration may be arranged away or arrive; this
     * machine's waiting order alone would then name another group and refuse the one the screen
     * shows as next (review 2026-11-27, fixed 2026-11-28). Null: this machine's own next group.
     */
    fun canMarkNoShow(registrationKey: Int, nextGroupKeys: Set<Int>? = null): Boolean {
        val registration = allRegistrations.firstOrNull { it.key == registrationKey }
            ?: return false
        if (!registration.canEnterPlayingPosition) return false
        if (playing.any { it.key == registrationKey }) return true
        return nextGroupKeys?.contains(registrationKey)
            ?: nextWaitingGroup().any { it.key == registrationKey }
    }

    fun join(registration: Registration): MachineQueue =
        join(registration, System.currentTimeMillis())

    fun join(registration: Registration, atMillis: Long): MachineQueue {
        val accepted = acceptUniqueRegistrations(listOf(registration)).firstOrNull() ?: return this
        return copy(waiting = waiting + accepted).advanceIfNeeded(atMillis)
    }

    fun joinAll(registrations: List<Registration>): MachineQueue =
        joinAll(registrations, System.currentTimeMillis())

    fun joinAll(registrations: List<Registration>, atMillis: Long): MachineQueue {
        val accepted = acceptUniqueRegistrations(registrations)
        if (accepted.isEmpty()) return this
        return copy(waiting = waiting + accepted).advanceIfNeeded(atMillis)
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
                    // Arriving at the tail already does what a pending 轮空一次 would do.
                    if (
                        registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND ||
                        registration.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN
                    ) {
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
                skippedTurnsCarriedFromPartner = 0,
                noShowCount = 0,
                lastNoShowActionWasDefer = false,
                deferOneRoundUsed = false,
                lastPlayedAtMillis = atMillis,
                passedOverKeys = emptyList()
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

    /** Gives pending online registrations a fresh check-in window after a machine stop. */
    fun restartPendingCheckInTimers(atMillis: Long = System.currentTimeMillis()): MachineQueue =
        copy(
            waiting = waiting.map { registration ->
                if (registration.requiresOnSiteCheckIn) {
                    registration.copy(onSiteCheckInStartedAtMillis = atMillis)
                } else {
                    registration
                }
            }
        )

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
        val joiningPlayer = nextWaitingGroup().firstOrNull { it.key == registrationKey } ?: return this
        if (!joiningPlayer.canEnterPlayingPosition) return this
        val remainingWaiting = sanitizeFriendPairs(waiting.filterNot { it.key == registrationKey })
        return copy(
            playing = listOf(
                // Both now share the round as 允许他人加入; the choice to let others pass belongs
                // to 单人游玩 and ends with it, as when the preference is changed by hand.
                currentPlayer.copy(
                    preference = PlayPreference.OPEN_TO_JOIN,
                    fixedPartnerKey = null,
                    allowsPassing = false,
                    // It now plays with somebody it did not pass anyone for.
                    passedOverKeys = emptyList()
                ),
                joiningPlayer.copy(
                    preference = PlayPreference.OPEN_TO_JOIN,
                    absenceStatus = QueueAbsenceStatus.NONE,
                    fixedPartnerKey = null,
                    allowsPassing = false
                )
            ),
            waiting = remainingWaiting
        )
    }

    /** Replays normal round-end operations until the selected position is playing. */
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

        var corrected = this
        repeat(allRegistrations.size + 1) {
            val previous = corrected
            corrected = RoundPlanner.finishRound(corrected).execute(atMillis)
            val playingKeys = corrected.playing.mapTo(mutableSetOf()) { it.key }
            if (playingKeys == registrationKeys) return corrected
            if (
                corrected == previous ||
                registrationKeys.any { targetKey ->
                    corrected.allRegistrations.none { it.key == targetKey }
                }
            ) return this
        }
        return this
    }

    /**
     * Skips exactly one opportunity while preserving the registration's physical order. Only a
     * waiting registration can defer; one in the playing position has to be taken back to the
     * front of the waiting order first.
     */
    fun deferOneRound(registrationKey: Int): MachineQueue {
        val affectedKeys = fixedGroupKeys(registrationKey)
        if (
            affectedKeys.isEmpty() ||
            playing.any { it.key in affectedKeys } ||
            allRegistrations.any { it.key in affectedKeys && it.requiresOnSiteCheckIn }
        ) return this

        return copy(
            waiting = waiting.map { registration ->
                if (registration.key in affectedKeys) {
                    registration.copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND)
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

    /**
     * Keeps skipping turns until manually cancelled, rotating to the tail after each skipped
     * turn. Like a deferral it is only for a waiting registration.
     */
    fun temporarilyLeave(registrationKey: Int): MachineQueue {
        val affectedKeys = fixedGroupKeys(registrationKey)
        if (
            affectedKeys.isEmpty() ||
            playing.any { it.key in affectedKeys } ||
            allRegistrations.any { it.key in affectedKeys && it.requiresOnSiteCheckIn }
        ) return this

        return copy(
            waiting = waiting.map { registration ->
                if (registration.key in affectedKeys) {
                    // Turns missed before the player came back the last time still count.
                    registration.copy(absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY)
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
                // The missed turns stay on record until the registration has played a round.
                registration.copy(absenceStatus = QueueAbsenceStatus.NONE)
            } else {
                registration
            }
        }
    }

    /**
     * 将轮空一次: at the next opportunity the registration goes to the tail, counting one
     * skipped turn. Like a deferral it is only for a waiting registration, and a fixed pair
     * goes together.
     */
    fun skipOneTurn(registrationKey: Int): MachineQueue {
        val affectedKeys = fixedGroupKeys(registrationKey)
        if (
            affectedKeys.isEmpty() ||
            playing.any { it.key in affectedKeys } ||
            allRegistrations.any { it.key in affectedKeys && it.requiresOnSiteCheckIn }
        ) return this

        return copy(
            waiting = waiting.map { registration ->
                if (registration.key in affectedKeys) {
                    registration.copy(absenceStatus = QueueAbsenceStatus.SKIP_ONE_TURN)
                } else {
                    registration
                }
            }
        )
    }

    fun cancelSkipOneTurn(registrationKey: Int): MachineQueue {
        val affectedKeys = fixedGroupKeys(registrationKey)
        return transformRegistrations(affectedKeys) { registration ->
            if (registration.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN) {
                registration.copy(absenceStatus = QueueAbsenceStatus.NONE)
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
                registrationKey -> it.copy(
                    preference = preference,
                    fixedPartnerKey = null,
                    // The choice belongs to 单人游玩; choosing anything else ends it. The choice
                    // of a fixed pair was made by two and ends with the pair.
                    allowsPassing = it.allowsPassing && preference == PlayPreference.SOLO &&
                        partnerKey == null
                )
                partnerKey -> it.copy(
                    preference = PlayPreference.OPEN_TO_JOIN,
                    fixedPartnerKey = null,
                    allowsPassing = false
                )
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
        // Not while either is postponed (QueueActionFailureCode.FIXED_PAIR_WHILE_POSTPONED).
        if (
            firstOriginal.absenceStatus != QueueAbsenceStatus.NONE ||
            secondOriginal.absenceStatus != QueueAbsenceStatus.NONE
        ) return null
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
        // A fixed pair shares one count of missed turns: the larger of the two own counts.
        // Each remembers how much of it came from the other, for when the pair is released.
        val firstOwnSkippedTurns = firstOriginal.ownSkippedTurns
        val secondOwnSkippedTurns = secondOriginal.ownSkippedTurns
        val pairSkippedTurns = maxOf(firstOwnSkippedTurns, secondOwnSkippedTurns)
        val first = clearedWaiting.first { it.key == firstKey }.copy(
            temporaryAwaySkippedTurns = pairSkippedTurns,
            skippedTurnsCarriedFromPartner = pairSkippedTurns - firstOwnSkippedTurns,
            fixedPartnerKey = secondKey,
            allowsPassing = false
        )
        val second = clearedWaiting.first { it.key == secondKey }.copy(
            temporaryAwaySkippedTurns = pairSkippedTurns,
            skippedTurnsCarriedFromPartner = pairSkippedTurns - secondOwnSkippedTurns,
            fixedPartnerKey = firstKey,
            allowsPassing = false
        )
        val firstIndex = waiting.indexOfFirst { it.key == firstKey }
        val secondIndex = waiting.indexOfFirst { it.key == secondKey }
        val samePosition = firstPosition == secondPosition
        val orderedPair = when {
            samePosition -> if (firstIndex < secondIndex) listOf(first, second) else listOf(second, first)
            firstPosition < secondPosition -> listOf(first, second)
            else -> listOf(second, first)
        }

        // Everybody else keeps the order they wait in. The pair takes the place of whichever of
        // the two waits further back (two that already share a position: the place of the one
        // further ahead, where the position is shown); where that would make somebody else
        // play in a later round, it takes the first place behind it at which nobody does.
        // Registrations that lose a partner to the pair are grouped again by the ordinary
        // rules, which never reach across a solo registration or a fixed pair.
        val others = clearedWaiting.filterNot { it.key in pairKeys }
        val anchorIndex = when {
            samePosition -> minOf(firstIndex, secondIndex)
            firstPosition > secondPosition -> firstIndex
            else -> secondIndex
        }
        val earliestInsertion = waiting.take(anchorIndex).count { it.key !in pairKeys }

        // "Later" is judged twice. By the positions as they wait; and by the round in which
        // each registration will really play, which also knows about postponed turns, pending
        // check-ins and registrations that let others pass: a pair that no longer pairs with a
        // registration around it can add a round to the queue without moving anybody.
        val originalRounds = RoundPlanner.firstPlayingRounds(this)

        data class Placement(
            val waiting: List<Registration>,
            val pairPositionIndex: Int,
            val delayedOthers: List<Registration>,
            val rounds: Map<Int, Int>
        )

        fun placementAt(insertion: Int): Placement {
            val candidate = others.take(insertion) + orderedPair + others.drop(insertion)
            val positionByKey = buildMap {
                groupIntoPositions(candidate).forEachIndexed { positionIndex, registrations ->
                    registrations.forEach { put(it.key, positionIndex) }
                }
            }
            val rounds = RoundPlanner.firstPlayingRounds(copy(waiting = candidate))
            return Placement(
                waiting = candidate,
                pairPositionIndex = positionByKey.getValue(firstKey),
                delayedOthers = others.filter { registration ->
                    positionByKey.getValue(registration.key) >
                        (originalPositionByKey[registration.key] ?: Int.MAX_VALUE) ||
                        (rounds[registration.key] ?: Int.MAX_VALUE) >
                        (originalRounds[registration.key] ?: Int.MAX_VALUE)
                },
                rounds = rounds
            )
        }

        // Never between the two registrations of another fixed pair.
        fun splitsAnotherPair(insertion: Int): Boolean =
            insertion in 1 until others.size &&
                others[insertion - 1].fixedPartnerKey == others[insertion].key

        val placement = (earliestInsertion..others.size).asSequence()
            .filterNot(::splitsAnotherPair)
            .map(::placementAt)
            .firstOrNull { it.delayedOthers.isEmpty() }
            ?: placementAt(
                (earliestInsertion..others.size).first { !splitsAnotherPair(it) }
            )
        val pairPositionIndex = placement.pairPositionIndex
        // Moved back is whoever of the two now plays in a later round; where the rounds do
        // not tell (neither can play for now), whoever waits in a later position.
        val movedBack = orderedPair.filter { registration ->
            val before = originalRounds[registration.key]
            val after = placement.rounds[registration.key]
            if (before != null && after != null) {
                after > before
            } else {
                pairPositionIndex > (originalPositionByKey[registration.key] ?: pairPositionIndex)
            }
        }
        return FriendPairPlan(
            originalWaiting = waiting,
            waiting = placement.waiting,
            firstRegistration = firstOriginal,
            secondRegistration = secondOriginal,
            pairPositionIndex = pairPositionIndex,
            movedBackRegistrations = movedBack,
            delayedOtherRegistrations = placement.delayedOthers
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
                skippedTurnsCarriedFromPartner = planned.skippedTurnsCarriedFromPartner,
                fixedPartnerKey = planned.fixedPartnerKey,
                allowsPassing = planned.allowsPassing && planned.fixedPartnerKey == null
            )
        }
        return copy(waiting = sanitizeFriendPairs(rebasedWaiting))
    }

    fun createFriendPair(registrationKey: Int, friend: Registration): MachineQueue {
        val registration = waiting.firstOrNull { it.key == registrationKey } ?: return this
        if (registration.requiresOnSiteCheckIn) return this
        if (registration.absenceStatus != QueueAbsenceStatus.NONE) return this
        val normalizedFriendId = friend.displayId.trim()
        if (normalizedFriendId.isBlank() || containsId(normalizedFriendId)) return this
        val previousPartnerKey = registration.fixedPartnerKey
        val remaining = waiting.filterNot { it.key == registrationKey }.map {
            if (it.key == previousPartnerKey) {
                it.copy(preference = PlayPreference.OPEN_TO_JOIN, fixedPartnerKey = null, allowsPassing = false)
            } else {
                it
            }
        }
        // The friend's registration is new: the whole shared count is the first one's own.
        val ownSkippedTurns = registration.ownSkippedTurns
        val first = registration.copy(
            preference = PlayPreference.OPEN_TO_JOIN,
            absenceStatus = registration.absenceStatus,
            temporaryAwaySkippedTurns = ownSkippedTurns,
            skippedTurnsCarriedFromPartner = 0,
            fixedPartnerKey = friend.key,
            allowsPassing = false
        )
        val second = friend.copy(
            displayId = normalizedFriendId,
            preference = PlayPreference.OPEN_TO_JOIN,
            absenceStatus = registration.absenceStatus,
            temporaryAwaySkippedTurns = ownSkippedTurns,
            skippedTurnsCarriedFromPartner = ownSkippedTurns,
            fixedPartnerKey = registration.key,
            allowsPassing = false
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
        startNextWhenPlayingBecomesEmpty: Boolean = true,
        atMillis: Long = System.currentTimeMillis(),
        nextGroupKeys: Set<Int>? = null
    ): MachineQueue {
        val affectedKeys = fixedGroupKeys(registrationKey)
        if (
            affectedKeys.isEmpty() ||
            affectedKeys.any { !canMarkNoShow(it, nextGroupKeys) } ||
            mustRemoveWhenAbsent(affectedKeys)
        ) return this
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
            deferred.advanceIfNeeded(atMillis)
        } else {
            deferred
        }
    }

    fun markNoShowMoveToEnd(
        registrationKeys: Set<Int>,
        startNextWhenPlayingBecomesEmpty: Boolean = true,
        atMillis: Long = System.currentTimeMillis(),
        nextGroupKeys: Set<Int>? = null
    ): MachineQueue {
        if (
            registrationKeys.isEmpty() ||
            registrationKeys.any { !canMarkNoShow(it, nextGroupKeys) } ||
            mustRemoveWhenAbsent(registrationKeys)
        ) {
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
            // Moving an absent registration to the tail must not immediately select it again
            // when the remaining queue is short. It rejoins normal selection next time.
            updated.advanceIfNeeded(atMillis, registrationKeys)
        } else {
            updated
        }
    }

    fun markNoShowGroupDeferOneRound(
        registrationKeys: Set<Int>,
        startNextWhenPlayingBecomesEmpty: Boolean = true,
        atMillis: Long = System.currentTimeMillis(),
        nextGroupKeys: Set<Int>? = null
    ): MachineQueue {
        // Deferring one of a fixed pair defers both, as markNoShowDeferOneRound does; the
        // terminal always names the whole position, a website or API caller may not
        // (review 2026-11-27).
        val affectedKeys = registrationKeys.flatMapTo(mutableSetOf(), ::fixedGroupKeys)
        if (
            registrationKeys.isEmpty() ||
            affectedKeys.isEmpty() ||
            affectedKeys.any { !canMarkNoShow(it, nextGroupKeys) } ||
            mustRemoveWhenAbsent(affectedKeys)
        ) {
            return this
        }
        val updated = transformRegistrations(affectedKeys) {
            it.copy(
                noShowCount = it.noShowCount + 1,
                lastNoShowActionWasDefer = true
            )
        }
        val deferred = updated.deferRegistrationsOneRound(affectedKeys)
        return if (startNextWhenPlayingBecomesEmpty && deferred.playing.isEmpty()) {
            deferred.advanceIfNeeded(atMillis)
        } else {
            deferred
        }
    }

    fun markNoShowAndRemove(
        registrationKeys: Set<Int>,
        startNextWhenPlayingBecomesEmpty: Boolean = true,
        atMillis: Long = System.currentTimeMillis(),
        nextGroupKeys: Set<Int>? = null
    ): MachineQueue {
        if (registrationKeys.isEmpty() || registrationKeys.any { !canMarkNoShow(it, nextGroupKeys) }) {
            return this
        }
        val updated = removeAll(registrationKeys)
        return if (startNextWhenPlayingBecomesEmpty && updated.playing.isEmpty()) {
            updated.advanceIfNeeded(atMillis)
        } else {
            updated
        }
    }

    /** Whether a no-show of [registrationKeys] can only be settled by removing them. */
    private fun mustRemoveWhenAbsent(registrationKeys: Set<Int>): Boolean =
        allRegistrations.any { it.key in registrationKeys && it.mustBeRemovedWhenAbsent }

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
        if (it.requiresOnSiteCheckIn) {
            it.copy(requiresOnSiteCheckIn = false, wasOnlineRegistration = true)
        } else {
            it
        }
    }

    /**
     * Records whether this 单人游玩 registration, or the fixed pair it belongs to, lets the
     * registrations around it pass. A fixed pair has one choice for both.
     */
    fun setAllowsPassing(registrationKey: Int, enabled: Boolean): MachineQueue =
        transformRegistrations(fixedGroupKeys(registrationKey)) {
            if (it.allowsPassing == enabled) it else it.copy(allowsPassing = enabled)
        }

    /**
     * The passes the waiting positions on screen show: for each registration that is shown
     * with a registration ahead of it in real order across one or more registrations that let
     * others pass, those passed registrations in their real order. The passes of the playing
     * position are in [Registration.passedOverKeys].
     */
    fun projectedPasses(): Map<Int, List<Int>> {
        if (waiting.none(Registration::letsOthersPass)) return emptyMap()
        val indexByKey = waiting.mapIndexed { index, registration -> registration.key to index }.toMap()
        val passes = mutableMapOf<Int, List<Int>>()
        val shown = waitingProjection(includeCommonPlayPreview = false).positions
        val shownIndexByKey = buildMap {
            shown.forEachIndexed { positionIndex, position ->
                position.registrations.forEach { put(it.key, positionIndex) }
            }
        }
        shown.forEachIndexed { positionIndex, position ->
            if (position.registrations.size != 2) return@forEachIndexed
            val (first, second) = position.registrations
                .sortedBy { indexByKey[it.key] ?: Int.MAX_VALUE }
            val from = indexByKey[first.key] ?: return@forEachIndexed
            val to = indexByKey[second.key] ?: return@forEachIndexed
            // Passed is only a registration that plays after the two. One that is shown ahead
            // of them, because the registration in front of it postponed its turn, plays first.
            val passed = waiting.subList(from + 1, to)
                .filter { between ->
                    isShownAsPassedOver(between) &&
                        (shownIndexByKey[between.key] ?: Int.MAX_VALUE) > positionIndex
                }
                .map(Registration::key)
            if (passed.isNotEmpty()) passes[second.key] = passed
        }
        return passes
    }

    /** Records whether this registration also queues at the paired machine. */
    fun setPairedMachineQueueing(registrationKey: Int, enabled: Boolean): MachineQueue =
        transformRegistrations(setOf(registrationKey)) {
            if (it.alsoQueuesAtPairedMachine == enabled) {
                it
            } else {
                it.copy(alsoQueuesAtPairedMachine = enabled)
            }
        }

    fun removeAll(registrationKeys: Set<Int>): MachineQueue {
        val remainingPlaying = playing.filterNot { it.key in registrationKeys }
        return copy(
            playing = sanitizeFriendPairs(remainingPlaying.map {
                if (it.fixedPartnerKey?.let { partnerKey -> partnerKey in registrationKeys } == true) {
                    it.copy(preference = PlayPreference.OPEN_TO_JOIN, fixedPartnerKey = null, allowsPassing = false)
                } else it
            }),
            waiting = sanitizeFriendPairs(waiting.filterNot { it.key in registrationKeys }.map {
                if (it.fixedPartnerKey?.let { partnerKey -> partnerKey in registrationKeys } == true) {
                    it.copy(preference = PlayPreference.OPEN_TO_JOIN, fixedPartnerKey = null, allowsPassing = false)
                } else it
            }),
            playingStartedAtMillis = if (remainingPlaying.isEmpty()) null else playingStartedAtMillis
        )
    }

    /**
     * Moves the registrations of one waiting position in front of the position at
     * [destinationIndex] (or behind it when moving later). Only the moved registrations change
     * place: every other registration keeps its real order. Positions are not written back one
     * after another, because a position need not be a contiguous stretch of the real order: two
     * open registrations pair across a registration that cannot play now (将暂缓一次 and the
     * like), and writing the positions back would quietly move that registration too.
     */
    fun moveWaitingPosition(sourceIndex: Int, destinationIndex: Int): MachineQueue {
        val positions = waitingPositions()
        if (
            sourceIndex !in positions.indices ||
            destinationIndex !in positions.indices ||
            sourceIndex == destinationIndex
        ) return this
        return moveWaitingRegistrations(
            movedKeys = positions[sourceIndex].mapTo(mutableSetOf(), Registration::key),
            targetKeys = positions[destinationIndex].mapTo(mutableSetOf(), Registration::key),
            beforeTarget = destinationIndex < sourceIndex
        )
    }

    /**
     * Moves the waiting registrations [movedKeys] together, in their order, to just before the
     * first of [targetKeys] or just after the last of them. The home screen drags the positions
     * it shows, which need not be those of [waitingPositions]: a pending online registration is
     * shown as if it checks in, a pass that the check refuses is not shown, and the previews of
     * the other machine of a pair regroup the registrations around them (reported 2026-11-27:
     * such positions could not be dragged).
     */
    fun moveWaitingRegistrations(
        movedKeys: Set<Int>,
        targetKeys: Set<Int>,
        beforeTarget: Boolean
    ): MachineQueue {
        if (movedKeys.isEmpty() || targetKeys.isEmpty() || movedKeys.any { it in targetKeys }) return this
        val moved = waiting.filter { it.key in movedKeys }
        if (moved.size != movedKeys.size) return this
        val others = waiting.filterNot { it.key in movedKeys }
        val insertAt = if (beforeTarget) {
            others.indexOfFirst { it.key in targetKeys }
        } else {
            others.indexOfLast { it.key in targetKeys } + 1
        }
        if (insertAt < 0 || (!beforeTarget && insertAt == 0)) return this
        return copy(waiting = others.take(insertAt) + moved + others.drop(insertAt))
    }

    /**
     * The waiting positions as the home screen and the snapshot show them, as sets of keys in
     * the order shown. This is what a management app or a server knows of this machine.
     */
    fun shownWaitingPositionKeys(): List<List<Int>> =
        waitingProjection(includeCommonPlayPreview = false).positions.map { position ->
            position.registrations.map(Registration::key)
        }

    /**
     * Moves one of [positions] (the waiting registrations grouped as the home screen shows them,
     * in the order it lists them) from [sourceIndex] to [destinationIndex] in the real waiting
     * order. The positions it jumps over decide where it goes: moved forward, its registrations
     * go to just before the first of theirs; moved back, those of its registrations that stand
     * before the last of theirs go to just behind it, and the others stay where they are.
     * Nobody else moves. The shown positions are not always runs of the real order: a
     * registration that passes a 单人游玩 one is shown with its partner while the one it passes
     * stays between them, and keeps that place (found 2026-11-27 by the random test: moving a
     * position as one block took such places away).
     */
    fun withShownPositionMoved(
        positions: List<Set<Int>>,
        sourceIndex: Int,
        destinationIndex: Int
    ): MachineQueue {
        if (
            sourceIndex !in positions.indices ||
            destinationIndex !in positions.indices ||
            sourceIndex == destinationIndex
        ) return this
        val moved = positions[sourceIndex]
        val backward = destinationIndex > sourceIndex
        val jumped = if (backward) {
            positions.subList(sourceIndex + 1, destinationIndex + 1)
        } else {
            positions.subList(destinationIndex, sourceIndex)
        }.flatMapTo(mutableSetOf()) { it }
        val boundary = if (backward) {
            waiting.indexOfLast { it.key in jumped }
        } else {
            waiting.indexOfFirst { it.key in jumped }
        }
        if (boundary < 0) return this
        val boundaryKey = waiting[boundary].key
        val moving = waiting.filterIndexed { index, registration ->
            registration.key in moved && if (backward) index < boundary else index > boundary
        }
        if (moving.isEmpty()) return this
        val movingKeys = moving.mapTo(mutableSetOf(), Registration::key)
        val others = waiting.filterNot { it.key in movingKeys }
        val insertAt = others.indexOfFirst { it.key == boundaryKey } + if (backward) 1 else 0
        return copy(waiting = others.take(insertAt) + moving + others.drop(insertAt))
    }

    /**
     * Applies a new order of the positions as shown, [desired] (the positions of
     * [shownWaitingPositionKeys], rearranged), to the real waiting order: the positions that
     * keep their order among themselves stay, and each of the others is moved to its new place
     * with [withShownPositionMoved]. So a management app that rearranges what it sees does not
     * write the shown order back as the real one, which silently took away the place of a
     * registration that is passed over or deferred (found 2026-11-27). Returns null when
     * [desired] is not a rearrangement of the positions shown.
     */
    fun withShownPositionsReordered(desired: List<List<Int>>): MachineQueue? =
        reorderedAsShown(shownWaitingPositionKeys().map { it.toSet() }, desired.map { it.toSet() })

    /**
     * [withShownPositionsReordered] for a new order of the registrations themselves, as shown:
     * [desiredKeys] holds every waiting registration once.
     */
    fun withShownRegistrationOrder(desiredKeys: List<Int>): MachineQueue? =
        reorderedAsShown(shownWaitingPositionKeys().flatten().map(::setOf), desiredKeys.map(::setOf))

    private fun reorderedAsShown(shown: List<Set<Int>>, wanted: List<Set<Int>>): MachineQueue? {
        if (wanted.size != shown.size || wanted.toSet() != shown.toSet() || wanted.toSet().size != wanted.size) return null
        if (wanted == shown) return this
        // The positions left where they are: a longest run that both orders share.
        val lengths = Array(shown.size + 1) { IntArray(wanted.size + 1) }
        for (i in shown.indices.reversed()) {
            for (j in wanted.indices.reversed()) {
                lengths[i][j] = if (shown[i] == wanted[j]) {
                    lengths[i + 1][j + 1] + 1
                } else {
                    maxOf(lengths[i + 1][j], lengths[i][j + 1])
                }
            }
        }
        val kept = mutableSetOf<Set<Int>>()
        var i = 0
        var j = 0
        while (i < shown.size && j < wanted.size) {
            when {
                shown[i] == wanted[j] -> {
                    kept += shown[i]
                    i++
                    j++
                }
                lengths[i + 1][j] >= lengths[i][j + 1] -> i++
                else -> j++
            }
        }
        val current = shown.toMutableList()
        var result = this
        wanted.forEachIndexed { index, position ->
            if (position in kept) return@forEachIndexed
            val from = current.indexOf(position)
            // Right behind the position that precedes it in the new order.
            val anchor = if (index == 0) -1 else current.indexOf(wanted[index - 1])
            val to = if (from > anchor) anchor + 1 else anchor
            result = result.withShownPositionMoved(current, from, to)
            current.removeAt(from)
            current.add(to, position)
        }
        return result
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

    internal fun fixedGroupKeys(registrationKey: Int): Set<Int> {
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
                        it.copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND)
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
            it.copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND)
        }
    }
}

fun MachineQueue.hasSameQueueOperationState(other: MachineQueue): Boolean =
    playingStartedAtMillis == other.playingStartedAtMillis &&
        registrationsHaveSameQueueState(playing, other.playing) &&
        registrationsHaveSameQueueState(waiting, other.waiting)

/**
 * Whether a pair forming across [registration] really passes it. One that cannot play at that
 * moment (暂缓一次, 轮空一次, 暂时离开, 待签到) would be passed with or without its choice, so it
 * is not told that it was (review 2026-11-27).
 */
internal fun isPassedOver(registration: Registration): Boolean =
    registration.letsOthersPass && registration.canEnterPlayingPosition

/**
 * [isPassedOver] as the home page tells it in advance: a pending online registration counts as
 * checking in there, so a pair forming across it is shown as passing it (reported 2026-11-27:
 * “将越过” was missing). When the round really starts, one still pending is reached and removed
 * instead, so the record made then ([isPassedOver]) leaves it out.
 */
internal fun isShownAsPassedOver(registration: Registration): Boolean =
    registration.letsOthersPass && registration.absenceStatus == QueueAbsenceStatus.NONE

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
        left.deferOneRoundUsed == right.deferOneRoundUsed &&
        left.fixedPartnerKey == right.fixedPartnerKey &&
        left.requiresOnSiteCheckIn == right.requiresOnSiteCheckIn &&
        left.onSiteCheckInStartedAtMillis == right.onSiteCheckInStartedAtMillis &&
        left.alsoQueuesAtPairedMachine == right.alsoQueuesAtPairedMachine &&
        left.skipsPairedMachineThisRound == right.skipsPairedMachineThisRound &&
        // Who pairs with whom depends on it, so a plan made before it changed is out of date
        // (review 2026-11-27: an old plan for a fixed pair turned a player's choice back).
        left.allowsPassing == right.allowsPassing
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
                        (
                            (first.absenceStatus != QueueAbsenceStatus.TEMPORARILY_AWAY &&
                                first.absenceStatus != QueueAbsenceStatus.SKIP_ONE_TURN) ||
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
            // Like a 单人游玩 registration that lets others pass, a fixed pair that does keeps
            // the open registration ahead of it waiting for a partner from behind.
            if (!group.all(Registration::letsOthersPass)) flushPendingOpen()
            positions += group.toMutableList()
            index = partnerIndex!! + 1
            continue
        }

        when (first.preference) {
            PlayPreference.SOLO -> {
                // An open registration ahead still waiting for a partner may pair across a
                // registration that lets others pass; it keeps its own position behind them.
                if (!first.letsOthersPass) flushPendingOpen()
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

/** The missed turns that are this registration's own, without those shared by a fixed partner. */
val Registration.ownSkippedTurns: Int
    get() = (temporaryAwaySkippedTurns - skippedTurnsCarriedFromPartner).coerceAtLeast(0)

/** A registration that is no longer part of a fixed pair counts its own missed turns again. */
fun Registration.withOwnSkippedTurnsOutsideAPair(): Registration =
    if (fixedPartnerKey != null || skippedTurnsCarriedFromPartner == 0) {
        this
    } else {
        copy(temporaryAwaySkippedTurns = ownSkippedTurns, skippedTurnsCarriedFromPartner = 0)
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
                fixedPartnerKey = null,
                allowsPassing = false
            )
        } else {
            registration
        }
    }
}
