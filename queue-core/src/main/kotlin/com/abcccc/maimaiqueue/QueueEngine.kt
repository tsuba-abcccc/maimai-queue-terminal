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
    STALE_STATE,
    NO_STATE_CHANGE,
    INVARIANT_VIOLATION
}

data class QueueActionFailure(
    val code: QueueActionFailureCode,
    val detail: String? = null
)

data class QueueEnginePolicy(
    val registrationOpen: Boolean = true,
    val allowOnlineRegistration: Boolean = true,
    val allowDeferOneRound: Boolean = true,
    val allowTemporaryLeave: Boolean = true,
    val machineStatuses: Map<String, MachineStatus> = emptyMap(),
    val machineCapacities: Map<String, Int> = emptyMap(),
    val maxRegistrationsPerMachine: Int = 20,
    val requireOperationalForPlayerActions: Boolean = false
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
    val queues: Map<String, MachineQueue>
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
        val advanceWhenPlayingEmpty: Boolean = false
    ) : QueueAction

    data class CreateFixedPairWithRegistration(
        override val machineId: String,
        val registrationKey: Int,
        val friend: Registration,
        val advanceWhenPlayingEmpty: Boolean = false
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

    data class RemoveRegistrations(
        override val machineId: String,
        val registrationKeys: Set<Int>
    ) : QueueAction

    data class RemoveExpiredOnlineRegistrations(override val machineId: String) : QueueAction

    data class MoveWaitingPosition(
        override val machineId: String,
        val sourceIndex: Int,
        val destinationIndex: Int
    ) : QueueAction

    data class ReplaceOrder(
        override val machineId: String,
        val registrations: List<Registration>
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
        val excludedRegistrationKeys: Set<Int> = emptySet()
    ) : QueueAction
}

private val QueueAction.requiredUnchangedMachineIds: Set<String>
    get() = when (this) {
        is QueueAction.TransferRegistrations -> setOf(sourceMachineId, destinationMachineId)
        is QueueAction.ClearRegistrations -> machineIds
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
        if (action.requiredUnchangedMachineIds.any { machineId ->
                currentState.queue(machineId) != sourceState.queue(machineId)
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

        val applied = applyLegacyBehavior(state, action, context)
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
                val registration = queue.allRegistrations.first { it.key == action.registrationKey }
                if (registration.requiresOnSiteCheckIn) {
                    return QueueActionFailure(QueueActionFailureCode.PENDING_CHECK_IN)
                }
                if (registration.absenceStatus != QueueAbsenceStatus.NONE) {
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
                val registration = queue.allRegistrations.first { it.key == action.registrationKey }
                if (registration.requiresOnSiteCheckIn) {
                    return QueueActionFailure(QueueActionFailureCode.PENDING_CHECK_IN)
                }
                if (registration.absenceStatus != QueueAbsenceStatus.NONE) {
                    return QueueActionFailure(QueueActionFailureCode.WRONG_ABSENCE_STATE)
                }
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
                if (
                    !policy.isOperational(action.sourceMachineId) ||
                    !policy.isOperational(action.destinationMachineId)
                ) {
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
                val firstAvailable = queue.waitingPositions()
                    .getOrNull(queue.firstAvailableWaitingPositionIndex() ?: -1)
                if (
                    queue.playing.size != 1 ||
                    firstAvailable?.none { it.key == action.registrationKey } != false
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
                if (
                    action.registrationKeys.isEmpty() ||
                    action.registrationKeys.any { !queue.canMarkNoShow(it) }
                ) {
                    return QueueActionFailure(QueueActionFailureCode.INVALID_POSITION)
                }
            }

            is QueueAction.CreateFixedPair -> {
                if (policy.machineCapacity(action.machineId) == 1) {
                    return QueueActionFailure(QueueActionFailureCode.SINGLE_PLAYER_MACHINE_ONLY)
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
                val restoredRegistrations = action.restoredQueue
                    .removeAll(action.excludedRegistrationKeys)
                    .allRegistrations
                val otherRegistrations = state.queues.asSequence()
                    .filter { (machineId, _) -> machineId != action.machineId }
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

    private fun registrationFailure(
        queue: MachineQueue,
        registrationKey: Int
    ): QueueActionFailure? = if (queue.allRegistrations.none { it.key == registrationKey }) {
        QueueActionFailure(QueueActionFailureCode.REGISTRATION_NOT_FOUND)
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

        val updatedQueue = when (action) {
            is QueueAction.AddRegistrations -> when (action.placement) {
                RegistrationPlacement.AUTO_ADVANCE ->
                    queue.joinAll(action.registrations, context.atMillis)
                RegistrationPlacement.WAITING_TAIL -> queue.receiveAtWaitingTail(action.registrations)
                RegistrationPlacement.STAGED_WAITING -> action.registrations.fold(queue) { current, registration ->
                    current.stageWaiting(registration)
                }
                RegistrationPlacement.ADVANCE_IF_UNAMBIGUOUS -> {
                    val staged = queue.receiveAtWaitingTail(action.registrations)
                    val plan = RoundPlanner.enterPlayingPosition(staged)
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

            is QueueAction.FinishRound -> RoundPlanner.finishRound(queue).let { plan ->
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
                RoundPlanner.removeCurrentRoundAndStartNext(queue).let { plan ->
                    roundPreview = plan.preview
                    confirmation = true
                    plan.execute(context.atMillis)
                }
            is QueueAction.EnterPlayingPosition -> RoundPlanner.enterPlayingPosition(queue).let { plan ->
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
            is QueueAction.DeferOneRound -> {
                val staged = queue.deferOneRound(
                    registrationKey = action.registrationKey,
                    atMillis = context.atMillis,
                    advanceWhenPlayingBecomesEmpty = false
                )
                if (queue.playing.isNotEmpty() && staged.playing.isEmpty()) {
                    RoundPlanner.enterPlayingPosition(staged).let { plan ->
                        roundPreview = plan.preview
                        plan.execute(context.atMillis)
                    }
                } else {
                    staged
                }
            }
            is QueueAction.CancelDeferOneRound -> queue.cancelDeferOneRound(action.registrationKey)
            is QueueAction.TemporarilyLeave -> {
                val affectedKeys = queue.fixedGroupKeys(action.registrationKey)
                val staged = queue.temporarilyLeave(
                    registrationKey = action.registrationKey,
                    atMillis = context.atMillis,
                    advanceWhenPlayingBecomesEmpty = false
                )
                if (queue.playing.isNotEmpty() && staged.playing.isEmpty()) {
                    RoundPlanner.advance(staged, affectedKeys).let { plan ->
                        roundPreview = plan.preview
                        plan.execute(context.atMillis)
                    }
                } else {
                    staged
                }
            }
            is QueueAction.CancelTemporaryLeave -> queue.cancelTemporaryLeave(action.registrationKey)
            is QueueAction.ChangePreference ->
                queue.changePreference(action.registrationKey, action.preference)
            is QueueAction.CreateFixedPair -> {
                val plan = action.expectedPlan ?: queue.planFriendPair(
                    action.firstRegistrationKey,
                    action.secondRegistrationKey
                )
                friendPairPlan = plan
                confirmation = plan?.movedBackRegistrations?.isNotEmpty() == true
                val paired = plan?.let(queue::applyFriendPair) ?: queue
                if (action.advanceWhenPlayingEmpty && paired.playing.isEmpty()) {
                    val roundPlan = RoundPlanner.enterPlayingPosition(paired)
                    roundPreview = roundPlan.preview
                    availabilityConfirmation = roundPlan.preview?.changedByAvailability == true
                    if (availabilityConfirmation) paired else roundPlan.execute(context.atMillis)
                } else {
                    paired
                }
            }
            is QueueAction.CreateFixedPairWithRegistration -> {
                val paired = queue.createFriendPair(action.registrationKey, action.friend)
                if (action.advanceWhenPlayingEmpty && paired.playing.isEmpty()) {
                    val roundPlan = RoundPlanner.enterPlayingPosition(paired)
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
                val staged = when (action.resolution) {
                    NoShowResolution.DEFER_ONE_ROUND -> queue.markNoShowDeferOneRound(
                        action.registrationKeys.singleOrNull() ?: return AppliedBehavior(state),
                        startNextWhenPlayingBecomesEmpty = false,
                        atMillis = context.atMillis
                    )
                    NoShowResolution.DEFER_GROUP_ONE_ROUND -> queue.markNoShowGroupDeferOneRound(
                        action.registrationKeys,
                        startNextWhenPlayingBecomesEmpty = false,
                        atMillis = context.atMillis
                    )
                    NoShowResolution.MOVE_TO_TAIL -> queue.markNoShowMoveToEnd(
                        action.registrationKeys,
                        startNextWhenPlayingBecomesEmpty = false,
                        atMillis = context.atMillis
                    )
                    NoShowResolution.REMOVE -> queue.markNoShowAndRemove(
                        action.registrationKeys,
                        startNextWhenPlayingBecomesEmpty = false,
                        atMillis = context.atMillis
                    )
                }
                if (action.startNextWhenPlayingBecomesEmpty && staged.playing.isEmpty()) {
                    val skippedThisOpportunity = if (
                        action.resolution == NoShowResolution.MOVE_TO_TAIL
                    ) {
                        action.registrationKeys
                    } else {
                        emptySet()
                    }
                    RoundPlanner.advance(staged, skippedThisOpportunity).let { plan ->
                        roundPreview = plan.preview
                        plan.execute(context.atMillis)
                    }
                } else {
                    staged
                }
            }
            is QueueAction.CheckIn -> queue.checkIn(action.registrationKey)
            is QueueAction.RemoveRegistrations -> queue.removeAll(action.registrationKeys)
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
            is QueueAction.RestoreSnapshot -> normalizeQueueForCapacity(
                action.restoredQueue.removeAll(action.excludedRegistrationKeys),
                context.policy.machineCapacity(action.machineId)
            )
        }
        return AppliedBehavior(
            state = state.replace(action.machineId, updatedQueue),
            roundPreview = roundPreview,
            friendPairPlan = friendPairPlan,
            requiresAvailabilityConfirmation = availabilityConfirmation,
            requiresConfirmation = confirmation
        )
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
                    registration.copy(
                        preference = PlayPreference.SOLO,
                        fixedPartnerKey = null
                    )
                } else {
                    registration
                }
            }
        if (registrations.size != action.registrationKeys.size) return AppliedBehavior(state)
        val updatedSource = source.removeAll(action.registrationKeys)
        val updatedDestination = destination.receiveAtWaitingTail(registrations)
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
                    (action.destinationMachineId to updatedDestination)
            ),
            requiresConfirmation = true
        )
    }

    fun normalizeQueueForCapacity(queue: MachineQueue, capacity: Int): MachineQueue {
        if (capacity != 1) return queue
        val normalizeRegistration: (Registration) -> Registration = { registration ->
            registration.copy(
                preference = PlayPreference.SOLO,
                fixedPartnerKey = null
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
