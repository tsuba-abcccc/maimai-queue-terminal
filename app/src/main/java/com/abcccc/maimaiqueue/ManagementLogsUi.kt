package com.abcccc.maimaiqueue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ManagementLogsPage(
    endpoint: String,
    token: String,
    overview: ManagementOverview?,
    loadingOverview: Boolean,
    onError: (String?) -> Unit
) {
    if (loadingOverview && overview == null) {
        LoadingManagementPage()
        return
    }
    if (overview == null) {
        EmptyManagementPage("暂无日志")
        return
    }
    val queueId = overview.queueId
    val scope = rememberCoroutineScope()
    var source by remember(queueId) { mutableStateOf("ALL") }
    var logs by remember(queueId, source) { mutableStateOf(emptyList<ManagementLogEntry>()) }
    var nextCursor by remember(queueId, source) { mutableStateOf<Long?>(null) }
    var busy by remember(queueId, source) { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    val sourceOptions = listOf(
        "ALL" to "全部来源",
        "MANAGEMENT_APP" to "管理后台",
        "ON_SITE_TERMINAL" to "现场终端",
        "WEBSITE_REMOTE" to "网页端",
        "MOBILE_DEVICE" to "移动设备",
        "QQ_BOT" to "QQ Bot",
        "SYSTEM_AUTOMATIC" to "系统"
    )

    fun load(reset: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching {
                ManagementApi(endpoint, token).fetchLogs(
                    queueId = queueId,
                    before = if (reset) null else nextCursor,
                    limit = 50,
                    operationSource = source
                )
            }.onSuccess { page ->
                logs = if (reset) page.logs else logs + page.logs
                nextCursor = page.nextCursor
                onError(null)
            }.onFailure { throwable ->
                onError(throwable.message ?: "日志读取失败")
            }
            busy = false
        }
    }

    LaunchedEffect(queueId, source) { load(reset = true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("操作日志", color = PrimaryText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("按来源筛选现场与远程操作记录", color = SecondaryText, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { load(reset = true) }, enabled = !busy) {
                    if (busy && logs.isEmpty()) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = "刷新日志")
                }
            }
        }
        item {
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { filterExpanded = true }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                    Text(sourceOptions.firstOrNull { it.first == source }?.second ?: source, modifier = Modifier.fillMaxWidth())
                }
                DropdownMenu(expanded = filterExpanded, onDismissRequest = { filterExpanded = false }) {
                    sourceOptions.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                source = value
                                filterExpanded = false
                            }
                        )
                    }
                }
            }
        }
        if (logs.isEmpty() && !busy) {
            item { EmptyManagementPage("当前筛选暂无日志") }
        } else {
            items(logs, key = { it.eventId.ifBlank { "cursor-${it.cursor}" } }) { log ->
                ManagementLogCard(log)
            }
            if (nextCursor != null) {
                item {
                    OutlinedButton(
                        onClick = { load(reset = false) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("加载更早日志")
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagementLogCard(log: ManagementLogEntry) {
    Surface(color = CardBackground, tonalElevation = 1.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(CardRadius), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(log.title, color = PrimaryText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(formatManagementTime(log.occurredAtMillis), color = TertiaryText, style = MaterialTheme.typography.labelSmall)
                }
                Text(operationSourceLabel(log.operationSource), color = SystemBlue, style = MaterialTheme.typography.labelSmall)
            }
            if (log.machineName != null || log.machineId != null) {
                Spacer(Modifier.height(5.dp))
                Text(
                    listOfNotNull(log.machineName, log.machineId?.let { "机台 $it" }).joinToString(" · "),
                    color = SecondaryText,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (log.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(log.detail, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
            }
            if (log.registrationIds.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("关联登记：${log.registrationIds.joinToString("、")}", color = TertiaryText, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun operationSourceLabel(value: String): String = when (value) {
    "MANAGEMENT_APP" -> "管理后台"
    "ON_SITE_TERMINAL" -> "现场终端"
    "WEBSITE_REMOTE" -> "网页端"
    "MOBILE_DEVICE" -> "移动设备"
    "QQ_BOT" -> "QQ Bot"
    "SYSTEM_AUTOMATIC" -> "系统"
    else -> value.ifBlank { "未知来源" }
}
