package com.abcccc.maimaiqueue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

internal enum class MachineId { A, B }

internal enum class RegistrationActionMode { ACTIONS, PREFERENCE, RENAME }
internal enum class QueueAbsenceChoice { DEFER_ONE_ROUND, TEMPORARILY_AWAY }
internal enum class PlayerProfileContext { JOIN_QUEUE, CLAIM_REGISTRATION, FRIEND_PAIR }
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
    val fromPlayingPosition: Boolean = false
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
    val tone: HomeSidePanelFeedbackTone = HomeSidePanelFeedbackTone.SUCCESS
)

internal data class NewRegistrationHomeRequest(
    val highlight: NewRegistrationHighlight,
    val enterPlayingConfirmation: MachineId? = null,
    val forceImmediateHome: Boolean = false
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
    val nonRestorableRegistrationKeys: Set<Int> = emptySet()
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
    val registrationKeys: List<Int>
)

internal data class JoinClosingWarningRequest(
    val requestedMachineId: MachineId?,
    val lateMachineIds: List<MachineId>,
    val joinableMachineIds: List<MachineId>,
    val continueFromMachineSelection: Boolean = false
)

internal data class MenuAction(
    val title: String,
    val description: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val accented: Boolean? = null,
    val accentColor: Color? = null,
    val accentBackgroundColor: Color? = null
)

internal data class PositionReorderProposal(
    val machineId: MachineId,
    val originalQueue: MachineQueue,
    val proposedOrder: List<Registration>,
    val sourcePositionIndex: Int,
    val destinationPositionIndex: Int,
    val movedRegistrationKeys: Set<Int>,
    val relationshipChanges: List<String>
)

internal data class GlobalDragOverlayState(
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
        pointerInRoot: Offset,
        itemBoundsInRoot: Rect,
        content: @Composable () -> Unit
    ) {
        state = GlobalDragOverlayState(
            pointerInRoot = pointerInRoot,
            grabOffsetInItem = pointerInRoot - itemBoundsInRoot.topLeft,
            widthPx = itemBoundsInRoot.width,
            heightPx = itemBoundsInRoot.height,
            content = content
        )
    }

    fun moveBy(dragAmount: Offset) {
        state = state?.let { current ->
            current.copy(pointerInRoot = current.pointerInRoot + dragAmount)
        }
    }

    fun clear() {
        state = null
    }
}

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
