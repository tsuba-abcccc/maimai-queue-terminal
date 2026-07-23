package com.abcccc.maimaiqueue

import android.content.Context

data class QueueRuleSettings(
    val allowDeferOneRound: Boolean = true,
    val allowTemporaryLeave: Boolean = true,
    val websiteSyncEnabled: Boolean = true,
    val machineARemark: String = DEFAULT_MACHINE_A_REMARK,
    val machineBRemark: String = DEFAULT_MACHINE_B_REMARK
) {
    val allowsAnyAbsenceAction: Boolean
        get() = allowDeferOneRound || allowTemporaryLeave
}

class LocalQueueRuleSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getSettings(): QueueRuleSettings = QueueRuleSettings(
        allowDeferOneRound = preferences.getBoolean(KEY_ALLOW_DEFER_ONE_ROUND, true),
        allowTemporaryLeave = preferences.getBoolean(KEY_ALLOW_TEMPORARY_LEAVE, true),
        websiteSyncEnabled = preferences.getBoolean(KEY_WEBSITE_SYNC_ENABLED, true),
        machineARemark = normalizeMachineRemark(
            preferences.getString(KEY_MACHINE_A_REMARK, DEFAULT_MACHINE_A_REMARK),
            DEFAULT_MACHINE_A_REMARK
        ),
        machineBRemark = normalizeMachineRemark(
            preferences.getString(KEY_MACHINE_B_REMARK, DEFAULT_MACHINE_B_REMARK),
            DEFAULT_MACHINE_B_REMARK
        )
    )

    fun saveSettings(settings: QueueRuleSettings) {
        preferences.edit()
            .putBoolean(KEY_ALLOW_DEFER_ONE_ROUND, settings.allowDeferOneRound)
            .putBoolean(KEY_ALLOW_TEMPORARY_LEAVE, settings.allowTemporaryLeave)
            .putBoolean(KEY_WEBSITE_SYNC_ENABLED, settings.websiteSyncEnabled)
            .putString(
                KEY_MACHINE_A_REMARK,
                normalizeMachineRemark(settings.machineARemark, DEFAULT_MACHINE_A_REMARK)
            )
            .putString(
                KEY_MACHINE_B_REMARK,
                normalizeMachineRemark(settings.machineBRemark, DEFAULT_MACHINE_B_REMARK)
            )
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "queue_rule_settings"
        const val KEY_ALLOW_DEFER_ONE_ROUND = "allow_defer_one_round"
        const val KEY_ALLOW_TEMPORARY_LEAVE = "allow_temporary_leave"
        const val KEY_WEBSITE_SYNC_ENABLED = "website_sync_enabled"
        const val KEY_MACHINE_A_REMARK = "machine_a_remark"
        const val KEY_MACHINE_B_REMARK = "machine_b_remark"
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
