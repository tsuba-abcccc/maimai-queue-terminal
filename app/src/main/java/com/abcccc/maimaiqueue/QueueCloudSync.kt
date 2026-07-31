package com.abcccc.maimaiqueue

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
    val retryStartedAtMillis: Long? = null,
    val lastErrorAtMillis: Long? = null,
    val retryDetail: String? = null,
    val syncMode: QueueSyncMode = QueueSyncMode.UNSPECIFIED
)

internal fun combinedQueueCloudSyncStatus(
    publicStatus: QueueCloudSyncStatus,
    privateFailureDetail: String?,
    privateFailureRetryStartedAtMillis: Long? = null,
    privateFailureLastErrorAtMillis: Long? = privateFailureRetryStartedAtMillis
): QueueCloudSyncStatus {
    val privateDetail = privateFailureDetail?.trim()?.takeIf { it.isNotEmpty() }
    val combinedLastErrorAtMillis = listOfNotNull(
        publicStatus.lastErrorAtMillis,
        privateFailureLastErrorAtMillis
    ).maxOrNull()
    if (privateDetail == null) {
        return publicStatus.copy(lastErrorAtMillis = combinedLastErrorAtMillis)
    }
    if (publicStatus.phase == QueueCloudSyncPhase.DISABLED ||
        publicStatus.phase == QueueCloudSyncPhase.NOT_CONFIGURED
    ) {
        return publicStatus.copy(lastErrorAtMillis = combinedLastErrorAtMillis)
    }
    val combinedDetail = listOfNotNull(
        publicStatus.retryDetail?.takeIf {
            publicStatus.phase == QueueCloudSyncPhase.WAITING_TO_RETRY
        },
        "资料与命令同步：$privateDetail"
    ).distinct().joinToString("；")
    return publicStatus.copy(
        phase = QueueCloudSyncPhase.WAITING_TO_RETRY,
        retryStartedAtMillis = listOfNotNull(
            publicStatus.retryStartedAtMillis,
            privateFailureRetryStartedAtMillis
        ).minOrNull(),
        lastErrorAtMillis = combinedLastErrorAtMillis,
        retryDetail = combinedDetail
    )
}

internal sealed interface QueuePublishResult {
    data object Success : QueuePublishResult
    data class Failure(val detail: String) : QueuePublishResult
}

internal sealed interface RemoteTerminalCommand {
    val commandId: String
}

internal data class PlayerProfileUpdateCommand(
    override val commandId: String,
    val profileId: String,
    val qqNumber: String,
    val expectedUpdatedAtMillis: Long,
    val nickname: String,
    val gender: PlayerGender,
    val defaultPreference: ProfilePlayPreference,
    val qqVisibility: QqVisibility = QqVisibility.TERMINAL_ONLY,
    val notificationPreferences: QueueNotificationPreferences =
        QueueNotificationPreferences(),
    val setupVersion: Int = 0,
    val expectedRevision: Long = 1L
) : RemoteTerminalCommand

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
        profile.defaultPreference == command.defaultPreference &&
        profile.qqVisibility == command.qqVisibility &&
        profile.notificationPreferences == command.notificationPreferences &&
        profile.setupVersion == command.setupVersion
    if (
        profile.revision != command.expectedRevision ||
        profile.updatedAtMillis != command.expectedUpdatedAtMillis
    ) {
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
                qqVisibility = command.qqVisibility,
                notificationPreferences = command.notificationPreferences,
                setupVersion = command.setupVersion,
                revision = profile.revision + 1L,
                updatedAtMillis = nowMillis
            )
        )
    }
}

internal interface QueueCommandClient {
    val isConfigured: Boolean
    val profileSyncFailureDetail: String?
    val profileSyncLastErrorAtMillis: Long?
    val commandSyncFailureDetail: String?
    val commandSyncLastErrorAtMillis: Long?
    suspend fun fetchPlayerProfiles(): PlayerProfileSyncPayload?
    suspend fun fetchPendingCommands(): List<RemoteTerminalCommand>?
    suspend fun createMobileRegistrationSession(
        requestId: String,
        queueId: String,
        machineId: String
    ): MobileRegistrationSession?
    suspend fun complete(
        commandId: String,
        applied: Boolean,
        detail: String,
        resultRegistrationId: String? = null
    ): Boolean
}

internal data class PlayerProfileSyncPayload(
    val profiles: List<PlayerProfile>,
    val profileAliases: Map<String, String>,
    val botQqNumber: String?
)

internal data class MobileRegistrationSession(
    val sessionId: String,
    val registrationUrl: String,
    val expiresAtMillis: Long
)

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
    val websiteRemoteEnabled: Boolean = true,
    val oneBotSyncEnabled: Boolean = true,
    val syncMode: QueueSyncMode = QueueSyncMode.UNSPECIFIED,
    val allowDeferOneRound: Boolean = true,
    val allowTemporaryLeave: Boolean = true,
    val allowOnlineRegistration: Boolean = true,
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

private data class QueueConnectionConfiguration(
    val endpoint: String,
    val token: String
) {
    val isValid: Boolean
        get() = normalizeQueueSyncEndpoint(endpoint) == endpoint &&
            isValidQueueSyncToken(token)
}

internal val remoteTerminalCommandPollMutex = Mutex()

internal class HttpQueueStatePublisher(
    context: Context,
    endpoint: String,
    token: String
) : QueueStatePublisher {
    private val terminalIdentity = LocalTerminalIdentity(context).getOrCreateRuntimeIdentity()

    @Volatile
    private var configuration = QueueConnectionConfiguration(
        endpoint = endpoint.trim(),
        token = token.trim()
    )

    override val isConfigured: Boolean
        get() = configuration.isValid

    fun updateConfiguration(endpoint: String, token: String) {
        configuration = QueueConnectionConfiguration(
            endpoint = endpoint.trim(),
            token = token.trim()
        )
    }

    override suspend fun publish(
        state: PersistedQueueState,
        auditLogs: List<AuditLogEntry>,
        displaySettings: QueuePublicDisplaySettings,
        playerProfiles: List<PlayerProfile>
    ): QueuePublishResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val requestConfiguration = configuration
                val body = buildQueueSyncSnapshot(
                    state = state,
                    terminalId = terminalIdentity.terminalId,
                    capturedAtMillis = System.currentTimeMillis(),
                    auditLogs = auditLogs,
                    displaySettings = displaySettings,
                    playerProfiles = playerProfiles
                ).toString().toByteArray(Charsets.UTF_8)
                val connection = (
                    URL(requestConfiguration.endpoint).openConnection() as HttpURLConnection
                    ).apply {
                    requestMethod = "POST"
                    connectTimeout = NETWORK_TIMEOUT_MILLIS
                    readTimeout = NETWORK_TIMEOUT_MILLIS
                    doOutput = true
                    useCaches = false
                    setFixedLengthStreamingMode(body.size)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer ${requestConfiguration.token}")
                    setTerminalIdentityHeaders(terminalIdentity)
                    setRequestProperty("X-Queue-Schema-Version", SYNC_SCHEMA_VERSION.toString())
                    displaySettings.syncMode.headerValue?.let { mode ->
                        setRequestProperty("X-Queue-Sync-Mode", mode)
                    }
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
    queueStatusEndpoint: String,
    token: String
) : QueueCommandClient {
    private val terminalIdentity = LocalTerminalIdentity(context).getOrCreateRuntimeIdentity()

    @Volatile
    private var configuration = QueueConnectionConfiguration(
        endpoint = queueStatusEndpoint.trim(),
        token = token.trim()
    )

    private fun terminalEndpoint(configuration: QueueConnectionConfiguration, path: String): String =
        configuration.endpoint.trimEnd('/').substringBeforeLast('/') + path

    @Volatile
    override var profileSyncFailureDetail: String? = null
        private set

    @Volatile
    override var profileSyncLastErrorAtMillis: Long? = null
        private set

    @Volatile
    override var commandSyncFailureDetail: String? = null
        private set

    @Volatile
    override var commandSyncLastErrorAtMillis: Long? = null
        private set

    override val isConfigured: Boolean
        get() = configuration.isValid

    fun updateConfiguration(queueStatusEndpoint: String, token: String) {
        configuration = QueueConnectionConfiguration(
            endpoint = queueStatusEndpoint.trim(),
            token = token.trim()
        )
        profileSyncFailureDetail = null
        profileSyncLastErrorAtMillis = null
        commandSyncFailureDetail = null
        commandSyncLastErrorAtMillis = null
    }

    override suspend fun fetchPlayerProfiles(): PlayerProfileSyncPayload? =
        withContext(Dispatchers.IO) {
            runCatching {
                val requestConfiguration = configuration
                val connection = openConnection(
                    terminalEndpoint(requestConfiguration, "/queue-terminal/profiles"),
                    "GET",
                    requestConfiguration.token
                )
                try {
                    requireSuccessfulResponse(connection)
                    val response = connection.inputStream.bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    parsePlayerProfileSyncPayload(response)
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
                    profileSyncLastErrorAtMillis = System.currentTimeMillis()
                    null
                }
            )
        }

    override suspend fun fetchPendingCommands(): List<RemoteTerminalCommand>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val requestConfiguration = configuration
                val connection = openConnection(
                    terminalEndpoint(requestConfiguration, "/queue-terminal/commands"),
                    "GET",
                    requestConfiguration.token
                )
                try {
                    requireSuccessfulResponse(connection)
                    val response = connection.inputStream.bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    parseRemoteTerminalCommands(response)
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
                    commandSyncLastErrorAtMillis = System.currentTimeMillis()
                    null
                }
            )
        }

    override suspend fun createMobileRegistrationSession(
        requestId: String,
        queueId: String,
        machineId: String
    ): MobileRegistrationSession? = withContext(Dispatchers.IO) {
        runCatching {
            val requestConfiguration = configuration
            val body = JSONObject().apply {
                put("request_id", requestId)
                put("queue_id", queueId)
                put("machine_id", machineId)
            }.toString().toByteArray(Charsets.UTF_8)
            val connection = openConnection(
                terminalEndpoint(requestConfiguration, "/queue-terminal/mobile-registration-sessions"),
                "POST",
                requestConfiguration.token
            ).apply {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                connection.outputStream.use { it.write(body) }
                requireSuccessfulResponse(connection)
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                val payload = JSONObject(response)
                MobileRegistrationSession(
                    sessionId = payload.getString("session_id"),
                    registrationUrl = payload.getString("registration_url"),
                    expiresAtMillis = payload.getLong("expires_at")
                )
            } finally {
                connection.disconnect()
            }
        }.fold(
            onSuccess = { session ->
                commandSyncFailureDetail = null
                session
            },
            onFailure = { error ->
                Log.w(LOG_TAG, "Mobile registration session creation failed", error)
                commandSyncFailureDetail = queuePublishFailureDetail(error)
                commandSyncLastErrorAtMillis = System.currentTimeMillis()
                null
            }
        )
    }

    override suspend fun complete(
        commandId: String,
        applied: Boolean,
        detail: String,
        resultRegistrationId: String?
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val requestConfiguration = configuration
            val body = JSONObject().apply {
                put("status", if (applied) "APPLIED" else "REJECTED")
                put("detail", detail.take(MAX_COMMAND_DETAIL_LENGTH))
                if (applied && resultRegistrationId != null) {
                    put("result_registration_id", resultRegistrationId)
                }
            }.toString().toByteArray(Charsets.UTF_8)
            val endpoint = "${terminalEndpoint(requestConfiguration, "/queue-terminal/commands")}/$commandId/result"
            val connection = openConnection(endpoint, "POST", requestConfiguration.token).apply {
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
                commandSyncLastErrorAtMillis = System.currentTimeMillis()
                false
            }
        )
    }

    private fun openConnection(
        endpoint: String,
        method: String,
        requestToken: String
    ): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $requestToken")
            setTerminalIdentityHeaders(terminalIdentity)
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

    private companion object {
        const val LOG_TAG = "QueueCommandSync"
        const val MAX_COMMAND_DETAIL_LENGTH = 500
        const val MAX_ERROR_BODY_LENGTH = 512
    }
}

internal fun parsePlayerProfileSyncPayload(response: String): PlayerProfileSyncPayload {
    val payload = JSONObject(response)
    val sourceProfiles = payload.getJSONArray("profiles")
    val profiles = buildList {
        repeat(sourceProfiles.length()) { index ->
            parseSyncedPlayerProfile(sourceProfiles.optJSONObject(index))?.let(::add)
        }
    }
    return PlayerProfileSyncPayload(
        profiles = profiles,
        profileAliases = parsePlayerProfileAliases(
            payload.optJSONObject("profile_aliases"),
            profiles.mapTo(mutableSetOf(), PlayerProfile::id)
        ),
        botQqNumber = payload.optionalNonBlankString("bot_qq")
            ?.takeIf(::isValidQqNumber)
    )
}

private fun parseSyncedPlayerProfile(source: JSONObject?): PlayerProfile? {
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
            qqVisibility = QqVisibility.valueOf(
                source.optString("qq_visibility", QqVisibility.TERMINAL_ONLY.name)
            ),
            notificationPreferences = QueueNotificationPreferences(
                enabled = source.optBoolean("notification_enabled", true),
                queueChanges = source.optBoolean("notify_queue_changes", true),
                playingPosition = source.optBoolean("notify_playing_position", false),
                onlineCheckIn = source.optBoolean("notify_online_check_in", true),
                absence = source.optBoolean("notify_absence", true),
                machineStatus = source.optBoolean("notify_machine_status", false)
            ),
            setupVersion = source.optInt("setup_version", 0).coerceAtLeast(0),
            revision = source.optLong("profile_revision", 1L).coerceAtLeast(1L),
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

private fun parsePlayerProfileAliases(
    source: JSONObject?,
    canonicalProfileIds: Set<String>
): Map<String, String> {
    source ?: return emptyMap()
    val rawAliases = buildMap {
        val keys = source.keys()
        while (keys.hasNext() && size < MAX_PROFILE_ALIASES) {
            val sourceId = keys.next()
            val targetId = source.optString(sourceId)
            if (
                sourceId != targetId &&
                sourceId !in canonicalProfileIds &&
                isUuid(sourceId) &&
                isUuid(targetId)
            ) {
                put(sourceId, targetId)
            }
        }
    }
    return buildMap {
        rawAliases.keys.forEach { sourceId ->
            val visited = mutableSetOf<String>()
            var targetId = sourceId
            while (targetId in rawAliases && visited.add(targetId)) {
                targetId = rawAliases.getValue(targetId)
            }
            if (targetId in canonicalProfileIds && targetId !in visited) {
                put(sourceId, targetId)
            }
        }
    }
}

private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

private const val MAX_PROFILE_ALIASES = 500

private const val PROFILE_UPDATE_COMMAND = "UPDATE_PLAYER_PROFILE"
private const val QUEUE_OPERATION_COMMAND = "QUEUE_OPERATION"
private const val MOBILE_REGISTRATION_COMMAND = "MOBILE_DEVICE_REGISTRATION"

internal fun parseRemoteTerminalCommands(response: String): List<RemoteTerminalCommand> {
    val commands = JSONObject(response).getJSONArray("commands")
    return buildList {
        repeat(commands.length()) { index ->
            val source = commands.optJSONObject(index)
            when (source?.optString("type")) {
                PROFILE_UPDATE_COMMAND -> parsePlayerProfileUpdate(source)
                QUEUE_OPERATION_COMMAND -> parseQueueOperation(source)
                MOBILE_REGISTRATION_COMMAND -> parseMobileDeviceRegistration(source)
                else -> null
            }?.let(::add)
        }
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
            ),
            qqVisibility = QqVisibility.valueOf(
                payload.optString("qq_visibility", QqVisibility.TERMINAL_ONLY.name)
            ),
            notificationPreferences = QueueNotificationPreferences(
                enabled = payload.optBoolean("notification_enabled", true),
                queueChanges = payload.optBoolean("notify_queue_changes", true),
                playingPosition = payload.optBoolean("notify_playing_position", false),
                onlineCheckIn = payload.optBoolean("notify_online_check_in", true),
                absence = payload.optBoolean("notify_absence", true),
                machineStatus = payload.optBoolean("notify_machine_status", false)
            ),
            setupVersion = payload.optInt("setup_version", 0).coerceAtLeast(0),
            expectedRevision = payload.optLong("expected_profile_revision", 1L)
        )
    }.getOrNull()?.takeIf { parsed ->
        runCatching { UUID.fromString(parsed.commandId) }.isSuccess &&
            runCatching { UUID.fromString(parsed.profileId) }.isSuccess &&
            isValidQqNumber(parsed.qqNumber) &&
            parsed.expectedUpdatedAtMillis > 0L &&
            parsed.expectedRevision > 0L &&
            parsed.nickname.isNotBlank() &&
            parsed.nickname.codePointCount(0, parsed.nickname.length) <= 18
    }
}

private fun parseQueueOperation(command: JSONObject?): RemoteQueueOperationCommand? {
    if (command == null || command.optString("type") != QUEUE_OPERATION_COMMAND) return null
    val payload = command.optJSONObject("payload") ?: return null
    return runCatching {
        RemoteQueueOperationCommand(
            commandId = command.getString("command_id"),
            createdAtMillis = command.getLong("created_at"),
            queueId = payload.getString("queue_id"),
            profileId = payload.getString("profile_id"),
            actorQq = payload.getString("actor_qq"),
            operation = RemoteQueueOperation.valueOf(payload.getString("operation")),
            source = RemoteQueueOperationSource.valueOf(
                payload.getString("operation_source")
            ),
            machineId = payload.optionalNonBlankString("machine_id"),
            targetMachineId = payload.optionalNonBlankString("target_machine_id"),
            registrationId = payload.optionalNonBlankString("registration_id"),
            preference = payload.optionalNonBlankString("preference")?.let {
                PlayPreference.valueOf(it)
            }
        )
    }.getOrNull()?.takeIf(::isValidQueueOperationCommand)
}

private fun parseMobileDeviceRegistration(
    command: JSONObject?
): MobileDeviceRegistrationCommand? {
    if (command == null || command.optString("type") != MOBILE_REGISTRATION_COMMAND) return null
    val payload = command.optJSONObject("payload") ?: return null
    val profileSource = payload.optJSONObject("profile") ?: return null
    return runCatching {
        val mode = profileSource.getString("mode")
        val completion = profileSource.optJSONObject("completion")?.let { source ->
            MobileProfileCompletion(
                qqNumber = source.getString("qq_number"),
                qqVisibility = QqVisibility.valueOf(source.getString("qq_visibility")),
                notificationPreferences = parseNotificationPreferences(source),
                setupVersion = source.getInt("setup_version")
            )
        }
        val newProfile = profileSource.optJSONObject("profile")?.let { source ->
            MobileNewPlayerProfile(
                nickname = source.getString("nickname").trim(),
                gender = PlayerGender.valueOf(source.getString("gender")),
                defaultPreference = ProfilePlayPreference.valueOf(
                    source.getString("default_preference")
                ),
                qqNumber = source.getString("qq_number"),
                qqVisibility = QqVisibility.valueOf(source.getString("qq_visibility")),
                notificationPreferences = parseNotificationPreferences(source),
                setupVersion = source.getInt("setup_version")
            )
        }
        MobileDeviceRegistrationCommand(
            commandId = command.getString("command_id"),
            createdAtMillis = command.getLong("created_at"),
            sessionId = payload.optionalNonBlankString("session_id"),
            queueId = payload.getString("queue_id"),
            machineId = payload.getString("machine_id"),
            actorQq = payload.getString("actor_qq"),
            preference = PlayPreference.valueOf(payload.getString("preference")),
            profileId = profileSource.getString("profile_id"),
            expectedProfileRevision = if (profileSource.isNull("expected_profile_revision")) {
                null
            } else {
                profileSource.optLong("expected_profile_revision").takeIf { it > 0L }
            },
            completion = completion,
            newProfile = newProfile
        ).also { parsed ->
            check((mode == "NEW") == (parsed.newProfile != null))
            check((mode == "EXISTING") == (parsed.newProfile == null))
            check(mode == "NEW" || parsed.expectedProfileRevision != null)
            check(mode == "EXISTING" || parsed.completion == null)
        }
    }.getOrNull()?.takeIf { parsed ->
        runCatching { UUID.fromString(parsed.commandId) }.isSuccess &&
            (parsed.sessionId == null ||
                runCatching { UUID.fromString(parsed.sessionId) }.isSuccess) &&
            runCatching { UUID.fromString(parsed.queueId) }.isSuccess &&
            runCatching { UUID.fromString(parsed.profileId) }.isSuccess &&
            parsed.createdAtMillis > 0L &&
            parsed.machineId.matches(Regex("[A-Z][A-Z0-9_-]{0,7}")) &&
            isValidQqNumber(parsed.actorQq) &&
            parsed.actorQq.isNotBlank() &&
            parsed.completion?.let { completion ->
                isValidQqNumber(completion.qqNumber) &&
                    completion.qqNumber.isNotBlank() &&
                    completion.setupVersion >= CURRENT_PLAYER_PROFILE_SETUP_VERSION
            } != false &&
            parsed.newProfile?.let { profile ->
                profile.nickname.isNotBlank() &&
                    profile.nickname.codePointCount(0, profile.nickname.length) <= 18 &&
                    isValidQqNumber(profile.qqNumber) &&
                    profile.qqNumber.isNotBlank() &&
                    profile.setupVersion >= CURRENT_PLAYER_PROFILE_SETUP_VERSION
            } != false
    }
}

private fun parseNotificationPreferences(source: JSONObject): QueueNotificationPreferences =
    QueueNotificationPreferences(
        enabled = source.getBoolean("notification_enabled"),
        queueChanges = source.getBoolean("notify_queue_changes"),
        playingPosition = source.getBoolean("notify_playing_position"),
        onlineCheckIn = source.getBoolean("notify_online_check_in"),
        absence = source.getBoolean("notify_absence"),
        machineStatus = source.getBoolean("notify_machine_status")
    )

private fun JSONObject.optionalNonBlankString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

private fun isValidQueueOperationCommand(command: RemoteQueueOperationCommand): Boolean {
    val validUuid = { value: String -> runCatching { UUID.fromString(value) }.isSuccess }
    val validMachineId = { value: String? ->
        value != null && value.matches(Regex("[A-Z][A-Z0-9_-]{0,7}"))
    }
    val validRegistrationId = command.registrationId?.matches(Regex("[0-9a-f]{24}")) == true
    if (
        !validUuid(command.commandId) ||
        !validUuid(command.queueId) ||
        !validUuid(command.profileId) ||
        command.createdAtMillis <= 0L ||
        command.actorQq.isBlank() ||
        !isValidQqNumber(command.actorQq) ||
        (command.source == RemoteQueueOperationSource.WEBSITE_REMOTE &&
            command.operation != RemoteQueueOperation.JOIN_QUEUE)
    ) return false

    return when (command.operation) {
        RemoteQueueOperation.JOIN_QUEUE ->
            validMachineId(command.machineId) &&
                command.targetMachineId == null && command.registrationId == null
        RemoteQueueOperation.TRANSFER_MACHINE ->
            validMachineId(command.machineId) && validMachineId(command.targetMachineId) &&
                validRegistrationId && command.preference == null
        RemoteQueueOperation.CHANGE_PLAY_PREFERENCE ->
            validMachineId(command.machineId) && validRegistrationId &&
                command.targetMachineId == null && command.preference != null
        else ->
            validMachineId(command.machineId) && validRegistrationId &&
                command.targetMachineId == null && command.preference == null
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
    private var latestSubmittedPayload: QueuePublishPayload? = null
    private var lastSuccessfulAtMillis: Long? = null
    private var retryStartedAtMillis: Long? = null
    private var lastErrorAtMillis: Long? = null
    private var lastSyncMode = QueueSyncMode.UNSPECIFIED

    init {
        if (enabled && publisher.isConfigured) startPublishLoop()
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            publishJob?.cancel()
            publishJob = null
            retryStartedAtMillis = null
            while (updates.tryReceive().isSuccess) {
                // Discard states queued before the user disabled website sync.
            }
            onStatusChange(
                QueueCloudSyncStatus(
                    phase = QueueCloudSyncPhase.DISABLED,
                    lastSuccessfulAtMillis = lastSuccessfulAtMillis,
                    lastErrorAtMillis = lastErrorAtMillis,
                    syncMode = lastSyncMode
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
                lastSuccessfulAtMillis = lastSuccessfulAtMillis,
                retryStartedAtMillis = retryStartedAtMillis,
                lastErrorAtMillis = lastErrorAtMillis,
                syncMode = lastSyncMode
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
        val payload = QueuePublishPayload(state, auditLogs, displaySettings, playerProfiles)
        latestSubmittedPayload = payload
        startPublishLoop()
        updates.trySend(payload)
    }

    fun refresh() {
        if (!enabled || !publisher.isConfigured) return
        val payload = latestSubmittedPayload ?: return
        startPublishLoop()
        updates.trySend(payload)
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
            val result = coroutineScope {
                val slowSyncIndicator = launch {
                    delay(SYNCING_INDICATOR_DELAY_MILLIS)
                    if (enabled) {
                        onStatusChange(
                            QueueCloudSyncStatus(
                                phase = QueueCloudSyncPhase.SYNCING,
                                lastSuccessfulAtMillis = lastSuccessfulAtMillis,
                                retryStartedAtMillis = retryStartedAtMillis,
                                lastErrorAtMillis = lastErrorAtMillis,
                                syncMode = payloadToPublish.displaySettings.syncMode
                            )
                        )
                    }
                }
                try {
                    publisher.publish(
                        payloadToPublish.state,
                        payloadToPublish.auditLogs,
                        payloadToPublish.displaySettings,
                        payloadToPublish.playerProfiles
                    )
                } finally {
                    slowSyncIndicator.cancel()
                }
            }
            when (result) {
                QueuePublishResult.Success -> {
                    if (!enabled) return
                    lastSuccessfulAtMillis = System.currentTimeMillis()
                    retryStartedAtMillis = null
                    lastSyncMode = payloadToPublish.displaySettings.syncMode
                    retryDelayMillis = INITIAL_RETRY_MILLIS
                    waitBeforeNextPublishMillis = HEARTBEAT_INTERVAL_MILLIS
                    onStatusChange(
                        QueueCloudSyncStatus(
                            phase = QueueCloudSyncPhase.SYNCED,
                            lastSuccessfulAtMillis = lastSuccessfulAtMillis,
                            lastErrorAtMillis = lastErrorAtMillis,
                            syncMode = lastSyncMode
                        )
                    )
                }

                is QueuePublishResult.Failure -> {
                    if (!enabled) return
                    val failedAtMillis = System.currentTimeMillis()
                    if (retryStartedAtMillis == null) retryStartedAtMillis = failedAtMillis
                    lastErrorAtMillis = failedAtMillis
                    waitBeforeNextPublishMillis = retryDelayMillis
                    retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
                    onStatusChange(
                        QueueCloudSyncStatus(
                            phase = QueueCloudSyncPhase.WAITING_TO_RETRY,
                            lastSuccessfulAtMillis = lastSuccessfulAtMillis,
                            retryStartedAtMillis = retryStartedAtMillis,
                            lastErrorAtMillis = lastErrorAtMillis,
                            retryDetail = result.detail,
                            syncMode = payloadToPublish.displaySettings.syncMode
                        )
                    )
                }
            }
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MILLIS = 30_000L
        const val SYNCING_INDICATOR_DELAY_MILLIS = 500L
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
                        put("qq_visibility", profile.qqVisibility.name)
                        put("notification_enabled", profile.notificationPreferences.enabled)
                        put(
                            "notify_queue_changes",
                            profile.notificationPreferences.queueChanges
                        )
                        put(
                            "notify_playing_position",
                            profile.notificationPreferences.playingPosition
                        )
                        put(
                            "notify_online_check_in",
                            profile.notificationPreferences.onlineCheckIn
                        )
                        put("notify_absence", profile.notificationPreferences.absence)
                        put(
                            "notify_machine_status",
                            profile.notificationPreferences.machineStatus
                        )
                        put("setup_version", profile.setupVersion)
                        put("profile_revision", profile.revision)
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
            setupVersion = profile.setupVersion.coerceAtLeast(0),
            revision = profile.revision.coerceAtLeast(1L),
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
    put(
        "registration_open",
        state.registrationOpen && !displaySettings.businessHours.closingGracePeriod
    )
    put("website_remote_enabled", displaySettings.websiteRemoteEnabled)
    put("onebot_sync_enabled", displaySettings.oneBotSyncEnabled)
    put(
        "queue_rules",
        JSONObject().apply {
            put("allow_defer_one_round", displaySettings.allowDeferOneRound)
            put("allow_temporary_leave", displaySettings.allowTemporaryLeave)
            put("allow_online_registration", displaySettings.allowOnlineRegistration)
        }
    )
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
            "notification_categories",
            JSONArray().apply {
                event.notificationCategories
                    .sortedBy(PublicQueueNotificationCategory::name)
                    .forEach { put(it.name) }
            }
        )
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
        "new_registration_estimated_wait_minutes",
        if (status.isOperational && queue.registrationCount < 20) {
            estimatedWaitForNewOpenRegistration(queue, capturedAtMillis) ?: JSONObject.NULL
        } else {
            JSONObject.NULL
        }
    )
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
        put("online_registration_pending_check_in", registration.requiresOnSiteCheckIn)
        put("registration_type", if (registration.isTemporary) "TEMPORARY" else "PLAYER_PROFILE")
        put("created_at", registration.createdAtMillis)
        put(
            "online_check_in_started_at",
            registration.onSiteCheckInStartedAtMillis ?: registration.createdAtMillis
        )
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

private data class TerminalRuntimeIdentity(
    val terminalId: String,
    val instanceId: String,
    val instanceGeneration: Long
)

private fun HttpURLConnection.setTerminalIdentityHeaders(identity: TerminalRuntimeIdentity) {
    setRequestProperty("X-Device-ID", identity.terminalId)
    setRequestProperty("X-Terminal-Instance-ID", identity.instanceId)
    setRequestProperty(
        "X-Terminal-Instance-Generation",
        identity.instanceGeneration.toString()
    )
}

private class LocalTerminalIdentity(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "terminal_identity",
        Context.MODE_PRIVATE
    )

    fun getOrCreateRuntimeIdentity(): TerminalRuntimeIdentity = synchronized(identityLock) {
        activeRuntimeIdentity?.let { return@synchronized it }
        val terminalId = preferences.getString(KEY_TERMINAL_ID, null)
            ?.takeIf(::isValidUuid)
            ?: UUID.randomUUID().toString()
        val previousGeneration = preferences.getLong(KEY_INSTANCE_GENERATION, 0L)
            .coerceIn(0L, Long.MAX_VALUE - 1L)
        val instanceGeneration = previousGeneration + 1L
        preferences.edit()
            .putString(KEY_TERMINAL_ID, terminalId)
            .putLong(KEY_INSTANCE_GENERATION, instanceGeneration)
            .commit()
        TerminalRuntimeIdentity(
            terminalId = terminalId,
            instanceId = UUID.randomUUID().toString(),
            instanceGeneration = instanceGeneration
        ).also { activeRuntimeIdentity = it }
    }

    private fun isValidUuid(value: String): Boolean = runCatching {
        UUID.fromString(value)
    }.isSuccess

    private companion object {
        const val KEY_TERMINAL_ID = "id"
        const val KEY_INSTANCE_GENERATION = "instance_generation"
        val identityLock = Any()

        @Volatile
        var activeRuntimeIdentity: TerminalRuntimeIdentity? = null
    }
}

private const val PUBLIC_SCHEMA_VERSION = 5
private const val SYNC_SCHEMA_VERSION = 5
private const val MAX_PUBLIC_EVENTS_PER_SNAPSHOT = 200
private const val MAX_PUBLIC_EVENT_TITLE_LENGTH = 120
private const val MAX_PUBLIC_EVENT_DETAIL_LENGTH = 2_000
private const val NETWORK_TIMEOUT_MILLIS = 8_000
