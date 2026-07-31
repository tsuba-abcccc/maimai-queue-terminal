package com.abcccc.maimaiqueue

import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.os.BatteryManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
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
import com.abcccc.maimaiqueue.ui.theme.MaimaiQueueTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
internal fun RegistrationApp() {
    val context = LocalContext.current
    val hostActivity = context as? MainActivity
    val foregroundRefreshGeneration = hostActivity?.foregroundRefreshGeneration ?: 0L
    val coroutineScope = rememberCoroutineScope()
    val cloudSyncAvailable = BuildConfig.CLOUD_SYNC_AVAILABLE
    val queueSoundPlayer = remember { QueueSoundPlayer() }
    DisposableEffect(queueSoundPlayer) {
        onDispose(queueSoundPlayer::close)
    }
    val playerProfileRepository = remember(context) { LocalPlayerProfileRepository(context) }
    val playerProfilePersistence = remember(playerProfileRepository) {
        PlayerProfilePersistenceCoordinator(playerProfileRepository)
    }
    val mobileRegistrationReceiptRepository = remember(context) {
        LocalMobileRegistrationCommandReceiptRepository(context)
    }
    val auditLogRepository = remember(context) { LocalAuditLogRepository(context) }
    val queueStateRepository = remember(context) { LocalQueueStateRepository(context) }
    val queueRuleSettingsRepository = remember(context) {
        LocalQueueRuleSettingsRepository(
            context = context,
            defaultQueueSyncEndpoint = BuildConfig.QUEUE_SYNC_URL,
            defaultQueueSyncToken = BuildConfig.QUEUE_SYNC_TOKEN
        )
    }
    val initialQueueRuleSettings = remember(queueRuleSettingsRepository) {
        normalizeQueueRuleSettingsForRuntime(
            settings = queueRuleSettingsRepository.getSettings(),
            cloudSyncAvailable = cloudSyncAvailable
        )
    }
    val initialHandledClosingOccurrenceId = remember(queueRuleSettingsRepository) {
        queueRuleSettingsRepository.getLastHandledClosingOccurrenceId()
    }
    val queueStatePublisher = remember(context) {
        HttpQueueStatePublisher(
            context = context,
            endpoint = initialQueueRuleSettings.queueSyncEndpoint,
            token = initialQueueRuleSettings.queueSyncToken
        )
    }
    val queueCommandClient = remember(context) {
        HttpQueueCommandClient(
            context = context,
            queueStatusEndpoint = initialQueueRuleSettings.queueSyncEndpoint,
            token = initialQueueRuleSettings.queueSyncToken
        )
    }
    var cloudSyncStatus by remember(
        queueStatePublisher.isConfigured,
        initialQueueRuleSettings.websiteSyncEnabled
    ) {
        mutableStateOf(
            QueueCloudSyncStatus(
                phase = when {
                    !cloudSyncAvailable -> QueueCloudSyncPhase.NOT_CONFIGURED
                    !initialQueueRuleSettings.websiteSyncEnabled -> QueueCloudSyncPhase.DISABLED
                    queueStatePublisher.isConfigured -> QueueCloudSyncPhase.CONFIGURED
                    else -> QueueCloudSyncPhase.NOT_CONFIGURED
                },
                syncMode = initialQueueRuleSettings.syncMode
            )
        )
    }
    var privateSyncFailureDetail by remember { mutableStateOf<String?>(null) }
    var privateSyncRetryStartedAtMillis by remember { mutableStateOf<Long?>(null) }
    var privateSyncLastErrorAtMillis by remember { mutableStateOf<Long?>(null) }
    val cloudSyncController = remember(queueStatePublisher, coroutineScope) {
        QueueCloudSyncController(
            scope = coroutineScope,
            publisher = queueStatePublisher,
            initiallyEnabled = cloudSyncAvailable && initialQueueRuleSettings.websiteSyncEnabled,
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
    var joinClosingWarningRequest by remember {
        mutableStateOf<JoinClosingWarningRequest?>(null)
    }
    var closingWarningAcknowledgedMachineIds by remember {
        mutableStateOf<Set<MachineId>>(emptySet())
    }
    var nextKey by remember { mutableIntStateOf(1) }
    var queueId by remember { mutableStateOf(newQueueId()) }
    val queueRevision = remember { AtomicLong(0L) }
    var playerProfiles by remember { mutableStateOf<List<PlayerProfile>>(emptyList()) }
    var playerProfilesLoaded by remember { mutableStateOf(false) }
    var appliedMobileRegistrationCommandIds by remember {
        mutableStateOf<Set<String>>(emptySet())
    }
    var mobileRegistrationReceiptsLoaded by remember { mutableStateOf(false) }
    var auditLogs by remember { mutableStateOf<List<AuditLogEntry>>(emptyList()) }
    var queueRuleSettings by remember { mutableStateOf(initialQueueRuleSettings) }
    fun configuredMachineName(machineId: MachineId): String = machineName(
        machineId = machineId,
        remark = if (machineId == MachineId.A) {
            queueRuleSettings.machineARemark
        } else {
            queueRuleSettings.machineBRemark
        }
    )
    var lastHandledClosingOccurrenceId by remember {
        mutableStateOf(initialHandledClosingOccurrenceId)
    }
    var playerProfileSearch by remember { mutableStateOf("") }
    var playerProfileSort by remember { mutableStateOf(ProfileSortMode.RECOMMENDED) }
    var selectedPlayerProfileId by remember { mutableStateOf<String?>(null) }
    var editingPlayerProfileId by remember { mutableStateOf<String?>(null) }
    var profileNicknameDraft by remember { mutableStateOf("") }
    var profileGenderDraft by remember { mutableStateOf(PlayerGender.UNDISCLOSED) }
    var profilePreferenceDraft by remember { mutableStateOf(ProfilePlayPreference.ASK_EVERY_TIME) }
    var profileQqDraft by remember { mutableStateOf("") }
    var profileQqVisibilityDraft by remember {
        mutableStateOf(QqVisibility.TERMINAL_ONLY)
    }
    var profileNotificationDraft by remember {
        mutableStateOf(QueueNotificationPreferences())
    }
    var botQqNumber by remember { mutableStateOf<String?>(null) }
    var botFriendPromptQq by remember { mutableStateOf<String?>(null) }
    var mobileRegistrationSession by remember {
        mutableStateOf<MobileRegistrationSession?>(null)
    }
    var mobileRegistrationLoading by remember { mutableStateOf(false) }
    var mobileRegistrationFailureDetail by remember { mutableStateOf<String?>(null) }
    var profileJoinPreference by remember { mutableStateOf<PlayPreference?>(null) }
    var rememberProfileJoinPreference by remember { mutableStateOf(false) }
    var playerProfileContext by remember { mutableStateOf(PlayerProfileContext.JOIN_QUEUE) }
    var playerProfileEditorReturnScreen by remember { mutableStateOf(Screen.PLAYER_LIBRARY) }
    var claimPreferenceMismatchProfileId by remember { mutableStateOf<String?>(null) }
    var playerProfileWriteInProgress by remember { mutableStateOf(false) }
    var playerProfileWriteFailureDetail by remember { mutableStateOf<String?>(null) }
    var cloudProfileRestoreFailureDetail by remember { mutableStateOf<String?>(null) }
    var cloudProfileRestoreFailureAtMillis by remember { mutableStateOf<Long?>(null) }
    var initialPlayerProfileDraft by remember { mutableStateOf<PlayerProfileDraftSnapshot?>(null) }
    var discardPlayerProfileDraftConfirmationVisible by remember { mutableStateOf(false) }
    val playerProfileListState = rememberLazyListState()

    var selectedRegistration by remember { mutableStateOf<SelectedRegistration?>(null) }
    var incompleteCheckInProfileId by remember { mutableStateOf<String?>(null) }
    var moveIntoPlayingTarget by remember { mutableStateOf<SelectedRegistration?>(null) }
    var registrationActionMode by remember { mutableStateOf(RegistrationActionMode.ACTIONS) }
    var renameDraft by remember { mutableStateOf("") }
    var claimTarget by remember { mutableStateOf<SelectedRegistration?>(null) }
    var finishConfirmation by remember { mutableStateOf<MachineId?>(null) }
    var enterPlayingConfirmation by remember { mutableStateOf<MachineId?>(null) }
    var moreMenuVisible by remember { mutableStateOf(false) }
    var appDetailsVisible by remember { mutableStateOf(false) }
    var versionHistoryVisible by remember { mutableStateOf(false) }
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
    var homeSidePanelFeedback by remember { mutableStateOf<HomeSidePanelFeedback?>(null) }
    var nextHomeSidePanelFeedbackId by remember { mutableLongStateOf(1L) }
    var pendingQueueRestore by remember { mutableStateOf<PersistedQueueState?>(null) }
    var queuePersistenceReady by remember { mutableStateOf(false) }
    var inactivityWarningSeconds by remember { mutableStateOf<Int?>(null) }
    var homeQueueScrollResetToken by remember { mutableIntStateOf(0) }
    var newRegistrationHighlight by remember { mutableStateOf<NewRegistrationHighlight?>(null) }
    var pendingNewRegistrationHomeRequests by remember {
        mutableStateOf<List<NewRegistrationHomeRequest>>(emptyList())
    }
    val nowMillis = rememberCurrentTimeMillis()
    val businessHoursStatus = evaluateBusinessHours(queueRuleSettings.businessHours, nowMillis)
    val closingOccurrencePending = hasUnhandledClosingOccurrence(
        businessHoursStatus,
        lastHandledClosingOccurrenceId
    )
    val activeClosingGracePeriod = isActiveClosingGracePeriod(
        businessHoursStatus,
        lastHandledClosingOccurrenceId
    )
    val acceptingNewRegistrations = registrationOpen && !closingOccurrencePending

    fun showHomeOperationFeedback(
        title: String,
        detail: String,
        contextLabel: String? = null,
        tone: HomeSidePanelFeedbackTone = HomeSidePanelFeedbackTone.SUCCESS
    ) {
        if (queueUndoAction != null && tone != HomeSidePanelFeedbackTone.WARNING) return
        if (tone == HomeSidePanelFeedbackTone.WARNING) queueUndoAction = null
        newRegistrationHighlight = null
        homeSidePanelFeedback = HomeSidePanelFeedback(
            id = nextHomeSidePanelFeedbackId++,
            title = title,
            detail = detail,
            contextLabel = contextLabel,
            tone = tone
        )
    }

    fun dismissHomeSidePanelContent() {
        newRegistrationHighlight = null
        homeSidePanelFeedback = null
        queueUndoAction = null
    }

    fun showNewRegistrationFeedback(machineId: MachineId, registrationKey: Int) {
        homeSidePanelFeedback = null
        queueUndoAction = null
        newRegistrationHighlight = NewRegistrationHighlight(
            machineId = machineId,
            registrationKey = registrationKey,
            requestId = "local-${java.util.UUID.randomUUID()}"
        )
    }

    LaunchedEffect(playerProfileRepository) {
        playerProfiles = playerProfileRepository.getProfiles()
        playerProfilesLoaded = true
    }

    LaunchedEffect(mobileRegistrationReceiptRepository) {
        appliedMobileRegistrationCommandIds =
            mobileRegistrationReceiptRepository.getAppliedCommandIds()
        mobileRegistrationReceiptsLoaded = true
    }

    LaunchedEffect(auditLogRepository) {
        auditLogs = auditLogRepository.getLogs()
    }

    LaunchedEffect(queueStateRepository) {
        val savedState = queueStateRepository.getState()
        if (savedState != null) {
            queueId = savedState.queueId
            queueRevision.set(savedState.revision)
            nextKey = savedState.safeNextRegistrationKey
        }
        pendingQueueRestore = savedState?.takeIf(PersistedQueueState::hasMeaningfulState)
        queuePersistenceReady = true
    }

    LaunchedEffect(
        queuePersistenceReady,
        playerProfilesLoaded,
        pendingQueueRestore,
        machineA,
        machineB,
        machineAStatus,
        machineBStatus,
        registrationOpen,
        nextKey,
        queueId,
        auditLogs,
        playerProfiles,
        queueRuleSettings.websiteSyncEnabled,
        queueRuleSettings.oneBotSyncEnabled,
        queueRuleSettings.allowOnlineRegistration,
        queueRuleSettings.allowDeferOneRound,
        queueRuleSettings.allowTemporaryLeave,
        queueRuleSettings.syncMode,
        queueRuleSettings.machineARemark,
        queueRuleSettings.machineBRemark,
        businessHoursStatus,
        activeClosingGracePeriod
    ) {
        if (!queuePersistenceReady || !playerProfilesLoaded || pendingQueueRestore != null) {
            return@LaunchedEffect
        }
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
        var retryDelayMillis = 250L
        while (true) {
            when (withContext(NonCancellable) { queueStateRepository.saveState(snapshot) }) {
                QueueStateSaveResult.SAVED -> break
                QueueStateSaveResult.SUPERSEDED -> return@LaunchedEffect
                QueueStateSaveResult.FAILED -> {
                    delay(retryDelayMillis)
                    retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(5_000L)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        cloudSyncController.submit(
            state = snapshot,
            auditLogs = auditLogs,
            displaySettings = QueuePublicDisplaySettings(
                machineARemark = queueRuleSettings.machineARemark,
                machineBRemark = queueRuleSettings.machineBRemark,
                websiteRemoteEnabled = queueRuleSettings.websiteSyncEnabled,
                oneBotSyncEnabled = queueRuleSettings.websiteSyncEnabled &&
                    queueRuleSettings.oneBotSyncEnabled,
                syncMode = queueRuleSettings.syncMode,
                allowDeferOneRound = queueRuleSettings.allowDeferOneRound,
                allowTemporaryLeave = queueRuleSettings.allowTemporaryLeave,
                allowOnlineRegistration = queueRuleSettings.allowOnlineRegistration,
                businessHours = QueuePublicBusinessHours(
                    enabled = businessHoursStatus.enabled,
                    outsideBusinessHours = businessHoursStatus.outsideBusinessHours,
                    closingSoon = businessHoursStatus.closingSoon,
                    closingGracePeriod = activeClosingGracePeriod,
                    closesAtMillis = businessHoursStatus.activeClosingAtMillis,
                    registrationClosesAtMillis =
                        businessHoursStatus.registrationClosesAtMillis
                            ?.takeIf { activeClosingGracePeriod }
                )
            ),
            playerProfiles = playerProfiles
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

    fun updateQueueRuleSettings(requestedSettings: QueueRuleSettings) {
        val previousSettings = queueRuleSettings
        val requestedConnectionChanged =
            requestedSettings.queueSyncEndpoint != previousSettings.queueSyncEndpoint ||
                requestedSettings.queueSyncToken != previousSettings.queueSyncToken
        val guardedSettings = if (
            cloudSyncAvailable &&
            previousSettings.websiteSyncEnabled &&
            requestedSettings.websiteSyncEnabled &&
            requestedConnectionChanged
        ) {
            requestedSettings.copy(
                queueSyncEndpoint = previousSettings.queueSyncEndpoint,
                queueSyncToken = previousSettings.queueSyncToken
            )
        } else {
            requestedSettings
        }
        val settings = normalizeQueueRuleSettingsForRuntime(
            settings = guardedSettings,
            cloudSyncAvailable = cloudSyncAvailable
        )
        if (settings == queueRuleSettings) return
        val connectionChanged = settings.queueSyncEndpoint != previousSettings.queueSyncEndpoint ||
            settings.queueSyncToken != previousSettings.queueSyncToken
        if (connectionChanged) {
            queueStatePublisher.updateConfiguration(
                endpoint = settings.queueSyncEndpoint,
                token = settings.queueSyncToken
            )
            queueCommandClient.updateConfiguration(
                queueStatusEndpoint = settings.queueSyncEndpoint,
                token = settings.queueSyncToken
            )
            privateSyncFailureDetail = null
            privateSyncRetryStartedAtMillis = null
            privateSyncLastErrorAtMillis = null
            cloudProfileRestoreFailureDetail = null
            cloudProfileRestoreFailureAtMillis = null
            botQqNumber = null
            mobileRegistrationSession = null
            mobileRegistrationFailureDetail = null
        }
        if (previousSettings.businessHours != settings.businessHours) {
            val changedScheduleStatus = evaluateBusinessHours(
                settings.businessHours,
                System.currentTimeMillis()
            )
            changedScheduleStatus.mostRecentClosingOccurrenceId
                ?.takeIf {
                    changedScheduleStatus.outsideBusinessHours &&
                        it != lastHandledClosingOccurrenceId
                }
                ?.let { occurrenceId ->
                    lastHandledClosingOccurrenceId = occurrenceId
                    queueRuleSettingsRepository.markClosingOccurrenceHandled(occurrenceId)
                }
        }
        queueRuleSettings = settings
        queueRuleSettingsRepository.saveSettings(settings)
        if (previousSettings.websiteSyncEnabled != settings.websiteSyncEnabled) {
            cloudSyncController.setEnabled(cloudSyncAvailable && settings.websiteSyncEnabled)
            if (
                cloudSyncAvailable &&
                previousSettings.websiteSyncEnabled &&
                !settings.websiteSyncEnabled &&
                queueStatePublisher.isConfigured
            ) {
                val closingStatus = evaluateBusinessHours(
                    settings.businessHours,
                    System.currentTimeMillis()
                )
                val activeClosingGrace = isActiveClosingGracePeriod(
                    closingStatus,
                    lastHandledClosingOccurrenceId
                )
                val finalSnapshot = PersistedQueueState(
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
                coroutineScope.launch {
                    queueStatePublisher.publish(
                        state = finalSnapshot,
                        auditLogs = auditLogs,
                        displaySettings = QueuePublicDisplaySettings(
                            machineARemark = settings.machineARemark,
                            machineBRemark = settings.machineBRemark,
                            websiteRemoteEnabled = false,
                            oneBotSyncEnabled = false,
                            syncMode = settings.syncMode,
                            allowDeferOneRound = settings.allowDeferOneRound,
                            allowTemporaryLeave = settings.allowTemporaryLeave,
                            allowOnlineRegistration = settings.allowOnlineRegistration,
                            businessHours = QueuePublicBusinessHours(
                                enabled = closingStatus.enabled,
                                outsideBusinessHours = closingStatus.outsideBusinessHours,
                                closingSoon = closingStatus.closingSoon,
                                closingGracePeriod = activeClosingGrace,
                                closesAtMillis = closingStatus.activeClosingAtMillis,
                                registrationClosesAtMillis =
                                    closingStatus.registrationClosesAtMillis
                                        ?.takeIf { activeClosingGrace }
                            )
                        ),
                        playerProfiles = playerProfiles
                    )
                }
            }
        }
        val changeDescriptions = buildList {
            if (previousSettings.allowDeferOneRound != settings.allowDeferOneRound) {
                add("暂缓一轮：${if (settings.allowDeferOneRound) "允许" else "不允许"}")
            }
            if (previousSettings.allowTemporaryLeave != settings.allowTemporaryLeave) {
                add("暂时离开：${if (settings.allowTemporaryLeave) "允许" else "不允许"}")
            }
            if (cloudSyncAvailable && previousSettings.websiteSyncEnabled != settings.websiteSyncEnabled) {
                add("网站同步：${if (settings.websiteSyncEnabled) "已开启" else "已关闭"}")
            }
            if (
                cloudSyncAvailable &&
                previousSettings.allowOnlineRegistration != settings.allowOnlineRegistration
            ) {
                add(
                    "线上登记：${if (settings.allowOnlineRegistration) "允许" else "不允许"}"
                )
            }
            if (cloudSyncAvailable && previousSettings.oneBotSyncEnabled != settings.oneBotSyncEnabled) {
                add("QQ Bot 联动：${if (settings.oneBotSyncEnabled) "已开启" else "已关闭"}")
            }
            if (cloudSyncAvailable && previousSettings.syncMode != settings.syncMode) {
                add("同步方式：${queueSyncModeLabel(settings.syncMode)}")
            }
            if (cloudSyncAvailable && connectionChanged) {
                add("网站连接配置已更新")
            }
            if (previousSettings.businessHours != settings.businessHours) {
                add(
                    "营业时间：${if (settings.businessHours.enabled) "已开启" else "已关闭"}"
                )
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
        affectedRegistrationKeysOverride: Collection<Int> = emptyList(),
        source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL
    ): AuditLogEntry? {
        val entry = createQueueAuditLog(
            category = auditCategoryFor(machineId),
            machineLabel = configuredMachineName(machineId),
            before = beforeQueue,
            after = afterQueue,
            titleOverride = titleOverride,
            publicEventTypeOverride = publicEventTypeOverride,
            affectedRegistrationKeysOverride = affectedRegistrationKeysOverride,
            source = source
        ) ?: return null
        appendAuditLog(entry)
        return entry
    }

    fun removedPendingCheckInKeys(
        beforeQueue: MachineQueue,
        afterQueue: MachineQueue
    ): Set<Int> {
        val remainingKeys = afterQueue.allRegistrations.mapTo(mutableSetOf()) { it.key }
        return beforeQueue.allRegistrations.asSequence()
            .filter { it.requiresOnSiteCheckIn && it.key !in remainingKeys }
            .mapTo(mutableSetOf()) { it.key }
    }

    fun updateQueue(
        machineId: MachineId,
        soundCue: QueueSoundCue? = null,
        publicEventTypeOverride: PublicQueueEventType? = null,
        affectedRegistrationKeysOverride: Collection<Int> = emptyList(),
        classifyMissedOnlineRegistrations: Boolean = false,
        source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL,
        surfaceHomeFeedback: Boolean = false,
        homeFeedbackTone: HomeSidePanelFeedbackTone = HomeSidePanelFeedbackTone.SUCCESS,
        homeFeedbackTitle: String? = null,
        transform: (MachineQueue) -> MachineQueue
    ): Boolean {
        val beforeQueue = queueFor(machineId)
        val afterQueue = transform(beforeQueue)
        if (afterQueue == beforeQueue) return false
        val missedOnlineRegistrationKeys = if (classifyMissedOnlineRegistrations) {
            removedPendingCheckInKeys(beforeQueue, afterQueue)
        } else {
            emptySet()
        }
        queueUndoAction = null
        homeSidePanelFeedback = null
        setQueue(machineId, afterQueue)
        val auditEntry = appendQueueAuditLog(
            machineId,
            beforeQueue,
            afterQueue,
            publicEventTypeOverride = publicEventTypeOverride
                ?: PublicQueueEventType.ONLINE_CHECK_IN_MISSED
                    .takeIf { missedOnlineRegistrationKeys.isNotEmpty() },
            affectedRegistrationKeysOverride =
                affectedRegistrationKeysOverride + missedOnlineRegistrationKeys,
            source = source
        )
        if (surfaceHomeFeedback && auditEntry != null) {
            val machineLabel = configuredMachineName(machineId)
            showHomeOperationFeedback(
                title = homeFeedbackTitle
                    ?: auditEntry.title.removePrefix("$machineLabel · "),
                detail = auditEntry.detail,
                contextLabel = machineLabel,
                tone = if (missedOnlineRegistrationKeys.isNotEmpty()) {
                    HomeSidePanelFeedbackTone.WARNING
                } else {
                    homeFeedbackTone
                }
            )
        }
        soundCue?.let(queueSoundPlayer::play)
        return true
    }

    fun updateQueueAfterOnSiteRegistration(
        machineId: MachineId,
        soundCue: QueueSoundCue,
        advanceWhenPlayingEmpty: Boolean = true,
        transform: (MachineQueue) -> MachineQueue
    ): Boolean {
        val beforeQueue = queueFor(machineId)
        val stagedQueue = transform(beforeQueue)
        if (stagedQueue == beforeQueue) return false
        val roundPlan = RoundPlanner.enterPlayingPosition(stagedQueue)
        val preview = roundPlan.preview
        val needsAvailabilityConfirmation = advanceWhenPlayingEmpty &&
            stagedQueue.playing.isEmpty() &&
            preview?.changedByAvailability == true
        val afterQueue = if (advanceWhenPlayingEmpty && !needsAvailabilityConfirmation) {
            roundPlan.execute()
        } else {
            stagedQueue
        }
        val changed = updateQueue(
            machineId = machineId,
            soundCue = soundCue,
            classifyMissedOnlineRegistrations = true
        ) { currentQueue ->
            if (currentQueue == beforeQueue) afterQueue else currentQueue
        }
        if (changed && needsAvailabilityConfirmation && queueFor(machineId) == afterQueue) {
            enterPlayingConfirmation = machineId
        }
        return changed
    }

    fun updateQueueWithUndo(
        machineId: MachineId,
        message: String,
        feedbackTitle: String = message.substringAfter(" 的 ", message),
        feedbackDetail: String = "$message。",
        transform: (MachineQueue) -> MachineQueue
    ): Boolean {
        val beforeQueue = queueFor(machineId)
        val afterQueue = transform(beforeQueue)
        if (afterQueue == beforeQueue) return false
        val feedbackOutcome = queueUndoFeedbackOutcome(beforeQueue, afterQueue)
        val nonRestorableRegistrationKeys = feedbackOutcome.nonRestorableRegistrationKeys
        homeSidePanelFeedback = null
        newRegistrationHighlight = null
        setQueue(machineId, afterQueue)
        appendQueueAuditLog(
            machineId = machineId,
            beforeQueue = beforeQueue,
            afterQueue = afterQueue,
            titleOverride = message,
            publicEventTypeOverride = PublicQueueEventType.ONLINE_CHECK_IN_MISSED
                .takeIf { nonRestorableRegistrationKeys.isNotEmpty() },
            affectedRegistrationKeysOverride = nonRestorableRegistrationKeys
        )
        queueUndoAction = QueueUndoAction(
            id = nextQueueUndoId++,
            machineId = machineId,
            beforeQueue = beforeQueue,
            afterQueue = afterQueue,
            message = message,
            feedbackTitle = feedbackTitle,
            feedbackDetail = if (feedbackOutcome.detailLines.isEmpty()) {
                feedbackDetail
            } else {
                (listOf(feedbackDetail) + feedbackOutcome.detailLines).joinToString("\n")
            },
            contextLabel = configuredMachineName(machineId),
            feedbackTone = feedbackOutcome.tone,
            nonRestorableRegistrationKeys = nonRestorableRegistrationKeys
        )
        queueSoundPlayer.play(QueueSoundCue.QUEUE_CHANGE)
        return true
    }

    fun undoLatestQueueAction() {
        val action = queueUndoAction ?: return
        var restored = false
        if (queueFor(action.machineId) == action.afterQueue) {
            val restoredQueue = action.beforeQueue.removeAll(
                action.nonRestorableRegistrationKeys
            )
            setQueue(action.machineId, restoredQueue)
            appendQueueAuditLog(
                action.machineId,
                action.afterQueue,
                restoredQueue,
                "撤销：${action.message}"
            )
            restored = restoredQueue != action.afterQueue
        }
        queueUndoAction = null
        if (restored) {
            queueSoundPlayer.play(QueueSoundCue.UNDO)
            showHomeOperationFeedback(
                title = "操作已撤销",
                detail = if (action.nonRestorableRegistrationKeys.isEmpty()) {
                    "${action.feedbackTitle}已撤销，队列已经恢复到操作前的状态。"
                } else {
                    "${action.feedbackTitle}已撤销；此前因未签到而退出的登记不会恢复。"
                },
                contextLabel = action.contextLabel,
                tone = HomeSidePanelFeedbackTone.INFO
            )
        } else {
            showHomeOperationFeedback(
                title = "无法撤销这次操作",
                detail = "队列已经发生新的变化，为避免覆盖后续操作，本次撤销没有执行。",
                contextLabel = action.contextLabel,
                tone = HomeSidePanelFeedbackTone.WARNING
            )
        }
    }

    fun updateStatus(machineId: MachineId, transform: (MachineStatus) -> MachineStatus) {
        if (machineId == MachineId.A) machineAStatus = transform(machineAStatus)
        else machineBStatus = transform(machineBStatus)
    }

    fun reportMachineStopped(
        machineId: MachineId,
        reason: MachineStopReason,
        reasonDetail: String? = null
    ) {
        val currentStatus = statusFor(machineId)
        val stoppedStatus = currentStatus.stop(reason, System.currentTimeMillis(), reasonDetail)
        if (stoppedStatus == currentStatus) return
        updateStatus(machineId) { stoppedStatus }
        queueSoundPlayer.play(QueueSoundCue.CAUTION)
        val registrations = queueFor(machineId).allRegistrations
        val registrationCount = registrations.size
        appendAuditLog(
            createAuditLogEntry(
                category = auditCategoryFor(machineId),
                title = "${configuredMachineName(machineId)} 已停止使用",
                detail = buildString {
                    append(
                        "原因：${machineStopReasonLabel(stoppedStatus.stopReason, stoppedStatus.stopReasonDetail)}。"
                    )
                    if (registrationCount > 0) {
                        append("现有 $registrationCount 份登记及其顺序已保留；恢复正常使用后，本轮计时会从头开始。")
                    }
                },
                publicEventType = PublicQueueEventType.MACHINE_STOPPED,
                affectedRegistrationKeys = registrations.map { it.key }
            )
        )
        if (queueUndoAction?.machineId == machineId) queueUndoAction = null
        showHomeOperationFeedback(
            title = "机台已停止使用",
            detail = buildString {
                append("停止原因：${machineStopReasonLabel(stoppedStatus.stopReason, stoppedStatus.stopReasonDetail)}。")
                if (registrationCount > 0) {
                    append("现有 $registrationCount 份登记及其顺序已保留。")
                }
            },
            contextLabel = configuredMachineName(machineId),
            tone = HomeSidePanelFeedbackTone.WARNING
        )
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
                title = "${configuredMachineName(machineId)} 已恢复正常使用",
                detail = if (queueFor(machineId).playing.isEmpty()) {
                    "这台机台现在可以继续接收和处理排队登记。"
                } else {
                    "保留的登记顺序已经恢复，本轮计时已从头开始。"
                },
                publicEventType = PublicQueueEventType.MACHINE_RESTORED,
                affectedRegistrationKeys = restoredQueue.allRegistrations.map { it.key }
            )
        )
        showHomeOperationFeedback(
            title = "机台已恢复正常使用",
            detail = if (restoredQueue.playing.isEmpty()) {
                "现在可以继续接收和处理排队登记。"
            } else {
                "保留的登记顺序已经恢复，本轮计时已从头开始。"
            },
            contextLabel = configuredMachineName(machineId)
        )
    }

    fun transferRegistrations(request: MachineTransferRequest): Boolean {
        val destinationMachineId = otherMachine(request.sourceMachineId)
        val registrationKeys = request.registrationKeys.toSet()
        val sourceQueue = queueFor(request.sourceMachineId)
        val destinationQueue = queueFor(destinationMachineId)
        val registrations = sourceQueue.allRegistrations
            .filter { it.key in registrationKeys }
        val releasedPartnerRegistrations = sourceQueue.allRegistrations.filter { registration ->
            registration.key !in registrationKeys && registration.fixedPartnerKey in registrationKeys
        }
        if (
            !statusFor(request.sourceMachineId).isOperational ||
            !statusFor(destinationMachineId).isOperational ||
            registrationKeys.isEmpty() ||
            registrations.size != registrationKeys.size ||
            sourceQueue.playing.any { it.key in registrationKeys } ||
            destinationQueue.registrationCount + registrations.size > 20
        ) return false

        val updatedSource = sourceQueue.removeAll(registrationKeys)
        val updatedDestination = destinationQueue.receiveAtWaitingTail(registrations)
        if (
            updatedSource == sourceQueue ||
            updatedDestination.registrationCount != destinationQueue.registrationCount + registrations.size
        ) return false

        queueUndoAction = null
        setQueue(request.sourceMachineId, updatedSource)
        setQueue(destinationMachineId, updatedDestination)
        createMachineTransferAuditLog(
            category = auditCategoryFor(request.sourceMachineId),
            sourceMachineLabel = configuredMachineName(request.sourceMachineId),
            destinationMachineLabel = configuredMachineName(destinationMachineId),
            registrations = registrations,
            releasedPartnerRegistrations = releasedPartnerRegistrations
        )?.let(::appendAuditLog)
        queueSoundPlayer.play(QueueSoundCue.QUEUE_CHANGE)
        val sourceMachineName = configuredMachineName(request.sourceMachineId)
        val destinationMachineName = configuredMachineName(destinationMachineId)
        showHomeOperationFeedback(
            title = if (registrations.size == 1) "登记已切换机台" else "多份登记已切换机台",
            detail = "${registrations.joinToString("、") { "“${it.displayId}”" }}已转至 $destinationMachineName 的等待顺序末端。",
            contextLabel = "$sourceMachineName · 转至 $destinationMachineName"
        )
        return true
    }

    fun idAlreadyExists(displayId: String, exceptKey: Int? = null): Boolean =
        (machineA.allRegistrations + machineB.allRegistrations).any {
            it.key != exceptKey && it.displayId.equals(displayId.trim(), ignoreCase = true)
        }

    fun playerProfileNicknameExists(nickname: String, exceptProfileId: String? = null): Boolean =
        playerProfiles.any {
            it.id != exceptProfileId && it.nickname.equals(nickname.trim(), ignoreCase = true)
        }

    fun playerProfileQqExists(qqNumber: String, exceptProfileId: String? = null): Boolean =
        playerProfiles.any {
            it.id != exceptProfileId && it.normalizedQqNumber() == qqNumber.trim()
        }

    fun playerProfileNicknameConflictsWithQueue(
        nickname: String,
        profileId: String? = editingPlayerProfileId,
        equivalentProfileIds: Set<String> = emptySet()
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
                registration.playerProfileId !in equivalentProfileIds &&
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

    fun applyPlayerProfileToState(
        profile: PlayerProfile,
        recordAudit: Boolean = true,
        source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL
    ) {
        val existingIndex = playerProfiles.indexOfFirst { it.id == profile.id }
        val existingProfile = playerProfiles.getOrNull(existingIndex)
        playerProfiles = if (existingIndex >= 0) {
            playerProfiles.toMutableList().apply { this[existingIndex] = profile }
        } else {
            playerProfiles + profile
        }
        updateQueue(MachineId.A, source = source) {
            it.syncPlayerProfileDetails(profile.id, profile.nickname, profile.gender)
        }
        updateQueue(MachineId.B, source = source) {
            it.syncPlayerProfileDetails(profile.id, profile.nickname, profile.gender)
        }
        if (recordAudit && (
            existingProfile == null ||
            existingProfile.nickname != profile.nickname ||
            existingProfile.gender != profile.gender ||
            existingProfile.defaultPreference != profile.defaultPreference ||
            existingProfile.qqNumber != profile.qqNumber ||
            existingProfile.qqVisibility != profile.qqVisibility ||
            existingProfile.notificationPreferences != profile.notificationPreferences ||
            existingProfile.setupVersion != profile.setupVersion
        )) {
            appendAuditLog(createPlayerProfileAuditLog(existingProfile, profile, source = source))
        }
    }

    fun persistPlayerProfileForUser(
        profile: PlayerProfile,
        latestProfileMutation: ((PlayerProfile) -> PlayerProfile)? = null,
        failureDetail: String,
        onPersisted: (PlayerProfile) -> Unit
    ) {
        if (playerProfileWriteInProgress) return
        playerProfileWriteInProgress = true
        playerProfileWriteFailureDetail = null
        coroutineScope.launch {
            try {
                val applyPersistedProfile: (PlayerProfile) -> Unit = { persistedProfile ->
                    applyPlayerProfileToState(persistedProfile)
                    onPersisted(persistedProfile)
                }
                val persisted = if (latestProfileMutation == null) {
                    playerProfilePersistence.persistAndApply(
                        profile = profile,
                        onPersisted = applyPersistedProfile
                    )
                } else {
                    playerProfilePersistence.mutateAndApply(
                        profileId = profile.id,
                        currentProfiles = { playerProfiles },
                        mutation = latestProfileMutation,
                        onPersisted = applyPersistedProfile
                    )
                }
                if (!persisted) {
                    playerProfileWriteFailureDetail = failureDetail
                }
            } finally {
                playerProfileWriteInProgress = false
            }
        }
    }

    suspend fun mergeCloudPlayerProfiles(cloudPayload: PlayerProfileSyncPayload) {
        if (cloudPayload.profiles.isEmpty() && cloudPayload.profileAliases.isEmpty()) {
            cloudProfileRestoreFailureDetail = null
            return
        }
        val result = playerProfilePersistence.reconcileCloudProfiles(
            cloudProfiles = cloudPayload.profiles,
            profileAliases = cloudPayload.profileAliases,
            currentProfiles = { playerProfiles },
            nicknameConflictsWithQueue = { nickname, profileId, equivalentProfileIds ->
                playerProfileNicknameConflictsWithQueue(
                    nickname = nickname,
                    profileId = profileId,
                    equivalentProfileIds = equivalentProfileIds
                )
            }
        )
        when (result) {
            CloudPlayerProfilePersistenceResult.PersistenceFailed -> {
                cloudProfileRestoreFailureDetail =
                    "云端玩家资料暂时无法写入本机，将继续重试。"
                cloudProfileRestoreFailureAtMillis = System.currentTimeMillis()
            }

            is CloudPlayerProfilePersistenceResult.Success -> {
                cloudProfileRestoreFailureDetail = null
                playerProfiles = result.profiles
                val syncedMachineA = result.profiles.fold(
                    machineA.resolvePlayerProfileAliases(
                        result.appliedAliases,
                        result.profiles
                    )
                ) { queue, profile ->
                    queue.syncPlayerProfileDetails(profile.id, profile.nickname, profile.gender)
                }
                val syncedMachineB = result.profiles.fold(
                    machineB.resolvePlayerProfileAliases(
                        result.appliedAliases,
                        result.profiles
                    )
                ) { queue, profile ->
                    queue.syncPlayerProfileDetails(profile.id, profile.nickname, profile.gender)
                }
                if (syncedMachineA != machineA || syncedMachineB != machineB) {
                    queueUndoAction = null
                    homeSidePanelFeedback = null
                }
                machineA = syncedMachineA
                machineB = syncedMachineB
                if (result.profilesChanged) {
                    val details = buildList {
                        if (result.appliedProfiles.isNotEmpty()) {
                            add("写入 ${result.appliedProfiles.size} 份新增或较新的资料")
                        }
                        if (result.appliedAliases.isNotEmpty()) {
                            add("清理 ${result.appliedAliases.size} 份旧资料副本")
                        }
                    }.joinToString("，")
                    appendAuditLog(
                        createAuditLogEntry(
                            category = AuditLogCategory.PLAYER_PROFILE,
                            title = "同步云端玩家资料",
                            detail = "已从服务器$details。",
                            source = AuditLogSource.SYSTEM_AUTOMATIC
                        )
                    )
                }
            }
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
        showHomeOperationFeedback(
            title = "登记排队已启用",
            detail = "现在可以继续创建新的排队登记。",
            contextLabel = "现场终端"
        )
    }

    fun closeRegistration(businessHoursTrigger: BusinessHoursCloseTrigger? = null) {
        if (!registrationOpen) return
        val automaticBusinessHours = businessHoursTrigger != null
        val removedCount = machineA.registrationCount + machineB.registrationCount
        val removedRegistrationKeys = (machineA.allRegistrations + machineB.allRegistrations)
            .map { it.key }
        queueUndoAction = null
        machineA = MachineQueue()
        machineB = MachineQueue()
        registrationOpen = false
        queueSoundPlayer.play(QueueSoundCue.CAUTION)
        val detail = if (removedCount == 0) {
            when (businessHoursTrigger) {
                BusinessHoursCloseTrigger.QUEUE_EMPTY_DURING_GRACE ->
                    "今日营业时间已结束，现有队列已经处理完毕，登记排队现已关闭。"
                BusinessHoursCloseTrigger.GRACE_PERIOD_EXPIRED ->
                    "今日营业时间结束已 20 分钟，登记排队现已关闭。"
                null -> "新的排队登记已停止接收。"
            }
        } else {
            when (businessHoursTrigger) {
                BusinessHoursCloseTrigger.GRACE_PERIOD_EXPIRED ->
                    "今日营业时间结束已 20 分钟，登记排队现已关闭，并清除了两台机台剩余的 $removedCount 份登记。"
                BusinessHoursCloseTrigger.QUEUE_EMPTY_DURING_GRACE ->
                    "今日营业时间已结束，登记排队现已关闭，并清除了两台机台的 $removedCount 份登记。"
                null ->
                    "新的排队登记已停止接收，并清除了两台机台的 $removedCount 份登记。"
            }
        }
        appendAuditLog(
            createAuditLogEntry(
                category = AuditLogCategory.SYSTEM,
                title = if (automaticBusinessHours) "营业结束，关闭登记排队" else "关闭登记排队",
                detail = detail,
                source = if (automaticBusinessHours) {
                    AuditLogSource.SYSTEM_AUTOMATIC
                } else {
                    AuditLogSource.ON_SITE_TERMINAL
                },
                publicEventType = PublicQueueEventType.REGISTRATION_CLOSED,
                affectedRegistrationKeys = removedRegistrationKeys
            )
        )
        showHomeOperationFeedback(
            title = if (automaticBusinessHours) "营业结束，登记排队已关闭" else "登记排队已关闭",
            detail = detail,
            contextLabel = if (automaticBusinessHours) "系统自动" else "现场终端",
            tone = HomeSidePanelFeedbackTone.WARNING
        )
    }

    fun restorePreviousQueue(savedState: PersistedQueueState) {
        fun syncKnownProfiles(queue: MachineQueue): MachineQueue = playerProfiles.fold(queue) {
            updatedQueue, profile ->
            updatedQueue.syncPlayerProfileDetails(profile.id, profile.nickname, profile.gender)
        }
        queueId = savedState.queueId
        queueRevision.set(savedState.revision)
        machineA = syncKnownProfiles(savedState.machineA)
        machineB = syncKnownProfiles(savedState.machineB)
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
                detail = "已恢复${configuredMachineName(MachineId.A)} 的 ${savedState.machineA.registrationCount} 个登记和${configuredMachineName(MachineId.B)} 的 ${savedState.machineB.registrationCount} 个登记。",
                publicEventType = PublicQueueEventType.QUEUE_RESTORED,
                affectedRegistrationKeys = (
                    savedState.machineA.allRegistrations + savedState.machineB.allRegistrations
                    ).map { it.key }
            )
        )
        showHomeOperationFeedback(
            title = "上次队列已恢复",
            detail = "已恢复 ${configuredMachineName(MachineId.A)} 的 ${savedState.machineA.registrationCount} 份登记，以及 ${configuredMachineName(MachineId.B)} 的 ${savedState.machineB.registrationCount} 份登记。",
            contextLabel = "本机队列恢复",
            tone = HomeSidePanelFeedbackTone.INFO
        )
    }

    fun startWithNewQueue(savedState: PersistedQueueState) {
        queueId = newQueueId()
        queueRevision.updateAndGet { current -> maxOf(current, savedState.revision) }
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
                detail = "未载入上次保存的 ${savedState.totalRegistrationCount} 个登记，已从空队列开始。",
                publicEventType = PublicQueueEventType.QUEUE_RESET
            )
        )
        showHomeOperationFeedback(
            title = "已开始新的队列",
            detail = "上次保存的 ${savedState.totalRegistrationCount} 份登记没有载入，当前从空队列开始。",
            contextLabel = "本机队列恢复",
            tone = HomeSidePanelFeedbackTone.WARNING
        )
    }

    fun openPlayerLibrary(context: PlayerProfileContext) {
        playerProfileContext = context
        temporarySelected = false
        playerProfileSearch = ""
        selectedPlayerProfileId = null
        coroutineScope.launch { playerProfileListState.scrollToItem(0) }
        screen = Screen.PLAYER_LIBRARY
    }

    fun currentPlayerProfileDraft() = PlayerProfileDraftSnapshot(
        nickname = profileNicknameDraft,
        gender = profileGenderDraft,
        defaultPreference = profilePreferenceDraft,
        qqNumber = profileQqDraft,
        qqVisibility = profileQqVisibilityDraft,
        notificationPreferences = profileNotificationDraft
    )

    fun openNewPlayerProfile() {
        playerProfileEditorReturnScreen = Screen.PLAYER_LIBRARY
        editingPlayerProfileId = null
        profileNicknameDraft = ""
        profileGenderDraft = PlayerGender.UNDISCLOSED
        profilePreferenceDraft = ProfilePlayPreference.ASK_EVERY_TIME
        profileQqDraft = ""
        profileQqVisibilityDraft = QqVisibility.TERMINAL_ONLY
        profileNotificationDraft = QueueNotificationPreferences()
        initialPlayerProfileDraft = currentPlayerProfileDraft()
        discardPlayerProfileDraftConfirmationVisible = false
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
        profileQqDraft = profile.qqNumber.orEmpty()
        profileQqVisibilityDraft = profile.qqVisibility
        profileNotificationDraft = profile.notificationPreferences
        initialPlayerProfileDraft = currentPlayerProfileDraft()
        discardPlayerProfileDraftConfirmationVisible = false
        screen = Screen.PLAYER_PROFILE_EDITOR
    }

    fun requestPlayerProfileEditorBack() {
        if (currentPlayerProfileDraft() != initialPlayerProfileDraft) {
            discardPlayerProfileDraftConfirmationVisible = true
        } else {
            screen = playerProfileEditorReturnScreen
        }
    }

    fun openPlayerProfile(profile: PlayerProfile) {
        selectedPlayerProfileId = profile.id
        profileJoinPreference = profile.defaultPreference.toPlayPreferenceOrNull()
        rememberProfileJoinPreference = false
        screen = Screen.PLAYER_PROFILE_DETAIL
    }

    fun savePlayerProfileDraft() {
        val normalizedNickname = profileNicknameDraft.trim()
        val normalizedQqNumber = normalizeOptionalContact(profileQqDraft)
        if (
            normalizedNickname.isBlank() ||
            playerProfileNicknameExists(normalizedNickname, editingPlayerProfileId) ||
            playerProfileNicknameConflictsWithQueue(normalizedNickname) ||
            normalizedQqNumber == null ||
            !isValidQqNumber(normalizedQqNumber) ||
            playerProfileQqExists(normalizedQqNumber, editingPlayerProfileId)
        ) return
        val nowMillis = System.currentTimeMillis()
        val existingProfile = editingPlayerProfileId?.let { profileId ->
            playerProfiles.firstOrNull { it.id == profileId }
        }
        val completedNewSettings = existingProfile?.hasCompleteRequiredDetails != true
        val savedProfile = existingProfile?.copy(
            nickname = normalizedNickname,
            gender = profileGenderDraft,
            defaultPreference = profilePreferenceDraft,
            qqNumber = normalizedQqNumber,
            qqVisibility = profileQqVisibilityDraft,
            notificationPreferences = profileNotificationDraft,
            setupVersion = CURRENT_PLAYER_PROFILE_SETUP_VERSION,
            revision = existingProfile.revision + 1L,
            updatedAtMillis = nowMillis
        ) ?: createPlayerProfile(
            nickname = normalizedNickname,
            gender = profileGenderDraft,
            defaultPreference = profilePreferenceDraft,
            qqNumber = normalizedQqNumber,
            qqVisibility = profileQqVisibilityDraft,
            notificationPreferences = profileNotificationDraft,
            setupVersion = CURRENT_PLAYER_PROFILE_SETUP_VERSION,
            createdAtMillis = nowMillis
        )
        persistPlayerProfileForUser(
            profile = savedProfile,
            latestProfileMutation = existingProfile?.let {
                { latestProfile ->
                    latestProfile.copy(
                        nickname = normalizedNickname,
                        gender = profileGenderDraft,
                        defaultPreference = profilePreferenceDraft,
                        qqNumber = normalizedQqNumber,
                        qqVisibility = profileQqVisibilityDraft,
                        notificationPreferences = profileNotificationDraft,
                        setupVersion = CURRENT_PLAYER_PROFILE_SETUP_VERSION,
                        revision = latestProfile.revision + 1L,
                        updatedAtMillis = maxOf(
                            nowMillis,
                            latestProfile.updatedAtMillis + 1
                        )
                    )
                }
            },
            failureDetail = "玩家资料未能保存到本机。当前修改尚未生效，请稍后重试。"
        ) { persistedProfile ->
            Toast.makeText(
                context,
                panguSpacing(if (existingProfile == null) {
                    "“${persistedProfile.nickname}”的玩家资料已创建。"
                } else {
                    "“${persistedProfile.nickname}”的玩家资料已保存。"
                }),
                Toast.LENGTH_SHORT
            ).show()
            if (existingProfile == null) openPlayerProfile(persistedProfile)
            else screen = playerProfileEditorReturnScreen
            if (completedNewSettings) {
                botQqNumber?.let { botFriendPromptQq = it }
            }
        }
    }

    fun completePlayerProfileRegistration() {
        val machineId = selectedMachine ?: return
        val profile = selectedPlayerProfileId?.let { profileId ->
            playerProfiles.firstOrNull { it.id == profileId }
        } ?: return
        val preference = profile.defaultPreference.toPlayPreferenceOrNull() ?: profileJoinPreference ?: return
        if (
            !acceptingNewRegistrations ||
            !profile.hasValidContact ||
            !profile.hasCompleteRequiredDetails ||
            playerProfileAlreadyRegistered(profile) ||
            !statusFor(machineId).isOperational ||
            queueFor(machineId).registrationCount >= 20
        ) return
        val usageAtMillis = System.currentTimeMillis()
        val preferenceToRemember = preference.takeIf {
                profile.defaultPreference == ProfilePlayPreference.ASK_EVERY_TIME &&
                    rememberProfileJoinPreference
            }
        val usedProfile = profile.recordUsage(
            atMillis = usageAtMillis,
            preferenceToRemember = preferenceToRemember
        )
        persistPlayerProfileForUser(
            profile = usedProfile,
            latestProfileMutation = { latestProfile ->
                latestProfile.recordUsage(
                    atMillis = maxOf(
                        usageAtMillis,
                        latestProfile.updatedAtMillis + 1
                    ),
                    preferenceToRemember = preferenceToRemember
                )
            },
            failureDetail = "玩家资料的本次使用记录未能保存，因此尚未加入排队。请稍后重试。"
        ) persisted@{ persistedProfile ->
            if (
                !acceptingNewRegistrations ||
                !statusFor(machineId).isOperational ||
                queueFor(machineId).registrationCount >= 20
            ) {
                screen = Screen.HOME
                return@persisted
            }
            val registrationKey = nextKey++
            val registrationAdded = updateQueueAfterOnSiteRegistration(machineId, QueueSoundCue.CONFIRM) {
                it.receiveAtWaitingTail(
                    listOf(
                    Registration(
                        key = registrationKey,
                        displayId = persistedProfile.nickname,
                        preference = preference,
                        isTemporary = false,
                        gender = persistedProfile.gender,
                        playerProfileId = persistedProfile.id
                    )
                    )
                )
            }
            selectedPlayerProfileId = null
            rememberProfileJoinPreference = false
            if (registrationAdded) showNewRegistrationFeedback(machineId, registrationKey)
            screen = Screen.HOME
        }
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
            !profile.hasCompleteRequiredDetails ||
            !registration.isTemporary ||
            !statusFor(selection.machineId).isOperational ||
            playerProfileAlreadyRegistered(profile, exceptKey = registration.key)
        ) return
        val usageAtMillis = System.currentTimeMillis()
        val usedProfile = profile.recordUsage(atMillis = usageAtMillis)
        persistPlayerProfileForUser(
            profile = usedProfile,
            latestProfileMutation = { latestProfile ->
                latestProfile.recordUsage(
                    atMillis = maxOf(
                        usageAtMillis,
                        latestProfile.updatedAtMillis + 1
                    )
                )
            },
            failureDetail = "玩家资料的本次使用记录未能保存，因此尚未认领登记。请稍后重试。"
        ) { persistedProfile ->
            updateQueue(
                machineId = selection.machineId,
                soundCue = QueueSoundCue.CONFIRM,
                surfaceHomeFeedback = true
            ) {
                it.claimWithPlayerProfile(
                    registrationKey = registration.key,
                    playerProfileId = persistedProfile.id,
                    playerNickname = persistedProfile.nickname,
                    gender = persistedProfile.gender,
                    preferenceOverride = preferenceOverride
                )
            }
            claimPreferenceMismatchProfileId = null
            claimTarget = null
            selectedPlayerProfileId = null
            playerProfileContext = PlayerProfileContext.JOIN_QUEUE
            screen = Screen.HOME
        }
    }

    fun joinableMachineIds(): List<MachineId> = MachineId.entries.filter { machineId ->
        statusFor(machineId).isOperational && queueFor(machineId).registrationCount < 20
    }

    fun registrationLikelyAfterClosing(machineId: MachineId): Boolean {
        val nowMillis = System.currentTimeMillis()
        return estimatedWaitExtendsPastClosing(
            status = businessHoursStatus,
            nowMillis = nowMillis,
            estimatedWaitMinutes = estimatedWaitForNewOpenRegistration(
                queueFor(machineId),
                nowMillis
            )
        )
    }

    fun continueRegistrationStart(batch: Boolean) {
        isBatchFlow = batch
        selectedMachine = null
        draftId = ""
        temporarySelected = false
        selectedPreference = PlayPreference.OPEN_TO_JOIN
        batchAmount = "2"
        screen = Screen.MACHINE
    }

    fun continueRegistrationForMachine(machineId: MachineId) {
        isBatchFlow = false
        selectedMachine = machineId
        mobileRegistrationSession = null
        mobileRegistrationFailureDetail = null
        draftId = ""
        temporarySelected = false
        selectedPreference = PlayPreference.OPEN_TO_JOIN
        screen = Screen.CREATE_REGISTRATION
    }

    fun requestMobileRegistrationSession() {
        val machineId = selectedMachine ?: return
        if (
            mobileRegistrationLoading ||
            !cloudSyncAvailable ||
            !queueRuleSettings.websiteSyncEnabled ||
            !queueCommandClient.isConfigured
        ) return
        mobileRegistrationLoading = true
        mobileRegistrationFailureDetail = null
        val requestedQueueId = queueId
        val requestId = java.util.UUID.randomUUID().toString()
        coroutineScope.launch {
            try {
                val session = queueCommandClient.createMobileRegistrationSession(
                    requestId = requestId,
                    queueId = requestedQueueId,
                    machineId = machineId.name
                )
                if (
                    queueId != requestedQueueId ||
                    selectedMachine != machineId ||
                    screen != Screen.CREATE_REGISTRATION ||
                    !queueRuleSettings.websiteSyncEnabled ||
                    !queueCommandClient.isConfigured
                ) return@launch
                if (session == null) {
                    mobileRegistrationFailureDetail =
                        queueCommandClient.commandSyncFailureDetail
                            ?: "暂时无法创建移动设备登记，请稍后重试。"
                } else {
                    mobileRegistrationSession = session
                }
            } finally {
                mobileRegistrationLoading = false
            }
        }
    }

    fun continueSelectedRegistrationMachine(machineId: MachineId) {
        selectedMachine = machineId
        if (isBatchFlow) {
            batchAmount = minOf(2, 20 - queueFor(machineId).registrationCount).toString()
        }
        screen = if (isBatchFlow) Screen.BATCH_AMOUNT else Screen.CREATE_REGISTRATION
    }

    fun beginRegistration(batch: Boolean) {
        if (
            reorderSession != null ||
            !acceptingNewRegistrations
        ) return
        val joinableMachines = joinableMachineIds()
        if (joinableMachines.isEmpty()) return
        closingWarningAcknowledgedMachineIds = emptySet()
        val lateMachines = if (batch) {
            emptyList()
        } else {
            joinableMachines.filter(::registrationLikelyAfterClosing)
        }
        if (lateMachines.isNotEmpty() && lateMachines.size == joinableMachines.size) {
            joinClosingWarningRequest = JoinClosingWarningRequest(
                requestedMachineId = null,
                lateMachineIds = lateMachines,
                joinableMachineIds = joinableMachines
            )
            return
        }
        continueRegistrationStart(batch)
    }

    fun beginRegistrationForMachine(machineId: MachineId) {
        if (
            reorderSession != null ||
            !acceptingNewRegistrations ||
            !statusFor(machineId).isOperational ||
            queueFor(machineId).registrationCount >= 20
        ) return
        closingWarningAcknowledgedMachineIds = emptySet()
        if (registrationLikelyAfterClosing(machineId)) {
            joinClosingWarningRequest = JoinClosingWarningRequest(
                requestedMachineId = machineId,
                lateMachineIds = listOf(machineId),
                joinableMachineIds = joinableMachineIds()
            )
            return
        }
        continueRegistrationForMachine(machineId)
    }

    fun selectRegistrationMachine(machineId: MachineId) {
        if (
            !acceptingNewRegistrations ||
            !statusFor(machineId).isOperational ||
            queueFor(machineId).registrationCount >= 20
        ) return
        if (
            !isBatchFlow &&
            machineId !in closingWarningAcknowledgedMachineIds &&
            registrationLikelyAfterClosing(machineId)
        ) {
            joinClosingWarningRequest = JoinClosingWarningRequest(
                requestedMachineId = machineId,
                lateMachineIds = listOf(machineId),
                joinableMachineIds = joinableMachineIds(),
                continueFromMachineSelection = true
            )
            return
        }
        continueSelectedRegistrationMachine(machineId)
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
            !acceptingNewRegistrations ||
            normalizedId.isBlank() ||
            idAlreadyExists(normalizedId) ||
            !statusFor(machineId).isOperational ||
            queueFor(machineId).registrationCount >= 20
        ) return
        val registrationKey = nextKey++
        val registrationAdded = updateQueueAfterOnSiteRegistration(machineId, QueueSoundCue.CONFIRM) {
            it.receiveAtWaitingTail(
                listOf(Registration(registrationKey, normalizedId, preference))
            )
        }
        if (registrationAdded) showNewRegistrationFeedback(machineId, registrationKey)
        screen = Screen.HOME
    }

    fun beginFriendPreferenceRegistration() {
        val machineId = selectedMachine ?: return
        val normalizedId = draftId.trim()
        if (
            !acceptingNewRegistrations ||
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
        if (!acceptingNewRegistrations || !statusFor(machineId).isOperational) return
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
        val registrationsAdded = updateQueueAfterOnSiteRegistration(machineId, QueueSoundCue.CONFIRM) { queue ->
            queue.receiveAtWaitingTail(registrations)
        }
        if (registrationsAdded) {
            showHomeOperationFeedback(
                title = "批量登记已创建",
                detail = "已在 ${configuredMachineName(machineId)} 创建 $amount 份登记，并按创建顺序加入队列。",
                contextLabel = configuredMachineName(machineId)
            )
        }
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

    LaunchedEffect(
        nowMillis,
        queuePersistenceReady,
        pendingQueueRestore,
        machineA.waiting,
        machineB.waiting
    ) {
        if (!queuePersistenceReady || pendingQueueRestore != null) return@LaunchedEffect
        val removalNotices = mutableListOf<String>()
        MachineId.entries.forEach { machineId ->
            val queueBeforeRemoval = queueFor(machineId)
            val expiredKeys = queueBeforeRemoval.expiredOnlineRegistrationKeys(nowMillis)
            if (expiredKeys.isNotEmpty()) {
                val expiredNames = queueBeforeRemoval.allRegistrations
                    .filter { it.key in expiredKeys }
                    .joinToString("、") { "“${it.displayId}”" }
                val removed = updateQueue(
                    machineId = machineId,
                    publicEventTypeOverride = PublicQueueEventType.ONLINE_CHECK_IN_TIMED_OUT,
                    affectedRegistrationKeysOverride = expiredKeys,
                    source = AuditLogSource.SYSTEM_AUTOMATIC
                ) { queue ->
                    queue.removeExpiredOnlineRegistrations(nowMillis)
                }
                if (removed) {
                    removalNotices += "${configuredMachineName(machineId)}：$expiredNames"
                }
            }
        }
        if (removalNotices.isNotEmpty()) {
            showHomeOperationFeedback(
                title = "未签到登记已自动移除",
                detail = removalNotices.joinToString("\n") +
                    "\n以上线上登记未在创建后的 30 分钟内完成现场签到，现已退出排队。",
                contextLabel = "系统自动",
                tone = HomeSidePanelFeedbackTone.WARNING
            )
        }
    }

    LaunchedEffect(
        queueRuleSettings.businessHours,
        lastHandledClosingOccurrenceId,
        registrationOpen,
        machineA.registrationCount,
        machineB.registrationCount,
        queuePersistenceReady,
        pendingQueueRestore
    ) {
        if (!queuePersistenceReady || pendingQueueRestore != null) return@LaunchedEffect
        while (true) {
            val status = evaluateBusinessHours(
                queueRuleSettings.businessHours,
                System.currentTimeMillis()
            )
            val trigger = businessHoursCloseTrigger(
                status = status,
                nowMillis = System.currentTimeMillis(),
                registrationCount = machineA.registrationCount + machineB.registrationCount,
                lastHandledOccurrenceId = lastHandledClosingOccurrenceId
            )
            if (trigger != null) {
                val occurrenceId = status.mostRecentClosingOccurrenceId
                if (registrationOpen) closeRegistration(trigger)
                if (occurrenceId != null) {
                    lastHandledClosingOccurrenceId = occurrenceId
                    queueRuleSettingsRepository.markClosingOccurrenceHandled(occurrenceId)
                }
            }
            val now = System.currentTimeMillis()
            val nextBoundary = listOfNotNull(
                status.activeClosingAtMillis,
                status.registrationClosesAtMillis
            ).filter { it > now }.minOrNull()
            delay(
                nextBoundary
                    ?.minus(now)
                    ?.coerceIn(250L, 30_000L)
                    ?: 30_000L
            )
        }
    }

    LaunchedEffect(activeClosingGracePeriod) {
        if (!activeClosingGracePeriod) return@LaunchedEffect
        joinClosingWarningRequest = null
        val ordinaryJoinFlow = screen in setOf(
            Screen.MACHINE,
            Screen.CREATE_REGISTRATION,
            Screen.PREFERENCE,
            Screen.BATCH_AMOUNT
        )
        val profileJoinFlow = selectedMachine != null &&
            playerProfileContext == PlayerProfileContext.JOIN_QUEUE &&
            screen in setOf(
                Screen.PLAYER_LIBRARY,
                Screen.PLAYER_PROFILE_DETAIL,
                Screen.PLAYER_PROFILE_EDITOR
            )
        if (ordinaryJoinFlow || profileJoinFlow) {
            selectedMachine = null
            selectedPlayerProfileId = null
            screen = Screen.HOME
        }
    }

    fun currentRemoteQueueExecutionState() = RemoteQueueExecutionState(
        queueId = queueId,
        queues = linkedMapOf(
            MachineId.A.name to machineA,
            MachineId.B.name to machineB
        ),
        machineStatuses = mapOf(
            MachineId.A.name to machineAStatus,
            MachineId.B.name to machineBStatus
        ),
        playerProfiles = playerProfiles,
        nextRegistrationKey = nextKey,
        acceptingNewRegistrations = acceptingNewRegistrations,
        websiteRemoteEnabled = queueRuleSettings.websiteSyncEnabled,
        oneBotSyncEnabled = queueRuleSettings.websiteSyncEnabled &&
            queueRuleSettings.oneBotSyncEnabled,
        allowOnlineRegistration = queueRuleSettings.allowOnlineRegistration,
        allowDeferOneRound = queueRuleSettings.allowDeferOneRound,
        allowTemporaryLeave = queueRuleSettings.allowTemporaryLeave
    )

    LaunchedEffect(
        queueCommandClient,
        queueRuleSettings.websiteSyncEnabled,
        queueRuleSettings.oneBotSyncEnabled,
        playerProfilesLoaded,
        mobileRegistrationReceiptsLoaded,
        queuePersistenceReady,
        pendingQueueRestore
    ) {
        var nextCloudProfileRefreshAtMillis = 0L
        var localWriteFailureDetail: String? = null
        var localWriteFailureAtMillis: Long? = null
        suspend fun persistMobileRegistrationReceipt(commandId: String): Boolean {
            if (commandId in appliedMobileRegistrationCommandIds) return true
            if (!mobileRegistrationReceiptRepository.recordApplied(commandId)) return false
            appliedMobileRegistrationCommandIds = appendRecentCommandId(
                appliedMobileRegistrationCommandIds.toList(),
                commandId
            ).toSet()
            return true
        }
        while (true) {
            if (
                playerProfilesLoaded &&
                mobileRegistrationReceiptsLoaded &&
                queuePersistenceReady &&
                pendingQueueRestore == null &&
                cloudSyncAvailable &&
                queueRuleSettings.websiteSyncEnabled &&
                queueCommandClient.isConfigured
            ) {
                val nowMillis = System.currentTimeMillis()
                if (nowMillis >= nextCloudProfileRefreshAtMillis) {
                    queueCommandClient.fetchPlayerProfiles()?.let { cloudPayload ->
                        mergeCloudPlayerProfiles(cloudPayload)
                        botQqNumber = cloudPayload.botQqNumber
                        nextCloudProfileRefreshAtMillis = nowMillis +
                            CLOUD_PROFILE_REFRESH_INTERVAL_MILLIS
                    }
                }
                queueCommandClient.fetchPendingCommands()?.let { commands ->
                    for (command in commands) {
                        when (command) {
                            is PlayerProfileUpdateCommand -> {
                                if (!queueRuleSettings.oneBotSyncEnabled) {
                                    queueCommandClient.complete(
                                        command.commandId,
                                        applied = false,
                                        detail = "现场终端已关闭 QQ Bot 联动。"
                                    )
                                    continue
                                }
                                when (val result = playerProfilePersistence.processCommand(
                                    command = command,
                                    currentProfiles = { playerProfiles },
                                    nicknameConflictsWithQueue =
                                        ::playerProfileNicknameConflictsWithQueue,
                                    onPersisted = { profile ->
                                        applyPlayerProfileToState(
                                            profile,
                                            source = AuditLogSource.QQ_BOT
                                        )
                                    }
                                )) {
                                    is PlayerProfileCommandPersistenceResult.Applied -> {
                                        localWriteFailureDetail = null
                                        queueCommandClient.complete(
                                            command.commandId,
                                            applied = true,
                                            detail = "玩家资料已由终端更新。"
                                        )
                                    }

                                    PlayerProfileCommandPersistenceResult.AlreadyApplied -> {
                                        localWriteFailureDetail = null
                                        queueCommandClient.complete(
                                            command.commandId,
                                            applied = true,
                                            detail = "玩家资料已经是请求的内容。"
                                        )
                                    }

                                    is PlayerProfileCommandPersistenceResult.Rejected -> {
                                        queueCommandClient.complete(
                                            command.commandId,
                                            applied = false,
                                            detail = result.detail
                                        )
                                    }

                                    PlayerProfileCommandPersistenceResult.PersistenceFailed -> {
                                        localWriteFailureDetail =
                                            "玩家资料暂时无法写入本机，应用将继续重试。"
                                        localWriteFailureAtMillis = System.currentTimeMillis()
                                    }
                                }
                            }

                            is RemoteQueueOperationCommand -> {
                                val commandAppliedAtMillis = System.currentTimeMillis()
                                var decision = decideRemoteQueueOperation(
                                    command,
                                    currentRemoteQueueExecutionState(),
                                    commandAppliedAtMillis
                                )
                                val profileToPersist =
                                    (decision as? RemoteQueueOperationDecision.Apply)
                                        ?.updatedProfile
                                if (profileToPersist != null) {
                                    val persisted = playerProfilePersistence.persistAndApply(
                                        profileToPersist
                                    ) { profile ->
                                        applyPlayerProfileToState(
                                            profile,
                                            recordAudit = false,
                                            source = command.source.auditLogSource
                                        )
                                    }
                                    if (!persisted) {
                                        localWriteFailureDetail =
                                            "线上登记的玩家资料暂时无法写入本机，应用将继续重试。"
                                        localWriteFailureAtMillis = System.currentTimeMillis()
                                        continue
                                    }
                                    decision = decideRemoteQueueOperation(
                                        command,
                                        currentRemoteQueueExecutionState(),
                                        commandAppliedAtMillis
                                    )
                                }
                                when (val result = decision) {
                                    is RemoteQueueOperationDecision.Apply -> {
                                        val beforeQueues = mapOf(
                                            MachineId.A.name to machineA,
                                            MachineId.B.name to machineB
                                        )
                                        machineA = result.state.queues[MachineId.A.name]
                                            ?: machineA
                                        machineB = result.state.queues[MachineId.B.name]
                                            ?: machineB
                                        nextKey = maxOf(
                                            nextKey,
                                            result.state.nextRegistrationKey
                                        )
                                        queueUndoAction = null
                                        homeSidePanelFeedback = null
                                        result.changedMachineIds.forEach { machineName ->
                                            val machineId = MachineId.entries.firstOrNull {
                                                it.name == machineName
                                            } ?: return@forEach
                                            appendQueueAuditLog(
                                                machineId = machineId,
                                                beforeQueue = beforeQueues.getValue(machineName),
                                                afterQueue = result.state.queues.getValue(machineName),
                                                publicEventTypeOverride =
                                                    PublicQueueEventType.ONLINE_REGISTRATION_ADDED
                                                        .takeIf {
                                                            command.operation ==
                                                                RemoteQueueOperation.JOIN_QUEUE
                                                        },
                                                source = command.source.auditLogSource
                                            )
                                        }
                                        if (result.changedMachineIds.isNotEmpty()) {
                                            queueSoundPlayer.play(
                                                if (command.operation ==
                                                    RemoteQueueOperation.JOIN_QUEUE
                                                ) {
                                                    QueueSoundCue.CONFIRM
                                                } else {
                                                    QueueSoundCue.QUEUE_CHANGE
                                                }
                                            )
                                        }
                                        if (command.operation == RemoteQueueOperation.JOIN_QUEUE) {
                                            val machineName = result.changedMachineIds.singleOrNull()
                                            val machineId = MachineId.entries.firstOrNull {
                                                it.name == machineName
                                            }
                                            val beforeQueue = machineName?.let(beforeQueues::get)
                                            val afterQueue = machineName?.let(result.state.queues::get)
                                            val addedRegistrationKey = afterQueue?.allRegistrations
                                                ?.firstOrNull { registration ->
                                                    beforeQueue?.allRegistrations?.none {
                                                        it.key == registration.key
                                                    } == true
                                                }?.key
                                            if (machineId != null && addedRegistrationKey != null) {
                                                pendingNewRegistrationHomeRequests =
                                                    pendingNewRegistrationHomeRequests +
                                                    NewRegistrationHomeRequest(
                                                        NewRegistrationHighlight(
                                                            machineId = machineId,
                                                            registrationKey = addedRegistrationKey,
                                                            requestId = command.commandId
                                                        )
                                                    )
                                            }
                                        } else if (result.changedMachineIds.isNotEmpty()) {
                                            val changedMachineLabels = result.changedMachineIds
                                                .mapNotNull { machineName ->
                                                    MachineId.entries.firstOrNull {
                                                        it.name == machineName
                                                    }?.let(::configuredMachineName)
                                                }
                                            val nickname = playerProfiles.firstOrNull {
                                                it.id == command.profileId
                                            }?.nickname
                                            showHomeOperationFeedback(
                                                title = remoteQueueOperationFeedbackTitle(
                                                    command.operation
                                                ),
                                                detail = buildString {
                                                    if (nickname != null) append("“$nickname”：")
                                                    append(result.detail)
                                                },
                                                contextLabel = buildString {
                                                    append(remoteQueueOperationSourceLabel(command.source))
                                                    if (changedMachineLabels.isNotEmpty()) {
                                                        append(" · ")
                                                        append(changedMachineLabels.joinToString("、"))
                                                    }
                                                },
                                                tone = HomeSidePanelFeedbackTone.INFO
                                            )
                                        }
                                        localWriteFailureDetail = null
                                        queueCommandClient.complete(
                                            command.commandId,
                                            applied = true,
                                            detail = result.detail
                                        )
                                    }

                                    is RemoteQueueOperationDecision.AlreadyApplied -> {
                                        localWriteFailureDetail = null
                                        queueCommandClient.complete(
                                            command.commandId,
                                            applied = true,
                                            detail = result.detail
                                        )
                                    }

                                    is RemoteQueueOperationDecision.Reject -> {
                                        queueCommandClient.complete(
                                            command.commandId,
                                            applied = false,
                                            detail = result.detail
                                        )
                                    }
                                }
                            }

                            is MobileDeviceRegistrationCommand -> {
                                var decision = decideMobileDeviceRegistration(
                                    command,
                                    currentRemoteQueueExecutionState(),
                                    appliedMobileRegistrationCommandIds
                                )
                                val profileToPersist =
                                    (decision as? MobileDeviceRegistrationDecision.Apply)
                                        ?.profileToPersist
                                if (profileToPersist != null) {
                                    val persisted = playerProfilePersistence.persistAndApply(
                                        profileToPersist
                                    ) { profile ->
                                        applyPlayerProfileToState(
                                            profile,
                                            source = AuditLogSource.MOBILE_DEVICE
                                        )
                                    }
                                    if (!persisted) {
                                        localWriteFailureDetail =
                                            "移动设备登记的玩家资料暂时无法写入本机，应用将继续重试。"
                                        localWriteFailureAtMillis = System.currentTimeMillis()
                                        continue
                                    }
                                    decision = decideMobileDeviceRegistration(
                                        command,
                                        currentRemoteQueueExecutionState(),
                                        appliedMobileRegistrationCommandIds
                                    )
                                }
                                when (val result = decision) {
                                    is MobileDeviceRegistrationDecision.Apply -> {
                                        val beforeQueue = result.changedMachineId.let { machineName ->
                                            if (machineName == MachineId.A.name) machineA else machineB
                                        }
                                        machineA = result.state.queues[MachineId.A.name]
                                            ?: machineA
                                        machineB = result.state.queues[MachineId.B.name]
                                            ?: machineB
                                        nextKey = maxOf(nextKey, result.state.nextRegistrationKey)
                                        queueUndoAction = null
                                        homeSidePanelFeedback = null
                                        val machineId = MachineId.entries.firstOrNull {
                                            it.name == result.changedMachineId
                                        }
                                        val afterQueue = result.state.queues[result.changedMachineId]
                                        val addedRegistrationKey = afterQueue?.allRegistrations
                                            ?.firstOrNull { registration ->
                                                beforeQueue.allRegistrations.none {
                                                    it.key == registration.key
                                                }
                                            }?.key
                                        if (machineId != null) {
                                            appendQueueAuditLog(
                                                machineId = machineId,
                                                beforeQueue = beforeQueue,
                                                afterQueue = result.state.queues
                                                    .getValue(result.changedMachineId),
                                                source = AuditLogSource.MOBILE_DEVICE
                                            )
                                        }
                                        if (!persistMobileRegistrationReceipt(command.commandId)) {
                                            localWriteFailureDetail =
                                                "移动设备登记的执行记录暂时无法写入本机，应用将继续重试。"
                                            localWriteFailureAtMillis = System.currentTimeMillis()
                                            continue
                                        }
                                        queueSoundPlayer.play(QueueSoundCue.CONFIRM)
                                        localWriteFailureDetail = null
                                        if (machineId != null && addedRegistrationKey != null) {
                                            pendingNewRegistrationHomeRequests =
                                                pendingNewRegistrationHomeRequests +
                                                NewRegistrationHomeRequest(
                                                    highlight = NewRegistrationHighlight(
                                                        machineId = machineId,
                                                        registrationKey = addedRegistrationKey,
                                                        requestId = command.commandId
                                                    ),
                                                    enterPlayingConfirmation = machineId.takeIf {
                                                        result.needsAvailabilityConfirmation
                                                    },
                                                    forceImmediateHome = command.matchesSession(
                                                        mobileRegistrationSession
                                                    )
                                                )
                                        }
                                        queueCommandClient.complete(
                                            command.commandId,
                                            applied = true,
                                            detail = result.detail
                                        )
                                    }

                                    is MobileDeviceRegistrationDecision.AlreadyApplied -> {
                                        val receiptWasAlreadyRecorded =
                                            command.commandId in appliedMobileRegistrationCommandIds
                                        if (!persistMobileRegistrationReceipt(command.commandId)) {
                                            localWriteFailureDetail =
                                                "移动设备登记的执行记录暂时无法写入本机，应用将继续重试。"
                                            localWriteFailureAtMillis = System.currentTimeMillis()
                                            continue
                                        }
                                        localWriteFailureDetail = null
                                        if (!receiptWasAlreadyRecorded) {
                                            val machineId = MachineId.entries.firstOrNull {
                                                it.name == command.machineId
                                            }
                                            val registrationKey = machineId?.let(::queueFor)
                                                ?.allRegistrations
                                                ?.firstOrNull {
                                                    it.originatingCommandId == command.commandId
                                                }?.key
                                            if (machineId != null && registrationKey != null) {
                                                queueSoundPlayer.play(QueueSoundCue.CONFIRM)
                                                pendingNewRegistrationHomeRequests =
                                                    pendingNewRegistrationHomeRequests +
                                                    NewRegistrationHomeRequest(
                                                        NewRegistrationHighlight(
                                                            machineId = machineId,
                                                            registrationKey = registrationKey,
                                                            requestId = command.commandId
                                                        ),
                                                        forceImmediateHome = command.matchesSession(
                                                            mobileRegistrationSession
                                                        )
                                                    )
                                            }
                                        }
                                        queueCommandClient.complete(
                                            command.commandId,
                                            applied = true,
                                            detail = result.detail
                                        )
                                    }

                                    is MobileDeviceRegistrationDecision.Reject -> {
                                        if (command.matchesSession(mobileRegistrationSession)) {
                                            mobileRegistrationSession = null
                                            mobileRegistrationFailureDetail = result.detail
                                        }
                                        queueCommandClient.complete(
                                            command.commandId,
                                            applied = false,
                                            detail = result.detail
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val nextPrivateFailureDetail = listOfNotNull(
                    cloudProfileRestoreFailureDetail,
                    localWriteFailureDetail,
                    queueCommandClient.profileSyncFailureDetail,
                    queueCommandClient.commandSyncFailureDetail
                ).distinct().joinToString("；").takeIf(String::isNotEmpty)
                val activePrivateFailureAtMillis = listOfNotNull(
                    cloudProfileRestoreFailureAtMillis.takeIf {
                        cloudProfileRestoreFailureDetail != null
                    },
                    localWriteFailureAtMillis.takeIf { localWriteFailureDetail != null },
                    queueCommandClient.profileSyncLastErrorAtMillis.takeIf {
                        queueCommandClient.profileSyncFailureDetail != null
                    },
                    queueCommandClient.commandSyncLastErrorAtMillis.takeIf {
                        queueCommandClient.commandSyncFailureDetail != null
                    }
                ).minOrNull()
                privateSyncLastErrorAtMillis = listOfNotNull(
                    privateSyncLastErrorAtMillis,
                    cloudProfileRestoreFailureAtMillis,
                    localWriteFailureAtMillis,
                    queueCommandClient.profileSyncLastErrorAtMillis,
                    queueCommandClient.commandSyncLastErrorAtMillis
                ).maxOrNull()
                if (nextPrivateFailureDetail != null && privateSyncFailureDetail == null) {
                    privateSyncRetryStartedAtMillis = activePrivateFailureAtMillis
                        ?: System.currentTimeMillis()
                } else if (nextPrivateFailureDetail == null) {
                    privateSyncRetryStartedAtMillis = null
                }
                privateSyncFailureDetail = nextPrivateFailureDetail
            } else {
                privateSyncFailureDetail = null
                privateSyncRetryStartedAtMillis = null
            }
            delay(REMOTE_COMMAND_POLL_INTERVAL_MILLIS)
        }
    }

    val displayedCloudSyncStatus = combinedQueueCloudSyncStatus(
        cloudSyncStatus,
        privateSyncFailureDetail,
        privateSyncRetryStartedAtMillis,
        privateSyncLastErrorAtMillis
    )

    LaunchedEffect(foregroundRefreshGeneration, cloudSyncController) {
        cloudSyncController.refresh()
    }

    fun returnHomeAndClearTransientState(resetQueueScroll: Boolean) {
        inactivityWarningSeconds = null
        selectedMachine = null
        isBatchFlow = false
        draftId = ""
        temporarySelected = false
        selectedPreference = PlayPreference.OPEN_TO_JOIN
        joinClosingWarningRequest = null
        mobileRegistrationSession = null
        mobileRegistrationFailureDetail = null
        selectedPlayerProfileId = null
        editingPlayerProfileId = null
        profileJoinPreference = null
        rememberProfileJoinPreference = false
        selectedRegistration = null
        incompleteCheckInProfileId = null
        moveIntoPlayingTarget = null
        registrationActionMode = RegistrationActionMode.ACTIONS
        renameDraft = ""
        claimTarget = null
        finishConfirmation = null
        enterPlayingConfirmation = null
        moreMenuVisible = false
        appDetailsVisible = false
        versionHistoryVisible = false
        editMachineChoiceVisible = false
        stopMachineChoiceVisible = false
        stopReasonTarget = null
        reorderSession = null
        inlineReorderProposal = null
        positionReorderProposal = null
        closeQueueConfirmation = false
        selectedPosition = null
        returnPlayingTarget = null
        returnPlayingRegistrationTarget = null
        advanceToPlayingTarget = null
        absenceChoiceTarget = null
        noShowTarget = null
        groupNoShowTarget = null
        exitTarget = null
        removeGroupTarget = null
        machineTransferTarget = null
        friendPairTarget = null
        stagedFriendPairRegistration = null
        releaseFixedPairTarget = null
        botFriendPromptQq = null
        playerProfileWriteFailureDetail = null
        discardPlayerProfileDraftConfirmationVisible = false
        screen = Screen.HOME
        if (resetQueueScroll) homeQueueScrollResetToken++
    }

    fun returnHomeAfterInactivity() = returnHomeAndClearTransientState(resetQueueScroll = true)

    val homeSidePanelOccluded = screen != Screen.HOME ||
        reorderSession?.explicitEditMode == true ||
        inlineReorderProposal != null ||
        positionReorderProposal != null ||
        selectedRegistration != null ||
        moveIntoPlayingTarget != null ||
        absenceChoiceTarget != null ||
        friendPairTarget != null ||
        finishConfirmation != null ||
        joinClosingWarningRequest != null ||
        enterPlayingConfirmation != null ||
        moreMenuVisible ||
        appDetailsVisible ||
        versionHistoryVisible ||
        editMachineChoiceVisible ||
        stopMachineChoiceVisible ||
        stopReasonTarget != null ||
        claimPreferenceMismatchProfileId != null ||
        selectedPosition != null ||
        returnPlayingTarget != null ||
        returnPlayingRegistrationTarget != null ||
        advanceToPlayingTarget != null ||
        releaseFixedPairTarget != null ||
        machineTransferTarget != null ||
        noShowTarget != null ||
        groupNoShowTarget != null ||
        exitTarget != null ||
        removeGroupTarget != null ||
        closeQueueConfirmation ||
        mobileRegistrationSession != null ||
        mobileRegistrationFailureDetail != null ||
        botFriendPromptQq != null ||
        incompleteCheckInProfileId != null ||
        playerProfileWriteInProgress ||
        playerProfileWriteFailureDetail != null ||
        discardPlayerProfileDraftConfirmationVisible ||
        pendingQueueRestore != null ||
        inactivityWarningSeconds != null

    LaunchedEffect(newRegistrationHighlight?.requestId, homeSidePanelOccluded) {
        if (homeSidePanelOccluded) return@LaunchedEffect
        val requestId = newRegistrationHighlight?.requestId ?: return@LaunchedEffect
        delay(NEW_REGISTRATION_FEEDBACK_MILLIS)
        if (newRegistrationHighlight?.requestId == requestId) {
            newRegistrationHighlight = null
        }
    }

    LaunchedEffect(homeSidePanelFeedback?.id, homeSidePanelOccluded) {
        if (homeSidePanelOccluded) return@LaunchedEffect
        val feedback = homeSidePanelFeedback ?: return@LaunchedEffect
        val feedbackId = feedback.id
        delay(
            if (feedback.tone == HomeSidePanelFeedbackTone.WARNING) {
                HOME_WARNING_FEEDBACK_MILLIS
            } else {
                HOME_OPERATION_FEEDBACK_MILLIS
            }
        )
        if (homeSidePanelFeedback?.id == feedbackId) {
            homeSidePanelFeedback = null
        }
    }

    val inactivityNeedsFormWarning = when (screen) {
        Screen.SETTINGS,
        Screen.CREATE_REGISTRATION,
        Screen.PREFERENCE,
        Screen.PLAYER_PROFILE_EDITOR,
        Screen.PLAYER_PROFILE_DETAIL,
        Screen.BATCH_AMOUNT -> true
        else -> false
    } || (selectedRegistration != null && registrationActionMode == RegistrationActionMode.RENAME) ||
        friendPairTarget != null || stopReasonTarget != null ||
        reorderSession?.explicitEditMode == true || inlineReorderProposal != null ||
        positionReorderProposal != null

    LaunchedEffect(
        pendingNewRegistrationHomeRequests.firstOrNull()?.highlight?.requestId,
        inactivityNeedsFormWarning,
        playerProfileWriteInProgress
    ) {
        val request = pendingNewRegistrationHomeRequests.firstOrNull() ?: return@LaunchedEffect
        if (!request.forceImmediateHome && (inactivityNeedsFormWarning || playerProfileWriteInProgress)) {
            return@LaunchedEffect
        }
        hostActivity?.recordUserInteraction()
        returnHomeAndClearTransientState(resetQueueScroll = false)
        homeSidePanelFeedback = null
        queueUndoAction = null
        newRegistrationHighlight = request.highlight
        enterPlayingConfirmation = request.enterPlayingConfirmation
        pendingNewRegistrationHomeRequests = pendingNewRegistrationHomeRequests.drop(1)
    }

    LaunchedEffect(
        screen,
        inactivityNeedsFormWarning,
        pendingQueueRestore,
        reorderSession?.explicitEditMode,
        playerProfileWriteInProgress
    ) {
        while (true) {
            delay(250L)
            if (playerProfileWriteInProgress) {
                inactivityWarningSeconds = null
                hostActivity?.recordUserInteraction()
                continue
            }
            val elapsedMillis = hostActivity?.elapsedSinceUserInteraction() ?: 0L
            if (elapsedMillis >= INACTIVITY_TIMEOUT_MILLIS) {
                val savedState = pendingQueueRestore
                if (savedState != null) {
                    restorePreviousQueue(savedState)
                    homeQueueScrollResetToken++
                } else {
                    returnHomeAfterInactivity()
                }
                inactivityWarningSeconds = null
                hostActivity?.recordUserInteraction()
                continue
            }
            inactivityWarningSeconds = if (
                inactivityNeedsFormWarning &&
                elapsedMillis >= INACTIVITY_TIMEOUT_MILLIS - INACTIVITY_WARNING_MILLIS
            ) {
                ((INACTIVITY_TIMEOUT_MILLIS - elapsedMillis + 999L) / 1_000L)
                    .coerceIn(1L, INACTIVITY_WARNING_MILLIS / 1_000L)
                    .toInt()
            } else {
                null
            }
        }
    }

    LaunchedEffect(queueUndoAction?.id, homeSidePanelOccluded) {
        if (homeSidePanelOccluded) return@LaunchedEffect
        val action = queueUndoAction ?: return@LaunchedEffect
        delay(HOME_UNDO_FEEDBACK_MILLIS)
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
                    machineName = configuredMachineName(activeReorder.machineId),
                    initialQueue = activeReorder.queueSnapshot,
                    explicitEditMode = activeReorder.explicitEditMode,
                    onCancel = { reorderSession = null },
                        onCommit = { registrations ->
                        if (
                            statusFor(activeReorder.machineId).isOperational &&
                            queueFor(activeReorder.machineId)
                                .hasSameQueueOperationState(activeReorder.queueSnapshot)
                        ) {
                            updateQueueWithUndo(
                                activeReorder.machineId,
                                "${configuredMachineName(activeReorder.machineId)} 的登记顺序已调整",
                                feedbackTitle = "登记顺序已调整",
                                feedbackDetail = "等待登记已经按照确认后的顺序重新排列。"
                            ) { it.replaceOrder(registrations) }
                        }
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
                            acceptingNewRegistrations = acceptingNewRegistrations,
                            businessHoursStatus = businessHoursStatus,
                            closingGracePeriod = activeClosingGracePeriod,
                            cloudSyncStatus = displayedCloudSyncStatus.takeIf { cloudSyncAvailable },
                            queueUndoAction = queueUndoAction,
                            onUndoQueueAction = ::undoLatestQueueAction,
                            onDismissQueueUndo = { queueUndoAction = null },
                            homeSidePanelFeedback = homeSidePanelFeedback,
                            onDismissHomeFeedback = { homeSidePanelFeedback = null },
                            inlineReorderSession = activeReorder?.takeIf { !it.explicitEditMode },
                            inlineReorderResetToken = inlineReorderResetToken,
                            positionReorderResetToken = positionReorderResetToken,
                            queueScrollResetToken = homeQueueScrollResetToken,
                            highlightedRegistration = newRegistrationHighlight,
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
                            onJoin = {
                                dismissHomeSidePanelContent()
                                beginRegistration(false)
                            },
                            onJoinMachine = { machineId ->
                                dismissHomeSidePanelContent()
                                beginRegistrationForMachine(machineId)
                            },
                            onBatch = {
                                dismissHomeSidePanelContent()
                                beginRegistration(true)
                            },
                            onCompletedRegistrationClick = { machineId, registrationKey ->
                                newRegistrationHighlight = null
                                openRegistration(machineId, registrationKey)
                            },
                            onDismissCompletedRegistration = {
                                newRegistrationHighlight = null
                            },
                            onMore = {
                                if (reorderSession == null) moreMenuVisible = true
                            },
                            onFinishRequest = {
                                if (reorderSession == null) finishConfirmation = it
                            },
                            onEnterPlaying = { machineId ->
                                if (reorderSession == null) {
                                    val roundPlan = RoundPlanner.enterPlayingPosition(
                                        queueFor(machineId)
                                    )
                                    if (roundPlan.preview?.changedByAvailability == true) {
                                        enterPlayingConfirmation = machineId
                                    } else {
                                        updateQueue(
                                            machineId = machineId,
                                            soundCue = QueueSoundCue.QUEUE_CHANGE,
                                            classifyMissedOnlineRegistrations = true,
                                            surfaceHomeFeedback = true
                                        ) { currentQueue ->
                                            roundPlan.applyTo(currentQueue) ?: currentQueue
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
                            machineAName = configuredMachineName(MachineId.A),
                            machineBName = configuredMachineName(MachineId.B),
                            onBack = { screen = Screen.HOME }
                        )

                        Screen.SETTINGS -> QueueRuleSettingsScreen(
                            persistedSettings = queueRuleSettings,
                            cloudSyncAvailable = cloudSyncAvailable,
                            onSettingsChange = ::updateQueueRuleSettings,
                            onBack = { screen = Screen.HOME }
                        )

                        Screen.MACHINE -> MachineSelectionScreen(
                            machineA = machineA,
                            machineB = machineB,
                            machineAStatus = machineAStatus,
                            machineBStatus = machineBStatus,
                            machineAName = configuredMachineName(MachineId.A),
                            machineBName = configuredMachineName(MachineId.B),
                            batch = isBatchFlow,
                            onBack = { screen = Screen.HOME },
                            onSelect = ::selectRegistrationMachine
                        )

                        Screen.CREATE_REGISTRATION -> CreateRegistrationScreen(
                            draftId = draftId,
                            temporarySelected = temporarySelected,
                            idAlreadyRegistered = draftId.isNotBlank() && idAlreadyExists(draftId),
                            onIdChange = {
                                draftId = limitCodePointLength(it, 18)
                                hostActivity?.recordUserInteraction()
                            },
                            onTemporarySelect = { temporarySelected = true },
                            onGenerateId = {
                                temporarySelected = true
                                draftId = randomUnusedId()
                            },
                            onPlayerLibrary = {
                                openPlayerLibrary(PlayerProfileContext.JOIN_QUEUE)
                            },
                            mobileRegistrationEnabled = cloudSyncAvailable &&
                                queueRuleSettings.websiteSyncEnabled &&
                                queueCommandClient.isConfigured,
                            mobileRegistrationLoading = mobileRegistrationLoading,
                            onMobileRegistration = ::requestMobileRegistrationSession,
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
                            listState = playerProfileListState,
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
                            onSearchQueryChange = {
                                playerProfileSearch = it
                                hostActivity?.recordUserInteraction()
                            },
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
                            qqAlreadyExists = profileQqDraft.isNotBlank() &&
                                playerProfileQqExists(profileQqDraft, editingPlayerProfileId),
                            qqVisibility = profileQqVisibilityDraft,
                            notificationPreferences = profileNotificationDraft,
                            botQqNumber = botQqNumber,
                            editingExisting = editingPlayerProfileId != null,
                            saving = playerProfileWriteInProgress,
                            onNicknameChange = {
                                profileNicknameDraft = limitCodePointLength(it, 18)
                                hostActivity?.recordUserInteraction()
                            },
                            onGenderChange = { profileGenderDraft = it },
                            onDefaultPreferenceChange = { profilePreferenceDraft = it },
                            onQqNumberChange = { value ->
                                profileQqDraft = value.filter { it in '0'..'9' }
                                    .take(MAX_QQ_NUMBER_LENGTH)
                                hostActivity?.recordUserInteraction()
                            },
                            onQqVisibilityChange = { profileQqVisibilityDraft = it },
                            onNotificationPreferencesChange = {
                                profileNotificationDraft = it
                            },
                            onSave = ::savePlayerProfileDraft,
                            onBack = ::requestPlayerProfileEditorBack
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
                                    machineLabel = selectedMachine
                                        ?.let(::configuredMachineName)
                                        ?: "所选机台",
                                    machineAvailable = selectedMachine?.let { machineId ->
                                        acceptingNewRegistrations &&
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
                                if (value.all(Char::isDigit)) {
                                    batchAmount = value.take(2)
                                    hostActivity?.recordUserInteraction()
                                }
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
                                statusFor(proposal.machineId).isOperational &&
                                queueFor(proposal.machineId)
                                    .hasSameQueueOperationState(proposal.originalQueue)
                            ) {
                                updateQueueWithUndo(
                                    proposal.machineId,
                                    "${configuredMachineName(proposal.machineId)} 的登记顺序已调整",
                                    feedbackTitle = "登记顺序已调整",
                                    feedbackDetail = "等待登记已经按照确认后的顺序重新排列。"
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
                            if (
                                statusFor(proposal.machineId).isOperational &&
                                queueFor(proposal.machineId)
                                    .hasSameQueueOperationState(proposal.originalQueue)
                            ) {
                                updateQueueWithUndo(
                                    proposal.machineId,
                                    "${configuredMachineName(proposal.machineId)} 的队列位置已调整",
                                    feedbackTitle = "队列位置已调整",
                                    feedbackDetail = "组间位置已经交换，队列位置编号也已同步更新。"
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
                            machineName = configuredMachineName(transferTargetMachineId),
                            status = statusFor(transferTargetMachineId),
                            queue = queueFor(transferTargetMachineId),
                            incomingRegistrationCount = 1
                        )
                        RegistrationActions(
                            registration = registration,
                            playerProfileGender = linkedPlayerProfile?.gender ?: registration.gender,
                            playerProfileQqNumber = linkedPlayerProfile?.qqNumber,
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
                                registration.canEnterPlayingPosition,
                            canReportNoShow = queue.canMarkNoShow(registration.key),
                            allowDeferOneRound = queueRuleSettings.allowDeferOneRound,
                            allowTemporaryLeave = queueRuleSettings.allowTemporaryLeave,
                            transferMachineName = configuredMachineName(transferTargetMachineId),
                            transferUnavailableReason = transferUnavailableReason,
                            canEditPlayerProfile = linkedPlayerProfile != null,
                            mode = registrationActionMode,
                            renameDraft = renameDraft,
                            renameAlreadyExists = renameDraft.isNotBlank() &&
                                idAlreadyExists(renameDraft, registration.key),
                            onRenameDraftChange = {
                                renameDraft = limitCodePointLength(it, 18)
                                hostActivity?.recordUserInteraction()
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
                                updateQueue(
                                    machineId = selection.machineId,
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTitle = "暂缓一轮已取消"
                                ) {
                                    it.cancelDeferOneRound(registration.key)
                                }
                                selectedRegistration = null
                            },
                            onCancelTemporaryLeave = {
                                updateQueue(
                                    machineId = selection.machineId,
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTitle = "暂时离开已取消"
                                ) {
                                    it.cancelTemporaryLeave(registration.key)
                                }
                                selectedRegistration = null
                            },
                            onChangePreference = {
                                registrationActionMode = RegistrationActionMode.PREFERENCE
                            },
                            onPreferenceSelected = { preference ->
                                updateQueue(
                                    machineId = selection.machineId,
                                    surfaceHomeFeedback = true
                                ) {
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
                                    updateQueue(
                                        machineId = selection.machineId,
                                        surfaceHomeFeedback = true
                                    ) {
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
                            onCheckIn = {
                                if (
                                    linkedPlayerProfile?.hasValidContact == true &&
                                    linkedPlayerProfile.hasCompleteRequiredDetails
                                ) {
                                    updateQueue(
                                        machineId = selection.machineId,
                                        soundCue = QueueSoundCue.CONFIRM,
                                        publicEventTypeOverride =
                                            PublicQueueEventType.ONLINE_CHECK_IN_COMPLETED,
                                        affectedRegistrationKeysOverride =
                                            listOf(registration.key),
                                        surfaceHomeFeedback = true,
                                        homeFeedbackTitle = "现场签到已完成"
                                    ) {
                                        it.checkIn(registration.key)
                                    }
                                } else {
                                    incompleteCheckInProfileId =
                                        registration.playerProfileId.orEmpty()
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
                                updateQueue(
                                    machineId = selection.machineId,
                                    soundCue = QueueSoundCue.QUEUE_CHANGE,
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTitle = "登记已加入本轮"
                                ) {
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
                                    updateQueue(
                                        machineId = selection.machineId,
                                        soundCue = QueueSoundCue.QUEUE_CHANGE,
                                        classifyMissedOnlineRegistrations = true,
                                        surfaceHomeFeedback = true,
                                        homeFeedbackTitle = "登记已暂缓一轮"
                                    ) {
                                        it.deferOneRound(registration.key)
                                    }
                                    absenceChoiceTarget = null
                                }
                            },
                            onTemporarilyLeave = {
                                if (queueRuleSettings.allowTemporaryLeave) {
                                    updateQueue(
                                        machineId = selection.machineId,
                                        soundCue = QueueSoundCue.QUEUE_CHANGE,
                                        classifyMissedOnlineRegistrations = true,
                                        surfaceHomeFeedback = true,
                                        homeFeedbackTitle = "登记已设为暂时离开"
                                    ) {
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
                            allowCreateFriend = acceptingNewRegistrations,
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
                            onPairExisting = onPairExisting@{ plan ->
                                if (
                                    plan.delayedOtherRegistrations.isNotEmpty() ||
                                    !registrationsHaveSameQueueState(
                                        queueFor(selection.machineId).waiting,
                                        plan.originalWaiting
                                    )
                                ) return@onPairExisting
                                val shouldFinishCreation = stagedFriendPairRegistration == selection
                                val pairCreated = updateQueueAfterOnSiteRegistration(
                                    selection.machineId,
                                    if (shouldFinishCreation) QueueSoundCue.CONFIRM else QueueSoundCue.QUEUE_CHANGE,
                                    advanceWhenPlayingEmpty = shouldFinishCreation
                                ) {
                                    it.applyFriendPair(plan)
                                }
                                if (shouldFinishCreation && pairCreated) {
                                    stagedFriendPairRegistration = null
                                    showNewRegistrationFeedback(
                                        selection.machineId,
                                        selection.registrationKey
                                    )
                                } else if (pairCreated) {
                                    showHomeOperationFeedback(
                                        title = "固定组合已建立",
                                        detail = "“${plan.firstRegistration.displayId}”与“${plan.secondRegistration.displayId}”已组成固定组合。",
                                        contextLabel = configuredMachineName(selection.machineId)
                                    )
                                }
                                friendPairTarget = null
                            },
                            onCreateFriend = { displayId ->
                                val normalizedId = displayId.trim()
                                if (
                                    acceptingNewRegistrations &&
                                    normalizedId.isNotBlank() &&
                                    !idAlreadyExists(normalizedId) &&
                                    queueFor(selection.machineId).waiting.any {
                                        it.key == registration.key
                                    } &&
                                    queueFor(selection.machineId).registrationCount < 20
                                ) {
                                    val shouldFinishCreation = stagedFriendPairRegistration == selection
                                    val friend = Registration(
                                        key = nextKey++,
                                        displayId = normalizedId,
                                        preference = PlayPreference.OPEN_TO_JOIN
                                    )
                                    val pairCreated = updateQueueAfterOnSiteRegistration(
                                        selection.machineId,
                                        if (shouldFinishCreation) QueueSoundCue.CONFIRM else QueueSoundCue.QUEUE_CHANGE,
                                        advanceWhenPlayingEmpty = shouldFinishCreation
                                    ) {
                                        it.createFriendPair(registration.key, friend)
                                    }
                                    if (shouldFinishCreation && pairCreated) {
                                        stagedFriendPairRegistration = null
                                        showNewRegistrationFeedback(
                                            selection.machineId,
                                            selection.registrationKey
                                        )
                                    } else if (pairCreated) {
                                        showHomeOperationFeedback(
                                            title = "固定组合已建立",
                                            detail = "“${registration.displayId}”与“${friend.displayId}”已组成固定组合。",
                                            contextLabel = configuredMachineName(selection.machineId)
                                        )
                                    }
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
                    val sourceQueue = queueFor(machineId)
                    if (sourceQueue.playing.isEmpty()) {
                        LaunchedEffect(machineId, sourceQueue) { finishConfirmation = null }
                        return@let
                    }
                    val finishPlan = RoundPlanner.finishRound(sourceQueue)
                    val endOnlyPlan = RoundPlanner.endRoundOnly(sourceQueue)
                    val removalPlan = RoundPlanner.removeCurrentRoundAndStartNext(sourceQueue)
                    val playingRegistrations = sourceQueue.playing
                    val nextPlayingNotice = nextPlayingChangeMessage(finishPlan.preview)
                    val removalPreview = removalPlan.preview
                    RoundEndConfirmation(
                        machineName = configuredMachineName(machineId),
                        playingPositionLabel = playingPositionName(machineId),
                        playingRegistrationNames = playingRegistrations.joinToString("、") {
                            "“${it.displayId}”"
                        },
                        nextPlayingNotice = nextPlayingNotice,
                        closingGracePeriod = activeClosingGracePeriod,
                        onDismiss = { finishConfirmation = null },
                        onConfirm = {
                            if (queueFor(machineId) == finishPlan.sourceQueue) {
                                val atMillis = System.currentTimeMillis()
                                updateQueueWithUndo(
                                    machineId,
                                    "${configuredMachineName(machineId)} 的本轮已结束",
                                    feedbackTitle = if (
                                        finishPlan.preview?.nextRegistrations.orEmpty().isEmpty()
                                    ) {
                                        "本轮已结束"
                                    } else {
                                        "本轮已结束，下一轮已开始"
                                    },
                                    feedbackDetail = finishPlan.preview?.nextRegistrations
                                        .orEmpty()
                                        .takeIf { it.isNotEmpty() }
                                        ?.joinToString(
                                            prefix = "现在由",
                                            postfix = "进入${playingPositionName(machineId)}。",
                                            separator = "、"
                                        ) { "“${it.displayId}”" }
                                        ?: "本轮已经结束，${playingPositionName(machineId)}目前为空。"
                                ) { currentQueue ->
                                    finishPlan.applyTo(currentQueue, atMillis) ?: currentQueue
                                }
                                finishConfirmation = null
                            }
                        },
                        onEndOnly = {
                            if (queueFor(machineId) == endOnlyPlan.sourceQueue) {
                                val atMillis = System.currentTimeMillis()
                                updateQueueWithUndo(
                                    machineId,
                                    "${configuredMachineName(machineId)} 的本轮已结束",
                                    feedbackTitle = "本轮已结束",
                                    feedbackDetail = "${playingPositionName(machineId)}已经空出，等待顺序保持不变。"
                                ) { currentQueue ->
                                    endOnlyPlan.applyTo(currentQueue, atMillis) ?: currentQueue
                                }
                                finishConfirmation = null
                            }
                        },
                        removalNextPlayingNames = removalPreview
                            ?.nextRegistrations
                            .orEmpty()
                            .joinToString("、") { "“${it.displayId}”" }
                            .takeIf { it.isNotEmpty() },
                        removalNextPlayingNotice = nextPlayingChangeMessage(removalPreview),
                        onRemoveAndStartNext = {
                            if (queueFor(machineId) == removalPlan.sourceQueue) {
                                val atMillis = System.currentTimeMillis()
                                updateQueue(
                                    machineId = machineId,
                                    soundCue = QueueSoundCue.CAUTION,
                                    classifyMissedOnlineRegistrations = true,
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTone = HomeSidePanelFeedbackTone.WARNING,
                                    homeFeedbackTitle = "本轮登记已移除"
                                ) { currentQueue ->
                                    removalPlan.applyTo(currentQueue, atMillis) ?: currentQueue
                                }
                                finishConfirmation = null
                            }
                        }
                    )
                }

                joinClosingWarningRequest?.let { request ->
                    val waitEstimateNowMillis = System.currentTimeMillis()
                    JoinClosingWarningDialog(
                        request = request,
                        machineNames = MachineId.entries.associateWith(::configuredMachineName),
                        closingAtMillis = businessHoursStatus.activeClosingAtMillis,
                        estimatedWaitMinutes = request.lateMachineIds.associateWith { machineId ->
                            estimatedWaitForNewOpenRegistration(
                                queueFor(machineId),
                                waitEstimateNowMillis
                            )
                        },
                        onDismiss = { joinClosingWarningRequest = null },
                        onConfirm = {
                            joinClosingWarningRequest = null
                            if (acceptingNewRegistrations) {
                                closingWarningAcknowledgedMachineIds =
                                    closingWarningAcknowledgedMachineIds + request.lateMachineIds
                                when {
                                    request.requestedMachineId == null ->
                                        continueRegistrationStart(batch = false)
                                    request.continueFromMachineSelection ->
                                        continueSelectedRegistrationMachine(request.requestedMachineId)
                                    statusFor(request.requestedMachineId).isOperational &&
                                        queueFor(request.requestedMachineId).registrationCount < 20 ->
                                        continueRegistrationForMachine(request.requestedMachineId)
                                }
                            }
                        }
                    )
                }

                enterPlayingConfirmation?.let { machineId ->
                    val queue = queueFor(machineId)
                    if (queue.playing.isNotEmpty()) {
                        LaunchedEffect(machineId, queue) { enterPlayingConfirmation = null }
                        return@let
                    }
                    val roundPlan = RoundPlanner.enterPlayingPosition(queue)
                    val preview = roundPlan.preview
                    val notice = nextPlayingChangeMessage(preview)
                    if (preview?.changedByAvailability == true && notice != null) {
                        EnterPlayingConfirmation(
                            playingPositionLabel = playingPositionName(machineId),
                            notice = notice,
                            onDismiss = { enterPlayingConfirmation = null },
                            onConfirm = {
                                if (queueFor(machineId) == roundPlan.sourceQueue) {
                                    val atMillis = System.currentTimeMillis()
                                    updateQueue(
                                        machineId = machineId,
                                        soundCue = QueueSoundCue.QUEUE_CHANGE,
                                        classifyMissedOnlineRegistrations = true,
                                        surfaceHomeFeedback = true
                                    ) { currentQueue ->
                                        roundPlan.applyTo(currentQueue, atMillis) ?: currentQueue
                                    }
                                    enterPlayingConfirmation = null
                                }
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
                        cloudSyncStatus = displayedCloudSyncStatus.takeIf { cloudSyncAvailable },
                        onOpenVersionHistory = {
                            appDetailsVisible = false
                            versionHistoryVisible = true
                        },
                        onDismiss = { appDetailsVisible = false }
                    )
                }

                if (versionHistoryVisible) {
                    VersionHistoryDialog(
                        onDismiss = {
                            versionHistoryVisible = false
                            appDetailsVisible = true
                        }
                    )
                }

                if (editMachineChoiceVisible) {
                    EditMachineChooser(
                        machineA = machineA,
                        machineB = machineB,
                        machineAStatus = machineAStatus,
                        machineBStatus = machineBStatus,
                        machineAName = configuredMachineName(MachineId.A),
                        machineBName = configuredMachineName(MachineId.B),
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
                        machineAName = configuredMachineName(MachineId.A),
                        machineBName = configuredMachineName(MachineId.B),
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
                        machineName = configuredMachineName(machineId),
                        registrationCount = queueFor(machineId).registrationCount,
                        onDismiss = { stopReasonTarget = null },
                        onSelect = { reason, reasonDetail ->
                            reportMachineStopped(machineId, reason, reasonDetail)
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
                        machineName = configuredMachineName(transferTargetMachineId),
                        status = statusFor(transferTargetMachineId),
                        queue = queueFor(transferTargetMachineId),
                        incomingRegistrationCount = selection.registrationKeys.size
                    )
                    PositionActions(
                        selection = selection,
                        queue = queueFor(selection.machineId),
                        transferMachineName = configuredMachineName(transferTargetMachineId),
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
                            val roundPlan = RoundPlanner.enterPlayingPosition(
                                queueFor(selection.machineId)
                            )
                            if (roundPlan.preview?.changedByAvailability == true) {
                                enterPlayingConfirmation = selection.machineId
                            } else {
                                updateQueue(
                                    machineId = selection.machineId,
                                    classifyMissedOnlineRegistrations = true,
                                    surfaceHomeFeedback = true
                                ) { currentQueue ->
                                    roundPlan.applyTo(currentQueue) ?: currentQueue
                                }
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
                            updateQueue(
                                machineId = selection.machineId,
                                surfaceHomeFeedback = true,
                                homeFeedbackTitle = "已撤回等待顺序前端"
                            ) {
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
                                updateQueue(
                                    machineId = selection.machineId,
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTitle = "已撤回等待顺序前端"
                                ) {
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
                    val correctionNotice = availabilityOutcomeMessage(
                        unavailableRegistrations = positions.take(targetIndex.coerceAtLeast(0))
                            .flatten()
                            .filterNot { it.canEnterPlayingPosition },
                        nextRegistrations = positions.getOrNull(targetIndex).orEmpty()
                    )
                    AdvanceToPlayingConfirmation(
                        selectionLabel = selection.label.substringBefore(" · "),
                        playingPositionLabel = playingPositionName(selection.machineId),
                        registrations = positions.getOrNull(targetIndex).orEmpty(),
                        completedWaitingPositionCount = targetIndex.coerceAtLeast(0),
                        availabilityNotice = correctionNotice,
                        enabled = queue.playing.isNotEmpty() && targetIndex > 0,
                        onDismiss = { advanceToPlayingTarget = null },
                        onConfirm = {
                            updateQueueWithUndo(
                                selection.machineId,
                                "${playingPositionName(selection.machineId)} 已校正",
                                feedbackTitle = "游玩位置已校正",
                                feedbackDetail = "已将${selection.label.substringBefore(" · ")}及其之前的队列位置按实际进度完成处理。"
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
                                updateQueue(
                                    machineId = selection.machineId,
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTitle = "固定组合已解除"
                                ) {
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
                    val destinationQueue = queueFor(destinationMachineId)
                    val requestedKeys = request.registrationKeys.toSet()
                    val registrations = sourceQueue.allRegistrations
                        .filter { it.key in requestedKeys }
                    val transferUnavailableReason = when {
                        requestedKeys.isEmpty() || registrations.size != requestedKeys.size ->
                            "待转移的登记已经发生变化，请关闭窗口后重试。"
                        !statusFor(request.sourceMachineId).isOperational ->
                            "${configuredMachineName(request.sourceMachineId)} 已停止使用，暂时不能转出。"
                        sourceQueue.playing.any { it.key in requestedKeys } ->
                            "登记已经进入${playingPositionName(request.sourceMachineId)}，不能转移。"
                        else -> machineTransferUnavailableReason(
                            machineName = configuredMachineName(destinationMachineId),
                            status = statusFor(destinationMachineId),
                            queue = destinationQueue,
                            incomingRegistrationCount = registrations.size
                        )
                    }
                    MachineTransferConfirmation(
                        registrations = registrations,
                        sourceMachineName = configuredMachineName(request.sourceMachineId),
                        destinationMachineName = configuredMachineName(destinationMachineId),
                        sourcePlayingPositionLabel = playingPositionName(request.sourceMachineId),
                        leavingPlayingPosition = sourceQueue.playing
                            .any { it.key in request.registrationKeys },
                        transferUnavailableReason = transferUnavailableReason,
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
                                        setOf(registration.key),
                                        surfaceHomeFeedback = true,
                                        homeFeedbackTone = HomeSidePanelFeedbackTone.WARNING
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
                                    setOf(registration.key),
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTone = HomeSidePanelFeedbackTone.WARNING
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
                                    setOf(registration.key),
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTone = HomeSidePanelFeedbackTone.WARNING
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
                                        targetKeys,
                                        surfaceHomeFeedback = true,
                                        homeFeedbackTone = HomeSidePanelFeedbackTone.WARNING
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
                                    targetKeys,
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTone = HomeSidePanelFeedbackTone.WARNING
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
                                    targetKeys,
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTone = HomeSidePanelFeedbackTone.WARNING
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
                                updateQueue(
                                    machineId = selection.machineId,
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTone = HomeSidePanelFeedbackTone.WARNING,
                                    homeFeedbackTitle = "登记已退出排队"
                                ) {
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
                                updateQueue(
                                    machineId = selection.machineId,
                                    surfaceHomeFeedback = true,
                                    homeFeedbackTone = HomeSidePanelFeedbackTone.WARNING,
                                    homeFeedbackTitle = "这组登记已移除"
                                ) { it.removeAll(targetKeys) }
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

                mobileRegistrationSession?.let { session ->
                    val machineId = selectedMachine
                    if (machineId != null) {
                        MobileRegistrationDialog(
                            session = session,
                            machineName = configuredMachineName(machineId),
                            nowMillis = nowMillis,
                            onDismiss = { mobileRegistrationSession = null },
                            onRefresh = {
                                mobileRegistrationSession = null
                                requestMobileRegistrationSession()
                            }
                        )
                    } else {
                        LaunchedEffect(session.sessionId) {
                            mobileRegistrationSession = null
                        }
                    }
                }

                mobileRegistrationFailureDetail?.let { detail ->
                    MobileRegistrationFailureDialog(
                        detail = detail,
                        retryEnabled = selectedMachine != null &&
                            cloudSyncAvailable &&
                            queueRuleSettings.websiteSyncEnabled &&
                            queueCommandClient.isConfigured &&
                            !mobileRegistrationLoading,
                        onDismiss = { mobileRegistrationFailureDetail = null },
                        onRetry = {
                            mobileRegistrationFailureDetail = null
                            requestMobileRegistrationSession()
                        }
                    )
                }

                botFriendPromptQq?.let { qqNumber ->
                    BotFriendQrDialog(
                        qqNumber = qqNumber,
                        onDismiss = { botFriendPromptQq = null }
                    )
                }

                incompleteCheckInProfileId?.let { profileId ->
                    val profile = playerProfiles.firstOrNull { it.id == profileId }
                    IncompleteCheckInProfileDialog(
                        profile = profile,
                        onDismiss = { incompleteCheckInProfileId = null },
                        onEditProfile = profile?.let {
                            {
                                incompleteCheckInProfileId = null
                                openEditPlayerProfile(it, Screen.HOME)
                            }
                        }
                    )
                }

                if (playerProfileWriteInProgress) {
                    PlayerProfileSavingOverlay()
                }

                playerProfileWriteFailureDetail?.let { detail ->
                    PlayerProfileWriteFailureDialog(
                        detail = detail,
                        onDismiss = { playerProfileWriteFailureDetail = null }
                    )
                }

                if (discardPlayerProfileDraftConfirmationVisible) {
                    RemoveRegistrationConfirmation(
                        title = "放弃未保存的修改？",
                        message = "返回后，这次对玩家资料所做的修改不会保存。",
                        confirmText = "放弃修改",
                        onDismiss = { discardPlayerProfileDraftConfirmationVisible = false },
                        onConfirm = {
                            discardPlayerProfileDraftConfirmationVisible = false
                            screen = playerProfileEditorReturnScreen
                        }
                    )
                }

                if (!queuePersistenceReady || !playerProfilesLoaded) {
                    QueueStateLoadingOverlay()
                } else {
                    pendingQueueRestore?.let { savedState ->
                        QueueRestoreDialog(
                            savedState = savedState,
                            machineAName = configuredMachineName(MachineId.A),
                            machineBName = configuredMachineName(MachineId.B),
                            onRestore = { restorePreviousQueue(savedState) },
                            onStartNew = { startWithNewQueue(savedState) }
                        )
                    }
                }
                inactivityWarningSeconds?.let { remainingSeconds ->
                    InactivityReturnWarningDialog(
                        remainingSeconds = remainingSeconds,
                        onContinue = {
                            hostActivity?.recordUserInteraction()
                            inactivityWarningSeconds = null
                        },
                        onReturnHome = {
                            returnHomeAfterInactivity()
                            hostActivity?.recordUserInteraction()
                            inactivityWarningSeconds = null
                        }
                    )
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
    machineAName: String,
    machineBName: String,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var selectedCategory by remember { mutableStateOf<AuditLogCategory?>(null) }
    var selectedSource by remember { mutableStateOf<AuditLogSource?>(null) }
    val filters: List<Pair<AuditLogCategory?, String>> = listOf(
        null to "全部",
        AuditLogCategory.MACHINE_A to machineAName,
        AuditLogCategory.MACHINE_B to machineBName,
        AuditLogCategory.SYSTEM to "系统",
        AuditLogCategory.PLAYER_PROFILE to "玩家资料"
    )
    val sourceFilters: List<Pair<AuditLogSource?, String>> = listOf(
        null to "全部来源",
        AuditLogSource.ON_SITE_TERMINAL to "现场终端",
        AuditLogSource.QQ_BOT to "QQ Bot",
        AuditLogSource.SYSTEM_AUTOMATIC to "系统自动",
        AuditLogSource.WEBSITE_REMOTE to "网站远程",
        AuditLogSource.MOBILE_DEVICE to "移动设备"
    )
    val displayedLogs = logs.filter { entry ->
        (selectedCategory == null || entry.category == selectedCategory) &&
            (selectedSource == null || entry.source == selectedSource)
    }

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
                        Modifier.height(40.dp).widthIn(min = 112.dp, max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) PrimaryText else CardBackground)
                            .border(
                                1.dp,
                                if (selected) PrimaryText else Separator,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = !selected) { selectedCategory = category }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (selected) Color.White else PrimaryText,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sourceFilters.forEach { (source, label) ->
                    val selected = selectedSource == source
                    Box(
                        Modifier.width(112.dp).height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) SoftBlue else CardBackground)
                            .border(
                                1.dp,
                                if (selected) SystemBlue.copy(alpha = .38f) else Separator,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = !selected) { selectedSource = source },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (selected) SystemBlue else SecondaryText,
                            fontSize = 11.sp,
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
                        AuditLogRow(
                            entry = entry,
                            categoryLabel = auditLogCategoryLabel(
                                entry.category,
                                machineAName,
                                machineBName
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRuleSettingsScreen(
    persistedSettings: QueueRuleSettings,
    cloudSyncAvailable: Boolean,
    onSettingsChange: (QueueRuleSettings) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val hostActivity = context as? MainActivity
    var settings by remember(persistedSettings) { mutableStateOf(persistedSettings) }
    var machineARemarkDraft by remember(persistedSettings.machineARemark) {
        mutableStateOf(persistedSettings.machineARemark)
    }
    var machineBRemarkDraft by remember(persistedSettings.machineBRemark) {
        mutableStateOf(persistedSettings.machineBRemark)
    }
    var queueSyncEndpointDraft by remember(persistedSettings.queueSyncEndpoint) {
        mutableStateOf(persistedSettings.queueSyncEndpoint)
    }
    var queueSyncTokenDraft by remember(persistedSettings.queueSyncToken) {
        mutableStateOf(persistedSettings.queueSyncToken)
    }
    var showTakeoverConfirmation by remember { mutableStateOf(false) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    fun updateDraft(updatedSettings: QueueRuleSettings) {
        settings = updatedSettings
        hostActivity?.recordUserInteraction()
    }
    val normalizedMachineARemark = machineARemarkDraft.trim()
    val normalizedMachineBRemark = machineBRemarkDraft.trim()
    val remarksValid = normalizedMachineARemark.isNotBlank() && normalizedMachineBRemark.isNotBlank()
    val normalizedQueueSyncEndpointDraft = normalizeQueueSyncEndpoint(queueSyncEndpointDraft)
    val normalizedQueueSyncTokenDraft = queueSyncTokenDraft.trim()
    val queueSyncEndpointValid = normalizedQueueSyncEndpointDraft != null
    val queueSyncTokenValid = isValidQueueSyncToken(normalizedQueueSyncTokenDraft)
    val queueConnectionConfigured = queueSyncEndpointValid && queueSyncTokenValid
    val queueConnectionEditable = !settings.websiteSyncEnabled
    val queueConnectionChanged =
        normalizedQueueSyncEndpointDraft != persistedSettings.queueSyncEndpoint ||
            normalizedQueueSyncTokenDraft != persistedSettings.queueSyncToken
    val settingsToSave = settings.copy(
        machineARemark = normalizedMachineARemark,
        machineBRemark = normalizedMachineBRemark,
        queueSyncEndpoint = normalizedQueueSyncEndpointDraft ?: queueSyncEndpointDraft.trim(),
        queueSyncToken = normalizedQueueSyncTokenDraft
    )
    val settingsChanged = settingsToSave != persistedSettings
    val connectionChangeValid = !queueConnectionChanged ||
        (queueSyncEndpointValid && queueSyncTokenValid)
    val settingsValid = remarksValid && connectionChangeValid &&
        (!settings.websiteSyncEnabled || queueConnectionConfigured)
    val requestBack = {
        if (settingsChanged) showDiscardConfirmation = true else onBack()
    }
    BackHandler(onBack = requestBack)

    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppBackButton(requestBack)
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
                if (cloudSyncAvailable) {
                    "调整网站同步、排队规则和首页机台备注。机台 A 与机台 B 是固定标识，不能修改。"
                } else {
                    "调整排队规则和首页机台备注。机台 A 与机台 B 是固定标识，不能修改。"
                },
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(20.dp))
            if (cloudSyncAvailable) {
                MenuSectionHeader("网站连接")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBackground)
                        .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(12.dp))
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            "服务器连接",
                            color = PrimaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (queueConnectionEditable) {
                                "终端版可以连接自建服务器。连接设置仅保存在本机，令牌不会写入日志。"
                            } else {
                                "如需更换服务器，请先关闭网站同步。"
                            },
                            color = SecondaryText,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = queueSyncEndpointDraft,
                            onValueChange = {
                                queueSyncEndpointDraft = it.filterNot(Char::isISOControl)
                                    .take(MAX_QUEUE_SYNC_ENDPOINT_CHARACTERS)
                                hostActivity?.recordUserInteraction()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = queueConnectionEditable,
                            label = { Text("队列 API 地址") },
                            placeholder = { Text("https://example.com") },
                            singleLine = true,
                            isError = queueConnectionEditable && queueSyncEndpointDraft.isNotBlank() &&
                                !queueSyncEndpointValid,
                            supportingText = {
                                Text(
                                    when {
                                        queueSyncEndpointDraft.isBlank() -> "请输入服务器的 HTTPS 地址。"
                                        !queueSyncEndpointValid ->
                                            "地址必须使用 HTTPS，且不能包含账号、参数或片段。"
                                        normalizedQueueSyncEndpointDraft != queueSyncEndpointDraft.trim() ->
                                            "将连接到 $normalizedQueueSyncEndpointDraft"
                                        else -> "可填写站点地址或完整的队列接口地址。"
                                    }
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            shape = RoundedCornerShape(ControlRadius),
                            colors = playerProfileTextFieldColors()
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = queueSyncTokenDraft,
                            onValueChange = {
                                queueSyncTokenDraft = it.filterNot(Char::isISOControl)
                                    .take(MAX_QUEUE_SYNC_TOKEN_CHARACTERS)
                                hostActivity?.recordUserInteraction()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = queueConnectionEditable,
                            label = { Text("终端同步令牌") },
                            placeholder = { Text("至少 $MIN_QUEUE_SYNC_TOKEN_BYTES 个字节") },
                            singleLine = true,
                            isError = queueConnectionEditable && queueSyncTokenDraft.isNotBlank() &&
                                !queueSyncTokenValid,
                            supportingText = {
                                Text(
                                    when {
                                        queueSyncTokenDraft.isBlank() -> "请输入服务器配置的终端同步令牌。"
                                        !queueSyncTokenValid ->
                                            "令牌至少 $MIN_QUEUE_SYNC_TOKEN_BYTES 个字节，最多 $MAX_QUEUE_SYNC_TOKEN_CHARACTERS 个字符。"
                                        else -> "令牌仅保存在本机，并以隐藏形式显示。"
                                    }
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = RoundedCornerShape(ControlRadius),
                            colors = playerProfileTextFieldColors()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "服务器连接与本页其他设置会在页面底部一并保存。",
                            color = TertiaryText,
                            fontSize = 10.sp
                        )
                    }
                    HorizontalDivider(color = Separator.copy(alpha = .72f))
                    QueueRuleSettingRow(
                        title = "网站同步",
                        description = when {
                            !queueConnectionConfigured && settings.websiteSyncEnabled ->
                                "请先关闭网站同步并保存有效的服务器地址与终端同步令牌。"
                            !queueConnectionConfigured ->
                                "请先填写有效的服务器地址与终端同步令牌。"
                            settings.websiteSyncEnabled ->
                                "上传最新队列和玩家资料，并接收服务器待执行的修改。只有玩家允许公开的 QQ 才会显示在网站详情中。"
                            else ->
                                "队列与玩家资料只在本机更新。重新开启后，会同步当前完整数据。"
                        },
                        checked = settings.websiteSyncEnabled,
                        enabled = settings.websiteSyncEnabled || queueConnectionConfigured,
                        onCheckedChange = {
                            updateDraft(
                                settings.copy(
                                    websiteSyncEnabled = it,
                                    oneBotSyncEnabled = settings.oneBotSyncEnabled && it
                                )
                            )
                        }
                    )
                    HorizontalDivider(color = Separator.copy(alpha = .72f))
                    QueueSyncModeSetting(
                        mode = settings.syncMode,
                        enabled = settings.websiteSyncEnabled,
                        onSelect = { mode ->
                            if (mode == QueueSyncMode.TAKEOVER) {
                                showTakeoverConfirmation = true
                            } else {
                                updateDraft(settings.copy(syncMode = mode))
                            }
                        }
                    )
                    HorizontalDivider(color = Separator.copy(alpha = .72f))
                    QueueRuleSettingRow(
                        title = "允许线上登记",
                        description = when {
                            !settings.websiteSyncEnabled ->
                                "网站同步关闭期间不会接收新的线上登记；重新开启后将按照此设置执行。"
                            settings.allowOnlineRegistration ->
                                "允许玩家通过网站和 QQ Bot 创建线上登记；到场后仍需在终端完成签到。"
                            else ->
                                "网站和 QQ Bot 仍可查询队列、管理已有登记，但不能创建新的线上登记。"
                        },
                        checked = settings.allowOnlineRegistration,
                        onCheckedChange = {
                            updateDraft(settings.copy(allowOnlineRegistration = it))
                        }
                    )
                    HorizontalDivider(color = Separator.copy(alpha = .72f))
                    QueueRuleSettingRow(
                        title = "QQ Bot 联动",
                        description = when {
                            !settings.websiteSyncEnabled ->
                                "需要先开启网站同步，QQ Bot 才能读取队列、修改玩家资料和发送通知。"
                            settings.oneBotSyncEnabled ->
                                "允许 QQ Bot 读取队列、修改玩家资料，并发送与玩家有关的通知。"
                            else ->
                                "QQ Bot 无法读取队列或修改资料，也不会补发关闭期间产生的通知。"
                        },
                        checked = settings.oneBotSyncEnabled,
                        enabled = settings.websiteSyncEnabled,
                        onCheckedChange = {
                            updateDraft(settings.copy(oneBotSyncEnabled = it))
                        }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
            MenuSectionHeader("营业时间")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBackground)
                    .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(12.dp))
            ) {
                QueueRuleSettingRow(
                    title = "设置营业时间",
                    description = if (settings.businessHours.enabled) {
                        "闭店后停止接收新登记，并为现有队列保留最多 20 分钟的收尾时间；到开店时间不会自动重新开启。"
                    } else {
                        "不开启时，登记排队不会受到营业时间影响。"
                    },
                    checked = settings.businessHours.enabled,
                    onCheckedChange = {
                        updateDraft(
                            settings.copy(
                                businessHours = settings.businessHours.copy(enabled = it)
                            )
                        )
                    }
                )
                if (settings.businessHours.enabled) {
                    HorizontalDivider(color = Separator.copy(alpha = .72f))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text("默认营业时间", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        BusinessHoursTimeRow(
                            hours = settings.businessHours.defaultHours,
                            onOpeningClick = {
                                showBusinessTimePicker(context, settings.businessHours.defaultHours.openingMinutes) { minutes ->
                                    updateDraft(
                                        settings.copy(
                                            businessHours = settings.businessHours.copy(
                                                defaultHours = settings.businessHours.defaultHours.copy(
                                                    openingMinutes = minutes
                                                )
                                            )
                                        )
                                    )
                                }
                            },
                            onClosingClick = {
                                showBusinessTimePicker(context, settings.businessHours.defaultHours.closingMinutes) { minutes ->
                                    updateDraft(
                                        settings.copy(
                                            businessHours = settings.businessHours.copy(
                                                defaultHours = settings.businessHours.defaultHours.copy(
                                                    closingMinutes = minutes
                                                )
                                            )
                                        )
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            "闭店时间早于开店时间时，按次日闭店计算；两者相同时按全天营业计算。",
                            color = TertiaryText,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                    HorizontalDivider(color = Separator.copy(alpha = .72f))
                    QueueRuleSettingRow(
                        title = "按星期分别设置",
                        description = "为周一至周日分别指定开店和闭店时间。",
                        checked = settings.businessHours.useWeeklySchedule,
                        onCheckedChange = {
                            updateDraft(
                                settings.copy(
                                    businessHours = settings.businessHours.copy(useWeeklySchedule = it)
                                )
                            )
                        }
                    )
                    if (settings.businessHours.useWeeklySchedule) {
                        Column(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DayOfWeek.entries.forEach { day ->
                                val hours = settings.businessHours.hoursFor(day)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        businessDayLabel(day),
                                        color = PrimaryText,
                                        fontSize = 13.sp,
                                        modifier = Modifier.width(58.dp)
                                    )
                                    BusinessTimeButton(
                                        label = "开店 ${formatBusinessTime(hours.openingMinutes)}",
                                        onClick = {
                                            showBusinessTimePicker(context, hours.openingMinutes) { minutes ->
                                                updateDraft(
                                                    settings.copy(
                                                        businessHours = settings.businessHours.copy(
                                                            weeklyHours = settings.businessHours.weeklyHours +
                                                                (day to hours.copy(openingMinutes = minutes))
                                                        )
                                                    )
                                                )
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    BusinessTimeButton(
                                        label = "闭店 ${formatBusinessTime(hours.closingMinutes)}",
                                        onClick = {
                                            showBusinessTimePicker(context, hours.closingMinutes) { minutes ->
                                                updateDraft(
                                                    settings.copy(
                                                        businessHours = settings.businessHours.copy(
                                                            weeklyHours = settings.businessHours.weeklyHours +
                                                                (day to hours.copy(closingMinutes = minutes))
                                                        )
                                                    )
                                                )
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
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
                        updateDraft(settings.copy(allowDeferOneRound = it))
                    }
                )
                HorizontalDivider(color = Separator.copy(alpha = .72f))
                QueueRuleSettingRow(
                    title = "允许暂时离开",
                    description = "允许玩家在返回前持续轮空，并在返回后手动恢复。",
                    checked = settings.allowTemporaryLeave,
                    onCheckedChange = {
                        updateDraft(settings.copy(allowTemporaryLeave = it))
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
                        onValueChange = {
                            machineARemarkDraft = limitMachineRemarkLength(it)
                            hostActivity?.recordUserInteraction()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    MachineRemarkField(
                        machineLabel = "机台 B",
                        value = machineBRemarkDraft,
                        onValueChange = {
                            machineBRemarkDraft = limitMachineRemarkLength(it)
                            hostActivity?.recordUserInteraction()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            PrimaryButton(
                text = "保存设置",
                onClick = {
                    onSettingsChange(settingsToSave)
                    Toast.makeText(
                        context,
                        panguSpacing("应用设置已保存。"),
                        Toast.LENGTH_SHORT
                    ).show()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = settingsChanged && settingsValid,
                disabledReason = when {
                    !settingsChanged -> "当前没有未保存的设置。"
                    !remarksValid -> "请填写两台机台的备注。"
                    !connectionChangeValid -> "请填写有效的队列 API 地址和终端同步令牌。"
                    else -> "开启网站同步前，请先填写有效的服务器连接。"
                }
            )
            if (settingsChanged && !settingsValid) {
                Spacer(Modifier.height(7.dp))
                Text(
                    when {
                        !remarksValid -> "请填写两台机台的备注。"
                        !connectionChangeValid -> "请填写有效的队列 API 地址和终端同步令牌。"
                        else -> "开启网站同步前，请先填写有效的服务器连接。"
                    },
                    color = Destructive,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
    if (showTakeoverConfirmation) {
        RemoveRegistrationConfirmation(
            title = "接管网站同步？",
            message = "接管后，网站和 QQ Bot 将以本机队列作为正式数据。请仅在确实需要用本机替代当前同步终端时继续；后台重试不会自行完成接管。",
            confirmText = "确认接管",
            onDismiss = { showTakeoverConfirmation = false },
            onConfirm = {
                updateDraft(settings.copy(syncMode = QueueSyncMode.TAKEOVER))
                showTakeoverConfirmation = false
            }
        )
    }
    if (showDiscardConfirmation) {
        RemoveRegistrationConfirmation(
            title = "放弃未保存的设置？",
            message = "返回后，这次对营业时间、同步设置和排队规则所做的修改不会生效。",
            confirmText = "放弃修改",
            onDismiss = { showDiscardConfirmation = false },
            onConfirm = {
                showDiscardConfirmation = false
                onBack()
            }
        )
    }
}

@Composable
private fun QueueSyncModeSetting(
    mode: QueueSyncMode,
    enabled: Boolean,
    onSelect: (QueueSyncMode) -> Unit
) {
    val description = when (mode) {
        QueueSyncMode.UNSPECIFIED ->
            "沿用当前同步身份；发现其他终端的数据时不会自动接管。需要切换终端时，请明确选择测试或接管。"
        QueueSyncMode.TEST ->
            "网站和 QQ Bot 完整使用本机数据，并标记为测试数据；主终端恢复联网后，正式队列会重新生效。"
        QueueSyncMode.TAKEOVER ->
            "网站和 QQ Bot 将本机队列作为正式数据；仅适合确实更换现场同步终端时使用。"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            "同步方式",
            color = if (enabled) PrimaryText else TertiaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            color = if (enabled) SecondaryText else TertiaryText,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(ControlRadius))
                .background(PageBackground).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                QueueSyncMode.TEST to "测试同步",
                QueueSyncMode.TAKEOVER to "接管同步"
            ).forEach { (candidate, label) ->
                val selected = mode == candidate
                Box(
                    Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(7.dp))
                        .background(if (selected) CardBackground else Color.Transparent)
                        .border(
                            1.dp,
                            if (selected) SystemBlue.copy(alpha = .34f) else Color.Transparent,
                            RoundedCornerShape(7.dp)
                        )
                        .clickable(enabled = enabled && !selected) { onSelect(candidate) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = when {
                            !enabled -> TertiaryText
                            selected -> SystemBlue
                            else -> SecondaryText
                        },
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun queueSyncModeLabel(mode: QueueSyncMode): String = when (mode) {
    QueueSyncMode.UNSPECIFIED -> "沿用当前身份"
    QueueSyncMode.TEST -> "测试同步"
    QueueSyncMode.TAKEOVER -> "接管同步"
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
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = {
                if (enabled) onCheckedChange(it)
                else showDisabledActionReason(context, title, description)
            }
        )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) PrimaryText else TertiaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                color = if (enabled) SecondaryText else TertiaryText,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
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
private fun BusinessHoursTimeRow(
    hours: DailyBusinessHours,
    onOpeningClick: () -> Unit,
    onClosingClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BusinessTimeButton(
            label = "开店 ${formatBusinessTime(hours.openingMinutes)}",
            onClick = onOpeningClick,
            modifier = Modifier.weight(1f)
        )
        BusinessTimeButton(
            label = "闭店 ${formatBusinessTime(hours.closingMinutes)}",
            onClick = onClosingClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BusinessTimeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.height(42.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(PageBackground)
            .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = PrimaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        Text("›", color = TertiaryText, fontSize = 17.sp)
    }
}

private fun showBusinessTimePicker(
    context: android.content.Context,
    initialMinutes: Int,
    onSelected: (Int) -> Unit
) {
    val normalized = initialMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
    TimePickerDialog(
        context,
        { _, hourOfDay, minute -> onSelected(hourOfDay * 60 + minute) },
        normalized / 60,
        normalized % 60,
        true
    ).show()
}

private fun businessDayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

@Composable
private fun AuditLogRow(entry: AuditLogEntry, categoryLabel: String) {
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
            Modifier.height(28.dp).widthIn(min = 72.dp, max = 180.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(categoryBackground)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                categoryLabel,
                color = categoryForeground,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    color = PrimaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    auditLogSourceLabel(entry.source),
                    color = SecondaryText,
                    fontSize = 9.sp,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp))
                        .background(PageBackground)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
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

private fun auditLogCategoryLabel(
    category: AuditLogCategory,
    machineAName: String,
    machineBName: String
): String = when (category) {
    AuditLogCategory.MACHINE_A -> machineAName
    AuditLogCategory.MACHINE_B -> machineBName
    AuditLogCategory.SYSTEM -> "系统"
    AuditLogCategory.PLAYER_PROFILE -> "玩家资料"
}

private fun auditLogSourceLabel(source: AuditLogSource): String = when (source) {
    AuditLogSource.ON_SITE_TERMINAL -> "现场终端"
    AuditLogSource.QQ_BOT -> "QQ Bot"
    AuditLogSource.SYSTEM_AUTOMATIC -> "系统自动"
    AuditLogSource.WEBSITE_REMOTE -> "网站远程"
    AuditLogSource.MOBILE_DEVICE -> "移动设备"
}

private fun remoteQueueOperationFeedbackTitle(operation: RemoteQueueOperation): String =
    when (operation) {
        RemoteQueueOperation.JOIN_QUEUE -> "线上登记已加入"
        RemoteQueueOperation.DEFER_ONE_ROUND -> "登记已暂缓一轮"
        RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND -> "暂缓一轮已取消"
        RemoteQueueOperation.TEMPORARILY_LEAVE -> "登记已设为暂时离开"
        RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE -> "暂时离开已取消"
        RemoteQueueOperation.TRANSFER_MACHINE -> "登记已切换机台"
        RemoteQueueOperation.CHANGE_PLAY_PREFERENCE -> "游玩偏好已修改"
        RemoteQueueOperation.LEAVE_QUEUE -> "登记已退出排队"
    }

private fun remoteQueueOperationSourceLabel(source: RemoteQueueOperationSource): String =
    when (source) {
        RemoteQueueOperationSource.QQ_BOT -> "QQ Bot"
        RemoteQueueOperationSource.WEBSITE_REMOTE -> "网站远程"
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
    acceptingNewRegistrations: Boolean,
    businessHoursStatus: BusinessHoursStatus,
    closingGracePeriod: Boolean,
    cloudSyncStatus: QueueCloudSyncStatus?,
    queueUndoAction: QueueUndoAction?,
    onUndoQueueAction: () -> Unit,
    onDismissQueueUndo: () -> Unit,
    homeSidePanelFeedback: HomeSidePanelFeedback?,
    onDismissHomeFeedback: () -> Unit,
    inlineReorderSession: ReorderSession?,
    inlineReorderResetToken: Int,
    positionReorderResetToken: Int,
    queueScrollResetToken: Int,
    highlightedRegistration: NewRegistrationHighlight?,
    onInlineReorderCancel: () -> Unit,
    onInlineReorderProposal: (MachineId, MachineQueue, List<Registration>, Int) -> Unit,
    onEnableRegistration: () -> Unit,
    onJoin: () -> Unit,
    onJoinMachine: (MachineId) -> Unit,
    onBatch: () -> Unit,
    onCompletedRegistrationClick: (MachineId, Int) -> Unit,
    onDismissCompletedRegistration: () -> Unit,
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
    val completedRegistration = highlightedRegistration?.let { highlight ->
        val queue = if (highlight.machineId == MachineId.A) machineA else machineB
        val registration = queue.allRegistrations
            .firstOrNull { it.key == highlight.registrationKey }
        val positionLabel = queue.registrationPositionName(
            highlight.machineId,
            highlight.registrationKey
        )
        if (registration != null && positionLabel != null) {
            HomeSidePanelRegistration(
                requestId = highlight.requestId,
                machineId = highlight.machineId,
                registrationKey = highlight.registrationKey,
                displayId = registration.displayId,
                machineName = machineName(
                    highlight.machineId,
                    if (highlight.machineId == MachineId.A) machineARemark else machineBRemark
                ),
                positionLabel = positionLabel,
                isPlaying = queue.playing.any { it.key == highlight.registrationKey },
                requiresOnSiteCheckIn = registration.requiresOnSiteCheckIn
            )
        } else {
            null
        }
    }
    var cloudSyncInfoVisible by remember { mutableStateOf(false) }
    val hasTransientSidePanelContent = completedRegistration != null ||
        homeSidePanelFeedback != null || queueUndoAction != null
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
            AppHeader(
                nowMillis = nowMillis,
                registrationOpen = registrationOpen,
                outsideBusinessHours = businessHoursStatus.outsideBusinessHours,
                totalRegistrationCount = machineA.registrationCount + machineB.registrationCount,
                cloudSyncStatus = cloudSyncStatus,
                onCloudSyncClick = { cloudSyncInfoVisible = true },
                onMore = onMore
            )
            Spacer(Modifier.height(11.dp))
            HorizontalDivider(color = Separator.copy(alpha = .72f))
            Spacer(Modifier.height(12.dp))
            if (!registrationOpen && !hasStoppedMachine && !hasTransientSidePanelContent) {
                ClosedHome(
                    outsideBusinessHours = businessHoursStatus.outsideBusinessHours,
                    onEnableRegistration = onEnableRegistration
                )
            } else if (
                isEmpty &&
                !hasStoppedMachine &&
                !businessHoursStatus.closingSoon &&
                !hasTransientSidePanelContent
            ) {
                EmptyHome(
                    outsideBusinessHours = businessHoursStatus.outsideBusinessHours,
                    closingGracePeriod = closingGracePeriod,
                    acceptingNewRegistrations = acceptingNewRegistrations,
                    onJoin = onJoin,
                    onBatch = onBatch
                )
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1.9f).fillMaxHeight()) {
                        MachineLane(
                            machineId = MachineId.A,
                            remark = machineARemark,
                            queue = machineA,
                            status = machineAStatus,
                            registrationOpen = registrationOpen,
                            acceptingNewRegistrations = acceptingNewRegistrations,
                            businessHoursClosingSoon = businessHoursStatus.closingSoon,
                            businessHoursClosingGrace = closingGracePeriod,
                            nowMillis = nowMillis,
                            inlineReorderSession = inlineReorderSession?.takeIf { it.machineId == MachineId.A },
                            inlineReorderResetToken = inlineReorderResetToken,
                            positionReorderResetToken = positionReorderResetToken,
                            queueScrollResetToken = queueScrollResetToken,
                            highlightedRegistrationKey = highlightedRegistration
                                ?.takeIf { it.machineId == MachineId.A }
                                ?.registrationKey,
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
                            acceptingNewRegistrations = acceptingNewRegistrations,
                            businessHoursClosingSoon = businessHoursStatus.closingSoon,
                            businessHoursClosingGrace = closingGracePeriod,
                            nowMillis = nowMillis,
                            inlineReorderSession = inlineReorderSession?.takeIf { it.machineId == MachineId.B },
                            inlineReorderResetToken = inlineReorderResetToken,
                            positionReorderResetToken = positionReorderResetToken,
                            queueScrollResetToken = queueScrollResetToken,
                            highlightedRegistrationKey = highlightedRegistration
                                ?.takeIf { it.machineId == MachineId.B }
                                ?.registrationKey,
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
                    HomeSidePanel(
                        machineA = machineA,
                        machineB = machineB,
                        machineAStatus = machineAStatus,
                        machineBStatus = machineBStatus,
                        machineAName = machineName(MachineId.A, machineARemark),
                        machineBName = machineName(MachineId.B, machineBRemark),
                        nowMillis = nowMillis,
                        registrationOpen = registrationOpen,
                        acceptingNewRegistrations = acceptingNewRegistrations,
                        closingGracePeriod = closingGracePeriod,
                        completedRegistration = completedRegistration,
                        operationFeedback = homeSidePanelFeedback,
                        queueUndoAction = queueUndoAction,
                        onCompletedRegistrationClick = onCompletedRegistrationClick,
                        onDismissCompletedRegistration = onDismissCompletedRegistration,
                        onDismissOperationFeedback = onDismissHomeFeedback,
                        onUndoQueueAction = onUndoQueueAction,
                        onDismissQueueUndo = onDismissQueueUndo,
                        onJoin = onJoin,
                        onBatch = onBatch,
                        modifier = Modifier.weight(.72f).fillMaxHeight()
                    )
                }
            }
        }
        if (cloudSyncInfoVisible && cloudSyncStatus != null) {
            CloudSyncInfoDialog(
                status = cloudSyncStatus,
                onDismiss = { cloudSyncInfoVisible = false }
            )
        }
    }
}

@Composable
private fun AppHeader(
    nowMillis: Long,
    registrationOpen: Boolean,
    outsideBusinessHours: Boolean,
    totalRegistrationCount: Int,
    cloudSyncStatus: QueueCloudSyncStatus?,
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
        if (cloudSyncStatus != null) {
            Spacer(Modifier.width(16.dp))
            CloudSyncIndicator(cloudSyncStatus, onCloudSyncClick)
        }
        AnimatedVisibility(
            visible = !registrationOpen || outsideBusinessHours,
            enter = fadeIn(tween(180)) + expandHorizontally(tween(220), expandFrom = Alignment.End),
            exit = fadeOut(tween(140)) + shrinkHorizontally(tween(180), shrinkTowards = Alignment.End)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(18.dp))
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (outsideBusinessHours) Color(0xFFFF3B30) else Color(0xFFFF9500))
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    if (outsideBusinessHours) "不在营业时间" else "未使用登记排队",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
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
        Modifier.heightIn(min = 38.dp).clip(RoundedCornerShape(8.dp))
            .background(statusColor.copy(alpha = .09f))
            .clickable(onClick = onClick)
            .animateContentSize(tween(180))
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
private fun EmptyHome(
    outsideBusinessHours: Boolean,
    closingGracePeriod: Boolean,
    acceptingNewRegistrations: Boolean,
    onJoin: () -> Unit,
    onBatch: () -> Unit
) {
    val joinDisabledReason = if (closingGracePeriod) {
        "闭店收尾期间不再接收新的排队登记。"
    } else {
        "当前暂不接收新的排队登记。"
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("目前没有登记", color = PrimaryText, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        VisuallyCenteredSentence(
            if (outsideBusinessHours) {
                if (closingGracePeriod) {
                    "今日营业时间已结束，登记排队正在完成最后的收尾。"
                } else {
                    "当前不在营业时间，登记排队已由现场手动开启。"
                }
            } else {
                "两台机台都没有正在等待的玩家。"
            },
            SecondaryText,
            16.sp
        )
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(
                "加入排队",
                onJoin,
                Modifier.width(260.dp),
                enabled = acceptingNewRegistrations,
                disabledReason = joinDisabledReason
            )
            SecondaryButton(
                "批量创建登记",
                onBatch,
                Modifier.width(190.dp),
                enabled = acceptingNewRegistrations,
                disabledReason = joinDisabledReason
            )
        }
        Spacer(Modifier.height(12.dp))
        VisuallyCenteredSentence("创建你的登记并加入排队。", SecondaryText, 13.sp)
    }
}

@Composable
private fun ClosedHome(
    outsideBusinessHours: Boolean,
    onEnableRegistration: () -> Unit
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (outsideBusinessHours) "不在营业时间" else "请在现场自然排队",
            color = PrimaryText,
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        VisuallyCenteredSentence(
            if (outsideBusinessHours) {
                "现在已过闭店时间；你仍可以手动开启登记排队。"
            } else {
                "当前未使用登记排队，请按照现场顺序依次游玩。"
            },
            SecondaryText,
            16.sp
        )
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
    acceptingNewRegistrations: Boolean,
    businessHoursClosingSoon: Boolean,
    businessHoursClosingGrace: Boolean,
    nowMillis: Long,
    inlineReorderSession: ReorderSession?,
    inlineReorderResetToken: Int,
    positionReorderResetToken: Int,
    queueScrollResetToken: Int,
    highlightedRegistrationKey: Int?,
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
                        !status.isOperational ->
                            "已停止使用 · ${machineStopReasonLabel(status.stopReason, status.stopReasonDetail)}"
                        else -> "当前空闲"
                    },
                    color = SecondaryText,
                    fontSize = 12.sp
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
                        enabled = queue.firstAvailableWaitingPositionIndex() != null,
                        disabledReason = "当前没有可以进入游玩位置的登记。"
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
                        "停止原因：${machineStopReasonLabel(status.stopReason, status.stopReasonDetail)}。现有 ${queue.registrationCount} 份登记、游玩位置和等待顺序均已保留。"
                    } else {
                        "停止原因：${machineStopReasonLabel(status.stopReason, status.stopReasonDetail)}。当前没有排队登记。"
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
                    // A status, preference, nickname, or pair change can regroup or relabel
                    // a position even when the registration keys remain in the same order.
                    val waitingPositionSignature = displayedQueue.waiting
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

                    LaunchedEffect(queueScrollResetToken) {
                        if (queueListState.firstVisibleItemIndex > 0 ||
                            queueListState.firstVisibleItemScrollOffset > 0
                        ) {
                            queueListState.animateScrollToItem(0)
                        }
                    }

                    LaunchedEffect(highlightedRegistrationKey, waitingPositionSignature) {
                        val key = highlightedRegistrationKey ?: return@LaunchedEffect
                        val targetIndex = when {
                            displayedQueue.playing.any { it.key == key } -> 0
                            else -> waitingPositions.indexOfFirst { registrations ->
                                registrations.any { it.key == key }
                            }.takeIf { it >= 0 }?.plus(1)
                        } ?: return@LaunchedEffect
                        delay(180L)
                        queueListState.animateScrollToItem(targetIndex)
                    }

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
                            overtimeWarning = playingOvertime &&
                                !businessHoursClosingSoon &&
                                !businessHoursClosingGrace,
                            warningTitle = when {
                                businessHoursClosingGrace -> "今日营业时间已结束"
                                businessHoursClosingSoon -> "将在 30 分钟内闭店"
                                else -> null
                            },
                            warningDescription = when {
                                businessHoursClosingGrace ->
                                    "不再接收新登记。现有队列处理完毕后将关闭，最迟保留 20 分钟。"
                                businessHoursClosingSoon && playingOvertime ->
                                    "本轮已超过 20 分钟，请确认机台是否仍在正常游玩，并留意后续队列安排。"
                                businessHoursClosingSoon -> "请留意后续队列安排。"
                                else -> null
                            },
                            highlightedRegistrationKey = highlightedRegistrationKey,
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
                                highlightedRegistrationKey = highlightedRegistrationKey,
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
                                    val validDrop = originalIndex != null &&
                                        destinationIndex >= 0 &&
                                        destinationIndex != originalIndex

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
                            acceptingNewRegistrations = acceptingNewRegistrations,
                            closingGracePeriod = businessHoursClosingGrace,
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
    warningTitle: String? = null,
    warningDescription: String? = null,
    highlightedRegistrationKey: Int? = null,
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
    val hasWarning = overtimeWarning || warningTitle != null
    val warningWidth = if (hasWarning) 178.dp else 0.dp
    val contentSpacing = if (hasWarning && registrations.isNotEmpty()) 7.dp else 0.dp
    val playingHeaderWidth = if (isPlaying && registrations.isNotEmpty()) {
        val labelWidth = with(density) {
            textMeasurer.measure(
                text = AnnotatedString(panguSpacing(label)),
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
            else -> PositionBackground
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
                                warningTitle = warningTitle,
                                warningDescription = warningDescription,
                                highlightedRegistrationKey = null,
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
                fontSize = 11.sp,
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
        if (registrations.isEmpty() && !hasWarning) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无登记", color = TertiaryText, fontSize = 13.sp)
            }
        } else {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasWarning) {
                    Column(
                        Modifier.width(178.dp).height(QueueRegistrationTileHeight)
                            .clip(RoundedCornerShape(11.dp))
                            .background(Color(0xFFFFF4E5)).padding(11.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            warningTitle ?: "本轮已超过 20 分钟",
                            color = Color(0xFF9A5B00),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            warningDescription ?: "请确认机台是否仍在正常游玩。",
                            color = Color(0xFF9A5B00),
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
                registrations.forEachIndexed { index, registration ->
                    RegistrationTile(
                        registration = registration,
                        isPlaying = isPlaying,
                        highlighted = registration.key == highlightedRegistrationKey,
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
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(ControlRadius)
    val absenceStatus = registrationAbsenceStatusLabel(
        registration,
        includeSkippedTurns = false
    )
    val noShowStatus = registration.noShowCount.takeIf { it > 0 }?.let { "未到场 $it 次" }
    val pendingCheckIn = registration.requiresOnSiteCheckIn
    val visibleStatus = when {
        pendingCheckIn -> "线上登记 · 待签到"
        absenceStatus != null -> absenceStatus
        else -> noShowStatus
    }
    val showNoShowStatus = !pendingCheckIn && absenceStatus == null && noShowStatus != null
    val normalBackground = when {
        pendingCheckIn -> OnlineRegistrationStatusBackground
        isPlaying -> PlayingRegistrationBackground
        showNoShowStatus -> NoShowStatusBackground
        else -> CardBackground
    }
    val normalBorder = when {
        pendingCheckIn -> OnlineRegistrationStatusColor.copy(alpha = .20f)
        isPlaying -> SystemBlue.copy(alpha = .08f)
        showNoShowStatus -> NoShowStatusColor.copy(alpha = .16f)
        else -> Color.Transparent
    }
    val tileBackground by animateColorAsState(
        if (highlighted) Color(0xFFEAF8EF) else normalBackground,
        tween(if (highlighted) 180 else 420),
        label = "新登记高亮背景"
    )
    val tileBorder by animateColorAsState(
        if (highlighted) Color(0xFF248A4B).copy(alpha = .58f) else normalBorder,
        tween(if (highlighted) 180 else 420),
        label = "新登记高亮边框"
    )
    val tileScale by animateFloatAsState(
        if (highlighted) 1.025f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "新登记高亮缩放"
    )
    Column(
        modifier.height(QueueRegistrationTileHeight)
            .graphicsLayer {
                scaleX = tileScale
                scaleY = tileScale
            }
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(tileBackground)
            .border(1.dp, tileBorder, shape)
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
                overflow = TextOverflow.Ellipsis,
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
        Text(
            visibleStatus ?: when {
                registration.fixedPartnerKey != null -> "固定组合"
                registration.preference == PlayPreference.SOLO -> "单人游玩"
                else -> "允许他人加入"
            },
            color = when {
                pendingCheckIn -> OnlineRegistrationStatusColor
                absenceStatus != null -> AbsenceStatusColor
                showNoShowStatus -> NoShowStatusColor
                else -> SecondaryText
            },
            fontSize = 10.sp,
            fontWeight = if (visibleStatus != null) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun QueueJoinPosition(
    machineId: MachineId,
    registrationOpen: Boolean,
    acceptingNewRegistrations: Boolean,
    closingGracePeriod: Boolean,
    hasCapacity: Boolean,
    onClick: () -> Unit
) {
    val enabled = acceptingNewRegistrations && hasCapacity
    val disabledReason = when {
        closingGracePeriod -> "闭店收尾期间不再接收新的排队登记。"
        !registrationOpen -> "当前未启用登记排队，请按照现场顺序排队。"
        !hasCapacity -> "机台 ${machineId.name} 已达到 20 人上限。"
        else -> "当前暂不接收新的排队登记。"
    }
    val context = LocalContext.current
    Column(
        Modifier.width(148.dp).height(QueueViewportHeight).clip(RoundedCornerShape(CardRadius))
            .background(if (enabled) SoftBlue.copy(alpha = .62f) else Color(0xFFF3F3F5))
            .border(
                1.dp,
                if (enabled) SystemBlue.copy(alpha = .22f) else Separator.copy(alpha = .65f),
                RoundedCornerShape(CardRadius)
            )
            .clickable {
                if (enabled) onClick()
                else showDisabledActionReason(context, "加入机台 ${machineId.name}", disabledReason)
            }
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
                closingGracePeriod -> "闭店收尾中"
                !registrationOpen -> "未启用登记"
                else -> "机台 ${machineId.name} 已满"
            },
            color = if (enabled) SystemBlue else TertiaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private sealed interface HomeSidePanelContent {
    data object Join : HomeSidePanelContent
    data class Registration(val value: HomeSidePanelRegistration) : HomeSidePanelContent
    data class Operation(val value: HomeSidePanelFeedback) : HomeSidePanelContent
    data class Undo(val value: QueueUndoAction) : HomeSidePanelContent
}

@Composable
private fun HomeSidePanel(
    machineA: MachineQueue,
    machineB: MachineQueue,
    machineAStatus: MachineStatus,
    machineBStatus: MachineStatus,
    machineAName: String,
    machineBName: String,
    nowMillis: Long,
    registrationOpen: Boolean,
    acceptingNewRegistrations: Boolean,
    closingGracePeriod: Boolean,
    completedRegistration: HomeSidePanelRegistration?,
    operationFeedback: HomeSidePanelFeedback?,
    queueUndoAction: QueueUndoAction?,
    onCompletedRegistrationClick: (MachineId, Int) -> Unit,
    onDismissCompletedRegistration: () -> Unit,
    onDismissOperationFeedback: () -> Unit,
    onUndoQueueAction: () -> Unit,
    onDismissQueueUndo: () -> Unit,
    onJoin: () -> Unit,
    onBatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val machineAJoinable = machineAStatus.isOperational && machineA.registrationCount < 20
    val machineBJoinable = machineBStatus.isOperational && machineB.registrationCount < 20
    val joiningEnabled = acceptingNewRegistrations && (machineAJoinable || machineBJoinable)
    val joiningDisabledReason = when {
        closingGracePeriod -> "闭店收尾期间不再接收新的排队登记。"
        !registrationOpen -> "当前未启用登记排队，请按照现场顺序排队。"
        !machineAStatus.isOperational && !machineBStatus.isOperational -> "两台机台均已停止使用。"
        !machineAJoinable && !machineBJoinable -> "两台机台的登记均已达到上限。"
        else -> "当前暂不接收新的排队登记。"
    }
    val panelContent = when {
        queueUndoAction != null -> HomeSidePanelContent.Undo(queueUndoAction)
        completedRegistration != null -> HomeSidePanelContent.Registration(completedRegistration)
        operationFeedback != null -> HomeSidePanelContent.Operation(operationFeedback)
        else -> HomeSidePanelContent.Join
    }
    val panelShape = RoundedCornerShape(CardRadius)
    Surface(
        modifier = modifier,
        shape = panelShape,
        color = CardBackground,
        border = BorderStroke(1.dp, Separator.copy(alpha = .92f))
    ) {
        AnimatedContent(
            targetState = panelContent,
            modifier = Modifier.fillMaxSize().clip(panelShape),
            transitionSpec = {
                if (targetState !is HomeSidePanelContent.Join) {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(280)) { width -> width / 5 })
                        .togetherWith(
                            fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { width -> -width / 5 }
                        )
                } else {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(280)) { width -> -width / 5 })
                        .togetherWith(
                            fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { width -> width / 5 }
                        )
                }
            },
            contentAlignment = Alignment.Center,
            contentKey = { content ->
                when (content) {
                    HomeSidePanelContent.Join -> "join"
                    is HomeSidePanelContent.Registration -> "registration-${content.value.requestId}"
                    is HomeSidePanelContent.Operation -> "operation-${content.value.id}"
                    is HomeSidePanelContent.Undo -> "undo-${content.value.id}"
                }
            },
            label = "首页右侧动态区域"
        ) { content ->
            when (content) {
                is HomeSidePanelContent.Registration -> RegistrationCompletedPanel(
                    completion = content.value,
                    joiningEnabled = joiningEnabled,
                    onViewRegistration = {
                        onCompletedRegistrationClick(
                            content.value.machineId,
                            content.value.registrationKey
                        )
                    },
                    onContinueJoining = onJoin,
                    onDismiss = onDismissCompletedRegistration,
                    modifier = Modifier.fillMaxSize()
                )

                is HomeSidePanelContent.Operation -> HomeOperationFeedbackPanel(
                    feedback = content.value,
                    onDismiss = onDismissOperationFeedback,
                    modifier = Modifier.fillMaxSize()
                )

                is HomeSidePanelContent.Undo -> HomeUndoFeedbackPanel(
                    action = content.value,
                    onUndo = onUndoQueueAction,
                    onDismiss = onDismissQueueUndo,
                    modifier = Modifier.fillMaxSize()
                )

                HomeSidePanelContent.Join -> JoinPanelContent(
                    machineA = machineA,
                    machineB = machineB,
                    machineAStatus = machineAStatus,
                    machineBStatus = machineBStatus,
                    machineAName = machineAName,
                    machineBName = machineBName,
                    nowMillis = nowMillis,
                    registrationOpen = registrationOpen,
                    acceptingNewRegistrations = acceptingNewRegistrations,
                    closingGracePeriod = closingGracePeriod,
                    joiningEnabled = joiningEnabled,
                    joiningDisabledReason = joiningDisabledReason,
                    onJoin = onJoin,
                    onBatch = onBatch,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun RegistrationCompletedPanel(
    completion: HomeSidePanelRegistration,
    joiningEnabled: Boolean,
    onViewRegistration: () -> Unit,
    onContinueJoining: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleDisplayId = queueDisplayId(completion.displayId)
    val statusText = when {
        completion.requiresOnSiteCheckIn -> "线上登记 · 等待现场签到"
        completion.isPlaying -> "已经进入游玩位置"
        else -> "登记已加入等待顺序"
    }
    val summary = when {
        completion.requiresOnSiteCheckIn ->
            "“$visibleDisplayId”已加入${completion.machineName}，完成现场签到后会转换为正常登记。"
        completion.isPlaying ->
            "“$visibleDisplayId”已经进入${completion.machineName}的游玩位置。"
        else ->
            "“$visibleDisplayId”已加入${completion.machineName}的等待顺序。"
    }
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF248A4B)),
                contentAlignment = Alignment.Center
            ) {
                StraightCheckMark(Modifier.size(19.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (completion.requiresOnSiteCheckIn) "线上登记已加入" else "登记完成",
                color = PrimaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(summary, color = SecondaryText, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(20.dp))
            Text(
                completion.machineName,
                color = TertiaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                completion.positionLabel,
                color = PrimaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(5.dp))
            Text(
                statusText,
                color = Color(0xFF248A4B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(22.dp))
            SecondaryButton(
                "查看${completion.positionLabel}",
                onViewRegistration,
                Modifier.fillMaxWidth()
            )
            if (joiningEnabled) {
                Spacer(Modifier.height(2.dp))
                CancelAction("继续添加登记", onContinueJoining)
            }
            if (completion.requiresOnSiteCheckIn) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "请在 30 分钟内使用现场终端签到；如果更早轮到，也必须在进入游玩位置前完成签到。",
                    color = TertiaryText,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        }
        HomeSidePanelCloseButton(onDismiss, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun HomeOperationFeedbackPanel(
    feedback: HomeSidePanelFeedback,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = when (feedback.tone) {
        HomeSidePanelFeedbackTone.SUCCESS -> Color(0xFF248A4B)
        HomeSidePanelFeedbackTone.INFO -> SystemBlue
        HomeSidePanelFeedbackTone.WARNING -> NoShowStatusColor
    }
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            HomeFeedbackStatusIcon(feedback.tone, accentColor)
            Spacer(Modifier.height(14.dp))
            Text(
                feedback.title,
                color = PrimaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 28.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                feedback.detail,
                color = SecondaryText,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            feedback.contextLabel?.let { contextLabel ->
                Spacer(Modifier.height(18.dp))
                Text(
                    contextLabel,
                    color = accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 16.sp
                )
            }
        }
        HomeSidePanelCloseButton(onDismiss, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun HomeUndoFeedbackPanel(
    action: QueueUndoAction,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tone = action.feedbackTone
    val accentColor = if (tone == HomeSidePanelFeedbackTone.SUCCESS) {
        Color(0xFF248A4B)
    } else {
        NoShowStatusColor
    }
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            HomeFeedbackStatusIcon(tone, accentColor)
            Spacer(Modifier.height(14.dp))
            Text(
                action.feedbackTitle,
                color = PrimaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 28.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                action.feedbackDetail,
                color = SecondaryText,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(18.dp))
            Text(
                action.contextLabel,
                color = TertiaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "可在 10 秒内撤销；关闭提示后将无法撤销",
                color = SystemBlue,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(22.dp))
            SecondaryButton(
                "撤销本次操作",
                onUndo,
                Modifier.fillMaxWidth()
            )
        }
        HomeSidePanelCloseButton(onDismiss, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun HomeFeedbackStatusIcon(
    tone: HomeSidePanelFeedbackTone,
    accentColor: Color
) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(accentColor),
        contentAlignment = Alignment.Center
    ) {
        when (tone) {
            HomeSidePanelFeedbackTone.SUCCESS -> StraightCheckMark(Modifier.size(19.dp))
            HomeSidePanelFeedbackTone.INFO -> Text(
                "i",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            HomeSidePanelFeedbackTone.WARNING -> Text(
                "!",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun HomeSidePanelCloseButton(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onDismiss,
        modifier = modifier.padding(6.dp).size(48.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "关闭提示",
            tint = TertiaryText,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun JoinPanelContent(
    machineA: MachineQueue,
    machineB: MachineQueue,
    machineAStatus: MachineStatus,
    machineBStatus: MachineStatus,
    machineAName: String,
    machineBName: String,
    nowMillis: Long,
    registrationOpen: Boolean,
    acceptingNewRegistrations: Boolean,
    closingGracePeriod: Boolean,
    joiningEnabled: Boolean,
    joiningDisabledReason: String,
    onJoin: () -> Unit,
    onBatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val machineAJoinable = machineAStatus.isOperational && machineA.registrationCount < 20
    val machineBJoinable = machineBStatus.isOperational && machineB.registrationCount < 20
    Column(
        modifier.padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("加入排队", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                closingGracePeriod -> "今日营业时间已结束，收尾期间不再接收新的排队登记。"
                !registrationOpen -> "当前未使用登记排队，请在现场自然排队。"
                !machineAStatus.isOperational && !machineBStatus.isOperational -> "两台机台均已停止使用。"
                !machineAJoinable && !machineBJoinable -> "两台机台目前都无法接受新登记。"
                else -> "创建你的登记并加入排队。"
            },
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        if (acceptingNewRegistrations) {
            Spacer(Modifier.height(17.dp))
            Text("新登记预计等待", color = TertiaryText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(7.dp))
            JoinEstimateRow(machineAName, machineA, machineAStatus, nowMillis)
            Spacer(Modifier.height(5.dp))
            JoinEstimateRow(machineBName, machineB, machineBStatus, nowMillis)
        }
        Spacer(Modifier.height(22.dp))
        PrimaryButton(
            "开始",
            onJoin,
            Modifier.fillMaxWidth(),
            enabled = joiningEnabled,
            disabledReason = joiningDisabledReason
        )
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            "批量创建登记",
            onBatch,
            Modifier.fillMaxWidth(),
            enabled = joiningEnabled,
            disabledReason = joiningDisabledReason
        )
        Spacer(Modifier.height(20.dp))
        Text("每位玩家只应保有一份有效登记。批量功能用于现场录入多名实际玩家。", color = TertiaryText, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun JoinEstimateRow(
    machineName: String,
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
        Text(machineName, color = SecondaryText, fontSize = 12.sp)
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
    machineAName: String,
    machineBName: String,
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
                machineAName,
                machineA,
                machineAStatus,
                { onSelect(MachineId.A) },
                Modifier.weight(1f)
            )
            MachineChoice(
                machineBName,
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
    val unavailableReason = if (!status.isOperational) {
        "机台已停止使用：${machineStopReasonLabel(status.stopReason, status.stopReasonDetail)}。"
    } else {
        "机台已有 20 份登记，暂时不能新增。"
    }
    val context = LocalContext.current
    Column(
        modifier.height(154.dp).clip(RoundedCornerShape(CardRadius)).background(CardBackground)
            .border(1.dp, Separator.copy(alpha = .82f), RoundedCornerShape(CardRadius))
            .clickable {
                if (available) onClick()
                else showDisabledActionReason(context, name, unavailableReason)
            }.padding(20.dp)
    ) {
        Text(name, color = if (available) PrimaryText else TertiaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (!status.isOperational) {
                "机台已停止使用：${machineStopReasonLabel(status.stopReason, status.stopReasonDetail)}。"
            }
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
    mobileRegistrationEnabled: Boolean,
    mobileRegistrationLoading: Boolean,
    onMobileRegistration: () -> Unit,
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
                title = "使用玩家资料库",
                description = "从本机保存的玩家资料中选择，并沿用常用设置。",
                selected = false,
                enabled = true,
                onClick = onPlayerLibrary,
                modifier = Modifier.weight(1f)
            )
            OptionCard(
                title = "使用移动设备登记",
                description = if (mobileRegistrationLoading) {
                    "正在创建本次登记二维码…"
                } else {
                    "扫码后，在移动设备上选择或新建玩家资料。"
                },
                selected = false,
                enabled = mobileRegistrationEnabled && !mobileRegistrationLoading,
                badge = if (mobileRegistrationEnabled) null else "需要网站同步",
                disabledReason = if (mobileRegistrationLoading) {
                    "登记二维码正在创建，请稍候。"
                } else {
                    "请先在应用设置中配置并开启网站同步。"
                },
                onClick = onMobileRegistration,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SmallActionButton(
                text = "创建临时登记",
                onClick = onTemporarySelect,
                primary = temporarySelected
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "不保存玩家资料，只输入本次排队使用的昵称。",
                color = SecondaryText,
                fontSize = 11.sp
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
                enabled = draftId.isNotBlank() && !idAlreadyRegistered,
                disabledReason = if (draftId.isBlank()) {
                    "请先输入或生成一个玩家昵称。"
                } else {
                    "当前队列中已经有相同昵称的登记，请更换昵称。"
                }
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
    listState: LazyListState,
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
    BackHandler(onBack = onBack)
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
                    label = { Text("搜索玩家") },
                    placeholder = { Text("输入昵称或 QQ 号") },
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
                            if (profiles.isEmpty()) "新建后，之后可以更快地加入排队。" else "请尝试其他昵称或 QQ 号。",
                            color = SecondaryText,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                val columnCount = 4
                val profileRows = displayedProfiles.chunked(columnCount)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
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
            .background(DisabledBackground).padding(3.dp)
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
                if (profile.hasValidContact && profile.hasCompleteRequiredDetails) {
                    profilePreferenceLabel(profile.defaultPreference)
                } else {
                    "需要补全玩家资料"
                },
                color = if (
                    profile.hasValidContact && profile.hasCompleteRequiredDetails
                ) SecondaryText else Color(0xFF9A5B00),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = panguSpacing("编辑“${profile.nickname}”的玩家资料"),
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
    qqAlreadyExists: Boolean,
    qqVisibility: QqVisibility,
    notificationPreferences: QueueNotificationPreferences,
    botQqNumber: String?,
    editingExisting: Boolean,
    saving: Boolean,
    onNicknameChange: (String) -> Unit,
    onGenderChange: (PlayerGender) -> Unit,
    onDefaultPreferenceChange: (ProfilePlayPreference) -> Unit,
    onQqNumberChange: (String) -> Unit,
    onQqVisibilityChange: (QqVisibility) -> Unit,
    onNotificationPreferencesChange: (QueueNotificationPreferences) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val requestBack = {
        if (saving) {
            showDisabledActionReason(context, "返回", "玩家资料正在保存，请稍候。")
        } else {
            onBack()
        }
    }
    var nicknameFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = nickname,
                selection = if (editingExisting) {
                    TextRange(0, nickname.length)
                } else {
                    TextRange(nickname.length)
                }
            )
        )
    }
    LaunchedEffect(nickname) {
        if (nicknameFieldValue.text != nickname) {
            nicknameFieldValue = TextFieldValue(
                text = nickname,
                selection = TextRange(nickname.length)
            )
        }
    }
    val normalizedQqNumber = normalizeOptionalContact(qqNumber)
    val qqSyntaxValid = normalizedQqNumber != null && isValidQqNumber(normalizedQqNumber)
    val contactValid = qqSyntaxValid && !qqAlreadyExists
    val contactMessage = when {
        normalizedQqNumber == null -> "请输入 QQ 号。"
        !qqSyntaxValid -> "QQ 号应为 5 至 12 位数字。"
        qqAlreadyExists -> "这个 QQ 号已经用于另一份玩家资料。"
        else -> "QQ 号用于现场联系，并可用于接收排队提醒。"
    }
    WizardPage(
        step = if (editingExisting) "编辑资料" else "新建资料",
        title = if (editingExisting) "编辑玩家资料" else "新建玩家资料",
        subtitle = if (editingExisting) {
            "修改会保存到玩家资料库；已经创建的登记不会因此改变游玩偏好。请填写一个可用的 QQ 号。"
        } else {
            "这些资料会保存在玩家资料库中，加入排队时再进行确认。请填写一个可用的 QQ 号。"
        },
        onBack = requestBack
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
                    value = nicknameFieldValue,
                    onValueChange = { value ->
                        nicknameFieldValue = value
                        onNicknameChange(value.text)
                    },
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
                Text("QQ 联系方式", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (BuildConfig.CLOUD_SYNC_AVAILABLE) {
                        "QQ 号会与玩家资料绑定，用于 QQ Bot 识别玩家和发送相关提醒。是否允许网站显示，可以在下方单独设置。"
                    } else {
                        "QQ 号会与玩家资料绑定，并仅保存在本机；本地版不会连接 QQ Bot。"
                    },
                    color = SecondaryText,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(
                    value = qqNumber,
                    onValueChange = onQqNumberChange,
                    label = { Text("QQ 号") },
                    placeholder = { Text("5 至 12 位数字") },
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
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius))
                .background(CardBackground)
                .border(1.dp, Separator.copy(alpha = .72f), RoundedCornerShape(CardRadius))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    "QQ 显示范围",
                    color = PrimaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "终端登记详情始终可以查看 QQ；网站只会按照这里的选择显示。",
                    color = SecondaryText,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            HorizontalDivider(color = Separator.copy(alpha = .72f))
            Row(Modifier.fillMaxWidth()) {
                ProfilePrivacyChoice(
                    title = "仅终端显示",
                    description = "网站队列详情不会公开 QQ。",
                    selected = qqVisibility == QqVisibility.TERMINAL_ONLY,
                    onClick = { onQqVisibilityChange(QqVisibility.TERMINAL_ONLY) },
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.width(1.dp).height(66.dp).background(Separator.copy(alpha = .72f)))
                ProfilePrivacyChoice(
                    title = "允许网站显示",
                    description = "网站登记详情可以显示 QQ，便于现场联系。",
                    selected = qqVisibility == QqVisibility.PUBLIC_WEBSITE,
                    onClick = { onQqVisibilityChange(QqVisibility.PUBLIC_WEBSITE) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius))
                .background(CardBackground)
                .border(1.dp, Separator.copy(alpha = .72f), RoundedCornerShape(CardRadius))
        ) {
            ProfileNotificationToggle(
                title = "排队通知",
                description = if (botQqNumber != null) {
                    "需要添加 QQ Bot（$botQqNumber）为好友，才能接收主动私信通知。"
                } else {
                    "需要添加现场 QQ Bot 为好友，才能接收主动私信通知。Bot QQ 会在同步后显示。"
                },
                checked = notificationPreferences.enabled,
                onCheckedChange = { enabled ->
                    onNotificationPreferencesChange(
                        notificationPreferences.copy(enabled = enabled)
                    )
                }
            )
            HorizontalDivider(color = Separator.copy(alpha = .72f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    ProfileNotificationToggle(
                        title = "队列状态变化",
                        description = "加入、退出、切换机台、顺序和本次偏好变化。",
                        checked = notificationPreferences.queueChanges,
                        enabled = notificationPreferences.enabled,
                        onCheckedChange = { checked ->
                            onNotificationPreferencesChange(
                                notificationPreferences.copy(queueChanges = checked)
                            )
                        }
                    )
                    HorizontalDivider(color = Separator.copy(alpha = .58f))
                    ProfileNotificationToggle(
                        title = "线上登记与签到",
                        description = "线上登记创建、签到及未签到移除结果。",
                        checked = notificationPreferences.onlineCheckIn,
                        enabled = notificationPreferences.enabled,
                        onCheckedChange = { checked ->
                            onNotificationPreferencesChange(
                                notificationPreferences.copy(onlineCheckIn = checked)
                            )
                        }
                    )
                    HorizontalDivider(color = Separator.copy(alpha = .58f))
                    ProfileNotificationToggle(
                        title = "暂缓一轮、暂时离开和未到场",
                        description = "相关个人状态及系统处理结果。",
                        checked = notificationPreferences.absence,
                        enabled = notificationPreferences.enabled,
                        onCheckedChange = { checked ->
                            onNotificationPreferencesChange(
                                notificationPreferences.copy(absence = checked)
                            )
                        }
                    )
                }
                Box(Modifier.width(1.dp).height(216.dp).background(Separator.copy(alpha = .72f)))
                Column(Modifier.weight(1f)) {
                    ProfileNotificationToggle(
                        title = "游玩位置变化",
                        description = "进入、离开游玩位置及正常轮换。",
                        checked = notificationPreferences.playingPosition,
                        enabled = notificationPreferences.enabled,
                        onCheckedChange = { checked ->
                            onNotificationPreferencesChange(
                                notificationPreferences.copy(playingPosition = checked)
                            )
                        }
                    )
                    HorizontalDivider(color = Separator.copy(alpha = .58f))
                    ProfileNotificationToggle(
                        title = "机台及营业状态",
                        description = "所在机台停止、恢复和登记营业状态变化。",
                        checked = notificationPreferences.machineStatus,
                        enabled = notificationPreferences.enabled,
                        onCheckedChange = { checked ->
                            onNotificationPreferencesChange(
                                notificationPreferences.copy(machineStatus = checked)
                            )
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            when {
                saving -> "正在保存…"
                editingExisting -> "保存玩家资料"
                else -> "完成新建"
            },
            onSave,
            Modifier.fillMaxWidth(),
            enabled = !saving && nickname.isNotBlank() && !nicknameAlreadyExists && contactValid,
            disabledReason = when {
                saving -> "玩家资料正在保存，请稍候。"
                nickname.isBlank() -> "请填写玩家昵称。"
                nicknameAlreadyExists -> "当前已有相同昵称的玩家资料或登记，请更换昵称。"
                else -> contactMessage
            }
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
            Text(description, color = SecondaryText, fontSize = 10.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) StraightCheckMark(Modifier.size(14.dp), color = SystemBlue)
        }
    }
}

@Composable
private fun ProfilePrivacyChoice(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.heightIn(min = 66.dp)
            .background(if (selected) SoftBlue.copy(alpha = .62f) else Color.Transparent)
            .clickable(enabled = !selected, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(description, color = SecondaryText, fontSize = 10.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) StraightCheckMark(Modifier.size(14.dp), color = SystemBlue)
        }
    }
}

@Composable
private fun ProfileNotificationToggle(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = {
                    if (enabled) onCheckedChange(it)
                    else showDisabledActionReason(
                        context,
                        title,
                        "请先开启排队通知总开关。"
                    )
                }
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) PrimaryText else TertiaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                color = if (enabled) SecondaryText else TertiaryText,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
        Spacer(Modifier.width(14.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
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
    if (!profile.hasValidContact || !profile.hasCompleteRequiredDetails) {
        IncompletePlayerProfileScreen(
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
            enabled = !alreadyRegistered && machineAvailable && (!asksEveryTime || selectedPreference != null),
            disabledReason = when {
                alreadyRegistered -> "这名玩家已经有一份有效登记，不能重复加入。"
                !machineAvailable -> "$machineLabel 目前无法接收新的登记。"
                else -> "请先选择本次游玩偏好。"
            }
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
    if (!profile.hasValidContact || !profile.hasCompleteRequiredDetails) {
        IncompletePlayerProfileScreen(
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
            enabled = !alreadyRegistered && machineAvailable,
            disabledReason = if (alreadyRegistered) {
                "这份玩家资料已经关联到另一份有效登记。"
            } else {
                "当前机台已停止使用，暂时不能认领登记。"
            }
        )
    }
}

@Composable
private fun IncompletePlayerProfileScreen(
    profile: PlayerProfile,
    continuation: String,
    onEditProfile: () -> Unit,
    onBack: () -> Unit
) {
    WizardPage(
        step = "资料待补充",
        title = "需要补全玩家资料",
        subtitle = "“${profile.nickname}”尚未确认新版资料设置。请先补全并保存，再继续$continuation。",
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
                Text(
                    if (!profile.hasValidContact) {
                        "需要填写有效的 QQ 号并确认通知设置。"
                    } else {
                        "需要确认 QQ 显示范围和排队通知设置。"
                    },
                    color = Destructive,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("前往补全玩家资料", onEditProfile, Modifier.fillMaxWidth())
        Spacer(Modifier.height(9.dp))
        SecondaryButton("返回玩家资料库", onBack, Modifier.fillMaxWidth())
    }
}

private fun profileContactSummary(profile: PlayerProfile): String =
    profile.normalizedQqNumber()?.let { "QQ：$it" }.orEmpty()

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
        PrimaryButton(
            "完成并加入排队",
            onComplete,
            Modifier.fillMaxWidth(),
            enabled = selected != null,
            disabledReason = "请先选择本次游玩偏好。"
        )
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
            enabled = amountValue != null && amountValue in 1..maximum,
            disabledReason = when {
                amountValue == null -> "请输入有效的登记数量。"
                amountValue < 1 -> "至少需要创建 1 份登记。"
                else -> "当前机台最多还可以创建 $maximum 份登记。"
            }
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
                "使用移动设备",
                "移动设备暂不支持认领已有登记。",
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
    BackHandler(onBack = onBack)
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
    badge: String? = null,
    disabledReason: String? = null
) {
    val context = LocalContext.current
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
            .clickable {
                if (enabled) onClick()
                else showDisabledActionReason(context, title, disabledReason ?: description)
            }.padding(18.dp)
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
    allowCreateFriend: Boolean,
    idAlreadyExists: (String) -> Boolean,
    onGenerateFriendId: () -> String,
    onDismiss: () -> Unit,
    onPairExisting: (FriendPairPlan) -> Unit,
    onCreateFriend: (String) -> Unit
) {
    val hostActivity = LocalContext.current as? MainActivity
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
                            !allowCreateFriend -> "闭店收尾期间不再接收新的排队登记。"
                            queue.registrationCount >= 20 -> "当前机台已达到 20 人上限，无法继续创建登记。"
                            else -> "创建一份临时登记，并让你们在登记顺序末端组成固定组合。"
                        },
                        {
                            friendConsentConfirmed = false
                            step = FriendPairStep.CREATE_FRIEND
                        },
                        enabled = allowCreateFriend && isWaiting && queue.registrationCount < 20
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
                val planIsCurrent = plan?.let {
                    registrationsHaveSameQueueState(queue.waiting, it.originalWaiting)
                } == true
                val delayedOthers = plan?.delayedOtherRegistrations.orEmpty()
                val movedBack = plan?.movedBackRegistrations.orEmpty()
                Text(
                    when {
                        plan == null -> "无法确认固定组合"
                        !planIsCurrent -> "队列已经发生变化"
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
                    !planIsCurrent -> Text(
                        "等待顺序或登记状态已经改变。为避免覆盖新的队列状态，请返回后重新选择朋友。",
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
                if (plan != null && planIsCurrent && delayedOthers.isEmpty()) {
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
                        enabled = friendConsentConfirmed,
                        disabledReason = "请先确认两位玩家都同意组成固定组合。"
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
                        {
                            friendIdDraft = limitCodePointLength(it, 18)
                            hostActivity?.recordUserInteraction()
                        },
                        friendIdAlreadyExists,
                        Modifier.weight(1f),
                        label = "朋友的昵称",
                        placeholder = "输入昵称或生成随机昵称"
                    )
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton(
                        "生成随机昵称",
                        {
                            friendIdDraft = onGenerateFriendId()
                            hostActivity?.recordUserInteraction()
                        },
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
                    enabled = friendIdDraft.isNotBlank() &&
                        allowCreateFriend &&
                        !friendIdAlreadyExists &&
                        queue.registrationCount < 20 &&
                        friendConsentConfirmed,
                    disabledReason = when {
                        friendIdDraft.isBlank() -> "请先填写朋友的登记昵称。"
                        !allowCreateFriend -> "当前状态不能再创建朋友登记。"
                        friendIdAlreadyExists -> "当前队列中已经有相同昵称的登记。"
                        queue.registrationCount >= 20 -> "当前机台已有 20 份登记，不能继续新增。"
                        else -> "请先确认两位玩家都同意组成固定组合。"
                    }
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
    onCheckIn: () -> Unit,
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
                    enabled = renameDraft.isNotBlank() && !renameAlreadyExists,
                    disabledReason = if (renameDraft.isBlank()) {
                        "请先填写新的登记昵称。"
                    } else {
                        "当前队列中已经有相同昵称的登记。"
                    }
                )
            }

            RegistrationActionMode.ACTIONS -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        registration.displayId,
                        color = PrimaryText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Clip,
                        lineHeight = 28.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    if (!registration.requiresOnSiteCheckIn) {
                        IconButton(onClick = onRename, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "编辑登记昵称",
                                tint = SystemBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (registration.requiresOnSiteCheckIn) {
                        DetailPill(
                            text = "线上登记 · 待签到",
                            color = OnlineRegistrationStatusColor,
                            backgroundColor = OnlineRegistrationStatusBackground
                        )
                    }
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
                val qqNumber = normalizeOptionalContact(playerProfileQqNumber)
                if (registration.playerProfileId != null && qqNumber != null) {
                    Spacer(Modifier.height(7.dp))
                    DetailPill("QQ：$qqNumber")
                }
                Spacer(Modifier.height(14.dp))
                MetadataRow("创建时间", formatRegistrationTime(registration.createdAtMillis))
                MetadataRow(
                    "上次游玩",
                    registration.lastPlayedAtMillis?.let(::formatRegistrationTime) ?: "尚未游玩"
                )
                if (registration.requiresOnSiteCheckIn) {
                    MetadataRow("签到时限", "创建线上登记后 30 分钟内")
                }
                if (registration.noShowCount > 0) {
                    MetadataRow("未到场记录", "${registration.noShowCount} 次")
                    MetadataRow(
                        "上次未到场处理",
                        if (registration.lastNoShowActionWasDefer) "暂缓一轮" else "移至队尾"
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (registration.requiresOnSiteCheckIn) {
                    Text(
                        "请在创建线上登记后的 30 分钟内完成现场签到。超过 30 分钟，或轮到进入游玩位置时仍未签到，这份登记会自动退出排队。",
                        color = OnlineRegistrationStatusColor,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(OnlineRegistrationStatusBackground)
                            .padding(horizontal = 13.dp, vertical = 10.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    MenuSectionHeader("到场与退出")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        MenuActionButton(
                            MenuAction(
                                "已到场",
                                "完成签到后，这份登记会保持当前顺序，并开始参与后续游玩位置分配。请在创建登记后的 30 分钟内操作。",
                                onCheckIn,
                                accented = true,
                                accentColor = OnlineRegistrationStatusColor,
                                accentBackgroundColor = OnlineRegistrationStatusBackground
                            ),
                            Modifier.weight(1f)
                        )
                        MenuActionButton(
                            MenuAction(
                                "退出排队",
                                "移除这份线上登记；继续游玩时需要重新加入排队。",
                                onExit,
                                destructive = true
                            ),
                            Modifier.weight(1f)
                        )
                    }
                } else {
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
                                "修改昵称、性别、QQ 号或资料库默认偏好；不会改变本次登记的游玩偏好。",
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
    playingRegistrationNames: String,
    nextPlayingNotice: String?,
    closingGracePeriod: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEndOnly: () -> Unit,
    removalNextPlayingNames: String?,
    removalNextPlayingNotice: String?,
    onRemoveAndStartNext: () -> Unit
) {
    var confirmEndOnly by remember { mutableStateOf(false) }
    var confirmRemoveRegistrations by remember { mutableStateOf(false) }
    ModalSurface(onDismiss, width = 480.dp) {
        if (confirmRemoveRegistrations) {
                Text(
                    "移除本轮玩家的登记并开始下一轮？",
                    color = PrimaryText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append("$playingRegistrationNames 的登记会从$machineName 队列中移除，不会回到队尾。")
                        if (removalNextPlayingNames != null) {
                            append("随后，$removalNextPlayingNames 将进入$playingPositionLabel 并开始下一轮。")
                        } else {
                            append("当前没有可以进入下一轮的登记，$playingPositionLabel 将保持空缺。")
                        }
                        append("被移除的玩家之后仍要游玩时，需要重新加入排队。")
                    },
                    color = SecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                if (removalNextPlayingNotice != null) {
                    Spacer(Modifier.height(11.dp))
                    Text(
                        removalNextPlayingNotice,
                        color = AbsenceStatusColor,
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(AbsenceStatusBackground)
                            .padding(horizontal = 13.dp, vertical = 11.dp)
                    )
                }
                Spacer(Modifier.height(18.dp))
                DestructiveButton(
                    "确认移除并开始下一轮",
                    onRemoveAndStartNext,
                    Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                CancelAction { confirmRemoveRegistrations = false }
        } else if (confirmEndOnly && !closingGracePeriod) {
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
                    if (closingGracePeriod) {
                        "闭店收尾期间，请确认如何结束当前玩家的排队登记。"
                    } else {
                        "本轮玩家的登记会回到队尾。请选择是否立即开始下一轮。"
                    },
                    color = SecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                if (closingGracePeriod) {
                    Spacer(Modifier.height(11.dp))
                    Text(
                        "闭店收尾期间，需要让现有队列逐步结束，因此只能移除本轮玩家的登记并开始下一轮。其他处理方式暂时不可用。",
                        color = Color(0xFF9A5B00),
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFFFF4E5))
                            .padding(horizontal = 13.dp, vertical = 11.dp)
                    )
                }
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
                if (closingGracePeriod) {
                    DestructiveButton(
                        "移除本轮玩家的登记并开始下一轮",
                        { confirmRemoveRegistrations = true },
                        Modifier.fillMaxWidth()
                    )
                } else {
                    PrimaryButton(
                        "确认结束本轮并开始下一轮",
                        onConfirm,
                        Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(9.dp))
                    DestructiveButton(
                        "移除本轮玩家的登记并开始下一轮",
                        { confirmRemoveRegistrations = true },
                        Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(9.dp))
                    SecondaryButton(
                        "仅结束本轮",
                        { confirmEndOnly = true },
                        Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                CancelAction(onDismiss)
        }
    }
}

@Composable
private fun JoinClosingWarningDialog(
    request: JoinClosingWarningRequest,
    machineNames: Map<MachineId, String>,
    closingAtMillis: Long?,
    estimatedWaitMinutes: Map<MachineId, Long?>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val closingTime = closingAtMillis?.let { timestamp ->
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))
    } ?: "今日闭店时间"
    val requestedMachine = request.requestedMachineId
    val description = if (requestedMachine != null) {
        val estimate = estimatedWaitMinutes[requestedMachine]
        val estimateText = estimate?.let { "约 $it 分钟后" } ?: "按照当前队列估算"
        val alternativeMachines = request.joinableMachineIds
            .filterNot { it in request.lateMachineIds }
            .joinToString("、") { machineNames.getValue(it) }
        val alternativeText = alternativeMachines.takeIf { it.isNotEmpty() }?.let {
            "也可以取消并改选$it；按照当前队列估算，它更可能在闭店前轮到。"
        }.orEmpty()
        "加入${machineNames.getValue(requestedMachine)}后，${estimateText}才能游玩，可能晚于 $closingTime。" +
            alternativeText +
            "现场进度仍可能变化，是否继续创建登记？"
    } else {
        val lateMachineNames = request.lateMachineIds.joinToString("、") {
            machineNames.getValue(it)
        }
        "$lateMachineNames 当前的预计等待时间都会超过 $closingTime。这份登记可能无法在闭店前游玩，是否仍要继续选择机台？"
    }
    ModalSurface(onDismiss, width = 480.dp) {
        Text(
            "可能无法在闭店前游玩",
            color = PrimaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(description, color = SecondaryText, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("仍然继续", onConfirm, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CancelAction(onDismiss)
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
    transferUnavailableReason: String?,
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
        if (transferUnavailableReason != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                transferUnavailableReason,
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
            enabled = transferUnavailableReason == null,
            disabledReason = transferUnavailableReason
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
    val hasPendingCheckIn = registrations.any { it.requiresOnSiteCheckIn }
    val showsRoundEndShortcut = !selection.isPlayingPosition &&
        selection.waitingPositionIndex == firstAvailablePositionIndex &&
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
        targetPosition.all { it.canEnterPlayingPosition }
    val playActions = buildList {
        when {
            selection.isPlayingPosition && registrations.isNotEmpty() -> add(
                MenuAction(
                    "本轮结束",
                    "结束当前游玩，并选择登记与下一轮的处理方式。",
                    onFinishRound
                )
            )
            selection.isPlayingPosition -> add(
                MenuAction(
                    "进入$playingPositionLabel",
                    "将第一个可以进入游玩位置的队列位置移入$playingPositionLabel。",
                    onEnterPlaying,
                    enabled = firstAvailablePositionIndex != null
                )
            )
        }
        if (showsRoundEndShortcut) {
            add(
                MenuAction(
                    "本轮结束",
                    "结束$playingPositionLabel 中的本轮游玩，并选择本轮登记与下一轮的处理方式。此位置中的登记不会被视为本轮玩家。",
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
        if (registrations.isNotEmpty() && !selection.isPlayingPosition && !hasPendingCheckIn) {
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
        if (isFixedPair && !selection.isPlayingPosition && !hasPendingCheckIn) {
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
                    val absenceStatus = registrationAbsenceStatusLabel(
                        registration,
                        includeSkippedTurns = false
                    )
                    val noShowStatus = registration.noShowCount.takeIf { it > 0 }
                        ?.let { "未到场 $it 次" }
                    val pendingCheckIn = registration.requiresOnSiteCheckIn
                    val visibleStatus = when {
                        pendingCheckIn -> "线上登记 · 待签到"
                        absenceStatus != null -> absenceStatus
                        noShowStatus != null -> noShowStatus
                        else -> playPreferenceLabel(registration)
                    }
                    val showNoShowStatus = !pendingCheckIn && absenceStatus == null && noShowStatus != null
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    pendingCheckIn -> OnlineRegistrationStatusBackground
                                    showNoShowStatus -> NoShowStatusBackground
                                    else -> PageBackground
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    pendingCheckIn -> OnlineRegistrationStatusColor.copy(alpha = .20f)
                                    showNoShowStatus -> NoShowStatusColor.copy(alpha = .18f)
                                    else -> Separator.copy(alpha = .8f)
                                },
                                RoundedCornerShape(12.dp)
                            )
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
                                visibleStatus,
                                color = when {
                                    pendingCheckIn -> OnlineRegistrationStatusColor
                                    absenceStatus != null -> AbsenceStatusColor
                                    showNoShowStatus -> NoShowStatusColor
                                    else -> SecondaryText
                                },
                                fontSize = 10.sp,
                                fontWeight = if (pendingCheckIn || absenceStatus != null || showNoShowStatus) {
                                    FontWeight.Medium
                                } else {
                                    FontWeight.Normal
                                },
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
        if (registrations.isNotEmpty() && !hasPendingCheckIn) {
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
    availabilityNotice: String?,
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
                "确认后，系统会补记队列已经推进 $completedRoundCount 轮：$playingPositionLabel，以及此位置之前的 $completedWaitingPositionCount 个等待位置。能够正常进入游玩位置的登记会按已经完成游玩处理；处于特殊状态的登记会按各自规则处理。",
                color = PrimaryText,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFFF4E5)).padding(horizontal = 13.dp, vertical = 11.dp)
            )
            if (availabilityNotice != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    availabilityNotice,
                    color = AbsenceStatusColor,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(AbsenceStatusBackground)
                        .padding(horizontal = 13.dp, vertical = 11.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "已完成游玩的登记会按实际游玩顺序回到等待顺序末端，$names 会进入$playingPositionLabel。此操作会同时改变多个等待位置，请只在现场顺序确实已经推进到这里时使用。",
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 19.sp
            )
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            "确认并补记轮次",
            onConfirm,
            Modifier.fillMaxWidth(),
            enabled = enabled,
            disabledReason = "队列状态已经发生变化，请关闭后重新选择目标位置。"
        )
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
            enabled = registrations.size == 2,
            disabledReason = "固定组合状态已经发生变化，请关闭后重新打开登记。"
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
                                "下一次轮到$subject 时，整组不会进入游玩位置。两份登记保持当前顺序，并在跳过这次机会后自动解除暂缓。"
                                else ->
                                "下一次轮到$subject 时不会进入游玩位置。这份登记保持当前顺序，并在跳过这次机会后自动解除暂缓。"
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
                            enabled = allowDeferOneRound,
                            disabledReason = "系统规则不允许暂缓一轮。"
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
                                "下一次轮到$subject 时不会进入游玩位置，而会按一次轮空移至当前等待顺序末端；之后每次轮到时仍会移至队尾。"
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
                            enabled = allowTemporaryLeave,
                            disabledReason = "系统规则不允许暂时离开。"
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
            Text("正在读取本机数据", color = PrimaryText, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text("请稍候。", color = SecondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlayerProfileSavingOverlay() {
    Box(
        Modifier.fillMaxSize()
            .zIndex(900f)
            .background(Color.Black.copy(alpha = .2f))
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.width(320.dp)
                .clip(RoundedCornerShape(DialogRadius))
                .background(CardBackground)
                .border(1.dp, Separator.copy(alpha = .55f), RoundedCornerShape(DialogRadius))
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("正在保存玩家资料", color = PrimaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text("保存完成后会继续当前操作。", color = SecondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MobileRegistrationDialog(
    session: MobileRegistrationSession,
    machineName: String,
    nowMillis: Long,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    val expired = nowMillis >= session.expiresAtMillis
    ModalSurface(onDismiss, width = 500.dp) {
        Text(
            "使用移动设备登记",
            color = PrimaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(7.dp))
        Text(
            if (expired) {
                "这张二维码已经失效，请重新生成后再扫码。"
            } else {
                "使用手机扫描二维码，在网页中为 $machineName 选择或新建玩家资料。"
            },
            color = if (expired) Destructive else SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(16.dp))
        if (!expired) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCodeImage(
                    content = session.registrationUrl,
                    contentDescription = "移动设备登记二维码",
                    size = 224.dp
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "二维码将在 ${formatClockTime(session.expiresAtMillis)} 失效",
                color = TertiaryText,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "手机页面可以在完整的玩家资料库中选择玩家；浏览器只会记住上次选择的资料，方便下次快速定位。通过此入口创建的是现场登记，无需另行签到。",
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(PageBackground)
                    .padding(horizontal = 13.dp, vertical = 11.dp)
            )
            Spacer(Modifier.height(17.dp))
            SecondaryButton("关闭", onDismiss, Modifier.fillMaxWidth())
        } else {
            PrimaryButton("重新生成二维码", onRefresh, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            CancelAction(onDismiss)
        }
    }
}

@Composable
private fun MobileRegistrationFailureDialog(
    detail: String,
    retryEnabled: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    ModalSurface(onDismiss, width = 450.dp) {
        Text(
            "无法创建移动设备登记",
            color = PrimaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(detail, color = SecondaryText, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            "重试",
            onRetry,
            Modifier.fillMaxWidth(),
            enabled = retryEnabled,
            disabledReason = "本次移动设备登记会话已经结束，请关闭后重新进入。"
        )
        Spacer(Modifier.height(8.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun InactivityReturnWarningDialog(
    remainingSeconds: Int,
    onContinue: () -> Unit,
    onReturnHome: () -> Unit
) {
    ModalSurface(onDismiss = onContinue, width = 460.dp) {
        Text(
            "即将返回首页",
            color = PrimaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "由于较长时间没有操作，$remainingSeconds 秒后将返回排队页面。如果有尚未保存的内容，返回后将不会保留。",
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("继续操作", onContinue, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SecondaryButton("立即返回首页", onReturnHome, Modifier.fillMaxWidth())
    }
}

@Composable
private fun BotFriendQrDialog(qqNumber: String, onDismiss: () -> Unit) {
    val contactUri = remember(qqNumber) {
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$qqNumber" +
            "&card_type=person&source=qrcode"
    }
    ModalSurface(onDismiss, width = 450.dp) {
        Text(
            "添加 QQ Bot 好友",
            color = PrimaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(7.dp))
        Text(
            "排队通知需要由 QQ Bot 主动发送私信。请使用 QQ 扫描二维码并添加好友，否则即使开启通知也可能无法收到。",
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrCodeImage(
                content = contactUri,
                contentDescription = "QQ Bot 好友二维码",
                size = 210.dp
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "QQ：$qqNumber",
            color = PrimaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(17.dp))
        PrimaryButton("我知道了", onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
private fun IncompleteCheckInProfileDialog(
    profile: PlayerProfile?,
    onDismiss: () -> Unit,
    onEditProfile: (() -> Unit)?
) {
    ModalSurface(onDismiss, width = 470.dp) {
        Text(
            "需要先补全玩家资料",
            color = PrimaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (profile == null) {
                "这份线上登记关联的玩家资料暂时不可用，因此不能完成签到。请等待资料同步后重试，或联系现场工作人员。"
            } else {
                "“${profile.nickname}”还需要确认 QQ 显示范围和排队通知设置。线上登记会继续保留；保存资料后，请再次点击登记并选择“已到场”完成签到。"
            },
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(18.dp))
        if (onEditProfile != null) {
            PrimaryButton("前往补全玩家资料", onEditProfile, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            CancelAction(onDismiss)
        } else {
            PrimaryButton("我知道了", onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun QrCodeImage(content: String, contentDescription: String, size: Dp) {
    val bitmap = remember(content) { createQrCodeBitmap(content) }
    Box(
        Modifier.size(size + 20.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, Separator.copy(alpha = .7f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = panguSpacing(contentDescription),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                "二维码生成失败",
                color = Destructive,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun createQrCodeBitmap(content: String, dimension: Int = 640): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, dimension, dimension)
    val foreground = android.graphics.Color.BLACK
    val background = android.graphics.Color.WHITE
    val pixels = IntArray(dimension * dimension) { index ->
        if (matrix[index % dimension, index / dimension]) foreground else background
    }
    Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, dimension, 0, 0, dimension, dimension)
    }
}.getOrNull()

private fun formatClockTime(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestampMillis))

@Composable
private fun PlayerProfileWriteFailureDialog(detail: String, onDismiss: () -> Unit) {
    ModalSurface(onDismiss) {
        Text("玩家资料未能保存", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = SecondaryText, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("我知道了", onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
private fun QueueRestoreDialog(
    savedState: PersistedQueueState,
    machineAName: String,
    machineBName: String,
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
                QueueRestoreMachineRow(machineAName, savedState.machineA, savedState.machineAStatus)
                HorizontalDivider(color = Separator.copy(alpha = .72f))
                QueueRestoreMachineRow(machineBName, savedState.machineB, savedState.machineBStatus)
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
    machineName: String,
    queue: MachineQueue,
    status: MachineStatus
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(machineName, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
    cloudSyncStatus: QueueCloudSyncStatus?,
    onOpenVersionHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalSurface(onDismiss, width = 500.dp) {
        Text("应用详情", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text("maimai Q", color = SecondaryText, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        AppDetailSectionTitle("版本信息")
        AppDetailLinkRow("版本", BuildConfig.VERSION_NAME, "查看更新记录", onOpenVersionHistory)
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
        AppDetailRow("资料与日志", "本机优先保存")
        HorizontalDivider(color = Separator.copy(alpha = .72f))
        AppDetailRow("日志保留", "最近 1,000 条操作")
        HorizontalDivider(color = Separator.copy(alpha = .72f))
        AppDetailRow("队列恢复", "保留当前及上一份有效状态")
        if (cloudSyncStatus != null) {
            HorizontalDivider(color = Separator.copy(alpha = .72f))
            AppDetailRow("网站同步", queueCloudSyncStatusLabel(cloudSyncStatus))
        }
        Spacer(Modifier.height(13.dp))
        Text(
            if (cloudSyncStatus != null) {
                "队列、玩家资料和日志始终先保存在本机。开启网站同步后，只有玩家选择允许公开时，当前登记的 QQ 才会显示在网页详情中；完整玩家资料、性别、默认偏好和资料内部编号仍通过私有接口保存。"
            } else {
                "这是不连接网站的纯本地版本。队列、玩家资料和日志只保存在本机，不会上传到服务器。"
            },
            color = SecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(18.dp))
        SecondaryButton("关闭", onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
private fun VersionHistoryDialog(onDismiss: () -> Unit) {
    val releases = listOf(
        Triple(
            "0.6.4",
            "估时表达修正",
            "预计等待时间不足 1 分钟时恢复显示“预计很快可以游玩”，避免将队列估时表达成精确倒计时；App、网站和 QQ Bot 使用一致文案。"
        ),
        Triple(
            "0.6.3",
            "首页分区与文本排版",
            "首页右侧恢复为始终可见的圆角矩形分区，动态反馈只在框内切换；App 文本统一补全中文与字母、数字之间的 Pangu 空格，同时保持玩家资料和其他原始数据不变。"
        ),
        Triple(
            "0.6.2",
            "首页操作反馈",
            "首页右侧动态区域现在会反馈签到、暂缓与暂离、偏好修改、切换机台、本轮处理、机台状态和远程操作等结果；可撤销的重要操作也迁入右侧，并将撤销时间延长至 10 秒。"
        ),
        Triple(
            "0.6.1",
            "本轮规划一致性",
            "结束本轮、移除本轮玩家和进入游玩位置改用同一份队列计划，确认提示与实际安排保持一致；过期确认不会覆盖已经变化的队列。内部队列逻辑独立为轻量核心模块，既有规则、界面和数据保持兼容。"
        ),
        Triple(
            "0.6.0",
            "队列可靠性与结构整理",
            "终端内部职责完成第一阶段拆分；队列和玩家资料增加本机有效快照保护，并通过连续组合动作测试核对轮换、暂缓、暂离、签到、未到场、固定组合和顺序调整的一致性。"
        ),
        Triple(
            "0.5.5",
            "首页动态反馈",
            "登记完成后，首页右侧会显示玩家、机台和实时队列位置，并可直接查看登记或继续添加；现场与线上登记共用一致的完成反馈。"
        ),
        Triple(
            "0.5.4",
            "登记入口与使用引导",
            "加入排队时优先显示玩家资料库和移动设备登记；临时登记调整为辅助入口，减少重复创建资料的可能。"
        ),
        Triple(
            "0.5.3",
            "交互收尾与状态一致性",
            "终端连续 30 秒无操作会返回首页并将队列翻至最左侧，填写页面会先提示；同时完善资料表单保护、远程新登记定位、同步时间详情，以及 App、网站和 QQ Bot 的离线、停机与等待时间表达。"
        ),
        Triple(
            "0.5.2",
            "资料同步与移动登记可靠性",
            "修正旧资料通过 QQ 查询重新出现、同秒同步反转资料身份和终端未清理旧副本的问题；移动设备登记执行后会在本机保存命令记录，结果回传失败也不会在玩家离队后重复建立登记。"
        ),
        Triple(
            "0.5.1",
            "移动设备登记修正",
            "修正旧资料重复副本导致 QQ 显示为空且无法补全，以及玩家离队后仍被误判为已有登记的问题；移动登记成功后终端会自动返回首页，失败时会关闭失效二维码并显示原因，同时避免迟到的二维码请求跨页面弹出。"
        ),
        Triple(
            "0.5.0",
            "玩家资料与移动设备登记",
            "玩家资料新增 QQ 显示范围和分项通知设置，并在终端、云端与 QQ Bot 间同步；现场玩家还可以扫描终端二维码，在手机网页中选择或新建资料并完成登记。"
        ),
        Triple(
            "0.4.1",
            "线上签到规则与入口控制",
            "线上登记在创建后 30 分钟未签到或轮到时仍未签到会自动退出；设置中可以单独关闭新线上登记，而不影响查询和已有登记管理。"
        ),
        Triple(
            "0.4.0",
            "线上登记与远程排队操作",
            "网站和 QQ Bot 可以通过玩家资料创建线上登记；玩家到场后需在终端完成签到。QQ Bot 同时支持管理本人当前登记，终端继续负责最终校验和落地。"
        ),
        Triple(
            "0.3.7",
            "跨批次通知与闭店提醒",
            "恢复闭店前 30 分钟提醒，并修正开始新队列后旧登记玩家可能收不到相关通知的问题。"
        ),
        Triple(
            "0.3.6",
            "玩家资料搜索补充",
            "玩家资料库现在可以使用昵称或 QQ 号搜索玩家。"
        ),
        Triple(
            "0.3.5",
            "弹窗阴影修正",
            "改回按圆角轮廓绘制弹窗阴影，修正四角出现直角阴影的问题。"
        ),
        Triple(
            "0.3.4",
            "本轮结束操作修正",
            "恢复普通营业状态下移除本轮玩家登记并开始下一轮的选项，并继续保留独立确认。"
        ),
        Triple(
            "0.3.3",
            "视觉统一与细节校正",
            "统一界面色彩、文字层级、按钮状态和弹窗反馈，并校正终端、网站与 QQ 之间的状态和详情显示。"
        ),
        Triple(
            "0.3.2",
            "跨端状态一致性",
            "修正网站估时、玩家资料回填、营业状态和登记详情在终端、网站与 QQ 之间显示不一致的问题。"
        ),
        Triple(
            "0.3.1",
            "闭店收尾与个人通知",
            "闭店后为现有队列保留最多 20 分钟的收尾时间，并统一终端、网站与 QQ Bot 的状态；玩家还可以单独开启或关闭自己的排队通知。"
        ),
        Triple(
            "0.3.0",
            "营业时间与远程联动",
            "加入营业时间、QQ Bot 联动开关、操作来源和闭店提醒；本轮结束可以选择正常轮转、移除本轮登记后开始下一轮，或仅结束本轮。"
        ),
        Triple(
            "0.2.19",
            "机台停止原因补充",
            "加入机台维护和其他原因说明，并统一终端、网站与日志中的停止状态。"
        ),
        Triple(
            "0.2.18",
            "启动恢复与轮次预览",
            "恢复队列时同步玩家资料，并修正结束本轮前对下一组玩家的预览。"
        ),
        Triple(
            "0.2.17",
            "玩家资料持久化",
            "资料只有在本机保存成功后才参与队列和网站同步，写入失败时可以明确重试。"
        ),
        Triple(
            "0.2.16",
            "发布构建与同步边界",
            "拆分纯本地版和现场终端版；本地版不申请联网权限，终端版需要显式启用构建。"
        ),
        Triple(
            "0.2.15",
            "QQ 与玩家资料云同步",
            "玩家资料改用 QQ 联系方式，并加入私有云端备份、身份识别、通知和资料修改通道。"
        )
    )
    ModalSurface(onDismiss, width = 560.dp) {
        Text("更新记录", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(
            "这里列出近期版本中会直接影响现场使用的变化。",
            color = SecondaryText,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
        releases.forEachIndexed { index, (version, title, detail) ->
            if (index > 0) HorizontalDivider(color = Separator.copy(alpha = .72f))
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        version,
                        color = if (index == 0) SystemBlue else PrimaryText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(title, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (index == 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "当前版本",
                            color = SystemBlue,
                            fontSize = 10.sp,
                            modifier = Modifier.clip(RoundedCornerShape(5.dp))
                                .background(SoftBlue)
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(detail, color = SecondaryText, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton("返回应用详情", onDismiss, Modifier.fillMaxWidth())
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
            "开启网站同步后，每次变动会先保存在本机，再尝试上传。终端也会接收服务器待执行的资料修改，并按本地规则校验后应用。关闭同步或网络不可用时，不会阻止现场排队。",
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

        val timestamps = listOfNotNull(
            status.lastSuccessfulAtMillis?.let { "上次成功同步" to it },
            status.retryStartedAtMillis?.let { "开始等待重试" to it },
            status.lastErrorAtMillis?.let { "最后发生错误" to it }
        )
        if (timestamps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(PageBackground)
                    .border(1.dp, Separator.copy(alpha = .65f), RoundedCornerShape(10.dp))
            ) {
                timestamps.forEachIndexed { index, (label, timestampMillis) ->
                    if (index > 0) HorizontalDivider(color = Separator.copy(alpha = .58f))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = SecondaryText, fontSize = 11.sp, modifier = Modifier.width(108.dp))
                        Text(
                            formatCloudSyncTimestamp(timestampMillis),
                            color = PrimaryText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
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
            "公开网站会显示登记昵称、队列位置、游玩状态、时间估算，以及资料库登记的 QQ。完整玩家资料通过需要专用令牌的私有接口同步；性别、默认偏好和资料内部编号不会出现在公开队列或公开日志中。",
            color = SecondaryText,
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(18.dp))
        SecondaryButton("关闭", onDismiss, Modifier.fillMaxWidth())
    }
}

private fun formatCloudSyncTimestamp(timestampMillis: Long): String =
    SimpleDateFormat("M 月 d 日 HH:mm:ss", Locale.CHINA).format(Date(timestampMillis))

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
    QueueCloudSyncPhase.SYNCING -> if (status.syncMode == QueueSyncMode.TEST) {
        "正在同步测试数据"
    } else {
        "正在同步"
    }
    QueueCloudSyncPhase.SYNCED -> status.lastSuccessfulAtMillis?.let {
        val prefix = if (status.syncMode == QueueSyncMode.TEST) "测试数据已同步" else "已同步"
        "$prefix · ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it))}"
    } ?: if (status.syncMode == QueueSyncMode.TEST) "测试数据已同步" else "已同步"
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
private fun AppDetailLinkRow(
    label: String,
    value: String,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 12.dp),
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
        Spacer(Modifier.width(12.dp))
        Text(actionLabel, color = SystemBlue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StopMachineChooser(
    machineAStatus: MachineStatus,
    machineBStatus: MachineStatus,
    machineAName: String,
    machineBName: String,
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
        MachineStopChoice(machineAName, machineAStatus) { onSelect(MachineId.A) }
        Spacer(Modifier.height(9.dp))
        MachineStopChoice(machineBName, machineBStatus) { onSelect(MachineId.B) }
        Spacer(Modifier.height(13.dp))
        CancelAction(onDismiss)
    }
}

@Composable
private fun MachineStopChoice(machineName: String, status: MachineStatus, onClick: () -> Unit) {
    ActionRow(
        title = machineName,
        description = if (status.isOperational) {
            "选择后继续说明停止使用的原因。"
        } else {
            "已停止使用 · ${machineStopReasonLabel(status.stopReason, status.stopReasonDetail)}"
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
    onSelect: (MachineStopReason, String?) -> Unit
) {
    val hostActivity = LocalContext.current as? MainActivity
    var enteringOtherReason by remember(machineName) { mutableStateOf(false) }
    var otherReasonDetail by remember(machineName) { mutableStateOf("") }
    ModalSurface(onDismiss, width = 470.dp) {
        if (enteringOtherReason) {
            Text("补充其他原因", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "可以填写便于现场识别的具体原因。此项为选填；留空时将只显示“其他原因”。",
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = otherReasonDetail,
                onValueChange = { value ->
                    otherReasonDetail = value.filterNot { it.isISOControl() }
                        .take(MAX_MACHINE_STOP_REASON_DETAIL_CHARACTERS)
                    hostActivity?.recordUserInteraction()
                },
                label = { Text("原因说明（选填）") },
                placeholder = { Text("例如：按钮失灵") },
                singleLine = true,
                supportingText = {
                    Text("${otherReasonDetail.length} / $MAX_MACHINE_STOP_REASON_DETAIL_CHARACTERS")
                },
                shape = RoundedCornerShape(ControlRadius),
                colors = playerProfileTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            DestructiveButton(
                "确认停止使用",
                { onSelect(MachineStopReason.OTHER, otherReasonDetail) },
                Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            CancelAction("返回选择原因") { enteringOtherReason = false }
        } else {
            Text("选择停止使用原因", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (registrationCount > 0) {
                    "选择固定原因后，$machineName 将立即停止使用。现有 $registrationCount 份登记、游玩位置和等待顺序会被保留；恢复正常使用后，将按原顺序继续，本轮计时从头开始。"
                } else {
                    "选择固定原因后，$machineName 将立即停止使用。恢复正常使用前，这台机台不能接收新的登记。"
                },
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(16.dp))
            CompactActionButton(
                MenuAction(
                    "机台断网",
                    "",
                    { onSelect(MachineStopReason.NETWORK_DISCONNECTED, null) },
                    destructive = true
                ),
                Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(9.dp))
            CompactActionButton(
                MenuAction(
                    "机台维护",
                    "",
                    { onSelect(MachineStopReason.MAINTENANCE, null) },
                    destructive = true
                ),
                Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(9.dp))
            CompactActionButton(
                MenuAction(
                    "机台未开机",
                    "",
                    { onSelect(MachineStopReason.NOT_POWERED_ON, null) },
                    destructive = true
                ),
                Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(9.dp))
            CompactActionButton(
                MenuAction("其他原因", "", { enteringOtherReason = true }, destructive = true),
                Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(13.dp))
            CancelAction(onDismiss)
        }
    }
}

@Composable
private fun EditMachineChooser(
    machineA: MachineQueue,
    machineB: MachineQueue,
    machineAStatus: MachineStatus,
    machineBStatus: MachineStatus,
    machineAName: String,
    machineBName: String,
    onDismiss: () -> Unit,
    onSelect: (MachineId) -> Unit
) {
    ModalSurface(onDismiss, width = 470.dp) {
        Text("编辑登记", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("选择需要调整的机台。", color = SecondaryText, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        ActionRow(
            machineAName,
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
            machineBName,
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
    machineName: String,
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
    var discardConfirmationVisible by remember(initialQueue) { mutableStateOf(false) }
    val requestCancel = {
        if (hasRegistrationOrderChanged(originalOrder, registrations)) {
            discardConfirmationVisible = true
        } else {
            onCancel()
        }
    }
    BackHandler(onBack = requestCancel)
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
            AppBackButton(requestCancel, text = "取消")
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
                if (explicitEditMode) "编辑$machineName 的登记" else "调整登记位置",
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
    if (discardConfirmationVisible) {
        RemoveRegistrationConfirmation(
            title = "放弃顺序调整？",
            message = "返回后，这次尚未完成的登记顺序调整不会保存。",
            confirmText = "放弃调整",
            onDismiss = { discardConfirmationVisible = false },
            onConfirm = {
                discardConfirmationVisible = false
                onCancel()
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
    BackHandler(onBack = dismissWithAnimation)

    LaunchedEffect(Unit) { shown = true }

    BoxWithConstraints(
        Modifier.fillMaxSize().imePadding()
            .background(Color.Black.copy(alpha = .28f * visibilityProgress))
            .pointerInput(onDismiss) {
                detectTapGestures(onTap = { dismissWithAnimation() })
            }.padding(horizontal = 20.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.widthIn(max = width).fillMaxWidth().heightIn(max = maxHeight)
                .graphicsLayer {
                    alpha = visibilityProgress
                    scaleX = .965f + visibilityProgress * .035f
                    scaleY = .965f + visibilityProgress * .035f
                    translationY = (1f - visibilityProgress) * 10.dp.toPx()
                }
                .shadow(8.dp, dialogShape, clip = false)
                .clip(dialogShape)
                .animateContentSize(tween(190))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
            },
            shape = dialogShape,
            color = CardBackground,
            border = BorderStroke(1.dp, Separator.copy(alpha = .48f))
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) { content() }
        }
    }
}

private fun showDisabledActionReason(
    context: android.content.Context,
    title: String,
    description: String?
) {
    val reason = description?.trim()?.takeIf { it.isNotEmpty() }
        ?: "当前状态下不能使用这项操作。"
    Toast.makeText(context, panguSpacing("$title：$reason"), Toast.LENGTH_LONG).show()
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
    val context = LocalContext.current
    val shape = RoundedCornerShape(ControlRadius)
    val visibleDescription = description?.takeIf { it.isNotBlank() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (visibleDescription == null) 52.dp else 68.dp)
            .clip(shape)
            .background(
                when {
                    !enabled -> DisabledBackground
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
            .clickable {
                if (enabled) onClick() else showDisabledActionReason(
                    context,
                    title,
                    visibleDescription
                )
            }.padding(horizontal = 15.dp, vertical = 10.dp),
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
    val context = LocalContext.current
    val shape = RoundedCornerShape(ControlRadius)
    val visibleDescription = action.description.takeIf { it.isNotBlank() }
    Column(
        modifier.heightIn(min = if (visibleDescription == null) 52.dp else 82.dp).clip(shape)
            .background(
                when {
                    !enabled -> DisabledBackground
                    action.destructive -> Destructive.copy(alpha = .075f)
                    !accented -> PageBackground
                    action.accentBackgroundColor != null -> action.accentBackgroundColor
                    else -> SoftBlue
                }
            )
            .border(
                1.dp,
                when {
                    !enabled -> Separator.copy(alpha = .65f)
                    action.destructive -> Destructive.copy(alpha = .18f)
                    !accented -> Separator.copy(alpha = .8f)
                    action.accentColor != null -> action.accentColor.copy(alpha = .24f)
                    else -> SystemBlue.copy(alpha = .14f)
                },
                shape
            )
            .clickable {
                if (enabled) action.onClick() else showDisabledActionReason(
                    context,
                    action.title,
                    visibleDescription
                )
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                action.title,
                color = when {
                    !enabled -> TertiaryText
                    action.destructive -> Destructive
                    action.accentColor != null -> action.accentColor
                    else -> PrimaryText
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Text(
                "›",
                color = when {
                    !enabled -> Separator
                    action.accentColor != null -> action.accentColor.copy(alpha = .72f)
                    else -> TertiaryText
                },
                fontSize = 17.sp
            )
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
    val context = LocalContext.current
    val shape = RoundedCornerShape(ControlRadius)
    Box(
        modifier.height(50.dp).clip(shape)
            .background(
                when {
                    !enabled -> DisabledBackground
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
            .clickable {
                if (enabled) action.onClick() else showDisabledActionReason(
                    context,
                    action.title,
                    action.description
                )
            }
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
        Modifier.height(48.dp).clip(RoundedCornerShape(ControlRadius))
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
    val context = LocalContext.current
    val shape = RoundedCornerShape(ControlRadius)
    Column(
        modifier.heightIn(min = 88.dp).clip(shape)
            .background(if (visuallyDisabled) DisabledBackground else PageBackground)
            .border(1.dp, Separator.copy(alpha = .7f), shape)
            .clickable {
                if (visuallyDisabled) {
                    showDisabledActionReason(context, title, description)
                } else {
                    onClick()
                }
            }
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
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(ControlRadius))
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
    enabled: Boolean = true,
    disabledReason: String = "当前状态下不能使用这项操作。"
) {
    val context = LocalContext.current
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(ControlRadius))
            .background(if (!enabled) DisabledBackground else if (primary) SystemBlue else SoftBlue)
            .border(
                1.dp,
                if (!enabled) Separator.copy(alpha = .55f) else if (primary) SystemBlue else SystemBlue.copy(alpha = .12f),
                RoundedCornerShape(ControlRadius)
            )
            .clickable {
                if (enabled) onClick() else showDisabledActionReason(context, text, disabledReason)
            }
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
    enabled: Boolean = true,
    disabledReason: String? = null
) {
    val context = LocalContext.current
    Button(
        onClick = {
            if (enabled) onClick()
            else showDisabledActionReason(context, text, disabledReason)
        },
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(ControlRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) SystemBlue else DisabledBackground,
            contentColor = if (enabled) Color.White else TertiaryText
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
            .background(if (enabled) SoftBlue else DisabledBackground)
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
    enabled: Boolean = true,
    disabledReason: String? = null
) {
    val context = LocalContext.current
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(ControlRadius))
            .background(if (enabled) CardBackground else DisabledBackground)
            .border(
                1.dp,
                if (enabled) SystemBlue.copy(alpha = .28f) else Separator.copy(alpha = .65f),
                RoundedCornerShape(ControlRadius)
            )
            .clickable {
                if (enabled) onClick()
                else showDisabledActionReason(context, text, disabledReason)
            }.padding(horizontal = 18.dp),
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
    val formattedDisplayId = panguSpacing(displayId)
    val characterCount = formattedDisplayId.codePointCount(0, formattedDisplayId.length)
    if (characterCount <= 6) return formattedDisplayId
    val truncationEnd = formattedDisplayId.offsetByCodePoints(0, 5)
    return formattedDisplayId.substring(0, truncationEnd).trimEnd() + "…"
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
    if (preview?.changedByAvailability != true) return null

    return availabilityOutcomeMessage(
        unavailableRegistrations = preview.unavailableRegistrations,
        nextRegistrations = preview.nextRegistrations
    )
}

private fun availabilityOutcomeMessage(
    unavailableRegistrations: List<Registration>,
    nextRegistrations: List<Registration>
): String? {
    if (unavailableRegistrations.isEmpty()) return null

    fun names(registrations: List<Registration>): String =
        registrations.joinToString("和") { "“${it.displayId}”" }

    val temporarilyAway = unavailableRegistrations.filter {
        it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
    }
    val deferred = unavailableRegistrations.filter {
        it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
    }
    val pendingCheckIn = unavailableRegistrations.filter {
        it.requiresOnSiteCheckIn
    }
    val temporaryAwayWillExit = temporarilyAway.filter {
        it.temporaryAwaySkippedTurns >= 3
    }
    val temporaryAwayWillRemain = temporarilyAway.filterNot {
        it.temporaryAwaySkippedTurns >= 3
    }
    val outcomes = buildList {
        if (pendingCheckIn.isNotEmpty()) {
            add(
                "${names(pendingCheckIn)}尚未完成现场签到，" +
                    if (pendingCheckIn.size == 1) {
                        "这份线上登记将自动移除。"
                    } else {
                        "这些线上登记将自动移除。"
                    }
            )
        }
        if (deferred.isNotEmpty()) {
            add(
                "${names(deferred)}已暂缓一轮，本轮会被跳过；跳过后暂缓一轮状态自动解除，登记位置保持不变。"
            )
        }
        temporaryAwayWillRemain.forEach { registration ->
            add(
                "“${registration.displayId}”处于暂时离开状态，本轮会轮空并移至等待顺序末端；" +
                    "轮空次数将变为 ${registration.temporaryAwaySkippedTurns + 1} 次。"
            )
        }
        if (temporaryAwayWillExit.isNotEmpty()) {
            add(
                "${names(temporaryAwayWillExit)}暂时离开期间已轮空 3 次，" +
                    if (temporaryAwayWillExit.size == 1) {
                        "本次再轮到时会自动退出排队。"
                    } else {
                        "本次再轮到时都会自动退出排队。"
                    }
            )
        }
    }
    val nextRound = when (nextRegistrations.size) {
        0 -> "目前没有可以进入游玩位置的登记，游玩位置将保持空缺。"
        1 -> "实际将由${names(nextRegistrations)}单人游玩。"
        else -> "实际将由${names(nextRegistrations)}共同游玩。"
    }
    return (outcomes + "系统会按照其余有效登记的顺序和游玩偏好重新组合。$nextRound")
        .joinToString("\n")
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

internal fun formatJoinWaitEstimate(minutes: Long?): String = when {
    minutes == null -> "暂时无法估算"
    minutes <= 0L -> "预计很快可以游玩"
    else -> "约 $minutes 分钟"
}

internal fun formatPositionWaitEstimate(minutes: Long?): String = when {
    minutes == null -> "暂时无法估算"
    minutes <= 0L -> "预计很快可以游玩"
    else -> "约 $minutes 分钟后可以游玩"
}

private fun positionWaitEstimateLabel(
    registrations: List<Registration>,
    minutes: Long?
): String = when {
    registrations.any { it.requiresOnSiteCheckIn } -> "签到后可估算"
    registrations.any { it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY } ->
        "暂时离开，无法估算"
    else -> formatPositionWaitEstimate(minutes)
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

private fun machineName(machineId: MachineId, remark: String): String =
    "$remark · 机台 ${machineId.name}"

private fun MachineQueue.registrationPositionName(
    machineId: MachineId,
    registrationKey: Int
): String? {
    if (playing.any { it.key == registrationKey }) return playingPositionName(machineId)
    val waitingPositionIndex = waitingPositions().indexOfFirst { registrations ->
        registrations.any { it.key == registrationKey }
    }
    return waitingPositionIndex.takeIf { it >= 0 }
        ?.let { "位置 ${machineId.name}${it + 1}" }
}

private fun playingPositionName(machineId: MachineId): String = "游玩位置 ${machineId.name}"

private fun waitingFrontPositionName(machineId: MachineId): String = "队列位置 ${machineId.name}1"

private fun machineStopReasonLabel(
    reason: MachineStopReason?,
    reasonDetail: String? = null
): String = when (reason) {
    MachineStopReason.NOT_POWERED_ON -> "机台未开机"
    MachineStopReason.NETWORK_DISCONNECTED -> "机台断网"
    MachineStopReason.MAINTENANCE -> "机台维护"
    MachineStopReason.OTHER -> normalizeMachineStopReasonDetail(reason, reasonDetail)
        ?.let { "其他原因（$it）" }
        ?: "其他原因"
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
    val hasPendingCheckIn = registrations.any { it.requiresOnSiteCheckIn }
    val hasTemporaryLeave = registrations.any {
        it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
    }
    val hasOneRoundDeferral = registrations.any {
        it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
    }
    val subject = if (registrations.size > 1) "这组玩家" else "这名玩家"
    return when {
        hasPendingCheckIn ->
            "$subject 尚未完成现场签到，不会进入游玩位置，不能标记为未到场。"
        hasTemporaryLeave && hasOneRoundDeferral ->
            "$subject 包含暂缓或暂时离开的登记，本次不会进入游玩位置，不能标记为未到场。"
        hasTemporaryLeave ->
            "$subject 处于暂时离开状态，本次不会进入游玩位置，不能标记为未到场。"
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
