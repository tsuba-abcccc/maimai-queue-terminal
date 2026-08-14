package com.abcccc.maimaiqueue

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal enum class TerminalInstallationRegistrationState {
    LOCAL_ONLY,
    WAITING_FOR_SERVER,
    REGISTERED,
    VENUE_MISMATCH
}

internal data class TerminalInstallationIdentity(
    val venueId: String? = null,
    val venueCode: String? = null,
    val venueName: String = "",
    val terminalName: String = DEFAULT_TERMINAL_NAME,
    val registrationState: TerminalInstallationRegistrationState =
        TerminalInstallationRegistrationState.LOCAL_ONLY,
    val onboardingCompleted: Boolean = false,
    val onboardingStep: Int = 0,
    /** The server that issued [venueId] and [venueCode], even while a new endpoint is being checked. */
    val venueBindingServerEndpoint: String? = null,
    val verifiedServerEndpoint: String? = null,
    val pendingServerNameUpdate: Boolean = false,
    val lastError: String? = null,
    val lastUpdatedAtMillis: Long? = null
) {
    val hasServerVenueBinding: Boolean
        get() = venueId != null && venueCode != null

    val expectedServerVenueId: String?
        get() = venueId.takeIf { hasServerVenueBinding }

    val isRegistered: Boolean
        get() = registrationState == TerminalInstallationRegistrationState.REGISTERED &&
            hasServerVenueBinding

    /**
     * Private profiles and terminal commands must never be read from a newly
     * entered server before its venue identity has been checked. A legacy
     * server is trusted only after the installation probe explicitly reported
     * that the endpoint does not support the new identity API.
     */
    fun allowsOnlineAccess(queueStatusEndpoint: String): Boolean {
        if (!onboardingCompleted ||
            registrationState == TerminalInstallationRegistrationState.VENUE_MISMATCH
        ) return false
        val endpoint = normalizeQueueSyncEndpoint(queueStatusEndpoint) ?: return false
        return verifiedServerEndpoint == endpoint && when (registrationState) {
            TerminalInstallationRegistrationState.REGISTERED,
            TerminalInstallationRegistrationState.WAITING_FOR_SERVER -> true
            TerminalInstallationRegistrationState.LOCAL_ONLY,
            TerminalInstallationRegistrationState.VENUE_MISMATCH -> false
        }
    }
}

/**
 * Diagnostic refreshes must not restart queue persistence or remote command processing.
 * Every field that can change the installation's online behavior remains part of this boundary.
 */
internal fun TerminalInstallationIdentity.runtimeEffectBoundary(): TerminalInstallationIdentity =
    copy(lastError = null, lastUpdatedAtMillis = null)

internal sealed interface TerminalInstallationFetchResult {
    data class Success(val identity: TerminalInstallationIdentity) : TerminalInstallationFetchResult
    data object UnsupportedServer : TerminalInstallationFetchResult
    data class Failure(val detail: String, val venueMismatch: Boolean = false) :
        TerminalInstallationFetchResult
}

internal class LocalTerminalInstallationRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getIdentity(): TerminalInstallationIdentity = TerminalInstallationIdentity(
        venueId = preferences.getString(KEY_VENUE_ID, null)?.takeIf(::isValidUuid),
        venueCode = preferences.getString(KEY_VENUE_CODE, null)
            ?.trim()?.takeIf { it.matches(VENUE_CODE_PATTERN) },
        venueName = preferences.getString(KEY_VENUE_NAME, "").orEmpty().trim(),
        terminalName = preferences.getString(KEY_TERMINAL_NAME, DEFAULT_TERMINAL_NAME)
            .orEmpty().trim().ifEmpty { DEFAULT_TERMINAL_NAME },
        registrationState = runCatching {
            TerminalInstallationRegistrationState.valueOf(
                preferences.getString(
                    KEY_REGISTRATION_STATE,
                    TerminalInstallationRegistrationState.LOCAL_ONLY.name
                ).orEmpty()
            )
        }.getOrDefault(TerminalInstallationRegistrationState.LOCAL_ONLY),
        onboardingCompleted = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false),
        onboardingStep = preferences.getInt(KEY_ONBOARDING_STEP, 0)
            .coerceIn(0, LAST_ONBOARDING_STEP),
        venueBindingServerEndpoint = preferences.getString(
            KEY_VENUE_BINDING_SERVER_ENDPOINT,
            null
        )?.let(::normalizeQueueSyncEndpoint)
            ?: preferences.getString(KEY_VERIFIED_SERVER_ENDPOINT, null)
                ?.let(::normalizeQueueSyncEndpoint),
        verifiedServerEndpoint = preferences.getString(KEY_VERIFIED_SERVER_ENDPOINT, null)
            ?.let(::normalizeQueueSyncEndpoint),
        pendingServerNameUpdate = preferences.getBoolean(
            KEY_PENDING_SERVER_NAME_UPDATE,
            false
        ),
        lastError = preferences.getString(KEY_LAST_ERROR, null)?.trim()?.takeIf(String::isNotEmpty),
        lastUpdatedAtMillis = preferences.getLong(KEY_LAST_UPDATED_AT, 0L)
            .takeIf { it > 0L }
    )

    fun save(identity: TerminalInstallationIdentity): Boolean = preferences.edit()
        .putString(KEY_VENUE_ID, identity.venueId)
        .putString(KEY_VENUE_CODE, identity.venueCode)
        .putString(KEY_VENUE_NAME, identity.venueName.trim())
        .putString(KEY_TERMINAL_NAME, identity.terminalName.trim())
        .putString(KEY_REGISTRATION_STATE, identity.registrationState.name)
        .putBoolean(KEY_ONBOARDING_COMPLETED, identity.onboardingCompleted)
        .putInt(KEY_ONBOARDING_STEP, identity.onboardingStep.coerceIn(0, LAST_ONBOARDING_STEP))
        .putString(
            KEY_VENUE_BINDING_SERVER_ENDPOINT,
            identity.venueBindingServerEndpoint?.let(::normalizeQueueSyncEndpoint)
        )
        .putString(
            KEY_VERIFIED_SERVER_ENDPOINT,
            identity.verifiedServerEndpoint?.let(::normalizeQueueSyncEndpoint)
        )
        .putBoolean(KEY_PENDING_SERVER_NAME_UPDATE, identity.pendingServerNameUpdate)
        .putString(KEY_LAST_ERROR, identity.lastError)
        .putLong(KEY_LAST_UPDATED_AT, identity.lastUpdatedAtMillis ?: 0L)
        .commit()

    fun markExistingInstallationIfNeeded(
        isExistingInstallation: Boolean,
        cloudConfigured: Boolean
    ): TerminalInstallationIdentity {
        val current = getIdentity()
        val migrated = prepareExistingInstallationMigration(
            current = current,
            isExistingInstallation = isExistingInstallation,
            cloudConfigured = cloudConfigured
        )
        if (migrated != current) save(migrated)
        return migrated
    }

    fun markOnboardingCompleted(
        venueName: String,
        terminalName: String,
        connected: Boolean,
        queueStatusEndpoint: String? = null,
        verifiedServerEndpoint: String? = null,
        registeredVenueId: String? = null,
        registeredVenueCode: String? = null
    ): TerminalInstallationIdentity {
        val current = getIdentity()
        val localVenueId = current.venueId ?: UUID.randomUUID().toString()
        return current.copy(
            venueId = registeredVenueId ?: current.venueId ?: localVenueId,
            venueCode = registeredVenueCode ?: current.venueCode,
            venueName = venueName.trim(),
            terminalName = terminalName.trim().ifEmpty { DEFAULT_TERMINAL_NAME },
            registrationState = if (connected) {
                TerminalInstallationRegistrationState.WAITING_FOR_SERVER
            } else {
                TerminalInstallationRegistrationState.LOCAL_ONLY
            },
            onboardingCompleted = true,
            onboardingStep = LAST_ONBOARDING_STEP,
            venueBindingServerEndpoint = (
                verifiedServerEndpoint?.let(::normalizeQueueSyncEndpoint)
                    ?: queueStatusEndpoint?.let(::normalizeQueueSyncEndpoint)
            )?.takeIf { connected && registeredVenueId != null },
            verifiedServerEndpoint = verifiedServerEndpoint
                ?.let(::normalizeQueueSyncEndpoint)
                ?: queueStatusEndpoint
                    ?.let(::normalizeQueueSyncEndpoint)
                    ?.takeIf { connected && registeredVenueId != null },
            pendingServerNameUpdate = false,
            lastError = null,
            lastUpdatedAtMillis = System.currentTimeMillis()
        ).also(::save)
    }

    fun saveOnboardingProgress(
        step: Int,
        venueName: String? = null,
        terminalName: String? = null
    ): TerminalInstallationIdentity {
        val current = getIdentity()
        return current.copy(
            venueName = venueName?.trim() ?: current.venueName,
            terminalName = terminalName?.trim()?.ifEmpty { DEFAULT_TERMINAL_NAME }
                ?: current.terminalName,
            onboardingStep = step.coerceIn(0, LAST_ONBOARDING_STEP),
            lastUpdatedAtMillis = System.currentTimeMillis()
        ).also(::save)
    }

    fun completePreparedOnboarding(): TerminalInstallationIdentity {
        val current = getIdentity()
        val completed = current.copy(
            // A local-only installation owns a locally generated identity.
            // A legacy-compatible server does not: inventing a UUID here
            // would make a schema-7 connection look like it had a server
            // venue and could leak that value on a later schema probe.
            venueId = completePreparedOnboardingVenueId(
                current,
                UUID.randomUUID().toString()
            ),
            onboardingCompleted = true,
            onboardingStep = LAST_ONBOARDING_STEP,
            lastUpdatedAtMillis = System.currentTimeMillis()
        )
        save(completed)
        return completed
    }

    private fun isValidUuid(value: String): Boolean = runCatching {
        UUID.fromString(value)
    }.isSuccess

    private companion object {
        const val PREFERENCES_NAME = "terminal_installation_identity"
        const val KEY_VENUE_ID = "venue_id"
        const val KEY_VENUE_CODE = "venue_code"
        const val KEY_VENUE_NAME = "venue_name"
        const val KEY_TERMINAL_NAME = "terminal_name"
        const val KEY_REGISTRATION_STATE = "registration_state"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_ONBOARDING_STEP = "onboarding_step"
        const val KEY_VENUE_BINDING_SERVER_ENDPOINT = "venue_binding_server_endpoint"
        const val KEY_VERIFIED_SERVER_ENDPOINT = "verified_server_endpoint"
        const val KEY_PENDING_SERVER_NAME_UPDATE = "pending_server_name_update"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_LAST_UPDATED_AT = "last_updated_at"
    }
}

internal fun completePreparedOnboardingVenueId(
    current: TerminalInstallationIdentity,
    generatedLocalVenueId: String
): String? = current.venueId ?: generatedLocalVenueId.takeIf {
    current.registrationState == TerminalInstallationRegistrationState.LOCAL_ONLY
}

/**
 * Migrates an installation that predates server-side venue identities without
 * interrupting its local queue. Even though the endpoint was already used by
 * the former version, it is deliberately left unverified: the queue publisher,
 * private-profile restore and remote-command channel remain stopped until the
 * installation probe either obtains the venue identity or explicitly enters
 * legacy compatibility mode.
 */
internal fun prepareExistingInstallationMigration(
    current: TerminalInstallationIdentity,
    isExistingInstallation: Boolean,
    cloudConfigured: Boolean,
    nowMillis: Long = System.currentTimeMillis()
): TerminalInstallationIdentity {
    if (current.onboardingCompleted || !isExistingInstallation) return current
    return current.copy(
        registrationState = if (cloudConfigured) {
            TerminalInstallationRegistrationState.WAITING_FOR_SERVER
        } else {
            TerminalInstallationRegistrationState.LOCAL_ONLY
        },
        onboardingCompleted = true,
        onboardingStep = LAST_ONBOARDING_STEP,
        verifiedServerEndpoint = null,
        pendingServerNameUpdate = false,
        lastError = if (cloudConfigured) {
            "正在核对服务器所属机厅，完成前不会上传队列或处理远程操作。"
        } else {
            null
        },
        lastUpdatedAtMillis = nowMillis
    )
}

internal class HttpTerminalInstallationClient(
    context: Context,
    queueStatusEndpoint: String,
    token: String
) {
    private val terminalIdentity = LocalTerminalIdentity(context).getOrCreateRuntimeIdentity()

    @Volatile
    private var configuration = QueueConnectionConfiguration(
        endpoint = queueStatusEndpoint.trim(),
        token = token.trim()
    )

    val isConfigured: Boolean
        get() = configuration.isValid

    fun updateConfiguration(queueStatusEndpoint: String, token: String) {
        configuration = QueueConnectionConfiguration(
            endpoint = queueStatusEndpoint.trim(),
            token = token.trim()
        )
    }

    suspend fun fetch(): TerminalInstallationFetchResult = request(null)

    suspend fun register(
        venueName: String,
        terminalName: String,
        expectedVenueId: String?
    ): TerminalInstallationFetchResult = request(
        JSONObject().apply {
            put("venue_name", venueName.trim())
            put("terminal_name", terminalName.trim())
            if (expectedVenueId != null) put("venue_id", expectedVenueId)
        }
    )

    private suspend fun request(body: JSONObject?): TerminalInstallationFetchResult =
        withContext(Dispatchers.IO) {
            val requestConfiguration = configuration
            if (!requestConfiguration.isValid) {
                return@withContext TerminalInstallationFetchResult.Failure(
                    "服务器连接配置无效。"
                )
            }
            runCatching {
                val endpoint = requestConfiguration.endpoint.trimEnd('/')
                    .substringBeforeLast('/') + "/queue-terminal/installation"
                val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = if (bytes == null) "GET" else "POST"
                    connectTimeout = INSTALLATION_NETWORK_TIMEOUT_MILLIS
                    readTimeout = INSTALLATION_NETWORK_TIMEOUT_MILLIS
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer ${requestConfiguration.token}")
                    setTerminalIdentityHeaders(terminalIdentity)
                    setRequestProperty("X-Queue-Schema-Version", CURRENT_SCHEMA_VERSION.toString())
                    if (bytes != null) {
                        doOutput = true
                        setFixedLengthStreamingMode(bytes.size)
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                }
                try {
                    if (bytes != null) connection.outputStream.use { it.write(bytes) }
                    val responseCode = connection.responseCode
                    if (responseCode == 404 || responseCode == 405) {
                        return@runCatching TerminalInstallationFetchResult.UnsupportedServer
                    }
                    if (responseCode !in 200..299) {
                        val errorBody = connection.errorStream?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText().take(4_096) }.orEmpty()
                        val errorPayload = runCatching { JSONObject(errorBody) }.getOrNull()
                        val detail = errorPayload?.optString("error")
                            ?.trim()?.takeIf(String::isNotEmpty)
                            ?: "服务器返回 HTTP $responseCode。"
                        return@runCatching TerminalInstallationFetchResult.Failure(
                            detail = detail,
                            venueMismatch = errorPayload?.optString("code") == "VENUE_MISMATCH"
                        )
                    }
                    val payload = JSONObject(
                        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    )
                    TerminalInstallationFetchResult.Success(
                        parseTerminalInstallationIdentity(payload)
                    )
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                TerminalInstallationFetchResult.Failure(queuePublishFailureDetail(error))
            }
        }
}

internal fun parseTerminalInstallationIdentity(payload: JSONObject): TerminalInstallationIdentity {
    val venue = payload.getJSONObject("venue")
    val terminal = payload.getJSONObject("terminal")
    val venueId = venue.getString("id").takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        ?: throw IllegalArgumentException("服务器返回的机厅 ID 无效")
    val venueCode = venue.getString("code").trim().takeIf { it.matches(VENUE_CODE_PATTERN) }
        ?: throw IllegalArgumentException("服务器返回的机厅编号无效")
    return TerminalInstallationIdentity(
        venueId = venueId,
        venueCode = venueCode,
        venueName = venue.optionalIdentityName("name"),
        terminalName = terminal.optionalIdentityName("name"),
        registrationState = TerminalInstallationRegistrationState.REGISTERED,
        onboardingCompleted = false,
        lastError = null,
        lastUpdatedAtMillis = System.currentTimeMillis()
    )
}

internal fun reconcileTerminalInstallationIdentity(
    current: TerminalInstallationIdentity,
    remote: TerminalInstallationIdentity,
    queueStatusEndpoint: String
): TerminalInstallationIdentity {
    val normalizedEndpoint = normalizeQueueSyncEndpoint(queueStatusEndpoint)
        ?: return current.copy(lastError = "服务器连接配置无效。")
    if (current.hasServerVenueBinding && current.venueId != remote.venueId) {
        return current.copy(
            registrationState = TerminalInstallationRegistrationState.VENUE_MISMATCH,
            pendingServerNameUpdate = current.pendingServerNameUpdate,
            lastError = "当前服务器属于另一机厅。为防止覆盖其他机厅的数据，与服务端同步和远程操作已暂停。",
            lastUpdatedAtMillis = System.currentTimeMillis()
        )
    }
    return remote.copy(
        // A first read from an upgraded server can legitimately find an old
        // installation before it has ever published display names. Missing
        // names must not turn a local name into the literal text "null" or
        // silently replace a customized terminal name with a default value.
        venueName = remote.venueName.ifBlank { current.venueName },
        terminalName = remote.terminalName.ifBlank {
            current.terminalName.ifBlank { DEFAULT_TERMINAL_NAME }
        },
        onboardingCompleted = current.onboardingCompleted,
        onboardingStep = current.onboardingStep,
        venueBindingServerEndpoint = normalizedEndpoint,
        verifiedServerEndpoint = normalizedEndpoint,
        pendingServerNameUpdate = false,
        lastError = null,
        lastUpdatedAtMillis = System.currentTimeMillis()
    )
}

private fun JSONObject.optionalIdentityName(key: String): String =
    if (!has(key) || isNull(key)) "" else optString(key).trim()

internal fun markLegacyInstallationEndpointVerified(
    current: TerminalInstallationIdentity,
    queueStatusEndpoint: String
): TerminalInstallationIdentity {
    val normalizedEndpoint = normalizeQueueSyncEndpoint(queueStatusEndpoint)
    // A legacy endpoint has no way to validate or own a schema-8 venue ID.
    // Keep an existing binding only when this is the same endpoint that issued
    // it; after a server switch, carrying the former ID would either leak it
    // to the replacement server or make a later schema negotiation ambiguous.
    val sameBinding = normalizedEndpoint != null &&
        current.venueBindingServerEndpoint?.let {
            sameQueueSyncEndpoint(it, normalizedEndpoint)
        } == true
    return current.copy(
        venueId = current.venueId.takeIf { sameBinding },
        venueCode = current.venueCode.takeIf { sameBinding },
        venueBindingServerEndpoint = current.venueBindingServerEndpoint
            ?.takeIf { sameBinding },
        registrationState = if (sameBinding && current.isRegistered) {
            current.registrationState
        } else {
            TerminalInstallationRegistrationState.WAITING_FOR_SERVER
        },
        verifiedServerEndpoint = normalizedEndpoint,
        pendingServerNameUpdate = false,
        lastError = "当前服务端暂不支持机厅身份，终端将使用兼容模式。",
        lastUpdatedAtMillis = System.currentTimeMillis()
    )
}

/**
 * Starts verification for a newly saved server connection without discarding
 * a locally edited venue or terminal name. A bound installation first probes
 * an unchanged name, but an outstanding name edit must use registration so the
 * old name returned by the server cannot overwrite the local draft.
 */
internal fun prepareTerminalInstallationForEndpointChange(
    current: TerminalInstallationIdentity,
    queueStatusEndpoint: String,
    nowMillis: Long = System.currentTimeMillis()
): TerminalInstallationIdentity {
    val normalizedEndpoint = normalizeQueueSyncEndpoint(queueStatusEndpoint)
    val hasServerBinding = current.hasServerVenueBinding
    return current.copy(
        venueId = current.venueId.takeIf { hasServerBinding },
        venueCode = current.venueCode.takeIf { hasServerBinding },
        registrationState = if (normalizedEndpoint == null) {
            TerminalInstallationRegistrationState.LOCAL_ONLY
        } else {
            TerminalInstallationRegistrationState.WAITING_FOR_SERVER
        },
        verifiedServerEndpoint = null,
        pendingServerNameUpdate = normalizedEndpoint != null &&
            (current.pendingServerNameUpdate || !hasServerBinding),
        lastError = if (normalizedEndpoint == null) null else
            "正在核对新服务器的机厅身份，完成前不会上传队列或处理远程操作。",
        lastUpdatedAtMillis = nowMillis
    )
}

/**
 * Re-enabling online synchronization is also an identity boundary. The server
 * behind an unchanged URL may have been replaced while synchronization was
 * disabled, so the former verification must not immediately unlock uploads or
 * private terminal routes. Keep the expected venue for a read-only comparison,
 * then resume only after the installation probe succeeds (or explicitly
 * confirms a legacy server).
 */
internal fun prepareTerminalInstallationForSyncEnable(
    current: TerminalInstallationIdentity,
    queueStatusEndpoint: String,
    nowMillis: Long = System.currentTimeMillis()
): TerminalInstallationIdentity {
    val normalizedEndpoint = normalizeQueueSyncEndpoint(queueStatusEndpoint)
        ?: return current.copy(
            registrationState = TerminalInstallationRegistrationState.LOCAL_ONLY,
            verifiedServerEndpoint = null,
            lastError = "服务器连接配置无效。",
            lastUpdatedAtMillis = nowMillis
        )
    return current.copy(
        registrationState = TerminalInstallationRegistrationState.WAITING_FOR_SERVER,
        verifiedServerEndpoint = null,
        pendingServerNameUpdate = current.pendingServerNameUpdate ||
            !current.hasServerVenueBinding,
        lastError = if (current.hasServerVenueBinding) {
            "正在重新核对服务器所属机厅，完成前不会上传队列或处理远程操作。"
        } else {
            "正在注册机厅并核对服务器身份，完成前不会上传队列或处理远程操作。"
        },
        lastUpdatedAtMillis = nowMillis
    )
}

/** Keeps a local name edit pending until the server that owns this installation confirms it. */
internal fun prepareTerminalInstallationNameUpdate(
    current: TerminalInstallationIdentity,
    venueName: String,
    terminalName: String,
    syncConfigured: Boolean,
    nowMillis: Long = System.currentTimeMillis()
): TerminalInstallationIdentity = current.copy(
    venueName = venueName.trim(),
    terminalName = terminalName.trim(),
    pendingServerNameUpdate = current.pendingServerNameUpdate ||
        current.hasServerVenueBinding ||
        syncConfigured,
    lastUpdatedAtMillis = nowMillis
)

/** Returns the identity only when its server-issued venue belongs to [queueStatusEndpoint]. */
internal fun terminalInstallationIdentityForEndpoint(
    current: TerminalInstallationIdentity,
    queueStatusEndpoint: String
): TerminalInstallationIdentity? {
    if (!current.hasServerVenueBinding) return null
    val bindingEndpoint = current.venueBindingServerEndpoint ?: return null
    return current.takeIf { sameQueueSyncEndpoint(bindingEndpoint, queueStatusEndpoint) }
}

/**
 * Captures a delayed "online operations disabled" publish only for a server
 * that this installation had already verified and was allowed to use. This
 * prevents a newly entered, still-unverified endpoint from receiving queue or
 * private-profile data merely because synchronization is switched off while
 * its identity probe is pending.
 */
internal fun pendingSyncDisableSnapshotForVerifiedEndpoint(
    current: TerminalInstallationIdentity,
    queueStatusEndpoint: String,
    token: String
): PendingSyncDisableSnapshot? {
    if (!current.allowsOnlineAccess(queueStatusEndpoint)) return null
    val boundIdentity = terminalInstallationIdentityForEndpoint(
        current = current,
        queueStatusEndpoint = queueStatusEndpoint
    )
    return PendingSyncDisableSnapshot(
        endpoint = queueStatusEndpoint,
        token = token,
        venueId = boundIdentity?.venueId,
        terminalName = boundIdentity?.terminalName
    )
}

/**
 * Applies the connection choice made in the first-run flow. Going back after a
 * successful registration and choosing local-only use is a real identity
 * boundary: the server-issued venue ID and public code must not survive that
 * choice or be sent to a different server later.
 */
internal fun prepareOnboardingIdentityForConnectionChoice(
    current: TerminalInstallationIdentity,
    useServer: Boolean,
    queueStatusEndpoint: String? = null
): TerminalInstallationIdentity = if (useServer) {
    val selectedEndpoint = queueStatusEndpoint?.let(::normalizeQueueSyncEndpoint)
    val returningToSameServer = selectedEndpoint != null &&
        (current.venueBindingServerEndpoint == selectedEndpoint ||
            current.verifiedServerEndpoint == selectedEndpoint)
    current.copy(
        // During first-run setup there is no queue data to reassign yet. If
        // the administrator goes back after registering one server and enters
        // another address, do not send the former server's venue ID to the new
        // server. Keeping it is still important for an idempotent retry against
        // the same endpoint.
        venueId = current.venueId.takeIf { returningToSameServer },
        venueCode = current.venueCode.takeIf { returningToSameServer },
        venueBindingServerEndpoint = current.venueBindingServerEndpoint
            .takeIf { returningToSameServer },
        registrationState = TerminalInstallationRegistrationState.WAITING_FOR_SERVER,
        verifiedServerEndpoint = null,
        pendingServerNameUpdate = false,
        lastError = null
    )
} else {
    current.copy(
        venueId = null,
        venueCode = null,
        venueBindingServerEndpoint = null,
        registrationState = TerminalInstallationRegistrationState.LOCAL_ONLY,
        verifiedServerEndpoint = null,
        pendingServerNameUpdate = false,
        lastError = null
    )
}

internal fun previousOnboardingStep(currentStep: Int, cloudSyncAvailable: Boolean): Int = when {
    currentStep <= 0 -> 0
    !cloudSyncAvailable && currentStep == 2 -> 0
    else -> currentStep - 1
}

internal fun prepareTerminalInstallationForExplicitVenueRebind(
    current: TerminalInstallationIdentity,
    queueStatusEndpoint: String,
    nowMillis: Long = System.currentTimeMillis()
): TerminalInstallationIdentity {
    require(normalizeQueueSyncEndpoint(queueStatusEndpoint) != null)
    return current.copy(
        venueId = null,
        venueCode = null,
        venueBindingServerEndpoint = null,
        registrationState = TerminalInstallationRegistrationState.WAITING_FOR_SERVER,
        verifiedServerEndpoint = null,
        pendingServerNameUpdate = true,
        lastError = "正在按管理员确认重新绑定当前服务器；核对完成前不会上传队列或处理远程操作。",
        lastUpdatedAtMillis = nowMillis
    )
}

internal const val DEFAULT_TERMINAL_NAME = "现场终端"
internal const val MAX_VENUE_NAME_CHARACTERS = 40
internal const val MAX_TERMINAL_NAME_CHARACTERS = 24
internal const val LAST_ONBOARDING_STEP = 3
private const val INSTALLATION_NETWORK_TIMEOUT_MILLIS = 8_000
private val VENUE_CODE_PATTERN = Regex("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}$")
