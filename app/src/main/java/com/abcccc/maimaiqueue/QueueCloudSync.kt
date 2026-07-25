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
import java.util.Locale
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

internal fun combinedQueueCloudSyncStatus(
    publicStatus: QueueCloudSyncStatus,
    privateFailureDetail: String?
): QueueCloudSyncStatus {
    val privateDetail = privateFailureDetail?.trim()?.takeIf { it.isNotEmpty() }
        ?: return publicStatus
    if (publicStatus.phase == QueueCloudSyncPhase.DISABLED ||
        publicStatus.phase == QueueCloudSyncPhase.NOT_CONFIGURED
    ) {
        return publicStatus
    }
    val combinedDetail = listOfNotNull(
        publicStatus.retryDetail?.takeIf {
            publicStatus.phase == QueueCloudSyncPhase.WAITING_TO_RETRY
        },
        "资料与命令同步：$privateDetail"
    ).distinct().joinToString("；")
    return publicStatus.copy(
        phase = QueueCloudSyncPhase.WAITING_TO_RETRY,
        retryDetail = combinedDetail
    )
}

internal sealed interface QueuePublishResult {
    data object Success : QueuePublishResult
    data class Failure(val detail: String) : QueuePublishResult
}

internal data class PlayerProfileUpdateCommand(
    val commandId: String,
    val profileId: String,
    val qqNumber: String,
    val expectedUpdatedAtMillis: Long,
    val nickname: String,
    val gender: PlayerGender,
    val defaultPreference: ProfilePlayPreference
)

internal sealed interface PlayerProfileCommandDecision {
    data class Apply(val profile: PlayerProfile) : PlayerProfileCommandDecision
    data object AlreadyApplied : PlayerProfileCommandDecision
    data class Reject(val detail: String) : PlayerProfileCommandDecision
}

internal fun decidePlayerProfileUpdate(
    command: PlayerProfileUpdateCommand,
    profiles: List<PlayerProfile>,
    nicknameConflictsWithQueue: (nickname: String, profileId: String) -> Boolean,
    nowMillis: Long = System.currentTimeMillis()
): PlayerProfileCommandDecision {
    val profile = profiles.firstOrNull { it.id == command.profileId }
        ?: return PlayerProfileCommandDecision.Reject("玩家资料已不存在。")
    if (profile.normalizedQqNumber() != command.qqNumber) {
        return PlayerProfileCommandDecision.Reject("玩家资料绑定的 QQ 已发生变化。")
    }
    val desiredAlreadyPresent = profile.nickname == command.nickname &&
        profile.gender == command.gender &&
        profile.defaultPreference == command.defaultPreference
    if (profile.updatedAtMillis != command.expectedUpdatedAtMillis) {
        return if (desiredAlreadyPresent) {
            PlayerProfileCommandDecision.AlreadyApplied
        } else {
            PlayerProfileCommandDecision.Reject("玩家资料已在终端发生更新，请重新提交修改。")
        }
    }
    if (profiles.any {
            it.id != profile.id && it.nickname.equals(command.nickname, ignoreCase = true)
        }
    ) {
        return PlayerProfileCommandDecision.Reject("这个昵称已经用于其他玩家资料。")
    }
    if (nicknameConflictsWithQueue(command.nickname, profile.id)) {
        return PlayerProfileCommandDecision.Reject("这个昵称已经用于当前队列中的其他登记。")
    }
    return if (desiredAlreadyPresent) {
        PlayerProfileCommandDecision.AlreadyApplied
    } else {
        PlayerProfileCommandDecision.Apply(
            profile.copy(
                nickname = command.nickname,
                gender = command.gender,
                defaultPreference = command.defaultPreference,
                updatedAtMillis = nowMillis
            )
        )
    }
}

internal interface QueueCommandClient {
    val isConfigured: Boolean
    val profileSyncFailureDetail: String?
    val commandSyncFailureDetail: String?
    suspend fun fetchPlayerProfiles(): List<PlayerProfile>?
    suspend fun fetchPlayerProfileUpdates(): List<PlayerProfileUpdateCommand>?
    suspend fun complete(commandId: String, applied: Boolean, detail: String): Boolean
}

internal interface QueueStatePublisher {
    val isConfigured: Boolean
    suspend fun publish(
        state: PersistedQueueState,
        auditLogs: List<AuditLogEntry> = emptyList(),
        displaySettings: QueuePublicDisplaySettings = QueuePublicDisplaySettings(),
        playerProfiles: List<PlayerProfile> = emptyList()
    ): QueuePublishResult
}

internal data class QueuePublicDisplaySettings(
    val machineARemark: String = DEFAULT_MACHINE_A_REMARK,
    val machineBRemark: String = DEFAULT_MACHINE_B_REMARK,
    val oneBotSyncEnabled: Boolean = true,
    val businessHours: QueuePublicBusinessHours = QueuePublicBusinessHours()
)

internal data class QueuePublicBusinessHours(
    val enabled: Boolean = false,
    val outsideBusinessHours: Boolean = false,
    val closingSoon: Boolean = false,
    val closingGracePeriod: Boolean = false,
    val closesAtMillis: Long? = null,
    val registrationClosesAtMillis: Long? = null
)

private data class QueuePublishPayload(
    val state: PersistedQueueState,
    val auditLogs: List<AuditLogEntry>,
    val displaySettings: QueuePublicDisplaySettings,
    val playerProfiles: List<PlayerProfile>
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
        displaySettings: QueuePublicDisplaySettings,
        playerProfiles: List<PlayerProfile>
    ): QueuePublishResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildQueueSyncSnapshot(
                    state = state,
                    terminalId = terminalId,
                    capturedAtMillis = System.currentTimeMillis(),
                    auditLogs = auditLogs,
                    displaySettings = displaySettings,
                    playerProfiles = playerProfiles
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
                    setRequestProperty("X-Queue-Schema-Version", SYNC_SCHEMA_VERSION.toString())
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

internal class HttpQueueCommandClient(
    context: Context,
    private val queueStatusEndpoint: String,
    private val token: String
) : QueueCommandClient {
    private val terminalId = LocalTerminalIdentity(context).getOrCreateId()
    private val endpointBase = queueStatusEndpoint.trimEnd('/').substringBeforeLast('/')
    private val commandsEndpoint = endpointBase +
        "/queue-terminal/commands"
    private val profilesEndpoint = endpointBase +
        "/queue-terminal/profiles"

    @Volatile
    override var profileSyncFailureDetail: String? = null
        private set

    @Volatile
    override var commandSyncFailureDetail: String? = null
        private set

    override val isConfigured: Boolean =
        queueStatusEndpoint.startsWith("https://") && token.isNotBlank()

    override suspend fun fetchPlayerProfiles(): List<PlayerProfile>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = openConnection(profilesEndpoint, "GET")
                try {
                    requireSuccessfulResponse(connection)
                    val response = connection.inputStream.bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    val profiles = JSONObject(response).getJSONArray("profiles")
                    buildList {
                        repeat(profiles.length()) { index ->
                            parsePlayerProfile(profiles.optJSONObject(index))?.let(::add)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }.fold(
                onSuccess = { profiles ->
                    profileSyncFailureDetail = null
                    profiles
                },
                onFailure = { error ->
                    Log.w(LOG_TAG, "Player profile fetch failed", error)
                    profileSyncFailureDetail = queuePublishFailureDetail(error)
                    null
                }
            )
        }

    override suspend fun fetchPlayerProfileUpdates(): List<PlayerProfileUpdateCommand>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = openConnection(commandsEndpoint, "GET")
                try {
                    requireSuccessfulResponse(connection)
                    val response = connection.inputStream.bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    val commands = JSONObject(response).getJSONArray("commands")
                    buildList {
                        repeat(commands.length()) { index ->
                            parsePlayerProfileUpdate(commands.optJSONObject(index))?.let(::add)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }.fold(
                onSuccess = { commands ->
                    commandSyncFailureDetail = null
                    commands
                },
                onFailure = { error ->
                    Log.w(LOG_TAG, "Queue command fetch failed", error)
                    commandSyncFailureDetail = queuePublishFailureDetail(error)
                    null
                }
            )
        }

    override suspend fun complete(
        commandId: String,
        applied: Boolean,
        detail: String
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("status", if (applied) "APPLIED" else "REJECTED")
                put("detail", detail.take(MAX_COMMAND_DETAIL_LENGTH))
            }.toString().toByteArray(Charsets.UTF_8)
            val endpoint = "$commandsEndpoint/$commandId/result"
            val connection = openConnection(endpoint, "POST").apply {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                connection.outputStream.use { it.write(body) }
                requireSuccessfulResponse(connection)
            } finally {
                connection.disconnect()
            }
        }.fold(
            onSuccess = {
                commandSyncFailureDetail = null
                true
            },
            onFailure = { error ->
                Log.w(LOG_TAG, "Queue command completion failed", error)
                commandSyncFailureDetail = queuePublishFailureDetail(error)
                false
            }
        )
    }

    private fun openConnection(endpoint: String, method: String): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-Device-ID", terminalId)
            setRequestProperty("X-Queue-Schema-Version", SYNC_SCHEMA_VERSION.toString())
        }

    private fun requireSuccessfulResponse(connection: HttpURLConnection) {
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
    }

    private fun parsePlayerProfileUpdate(command: JSONObject?): PlayerProfileUpdateCommand? {
        if (command == null || command.optString("type") != PROFILE_UPDATE_COMMAND) return null
        val payload = command.optJSONObject("payload") ?: return null
        return runCatching {
            PlayerProfileUpdateCommand(
                commandId = command.getString("command_id"),
                profileId = payload.getString("profile_id"),
                qqNumber = payload.getString("qq_number"),
                expectedUpdatedAtMillis = payload.getLong("expected_updated_at"),
                nickname = payload.getString("nickname").trim(),
                gender = PlayerGender.valueOf(payload.getString("gender")),
                defaultPreference = ProfilePlayPreference.valueOf(
                    payload.getString("default_preference")
                )
            )
        }.getOrNull()?.takeIf { parsed ->
            runCatching { UUID.fromString(parsed.commandId) }.isSuccess &&
                runCatching { UUID.fromString(parsed.profileId) }.isSuccess &&
                isValidQqNumber(parsed.qqNumber) &&
                parsed.expectedUpdatedAtMillis > 0L &&
                parsed.nickname.isNotBlank() &&
                parsed.nickname.codePointCount(0, parsed.nickname.length) <= 18
        }
    }

    private fun parsePlayerProfile(source: JSONObject?): PlayerProfile? {
        if (source == null) return null
        return runCatching {
            val qqNumber = if (source.isNull("qq_number")) {
                null
            } else {
                source.getString("qq_number")
            }
            PlayerProfile(
                id = source.getString("profile_id"),
                nickname = source.getString("nickname").trim(),
                gender = PlayerGender.valueOf(source.getString("gender")),
                defaultPreference = ProfilePlayPreference.valueOf(
                    source.getString("default_preference")
                ),
                qqNumber = qqNumber,
                usageCount = source.getInt("usage_count"),
                lastUsedAtMillis = if (source.isNull("last_used_at")) {
                    null
                } else {
                    source.getLong("last_used_at")
                },
                createdAtMillis = source.getLong("created_at"),
                updatedAtMillis = source.getLong("updated_at")
            ).withCanonicalContact()
        }.getOrNull()?.takeIf { profile ->
            runCatching { UUID.fromString(profile.id) }.isSuccess &&
                profile.nickname.isNotBlank() &&
                profile.nickname.codePointCount(0, profile.nickname.length) <= 18 &&
                profile.usageCount >= 0 &&
                profile.createdAtMillis > 0L &&
                profile.updatedAtMillis > 0L &&
                (profile.qqNumber == null || isValidQqNumber(profile.qqNumber))
        }
    }

    private companion object {
        const val LOG_TAG = "QueueCommandSync"
        const val PROFILE_UPDATE_COMMAND = "UPDATE_PLAYER_PROFILE"
        const val MAX_COMMAND_DETAIL_LENGTH = 500
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
        displaySettings: QueuePublicDisplaySettings = QueuePublicDisplaySettings(),
        playerProfiles: List<PlayerProfile> = emptyList()
    ) {
        if (!enabled) return
        if (!publisher.isConfigured) {
            onStatusChange(QueueCloudSyncStatus(QueueCloudSyncPhase.NOT_CONFIGURED))
            return
        }
        startPublishLoop()
        updates.trySend(QueuePublishPayload(state, auditLogs, displaySettings, playerProfiles))
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
                payloadToPublish.displaySettings,
                payloadToPublish.playerProfiles
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

internal fun buildQueueSyncSnapshot(
    state: PersistedQueueState,
    terminalId: String,
    capturedAtMillis: Long,
    auditLogs: List<AuditLogEntry> = emptyList(),
    displaySettings: QueuePublicDisplaySettings = QueuePublicDisplaySettings(),
    playerProfiles: List<PlayerProfile> = emptyList()
): JSONObject = buildPublicQueueSnapshot(
    state = state,
    terminalId = terminalId,
    capturedAtMillis = capturedAtMillis,
    auditLogs = auditLogs,
    displaySettings = displaySettings
).apply {
    put("schema_version", SYNC_SCHEMA_VERSION)
    val syncableProfiles = playerProfilesForCloudSync(playerProfiles)
    val profilesById = syncableProfiles.associateBy(PlayerProfile::id)
    put(
        "private_player_profiles",
        JSONArray().apply {
            syncableProfiles.forEach { profile ->
                put(
                    JSONObject().apply {
                        put("profile_id", profile.id)
                        put("nickname", profile.nickname)
                        put("gender", profile.gender.name)
                        put("default_preference", profile.defaultPreference.name)
                        put(
                            "qq_number",
                            profile.normalizedQqNumber()
                                ?.takeIf(::isValidQqNumber)
                                ?: JSONObject.NULL
                        )
                        put("usage_count", profile.usageCount)
                        put("last_used_at", profile.lastUsedAtMillis ?: JSONObject.NULL)
                        put("created_at", profile.createdAtMillis)
                        put("updated_at", profile.updatedAtMillis)
                    }
                )
            }
        }
    )
    put(
        "private_player_contacts",
        JSONArray().apply {
            sequenceOf(state.machineA, state.machineB)
                .flatMap { machine -> machine.allRegistrations.asSequence() }
                .mapNotNull { registration ->
                    val profileId = registration.playerProfileId ?: return@mapNotNull null
                    val qqNumber = profilesById[profileId]
                        ?.normalizedQqNumber()
                        ?.takeIf(::isValidQqNumber)
                        ?: return@mapNotNull null
                    JSONObject().apply {
                        put("registration_id", publicRegistrationId(state.queueId, registration.key))
                        put("profile_id", profileId)
                        put("qq_number", qqNumber)
                    }
                }
                .forEach(::put)
        }
    )
}

internal fun playerProfilesForCloudSync(profiles: List<PlayerProfile>): List<PlayerProfile> {
    val normalized = profiles.mapNotNull { profile ->
        val nickname = profile.nickname.trim().takeCodePointsForSync(18)
        if (!isValidUuidForSync(profile.id) || nickname.isBlank()) return@mapNotNull null
        profile.copy(
            nickname = nickname,
            qqNumber = profile.normalizedQqNumber()?.takeIf(::isValidQqNumber),
            usageCount = profile.usageCount.coerceAtLeast(0),
            lastUsedAtMillis = profile.lastUsedAtMillis?.takeIf { it > 0L },
            createdAtMillis = profile.createdAtMillis.coerceAtLeast(1L),
            updatedAtMillis = profile.updatedAtMillis.coerceAtLeast(1L)
        )
    }
    val newestById = normalized.groupBy(PlayerProfile::id).mapValues { (_, matches) ->
        matches.maxBy(PlayerProfile::updatedAtMillis)
    }
    val newestByNickname = newestById.values
        .groupBy { it.nickname.lowercase(Locale.ROOT) }
        .mapValues { (_, matches) -> matches.maxBy(PlayerProfile::updatedAtMillis) }
    val deduplicated = normalized.filter { profile ->
        newestById[profile.id] === profile &&
            newestByNickname[profile.nickname.lowercase(Locale.ROOT)] === profile
    }
    val duplicateQqNumbers = deduplicated.asSequence()
        .mapNotNull(PlayerProfile::normalizedQqNumber)
        .groupingBy { it }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
    return deduplicated.map { profile ->
        if (profile.normalizedQqNumber() in duplicateQqNumbers) {
            profile.copy(qqNumber = null)
        } else {
            profile
        }
    }
}

private fun isValidUuidForSync(value: String): Boolean = runCatching {
    UUID.fromString(value)
}.isSuccess

private fun String.takeCodePointsForSync(maximum: Int): String =
    if (codePointCount(0, length) <= maximum) {
        this
    } else {
        substring(0, offsetByCodePoints(0, maximum))
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
    put("onebot_sync_enabled", displaySettings.oneBotSyncEnabled)
    put(
        "business_hours",
        JSONObject().apply {
            put("enabled", displaySettings.businessHours.enabled)
            put("outside", displaySettings.businessHours.outsideBusinessHours)
            put("closing_soon", displaySettings.businessHours.closingSoon)
            put("closing_grace", displaySettings.businessHours.closingGracePeriod)
            put("closes_at", displaySettings.businessHours.closesAtMillis ?: JSONObject.NULL)
            put(
                "registration_closes_at",
                displaySettings.businessHours.registrationClosesAtMillis ?: JSONObject.NULL
            )
        }
    )
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
        put("title", event.title.takeCodePointsForSync(MAX_PUBLIC_EVENT_TITLE_LENGTH))
        put("detail", event.detail.takeCodePointsForSync(MAX_PUBLIC_EVENT_DETAIL_LENGTH))
        put("operation_source", event.source.name)
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
    put("stop_reason_detail", status.stopReasonDetail ?: JSONObject.NULL)
    put("stopped_at", status.stoppedAtMillis ?: JSONObject.NULL)
    put(
        "playing_started_at",
        queue.playingStartedAtMillis.takeIf { status.isOperational } ?: JSONObject.NULL
    )
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
                            if (status.isOperational) {
                                estimatedMinutesUntilPlaying(
                                    queue = queue,
                                    targetRegistrationKeys = registrationKeys,
                                    nowMillis = capturedAtMillis
                                ) ?: JSONObject.NULL
                            } else {
                                JSONObject.NULL
                            }
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

private const val PUBLIC_SCHEMA_VERSION = 3
private const val SYNC_SCHEMA_VERSION = 3
private const val MAX_PUBLIC_EVENTS_PER_SNAPSHOT = 200
private const val MAX_PUBLIC_EVENT_TITLE_LENGTH = 120
private const val MAX_PUBLIC_EVENT_DETAIL_LENGTH = 2_000
private const val NETWORK_TIMEOUT_MILLIS = 8_000
