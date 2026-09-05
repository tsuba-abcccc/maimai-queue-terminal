package com.abcccc.maimaiqueue

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MANAGEMENT_PREFERENCES = "management_app_preferences"
private const val MANAGEMENT_ENDPOINT_KEY = "management_endpoint"
private const val MANAGEMENT_TOKEN_KEY = "management_token"

private data class ManagementActionPrompt(
    val title: String,
    val detail: String,
    val confirmLabel: String,
    val request: ManagementTerminalActionRequest,
    val destructive: Boolean = false
)

private data class ManagementPositionTarget(
    val machine: ManagementMachine,
    val registrations: List<ManagementRegistration>,
    val playing: Boolean,
    val waitingPositionIndex: Int? = null
)

@Composable
internal fun ManagementApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(MANAGEMENT_PREFERENCES, Context.MODE_PRIVATE)
    }
    var endpoint by rememberSaveable {
        mutableStateOf(
            preferences.getString(MANAGEMENT_ENDPOINT_KEY, null)
                ?.trim()
                .orEmpty()
                .ifBlank { BuildConfig.MANAGEMENT_API_URL.trim() }
        )
    }
    var token by rememberSaveable {
        mutableStateOf(
            preferences.getString(MANAGEMENT_TOKEN_KEY, null)
                ?.trim()
                .orEmpty()
                .ifBlank { BuildConfig.MANAGEMENT_API_TOKEN.trim() }
        )
    }
    var configured by rememberSaveable(endpoint, token) {
        mutableStateOf(endpoint.isNotBlank() && token.isNotBlank())
    }
    var overview by remember { mutableStateOf<ManagementOverview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var pendingCommandIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingProfileIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editingProfile by remember { mutableStateOf<ManagementProfile?>(null) }
    var passwordProfile by remember { mutableStateOf<ManagementProfile?>(null) }
    var createRegistrationVisible by rememberSaveable { mutableStateOf(false) }
    var reorderMachine by remember { mutableStateOf<ManagementMachine?>(null) }
    var creatingRegistration by remember { mutableStateOf(false) }
    var updatingTerminalPolicy by remember { mutableStateOf(false) }
    var updatingTerminalSettings by remember { mutableStateOf(false) }
    var updatingRegistrationAvailability by remember { mutableStateOf(false) }
    var updatingMachineStatus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (endpoint.isBlank() || token.isBlank()) {
            configured = false
            return
        }
        loading = true
        error = null
        scope.launch {
            runCatching { ManagementApi(endpoint, token).fetchOverview() }
                .onSuccess { overview = it }
                .onFailure { throwable ->
                    error = when (throwable) {
                        is ManagementApiException -> throwable.message
                        else -> throwable.message ?: "暂时无法连接管理服务"
                    }
                }
            loading = false
        }
    }

    fun submitTerminalAction(
        request: ManagementTerminalActionRequest,
        onSubmitted: (() -> Unit)? = null
    ) {
        val currentOverview = overview ?: return
        val pendingKeys = request.registrationIds.toSet() + request.machine.id
        pendingCommandIds = pendingCommandIds + pendingKeys
        scope.launch {
            runCatching {
                ManagementApi(endpoint, token).terminalQueueAction(currentOverview, request)
            }.onSuccess { result ->
                if (result.status.equals("REJECTED", true)) {
                    error = result.detail ?: "现场终端未执行这项队列操作"
                } else {
                    error = "队列操作已发送，等待现场终端处理。"
                    onSubmitted?.invoke()
                }
                refresh()
            }.onFailure { throwable ->
                error = throwable.message ?: "管理队列操作请求失败"
            }
            pendingCommandIds = pendingCommandIds - pendingKeys
        }
    }

    if (!configured || settingsVisible) {
        ManagementSetupScreen(
            endpoint = endpoint,
            token = token,
            showCancel = configured,
            onEndpointChange = { endpoint = it },
            onTokenChange = { token = it },
            onCancel = { settingsVisible = false },
            onSave = {
                endpoint = endpoint.trim().trimEnd('/')
                token = token.trim()
                preferences.edit()
                    .putString(MANAGEMENT_ENDPOINT_KEY, endpoint)
                    .putString(MANAGEMENT_TOKEN_KEY, token)
                    .apply()
                configured = endpoint.isNotBlank() && token.isNotBlank()
                settingsVisible = false
                if (configured) refresh()
            }
        )
        return
    }

    LaunchedEffect(Unit) {
        if (overview == null) refresh()
        while (true) {
            delay(30_000L)
            refresh()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PageBackground,
        topBar = {
            ManagementTopBar(
                overview = overview,
                loading = loading,
                onRefresh = ::refresh,
                onSettings = { settingsVisible = true }
            )
        },
        bottomBar = {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.navigationBarsPadding(),
                containerColor = CardBackground,
                contentColor = SystemBlue
            ) {
                listOf("队列", "玩家资料", "设置", "日志").forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            error?.let { detail ->
                ManagementErrorBanner(detail = detail, onDismiss = { error = null })
            }
            when (selectedTab) {
                0 -> ManagementQueuePage(
                    overview = overview,
                    loading = loading,
                    pendingCommandIds = pendingCommandIds,
                    onCreateRegistration = { createRegistrationVisible = true },
                    onReorder = { reorderMachine = it },
                    profiles = overview?.profiles.orEmpty(),
                    queueRules = overview?.queueRules.orEmpty(),
                    registrationOpen = overview?.registrationOpen == true,
                    onTerminalAction = { request -> submitTerminalAction(request) }
                )
                1 -> ManagementProfilesPage(
                    profiles = overview?.profiles.orEmpty(),
                    loading = loading,
                    pendingProfileIds = pendingProfileIds,
                    onEdit = { editingProfile = it },
                    onPassword = { passwordProfile = it }
                )
                2 -> ManagementSettingsPage(
                    overview = overview,
                    loading = loading,
                    settingsBusy = updatingTerminalSettings,
                    registrationBusy = updatingRegistrationAvailability,
                    statusBusy = updatingMachineStatus,
                    policyBusy = updatingTerminalPolicy,
                    onPolicySubmit = { policy ->
                        overview?.let { currentOverview ->
                            updatingTerminalPolicy = true
                            scope.launch {
                                runCatching {
                                    ManagementApi(endpoint, token).updateTerminalPolicy(
                                        expectedQueueId = currentOverview.queueId,
                                        expectedPolicyRevision = currentOverview.terminalPolicy.revision,
                                        managementAppBound = policy.managementAppBound,
                                        allowOnlineRegistration = policy.allowOnlineRegistration,
                                        allowDeferOneRound = policy.allowDeferOneRound,
                                        allowTemporaryLeave = policy.allowTemporaryLeave,
                                        oneBotSyncEnabled = policy.oneBotSyncEnabled,
                                        reason = if (policy.managementAppBound) "管理后台接管终端敏感策略" else "管理后台解除终端敏感策略接管"
                                    )
                                }.onSuccess { result ->
                                    error = if (result.status.equals("REJECTED", true)) result.detail ?: "终端策略修改未执行" else "终端策略命令已发送，等待现场终端处理。"
                                    refresh()
                                }.onFailure { throwable ->
                                    error = throwable.message ?: "终端策略修改请求失败"
                                }
                                updatingTerminalPolicy = false
                            }
                        }
                    },
                    onRegistrationOpenChange = { registrationOpen ->
                        overview?.let { currentOverview ->
                            updatingRegistrationAvailability = true
                            scope.launch {
                                runCatching {
                                    ManagementApi(endpoint, token).updateRegistrationAvailability(
                                        expectedQueueId = currentOverview.queueId,
                                        expectedQueueRevision = currentOverview.queueRevision,
                                        expectedMachineConfigurationRevision = currentOverview.machineConfigurationRevision,
                                        expectedRegistrationOpen = currentOverview.registrationOpen,
                                        registrationOpen = registrationOpen,
                                        expectedRegistrationIds = currentOverview.machines.flatMap { machine ->
                                            machine.playing.map { it.registrationId } + machine.waiting.map { it.registrationId }
                                        },
                                        confirmClearQueue = !registrationOpen,
                                        reason = if (registrationOpen) "管理后台开启登记排队" else "管理后台关闭登记排队"
                                    )
                                }.onSuccess { result ->
                                    error = if (result.status.equals("REJECTED", true)) result.detail ?: "登记开关未执行" else "登记开关命令已发送，等待现场终端处理。"
                                    refresh()
                                }.onFailure { throwable ->
                                    error = throwable.message ?: "登记开关请求失败"
                                }
                                updatingRegistrationAvailability = false
                            }
                        }
                    },
                    onSave = { draft ->
                        overview?.let { currentOverview ->
                            updatingTerminalSettings = true
                            scope.launch {
                                runCatching {
                                    ManagementApi(endpoint, token).updateTerminalSettings(
                                        expectedQueueId = currentOverview.queueId,
                                        expectedSettingsRevision = currentOverview.terminalSettings.revision,
                                        expectedPolicyRevision = currentOverview.terminalPolicy.revision,
                                        expectedMachineConfigurationRevision = currentOverview.machineConfigurationRevision,
                                        expectedRegistrationOpen = currentOverview.registrationOpen,
                                        showCommonPlayPreview = draft.showCommonPlayPreview,
                                        businessHours = draft.businessHours,
                                        machineGroups = draft.machineGroups,
                                        defaultMachineGroupId = draft.defaultMachineGroupId,
                                        machines = draft.machines,
                                        reason = "管理后台更新营业时间、预览和机台设置"
                                    )
                                }.onSuccess { result ->
                                    error = if (result.status.equals("REJECTED", true)) result.detail ?: "终端设置未执行" else "终端设置命令已发送，等待现场终端处理。"
                                    refresh()
                                }.onFailure { throwable ->
                                    error = throwable.message ?: "终端设置请求失败"
                                }
                                updatingTerminalSettings = false
                            }
                        }
                    },
                    onMachineStatus = { machine, operational, reason, detail ->
                        overview?.let { currentOverview ->
                            updatingMachineStatus = true
                            scope.launch {
                                runCatching {
                                    ManagementApi(endpoint, token).updateMachineStatus(
                                        expectedQueueId = currentOverview.queueId,
                                        expectedMachineConfigurationRevision = currentOverview.machineConfigurationRevision,
                                        machine = machine,
                                        operational = operational,
                                        stopReason = reason,
                                        stopReasonDetail = detail,
                                        reason = if (operational) "管理后台恢复机台" else "管理后台停止机台"
                                    )
                                }.onSuccess { result ->
                                    error = if (result.status.equals("REJECTED", true)) result.detail ?: "机台状态未执行" else "机台状态命令已发送，等待现场终端处理。"
                                    refresh()
                                }.onFailure { throwable ->
                                    error = throwable.message ?: "机台状态请求失败"
                                }
                                updatingMachineStatus = false
                            }
                        }
                    }
                )
                3 -> ManagementLogsPage(
                    endpoint = endpoint,
                    token = token,
                    overview = overview,
                    loadingOverview = loading,
                    onError = { detail -> if (detail != null) error = detail }
                )
                else -> EmptyManagementPage("请选择管理页面")
            }
        }
    }

    editingProfile?.let { profile ->
        ManagementProfileEditorDialog(
            profile = profile,
            busy = profile.id in pendingProfileIds,
            onDismiss = { editingProfile = null },
            onSubmit = { nickname, gender, defaultPreference, qqVisibility,
                         terminalEditingAllowed, visitedVenuesPublic ->
                pendingProfileIds = pendingProfileIds + profile.id
                scope.launch {
                    runCatching {
                        ManagementApi(endpoint, token).updateProfile(
                            profileId = profile.id,
                            nickname = nickname,
                            gender = gender,
                            defaultPreference = defaultPreference,
                            qqVisibility = qqVisibility,
                            terminalEditingAllowed = terminalEditingAllowed,
                            visitedVenuesPublic = visitedVenuesPublic
                        )
                    }.onSuccess { result ->
                        error = if (result.status.equals("REJECTED", true)) {
                            result.detail ?: "资料修改未执行"
                        } else {
                            editingProfile = null
                            null
                        }
                        refresh()
                    }.onFailure { throwable ->
                        error = throwable.message ?: "资料修改请求失败"
                    }
                    pendingProfileIds = pendingProfileIds - profile.id
                }
            }
        )
    }

    passwordProfile?.let { profile ->
        ManagementPasswordDialog(
            profile = profile,
            onDismiss = { passwordProfile = null },
            onSubmit = { password, confirmation ->
                pendingProfileIds = pendingProfileIds + profile.id
                scope.launch {
                    runCatching {
                        ManagementApi(endpoint, token).updatePassword(
                            profileId = profile.id,
                            password = password,
                            confirmation = confirmation
                        )
                    }.onSuccess { detail ->
                        passwordProfile = null
                        error = detail
                    }.onFailure { throwable ->
                        error = throwable.message ?: "密码修改请求失败"
                    }
                    pendingProfileIds = pendingProfileIds - profile.id
                }
            }
        )
    }

    if (createRegistrationVisible) {
        ManagementCreateRegistrationDialog(
            profiles = overview?.profiles.orEmpty(),
            machines = overview?.machines.orEmpty(),
            busy = creatingRegistration,
            onDismiss = { if (!creatingRegistration) createRegistrationVisible = false },
            onSubmit = { profileId, temporaryDisplayId, machine, preference ->
                creatingRegistration = true
                submitTerminalAction(
                    ManagementTerminalActionRequest(
                        action = if (profileId == null) {
                            ManagementQueueAction.ADD_TEMPORARY_REGISTRATION
                        } else {
                            ManagementQueueAction.ADD_PROFILE_REGISTRATION
                        },
                        machine = machine,
                        profileId = profileId,
                        displayId = temporaryDisplayId,
                        preference = preference,
                        reason = "管理后台新建登记"
                    )
                ) {
                    createRegistrationVisible = false
                    creatingRegistration = false
                }
                creatingRegistration = false
            }
        )
    }

    reorderMachine?.let { machine ->
        ManagementReorderDialog(
            machine = machine,
            busy = machine.id in pendingCommandIds,
            onDismiss = { if (machine.id !in pendingCommandIds) reorderMachine = null },
            onSubmit = { desiredPositions ->
                submitTerminalAction(
                    ManagementTerminalActionRequest(
                        action = ManagementQueueAction.REPLACE_WAITING_POSITIONS,
                        machine = machine,
                        desiredWaitingPositions = desiredPositions,
                        reason = "管理后台调整等待位置顺序"
                    )
                ) { reorderMachine = null }
            }
        )
    }
}

@Composable
private fun ManagementSetupScreen(
    endpoint: String,
    token: String,
    showCancel: Boolean,
    onEndpointChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    val valid = endpoint.trim().isNotBlank() && token.trim().isNotBlank()
    Surface(modifier = Modifier.fillMaxSize(), color = PageBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "maimai Q 管理后台",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = PrimaryText
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "连接已部署的队列服务",
                style = MaterialTheme.typography.bodyMedium,
                color = SecondaryText
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = endpoint,
                onValueChange = onEndpointChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("服务地址") },
                placeholder = { Text("https://example.com") }
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("管理令牌") },
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onSave,
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 13.dp)
            ) {
                Text("保存并连接")
            }
            if (showCancel) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text("取消") }
            }
        }
    }
}

@Composable
private fun ManagementTopBar(
    overview: ManagementOverview?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(color = CardBackground, shadowElevation = 1.dp) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = overview?.venueName ?: "管理后台",
                        color = PrimaryText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val terminalText = overview?.terminalName?.let { "$it · " }.orEmpty() +
                        if (overview?.terminalOnline == true) "现场终端在线" else "现场终端离线"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (overview?.terminalOnline == true) OnlineRegistrationStatusColor else Destructive,
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(terminalText, color = SecondaryText, style = MaterialTheme.typography.labelMedium)
                    }
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "连接设置")
                }
            }
            overview?.let {
                Text(
                    text = "队列 ${it.queueRevision} · ${formatManagementTime(it.receivedAtMillis)}",
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 9.dp),
                    color = TertiaryText,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun ManagementErrorBanner(detail: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Destructive.copy(alpha = 0.10f))
            .padding(start = 16.dp, top = 9.dp, bottom = 9.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = detail,
            modifier = Modifier.weight(1f),
            color = Destructive,
            style = MaterialTheme.typography.bodySmall
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "关闭提示", tint = Destructive)
        }
    }
}

@Composable
private fun ManagementQueuePage(
    overview: ManagementOverview?,
    loading: Boolean,
    pendingCommandIds: Set<String>,
    onCreateRegistration: () -> Unit,
    onReorder: (ManagementMachine) -> Unit,
    profiles: List<ManagementProfile>,
    queueRules: Map<String, Boolean>,
    registrationOpen: Boolean,
    onTerminalAction: (ManagementTerminalActionRequest) -> Unit
) {
    val machines = overview?.machines.orEmpty()
    if (loading && overview == null) {
        LoadingManagementPage()
        return
    }
    if (overview == null) {
        EmptyManagementPage("暂无队列数据")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ManagementMetric("机台", machines.size.toString(), Modifier.weight(1f))
                    ManagementMetric("登记", machines.sumOf { it.registrationCount }.toString(), Modifier.weight(1f))
                    ManagementMetric("状态", if (overview.registrationOpen) "开放" else "关闭", Modifier.weight(1f))
                }
                OutlinedButton(
                    onClick = onCreateRegistration,
                    enabled = overview.registrationOpen && machines.any(ManagementMachine::operational),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 9.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("新建登记")
                }
            }
        }
        items(machines, key = { it.id }) { machine ->
            ManagementMachineCard(
                machine = machine,
                machines = machines,
                pendingCommandIds = pendingCommandIds,
                onReorder = onReorder,
                profiles = profiles,
                queueRules = queueRules,
                registrationOpen = registrationOpen,
                onTerminalAction = onTerminalAction
            )
        }
    }
}

@Composable
private fun ManagementMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = CardBackground, shape = RoundedCornerShape(CardRadius)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, color = SecondaryText, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(3.dp))
            Text(value, color = PrimaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ManagementCreateRegistrationDialog(
    profiles: List<ManagementProfile>,
    machines: List<ManagementMachine>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        profileId: String?,
        temporaryDisplayId: String?,
        machine: ManagementMachine,
        preference: String?
    ) -> Unit
) {
    val firstProfile = profiles.firstOrNull()
    val firstMachine = machines.firstOrNull { it.operational } ?: machines.firstOrNull()
    var temporaryMode by rememberSaveable { mutableStateOf(false) }
    var temporaryDisplayId by rememberSaveable { mutableStateOf("") }
    var selectedProfileId by remember(profiles) {
        mutableStateOf(firstProfile?.id.orEmpty())
    }
    var selectedMachineId by remember(machines) {
        mutableStateOf(firstMachine?.id.orEmpty())
    }
    var preference by remember(profiles) {
        mutableStateOf(firstProfile?.defaultPreference?.takeIf { it != "ASK_EVERY_TIME" })
    }
    var profileMenuOpen by remember { mutableStateOf(false) }
    var machineMenuOpen by remember { mutableStateOf(false) }
    var preferenceMenuOpen by remember { mutableStateOf(false) }
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
    val selectedMachine = machines.firstOrNull { it.id == selectedMachineId }
    val preferenceOptions = listOf(
        "SOLO" to "单人游玩",
        "OPEN_TO_JOIN" to "允许他人加入"
    )
    val resolvedPreference = when {
        selectedMachine?.capacity == 1 -> "SOLO"
        temporaryMode -> preference
        selectedProfile?.defaultPreference == "SOLO" -> "SOLO"
        selectedProfile?.defaultPreference == "OPEN_TO_JOIN" -> "OPEN_TO_JOIN"
        else -> preference
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("新建登记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { temporaryMode = false },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(
                            "玩家资料",
                            color = if (!temporaryMode) SystemBlue else PrimaryText
                        )
                    }
                    OutlinedButton(
                        onClick = { temporaryMode = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(
                            "临时登记",
                            color = if (temporaryMode) SystemBlue else PrimaryText
                        )
                    }
                }
                if (temporaryMode) {
                    OutlinedTextField(
                        value = temporaryDisplayId,
                        onValueChange = { temporaryDisplayId = it.take(18) },
                        label = { Text("登记名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Box {
                        OutlinedButton(
                            onClick = { profileMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Text(
                                "玩家：${selectedProfile?.nickname ?: "请选择玩家"}",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        DropdownMenu(
                            expanded = profileMenuOpen,
                            onDismissRequest = { profileMenuOpen = false }
                        ) {
                            profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            listOfNotNull(
                                                profile.nickname,
                                                profile.qqNumber?.let { "QQ $it" }
                                            ).joinToString(" · ")
                                        )
                                    },
                                    onClick = {
                                        selectedProfileId = profile.id
                                        preference = profile.defaultPreference.takeIf {
                                            it != "ASK_EVERY_TIME"
                                        }
                                        profileMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
                Box {
                    OutlinedButton(
                        onClick = { machineMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Text(
                            "机台：${selectedMachine?.name ?: "请选择机台"}",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    DropdownMenu(
                        expanded = machineMenuOpen,
                        onDismissRequest = { machineMenuOpen = false }
                    ) {
                        machines.forEach { machine ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (machine.operational) machine.name
                                        else "${machine.name}（已停止使用）"
                                    )
                                },
                                onClick = {
                                    selectedMachineId = machine.id
                                    machineMenuOpen = false
                                },
                                enabled = machine.operational
                            )
                        }
                    }
                }
                if (
                    selectedMachine?.capacity != 1 &&
                    (temporaryMode || selectedProfile?.defaultPreference == "ASK_EVERY_TIME")
                ) {
                    Box {
                        OutlinedButton(
                            onClick = { preferenceMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Text(
                                "本次偏好：${preferenceOptions.firstOrNull { it.first == preference }?.second ?: "请选择"}",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        DropdownMenu(
                            expanded = preferenceMenuOpen,
                            onDismissRequest = { preferenceMenuOpen = false }
                        ) {
                            preferenceOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        preference = value
                                        preferenceMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        "本次偏好：${preferenceOptions.firstOrNull { it.first == resolvedPreference }?.second ?: "按玩家资料默认"}",
                        color = SecondaryText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val machine = selectedMachine
                    if (machine != null) {
                        onSubmit(
                            selectedProfile?.id.takeUnless { temporaryMode },
                            temporaryDisplayId.trim().takeIf { temporaryMode },
                            machine,
                            resolvedPreference
                        )
                    }
                },
                enabled = !busy && selectedMachine?.operational == true &&
                    (if (temporaryMode) temporaryDisplayId.isNotBlank() else selectedProfile != null) &&
                    (selectedMachine.capacity == 1 ||
                        (!temporaryMode && selectedProfile?.defaultPreference != "ASK_EVERY_TIME") ||
                        preference != null)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("新建登记")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}

@Composable
private fun ManagementMachineCard(
    machine: ManagementMachine,
    machines: List<ManagementMachine>,
    pendingCommandIds: Set<String>,
    onReorder: (ManagementMachine) -> Unit,
    profiles: List<ManagementProfile>,
    queueRules: Map<String, Boolean>,
    registrationOpen: Boolean,
    onTerminalAction: (ManagementTerminalActionRequest) -> Unit
) {
    var machineMenuOpen by remember(machine.id) { mutableStateOf(false) }
    var roundMenuOpen by remember(machine.id) { mutableStateOf(false) }
    var prompt by remember(machine.id) { mutableStateOf<ManagementActionPrompt?>(null) }
    var noShowTarget by remember(machine.id) { mutableStateOf<ManagementPositionTarget?>(null) }
    var transferTarget by remember(machine.id) { mutableStateOf<ManagementPositionTarget?>(null) }
    val busy = machine.id in pendingCommandIds
    fun request(
        action: ManagementQueueAction,
        registrations: List<ManagementRegistration> = emptyList(),
        reason: String,
        targetMachine: ManagementMachine? = null,
        noShowResolution: String? = null,
        startNext: Boolean = true
    ) = ManagementTerminalActionRequest(
        action = action,
        machine = machine,
        registrationIds = registrations.map(ManagementRegistration::registrationId),
        targetMachine = targetMachine,
        noShowResolution = noShowResolution,
        startNextWhenPlayingBecomesEmpty = startNext,
        reason = reason
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBackground,
        shape = RoundedCornerShape(CardRadius),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(machine.name, color = PrimaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (machine.operational) "运行中 · ${machine.registrationCount} 份登记" else "已停止使用",
                        color = if (machine.operational) SecondaryText else Destructive,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Text(machine.id, color = TertiaryText, style = MaterialTheme.typography.labelMedium)
                Box {
                    IconButton(
                        onClick = { machineMenuOpen = true },
                        enabled = !busy && machine.operational
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "机台队列操作")
                    }
                    DropdownMenu(
                        expanded = machineMenuOpen,
                        onDismissRequest = { machineMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("重新开始游玩计时") },
                            enabled = machine.playing.isNotEmpty(),
                            onClick = {
                                machineMenuOpen = false
                                prompt = ManagementActionPrompt(
                                    "重新开始游玩计时？",
                                    "${machine.name} 当前一轮的计时会从现在重新开始。",
                                    "确认重新计时",
                                    request(
                                        ManagementQueueAction.RESTART_PLAYING_TIMER,
                                        reason = "管理后台重新开始游玩计时"
                                    )
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("重新开始待签到计时") },
                            enabled = machine.playing.any { it.pendingCheckIn } ||
                                machine.waiting.any { it.pendingCheckIn },
                            onClick = {
                                machineMenuOpen = false
                                prompt = ManagementActionPrompt(
                                    "重新开始待签到计时？",
                                    "${machine.name} 所有待签到登记的现场签到时限会从现在重新计算。",
                                    "确认重新计时",
                                    request(
                                        ManagementQueueAction.RESTART_PENDING_CHECK_IN_TIMERS,
                                        reason = "管理后台重新开始待签到计时"
                                    )
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("重新开始全部计时") },
                            enabled = machine.registrationCount > 0,
                            onClick = {
                                machineMenuOpen = false
                                prompt = ManagementActionPrompt(
                                    "重新开始全部计时？",
                                    "${machine.name} 的游玩计时和待签到计时都会从现在重新开始。",
                                    "确认重新计时",
                                    request(
                                        ManagementQueueAction.RESTART_MACHINE_TIMERS,
                                        reason = "管理后台重新开始机台全部计时"
                                    )
                                )
                            }
                        )
                    }
                }
            }
            if (machine.playing.isNotEmpty()) {
                Button(
                    onClick = { roundMenuOpen = true },
                    enabled = !busy && machine.operational,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) { Text("结束本轮") }
            } else if (machine.waiting.isNotEmpty()) {
                Button(
                    onClick = {
                        prompt = ManagementActionPrompt(
                            "开始下一轮？",
                            "${machine.name} 当前可用的首个等待位置将进入游玩位置。",
                            "确认开始下一轮",
                            request(
                                ManagementQueueAction.ENTER_PLAYING_POSITION,
                                reason = "管理后台开始下一轮"
                            )
                        )
                    },
                    enabled = !busy && machine.operational,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) { Text("开始下一轮") }
            }
            if (machine.waitingPositions.size > 1) {
                OutlinedButton(
                    onClick = { onReorder(machine) },
                    enabled = machine.id !in pendingCommandIds,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 7.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("调整等待顺序", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            ManagementPositionLabel("当前游玩")
            if (machine.playing.isEmpty()) {
                ManagementEmptyRow("游玩位置为空")
            } else {
                machine.playing.forEach { registration ->
                    ManagementRegistrationRow(
                        machine,
                        machines,
                        registration,
                        pendingCommandIds,
                        profiles,
                        registrationOpen,
                        onTerminalAction
                    )
                }
                ManagementPositionMenu(
                    target = ManagementPositionTarget(machine, machine.playing, playing = true),
                    machines = machines,
                    allowDeferOneRound = queueRules["allow_defer_one_round"] ?: true,
                    enabled = !busy,
                    onPrompt = { prompt = it },
                    onNoShow = { noShowTarget = it },
                    onTransfer = { transferTarget = it }
                )
            }
            Spacer(Modifier.height(6.dp))
            ManagementPositionLabel("等待顺序")
            if (machine.waiting.isEmpty()) {
                ManagementEmptyRow("暂无等待登记")
            } else {
                machine.waitingPositions.forEach { waitingPosition ->
                    val registrations = waitingPosition.registrations
                    val position = waitingPosition.index
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    buildString {
                                        append("第 $position 位")
                                        waitingPosition.estimatedWaitMinutes?.let {
                                            append(" · 预计 $it 分钟")
                                        }
                                    },
                                    color = TertiaryText,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f).padding(top = 5.dp, bottom = 2.dp)
                                )
                                ManagementPositionMenu(
                                    target = ManagementPositionTarget(
                                        machine,
                                        registrations,
                                        playing = false,
                                        waitingPositionIndex = position - 1
                                    ),
                                    machines = machines,
                                    allowDeferOneRound = queueRules["allow_defer_one_round"] ?: true,
                                    enabled = !busy,
                                    onPrompt = { prompt = it },
                                    onNoShow = { noShowTarget = it },
                                    onTransfer = { transferTarget = it }
                                )
                            }
                            registrations.forEach { registration ->
                                ManagementRegistrationRow(
                                    machine,
                                    machines,
                                    registration,
                                    pendingCommandIds,
                                    profiles,
                                    registrationOpen,
                                    onTerminalAction
                                )
                            }
                        }
                    }
            }
        }
    }
    if (roundMenuOpen) {
        AlertDialog(
            onDismissRequest = { roundMenuOpen = false },
            title = { Text("结束${machine.name}本轮游玩") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            roundMenuOpen = false
                            prompt = ManagementActionPrompt(
                                "结束本轮并开始下一轮？",
                                "当前游玩登记会回到队尾，首个可用等待位置随后进入游玩位置。",
                                "确认结束并开始下一轮",
                                request(
                                    ManagementQueueAction.FINISH_ROUND,
                                    reason = "管理后台结束本轮并开始下一轮"
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("结束本轮并开始下一轮") }
                    OutlinedButton(
                        onClick = {
                            roundMenuOpen = false
                            prompt = ManagementActionPrompt(
                                "仅结束本轮？",
                                "当前游玩登记会回到队尾，游玩位置保持空缺。",
                                "确认仅结束本轮",
                                request(
                                    ManagementQueueAction.END_ROUND_ONLY,
                                    reason = "管理后台仅结束本轮"
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("仅结束本轮") }
                    Button(
                        onClick = {
                            roundMenuOpen = false
                            prompt = ManagementActionPrompt(
                                "移除本轮登记并开始下一轮？",
                                "当前游玩位置中的登记会永久退出队列，随后开始下一轮。",
                                "确认移除并开始下一轮",
                                request(
                                    ManagementQueueAction.REMOVE_CURRENT_ROUND_AND_START_NEXT,
                                    reason = "管理后台移除本轮登记并开始下一轮"
                                ),
                                destructive = true
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Destructive),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("移除本轮登记并开始下一轮") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { roundMenuOpen = false }) { Text("取消") }
            }
        )
    }
    prompt?.let { currentPrompt ->
        ManagementActionPromptDialog(
            prompt = currentPrompt,
            onDismiss = { prompt = null },
            onConfirm = {
                prompt = null
                onTerminalAction(currentPrompt.request)
            }
        )
    }
    noShowTarget?.let { target ->
        ManagementNoShowDialog(
            target = target,
            allowDeferOneRound = queueRules["allow_defer_one_round"] ?: true,
            onDismiss = { noShowTarget = null },
            onSelect = { resolution ->
                noShowTarget = null
                prompt = ManagementActionPrompt(
                    "确认未到场处理？",
                    "所选${if (target.registrations.size > 1) "位置" else "登记"}将按所选方式处理。",
                    "确认处理",
                    request(
                        ManagementQueueAction.MARK_NO_SHOW,
                        registrations = target.registrations,
                        reason = "管理后台处理未到场",
                        noShowResolution = resolution,
                        startNext = !target.playing
                    ),
                    destructive = resolution == "REMOVE"
                )
            }
        )
    }
    transferTarget?.let { target ->
        ManagementTransferChooser(
            sourceMachine = machine,
            machines = machines,
            registrations = target.registrations,
            onDismiss = { transferTarget = null },
            onSelect = { destination ->
                transferTarget = null
                prompt = ManagementActionPrompt(
                    "转移到${destination.name}？",
                    "所选${target.registrations.size}份登记会作为完整位置转入目标机台等待末端。",
                    "确认转移",
                    request(
                        ManagementQueueAction.TRANSFER_REGISTRATIONS,
                        registrations = target.registrations,
                        reason = "管理后台转移等待位置",
                        targetMachine = destination
                    )
                )
            }
        )
    }
}

@Composable
private fun ManagementPositionMenu(
    target: ManagementPositionTarget,
    machines: List<ManagementMachine>,
    allowDeferOneRound: Boolean,
    enabled: Boolean,
    onPrompt: (ManagementActionPrompt) -> Unit,
    onNoShow: (ManagementPositionTarget) -> Unit,
    onTransfer: (ManagementPositionTarget) -> Unit
) {
    var expanded by remember(
        target.machine.id,
        target.playing,
        target.waitingPositionIndex,
        target.registrations.map(ManagementRegistration::registrationId)
    ) { mutableStateOf(false) }
    val machine = target.machine
    fun request(action: ManagementQueueAction, reason: String) =
        ManagementTerminalActionRequest(
            action = action,
            machine = machine,
            registrationIds = target.registrations.map(ManagementRegistration::registrationId),
            reason = reason
        )
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, contentDescription = "位置操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (target.playing) {
                DropdownMenuItem(
                    text = { Text("退回等待顺序前端") },
                    onClick = {
                        expanded = false
                        onPrompt(
                            ManagementActionPrompt(
                                "退回等待顺序前端？",
                                "所选游玩登记会离开当前游玩位置并回到等待首位。",
                                "确认退回",
                                request(
                                    ManagementQueueAction.RETURN_PLAYING_TO_WAITING_FRONT,
                                    "管理后台将游玩登记退回等待前端"
                                )
                            )
                        )
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("让此位置上场") },
                    enabled = machine.playing.isNotEmpty() &&
                        (target.waitingPositionIndex ?: 0) > 0,
                    onClick = {
                        expanded = false
                        onPrompt(
                            ManagementActionPrompt(
                                "让此等待位置上场？",
                                "当前游玩登记会回到队尾，前方位置会被跳过，本位置进入游玩位置。",
                                "确认上场",
                                request(
                                    ManagementQueueAction.ADVANCE_TO_WAITING_POSITION,
                                    "管理后台指定等待位置上场"
                                )
                            )
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("补入当前游玩位置") },
                    enabled = machine.capacity > 1 && machine.playing.size == 1 &&
                        target.waitingPositionIndex == 0 && target.registrations.size == 1,
                    onClick = {
                        expanded = false
                        onPrompt(
                            ManagementActionPrompt(
                                "补入当前游玩位置？",
                                "这份等待登记会与当前玩家共同游玩。",
                                "确认补入",
                                request(
                                    ManagementQueueAction.MOVE_WAITING_REGISTRATION_INTO_CURRENT_ROUND,
                                    "管理后台补入当前游玩位置"
                                )
                            )
                        )
                    }
                )
                if (
                    target.registrations.size == 2 &&
                    target.registrations.all(ManagementRegistration::fixedPair)
                ) {
                    DropdownMenuItem(
                        text = { Text("解除固定组合") },
                        onClick = {
                            expanded = false
                            onPrompt(
                                ManagementActionPrompt(
                                    "解除固定组合？",
                                    "两份登记会恢复为允许他人加入，等待位置将重新划分。",
                                    "确认解除",
                                    request(
                                        ManagementQueueAction.RELEASE_FIXED_PAIR,
                                        "管理后台解除固定组合"
                                    )
                                )
                            )
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("转移整个位置") },
                    enabled = machines.any { it.operational && it.id != machine.id },
                    onClick = {
                        expanded = false
                        onTransfer(target)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("未到场处理") },
                enabled = target.registrations.none { it.pendingCheckIn } &&
                    (allowDeferOneRound || target.registrations.isNotEmpty()),
                onClick = {
                    expanded = false
                    onNoShow(target)
                }
            )
            DropdownMenuItem(
                text = { Text("移除整个位置", color = Destructive) },
                onClick = {
                    expanded = false
                    onPrompt(
                        ManagementActionPrompt(
                            "移除整个位置？",
                            "所选${target.registrations.size}份登记会永久退出当前队列。",
                            "确认移除",
                            request(
                                ManagementQueueAction.REMOVE_REGISTRATIONS,
                                "管理后台移除队列位置"
                            ),
                            destructive = true
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun ManagementActionPromptDialog(
    prompt: ManagementActionPrompt,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = { Text(prompt.detail, color = SecondaryText) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (prompt.destructive) {
                    ButtonDefaults.buttonColors(containerColor = Destructive)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) { Text(prompt.confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ManagementNoShowDialog(
    target: ManagementPositionTarget,
    allowDeferOneRound: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("未到场处理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onSelect(
                            if (target.registrations.size > 1) {
                                "DEFER_GROUP_ONE_ROUND"
                            } else {
                                "DEFER_ONE_ROUND"
                            }
                        )
                    },
                    enabled = allowDeferOneRound,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("暂缓一轮") }
                OutlinedButton(
                    onClick = { onSelect("MOVE_TO_TAIL") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("移至队尾") }
                Button(
                    onClick = { onSelect("REMOVE") },
                    colors = ButtonDefaults.buttonColors(containerColor = Destructive),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("删除登记") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ManagementTransferChooser(
    sourceMachine: ManagementMachine,
    machines: List<ManagementMachine>,
    registrations: List<ManagementRegistration>,
    onDismiss: () -> Unit,
    onSelect: (ManagementMachine) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标机台") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                machines.filter { it.operational && it.id != sourceMachine.id }.forEach { machine ->
                    OutlinedButton(
                        onClick = { onSelect(machine) },
                        enabled = machine.registrationCount + registrations.size <= 20,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("${machine.name} · ${machine.registrationCount} 份登记") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ManagementPositionLabel(label: String) {
    Text(label, color = SecondaryText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
}

@Composable
private fun ManagementEmptyRow(text: String) {
    Text(text, color = TertiaryText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun ManagementReorderDialog(
    machine: ManagementMachine,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (List<List<String>>) -> Unit
) {
    val initialWaiting = machine.waitingPositions
    var waitingOrder by remember(machine.id, machine.waitingPositions) {
        mutableStateOf(initialWaiting)
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("调整${machine.name}等待顺序") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (waitingOrder.isEmpty()) {
                    Text("暂无等待登记", color = SecondaryText)
                } else {
                    waitingOrder.forEachIndexed { index, position ->
                        Surface(
                            color = PageBackground,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${index + 1}",
                                    color = TertiaryText,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.width(24.dp)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        position.registrations.joinToString(" + ") {
                                            it.displayId
                                        },
                                        color = PrimaryText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "完整等待位置 · ${position.registrations.size} 份登记",
                                        color = SecondaryText,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            waitingOrder = waitingOrder.toMutableList().also {
                                                val item = it.removeAt(index)
                                                it.add(index - 1, item)
                                            }
                                        }
                                    },
                                    enabled = !busy && index > 0
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
                                }
                                IconButton(
                                    onClick = {
                                        if (index < waitingOrder.lastIndex) {
                                            waitingOrder = waitingOrder.toMutableList().also {
                                                val item = it.removeAt(index)
                                                it.add(index + 1, item)
                                            }
                                        }
                                    },
                                    enabled = !busy && index < waitingOrder.lastIndex
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        waitingOrder.map { position ->
                            position.registrations.map(ManagementRegistration::registrationId)
                        }
                    )
                },
                enabled = !busy && waitingOrder.map(ManagementWaitingPosition::index) !=
                    initialWaiting.map(ManagementWaitingPosition::index)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("确认调整")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}

@Composable
private fun ManagementRegistrationRow(
    machine: ManagementMachine,
    machines: List<ManagementMachine>,
    registration: ManagementRegistration,
    pendingCommandIds: Set<String>,
    profiles: List<ManagementProfile>,
    registrationOpen: Boolean,
    onTerminalAction: (ManagementTerminalActionRequest) -> Unit
) {
    var actionMenuOpen by remember(registration.registrationId) { mutableStateOf(false) }
    var transferDialogOpen by remember(registration.registrationId) { mutableStateOf(false) }
    var preferenceDialogOpen by remember(registration.registrationId) { mutableStateOf(false) }
    var renameDialogOpen by remember(registration.registrationId) { mutableStateOf(false) }
    var claimDialogOpen by remember(registration.registrationId) { mutableStateOf(false) }
    var fixedPairDialogOpen by remember(registration.registrationId) { mutableStateOf(false) }
    var renameDraft by remember(registration.registrationId) {
        mutableStateOf(registration.displayId)
    }
    var prompt by remember(registration.registrationId) {
        mutableStateOf<ManagementActionPrompt?>(null)
    }
    var selectedTargetMachineId by remember(registration.registrationId) {
        mutableStateOf<String?>(null)
    }
    var selectedPreference by remember(registration.registrationId) {
        mutableStateOf(registration.preference)
    }
    fun request(
        action: ManagementQueueAction,
        registrationIds: List<String> = listOf(registration.registrationId),
        preference: String? = null,
        targetMachine: ManagementMachine? = null,
        profileId: String? = null,
        friendProfileId: String? = null,
        displayId: String? = null,
        reason: String
    ) = ManagementTerminalActionRequest(
        action = action,
        machine = machine,
        registrationIds = registrationIds,
        preference = preference,
        targetMachine = targetMachine,
        profileId = profileId,
        friendProfileId = friendProfileId,
        displayId = displayId,
        reason = reason
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(registration.displayId, color = PrimaryText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val details = buildList {
                    registration.qqNumber?.let { add("QQ $it") }
                    registration.profileId?.let { add("资料已关联") }
                    if (registration.fixedPair) add("固定组合")
                    if (registration.deferredOnce) add("暂缓")
                    if (registration.temporarilyAway) add("暂离")
                }
                if (details.isNotEmpty()) {
                    Text(details.joinToString(" · "), color = SecondaryText, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (registration.pendingCheckIn) {
                val pending = registration.registrationId in pendingCommandIds
                Button(
                    onClick = {
                        onTerminalAction(
                            request(
                                ManagementQueueAction.CHECK_IN,
                                reason = "管理后台立即签到"
                            )
                        )
                    },
                    enabled = !pending,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    if (pending) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("立即签到", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (registration.registrationId.isNotBlank()) {
                Box {
                    IconButton(
                        onClick = { actionMenuOpen = true },
                        enabled = registration.registrationId !in pendingCommandIds
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "登记操作")
                    }
                    DropdownMenu(
                        expanded = actionMenuOpen,
                        onDismissRequest = { actionMenuOpen = false }
                    ) {
                        if (!registration.pendingCheckIn &&
                            !registration.deferredOnce &&
                            !registration.temporarilyAway
                        ) {
                            DropdownMenuItem(
                                text = { Text("暂缓一次") },
                                onClick = {
                                    actionMenuOpen = false
                                    onTerminalAction(
                                        request(
                                            ManagementQueueAction.DEFER_ONE_ROUND,
                                            reason = "管理后台暂缓一轮"
                                        )
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("暂时离开") },
                                onClick = {
                                    actionMenuOpen = false
                                    onTerminalAction(
                                        request(
                                            ManagementQueueAction.TEMPORARILY_LEAVE,
                                            reason = "管理后台设置暂时离开"
                                        )
                                    )
                                }
                            )
                        }
                        if (registration.deferredOnce) {
                            DropdownMenuItem(
                                text = { Text("取消暂缓一次") },
                                onClick = {
                                    actionMenuOpen = false
                                    onTerminalAction(
                                        request(
                                            ManagementQueueAction.CANCEL_DEFER_ONE_ROUND,
                                            reason = "管理后台取消暂缓一轮"
                                        )
                                    )
                                }
                            )
                        }
                        if (registration.temporarilyAway) {
                            DropdownMenuItem(
                                text = { Text("取消暂时离开") },
                                onClick = {
                                    actionMenuOpen = false
                                    onTerminalAction(
                                        request(
                                            ManagementQueueAction.CANCEL_TEMPORARY_LEAVE,
                                            reason = "管理后台取消暂时离开"
                                        )
                                    )
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("修改登记名称") },
                            enabled = !registration.pendingCheckIn,
                            onClick = {
                                actionMenuOpen = false
                                renameDraft = registration.displayId
                                renameDialogOpen = true
                            }
                        )
                        if (registration.profileId == null) {
                            DropdownMenuItem(
                                text = { Text("关联玩家资料") },
                                enabled = !registration.pendingCheckIn && profiles.isNotEmpty(),
                                onClick = {
                                    actionMenuOpen = false
                                    claimDialogOpen = true
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("与朋友组成固定组合") },
                            enabled = registrationOpen && machine.capacity > 1 &&
                                registration.position == "WAITING" && !registration.pendingCheckIn,
                            onClick = {
                                actionMenuOpen = false
                                fixedPairDialogOpen = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("转移机台") },
                            enabled = registration.position == "WAITING" &&
                                machines.any { it.operational && it.id != machine.id },
                            onClick = {
                                actionMenuOpen = false
                                selectedTargetMachineId = machines.firstOrNull {
                                    it.operational && it.id != machine.id
                                }?.id
                                transferDialogOpen = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("修改本次游玩偏好") },
                            enabled = machine.capacity > 1 && !registration.pendingCheckIn,
                            onClick = {
                                actionMenuOpen = false
                                selectedPreference = registration.preference
                                preferenceDialogOpen = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("退出排队", color = Destructive) },
                            onClick = {
                                actionMenuOpen = false
                                prompt = ManagementActionPrompt(
                                    "移除这份登记？",
                                    "“${registration.displayId}”会永久退出当前队列。",
                                    "确认退出排队",
                                    request(
                                        ManagementQueueAction.REMOVE_REGISTRATIONS,
                                        reason = "管理后台移除登记"
                                    ),
                                    destructive = true
                                )
                            }
                        )
                    }
                }
            }
        }
        Divider(color = Separator.copy(alpha = .65f), modifier = Modifier.padding(top = 6.dp))
    }
    if (transferDialogOpen) {
        AlertDialog(
            onDismissRequest = { transferDialogOpen = false },
            title = { Text("转移到其他机台") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    machines.filter { it.operational && it.id != machine.id }.forEach { target ->
                        OutlinedButton(
                            onClick = { selectedTargetMachineId = target.id },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text(
                                "${target.name} · ${target.registrationCount} 份登记",
                                color = if (selectedTargetMachineId == target.id) {
                                    SystemBlue
                                } else {
                                    PrimaryText
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = machines.firstOrNull { it.id == selectedTargetMachineId }
                        if (target != null) {
                            transferDialogOpen = false
                            prompt = ManagementActionPrompt(
                                "转移到${target.name}？",
                                "“${registration.displayId}”会转入目标机台等待末端。",
                                "确认转移",
                                request(
                                    ManagementQueueAction.TRANSFER_REGISTRATIONS,
                                    targetMachine = target,
                                    reason = "管理后台转移登记"
                                )
                            )
                        }
                    },
                    enabled = selectedTargetMachineId != null
                ) { Text("确认转移") }
            },
            dismissButton = {
                TextButton(onClick = { transferDialogOpen = false }) { Text("取消") }
            }
        )
    }
    if (preferenceDialogOpen) {
        val preferenceOptions = listOf(
            "SOLO" to "单人游玩",
            "OPEN_TO_JOIN" to "允许他人加入"
        )
        AlertDialog(
            onDismissRequest = { preferenceDialogOpen = false },
            title = { Text("修改本次游玩偏好") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    preferenceOptions.forEach { (value, label) ->
                        OutlinedButton(
                            onClick = { selectedPreference = value },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text(
                                label,
                                color = if (selectedPreference == value) SystemBlue else PrimaryText
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        preferenceDialogOpen = false
                        onTerminalAction(
                            request(
                                ManagementQueueAction.CHANGE_PREFERENCE,
                                preference = selectedPreference,
                                reason = "管理后台修改本次游玩偏好"
                            )
                        )
                    },
                    enabled = selectedPreference.isNotBlank()
                ) { Text("确认修改") }
            },
            dismissButton = {
                TextButton(onClick = { preferenceDialogOpen = false }) { Text("取消") }
            }
        )
    }
    if (renameDialogOpen) {
        AlertDialog(
            onDismissRequest = { renameDialogOpen = false },
            title = { Text("修改登记名称") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(18) },
                    label = { Text("登记名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        renameDialogOpen = false
                        onTerminalAction(
                            request(
                                ManagementQueueAction.RENAME_REGISTRATION,
                                displayId = renameDraft.trim(),
                                reason = "管理后台修改登记名称"
                            )
                        )
                    },
                    enabled = renameDraft.isNotBlank() && renameDraft.trim() != registration.displayId
                ) { Text("确认修改") }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogOpen = false }) { Text("取消") }
            }
        )
    }
    if (claimDialogOpen) {
        ManagementProfileChooserDialog(
            title = "关联玩家资料",
            profiles = profiles.filter { profile ->
                machines.none { currentMachine ->
                    (currentMachine.playing + currentMachine.waiting).any {
                        it.profileId == profile.id
                    }
                }
            },
            onDismiss = { claimDialogOpen = false },
            onSelect = { profile ->
                claimDialogOpen = false
                prompt = ManagementActionPrompt(
                    "关联到“${profile.nickname}”？",
                    "临时登记“${registration.displayId}”会改用这份玩家资料的昵称和资料信息。",
                    "确认关联",
                    request(
                        ManagementQueueAction.CLAIM_WITH_PLAYER_PROFILE,
                        profileId = profile.id,
                        preference = profile.defaultPreference.takeIf {
                            it != "ASK_EVERY_TIME"
                        },
                        reason = "管理后台关联临时登记与玩家资料"
                    )
                )
            }
        )
    }
    if (fixedPairDialogOpen) {
        ManagementFixedPairDialog(
            registration = registration,
            machine = machine,
            profiles = profiles,
            registeredProfileIds = machines.flatMap { currentMachine ->
                currentMachine.playing + currentMachine.waiting
            }.mapNotNull(ManagementRegistration::profileId).toSet(),
            onDismiss = { fixedPairDialogOpen = false },
            onPairExisting = { friend ->
                fixedPairDialogOpen = false
                prompt = ManagementActionPrompt(
                    "组成固定组合？",
                    "“${registration.displayId}”与“${friend.displayId}”会作为完整双人位置共同游玩。",
                    "确认组成固定组合",
                    request(
                        ManagementQueueAction.CREATE_FIXED_PAIR,
                        registrationIds = listOf(
                            registration.registrationId,
                            friend.registrationId
                        ),
                        reason = "管理后台组成固定组合"
                    )
                )
            },
            onCreateProfileFriend = { profile, preference ->
                fixedPairDialogOpen = false
                prompt = ManagementActionPrompt(
                    "为“${profile.nickname}”新建朋友登记？",
                    "新登记会与“${registration.displayId}”组成固定组合。",
                    "确认新建并组合",
                    request(
                        ManagementQueueAction.CREATE_FIXED_PAIR_WITH_REGISTRATION,
                        friendProfileId = profile.id,
                        preference = preference,
                        reason = "管理后台新建玩家登记并组成固定组合"
                    )
                )
            },
            onCreateTemporaryFriend = { displayId, preference ->
                fixedPairDialogOpen = false
                prompt = ManagementActionPrompt(
                    "新建朋友临时登记？",
                    "“$displayId”会作为新登记与“${registration.displayId}”组成固定组合。",
                    "确认新建并组合",
                    request(
                        ManagementQueueAction.CREATE_FIXED_PAIR_WITH_REGISTRATION,
                        displayId = displayId,
                        preference = preference,
                        reason = "管理后台新建临时登记并组成固定组合"
                    )
                )
            }
        )
    }
    prompt?.let { currentPrompt ->
        ManagementActionPromptDialog(
            prompt = currentPrompt,
            onDismiss = { prompt = null },
            onConfirm = {
                prompt = null
                onTerminalAction(currentPrompt.request)
            }
        )
    }
}

@Composable
private fun ManagementProfileChooserDialog(
    title: String,
    profiles: List<ManagementProfile>,
    onDismiss: () -> Unit,
    onSelect: (ManagementProfile) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (profiles.isEmpty()) {
                Text("当前没有可用的玩家资料。", color = SecondaryText)
            } else {
                LazyColumn(
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        OutlinedButton(
                            onClick = { onSelect(profile) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(profile.nickname, color = PrimaryText)
                                Text(
                                    listOfNotNull(
                                        profile.publicPlayerId?.let { "玩家 $it" },
                                        profile.qqNumber?.let { "QQ $it" }
                                    ).joinToString(" · "),
                                    color = SecondaryText,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ManagementFixedPairDialog(
    registration: ManagementRegistration,
    machine: ManagementMachine,
    profiles: List<ManagementProfile>,
    registeredProfileIds: Set<String>,
    onDismiss: () -> Unit,
    onPairExisting: (ManagementRegistration) -> Unit,
    onCreateProfileFriend: (ManagementProfile, String?) -> Unit,
    onCreateTemporaryFriend: (String, String) -> Unit
) {
    var mode by remember { mutableStateOf("EXISTING") }
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var temporaryName by remember { mutableStateOf("") }
    var preference by remember { mutableStateOf("SOLO") }
    val candidates = machine.waiting.filter {
        it.registrationId != registration.registrationId && !it.pendingCheckIn
    }
    val availableProfiles = profiles.filter { it.id !in registeredProfileIds }
    val selectedProfile = availableProfiles.firstOrNull { it.id == selectedProfileId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("与朋友组成固定组合") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "EXISTING" to "现有登记",
                        "PROFILE" to "玩家资料",
                        "TEMPORARY" to "临时登记"
                    ).forEach { (value, label) ->
                        OutlinedButton(
                            onClick = { mode = value },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 7.dp)
                        ) {
                            Text(
                                label,
                                color = if (mode == value) SystemBlue else PrimaryText,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                when (mode) {
                    "EXISTING" -> LazyColumn(
                        modifier = Modifier.height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(candidates, key = { it.registrationId }) { candidate ->
                            OutlinedButton(
                                onClick = { onPairExisting(candidate) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(candidate.displayId) }
                        }
                    }
                    "PROFILE" -> {
                        LazyColumn(
                            modifier = Modifier.height(220.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(availableProfiles, key = { it.id }) { profile ->
                                OutlinedButton(
                                    onClick = {
                                        selectedProfileId = profile.id
                                        preference = profile.defaultPreference.takeIf {
                                            it != "ASK_EVERY_TIME"
                                        } ?: "SOLO"
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        profile.nickname,
                                        color = if (selectedProfileId == profile.id) {
                                            SystemBlue
                                        } else {
                                            PrimaryText
                                        }
                                    )
                                }
                            }
                        }
                        if (selectedProfile?.defaultPreference == "ASK_EVERY_TIME") {
                            ManagementPreferenceSelector(preference) { preference = it }
                        }
                    }
                    else -> {
                        OutlinedTextField(
                            value = temporaryName,
                            onValueChange = { temporaryName = it.take(18) },
                            label = { Text("朋友的登记名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ManagementPreferenceSelector(preference) { preference = it }
                    }
                }
            }
        },
        confirmButton = {
            when (mode) {
                "PROFILE" -> Button(
                    onClick = {
                        selectedProfile?.let {
                            onCreateProfileFriend(it, preference)
                        }
                    },
                    enabled = selectedProfile != null
                ) { Text("新建并组合") }
                "TEMPORARY" -> Button(
                    onClick = {
                        onCreateTemporaryFriend(temporaryName.trim(), preference)
                    },
                    enabled = temporaryName.isNotBlank()
                ) { Text("新建并组合") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ManagementPreferenceSelector(
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("SOLO" to "单人游玩", "OPEN_TO_JOIN" to "允许加入").forEach { option ->
            OutlinedButton(
                onClick = { onSelect(option.first) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    option.second,
                    color = if (selected == option.first) SystemBlue else PrimaryText,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun ManagementProfilesPage(
    profiles: List<ManagementProfile>,
    loading: Boolean,
    pendingProfileIds: Set<String>,
    onEdit: (ManagementProfile) -> Unit,
    onPassword: (ManagementProfile) -> Unit
) {
    if (loading && profiles.isEmpty()) {
        LoadingManagementPage()
        return
    }
    if (profiles.isEmpty()) {
        EmptyManagementPage("暂无玩家资料")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("玩家资料库", color = PrimaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 2.dp))
        }
        items(profiles, key = { it.id }) { profile ->
                Surface(color = CardBackground, shape = RoundedCornerShape(CardRadius), tonalElevation = 1.dp) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.nickname, color = PrimaryText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                text = listOfNotNull(profile.publicPlayerId?.let { "玩家编号 $it" }, profile.qqNumber?.let { "QQ $it" }).joinToString(" · ").ifBlank { "未填写 QQ" },
                                color = SecondaryText,
                                style = MaterialTheme.typography.labelSmall
                            )
                            }
                            Text("v${profile.profileRevision}", color = TertiaryText, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append(if (profile.webAccountBound) "网页后台已绑定" else "网页后台未绑定")
                            append(" · ")
                            append(if (profile.terminalEditingAllowed) "终端可编辑" else "终端编辑已锁定")
                        },
                        color = if (profile.terminalEditingAllowed) SecondaryText else AbsenceStatusColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onEdit(profile) },
                            enabled = profile.id !in pendingProfileIds,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 7.dp)
                        ) {
                            Text("编辑资料", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { onPassword(profile) },
                            enabled = profile.webAccountBound && profile.id !in pendingProfileIds,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 7.dp)
                        ) {
                            Text("修改密码", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagementProfileEditorDialog(
    profile: ManagementProfile,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        nickname: String,
        gender: String,
        defaultPreference: String,
        qqVisibility: String,
        terminalEditingAllowed: Boolean,
        visitedVenuesPublic: Boolean
    ) -> Unit
) {
    var nickname by remember(profile.id) { mutableStateOf(profile.nickname) }
    var gender by remember(profile.id) { mutableStateOf(profile.gender) }
    var defaultPreference by remember(profile.id) { mutableStateOf(profile.defaultPreference) }
    var qqVisibility by remember(profile.id) { mutableStateOf(profile.qqVisibility) }
    var terminalEditingAllowed by remember(profile.id) {
        mutableStateOf(profile.terminalEditingAllowed)
    }
    var visitedVenuesPublic by remember(profile.id) {
        mutableStateOf(profile.visitedVenuesPublic)
    }
    var genderMenuOpen by remember { mutableStateOf(false) }
    var preferenceMenuOpen by remember { mutableStateOf(false) }
    var visibilityMenuOpen by remember { mutableStateOf(false) }
    val genderOptions = listOf("MALE" to "男", "FEMALE" to "女", "UNDISCLOSED" to "未公开")
    val preferenceOptions = listOf(
        "SOLO" to "单人游玩",
        "OPEN_TO_JOIN" to "允许他人加入",
        "ASK_EVERY_TIME" to "每次询问"
    )
    val visibilityOptions = listOf(
        "TERMINAL_ONLY" to "仅现场终端",
        "PUBLIC_WEBSITE" to "网页公开"
    )
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("编辑玩家资料") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("昵称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Box {
                    OutlinedButton(
                        onClick = { genderMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Text("性别：${genderOptions.firstOrNull { it.first == gender }?.second ?: gender}", modifier = Modifier.fillMaxWidth())
                    }
                    DropdownMenu(
                        expanded = genderMenuOpen,
                        onDismissRequest = { genderMenuOpen = false }
                    ) {
                        genderOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    gender = value
                                    genderMenuOpen = false
                                }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(
                        onClick = { preferenceMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Text("默认偏好：${preferenceOptions.firstOrNull { it.first == defaultPreference }?.second ?: defaultPreference}", modifier = Modifier.fillMaxWidth())
                    }
                    DropdownMenu(
                        expanded = preferenceMenuOpen,
                        onDismissRequest = { preferenceMenuOpen = false }
                    ) {
                        preferenceOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    defaultPreference = value
                                    preferenceMenuOpen = false
                                }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(
                        onClick = { visibilityMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Text("QQ 可见性：${visibilityOptions.firstOrNull { it.first == qqVisibility }?.second ?: qqVisibility}", modifier = Modifier.fillMaxWidth())
                    }
                    DropdownMenu(
                        expanded = visibilityMenuOpen,
                        onDismissRequest = { visibilityMenuOpen = false }
                    ) {
                        visibilityOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    qqVisibility = value
                                    visibilityMenuOpen = false
                                }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = terminalEditingAllowed,
                        onCheckedChange = { terminalEditingAllowed = it }
                    )
                    Text("允许现场终端编辑资料", color = PrimaryText, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = visitedVenuesPublic,
                        onCheckedChange = { visitedVenuesPublic = it }
                    )
                    Text("允许网页公开到访机厅", color = PrimaryText, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        nickname.trim(),
                        gender,
                        defaultPreference,
                        qqVisibility,
                        terminalEditingAllowed,
                        visitedVenuesPublic
                    )
                },
                enabled = !busy && nickname.trim().isNotBlank()
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("提交修改")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}

@Composable
private fun ManagementPasswordDialog(
    profile: ManagementProfile,
    onDismiss: () -> Unit,
    onSubmit: (password: String, confirmation: String) -> Unit
) {
    var password by remember(profile.id) { mutableStateOf("") }
    var confirmation by remember(profile.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改“${profile.nickname}”的网页密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("再次输入新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(password, confirmation) },
                enabled = password.isNotBlank() && password == confirmation
            ) { Text("修改密码") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ManagementCapabilitiesPage(
    overview: ManagementOverview?,
    loading: Boolean,
    busy: Boolean,
    onSubmit: (ManagementTerminalPolicy) -> Unit
) {
    if (loading && overview == null) {
        LoadingManagementPage()
        return
    }
    if (overview == null) {
        EmptyManagementPage("暂无权限信息")
        return
    }
    val capabilities = overview.capabilities
    val terminalPolicy = overview.terminalPolicy
    var draft by remember(terminalPolicy) { mutableStateOf(terminalPolicy) }
    val draftChanged = draft != terminalPolicy
    val entries = listOf(
        "查看完整队列" to capabilities.queueReadAll,
        "编辑所有登记" to capabilities.queueEditAll,
        "调整队列顺序" to capabilities.queueReorder,
        "查看私有玩家资料" to capabilities.profileReadPrivate,
        "编辑所有玩家资料" to capabilities.profileEditAll,
        "修改玩家密码" to capabilities.profileResetPassword,
        "修改终端敏感策略" to capabilities.terminalPolicyEdit,
        "查看审计记录" to capabilities.auditRead
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Surface(color = CardBackground, shape = RoundedCornerShape(CardRadius), tonalElevation = 1.dp) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = SystemBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("管理权限", color = PrimaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("敏感设置由管理后台统一控制；正常拖动队列排序属于日常队列操作，不受此策略限制。", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Surface(
                color = CardBackground,
                shape = RoundedCornerShape(CardRadius),
                tonalElevation = 1.dp
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = SystemBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "终端敏感策略",
                                color = PrimaryText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (terminalPolicy.supported) {
                                    if (terminalPolicy.managementAppBound) {
                                        "管理后台已接管终端设置 · 版本 ${terminalPolicy.revision}"
                                    } else {
                                        "终端支持策略接管 · 当前未绑定"
                                    }
                                } else {
                                    "当前终端版本不支持远程策略接管"
                                },
                                color = SecondaryText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (!terminalPolicy.supported) {
                        Text(
                            "请先将现场终端更新到支持管理策略的版本。正常拖动全队列排序不会受到管理后台绑定影响。",
                            color = SecondaryText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        ManagementPolicySwitchRow(
                            title = "允许线上登记",
                            checked = draft.allowOnlineRegistration,
                            enabled = !busy,
                            onCheckedChange = {
                                draft = draft.copy(allowOnlineRegistration = it)
                            }
                        )
                        ManagementPolicySwitchRow(
                            title = "允许暂缓一次",
                            checked = draft.allowDeferOneRound,
                            enabled = !busy,
                            onCheckedChange = {
                                draft = draft.copy(allowDeferOneRound = it)
                            }
                        )
                        ManagementPolicySwitchRow(
                            title = "允许暂时离开",
                            checked = draft.allowTemporaryLeave,
                            enabled = !busy,
                            onCheckedChange = {
                                draft = draft.copy(allowTemporaryLeave = it)
                            }
                        )
                        ManagementPolicySwitchRow(
                            title = "QQ Bot 联动",
                            checked = draft.oneBotSyncEnabled,
                            enabled = !busy,
                            onCheckedChange = {
                                draft = draft.copy(oneBotSyncEnabled = it)
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "绑定后现场终端不能修改以上设置；队列中的正常拖动排序和现场执行仍由终端负责。",
                            color = TertiaryText,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onSubmit(draft.copy(managementAppBound = true))
                                },
                                enabled = !busy && (!terminalPolicy.managementAppBound || draftChanged),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Text(
                                        if (terminalPolicy.managementAppBound) "保存策略" else "绑定并接管"
                                    )
                                }
                            }
                            if (terminalPolicy.managementAppBound) {
                                OutlinedButton(
                                    onClick = {
                                        onSubmit(draft.copy(managementAppBound = false))
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("解除接管")
                                }
                            }
                        }
                    }
                }
            }
        }
        itemsIndexed(entries) { _, entry ->
            val (label, enabled) = entry
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(ControlRadius))
                    .border(1.dp, Separator.copy(alpha = .65f), RoundedCornerShape(ControlRadius))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, modifier = Modifier.weight(1f), color = PrimaryText, style = MaterialTheme.typography.bodyMedium)
                Text(if (enabled) "已开放" else "已关闭", color = if (enabled) OnlineRegistrationStatusColor else TertiaryText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ManagementPolicySwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = if (enabled) PrimaryText else TertiaryText,
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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
internal fun LoadingManagementPage() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = SystemBlue)
    }
}

@Composable
internal fun EmptyManagementPage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = SecondaryText, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun formatManagementTime(millis: Long): String {
    if (millis <= 0L) return "尚未同步"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
