package com.abcccc.maimaiqueue

import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.abcccc.maimaiqueue.ui.theme.MaimaiQueueTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.random.Random

private val PageBackground = Color(0xFFF5F5F7)
private val CardBackground = Color.White
private val PrimaryText = Color(0xFF1D1D1F)
private val SecondaryText = Color(0xFF6E6E73)
private val TertiaryText = Color(0xFF8E8E93)
private val Separator = Color(0xFFD2D2D7)
private val SystemBlue = Color(0xFF007AFF)
private val SoftBlue = Color(0xFFEAF3FF)
private val PlayingRegistrationBackground = Color(0xFFF8FBFF)
private val Destructive = Color(0xFFFF3B30)
private val AbsenceStatusColor = Color(0xFFB85C00)
private val AbsenceStatusBackground = Color(0xFFFFF1DC)
private val ControlRadius = 11.dp
private val CardRadius = 16.dp
private val DialogRadius = 20.dp
private val QueueViewportHeight = 154.dp
private val QueueRegistrationTileHeight = 96.dp
private const val SOLO_ROUND_DURATION_MILLIS = 12 * 60_000L
private const val SHARED_ROUND_DURATION_MILLIS = 15 * 60_000L

private enum class Screen {
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

private enum class MachineId { A, B }

private enum class RegistrationActionMode { ACTIONS, PREFERENCE, RENAME }
private enum class QueueAbsenceChoice { DEFER_ONE_ROUND, TEMPORARILY_AWAY }
private enum class PlayerProfileContext { JOIN_QUEUE, CLAIM_REGISTRATION }
private enum class FriendPairStep { METHOD, SELECT_EXISTING, CONFIRM_EXISTING, CREATE_FRIEND }
private data class SelectedRegistration(
    val machineId: MachineId,
    val registrationKey: Int,
    val fromPlayingPosition: Boolean = false
)

private data class ReorderSession(
    val machineId: MachineId,
    val queueSnapshot: MachineQueue,
    val explicitEditMode: Boolean,
    val initialRegistrationKey: Int? = null
)

private data class ReorderProposal(
    val machineId: MachineId,
    val originalQueue: MachineQueue,
    val proposedOrder: List<Registration>,
    val movedKey: Int
)

private data class QueueUndoAction(
    val id: Long,
    val machineId: MachineId,
    val beforeQueue: MachineQueue,
    val afterQueue: MachineQueue,
    val message: String
)

private data class PositionSelection(
    val machineId: MachineId,
    val label: String,
    val registrationKeys: List<Int>,
    val isPlayingPosition: Boolean,
    val waitingPositionIndex: Int? = null,
    val fromPlayingPosition: Boolean = false
)

private data class MachineTransferRequest(
    val sourceMachineId: MachineId,
    val registrationKeys: List<Int>
)

private data class MenuAction(
    val title: String,
    val description: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val accented: Boolean? = null
)

private data class PositionReorderProposal(
    val machineId: MachineId,
    val originalQueue: MachineQueue,
    val proposedOrder: List<Registration>,
    val sourcePositionIndex: Int,
    val destinationPositionIndex: Int,
    val movedRegistrationKeys: Set<Int>,
    val relationshipChanges: List<String>
)

private data class GlobalDragOverlayState(
    val pointerInRoot: Offset,
    val grabOffsetInItem: Offset,
    val widthPx: Float,
    val heightPx: Float,
    val content: @Composable () -> Unit
)

private class GlobalDragOverlayController {
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

private val LocalGlobalDragOverlayController = staticCompositionLocalOf<GlobalDragOverlayController> {
    error("GlobalDragOverlayController is not available")
}

@Composable
private fun GlobalDragOverlay(controller: GlobalDragOverlayController) {
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        enableEdgeToEdge()
        hideSystemBars()
        setContent {
            MaimaiQueueTheme(darkTheme = false, dynamicColor = false) {
                RegistrationApp()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun RegistrationApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val queueSoundPlayer = remember { QueueSoundPlayer() }
    DisposableEffect(queueSoundPlayer) {
        onDispose(queueSoundPlayer::close)
    }
    val playerProfileRepository = remember(context) { LocalPlayerProfileRepository(context) }
    val auditLogRepository = remember(context) { LocalAuditLogRepository(context) }
    val queueStateRepository = remember(context) { LocalQueueStateRepository(context) }
    val queueRuleSettingsRepository = remember(context) { LocalQueueRuleSettingsRepository(context) }
    val initialQueueRuleSettings = remember(queueRuleSettingsRepository) {
        queueRuleSettingsRepository.getSettings()
    }
    val queueStatePublisher = remember(context) {
        HttpQueueStatePublisher(
            context = context,
            endpoint = BuildConfig.QUEUE_SYNC_URL,
            token = BuildConfig.QUEUE_SYNC_TOKEN
        )
    }
    var cloudSyncStatus by remember(
        queueStatePublisher.isConfigured,
        initialQueueRuleSettings.websiteSyncEnabled
    ) {
        mutableStateOf(
            QueueCloudSyncStatus(
                phase = when {
                    !initialQueueRuleSettings.websiteSyncEnabled -> QueueCloudSyncPhase.DISABLED
                    queueStatePublisher.isConfigured -> QueueCloudSyncPhase.CONFIGURED
                    else -> QueueCloudSyncPhase.NOT_CONFIGURED
                }
            )
        )
    }
    val cloudSyncController = remember(queueStatePublisher, coroutineScope) {
        QueueCloudSyncController(
            scope = coroutineScope,
            publisher = queueStatePublisher,
            initiallyEnabled = initialQueueRuleSettings.websiteSyncEnabled,
            onStatusChange = { cloudSyncStatus = it }
        )
    }
    var machineA by remember { mutableStateOf(MachineQueue()) }
    var machineB by remember { mutableStateOf(MachineQueue()) }
    var machineAStatus by remember { mutableStateOf(MachineStatus()) }
    var machineBStatus by remember { mutableStateOf(MachineStatus()) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var selectedMachine by remember { mutableStateOf<MachineId?>(null) }
    var isBatchFlow by remember { mutableStateOf(false) }
    var batchAmount by remember { mutableStateOf("2") }
    var draftId by remember { mutableStateOf("") }
    var temporarySelected by remember { mutableStateOf(false) }
    var selectedPreference by remember { mutableStateOf<PlayPreference?>(PlayPreference.OPEN_TO_JOIN) }
    var nextKey by remember { mutableIntStateOf(1) }
    var queueId by remember { mutableStateOf(newQueueId()) }
    val queueRevision = remember { AtomicLong(0L) }
    var playerProfiles by remember { mutableStateOf<List<PlayerProfile>>(emptyList()) }
    var auditLogs by remember { mutableStateOf<List<AuditLogEntry>>(emptyList()) }
    var queueRuleSettings by remember { mutableStateOf(initialQueueRuleSettings) }
    var playerProfileSearch by remember { mutableStateOf("") }
    var playerProfileSort by remember { mutableStateOf(ProfileSortMode.RECOMMENDED) }
    var selectedPlayerProfileId by remember { mutableStateOf<String?>(null) }
    var editingPlayerProfileId by remember { mutableStateOf<String?>(null) }
    var profileNicknameDraft by remember { mutableStateOf("") }
    var profileGenderDraft by remember { mutableStateOf(PlayerGender.UNDISCLOSED) }
    var profilePreferenceDraft by remember { mutableStateOf(ProfilePlayPreference.ASK_EVERY_TIME) }
    var profileQqDraft by remember { mutableStateOf("") }
    var profileAllowsPhoneDraft by remember { mutableStateOf(false) }
    var profileJoinPreference by remember { mutableStateOf<PlayPreference?>(null) }
    var rememberProfileJoinPreference by remember { mutableStateOf(false) }
    var playerProfileContext by remember { mutableStateOf(PlayerProfileContext.JOIN_QUEUE) }
    var playerProfileEditorReturnScreen by remember { mutableStateOf(Screen.PLAYER_LIBRARY) }
    var claimPreferenceMismatchProfileId by remember { mutableStateOf<String?>(null) }

    var selectedRegistration by remember { mutableStateOf<SelectedRegistration?>(null) }
    var moveIntoPlayingTarget by remember { mutableStateOf<SelectedRegistration?>(null) }
    var registrationActionMode by remember { mutableStateOf(RegistrationActionMode.ACTIONS) }
    var renameDraft by remember { mutableStateOf("") }
    var claimTarget by remember { mutableStateOf<SelectedRegistration?>(null) }
    var finishConfirmation by remember { mutableStateOf<MachineId?>(null) }
    var enterPlayingConfirmation by remember { mutableStateOf<MachineId?>(null) }
    var moreMenuVisible by remember { mutableStateOf(false) }
    var appDetailsVisible by remember { mutableStateOf(false) }
    var editMachineChoiceVisible by remember { mutableStateOf(false) }
    var stopMachineChoiceVisible by remember { mutableStateOf(false) }
    var stopReasonTarget by remember { mutableStateOf<MachineId?>(null) }
    var reorderSession by remember { mutableStateOf<ReorderSession?>(null) }
    var inlineReorderProposal by remember { mutableStateOf<ReorderProposal?>(null) }
    var positionReorderProposal by remember { mutableStateOf<PositionReorderProposal?>(null) }
    var inlineReorderResetToken by remember { mutableIntStateOf(0) }
    var positionReorderResetToken by remember { mutableIntStateOf(0) }
    var registrationOpen by remember { mutableStateOf(true) }
    var closeQueueConfirmation by remember { mutableStateOf(false) }
    var selectedPosition by remember { mutableStateOf<PositionSelection?>(null) }
    var returnPlayingTarget by remember { mutableStateOf<PositionSelection?>(null) }
    var returnPlayingRegistrationTarget by remember { mutableStateOf<SelectedRegistration?>(null) }
    var advanceToPlayingTarget by remember { mutableStateOf<PositionSelection?>(null) }
    var absenceChoiceTarget by remember { mutableStateOf<SelectedRegistration?>(null) }
    var noShowTarget by remember { mutableStateOf<SelectedRegistration?>(null) }
    var groupNoShowTarget by remember { mutableStateOf<PositionSelection?>(null) }
    var exitTarget by remember { mutableStateOf<SelectedRegistration?>(null) }
    var removeGroupTarget by remember { mutableStateOf<PositionSelection?>(null) }
    var machineTransferTarget by remember { mutableStateOf<MachineTransferRequest?>(null) }
    var friendPairTarget by remember { mutableStateOf<SelectedRegistration?>(null) }
    var stagedFriendPairRegistration by remember { mutableStateOf<SelectedRegistration?>(null) }
    var releaseFixedPairTarget by remember { mutableStateOf<PositionSelection?>(null) }
    var queueUndoAction by remember { mutableStateOf<QueueUndoAction?>(null) }
    var nextQueueUndoId by remember { mutableLongStateOf(1L) }
    var pendingQueueRestore by remember { mutableStateOf<PersistedQueueState?>(null) }
    var queuePersistenceReady by remember { mutableStateOf(false) }

    LaunchedEffect(playerProfileRepository) {
        playerProfiles = playerProfileRepository.getProfiles()
    }

    LaunchedEffect(auditLogRepository) {
        auditLogs = auditLogRepository.getLogs()
    }

    LaunchedEffect(queueStateRepository) {
        val savedState = queueStateRepository.getState()?.takeIf(PersistedQueueState::hasMeaningfulState)
        pendingQueueRestore = savedState
        queuePersistenceReady = true
    }

    LaunchedEffect(
        queuePersistenceReady,
        pendingQueueRestore,
        machineA,
        machineB,
        machineAStatus,
        machineBStatus,
        registrationOpen,
        nextKey,
        queueId,
        auditLogs,
        queueRuleSettings.websiteSyncEnabled,
        queueRuleSettings.machineARemark,
        queueRuleSettings.machineBRemark
    ) {
        if (!queuePersistenceReady || pendingQueueRestore != null) return@LaunchedEffect
        val snapshot = PersistedQueueState(
            queueId = queueId,
            revision = queueRevision.incrementAndGet(),
            machineA = machineA,
            machineB = machineB,
            machineAStatus = machineAStatus,
            machineBStatus = machineBStatus,
            registrationOpen = registrationOpen,
            nextRegistrationKey = nextKey,
            savedAtMillis = System.currentTimeMillis()
        )
        withContext(NonCancellable) { queueStateRepository.saveState(snapshot) }
        cloudSyncController.submit(
            state = snapshot,
            auditLogs = auditLogs,
            displaySettings = QueuePublicDisplaySettings(
                machineARemark = queueRuleSettings.machineARemark,
                machineBRemark = queueRuleSettings.machineBRemark
            )
        )
    }

    fun queueFor(machineId: MachineId): MachineQueue =
        if (machineId == MachineId.A) machineA else machineB

    fun statusFor(machineId: MachineId): MachineStatus =
        if (machineId == MachineId.A) machineAStatus else machineBStatus

    fun setQueue(machineId: MachineId, queue: MachineQueue) {
        if (machineId == MachineId.A) machineA = queue else machineB = queue
    }

    fun appendAuditLog(entry: AuditLogEntry) {
        val queueScopedEntry = if (entry.queueId == null) entry.copy(queueId = queueId) else entry
        auditLogs = (listOf(queueScopedEntry) + auditLogs.filterNot {
            it.id == queueScopedEntry.id
        }).take(1_000)
        coroutineScope.launch { auditLogRepository.append(queueScopedEntry) }
    }

    fun updateQueueRuleSettings(settings: QueueRuleSettings) {
        if (settings == queueRuleSettings) return
        val previousSettings = queueRuleSettings
        queueRuleSettings = settings
        queueRuleSettingsRepository.saveSettings(settings)
        if (previousSettings.websiteSyncEnabled != settings.websiteSyncEnabled) {
            cloudSyncController.setEnabled(settings.websiteSyncEnabled)
        }
        val changeDescriptions = buildList {
            if (previousSettings.allowDeferOneRound != settings.allowDeferOneRound) {
                add("暂缓一轮：${if (settings.allowDeferOneRound) "允许" else "不允许"}")
            }
            if (previousSettings.allowTemporaryLeave != settings.allowTemporaryLeave) {
                add("暂时离开：${if (settings.allowTemporaryLeave) "允许" else "不允许"}")
            }
            if (previousSettings.websiteSyncEnabled != settings.websiteSyncEnabled) {
                add("网站同步：${if (settings.websiteSyncEnabled) "已开启" else "已关闭"}")
            }
            if (previousSettings.machineARemark != settings.machineARemark) {
                add("机台 A 备注改为“${settings.machineARemark}”")
            }
            if (previousSettings.machineBRemark != settings.machineBRemark) {
                add("机台 B 备注改为“${settings.machineBRemark}”")
            }
        }
        appendAuditLog(
            createAuditLogEntry(
                category = AuditLogCategory.SYSTEM,
                title = "更新应用设置",
                detail = changeDescriptions.joinToString(separator = "；", postfix = "。")
            )
        )
    }

    fun auditCategoryFor(machineId: MachineId): AuditLogCategory =
        if (machineId == MachineId.A) {
            AuditLogCategory.MACHINE_A
        } else {
            AuditLogCategory.MACHINE_B
        }

    fun appendQueueAuditLog(
        machineId: MachineId,
        beforeQueue: MachineQueue,
        afterQueue: MachineQueue,
        titleOverride: String? = null,
        publicEventTypeOverride: PublicQueueEventType? = null,
        affectedRegistrationKeysOverride: Collection<Int> = emptyList()
    ) {
        createQueueAuditLog(
            category = auditCategoryFor(machineId),
            machineLabel = machineName(machineId),
            before = beforeQueue,
            after = afterQueue,
            titleOverride = titleOverride,
            publicEventTypeOverride = publicEventTypeOverride,
            affectedRegistrationKeysOverride = affectedRegistrationKeysOverride
        )?.let(::appendAuditLog)
    }

    fun updateQueue(
        machineId: MachineId,
        soundCue: QueueSoundCue? = null,
        publicEventTypeOverride: PublicQueueEventType? = null,
        affectedRegistrationKeysOverride: Collection<Int> = emptyList(),
        transform: (MachineQueue) -> MachineQueue
    ) {
        val beforeQueue = queueFor(machineId)
        val afterQueue = transform(beforeQueue)
        if (afterQueue == beforeQueue) return
        queueUndoAction = null
        setQueue(machineId, afterQueue)
        appendQueueAuditLog(
            machineId,
            beforeQueue,
            afterQueue,
            publicEventTypeOverride = publicEventTypeOverride,
            affectedRegistrationKeysOverride = affectedRegistrationKeysOverride
        )
        soundCue?.let(queueSoundPlayer::play)
    }

    fun updateQueueWithUndo(
        machineId: MachineId,
        message: String,
        transform: (MachineQueue) -> MachineQueue
    ) {
        val beforeQueue = queueFor(machineId)
        val afterQueue = transform(beforeQueue)
        if (afterQueue == beforeQueue) return
        setQueue(machineId, afterQueue)
        appendQueueAuditLog(machineId, beforeQueue, afterQueue, message)
        queueUndoAction = QueueUndoAction(
            id = nextQueueUndoId++,
            machineId = machineId,
            beforeQueue = beforeQueue,
            afterQueue = afterQueue,
            message = message
        )
        queueSoundPlayer.play(QueueSoundCue.QUEUE_CHANGE)
    }

    fun undoLatestQueueAction() {
        val action = queueUndoAction ?: return
        var restored = false
        if (queueFor(action.machineId) == action.afterQueue) {
            setQueue(action.machineId, action.beforeQueue)
            appendQueueAuditLog(
                action.machineId,
                action.afterQueue,
                action.beforeQueue,
                "撤销：${action.message}"
            )
            restored = true
        }
        queueUndoAction = null
        if (restored) queueSoundPlayer.play(QueueSoundCue.UNDO)
    }

    fun updateStatus(machineId: MachineId, transform: (MachineStatus) -> MachineStatus) {
        if (machineId == MachineId.A) machineAStatus = transform(machineAStatus)
        else machineBStatus = transform(machineBStatus)
    }

    fun reportMachineStopped(machineId: MachineId, reason: MachineStopReason) {
        val currentStatus = statusFor(machineId)
        val stoppedStatus = currentStatus.stop(reason, System.currentTimeMillis())
        if (stoppedStatus == currentStatus) return
        updateStatus(machineId) { stoppedStatus }
        queueSoundPlayer.play(QueueSoundCue.CAUTION)
        val registrationCount = queueFor(machineId).registrationCount
        appendAuditLog(
            createAuditLogEntry(
                category = auditCategoryFor(machineId),
                title = "${machineName(machineId)} 已停止使用",
                detail = buildString {
                    append("原因：${machineStopReasonLabel(reason)}。")
                    if (registrationCount > 0) {
                        append("现有 $registrationCount 份登记及其顺序已保留；恢复正常使用后，本轮计时会从头开始。")
                    }
                },
                publicEventType = PublicQueueEventType.MACHINE_STOPPED
            )
        )
        if (queueUndoAction?.machineId == machineId) queueUndoAction = null
        if (selectedMachine == machineId) selectedMachine = null
        screen = Screen.HOME
    }

    fun restoreMachine(machineId: MachineId) {
        val stoppedStatus = statusFor(machineId)
        if (stoppedStatus.isOperational) return
        val restoredAtMillis = System.currentTimeMillis()
        val restoredQueue = queueFor(machineId).restartPlayingTimer(restoredAtMillis)
        setQueue(machineId, restoredQueue)
        updateStatus(machineId) { it.restore() }
        queueSoundPlayer.play(QueueSoundCue.CONFIRM)
        appendAuditLog(
            createAuditLogEntry(
                category = auditCategoryFor(machineId),
                title = "${machineName(machineId)} 已恢复正常使用",
                detail = if (queueFor(machineId).playing.isEmpty()) {
                    "这台机台现在可以继续接收和处理排队登记。"
                } else {
                    "保留的登记顺序已经恢复，本轮计时已从头开始。"
                },
                publicEventType = PublicQueueEventType.MACHINE_RESTORED
            )
        )
    }

    fun transferRegistrations(request: MachineTransferRequest) {
        val destinationMachineId = otherMachine(request.sourceMachineId)
        val registrationKeys = request.registrationKeys.toSet()
        val sourceQueue = queueFor(request.sourceMachineId)
        val destinationQueue = queueFor(destinationMachineId)
        val registrations = sourceQueue.allRegistrations
            .filter { it.key in registrationKeys }
        if (
            !statusFor(request.sourceMachineId).isOperational ||
            !statusFor(destinationMachineId).isOperational ||
            registrations.isEmpty() ||
            destinationQueue.registrationCount + registrations.size > 20
        ) return

        val updatedSource = sourceQueue.removeAll(registrationKeys)
        val updatedDestination = destinationQueue.receiveAtWaitingTail(registrations)
        if (
            updatedSource == sourceQueue ||
            updatedDestination.registrationCount != destinationQueue.registrationCount + registrations.size
        ) return

        queueUndoAction = null
        setQueue(request.sourceMachineId, updatedSource)
        setQueue(destinationMachineId, updatedDestination)
        appendQueueAuditLog(request.sourceMachineId, sourceQueue, updatedSource)
        appendQueueAuditLog(destinationMachineId, destinationQueue, updatedDestination)
        queueSoundPlayer.play(QueueSoundCue.QUEUE_CHANGE)
    }

    fun idAlreadyExists(displayId: String, exceptKey: Int? = null): Boolean =
        (machineA.allRegistrations + machineB.allRegistrations).any {
            it.key != exceptKey && it.displayId.equals(displayId.trim(), ignoreCase = true)
        }

    fun playerProfileNicknameExists(nickname: String, exceptProfileId: String? = null): Boolean =
        playerProfiles.any {
            it.id != exceptProfileId && it.nickname.equals(nickname.trim(), ignoreCase = true)
        }

    fun playerProfileNicknameConflictsWithQueue(
        nickname: String,
        profileId: String? = editingPlayerProfileId
    ): Boolean {
        val normalizedNickname = nickname.trim()
        if (normalizedNickname.isBlank()) return false
        val claimRegistrationKey = if (playerProfileContext == PlayerProfileContext.CLAIM_REGISTRATION) {
            claimTarget?.registrationKey
        } else {
            null
        }
        return (machineA.allRegistrations + machineB.allRegistrations).any { registration ->
            registration.key != claimRegistrationKey &&
                registration.playerProfileId != profileId &&
                registration.displayId.trim().equals(normalizedNickname, ignoreCase = true)
        }
    }

    fun playerProfileAlreadyRegistered(profile: PlayerProfile, exceptKey: Int? = null): Boolean =
        (machineA.allRegistrations + machineB.allRegistrations).any {
            it.key != exceptKey && (
                it.playerProfileId == profile.id ||
                    it.displayId.equals(profile.nickname.trim(), ignoreCase = true)
                )
        }

    fun upsertPlayerProfile(profile: PlayerProfile) {
        val existingIndex = playerProfiles.indexOfFirst { it.id == profile.id }
        val existingProfile = playerProfiles.getOrNull(existingIndex)
        playerProfiles = if (existingIndex >= 0) {
            playerProfiles.toMutableList().apply { this[existingIndex] = profile }
        } else {
            playerProfiles + profile
        }
        coroutineScope.launch { playerProfileRepository.upsertProfile(profile) }
        if (
            existingProfile != null &&
            (existingProfile.nickname != profile.nickname || existingProfile.gender != profile.gender)
        ) {
            updateQueue(MachineId.A) {
                it.syncPlayerProfileDetails(profile.id, profile.nickname, profile.gender)
            }
            updateQueue(MachineId.B) {
                it.syncPlayerProfileDetails(profile.id, profile.nickname, profile.gender)
            }
        }
        if (
            existingProfile == null ||
            existingProfile.nickname != profile.nickname ||
            existingProfile.gender != profile.gender ||
            existingProfile.defaultPreference != profile.defaultPreference ||
            existingProfile.qqNumber != profile.qqNumber ||
            existingProfile.phoneNumber != profile.phoneNumber
        ) {
            appendAuditLog(createPlayerProfileAuditLog(existingProfile, profile))
        }
    }

    fun reopenRegistration() {
        if (registrationOpen) return
        registrationOpen = true
        queueSoundPlayer.play(QueueSoundCue.CONFIRM)
        appendAuditLog(
            createAuditLogEntry(
                category = AuditLogCategory.SYSTEM,
                title = "重新开放登记排队",
                detail = "现在可以创建新的排队登记。",
                publicEventType = PublicQueueEventType.REGISTRATION_OPENED
            )
        )
    }

    fun closeRegistration() {
        if (!registrationOpen) return
        val removedCount = machineA.registrationCount + machineB.registrationCount
        val removedRegistrationKeys = (machineA.allRegistrations + machineB.allRegistrations)
            .map { it.key }
        updateQueue(MachineId.A) { MachineQueue() }
        updateQueue(MachineId.B) { MachineQueue() }
        registrationOpen = false
        queueSoundPlayer.play(QueueSoundCue.CAUTION)
        appendAuditLog(
            createAuditLogEntry(
                category = AuditLogCategory.SYSTEM,
                title = "关闭登记排队",
                detail = if (removedCount == 0) {
                    "新的排队登记已停止接收。"
                } else {
                    "新的排队登记已停止接收，并清除了两台机台的 $removedCount 份登记。"
                },
                publicEventType = PublicQueueEventType.REGISTRATION_CLOSED,
                affectedRegistrationKeys = removedRegistrationKeys
            )
        )
    }

    fun restorePreviousQueue(savedState: PersistedQueueState) {
        queueId = savedState.queueId
        queueRevision.set(savedState.revision)
        machineA = savedState.machineA
        machineB = savedState.machineB
        machineAStatus = savedState.machineAStatus
        machineBStatus = savedState.machineBStatus
        registrationOpen = savedState.registrationOpen
        nextKey = savedState.safeNextRegistrationKey
        queueUndoAction = null
        screen = Screen.HOME
        pendingQueueRestore = null
        queueSoundPlayer.play(QueueSoundCue.CONFIRM)
        appendAuditLog(
            createAuditLogEntry(
                category = AuditLogCategory.SYSTEM,
                title = "恢复上次队列",
                detail = "已恢复机台 A 的 ${savedState.machineA.registrationCount} 个登记和机台 B 的 ${savedState.machineB.registrationCount} 个登记。"
            )
        )
    }

    fun startWithNewQueue(savedState: PersistedQueueState) {
        queueId = newQueueId()
        queueRevision.set(0L)
        machineA = MachineQueue()
        machineB = MachineQueue()
        machineAStatus = MachineStatus()
        machineBStatus = MachineStatus()
        registrationOpen = true
        nextKey = 1
        queueUndoAction = null
        screen = Screen.HOME
        pendingQueueRestore = null
        queueSoundPlayer.play(QueueSoundCue.QUEUE_CHANGE)
        appendAuditLog(
            createAuditLogEntry(
                category = AuditLogCategory.SYSTEM,
                title = "开始新的队列",
                detail = "未载入上次保存的 ${savedState.totalRegistrationCount} 个登记，已从空队列开始。"
            )
        )
    }

    fun openPlayerLibrary(context: PlayerProfileContext) {
        playerProfileContext = context
        temporarySelected = false
        playerProfileSearch = ""
        selectedPlayerProfileId = null
        screen = Screen.PLAYER_LIBRARY
    }

    fun openNewPlayerProfile() {
        playerProfileEditorReturnScreen = Screen.PLAYER_LIBRARY
        editingPlayerProfileId = null
        profileNicknameDraft = ""
        profileGenderDraft = PlayerGender.UNDISCLOSED
        profilePreferenceDraft = ProfilePlayPreference.ASK_EVERY_TIME
        profileQqDraft = ""
        profileAllowsPhoneDraft = false
        screen = Screen.PLAYER_PROFILE_EDITOR
    }

    fun openEditPlayerProfile(
        profile: PlayerProfile,
        returnScreen: Screen = Screen.PLAYER_LIBRARY
    ) {
        playerProfileEditorReturnScreen = returnScreen
        editingPlayerProfileId = profile.id
        profileNicknameDraft = profile.nickname
        profileGenderDraft = profile.gender
        profilePreferenceDraft = profile.defaultPreference
        profileQqDraft = profile.qqNumber ?: profile.phoneNumber.orEmpty()
        profileAllowsPhoneDraft = !profile.phoneNumber.isNullOrBlank()
        screen = Screen.PLAYER_PROFILE_EDITOR
    }

    fun openPlayerProfile(profile: PlayerProfile) {
        selectedPlayerProfileId = profile.id
        profileJoinPreference = profile.defaultPreference.toPlayPreferenceOrNull()
        rememberProfileJoinPreference = false
        screen = Screen.PLAYER_PROFILE_DETAIL
    }

    fun savePlayerProfileDraft() {
        val normalizedNickname = profileNicknameDraft.trim()
        val contact = playerContactFromInput(profileQqDraft, profileAllowsPhoneDraft)
        if (
            normalizedNickname.isBlank() ||
            playerProfileNicknameExists(normalizedNickname, editingPlayerProfileId) ||
            playerProfileNicknameConflictsWithQueue(normalizedNickname) ||
            !contact.isValid
        ) return
        val nowMillis = System.currentTimeMillis()
        val existingProfile = editingPlayerProfileId?.let { profileId ->
            playerProfiles.firstOrNull { it.id == profileId }
        }
        val savedProfile = existingProfile?.copy(
            nickname = normalizedNickname,
            gender = profileGenderDraft,
            defaultPreference = profilePreferenceDraft,
            qqNumber = contact.qqNumber,
            phoneNumber = contact.phoneNumber,
            updatedAtMillis = nowMillis
        ) ?: createPlayerProfile(
            nickname = normalizedNickname,
            gender = profileGenderDraft,
            defaultPreference = profilePreferenceDraft,
            qqNumber = contact.qqNumber,
            phoneNumber = contact.phoneNumber,
            createdAtMillis = nowMillis
        )
        upsertPlayerProfile(savedProfile)
        profileAllowsPhoneDraft = false
        if (existingProfile == null) openPlayerProfile(savedProfile)
        else screen = playerProfileEditorReturnScreen
    }

    fun completePlayerProfileRegistration() {
        val machineId = selectedMachine ?: return
        val profile = selectedPlayerProfileId?.let { profileId ->
            playerProfiles.firstOrNull { it.id == profileId }
        } ?: return
        val preference = profile.defaultPreference.toPlayPreferenceOrNull() ?: profileJoinPreference ?: return
        if (
            !profile.hasValidContact ||
            playerProfileAlreadyRegistered(profile) ||
            !statusFor(machineId).isOperational ||
            queueFor(machineId).registrationCount >= 20
        ) return
        updateQueue(machineId, QueueSoundCue.CONFIRM) {
            it.join(
                Registration(
                    key = nextKey++,
                    displayId = profile.nickname,
                    preference = preference,
                    isTemporary = false,
                    gender = profile.gender,
                    playerProfileId = profile.id
                )
            )
        }
        upsertPlayerProfile(
            profile.recordUsage(
                preferenceToRemember = preference.takeIf {
                    profile.defaultPreference == ProfilePlayPreference.ASK_EVERY_TIME &&
                        rememberProfileJoinPreference
                }
            )
        )
        selectedPlayerProfileId = null
        rememberProfileJoinPreference = false
        screen = Screen.HOME
    }

    fun completePlayerProfileClaim(preferenceOverride: PlayPreference? = null) {
        val selection = claimTarget ?: return
        val profile = selectedPlayerProfileId?.let { profileId ->
            playerProfiles.firstOrNull { it.id == profileId }
        } ?: return
        val registration = queueFor(selection.machineId).allRegistrations
            .firstOrNull { it.key == selection.registrationKey } ?: return
        if (
            !profile.hasValidContact ||
            !registration.isTemporary ||
            !statusFor(selection.machineId).isOperational ||
            playerProfileAlreadyRegistered(profile, exceptKey = registration.key)
        ) return
        updateQueue(selection.machineId, QueueSoundCue.CONFIRM) {
            it.claimWithPlayerProfile(
                registrationKey = registration.key,
                playerProfileId = profile.id,
                playerNickname = profile.nickname,
                gender = profile.gender,
                preferenceOverride = preferenceOverride
            )
        }
        upsertPlayerProfile(profile.recordUsage())
        claimPreferenceMismatchProfileId = null
        claimTarget = null
        selectedPlayerProfileId = null
        playerProfileContext = PlayerProfileContext.JOIN_QUEUE
        screen = Screen.HOME
    }

    fun beginRegistration(batch: Boolean) {
        if (
            reorderSession != null ||
            !registrationOpen ||
            listOf(MachineId.A, MachineId.B).none { statusFor(it).isOperational }
        ) return
        isBatchFlow = batch
        selectedMachine = null
        draftId = ""
        temporarySelected = false
        selectedPreference = PlayPreference.OPEN_TO_JOIN
        batchAmount = "2"
        screen = Screen.MACHINE
    }

    fun beginRegistrationForMachine(machineId: MachineId) {
        if (
            reorderSession != null ||
            !registrationOpen ||
            !statusFor(machineId).isOperational ||
            queueFor(machineId).registrationCount >= 20
        ) return
        isBatchFlow = false
        selectedMachine = machineId
        draftId = ""
        temporarySelected = false
        selectedPreference = PlayPreference.OPEN_TO_JOIN
        screen = Screen.CREATE_REGISTRATION
    }

    fun randomUnusedId(): String {
        var candidate: String
        do candidate = randomChinesePlayerId()
        while (idAlreadyExists(candidate))
        return candidate
    }

    fun completeRegistration() {
        val machineId = selectedMachine ?: return
        val preference = selectedPreference ?: return
        val normalizedId = draftId.trim()
        if (
            normalizedId.isBlank() ||
            idAlreadyExists(normalizedId) ||
            !statusFor(machineId).isOperational ||
            queueFor(machineId).registrationCount >= 20
        ) return
        updateQueue(machineId, QueueSoundCue.CONFIRM) {
            it.join(Registration(nextKey++, normalizedId, preference))
        }
        screen = Screen.HOME
    }

    fun beginFriendPreferenceRegistration() {
        val machineId = selectedMachine ?: return
        val normalizedId = draftId.trim()
        if (
            normalizedId.isBlank() ||
            idAlreadyExists(normalizedId) ||
            !statusFor(machineId).isOperational ||
            queueFor(machineId).registrationCount >= 20
        ) return
        val registration = Registration(
            key = nextKey++,
            displayId = normalizedId,
            preference = PlayPreference.OPEN_TO_JOIN
        )
        updateQueue(machineId) { it.stageWaiting(registration) }
        val selection = SelectedRegistration(machineId, registration.key)
        stagedFriendPairRegistration = selection
        friendPairTarget = selection
        screen = Screen.HOME
    }

    fun completeBatch() {
        val machineId = selectedMachine ?: return
        if (!statusFor(machineId).isOperational) return
        val remainingCapacity = 20 - queueFor(machineId).registrationCount
        if (remainingCapacity <= 0) return
        val amount = batchAmount.toIntOrNull() ?: return
        if (amount !in 1..remainingCapacity) return
        val usedIds = (machineA.allRegistrations + machineB.allRegistrations)
            .map { it.displayId.lowercase() }.toMutableSet()
        val registrations = buildList {
            repeat(amount) {
                var generatedId: String
                do generatedId = randomChinesePlayerId()
                while (!usedIds.add(generatedId.lowercase()))
                add(
                    Registration(
                        key = nextKey++,
                        displayId = generatedId,
                        preference = PlayPreference.OPEN_TO_JOIN
                    )
                )
            }
        }
        updateQueue(machineId, QueueSoundCue.CONFIRM) { queue -> queue.joinAll(registrations) }
        screen = Screen.HOME
    }

    fun openRegistration(machineId: MachineId, key: Int) {
        if (reorderSession != null) return
        selectedRegistration = SelectedRegistration(machineId, key)
        registrationActionMode = RegistrationActionMode.ACTIONS
    }

    fun beginReorder(machineId: MachineId, explicit: Boolean, initialRegistrationKey: Int? = null) {
        if (reorderSession != null) return
        val queue = queueFor(machineId)
        if (queue.waiting.isEmpty()) return
        inlineReorderProposal = null
        reorderSession = ReorderSession(machineId, queue, explicit, initialRegistrationKey)
    }

    LaunchedEffect(queueUndoAction?.id) {
        val action = queueUndoAction ?: return@LaunchedEffect
        delay(5_000L)
        if (queueUndoAction?.id == action.id) queueUndoAction = null
    }

    val globalDragOverlayController = remember { GlobalDragOverlayController() }
    LaunchedEffect(screen, reorderSession) {
        globalDragOverlayController.clear()
    }
    Surface(Modifier.fillMaxSize(), color = PageBackground) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalGlobalDragOverlayController provides globalDragOverlayController
            ) {
                val activeReorder = reorderSession
                if (activeReorder?.explicitEditMode == true) {
                    ReorderScreen(
                    machineId = activeReorder.machineId,
                    initialQueue = activeReorder.queueSnapshot,
                    explicitEditMode = activeReorder.explicitEditMode,
                    onCancel = { reorderSession = null },
                    onCommit = { registrations ->
                        updateQueueWithUndo(
                            activeReorder.machineId,
                            "${machineName(activeReorder.machineId)} 的登记顺序已调整"
                        ) { it.replaceOrder(registrations) }
                        reorderSession = null
                    }
                )
            } else {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "navigation"
                ) { target ->
                    when (target) {
                        Screen.HOME -> HomeScreen(
                            machineA = machineA,
                            machineB = machineB,
                            machineAStatus = machineAStatus,
                            machineBStatus = machineBStatus,
                            machineARemark = queueRuleSettings.machineARemark,
                            machineBRemark = queueRuleSettings.machineBRemark,
                            registrationOpen = registrationOpen,
                            cloudSyncStatus = cloudSyncStatus,
                            queueUndoAction = queueUndoAction,
                            onUndoQueueAction = ::undoLatestQueueAction,
                            inlineReorderSession = activeReorder?.takeIf { !it.explicitEditMode },
                            inlineReorderResetToken = inlineReorderResetToken,
                            positionReorderResetToken = positionReorderResetToken,
                            onInlineReorderCancel = {
                                inlineReorderProposal = null
                                reorderSession = null
                            },
                            onInlineReorderProposal = { machineId, originalQueue, proposed, movedKey ->
                                inlineReorderProposal = ReorderProposal(
                                    machineId,
                                    originalQueue,
                                    proposed,
                                    movedKey
                                )
                            },
                            onEnableRegistration = ::reopenRegistration,
                            onJoin = { beginRegistration(false) },
                            onJoinMachine = ::beginRegistrationForMachine,
                            onBatch = { beginRegistration(true) },
                            onMore = {
                                if (reorderSession == null) moreMenuVisible = true
                            },
                            onFinishRequest = {
                                if (reorderSession == null) finishConfirmation = it
                            },
                            onEnterPlaying = { machineId ->
                                if (reorderSession == null) {
                                    val preview = queueFor(machineId).nextPlayingPositionPreview()
                                    if (preview?.changedByAbsence == true) {
                                        enterPlayingConfirmation = machineId
                                    } else {
                                        updateQueue(machineId, QueueSoundCue.QUEUE_CHANGE) {
                                            it.enterPlayingPosition()
                                        }
                                    }
                                }
                            },
                            onRestoreMachine = {
                                if (reorderSession == null) restoreMachine(it)
                            },
                            onRegistrationClick = ::openRegistration,
                            onRegistrationLongPress = { machineId, registrationKey ->
                                beginReorder(machineId, false, registrationKey)
                            },
                            onPositionClick = {
                                if (reorderSession == null) selectedPosition = it
                            },
                            onPositionReorderRequest = { machineId, originalQueue, sourceIndex, destinationIndex ->
                                if (reorderSession == null) {
                                    positionReorderProposal = createPositionReorderProposal(
                                        machineId = machineId,
                                        queue = originalQueue,
                                        sourceIndex = sourceIndex,
                                        destinationIndex = destinationIndex
                                    )
                                }
                            }
                        )

                        Screen.AUDIT_LOG -> AuditLogScreen(
                            logs = auditLogs,
                            onBack = { screen = Screen.HOME }
                        )

                        Screen.SETTINGS -> QueueRuleSettingsScreen(
                            settings = queueRuleSettings,
                            onSettingsChange = ::updateQueueRuleSettings,
                            onBack = { screen = Screen.HOME }
                        )

                        Screen.MACHINE -> MachineSelectionScreen(
                            machineA = machineA,
                            machineB = machineB,
                            machineAStatus = machineAStatus,
                            machineBStatus = machineBStatus,
                            batch = isBatchFlow,
                            onBack = { screen = Screen.HOME },
                            onSelect = {
                                selectedMachine = it
                                if (isBatchFlow) {
                                    batchAmount = minOf(2, 20 - queueFor(it).registrationCount).toString()
                                }
                                screen = if (isBatchFlow) Screen.BATCH_AMOUNT else Screen.CREATE_REGISTRATION
                            }
                        )

                        Screen.CREATE_REGISTRATION -> CreateRegistrationScreen(
                            draftId = draftId,
                            temporarySelected = temporarySelected,
                            idAlreadyRegistered = draftId.isNotBlank() && idAlreadyExists(draftId),
                            onIdChange = { draftId = limitCodePointLength(it, 18) },
                            onTemporarySelect = { temporarySelected = true },
                            onGenerateId = {
                                temporarySelected = true
                                draftId = randomUnusedId()
                            },
                            onPlayerLibrary = {
                                openPlayerLibrary(PlayerProfileContext.JOIN_QUEUE)
                            },
                            onBack = { screen = Screen.MACHINE },
                            onContinue = { screen = Screen.PREFERENCE }
                        )

                        Screen.PREFERENCE -> PreferenceScreen(
                            selected = selectedPreference,
                            onSelect = { selectedPreference = it },
                            onBack = { screen = Screen.CREATE_REGISTRATION },
                            onComplete = ::completeRegistration,
                            onFriendPair = ::beginFriendPreferenceRegistration
                        )

                        Screen.PLAYER_LIBRARY -> PlayerLibraryScreen(
                            profiles = playerProfiles,
                            searchQuery = playerProfileSearch,
                            sortMode = playerProfileSort,
                            contextLabel = if (playerProfileContext == PlayerProfileContext.CLAIM_REGISTRATION) {
                                "认领登记"
                            } else {
                                "本机玩家资料"
                            },
                            title = if (playerProfileContext == PlayerProfileContext.CLAIM_REGISTRATION) {
                                "选择玩家资料"
                            } else {
                                "玩家资料库"
                            },
                            subtitle = if (playerProfileContext == PlayerProfileContext.CLAIM_REGISTRATION) {
                                "选择用于认领这份临时登记的玩家资料。"
                            } else {
                                "选择玩家资料，确认后加入当前机台的排队。"
                            },
                            onSearchQueryChange = { playerProfileSearch = it },
                            onSortModeChange = { playerProfileSort = it },
                            onNewProfile = ::openNewPlayerProfile,
                            onProfileClick = ::openPlayerProfile,
                            onEditProfile = {
                                openEditPlayerProfile(it, Screen.PLAYER_LIBRARY)
                            },
                            onBack = {
                                screen = if (playerProfileContext == PlayerProfileContext.CLAIM_REGISTRATION) {
                                    Screen.CLAIM_REGISTRATION
                                } else {
                                    Screen.CREATE_REGISTRATION
                                }
                            }
                        )

                        Screen.PLAYER_PROFILE_EDITOR -> PlayerProfileEditorScreen(
                            nickname = profileNicknameDraft,
                            nicknameAlreadyExists = profileNicknameDraft.isNotBlank() &&
                                (
                                    playerProfileNicknameExists(profileNicknameDraft, editingPlayerProfileId) ||
                                        playerProfileNicknameConflictsWithQueue(profileNicknameDraft)
                                    ),
                            gender = profileGenderDraft,
                            defaultPreference = profilePreferenceDraft,
                            qqNumber = profileQqDraft,
                            allowPhoneNumber = profileAllowsPhoneDraft,
                            editingExisting = editingPlayerProfileId != null,
                            onNicknameChange = {
                                profileNicknameDraft = limitCodePointLength(it, 18)
                            },
                            onGenderChange = { profileGenderDraft = it },
                            onDefaultPreferenceChange = { profilePreferenceDraft = it },
                            onQqNumberChange = { value ->
                                profileQqDraft = value.filter { it in '0'..'9' }
                                    .take(MAX_QQ_NUMBER_LENGTH)
                            },
                            onAllowPhoneNumberChange = { allowPhoneNumber ->
                                profileAllowsPhoneDraft = allowPhoneNumber
                            },
                            onSave = ::savePlayerProfileDraft,
                            onBack = { screen = playerProfileEditorReturnScreen }
                        )

                        Screen.PLAYER_PROFILE_DETAIL -> {
                            val profile = selectedPlayerProfileId?.let { profileId ->
                                playerProfiles.firstOrNull { it.id == profileId }
                            }
                            if (playerProfileContext == PlayerProfileContext.CLAIM_REGISTRATION) {
                                val selection = claimTarget
                                val registration = selection?.let {
                                    queueFor(it.machineId).allRegistrations
                                        .firstOrNull { registration -> registration.key == it.registrationKey }
                                }
                                ClaimPlayerProfileDetailScreen(
                                    profile = profile,
                                    registration = registration,
                                    alreadyRegistered = profile?.let {
                                        playerProfileAlreadyRegistered(it, exceptKey = registration?.key)
                                    } == true,
                                    machineAvailable = selection?.let {
                                        statusFor(it.machineId).isOperational
                                    } == true,
                                    onEditProfile = {
                                        profile?.let {
                                            openEditPlayerProfile(it, Screen.PLAYER_PROFILE_DETAIL)
                                        }
                                    },
                                    onComplete = {
                                        val profilePreference = profile?.defaultPreference
                                            ?.toPlayPreferenceOrNull()
                                        if (
                                            profile != null &&
                                            registration != null &&
                                            profilePreference != null &&
                                            profilePreference != registration.preference
                                        ) {
                                            claimPreferenceMismatchProfileId = profile.id
                                        } else {
                                            completePlayerProfileClaim()
                                        }
                                    },
                                    onBack = { screen = Screen.PLAYER_LIBRARY }
                                )
                            } else {
                                PlayerProfileDetailScreen(
                                    profile = profile,
                                    selectedPreference = profileJoinPreference,
                                    rememberPreference = rememberProfileJoinPreference,
                                    alreadyRegistered = profile?.let(::playerProfileAlreadyRegistered) == true,
                                    machineLabel = selectedMachine?.let(::machineName) ?: "所选机台",
                                    machineAvailable = selectedMachine?.let { machineId ->
                                        statusFor(machineId).isOperational &&
                                            queueFor(machineId).registrationCount < 20
                                    } == true,
                                    onPreferenceChange = { profileJoinPreference = it },
                                    onRememberPreferenceChange = { rememberProfileJoinPreference = it },
                                    onEditProfile = {
                                        profile?.let {
                                            openEditPlayerProfile(it, Screen.PLAYER_PROFILE_DETAIL)
                                        }
                                    },
                                    onComplete = ::completePlayerProfileRegistration,
                                    onBack = { screen = Screen.PLAYER_LIBRARY }
                                )
                            }
                        }

                        Screen.BATCH_AMOUNT -> BatchAmountScreen(
                            amount = batchAmount,
                            maximum = selectedMachine?.let { 20 - queueFor(it).registrationCount } ?: 20,
                            onAmountChange = { value ->
                                if (value.all(Char::isDigit)) batchAmount = value.take(2)
                            },
                            onDecrease = {
                                batchAmount = ((batchAmount.toIntOrNull() ?: 2) - 1).coerceAtLeast(1).toString()
                            },
                            onIncrease = {
                                val maximum = selectedMachine?.let { 20 - queueFor(it).registrationCount } ?: 20
                                batchAmount = ((batchAmount.toIntOrNull() ?: 1) + 1).coerceAtMost(maximum).toString()
                            },
                            onBack = { screen = Screen.MACHINE },
                            onComplete = ::completeBatch
                        )

                        Screen.CLAIM_REGISTRATION -> ClaimRegistrationScreen(
                            displayId = claimTarget?.let { selection ->
                                queueFor(selection.machineId).allRegistrations
                                    .firstOrNull { it.key == selection.registrationKey }?.displayId
                            } ?: "这份登记",
                            onPlayerLibrary = {
                                openPlayerLibrary(PlayerProfileContext.CLAIM_REGISTRATION)
                            },
                            onBack = {
                                claimTarget = null
                                screen = Screen.HOME
                            }
                        )
                    }
                }

                inlineReorderProposal?.let { proposal ->
                    ReorderConfirmation(
                        originalQueue = proposal.originalQueue,
                        proposedOrder = proposal.proposedOrder,
                        movedKey = proposal.movedKey,
                        onKeepOriginal = {
                            inlineReorderProposal = null
                            inlineReorderResetToken++
                        },
                        onConfirm = {
                            val activeSession = reorderSession
                            if (
                                activeSession?.machineId == proposal.machineId &&
                                !activeSession.explicitEditMode &&
                                !hasRegistrationOrderChanged(
                                    proposal.originalQueue.allRegistrations,
                                    queueFor(proposal.machineId).allRegistrations
                                )
                            ) {
                                updateQueueWithUndo(
                                    proposal.machineId,
                                    "${machineName(proposal.machineId)} 的登记顺序已调整"
                                ) {
                                    it.replaceOrder(proposal.proposedOrder)
                                }
                            }
                            inlineReorderProposal = null
                            reorderSession = null
                        }
                    )
                }

                positionReorderProposal?.let { proposal ->
                    PositionReorderConfirmation(
                        proposal = proposal,
                        onKeepOriginal = {
                            positionReorderProposal = null
                            positionReorderResetToken++
                        },
                        onConfirm = {
                            if (queueFor(proposal.machineId) == proposal.originalQueue) {
                                updateQueueWithUndo(
                                    proposal.machineId,
                                    "${machineName(proposal.machineId)} 的队列位置已调整"
                                ) {
                                    it.replaceOrder(proposal.proposedOrder)
                                }
                            }
                            positionReorderProposal = null
                        }
                    )
                }

                selectedRegistration?.let { selection ->
                    val queue = queueFor(selection.machineId)
                    val registration = queue.allRegistrations
                        .firstOrNull { it.key == selection.registrationKey }
                    if (registration != null) {
                        val currentPlayer = queue.playing.singleOrNull()
                        val isInFirstWaitingPosition = queue.waitingPositions()
                            .getOrNull(queue.firstAvailableWaitingPositionIndex() ?: -1)
                            ?.any { it.key == registration.key } == true
                        val linkedPlayerProfile = registration.playerProfileId?.let { profileId ->
                            playerProfiles.firstOrNull { it.id == profileId }
                        }
                        val transferTargetMachineId = otherMachine(selection.machineId)
                        val transferUnavailableReason = machineTransferUnavailableReason(
                            machineName = machineName(transferTargetMachineId),
                            status = statusFor(transferTargetMachineId),
                            queue = queueFor(transferTargetMachineId),
                            incomingRegistrationCount = 1
                        )
                        RegistrationActions(
                            registration = registration,
                            playerProfileGender = linkedPlayerProfile?.gender ?: registration.gender,
                            playerProfileQqNumber = linkedPlayerProfile?.qqNumber,
                            playerProfilePhoneNumber = linkedPlayerProfile?.phoneNumber,
                            fixedPartnerDisplayId = registration.fixedPartnerKey?.let { partnerKey ->
                                queue.allRegistrations
                                    .firstOrNull { it.key == partnerKey }?.displayId
                            },
                            playingPartnerDisplayId = queue.playing
                                .firstOrNull { it.key != registration.key }
                                ?.displayId,
                            isPlayingPosition = queue.playing.any { it.key == selection.registrationKey },
                            playingPositionLabel = playingPositionName(selection.machineId),
                            canMoveIntoPlaying = currentPlayer != null &&
                                isInFirstWaitingPosition &&
                                registration.absenceStatus == QueueAbsenceStatus.NONE,
                            canReportNoShow = queue.canMarkNoShow(registration.key),
                            allowDeferOneRound = queueRuleSettings.allowDeferOneRound,
                            allowTemporaryLeave = queueRuleSettings.allowTemporaryLeave,
                            transferMachineName = machineName(transferTargetMachineId),
                            transferUnavailableReason = transferUnavailableReason,
                            canEditPlayerProfile = linkedPlayerProfile != null,
                            mode = registrationActionMode,
                            renameDraft = renameDraft,
                            renameAlreadyExists = renameDraft.isNotBlank() &&
                                idAlreadyExists(renameDraft, registration.key),
                            onRenameDraftChange = {
                                renameDraft = limitCodePointLength(it, 18)
                            },
                            onDismiss = { selectedRegistration = null },
                            onMoveIntoPlaying = {
                                moveIntoPlayingTarget = selection
                                selectedRegistration = null
                            },
                            onReturnToWaitingFront = {
                                returnPlayingRegistrationTarget = selection
                                selectedRegistration = null
                            },
                            onPauseOrLeave = {
                                if (queueRuleSettings.allowsAnyAbsenceAction) {
                                    absenceChoiceTarget = selection
                                    selectedRegistration = null
                                }
                            },
                            onCancelDeferOneRound = {
                                updateQueue(selection.machineId) {
                                    it.cancelDeferOneRound(registration.key)
                                }
                                selectedRegistration = null
                            },
                            onCancelTemporaryLeave = {
                                updateQueue(selection.machineId) {
                                    it.cancelTemporaryLeave(registration.key)
                                }
                                selectedRegistration = null
                            },
                            onChangePreference = {
                                registrationActionMode = RegistrationActionMode.PREFERENCE
                            },
                            onPreferenceSelected = { preference ->
                                updateQueue(selection.machineId) {
                                    it.changePreference(registration.key, preference)
                                }
                                selectedRegistration = null
                            },
                            onFriendPair = {
                                friendPairTarget = selection
                                selectedRegistration = null
                            },
                            onRename = {
                                renameDraft = registration.displayId
                                registrationActionMode = RegistrationActionMode.RENAME
                            },
                            onRenameConfirm = {
                                val normalized = renameDraft.trim()
                                if (normalized.isNotBlank() && !idAlreadyExists(normalized, registration.key)) {
                                    updateQueue(selection.machineId) {
                                        it.rename(registration.key, normalized)
                                    }
                                    selectedRegistration = null
                                }
                            },
                            onClaim = {
                                claimTarget = selection
                                selectedRegistration = null
                                screen = Screen.CLAIM_REGISTRATION
                            },
                            onEditPlayerProfile = {
                                linkedPlayerProfile?.let { profile ->
                                    openEditPlayerProfile(profile, Screen.HOME)
                                    selectedRegistration = null
                                }
                            },
                            onTransfer = {
                                machineTransferTarget = MachineTransferRequest(
                                    selection.machineId,
                                    listOf(selection.registrationKey)
                                )
                                selectedRegistration = null
                            },
                            onNoShow = {
                                val currentQueue = queueFor(selection.machineId)
                                if (currentQueue.canMarkNoShow(selection.registrationKey)) {
                                    val registration = currentQueue.allRegistrations
                                        .firstOrNull { it.key == selection.registrationKey }
                                    val wasPlaying = currentQueue.playing
                                        .any { it.key == selection.registrationKey }
                                    val partner = registration?.fixedPartnerKey?.let { partnerKey ->
                                        currentQueue.allRegistrations.firstOrNull { it.key == partnerKey }
                                    }?.takeIf { it.fixedPartnerKey == registration?.key }
                                    if (partner != null) {
                                        val groupKeys = setOf(selection.registrationKey, partner.key)
                                        val waitingIndex = currentQueue.waitingPositions()
                                            .indexOfFirst { position ->
                                                position.any { it.key == selection.registrationKey }
                                            }
                                        groupNoShowTarget = PositionSelection(
                                            machineId = selection.machineId,
                                            label = if (wasPlaying) {
                                                playingPositionName(selection.machineId)
                                            } else {
                                                "位置 ${selection.machineId.name}${waitingIndex + 1} · 固定组合"
                                            },
                                            registrationKeys = groupKeys.toList(),
                                            isPlayingPosition = wasPlaying,
                                            waitingPositionIndex = waitingIndex.takeIf { it >= 0 },
                                            fromPlayingPosition = wasPlaying
                                        )
                                    } else {
                                        noShowTarget = selection.copy(fromPlayingPosition = wasPlaying)
                                    }
                                }
                                selectedRegistration = null
                            },
                            onExit = {
                                exitTarget = selection
                                selectedRegistration = null
                            }
                        )
                    } else {
                        LaunchedEffect(selection, queue) { selectedRegistration = null }
                    }
                }

                moveIntoPlayingTarget?.let { selection ->
                    val queue = queueFor(selection.machineId)
                    val currentPlayer = queue.playing.singleOrNull()
                    val joiningPlayer = queue.waitingPositions()
                        .getOrNull(queue.firstAvailableWaitingPositionIndex() ?: -1)
                        ?.firstOrNull { it.key == selection.registrationKey }
                    if (currentPlayer != null && joiningPlayer != null) {
                        MoveIntoPlayingConfirmation(
                            currentPlayer = currentPlayer,
                            joiningPlayer = joiningPlayer,
                            playingPositionLabel = playingPositionName(selection.machineId),
                            fixedPartnerDisplayId = joiningPlayer.fixedPartnerKey?.let { partnerKey ->
                                queue.waiting.firstOrNull { it.key == partnerKey }?.displayId
                            },
                            onDismiss = { moveIntoPlayingTarget = null },
                            onConfirm = {
                                updateQueue(selection.machineId, QueueSoundCue.QUEUE_CHANGE) {
                                    it.moveFirstWaitingRegistrationIntoCurrentRound(
                                        selection.registrationKey
                                    )
                                }
                                moveIntoPlayingTarget = null
                            }
                        )
                    } else {
                        LaunchedEffect(selection, queue) { moveIntoPlayingTarget = null }
                    }
                }

                absenceChoiceTarget?.let { selection ->
                    val queue = queueFor(selection.machineId)
                    val registration = queue.allRegistrations
                        .firstOrNull { it.key == selection.registrationKey }
                    if (registration != null) {
                        QueueAbsenceDialog(
                            displayId = registration.displayId,
                            fixedPartnerDisplayId = registration.fixedPartnerKey?.let { partnerKey ->
                                queue.allRegistrations.firstOrNull { it.key == partnerKey }?.displayId
                            },
                            playingPartnerDisplayId = queue.playing
                                .firstOrNull { it.key != registration.key }
                                ?.displayId,
                            isPlayingPosition = queue.playing.any { it.key == registration.key },
                            playingPositionLabel = playingPositionName(selection.machineId),
                            allowDeferOneRound = queueRuleSettings.allowDeferOneRound,
                            allowTemporaryLeave = queueRuleSettings.allowTemporaryLeave,
                            onDismiss = { absenceChoiceTarget = null },
                            onDeferOneRound = {
                                if (queueRuleSettings.allowDeferOneRound) {
                                    updateQueue(selection.machineId, QueueSoundCue.QUEUE_CHANGE) {
                                        it.deferOneRound(registration.key)
                                    }
                                    absenceChoiceTarget = null
                                }
                            },
                            onTemporarilyLeave = {
                                if (queueRuleSettings.allowTemporaryLeave) {
                                    updateQueue(selection.machineId, QueueSoundCue.QUEUE_CHANGE) {
                                        it.temporarilyLeave(registration.key)
                                    }
                                    absenceChoiceTarget = null
                                }
                            }
                        )
                    } else {
                        LaunchedEffect(selection, queue) { absenceChoiceTarget = null }
                    }
                }

                friendPairTarget?.let { selection ->
                    val queue = queueFor(selection.machineId)
                    val registration = queue.allRegistrations
                        .firstOrNull { it.key == selection.registrationKey }
                    if (registration != null) {
                        FriendPairFlowDialog(
                            machineId = selection.machineId,
                            registration = registration,
                            queue = queue,
                            idAlreadyExists = ::idAlreadyExists,
                            onGenerateFriendId = ::randomUnusedId,
                            onDismiss = {
                                if (stagedFriendPairRegistration == selection) {
                                    updateQueue(selection.machineId) {
                                        it.remove(selection.registrationKey)
                                    }
                                    stagedFriendPairRegistration = null
                                    screen = Screen.PREFERENCE
                                }
                                friendPairTarget = null
                            },
                            onPairExisting = { plan ->
                                val shouldFinishCreation = stagedFriendPairRegistration == selection
                                updateQueue(
                                    selection.machineId,
                                    if (shouldFinishCreation) QueueSoundCue.CONFIRM else QueueSoundCue.QUEUE_CHANGE
                                ) {
                                    val paired = it.applyFriendPair(plan)
                                    if (shouldFinishCreation) paired.enterPlayingPosition() else paired
                                }
                                if (shouldFinishCreation) stagedFriendPairRegistration = null
                                friendPairTarget = null
                            },
                            onCreateFriend = { displayId ->
                                val normalizedId = displayId.trim()
                                if (
                                    normalizedId.isNotBlank() &&
                                    !idAlreadyExists(normalizedId) &&
                                    queueFor(selection.machineId).registrationCount < 20
                                ) {
                                    val shouldFinishCreation = stagedFriendPairRegistration == selection
                                    val friend = Registration(
                                        key = nextKey++,
                                        displayId = normalizedId,
                                        preference = PlayPreference.OPEN_TO_JOIN
                                    )
                                    updateQueue(
                                        selection.machineId,
                                        if (shouldFinishCreation) QueueSoundCue.CONFIRM else QueueSoundCue.QUEUE_CHANGE
                                    ) {
                                        val paired = it.createFriendPair(registration.key, friend)
                                        if (shouldFinishCreation) paired.enterPlayingPosition() else paired
                                    }
                                    if (shouldFinishCreation) stagedFriendPairRegistration = null
                                    friendPairTarget = null
                                }
                            }
                        )
                    } else {
                        LaunchedEffect(selection, queue) {
                            if (stagedFriendPairRegistration == selection) {
                                stagedFriendPairRegistration = null
                                screen = Screen.PREFERENCE
                            }
                            friendPairTarget = null
                        }
                    }
                }

                finishConfirmation?.let { machineId ->
                    val nextPlayingNotice = nextPlayingChangeMessage(
                        queueFor(machineId).nextPlayingPositionPreview()
                    )
                    RoundEndConfirmation(
                        machineName = machineName(machineId),
                        playingPositionLabel = playingPositionName(machineId),
                        nextPlayingNotice = nextPlayingNotice,
                        onDismiss = { finishConfirmation = null },
                        onConfirm = {
                            updateQueueWithUndo(
                                machineId,
                                "${machineName(machineId)} 的本轮已结束"
                            ) { it.finishRound() }
                            finishConfirmation = null
                        },
                        onEndOnly = {
                            updateQueueWithUndo(
                                machineId,
                                "${machineName(machineId)} 的本轮已结束"
                            ) { it.endRoundWithoutStartingNext() }
                            finishConfirmation = null
                        }
                    )
                }

                enterPlayingConfirmation?.let { machineId ->
                    val queue = queueFor(machineId)
                    val preview = queue.nextPlayingPositionPreview()
                    val notice = nextPlayingChangeMessage(preview)
                    if (preview?.changedByAbsence == true && notice != null) {
                        EnterPlayingConfirmation(
                            playingPositionLabel = playingPositionName(machineId),
                            notice = notice,
                            onDismiss = { enterPlayingConfirmation = null },
                            onConfirm = {
                                updateQueue(machineId, QueueSoundCue.QUEUE_CHANGE) {
                                    it.enterPlayingPosition()
                                }
                                enterPlayingConfirmation = null
                            }
                        )
                    } else {
                        LaunchedEffect(machineId, preview) { enterPlayingConfirmation = null }
                    }
                }

                if (moreMenuVisible) {
                    MoreMenu(
                        registrationOpen = registrationOpen,
                        canEditRegistrations =
                            (machineAStatus.isOperational && machineA.waiting.isNotEmpty()) ||
                                (machineBStatus.isOperational && machineB.waiting.isNotEmpty()),
                        canReportMachineStop = machineAStatus.isOperational || machineBStatus.isOperational,
                        onDismiss = { moreMenuVisible = false },
                        onEditRegistrations = {
                            moreMenuVisible = false
                            editMachineChoiceVisible = true
                        },
                        onOpenAuditLog = {
                            moreMenuVisible = false
                            screen = Screen.AUDIT_LOG
                        },
                        onOpenSettings = {
                            moreMenuVisible = false
                            screen = Screen.SETTINGS
                        },
                        onOpenAppDetails = {
                            moreMenuVisible = false
                            appDetailsVisible = true
                        },
                        onReportMachineStop = {
                            moreMenuVisible = false
                            stopMachineChoiceVisible = true
                        },
                        onToggleRegistration = {
                            moreMenuVisible = false
                            if (registrationOpen) closeQueueConfirmation = true
                            else reopenRegistration()
                        }
                    )
                }

                if (appDetailsVisible) {
                    AppDetailsDialog(
                        cloudSyncStatus = cloudSyncStatus,
                        onDismiss = { appDetailsVisible = false }
                    )
                }

                if (editMachineChoiceVisible) {
                    EditMachineChooser(
                        machineA = machineA,
                        machineB = machineB,
                        machineAStatus = machineAStatus,
                        machineBStatus = machineBStatus,
                        onDismiss = { editMachineChoiceVisible = false },
                        onSelect = { machineId ->
                            editMachineChoiceVisible = false
                            beginReorder(machineId, true)
                        }
                    )
                }

                if (stopMachineChoiceVisible) {
                    StopMachineChooser(
                        machineAStatus = machineAStatus,
                        machineBStatus = machineBStatus,
                        onDismiss = { stopMachineChoiceVisible = false },
                        onSelect = { machineId ->
                            if (statusFor(machineId).isOperational) {
                                stopMachineChoiceVisible = false
                                stopReasonTarget = machineId
                            }
                        }
                    )
                }

                stopReasonTarget?.let { machineId ->
                    StopMachineReasonDialog(
                        machineName = machineName(machineId),
                        registrationCount = queueFor(machineId).registrationCount,
                        onDismiss = { stopReasonTarget = null },
                        onSelect = { reason ->
                            reportMachineStopped(machineId, reason)
                            stopReasonTarget = null
                        }
                    )
                }

                claimPreferenceMismatchProfileId?.let { profileId ->
                    val profile = playerProfiles.firstOrNull { it.id == profileId }
                    val selection = claimTarget
                    val registration = selection?.let { target ->
                        queueFor(target.machineId).allRegistrations
                            .firstOrNull { it.key == target.registrationKey }
                    }
                    val profilePreference = profile?.defaultPreference?.toPlayPreferenceOrNull()
                    if (profile != null && registration != null && profilePreference != null) {
                        ClaimPreferenceMismatchDialog(
                            profileNickname = profile.nickname,
                            currentPreferenceLabel = playPreferenceLabel(registration),
                            profilePreference = profilePreference,
                            fixedPartnerDisplayId = registration.fixedPartnerKey?.let { partnerKey ->
                                queueFor(selection.machineId).allRegistrations
                                    .firstOrNull { it.key == partnerKey }
                                    ?.takeIf { it.fixedPartnerKey == registration.key }
                                    ?.displayId
                            },
                            onDismiss = { claimPreferenceMismatchProfileId = null },
                            onKeepCurrent = { completePlayerProfileClaim() },
                            onUseProfileDefault = {
                                completePlayerProfileClaim(profilePreference)
                            }
                        )
                    } else {
                        LaunchedEffect(profileId, selection, registration) {
                            claimPreferenceMismatchProfileId = null
                        }
                    }
                }

                selectedPosition?.let { selection ->
                    val transferTargetMachineId = otherMachine(selection.machineId)
                    val transferUnavailableReason = machineTransferUnavailableReason(
                        machineName = machineName(transferTargetMachineId),
                        status = statusFor(transferTargetMachineId),
                        queue = queueFor(transferTargetMachineId),
                        incomingRegistrationCount = selection.registrationKeys.size
                    )
                    PositionActions(
                        selection = selection,
                        queue = queueFor(selection.machineId),
                        transferMachineName = machineName(transferTargetMachineId),
                        transferUnavailableReason = transferUnavailableReason,
                        onDismiss = { selectedPosition = null },
                        onRegistrationClick = { registrationKey ->
                            selectedPosition = null
                            openRegistration(selection.machineId, registrationKey)
                        },
                        onFinishRound = {
                            selectedPosition = null
                            finishConfirmation = selection.machineId
                        },
                        onReturnToWaitingFront = {
                            returnPlayingTarget = selection
                            selectedPosition = null
                        },
                        onAdvanceToPlaying = {
                            advanceToPlayingTarget = selection
                            selectedPosition = null
                        },
                        onEnterPlaying = {
                            val preview = queueFor(selection.machineId).nextPlayingPositionPreview()
                            if (preview?.changedByAbsence == true) {
                                enterPlayingConfirmation = selection.machineId
                            } else {
                                updateQueue(selection.machineId) { it.enterPlayingPosition() }
                            }
                            selectedPosition = null
                        },
                        onTransfer = {
                            machineTransferTarget = MachineTransferRequest(
                                selection.machineId,
                                selection.registrationKeys
                            )
                            selectedPosition = null
                        },
                        onReleaseFixedPair = {
                            releaseFixedPairTarget = selection
                            selectedPosition = null
                        },
                        onNoShow = {
                            val currentQueue = queueFor(selection.machineId)
                            val canReportNoShow = selection.registrationKeys.isNotEmpty() &&
                                selection.registrationKeys.all(currentQueue::canMarkNoShow)
                            if (canReportNoShow) {
                                val wasPlaying = selection.isPlayingPosition &&
                                    currentQueue.playing.any { it.key in selection.registrationKeys }
                                if (selection.registrationKeys.size == 1) {
                                    noShowTarget = SelectedRegistration(
                                        selection.machineId,
                                        selection.registrationKeys.first(),
                                        fromPlayingPosition = wasPlaying
                                    )
                                } else {
                                    groupNoShowTarget = selection.copy(
                                        fromPlayingPosition = wasPlaying
                                    )
                                }
                            }
                            selectedPosition = null
                        },
                        onRemove = {
                            if (selection.registrationKeys.size == 1) {
                                exitTarget = SelectedRegistration(
                                    selection.machineId,
                                    selection.registrationKeys.first()
                                )
                            } else {
                                removeGroupTarget = selection
                            }
                            selectedPosition = null
                        }
                    )
                }

                returnPlayingTarget?.let { selection ->
                    ReturnPlayingToWaitingConfirmation(
                        playingPositionLabel = playingPositionName(selection.machineId),
                        onDismiss = { returnPlayingTarget = null },
                        onConfirm = {
                            updateQueue(selection.machineId) {
                                it.returnPlayingRegistrationsToWaitingFront(
                                    selection.registrationKeys.toSet()
                                )
                            }
                            returnPlayingTarget = null
                        }
                    )
                }

                returnPlayingRegistrationTarget?.let { selection ->
                    val queue = queueFor(selection.machineId)
                    val registration = queue.playing
                        .firstOrNull { it.key == selection.registrationKey }
                    if (registration != null) {
                        val remainingPlayer = queue.playing
                            .firstOrNull { it.key != registration.key }
                        val isFixedPair = remainingPlayer != null &&
                            registration.fixedPartnerKey == remainingPlayer.key &&
                            remainingPlayer.fixedPartnerKey == registration.key
                        ReturnPlayingRegistrationConfirmation(
                            registration = registration,
                            remainingPlayer = remainingPlayer,
                            isFixedPair = isFixedPair,
                            playingPositionLabel = playingPositionName(selection.machineId),
                            onDismiss = { returnPlayingRegistrationTarget = null },
                            onConfirm = {
                                updateQueue(selection.machineId) {
                                    it.returnPlayingRegistrationsToWaitingFront(
                                        setOf(selection.registrationKey)
                                    )
                                }
                                returnPlayingRegistrationTarget = null
                            }
                        )
                    } else {
                        LaunchedEffect(selection, queue) { returnPlayingRegistrationTarget = null }
                    }
                }

                advanceToPlayingTarget?.let { selection ->
                    val queue = queueFor(selection.machineId)
                    val positions = queue.waitingPositions()
                    val targetKeys = selection.registrationKeys.toSet()
                    val targetIndex = positions.indexOfFirst { position ->
                        position.size == targetKeys.size && position.all { it.key in targetKeys }
                    }
                    AdvanceToPlayingConfirmation(
                        selectionLabel = selection.label.substringBefore(" · "),
                        playingPositionLabel = playingPositionName(selection.machineId),
                        registrations = positions.getOrNull(targetIndex).orEmpty(),
                        completedWaitingPositionCount = targetIndex.coerceAtLeast(0),
                        enabled = queue.playing.isNotEmpty() && targetIndex > 0,
                        onDismiss = { advanceToPlayingTarget = null },
                        onConfirm = {
                            updateQueueWithUndo(
                                selection.machineId,
                                "${playingPositionName(selection.machineId)} 已校正"
                            ) {
                                it.advanceToWaitingPosition(targetKeys)
                            }
                            advanceToPlayingTarget = null
                        }
                    )
                }

                releaseFixedPairTarget?.let { selection ->
                    val registrations = queueFor(selection.machineId).allRegistrations
                        .filter { it.key in selection.registrationKeys }
                    ReleaseFixedPairConfirmation(
                        registrations = registrations,
                        onDismiss = { releaseFixedPairTarget = null },
                        onConfirm = {
                            val firstKey = registrations.firstOrNull()?.key
                            if (firstKey != null) {
                                updateQueue(selection.machineId) {
                                    it.changePreference(firstKey, PlayPreference.OPEN_TO_JOIN)
                                }
                            }
                            releaseFixedPairTarget = null
                        }
                    )
                }

                machineTransferTarget?.let { request ->
                    val sourceQueue = queueFor(request.sourceMachineId)
                    val destinationMachineId = otherMachine(request.sourceMachineId)
                    val registrations = sourceQueue.allRegistrations
                        .filter { it.key in request.registrationKeys }
                    val transferEnabled = registrations.isNotEmpty() &&
                        queueFor(destinationMachineId).registrationCount + registrations.size <= 20
                    MachineTransferConfirmation(
                        registrations = registrations,
                        sourceMachineName = machineName(request.sourceMachineId),
                        destinationMachineName = machineName(destinationMachineId),
                        sourcePlayingPositionLabel = playingPositionName(request.sourceMachineId),
                        leavingPlayingPosition = sourceQueue.playing
                            .any { it.key in request.registrationKeys },
                        transferEnabled = transferEnabled,
                        onDismiss = { machineTransferTarget = null },
                        onConfirm = {
                            transferRegistrations(request)
                            machineTransferTarget = null
                        }
                    )
                }

                noShowTarget?.let { selection ->
                    val queue = queueFor(selection.machineId)
                    val registration = queue.allRegistrations
                        .firstOrNull { it.key == selection.registrationKey }
                    if (registration != null) {
                        NoShowDialog(
                            registration = registration,
                            fromPlayingPosition = selection.fromPlayingPosition,
                            playingPositionLabel = playingPositionName(selection.machineId),
                            waitingFrontPositionLabel = waitingFrontPositionName(selection.machineId),
                            allowDeferOneRound = queueRuleSettings.allowDeferOneRound,
                            onDismiss = { noShowTarget = null },
                            onDefer = {
                                if (queueRuleSettings.allowDeferOneRound) {
                                    updateQueue(
                                        selection.machineId,
                                        QueueSoundCue.CAUTION,
                                        PublicQueueEventType.NO_SHOW_DEFERRED,
                                        setOf(registration.key)
                                    ) {
                                        it.markNoShowDeferOneRound(
                                            registration.key,
                                            startNextWhenPlayingBecomesEmpty = !selection.fromPlayingPosition
                                        )
                                    }
                                    noShowTarget = null
                                }
                            },
                            onMoveToEnd = {
                                updateQueue(
                                    selection.machineId,
                                    QueueSoundCue.CAUTION,
                                    PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL,
                                    setOf(registration.key)
                                ) {
                                    it.markNoShowMoveToEnd(
                                        setOf(registration.key),
                                        startNextWhenPlayingBecomesEmpty = !selection.fromPlayingPosition
                                    )
                                }
                                noShowTarget = null
                            },
                            onRemove = {
                                updateQueue(
                                    selection.machineId,
                                    QueueSoundCue.CAUTION,
                                    PublicQueueEventType.NO_SHOW_REMOVED,
                                    setOf(registration.key)
                                ) {
                                    it.markNoShowAndRemove(
                                        setOf(registration.key),
                                        startNextWhenPlayingBecomesEmpty = !selection.fromPlayingPosition
                                    )
                                }
                                noShowTarget = null
                            }
                        )
                    } else {
                        LaunchedEffect(selection, queue) { noShowTarget = null }
                    }
                }

                groupNoShowTarget?.let { selection ->
                    val queue = queueFor(selection.machineId)
                    val targetKeys = selection.registrationKeys.toSet()
                    val registrations = queue.allRegistrations
                        .filter { it.key in selection.registrationKeys }
                    if (registrations.isNotEmpty() && registrations.map { it.key }.toSet() == targetKeys) {
                        GroupNoShowDialog(
                            registrations = registrations,
                            fromPlayingPosition = selection.fromPlayingPosition,
                            playingPositionLabel = playingPositionName(selection.machineId),
                            waitingFrontPositionLabel = waitingFrontPositionName(selection.machineId),
                            allowDeferOneRound = queueRuleSettings.allowDeferOneRound,
                            onDismiss = { groupNoShowTarget = null },
                            onDefer = {
                                if (queueRuleSettings.allowDeferOneRound) {
                                    updateQueue(
                                        selection.machineId,
                                        QueueSoundCue.CAUTION,
                                        PublicQueueEventType.NO_SHOW_DEFERRED,
                                        targetKeys
                                    ) {
                                        it.markNoShowGroupDeferOneRound(
                                            targetKeys,
                                            startNextWhenPlayingBecomesEmpty = !selection.fromPlayingPosition
                                        )
                                    }
                                    groupNoShowTarget = null
                                }
                            },
                            onMoveToEnd = {
                                updateQueue(
                                    selection.machineId,
                                    QueueSoundCue.CAUTION,
                                    PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL,
                                    targetKeys
                                ) {
                                    it.markNoShowMoveToEnd(
                                        targetKeys,
                                        startNextWhenPlayingBecomesEmpty = !selection.fromPlayingPosition
                                    )
                                }
                                groupNoShowTarget = null
                            },
                            onRemove = {
                                updateQueue(
                                    selection.machineId,
                                    QueueSoundCue.CAUTION,
                                    PublicQueueEventType.NO_SHOW_REMOVED,
                                    targetKeys
                                ) {
                                    it.markNoShowAndRemove(
                                        targetKeys,
                                        startNextWhenPlayingBecomesEmpty = !selection.fromPlayingPosition
                                    )
                                }
                                groupNoShowTarget = null
                            }
                        )
                    } else {
                        LaunchedEffect(selection, queue) { groupNoShowTarget = null }
                    }
                }

                exitTarget?.let { selection ->
                    val exitQueue = queueFor(selection.machineId)
                    val exitRegistration = exitQueue.allRegistrations
                        .firstOrNull { it.key == selection.registrationKey }
                    if (exitRegistration != null) {
                        val fixedPartnerDisplayId = exitRegistration.fixedPartnerKey?.let { partnerKey ->
                            exitQueue.allRegistrations.firstOrNull { it.key == partnerKey }
                                ?.takeIf { it.fixedPartnerKey == exitRegistration.key }
                                ?.displayId
                        }
                        RemoveRegistrationConfirmation(
                            title = "退出排队？",
                            message = if (fixedPartnerDisplayId != null) {
                                "“${exitRegistration.displayId}”退出后，这份登记将立即失效。与“$fixedPartnerDisplayId”的固定组合也会解除；对方会保留在原位置，并恢复为允许他人加入。若仍想游玩，需要重新加入队尾。"
                            } else {
                                "“${exitRegistration.displayId}”退出后，这份登记将立即失效。若仍想游玩，需要重新加入队尾。"
                            },
                            confirmText = "确认退出",
                            onDismiss = { exitTarget = null },
                            onConfirm = {
                                updateQueue(selection.machineId) {
                                    it.remove(selection.registrationKey)
                                }
                                exitTarget = null
                            }
                        )
                    } else {
                        LaunchedEffect(selection, exitQueue) { exitTarget = null }
                    }
                }

                removeGroupTarget?.let { selection ->
                    val queue = queueFor(selection.machineId)
                    val targetKeys = selection.registrationKeys.toSet()
                    val allTargetsExist = targetKeys.isNotEmpty() &&
                        queue.allRegistrations.count { it.key in targetKeys } == targetKeys.size
                    if (allTargetsExist) {
                        RemoveRegistrationConfirmation(
                            title = "移除这组登记？",
                            message = "这会同时移除该位置中的 ${targetKeys.size} 份登记。玩家如需继续游玩，只能重新加入队尾。",
                            confirmText = "移除这组登记",
                            onDismiss = { removeGroupTarget = null },
                            onConfirm = {
                                updateQueue(selection.machineId) { it.removeAll(targetKeys) }
                                removeGroupTarget = null
                            }
                        )
                    } else {
                        LaunchedEffect(selection, queue) { removeGroupTarget = null }
                    }
                }

                if (closeQueueConfirmation) {
                    CloseRegistrationConfirmation(
                        onDismiss = { closeQueueConfirmation = false },
                        onConfirm = {
                            closeRegistration()
                            screen = Screen.HOME
                            closeQueueConfirmation = false
                        }
                    )
                }

                if (!queuePersistenceReady) {
                    QueueStateLoadingOverlay()
                } else {
                    pendingQueueRestore?.let { savedState ->
                        QueueRestoreDialog(
                            savedState = savedState,
                            onRestore = { restorePreviousQueue(savedState) },
                            onStartNew = { startWithNewQueue(savedState) }
                        )
                    }
                }
                }
                GlobalDragOverlay(globalDragOverlayController)
            }
        }
    }
}

@Composable
private fun AuditLogScreen(
    logs: List<AuditLogEntry>,
    onBack: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf<AuditLogCategory?>(null) }
    val filters: List<Pair<AuditLogCategory?, String>> = listOf(
        null to "全部",
        AuditLogCategory.MACHINE_A to "机台 A",
        AuditLogCategory.MACHINE_B to "机台 B",
        AuditLogCategory.SYSTEM to "系统",
        AuditLogCategory.PLAYER_PROFILE to "玩家资料"
    )
    val displayedLogs = selectedCategory?.let { category ->
        logs.filter { it.category == category }
    } ?: logs

    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppBackButton(onBack)
            Spacer(Modifier.weight(1f))
            Text("操作日志", color = TertiaryText, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxSize().widthIn(max = 980.dp).align(Alignment.CenterHorizontally)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("操作日志", color = PrimaryText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("共 ${displayedLogs.size} 条记录", color = SecondaryText, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filters.forEach { (category, label) ->
                    val selected = selectedCategory == category
                    Box(
                        Modifier.width(112.dp).height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) PrimaryText else CardBackground)
                            .border(
                                1.dp,
                                if (selected) PrimaryText else Separator,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = !selected) { selectedCategory = category },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (selected) Color.White else PrimaryText,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (displayedLogs.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (logs.isEmpty()) "还没有操作日志" else "此分类还没有记录",
                            color = PrimaryText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("新的变动会显示在这里。", color = SecondaryText, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(displayedLogs, key = { _, entry -> entry.id }) { _, entry ->
                        AuditLogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRuleSettingsScreen(
    settings: QueueRuleSettings,
    onSettingsChange: (QueueRuleSettings) -> Unit,
    onBack: () -> Unit
) {
    var machineARemarkDraft by remember(settings.machineARemark) {
        mutableStateOf(settings.machineARemark)
    }
    var machineBRemarkDraft by remember(settings.machineBRemark) {
        mutableStateOf(settings.machineBRemark)
    }
    val normalizedMachineARemark = machineARemarkDraft.trim()
    val normalizedMachineBRemark = machineBRemarkDraft.trim()
    val remarksValid = normalizedMachineARemark.isNotBlank() && normalizedMachineBRemark.isNotBlank()
    val remarksChanged = normalizedMachineARemark != settings.machineARemark ||
        normalizedMachineBRemark != settings.machineBRemark

    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppBackButton(onBack)
            Spacer(Modifier.weight(1f))
            Text("应用设置", color = TertiaryText, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxWidth().weight(1f).widthIn(max = 760.dp)
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp)
        ) {
            Text("应用设置", color = PrimaryText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "调整网站同步、排队规则和首页机台备注。机台 A 与机台 B 是固定标识，不能修改。",
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(20.dp))
            MenuSectionHeader("网站连接")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBackground)
                    .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(12.dp))
            ) {
                QueueRuleSettingRow(
                    title = "网站同步",
                    description = if (settings.websiteSyncEnabled) {
                        "将最新队列状态上传到网站。关闭后，网站会保留最后一次已同步的内容。"
                    } else {
                        "队列只在本机更新。重新开启后，会立即上传当前完整队列。"
                    },
                    checked = settings.websiteSyncEnabled,
                    onCheckedChange = {
                        onSettingsChange(settings.copy(websiteSyncEnabled = it))
                    }
                )
            }
            Spacer(Modifier.height(20.dp))
            MenuSectionHeader("排队规则")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBackground)
                    .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(12.dp))
            ) {
                QueueRuleSettingRow(
                    title = "允许暂缓一轮",
                    description = "允许玩家跳过一次游玩机会，并保留当前等待顺序。",
                    checked = settings.allowDeferOneRound,
                    onCheckedChange = {
                        onSettingsChange(settings.copy(allowDeferOneRound = it))
                    }
                )
                HorizontalDivider(color = Separator.copy(alpha = .72f))
                QueueRuleSettingRow(
                    title = "允许暂时离开",
                    description = "允许玩家在返回前持续轮空，并在返回后手动恢复。",
                    checked = settings.allowTemporaryLeave,
                    onCheckedChange = {
                        onSettingsChange(settings.copy(allowTemporaryLeave = it))
                    }
                )
            }
            Spacer(Modifier.height(20.dp))
            MenuSectionHeader("首页机台显示")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius)).background(CardBackground)
                    .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(CardRadius))
                    .padding(16.dp)
            ) {
                Text(
                    "备注用于帮助玩家辨认机台的现场位置，固定标识“机台 A”和“机台 B”始终保留。",
                    color = SecondaryText,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MachineRemarkField(
                        machineLabel = "机台 A",
                        value = machineARemarkDraft,
                        onValueChange = { machineARemarkDraft = limitMachineRemarkLength(it) },
                        modifier = Modifier.weight(1f)
                    )
                    MachineRemarkField(
                        machineLabel = "机台 B",
                        value = machineBRemarkDraft,
                        onValueChange = { machineBRemarkDraft = limitMachineRemarkLength(it) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PrimaryButton(
                        text = "保存机台备注",
                        onClick = {
                            onSettingsChange(
                                settings.copy(
                                    machineARemark = normalizedMachineARemark,
                                    machineBRemark = normalizedMachineBRemark
                                )
                            )
                        },
                        modifier = Modifier.width(180.dp),
                        enabled = remarksValid && remarksChanged
                    )
                }
            }
        }
    }
}

@Composable
private fun MachineRemarkField(
    machineLabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isBlank = value.isBlank()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text("$machineLabel 备注") },
        placeholder = { Text("例如：入口侧") },
        singleLine = true,
        isError = isBlank,
        supportingText = {
            Text(if (isBlank) "请输入备注。" else "最多 $MAX_MACHINE_REMARK_CHARACTERS 个字符。")
        },
        shape = RoundedCornerShape(ControlRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PrimaryText,
            unfocusedTextColor = PrimaryText,
            focusedBorderColor = SystemBlue,
            unfocusedBorderColor = Color(0xFFA7A7AC),
            focusedLabelColor = SystemBlue,
            unfocusedLabelColor = SecondaryText,
            focusedPlaceholderColor = SecondaryText,
            unfocusedPlaceholderColor = SecondaryText,
            focusedSupportingTextColor = SecondaryText,
            unfocusedSupportingTextColor = SecondaryText,
            focusedContainerColor = CardBackground,
            unfocusedContainerColor = CardBackground,
            cursorColor = SystemBlue
        )
    )
}

@Composable
private fun QueueRuleSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onCheckedChange
        )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(description, color = SecondaryText, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SystemBlue,
                uncheckedThumbColor = TertiaryText,
                uncheckedTrackColor = Separator
            )
        )
    }
}

@Composable
private fun AuditLogRow(entry: AuditLogEntry) {
    val (categoryBackground, categoryForeground) = when (entry.category) {
        AuditLogCategory.MACHINE_A -> SoftBlue to SystemBlue
        AuditLogCategory.MACHINE_B -> Color(0xFFEAF8EF) to Color(0xFF248A4B)
        AuditLogCategory.SYSTEM -> Color(0xFFEEEEF0) to SecondaryText
        AuditLogCategory.PLAYER_PROFILE -> Color(0xFFFFEDF3) to Color(0xFFC02D62)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CardBackground)
            .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.width(72.dp).height(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(categoryBackground),
            contentAlignment = Alignment.Center
        ) {
            Text(
                auditLogCategoryLabel(entry.category),
                color = categoryForeground,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, color = PrimaryText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (entry.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(entry.detail, color = SecondaryText, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            formatAuditLogTimestamp(entry.timestampMillis),
            color = TertiaryText,
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(180.dp)
        )
    }
}

private fun auditLogCategoryLabel(category: AuditLogCategory): String = when (category) {
    AuditLogCategory.MACHINE_A -> "机台 A"
    AuditLogCategory.MACHINE_B -> "机台 B"
    AuditLogCategory.SYSTEM -> "系统"
    AuditLogCategory.PLAYER_PROFILE -> "玩家资料"
}

private fun formatAuditLogTimestamp(timestampMillis: Long): String =
    SimpleDateFormat("yyyy 年 M 月 d 日 HH:mm:ss", Locale.CHINA).format(Date(timestampMillis))

@Composable
private fun HomeScreen(
    machineA: MachineQueue,
    machineB: MachineQueue,
    machineAStatus: MachineStatus,
    machineBStatus: MachineStatus,
    machineARemark: String,
    machineBRemark: String,
    registrationOpen: Boolean,
    cloudSyncStatus: QueueCloudSyncStatus,
    queueUndoAction: QueueUndoAction?,
    onUndoQueueAction: () -> Unit,
    inlineReorderSession: ReorderSession?,
    inlineReorderResetToken: Int,
    positionReorderResetToken: Int,
    onInlineReorderCancel: () -> Unit,
    onInlineReorderProposal: (MachineId, MachineQueue, List<Registration>, Int) -> Unit,
    onEnableRegistration: () -> Unit,
    onJoin: () -> Unit,
    onJoinMachine: (MachineId) -> Unit,
    onBatch: () -> Unit,
    onMore: () -> Unit,
    onFinishRequest: (MachineId) -> Unit,
    onEnterPlaying: (MachineId) -> Unit,
    onRestoreMachine: (MachineId) -> Unit,
    onRegistrationClick: (MachineId, Int) -> Unit,
    onRegistrationLongPress: (MachineId, Int) -> Unit,
    onPositionClick: (PositionSelection) -> Unit,
    onPositionReorderRequest: (MachineId, MachineQueue, Int, Int) -> Unit
) {
    val isEmpty = machineA.registrationCount == 0 && machineB.registrationCount == 0
    val hasStoppedMachine = !machineAStatus.isOperational || !machineBStatus.isOperational
    val nowMillis = rememberCurrentTimeMillis()
    var cloudSyncInfoVisible by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
            AppHeader(
                nowMillis = nowMillis,
                registrationOpen = registrationOpen,
                totalRegistrationCount = machineA.registrationCount + machineB.registrationCount,
                cloudSyncStatus = cloudSyncStatus,
                onCloudSyncClick = { cloudSyncInfoVisible = true },
                onMore = onMore
            )
            Spacer(Modifier.height(11.dp))
            HorizontalDivider(color = Separator.copy(alpha = .72f))
            Spacer(Modifier.height(12.dp))
            if (!registrationOpen && !hasStoppedMachine) {
                ClosedHome(onEnableRegistration)
            } else if (isEmpty && !hasStoppedMachine) {
                EmptyHome(onJoin, onBatch)
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1.9f).fillMaxHeight()) {
                        MachineLane(
                            machineId = MachineId.A,
                            remark = machineARemark,
                            queue = machineA,
                            status = machineAStatus,
                            registrationOpen = registrationOpen,
                            nowMillis = nowMillis,
                            inlineReorderSession = inlineReorderSession?.takeIf { it.machineId == MachineId.A },
                            inlineReorderResetToken = inlineReorderResetToken,
                            positionReorderResetToken = positionReorderResetToken,
                            onInlineReorderCancel = onInlineReorderCancel,
                            onInlineReorderProposal = { originalQueue, proposed, movedKey ->
                                onInlineReorderProposal(MachineId.A, originalQueue, proposed, movedKey)
                            },
                            onFinishRequest = { onFinishRequest(MachineId.A) },
                            onEnterPlaying = { onEnterPlaying(MachineId.A) },
                            onRestore = { onRestoreMachine(MachineId.A) },
                            onJoinThisMachine = { onJoinMachine(MachineId.A) },
                            onRegistrationClick = { onRegistrationClick(MachineId.A, it) },
                            onRegistrationLongPress = { onRegistrationLongPress(MachineId.A, it) },
                            onPositionClick = onPositionClick,
                            onPositionReorderRequest = { queue, sourceIndex, destinationIndex ->
                                onPositionReorderRequest(MachineId.A, queue, sourceIndex, destinationIndex)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider(color = Separator.copy(alpha = .64f))
                        Spacer(Modifier.height(6.dp))
                        MachineLane(
                            machineId = MachineId.B,
                            remark = machineBRemark,
                            queue = machineB,
                            status = machineBStatus,
                            registrationOpen = registrationOpen,
                            nowMillis = nowMillis,
                            inlineReorderSession = inlineReorderSession?.takeIf { it.machineId == MachineId.B },
                            inlineReorderResetToken = inlineReorderResetToken,
                            positionReorderResetToken = positionReorderResetToken,
                            onInlineReorderCancel = onInlineReorderCancel,
                            onInlineReorderProposal = { originalQueue, proposed, movedKey ->
                                onInlineReorderProposal(MachineId.B, originalQueue, proposed, movedKey)
                            },
                            onFinishRequest = { onFinishRequest(MachineId.B) },
                            onEnterPlaying = { onEnterPlaying(MachineId.B) },
                            onRestore = { onRestoreMachine(MachineId.B) },
                            onJoinThisMachine = { onJoinMachine(MachineId.B) },
                            onRegistrationClick = { onRegistrationClick(MachineId.B, it) },
                            onRegistrationLongPress = { onRegistrationLongPress(MachineId.B, it) },
                            onPositionClick = onPositionClick,
                            onPositionReorderRequest = { queue, sourceIndex, destinationIndex ->
                                onPositionReorderRequest(MachineId.B, queue, sourceIndex, destinationIndex)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    JoinPanel(
                        machineA = machineA,
                        machineB = machineB,
                        machineAStatus = machineAStatus,
                        machineBStatus = machineBStatus,
                        nowMillis = nowMillis,
                        registrationOpen = registrationOpen,
                        onJoin = onJoin,
                        onBatch = onBatch,
                        modifier = Modifier.weight(.72f).fillMaxHeight()
                    )
                }
            }
        }
        AnimatedContent(
            targetState = queueUndoAction,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            transitionSpec = {
                (fadeIn(tween(180)) + slideInVertically(tween(220)) { height -> height / 2 })
                    .togetherWith(
                        fadeOut(tween(140)) + slideOutVertically(tween(180)) { height -> height / 2 }
                    )
            },
            label = "队列操作撤销提示"
        ) { action ->
            if (action == null) {
                Spacer(Modifier.size(0.dp))
            } else {
                QueueUndoBar(action.message, onUndoQueueAction)
            }
        }
        if (cloudSyncInfoVisible) {
            CloudSyncInfoDialog(
                status = cloudSyncStatus,
                onDismiss = { cloudSyncInfoVisible = false }
            )
        }
    }
}

@Composable
private fun QueueUndoBar(message: String, onUndo: () -> Unit) {
    Surface(
        color = Color(0xFF2C2C2E),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 8.dp
    ) {
        Row(
            Modifier.height(48.dp).widthIn(min = 330.dp, max = 520.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f).padding(start = 18.dp, end = 14.dp)
            )
            Box(
                Modifier.fillMaxHeight().clickable(onClick = onUndo).padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "撤销",
                    color = Color(0xFF75B7FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun AppHeader(
    nowMillis: Long,
    registrationOpen: Boolean,
    totalRegistrationCount: Int,
    cloudSyncStatus: QueueCloudSyncStatus,
    onCloudSyncClick: () -> Unit,
    onMore: () -> Unit
) {
    val context = LocalContext.current
    val batteryManager = remember(context) { context.getSystemService(BatteryManager::class.java) }
    val batteryLevel = remember(nowMillis, batteryManager) {
        batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("排队登记", color = PrimaryText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("两台机台的登记顺序彼此独立", color = SecondaryText, fontSize = 13.sp)
                Text(" · ", color = TertiaryText, fontSize = 13.sp)
                Text(
                    "当前共 $totalRegistrationCount 个登记",
                    color = PrimaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(nowMillis)),
            color = PrimaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(1.dp).height(16.dp).background(Separator))
        Spacer(Modifier.width(10.dp))
        Text(
            "电量 ${batteryLevel?.let { "$it%" } ?: "--"}",
            color = SecondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(16.dp))
        CloudSyncIndicator(cloudSyncStatus, onCloudSyncClick)
        AnimatedVisibility(
            visible = !registrationOpen,
            enter = fadeIn(tween(180)) + expandHorizontally(tween(220), expandFrom = Alignment.End),
            exit = fadeOut(tween(140)) + shrinkHorizontally(tween(180), shrinkTowards = Alignment.End)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(18.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF9500)))
                Spacer(Modifier.width(7.dp))
                Text("未使用登记排队", color = SecondaryText, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(18.dp))
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(CardBackground)
                .border(1.dp, Separator.copy(alpha = .7f), CircleShape)
                .clickable(onClick = onMore),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "更多",
                tint = PrimaryText,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun CloudSyncIndicator(status: QueueCloudSyncStatus, onClick: () -> Unit) {
    val statusColor by animateColorAsState(
        queueCloudSyncStatusColor(status.phase),
        tween(220),
        label = "同步状态颜色"
    )
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(statusColor.copy(alpha = .09f))
            .clickable(onClick = onClick)
            .animateContentSize(tween(180))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
        Spacer(Modifier.width(6.dp))
        Text(
            queueCloudSyncShortLabel(status.phase),
            color = statusColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyHome(onJoin: () -> Unit, onBatch: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("目前没有登记", color = PrimaryText, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        VisuallyCenteredSentence("两台机台都没有正在等待的玩家。", SecondaryText, 16.sp)
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("加入排队", onJoin, Modifier.width(260.dp))
            SecondaryButton("批量创建登记", onBatch, Modifier.width(190.dp))
        }
        Spacer(Modifier.height(12.dp))
        VisuallyCenteredSentence("创建你的登记并加入排队。", SecondaryText, 13.sp)
    }
}

@Composable
private fun ClosedHome(onEnableRegistration: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("请在现场自然排队", color = PrimaryText, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        VisuallyCenteredSentence("当前未使用登记排队，请按照现场顺序依次游玩。", SecondaryText, 16.sp)
        Spacer(Modifier.height(30.dp))
        PrimaryButton("启用登记排队", onEnableRegistration, Modifier.width(260.dp))
    }
}

/**
 * Keeps trailing full-width punctuation visible without including it in the
 * measured width used by a parent to center the sentence.
 */
@Composable
private fun VisuallyCenteredSentence(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val trailingPunctuation = text.takeLastWhile { it in "。，！？；：" }
    if (trailingPunctuation.isEmpty()) {
        Text(text, color = color, fontSize = fontSize, modifier = modifier)
        return
    }

    val sentence = text.dropLast(trailingPunctuation.length)
    Layout(
        modifier = modifier,
        content = {
            Text(sentence, color = color, fontSize = fontSize, maxLines = 1)
            Text(trailingPunctuation, color = color, fontSize = fontSize, maxLines = 1)
        }
    ) { measurables, constraints ->
        val sentencePlaceable = measurables[0].measure(constraints)
        val punctuationPlaceable = measurables[1].measure(constraints.copy(minWidth = 0))
        val height = maxOf(sentencePlaceable.height, punctuationPlaceable.height)
        layout(sentencePlaceable.width, height) {
            sentencePlaceable.placeRelative(0, (height - sentencePlaceable.height) / 2)
            punctuationPlaceable.placeRelative(
                sentencePlaceable.width,
                (height - punctuationPlaceable.height) / 2
            )
        }
    }
}

@Composable
private fun MachineLane(
    machineId: MachineId,
    remark: String,
    queue: MachineQueue,
    status: MachineStatus,
    registrationOpen: Boolean,
    nowMillis: Long,
    inlineReorderSession: ReorderSession?,
    inlineReorderResetToken: Int,
    positionReorderResetToken: Int,
    onInlineReorderCancel: () -> Unit,
    onInlineReorderProposal: (MachineQueue, List<Registration>, Int) -> Unit,
    onFinishRequest: () -> Unit,
    onEnterPlaying: () -> Unit,
    onRestore: () -> Unit,
    onJoinThisMachine: () -> Unit,
    onRegistrationClick: (Int) -> Unit,
    onRegistrationLongPress: (Int) -> Unit,
    onPositionClick: (PositionSelection) -> Unit,
    onPositionReorderRequest: (MachineQueue, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val letter = machineId.name
    val queueCountSummary =
        "${queue.waitingPositions().size} 个等待位置 · ${queue.registrationCount} 个登记"
    Column(
        modifier.padding(horizontal = 2.dp, vertical = 3.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = SecondaryText, fontWeight = FontWeight.Medium)) {
                            append(remark)
                            append(" · ")
                        }
                        withStyle(SpanStyle(color = PrimaryText, fontWeight = FontWeight.SemiBold)) {
                            append("机台 $letter")
                        }
                    },
                    fontSize = 17.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        queue.registrationCount > 0 && inlineReorderSession != null ->
                            "$queueCountSummary · 正在调整"
                        queue.registrationCount > 0 && !status.isOperational ->
                            "$queueCountSummary · 已停止使用"
                        queue.registrationCount > 0 -> queueCountSummary
                        !status.isOperational -> "已停止使用 · ${machineStopReasonLabel(status.stopReason)}"
                        else -> "当前空闲"
                    },
                    color = SecondaryText,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.weight(1f))
            when {
                inlineReorderSession != null -> SmallActionButton("结束调整", onInlineReorderCancel)
                !status.isOperational -> SmallActionButton("恢复正常使用", onRestore)
                queue.playing.isNotEmpty() -> SmallActionButton(
                    "本轮结束",
                    onFinishRequest,
                    primary = true
                )
                queue.waiting.isNotEmpty() ->
                    SmallActionButton(
                        "进入${playingPositionName(machineId)}",
                        onEnterPlaying,
                        enabled = queue.firstAvailableWaitingPositionIndex() != null
                    )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!status.isOperational) {
            Column(
                Modifier.fillMaxWidth().height(QueueViewportHeight).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("机台已停止使用", color = PrimaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (queue.registrationCount > 0) {
                        "停止原因：${machineStopReasonLabel(status.stopReason)}。现有 ${queue.registrationCount} 份登记、游玩位置和等待顺序均已保留。"
                    } else {
                        "停止原因：${machineStopReasonLabel(status.stopReason)}。当前没有排队登记。"
                    },
                    color = SecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (queue.registrationCount > 0) {
                        "停止期间不能操作该队列；恢复后将按原顺序继续，本轮计时会从头开始。"
                    } else {
                        "恢复正常使用后，可以继续创建登记。"
                    },
                    color = TertiaryText,
                    fontSize = 11.sp
                )
            }
            return@Column
        }
        AnimatedContent(
            targetState = inlineReorderSession,
            modifier = Modifier.fillMaxWidth().height(QueueViewportHeight),
            contentAlignment = Alignment.CenterStart,
            transitionSpec = {
                (fadeIn(tween(240)) + scaleIn(tween(240), initialScale = .965f))
                    .togetherWith(fadeOut(tween(150)) + scaleOut(tween(180), targetScale = .96f))
            },
            label = "${letter} 机台内联调整"
        ) { reorderSession ->
            if (reorderSession != null) {
                InlineReorderContent(
                    initialQueue = reorderSession.queueSnapshot,
                    initialRegistrationKey = reorderSession.initialRegistrationKey,
                    resetToken = inlineReorderResetToken,
                    onProposal = onInlineReorderProposal
                )
            } else {
                AnimatedContent(
                    targetState = queue,
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                    transitionSpec = {
                        (fadeIn(tween(260)) + slideInHorizontally(tween(300)) { width -> width / 10 })
                            .togetherWith(
                                fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { width -> -width / 12 }
                            )
                    },
                    label = "${letter} 机台队列演进"
                ) { displayedQueue ->
                    val playingMinutes = displayedQueue.playingStartedAtMillis?.let {
                        (nowMillis - it).coerceAtLeast(0L) / 60_000L
                    }
                    val playingOvertime = playingMinutes != null && playingMinutes > 20
                    val waitingPositions = displayedQueue.waitingPositions()
                    val waitingPositionSignature = displayedQueue.waiting.map { it.key }
                    val visualWaitingPositions = remember(waitingPositionSignature) {
                        mutableStateListOf<List<Registration>>().apply { addAll(waitingPositions) }
                    }
                    var queueViewportBounds by remember(waitingPositionSignature) {
                        mutableStateOf<Rect?>(null)
                    }
                    var draggedPositionKey by remember(waitingPositionSignature) {
                        mutableStateOf<String?>(null)
                    }
                    var draggedOriginalIndex by remember(waitingPositionSignature) {
                        mutableStateOf<Int?>(null)
                    }
                    var positionDragOffset by remember(waitingPositionSignature) {
                        mutableStateOf(Offset.Zero)
                    }
                    var dragPointerInRoot by remember(waitingPositionSignature) {
                        mutableStateOf<Offset?>(null)
                    }
                    var edgeScrollPerFramePx by remember(waitingPositionSignature) {
                        mutableFloatStateOf(0f)
                    }
                    val queueListState = rememberLazyListState()
                    val density = LocalDensity.current
                    val edgeZonePx = with(density) { 62.dp.toPx() }
                    val maximumEdgeScrollPx = with(density) { 7.dp.toPx() }

                    fun updatePositionEdgeScroll() {
                        val pointer = dragPointerInRoot
                        val viewport = queueViewportBounds
                        edgeScrollPerFramePx = when {
                            pointer == null || viewport == null -> 0f
                            pointer.x < viewport.left + edgeZonePx -> {
                                val strength = (
                                    (viewport.left + edgeZonePx - pointer.x) / edgeZonePx
                                    ).coerceIn(0f, 1f)
                                -maximumEdgeScrollPx * strength
                            }
                            pointer.x > viewport.right - edgeZonePx -> {
                                val strength = (
                                    (pointer.x - (viewport.right - edgeZonePx)) / edgeZonePx
                                    ).coerceIn(0f, 1f)
                                maximumEdgeScrollPx * strength
                            }
                            else -> 0f
                        }
                    }

                    fun reorderDraggedPosition() {
                        val draggedKey = draggedPositionKey ?: return
                        val sourceIndex = visualWaitingPositions.indexOfFirst {
                            waitingPositionKey(it) == draggedKey
                        }
                        if (sourceIndex < 0) return
                        val update = calculateDragReorder(
                            sourceIndex = sourceIndex,
                            dragOffset = positionDragOffset.x,
                            itemSizes = visualWaitingPositions.map {
                                with(density) { waitingPositionWidth(it).toPx() }
                            },
                            spacing = with(density) { 10.dp.toPx() }
                        )
                        if (update.destinationIndex != sourceIndex) {
                            val movedPosition = visualWaitingPositions.removeAt(sourceIndex)
                            visualWaitingPositions.add(update.destinationIndex, movedPosition)
                        }
                        positionDragOffset = positionDragOffset.copy(x = update.remainingOffset)
                    }

                    fun restoreWaitingPositionOrder() {
                        if (
                            visualWaitingPositions.map(::waitingPositionKey) !=
                            waitingPositions.map(::waitingPositionKey)
                        ) {
                            visualWaitingPositions.clear()
                            visualWaitingPositions.addAll(waitingPositions)
                        }
                    }

                    LaunchedEffect(positionReorderResetToken, waitingPositionSignature) {
                        if (draggedPositionKey == null) restoreWaitingPositionOrder()
                    }

                    LaunchedEffect(draggedPositionKey) {
                        while (draggedPositionKey != null) {
                            val requestedScroll = edgeScrollPerFramePx
                            if (kotlin.math.abs(requestedScroll) > .1f) {
                                val consumedScroll = queueListState.scrollBy(requestedScroll)
                                if (kotlin.math.abs(consumedScroll) > .1f) {
                                    positionDragOffset += Offset(consumedScroll, 0f)
                                    delay(16L)
                                    reorderDraggedPosition()
                                } else {
                                    edgeScrollPerFramePx = 0f
                                    delay(16L)
                                }
                            } else {
                                delay(16L)
                            }
                        }
                    }

                    LazyRow(
                        state = queueListState,
                        userScrollEnabled = draggedPositionKey == null,
                        modifier = Modifier.fillMaxSize().onGloballyPositioned {
                            queueViewportBounds = it.boundsInRoot()
                        },
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "${machineId.name}-playing-position") {
                        QueuePosition(
                            label = when {
                                displayedQueue.playing.isEmpty() -> playingPositionName(machineId)
                                playingMinutes == null || playingMinutes == 0L ->
                                    "${playingPositionName(machineId)} · 刚刚"
                                else -> "${playingPositionName(machineId)} · 已游玩 $playingMinutes 分钟"
                            },
                            registrations = displayedQueue.playing,
                            isPlaying = true,
                            overtimeWarning = playingOvertime,
                            onRegistrationClick = onRegistrationClick,
                            onRegistrationLongPress = onRegistrationLongPress,
                            onPositionClick = {
                                onPositionClick(
                                    PositionSelection(
                                        machineId = machineId,
                                        label = playingPositionName(machineId),
                                        registrationKeys = displayedQueue.playing.map { it.key },
                                        isPlayingPosition = true
                                    )
                                )
                            }
                        )
                        }
                        itemsIndexed(
                            items = visualWaitingPositions,
                            key = { _, registrations -> waitingPositionKey(registrations) }
                        ) { index, registrations ->
                            val fixedPair = registrations.size == 2 &&
                                registrations[0].fixedPartnerKey == registrations[1].key &&
                                registrations[1].fixedPartnerKey == registrations[0].key
                            val positionLabel = "位置 $letter${index + 1}" +
                                if (fixedPair) " · 固定组合" else ""
                            val positionKey = waitingPositionKey(registrations)
                            val isDraggingPosition = draggedPositionKey == positionKey
                            QueuePosition(
                                label = positionLabel,
                                registrations = registrations,
                                isPlaying = false,
                                overtimeWarning = false,
                                dragEnabled = visualWaitingPositions.size > 1,
                                isDragging = isDraggingPosition,
                                onPositionDragStart = { pointerInRoot ->
                                    draggedPositionKey = positionKey
                                    draggedOriginalIndex = waitingPositions.indexOfFirst {
                                        waitingPositionKey(it) == positionKey
                                    }
                                    positionDragOffset = Offset.Zero
                                    dragPointerInRoot = pointerInRoot
                                    edgeScrollPerFramePx = 0f
                                },
                                onPositionDrag = { dragAmount ->
                                    positionDragOffset += dragAmount
                                    dragPointerInRoot = dragPointerInRoot?.plus(dragAmount)
                                    reorderDraggedPosition()
                                    updatePositionEdgeScroll()
                                },
                                onPositionDragEnd = {
                                    val originalIndex = draggedOriginalIndex
                                    val destinationIndex = visualWaitingPositions.indexOfFirst {
                                        waitingPositionKey(it) == positionKey
                                    }
                                    val viewport = queueViewportBounds
                                    val pointer = dragPointerInRoot
                                    val waitingItems = queueListState.layoutInfo.visibleItemsInfo.filter {
                                        (it.key as? String)?.startsWith("waiting-") == true
                                    }
                                    val pointerXInViewport = if (pointer != null && viewport != null) {
                                        pointer.x - viewport.left
                                    } else {
                                        null
                                    }
                                    val validHorizontalRange = waitingItems.minOfOrNull { it.offset.toFloat() }
                                        ?.let { left ->
                                            left..waitingItems.maxOf { it.offset + it.size }.toFloat()
                                        }
                                    val validDrop = originalIndex != null &&
                                        destinationIndex >= 0 &&
                                        destinationIndex != originalIndex &&
                                        pointer != null &&
                                        viewport?.contains(pointer) == true &&
                                        pointerXInViewport != null &&
                                        validHorizontalRange?.contains(pointerXInViewport) == true

                                    edgeScrollPerFramePx = 0f
                                    draggedPositionKey = null
                                    draggedOriginalIndex = null
                                    positionDragOffset = Offset.Zero
                                    dragPointerInRoot = null
                                    if (validDrop) {
                                        onPositionReorderRequest(
                                            displayedQueue,
                                            originalIndex,
                                            destinationIndex
                                        )
                                    } else {
                                        restoreWaitingPositionOrder()
                                    }
                                },
                                onPositionDragCancel = {
                                    edgeScrollPerFramePx = 0f
                                    draggedPositionKey = null
                                    draggedOriginalIndex = null
                                    positionDragOffset = Offset.Zero
                                    dragPointerInRoot = null
                                    restoreWaitingPositionOrder()
                                },
                                onRegistrationClick = onRegistrationClick,
                                onRegistrationLongPress = onRegistrationLongPress,
                                onPositionClick = {
                                    onPositionClick(
                                        PositionSelection(
                                            machineId = machineId,
                                            label = positionLabel,
                                            registrationKeys = registrations.map { it.key },
                                            isPlayingPosition = false,
                                            waitingPositionIndex = index
                                        )
                                    )
                                },
                                modifier = Modifier.zIndex(if (isDraggingPosition) 12f else 0f).let {
                                    if (isDraggingPosition) it else it.animateItem()
                                }
                            )
                        }
                        item(key = "${machineId.name}-join-position") {
                        QueueJoinPosition(
                            machineId = machineId,
                            registrationOpen = registrationOpen,
                            hasCapacity = displayedQueue.registrationCount < 20,
                            onClick = onJoinThisMachine
                        )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineReorderContent(
    initialQueue: MachineQueue,
    initialRegistrationKey: Int?,
    resetToken: Int,
    onProposal: (MachineQueue, List<Registration>, Int) -> Unit
) {
    val registrations = remember(initialQueue) {
        mutableStateListOf<Registration>().apply { addAll(initialQueue.allRegistrations) }
    }
    val originalOrder = remember(initialQueue) { initialQueue.allRegistrations }
    val playingKeys = remember(initialQueue) { initialQueue.playing.map { it.key }.toSet() }
    val playingCount = initialQueue.playing.size
    var draggedKey by remember(initialQueue) { mutableStateOf<Int?>(null) }
    var dragStartOrder by remember(initialQueue) { mutableStateOf<List<Registration>?>(null) }
    var highlightedKey by remember(initialQueue, initialRegistrationKey) {
        mutableStateOf(initialRegistrationKey?.takeIf { it !in playingKeys })
    }
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var viewportBounds by remember(initialQueue) { mutableStateOf<Rect?>(null) }
    var registrationDragOffset by remember(initialQueue) { mutableStateOf(Offset.Zero) }
    var dragPointerInRoot by remember(initialQueue) { mutableStateOf<Offset?>(null) }
    var edgeScrollPerFramePx by remember(initialQueue) { mutableFloatStateOf(0f) }
    val edgeZonePx = with(density) { 58.dp.toPx() }
    val maximumEdgeScrollPx = with(density) { 7.dp.toPx() }

    fun updateEdgeScroll() {
        val pointer = dragPointerInRoot
        val viewport = viewportBounds
        edgeScrollPerFramePx = when {
            pointer == null || viewport == null -> 0f
            pointer.x < viewport.left + edgeZonePx -> {
                -maximumEdgeScrollPx * (
                    (viewport.left + edgeZonePx - pointer.x) / edgeZonePx
                    ).coerceIn(0f, 1f)
            }
            pointer.x > viewport.right - edgeZonePx -> {
                maximumEdgeScrollPx * (
                    (pointer.x - (viewport.right - edgeZonePx)) / edgeZonePx
                    ).coerceIn(0f, 1f)
            }
            else -> 0f
        }
    }

    fun reorderDraggedRegistration() {
        val key = draggedKey ?: return
        val sourceIndex = registrations.indexOfFirst { it.key == key }
        if (sourceIndex < playingCount) return
        val update = calculateDragReorder(
            sourceIndex = sourceIndex,
            dragOffset = registrationDragOffset.x,
            itemSizes = registrations.map {
                with(density) { inlineReorderRegistrationTileWidth(it.displayId).toPx() }
            },
            spacing = with(density) { 8.dp.toPx() },
            minimumIndex = playingCount
        )
        if (update.destinationIndex != sourceIndex) {
            registrations.add(update.destinationIndex, registrations.removeAt(sourceIndex))
        }
        registrationDragOffset = registrationDragOffset.copy(x = update.remainingOffset)
    }

    LaunchedEffect(initialQueue, initialRegistrationKey) {
        delay(850L)
        highlightedKey = null
    }
    LaunchedEffect(resetToken, initialQueue) {
        registrations.clear()
        registrations.addAll(originalOrder)
        draggedKey = null
        dragStartOrder = null
        registrationDragOffset = Offset.Zero
        dragPointerInRoot = null
        edgeScrollPerFramePx = 0f
    }

    LaunchedEffect(draggedKey) {
        while (draggedKey != null) {
            val requestedScroll = edgeScrollPerFramePx
            if (kotlin.math.abs(requestedScroll) > .1f) {
                val consumedScroll = listState.scrollBy(requestedScroll)
                if (kotlin.math.abs(consumedScroll) > .1f) {
                    registrationDragOffset += Offset(consumedScroll, 0f)
                    delay(16L)
                    reorderDraggedRegistration()
                } else {
                    edgeScrollPerFramePx = 0f
                    delay(16L)
                }
            } else {
                delay(16L)
            }
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxSize().onGloballyPositioned {
            viewportBounds = it.boundsInRoot()
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        userScrollEnabled = draggedKey == null
    ) {
        itemsIndexed(registrations, key = { _, registration -> registration.key }) { index, registration ->
            val width = inlineReorderRegistrationTileWidth(registration.displayId)
            val locked = registration.key in playingKeys
            val dragging = draggedKey == registration.key
            InlineReorderRegistrationTile(
                orderLabel = if (locked) "游玩位置" else "顺序 ${index - playingCount + 1}",
                registration = registration,
                width = width,
                active = !locked && (dragging || highlightedKey == registration.key),
                locked = locked,
                dragging = dragging,
                modifier = Modifier.zIndex(if (dragging) 1f else 0f).let {
                    if (dragging) it else it.animateItem()
                },
                onDragStart = { pointerInRoot ->
                    highlightedKey = null
                    dragStartOrder = registrations.toList()
                    draggedKey = registration.key
                    registrationDragOffset = Offset.Zero
                    dragPointerInRoot = pointerInRoot
                    edgeScrollPerFramePx = 0f
                },
                onDrag = { dragAmount ->
                    registrationDragOffset += dragAmount
                    dragPointerInRoot = dragPointerInRoot?.plus(dragAmount)
                    reorderDraggedRegistration()
                    updateEdgeScroll()
                },
                onDragEnd = {
                    edgeScrollPerFramePx = 0f
                    draggedKey = null
                    dragStartOrder = null
                    registrationDragOffset = Offset.Zero
                    dragPointerInRoot = null
                    val proposedOrder = registrations.toList()
                    if (hasRegistrationOrderChanged(originalOrder, proposedOrder)) {
                        onProposal(initialQueue, proposedOrder, registration.key)
                    }
                },
                onDragCancel = {
                    val orderToRestore = dragStartOrder
                    edgeScrollPerFramePx = 0f
                    draggedKey = null
                    dragStartOrder = null
                    registrationDragOffset = Offset.Zero
                    dragPointerInRoot = null
                    if (orderToRestore != null) {
                        registrations.clear()
                        registrations.addAll(orderToRestore)
                    }
                }
            )
        }
    }
}

@Composable
private fun InlineReorderRegistrationTile(
    orderLabel: String,
    registration: Registration,
    width: Dp,
    active: Boolean,
    locked: Boolean,
    dragging: Boolean,
    modifier: Modifier = Modifier,
    isDragOverlay: Boolean = false,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val dragOverlayController = LocalGlobalDragOverlayController.current
    val visibleDisplayId = queueDisplayId(registration.displayId)
    val displayIdCharacterCount = visibleDisplayId.codePointCount(0, visibleDisplayId.length)
    var dragSurfaceCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var tileCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val background by animateColorAsState(
        when {
            locked -> Color(0xFFEDEDF1)
            active -> SoftBlue
            else -> CardBackground
        },
        tween(150),
        label = "inline drag color"
    )
    val elevation by animateDpAsState(if (active) 10.dp else 0.dp, tween(150), label = "inline drag elevation")

    Row(
        modifier.width(width).height(108.dp)
            .onGloballyPositioned { tileCoordinates = it }
            .graphicsLayer {
                alpha = if (dragging && !isDragOverlay) 0f else 1f
                shadowElevation = elevation.toPx()
                shape = RoundedCornerShape(12.dp)
            }
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(
                1.dp,
                if (active) SystemBlue.copy(alpha = .42f) else Separator.copy(alpha = .85f),
                RoundedCornerShape(12.dp)
            ).padding(start = 10.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(orderLabel, color = TertiaryText, fontSize = 9.sp, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(
                visibleDisplayId,
                color = if (locked) SecondaryText else PrimaryText,
                fontSize = if (displayIdCharacterCount > 4) 12.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                playPreferenceLabel(registration),
                color = if (locked) TertiaryText else SecondaryText,
                fontSize = 9.sp,
                maxLines = 1
            )
        }
        if (locked) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text("锁定", color = TertiaryText, fontSize = 9.sp, fontWeight = FontWeight.Medium)
            }
        } else {
            val dragHandleModifier = if (isDragOverlay) {
                Modifier
            } else {
                Modifier
                    .onGloballyPositioned { dragSurfaceCoordinates = it }
                    .pointerInput(registration.key, width) {
                        detectDragGestures(
                            onDragStart = { position ->
                                val pointerInRoot =
                                    dragSurfaceCoordinates?.localToRoot(position) ?: position
                                tileCoordinates?.boundsInRoot()?.let { itemBounds ->
                                    dragOverlayController.start(pointerInRoot, itemBounds) {
                                        InlineReorderRegistrationTile(
                                            orderLabel = orderLabel,
                                            registration = registration,
                                            width = width,
                                            active = true,
                                            locked = false,
                                            dragging = true,
                                            isDragOverlay = true,
                                            onDragStart = {},
                                            onDrag = {},
                                            onDragEnd = {},
                                            onDragCancel = {}
                                        )
                                    }
                                }
                                onDragStart(pointerInRoot)
                            },
                            onDragCancel = {
                                dragOverlayController.clear()
                                onDragCancel()
                            },
                            onDragEnd = {
                                dragOverlayController.clear()
                                onDragEnd()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOverlayController.moveBy(dragAmount)
                                onDrag(dragAmount)
                            }
                        )
                    }
            }
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (active) SystemBlue.copy(alpha = .10f) else Color.Transparent)
                    .then(dragHandleModifier),
                contentAlignment = Alignment.Center
            ) {
                Text("≡", color = if (active) SystemBlue else TertiaryText, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun QueuePosition(
    label: String,
    registrations: List<Registration>,
    isPlaying: Boolean,
    overtimeWarning: Boolean,
    modifier: Modifier = Modifier,
    dragEnabled: Boolean = false,
    isDragging: Boolean = false,
    isDragOverlay: Boolean = false,
    onPositionDragStart: (Offset) -> Unit = {},
    onPositionDrag: (Offset) -> Unit = {},
    onPositionDragEnd: () -> Unit = {},
    onPositionDragCancel: () -> Unit = {},
    onRegistrationClick: (Int) -> Unit,
    onRegistrationLongPress: (Int) -> Unit,
    onPositionClick: () -> Unit
) {
    val dragOverlayController = LocalGlobalDragOverlayController.current
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tileWidths = registrations.map { registrationTileWidth(it.displayId) }
    val registrationContentWidth = tileWidths.fold(0.dp) { total, width -> total + width } +
        if (tileWidths.size > 1) 7.dp * (tileWidths.size - 1) else 0.dp
    val warningWidth = if (overtimeWarning) 178.dp else 0.dp
    val contentSpacing = if (overtimeWarning && registrations.isNotEmpty()) 7.dp else 0.dp
    val playingHeaderWidth = if (isPlaying && registrations.isNotEmpty()) {
        val labelWidth = with(density) {
            textMeasurer.measure(
                text = AnnotatedString(label),
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
            ).size.width.toDp()
        }
        val arrowWidth = with(density) {
            textMeasurer.measure(
                text = AnnotatedString("›"),
                style = TextStyle(fontSize = 16.sp)
            ).size.width.toDp()
        }
        labelWidth + arrowWidth + 30.dp
    } else {
        0.dp
    }
    val minimumWidth = when {
        isPlaying && registrations.isEmpty() -> 148.dp
        isPlaying -> playingHeaderWidth
        else -> 0.dp
    }
    val positionWidth = maxOf(
        minimumWidth,
        registrationContentWidth + warningWidth + contentSpacing + 22.dp
    )
    val positionElevation by animateDpAsState(
        targetValue = if (isDragging) 16.dp else 0.dp,
        animationSpec = tween(150),
        label = "$label 位置拖动阴影"
    )
    val positionBackground by animateColorAsState(
        targetValue = when {
            isPlaying -> SoftBlue
            isDragging -> Color.White
            else -> Color(0xFFFAFAFC)
        },
        animationSpec = tween(150),
        label = "$label 位置拖动背景"
    )
    val positionBorder by animateColorAsState(
        targetValue = when {
            isPlaying -> SystemBlue.copy(alpha = .25f)
            isDragging -> SystemBlue.copy(alpha = .62f)
            else -> Separator
        },
        animationSpec = tween(150),
        label = "$label 位置拖动边框"
    )
    var dragSurfaceCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var positionCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val dragHeaderModifier = if (dragEnabled) {
        Modifier.pointerInput(dragEnabled) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val pointerInRoot = dragSurfaceCoordinates?.localToRoot(position) ?: position
                    positionCoordinates?.boundsInRoot()?.let { itemBounds ->
                        dragOverlayController.start(pointerInRoot, itemBounds) {
                            QueuePosition(
                                label = label,
                                registrations = registrations,
                                isPlaying = isPlaying,
                                overtimeWarning = overtimeWarning,
                                dragEnabled = false,
                                isDragging = true,
                                isDragOverlay = true,
                                onRegistrationClick = {},
                                onRegistrationLongPress = {},
                                onPositionClick = {}
                            )
                        }
                    }
                    onPositionDragStart(pointerInRoot)
                },
                onDragCancel = {
                    dragOverlayController.clear()
                    onPositionDragCancel()
                },
                onDragEnd = {
                    dragOverlayController.clear()
                    onPositionDragEnd()
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    dragOverlayController.moveBy(dragAmount)
                    onPositionDrag(dragAmount)
                }
            )
        }
    } else {
        Modifier
    }
    Column(
        modifier.width(positionWidth).height(QueueViewportHeight)
            .zIndex(if (isDragging) 12f else 0f)
            .onGloballyPositioned { positionCoordinates = it }
            .graphicsLayer {
                alpha = if (isDragging && !isDragOverlay) 0f else 1f
                shadowElevation = positionElevation.toPx()
                shape = RoundedCornerShape(CardRadius)
            }
            .clip(RoundedCornerShape(CardRadius))
            .clickable(
                enabled = registrations.isNotEmpty() && !isDragging,
                onClick = onPositionClick
            )
            .background(positionBackground)
            .border(1.dp, positionBorder, RoundedCornerShape(CardRadius))
            .padding(start = 11.dp, end = 11.dp, top = 8.dp, bottom = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(20.dp).onGloballyPositioned {
                dragSurfaceCoordinates = it
            }.then(dragHeaderModifier).padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = when {
                    isPlaying -> SystemBlue
                    isDragging -> SystemBlue
                    else -> TertiaryText
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = if (isPlaying) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (registrations.isNotEmpty()) {
                if (dragEnabled) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "≡",
                        color = if (isDragging) SystemBlue else TertiaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(if (isPlaying) 8.dp else 5.dp))
                Text(
                    "›",
                    color = if (isPlaying) SystemBlue.copy(alpha = .58f) else TertiaryText,
                    fontSize = 16.sp
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        if (registrations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无登记", color = TertiaryText, fontSize = 13.sp)
            }
        } else {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (overtimeWarning) {
                    Column(
                        Modifier.width(178.dp).height(QueueRegistrationTileHeight)
                            .clip(RoundedCornerShape(11.dp))
                            .background(Color(0xFFFFF4E5)).padding(11.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("本轮已超过 20 分钟", color = Color(0xFF9A5B00), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("请确认机台是否仍在正常游玩。", color = Color(0xFF9A5B00), fontSize = 9.sp, lineHeight = 14.sp)
                    }
                }
                registrations.forEachIndexed { index, registration ->
                    RegistrationTile(
                        registration = registration,
                        isPlaying = isPlaying,
                        onClick = { onRegistrationClick(registration.key) },
                        onLongClick = { onRegistrationLongPress(registration.key) },
                        modifier = Modifier.width(tileWidths[index])
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RegistrationTile(
    registration: Registration,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(ControlRadius)
    Column(
        modifier.height(QueueRegistrationTileHeight).clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (isPlaying) PlayingRegistrationBackground else CardBackground)
            .border(
                1.dp,
                if (isPlaying) SystemBlue.copy(alpha = .08f) else Color.Transparent,
                shape
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                queueDisplayId(registration.displayId),
                color = PrimaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "›",
                color = if (isPlaying) SystemBlue.copy(alpha = .48f) else TertiaryText,
                fontSize = 17.sp
            )
        }
        Spacer(Modifier.height(5.dp))
        val absenceStatus = registrationAbsenceStatusLabel(
            registration,
            includeSkippedTurns = false
        )
        Text(
            absenceStatus ?: when {
                registration.fixedPartnerKey != null -> "固定组合"
                registration.preference == PlayPreference.SOLO -> "单人游玩"
                else -> "允许他人加入"
            },
            color = if (absenceStatus != null) AbsenceStatusColor else SecondaryText,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun QueueJoinPosition(
    machineId: MachineId,
    registrationOpen: Boolean,
    hasCapacity: Boolean,
    onClick: () -> Unit
) {
    val enabled = registrationOpen && hasCapacity
    Column(
        Modifier.width(148.dp).height(QueueViewportHeight).clip(RoundedCornerShape(CardRadius))
            .background(if (enabled) SoftBlue.copy(alpha = .62f) else Color(0xFFF3F3F5))
            .border(
                1.dp,
                if (enabled) SystemBlue.copy(alpha = .22f) else Separator.copy(alpha = .65f),
                RoundedCornerShape(CardRadius)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "+",
            color = if (enabled) SystemBlue else TertiaryText,
            fontSize = 28.sp,
            fontWeight = FontWeight.Light
        )
        Spacer(Modifier.height(5.dp))
        Text(
            when {
                enabled -> "加入机台 ${machineId.name}"
                !registrationOpen -> "未启用登记"
                else -> "机台 ${machineId.name} 已满"
            },
            color = if (enabled) SystemBlue else TertiaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun JoinPanel(
    machineA: MachineQueue,
    machineB: MachineQueue,
    machineAStatus: MachineStatus,
    machineBStatus: MachineStatus,
    nowMillis: Long,
    registrationOpen: Boolean,
    onJoin: () -> Unit,
    onBatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val machineAJoinable = machineAStatus.isOperational && machineA.registrationCount < 20
    val machineBJoinable = machineBStatus.isOperational && machineB.registrationCount < 20
    val joiningEnabled = registrationOpen && (machineAJoinable || machineBJoinable)
    Column(
        modifier.clip(RoundedCornerShape(CardRadius)).background(CardBackground)
            .border(1.dp, Separator.copy(alpha = .68f), RoundedCornerShape(CardRadius))
            .padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("加入排队", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                !registrationOpen -> "当前未使用登记排队，请在现场自然排队。"
                !machineAStatus.isOperational && !machineBStatus.isOperational -> "两台机台均已停止使用。"
                !machineAJoinable && !machineBJoinable -> "两台机台目前都无法接受新登记。"
                else -> "创建你的登记并加入排队。"
            },
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        if (registrationOpen) {
            Spacer(Modifier.height(17.dp))
            Text("新登记预计等待", color = TertiaryText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(7.dp))
            JoinEstimateRow(MachineId.A, machineA, machineAStatus, nowMillis)
            Spacer(Modifier.height(5.dp))
            JoinEstimateRow(MachineId.B, machineB, machineBStatus, nowMillis)
        }
        Spacer(Modifier.height(22.dp))
        PrimaryButton("开始", onJoin, Modifier.fillMaxWidth(), enabled = joiningEnabled)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("批量创建登记", onBatch, Modifier.fillMaxWidth(), enabled = joiningEnabled)
        Spacer(Modifier.height(20.dp))
        Text("每位玩家只应保有一份有效登记。批量功能用于现场录入多名实际玩家。", color = TertiaryText, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun JoinEstimateRow(
    machineId: MachineId,
    queue: MachineQueue,
    status: MachineStatus,
    nowMillis: Long
) {
    val estimate = when {
        !status.isOperational -> "已停止使用"
        queue.registrationCount >= 20 -> "登记已满"
        else -> formatJoinWaitEstimate(estimatedWaitForNewOpenRegistration(queue, nowMillis))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(machineName(machineId), color = SecondaryText, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(
            estimate,
            color = if (status.isOperational && queue.registrationCount < 20) PrimaryText else TertiaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MachineSelectionScreen(
    machineA: MachineQueue,
    machineB: MachineQueue,
    machineAStatus: MachineStatus,
    machineBStatus: MachineStatus,
    batch: Boolean,
    onBack: () -> Unit,
    onSelect: (MachineId) -> Unit
) {
    WizardPage(
        step = if (batch) "1 / 2" else "1 / 3",
        title = if (batch) "批量创建登记" else "选择机台",
        subtitle = if (batch) "选择要录入登记的机台。每一份登记仍然对应一名实际玩家。"
        else "两台机台分别维护独立的登记顺序。",
        onBack = onBack
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MachineChoice(
                machineName(MachineId.A),
                machineA,
                machineAStatus,
                { onSelect(MachineId.A) },
                Modifier.weight(1f)
            )
            MachineChoice(
                machineName(MachineId.B),
                machineB,
                machineBStatus,
                { onSelect(MachineId.B) },
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MachineChoice(
    name: String,
    queue: MachineQueue,
    status: MachineStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val available = status.isOperational && queue.registrationCount < 20
    Column(
        modifier.height(154.dp).clip(RoundedCornerShape(CardRadius)).background(CardBackground)
            .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(CardRadius))
            .clickable(enabled = available, onClick = onClick).padding(20.dp)
    ) {
        Text(name, color = if (available) PrimaryText else TertiaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (!status.isOperational) "机台已停止使用：${machineStopReasonLabel(status.stopReason)}。"
            else if (queue.registrationCount == 0) "目前没有登记，可以直接开始。"
            else if (available) "现有 ${queue.registrationCount} 份有效登记。"
            else "已达到 20 人上限，暂时不能新增登记。",
            color = SecondaryText,
            fontSize = 13.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            when {
                available -> "选择这台机台  →"
                !status.isOperational -> "机台已停止使用"
                else -> "登记人数已满"
            },
            color = if (available) SystemBlue else TertiaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CreateRegistrationScreen(
    draftId: String,
    temporarySelected: Boolean,
    idAlreadyRegistered: Boolean,
    onIdChange: (String) -> Unit,
    onTemporarySelect: () -> Unit,
    onGenerateId: () -> Unit,
    onPlayerLibrary: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    WizardPage(
        step = "2 / 3",
        title = "创建你的排队登记",
        subtitle = "选择一种可以确认你身份的方式。登记只用于维护本次排队顺序。",
        onBack = onBack
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OptionCard(
                title = "创建临时登记",
                description = "输入一个容易识别的昵称；不需要创建玩家资料。",
                selected = temporarySelected,
                enabled = true,
                onClick = onTemporarySelect,
                modifier = Modifier.weight(1f)
            )
            OptionCard(
                title = "使用玩家资料库",
                description = "从本机保存的玩家资料中选择，并沿用常用设置。",
                selected = false,
                enabled = true,
                onClick = onPlayerLibrary,
                modifier = Modifier.weight(1f)
            )
            OptionCard(
                title = "使用二维码",
                description = "使用你的移动设备登录并恢复登记。",
                selected = false,
                enabled = false,
                badge = "暂不可用",
                onClick = {},
                modifier = Modifier.weight(1f)
            )
        }
        if (temporarySelected) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Top) {
                RegistrationNicknameField(draftId, onIdChange, idAlreadyRegistered, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                SecondaryButton("生成随机昵称", onGenerateId, Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                "继续",
                onContinue,
                Modifier.fillMaxWidth(),
                draftId.isNotBlank() && !idAlreadyRegistered
            )
        }
    }
}

@Composable
private fun RegistrationNicknameField(
    value: String,
    onValueChange: (String) -> Unit,
    alreadyExists: Boolean,
    modifier: Modifier = Modifier,
    label: String = "你的昵称",
    placeholder: String = "例如：Rin"
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        isError = alreadyExists,
        supportingText = {
            Text(if (alreadyExists) "这个昵称已有一份有效登记。" else "请使用现场玩家能够认出的昵称。")
        },
        shape = RoundedCornerShape(ControlRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PrimaryText,
            unfocusedTextColor = PrimaryText,
            focusedBorderColor = SystemBlue,
            unfocusedBorderColor = Color(0xFFA7A7AC),
            focusedLabelColor = SystemBlue,
            unfocusedLabelColor = SecondaryText,
            focusedPlaceholderColor = SecondaryText,
            unfocusedPlaceholderColor = SecondaryText,
            focusedSupportingTextColor = SecondaryText,
            unfocusedSupportingTextColor = SecondaryText,
            focusedContainerColor = CardBackground,
            unfocusedContainerColor = CardBackground,
            cursorColor = SystemBlue
        ),
        modifier = modifier
    )
}

@Composable
private fun PlayerLibraryScreen(
    profiles: List<PlayerProfile>,
    searchQuery: String,
    sortMode: ProfileSortMode,
    contextLabel: String,
    title: String,
    subtitle: String,
    onSearchQueryChange: (String) -> Unit,
    onSortModeChange: (ProfileSortMode) -> Unit,
    onNewProfile: () -> Unit,
    onProfileClick: (PlayerProfile) -> Unit,
    onEditProfile: (PlayerProfile) -> Unit,
    onBack: () -> Unit
) {
    val displayedProfiles = filterAndSortPlayerProfiles(profiles, searchQuery, sortMode)
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 36.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppBackButton(onBack)
            Spacer(Modifier.weight(1f))
            Text(contextLabel, color = TertiaryText, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxSize().widthIn(max = 980.dp).align(Alignment.CenterHorizontally)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = PrimaryText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, color = SecondaryText, fontSize = 13.sp)
                }
                Spacer(Modifier.width(20.dp))
                PrimaryButton("新建玩家资料", onNewProfile, Modifier.width(174.dp))
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text("搜索玩家昵称") },
                    placeholder = { Text("输入昵称") },
                    singleLine = true,
                    shape = RoundedCornerShape(ControlRadius),
                    colors = playerProfileTextFieldColors(),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(14.dp))
                ProfileSortControl(
                    selected = sortMode,
                    onSelect = onSortModeChange,
                    modifier = Modifier.width(250.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            if (displayedProfiles.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (profiles.isEmpty()) "还没有玩家资料" else "没有符合搜索条件的玩家",
                            color = PrimaryText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (profiles.isEmpty()) "新建后，之后可以更快地加入排队。" else "请尝试其他昵称。",
                            color = SecondaryText,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                val columnCount = 4
                val profileRows = displayedProfiles.chunked(columnCount)
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    itemsIndexed(profileRows, key = { _, row -> row.joinToString("|") { it.id } }) { _, row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            row.forEach { profile ->
                                PlayerProfileCard(
                                    profile = profile,
                                    onClick = { onProfileClick(profile) },
                                    onEdit = { onEditProfile(profile) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(columnCount - row.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSortControl(
    selected: ProfileSortMode,
    onSelect: (ProfileSortMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(ControlRadius))
            .background(Color(0xFFE8E8ED)).padding(3.dp)
    ) {
        listOf(
            ProfileSortMode.RECOMMENDED to "推荐排序",
            ProfileSortMode.ALPHABETICAL to "首字母排序"
        ).forEach { (mode, label) ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                    .background(if (selected == mode) CardBackground else Color.Transparent)
                    .clickable(enabled = selected != mode) { onSelect(mode) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected == mode) PrimaryText else SecondaryText,
                    fontSize = 12.sp,
                    fontWeight = if (selected == mode) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun PlayerProfileCard(
    profile: PlayerProfile,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.height(80.dp).clip(RoundedCornerShape(ControlRadius)).background(CardBackground)
            .border(1.dp, Separator.copy(alpha = .78f), RoundedCornerShape(ControlRadius))
            .clickable(onClick = onClick).padding(start = 11.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerAvatar(profile, 44.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.nickname,
                    color = PrimaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(5.dp))
                PlayerGenderMark(profile.gender, hideUndisclosed = false)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (profile.hasValidContact) {
                    profilePreferenceLabel(profile.defaultPreference)
                } else {
                    "需要补充联系方式"
                },
                color = if (profile.hasValidContact) SecondaryText else Color(0xFF9A5B00),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "编辑“${profile.nickname}”的玩家资料",
                tint = SystemBlue,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(2.dp))
        Text("›", color = TertiaryText, fontSize = 18.sp)
    }
}

@Composable
private fun PlayerProfileEditorScreen(
    nickname: String,
    nicknameAlreadyExists: Boolean,
    gender: PlayerGender,
    defaultPreference: ProfilePlayPreference,
    qqNumber: String,
    allowPhoneNumber: Boolean,
    editingExisting: Boolean,
    onNicknameChange: (String) -> Unit,
    onGenderChange: (PlayerGender) -> Unit,
    onDefaultPreferenceChange: (ProfilePlayPreference) -> Unit,
    onQqNumberChange: (String) -> Unit,
    onAllowPhoneNumberChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val contact = playerContactFromInput(qqNumber, allowPhoneNumber)
    val contactValid = contact.isValid
    val contactMessage = when {
        !contact.isPresent -> if (allowPhoneNumber) {
            "请输入 QQ 号或中国大陆手机号。"
        } else {
            "请输入 QQ 号。"
        }
        allowPhoneNumber && isMainlandChinaMobileNumber(qqNumber) ->
            "已识别为手机号。联系方式只会保存在玩家资料中。"
        !contactValid -> if (allowPhoneNumber) {
            "请输入 5 至 12 位 QQ 号，或 1 开头的 11 位中国大陆手机号。"
        } else {
            "QQ 号应为 5 至 12 位数字。"
        }
        else -> "联系方式只会保存在玩家资料中。"
    }
    WizardPage(
        step = if (editingExisting) "编辑资料" else "新建资料",
        title = if (editingExisting) "编辑玩家资料" else "新建玩家资料",
        subtitle = if (editingExisting) {
            "修改会保存到玩家资料库；已经创建的登记不会因此改变游玩偏好。请填写一个可用的联系方式。"
        } else {
            "这些资料会保存在玩家资料库中，加入排队时再进行确认。请填写一个可用的联系方式。"
        },
        onBack = onBack
    ) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                Modifier.weight(.9f).fillMaxHeight().clip(RoundedCornerShape(CardRadius))
                    .background(CardBackground)
                    .border(1.dp, Separator.copy(alpha = .72f), RoundedCornerShape(CardRadius))
                    .padding(16.dp)
            ) {
                Text("玩家身份", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onNicknameChange,
                    label = { Text("玩家昵称") },
                    placeholder = { Text("输入现场容易辨认的昵称") },
                    singleLine = true,
                    isError = nicknameAlreadyExists,
                    supportingText = {
                        Text(
                            if (nicknameAlreadyExists) "这个昵称已经存在于玩家资料库或当前排队中。"
                            else "昵称会显示在排队登记中。"
                        )
                    },
                    shape = RoundedCornerShape(ControlRadius),
                    colors = playerProfileTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("性别", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("选择“—”时，登记详情中不会显示性别符号。", color = SecondaryText, fontSize = 11.sp)
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerGender.entries.forEach { option ->
                        GenderChoice(
                            gender = option,
                            selected = gender == option,
                            onClick = { onGenderChange(option) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(CardRadius))
                    .background(CardBackground)
                    .border(1.dp, Separator.copy(alpha = .72f), RoundedCornerShape(CardRadius))
                    .padding(16.dp)
            ) {
                Text("联系方式", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "资料仅保存一个联系方式；手机号仅支持中国大陆手机号码。",
                    color = SecondaryText,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(
                    value = qqNumber,
                    onValueChange = onQqNumberChange,
                    label = { Text(if (allowPhoneNumber) "QQ 号或手机号" else "QQ 号") },
                    placeholder = {
                        Text(if (allowPhoneNumber) "QQ 号或 11 位中国大陆手机号" else "5 至 12 位数字")
                    },
                    singleLine = true,
                    isError = qqNumber.isNotBlank() && !contactValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    shape = RoundedCornerShape(ControlRadius),
                    colors = playerProfileTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (allowPhoneNumber) "改为只填写 QQ 号" else "没有 QQ？使用手机号作为联系方式",
                    color = SystemBlue,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { onAllowPhoneNumberChange(!allowPhoneNumber) }
                        .padding(vertical = 4.dp)
                )
                Text(
                    contactMessage,
                    color = if (contactValid) SecondaryText else Destructive,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
            Column(
                Modifier.weight(1.1f).fillMaxHeight().clip(RoundedCornerShape(CardRadius))
                    .background(CardBackground)
                    .border(1.dp, Separator.copy(alpha = .72f), RoundedCornerShape(CardRadius))
                    .padding(16.dp)
            ) {
                Text("默认游玩偏好", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(ControlRadius))
                        .background(PageBackground)
                ) {
                    ProfilePreferenceSelectionRow(
                        title = "单人游玩",
                        description = "每次使用这份资料时，默认独自占用一个等待位置。",
                        selected = defaultPreference == ProfilePlayPreference.SOLO,
                        onClick = { onDefaultPreferenceChange(ProfilePlayPreference.SOLO) }
                    )
                    HorizontalDivider(color = Separator.copy(alpha = .72f))
                    ProfilePreferenceSelectionRow(
                        title = "允许他人加入",
                        description = "默认接受系统分配的共同游玩，以缩短排队等待。",
                        selected = defaultPreference == ProfilePlayPreference.OPEN_TO_JOIN,
                        onClick = { onDefaultPreferenceChange(ProfilePlayPreference.OPEN_TO_JOIN) }
                    )
                    HorizontalDivider(color = Separator.copy(alpha = .72f))
                    ProfilePreferenceSelectionRow(
                        title = "每次询问",
                        description = "每次使用这份资料加入排队时，再选择本次偏好。",
                        selected = defaultPreference == ProfilePlayPreference.ASK_EVERY_TIME,
                        onClick = { onDefaultPreferenceChange(ProfilePlayPreference.ASK_EVERY_TIME) }
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            if (editingExisting) "保存玩家资料" else "完成新建",
            onSave,
            Modifier.fillMaxWidth(),
            enabled = nickname.isNotBlank() && !nicknameAlreadyExists && contactValid
        )
    }
}

@Composable
private fun GenderChoice(
    gender: PlayerGender,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        if (selected) SoftBlue else PageBackground,
        tween(180),
        label = "性别选项背景"
    )
    val borderColor by animateColorAsState(
        if (selected) SystemBlue else Separator.copy(alpha = .78f),
        tween(180),
        label = "性别选项边框"
    )
    val borderWidth by animateDpAsState(
        if (selected) 2.dp else 1.dp,
        tween(180),
        label = "性别选项边框宽度"
    )
    Box(
        modifier.height(60.dp).clip(RoundedCornerShape(ControlRadius))
            .background(backgroundColor)
            .border(
                borderWidth,
                borderColor,
                RoundedCornerShape(ControlRadius)
            )
            .clickable(enabled = !selected, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        PlayerGenderMark(gender, hideUndisclosed = false, fontSize = 25.sp)
    }
}

@Composable
private fun ProfilePreferenceSelectionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        if (selected) SoftBlue.copy(alpha = .62f) else Color.Transparent,
        tween(180),
        label = "默认偏好选项背景"
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(enabled = !selected, onClick = onClick)
            .background(backgroundColor)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(description, color = SecondaryText, fontSize = 9.sp, lineHeight = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) StraightCheckMark(Modifier.size(14.dp), color = SystemBlue)
        }
    }
}

@Composable
private fun PlayerProfileDetailScreen(
    profile: PlayerProfile?,
    selectedPreference: PlayPreference?,
    rememberPreference: Boolean,
    alreadyRegistered: Boolean,
    machineAvailable: Boolean,
    machineLabel: String,
    onPreferenceChange: (PlayPreference) -> Unit,
    onRememberPreferenceChange: (Boolean) -> Unit,
    onEditProfile: () -> Unit,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    if (profile == null) {
        WizardPage(
            step = "玩家资料",
            title = "找不到这份玩家资料",
            subtitle = "资料可能已经发生变化，请返回资料库后重新选择。",
            onBack = onBack
        ) {
            SecondaryButton("返回玩家资料库", onBack, Modifier.fillMaxWidth())
        }
        return
    }
    if (!profile.hasValidContact) {
        IncompletePlayerContactScreen(
            profile = profile,
            continuation = "加入排队",
            onEditProfile = onEditProfile,
            onBack = onBack
        )
        return
    }
    val asksEveryTime = profile.defaultPreference == ProfilePlayPreference.ASK_EVERY_TIME
    WizardPage(
        step = "确认资料",
        title = "加入排队",
        subtitle = "请确认玩家资料和本次游玩偏好。完成后会加入 $machineLabel。",
        onBack = onBack
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBackground)
                .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(12.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerAvatar(profile, 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.nickname, color = PrimaryText, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    PlayerGenderMark(profile.gender, hideUndisclosed = false, fontSize = 20.sp)
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "默认偏好：${profilePreferenceLabel(profile.defaultPreference)}",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(profileContactSummary(profile), color = SecondaryText, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (asksEveryTime) {
            Text("选择本次游玩偏好", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileJoinPreferenceChoice(
                    title = "单人游玩",
                    selected = selectedPreference == PlayPreference.SOLO,
                    onClick = { onPreferenceChange(PlayPreference.SOLO) },
                    modifier = Modifier.weight(1f)
                )
                ProfileJoinPreferenceChoice(
                    title = "允许他人加入（推荐）",
                    selected = selectedPreference == PlayPreference.OPEN_TO_JOIN,
                    onClick = { onPreferenceChange(PlayPreference.OPEN_TO_JOIN) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(7.dp))
            Text("接受系统分配的共同游玩通常可以缩短排队时间。", color = SecondaryText, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            FriendConsentConfirmation(
                checked = rememberPreference,
                text = "将本次选择保存为默认偏好，以后不再询问。",
                onToggle = { onRememberPreferenceChange(!rememberPreference) }
            )
        } else {
            Text(
                "本次将使用“${profilePreferenceLabel(profile.defaultPreference)}”。",
                color = SecondaryText,
                fontSize = 12.sp
            )
        }
        if (alreadyRegistered || !machineAvailable) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (alreadyRegistered) "这名玩家已经有一份有效登记，不能重复加入。"
                else "$machineLabel 目前无法接收新的登记。",
                color = Destructive,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            "完成并加入排队",
            onComplete,
            Modifier.fillMaxWidth(),
            enabled = !alreadyRegistered && machineAvailable && (!asksEveryTime || selectedPreference != null)
        )
    }
}

@Composable
private fun ClaimPlayerProfileDetailScreen(
    profile: PlayerProfile?,
    registration: Registration?,
    alreadyRegistered: Boolean,
    machineAvailable: Boolean,
    onEditProfile: () -> Unit,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    if (profile == null || registration == null) {
        WizardPage(
            step = "认领登记",
            title = "无法继续认领",
            subtitle = "玩家资料或临时登记可能已经发生变化，请返回后重新选择。",
            onBack = onBack
        ) {
            SecondaryButton("返回玩家资料库", onBack, Modifier.fillMaxWidth())
        }
        return
    }
    if (!profile.hasValidContact) {
        IncompletePlayerContactScreen(
            profile = profile,
            continuation = "认领登记",
            onEditProfile = onEditProfile,
            onBack = onBack
        )
        return
    }
    val profilePreference = profile.defaultPreference.toPlayPreferenceOrNull()
    val preferenceMismatch = profilePreference != null &&
        profilePreference != registration.preference
    WizardPage(
        step = "确认认领",
        title = "使用玩家资料认领登记",
        subtitle = "认领后，登记昵称会更新为“${profile.nickname}”；当前位置和排队先后顺序不会改变。",
        onBack = onBack
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBackground)
                .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(12.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerAvatar(profile, 60.dp)
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.nickname, color = PrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    PlayerGenderMark(profile.gender, hideUndisclosed = false, fontSize = 19.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "资料默认偏好：${profilePreferenceLabel(profile.defaultPreference)}",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(profileContactSummary(profile), color = SecondaryText, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(13.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PageBackground)
                .padding(horizontal = 15.dp, vertical = 12.dp)
        ) {
            MetadataRow("当前登记", registration.displayId)
            MetadataRow("认领后昵称", profile.nickname)
            MetadataRow("本次游玩偏好", playPreferenceLabel(registration))
        }
        Spacer(Modifier.height(11.dp))
        Text(
            when {
                profile.defaultPreference == ProfilePlayPreference.ASK_EVERY_TIME ->
                    "这份资料设置为每次询问。认领后，本次将保留当前登记的游玩偏好。"
                preferenceMismatch ->
                    "资料默认偏好与当前登记不同。继续后，需要选择本次登记使用哪一项。"
                else -> "资料默认偏好与当前登记一致。"
            },
            color = if (preferenceMismatch) Color(0xFF9A5B00) else SecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        if (alreadyRegistered || !machineAvailable) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (alreadyRegistered) "这份玩家资料已经关联到另一份有效登记，不能重复使用。"
                else "当前机台已停止使用，暂时不能认领登记。",
                color = Destructive,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            "使用这份资料认领登记",
            onComplete,
            Modifier.fillMaxWidth(),
            enabled = !alreadyRegistered && machineAvailable
        )
    }
}

@Composable
private fun IncompletePlayerContactScreen(
    profile: PlayerProfile,
    continuation: String,
    onEditProfile: () -> Unit,
    onBack: () -> Unit
) {
    WizardPage(
        step = "资料待补充",
        title = "需要补充玩家信息",
        subtitle = "“${profile.nickname}”的玩家资料还没有有效的 QQ 号或手机号。请先完成编辑，再继续$continuation。",
        onBack = onBack
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBackground)
                .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerAvatar(profile, 58.dp)
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.nickname, color = PrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("这份资料还没有可用的联系方式。", color = Destructive, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("编辑并补充联系方式", onEditProfile, Modifier.fillMaxWidth())
        Spacer(Modifier.height(9.dp))
        SecondaryButton("返回玩家资料库", onBack, Modifier.fillMaxWidth())
    }
}

private fun profileContactSummary(profile: PlayerProfile): String = when {
    profile.qqNumber?.isNotBlank() == true -> "QQ：${profile.qqNumber}"
    profile.phoneNumber?.isNotBlank() == true -> "手机号：${profile.phoneNumber}"
    else -> ""
}

@Composable
private fun ClaimPreferenceMismatchDialog(
    profileNickname: String,
    currentPreferenceLabel: String,
    profilePreference: PlayPreference,
    fixedPartnerDisplayId: String?,
    onDismiss: () -> Unit,
    onKeepCurrent: () -> Unit,
    onUseProfileDefault: () -> Unit
) {
    ModalSurface(onDismiss, width = 500.dp) {
        Text("选择本次游玩偏好", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "“$profileNickname”的资料默认偏好为“${playPreferenceLabel(profilePreference)}”，当前临时登记为“$currentPreferenceLabel”。请选择认领后本次登记使用的偏好。这个选择不会修改玩家资料中的默认偏好。",
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        if (fixedPartnerDisplayId != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "使用资料默认偏好会解除与“$fixedPartnerDisplayId”的固定组合；对方会保留在原位置，并恢复为允许他人加入。",
                color = Destructive,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        ActionRow(
            title = "保留当前登记偏好",
            description = "继续使用“$currentPreferenceLabel”，队列组合保持不变。",
            onClick = onKeepCurrent
        )
        Spacer(Modifier.height(9.dp))
        ActionRow(
            title = "使用资料默认偏好",
            description = if (fixedPartnerDisplayId != null) {
                "解除固定组合，本次改为“${playPreferenceLabel(profilePreference)}”；等待位置会重新划分。"
            } else {
                "本次改为“${playPreferenceLabel(profilePreference)}”，等待位置可能重新划分。"
            },
            onClick = onUseProfileDefault
        )
        Spacer(Modifier.height(13.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun ProfileJoinPreferenceChoice(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) SoftBlue else CardBackground)
            .border(if (selected) 2.dp else 1.dp, if (selected) SystemBlue else Separator, RoundedCornerShape(12.dp))
            .clickable(enabled = !selected, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (selected) StraightCheckMark(Modifier.size(14.dp), color = SystemBlue)
    }
}

@Composable
private fun PlayerAvatar(profile: PlayerProfile, size: Dp) {
    val colors = listOf(
        Color(0xFF2F6B9A), Color(0xFF3B7D5C), Color(0xFF8B5E83),
        Color(0xFFA05A4A), Color(0xFF58677C), Color(0xFF8A6A24)
    )
    val color = colors[(profile.id.hashCode() and Int.MAX_VALUE) % colors.size]
    val initial = profile.nickname.takeFirstCodePoint()
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(initial, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PlayerGenderMark(
    gender: PlayerGender,
    hideUndisclosed: Boolean,
    fontSize: TextUnit = 15.sp
) {
    if (gender == PlayerGender.UNDISCLOSED && hideUndisclosed) return
    Text(
        playerGenderSymbol(gender),
        color = playerGenderColor(gender),
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

private fun playerGenderSymbol(gender: PlayerGender): String = when (gender) {
    PlayerGender.MALE -> "♂"
    PlayerGender.FEMALE -> "♀"
    PlayerGender.UNDISCLOSED -> "—"
}

private fun playerGenderColor(gender: PlayerGender): Color = when (gender) {
    PlayerGender.MALE -> Color(0xFF1479D1)
    PlayerGender.FEMALE -> Color(0xFFE04B87)
    PlayerGender.UNDISCLOSED -> TertiaryText
}

@Composable
private fun playerProfileTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PrimaryText,
    unfocusedTextColor = PrimaryText,
    focusedBorderColor = SystemBlue,
    unfocusedBorderColor = Color(0xFFA7A7AC),
    focusedLabelColor = SystemBlue,
    unfocusedLabelColor = SecondaryText,
    focusedPlaceholderColor = SecondaryText,
    unfocusedPlaceholderColor = SecondaryText,
    focusedSupportingTextColor = SecondaryText,
    unfocusedSupportingTextColor = SecondaryText,
    focusedContainerColor = CardBackground,
    unfocusedContainerColor = CardBackground,
    cursorColor = SystemBlue
)

@Composable
private fun PreferenceScreen(
    selected: PlayPreference?,
    onSelect: (PlayPreference) -> Unit,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    onFriendPair: () -> Unit
) {
    WizardPage(
        step = "3 / 3",
        title = "选择你的游玩偏好",
        subtitle = "你的选择会显示在登记中，并用于安排每一轮的游玩组合。",
        onBack = onBack
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OptionCard(
                "单人游玩",
                "这份登记始终单独占用一个等待位置。",
                selected == PlayPreference.SOLO,
                true,
                { onSelect(PlayPreference.SOLO) },
                Modifier.weight(1f)
            )
            OptionCard(
                "允许他人加入（推荐）",
                "系统会按顺序与其他开放登记组成共同游玩；接受系统分配通常可以缩短等待时间，每轮最多两人。",
                selected == PlayPreference.OPEN_TO_JOIN,
                true,
                { onSelect(PlayPreference.OPEN_TO_JOIN) },
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SmallActionButton("与朋友共同游玩", onFriendPair)
            Spacer(Modifier.width(12.dp))
            Text(
                "选择一份现有登记，或为朋友创建登记，并形成固定的共同游玩组合。",
                color = SecondaryText,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("组合不会改变原有的先后顺序，也不会延后其他登记应当取得的游玩机会。", color = SecondaryText, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("完成并加入排队", onComplete, Modifier.fillMaxWidth(), selected != null)
    }
}

@Composable
private fun BatchAmountScreen(
    amount: String,
    maximum: Int,
    onAmountChange: (String) -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    val amountValue = amount.toIntOrNull()
    WizardPage(
        step = "2 / 2",
        title = "创建临时登记",
        subtitle = "输入需要录入的玩家数量。所有登记将默认允许他人加入，并按创建先后排列在登记顺序末端。",
        onBack = onBack
    ) {
        Row(verticalAlignment = Alignment.Top) {
            StepperButton("−", onDecrease, enabled = (amountValue ?: 1) > 1)
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = onAmountChange,
                label = { Text("登记数量") },
                supportingText = { Text("本机台还可以创建 $maximum 份登记；每台最多 20 人。") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = RoundedCornerShape(ControlRadius),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PrimaryText,
                    unfocusedTextColor = PrimaryText,
                    focusedBorderColor = SystemBlue,
                    unfocusedBorderColor = Color(0xFFA7A7AC),
                    focusedLabelColor = SystemBlue,
                    unfocusedLabelColor = SecondaryText,
                    focusedSupportingTextColor = SecondaryText,
                    unfocusedSupportingTextColor = SecondaryText,
                    focusedContainerColor = CardBackground,
                    unfocusedContainerColor = CardBackground,
                    cursorColor = SystemBlue
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            StepperButton("＋", onIncrease, enabled = (amountValue ?: 0) < maximum)
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            "创建 ${amountValue ?: 0} 份登记",
            onComplete,
            Modifier.fillMaxWidth(),
            amountValue != null && amountValue in 1..maximum
        )
    }
}

@Composable
private fun ClaimRegistrationScreen(
    displayId: String,
    onPlayerLibrary: () -> Unit,
    onBack: () -> Unit
) {
    WizardPage(
        step = "认领登记",
        title = "认领这份排队登记",
        subtitle = "将“$displayId”与玩家资料关联。登记顺序不会改变；如果资料默认游玩偏好与当前登记不同，系统会先询问本次使用哪一项。",
        onBack = onBack
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OptionCard(
                "创建临时登记",
                "这已经是一份临时登记，不能再次创建。",
                false,
                false,
                {},
                Modifier.weight(1f),
                badge = "不可选择"
            )
            OptionCard(
                "使用玩家资料库",
                "选择本机保存的玩家资料来认领这份登记。",
                false,
                true,
                onPlayerLibrary,
                Modifier.weight(1f)
            )
            OptionCard(
                "使用二维码",
                "使用你的移动设备登录并认领登记。",
                false,
                false,
                {},
                Modifier.weight(1f),
                badge = "暂不可用"
            )
        }
    }
}

@Composable
private fun WizardPage(
    step: String,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val nowMillis = rememberCurrentTimeMillis()
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 36.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppBackButton(onBack)
            Spacer(Modifier.weight(1f))
            Text(
                SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(nowMillis)),
                color = PrimaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(16.dp))
            Text(step, color = TertiaryText, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxSize().widthIn(max = 820.dp).align(Alignment.CenterHorizontally)) {
            Column(
                Modifier.fillMaxWidth().align(Alignment.Center)
                    .verticalScroll(rememberScrollState()).padding(vertical = 20.dp)
            ) {
                Text(title, color = PrimaryText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                Text(subtitle, color = SecondaryText, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(24.dp))
                content()
            }
        }
    }
}

@Composable
private fun OptionCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null
) {
    val targetBorderColor = when {
        !enabled -> Separator.copy(alpha = .6f)
        selected -> SystemBlue
        else -> Separator
    }
    val backgroundColor by animateColorAsState(
        when {
            !enabled -> PageBackground
            selected -> SoftBlue
            else -> CardBackground
        },
        tween(180),
        label = "$title 选项背景"
    )
    val borderColor by animateColorAsState(
        targetBorderColor,
        tween(180),
        label = "$title 选项边框"
    )
    val borderWidth by animateDpAsState(
        if (selected) 2.dp else 1.dp,
        tween(180),
        label = "$title 选项边框宽度"
    )
    Column(
        modifier.height(136.dp).clip(RoundedCornerShape(CardRadius))
            .background(backgroundColor)
            .border(borderWidth, borderColor, RoundedCornerShape(CardRadius))
            .clickable(enabled = enabled, onClick = onClick).padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = if (enabled) PrimaryText else TertiaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (badge != null) {
                Text(
                    badge,
                    color = TertiaryText,
                    fontSize = 10.sp,
                    modifier = Modifier.clip(RoundedCornerShape(100.dp)).background(PageBackground)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            } else if (selected) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(SystemBlue), contentAlignment = Alignment.Center) {
                    StraightCheckMark(Modifier.size(11.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(description, color = if (enabled) SecondaryText else TertiaryText, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun FriendPairFlowDialog(
    machineId: MachineId,
    registration: Registration,
    queue: MachineQueue,
    idAlreadyExists: (String) -> Boolean,
    onGenerateFriendId: () -> String,
    onDismiss: () -> Unit,
    onPairExisting: (FriendPairPlan) -> Unit,
    onCreateFriend: (String) -> Unit
) {
    var step by remember(registration.key) { mutableStateOf(FriendPairStep.METHOD) }
    var selectedPlan by remember(registration.key) { mutableStateOf<FriendPairPlan?>(null) }
    var friendIdDraft by remember(registration.key) { mutableStateOf("") }
    var friendConsentConfirmed by remember(registration.key) { mutableStateOf(false) }
    val isWaiting = queue.waiting.any { it.key == registration.key }
    val candidates = if (isWaiting) {
        queue.waiting.filter {
            it.key != registration.key &&
                (it.fixedPartnerKey == null || it.fixedPartnerKey == registration.key)
        }
    } else {
        emptyList()
    }
    val positionLabels = buildMap {
        queue.waitingPositions().forEachIndexed { index, registrations ->
            registrations.forEach { put(it.key, "位置 ${machineId.name}${index + 1}") }
        }
    }
    val friendIdAlreadyExists = friendIdDraft.isNotBlank() && idAlreadyExists(friendIdDraft)
    val currentPartner = registration.fixedPartnerKey?.let { partnerKey ->
        queue.waiting.firstOrNull { it.key == partnerKey }
    }

    ModalSurface(onDismiss, width = 560.dp) {
        when (step) {
            FriendPairStep.METHOD -> {
                Text("与朋友共同游玩", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isWaiting) {
                        "请选择朋友已有的登记，或为朋友创建一份新的临时登记。固定组合只会通过让组合中的前位玩家后移来实现，不会延后其他玩家。"
                    } else {
                        "这份登记正在${playingPositionName(machineId)}。请在本轮结束并重新进入等待顺序后，再设置固定组合。"
                    },
                    color = SecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(17.dp))
                MenuActionButton(
                    MenuAction(
                        "选择现有登记",
                        if (candidates.isNotEmpty()) {
                            "从当前机台的等待登记中选择朋友，并检查顺序变化。"
                        } else {
                            "当前没有可用于组成固定组合的其他等待登记。"
                        },
                        { step = FriendPairStep.SELECT_EXISTING },
                        enabled = candidates.isNotEmpty()
                    ),
                    Modifier.widthIn(max = 340.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(9.dp))
                MenuActionButton(
                    MenuAction(
                        "为朋友创建登记",
                        when {
                            !isWaiting -> "${playingPositionName(machineId)} 中的登记暂时不能创建固定组合。"
                            queue.registrationCount >= 20 -> "当前机台已达到 20 人上限，无法继续创建登记。"
                            else -> "创建一份临时登记，并让你们在登记顺序末端组成固定组合。"
                        },
                        {
                            friendConsentConfirmed = false
                            step = FriendPairStep.CREATE_FRIEND
                        },
                        enabled = isWaiting && queue.registrationCount < 20
                    ),
                    Modifier.widthIn(max = 340.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                )
            }

            FriendPairStep.SELECT_EXISTING -> {
                Text("选择朋友的登记", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "系统会以较靠后的登记为基准安排固定组合，并先检查是否有其他玩家因此延后。",
                    color = SecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(14.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(candidates, key = { _, candidate -> candidate.key }) { _, candidate ->
                        MenuActionButton(
                            MenuAction(
                                candidate.displayId,
                                "${positionLabels[candidate.key] ?: "等待顺序"} · ${playPreferenceLabel(candidate)}。",
                                {
                                    selectedPlan = queue.planFriendPair(registration.key, candidate.key)
                                    friendConsentConfirmed = false
                                    step = FriendPairStep.CONFIRM_EXISTING
                                }
                            ),
                            Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                CancelAction { step = FriendPairStep.METHOD }
            }

            FriendPairStep.CONFIRM_EXISTING -> {
                val plan = selectedPlan
                val delayedOthers = plan?.delayedOtherRegistrations.orEmpty()
                val movedBack = plan?.movedBackRegistrations.orEmpty()
                Text(
                    when {
                        plan == null -> "无法确认固定组合"
                        delayedOthers.isEmpty() -> "可以组成固定组合"
                        else -> "不能直接组成固定组合"
                    },
                    color = PrimaryText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                when {
                    plan == null -> Text(
                        "无法确认这两份登记当前所在的等待顺序，请返回后重新选择。",
                        color = Destructive,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                    delayedOthers.isNotEmpty() -> Text(
                        "如果直接组成固定组合，${delayedOthers.joinToString("、") { "“${it.displayId}”" }} 将会延后游玩。系统不会应用这项调整。",
                        color = Destructive,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                    movedBack.isEmpty() -> Text(
                        "“${plan.firstRegistration.displayId}”和“${plan.secondRegistration.displayId}”可以共同游玩。建立固定组合不会使其他玩家延后，也不需要改变你们当前取得游玩机会的轮次。",
                        color = SecondaryText,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                    else -> Text(
                        "为避免延后其他玩家，${movedBack.joinToString("、") { "“${it.displayId}”" }} 需要放弃当前较靠前的位置，并向后移动到位置 ${machineId.name}${plan.pairPositionIndex + 1}。其他玩家的游玩机会只会保持或提前。",
                        color = SecondaryText,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
                if (plan != null && delayedOthers.isEmpty()) {
                    val selectedPartnerKey = if (plan.firstRegistration.key == registration.key) {
                        plan.secondRegistration.key
                    } else {
                        plan.firstRegistration.key
                    }
                    if (currentPartner != null && currentPartner.key != selectedPartnerKey) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "确认后，与“${currentPartner.displayId}”的原固定组合会解除；该登记会恢复为允许他人加入。",
                            color = SecondaryText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "固定组合建立后，系统不会再为这两份登记自动匹配其他玩家。为保持原有游玩轮次，其他选择允许他人加入的登记可能重新组合；只要其游玩轮次没有变晚，就不视为延后。",
                        color = PrimaryText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(SoftBlue).padding(horizontal = 13.dp, vertical = 10.dp)
                    )
                    Spacer(Modifier.height(11.dp))
                    FriendConsentConfirmation(
                        checked = friendConsentConfirmed,
                        text = "双方已经明确同意组成固定组合。",
                        onToggle = { friendConsentConfirmed = !friendConsentConfirmed }
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        "确认组成固定组合",
                        { onPairExisting(plan) },
                        Modifier.fillMaxWidth(),
                        enabled = friendConsentConfirmed
                    )
                }
                Spacer(Modifier.height(8.dp))
                CancelAction { step = FriendPairStep.SELECT_EXISTING }
            }

            FriendPairStep.CREATE_FRIEND -> {
                Text("为朋友创建登记", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "朋友会取得一份新的临时登记。你们两份登记将一起移动到当前机台的登记顺序末端，并形成固定组合；其他玩家不会因此延后。",
                    color = SecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                if (currentPartner != null) {
                    Spacer(Modifier.height(9.dp))
                    Text(
                        "这也会解除当前与“${currentPartner.displayId}”的固定组合；该登记会恢复为允许他人加入。",
                        color = SecondaryText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RegistrationNicknameField(
                        friendIdDraft,
                        { friendIdDraft = limitCodePointLength(it, 18) },
                        friendIdAlreadyExists,
                        Modifier.weight(1f),
                        label = "朋友的昵称",
                        placeholder = "输入昵称或生成随机昵称"
                    )
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton(
                        "生成随机昵称",
                        { friendIdDraft = onGenerateFriendId() },
                        Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(Modifier.height(11.dp))
                FriendConsentConfirmation(
                    checked = friendConsentConfirmed,
                    text = "朋友本人已经明确同意由我代为创建登记并组成固定组合。",
                    onToggle = { friendConsentConfirmed = !friendConsentConfirmed }
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    "创建并组成固定组合",
                    { onCreateFriend(friendIdDraft) },
                    Modifier.fillMaxWidth(),
                    friendIdDraft.isNotBlank() &&
                        !friendIdAlreadyExists &&
                        queue.registrationCount < 20 &&
                        friendConsentConfirmed
                )
                Spacer(Modifier.height(8.dp))
                CancelAction { step = FriendPairStep.METHOD }
            }
        }
        if (step == FriendPairStep.METHOD) {
            Spacer(Modifier.height(12.dp))
            CancelAction(onDismiss)
        }
    }
}

@Composable
private fun FriendConsentConfirmation(
    checked: Boolean,
    text: String,
    onToggle: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PageBackground)
            .clickable(onClick = onToggle).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                .background(if (checked) SystemBlue else Color.Transparent)
                .border(
                    1.dp,
                    if (checked) SystemBlue else Separator,
                    RoundedCornerShape(6.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) StraightCheckMark(Modifier.size(12.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = PrimaryText, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun RegistrationActions(
    registration: Registration,
    playerProfileGender: PlayerGender?,
    playerProfileQqNumber: String?,
    playerProfilePhoneNumber: String?,
    fixedPartnerDisplayId: String?,
    playingPartnerDisplayId: String?,
    isPlayingPosition: Boolean,
    playingPositionLabel: String,
    canMoveIntoPlaying: Boolean,
    canReportNoShow: Boolean,
    allowDeferOneRound: Boolean,
    allowTemporaryLeave: Boolean,
    transferMachineName: String,
    transferUnavailableReason: String?,
    canEditPlayerProfile: Boolean,
    mode: RegistrationActionMode,
    renameDraft: String,
    renameAlreadyExists: Boolean,
    onRenameDraftChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onMoveIntoPlaying: () -> Unit,
    onReturnToWaitingFront: () -> Unit,
    onPauseOrLeave: () -> Unit,
    onCancelDeferOneRound: () -> Unit,
    onCancelTemporaryLeave: () -> Unit,
    onChangePreference: () -> Unit,
    onPreferenceSelected: (PlayPreference) -> Unit,
    onFriendPair: () -> Unit,
    onRename: () -> Unit,
    onRenameConfirm: () -> Unit,
    onClaim: () -> Unit,
    onEditPlayerProfile: () -> Unit,
    onTransfer: () -> Unit,
    onNoShow: () -> Unit,
    onExit: () -> Unit
) {
    ModalSurface(onDismiss, width = 560.dp) {
        when (mode) {
            RegistrationActionMode.PREFERENCE -> {
                Text("更改游玩偏好", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (registration.playerProfileId != null) {
                        "新的选择只用于本次排队，并会立即用于重新划分等待位置；玩家资料中的默认偏好不会改变。"
                    } else {
                        "新的选择会立即用于重新划分等待位置。"
                    },
                    color = SecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                if (fixedPartnerDisplayId != null) {
                    Spacer(Modifier.height(9.dp))
                    Text(
                        "当前与“$fixedPartnerDisplayId”组成固定组合。改为单人游玩或允许他人加入时，双方的固定组合会同时解除。",
                        color = SecondaryText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.height(18.dp))
                val isFixedPair = registration.fixedPartnerKey != null
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(PageBackground)
                        .border(1.dp, Separator.copy(alpha = .78f), RoundedCornerShape(14.dp))
                ) {
                    PreferenceSelectionRow(
                        title = "单人游玩",
                        description = "这份登记将独自占用一个等待位置。",
                        selected = !isFixedPair && registration.preference == PlayPreference.SOLO,
                        onClick = { onPreferenceSelected(PlayPreference.SOLO) }
                    )
                    HorizontalDivider(color = Separator.copy(alpha = .72f))
                    PreferenceSelectionRow(
                        title = "允许他人加入",
                        description = "这份登记可以与相邻的开放登记组成共同游玩位置。",
                        selected = !isFixedPair && registration.preference == PlayPreference.OPEN_TO_JOIN,
                        onClick = { onPreferenceSelected(PlayPreference.OPEN_TO_JOIN) }
                    )
                    HorizontalDivider(color = Separator.copy(alpha = .72f))
                    PreferenceSelectionRow(
                        title = "与朋友共同游玩",
                        description = "与指定玩家形成固定组合，并在不延后其他玩家的前提下调整顺序。",
                        selected = isFixedPair,
                        onClick = onFriendPair
                    )
                }
            }

            RegistrationActionMode.RENAME -> {
                Text("修改登记昵称", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (registration.playerProfileId != null) {
                        "这里只修改本次登记显示的昵称，不会修改玩家资料。昵称应当能够让现场玩家确认对应的人。"
                    } else {
                        "昵称应当能够让现场玩家确认这份登记对应的人。"
                    },
                    color = SecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(16.dp))
                RegistrationNicknameField(renameDraft, onRenameDraftChange, renameAlreadyExists, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    "保存昵称",
                    onRenameConfirm,
                    Modifier.fillMaxWidth(),
                    renameDraft.isNotBlank() && !renameAlreadyExists
                )
            }

            RegistrationActionMode.ACTIONS -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        registration.displayId,
                        color = PrimaryText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onRename, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑登记昵称",
                            tint = SystemBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    registrationAbsenceStatusLabel(registration, includeSkippedTurns = true)?.let { status ->
                        DetailPill(
                            text = status,
                            color = AbsenceStatusColor,
                            backgroundColor = AbsenceStatusBackground
                        )
                    }
                    DetailPill(
                        fixedPartnerDisplayId?.let { "与 $it 共同游玩" }
                            ?: playPreferenceLabel(registration)
                    )
                    DetailPill(
                        when {
                            registration.isTemporary -> "临时登记"
                            registration.playerProfileId != null -> "玩家资料"
                            else -> "已认领登记"
                        }
                    )
                    if (registration.playerProfileId != null) {
                        playerProfileGender?.takeIf { it != PlayerGender.UNDISCLOSED }?.let { gender ->
                            DetailPill(
                                text = playerGenderSymbol(gender),
                                color = playerGenderColor(gender)
                            )
                        }
                    }
                }
                val contact = canonicalPlayerContact(playerProfileQqNumber, playerProfilePhoneNumber)
                if (registration.playerProfileId != null && contact.isPresent) {
                    Spacer(Modifier.height(7.dp))
                    DetailPill(
                        contact.qqNumber?.let { "QQ：$it" }
                            ?: contact.phoneNumber?.let { "手机号：$it" }
                            ?: ""
                    )
                }
                Spacer(Modifier.height(14.dp))
                MetadataRow("创建时间", formatRegistrationTime(registration.createdAtMillis))
                MetadataRow(
                    "上次游玩",
                    registration.lastPlayedAtMillis?.let(::formatRegistrationTime) ?: "尚未游玩"
                )
                if (registration.noShowCount > 0) MetadataRow("未到场记录", "${registration.noShowCount} 次")
                Spacer(Modifier.height(16.dp))
                val queueActions = buildList {
                    if (canMoveIntoPlaying) {
                        add(
                            MenuAction(
                                "应处于游玩位置",
                                "现场实际为共同游玩时，将这份登记移入$playingPositionLabel，并同步修正相关游玩偏好。",
                                onMoveIntoPlaying,
                                accented = true
                            )
                        )
                    }
                    when (registration.absenceStatus) {
                        QueueAbsenceStatus.DEFER_ONE_ROUND -> add(
                            MenuAction(
                                "取消暂缓一轮",
                                "恢复下一次游玩机会；登记保持当前顺序。",
                                onCancelDeferOneRound,
                                accented = false
                            )
                        )
                        QueueAbsenceStatus.TEMPORARILY_AWAY -> add(
                            MenuAction(
                                "取消暂时离开",
                                "恢复正常轮候，并将已轮空次数清零。",
                                onCancelTemporaryLeave,
                                accented = false
                            )
                        )
                        QueueAbsenceStatus.NONE -> add(
                            MenuAction(
                                "暂缓或暂离",
                                if (allowDeferOneRound || allowTemporaryLeave) {
                                    "选择只跳过下一轮，或在返回前持续轮空。"
                                } else {
                                    "系统规则不允许。"
                                },
                                onPauseOrLeave,
                                enabled = allowDeferOneRound || allowTemporaryLeave,
                                accented = false
                            )
                        )
                    }
                    add(
                        MenuAction(
                            "转至 $transferMachineName",
                            if (isPlayingPosition) {
                                ""
                            } else if (transferUnavailableReason == null) {
                                "离开当前机台，并在 $transferMachineName 的登记顺序末端重新排队。"
                            } else {
                                transferUnavailableReason
                            },
                            onTransfer,
                            enabled = !isPlayingPosition && transferUnavailableReason == null,
                            accented = false
                        )
                    )
                }
                val registrationActions = buildList {
                    add(
                        MenuAction(
                            "更改游玩偏好",
                            if (registration.playerProfileId != null) {
                                "只调整本次排队的偏好，不会修改玩家资料中的默认偏好。"
                            } else {
                                "调整为单人游玩、允许他人加入或与朋友共同游玩。"
                            },
                            onChangePreference
                        )
                    )
                    if (registration.isTemporary) {
                        add(MenuAction("认领登记", "通过登录将这份临时登记关联到你的身份。", onClaim))
                    }
                    if (canEditPlayerProfile) {
                        add(
                            MenuAction(
                                "编辑玩家资料",
                                "修改昵称、性别、联系方式或资料库默认偏好；不会改变本次登记的游玩偏好。",
                                onEditPlayerProfile
                            )
                        )
                    }
                }

                MenuSectionHeader("排队安排")
                CompactActionGrid(queueActions)
                if (isPlayingPosition) {
                    Spacer(Modifier.height(9.dp))
                    MenuActionButton(
                        MenuAction(
                            "撤回至等待顺序前端",
                            when {
                                fixedPartnerDisplayId != null ->
                                    "只撤回这份登记；确认后会解除固定组合，另一份登记继续留在$playingPositionLabel。"
                                playingPartnerDisplayId != null ->
                                    "只撤回这份登记；另一份登记会继续留在$playingPositionLabel，双方游玩偏好保持不变。"
                                else ->
                                    "将这份登记撤回等待顺序前端；$playingPositionLabel 会保持空缺。"
                            },
                            onReturnToWaitingFront
                        ),
                        Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(16.dp))
                MenuSectionHeader("登记与玩家")
                CompactActionGrid(registrationActions, accented = false)

                Spacer(Modifier.height(16.dp))
                MenuSectionHeader("到场与退出")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    MenuActionButton(
                        MenuAction(
                            "未到场",
                            when {
                                registration.absenceStatus != QueueAbsenceStatus.NONE ->
                                    unavailableNoShowExplanation(listOf(registration))
                                !canReportNoShow ->
                                    "现在还未轮到这名玩家游玩，不能记录为未到场。"
                                isPlayingPosition ->
                                    "将这次进入$playingPositionLabel 记录为未到场，并选择后续处理方式。"
                                else -> "记录本次未到场，并选择如何处理这份登记。"
                            },
                            onNoShow,
                            destructive = true,
                            enabled = canReportNoShow
                        ),
                        Modifier.weight(1f)
                    )
                    MenuActionButton(
                        MenuAction("退出排队", "移除这份登记；继续游玩时需要重新排队。", onExit, destructive = true),
                        Modifier.weight(1f)
                    )
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun MoveIntoPlayingConfirmation(
    currentPlayer: Registration,
    joiningPlayer: Registration,
    playingPositionLabel: String,
    fixedPartnerDisplayId: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val preferenceChanges = buildList {
        if (currentPlayer.preference == PlayPreference.SOLO) add("“${currentPlayer.displayId}”")
        if (joiningPlayer.preference == PlayPreference.SOLO) add("“${joiningPlayer.displayId}”")
    }
    ModalSurface(onDismiss, width = 470.dp) {
        Text("调整为共同游玩？", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "是否调整为“${currentPlayer.displayId}”和“${joiningPlayer.displayId}”共同游玩？确认后，“${joiningPlayer.displayId}”会从首个等待位置移入$playingPositionLabel。",
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        if (preferenceChanges.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "为了使本次登记与本轮共同游玩一致，${preferenceChanges.joinToString("和")}的游玩偏好将改为允许他人加入。",
                color = SecondaryText,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
        if (fixedPartnerDisplayId != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "“${joiningPlayer.displayId}”当前与“$fixedPartnerDisplayId”组成固定组合。此操作会解除该组合；“$fixedPartnerDisplayId”会保留在等待顺序中，并改为允许他人加入。",
                color = SecondaryText,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton("确认共同游玩", onConfirm, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun PreferenceSelectionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 70.dp)
            .clickable(enabled = !selected, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(description, color = SecondaryText, fontSize = 10.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) StraightCheckMark(Modifier.size(15.dp), color = SystemBlue)
        }
    }
}

@Composable
private fun DetailPill(
    text: String,
    color: Color = SecondaryText,
    backgroundColor: Color = PageBackground
) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        modifier = Modifier.clip(RoundedCornerShape(100.dp)).background(backgroundColor)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TertiaryText, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = SecondaryText, fontSize = 11.sp)
    }
}

@Composable
private fun RoundEndConfirmation(
    machineName: String,
    playingPositionLabel: String,
    nextPlayingNotice: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEndOnly: () -> Unit
) {
    var confirmEndOnly by remember { mutableStateOf(false) }
    ModalSurface(onDismiss, width = 480.dp) {
        if (confirmEndOnly) {
                Text("仅结束本轮？", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "$machineName 当前玩家的登记会回到队尾，但第一个等待位置不会自动进入$playingPositionLabel。完成后，$playingPositionLabel 会保持空缺，需要手动开始下一轮。",
                    color = SecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(18.dp))
                PrimaryButton("确认仅结束本轮", onEndOnly, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                CancelAction { confirmEndOnly = false }
        } else {
                Text("结束本轮游玩", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "请选择本轮结束后如何处理当前玩家的登记和下一轮游玩。",
                    color = SecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                if (nextPlayingNotice != null) {
                    Spacer(Modifier.height(11.dp))
                    Text(
                        nextPlayingNotice,
                        color = AbsenceStatusColor,
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(AbsenceStatusBackground)
                            .padding(horizontal = 13.dp, vertical = 11.dp)
                    )
                }
                Spacer(Modifier.height(18.dp))
                PrimaryButton("确认结束本轮并开始下一轮", onConfirm, Modifier.fillMaxWidth())
                Spacer(Modifier.height(9.dp))
                SecondaryButton(
                    "仅结束本轮",
                    { confirmEndOnly = true },
                    Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                CancelAction(onDismiss)
        }
    }
}

@Composable
private fun EnterPlayingConfirmation(
    playingPositionLabel: String,
    notice: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ModalSurface(onDismiss, width = 480.dp) {
        Text("确认进入$playingPositionLabel？", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            notice,
            color = AbsenceStatusColor,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(AbsenceStatusBackground)
                .padding(horizontal = 13.dp, vertical = 11.dp)
        )
        Spacer(Modifier.height(18.dp))
        PrimaryButton("确认进入$playingPositionLabel", onConfirm, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun MachineTransferConfirmation(
    registrations: List<Registration>,
    sourceMachineName: String,
    destinationMachineName: String,
    sourcePlayingPositionLabel: String,
    leavingPlayingPosition: Boolean,
    transferEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val isGroup = registrations.size > 1
    val transferredKeys = registrations.map { it.key }.toSet()
    val breaksFixedPair = registrations.any {
        it.fixedPartnerKey != null && it.fixedPartnerKey !in transferredKeys
    }
    val registrationNames = registrations.joinToString("、") { "“${it.displayId}”" }
    ModalSurface(onDismiss, width = 480.dp) {
        Text(
            "转至 $destinationMachineName？",
            color = PrimaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (isGroup) {
                "这会将 $registrationNames 从 $sourceMachineName 移出，并把整组登记加入 $destinationMachineName 的登记顺序末端。"
            } else {
                "这会将 ${registrationNames.ifBlank { "这份登记" }} 从 $sourceMachineName 移出，并加入 $destinationMachineName 的登记顺序末端。"
            },
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "原机台上的当前位置和排队顺序不会保留。即使之后再次转回 $sourceMachineName，也只能加入转回时的队尾，无法恢复到现在的位置。转入后，等待位置会按照 $destinationMachineName 的现有顺序和游玩偏好重新划分。",
            color = Destructive,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Destructive.copy(alpha = .07f))
                .border(
                    1.dp,
                    Destructive.copy(alpha = .18f),
                    RoundedCornerShape(12.dp)
                ).padding(horizontal = 13.dp, vertical = 11.dp)
        )
        if (leavingPlayingPosition) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (isGroup) {
                    "这组登记目前处于$sourcePlayingPositionLabel，确认后会立即退出该位置，原机台不会自动补入下一组登记。"
                } else {
                    "这份登记目前处于$sourcePlayingPositionLabel，确认后会立即退出该位置；如果$sourcePlayingPositionLabel 因此空缺，原机台不会自动补入下一组登记。"
                },
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
        if (breaksFixedPair) {
            Spacer(Modifier.height(10.dp))
            Text(
                "这份登记属于固定组合。只转移其中一份会同时解除双方的固定组合；留在原机台的登记会恢复为允许他人加入。",
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
        if (!transferEnabled) {
            Spacer(Modifier.height(10.dp))
            Text(
                "$destinationMachineName 的剩余容量不足，本次转移无法完成。",
                color = Destructive,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            "确认转至 $destinationMachineName",
            onConfirm,
            Modifier.fillMaxWidth(),
            enabled = transferEnabled
        )
        Spacer(Modifier.height(8.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun PositionActions(
    selection: PositionSelection,
    queue: MachineQueue,
    transferMachineName: String,
    transferUnavailableReason: String?,
    onDismiss: () -> Unit,
    onRegistrationClick: (Int) -> Unit,
    onFinishRound: () -> Unit,
    onReturnToWaitingFront: () -> Unit,
    onAdvanceToPlaying: () -> Unit,
    onEnterPlaying: () -> Unit,
    onTransfer: () -> Unit,
    onReleaseFixedPair: () -> Unit,
    onNoShow: () -> Unit,
    onRemove: () -> Unit
) {
    val registrations = queue.allRegistrations.filter { it.key in selection.registrationKeys }
    val playingPositionLabel = playingPositionName(selection.machineId)
    val nowMillis = rememberCurrentTimeMillis()
    val isFixedPair = registrations.size == 2 &&
        registrations[0].fixedPartnerKey == registrations[1].key &&
        registrations[1].fixedPartnerKey == registrations[0].key
    val detailPositionLabel = if (selection.isPlayingPosition) {
        playingPositionLabel
    } else {
        buildString {
            append("队列位置 ${selection.machineId.name}${(selection.waitingPositionIndex ?: 0) + 1}")
            if (isFixedPair) append(" · 固定组合")
        }
    }
    val firstAvailablePositionIndex = queue.firstAvailableWaitingPositionIndex()
    val showsRoundEndShortcut = selection.waitingPositionIndex == firstAvailablePositionIndex &&
        queue.playing.isNotEmpty()
    val canReportNoShow = registrations.isNotEmpty() && registrations.all { queue.canMarkNoShow(it.key) }
    val playingOvertime = queue.playingStartedAtMillis?.let { startedAt ->
        (nowMillis - startedAt).coerceAtLeast(0L) / 60_000L > 20
    } == true
    val targetPosition = selection.waitingPositionIndex?.let { queue.waitingPositions().getOrNull(it) }
    val canAdvanceToPlaying = playingOvertime &&
        (selection.waitingPositionIndex ?: 0) > 0 &&
        !showsRoundEndShortcut &&
        targetPosition?.map { it.key }?.toSet() == selection.registrationKeys.toSet() &&
        targetPosition.all { it.absenceStatus == QueueAbsenceStatus.NONE }
    val playActions = buildList {
        when {
            selection.isPlayingPosition && registrations.isNotEmpty() -> add(
                MenuAction("本轮结束", "结束当前游玩，并选择是否开始下一轮。", onFinishRound)
            )
            selection.isPlayingPosition -> add(
                MenuAction(
                    "进入$playingPositionLabel",
                    "将第一个可以上机的队列位置移入$playingPositionLabel。",
                    onEnterPlaying,
                    enabled = firstAvailablePositionIndex != null
                )
            )
        }
        if (showsRoundEndShortcut) {
            add(
                MenuAction(
                    "本轮结束",
                    "结束$playingPositionLabel 中的本轮游玩，并选择是否开始下一轮。此位置中的登记不会被视为本轮玩家。",
                    onFinishRound
                )
            )
        }
        if (canAdvanceToPlaying) {
            add(
                MenuAction(
                    "应处于游玩位置",
                    "现场已经推进到此位置，而此前只是连续忘记结束轮次时，按实际进度补记并调整整个队列。",
                    onAdvanceToPlaying
                )
            )
        }
    }
    val queueArrangementActions = buildList {
        if (registrations.isNotEmpty() && !selection.isPlayingPosition) {
            add(
                MenuAction(
                    "转至 $transferMachineName",
                    if (transferUnavailableReason == null) {
                        "将此位置中的登记移至 $transferMachineName 的登记顺序末端。"
                    } else {
                        transferUnavailableReason
                    },
                    onTransfer,
                    enabled = transferUnavailableReason == null
                )
            )
        }
        if (isFixedPair && !selection.isPlayingPosition) {
            add(
                MenuAction(
                    "释放组合",
                    "解除两份登记的固定共同游玩关系，并将双方的游玩偏好都改为允许他人加入。",
                    onReleaseFixedPair
                )
            )
        }
    }
    ModalSurface(onDismiss, width = 560.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(detailPositionLabel, color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (!selection.isPlayingPosition && registrations.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    positionWaitEstimateLabel(
                        registrations,
                        estimatedMinutesUntilPlaying(queue, selection.registrationKeys.toSet(), nowMillis)
                    ),
                    color = SecondaryText,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                registrations.isEmpty() -> "这个位置目前没有登记。"
                registrations.size == 1 -> "这个位置包含 1 份登记。"
                else -> "这是一个由 ${registrations.size} 份登记组成的共同游玩位置。以下操作会作用于整组登记。"
            },
            color = SecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        if (registrations.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("此位置中的登记")
            Spacer(Modifier.height(7.dp))
            HorizontalDivider(color = Separator.copy(alpha = .72f))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                registrations.forEachIndexed { index, registration ->
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(PageBackground)
                            .border(1.dp, Separator.copy(alpha = .8f), RoundedCornerShape(12.dp))
                            .clickable { onRegistrationClick(registration.key) }
                            .padding(start = 14.dp, end = 11.dp, top = 11.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("登记 ${index + 1}", color = TertiaryText, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                registration.displayId,
                                color = PrimaryText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                registrationAbsenceStatusLabel(
                                    registration,
                                    includeSkippedTurns = false
                                ) ?: playPreferenceLabel(registration),
                                color = if (registration.absenceStatus == QueueAbsenceStatus.NONE) {
                                    SecondaryText
                                } else {
                                    AbsenceStatusColor
                                },
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("›", color = TertiaryText, fontSize = 19.sp)
                    }
                }
            }
        }
        if (playActions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            MenuSectionHeader("游玩进度")
            CompactActionGrid(playActions)
            if (selection.isPlayingPosition && registrations.isNotEmpty()) {
                Spacer(Modifier.height(9.dp))
                MenuActionButton(
                    MenuAction(
                        "撤回至等待顺序前端",
                        "$playingPositionLabel 与现场不一致时，将整组按原顺序撤回等待顺序前端，再调整登记顺序或游玩偏好。$playingPositionLabel 会保持空缺。",
                        onReturnToWaitingFront
                    ),
                    Modifier.fillMaxWidth()
                )
            }
        }
        if (queueArrangementActions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            MenuSectionHeader("队列安排")
            CompactActionGrid(queueArrangementActions, accented = false)
        }
        if (registrations.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            MenuSectionHeader("到场与登记")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MenuActionButton(
                    MenuAction(
                        if (registrations.size > 1) "这组玩家未到场" else "未到场",
                        when {
                            registrations.any {
                                it.absenceStatus != QueueAbsenceStatus.NONE
                            } -> unavailableNoShowExplanation(registrations)
                            !canReportNoShow && registrations.size > 1 ->
                                "现在还未轮到这个位置中的玩家游玩，不能记录为未到场。"
                            !canReportNoShow ->
                                "现在还未轮到这名玩家游玩，不能记录为未到场。"
                            selection.isPlayingPosition && registrations.size > 1 ->
                                "将整组本次进入$playingPositionLabel 记录为未到场，并选择后续处理方式。"
                            selection.isPlayingPosition ->
                                "将这次进入$playingPositionLabel 记录为未到场，并选择后续处理方式。"
                            registrations.size > 1 ->
                                "记录整组玩家未到场，并选择如何处理这组登记。"
                            else -> "记录本次未到场，并选择如何处理这份登记。"
                        },
                        onNoShow,
                        destructive = true,
                        enabled = canReportNoShow
                    ),
                    Modifier.weight(1f)
                )
                MenuActionButton(
                    MenuAction(
                        if (registrations.size > 1) "移除这组登记" else "移除登记",
                        if (registrations.size > 1) "同时移除此位置中的全部登记。"
                        else "移除这份登记；继续游玩时需要重新排队。",
                        onRemove,
                        destructive = true
                    ),
                    Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun ReturnPlayingToWaitingConfirmation(
    playingPositionLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ModalSurface(onDismiss, width = 470.dp) {
        Text(
            "撤回至等待顺序前端？",
            color = PrimaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "此操作用于纠正登记误进入$playingPositionLabel 的情况，并不表示玩家已经完成游玩。确认后，此位置中的全部登记会按原有顺序回到等待顺序前端；$playingPositionLabel 保持空缺，系统不会自动开始下一轮。",
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "$playingPositionLabel 中的登记不会因其他登记修改游玩偏好而自动变化。撤回后再调整等待顺序或游玩偏好，可以使电子队列与现场实际玩家一致。",
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(18.dp))
        PrimaryButton("确认撤回", onConfirm, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun ReturnPlayingRegistrationConfirmation(
    registration: Registration,
    remainingPlayer: Registration?,
    isFixedPair: Boolean,
    playingPositionLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ModalSurface(onDismiss, width = 490.dp) {
        Text("撤回这份游玩登记？", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                remainingPlayer == null ->
                    "确认后，“${registration.displayId}”会回到等待顺序前端，$playingPositionLabel 保持空缺，系统不会自动开始下一轮。它的游玩偏好仍为“${playPreferenceLabel(registration)}”。"
                isFixedPair ->
                    "“${registration.displayId}”和“${remainingPlayer.displayId}”当前为固定组合。确认后，固定组合会解除，两份登记都会变为“允许他人加入”。“${registration.displayId}”会回到等待顺序前端，“${remainingPlayer.displayId}”会单独留在$playingPositionLabel 继续本轮。"
                else ->
                    "确认后，“${registration.displayId}”会回到等待顺序前端，“${remainingPlayer.displayId}”会单独留在$playingPositionLabel 继续本轮。两份登记的游玩偏好都会保持“允许他人加入”，不会改为“单人游玩”。"
            },
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(18.dp))
        PrimaryButton("确认撤回这份登记", onConfirm, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun AdvanceToPlayingConfirmation(
    selectionLabel: String,
    playingPositionLabel: String,
    registrations: List<Registration>,
    completedWaitingPositionCount: Int,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val names = registrations.joinToString("和") { "“${it.displayId}”" }
    val completedRoundCount = completedWaitingPositionCount + 1
    ModalSurface(onDismiss, width = 500.dp) {
        Text("调整为$playingPositionLabel？", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (registrations.isNotEmpty()) {
                "是否将 $selectionLabel 中的 $names 调整为当前正在游玩的登记？"
            } else {
                "此位置的登记已经发生变化，无法继续执行调整。"
            },
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        if (registrations.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "确认后，系统会补记 $completedRoundCount 轮已经结束：$playingPositionLabel，以及此位置之前的 $completedWaitingPositionCount 个等待位置，都会被视为已经依次完成游玩。",
                color = PrimaryText,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFFF4E5)).padding(horizontal = 13.dp, vertical = 11.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "这些已完成的登记会按实际游玩顺序回到等待顺序末端，$names 会进入$playingPositionLabel。此操作会同时改变多个等待位置，请只在现场顺序确实已经推进到这里时使用。",
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 19.sp
            )
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton("确认并补记轮次", onConfirm, Modifier.fillMaxWidth(), enabled = enabled)
        Spacer(Modifier.height(8.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun ReleaseFixedPairConfirmation(
    registrations: List<Registration>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val names = registrations.joinToString("和") { "“${it.displayId}”" }
    ModalSurface(onDismiss, width = 450.dp) {
        Text("释放固定组合？", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (registrations.size == 2) {
                "$names 将不再固定共同游玩。两份登记的游玩偏好都会改为允许他人加入，系统会按照登记顺序重新组成等待位置。"
            } else {
                "这个固定组合已经发生变化，无法继续执行释放操作。"
            },
            color = SecondaryText,
            fontSize = 12.sp,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            "确认释放组合",
            onConfirm,
            Modifier.fillMaxWidth(),
            enabled = registrations.size == 2
        )
        Spacer(Modifier.height(8.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun NoShowDialog(
    registration: Registration,
    fromPlayingPosition: Boolean,
    playingPositionLabel: String,
    waitingFrontPositionLabel: String,
    allowDeferOneRound: Boolean,
    onDismiss: () -> Unit,
    onDefer: () -> Unit,
    onMoveToEnd: () -> Unit,
    onRemove: () -> Unit
) {
    val occurrence = registration.noShowCount + 1
    ModalSurface(onDismiss, width = 540.dp) {
        Text("第 $occurrence 次未到场", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (fromPlayingPosition) {
            Text(
                "这份登记当前处于$playingPositionLabel。确认处理后，它会离开游玩位置；如果该位置因此无人游玩，将保持空缺，不会自动安排下一组。请核对现场后，再手动让下一组进入游玩位置。取消不会改变队列。",
                color = PrimaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(SoftBlue).border(
                        1.dp,
                        SystemBlue.copy(alpha = .14f),
                        RoundedCornerShape(12.dp)
                    ).padding(horizontal = 13.dp, vertical = 10.dp)
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(
            if (occurrence == 1) {
                "请根据玩家是否仍会回来，选择保留原位、移至队尾或退出排队。"
            } else if (!allowDeferOneRound) {
                "这是再次未到场。系统规则不允许暂缓一轮，请选择移至队尾或移除登记。"
            } else {
                "这是再次未到场。确认玩家仍会回来时可以暂缓一轮或移至队尾，否则建议移除登记。"
            },
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(17.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NoShowChoice(
                "暂缓一轮",
                if (allowDeferOneRound) {
                    if (fromPlayingPosition) {
                        "回到$waitingFrontPositionLabel，跳过本次机会后自动解除。"
                    } else {
                        "$waitingFrontPositionLabel 保持不变；跳过本次机会后自动解除。"
                    }
                } else {
                    "系统规则不允许。"
                },
                onClick = onDefer,
                visuallyDisabled = !allowDeferOneRound,
                modifier = Modifier.weight(1f)
            )
            NoShowChoice(
                "移至队尾",
                "将这份登记移动到当前队尾。",
                onClick = onMoveToEnd,
                modifier = Modifier.weight(1f)
            )
            NoShowChoice(
                "移除登记",
                "从当前排队中移除这份登记。",
                destructive = true,
                onClick = onRemove,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun GroupNoShowDialog(
    registrations: List<Registration>,
    fromPlayingPosition: Boolean,
    playingPositionLabel: String,
    waitingFrontPositionLabel: String,
    allowDeferOneRound: Boolean,
    onDismiss: () -> Unit,
    onDefer: () -> Unit,
    onMoveToEnd: () -> Unit,
    onRemove: () -> Unit
) {
    val anyPreviousNoShow = registrations.any { it.noShowCount > 0 }
    ModalSurface(onDismiss, width = 550.dp) {
        Text("记录这组玩家未到场", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (fromPlayingPosition) {
            Text(
                "这组登记当前处于$playingPositionLabel。确认处理后，整组会离开游玩位置，该位置将保持空缺，不会自动安排下一组。请核对现场后，再手动让下一组进入游玩位置。取消不会改变队列。",
                color = PrimaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(SoftBlue).border(
                        1.dp,
                        SystemBlue.copy(alpha = .14f),
                        RoundedCornerShape(12.dp)
                    ).padding(horizontal = 13.dp, vertical = 10.dp)
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(
            if (!anyPreviousNoShow) {
                "以下操作会同时作用于组内全部登记。请根据玩家是否仍会回来选择处理方式。"
            } else if (!allowDeferOneRound) {
                "组内已有登记曾被记录为未到场。系统规则不允许暂缓一轮，请选择整组移至队尾或移除整组登记。"
            } else {
                "组内已有登记曾被记录为未到场。确认仍会回来时可以暂缓一轮或移至队尾，否则建议移除整组登记。"
            },
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(17.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NoShowChoice(
                "暂缓一轮",
                if (allowDeferOneRound) {
                    if (fromPlayingPosition) {
                        "整组回到$waitingFrontPositionLabel，跳过本次机会后自动解除。"
                    } else {
                        "$waitingFrontPositionLabel 保持不变；整组跳过本次机会后自动解除。"
                    }
                } else {
                    "系统规则不允许。"
                },
                onClick = onDefer,
                visuallyDisabled = !allowDeferOneRound,
                modifier = Modifier.weight(1f)
            )
            NoShowChoice(
                "整组移至队尾",
                "将这组登记移动到当前队尾。",
                onClick = onMoveToEnd,
                modifier = Modifier.weight(1f)
            )
            NoShowChoice(
                "移除整组",
                "从当前排队中移除这组登记。",
                destructive = true,
                onClick = onRemove,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun QueueAbsenceDialog(
    displayId: String,
    fixedPartnerDisplayId: String?,
    playingPartnerDisplayId: String?,
    isPlayingPosition: Boolean,
    playingPositionLabel: String,
    allowDeferOneRound: Boolean,
    allowTemporaryLeave: Boolean,
    onDismiss: () -> Unit,
    onDeferOneRound: () -> Unit,
    onTemporarilyLeave: () -> Unit
) {
    var choice by remember(displayId, fixedPartnerDisplayId) {
        mutableStateOf<QueueAbsenceChoice?>(null)
    }
    val subject = fixedPartnerDisplayId?.let { "“$displayId”和“$it”" } ?: "“$displayId”"
    ModalSurface(onDismiss, width = 500.dp) {
        AnimatedContent(targetState = choice, label = "暂缓或暂离选项") { selectedChoice ->
            Column(Modifier.fillMaxWidth()) {
                when (selectedChoice) {
                    null -> {
                        Text("暂缓或暂离", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (fixedPartnerDisplayId == null) {
                                "请选择这份登记暂时无法游玩时的处理方式。"
                            } else {
                                "$subject 是固定组合，以下操作会同时作用于两份登记。"
                            },
                            color = SecondaryText,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(17.dp))
                        MenuActionButton(
                            MenuAction(
                                "暂缓一轮",
                                if (allowDeferOneRound) {
                                    "只跳过下一次游玩机会；本轮按其余在场登记重新组合，之后自动解除。"
                                } else {
                                    "系统规则不允许。"
                                },
                                { choice = QueueAbsenceChoice.DEFER_ONE_ROUND },
                                enabled = allowDeferOneRound
                            ),
                            Modifier.fillMaxWidth(),
                            accented = false
                        )
                        Spacer(Modifier.height(9.dp))
                        MenuActionButton(
                            MenuAction(
                                "暂时离开",
                                if (allowTemporaryLeave) {
                                    "暂离期间不参与游玩组合；每次轮到时移至队尾，返回后需要手动解除。"
                                } else {
                                    "系统规则不允许。"
                                },
                                { choice = QueueAbsenceChoice.TEMPORARILY_AWAY },
                                enabled = allowTemporaryLeave
                            ),
                            Modifier.fillMaxWidth(),
                            accented = false
                        )
                        Spacer(Modifier.height(10.dp))
                        CancelAction(onDismiss)
                    }

                    QueueAbsenceChoice.DEFER_ONE_ROUND -> {
                        Text(
                            if (fixedPartnerDisplayId == null) "确认暂缓一轮？" else "确认整组暂缓一轮？",
                            color = PrimaryText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                isPlayingPosition && fixedPartnerDisplayId != null ->
                                    "$subject 会一起离开$playingPositionLabel 并回到等待顺序前端。这次游玩机会会被跳过，两份登记保持原有顺序，随后自动解除暂缓。"
                                isPlayingPosition && playingPartnerDisplayId != null ->
                                    "$subject 会离开$playingPositionLabel 并回到等待顺序前端；“$playingPartnerDisplayId”继续本轮游玩。这次机会视为已经跳过，暂缓随即自动解除。"
                                isPlayingPosition ->
                                    "$subject 会离开$playingPositionLabel 并回到等待顺序前端。这次游玩机会会被跳过，登记保持原有顺序，随后自动解除暂缓。"
                                fixedPartnerDisplayId != null ->
                                    "下一次轮到$subject 时，整组不会上机。两份登记保持当前顺序，并在跳过这次机会后自动解除暂缓。"
                                else ->
                                    "下一次轮到$subject 时不会上机。这份登记保持当前顺序，并在跳过这次机会后自动解除暂缓。"
                            },
                            color = SecondaryText,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "暂缓的登记本轮不会占用共同游玩位置。系统会忽略它，并按照其余在场登记的单人游玩、允许他人加入或固定组合偏好重新组成下一轮。",
                            color = AbsenceStatusColor,
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(AbsenceStatusBackground)
                                .padding(horizontal = 13.dp, vertical = 10.dp)
                        )
                        Spacer(Modifier.height(18.dp))
                        PrimaryButton(
                            "确认暂缓一轮",
                            onDeferOneRound,
                            Modifier.fillMaxWidth(),
                            enabled = allowDeferOneRound
                        )
                        Spacer(Modifier.height(8.dp))
                        CancelAction("返回选择") { choice = null }
                    }

                    QueueAbsenceChoice.TEMPORARILY_AWAY -> {
                        Text(
                            if (fixedPartnerDisplayId == null) "确认暂时离开？" else "确认整组暂时离开？",
                            color = PrimaryText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (isPlayingPosition) {
                                "$subject 会离开$playingPositionLabel，并按一次轮空移至当前等待顺序末端。之后每次轮到时仍会移至队尾，状态不会自动解除。"
                            } else {
                                "下一次轮到$subject 时不会上机，而会按一次轮空移至当前等待顺序末端；之后每次轮到时仍会移至队尾。"
                            },
                            color = SecondaryText,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "暂时离开的登记不会占用共同游玩位置，系统会按照其余在场登记的游玩偏好重新组合。玩家返回后，需要从登记菜单手动取消暂时离开。连续轮空 3 次后仍未取消，第四次轮到时会自动退出排队。",
                            color = AbsenceStatusColor,
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(AbsenceStatusBackground)
                                .padding(horizontal = 13.dp, vertical = 10.dp)
                        )
                        Spacer(Modifier.height(18.dp))
                        PrimaryButton(
                            "确认暂时离开",
                            onTemporarilyLeave,
                            Modifier.fillMaxWidth(),
                            enabled = allowTemporaryLeave
                        )
                        Spacer(Modifier.height(8.dp))
                        CancelAction("返回选择") { choice = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoveRegistrationConfirmation(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ModalSurface(onDismiss, width = 430.dp) {
        Text(title, color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = SecondaryText, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(18.dp))
        DestructiveButton(confirmText, onConfirm, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun QueueStateLoadingOverlay() {
    Box(
        Modifier.fillMaxSize().background(PageBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在读取本机队列", color = PrimaryText, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text("请稍候。", color = SecondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun QueueRestoreDialog(
    savedState: PersistedQueueState,
    onRestore: () -> Unit,
    onStartNew: () -> Unit
) {
    var startNewConfirmationVisible by remember { mutableStateOf(false) }
    ModalSurface(onDismiss = {}, width = 500.dp) {
        if (startNewConfirmationVisible) {
            Text("开始新的队列？", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append("确认后，上次保存的队列和机台状态会被新的空队列替换。")
                    if (savedState.totalRegistrationCount > 0) {
                        append("现有 ${savedState.totalRegistrationCount} 份登记不会进入新队列。")
                    }
                    append("此操作无法撤销。")
                },
                color = SecondaryText,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(18.dp))
            DestructiveButton("确认开始新的队列", onStartNew, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            CancelAction { startNewConfirmationVisible = false }
        } else {
            Text("继续使用上次的队列？", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text(
                "上次队列保存于 ${formatQueueSnapshotTime(savedState.savedAtMillis)}。继续后会恢复游玩位置、等待顺序、暂缓一轮、暂时离开、未到场和机台状态。",
                color = SecondaryText,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(15.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PageBackground)
                    .border(1.dp, Separator.copy(alpha = .72f), RoundedCornerShape(10.dp))
            ) {
                QueueRestoreMachineRow(MachineId.A, savedState.machineA, savedState.machineAStatus)
                HorizontalDivider(color = Separator.copy(alpha = .72f))
                QueueRestoreMachineRow(MachineId.B, savedState.machineB, savedState.machineBStatus)
            }
            if (!savedState.registrationOpen) {
                Spacer(Modifier.height(9.dp))
                Text("上次退出时，登记排队处于关闭状态。", color = Color(0xFF9A5B00), fontSize = 11.sp)
            }
            if (
                (savedState.machineAStatus.isOperational && savedState.machineA.playing.isNotEmpty()) ||
                (savedState.machineBStatus.isOperational && savedState.machineB.playing.isNotEmpty())
            ) {
                Spacer(Modifier.height(9.dp))
                Text(
                    "本轮计时会沿用原来的开始时间，并包含应用关闭期间经过的时间。",
                    color = SecondaryText,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton("继续使用上次队列", onRestore, Modifier.fillMaxWidth())
            Spacer(Modifier.height(9.dp))
            DestructiveButton(
                "不使用，开始新的队列",
                {
                    if (savedState.hasMeaningfulState) startNewConfirmationVisible = true
                    else onStartNew()
                },
                Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun QueueRestoreMachineRow(
    machineId: MachineId,
    queue: MachineQueue,
    status: MachineStatus
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(machineName(machineId), color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(
            buildString {
                if (queue.registrationCount == 0) {
                    append("没有登记")
                } else {
                    append("${queue.waitingPositions().size} 个等待位置 · ${queue.registrationCount} 个登记")
                }
                if (!status.isOperational) append(" · 已停止使用")
            },
            color = if (status.isOperational) SecondaryText else Color(0xFF9A5B00),
            fontSize = 11.sp,
            textAlign = TextAlign.End
        )
    }
}

private fun formatQueueSnapshotTime(timestampMillis: Long): String =
    SimpleDateFormat("M 月 d 日 HH:mm:ss", Locale.CHINA).format(Date(timestampMillis))

@Composable
private fun CloseRegistrationConfirmation(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalSurface(onDismiss, width = 450.dp) {
        Text("关闭登记排队？", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "仅建议在无人排队，或现场已经能够自行辨认排队顺序时关闭。确认后将删除两台机台的全部登记，且无法恢复。",
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(18.dp))
        DestructiveButton("关闭登记排队", onConfirm, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun MoreMenu(
    registrationOpen: Boolean,
    canEditRegistrations: Boolean,
    canReportMachineStop: Boolean,
    onDismiss: () -> Unit,
    onEditRegistrations: () -> Unit,
    onOpenAuditLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onReportMachineStop: () -> Unit,
    onToggleRegistration: () -> Unit
) {
    ModalSurface(onDismiss, width = 390.dp) {
        Text("更多", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        MenuSectionHeader("队列管理")
        ActionRow(
            title = "编辑登记",
            enabled = canEditRegistrations,
            onClick = onEditRegistrations
        )
        Spacer(Modifier.height(7.dp))
        ActionRow(
            title = if (registrationOpen) "关闭登记排队" else "启用登记排队",
            destructive = registrationOpen,
            onClick = onToggleRegistration
        )

        Spacer(Modifier.height(16.dp))
        MenuSectionHeader("机台管理")
        ActionRow(
            title = "报告机台停止使用",
            destructive = true,
            enabled = canReportMachineStop,
            onClick = onReportMachineStop
        )

        Spacer(Modifier.height(16.dp))
        MenuSectionHeader("记录与应用")
        ActionRow(
            title = "操作日志",
            accented = false,
            onClick = onOpenAuditLog
        )
        Spacer(Modifier.height(7.dp))
        ActionRow(
            title = "应用设置",
            accented = false,
            onClick = onOpenSettings
        )
        Spacer(Modifier.height(7.dp))
        ActionRow(
            title = "应用详情",
            accented = false,
            onClick = onOpenAppDetails
        )
        Spacer(Modifier.height(13.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun AppDetailsDialog(
    cloudSyncStatus: QueueCloudSyncStatus,
    onDismiss: () -> Unit
) {
    ModalSurface(onDismiss, width = 500.dp) {
        Text("应用详情", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text("maimai Q · 舞萌机台排队管理终端", color = SecondaryText, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        AppDetailSectionTitle("版本信息")
        AppDetailRow("版本", BuildConfig.VERSION_NAME)
        HorizontalDivider(color = Separator.copy(alpha = .72f))
        AppDetailRow("构建编号", BuildConfig.VERSION_CODE.toString())
        Spacer(Modifier.height(15.dp))
        AppDetailSectionTitle("运行规格")
        AppDetailRow("排队方式", "机台 A 与机台 B 独立排序")
        HorizontalDivider(color = Separator.copy(alpha = .72f))
        AppDetailRow("单台上限", "20 个登记")
        HorizontalDivider(color = Separator.copy(alpha = .72f))
        AppDetailRow("系统要求", "Android 10 或更高版本")
        Spacer(Modifier.height(15.dp))
        AppDetailSectionTitle("数据说明")
        AppDetailRow("资料与日志", "保存在当前设备")
        HorizontalDivider(color = Separator.copy(alpha = .72f))
        AppDetailRow("日志保留", "最近 1,000 条操作")
        HorizontalDivider(color = Separator.copy(alpha = .72f))
        AppDetailRow("队列恢复", "保留最近一次本机队列状态")
        HorizontalDivider(color = Separator.copy(alpha = .72f))
        AppDetailRow("网站同步", queueCloudSyncStatusLabel(cloudSyncStatus))
        Spacer(Modifier.height(13.dp))
        Text(
            "玩家资料、操作日志和最近一次队列状态保存在本机。开启网站同步时，队列会先写入本机，再尝试上传；网络不可用时不会阻止现场操作。公开队列不会上传 QQ 号、手机号、性别或玩家资料内部编号。",
            color = SecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(18.dp))
        SecondaryButton("关闭", onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CloudSyncInfoDialog(
    status: QueueCloudSyncStatus,
    onDismiss: () -> Unit
) {
    val currentStatusColor = queueCloudSyncStatusColor(status.phase)
    ModalSurface(onDismiss, width = 540.dp) {
        Text("网站同步状态", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "开启网站同步后，每次队列变动会先保存在本机，再尝试上传。关闭同步或网络不可用时，都不会阻止现场的排队操作。",
            color = SecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(currentStatusColor.copy(alpha = .09f))
                .border(1.dp, currentStatusColor.copy(alpha = .18f), RoundedCornerShape(10.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(currentStatusColor))
            Spacer(Modifier.width(8.dp))
            Text("当前：", color = SecondaryText, fontSize = 12.sp)
            Text(
                queueCloudSyncStatusLabel(status),
                color = currentStatusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(18.dp))
        MenuSectionHeader("各状态的含义")
        CloudSyncMeaningRow(
            phase = QueueCloudSyncPhase.DISABLED,
            description = "网站同步已在设置中关闭；新的队列变动只保存在本机。"
        )
        HorizontalDivider(color = Separator.copy(alpha = .6f))
        CloudSyncMeaningRow(
            phase = QueueCloudSyncPhase.CONFIGURED,
            description = "同步功能已经就绪，正在等待首次上传。"
        )
        HorizontalDivider(color = Separator.copy(alpha = .6f))
        CloudSyncMeaningRow(
            phase = QueueCloudSyncPhase.SYNCING,
            description = "正在将最新队列状态上传到网站。"
        )
        HorizontalDivider(color = Separator.copy(alpha = .6f))
        CloudSyncMeaningRow(
            phase = QueueCloudSyncPhase.SYNCED,
            description = "最新队列状态已经上传到网站，网页可以显示当前内容。"
        )
        HorizontalDivider(color = Separator.copy(alpha = .6f))
        CloudSyncMeaningRow(
            phase = QueueCloudSyncPhase.WAITING_TO_RETRY,
            description = "本次上传没有完成，应用会自动重试；本机排队仍可正常使用。"
        )
        HorizontalDivider(color = Separator.copy(alpha = .6f))
        CloudSyncMeaningRow(
            phase = QueueCloudSyncPhase.NOT_CONFIGURED,
            description = "当前版本没有网站同步配置，队列状态只保存在本机。"
        )

        Spacer(Modifier.height(13.dp))
        Text(
            "网站只会收到公开展示队列所需的信息，例如登记昵称、队列位置、游玩状态和时间估算。QQ 号、手机号、性别、操作日志及玩家资料内部编号不会上传。",
            color = SecondaryText,
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(18.dp))
        SecondaryButton("关闭", onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CloudSyncMeaningRow(
    phase: QueueCloudSyncPhase,
    description: String
) {
    val statusColor = queueCloudSyncStatusColor(phase)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(statusColor)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                queueCloudSyncShortLabel(phase),
                color = PrimaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(description, color = SecondaryText, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

private fun queueCloudSyncStatusLabel(status: QueueCloudSyncStatus): String = when (status.phase) {
    QueueCloudSyncPhase.DISABLED -> "网站同步已关闭"
    QueueCloudSyncPhase.NOT_CONFIGURED -> "等待服务器配置"
    QueueCloudSyncPhase.CONFIGURED -> "已配置，等待首次同步"
    QueueCloudSyncPhase.SYNCING -> "正在同步"
    QueueCloudSyncPhase.SYNCED -> status.lastSuccessfulAtMillis?.let {
        "已同步 · ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it))}"
    } ?: "已同步"
    QueueCloudSyncPhase.WAITING_TO_RETRY -> status.retryDetail?.let {
        "连接中断，等待重试：$it"
    } ?: "连接中断，等待重试"
}

private fun queueCloudSyncShortLabel(phase: QueueCloudSyncPhase): String = when (phase) {
    QueueCloudSyncPhase.DISABLED -> "同步已关闭"
    QueueCloudSyncPhase.NOT_CONFIGURED -> "未配置"
    QueueCloudSyncPhase.CONFIGURED -> "待同步"
    QueueCloudSyncPhase.SYNCING -> "同步中"
    QueueCloudSyncPhase.SYNCED -> "已同步"
    QueueCloudSyncPhase.WAITING_TO_RETRY -> "待重试"
}

private fun queueCloudSyncStatusColor(phase: QueueCloudSyncPhase): Color = when (phase) {
    QueueCloudSyncPhase.DISABLED -> TertiaryText
    QueueCloudSyncPhase.NOT_CONFIGURED -> TertiaryText
    QueueCloudSyncPhase.CONFIGURED -> SecondaryText
    QueueCloudSyncPhase.SYNCING -> SystemBlue
    QueueCloudSyncPhase.SYNCED -> Color(0xFF34C759)
    QueueCloudSyncPhase.WAITING_TO_RETRY -> Color(0xFFFF9500)
}

@Composable
private fun AppDetailSectionTitle(title: String) {
    Text(
        title,
        color = TertiaryText,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun AppDetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TertiaryText, fontSize = 12.sp, modifier = Modifier.width(92.dp))
        Text(
            value,
            color = PrimaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StopMachineChooser(
    machineAStatus: MachineStatus,
    machineBStatus: MachineStatus,
    onDismiss: () -> Unit,
    onSelect: (MachineId) -> Unit
) {
    ModalSurface(onDismiss, width = 470.dp) {
        Text("报告机台停止使用", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "选择需要停止使用的机台。已停止的机台不能再次选择，现有登记不会被删除。",
            color = SecondaryText,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
        MachineStopChoice(MachineId.A, machineAStatus) { onSelect(MachineId.A) }
        Spacer(Modifier.height(9.dp))
        MachineStopChoice(MachineId.B, machineBStatus) { onSelect(MachineId.B) }
        Spacer(Modifier.height(13.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun MachineStopChoice(machineId: MachineId, status: MachineStatus, onClick: () -> Unit) {
    ActionRow(
        title = machineName(machineId),
        description = if (status.isOperational) {
            "选择后继续说明停止使用的原因。"
        } else {
            "已停止使用 · ${machineStopReasonLabel(status.stopReason)}"
        },
        destructive = status.isOperational,
        enabled = status.isOperational,
        onClick = onClick
    )
}

@Composable
private fun StopMachineReasonDialog(
    machineName: String,
    registrationCount: Int,
    onDismiss: () -> Unit,
    onSelect: (MachineStopReason) -> Unit
) {
    ModalSurface(onDismiss, width = 470.dp) {
        Text("选择停止使用原因", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (registrationCount > 0) {
                "选择原因后，$machineName 将立即停止使用。现有 $registrationCount 份登记、游玩位置和等待顺序会被保留；恢复正常使用后，将按原顺序继续，本轮计时从头开始。"
            } else {
                "选择原因后，$machineName 将立即停止使用。恢复正常使用前，这台机台不能接收新的登记。"
            },
            color = SecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(16.dp))
        CompactActionButton(
            MenuAction("机台未开机", "", { onSelect(MachineStopReason.NOT_POWERED_ON) }, destructive = true),
            Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(9.dp))
        CompactActionButton(
            MenuAction("机台断网", "", { onSelect(MachineStopReason.NETWORK_DISCONNECTED) }, destructive = true),
            Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(9.dp))
        CompactActionButton(
            MenuAction("其他原因", "", { onSelect(MachineStopReason.OTHER) }, destructive = true),
            Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(13.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun EditMachineChooser(
    machineA: MachineQueue,
    machineB: MachineQueue,
    machineAStatus: MachineStatus,
    machineBStatus: MachineStatus,
    onDismiss: () -> Unit,
    onSelect: (MachineId) -> Unit
) {
    ModalSurface(onDismiss, width = 470.dp) {
        Text("编辑登记", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("选择需要调整的机台。", color = SecondaryText, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        ActionRow(
            machineName(MachineId.A),
            when {
                !machineAStatus.isOperational -> "这台机台已经停止使用。"
                machineA.waiting.isEmpty() -> "当前没有可调整的等待登记。"
                else -> "当前有 ${machineA.waiting.size} 份等待登记可以调整。"
            },
            enabled = machineAStatus.isOperational && machineA.waiting.isNotEmpty()
        ) {
            onSelect(MachineId.A)
        }
        Spacer(Modifier.height(9.dp))
        ActionRow(
            machineName(MachineId.B),
            when {
                !machineBStatus.isOperational -> "这台机台已经停止使用。"
                machineB.waiting.isEmpty() -> "当前没有可调整的等待登记。"
                else -> "当前有 ${machineB.waiting.size} 份等待登记可以调整。"
            },
            enabled = machineBStatus.isOperational && machineB.waiting.isNotEmpty()
        ) {
            onSelect(MachineId.B)
        }
        Spacer(Modifier.height(13.dp))
        CancelAction(onDismiss)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReorderScreen(
    machineId: MachineId,
    initialQueue: MachineQueue,
    explicitEditMode: Boolean,
    onCancel: () -> Unit,
    onCommit: (List<Registration>) -> Unit
) {
    val registrations = remember(initialQueue) {
        mutableStateListOf<Registration>().apply { addAll(initialQueue.allRegistrations) }
    }
    val originalOrder = remember(initialQueue) { initialQueue.allRegistrations }
    val playingKeys = remember(initialQueue) { initialQueue.playing.map { it.key }.toSet() }
    val playingCount = initialQueue.playing.size
    var draggedKey by remember { mutableStateOf<Int?>(null) }
    var dragStartOrder by remember(initialQueue) { mutableStateOf<List<Registration>?>(null) }
    var pendingMovedKey by remember { mutableStateOf<Int?>(null) }
    val nowMillis = rememberCurrentTimeMillis()
    val listState = rememberLazyListState()
    var viewportBounds by remember(initialQueue) { mutableStateOf<Rect?>(null) }
    var registrationDragOffset by remember(initialQueue) { mutableStateOf(Offset.Zero) }
    var dragPointerInRoot by remember(initialQueue) { mutableStateOf<Offset?>(null) }
    var edgeScrollPerFramePx by remember(initialQueue) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val edgeZonePx = with(density) { 66.dp.toPx() }
    val maximumEdgeScrollPx = with(density) { 8.dp.toPx() }

    fun updateEdgeScroll() {
        val pointer = dragPointerInRoot
        val viewport = viewportBounds
        edgeScrollPerFramePx = when {
            pointer == null || viewport == null -> 0f
            pointer.y < viewport.top + edgeZonePx -> {
                -maximumEdgeScrollPx * (
                    (viewport.top + edgeZonePx - pointer.y) / edgeZonePx
                    ).coerceIn(0f, 1f)
            }
            pointer.y > viewport.bottom - edgeZonePx -> {
                maximumEdgeScrollPx * (
                    (pointer.y - (viewport.bottom - edgeZonePx)) / edgeZonePx
                    ).coerceIn(0f, 1f)
            }
            else -> 0f
        }
    }

    fun reorderDraggedRegistration() {
        val key = draggedKey ?: return
        val sourceIndex = registrations.indexOfFirst { it.key == key }
        if (sourceIndex < playingCount) return
        val update = calculateDragReorder(
            sourceIndex = sourceIndex,
            dragOffset = registrationDragOffset.y,
            itemSizes = List(registrations.size) { with(density) { 68.dp.toPx() } },
            spacing = with(density) { 9.dp.toPx() },
            minimumIndex = playingCount
        )
        if (update.destinationIndex != sourceIndex) {
            registrations.add(update.destinationIndex, registrations.removeAt(sourceIndex))
        }
        registrationDragOffset = registrationDragOffset.copy(y = update.remainingOffset)
    }

    LaunchedEffect(draggedKey) {
        while (draggedKey != null) {
            val requestedScroll = edgeScrollPerFramePx
            if (kotlin.math.abs(requestedScroll) > .1f) {
                val consumedScroll = listState.scrollBy(requestedScroll)
                if (kotlin.math.abs(consumedScroll) > .1f) {
                    registrationDragOffset += Offset(0f, consumedScroll)
                    delay(16L)
                    reorderDraggedRegistration()
                } else {
                    edgeScrollPerFramePx = 0f
                    delay(16L)
                }
            } else {
                delay(16L)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppBackButton(onCancel, text = "取消")
            Spacer(Modifier.weight(1f))
            Text(
                SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(nowMillis)),
                color = PrimaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(16.dp))
            if (explicitEditMode) {
                Text(
                    "完成",
                    color = SystemBlue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).clickable {
                        if (hasRegistrationOrderChanged(originalOrder, registrations)) {
                            onCommit(registrations.toList())
                        } else {
                            onCancel()
                        }
                    }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
            Text(
                if (explicitEditMode) "编辑${machineName(machineId)} 的登记" else "调整登记位置",
                color = PrimaryText,
                fontSize = 29.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (initialQueue.playing.isNotEmpty()) {
                    "游玩位置中的登记已锁定；需要调整时，请先将其撤回至等待顺序前端。"
                } else {
                    "当前仅显示等待登记；游玩位置不会参与顺序调整。"
                },
                color = SecondaryText,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(18.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).onGloballyPositioned {
                    viewportBounds = it.boundsInRoot()
                },
                verticalArrangement = Arrangement.spacedBy(9.dp),
                userScrollEnabled = draggedKey == null
            ) {
                itemsIndexed(registrations, key = { _, registration -> registration.key }) { index, registration ->
                    val locked = registration.key in playingKeys
                    val dragging = draggedKey == registration.key
                    ReorderRegistrationRow(
                        orderLabel = if (locked) "游玩" else "${index - playingCount + 1}",
                        registration = registration,
                        dragging = dragging,
                        locked = locked,
                        modifier = Modifier.zIndex(if (dragging) 1f else 0f).let {
                            if (dragging) it else it.animateItem()
                        },
                        onDragStart = { pointerInRoot ->
                            dragStartOrder = registrations.toList()
                            draggedKey = registration.key
                            registrationDragOffset = Offset.Zero
                            dragPointerInRoot = pointerInRoot
                            edgeScrollPerFramePx = 0f
                        },
                        onDrag = { dragAmount ->
                            registrationDragOffset += dragAmount
                            dragPointerInRoot = dragPointerInRoot?.plus(dragAmount)
                            reorderDraggedRegistration()
                            updateEdgeScroll()
                        },
                        onDragEnd = {
                            edgeScrollPerFramePx = 0f
                            draggedKey = null
                            dragStartOrder = null
                            registrationDragOffset = Offset.Zero
                            dragPointerInRoot = null
                            if (!explicitEditMode && hasRegistrationOrderChanged(originalOrder, registrations)) {
                                pendingMovedKey = registration.key
                            }
                        },
                        onDragCancel = {
                            val orderToRestore = dragStartOrder
                            edgeScrollPerFramePx = 0f
                            draggedKey = null
                            dragStartOrder = null
                            registrationDragOffset = Offset.Zero
                            dragPointerInRoot = null
                            if (orderToRestore != null) {
                                registrations.clear()
                                registrations.addAll(orderToRestore)
                            }
                        }
                    )
                }
            }
        }
    }

    pendingMovedKey?.let { movedKey ->
        ReorderConfirmation(
            originalQueue = initialQueue,
            proposedOrder = registrations,
            movedKey = movedKey,
            onKeepOriginal = {
                pendingMovedKey = null
                registrations.clear()
                registrations.addAll(originalOrder)
            },
            onConfirm = {
                pendingMovedKey = null
                onCommit(registrations.toList())
            }
        )
    }
}

@Composable
private fun ReorderRegistrationRow(
    orderLabel: String,
    registration: Registration,
    dragging: Boolean,
    locked: Boolean,
    modifier: Modifier = Modifier,
    isDragOverlay: Boolean = false,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val dragOverlayController = LocalGlobalDragOverlayController.current
    var dragSurfaceCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val background by animateColorAsState(
        when {
            locked -> Color(0xFFEDEDF1)
            dragging -> SoftBlue
            else -> CardBackground
        },
        tween(140),
        label = "drag color"
    )
    val elevation by animateDpAsState(if (dragging) 12.dp else 0.dp, tween(140), label = "drag elevation")
    Row(
        modifier.fillMaxWidth().height(68.dp)
            .onGloballyPositioned { rowCoordinates = it }
            .graphicsLayer {
                alpha = if (dragging && !isDragOverlay) 0f else 1f
                shadowElevation = elevation.toPx()
                shape = RoundedCornerShape(15.dp)
            }
            .clip(RoundedCornerShape(15.dp))
            .background(background)
            .border(
                1.dp,
                if (dragging) SystemBlue.copy(alpha = .35f) else Separator.copy(alpha = .85f),
                RoundedCornerShape(15.dp)
            )
            .padding(start = 17.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(orderLabel, color = TertiaryText, fontSize = 12.sp, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text(
                registration.displayId,
                color = if (locked) SecondaryText else PrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                playPreferenceLabel(registration),
                color = if (locked) TertiaryText else SecondaryText,
                fontSize = 10.sp
            )
        }
        if (locked) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Text("锁定", color = TertiaryText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        } else {
            val dragHandleModifier = if (isDragOverlay) {
                Modifier
            } else {
                Modifier
                    .onGloballyPositioned { dragSurfaceCoordinates = it }
                    .pointerInput(registration.key) {
                        detectDragGestures(
                            onDragStart = { position ->
                                val pointerInRoot =
                                    dragSurfaceCoordinates?.localToRoot(position) ?: position
                                rowCoordinates?.boundsInRoot()?.let { itemBounds ->
                                    dragOverlayController.start(pointerInRoot, itemBounds) {
                                        ReorderRegistrationRow(
                                            orderLabel = orderLabel,
                                            registration = registration,
                                            dragging = true,
                                            locked = false,
                                            isDragOverlay = true,
                                            onDragStart = {},
                                            onDrag = {},
                                            onDragEnd = {},
                                            onDragCancel = {}
                                        )
                                    }
                                }
                                onDragStart(pointerInRoot)
                            },
                            onDragCancel = {
                                dragOverlayController.clear()
                                onDragCancel()
                            },
                            onDragEnd = {
                                dragOverlayController.clear()
                                onDragEnd()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOverlayController.moveBy(dragAmount)
                                onDrag(dragAmount)
                            }
                        )
                    }
            }
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (dragging) SystemBlue.copy(alpha = .10f) else Color.Transparent)
                    .then(dragHandleModifier),
                contentAlignment = Alignment.Center
            ) {
                Text("≡", color = if (dragging) SystemBlue else TertiaryText, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun ReorderConfirmation(
    originalQueue: MachineQueue,
    proposedOrder: List<Registration>,
    movedKey: Int,
    onKeepOriginal: () -> Unit,
    onConfirm: () -> Unit
) {
    var consentConfirmed by remember { mutableStateOf(false) }
    val originalOrder = originalQueue.allRegistrations
    val oldIndex = originalOrder.indexOfFirst { it.key == movedKey }
    val newIndex = proposedOrder.indexOfFirst { it.key == movedKey }
    val delayed = delayedRegistrationsForMove(originalQueue, proposedOrder, movedKey)
    val delayedOtherPlayers = delayed.filterNot { it.key == movedKey }
    val movedForward = oldIndex >= 0 && newIndex >= 0 && newIndex < oldIndex
    val impactText = if (delayed.isEmpty()) {
        "未发现会因此延后游玩的其他登记。"
    } else {
        val names = delayed.take(3).joinToString("、") { "“${it.displayId}”" }
        val suffix = if (delayed.size > 3) "等 ${delayed.size} 份登记" else ""
        "这个变动会使 $names$suffix 延后取得游玩机会。"
    }

    ModalSurface(onKeepOriginal, width = 470.dp) {
        Text("确认调整登记顺序？", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(impactText, color = if (delayed.isEmpty()) SecondaryText else Destructive, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(PageBackground)
                .clickable { consentConfirmed = !consentConfirmed }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (consentConfirmed) SystemBlue else Color.Transparent)
                    .border(1.dp, if (consentConfirmed) SystemBlue else Separator, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (consentConfirmed) StraightCheckMark(Modifier.size(12.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (movedForward && delayedOtherPlayers.isNotEmpty()) {
                    "已经得到所有因这次调整而延后的玩家明确同意。"
                } else {
                    "这是被拖动登记的玩家本人操作，或已经得到其明确同意。"
                },
                color = PrimaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("保持原有顺序", onKeepOriginal, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            "确认调整",
            color = if (consentConfirmed) Destructive else TertiaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .background(PageBackground).clickable(enabled = consentConfirmed, onClick = onConfirm).padding(13.dp)
        )
    }
}

@Composable
private fun PositionReorderConfirmation(
    proposal: PositionReorderProposal,
    onKeepOriginal: () -> Unit,
    onConfirm: () -> Unit
) {
    var consentConfirmed by remember(proposal) { mutableStateOf(false) }
    val originalPositions = proposal.originalQueue.waitingPositions()
    val movedRegistrations = originalPositions
        .getOrNull(proposal.sourcePositionIndex)
        .orEmpty()
        .filter { it.key in proposal.movedRegistrationKeys }
    val movedForward = proposal.destinationPositionIndex < proposal.sourcePositionIndex
    val delayedRegistrations = if (movedForward) {
        originalPositions.subList(
            proposal.destinationPositionIndex,
            proposal.sourcePositionIndex
        ).flatten()
    } else {
        movedRegistrations
    }
    val sourceLabel = "${proposal.machineId.name}${proposal.sourcePositionIndex + 1}"
    val destinationLabel = "${proposal.machineId.name}${proposal.destinationPositionIndex + 1}"
    val shiftedStart = if (movedForward) {
        proposal.destinationPositionIndex + 1
    } else {
        proposal.sourcePositionIndex + 2
    }
    val shiftedEnd = if (movedForward) {
        proposal.sourcePositionIndex
    } else {
        proposal.destinationPositionIndex + 1
    }
    val shiftedRange = if (shiftedStart == shiftedEnd) {
        "位置 ${proposal.machineId.name}$shiftedStart"
    } else {
        "位置 ${proposal.machineId.name}$shiftedStart 至 ${proposal.machineId.name}$shiftedEnd"
    }
    val movedNames = movedRegistrations.joinToString("、") { "“${it.displayId}”" }
    val delayedNames = delayedRegistrations.take(3).joinToString("、") { "“${it.displayId}”" }
    val delayedSuffix = if (delayedRegistrations.size > 3) {
        "等 ${delayedRegistrations.size} 份登记"
    } else {
        ""
    }

    ModalSurface(onKeepOriginal, width = 500.dp) {
        Text("确认调整队列位置？", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "${movedNames}所在的位置将从 $sourceLabel 移至 $destinationLabel，$shiftedRange 将依次${if (movedForward) "后移" else "前移"}。",
            color = PrimaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(7.dp))
        Text(
            if (movedForward) {
                "此操作会让$delayedNames${delayedSuffix}延后取得游玩机会。"
            } else {
                "被移动位置中的登记会延后取得游玩机会，其他经过的位置会依次提前。"
            },
            color = if (movedForward) Destructive else SecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        if (proposal.relationshipChanges.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFFF4E5)).padding(13.dp)
            ) {
                Text(
                    "共同游玩关系将重新分配",
                    color = Color(0xFF9A5B00),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "系统会按照各登记当前的游玩偏好重新组成队列位置，不会修改任何人的游玩偏好。",
                    color = Color(0xFF9A5B00),
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(5.dp))
                proposal.relationshipChanges.take(3).forEach { description ->
                    Text("• $description", color = Color(0xFF7A4800), fontSize = 11.sp, lineHeight = 17.sp)
                }
                if (proposal.relationshipChanges.size > 3) {
                    Text(
                        "• 另有 ${proposal.relationshipChanges.size - 3} 个位置会随之调整。",
                        color = Color(0xFF7A4800),
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(15.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(PageBackground)
                .clickable { consentConfirmed = !consentConfirmed }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (consentConfirmed) SystemBlue else Color.Transparent)
                    .border(1.dp, if (consentConfirmed) SystemBlue else Separator, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (consentConfirmed) StraightCheckMark(Modifier.size(12.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (movedForward) {
                    "已经得到所有因这次调整而延后的玩家明确同意。"
                } else {
                    "这是被移动位置中的玩家本人操作，或已经得到其明确同意。"
                },
                color = PrimaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("保持原有顺序", onKeepOriginal, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            "确认调整位置",
            color = if (consentConfirmed) Destructive else TertiaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .background(PageBackground).clickable(enabled = consentConfirmed, onClick = onConfirm).padding(13.dp)
        )
    }
}

@Composable
private fun ModalSurface(
    onDismiss: () -> Unit,
    width: androidx.compose.ui.unit.Dp = 440.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val dialogShape = RoundedCornerShape(DialogRadius)
    var shown by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val visibilityProgress by animateFloatAsState(
        if (shown) 1f else 0f,
        tween(if (shown) 210 else 140),
        label = "弹窗显隐"
    )
    val dismissWithAnimation = {
        if (!dismissing) {
            dismissing = true
            shown = false
            coroutineScope.launch {
                delay(140L)
                onDismiss()
            }
        }
    }

    LaunchedEffect(Unit) { shown = true }

    BoxWithConstraints(
        Modifier.fillMaxSize().imePadding()
            .background(Color.Black.copy(alpha = .28f * visibilityProgress))
            .pointerInput(onDismiss) {
                detectTapGestures(onTap = { dismissWithAnimation() })
            }.padding(horizontal = 20.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = width).fillMaxWidth().heightIn(max = maxHeight)
                .graphicsLayer {
                    alpha = visibilityProgress
                    scaleX = .965f + visibilityProgress * .035f
                    scaleY = .965f + visibilityProgress * .035f
                    translationY = (1f - visibilityProgress) * 10.dp.toPx()
                }
                .shadow(8.dp, dialogShape, clip = false)
                .clip(dialogShape)
                .animateContentSize(tween(190))
                .background(CardBackground)
                .border(1.dp, Separator.copy(alpha = .48f), dialogShape)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
                }.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 22.dp)
        ) { content() }
    }
}

@Composable
private fun ActionRow(
    title: String,
    description: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    accented: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(ControlRadius)
    val visibleDescription = description?.takeIf { it.isNotBlank() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (visibleDescription == null) 52.dp else 68.dp)
            .clip(shape)
            .background(
                when {
                    !enabled -> Color(0xFFE8E8ED)
                    destructive -> Destructive.copy(alpha = .075f)
                    !accented -> PageBackground
                    else -> SoftBlue
                }
            )
            .border(
                1.dp,
                when {
                    !enabled -> Separator.copy(alpha = .65f)
                    destructive -> Destructive.copy(alpha = .18f)
                    !accented -> Separator.copy(alpha = .8f)
                    else -> SystemBlue.copy(alpha = .12f)
                },
                shape
            )
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = when {
                    !enabled -> TertiaryText
                    destructive -> Destructive
                    else -> PrimaryText
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            visibleDescription?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = SecondaryText, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
        Text("›", color = if (enabled) TertiaryText else Separator, fontSize = 19.sp)
    }
}

@Composable
private fun MenuActionButton(
    action: MenuAction,
    modifier: Modifier = Modifier,
    enabled: Boolean = action.enabled,
    accented: Boolean = true
) {
    val shape = RoundedCornerShape(ControlRadius)
    val visibleDescription = action.description.takeIf { it.isNotBlank() }
    Column(
        modifier.heightIn(min = if (visibleDescription == null) 52.dp else 82.dp).clip(shape)
            .background(
                when {
                    !enabled -> Color(0xFFE8E8ED)
                    action.destructive -> Destructive.copy(alpha = .075f)
                    !accented -> PageBackground
                    else -> SoftBlue
                }
            )
            .border(
                1.dp,
                when {
                    !enabled -> Separator.copy(alpha = .65f)
                    action.destructive -> Destructive.copy(alpha = .18f)
                    !accented -> Separator.copy(alpha = .8f)
                    else -> SystemBlue.copy(alpha = .14f)
                },
                shape
            )
            .clickable(enabled = enabled, onClick = action.onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                action.title,
                color = when {
                    !enabled -> TertiaryText
                    action.destructive -> Destructive
                    else -> PrimaryText
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Text("›", color = if (enabled) TertiaryText else Separator, fontSize = 17.sp)
        }
        visibleDescription?.let { description ->
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                color = if (enabled) SecondaryText else TertiaryText,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun CompactActionButton(
    action: MenuAction,
    modifier: Modifier = Modifier,
    enabled: Boolean = action.enabled,
    accented: Boolean = true
) {
    val shape = RoundedCornerShape(ControlRadius)
    Box(
        modifier.height(50.dp).clip(shape)
            .background(
                when {
                    !enabled -> Color(0xFFE8E8ED)
                    action.destructive -> Destructive.copy(alpha = .075f)
                    !accented -> PageBackground
                    else -> SoftBlue
                }
            )
            .border(
                1.dp,
                when {
                    !enabled -> Separator.copy(alpha = .65f)
                    action.destructive -> Destructive.copy(alpha = .18f)
                    !accented -> Separator.copy(alpha = .8f)
                    else -> SystemBlue.copy(alpha = .14f)
                },
                shape
            )
            .clickable(enabled = enabled, onClick = action.onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            action.title,
            color = when {
                !enabled -> TertiaryText
                action.destructive -> Destructive
                !accented -> PrimaryText
                else -> SystemBlue
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactActionGrid(actions: List<MenuAction>, accented: Boolean = true) {
    actions.chunked(2).forEachIndexed { index, rowActions ->
        if (index > 0) Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            rowActions.forEach { action ->
                CompactActionButton(
                    action,
                    Modifier.weight(1f),
                    accented = action.accented ?: accented
                )
            }
        }
    }
}

@Composable
private fun MenuSectionHeader(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = SecondaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(Modifier.weight(1f), color = Separator.copy(alpha = .72f))
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = SecondaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun AppBackButton(onClick: () -> Unit, text: String = "返回") {
    Row(
        Modifier.height(42.dp).clip(RoundedCornerShape(ControlRadius))
            .clickable(onClick = onClick).padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = SystemBlue,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(text, color = SystemBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NoShowChoice(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    visuallyDisabled: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(ControlRadius)
    Column(
        modifier.heightIn(min = 88.dp).clip(shape)
            .background(if (visuallyDisabled) Color(0xFFE8E8ED) else PageBackground)
            .border(1.dp, Separator.copy(alpha = .7f), shape)
            .clickable(enabled = !visuallyDisabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            title,
            color = when {
                visuallyDisabled -> TertiaryText
                destructive -> Destructive
                else -> PrimaryText
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Text(description, color = TertiaryText, fontSize = 9.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun CancelAction(onClick: () -> Unit) {
    CancelAction("取消", onClick)
}

@Composable
private fun CancelAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(ControlRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = SystemBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SmallActionButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(ControlRadius))
            .background(if (!enabled) Color(0xFFE8E8ED) else if (primary) SystemBlue else SoftBlue)
            .border(
                1.dp,
                if (!enabled) Separator.copy(alpha = .55f) else if (primary) SystemBlue else SystemBlue.copy(alpha = .12f),
                RoundedCornerShape(ControlRadius)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (primary) 16.dp else 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = when {
                !enabled -> TertiaryText
                primary -> Color.White
                else -> SystemBlue
            },
            fontSize = 12.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(ControlRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = SystemBlue,
            contentColor = Color.White,
            disabledContainerColor = Separator,
            disabledContentColor = Color.White
        )
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun DestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(ControlRadius),
        colors = ButtonDefaults.buttonColors(containerColor = Destructive, contentColor = Color.White)
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun StepperButton(text: String, onClick: () -> Unit, enabled: Boolean) {
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) SoftBlue else Color(0xFFE8E8ED))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) SystemBlue else TertiaryText, fontSize = 24.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(ControlRadius))
            .background(if (enabled) CardBackground else Color(0xFFE8E8ED))
            .border(
                1.dp,
                if (enabled) SystemBlue.copy(alpha = .28f) else Separator.copy(alpha = .65f),
                RoundedCornerShape(ControlRadius)
            )
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) SystemBlue else TertiaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StraightCheckMark(
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    Canvas(modifier) {
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * .12f, size.height * .52f),
            end = androidx.compose.ui.geometry.Offset(size.width * .42f, size.height * .80f),
            strokeWidth = 1.7.dp.toPx(),
            cap = StrokeCap.Butt
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * .42f, size.height * .80f),
            end = androidx.compose.ui.geometry.Offset(size.width * .90f, size.height * .18f),
            strokeWidth = 1.7.dp.toPx(),
            cap = StrokeCap.Butt
        )
    }
}

@Composable
private fun rememberCurrentTimeMillis(): Long {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            currentTime = System.currentTimeMillis()
        }
    }
    return currentTime
}

private fun registrationTileWidth(displayId: String): Dp {
    val visibleId = queueDisplayId(displayId)
    val visibleCharacters = visibleId
        .codePointCount(0, visibleId.length)
        .coerceIn(3, 6)
    return (66 + visibleCharacters * 8).dp
}

private fun inlineReorderRegistrationTileWidth(displayId: String): Dp {
    val visibleId = queueDisplayId(displayId)
    val visibleCharacters = visibleId
        .codePointCount(0, visibleId.length)
        .coerceIn(4, 6)
    return (86 + visibleCharacters * 8).dp
}

private fun waitingPositionWidth(registrations: List<Registration>): Dp {
    val tileWidths = registrations.map { registrationTileWidth(it.displayId) }
    val registrationWidth = tileWidths.fold(0.dp) { total, width -> total + width }
    val spacing = if (tileWidths.size > 1) 7.dp * (tileWidths.size - 1) else 0.dp
    return registrationWidth + spacing + 22.dp
}

private fun waitingPositionKey(registrations: List<Registration>): String =
    registrations.joinToString(prefix = "waiting-", separator = "-") { it.key.toString() }

internal data class DragReorderUpdate(
    val destinationIndex: Int,
    val remainingOffset: Float
)

internal fun calculateDragReorder(
    sourceIndex: Int,
    dragOffset: Float,
    itemSizes: List<Float>,
    spacing: Float,
    minimumIndex: Int = 0,
    thresholdFraction: Float = .52f
): DragReorderUpdate {
    if (
        sourceIndex !in itemSizes.indices ||
        minimumIndex !in 0..itemSizes.size ||
        sourceIndex < minimumIndex ||
        itemSizes.any { it <= 0f } ||
        spacing < 0f
    ) return DragReorderUpdate(sourceIndex, dragOffset)

    val reorderedSizes = itemSizes.toMutableList()
    var currentIndex = sourceIndex
    var remainingOffset = dragOffset
    while (true) {
        val direction = when {
            remainingOffset > 0f -> 1
            remainingOffset < 0f -> -1
            else -> break
        }
        val destinationIndex = currentIndex + direction
        if (destinationIndex !in reorderedSizes.indices || destinationIndex < minimumIndex) break
        val centerDistance =
            reorderedSizes[currentIndex] / 2f +
                reorderedSizes[destinationIndex] / 2f +
                spacing
        val layoutShift = reorderedSizes[destinationIndex] + spacing
        val reorderThreshold = maxOf(centerDistance, layoutShift) * thresholdFraction
        if (kotlin.math.abs(remainingOffset) < reorderThreshold) break

        reorderedSizes.add(destinationIndex, reorderedSizes.removeAt(currentIndex))
        currentIndex = destinationIndex
        remainingOffset -= direction * layoutShift
    }
    return DragReorderUpdate(currentIndex, remainingOffset)
}

internal fun queueDisplayId(displayId: String): String {
    val characterCount = displayId.codePointCount(0, displayId.length)
    if (characterCount <= 6) return displayId
    val truncationEnd = displayId.offsetByCodePoints(0, 5)
    return displayId.substring(0, truncationEnd) + "…"
}

internal fun limitCodePointLength(value: String, maxCodePoints: Int): String {
    if (maxCodePoints <= 0) return ""
    val characterCount = value.codePointCount(0, value.length)
    if (characterCount <= maxCodePoints) return value
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints))
}

internal fun hasRegistrationOrderChanged(
    originalOrder: List<Registration>,
    proposedOrder: List<Registration>
): Boolean = originalOrder.map { it.key } != proposedOrder.map { it.key }

internal fun delayedRegistrationsForMove(
    originalQueue: MachineQueue,
    proposedOrder: List<Registration>,
    movedKey: Int
): List<Registration> {
    val originalOrder = originalQueue.allRegistrations
    val oldIndex = originalOrder.indexOfFirst { it.key == movedKey }
    val newIndex = proposedOrder.indexOfFirst { it.key == movedKey }
    if (oldIndex < 0 || newIndex < 0 || oldIndex == newIndex) return emptyList()

    fun positionIndexByKey(queue: MachineQueue): Map<Int, Int> {
        val positions = buildList {
            if (queue.playing.isNotEmpty()) add(queue.playing)
            addAll(queue.waitingPositions())
        }
        return buildMap {
            positions.forEachIndexed { positionIndex, registrations ->
                registrations.forEach { put(it.key, positionIndex) }
            }
        }
    }

    val originalPositionByKey = positionIndexByKey(originalQueue)
    val proposedPositionByKey = positionIndexByKey(originalQueue.replaceOrder(proposedOrder))
    return originalOrder.filter { registration ->
        val originalPosition = originalPositionByKey[registration.key] ?: return@filter false
        val proposedPosition = proposedPositionByKey[registration.key] ?: return@filter false
        proposedPosition > originalPosition
    }
}

internal fun estimatedMinutesUntilPlaying(
    queue: MachineQueue,
    targetRegistrationKeys: Set<Int>,
    nowMillis: Long
): Long? {
    if (
        targetRegistrationKeys.isEmpty() ||
        queue.allRegistrations.none { it.key in targetRegistrationKeys }
    ) return null
    if (queue.playing.any { it.key in targetRegistrationKeys }) return 0L

    var simulatedQueue = queue
    var waitMillis = 0L
    if (simulatedQueue.playing.isNotEmpty()) {
        val elapsedMillis = simulatedQueue.playingStartedAtMillis
            ?.let { (nowMillis - it).coerceAtLeast(0L) }
            ?: 0L
        waitMillis += (roundDurationMillis(simulatedQueue.playing) - elapsedMillis).coerceAtLeast(0L)
        simulatedQueue = simulatedQueue.finishRound(nowMillis + waitMillis)
    } else {
        simulatedQueue = simulatedQueue.enterPlayingPosition()
    }

    repeat(queue.registrationCount + 3) {
        if (simulatedQueue.playing.any { it.key in targetRegistrationKeys }) {
            return (waitMillis + 59_999L) / 60_000L
        }
        if (simulatedQueue.playing.isEmpty()) return null
        waitMillis += roundDurationMillis(simulatedQueue.playing)
        simulatedQueue = simulatedQueue.finishRound(nowMillis + waitMillis)
    }
    return null
}

private fun nextPlayingChangeMessage(preview: NextPlayingPositionPreview?): String? {
    if (preview?.changedByAbsence != true) return null

    fun names(registrations: List<Registration>): String =
        registrations.joinToString("和") { "“${it.displayId}”" }

    val temporarilyAway = preview.unavailableRegistrations.filter {
        it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
    }
    val deferred = preview.unavailableRegistrations.filter {
        it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
    }
    val temporaryAwayWillExpire = temporarilyAway.any {
        it.temporaryAwaySkippedTurns >= 3
    }
    val reasons = buildList {
        if (temporarilyAway.isNotEmpty()) {
            add("${names(temporarilyAway)}${if (temporarilyAway.size > 1) "均" else ""}处于暂时离开状态")
        }
        if (deferred.isNotEmpty()) {
            add("${names(deferred)}${if (deferred.size > 1) "均" else ""}已暂缓一轮")
        }
    }.joinToString("，且")
    val nextRound = when (preview.nextRegistrations.size) {
        0 -> "目前没有仍可上机的登记，游玩位置将保持空缺。"
        1 -> "本轮将改由${names(preview.nextRegistrations)}单人游玩。"
        else -> "本轮将改由${names(preview.nextRegistrations)}共同游玩。"
    }
    val statusResult = buildList {
        if (temporaryAwayWillExpire) {
            add("已轮空 3 次的暂时离开登记本次再轮到时会退出排队")
        } else if (temporarilyAway.isNotEmpty()) {
            add("暂时离开状态会继续保留")
        }
        if (deferred.isNotEmpty()) add("暂缓会在跳过本次后自动解除")
    }.joinToString("；")

    return "由于$reasons，系统会跳过相关登记，并按照其余在场登记的游玩偏好重新组合。$nextRound$statusResult。"
}

internal fun estimatedWaitForNewOpenRegistration(queue: MachineQueue, nowMillis: Long): Long? {
    if (queue.registrationCount >= 20) return null
    var previewKey = 1
    while (queue.allRegistrations.any { it.key == previewKey }) previewKey++
    var previewId = "预计新增登记"
    while (queue.containsId(previewId)) previewId += "_"
    val previewRegistration = Registration(
        key = previewKey,
        displayId = previewId,
        preference = PlayPreference.OPEN_TO_JOIN,
        createdAtMillis = nowMillis
    )
    val previewQueue = queue.join(previewRegistration)
    return estimatedMinutesUntilPlaying(previewQueue, setOf(previewKey), nowMillis)
}

private fun roundDurationMillis(registrations: List<Registration>): Long =
    if (registrations.size <= 1) SOLO_ROUND_DURATION_MILLIS else SHARED_ROUND_DURATION_MILLIS

private fun formatJoinWaitEstimate(minutes: Long?): String = when {
    minutes == null -> "暂时无法估算"
    minutes <= 0L -> "现在可以游玩"
    else -> "约 $minutes 分钟"
}

private fun formatPositionWaitEstimate(minutes: Long?): String = when {
    minutes == null -> "暂时无法估算"
    minutes <= 0L -> "预计现在可以游玩"
    else -> "约 $minutes 分钟后可以游玩"
}

private fun positionWaitEstimateLabel(
    registrations: List<Registration>,
    minutes: Long?
): String = if (
    registrations.any { it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY }
) {
    "暂时离开，无法估算"
} else {
    formatPositionWaitEstimate(minutes)
}

private fun randomChinesePlayerId(): String {
    val descriptions = listOf("安静", "发光", "迷路", "晚睡", "路过", "幸运", "认真", "悠闲")
    val things = listOf("海盐", "星星", "团子", "企鹅", "月亮", "云朵", "汽水", "橘子", "猫咪", "音符")
    return "${descriptions.random(Random.Default)}的${things.random(Random.Default)}"
}

private fun createPositionReorderProposal(
    machineId: MachineId,
    queue: MachineQueue,
    sourceIndex: Int,
    destinationIndex: Int
): PositionReorderProposal? {
    val currentPositions = queue.waitingPositions()
    if (
        sourceIndex !in currentPositions.indices ||
        destinationIndex !in currentPositions.indices ||
        sourceIndex == destinationIndex
    ) return null

    val expectedPositions = currentPositions.toMutableList().apply {
        val movedPosition = removeAt(sourceIndex)
        add(destinationIndex, movedPosition)
    }
    val proposedQueue = queue.moveWaitingPosition(sourceIndex, destinationIndex)
    val actualPositions = groupIntoPositions(proposedQueue.waiting)
    return PositionReorderProposal(
        machineId = machineId,
        originalQueue = queue,
        proposedOrder = proposedQueue.allRegistrations,
        sourcePositionIndex = sourceIndex,
        destinationPositionIndex = destinationIndex,
        movedRegistrationKeys = currentPositions[sourceIndex].map { it.key }.toSet(),
        relationshipChanges = positionRelationshipDescriptions(
            machineId = machineId,
            expectedPositions = expectedPositions,
            actualPositions = actualPositions
        )
    )
}

private fun positionRelationshipDescriptions(
    machineId: MachineId,
    expectedPositions: List<List<Registration>>,
    actualPositions: List<List<Registration>>
): List<String> {
    val expectedKeys = expectedPositions.map { group -> group.map { it.key } }
    val actualKeys = actualPositions.map { group -> group.map { it.key } }
    val descriptions = mutableListOf<String>()
    val count = maxOf(expectedKeys.size, actualKeys.size)

    repeat(count) { index ->
        if (expectedKeys.getOrNull(index) == actualKeys.getOrNull(index)) return@repeat
        val actualGroup = actualPositions.getOrNull(index)
        val positionName = "队列位置 ${machineId.name}${index + 1}"
        descriptions += when {
            actualGroup == null -> "$positionName 将不再作为单独的位置显示。"
            actualGroup.size == 1 -> "$positionName 将由“${actualGroup.first().displayId}”单独游玩。"
            else -> {
                val names = actualGroup.joinToString("和") { "“${it.displayId}”" }
                "$positionName 将由${names}共同游玩。"
            }
        }
    }
    return descriptions
}

private fun machineName(machineId: MachineId): String =
    if (machineId == MachineId.A) "左侧机台 A" else "右侧机台 B"

private fun playingPositionName(machineId: MachineId): String = "游玩位置 ${machineId.name}"

private fun waitingFrontPositionName(machineId: MachineId): String = "队列位置 ${machineId.name}1"

private fun machineStopReasonLabel(reason: MachineStopReason?): String = when (reason) {
    MachineStopReason.NOT_POWERED_ON -> "机台未开机"
    MachineStopReason.NETWORK_DISCONNECTED -> "机台断网"
    MachineStopReason.OTHER -> "其他原因"
    null -> "原因未记录"
}

private fun otherMachine(machineId: MachineId): MachineId =
    if (machineId == MachineId.A) MachineId.B else MachineId.A

private fun machineTransferUnavailableReason(
    machineName: String,
    status: MachineStatus,
    queue: MachineQueue,
    incomingRegistrationCount: Int
): String? = when {
    !status.isOperational -> "$machineName 已停止使用，暂时不能转入。"
    incomingRegistrationCount <= 0 -> "当前没有可以转移的登记。"
    queue.registrationCount + incomingRegistrationCount > 20 ->
        "$machineName 剩余容量不足，无法接收这些登记。"
    else -> null
}

private fun playPreferenceLabel(registration: Registration): String = when {
    registration.fixedPartnerKey != null -> "与朋友共同游玩"
    else -> playPreferenceLabel(registration.preference)
}

private fun registrationAbsenceStatusLabel(
    registration: Registration,
    includeSkippedTurns: Boolean
): String? = when (registration.absenceStatus) {
    QueueAbsenceStatus.NONE -> null
    QueueAbsenceStatus.DEFER_ONE_ROUND -> "暂缓一轮"
    QueueAbsenceStatus.TEMPORARILY_AWAY -> buildString {
        append("暂时离开")
        if (includeSkippedTurns && registration.temporaryAwaySkippedTurns > 0) {
            append(" · 已轮空 ${registration.temporaryAwaySkippedTurns} 次")
        }
    }
}

private fun unavailableNoShowExplanation(registrations: List<Registration>): String {
    val hasTemporaryLeave = registrations.any {
        it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
    }
    val hasOneRoundDeferral = registrations.any {
        it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
    }
    val subject = if (registrations.size > 1) "这组玩家" else "这名玩家"
    return when {
        hasTemporaryLeave && hasOneRoundDeferral ->
            "$subject 包含暂缓或暂时离开的登记，本次不会被安排上机，不能标记为未到场。"
        hasTemporaryLeave ->
            "$subject 处于暂时离开状态，本次不会被安排上机，不能标记为未到场。"
        else ->
            "$subject 已暂缓一轮，本次机会会被跳过，不能标记为未到场。"
    }
}

private fun playPreferenceLabel(preference: PlayPreference): String = when (preference) {
    PlayPreference.SOLO -> "单人游玩"
    PlayPreference.OPEN_TO_JOIN -> "允许他人加入"
}

private fun profilePreferenceLabel(preference: ProfilePlayPreference): String = when (preference) {
    ProfilePlayPreference.SOLO -> "单人游玩"
    ProfilePlayPreference.OPEN_TO_JOIN -> "允许他人加入"
    ProfilePlayPreference.ASK_EVERY_TIME -> "每次询问"
}

private fun String.takeFirstCodePoint(): String {
    if (isEmpty()) return "?"
    return substring(0, offsetByCodePoints(0, 1))
}

private fun formatRegistrationTime(timeMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val difference = (nowMillis - timeMillis).coerceAtLeast(0L)
    if (difference < 60_000L) return "刚刚"
    if (difference <= 30 * 60_000L) return "${difference / 60_000L} 分钟前"

    val pattern = if (difference < 24 * 60 * 60_000L) "HH:mm" else "M 月 d 日 HH:mm"
    return SimpleDateFormat(pattern, Locale.CHINA).format(Date(timeMillis))
}

@Preview(widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
private fun RegistrationAppPreview() {
    MaimaiQueueTheme(dynamicColor = false) { RegistrationApp() }
}
