package com.abcccc.maimaiqueue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek

internal data class ManagementSettingsDraft(
    val showCommonPlayPreview: Boolean,
    val businessHours: BusinessHoursSettings,
    val machineGroups: List<MachineGroupConfiguration>,
    val defaultMachineGroupId: String,
    val machines: List<ManagementMachine>
)

private data class ManagementMachineDraftSource(
    val id: String,
    val name: String,
    val stableId: String?,
    val groupId: String,
    val operational: Boolean,
    val stopReason: String?,
    val stopReasonDetail: String?,
    val configuration: MachineConfiguration
)

@Composable
internal fun ManagementSettingsPage(
    overview: ManagementOverview?,
    loading: Boolean,
    settingsBusy: Boolean,
    registrationBusy: Boolean,
    statusBusy: Boolean,
    policyBusy: Boolean,
    onPolicySubmit: (ManagementTerminalPolicy) -> Unit,
    onRegistrationOpenChange: (Boolean) -> Unit,
    onSave: (ManagementSettingsDraft) -> Unit,
    onMachineStatus: (ManagementMachine, Boolean, String?, String?) -> Unit
) {
    if (loading && overview == null) {
        LoadingManagementPage()
        return
    }
    if (overview == null) {
        EmptyManagementPage("暂无终端设置")
        return
    }
    val terminalSettings = overview.terminalSettings
    val canEdit = terminalSettings.supported && overview.terminalPolicy.managementAppBound
    val canEditRiskSensitiveSettings = canEdit && !overview.registrationOpen
    var showPreview by remember(terminalSettings) {
        mutableStateOf(terminalSettings.showCommonPlayPreview)
    }
    var businessHours by remember(terminalSettings) {
        mutableStateOf(terminalSettings.businessHours)
    }
    var groups by remember(terminalSettings) {
        mutableStateOf(terminalSettings.machineGroups)
    }
    var defaultGroupId by remember(terminalSettings) {
        mutableStateOf(terminalSettings.defaultMachineGroupId)
    }
    val machineDraftSource = overview.machines.map { machine ->
        ManagementMachineDraftSource(
            id = machine.id,
            name = machine.name,
            stableId = machine.stableId,
            groupId = machine.groupId,
            operational = machine.operational,
            stopReason = machine.stopReason,
            stopReasonDetail = machine.stopReasonDetail,
            configuration = machine.configuration
        )
    }
    var machines by remember(machineDraftSource, terminalSettings.revision) {
        mutableStateOf(overview.machines)
    }
    var editingMachine by remember { mutableStateOf<ManagementMachine?>(null) }
    var stoppingMachine by remember { mutableStateOf<ManagementMachine?>(null) }
    var closeRegistrationConfirm by remember { mutableStateOf(false) }

    fun normalizedGroups(): List<MachineGroupConfiguration> = groups.mapIndexed { index, group ->
        group.copy(name = normalizeMachineGroupName(group.name, index))
    }.distinctBy { it.id }.ifEmpty {
        listOf(MachineGroupConfiguration(DEFAULT_MACHINE_GROUP_ID, DEFAULT_MACHINE_GROUP_NAME))
    }

    fun normalizedDraft(): ManagementSettingsDraft {
        val validGroups = normalizedGroups()
        val validGroupIds = validGroups.mapTo(linkedSetOf()) { it.id }
        val fallbackGroup = validGroups.first().id
        val selectedDefault = defaultGroupId.takeIf(validGroupIds::contains) ?: fallbackGroup
        val normalizedMachines = machines.map { machine ->
            val groupId = machine.groupId.takeIf(validGroupIds::contains) ?: selectedDefault
            val remark = machine.configuration.remark.trim().ifBlank { "机台 ${machine.id}" }
            machine.copy(
                name = "$remark·机台 ${machine.id}",
                groupId = groupId,
                configuration = machine.configuration.copy(remark = remark)
            )
        }
        return ManagementSettingsDraft(
            showCommonPlayPreview = showPreview,
            businessHours = businessHours.normalized(),
            machineGroups = validGroups,
            defaultMachineGroupId = selectedDefault,
            machines = normalizedMachines
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SurfaceLikeSettingsCard {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = SystemBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("终端设置", color = PrimaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            when {
                                !terminalSettings.supported -> "当前终端版本不支持管理后台设置接管"
                                !overview.terminalPolicy.managementAppBound -> "请先在权限页绑定并接管终端设置"
                                else -> "设置修订 ${terminalSettings.revision} · 修改后由现场终端执行"
                            },
                            color = SecondaryText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        item {
            ManagementTerminalPolicyCard(
                policy = overview.terminalPolicy,
                busy = policyBusy,
                onSubmit = onPolicySubmit
            )
        }
        item {
            SurfaceLikeSettingsCard {
                ManagementSettingsSwitchRow(
                    title = "开放登记排队",
                    checked = overview.registrationOpen,
                    enabled = canEdit && !registrationBusy,
                    onCheckedChange = { open ->
                        if (open) onRegistrationOpenChange(true) else closeRegistrationConfirm = true
                    }
                )
                Text(
                    if (overview.registrationOpen) "关闭登记会清空当前全部登记，必须再次确认。" else "登记已关闭；开启后现场终端会创建新的空队列。",
                    color = TertiaryText,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        item {
            SurfaceLikeSettingsCard {
                ManagementSettingsSwitchRow(
                    title = "共同游玩预览",
                    checked = showPreview,
                    enabled = canEdit && !settingsBusy,
                    onCheckedChange = { showPreview = it }
                )
                Spacer(Modifier.height(6.dp))
                ManagementBusinessHoursEditor(
                    value = businessHours,
                    enabled = canEdit && !settingsBusy,
                    onChange = { businessHours = it }
                )
            }
        }
        item {
            SurfaceLikeSettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = SystemBlue, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("机台分组", color = PrimaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            groups = groups + MachineGroupConfiguration(newMachineInternalId(), "分组 ${groups.size + 1}")
                        },
                        enabled = canEdit && !settingsBusy && groups.size < machines.size.coerceAtLeast(1)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "新增分组", tint = SystemBlue)
                    }
                }
                Spacer(Modifier.height(6.dp))
                groups.forEachIndexed { index, group ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = group.name,
                            onValueChange = { value ->
                            groups = groups.mapIndexed { groupIndex, current ->
                                    if (groupIndex == index) {
                                        current.copy(name = limitMachineGroupNameLength(value))
                                    } else current
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("分组 ${index + 1}") },
                            singleLine = true,
                            enabled = canEdit && !settingsBusy
                        )
                        IconButton(
                            onClick = {
                                val used = machines.any { it.groupId == group.id }
                                if (!used && groups.size > 1) {
                                    groups = groups.filterNot { it.id == group.id }
                                    if (defaultGroupId == group.id) defaultGroupId = groups.first().id
                                }
                            },
                            enabled = canEdit && !settingsBusy && groups.size > 1 && machines.none { it.groupId == group.id }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "删除分组", tint = Destructive)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (groups.isNotEmpty()) {
                    ManagementGroupSelector(
                        title = "默认分组",
                        selectedId = defaultGroupId,
                        groups = groups,
                        enabled = canEdit && !settingsBusy,
                        onSelected = { defaultGroupId = it }
                    )
                }
            }
        }
        item {
            SurfaceLikeSettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("机台管理", color = PrimaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            val nextMachineId = MachineId.entries[machines.size]
                            val nextId = nextMachineId.name
                            val groupId = defaultGroupId.takeIf { id -> groups.any { it.id == id } }
                                ?: groups.firstOrNull()?.id
                                ?: DEFAULT_MACHINE_GROUP_ID
                            val configuration = DEFAULT_MACHINE_CONFIGURATIONS.getValue(nextMachineId)
                            val newMachine = ManagementMachine(
                                id = nextId,
                                name = "${configuration.remark}·机台 $nextId",
                                stableId = newMachineInternalId(),
                                groupId = groupId,
                                capacity = configuration.capacity,
                                operational = true,
                                stopReason = null,
                                stopReasonDetail = null,
                                configuration = configuration,
                                registrationCount = 0,
                                playing = emptyList(),
                                waiting = emptyList()
                            )
                            editingMachine = newMachine
                        },
                        enabled = canEditRiskSensitiveSettings && !settingsBusy &&
                            machines.size < MachineId.entries.size,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("增加机台", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (overview.registrationOpen) {
                    Text(
                        "机台数量、游玩容量和计划时间需要先关闭登记排队；备注、分组和展示信息仍可修改。",
                        color = TertiaryText,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(8.dp))
                }
                machines.forEach { machine ->
                    ManagementSettingsMachineRow(
                        machine = machine,
                        groups = groups,
                        enabled = canEdit && !settingsBusy,
                        riskSensitiveEditEnabled = canEditRiskSensitiveSettings && !settingsBusy,
                        statusBusy = statusBusy,
                        canRemove = machines.size > 1 && machine.id == machines.lastOrNull()?.id,
                        onEdit = { editingMachine = machine },
                        onRemove = {
                            if (machine.id == machines.lastOrNull()?.id) {
                                machines = machines.filterNot { it.id == machine.id }
                            }
                        },
                        onStatus = { stoppingMachine = machine }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (machines.isEmpty()) {
                    Text("至少保留一台机台。", color = Destructive, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { onSave(normalizedDraft()) },
                    enabled = canEdit && !settingsBusy && machines.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 9.dp)
                ) {
                    if (settingsBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("保存终端设置")
                    }
                }
            }
        }
    }

    if (closeRegistrationConfirm) {
        AlertDialog(
            onDismissRequest = { if (!registrationBusy) closeRegistrationConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Destructive) },
            title = { Text("关闭登记排队？") },
            text = { Text("关闭后现场终端会清空当前全部登记并创建新的空队列。此操作需要现场终端在线。") },
            confirmButton = {
                Button(
                    onClick = {
                        closeRegistrationConfirm = false
                        onRegistrationOpenChange(false)
                    },
                    enabled = !registrationBusy
                ) { Text("确认关闭") }
            },
            dismissButton = { TextButton(onClick = { closeRegistrationConfirm = false }, enabled = !registrationBusy) { Text("取消") } }
        )
    }
    editingMachine?.let { machine ->
        ManagementMachineEditDialog(
            machine = machine,
            groups = groups,
            busy = settingsBusy,
            allowRiskSensitiveChanges = !overview.registrationOpen,
            onDismiss = { if (!settingsBusy) editingMachine = null },
            onSubmit = { updated ->
                machines = if (machines.any { it.id == updated.id }) {
                    machines.map { if (it.id == updated.id) updated else it }
                } else {
                    machines + updated
                }
                editingMachine = null
            }
        )
    }
    stoppingMachine?.let { machine ->
        ManagementMachineStatusDialog(
            machine = machine,
            busy = statusBusy,
            onDismiss = { if (!statusBusy) stoppingMachine = null },
            onSubmit = { operational, reason, detail ->
                stoppingMachine = null
                onMachineStatus(machine, operational, reason, detail)
            }
        )
    }
}

@Composable
private fun SurfaceLikeSettingsCard(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        color = CardBackground,
        shape = RoundedCornerShape(CardRadius),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) { content() }
    }
}

@Composable
private fun ManagementTerminalPolicyCard(
    policy: ManagementTerminalPolicy,
    busy: Boolean,
    onSubmit: (ManagementTerminalPolicy) -> Unit
) {
    if (!policy.supported) {
        SurfaceLikeSettingsCard {
            Text("终端敏感策略", color = PrimaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("当前终端版本不支持管理后台接管。", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    var draft by remember(policy) { mutableStateOf(policy) }
    val changed = draft != policy
    SurfaceLikeSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("终端敏感策略", color = PrimaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(if (policy.managementAppBound) "已接管" else "未接管", color = if (policy.managementAppBound) OnlineRegistrationStatusColor else TertiaryText, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        ManagementSettingsSwitchRow("允许线上登记", draft.allowOnlineRegistration, !busy) { draft = draft.copy(allowOnlineRegistration = it) }
        ManagementSettingsSwitchRow("允许暂缓一次", draft.allowDeferOneRound, !busy) { draft = draft.copy(allowDeferOneRound = it) }
        ManagementSettingsSwitchRow("允许暂时离开", draft.allowTemporaryLeave, !busy) { draft = draft.copy(allowTemporaryLeave = it) }
        ManagementSettingsSwitchRow("QQ Bot 联动", draft.oneBotSyncEnabled, !busy) { draft = draft.copy(oneBotSyncEnabled = it) }
        Spacer(Modifier.height(4.dp))
        Text("绑定后现场终端不能修改以上敏感设置；正常拖动队列排序仍由终端负责。", color = TertiaryText, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSubmit(draft.copy(managementAppBound = true)) },
                enabled = !busy && (!policy.managementAppBound || changed),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(if (policy.managementAppBound) "保存策略" else "绑定并接管")
            }
            if (policy.managementAppBound) {
                OutlinedButton(
                    onClick = { onSubmit(draft.copy(managementAppBound = false)) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) { Text("解除接管") }
            }
        }
    }
}

@Composable
private fun ManagementSettingsSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), color = if (enabled) PrimaryText else TertiaryText, style = MaterialTheme.typography.bodyMedium)
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
private fun ManagementBusinessHoursEditor(
    value: BusinessHoursSettings,
    enabled: Boolean,
    onChange: (BusinessHoursSettings) -> Unit
) {
    var weekly by remember(value.useWeeklySchedule) { mutableStateOf(value.useWeeklySchedule) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Settings, contentDescription = null, tint = SystemBlue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("营业时间", color = PrimaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = value.enabled,
            onCheckedChange = { onChange(value.copy(enabled = it)) },
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SystemBlue)
        )
    }
    if (value.enabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每周分别设置", modifier = Modifier.weight(1f), color = SecondaryText, style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = weekly,
                onCheckedChange = {
                    weekly = it
                    onChange(value.copy(useWeeklySchedule = it))
                },
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SystemBlue)
            )
        }
        if (weekly) {
            DayOfWeek.entries.forEach { day ->
                val hours = value.hoursFor(day)
                ManagementDailyHoursRow(
                    title = chineseDay(day),
                    hours = hours,
                    enabled = enabled,
                    onChange = { updated ->
                        onChange(value.copy(weeklyHours = value.weeklyHours + (day to updated)))
                    }
                )
            }
        } else {
            ManagementDailyHoursRow(
                title = "每日",
                hours = value.defaultHours,
                enabled = enabled,
                onChange = { onChange(value.copy(defaultHours = it)) }
            )
        }
    }
}

@Composable
private fun ManagementDailyHoursRow(
    title: String,
    hours: DailyBusinessHours,
    enabled: Boolean,
    onChange: (DailyBusinessHours) -> Unit
) {
    var opening by remember(title, hours.openingMinutes) { mutableStateOf(formatBusinessTime(hours.openingMinutes)) }
    var closing by remember(title, hours.closingMinutes) { mutableStateOf(formatBusinessTime(hours.closingMinutes)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.width(42.dp), color = SecondaryText, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = opening,
            onValueChange = { text ->
                opening = text.filter { it.isDigit() || it == ':' }.take(5)
                parseClock(opening)?.let { value -> onChange(hours.copy(openingMinutes = value)) }
            },
            label = { Text("开") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        Text("至", modifier = Modifier.padding(horizontal = 6.dp), color = SecondaryText)
        OutlinedTextField(
            value = closing,
            onValueChange = { text ->
                closing = text.filter { it.isDigit() || it == ':' }.take(5)
                parseClock(closing)?.let { value -> onChange(hours.copy(closingMinutes = value)) }
            },
            label = { Text("关") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ManagementGroupSelector(
    title: String,
    selectedId: String,
    groups: List<MachineGroupConfiguration>,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("$title：${groups.firstOrNull { it.id == selectedId }?.name ?: "未选择"}", modifier = Modifier.fillMaxWidth())
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.name) },
                    onClick = {
                        onSelected(group.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ManagementSettingsMachineRow(
    machine: ManagementMachine,
    groups: List<MachineGroupConfiguration>,
    enabled: Boolean,
    riskSensitiveEditEnabled: Boolean,
    statusBusy: Boolean,
    canRemove: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onStatus: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PageBackground, RoundedCornerShape(ControlRadius))
            .border(1.dp, Separator.copy(alpha = .7f), RoundedCornerShape(ControlRadius))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(machine.name, color = PrimaryText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "${groups.firstOrNull { it.id == machine.groupId }?.name ?: "未分组"} · 容量 ${machine.configuration.capacity} · ${if (machine.operational) "运行中" else "已停止"}",
                    color = if (machine.operational) SecondaryText else Destructive,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = onEdit, enabled = enabled) { Icon(Icons.Default.Edit, contentDescription = "编辑机台", tint = SystemBlue) }
            IconButton(onClick = onRemove, enabled = riskSensitiveEditEnabled && canRemove && machine.registrationCount == 0 && machine.operational) {
                Icon(Icons.Default.Delete, contentDescription = "删除机台", tint = Destructive)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onStatus,
                enabled = enabled && !statusBusy,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (machine.operational) "停止机台" else "恢复机台", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                machine.stopReasonDetail?.takeIf { it.isNotBlank() } ?: machine.stopReason.orEmpty(),
                modifier = Modifier.weight(1f).padding(top = 9.dp),
                color = TertiaryText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun ManagementMachineEditDialog(
    machine: ManagementMachine,
    groups: List<MachineGroupConfiguration>,
    busy: Boolean,
    allowRiskSensitiveChanges: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (ManagementMachine) -> Unit
) {
    var remark by remember(machine.id) { mutableStateOf(machine.configuration.remark) }
    var selectedGroupId by remember(machine.id) { mutableStateOf(machine.groupId) }
    var configuration by remember(machine.id) { mutableStateOf(machine.configuration) }
    var gameTypeExpanded by remember { mutableStateOf(false) }
    var serverExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("编辑${machine.id}机台") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = remark, onValueChange = { remark = limitMachineRemarkLength(it) }, label = { Text("机台备注") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                ManagementGroupSelector("分组", selectedGroupId, groups, !busy) { selectedGroupId = it }
                if (!allowRiskSensitiveChanges) {
                    Text("关闭登记排队后才能修改容量和计划游玩时间。", color = TertiaryText, style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { configuration = configuration.copy(capacity = 1) }, enabled = !busy && allowRiskSensitiveChanges, modifier = Modifier.weight(1f)) { Text(if (configuration.capacity == 1) "单人容量" else "单人") }
                    OutlinedButton(onClick = { configuration = configuration.copy(capacity = 2) }, enabled = !busy && allowRiskSensitiveChanges, modifier = Modifier.weight(1f)) { Text(if (configuration.capacity == 2) "共同容量" else "共同") }
                }
                Box {
                    OutlinedButton(onClick = { gameTypeExpanded = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("游戏类型：${machineGameTypeLabel(configuration.gameType)}", modifier = Modifier.fillMaxWidth()) }
                    DropdownMenu(expanded = gameTypeExpanded, onDismissRequest = { gameTypeExpanded = false }) {
                        MachineGameType.entries.forEach { type ->
                            DropdownMenuItem(text = { Text(machineGameTypeLabel(type)) }, onClick = { configuration = configuration.copy(gameType = type); gameTypeExpanded = false })
                        }
                    }
                }
                if (configuration.gameType == MachineGameType.OTHER) {
                    OutlinedTextField(value = configuration.customGameType, onValueChange = { configuration = configuration.copy(customGameType = limitManagementCodePoints(it, MAX_MACHINE_TYPE_CHARACTERS)) }, label = { Text("自定义游戏类型") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
                Box {
                    OutlinedButton(onClick = { serverExpanded = true }, enabled = !busy && configuration.gameType.supportsServerConfiguration, modifier = Modifier.fillMaxWidth()) { Text("服务器：${machineServerLabel(configuration.server)}", modifier = Modifier.fillMaxWidth()) }
                    DropdownMenu(expanded = serverExpanded, onDismissRequest = { serverExpanded = false }) {
                        MachineServer.entries.forEach { server ->
                            DropdownMenuItem(text = { Text(machineServerLabel(server)) }, onClick = { configuration = configuration.copy(server = server); serverExpanded = false })
                        }
                    }
                }
                if (configuration.server == MachineServer.OTHER) {
                    OutlinedTextField(value = configuration.customServer, onValueChange = { configuration = configuration.copy(customServer = limitManagementCodePoints(it, MAX_MACHINE_SERVER_CHARACTERS)) }, label = { Text("自定义服务器") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(value = configuration.gameVersion, onValueChange = { configuration = configuration.copy(gameVersion = limitManagementCodePoints(it, MAX_GAME_VERSION_CHARACTERS)) }, label = { Text("游戏版本") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = configuration.showGameVersion, onCheckedChange = { configuration = configuration.copy(showGameVersion = it) }, enabled = !busy)
                    Text("显示游戏版本", color = PrimaryText, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = configuration.soloRoundMinutes.toString(), onValueChange = { configuration = configuration.copy(soloRoundMinutes = it.toIntOrNull()?.coerceIn(MIN_PLANNED_ROUND_MINUTES, MAX_PLANNED_ROUND_MINUTES) ?: configuration.soloRoundMinutes) }, label = { Text("单人分钟") }, singleLine = true, enabled = !busy && allowRiskSensitiveChanges, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = configuration.sharedRoundMinutes.toString(), onValueChange = { configuration = configuration.copy(sharedRoundMinutes = it.toIntOrNull()?.coerceIn(MIN_PLANNED_ROUND_MINUTES, MAX_PLANNED_ROUND_MINUTES) ?: configuration.sharedRoundMinutes) }, label = { Text("共同分钟") }, singleLine = true, enabled = !busy && allowRiskSensitiveChanges, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val normalizedRemark = remark.trim().ifBlank { "机台 ${machine.id}" }
                    onSubmit(machine.copy(name = "$normalizedRemark·机台 ${machine.id}", groupId = selectedGroupId, capacity = configuration.capacity, configuration = configuration.copy(remark = normalizedRemark)))
                },
                enabled = !busy && remark.trim().isNotBlank() && groups.any { it.id == selectedGroupId }
            ) { Text("保存机台") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}

@Composable
private fun ManagementMachineStatusDialog(
    machine: ManagementMachine,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (Boolean, String?, String?) -> Unit
) {
    var reason by remember(machine.id) { mutableStateOf("MAINTENANCE") }
    var detail by remember(machine.id) { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val reasons = listOf(
        "NOT_POWERED_ON" to "机台未开机",
        "NETWORK_DISCONNECTED" to "机台断网",
        "MAINTENANCE" to "机台维护",
        "OTHER" to "其他"
    )
    val selectedLabel = reasons.firstOrNull { it.first == reason }?.second ?: reason
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (machine.operational) "停止${machine.name}" else "恢复${machine.name}") },
        text = {
            if (machine.operational) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box {
                        OutlinedButton(onClick = { expanded = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("停止原因：$selectedLabel", modifier = Modifier.fillMaxWidth()) }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            reasons.forEach { item -> DropdownMenuItem(text = { Text(item.second) }, onClick = { reason = item.first; expanded = false }) }
                        }
                    }
                    if (reason == "OTHER") {
                        OutlinedTextField(
                            value = detail,
                            onValueChange = {
                                detail = limitManagementCodePoints(
                                    it,
                                    MAX_MACHINE_STOP_REASON_DETAIL_CHARACTERS
                                )
                            },
                            label = { Text("补充说明") },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                Text("恢复后现场终端会继续保留当前队列，并恢复该机台的计时。", color = SecondaryText)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(!machine.operational, if (machine.operational) reason else null, if (machine.operational && reason == "OTHER") detail.trim() else null) },
                enabled = !busy && (!machine.operational || (reason != "OTHER" || detail.trim().isNotBlank()))
            ) { Text(if (machine.operational) "确认停止" else "确认恢复") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}

private fun parseClock(value: String): Int? {
    val parts = value.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun limitManagementCodePoints(value: String, maximum: Int): String {
    if (value.codePointCount(0, value.length) <= maximum) return value
    return value.substring(0, value.offsetByCodePoints(0, maximum))
}

private fun chineseDay(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}
