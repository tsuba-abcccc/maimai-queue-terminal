package com.abcccc.maimaiqueue

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.SSLException

enum class QueueCloudSyncPhase {
    DISABLED,
    NOT_CONFIGURED,
    CONFIGURED,
    SYNCING,
    SYNCED,
    WAITING_TO_RETRY
}

data class QueueCloudSyncStatus(
    val phase: QueueCloudSyncPhase,
    val lastSuccessfulAtMillis: Long? = null,
    val retryDetail: String? = null
)

internal sealed interface QueuePublishResult {
    data object Success : QueuePublishResult
    data class Failure(val detail: String) : QueuePublishResult
}

internal interface QueueStatePublisher {
    val isConfigured: Boolean
    suspend fun publish(
        state: PersistedQueueState,
        auditLogs: List<AuditLogEntry> = emptyList(),
        displaySettings: QueuePublicDisplaySettings = QueuePublicDisplaySettings()
    ): QueuePublishResult
}

internal data class QueuePublicDisplaySettings(
    val machineARemark: String = DEFAULT_MACHINE_A_REMARK,
    val machineBRemark: String = DEFAULT_MACHINE_B_REMARK
)

private data class QueuePublishPayload(
    val state: PersistedQueueState,
    val auditLogs: List<AuditLogEntry>,
    val displaySettings: QueuePublicDisplaySettings
)

internal class HttpQueueStatePublisher(
    context: Context,
    private val endpoint: String,
    private val token: String
) : QueueStatePublisher {
    private val terminalId = LocalTerminalIdentity(context).getOrCreateId()

    override val isConfigured: Boolean =
        endpoint.startsWith("https://") && token.isNotBlank()

    override suspend fun publish(
        state: PersistedQueueState,
        auditLogs: List<AuditLogEntry>,
        displaySettings: QueuePublicDisplaySettings
    ): QueuePublishResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildPublicQueueSnapshot(
                    state = state,
                    terminalId = terminalId,
                    capturedAtMillis = System.currentTimeMillis(),
                    auditLogs = auditLogs,
                    displaySettings = displaySettings
                ).toString().toByteArray(Charsets.UTF_8)
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = NETWORK_TIMEOUT_MILLIS
                    readTimeout = NETWORK_TIMEOUT_MILLIS
                    doOutput = true
                    useCaches = false
                    setFixedLengthStreamingMode(body.size)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("X-Device-ID", terminalId)
                    setRequestProperty("X-Queue-Schema-Version", PUBLIC_SCHEMA_VERSION.toString())
                }
                try {
                    connection.outputStream.use { it.write(body) }
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        val serverMessage = connection.errorStream?.bufferedReader(Charsets.UTF_8)
                            ?.use { reader -> reader.readText().take(MAX_ERROR_BODY_LENGTH) }
                            ?.let { responseBody ->
                                runCatching {
                                    JSONObject(responseBody).optString("error").trim()
                                        .takeIf { it.isNotEmpty() }
                                }.getOrNull()
                            }
                        throw QueueEndpointException(responseCode, serverMessage)
                    }
                } finally {
                    connection.disconnect()
                }
            }.fold(
                onSuccess = { QueuePublishResult.Success },
                onFailure = { error ->
                    Log.w(LOG_TAG, "Queue snapshot publish failed", error)
                    QueuePublishResult.Failure(queuePublishFailureDetail(error))
                }
            )
        }

    private companion object {
        const val LOG_TAG = "QueueCloudSync"
        const val MAX_ERROR_BODY_LENGTH = 512
    }
}

private class QueueEndpointException(
    val statusCode: Int,
    val serverMessage: String?
) : Exception("Queue endpoint returned HTTP $statusCode")

private fun queuePublishFailureDetail(error: Throwable): String = when (error) {
    is QueueEndpointException -> error.serverMessage
        ?: "服务器返回 HTTP ${error.statusCode}。"
    is SocketTimeoutException -> "连接服务器超时。"
    is UnknownHostException -> "无法解析服务器地址。"
    is SSLException -> "无法建立安全连接，请检查设备时间或服务器证书。"
    is ConnectException -> "无法连接服务器。"
    is SocketException -> if (error.message?.contains("EPERM") == true) {
        "设备系统拒绝了应用联网，请检查应用联网权限。"
    } else {
        "网络连接被设备中断。"
    }
    else -> "同步请求在发送前失败。"
}

internal class QueueCloudSyncController(
    private val scope: CoroutineScope,
    private val publisher: QueueStatePublisher,
    initiallyEnabled: Boolean = true,
    private val onStatusChange: (QueueCloudSyncStatus) -> Unit
) {
    private val updates = Channel<QueuePublishPayload>(Channel.CONFLATED)
    @Volatile
    private var enabled = initiallyEnabled
    private var publishJob: Job? = null
    private var lastSuccessfulAtMillis: Long? = null

    init {
        if (enabled && publisher.isConfigured) startPublishLoop()
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            publishJob?.cancel()
            publishJob = null
            while (updates.tryReceive().isSuccess) {
                // Discard states queued before the user disabled website sync.
            }
            onStatusChange(
                QueueCloudSyncStatus(
                    phase = QueueCloudSyncPhase.DISABLED,
                    lastSuccessfulAtMillis = lastSuccessfulAtMillis
                )
            )
            return
        }

        if (!publisher.isConfigured) {
            onStatusChange(QueueCloudSyncStatus(QueueCloudSyncPhase.NOT_CONFIGURED))
            return
        }
        onStatusChange(
            QueueCloudSyncStatus(
                phase = QueueCloudSyncPhase.CONFIGURED,
                lastSuccessfulAtMillis = lastSuccessfulAtMillis
            )
        )
        startPublishLoop()
    }

    fun submit(
        state: PersistedQueueState,
        auditLogs: List<AuditLogEntry> = emptyList(),
        displaySettings: QueuePublicDisplaySettings = QueuePublicDisplaySettings()
    ) {
        if (!enabled) return
        if (!publisher.isConfigured) {
            onStatusChange(QueueCloudSyncStatus(QueueCloudSyncPhase.NOT_CONFIGURED))
            return
        }
        startPublishLoop()
        updates.trySend(QueuePublishPayload(state, auditLogs, displaySettings))
    }

    private fun startPublishLoop() {
        if (publishJob?.isActive == true || !enabled || !publisher.isConfigured) return
        publishJob = scope.launch { publishLoop() }
    }

    private suspend fun publishLoop() {
        var latestPayload: QueuePublishPayload? = null
        var waitBeforeNextPublishMillis = 0L
        var retryDelayMillis = INITIAL_RETRY_MILLIS

        while (currentCoroutineContext().isActive && enabled) {
            if (latestPayload == null) {
                latestPayload = updates.receive()
            } else if (waitBeforeNextPublishMillis > 0L) {
                val updatedState = withTimeoutOrNull(waitBeforeNextPublishMillis) {
                    updates.receive()
                }
                if (updatedState != null) latestPayload = updatedState
            }

            waitBeforeNextPublishMillis = 0L
            while (true) {
                val newerState = updates.tryReceive().getOrNull() ?: break
                latestPayload = newerState
            }

            val payloadToPublish = latestPayload ?: continue
            if (!enabled) return
            onStatusChange(
                QueueCloudSyncStatus(
                    phase = QueueCloudSyncPhase.SYNCING,
                    lastSuccessfulAtMillis = lastSuccessfulAtMillis
                )
            )
            when (val result = publisher.publish(
                payloadToPublish.state,
                payloadToPublish.auditLogs,
                payloadToPublish.displaySettings
            )) {
                QueuePublishResult.Success -> {
                    if (!enabled) return
                    lastSuccessfulAtMillis = System.currentTimeMillis()
                    retryDelayMillis = INITIAL_RETRY_MILLIS
                    waitBeforeNextPublishMillis = HEARTBEAT_INTERVAL_MILLIS
                    onStatusChange(
                        QueueCloudSyncStatus(
                            phase = QueueCloudSyncPhase.SYNCED,
                            lastSuccessfulAtMillis = lastSuccessfulAtMillis
                        )
                    )
                }

                is QueuePublishResult.Failure -> {
                    if (!enabled) return
                    waitBeforeNextPublishMillis = retryDelayMillis
                    retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
                    onStatusChange(
                        QueueCloudSyncStatus(
                            phase = QueueCloudSyncPhase.WAITING_TO_RETRY,
                            lastSuccessfulAtMillis = lastSuccessfulAtMillis,
                            retryDetail = result.detail
                        )
                    )
                }
            }
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MILLIS = 30_000L
        const val INITIAL_RETRY_MILLIS = 2_000L
        const val MAX_RETRY_MILLIS = 60_000L
    }
}

internal fun buildPublicQueueSnapshot(
    state: PersistedQueueState,
    terminalId: String,
    capturedAtMillis: Long,
    auditLogs: List<AuditLogEntry> = emptyList(),
    displaySettings: QueuePublicDisplaySettings = QueuePublicDisplaySettings()
): JSONObject = JSONObject().apply {
    put("schema_version", PUBLIC_SCHEMA_VERSION)
    put("queue_id", state.queueId)
    put("revision", state.revision)
    put("captured_at", capturedAtMillis)
    put("registration_open", state.registrationOpen)
    put(
        "terminal",
        JSONObject().apply {
            put("id", terminalId)
            put("online", true)
            put("app_version", BuildConfig.VERSION_NAME)
            put("last_seen_at", capturedAtMillis)
        }
    )
    put(
        "machines",
        JSONObject().apply {
            put(
                "A",
                buildPublicMachine(
                    queueId = state.queueId,
                    machineId = "A",
                    machineName = publicMachineName(
                        displaySettings.machineARemark,
                        DEFAULT_MACHINE_A_REMARK,
                        "A"
                    ),
                    queue = state.machineA,
                    status = state.machineAStatus,
                    capturedAtMillis = capturedAtMillis
                )
            )
            put(
                "B",
                buildPublicMachine(
                    queueId = state.queueId,
                    machineId = "B",
                    machineName = publicMachineName(
                        displaySettings.machineBRemark,
                        DEFAULT_MACHINE_B_REMARK,
                        "B"
                    ),
                    queue = state.machineB,
                    status = state.machineBStatus,
                    capturedAtMillis = capturedAtMillis
                )
            )
        }
    )
    put(
        "recent_events",
        JSONArray().apply {
            auditLogs.asSequence()
                .filter { event ->
                    event.queueId == state.queueId && event.publicEventType != null
                }
                .sortedByDescending(AuditLogEntry::timestampMillis)
                .take(MAX_PUBLIC_EVENTS_PER_SNAPSHOT)
                .forEach { event -> put(buildPublicQueueEvent(state.queueId, event)) }
        }
    )
}

private fun publicMachineName(remark: String, fallback: String, machineId: String): String =
    "${normalizeMachineRemark(remark, fallback)} · 机台 $machineId"

private fun buildPublicQueueEvent(queueId: String, event: AuditLogEntry): JSONObject =
    JSONObject().apply {
        put("event_id", event.id)
        put("occurred_at", event.timestampMillis)
        put("type", event.publicEventType?.name ?: PublicQueueEventType.OTHER.name)
        put(
            "machine_id",
            when (event.category) {
                AuditLogCategory.MACHINE_A -> "A"
                AuditLogCategory.MACHINE_B -> "B"
                else -> JSONObject.NULL
            }
        )
        put("title", event.title.take(MAX_PUBLIC_EVENT_TITLE_LENGTH))
        put("detail", event.detail.take(MAX_PUBLIC_EVENT_DETAIL_LENGTH))
        put(
            "registration_ids",
            JSONArray().apply {
                event.affectedRegistrationKeys.distinct().forEach { registrationKey ->
                    put(publicRegistrationId(queueId, registrationKey))
                }
            }
        )
    }

private fun buildPublicMachine(
    queueId: String,
    machineId: String,
    machineName: String,
    queue: MachineQueue,
    status: MachineStatus,
    capturedAtMillis: Long
): JSONObject = JSONObject().apply {
    put("id", machineId)
    put("name", machineName)
    put("operational", status.isOperational)
    put("stop_reason", status.stopReason?.name ?: JSONObject.NULL)
    put("stopped_at", status.stoppedAtMillis ?: JSONObject.NULL)
    put("playing_started_at", queue.playingStartedAtMillis ?: JSONObject.NULL)
    put("registration_count", queue.registrationCount)
    put("waiting_position_count", queue.waitingPositions().size)
    put(
        "playing",
        JSONArray().apply {
            queue.playing.forEach { registration ->
                put(buildPublicRegistration(queueId, registration))
            }
        }
    )
    put(
        "waiting_positions",
        JSONArray().apply {
            queue.waitingPositions().forEachIndexed { index, registrations ->
                val registrationKeys = registrations.map { it.key }.toSet()
                put(
                    JSONObject().apply {
                        put("index", index + 1)
                        put("position_id", publicPositionId(queueId, registrationKeys))
                        put("fixed_pair", registrations.size == 2 && registrations.all {
                            it.fixedPartnerKey != null && it.fixedPartnerKey in registrationKeys
                        })
                        put(
                            "estimated_wait_minutes",
                            estimatedMinutesUntilPlaying(
                                queue = queue,
                                targetRegistrationKeys = registrationKeys,
                                nowMillis = capturedAtMillis
                            ) ?: JSONObject.NULL
                        )
                        put(
                            "registrations",
                            JSONArray().apply {
                                registrations.forEach { registration ->
                                    put(buildPublicRegistration(queueId, registration))
                                }
                            }
                        )
                    }
                )
            }
        }
    )
}

private fun buildPublicRegistration(queueId: String, registration: Registration): JSONObject =
    JSONObject().apply {
        put("registration_id", publicRegistrationId(queueId, registration.key))
        put("display_id", registration.displayId)
        put("preference", registration.preference.name)
        put(
            "deferred_once",
            registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        put(
            "temporarily_away",
            registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
        )
        put("temporary_away_skipped_turns", registration.temporaryAwaySkippedTurns)
        put("fixed_pair", registration.fixedPartnerKey != null)
        put(
            "fixed_pair_id",
            registration.fixedPartnerKey?.let { partnerKey ->
                publicPositionId(queueId, setOf(registration.key, partnerKey))
            } ?: JSONObject.NULL
        )
        put("no_show_count", registration.noShowCount)
        put("last_no_show_action_was_defer", registration.lastNoShowActionWasDefer)
        put("registration_type", if (registration.isTemporary) "TEMPORARY" else "PLAYER_PROFILE")
        put("created_at", registration.createdAtMillis)
        put("last_played_at", registration.lastPlayedAtMillis ?: JSONObject.NULL)
    }

internal fun publicRegistrationId(queueId: String, registrationKey: Int): String =
    stablePublicId("registration:$queueId:$registrationKey")

private fun publicPositionId(queueId: String, registrationKeys: Set<Int>): String =
    stablePublicId("position:$queueId:${registrationKeys.sorted().joinToString(",")}")

private fun stablePublicId(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .take(12)
    .joinToString("") { byte -> "%02x".format(byte) }

private class LocalTerminalIdentity(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "terminal_identity",
        Context.MODE_PRIVATE
    )

    fun getOrCreateId(): String {
        preferences.getString(KEY_TERMINAL_ID, null)?.takeIf(::isValidUuid)?.let { return it }
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_TERMINAL_ID, created).commit()
        return created
    }

    private fun isValidUuid(value: String): Boolean = runCatching {
        UUID.fromString(value)
    }.isSuccess

    private companion object {
        const val KEY_TERMINAL_ID = "id"
    }
}

private const val PUBLIC_SCHEMA_VERSION = 2
private const val MAX_PUBLIC_EVENTS_PER_SNAPSHOT = 200
private const val MAX_PUBLIC_EVENT_TITLE_LENGTH = 120
private const val MAX_PUBLIC_EVENT_DETAIL_LENGTH = 2_000
private const val NETWORK_TIMEOUT_MILLIS = 8_000
