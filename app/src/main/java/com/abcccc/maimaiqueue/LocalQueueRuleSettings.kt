package com.abcccc.maimaiqueue

import android.content.Context
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
    val machineARemark: String = DEFAULT_MACHINE_A_REMARK,
    val machineBRemark: String = DEFAULT_MACHINE_B_REMARK,
    val businessHours: BusinessHoursSettings = BusinessHoursSettings()
) {
    val allowsAnyAbsenceAction: Boolean
        get() = allowDeferOneRound || allowTemporaryLeave
}

class LocalQueueRuleSettingsRepository(context: Context) {
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

    private companion object {
        const val PREFERENCES_NAME = "queue_rule_settings"
        const val KEY_ALLOW_DEFER_ONE_ROUND = "allow_defer_one_round"
        const val KEY_ALLOW_TEMPORARY_LEAVE = "allow_temporary_leave"
        const val KEY_ALLOW_ONLINE_REGISTRATION = "allow_online_registration"
        const val KEY_WEBSITE_SYNC_ENABLED = "website_sync_enabled"
        const val KEY_ONEBOT_SYNC_ENABLED = "onebot_sync_enabled"
        const val KEY_SYNC_MODE = "sync_mode"
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

internal fun limitMachineRemarkLength(value: String): String {
    val characterCount = value.codePointCount(0, value.length)
    if (characterCount <= MAX_MACHINE_REMARK_CHARACTERS) return value
    return value.substring(0, value.offsetByCodePoints(0, MAX_MACHINE_REMARK_CHARACTERS))
}

internal fun normalizeMachineRemark(value: String?, fallback: String): String {
    val normalized = value?.trim().orEmpty()
    return if (normalized.isBlank()) fallback else limitMachineRemarkLength(normalized)
}
