package com.abcccc.maimaiqueue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

internal enum class Screen {
    STARTUP_CHECK,
    ONBOARDING,
    HOME,
    AUDIT_LOG,
    SETTINGS,
    MACHINE,
    CREATE_REGISTRATION,
    PREFERENCE,
    PLAYER_LIBRARY,
    PLAYER_PROFILE_EDITOR,
    PLAYER_PROFILE_DETAIL,
    BATCH_AMOUNT,
    CLAIM_REGISTRATION
}

enum class MachineId { A, B, C, D, E, F, G, H, I, J }

internal val DEFAULT_CONFIGURED_MACHINE_IDS: List<MachineId> = MachineId.entries.take(2)

internal fun configuredMachineIds(machineCount: Int): List<MachineId> =
    MachineId.entries.take(machineCount.coerceIn(1, MachineId.entries.size))

internal fun machineSelectionColumnCount(machineCount: Int): Int =
    when (machineCount.coerceIn(1, MachineId.entries.size)) {
        1 -> 1
        2 -> 2
        3 -> 3
        4 -> 2
        else -> 3
    }

internal fun remapMachineStatesByStableIdentity(
    previousSettings: QueueRuleSettings,
    updatedSettings: QueueRuleSettings,
    previousStates: Map<MachineId, PersistedMachineState>
): Map<MachineId, PersistedMachineState> {
    val normalizedPrevious = normalizeMachineLayoutSettings(previousSettings)
    val normalizedUpdated = normalizeMachineLayoutSettings(updatedSettings)
    val statesByStableId = normalizedPrevious.configuredMachineIds.associate { machineId ->
        normalizedPrevious.machineStableId(machineId) to
            (previousStates[machineId] ?: PersistedMachineState())
    }
    return normalizedUpdated.configuredMachineIds.associateWith { machineId ->
        statesByStableId[normalizedUpdated.machineStableId(machineId)]
            ?: PersistedMachineState()
    }
}

internal data class MachineDisplayState(
    val machineId: MachineId,
    val stableId: String,
    val queue: MachineQueue,
    val status: MachineStatus,
    val configuration: MachineConfiguration
) {
    val remark: String
        get() = configuration.remark

    val name: String
        get() = machineName(machineId, remark)
}

internal enum class RegistrationActionMode { ACTIONS, PREFERENCE, RENAME }
internal enum class QueueAbsenceChoice { DEFER_ONE_ROUND, SKIP_ONE_TURN, TEMPORARILY_AWAY }
/** Why the player library is open. BROWSE: from the More menu, to look at and edit profiles (2026-11-28). */
internal enum class PlayerProfileContext { JOIN_QUEUE, CLAIM_REGISTRATION, FRIEND_PAIR, BROWSE }
internal enum class FriendPairStep { METHOD, SELECT_EXISTING, CONFIRM_EXISTING, CREATE_FRIEND }

internal data class RegistrationPlayArrangement(
    val isPlayingPosition: Boolean,
    val fixedPartnerDisplayId: String?,
    val playingPartnerDisplayId: String?,
    val waitingPartnerDisplayId: String?,
    val commonPlayPreviewDisplayId: String?
)

internal fun registrationPlayArrangement(
    queue: MachineQueue,
    registrationKey: Int,
    includeCommonPlayPreview: Boolean
): RegistrationPlayArrangement? {
    val registration = queue.allRegistrations.firstOrNull { it.key == registrationKey }
        ?: return null
    val isPlayingPosition = queue.playing.any { it.key == registrationKey }
    val projectedPosition = if (isPlayingPosition) {
        null
    } else {
        queue.waitingProjection(includeCommonPlayPreview).positions.firstOrNull { position ->
            position.registrations.any { it.key == registrationKey }
        }
    }
    return RegistrationPlayArrangement(
        isPlayingPosition = isPlayingPosition,
        fixedPartnerDisplayId = registration.fixedPartnerKey?.let { partnerKey ->
            queue.allRegistrations.firstOrNull { it.key == partnerKey }?.displayId
        },
        playingPartnerDisplayId = if (isPlayingPosition) {
            queue.playing.firstOrNull { it.key != registrationKey }?.displayId
        } else {
            null
        },
        waitingPartnerDisplayId = projectedPosition?.registrations
            ?.firstOrNull { it.key != registrationKey }
            ?.displayId,
        commonPlayPreviewDisplayId = projectedPosition?.commonPlayPreview?.displayId
    )
}

internal data class SelectedRegistration(
    val machineId: MachineId,
    val registrationKey: Int,
    val fromPlayingPosition: Boolean = false,
    val confirmationSnapshots: List<RegistrationConfirmationSnapshot> = emptyList(),
    /** Opens the 暂缓一次 or 暂时离开 confirmation directly, without the choice between them. */
    val initialAbsenceChoice: QueueAbsenceChoice? = null
)

internal data class RegistrationConfirmationSnapshot(
    val registrationKey: Int,
    val fixedPartnerKey: Int?,
    val absenceStatus: QueueAbsenceStatus,
    val temporaryAwaySkippedTurns: Int,
    val requiresOnSiteCheckIn: Boolean,
    val inPlayingPosition: Boolean
)

internal data class PlayerProfileDraftSnapshot(
    val nickname: String,
    val gender: PlayerGender,
    val defaultPreference: ProfilePlayPreference,
    val qqNumber: String,
    val qqVisibility: QqVisibility,
    val notificationPreferences: QueueNotificationPreferences
)

internal data class NewRegistrationHighlight(
    val machineId: MachineId,
    val registrationKey: Int,
    val requestId: String
)

internal data class HomeSidePanelRegistration(
    val requestId: String,
    val machineId: MachineId,
    val registrationKey: Int,
    val displayId: String,
    val machineName: String,
    val positionLabel: String,
    val isPlaying: Boolean,
    val requiresOnSiteCheckIn: Boolean
)

internal enum class HomeSidePanelFeedbackTone {
    SUCCESS,
    INFO,
    WARNING
}

internal data class HomeSidePanelFeedback(
    val id: Long,
    val title: String,
    val detail: String,
    val contextLabel: String? = null,
    val tone: HomeSidePanelFeedbackTone = HomeSidePanelFeedbackTone.SUCCESS,
    /** The machine the banner is about; home turns to it (maintainer, 2026-11-26). */
    val machineId: MachineId? = null
)

/** The machine home should bring into view, and what asked for it, so a repeat asks again. */
internal data class HomeScrollTarget(val machineId: MachineId, val key: String)

internal data class NewRegistrationHomeRequest(
    val highlight: NewRegistrationHighlight,
    val enterPlayingConfirmation: MachineId? = null,
    val forceImmediateHome: Boolean = false
)

/** What the terminal does with a registration that arrived from the website, the Bot or a phone. */
internal enum class NewRegistrationArrivalHandling {
    /** Home with nothing open (or the phone that scanned this terminal): clear and show it. */
    RETURN_HOME,

    /** Home is shown with something open over it: only the highlight is set. */
    HIGHLIGHT_ONLY,

    /** Kept until the terminal is idle again. */
    HOLD
}

/**
 * An arrival never takes away what the operator is doing (approved 2026-11-26): a form or another
 * screen holds it; a dialog, a menu or an adjustment on home stays and only the highlight is set.
 * One that asks whether the machine is free waits until nothing else is open.
 */
internal fun newRegistrationArrivalHandling(
    forceImmediateHome: Boolean,
    formOpen: Boolean,
    homeScreenShown: Boolean,
    terminalIdle: Boolean,
    raisesConfirmation: Boolean
): NewRegistrationArrivalHandling = when {
    forceImmediateHome -> NewRegistrationArrivalHandling.RETURN_HOME
    formOpen -> NewRegistrationArrivalHandling.HOLD
    terminalIdle -> NewRegistrationArrivalHandling.RETURN_HOME
    homeScreenShown && !raisesConfirmation -> NewRegistrationArrivalHandling.HIGHLIGHT_ONLY
    else -> NewRegistrationArrivalHandling.HOLD
}

/**
 * What home showed when arrivals began to be held. Anything else found there when they are
 * applied was produced by the action that closed the form, and is kept.
 */
internal class HeldNewRegistrationArrivals {
    var held = false
        private set
    private var highlightRequestId: String? = null
    private var feedbackId: Long? = null
    private var undoId: Long? = null

    fun hold(highlightRequestId: String?, feedbackId: Long?, undoId: Long?) {
        if (held) return
        held = true
        this.highlightRequestId = highlightRequestId
        this.feedbackId = feedbackId
        this.undoId = undoId
    }

    fun clear() {
        held = false
        highlightRequestId = null
        feedbackId = null
        undoId = null
    }

    fun localResultShown(
        highlightRequestId: String?,
        feedbackId: Long?,
        undoId: Long?,
        confirmationOpen: Boolean
    ): Boolean = held && (
        confirmationOpen ||
            (highlightRequestId != null && highlightRequestId != this.highlightRequestId) ||
            (feedbackId != null && feedbackId != this.feedbackId) ||
            (undoId != null && undoId != this.undoId)
        )
}

internal data class QueueOperationConfirmationRequest(
    val machineId: MachineId,
    val queueSnapshot: MachineQueue
)

internal data class ReorderSession(
    val machineId: MachineId,
    val queueSnapshot: MachineQueue,
    val explicitEditMode: Boolean,
    val initialRegistrationKey: Int? = null
)

internal data class ReorderProposal(
    val machineId: MachineId,
    val originalQueue: MachineQueue,
    val proposedOrder: List<Registration>,
    val movedKey: Int
)

internal data class QueueUndoAction(
    val id: Long,
    val machineId: MachineId,
    val beforeQueue: MachineQueue,
    val afterQueue: MachineQueue,
    val message: String,
    val feedbackTitle: String,
    val feedbackDetail: String,
    val contextLabel: String,
    val feedbackTone: HomeSidePanelFeedbackTone = HomeSidePanelFeedbackTone.SUCCESS,
    val nonRestorableRegistrationKeys: Set<Int> = emptySet(),
    /** Other machines the action also changed: their queues before and after it. */
    val otherMachineQueues: Map<MachineId, Pair<MachineQueue, MachineQueue>> = emptyMap(),
    /** The orders shared across paired machines before the action, by machine name. */
    val beforePairedOrders: Map<String, List<Int>>? = null,
    val beforePairedArrangements: Map<Int, PairedArrangementRecord>? = null
)

/** A play preference chosen in 更改游玩偏好 for one of a fixed pair; applied once confirmed. */
internal data class FixedPairPreferenceChange(
    val selection: SelectedRegistration,
    val partnerKey: Int,
    val preference: PlayPreference
)

internal data class PositionSelection(
    val machineId: MachineId,
    val label: String,
    val registrationKeys: List<Int>,
    val isPlayingPosition: Boolean,
    val waitingPositionIndex: Int? = null,
    val fromPlayingPosition: Boolean = false
)

internal data class MachineTransferRequest(
    val sourceMachineId: MachineId,
    val registrationKeys: List<Int>,
    val sourcePosition: PositionSelection? = null,
    val confirmationSnapshots: List<RegistrationConfirmationSnapshot>,
    val destinationMachineId: MachineId? = null
)

internal fun MachineQueue.registrationConfirmationSnapshots(
    registrationKeys: Collection<Int>
): List<RegistrationConfirmationSnapshot> {
    val requestedKeys = registrationKeys.toSet()
    if (requestedKeys.isEmpty()) return emptyList()
    val registrationsByKey = allRegistrations.associateBy(Registration::key)
    val relatedKeys = requestedKeys.toMutableSet()
    requestedKeys.forEach { key ->
        val registration = registrationsByKey[key] ?: return@forEach
        val partnerKey = registration.fixedPartnerKey ?: return@forEach
        val partner = registrationsByKey[partnerKey]
        if (partner?.fixedPartnerKey == key) relatedKeys += partnerKey
    }
    return relatedKeys.sorted().mapNotNull { key ->
        registrationsByKey[key]?.let { registration ->
            RegistrationConfirmationSnapshot(
                registrationKey = key,
                fixedPartnerKey = registration.fixedPartnerKey,
                absenceStatus = registration.absenceStatus,
                temporaryAwaySkippedTurns = registration.temporaryAwaySkippedTurns,
                requiresOnSiteCheckIn = registration.requiresOnSiteCheckIn,
                inPlayingPosition = playing.any { it.key == key }
            )
        }
    }.takeIf { snapshots -> snapshots.size == relatedKeys.size }.orEmpty()
}

internal fun MachineQueue.matchesRegistrationConfirmationSnapshots(
    snapshots: Collection<RegistrationConfirmationSnapshot>
): Boolean {
    if (snapshots.isEmpty()) return false
    val current = registrationConfirmationSnapshots(snapshots.map { it.registrationKey })
    return current == snapshots.sortedBy(RegistrationConfirmationSnapshot::registrationKey)
}

internal fun MachineQueue.matchesExactPosition(selection: PositionSelection): Boolean {
    val expectedKeys = selection.registrationKeys.toSet()
    if (expectedKeys.isEmpty() || expectedKeys.size != selection.registrationKeys.size) return false
    val currentPosition = if (selection.isPlayingPosition) {
        playing
    } else if (selection.waitingPositionIndex == null) {
        // A position as the home screen shows it that is not one real position (beside a
        // pending online registration or a preview): it still stands for these registrations
        // as long as all of them are waiting here.
        return expectedKeys.all { key -> waiting.any { it.key == key } }
    } else {
        waitingPositions().getOrNull(selection.waitingPositionIndex)
    } ?: return false
    return currentPosition.size == expectedKeys.size && currentPosition.all { it.key in expectedKeys }
}

internal fun MachineQueue.matchesFixedPairPosition(selection: PositionSelection): Boolean {
    if (!matchesExactPosition(selection)) return false
    val registrations = allRegistrations.filter { it.key in selection.registrationKeys }
    return registrations.size == 2 &&
        registrations[0].fixedPartnerKey == registrations[1].key &&
        registrations[1].fixedPartnerKey == registrations[0].key
}

/** [nextGroupKeys]: the next group as the engine sees it on a machine of a pair; see canMarkNoShow. */
internal fun MachineQueue.matchesNoShowLocation(
    selection: SelectedRegistration,
    nextGroupKeys: Set<Int>? = null
): Boolean {
    if (!canMarkNoShow(selection.registrationKey, nextGroupKeys)) return false
    val isCurrentlyPlaying = playing.any { it.key == selection.registrationKey }
    return if (selection.fromPlayingPosition) isCurrentlyPlaying else !isCurrentlyPlaying
}

internal data class JoinClosingWarningRequest(
    val requestedMachineId: MachineId?,
    val lateMachineIds: List<MachineId>,
    val joinableMachineIds: List<MachineId>,
    val continueFromMachineSelection: Boolean = false
)

/**
 * The tick box that lets the system arrange a registration between the two machines of a pair.
 * It is offered with the play preference of one registration: while the registration is
 * created, and later where that preference is changed.
 */
internal data class PairedMachineQueueJoinOption(
    val machineLabel: String,
    val pairedMachineLabel: String,
    val checked: Boolean,
    val onToggle: () -> Unit,
    /** Opens the explanation; the question mark beside the tick box. */
    val onShowDetails: () -> Unit,
    /**
     * True when the box is being ticked for a registration that is already waiting: it then
     * only takes effect after that registration has played a round.
     */
    val startsAfterNextRound: Boolean = false,
    /** Why the box cannot be changed, for example for a fixed pair; null when it can. */
    val disabledReason: String? = null,
    /** For a waiting registration whose box is on: the preview that announces a move, if any. */
    val announcement: PairedMachineArrangementAnnouncement? = null,
    /** Where the other machine shows that preview, such as B1; null when it shows none. */
    val previewPosition: String? = null
)

internal data class MenuAction(
    val title: String,
    val description: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val accented: Boolean? = null,
    val accentColor: Color? = null,
    val accentBackgroundColor: Color? = null,
    /**
     * Shown as a menu entry with its line of explanation, for the few entries whose explanation
     * must be read before choosing (docs/ui-guide.md). Otherwise the title says enough, and the
     * entry is an ordinary button; [description] then only explains why it is unavailable.
     */
    val explained: Boolean = false
)

/**
 * The waiting positions a drag of a position works on: this machine's registrations grouped as
 * the home screen shows them (pending online registrations as if they check in, without a pass
 * the check refuses, around the previews of the other machine of a pair), in their real order.
 * [labels] are the names the home screen gives them, such as B2. They need not be the positions
 * of MachineQueue.waitingPositions.
 */
internal data class DragPositions(
    val positions: List<List<Registration>>,
    val labels: List<String>
)

internal data class PositionReorderProposal(
    val machineId: MachineId,
    val originalQueue: MachineQueue,
    val proposedOrder: List<Registration>,
    /** The positions the drag worked on; the two indexes below are into them. */
    val positions: DragPositions,
    val sourcePositionIndex: Int,
    val destinationPositionIndex: Int,
    val movedRegistrationKeys: Set<Int>,
    val relationshipChanges: List<String>
)

internal data class GlobalDragOverlayState(
    val ownerKey: Any,
    val pointerInRoot: Offset,
    val grabOffsetInItem: Offset,
    val widthPx: Float,
    val heightPx: Float,
    val content: @Composable () -> Unit
)

internal class GlobalDragOverlayController {
    var state by mutableStateOf<GlobalDragOverlayState?>(null)
        private set

    fun start(
        ownerKey: Any,
        pointerInRoot: Offset,
        itemBoundsInRoot: Rect,
        content: @Composable () -> Unit
    ) {
        state = GlobalDragOverlayState(
            ownerKey = ownerKey,
            pointerInRoot = pointerInRoot,
            grabOffsetInItem = pointerInRoot - itemBoundsInRoot.topLeft,
            widthPx = itemBoundsInRoot.width,
            heightPx = itemBoundsInRoot.height,
            content = content
        )
    }

    fun moveBy(ownerKey: Any, dragAmount: Offset) {
        state = state?.let { current ->
            if (current.ownerKey === ownerKey) {
                current.copy(pointerInRoot = current.pointerInRoot + dragAmount)
            } else {
                current
            }
        }
    }

    fun clear(ownerKey: Any) {
        if (state?.ownerKey === ownerKey) state = null
    }

    fun clear() {
        state = null
    }
}

/** Registrations being dragged from one machine, which may be dropped on another to move them. */
internal data class DragTransfer(val sourceMachineId: MachineId, val registrationKeys: List<Int>)

/**
 * The machines on the home page as places to drop a dragged registration or position
 * (drag-transfer-mockup.html, maintainer 2026-11-26). Each machine reports where it is on
 * screen; while a drag is under way, the machine under the pointer, other than the one the drag
 * started on, shows a mask, and releasing there opens the confirmation of 转至其他机台. Its
 * positions do not make room.
 */
internal class MachineDropTargets(
    private val unavailableReason: (DragTransfer, MachineId) -> String?,
    private val onTransfer: (DragTransfer, MachineId) -> Unit,
    /** How many machines the venue has, on all group pages. */
    private val machineCount: () -> Int = { 0 }
) {
    /**
     * Where each machine is on screen, in root coordinates, clipped to what is visible. A machine
     * of another group page is composed too (so a drag survives turning the page) but has no
     * entry while it is out of sight.
     */
    val bounds = mutableStateMapOf<MachineId, Rect>()

    /** Whether the venue has another machine to drag registrations to, on any group page. */
    val otherMachinesExist: Boolean
        get() = machineCount() > 1
    var active by mutableStateOf<DragTransfer?>(null)
        private set
    var pointer by mutableStateOf<Offset?>(null)
        private set

    /** The machine under the pointer while dragging, other than the one the drag started on. */
    val hovered: MachineId?
        get() {
            val drag = active ?: return null
            val at = pointer ?: return null
            return bounds.entries.firstOrNull { (machineId, area) ->
                machineId != drag.sourceMachineId && area.contains(at)
            }?.key
        }

    /** Whether the pointer is still over the machine the drag started on. */
    val overSource: Boolean
        get() {
            val drag = active ?: return false
            val at = pointer ?: return false
            return bounds[drag.sourceMachineId]?.contains(at) == true
        }

    fun report(machineId: MachineId, area: Rect) {
        if (area.width <= 0f || area.height <= 0f) {
            bounds.remove(machineId)
        } else if (bounds[machineId] != area) {
            bounds[machineId] = area
        }
    }

    /**
     * Whether the machine the drag started on is on screen. After turning to another group page
     * it is not: the drag no longer reorders it, and ending there puts it back
     * ([DragDropResult.RETURNED]).
     */
    val sourceVisible: Boolean
        get() = active?.let { bounds[it.sourceMachineId] != null } == true

    fun forget(machineId: MachineId) {
        bounds.remove(machineId)
    }

    /** Why the dragged registrations cannot go to [machineId], or null when they can. */
    fun reasonFor(machineId: MachineId): String? = active?.let { unavailableReason(it, machineId) }

    fun begin(drag: DragTransfer, at: Offset) {
        active = drag
        pointer = at
    }

    fun move(at: Offset) {
        if (active != null) pointer = at
    }

    /**
     * Ends the drag. When it ended on another machine the caller puts the registrations back
     * where they were: the confirmation then opens ([DragDropResult.TRANSFER]) unless that machine
     * cannot take them ([DragDropResult.REFUSED]).
     */
    fun finish(): DragDropResult {
        val drag = active
        val target = hovered
        active = null
        pointer = null
        if (drag == null) return DragDropResult.NONE
        if (target == null) {
            return if (bounds[drag.sourceMachineId] == null) DragDropResult.RETURNED else DragDropResult.NONE
        }
        if (unavailableReason(drag, target) != null) return DragDropResult.REFUSED
        onTransfer(drag, target)
        return DragDropResult.TRANSFER
    }

    fun cancel() {
        active = null
        pointer = null
    }
}

/**
 * How a drag ended: not on another machine, on one that cannot take it, on one that can, or on a
 * group page without the machine it started on and without another machine under the pointer.
 */
internal enum class DragDropResult { NONE, REFUSED, TRANSFER, RETURNED }

internal val LocalMachineDropTargets = staticCompositionLocalOf<MachineDropTargets?> { null }

internal val LocalGlobalDragOverlayController = staticCompositionLocalOf<GlobalDragOverlayController> {
    error("GlobalDragOverlayController is not available")
}

@Composable
internal fun GlobalDragOverlay(controller: GlobalDragOverlayController) {
    val overlay = controller.state ?: return
    val density = LocalDensity.current
    val topLeft = overlay.pointerInRoot - overlay.grabOffsetInItem
    val width = with(density) { overlay.widthPx.toDp() }
    val height = with(density) { overlay.heightPx.toDp() }

    Box(Modifier.fillMaxSize().zIndex(1_000f)) {
        Box(
            Modifier
                .offset {
                    IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt())
                }
                .width(width)
                .height(height)
                .zIndex(1_001f)
        ) {
            overlay.content()
        }
    }
}

/**
 * Why a registration that is postponed cannot form a fixed pair (maintainer, 2026-11-27): the
 * state has to be cancelled first. [ending] says what it cannot do: 组成固定组合 for a registration
 * chosen as the friend, 与朋友共同游玩 for the one that starts. Null when it is not postponed.
 */
internal fun fixedPairPostponedReason(absenceStatus: QueueAbsenceStatus, ending: String): String? =
    when (absenceStatus) {
        QueueAbsenceStatus.NONE -> null
        QueueAbsenceStatus.DEFER_ONE_ROUND -> "这份登记将暂缓一次，取消后才能$ending。"
        QueueAbsenceStatus.SKIP_ONE_TURN -> "这份登记将轮空一次，取消后才能$ending。"
        QueueAbsenceStatus.TEMPORARILY_AWAY -> "这份登记处于暂时离开状态，取消后才能$ending。"
    }

/**
 * Where a machine's queue row is scrolled to. The row's content is replaced whenever the queue
 * changes; kept inside that content, the position went back to the start each time somebody
 * joined (review 2026-11-27). It still goes back when other players enter the playing
 * position, which stands at the start of the row and is what matters then.
 */
internal class QueueRowScrollMemory {
    var index = 0
    var offset = 0
    var secondRowOffset = 0
    /** The registrations last seen in the playing position; null before the first look. */
    var playingKeys: List<Int>? = null
    /** The return-to-start request that has been carried out, so that new content does not repeat it. */
    var handledResetToken: Int? = null
}
