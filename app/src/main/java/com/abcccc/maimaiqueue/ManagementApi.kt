package com.abcccc.maimaiqueue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class ManagementRegistration(
    val registrationId: String,
    val displayId: String,
    val profileId: String?,
    val qqNumber: String?,
    val preference: String,
    val position: String,
    val waitingPosition: Int?,
    val pendingCheckIn: Boolean,
    val deferredOnce: Boolean,
    val temporarilyAway: Boolean,
    val fixedPair: Boolean,
    val fixedPairId: String?,
    val temporaryAwaySkippedTurns: Int
)

internal data class ManagementMachine(
    val id: String,
    val name: String,
    val stableId: String?,
    val capacity: Int,
    val operational: Boolean,
    val registrationCount: Int,
    val playing: List<ManagementRegistration>,
    val waiting: List<ManagementRegistration>
)

internal data class ManagementProfile(
    val id: String,
    val publicPlayerId: String?,
    val nickname: String,
    val gender: String,
    val defaultPreference: String,
    val qqNumber: String?,
    val qqVisibility: String,
    val terminalEditingAllowed: Boolean,
    val visitedVenuesPublic: Boolean,
    val webAccountBound: Boolean,
    val profileRevision: Long,
    val updatedAtMillis: Long
)

internal data class ManagementCapabilities(
    val queueReadAll: Boolean,
    val queueEditAll: Boolean,
    val queueReorder: Boolean,
    val profileReadPrivate: Boolean,
    val profileEditAll: Boolean,
    val profileResetPassword: Boolean,
    val terminalPolicyEdit: Boolean,
    val auditRead: Boolean
)

internal data class ManagementTerminalPolicy(
    val supported: Boolean,
    val managementAppBound: Boolean,
    val revision: Long,
    val allowOnlineRegistration: Boolean,
    val allowDeferOneRound: Boolean,
    val allowTemporaryLeave: Boolean,
    val oneBotSyncEnabled: Boolean
)

internal data class ManagementOverview(
    val venueName: String,
    val venueCode: String?,
    val terminalName: String?,
    val terminalOnline: Boolean,
    val terminalLastSeenAtMillis: Long?,
    val queueId: String,
    val queueRevision: Long,
    val machineConfigurationRevision: Long,
    val receivedAtMillis: Long,
    val registrationOpen: Boolean,
    val queueRules: Map<String, Boolean>,
    val terminalPolicy: ManagementTerminalPolicy,
    val machines: List<ManagementMachine>,
    val profiles: List<ManagementProfile>,
    val capabilities: ManagementCapabilities
)

internal data class ManagementCommandResult(
    val commandId: String,
    val status: String,
    val detail: String?
)

internal class ManagementApiException(val statusCode: Int, message: String) : Exception(message)

internal class ManagementApi(
    endpoint: String,
    private val token: String
) {
    private val normalizedEndpoint = endpoint.trim()

    suspend fun fetchOverview(): ManagementOverview = withContext(Dispatchers.IO) {
        val connection = openConnection(overviewEndpoint(), "GET")
        try {
            val body = readResponse(connection)
            parseOverview(body)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun checkIn(
        registrationId: String,
        expectedQueueId: String,
        expectedMachineId: String,
        expectedPosition: String,
        expectedPendingCheckIn: Boolean,
        expectedMachineConfigurationRevision: Long,
        reason: String
    ): ManagementCommandResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("request_id", java.util.UUID.randomUUID().toString())
            put("operation", "CHECK_IN")
            put("registration_id", registrationId)
            put("expected_queue_id", expectedQueueId)
            put("expected_machine_id", expectedMachineId)
            put("expected_position", expectedPosition)
            put("expected_pending_check_in", expectedPendingCheckIn)
            put("expected_machine_configuration_revision", expectedMachineConfigurationRevision)
            put("reason", reason.trim().ifEmpty { "管理后台立即签到" })
        }.toString()
        val connection = openConnection(commandEndpoint(), "POST").apply {
            doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val response = readResponse(connection, body)
            parseCommandResult(response)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun createRegistration(
        profileId: String,
        expectedQueueId: String,
        machineId: String,
        machineStableId: String?,
        preference: String?,
        expectedMachineConfigurationRevision: Long,
        reason: String
    ): ManagementCommandResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("request_id", java.util.UUID.randomUUID().toString())
            put("profile_id", profileId)
            put("expected_queue_id", expectedQueueId)
            put("expected_machine_id", machineId)
            machineStableId?.let { put("expected_machine_stable_id", it) }
            put("expected_machine_configuration_revision", expectedMachineConfigurationRevision)
            preference?.let { put("preference", it) }
            put("reason", reason.trim().ifEmpty { "管理后台新建登记" })
        }.toString()
        val connection = openConnection(
            managementPath("/api/queue-management/registrations"),
            "POST"
        ).apply {
            doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            parseCommandResult(readResponse(connection, body))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun queueAction(
        profileId: String?,
        operation: String,
        queueId: String,
        registration: ManagementRegistration,
        machine: ManagementMachine,
        preference: String? = null,
        targetMachine: ManagementMachine? = null,
        machineConfigurationRevision: Long
    ): ManagementCommandResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("request_id", java.util.UUID.randomUUID().toString())
            profileId?.let { put("profile_id", it) }
            put("operation", operation)
            put("expected_queue_id", queueId)
            put("expected_registration_id", registration.registrationId)
            put("expected_machine_id", machine.id)
            put("expected_position", registration.position)
            put(
                "expected_fixed_pair_id",
                registration.fixedPairId ?: JSONObject.NULL
            )
            put(
                "expected_absence_status",
                when {
                    registration.deferredOnce -> "DEFER_ONE_ROUND"
                    registration.temporarilyAway -> "TEMPORARILY_AWAY"
                    else -> "NONE"
                }
            )
            put("expected_temporary_away_skipped_turns", registration.temporaryAwaySkippedTurns)
            put("expected_pending_check_in", registration.pendingCheckIn)
            put("expected_machine_configuration_revision", machineConfigurationRevision)
            machine.stableId?.let { put("expected_machine_stable_id", it) }
            targetMachine?.stableId?.let { put("expected_target_machine_stable_id", it) }
            targetMachine?.let { put("target_machine_id", it.id) }
            preference?.let { put("preference", it) }
        }.toString()
        val connection = openConnection(
            managementPath("/api/queue-management/queue-commands"),
            "POST"
        ).apply {
            doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            parseCommandResult(readResponse(connection, body))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun reorderQueue(
        queueId: String,
        machine: ManagementMachine,
        expectedOrder: List<String>,
        desiredOrder: List<String>,
        machineConfigurationRevision: Long
    ): ManagementCommandResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("request_id", java.util.UUID.randomUUID().toString())
            put("expected_queue_id", queueId)
            put("machine_id", machine.id)
            machine.stableId?.let { put("expected_machine_stable_id", it) }
            put("expected_machine_configuration_revision", machineConfigurationRevision)
            put("expected_registration_order", JSONArray(expectedOrder))
            put("registration_order", JSONArray(desiredOrder))
            put("reason", "管理后台调整队列顺序")
        }.toString()
        val connection = openConnection(
            managementPath("/api/queue-management/queue-reorders"),
            "POST"
        ).apply {
            doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            parseCommandResult(readResponse(connection, body))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun fetchCommand(commandId: String): ManagementCommandResult = withContext(Dispatchers.IO) {
        val connection = openConnection("${commandEndpoint()}/$commandId", "GET")
        try {
            parseCommandResult(readResponse(connection))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun updateProfile(
        profileId: String,
        nickname: String,
        gender: String,
        defaultPreference: String,
        qqVisibility: String,
        terminalEditingAllowed: Boolean,
        visitedVenuesPublic: Boolean
    ): ManagementCommandResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("request_id", java.util.UUID.randomUUID().toString())
            put("nickname", nickname.trim())
            put("gender", gender)
            put("default_preference", defaultPreference)
            put("qq_visibility", qqVisibility)
            put("terminal_editing_allowed", terminalEditingAllowed)
            put("visited_venues_public", visitedVenuesPublic)
        }.toString()
        val connection = openConnection(
            managementPath("/api/queue-management/profiles/$profileId"),
            "PATCH"
        ).apply {
            doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            parseCommandResult(readResponse(connection, body))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun updatePassword(
        profileId: String,
        password: String,
        confirmation: String
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("new_password", password)
            put("new_password_confirmation", confirmation)
        }.toString()
        val connection = openConnection(
            managementPath("/api/queue-management/profiles/$profileId/password"),
            "POST"
        ).apply {
            doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val source = JSONObject(readResponse(connection, body))
            source.optionalString("detail") ?: "玩家网页后台密码已修改。"
        } finally {
            connection.disconnect()
        }
    }

    suspend fun updateTerminalPolicy(
        expectedQueueId: String,
        expectedPolicyRevision: Long,
        managementAppBound: Boolean,
        allowOnlineRegistration: Boolean,
        allowDeferOneRound: Boolean,
        allowTemporaryLeave: Boolean,
        oneBotSyncEnabled: Boolean,
        reason: String
    ): ManagementCommandResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("request_id", java.util.UUID.randomUUID().toString())
            put("expected_queue_id", expectedQueueId)
            put("expected_policy_revision", expectedPolicyRevision)
            put("management_app_bound", managementAppBound)
            put("allow_online_registration", allowOnlineRegistration)
            put("allow_defer_one_round", allowDeferOneRound)
            put("allow_temporary_leave", allowTemporaryLeave)
            put("onebot_sync_enabled", oneBotSyncEnabled)
            put("reason", reason.trim().ifEmpty { "管理后台更新终端敏感策略" })
        }.toString()
        val connection = openConnection(
            managementPath("/api/queue-management/terminal-policy"),
            "PATCH"
        ).apply {
            doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            parseCommandResult(readResponse(connection, body))
        } finally {
            connection.disconnect()
        }
    }

    private fun overviewEndpoint(): String = managementPath("/api/queue-management/overview")

    private fun commandEndpoint(): String = managementPath("/api/queue-management/commands")

    private fun managementPath(path: String): String {
        val base = normalizedEndpoint.trimEnd('/')
        val root = when {
            base.contains("/api/queue-status") -> base.substringBefore("/api/queue-status")
            base.contains("/api/queue-management") -> base.substringBefore("/api/queue-management")
            base.endsWith("/api") -> base.removeSuffix("/api")
            else -> base
        }.trimEnd('/')
        return root + path
    }

    private fun openConnection(endpoint: String, method: String): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun readResponse(connection: HttpURLConnection, requestBody: String? = null): String {
        if (requestBody != null) {
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val detail = runCatching { JSONObject(body).optString("error") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "服务器返回 HTTP $status"
            throw ManagementApiException(status, detail)
        }
        return body
    }

    private companion object {
        const val NETWORK_TIMEOUT_MILLIS = 15_000
    }
}

private fun parseOverview(response: String): ManagementOverview {
    val source = JSONObject(response)
    val venue = source.optJSONObject("venue") ?: JSONObject()
    val terminal = source.optJSONObject("terminal") ?: JSONObject()
    val queue = source.optJSONObject("queue") ?: JSONObject()
    val machines = queue.optJSONArray("machines")?.let(::parseMachines).orEmpty()
    val profiles = source.optJSONArray("profiles")?.let(::parseProfiles).orEmpty()
    val capabilities = source.optJSONObject("capabilities") ?: JSONObject()
    val queueRules = queue.optJSONObject("queue_rules")?.let { rules ->
        buildMap {
            rules.keys().forEach { key -> put(key, rules.optBoolean(key, false)) }
        }
    }.orEmpty()
    val policy = queue.optJSONObject("terminal_policy") ?: JSONObject()
    return ManagementOverview(
        venueName = venue.optString("name", "未命名机厅"),
        venueCode = venue.optionalString("code"),
        terminalName = terminal.optionalString("name"),
        terminalOnline = terminal.optBoolean("online", false),
        terminalLastSeenAtMillis = terminal.optionalLong("last_seen_at"),
        queueId = queue.optString("queue_id"),
        queueRevision = queue.optLong("revision", 0L),
        machineConfigurationRevision = queue.optLong("machine_configuration_revision", 1L),
        receivedAtMillis = queue.optLong("received_at", 0L),
        registrationOpen = queue.optBoolean("registration_open", true),
        queueRules = queueRules,
        terminalPolicy = ManagementTerminalPolicy(
            supported = policy.optBoolean("supported", false),
            managementAppBound = policy.optBoolean("management_app_bound", false),
            revision = policy.optLong("revision", 0L).coerceAtLeast(0L),
            allowOnlineRegistration = policy.optBoolean(
                "allow_online_registration",
                queueRules["allow_online_registration"] ?: true
            ),
            allowDeferOneRound = policy.optBoolean(
                "allow_defer_one_round",
                queueRules["allow_defer_one_round"] ?: true
            ),
            allowTemporaryLeave = policy.optBoolean(
                "allow_temporary_leave",
                queueRules["allow_temporary_leave"] ?: true
            ),
            oneBotSyncEnabled = policy.optBoolean("onebot_sync_enabled", false)
        ),
        machines = machines,
        profiles = profiles,
        capabilities = ManagementCapabilities(
            queueReadAll = capabilities.optBoolean("QUEUE_READ_ALL", true),
            queueEditAll = capabilities.optBoolean("QUEUE_EDIT_ALL", true),
            queueReorder = capabilities.optBoolean("QUEUE_REORDER", true),
            profileReadPrivate = capabilities.optBoolean("PROFILE_READ_PRIVATE", true),
            profileEditAll = capabilities.optBoolean("PROFILE_EDIT_ALL", true),
            profileResetPassword = capabilities.optBoolean("PROFILE_RESET_PASSWORD", true),
            terminalPolicyEdit = capabilities.optBoolean("TERMINAL_POLICY_EDIT", true),
            auditRead = capabilities.optBoolean("AUDIT_READ", true)
        )
    )
}

private fun parseMachines(source: JSONArray): List<ManagementMachine> = buildList {
    repeat(source.length()) { index ->
        val machine = source.optJSONObject(index) ?: return@repeat
        val machineId = machine.optString("id")
        val playing = machine.optJSONArray("playing")?.let { registrations ->
            parseRegistrations(registrations, "PLAYING", null)
        }.orEmpty()
        val waiting = buildList {
            val positions = machine.optJSONArray("waiting_positions") ?: JSONArray()
            repeat(positions.length()) { positionIndex ->
                val position = positions.optJSONObject(positionIndex) ?: return@repeat
                val queuePosition = position.optInt("index", positionIndex + 1)
                position.optJSONArray("registrations")?.let { registrations ->
                    addAll(parseRegistrations(registrations, "WAITING", queuePosition))
                }
            }
        }
        add(
            ManagementMachine(
                id = machineId,
                name = machine.optString("name", "机台 $machineId"),
                stableId = machine.optionalString("stable_id"),
                capacity = machine.optJSONObject("configuration")
                    ?.optInt("capacity", 2)
                    ?.coerceIn(1, 2)
                    ?: 2,
                operational = machine.optBoolean("operational", false),
                registrationCount = machine.optInt("registration_count", playing.size + waiting.size),
                playing = playing,
                waiting = waiting
            )
        )
    }
}

private fun parseRegistrations(
    source: JSONArray,
    position: String,
    waitingPosition: Int?
): List<ManagementRegistration> = buildList {
    repeat(source.length()) { index ->
        val registration = source.optJSONObject(index) ?: return@repeat
        add(
            ManagementRegistration(
                registrationId = registration.optString("registration_id"),
                displayId = registration.optString("display_id", "未命名登记"),
                profileId = registration.optionalString("profile_id"),
                qqNumber = registration.optionalString("qq_number"),
                preference = registration.optString("preference", "SOLO"),
                position = position,
                waitingPosition = waitingPosition,
                pendingCheckIn = registration.optBoolean("online_registration_pending_check_in", false),
                deferredOnce = registration.optBoolean("deferred_once", false),
                temporarilyAway = registration.optBoolean("temporarily_away", false),
                fixedPair = registration.optBoolean("fixed_pair", false),
                fixedPairId = registration.optionalString("fixed_pair_id"),
                temporaryAwaySkippedTurns = registration.optInt(
                    "temporary_away_skipped_turns", 0
                ).coerceIn(0, 3)
            )
        )
    }
}

private fun parseProfiles(source: JSONArray): List<ManagementProfile> = buildList {
    repeat(source.length()) { index ->
        val profile = source.optJSONObject(index) ?: return@repeat
        add(
            ManagementProfile(
                id = profile.optString("profile_id"),
                publicPlayerId = profile.optionalString("public_player_id"),
                nickname = profile.optString("nickname", "未命名玩家"),
                gender = profile.optString("gender", "UNDISCLOSED"),
                defaultPreference = profile.optString("default_preference", "ASK_EVERY_TIME"),
                qqNumber = profile.optionalString("qq_number"),
                qqVisibility = profile.optString("qq_visibility", "TERMINAL_ONLY"),
                webAccountBound = profile.optBoolean("web_account_bound", false),
                terminalEditingAllowed = profile.optBoolean("terminal_editing_allowed", true),
                visitedVenuesPublic = profile.optBoolean("visited_venues_public", true),
                profileRevision = profile.optLong("profile_revision", 1L),
                updatedAtMillis = profile.optLong("updated_at", 0L)
            )
        )
    }
}

private fun parseCommandResult(response: String): ManagementCommandResult {
    val source = JSONObject(response)
    return ManagementCommandResult(
        commandId = source.optString("command_id"),
        status = source.optString("status", "PENDING"),
        detail = source.optionalString("result_detail") ?: source.optionalString("error")
    )
}

private fun JSONObject.optionalString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

private fun JSONObject.optionalLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name).takeIf { it > 0L }
