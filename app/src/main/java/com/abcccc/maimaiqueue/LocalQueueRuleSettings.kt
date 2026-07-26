package com.abcccc.maimaiqueue

import android.content.Context
import java.net.URI
import java.time.DayOfWeek

enum class QueueSyncMode(val headerValue: String?) {
    UNSPECIFIED(null),
    TEST("test"),
    TAKEOVER("takeover")
}

data class QueueRuleSettings(
    val allowDeferOneRound: Boolean = true,
    val allowTemporaryLeave: Boolean = true,
    val allowOnlineRegistration: Boolean = true,
    val websiteSyncEnabled: Boolean = true,
    val oneBotSyncEnabled: Boolean = true,
    val syncMode: QueueSyncMode = QueueSyncMode.UNSPECIFIED,
    val queueSyncEndpoint: String = "",
    val queueSyncToken: String = "",
    val machineARemark: String = DEFAULT_MACHINE_A_REMARK,
    val machineBRemark: String = DEFAULT_MACHINE_B_REMARK,
    val businessHours: BusinessHoursSettings = BusinessHoursSettings()
) {
    val allowsAnyAbsenceAction: Boolean
        get() = allowDeferOneRound || allowTemporaryLeave
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
            machineARemark = normalizeMachineRemark(
                preferences.getString(KEY_MACHINE_A_REMARK, DEFAULT_MACHINE_A_REMARK),
                DEFAULT_MACHINE_A_REMARK
            ),
            machineBRemark = normalizeMachineRemark(
                preferences.getString(KEY_MACHINE_B_REMARK, DEFAULT_MACHINE_B_REMARK),
                DEFAULT_MACHINE_B_REMARK
            ),
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
            .putBoolean(KEY_WEBSITE_SYNC_ENABLED, settings.websiteSyncEnabled)
            .putBoolean(KEY_ONEBOT_SYNC_ENABLED, oneBotSyncEnabled)
            .putString(KEY_SYNC_MODE, settings.syncMode.name)
            .putString(KEY_QUEUE_SYNC_ENDPOINT, settings.queueSyncEndpoint.trim())
            .putString(KEY_QUEUE_SYNC_TOKEN, settings.queueSyncToken.trim())
            .putBoolean(KEY_BUSINESS_HOURS_ENABLED, settings.businessHours.enabled)
            .putBoolean(KEY_WEEKLY_BUSINESS_HOURS_ENABLED, settings.businessHours.useWeeklySchedule)
            .putInt(KEY_DEFAULT_OPENING_MINUTES, settings.businessHours.defaultHours.openingMinutes)
            .putInt(KEY_DEFAULT_CLOSING_MINUTES, settings.businessHours.defaultHours.closingMinutes)
            .putString(
                KEY_MACHINE_A_REMARK,
                normalizeMachineRemark(settings.machineARemark, DEFAULT_MACHINE_A_REMARK)
            )
            .putString(
                KEY_MACHINE_B_REMARK,
                normalizeMachineRemark(settings.machineBRemark, DEFAULT_MACHINE_B_REMARK)
            )
            .also { editor ->
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
        const val KEY_WEBSITE_SYNC_ENABLED = "website_sync_enabled"
        const val KEY_ONEBOT_SYNC_ENABLED = "onebot_sync_enabled"
        const val KEY_SYNC_MODE = "sync_mode"
        const val KEY_QUEUE_SYNC_ENDPOINT = "queue_sync_endpoint"
        const val KEY_QUEUE_SYNC_TOKEN = "queue_sync_token"
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
    if (!cloudSyncAvailable) {
        return settings.copy(
            websiteSyncEnabled = false,
            oneBotSyncEnabled = false,
            queueSyncEndpoint = "",
            queueSyncToken = ""
        )
    }
    val endpoint = normalizeQueueSyncEndpoint(settings.queueSyncEndpoint)
        ?: settings.queueSyncEndpoint.trim()
    val token = settings.queueSyncToken.trim()
    val connectionConfigured = normalizeQueueSyncEndpoint(endpoint) == endpoint &&
        isValidQueueSyncToken(token)
    val websiteSyncEnabled = settings.websiteSyncEnabled && connectionConfigured
    return settings.copy(
        websiteSyncEnabled = websiteSyncEnabled,
        oneBotSyncEnabled = websiteSyncEnabled && settings.oneBotSyncEnabled,
        queueSyncEndpoint = endpoint,
        queueSyncToken = token
    )
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
