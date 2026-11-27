package com.abcccc.maimaiqueue

/** Identifies where an action originated without coupling queue-core to a UI or network stack. */
enum class QueueActionOrigin {
    ON_SITE_TERMINAL,
    MOBILE_DEVICE,
    WEBSITE,
    QQ_BOT,
    MANAGEMENT_APP,
    SYSTEM
}

enum class RegistrationPlacement {
    AUTO_ADVANCE,
    WAITING_TAIL,
    STAGED_WAITING,
    ADVANCE_IF_UNAMBIGUOUS
}

enum class NoShowResolution {
    DEFER_ONE_ROUND,
    DEFER_GROUP_ONE_ROUND,
    MOVE_TO_TAIL,
    REMOVE
}

enum class QueueActionFailureCode {
    MACHINE_NOT_FOUND,
    MACHINE_STOPPED,
    REGISTRATION_CLOSED,
    ONLINE_REGISTRATION_DISABLED,
    DEFER_ONE_ROUND_DISABLED,
    TEMPORARY_LEAVE_DISABLED,
    MACHINE_FULL,
    INVALID_REGISTRATION,
    DUPLICATE_REGISTRATION,
    REGISTRATION_NOT_FOUND,
    PENDING_CHECK_IN,
    WRONG_ABSENCE_STATE,
    PLAYING_POSITION_NOT_ALLOWED,
    INVALID_POSITION,
    INVALID_FIXED_PAIR,
    SINGLE_PLAYER_MACHINE_ONLY,
    WOULD_DELAY_OTHER_REGISTRATIONS,
    PAIRED_MACHINE_QUEUE_UNAVAILABLE,
    /** Only a registration of a player profile can be arranged between two machines. */
    PAIRED_MACHINE_QUEUE_NEEDS_PLAYER_PROFILE,
    /** Only a 单人游玩 registration outside a fixed pair can let others pass. */
    PASSING_NEEDS_SOLO,
    /** See [Registration.mustBeRemovedWhenAbsent]. */
    NO_SHOW_REMOVAL_ONLY,
    /** 暂缓一次 was used and the registration has not played since; see [Registration.deferOneRoundUsed]. */
    DEFER_ONE_ROUND_ALREADY_USED,
    /**
     * A checked-in online registration that has not played cannot be postponed in any way,
     * except from the management app (maintainer, 2026-11-25).
     */
    ONLINE_REGISTRATION_NOT_PLAYED,
    /**
     * A fixed pair cannot be formed while either registration is 将暂缓一次, 将轮空一次 or 暂时离开:
     * the pair used to take over the stricter state, which postponed the other registration
     * without its asking and got round the limits on postponing (maintainer, 2026-11-27).
     */
    FIXED_PAIR_WHILE_POSTPONED,
    STALE_STATE,
    NO_STATE_CHANGE,
    INVARIANT_VIOLATION
}

data class QueueActionFailure(
    val code: QueueActionFailureCode,
    val detail: String? = null
)

/**
 * The waits that make arranging a registration to the other machine of its pair predictable:
 * it is announced by a preview first, and only happens once that preview has been shown for a
 * while without a break.
 */
data class PairedArrangementRules(
    /** How long the preview must have been shown without a break before the registration moves. */
    val leadMillis: Long = 12L * 60_000L,
    /** A preview appears once the other machine is expected earlier by more than this... */
    val appearMarginMillis: Long = 3L * 60_000L,
    /** ...and breaks when it is no longer earlier by more than this. */
    val breakMarginMillis: Long = 60_000L,
    /** After this much uninterrupted preview the player is told. */
    val noticeAfterMillis: Long = 5L * 60_000L,
    /** A round that has run longer than this makes its machine's estimates unreliable. */
    val overdueRoundMillis: Long = 20L * 60_000L
)

/**
 * What the engine remembers about one registration that may be, or has just been, arranged to
 * the other machine of its pair. It is kept beside the queues rather than inside the
 * registration: the timing changes as time passes, and must not make an open confirmation of
 * some unrelated action look out of date.
 */
/** See [QueueEngine.pairedArrangementOutlook]. */
data class PairedArrangementOutlook(
    /**
     * The earliest moment at which the registration may be arranged to the other machine; null
     * while the count is held because the queues are not being kept up normally.
     */
    val notBeforeMillis: Long?
)

data class PairedArrangementRecord(
    /** Since when the preview at the other machine has been shown without a break. */
    val previewSinceMillis: Long? = null,
    /** Set while the count is paused because a machine's round is overdue. */
    val previewPausedAtMillis: Long? = null,
    /** When the player was told in this wait; they are told once per wait. */
    val noticedAtMillis: Long? = null,
    /** While it plays the round it was arranged for: the machine it came from... */
    val arrangedFromMachineId: String? = null,
    /** ...and the registrations that were ahead of it there, for the way back. */
    val aheadKeysAtOrigin: List<Int> = emptyList()
)

data class QueueEnginePolicy(
    val registrationOpen: Boolean = true,
    val allowOnlineRegistration: Boolean = true,
    val allowDeferOneRound: Boolean = true,
    val allowTemporaryLeave: Boolean = true,
    val machineStatuses: Map<String, MachineStatus> = emptyMap(),
    val machineCapacities: Map<String, Int> = emptyMap(),
    val maxRegistrationsPerMachine: Int = 20,
    val requireOperationalForPlayerActions: Boolean = false,
    /**
     * Machines configured as a pair for queueing at both at once, in both directions:
     * `A to B` and `B to A`. A machine belongs to at most one pair.
     */
    val machinePartners: Map<String, String> = emptyMap(),
    /**
     * When set, a registration is only arranged to the other machine of its pair after that has
     * been announced, see [PairedArrangementRules]. Null arranges as soon as it is reached.
     */
    val pairedArrangementRules: PairedArrangementRules? = null
) {
    fun isOperational(machineId: String): Boolean =
        machineStatuses[machineId]?.isOperational != false

    fun machineCapacity(machineId: String): Int =
        machineCapacities[machineId]?.takeIf { it == 1 || it == 2 } ?: 2
}

data class QueueActionContext(
    val atMillis: Long = System.currentTimeMillis(),
    val origin: QueueActionOrigin = QueueActionOrigin.ON_SITE_TERMINAL,
    val policy: QueueEnginePolicy = QueueEnginePolicy()
)

data class QueueEngineState(
    val queues: Map<String, MachineQueue>,
    /**
     * For each machine of an active pair: its waiting registrations interleaved with the places
     * that registrations of the other machine hold here, as registration keys in order.
     */
    val pairedMachineOrders: Map<String, List<Int>> = emptyMap(),
    /** By registration key; see [PairedArrangementRecord]. */
    val pairedArrangements: Map<Int, PairedArrangementRecord> = emptyMap()
) {
    val allRegistrations: List<Registration>
        get() = queues.values.flatMap(MachineQueue::allRegistrations)

    fun queue(machineId: String): MachineQueue? = queues[machineId]

    fun replace(machineId: String, queue: MachineQueue): QueueEngineState =
        copy(queues = queues + (machineId to queue))

    fun invariantViolations(): List<String> = buildList {
        queues.forEach { (machineId, queue) ->
            queue.invariantViolations().forEach { add("机台 $machineId：$it") }
        }
        val duplicateKeys = allRegistrations.groupBy(Registration::key)
            .filterValues { it.size > 1 }
            .keys
        if (duplicateKeys.isNotEmpty()) {
            add("不同机台不能出现重复登记编号：${duplicateKeys.sorted().joinToString()}")
        }
        addAll(PairedMachineQueues.invariantViolations(this@QueueEngineState))
    }.distinct()

    companion object {
        fun single(machineId: String, queue: MachineQueue): QueueEngineState =
            QueueEngineState(mapOf(machineId to queue))
    }
}

sealed interface QueueAction {
    val machineId: String

    data class AddRegistrations(
        override val machineId: String,
        val registrations: List<Registration>,
        val placement: RegistrationPlacement
    ) : QueueAction

    data class FinishRound(override val machineId: String) : QueueAction
    data class EndRoundOnly(override val machineId: String) : QueueAction
    data class RemoveCurrentRoundAndStartNext(override val machineId: String) : QueueAction
    data class EnterPlayingPosition(override val machineId: String) : QueueAction

    data class RestartPlayingTimer(override val machineId: String) : QueueAction
    data class RestartPendingCheckInTimers(override val machineId: String) : QueueAction
    data class RestartMachineTimers(override val machineId: String) : QueueAction

    data class ReturnPlayingToWaitingFront(
        override val machineId: String,
        val registrationKeys: Set<Int>
    ) : QueueAction

    data class MoveWaitingRegistrationIntoCurrentRound(
        override val machineId: String,
        val registrationKey: Int
    ) : QueueAction

    data class AdvanceToWaitingPosition(
        override val machineId: String,
        val registrationKeys: Set<Int>
    ) : QueueAction

    data class DeferOneRound(
        override val machineId: String,
        val registrationKey: Int
    ) : QueueAction

    data class CancelDeferOneRound(
        override val machineId: String,
        val registrationKey: Int
    ) : QueueAction

    data class TemporarilyLeave(
        override val machineId: String,
        val registrationKey: Int
    ) : QueueAction

    /** 将轮空一次; see [QueueAbsenceStatus.SKIP_ONE_TURN]. */
    data class SkipOneTurn(
        override val machineId: String,
        val registrationKey: Int
    ) : QueueAction

    data class CancelSkipOneTurn(
        override val machineId: String,
        val registrationKey: Int
    ) : QueueAction

    data class CancelTemporaryLeave(
        override val machineId: String,
        val registrationKey: Int
    ) : QueueAction

    data class ChangePreference(
        override val machineId: String,
        val registrationKey: Int,
        val preference: PlayPreference
    ) : QueueAction

    data class CreateFixedPair(
        override val machineId: String,
        val firstRegistrationKey: Int,
        val secondRegistrationKey: Int,
        val expectedPlan: FriendPairPlan? = null,
        val advanceWhenPlayingEmpty: Boolean = false,
        /** The pair lets the registrations behind it pass (asked when the pair is formed). */
        val allowsPassing: Boolean = false
    ) : QueueAction

    data class CreateFixedPairWithRegistration(
        override val machineId: String,
        val registrationKey: Int,
        val friend: Registration,
        val advanceWhenPlayingEmpty: Boolean = false,
        /** The pair lets the registrations behind it pass (asked when the pair is formed). */
        val allowsPassing: Boolean = false
    ) : QueueAction

    data class RenameRegistration(
        override val machineId: String,
        val registrationKey: Int,
        val displayId: String
    ) : QueueAction

    data class SyncPlayerProfileDetails(
        override val machineId: String,
        val playerProfileId: String,
        val nickname: String,
        val gender: PlayerGender
    ) : QueueAction

    data class ClaimRegistration(
        override val machineId: String,
        val registrationKey: Int
    ) : QueueAction

    data class ClaimWithPlayerProfile(
        override val machineId: String,
        val registrationKey: Int,
        val playerProfileId: String,
        val nickname: String,
        val gender: PlayerGender,
        val preferenceOverride: PlayPreference? = null
    ) : QueueAction

    data class MarkNoShow(
        override val machineId: String,
        val registrationKeys: Set<Int>,
        val resolution: NoShowResolution,
        val startNextWhenPlayingBecomesEmpty: Boolean = true
    ) : QueueAction

    data class CheckIn(
        override val machineId: String,
        val registrationKey: Int
    ) : QueueAction

    /**
     * Turns on or off whether a 单人游玩 registration lets the registrations around it pass
     * (docs/releases/solo-pass-plan-2026-11.md).
     */
    data class SetAllowsPassing(
        override val machineId: String,
        val registrationKey: Int,
        val enabled: Boolean
    ) : QueueAction

    /** Turns queueing at both machines of the pair on or off for one registration. */
    data class SetPairedMachineQueueing(
        override val machineId: String,
        val registrationKey: Int,
        val enabled: Boolean
    ) : QueueAction

    data class RemoveRegistrations(
        override val machineId: String,
        val registrationKeys: Set<Int>
    ) : QueueAction

    /**
     * The player takes their own registration out of the queue. The effect is that of
     * [RemoveRegistrations]; it is kept apart so that records can say who decided.
     */
    data class LeaveQueue(
        override val machineId: String,
        val registrationKey: Int
    ) : QueueAction

    data class RemoveExpiredOnlineRegistrations(override val machineId: String) : QueueAction

    data class MoveWaitingPosition(
        override val machineId: String,
        val sourceIndex: Int,
        val destinationIndex: Int
    ) : QueueAction

    data class ReplaceOrder(
        override val machineId: String,
        val registrations: List<Registration>,
        /**
         * The registrations the operator dragged, when the caller knows them. Without it the
         * moved registrations are worked out from the old and the new order.
         */
        val movedRegistrationKeys: Set<Int>? = null
    ) : QueueAction

    data class TransferRegistrations(
        val sourceMachineId: String,
        val destinationMachineId: String,
        val registrationKeys: Set<Int>
    ) : QueueAction {
        override val machineId: String
            get() = sourceMachineId
    }

    data class ClearRegistrations(
        val machineIds: Set<String>
    ) : QueueAction {
        override val machineId: String
            get() = machineIds.sorted().firstOrNull().orEmpty()
    }

    data class RestoreSnapshot(
        override val machineId: String,
        val expectedCurrentQueue: MachineQueue,
        val restoredQueue: MachineQueue,
        val excludedRegistrationKeys: Set<Int> = emptySet(),
        /**
         * Other machines the undone action also changed, for example the paired machine a
         * registration was moved from: machine id to its queue before and after that action.
         */
        val alsoRestoredQueues: Map<String, RestoredMachineQueue> = emptyMap(),
        /** The orders shared across paired machines as they were before the undone action. */
        val restoredPairedMachineOrders: Map<String, List<Int>>? = null,
        /** What was remembered about arrangements before the undone action. */
        val restoredPairedArrangements: Map<Int, PairedArrangementRecord>? = null
    ) : QueueAction

    data class RestoredMachineQueue(
        val expectedCurrentQueue: MachineQueue,
        val restoredQueue: MachineQueue
    )
}

private val QueueAction.requiredUnchangedMachineIds: Set<String>
    get() = when (this) {
        is QueueAction.TransferRegistrations -> setOf(sourceMachineId, destinationMachineId)
        is QueueAction.ClearRegistrations -> machineIds
        is QueueAction.RestoreSnapshot -> alsoRestoredQueues.keys + machineId
        else -> setOf(machineId)
    }

data class QueueActionImpact(
    val changedMachineIds: Set<String>,
    val addedRegistrationKeys: Set<Int>,
    val removedRegistrationKeys: Set<Int>,
    val changedRegistrationKeys: Set<Int>,
    val movedRegistrationKeys: Set<Int>,
    val affectedRegistrationKeys: Set<Int>,
    val roundPreview: NextPlayingPositionPreview? = null,
    val friendPairPlan: FriendPairPlan? = null,
    val requiresAvailabilityConfirmation: Boolean = false,
    val requiresConfirmation: Boolean = false
)

class QueueActionPlan internal constructor(
    val action: QueueAction,
    val context: QueueActionContext,
    val sourceState: QueueEngineState,
    val resultState: QueueEngineState,
    val impact: QueueActionImpact,
    val failure: QueueActionFailure?
) {
    val canApply: Boolean
        get() = failure == null && resultState != sourceState

    fun applyTo(
        currentState: QueueEngineState,
        currentPolicy: QueueEnginePolicy = context.policy,
        atMillis: Long = context.atMillis
    ): QueueActionExecution {
        // On a paired machine the outcome also depends on the other machine and on the places
        // held across the pair, so a change there invalidates the confirmation as well.
        val pairedMachineIds = action.requiredUnchangedMachineIds.flatMapTo(mutableSetOf()) { machineId ->
            listOfNotNull(
                machineId,
                PairedMachineQueues.activePartner(sourceState, context.policy, machineId)
            )
        }
        if (pairedMachineIds.any { machineId ->
                currentState.queue(machineId) != sourceState.queue(machineId) ||
                    currentState.pairedMachineOrders[machineId] !=
                    sourceState.pairedMachineOrders[machineId]
            }) {
            return QueueActionExecution.Rejected(
                QueueActionFailure(QueueActionFailureCode.STALE_STATE)
            )
        }
        failure?.let { return QueueActionExecution.Rejected(it) }
        val effectivePlan = if (
            currentState == sourceState &&
            currentPolicy == context.policy &&
            atMillis == context.atMillis
        ) {
            this
        } else {
            QueueEngine.plan(
                state = currentState,
                action = action,
                context = context.copy(
                    atMillis = atMillis,
                    policy = currentPolicy
                )
            )
        }
        val rejected = effectivePlan.failure
        return if (rejected != null) {
            QueueActionExecution.Rejected(rejected)
        } else {
            QueueActionExecution.Applied(effectivePlan.resultState, effectivePlan.impact)
        }
    }
}

/**
 * Whom a registration passed is recorded only for the round it plays (Registration.passedOverKeys),
 * only while it plays together with the registration it paired with, and only for registrations
 * that still wait behind it at that machine. A registration taken back to the waiting order, or
 * moved there as not present, carries none; neither does one left alone in the playing position
 * when the other leaves; and a registration that was passed and then left the queue, was removed
 * or moved to another machine is dropped from the record (review 2026-11-27: the snapshot named a
 * registration that no longer existed, and the server refused every upload until the round
 * ended). So a round without a pass is never told as one.
 */
/**
 * However a fixed pair ended (released, one of the two left, was moved or changed its
 * preference), each of the two counts its own missed turns again from here on.
 */
internal fun QueueEngineState.withOwnSkippedTurnsOutsidePairs(): QueueEngineState {
    fun stale(registration: Registration) =
        registration.fixedPartnerKey == null && registration.skippedTurnsCarriedFromPartner != 0
    if (queues.values.none { queue -> queue.allRegistrations.any(::stale) }) return this
    return copy(
        queues = queues.mapValues { (_, queue) ->
            if (queue.allRegistrations.none(::stale)) {
                queue
            } else {
                queue.copy(
                    playing = queue.playing.map(Registration::withOwnSkippedTurnsOutsideAPair),
                    waiting = queue.waiting.map(Registration::withOwnSkippedTurnsOutsideAPair)
                )
            }
        }
    )
}

internal fun QueueEngineState.withoutStalePassRecords(): QueueEngineState {
    fun MachineQueue.cleaned(): MachineQueue {
        if (allRegistrations.none { it.passedOverKeys.isNotEmpty() }) return this
        val waitingKeys = waiting.mapTo(mutableSetOf(), Registration::key)
        val keepsRecords = playing.size >= 2
        return copy(
            playing = playing.map { registration ->
                val kept = if (keepsRecords) registration.passedOverKeys.filter { it in waitingKeys } else emptyList()
                if (kept == registration.passedOverKeys) registration else registration.copy(passedOverKeys = kept)
            },
            waiting = waiting.map { if (it.passedOverKeys.isEmpty()) it else it.copy(passedOverKeys = emptyList()) }
        )
    }
    if (queues.values.none { queue -> queue.allRegistrations.any { it.passedOverKeys.isNotEmpty() } }) return this
    val cleaned = queues.mapValues { (_, queue) -> queue.cleaned() }
    return if (cleaned == queues) this else copy(queues = cleaned)
}

sealed interface QueueActionExecution {
    data class Applied(
        val state: QueueEngineState,
        val impact: QueueActionImpact
    ) : QueueActionExecution

    data class Rejected(val failure: QueueActionFailure) : QueueActionExecution
}

object QueueEngine {
    fun execute(
        state: QueueEngineState,
        action: QueueAction,
        context: QueueActionContext = QueueActionContext()
    ): QueueActionExecution = plan(state, action, context).applyTo(state, context.policy)

    fun plan(
        state: QueueEngineState,
        action: QueueAction,
        context: QueueActionContext = QueueActionContext()
    ): QueueActionPlan {
        val prerequisiteFailure = validatePrerequisites(state, action, context)
        if (prerequisiteFailure != null) {
            return rejectedPlan(state, action, context, prerequisiteFailure)
        }

        val legacyApplied = applyLegacyBehavior(state, action, context)
        if (legacyApplied.state == state) {
            return rejectedPlan(
                state,
                action,
                context,
                QueueActionFailure(QueueActionFailureCode.NO_STATE_CHANGE),
                legacyApplied
            )
        }
        val applied = legacyApplied.copy(
            state = settlePairedMachines(legacyApplied.state, action, state, context)
                .withoutStalePassRecords()
                .withOwnSkippedTurnsOutsidePairs()
        )
        val resultState = applied.state
        if (resultState == state) {
            return rejectedPlan(
                state,
                action,
                context,
                QueueActionFailure(QueueActionFailureCode.NO_STATE_CHANGE),
                applied
            )
        }

        val existingViolationKinds = state.invariantViolations()
            .mapTo(mutableSetOf(), ::invariantViolationKind)
        val introducedViolations = resultState.invariantViolations().filter {
            invariantViolationKind(it) !in existingViolationKinds
        }
        if (introducedViolations.isNotEmpty()) {
            return rejectedPlan(
                state,
                action,
                context,
                QueueActionFailure(
                    QueueActionFailureCode.INVARIANT_VIOLATION,
                    introducedViolations.joinToString("；")
                ),
                applied
            )
        }

        return QueueActionPlan(
            action = action,
            context = context,
            sourceState = state,
            resultState = resultState,
            impact = calculateImpact(state, resultState, action, applied),
            failure = null
        )
    }

    private data class AppliedBehavior(
        val state: QueueEngineState,
        val roundPreview: NextPlayingPositionPreview? = null,
        val friendPairPlan: FriendPairPlan? = null,
        val requiresAvailabilityConfirmation: Boolean = false,
        val requiresConfirmation: Boolean = false
    )

    private fun rejectedPlan(
        state: QueueEngineState,
        action: QueueAction,
        context: QueueActionContext,
        failure: QueueActionFailure,
        applied: AppliedBehavior = AppliedBehavior(state)
    ): QueueActionPlan = QueueActionPlan(
        action = action,
        context = context,
        sourceState = state,
        resultState = state,
        impact = calculateImpact(state, state, action, applied),
        failure = failure
    )

    private fun validatePrerequisites(
        state: QueueEngineState,
        action: QueueAction,
        context: QueueActionContext
    ): QueueActionFailure? {
        val policy = context.policy
        if (action is QueueAction.ClearRegistrations) {
            if (
                action.machineIds.isEmpty() ||
                action.machineIds.any { state.queue(it) == null }
            ) {
                return QueueActionFailure(QueueActionFailureCode.MACHINE_NOT_FOUND)
            }
            return null
        }
        val queue = state.queue(action.machineId)
            ?: return QueueActionFailure(QueueActionFailureCode.MACHINE_NOT_FOUND)

        if (
            policy.requireOperationalForPlayerActions &&
            action.isPlayerAction &&
            !action.remainsPossibleOnStoppedMachine &&
            context.origin != QueueActionOrigin.SYSTEM &&
            !policy.isOperational(action.machineId)
        ) {
            return QueueActionFailure(QueueActionFailureCode.MACHINE_STOPPED)
        }

        when (action) {
            is QueueAction.AddRegistrations -> {
                if (!policy.registrationOpen) {
                    return QueueActionFailure(QueueActionFailureCode.REGISTRATION_CLOSED)
                }
                if (
                    action.registrations.any(Registration::requiresOnSiteCheckIn) &&
                    !policy.allowOnlineRegistration
                ) {
                    return QueueActionFailure(QueueActionFailureCode.ONLINE_REGISTRATION_DISABLED)
                }
                if (!policy.isOperational(action.machineId)) {
                    return QueueActionFailure(QueueActionFailureCode.MACHINE_STOPPED)
                }
                if (action.registrations.isEmpty() || action.registrations.any {
                        it.key <= 0 || it.displayId.isBlank()
                    }) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_REGISTRATION)
                }
                if (queue.registrationCount + action.registrations.size > policy.maxRegistrationsPerMachine) {
                    return QueueActionFailure(QueueActionFailureCode.MACHINE_FULL)
                }
                if (
                    policy.machineCapacity(action.machineId) == 1 &&
                    action.registrations.any {
                        it.preference != PlayPreference.SOLO || it.fixedPartnerKey != null
                    }
                ) {
                    return QueueActionFailure(QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY)
                }
                val incomingKeys = action.registrations.map(Registration::key)
                val incomingIds = action.registrations.map { it.displayId.trim().lowercase() }
                val incomingProfileIds = action.registrations.mapNotNull(Registration::playerProfileId)
                val existingKeys = state.allRegistrations.mapTo(mutableSetOf(), Registration::key)
                val existingIds = state.allRegistrations
                    .mapTo(mutableSetOf()) { it.displayId.trim().lowercase() }
                val existingProfileIds = state.allRegistrations
                    .mapNotNullTo(mutableSetOf(), Registration::playerProfileId)
                if (
                    incomingKeys.distinct().size != incomingKeys.size ||
                    incomingIds.distinct().size != incomingIds.size ||
                    incomingProfileIds.distinct().size != incomingProfileIds.size ||
                    incomingKeys.any { it in existingKeys } ||
                    incomingIds.any { it in existingIds } ||
                    action.registrations.any {
                        it.playerProfileId != null && it.playerProfileId in existingProfileIds
                    }
                ) {
                    return QueueActionFailure(QueueActionFailureCode.DUPLICATE_REGISTRATION)
                }
            }

            is QueueAction.DeferOneRound -> {
                if (!policy.allowDeferOneRound) {
                    return QueueActionFailure(QueueActionFailureCode.DEFER_ONE_ROUND_DISABLED)
                }
                registrationFailure(queue, action.registrationKey)?.let { return it }
                playingPositionFailure(queue, action.registrationKey)?.let { return it }
                val registration = queue.allRegistrations.first { it.key == action.registrationKey }
                if (registration.requiresOnSiteCheckIn) {
                    return QueueActionFailure(QueueActionFailureCode.PENDING_CHECK_IN)
                }
                if (registration.absenceStatus != QueueAbsenceStatus.NONE) {
                    return QueueActionFailure(QueueActionFailureCode.WRONG_ABSENCE_STATE)
                }
                postponeFailure(queue, action.registrationKey, context)?.let { return it }
                if (queue.fixedGroupKeys(action.registrationKey).any { key ->
                        queue.allRegistrations.any { it.key == key && it.deferOneRoundUsed }
                    }) {
                    return QueueActionFailure(QueueActionFailureCode.DEFER_ONE_ROUND_ALREADY_USED)
                }
            }

            is QueueAction.SkipOneTurn -> {
                registrationFailure(queue, action.registrationKey)?.let { return it }
                playingPositionFailure(queue, action.registrationKey)?.let { return it }
                val registration = queue.allRegistrations.first { it.key == action.registrationKey }
                if (registration.requiresOnSiteCheckIn) {
                    return QueueActionFailure(QueueActionFailureCode.PENDING_CHECK_IN)
                }
                if (registration.absenceStatus != QueueAbsenceStatus.NONE) {
                    return QueueActionFailure(QueueActionFailureCode.WRONG_ABSENCE_STATE)
                }
                postponeFailure(queue, action.registrationKey, context)?.let { return it }
            }

            is QueueAction.CancelSkipOneTurn -> {
                registrationFailure(queue, action.registrationKey)?.let { return it }
                if (queue.allRegistrations.first { it.key == action.registrationKey }.absenceStatus !=
                    QueueAbsenceStatus.SKIP_ONE_TURN
                ) {
                    return QueueActionFailure(QueueActionFailureCode.WRONG_ABSENCE_STATE)
                }
            }

            is QueueAction.CancelDeferOneRound -> {
                registrationFailure(queue, action.registrationKey)?.let { return it }
                if (queue.allRegistrations.first { it.key == action.registrationKey }.absenceStatus !=
                    QueueAbsenceStatus.DEFER_ONE_ROUND
                ) {
                    return QueueActionFailure(QueueActionFailureCode.WRONG_ABSENCE_STATE)
                }
            }

            is QueueAction.TemporarilyLeave -> {
                if (!policy.allowTemporaryLeave) {
                    return QueueActionFailure(QueueActionFailureCode.TEMPORARY_LEAVE_DISABLED)
                }
                registrationFailure(queue, action.registrationKey)?.let { return it }
                playingPositionFailure(queue, action.registrationKey)?.let { return it }
                val registration = queue.allRegistrations.first { it.key == action.registrationKey }
                if (registration.requiresOnSiteCheckIn) {
                    return QueueActionFailure(QueueActionFailureCode.PENDING_CHECK_IN)
                }
                if (registration.absenceStatus != QueueAbsenceStatus.NONE) {
                    return QueueActionFailure(QueueActionFailureCode.WRONG_ABSENCE_STATE)
                }
                postponeFailure(queue, action.registrationKey, context)?.let { return it }
            }

            is QueueAction.CancelTemporaryLeave -> {
                registrationFailure(queue, action.registrationKey)?.let { return it }
                if (queue.allRegistrations.first { it.key == action.registrationKey }.absenceStatus !=
                    QueueAbsenceStatus.TEMPORARILY_AWAY
                ) {
                    return QueueActionFailure(QueueActionFailureCode.WRONG_ABSENCE_STATE)
                }
            }

            is QueueAction.CheckIn -> {
                registrationFailure(queue, action.registrationKey)?.let { return it }
                if (!queue.allRegistrations.first { it.key == action.registrationKey }.requiresOnSiteCheckIn) {
                    return QueueActionFailure(QueueActionFailureCode.NO_STATE_CHANGE)
                }
            }

            is QueueAction.ChangePreference -> {
                registrationFailure(queue, action.registrationKey)?.let { return it }
                if (policy.machineCapacity(action.machineId) == 1) {
                    return QueueActionFailure(QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY)
                }
                if (queue.allRegistrations.first { it.key == action.registrationKey }.requiresOnSiteCheckIn) {
                    return QueueActionFailure(QueueActionFailureCode.PENDING_CHECK_IN)
                }
            }

            is QueueAction.RenameRegistration -> {
                registrationFailure(queue, action.registrationKey)?.let { return it }
                val registration = queue.allRegistrations.first { it.key == action.registrationKey }
                if (registration.requiresOnSiteCheckIn) {
                    return QueueActionFailure(QueueActionFailureCode.PENDING_CHECK_IN)
                }
                val normalized = action.displayId.trim()
                if (normalized.isBlank()) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_REGISTRATION)
                }
                if (state.allRegistrations.any {
                        it.key != action.registrationKey &&
                            it.displayId.trim().equals(normalized, ignoreCase = true)
                    }) {
                    return QueueActionFailure(QueueActionFailureCode.DUPLICATE_REGISTRATION)
                }
            }

            is QueueAction.TransferRegistrations -> {
                val destination = state.queue(action.destinationMachineId)
                    ?: return QueueActionFailure(QueueActionFailureCode.MACHINE_NOT_FOUND)
                if (action.sourceMachineId == action.destinationMachineId) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_POSITION)
                }
                // A registration may leave a machine that has stopped, but not join one.
                if (!policy.isOperational(action.destinationMachineId)) {
                    return QueueActionFailure(QueueActionFailureCode.MACHINE_STOPPED)
                }
                if (action.registrationKeys.isEmpty() || action.registrationKeys.any { key ->
                        queue.allRegistrations.none { it.key == key }
                    }) {
                    return QueueActionFailure(QueueActionFailureCode.REGISTRATION_NOT_FOUND)
                }
                if (queue.playing.any { it.key in action.registrationKeys }) {
                    return QueueActionFailure(QueueActionFailureCode.PLAYING_POSITION_NOT_ALLOWED)
                }
                if (queue.allRegistrations.any {
                        it.key in action.registrationKeys && it.requiresOnSiteCheckIn
                    }) {
                    return QueueActionFailure(QueueActionFailureCode.PENDING_CHECK_IN)
                }
                if (
                    destination.registrationCount + action.registrationKeys.size >
                    policy.maxRegistrationsPerMachine
                ) {
                    return QueueActionFailure(QueueActionFailureCode.MACHINE_FULL)
                }
                if (
                    policy.machineCapacity(action.destinationMachineId) == 1 &&
                    queue.allRegistrations.any {
                        it.key in action.registrationKeys && it.fixedPartnerKey != null
                    }
                ) {
                    return QueueActionFailure(QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY)
                }
            }

            is QueueAction.SetAllowsPassing -> {
                registrationFailure(queue, action.registrationKey)?.let { return it }
                if (action.enabled) {
                    if (policy.machineCapacity(action.machineId) == 1) {
                        return QueueActionFailure(QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY)
                    }
                    // 单人游玩 on its own, or a fixed pair (one choice for both).
                    val registration = queue.allRegistrations.first { it.key == action.registrationKey }
                    val inFixedPair = queue.fixedGroupKeys(action.registrationKey).size == 2
                    if (
                        !inFixedPair &&
                        (registration.preference != PlayPreference.SOLO || registration.fixedPartnerKey != null)
                    ) {
                        return QueueActionFailure(QueueActionFailureCode.PASSING_NEEDS_SOLO)
                    }
                }
            }

            is QueueAction.SetPairedMachineQueueing -> {
                registrationFailure(queue, action.registrationKey)?.let { return it }
                if (action.enabled) {
                    if (PairedMachineQueues.activePartner(state, policy, action.machineId) == null) {
                        return QueueActionFailure(
                            QueueActionFailureCode.PAIRED_MACHINE_QUEUE_UNAVAILABLE
                        )
                    }
                    val registration = queue.allRegistrations.first {
                        it.key == action.registrationKey
                    }
                    if (registration.fixedPartnerKey != null) {
                        return QueueActionFailure(QueueActionFailureCode.INVALID_FIXED_PAIR)
                    }
                    // A temporary registration has no profile and no way to be told that it
                    // is about to be arranged to the other machine.
                    if (registration.isTemporary) {
                        return QueueActionFailure(
                            QueueActionFailureCode.PAIRED_MACHINE_QUEUE_NEEDS_PLAYER_PROFILE
                        )
                    }
                }
            }

            is QueueAction.ReturnPlayingToWaitingFront -> {
                if (
                    action.registrationKeys.isEmpty() ||
                    action.registrationKeys.any { key -> queue.playing.none { it.key == key } }
                ) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_POSITION)
                }
            }

            is QueueAction.MoveWaitingRegistrationIntoCurrentRound -> {
                if (policy.machineCapacity(action.machineId) == 1) {
                    return QueueActionFailure(QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY)
                }
                if (
                    queue.playing.size != 1 ||
                    queue.nextWaitingGroup().none { it.key == action.registrationKey } ||
                    nextWaitingGroup(state, policy, action.machineId, context.atMillis)
                        .none { it.key == action.registrationKey }
                ) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_POSITION)
                }
            }

            is QueueAction.AdvanceToWaitingPosition -> {
                val positions = queue.waitingPositions()
                val targetIndex = positions.indexOfFirst { position ->
                    position.size == action.registrationKeys.size &&
                        position.all { it.key in action.registrationKeys }
                }
                if (
                    queue.playing.isEmpty() ||
                    action.registrationKeys.isEmpty() ||
                    targetIndex <= 0 ||
                    positions.getOrNull(targetIndex)?.any { !it.canEnterPlayingPosition } != false
                ) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_POSITION)
                }
            }

            is QueueAction.RemoveRegistrations -> {
                if (action.registrationKeys.isEmpty() || action.registrationKeys.any { key ->
                        queue.allRegistrations.none { it.key == key }
                    }) {
                    return QueueActionFailure(QueueActionFailureCode.REGISTRATION_NOT_FOUND)
                }
            }

            is QueueAction.LeaveQueue -> registrationFailure(queue, action.registrationKey)?.let { return it }

            is QueueAction.MarkNoShow -> {
                if (
                    action.resolution in setOf(
                        NoShowResolution.DEFER_ONE_ROUND,
                        NoShowResolution.DEFER_GROUP_ONE_ROUND
                    ) &&
                    !policy.allowDeferOneRound
                ) {
                    return QueueActionFailure(QueueActionFailureCode.DEFER_ONE_ROUND_DISABLED)
                }
                // A waiting registration is only absent when it is really next, as the engine
                // sees it: not while a registration of the other machine takes the round ahead of
                // it, and also when the one ahead of it here is arranged to the other machine.
                val next = nextWaitingGroup(state, policy, action.machineId, context.atMillis)
                    .mapTo(mutableSetOf()) { it.key }
                if (
                    action.registrationKeys.isEmpty() ||
                    action.registrationKeys.any { !queue.canMarkNoShow(it, next) }
                ) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_POSITION)
                }
                if (action.resolution != NoShowResolution.REMOVE) {
                    // A registration the system arranged here goes back to its own machine and
                    // is not settled by the chosen resolution at all.
                    val settledKeys = action.registrationKeys - PairedMachineQueues.returnAbsentArrivals(
                        state = state,
                        policy = policy,
                        machineId = action.machineId,
                        registrationKeys = action.registrationKeys
                    ).returnedKeys
                    // Deferring one of a fixed pair defers both.
                    val concernedKeys = if (action.resolution == NoShowResolution.MOVE_TO_TAIL) {
                        settledKeys
                    } else {
                        settledKeys.flatMapTo(mutableSetOf(), queue::fixedGroupKeys)
                    }
                    if (queue.allRegistrations.any {
                            it.key in concernedKeys && it.mustBeRemovedWhenAbsent
                        }) {
                        return QueueActionFailure(QueueActionFailureCode.NO_SHOW_REMOVAL_ONLY)
                    }
                    // Settling a no-show by deferring is 暂缓一次 too: not twice before playing.
                    if (
                        action.resolution != NoShowResolution.MOVE_TO_TAIL &&
                        queue.allRegistrations.any { it.key in concernedKeys && it.deferOneRoundUsed }
                    ) {
                        return QueueActionFailure(QueueActionFailureCode.DEFER_ONE_ROUND_ALREADY_USED)
                    }
                }
            }

            is QueueAction.CreateFixedPair -> {
                if (policy.machineCapacity(action.machineId) == 1) {
                    return QueueActionFailure(QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY)
                }
                if (
                    queue.waiting.any {
                        (it.key == action.firstRegistrationKey || it.key == action.secondRegistrationKey) &&
                            it.absenceStatus != QueueAbsenceStatus.NONE
                    }
                ) {
                    return QueueActionFailure(QueueActionFailureCode.FIXED_PAIR_WHILE_POSTPONED)
                }
                val pairPlan = action.expectedPlan ?: queue.planFriendPair(
                    action.firstRegistrationKey,
                    action.secondRegistrationKey
                ) ?: return QueueActionFailure(QueueActionFailureCode.INVALID_FIXED_PAIR)
                val requestedKeys = setOf(
                    action.firstRegistrationKey,
                    action.secondRegistrationKey
                )
                val plannedKeys = setOf(
                    pairPlan.firstRegistration.key,
                    pairPlan.secondRegistration.key
                )
                if (requestedKeys.size != 2 || plannedKeys != requestedKeys) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_FIXED_PAIR)
                }
                if (!registrationsHaveSameQueueState(pairPlan.originalWaiting, queue.waiting)) {
                    return QueueActionFailure(QueueActionFailureCode.STALE_STATE)
                }
                if (pairPlan.delayedOtherRegistrations.isNotEmpty()) {
                    return QueueActionFailure(QueueActionFailureCode.WOULD_DELAY_OTHER_REGISTRATIONS)
                }
            }

            is QueueAction.CreateFixedPairWithRegistration -> {
                if (policy.machineCapacity(action.machineId) == 1) {
                    return QueueActionFailure(QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY)
                }
                if (!policy.registrationOpen) {
                    return QueueActionFailure(QueueActionFailureCode.REGISTRATION_CLOSED)
                }
                registrationFailure(queue, action.registrationKey)?.let { return it }
                val target = queue.waiting.firstOrNull { it.key == action.registrationKey }
                    ?: return QueueActionFailure(QueueActionFailureCode.INVALID_FIXED_PAIR)
                if (target.absenceStatus != QueueAbsenceStatus.NONE) {
                    return QueueActionFailure(QueueActionFailureCode.FIXED_PAIR_WHILE_POSTPONED)
                }
                if (
                    target.requiresOnSiteCheckIn ||
                    action.friend.key <= 0 ||
                    action.friend.displayId.isBlank() ||
                    action.friend.requiresOnSiteCheckIn
                ) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_FIXED_PAIR)
                }
                if (queue.registrationCount >= policy.maxRegistrationsPerMachine) {
                    return QueueActionFailure(QueueActionFailureCode.MACHINE_FULL)
                }
                if (state.allRegistrations.any {
                        it.key == action.friend.key ||
                            it.displayId.trim().equals(action.friend.displayId.trim(), ignoreCase = true) ||
                            action.friend.playerProfileId != null &&
                            it.playerProfileId == action.friend.playerProfileId
                    }) {
                    return QueueActionFailure(QueueActionFailureCode.DUPLICATE_REGISTRATION)
                }
            }

            is QueueAction.ClaimRegistration -> {
                registrationFailure(queue, action.registrationKey)?.let { return it }
                val registration = queue.allRegistrations.first { it.key == action.registrationKey }
                if (registration.requiresOnSiteCheckIn) {
                    return QueueActionFailure(QueueActionFailureCode.PENDING_CHECK_IN)
                }
            }

            is QueueAction.ClaimWithPlayerProfile -> {
                registrationFailure(queue, action.registrationKey)?.let { return it }
                val registration = queue.allRegistrations.first { it.key == action.registrationKey }
                if (
                    policy.machineCapacity(action.machineId) == 1 &&
                    action.preferenceOverride?.let { it != PlayPreference.SOLO } == true
                ) {
                    return QueueActionFailure(QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY)
                }
                if (
                    !registration.isTemporary ||
                    registration.requiresOnSiteCheckIn ||
                    action.playerProfileId.isBlank() ||
                    action.nickname.isBlank()
                ) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_REGISTRATION)
                }
                if (state.allRegistrations.any {
                        it.key != action.registrationKey && (
                            it.playerProfileId == action.playerProfileId ||
                                it.displayId.trim().equals(action.nickname.trim(), ignoreCase = true)
                            )
                    }) {
                    return QueueActionFailure(QueueActionFailureCode.DUPLICATE_REGISTRATION)
                }
            }

            is QueueAction.SyncPlayerProfileDetails -> {
                if (action.playerProfileId.isBlank() || action.nickname.isBlank()) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_REGISTRATION)
                }
                if (state.allRegistrations.any {
                        it.playerProfileId != action.playerProfileId &&
                            it.displayId.trim().equals(action.nickname.trim(), ignoreCase = true)
                    }) {
                    return QueueActionFailure(QueueActionFailureCode.DUPLICATE_REGISTRATION)
                }
            }

            is QueueAction.MoveWaitingPosition -> {
                val positionCount = queue.waitingPositions().size
                if (
                    action.sourceIndex !in 0 until positionCount ||
                    action.destinationIndex !in 0 until positionCount ||
                    action.sourceIndex == action.destinationIndex
                ) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_POSITION)
                }
            }

            is QueueAction.ReplaceOrder -> {
                val currentKeys = queue.allRegistrations.map(Registration::key)
                val proposedKeys = action.registrations.map(Registration::key)
                if (
                    proposedKeys.size != currentKeys.size ||
                    proposedKeys.toSet() != currentKeys.toSet() ||
                    proposedKeys == currentKeys ||
                    queue.playing.isNotEmpty() &&
                    proposedKeys.take(queue.playing.size) != queue.playing.map(Registration::key)
                ) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_POSITION)
                }
            }

            is QueueAction.RestoreSnapshot -> {
                if (queue != action.expectedCurrentQueue) {
                    return QueueActionFailure(QueueActionFailureCode.STALE_STATE)
                }
                if (action.machineId in action.alsoRestoredQueues) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_POSITION)
                }
                action.alsoRestoredQueues.forEach { (machineId, restored) ->
                    val current = state.queue(machineId)
                        ?: return QueueActionFailure(QueueActionFailureCode.MACHINE_NOT_FOUND)
                    if (current != restored.expectedCurrentQueue) {
                        return QueueActionFailure(QueueActionFailureCode.STALE_STATE)
                    }
                }
                val restoredRegistrations = action.restoredQueue
                    .removeAll(action.excludedRegistrationKeys)
                    .allRegistrations +
                    action.alsoRestoredQueues.values.flatMap { it.restoredQueue.allRegistrations }
                if (restoredRegistrations.map(Registration::key).distinct().size !=
                    restoredRegistrations.size
                ) {
                    return QueueActionFailure(QueueActionFailureCode.DUPLICATE_REGISTRATION)
                }
                val otherRegistrations = state.queues.asSequence()
                    .filter { (machineId, _) ->
                        machineId != action.machineId && machineId !in action.alsoRestoredQueues
                    }
                    .flatMap { (_, otherQueue) -> otherQueue.allRegistrations.asSequence() }
                    .toList()
                if (restoredRegistrations.any { restored ->
                        otherRegistrations.any { other ->
                            restored.key == other.key ||
                                restored.displayId.trim().equals(
                                    other.displayId.trim(),
                                    ignoreCase = true
                                ) ||
                                restored.playerProfileId?.let { profileId ->
                                    other.playerProfileId == profileId
                                } == true
                        }
                    }) {
                    return QueueActionFailure(QueueActionFailureCode.DUPLICATE_REGISTRATION)
                }
            }

            else -> Unit
        }
        return null
    }

    /**
     * Whether the empty playing position of [queue] is a state somebody chose: a registration
     * waits that could enter it, yet nobody has. That is how 仅结束本轮 and a no-show without
     * a next round leave a machine. A registration that joins then, however it joins, waits
     * like the others; the next round starts when the operator says so (user manual: “不会
     * 自动开始下一轮”). [joiningKeys] are registrations of the joining player already staged in
     * the queue, which do not count as waiting before it.
     */
    private fun playingPositionIsHeldEmpty(queue: MachineQueue, joiningKeys: Set<Int>): Boolean {
        if (queue.playing.isNotEmpty()) return false
        val others = if (joiningKeys.isEmpty()) {
            queue
        } else {
            queue.copy(waiting = queue.waiting.filterNot { it.key in joiningKeys })
        }
        return others.firstAvailableWaitingPositionIndex() != null
    }

    private fun registrationFailure(
        queue: MachineQueue,
        registrationKey: Int
    ): QueueActionFailure? = if (queue.allRegistrations.none { it.key == registrationKey }) {
        QueueActionFailure(QueueActionFailureCode.REGISTRATION_NOT_FOUND)
    } else {
        null
    }

    /**
     * Deferring and leaving temporarily are for registrations that wait. One that is in the
     * playing position has to be taken back to the front of the waiting order first; otherwise
     * a player could step out late in a round and be first in line for the next one.
     */
    private fun playingPositionFailure(
        queue: MachineQueue,
        registrationKey: Int
    ): QueueActionFailure? = if (
        queue.fixedGroupKeys(registrationKey).any { key -> queue.playing.any { it.key == key } }
    ) {
        QueueActionFailure(QueueActionFailureCode.PLAYING_POSITION_NOT_ALLOWED)
    } else {
        null
    }

    /**
     * A checked-in online registration that has not played yet can only be removed when it is
     * absent (see [Registration.mustBeRemovedWhenAbsent]); postponing it in any way would sidestep
     * that, so only the management app may (maintainer, 2026-11-25).
     */
    private fun postponeFailure(
        queue: MachineQueue,
        registrationKey: Int,
        context: QueueActionContext
    ): QueueActionFailure? =
        if (
            context.origin != QueueActionOrigin.MANAGEMENT_APP &&
            queue.fixedGroupKeys(registrationKey).any { key ->
                queue.allRegistrations.any { it.key == key && it.mustBeRemovedWhenAbsent }
            }
        ) {
            QueueActionFailure(QueueActionFailureCode.ONLINE_REGISTRATION_NOT_PLAYED)
        } else {
            null
        }

    private val QueueAction.isPlayerAction: Boolean
        get() = this !is QueueAction.RestartPlayingTimer &&
            this !is QueueAction.RestartPendingCheckInTimers &&
            this !is QueueAction.RestartMachineTimers &&
            this !is QueueAction.RemoveExpiredOnlineRegistrations &&
            this !is QueueAction.SyncPlayerProfileDetails &&
            this !is QueueAction.ClearRegistrations &&
            this !is QueueAction.RestoreSnapshot

    /**
     * A stopped machine keeps its registrations, and nobody is to be held at a machine that
     * cannot be played on: leaving the queue and moving to another machine stay possible.
     */
    private val QueueAction.remainsPossibleOnStoppedMachine: Boolean
        get() = this is QueueAction.RemoveRegistrations ||
            this is QueueAction.LeaveQueue ||
            this is QueueAction.TransferRegistrations

    private fun invariantViolationKind(violation: String): String {
        val withoutMachinePrefix = if (violation.startsWith("机台 ")) {
            violation.substringAfter("：", violation)
        } else {
            violation
        }
        return withoutMachinePrefix.substringBefore("：")
    }

    private fun applyLegacyBehavior(
        state: QueueEngineState,
        action: QueueAction,
        context: QueueActionContext
    ): AppliedBehavior {
        if (action is QueueAction.ClearRegistrations) {
            return AppliedBehavior(
                state.copy(
                    queues = state.queues.mapValues { (machineId, queue) ->
                        if (machineId in action.machineIds) MachineQueue() else queue
                    }
                ),
                requiresConfirmation = true
            )
        }
        val queue = state.queue(action.machineId) ?: return AppliedBehavior(state)
        var roundPreview: NextPlayingPositionPreview? = null
        var friendPairPlan: FriendPairPlan? = null
        var availabilityConfirmation = false
        var confirmation = false
        var stagedRound: PairedMachineQueues.StagedRound? = null
        // The state the outcome is written into; an action may first change another machine.
        var workingState = state

        // Whenever a waiting group may enter the playing position, the places held here by
        // registrations of the paired machine take part in the choice.
        fun forRound(
            base: MachineQueue,
            frontReturnedKeys: Set<Int> = emptySet()
        ): MachineQueue {
            val staged = PairedMachineQueues.stageRound(
                state = workingState,
                policy = context.policy,
                machineId = action.machineId,
                baseQueue = base,
                frontReturnedKeys = frontReturnedKeys,
                atMillis = context.atMillis
            )
            stagedRound = staged
            return staged?.queue ?: base
        }

        val updatedQueue = when (action) {
            is QueueAction.AddRegistrations -> when (action.placement) {
                RegistrationPlacement.AUTO_ADVANCE -> {
                    val joined = action.registrations.fold(queue) { current, registration ->
                        current.stageWaiting(registration)
                    }
                    if (joined == queue) {
                        queue
                    } else {
                        RoundPlanner.advance(forRound(joined), emptySet()).execute(context.atMillis)
                    }
                }
                RegistrationPlacement.WAITING_TAIL -> queue.receiveAtWaitingTail(action.registrations)
                RegistrationPlacement.STAGED_WAITING -> action.registrations.fold(queue) { current, registration ->
                    current.stageWaiting(registration)
                }
                RegistrationPlacement.ADVANCE_IF_UNAMBIGUOUS -> {
                    val staged = queue.receiveAtWaitingTail(action.registrations)
                    if (playingPositionIsHeldEmpty(queue, emptySet())) {
                        // Joining never starts a round that was held back on purpose.
                        staged
                    } else {
                        val plan = RoundPlanner.enterPlayingPosition(forRound(staged))
                        roundPreview = plan.preview
                        availabilityConfirmation = staged.playing.isEmpty() &&
                            plan.preview?.changedByAvailability == true
                        if (staged.playing.isEmpty() && !availabilityConfirmation) {
                            plan.execute(context.atMillis)
                        } else {
                            staged
                        }
                    }
                }
            }

            is QueueAction.FinishRound -> RoundPlanner.finishRound(forRound(queue)).let { plan ->
                roundPreview = plan.preview
                confirmation = true
                plan.execute(context.atMillis)
            }
            is QueueAction.EndRoundOnly -> RoundPlanner.endRoundOnly(queue).let { plan ->
                roundPreview = plan.preview
                confirmation = true
                plan.execute(context.atMillis)
            }
            is QueueAction.RemoveCurrentRoundAndStartNext ->
                RoundPlanner.removeCurrentRoundAndStartNext(forRound(queue)).let { plan ->
                    roundPreview = plan.preview
                    confirmation = true
                    plan.execute(context.atMillis)
                }
            is QueueAction.EnterPlayingPosition -> RoundPlanner.enterPlayingPosition(forRound(queue)).let { plan ->
                roundPreview = plan.preview
                confirmation = plan.preview?.changedByAvailability == true
                plan.execute(context.atMillis)
            }
            is QueueAction.RestartPlayingTimer -> queue.restartPlayingTimer(context.atMillis)
            is QueueAction.RestartPendingCheckInTimers ->
                queue.restartPendingCheckInTimers(context.atMillis)
            is QueueAction.RestartMachineTimers -> queue
                .restartPlayingTimer(context.atMillis)
                .restartPendingCheckInTimers(context.atMillis)
            is QueueAction.ReturnPlayingToWaitingFront ->
                queue.returnPlayingRegistrationsToWaitingFront(action.registrationKeys)
            is QueueAction.MoveWaitingRegistrationIntoCurrentRound ->
                queue.moveFirstWaitingRegistrationIntoCurrentRound(action.registrationKey)
            is QueueAction.AdvanceToWaitingPosition ->
                queue.advanceToWaitingPosition(action.registrationKeys, context.atMillis)
            is QueueAction.DeferOneRound -> queue.deferOneRound(action.registrationKey)
            is QueueAction.CancelDeferOneRound -> queue.cancelDeferOneRound(action.registrationKey)
            is QueueAction.TemporarilyLeave -> queue.temporarilyLeave(action.registrationKey)
            is QueueAction.CancelTemporaryLeave -> queue.cancelTemporaryLeave(action.registrationKey)
            is QueueAction.SkipOneTurn -> queue.skipOneTurn(action.registrationKey)
            is QueueAction.CancelSkipOneTurn -> queue.cancelSkipOneTurn(action.registrationKey)
            is QueueAction.ChangePreference ->
                queue.changePreference(action.registrationKey, action.preference)
            is QueueAction.CreateFixedPair -> {
                val plan = action.expectedPlan ?: queue.planFriendPair(
                    action.firstRegistrationKey,
                    action.secondRegistrationKey
                )
                friendPairPlan = plan
                confirmation = plan?.movedBackRegistrations?.isNotEmpty() == true
                val paired = (plan?.let(queue::applyFriendPair) ?: queue).let { formed ->
                    if (action.allowsPassing && formed != queue) {
                        formed.setAllowsPassing(action.firstRegistrationKey, true)
                    } else {
                        formed
                    }
                }
                if (
                    action.advanceWhenPlayingEmpty && paired.playing.isEmpty() &&
                    !playingPositionIsHeldEmpty(
                        queue,
                        setOf(action.firstRegistrationKey, action.secondRegistrationKey)
                    )
                ) {
                    val roundPlan = RoundPlanner.enterPlayingPosition(forRound(paired))
                    roundPreview = roundPlan.preview
                    availabilityConfirmation = roundPlan.preview?.changedByAvailability == true
                    if (availabilityConfirmation) paired else roundPlan.execute(context.atMillis)
                } else {
                    paired
                }
            }
            is QueueAction.CreateFixedPairWithRegistration -> {
                val paired = queue.createFriendPair(action.registrationKey, action.friend).let { formed ->
                    if (action.allowsPassing && formed != queue) {
                        formed.setAllowsPassing(action.registrationKey, true)
                    } else {
                        formed
                    }
                }
                if (
                    action.advanceWhenPlayingEmpty && paired.playing.isEmpty() &&
                    !playingPositionIsHeldEmpty(queue, setOf(action.registrationKey))
                ) {
                    val roundPlan = RoundPlanner.enterPlayingPosition(forRound(paired))
                    roundPreview = roundPlan.preview
                    availabilityConfirmation = roundPlan.preview?.changedByAvailability == true
                    if (availabilityConfirmation) paired else roundPlan.execute(context.atMillis)
                } else {
                    paired
                }
            }
            is QueueAction.RenameRegistration ->
                queue.rename(action.registrationKey, action.displayId)
            is QueueAction.SyncPlayerProfileDetails -> queue.syncPlayerProfileDetails(
                action.playerProfileId,
                action.nickname,
                action.gender
            )
            is QueueAction.ClaimRegistration -> queue.claim(action.registrationKey)
            is QueueAction.ClaimWithPlayerProfile -> queue.claimWithPlayerProfile(
                registrationKey = action.registrationKey,
                playerProfileId = action.playerProfileId,
                playerNickname = action.nickname,
                gender = action.gender,
                preferenceOverride = if (context.policy.machineCapacity(action.machineId) == 1) {
                    PlayPreference.SOLO
                } else {
                    action.preferenceOverride
                }
            )
            is QueueAction.MarkNoShow -> {
                // A registration the system arranged here and that did not show up is not
                // treated as a no-show of its own making: it goes back to where it came from.
                val returned = PairedMachineQueues.returnAbsentArrivals(
                    state = state,
                    policy = context.policy,
                    machineId = action.machineId,
                    registrationKeys = action.registrationKeys
                )
                workingState = returned.state
                val noShowQueue = returned.state.queue(action.machineId) ?: queue
                val noShowKeys = action.registrationKeys - returned.returnedKeys
                // The next group as validated, so that the queue does not refuse a waiting
                // registration that is next only because one ahead of it is arranged away.
                val nextGroupKeys = nextWaitingGroup(state, context.policy, action.machineId, context.atMillis)
                    .mapTo(mutableSetOf()) { it.key }
                val staged = if (noShowKeys.isEmpty()) noShowQueue else when (action.resolution) {
                    NoShowResolution.DEFER_ONE_ROUND -> noShowQueue.markNoShowDeferOneRound(
                        noShowKeys.singleOrNull() ?: return AppliedBehavior(state),
                        startNextWhenPlayingBecomesEmpty = false,
                        atMillis = context.atMillis,
                        nextGroupKeys = nextGroupKeys
                    )
                    NoShowResolution.DEFER_GROUP_ONE_ROUND -> noShowQueue.markNoShowGroupDeferOneRound(
                        noShowKeys,
                        startNextWhenPlayingBecomesEmpty = false,
                        atMillis = context.atMillis,
                        nextGroupKeys = nextGroupKeys
                    )
                    NoShowResolution.MOVE_TO_TAIL -> noShowQueue.markNoShowMoveToEnd(
                        noShowKeys,
                        startNextWhenPlayingBecomesEmpty = false,
                        atMillis = context.atMillis,
                        nextGroupKeys = nextGroupKeys
                    )
                    NoShowResolution.REMOVE -> noShowQueue.markNoShowAndRemove(
                        noShowKeys,
                        startNextWhenPlayingBecomesEmpty = false,
                        atMillis = context.atMillis,
                        nextGroupKeys = nextGroupKeys
                    )
                }
                if (action.startNextWhenPlayingBecomesEmpty && staged.playing.isEmpty()) {
                    val skippedThisOpportunity = if (
                        action.resolution == NoShowResolution.MOVE_TO_TAIL
                    ) {
                        noShowKeys
                    } else {
                        emptySet()
                    }
                    val returnedToFront = if (
                        noShowKeys.isNotEmpty() && (
                            action.resolution == NoShowResolution.DEFER_ONE_ROUND ||
                                action.resolution == NoShowResolution.DEFER_GROUP_ONE_ROUND
                            )
                    ) {
                        noShowQueue.playing.mapTo(mutableSetOf()) { it.key }
                    } else {
                        emptySet()
                    }
                    RoundPlanner.advance(
                        forRound(staged, returnedToFront),
                        skippedThisOpportunity
                    ).let { plan ->
                        roundPreview = plan.preview
                        plan.execute(context.atMillis)
                    }
                } else {
                    staged
                }
            }
            is QueueAction.CheckIn -> queue.checkIn(action.registrationKey)
            is QueueAction.SetPairedMachineQueueing ->
                queue.setPairedMachineQueueing(action.registrationKey, action.enabled)
            is QueueAction.SetAllowsPassing -> queue.setAllowsPassing(action.registrationKey, action.enabled)
            is QueueAction.RemoveRegistrations -> queue.removeAll(action.registrationKeys)
            is QueueAction.LeaveQueue -> queue.removeAll(setOf(action.registrationKey))
            is QueueAction.RemoveExpiredOnlineRegistrations ->
                queue.removeExpiredOnlineRegistrations(context.atMillis)
            is QueueAction.MoveWaitingPosition ->
                queue.moveWaitingPosition(action.sourceIndex, action.destinationIndex)
            is QueueAction.ReplaceOrder -> queue.replaceOrder(action.registrations)
            is QueueAction.TransferRegistrations -> return applyTransfer(
                state,
                action,
                context.policy
            )
            is QueueAction.ClearRegistrations -> error("handled before queue lookup")
            is QueueAction.RestoreSnapshot -> {
                val restoredQueues = buildMap {
                    putAll(state.queues)
                    put(
                        action.machineId,
                        normalizeQueueForCapacity(
                            action.restoredQueue.removeAll(action.excludedRegistrationKeys),
                            context.policy.machineCapacity(action.machineId)
                        )
                    )
                    action.alsoRestoredQueues.forEach { (machineId, restored) ->
                        put(
                            machineId,
                            normalizeQueueForCapacity(
                                restored.restoredQueue,
                                context.policy.machineCapacity(machineId)
                            )
                        )
                    }
                }
                return AppliedBehavior(
                    state.copy(
                        queues = restoredQueues,
                        pairedMachineOrders = action.restoredPairedMachineOrders
                            ?.filterKeys { it in restoredQueues }
                            ?: state.pairedMachineOrders,
                        pairedArrangements = action.restoredPairedArrangements
                            ?: state.pairedArrangements
                    )
                )
            }
        }
        val round = stagedRound
        val updatedState = if (
            round != null && PairedMachineQueues.isStagedResult(round, updatedQueue)
        ) {
            PairedMachineQueues.settleRound(workingState, context.policy, round, updatedQueue)
        } else {
            workingState.replace(action.machineId, updatedQueue)
        }
        return AppliedBehavior(
            state = updatedState,
            roundPreview = roundPreview,
            friendPairPlan = friendPairPlan,
            requiresAvailabilityConfirmation = availabilityConfirmation,
            requiresConfirmation = confirmation
        )
    }

    /**
     * Who enters the playing position of [machineId] next, counting the registrations of the
     * paired machine that may be arranged there now. On a machine without a usable pair this
     * is [MachineQueue.nextWaitingGroup]. Everything that asks “who is next” for a rule (who can
     * be marked as not present, who can be moved into the current round) asks here, so that it
     * agrees with what ending the round does (review 2026-11-27).
     */
    fun nextWaitingGroup(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        machineId: String,
        atMillis: Long = System.currentTimeMillis()
    ): List<Registration> {
        val queue = state.queue(machineId) ?: return emptyList()
        val staged = PairedMachineQueues.stageRound(state, policy, machineId, queue, atMillis = atMillis)
        return (staged?.queue ?: queue).nextWaitingGroup()
    }

    /**
     * Re-derives the places held across paired machines after settings changed outside a queue
     * action, for example a pair was configured or removed, or a machine resumed operation.
     */
    fun settlePairedMachines(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        atMillis: Long = System.currentTimeMillis()
    ): QueueEngineState = PairedMachineQueues.fillIdleMachines(
        PairedMachineQueues.normalize(state, policy),
        policy,
        atMillis
    )

    /**
     * Brings the previews that announce an arrangement up to date with the clock: which
     * registrations have one, since when, and whose player is to be told now. Call it after
     * every change of the queues and regularly while time passes; it also lets a machine that
     * stands empty take a registration whose announcement has run long enough. Without
     * [QueueEnginePolicy.pairedArrangementRules] it changes nothing.
     */
    fun refreshPairedArrangements(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        durations: Map<String, PairedMachineForecast.RoundDurations>,
        nowMillis: Long
    ): PairedArrangementRefresh =
        PairedArrangementAnnouncements.refresh(state, policy, durations, nowMillis)

    /**
     * What the player of a waiting registration should be told about a coming arrangement;
     * null while no preview announces one.
     */
    fun pairedArrangementOutlook(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        registrationKey: Int
    ): PairedArrangementOutlook? {
        val rules = policy.pairedArrangementRules ?: return null
        val record = state.pairedArrangements[registrationKey] ?: return null
        val since = record.previewSinceMillis ?: return null
        return PairedArrangementOutlook(
            notBeforeMillis = (since + rules.leadMillis)
                .takeIf { record.previewPausedAtMillis == null }
        )
    }

    /**
     * Of [registrationKeys] in the playing position of [machineId], those that the system
     * arranged there and that go back to the machine they came from when they are marked as not
     * having shown up, with that machine. The ordinary no-show rules apply to all others.
     */
    fun absentArrivalOrigins(
        state: QueueEngineState,
        policy: QueueEnginePolicy,
        machineId: String,
        registrationKeys: Set<Int>
    ): Map<Int, String> {
        val returned = PairedMachineQueues
            .returnAbsentArrivals(state, policy, machineId, registrationKeys)
            .returnedKeys
        return buildMap {
            returned.forEach { key ->
                state.pairedArrangements[key]?.arrangedFromMachineId?.let { put(key, it) }
            }
        }
    }

    /**
     * [records] as they stand after the terminal did not run from [stoppedAtMillis] until
     * [nowMillis]. No preview could be kept up in that time, so it does not count towards the
     * time an arrangement has to be announced for.
     */
    fun pairedArrangementsAfterDowntime(
        records: Map<Int, PairedArrangementRecord>,
        stoppedAtMillis: Long,
        nowMillis: Long
    ): Map<Int, PairedArrangementRecord> {
        val downtime = nowMillis - stoppedAtMillis
        if (downtime <= 0L) return records
        return records.mapValues { (_, record) ->
            record.copy(
                previewSinceMillis = record.previewSinceMillis?.plus(downtime),
                previewPausedAtMillis = record.previewPausedAtMillis?.plus(downtime)
            )
        }
    }

    private fun settlePairedMachines(
        applied: QueueEngineState,
        action: QueueAction,
        source: QueueEngineState,
        context: QueueActionContext
    ): QueueEngineState {
        if (
            context.policy.machinePartners.isEmpty() &&
            applied.pairedMachineOrders.isEmpty() &&
            applied.allRegistrations.none(Registration::alsoQueuesAtPairedMachine)
        ) {
            // No pair is configured and nothing refers to one: behave exactly as before.
            return applied
        }
        val sourceQueue = source.queue(action.machineId)
        // Registrations an operator or a rule put back at the very front keep that place ahead
        // of the places held by the other machine.
        val frontReturnedKeys: Set<Int> = when (action) {
            is QueueAction.ReturnPlayingToWaitingFront -> action.registrationKeys
            is QueueAction.MarkNoShow -> if (
                action.resolution == NoShowResolution.DEFER_ONE_ROUND ||
                action.resolution == NoShowResolution.DEFER_GROUP_ONE_ROUND
            ) {
                sourceQueue?.playing?.mapTo(mutableSetOf()) { it.key } ?: emptySet()
            } else {
                emptySet()
            }
            else -> emptySet()
        }
        // Where a registration was arranged from is remembered for the round it was arranged
        // into. One that ends that round and enters the playing position again at once, as
        // the only registration of a machine does, starts a round of its own choosing.
        val endsTheRound = action is QueueAction.FinishRound ||
            action is QueueAction.RemoveCurrentRoundAndStartNext ||
            action is QueueAction.EndRoundOnly
        val playedOutKeys = if (endsTheRound && applied.pairedArrangements.isNotEmpty()) {
            sourceQueue?.playing.orEmpty()
                .map(Registration::key)
                .filter { applied.pairedArrangements[it]?.arrangedFromMachineId != null }
        } else {
            emptyList()
        }
        val current = if (playedOutKeys.isEmpty()) {
            applied
        } else {
            applied.copy(pairedArrangements = applied.pairedArrangements - playedOutKeys.toSet())
        }
        // Registrations that went behind others, other than by an operator's reordering (that
        // keeps the number of registrations ahead of every place), no longer stand ahead of
        // the places held by the other machine.
        val reorderedByHand = action is QueueAction.MoveWaitingPosition ||
            action is QueueAction.ReplaceOrder ||
            action is QueueAction.RestoreSnapshot
        val sentBackKeys: Map<String, Set<Int>> = if (reorderedByHand) {
            emptyMap()
        } else {
            buildMap {
                applied.queues.forEach { (machineId, queue) ->
                    val before = source.queue(machineId)?.waiting ?: return@forEach
                    if (before === queue.waiting) return@forEach
                    val overtaken = PairedMachineQueues.overtakenKeys(
                        before.map(Registration::key),
                        queue.waiting.map(Registration::key)
                    )
                    if (overtaken.isNotEmpty()) put(machineId, overtaken)
                }
            }
        }
        val normalized = PairedMachineQueues.normalize(
            state = PairedMachineQueues.giveUpPlacesForThisRound(
                state = current,
                machineId = action.machineId,
                registrationKeys = keysMovedByHand(action, sourceQueue, applied.queue(action.machineId))
            ),
            policy = context.policy,
            frontReturnedMachineId = action.machineId,
            frontReturnedKeys = frontReturnedKeys,
            sentBackKeys = sentBackKeys
        )
        return PairedMachineQueues.fillIdleMachines(normalized, context.policy, context.atMillis)
    }

    /**
     * The registration always counts first: whenever its place in the waiting order is changed
     * by hand, the place it held at the paired machine is given up for this round instead of
     * being carried along. Likewise a waiting registration for which the paired machine is
     * turned on later does not get a place there before it has played. This names the
     * registrations such an action concerns.
     */
    private fun keysMovedByHand(
        action: QueueAction,
        before: MachineQueue?,
        after: MachineQueue?
    ): Set<Int> {
        if (before == null || after == null) return emptySet()
        fun reordered(declared: Set<Int>): Set<Int> {
            val formerOrder = before.waiting.map(Registration::key).filterNot { it in declared }
            val newOrder = after.waiting.map(Registration::key).filterNot { it in declared }
            return declared + PairedMachineQueues.reorderedKeys(formerOrder, newOrder)
        }
        return when (action) {
            is QueueAction.ReturnPlayingToWaitingFront -> action.registrationKeys
            is QueueAction.MoveWaitingPosition -> reordered(
                before.waitingPositions().getOrNull(action.sourceIndex).orEmpty()
                    .mapTo(mutableSetOf(), Registration::key)
            )
            is QueueAction.ReplaceOrder -> reordered(action.movedRegistrationKeys.orEmpty())
            // Turned on for a registration that is already waiting: it only counts from the
            // round after the one it is waiting for. A new registration that is created with
            // it on, and one that is playing right now, start at once.
            is QueueAction.SetPairedMachineQueueing ->
                if (action.enabled) setOf(action.registrationKey) else emptySet()
            else -> emptySet()
        }
    }

    private fun applyTransfer(
        state: QueueEngineState,
        action: QueueAction.TransferRegistrations,
        policy: QueueEnginePolicy
    ): AppliedBehavior {
        val source = state.queue(action.sourceMachineId) ?: return AppliedBehavior(state)
        val destination = state.queue(action.destinationMachineId) ?: return AppliedBehavior(state)
        val registrations = source.allRegistrations
            .filter { it.key in action.registrationKeys }
            .map { registration ->
                if (policy.machineCapacity(action.destinationMachineId) == 1) {
                    // Nobody plays together there, so the choice to let others pass ends.
                    registration.copy(
                        preference = PlayPreference.SOLO,
                        fixedPartnerKey = null,
                        allowsPassing = false
                    )
                } else {
                    registration
                }
            }
        if (registrations.size != action.registrationKeys.size) return AppliedBehavior(state)
        // Moved by hand to the other machine of its pair, a registration keeps being arranged
        // between the two: it joins the tail there and is treated like a registration that has
        // just started to wait. Moved anywhere else, that choice ends.
        val withinPair = PairedMachineQueues.activePartner(state, policy, action.sourceMachineId) ==
            action.destinationMachineId
        val movedRegistrations = registrations.map { registration ->
            when {
                !registration.alsoQueuesAtPairedMachine -> registration
                withinPair -> registration.copy(skipsPairedMachineThisRound = false)
                else -> registration.copy(
                    alsoQueuesAtPairedMachine = false,
                    skipsPairedMachineThisRound = false
                )
            }
        }
        val updatedSource = source.removeAll(action.registrationKeys)
        val updatedDestination = destination.receiveAtWaitingTail(movedRegistrations)
        if (
            updatedSource == source ||
            updatedDestination.registrationCount != destination.registrationCount + registrations.size
        ) {
            return AppliedBehavior(state)
        }
        return AppliedBehavior(
            state.copy(
                queues = state.queues +
                    (action.sourceMachineId to updatedSource) +
                    (action.destinationMachineId to updatedDestination),
                // Whatever position the moved registrations had in an order is forgotten. At
                // the machine they left, a place they hold from now on is a new one and goes
                // to the tail. At the machine they join they wait at the tail themselves;
                // left in its order, the place they held there would have been counted as a
                // registration of that machine and have pushed the places behind it back
                // (review 2026-11-27).
                pairedMachineOrders = state.pairedMachineOrders.mapValues { (_, order) ->
                    order.filterNot { it in action.registrationKeys }
                },
                // A preview announced a move away from the machine the registrations have now
                // left. A move back there has not been announced by anything, so what was
                // remembered about them is forgotten and an announcement starts afresh.
                pairedArrangements = state.pairedArrangements - action.registrationKeys
            ),
            requiresConfirmation = true
        )
    }

    fun normalizeQueueForCapacity(queue: MachineQueue, capacity: Int): MachineQueue {
        if (capacity != 1) return queue
        val normalizeRegistration: (Registration) -> Registration = { registration ->
            registration.copy(
                preference = PlayPreference.SOLO,
                fixedPartnerKey = null,
                allowsPassing = false
            )
        }
        val normalizedPlaying = queue.playing.take(1).map(normalizeRegistration)
        val overflowPlaying = queue.playing.drop(1).map(normalizeRegistration)
        return queue.copy(
            playing = normalizedPlaying,
            waiting = overflowPlaying + queue.waiting.map(normalizeRegistration),
            playingStartedAtMillis = queue.playingStartedAtMillis.takeIf {
                normalizedPlaying.isNotEmpty()
            }
        )
    }

    private data class RegistrationLocation(
        val machineId: String,
        val section: Int,
        val index: Int,
        val registration: Registration
    )

    private fun registrationLocations(state: QueueEngineState): Map<Int, RegistrationLocation> =
        buildMap {
            state.queues.forEach { (machineId, queue) ->
                queue.playing.forEachIndexed { index, registration ->
                    put(registration.key, RegistrationLocation(machineId, 0, index, registration))
                }
                queue.waiting.forEachIndexed { index, registration ->
                    put(registration.key, RegistrationLocation(machineId, 1, index, registration))
                }
            }
        }

    private fun calculateImpact(
        before: QueueEngineState,
        after: QueueEngineState,
        action: QueueAction,
        applied: AppliedBehavior
    ): QueueActionImpact {
        val beforeLocations = registrationLocations(before)
        val afterLocations = registrationLocations(after)
        val beforeKeys = beforeLocations.keys
        val afterKeys = afterLocations.keys
        val added = afterKeys - beforeKeys
        val removed = beforeKeys - afterKeys
        val shared = beforeKeys intersect afterKeys
        val changed = shared.filterTo(mutableSetOf()) { key ->
            beforeLocations.getValue(key).registration != afterLocations.getValue(key).registration
        }
        val moved = shared.filterTo(mutableSetOf()) { key ->
            val old = beforeLocations.getValue(key)
            val new = afterLocations.getValue(key)
            old.machineId != new.machineId || old.section != new.section || old.index != new.index
        }
        val changedMachines = (before.queues.keys + after.queues.keys).filterTo(mutableSetOf()) {
            before.queues[it] != after.queues[it]
        }
        return QueueActionImpact(
            changedMachineIds = changedMachines,
            addedRegistrationKeys = added,
            removedRegistrationKeys = removed,
            changedRegistrationKeys = changed,
            movedRegistrationKeys = moved,
            affectedRegistrationKeys = added + removed + changed + moved,
            roundPreview = applied.roundPreview,
            friendPairPlan = applied.friendPairPlan,
            requiresAvailabilityConfirmation = applied.requiresAvailabilityConfirmation,
            requiresConfirmation = applied.requiresConfirmation
        )
    }
}
