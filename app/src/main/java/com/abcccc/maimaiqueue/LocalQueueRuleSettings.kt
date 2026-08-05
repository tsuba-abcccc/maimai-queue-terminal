package com.abcccc.maimaiqueue

import android.content.Context
import java.net.URI
import java.time.DayOfWeek

enum class QueueSyncMode(val headerValue: String?) {
    UNSPECIFIED(null),
    TEST("test"),
    TAKEOVER("takeover")
}

enum class MachineGameType {
    MAIMAI_DX,
    CHUNITHM,
    ONGEKI,
    DANCE_CUBE,
    TAIKO_NO_TATSUJIN,
    OTHER;

    val supportsServerConfiguration: Boolean
        get() = this == MAIMAI_DX || this == CHUNITHM || this == ONGEKI
}

enum class MachineServer {
    CHINA,
    INTERNATIONAL,
    JAPAN,
    DABING,
    RINNET,
    OTHER,
    HIDDEN
}

data class MachineConfiguration(
    val remark: String,
    val gameType: MachineGameType = MachineGameType.MAIMAI_DX,
    val customGameType: String = "",
    val server: MachineServer = MachineServer.HIDDEN,
    val customServer: String = "",
    val gameVersion: String = "",
    val showGameVersion: Boolean = false,
    val capacity: Int = DEFAULT_MACHINE_CAPACITY,
    val soloRoundMinutes: Int = DEFAULT_SOLO_ROUND_MINUTES,
    val sharedRoundMinutes: Int = DEFAULT_SHARED_ROUND_MINUTES
)

data class QueueRuleSettings(
    val allowDeferOneRound: Boolean = true,
    val allowTemporaryLeave: Boolean = true,
    val allowOnlineRegistration: Boolean = true,
    val showCommonPlayPreview: Boolean = true,
    val websiteSyncEnabled: Boolean = true,
    val oneBotSyncEnabled: Boolean = true,
    val syncMode: QueueSyncMode = QueueSyncMode.UNSPECIFIED,
    val queueSyncEndpoint: String = "",
    val queueSyncToken: String = "",
    val configuredMachineCount: Int = DEFAULT_CONFIGURED_MACHINE_IDS.size,
    val machineConfigurations: Map<MachineId, MachineConfiguration> =
        DEFAULT_MACHINE_CONFIGURATIONS,
    val machineConfigurationRevision: Long = 1L,
    val businessHours: BusinessHoursSettings = BusinessHoursSettings()
) {
    val allowsAnyAbsenceAction: Boolean
        get() = allowDeferOneRound || allowTemporaryLeave

    val configuredMachineIds: List<MachineId>
        get() = configuredMachineIds(configuredMachineCount)

    val machineARemark: String
        get() = machineRemark(MachineId.A)

    val machineBRemark: String
        get() = machineRemark(MachineId.B)

    val machineRemarks: Map<MachineId, String>
        get() = MachineId.entries.associateWith(::machineRemark)

    fun machineRemark(machineId: MachineId): String =
        machineConfiguration(machineId).remark

    fun machineConfiguration(machineId: MachineId): MachineConfiguration =
        normalizeMachineConfiguration(
            machineId,
            machineConfigurations[machineId]
                ?: DEFAULT_MACHINE_CONFIGURATIONS.getValue(machineId)
        )
}

class LocalQueueRuleSettingsRepository(
    context: Context,
    private val defaultQueueSyncEndpoint: String = "",
    private val defaultQueueSyncToken: String = ""
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getSettings(): QueueRuleSettings {
        val websiteSyncEnabled = preferences.getBoolean(KEY_WEBSITE_SYNC_ENABLED, true)
        return QueueRuleSettings(
            allowDeferOneRound = preferences.getBoolean(KEY_ALLOW_DEFER_ONE_ROUND, true),
            allowTemporaryLeave = preferences.getBoolean(KEY_ALLOW_TEMPORARY_LEAVE, true),
            allowOnlineRegistration = preferences.getBoolean(
                KEY_ALLOW_ONLINE_REGISTRATION,
                true
            ),
            showCommonPlayPreview = preferences.getBoolean(
                KEY_SHOW_COMMON_PLAY_PREVIEW,
                true
            ),
            websiteSyncEnabled = websiteSyncEnabled,
            oneBotSyncEnabled = websiteSyncEnabled &&
                preferences.getBoolean(KEY_ONEBOT_SYNC_ENABLED, true),
            syncMode = runCatching {
                QueueSyncMode.valueOf(
                    preferences.getString(
                        KEY_SYNC_MODE,
                        QueueSyncMode.UNSPECIFIED.name
                    ).orEmpty()
                )
            }.getOrDefault(QueueSyncMode.UNSPECIFIED),
            queueSyncEndpoint = readConnectionValue(
                KEY_QUEUE_SYNC_ENDPOINT,
                defaultQueueSyncEndpoint
            ),
            queueSyncToken = readConnectionValue(
                KEY_QUEUE_SYNC_TOKEN,
                defaultQueueSyncToken
            ),
            configuredMachineCount = preferences.getInt(
                KEY_CONFIGURED_MACHINE_COUNT,
                DEFAULT_CONFIGURED_MACHINE_IDS.size
            ).coerceIn(1, MachineId.entries.size),
            machineConfigurations = MachineId.entries.associateWith { machineId ->
                val legacyKey = when (machineId) {
                    MachineId.A -> KEY_MACHINE_A_REMARK
                    MachineId.B -> KEY_MACHINE_B_REMARK
                    else -> null
                }
                normalizeMachineConfiguration(
                    machineId,
                    MachineConfiguration(
                        remark = preferences.getString(
                            machineRemarkKey(machineId),
                            legacyKey?.let { preferences.getString(it, null) }
                                ?: DEFAULT_MACHINE_REMARKS.getValue(machineId)
                        ).orEmpty(),
                        gameType = preferences.enumValue(
                            machineConfigurationKey(machineId, "game_type"),
                            MachineGameType.MAIMAI_DX
                        ),
                        customGameType = preferences.getString(
                            machineConfigurationKey(machineId, "custom_game_type"),
                            ""
                        ).orEmpty(),
                        server = preferences.enumValue(
                            machineConfigurationKey(machineId, "server"),
                            MachineServer.HIDDEN
                        ),
                        customServer = preferences.getString(
                            machineConfigurationKey(machineId, "custom_server"),
                            ""
                        ).orEmpty(),
                        gameVersion = preferences.getString(
                            machineConfigurationKey(machineId, "game_version"),
                            ""
                        ).orEmpty(),
                        showGameVersion = preferences.getBoolean(
                            machineConfigurationKey(machineId, "show_game_version"),
                            false
                        ),
                        capacity = preferences.getInt(
                            machineConfigurationKey(machineId, "capacity"),
                            DEFAULT_MACHINE_CAPACITY
                        ),
                        soloRoundMinutes = preferences.getInt(
                            machineConfigurationKey(machineId, "solo_round_minutes"),
                            DEFAULT_SOLO_ROUND_MINUTES
                        ),
                        sharedRoundMinutes = preferences.getInt(
                            machineConfigurationKey(machineId, "shared_round_minutes"),
                            DEFAULT_SHARED_ROUND_MINUTES
                        )
                    )
                )
            },
            machineConfigurationRevision = preferences.getLong(
                KEY_MACHINE_CONFIGURATION_REVISION,
                1L
            ).coerceAtLeast(1L),
            businessHours = BusinessHoursSettings(
                enabled = preferences.getBoolean(KEY_BUSINESS_HOURS_ENABLED, false),
                useWeeklySchedule = preferences.getBoolean(KEY_WEEKLY_BUSINESS_HOURS_ENABLED, false),
                defaultHours = readHours(KEY_DEFAULT_OPENING_MINUTES, KEY_DEFAULT_CLOSING_MINUTES),
                weeklyHours = DayOfWeek.entries.associateWith { day ->
                    readHours(
                        openingKey = weekdayKey(day, "opening"),
                        closingKey = weekdayKey(day, "closing")
                    )
                }
            ).normalized()
        )
    }

    fun saveSettings(settings: QueueRuleSettings) {
        val oneBotSyncEnabled = settings.websiteSyncEnabled && settings.oneBotSyncEnabled
        preferences.edit()
            .putBoolean(KEY_ALLOW_DEFER_ONE_ROUND, settings.allowDeferOneRound)
            .putBoolean(KEY_ALLOW_TEMPORARY_LEAVE, settings.allowTemporaryLeave)
            .putBoolean(KEY_ALLOW_ONLINE_REGISTRATION, settings.allowOnlineRegistration)
            .putBoolean(KEY_SHOW_COMMON_PLAY_PREVIEW, settings.showCommonPlayPreview)
            .putBoolean(KEY_WEBSITE_SYNC_ENABLED, settings.websiteSyncEnabled)
            .putBoolean(KEY_ONEBOT_SYNC_ENABLED, oneBotSyncEnabled)
            .putString(KEY_SYNC_MODE, settings.syncMode.name)
            .putString(KEY_QUEUE_SYNC_ENDPOINT, settings.queueSyncEndpoint.trim())
            .putString(KEY_QUEUE_SYNC_TOKEN, settings.queueSyncToken.trim())
            .putInt(
                KEY_CONFIGURED_MACHINE_COUNT,
                settings.configuredMachineCount.coerceIn(1, MachineId.entries.size)
            )
            .putLong(
                KEY_MACHINE_CONFIGURATION_REVISION,
                settings.machineConfigurationRevision.coerceAtLeast(1L)
            )
            .putBoolean(KEY_BUSINESS_HOURS_ENABLED, settings.businessHours.enabled)
            .putBoolean(KEY_WEEKLY_BUSINESS_HOURS_ENABLED, settings.businessHours.useWeeklySchedule)
            .putInt(KEY_DEFAULT_OPENING_MINUTES, settings.businessHours.defaultHours.openingMinutes)
            .putInt(KEY_DEFAULT_CLOSING_MINUTES, settings.businessHours.defaultHours.closingMinutes)
            .also { editor ->
                MachineId.entries.forEach { machineId ->
                    val configuration = settings.machineConfiguration(machineId)
                    editor.putString(
                        machineRemarkKey(machineId),
                        configuration.remark
                    )
                    editor.putString(
                        machineConfigurationKey(machineId, "game_type"),
                        configuration.gameType.name
                    )
                    editor.putString(
                        machineConfigurationKey(machineId, "custom_game_type"),
                        configuration.customGameType
                    )
                    editor.putString(
                        machineConfigurationKey(machineId, "server"),
                        configuration.server.name
                    )
                    editor.putString(
                        machineConfigurationKey(machineId, "custom_server"),
                        configuration.customServer
                    )
                    editor.putString(
                        machineConfigurationKey(machineId, "game_version"),
                        configuration.gameVersion
                    )
                    editor.putBoolean(
                        machineConfigurationKey(machineId, "show_game_version"),
                        configuration.showGameVersion
                    )
                    editor.putInt(
                        machineConfigurationKey(machineId, "capacity"),
                        configuration.capacity
                    )
                    editor.putInt(
                        machineConfigurationKey(machineId, "solo_round_minutes"),
                        configuration.soloRoundMinutes
                    )
                    editor.putInt(
                        machineConfigurationKey(machineId, "shared_round_minutes"),
                        configuration.sharedRoundMinutes
                    )
                }
                DayOfWeek.entries.forEach { day ->
                    val hours = settings.businessHours.hoursFor(day).normalized()
                    editor.putInt(weekdayKey(day, "opening"), hours.openingMinutes)
                    editor.putInt(weekdayKey(day, "closing"), hours.closingMinutes)
                }
            }
            .apply()
    }

    fun getLastHandledClosingOccurrenceId(): String? =
        preferences.getString(KEY_LAST_HANDLED_CLOSING_OCCURRENCE, null)

    fun markClosingOccurrenceHandled(occurrenceId: String) {
        preferences.edit()
            .putString(KEY_LAST_HANDLED_CLOSING_OCCURRENCE, occurrenceId)
            .apply()
    }

    private fun readHours(openingKey: String, closingKey: String): DailyBusinessHours =
        DailyBusinessHours(
            openingMinutes = preferences.getInt(openingKey, DEFAULT_OPENING_MINUTES),
            closingMinutes = preferences.getInt(closingKey, DEFAULT_CLOSING_MINUTES)
        ).normalized()

    private fun weekdayKey(day: DayOfWeek, suffix: String): String =
        "business_hours_${day.name.lowercase()}_$suffix"

    private fun machineRemarkKey(machineId: MachineId): String =
        "machine_${machineId.name.lowercase()}_remark"

    private fun machineConfigurationKey(machineId: MachineId, suffix: String): String =
        "machine_${machineId.name.lowercase()}_$suffix"

    private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enumValue(
        key: String,
        fallback: T
    ): T = runCatching {
        enumValueOf<T>(getString(key, fallback.name).orEmpty())
    }.getOrDefault(fallback)

    private fun readConnectionValue(key: String, defaultValue: String): String =
        if (preferences.contains(key)) {
            preferences.getString(key, "").orEmpty().trim()
        } else {
            defaultValue.trim()
        }

    private companion object {
        const val PREFERENCES_NAME = "queue_rule_settings"
        const val KEY_ALLOW_DEFER_ONE_ROUND = "allow_defer_one_round"
        const val KEY_ALLOW_TEMPORARY_LEAVE = "allow_temporary_leave"
        const val KEY_ALLOW_ONLINE_REGISTRATION = "allow_online_registration"
        const val KEY_SHOW_COMMON_PLAY_PREVIEW = "show_common_play_preview"
        const val KEY_WEBSITE_SYNC_ENABLED = "website_sync_enabled"
        const val KEY_ONEBOT_SYNC_ENABLED = "onebot_sync_enabled"
        const val KEY_SYNC_MODE = "sync_mode"
        const val KEY_QUEUE_SYNC_ENDPOINT = "queue_sync_endpoint"
        const val KEY_QUEUE_SYNC_TOKEN = "queue_sync_token"
        const val KEY_CONFIGURED_MACHINE_COUNT = "configured_machine_count"
        const val KEY_MACHINE_CONFIGURATION_REVISION = "machine_configuration_revision"
        const val KEY_MACHINE_A_REMARK = "machine_a_remark"
        const val KEY_MACHINE_B_REMARK = "machine_b_remark"
        const val KEY_BUSINESS_HOURS_ENABLED = "business_hours_enabled"
        const val KEY_WEEKLY_BUSINESS_HOURS_ENABLED = "weekly_business_hours_enabled"
        const val KEY_DEFAULT_OPENING_MINUTES = "default_opening_minutes"
        const val KEY_DEFAULT_CLOSING_MINUTES = "default_closing_minutes"
        const val KEY_LAST_HANDLED_CLOSING_OCCURRENCE = "last_handled_closing_occurrence"
    }
}

internal const val DEFAULT_MACHINE_A_REMARK = "左侧"
internal const val DEFAULT_MACHINE_B_REMARK = "右侧"
internal const val DEFAULT_MACHINE_C_REMARK = "中间左侧"
internal const val DEFAULT_MACHINE_D_REMARK = "中间右侧"
internal val DEFAULT_MACHINE_REMARKS: Map<MachineId, String> = linkedMapOf(
    MachineId.A to DEFAULT_MACHINE_A_REMARK,
    MachineId.B to DEFAULT_MACHINE_B_REMARK,
    MachineId.C to DEFAULT_MACHINE_C_REMARK,
    MachineId.D to DEFAULT_MACHINE_D_REMARK
)
internal const val DEFAULT_MACHINE_CAPACITY = 2
internal const val DEFAULT_SOLO_ROUND_MINUTES = 12
internal const val DEFAULT_SHARED_ROUND_MINUTES = 15
internal const val MIN_PLANNED_ROUND_MINUTES = 1
internal const val MAX_PLANNED_ROUND_MINUTES = 120
internal const val MAX_MACHINE_TYPE_CHARACTERS = 24
internal const val MAX_MACHINE_SERVER_CHARACTERS = 24
internal const val MAX_GAME_VERSION_CHARACTERS = 40
internal val DEFAULT_MACHINE_CONFIGURATIONS: Map<MachineId, MachineConfiguration> =
    MachineId.entries.associateWith { machineId ->
        MachineConfiguration(remark = DEFAULT_MACHINE_REMARKS.getValue(machineId))
    }
internal const val MAX_MACHINE_REMARK_CHARACTERS = 8
internal const val MIN_QUEUE_SYNC_TOKEN_BYTES = 32
internal const val MAX_QUEUE_SYNC_TOKEN_CHARACTERS = 256
internal const val MAX_QUEUE_SYNC_ENDPOINT_CHARACTERS = 500

internal fun normalizeQueueSyncEndpoint(value: String): String? {
    val raw = value.trim()
    if (raw.isEmpty() || raw.length > MAX_QUEUE_SYNC_ENDPOINT_CHARACTERS) return null
    return runCatching {
        val parsed = URI(raw)
        require(parsed.scheme.equals("https", ignoreCase = true))
        require(!parsed.host.isNullOrBlank())
        require(parsed.userInfo == null && parsed.query == null && parsed.fragment == null)
        val normalizedBase = raw.trimEnd('/')
        val normalizedPath = parsed.path.orEmpty().trimEnd('/')
        if (normalizedPath == "/api/queue-status" || normalizedPath.endsWith("/api/queue-status")) {
            normalizedBase
        } else {
            "$normalizedBase/api/queue-status"
        }
    }.getOrNull()
}

internal fun isValidQueueSyncToken(value: String): Boolean {
    val normalized = value.trim()
    return normalized.length <= MAX_QUEUE_SYNC_TOKEN_CHARACTERS &&
        normalized.toByteArray(Charsets.UTF_8).size >= MIN_QUEUE_SYNC_TOKEN_BYTES
}

internal fun normalizeQueueRuleSettingsForRuntime(
    settings: QueueRuleSettings,
    cloudSyncAvailable: Boolean
): QueueRuleSettings {
    val normalizedSettings = settings.copy(
        configuredMachineCount = settings.configuredMachineCount.coerceIn(1, MachineId.entries.size),
        machineConfigurations = MachineId.entries.associateWith(settings::machineConfiguration),
        machineConfigurationRevision = settings.machineConfigurationRevision.coerceAtLeast(1L)
    )
    if (!cloudSyncAvailable) {
        return normalizedSettings.copy(
            websiteSyncEnabled = false,
            oneBotSyncEnabled = false,
            queueSyncEndpoint = "",
            queueSyncToken = ""
        )
    }
    val endpoint = normalizeQueueSyncEndpoint(normalizedSettings.queueSyncEndpoint)
        ?: normalizedSettings.queueSyncEndpoint.trim()
    val token = normalizedSettings.queueSyncToken.trim()
    val connectionConfigured = normalizeQueueSyncEndpoint(endpoint) == endpoint &&
        isValidQueueSyncToken(token)
    val websiteSyncEnabled = normalizedSettings.websiteSyncEnabled && connectionConfigured
    return normalizedSettings.copy(
        websiteSyncEnabled = websiteSyncEnabled,
        oneBotSyncEnabled = websiteSyncEnabled && normalizedSettings.oneBotSyncEnabled,
        queueSyncEndpoint = endpoint,
        queueSyncToken = token
    )
}

internal fun hasRiskSensitiveMachineConfigurationChange(
    previous: QueueRuleSettings,
    updated: QueueRuleSettings
): Boolean {
    if (previous.configuredMachineCount != updated.configuredMachineCount) return true
    return MachineId.entries.any { machineId ->
        previous.machineConfiguration(machineId).capacity !=
            updated.machineConfiguration(machineId).capacity
    }
}

internal fun withUpdatedMachineConfigurationRevision(
    previous: QueueRuleSettings,
    updated: QueueRuleSettings
): QueueRuleSettings {
    val configurationChanged =
        previous.configuredMachineCount != updated.configuredMachineCount ||
            MachineId.entries.any { machineId ->
                previous.machineConfiguration(machineId) != updated.machineConfiguration(machineId)
            }
    return updated.copy(
        machineConfigurationRevision = if (configurationChanged) {
            (previous.machineConfigurationRevision + 1L).coerceAtLeast(1L)
        } else {
            previous.machineConfigurationRevision.coerceAtLeast(1L)
        }
    )
}

internal fun normalizeMachineConfiguration(
    machineId: MachineId,
    configuration: MachineConfiguration
): MachineConfiguration {
    val fallback = DEFAULT_MACHINE_REMARKS.getValue(machineId)
    val gameType = configuration.gameType
    val customGameType = limitTextCodePoints(
        configuration.customGameType.trim(),
        MAX_MACHINE_TYPE_CHARACTERS
    ).let { value ->
        if (gameType == MachineGameType.OTHER) value.ifBlank { "其他" } else ""
    }
    val requestedServer = configuration.server.takeIf {
        gameType.supportsServerConfiguration
    } ?: MachineServer.HIDDEN
    val customServer = limitTextCodePoints(
        configuration.customServer.trim(),
        MAX_MACHINE_SERVER_CHARACTERS
    )
    val server = if (requestedServer == MachineServer.OTHER && customServer.isBlank()) {
        MachineServer.HIDDEN
    } else {
        requestedServer
    }
    val gameVersion = limitTextCodePoints(
        configuration.gameVersion.trim(),
        MAX_GAME_VERSION_CHARACTERS
    )
    return configuration.copy(
        remark = normalizeMachineRemark(configuration.remark, fallback),
        customGameType = customGameType,
        server = server,
        customServer = if (server == MachineServer.OTHER) customServer else "",
        gameVersion = gameVersion,
        showGameVersion = configuration.showGameVersion && gameVersion.isNotBlank(),
        capacity = configuration.capacity.takeIf { it == 1 || it == 2 }
            ?: DEFAULT_MACHINE_CAPACITY,
        soloRoundMinutes = configuration.soloRoundMinutes.coerceIn(
            MIN_PLANNED_ROUND_MINUTES,
            MAX_PLANNED_ROUND_MINUTES
        ),
        sharedRoundMinutes = configuration.sharedRoundMinutes.coerceIn(
            MIN_PLANNED_ROUND_MINUTES,
            MAX_PLANNED_ROUND_MINUTES
        )
    )
}

private fun limitTextCodePoints(value: String, maximum: Int): String {
    val characterCount = value.codePointCount(0, value.length)
    if (characterCount <= maximum) return value
    return value.substring(0, value.offsetByCodePoints(0, maximum))
}

internal fun limitMachineRemarkLength(value: String): String {
    val characterCount = value.codePointCount(0, value.length)
    if (characterCount <= MAX_MACHINE_REMARK_CHARACTERS) return value
    return value.substring(0, value.offsetByCodePoints(0, MAX_MACHINE_REMARK_CHARACTERS))
}

internal fun normalizeMachineRemark(value: String?, fallback: String): String {
    val normalized = value?.trim().orEmpty()
    return if (normalized.isBlank()) fallback else limitMachineRemarkLength(normalized)
}
