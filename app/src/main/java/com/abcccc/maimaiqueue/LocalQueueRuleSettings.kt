package com.abcccc.maimaiqueue

import android.content.Context
import java.net.URI
import java.time.DayOfWeek
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

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

data class MachineGroupConfiguration(
    val id: String,
    val name: String
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
    val machineStableIds: Map<MachineId, String> = DEFAULT_MACHINE_STABLE_IDS,
    val machineGroupAssignments: Map<MachineId, String> = DEFAULT_MACHINE_GROUP_ASSIGNMENTS,
    val machineGroups: List<MachineGroupConfiguration> = DEFAULT_MACHINE_GROUPS,
    val defaultMachineGroupId: String = DEFAULT_MACHINE_GROUP_ID,
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

    fun machineStableId(machineId: MachineId): String =
        normalizeMachineInternalId(
            machineStableIds[machineId],
            defaultMachineStableId(machineId)
        )

    fun machineGroupId(machineId: MachineId): String {
        val validGroupIds = machineGroups.mapTo(mutableSetOf()) { it.id }
        return machineGroupAssignments[machineId]
            ?.takeIf(validGroupIds::contains)
            ?: defaultMachineGroupId.takeIf(validGroupIds::contains)
            ?: machineGroups.firstOrNull()?.id
            ?: DEFAULT_MACHINE_GROUP_ID
    }

    val configuredMachineGroups: List<MachineGroupConfiguration>
        get() {
            val usedGroupIds = configuredMachineIds.mapTo(linkedSetOf(), ::machineGroupId)
            return machineGroups.filter { it.id in usedGroupIds }
        }

    fun machinesInGroup(groupId: String): List<MachineId> =
        configuredMachineIds.filter { machineGroupId(it) == groupId }
}

data class PendingSyncDisableSnapshot(
    val endpoint: String,
    val token: String,
    /**
     * Identity captured while this endpoint was still the active connection.
     * It must travel with the endpoint instead of being looked up from the
     * terminal's current settings after a server switch.
     */
    val venueId: String? = null,
    val terminalName: String? = null,
    /** The protocol frozen with this delayed request; legacy snapshots have no venue ID. */
    val schemaVersion: Int = pendingSyncDisableSchemaVersion(venueId)
)

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
        val storedGroups = readMachineGroups()
        val settings = QueueRuleSettings(
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
            machineStableIds = MachineId.entries.associateWith { machineId ->
                normalizeMachineInternalId(
                    preferences.getString(machineConfigurationKey(machineId, "stable_id"), null),
                    defaultMachineStableId(machineId)
                )
            },
            machineGroupAssignments = MachineId.entries.associateWith { machineId ->
                preferences.getString(
                    machineConfigurationKey(machineId, "group_id"),
                    null
                ).orEmpty()
            },
            machineGroups = storedGroups,
            defaultMachineGroupId = preferences.getString(
                KEY_DEFAULT_MACHINE_GROUP_ID,
                storedGroups.firstOrNull()?.id ?: DEFAULT_MACHINE_GROUP_ID
            ).orEmpty(),
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
        return normalizeMachineLayoutSettings(settings)
    }

    fun saveSettings(settings: QueueRuleSettings) {
        val normalizedSettings = normalizeMachineLayoutSettings(settings)
        val oneBotSyncEnabled = normalizedSettings.websiteSyncEnabled &&
            normalizedSettings.oneBotSyncEnabled
        preferences.edit()
            .putBoolean(KEY_ALLOW_DEFER_ONE_ROUND, normalizedSettings.allowDeferOneRound)
            .putBoolean(KEY_ALLOW_TEMPORARY_LEAVE, normalizedSettings.allowTemporaryLeave)
            .putBoolean(KEY_ALLOW_ONLINE_REGISTRATION, normalizedSettings.allowOnlineRegistration)
            .putBoolean(KEY_SHOW_COMMON_PLAY_PREVIEW, normalizedSettings.showCommonPlayPreview)
            .putBoolean(KEY_WEBSITE_SYNC_ENABLED, normalizedSettings.websiteSyncEnabled)
            .putBoolean(KEY_ONEBOT_SYNC_ENABLED, oneBotSyncEnabled)
            .putString(KEY_SYNC_MODE, normalizedSettings.syncMode.name)
            .putString(KEY_QUEUE_SYNC_ENDPOINT, normalizedSettings.queueSyncEndpoint.trim())
            .putString(KEY_QUEUE_SYNC_TOKEN, normalizedSettings.queueSyncToken.trim())
            .putInt(
                KEY_CONFIGURED_MACHINE_COUNT,
                normalizedSettings.configuredMachineCount
            )
            .putLong(
                KEY_MACHINE_CONFIGURATION_REVISION,
                normalizedSettings.machineConfigurationRevision.coerceAtLeast(1L)
            )
            .putString(KEY_MACHINE_GROUPS, encodeMachineGroups(normalizedSettings.machineGroups))
            .putString(KEY_DEFAULT_MACHINE_GROUP_ID, normalizedSettings.defaultMachineGroupId)
            .putBoolean(KEY_BUSINESS_HOURS_ENABLED, normalizedSettings.businessHours.enabled)
            .putBoolean(
                KEY_WEEKLY_BUSINESS_HOURS_ENABLED,
                normalizedSettings.businessHours.useWeeklySchedule
            )
            .putInt(
                KEY_DEFAULT_OPENING_MINUTES,
                normalizedSettings.businessHours.defaultHours.openingMinutes
            )
            .putInt(
                KEY_DEFAULT_CLOSING_MINUTES,
                normalizedSettings.businessHours.defaultHours.closingMinutes
            )
            .also { editor ->
                MachineId.entries.forEach { machineId ->
                    val configuration = normalizedSettings.machineConfiguration(machineId)
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
                    editor.putString(
                        machineConfigurationKey(machineId, "stable_id"),
                        normalizedSettings.machineStableId(machineId)
                    )
                    editor.putString(
                        machineConfigurationKey(machineId, "group_id"),
                        normalizedSettings.machineGroupId(machineId)
                    )
                }
                DayOfWeek.entries.forEach { day ->
                    val hours = normalizedSettings.businessHours.hoursFor(day).normalized()
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

    /**
     * Keep this marker across process death until the server confirms that
     * remote operations are disabled. A synchronous commit is intentional:
     * losing the marker immediately after the user switches the setting off
     * would leave stale remote capabilities on the server.
     */
    fun getPendingSyncDisableSnapshot(): PendingSyncDisableSnapshot? {
        val endpoint = preferences.getString(KEY_PENDING_SYNC_DISABLE_ENDPOINT, null)
            ?.trim()
            .orEmpty()
        val token = preferences.getString(KEY_PENDING_SYNC_DISABLE_TOKEN, null)
            ?.trim()
            .orEmpty()
        return endpoint.takeIf { it.isNotEmpty() }?.let {
            val venueId = preferences.getString(KEY_PENDING_SYNC_DISABLE_VENUE_ID, null)
                ?.trim()?.takeIf(String::isNotEmpty)
            val fallbackSchemaVersion = pendingSyncDisableSchemaVersion(venueId)
            PendingSyncDisableSnapshot(
                endpoint = it,
                token = token,
                venueId = venueId,
                terminalName = preferences.getString(
                    KEY_PENDING_SYNC_DISABLE_TERMINAL_NAME,
                    null
                )?.trim()?.takeIf(String::isNotEmpty),
                schemaVersion = preferences.getInt(
                    KEY_PENDING_SYNC_DISABLE_SCHEMA_VERSION,
                    fallbackSchemaVersion
                ).takeIf { version ->
                    version in LEGACY_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION
                } ?: fallbackSchemaVersion
            )
        }
    }

    fun markPendingSyncDisableSnapshot(
        endpoint: String,
        token: String,
        venueId: String? = null,
        terminalName: String? = null,
        schemaVersion: Int = pendingSyncDisableSchemaVersion(venueId)
    ) {
        preferences.edit()
            .putString(KEY_PENDING_SYNC_DISABLE_ENDPOINT, endpoint.trim())
            .putString(KEY_PENDING_SYNC_DISABLE_TOKEN, token.trim())
            .putString(KEY_PENDING_SYNC_DISABLE_VENUE_ID, venueId?.trim())
            .putString(KEY_PENDING_SYNC_DISABLE_TERMINAL_NAME, terminalName?.trim())
            .putInt(
                KEY_PENDING_SYNC_DISABLE_SCHEMA_VERSION,
                schemaVersion.takeIf {
                    it in LEGACY_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION
                } ?: pendingSyncDisableSchemaVersion(venueId)
            )
            .commit()
    }

    fun clearPendingSyncDisableSnapshot() {
        preferences.edit()
            .remove(KEY_PENDING_SYNC_DISABLE_ENDPOINT)
            .remove(KEY_PENDING_SYNC_DISABLE_TOKEN)
            .remove(KEY_PENDING_SYNC_DISABLE_VENUE_ID)
            .remove(KEY_PENDING_SYNC_DISABLE_TERMINAL_NAME)
            .remove(KEY_PENDING_SYNC_DISABLE_SCHEMA_VERSION)
            .commit()
    }

    private fun readHours(openingKey: String, closingKey: String): DailyBusinessHours =
        DailyBusinessHours(
            openingMinutes = preferences.getInt(openingKey, DEFAULT_OPENING_MINUTES),
            closingMinutes = preferences.getInt(closingKey, DEFAULT_CLOSING_MINUTES)
        ).normalized()

    private fun readMachineGroups(): List<MachineGroupConfiguration> {
        val source = preferences.getString(KEY_MACHINE_GROUPS, null)
            ?: return DEFAULT_MACHINE_GROUPS
        return runCatching {
            val array = JSONArray(source)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        MachineGroupConfiguration(
                            id = item.optString("id"),
                            name = item.optString("name")
                        )
                    )
                }
            }
        }.getOrDefault(DEFAULT_MACHINE_GROUPS)
    }

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
        const val KEY_MACHINE_GROUPS = "machine_groups"
        const val KEY_DEFAULT_MACHINE_GROUP_ID = "default_machine_group_id"
        const val KEY_MACHINE_A_REMARK = "machine_a_remark"
        const val KEY_MACHINE_B_REMARK = "machine_b_remark"
        const val KEY_BUSINESS_HOURS_ENABLED = "business_hours_enabled"
        const val KEY_WEEKLY_BUSINESS_HOURS_ENABLED = "weekly_business_hours_enabled"
        const val KEY_DEFAULT_OPENING_MINUTES = "default_opening_minutes"
        const val KEY_DEFAULT_CLOSING_MINUTES = "default_closing_minutes"
        const val KEY_LAST_HANDLED_CLOSING_OCCURRENCE = "last_handled_closing_occurrence"
        const val KEY_PENDING_SYNC_DISABLE_ENDPOINT = "pending_sync_disable_endpoint"
        const val KEY_PENDING_SYNC_DISABLE_TOKEN = "pending_sync_disable_token"
        const val KEY_PENDING_SYNC_DISABLE_VENUE_ID = "pending_sync_disable_venue_id"
        const val KEY_PENDING_SYNC_DISABLE_TERMINAL_NAME = "pending_sync_disable_terminal_name"
        const val KEY_PENDING_SYNC_DISABLE_SCHEMA_VERSION = "pending_sync_disable_schema_version"
    }
}

internal fun pendingSyncDisableSchemaVersion(venueId: String?): Int =
    if (venueId.isNullOrBlank()) LEGACY_SCHEMA_VERSION else CURRENT_SCHEMA_VERSION

internal const val DEFAULT_MACHINE_A_REMARK = "左侧"
internal const val DEFAULT_MACHINE_B_REMARK = "右侧"
internal const val DEFAULT_MACHINE_C_REMARK = "中间左侧"
internal const val DEFAULT_MACHINE_D_REMARK = "中间右侧"
internal const val DEFAULT_MACHINE_GROUP_ID = "00000000000000000000000000000001"
internal const val DEFAULT_MACHINE_GROUP_NAME = "分组 1"
internal val DEFAULT_MACHINE_GROUPS = listOf(
    MachineGroupConfiguration(DEFAULT_MACHINE_GROUP_ID, DEFAULT_MACHINE_GROUP_NAME)
)
internal val DEFAULT_MACHINE_REMARKS: Map<MachineId, String> = MachineId.entries.associateWith {
    when (it) {
        MachineId.A -> DEFAULT_MACHINE_A_REMARK
        MachineId.B -> DEFAULT_MACHINE_B_REMARK
        MachineId.C -> DEFAULT_MACHINE_C_REMARK
        MachineId.D -> DEFAULT_MACHINE_D_REMARK
        else -> "第 ${it.ordinal + 1} 台"
    }
}
internal val DEFAULT_MACHINE_STABLE_IDS: Map<MachineId, String> =
    MachineId.entries.associateWith(::defaultMachineStableId)
internal val DEFAULT_MACHINE_GROUP_ASSIGNMENTS: Map<MachineId, String> =
    MachineId.entries.associateWith { DEFAULT_MACHINE_GROUP_ID }
internal const val DEFAULT_MACHINE_CAPACITY = 2
internal const val DEFAULT_SOLO_ROUND_MINUTES = 12
internal const val DEFAULT_SHARED_ROUND_MINUTES = 15
internal const val MIN_PLANNED_ROUND_MINUTES = 1
internal const val MAX_PLANNED_ROUND_MINUTES = 120
internal const val MAX_MACHINE_TYPE_CHARACTERS = 24
internal const val MAX_MACHINE_SERVER_CHARACTERS = 24
internal const val MAX_GAME_VERSION_CHARACTERS = 40
internal const val MAX_MACHINE_GROUP_NAME_CHARACTERS = 12
internal val DEFAULT_MACHINE_CONFIGURATIONS: Map<MachineId, MachineConfiguration> =
    MachineId.entries.associateWith { machineId ->
        MachineConfiguration(remark = DEFAULT_MACHINE_REMARKS.getValue(machineId))
    }
internal const val MAX_MACHINE_REMARK_CHARACTERS = 8
internal const val MIN_QUEUE_SYNC_TOKEN_BYTES = 32
internal const val MAX_QUEUE_SYNC_TOKEN_CHARACTERS = 256
internal const val MAX_QUEUE_SYNC_ENDPOINT_CHARACTERS = 500

private val MACHINE_INTERNAL_ID_PATTERN = Regex("^[0-9a-f]{32}$")

internal fun defaultMachineStableId(machineId: MachineId): String =
    (machineId.ordinal + 1).toString(16).padStart(32, '0')

internal fun newMachineInternalId(): String = UUID.randomUUID().toString().replace("-", "")

internal fun normalizeMachineInternalId(value: String?, fallback: String): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.takeIf(MACHINE_INTERNAL_ID_PATTERN::matches) ?: fallback
}

internal fun limitMachineGroupNameLength(value: String): String =
    limitTextCodePoints(value, MAX_MACHINE_GROUP_NAME_CHARACTERS)

internal fun normalizeMachineGroupName(value: String?, fallbackIndex: Int): String {
    val normalized = value?.trim().orEmpty()
    return limitMachineGroupNameLength(normalized).ifBlank { "分组 ${fallbackIndex + 1}" }
}

internal fun encodeMachineGroups(groups: List<MachineGroupConfiguration>): String =
    JSONArray().apply {
        groups.forEach { group ->
            put(JSONObject().put("id", group.id).put("name", group.name))
        }
    }.toString()

internal fun normalizeMachineLayoutSettings(settings: QueueRuleSettings): QueueRuleSettings {
    val machineCount = settings.configuredMachineCount.coerceIn(1, MachineId.entries.size)
    val configuredIds = configuredMachineIds(machineCount)
    val normalizedStableIds = linkedMapOf<MachineId, String>()
    val usedStableIds = mutableSetOf<String>()
    MachineId.entries.forEach { machineId ->
        var candidate = normalizeMachineInternalId(
            settings.machineStableIds[machineId],
            defaultMachineStableId(machineId)
        )
        if (machineId in configuredIds && !usedStableIds.add(candidate)) {
            candidate = newMachineInternalId().also(usedStableIds::add)
        }
        normalizedStableIds[machineId] = candidate
    }

    val normalizedGroups = buildList {
        val seenIds = mutableSetOf<String>()
        settings.machineGroups.forEachIndexed { index, group ->
            val fallbackId = if (index == 0) DEFAULT_MACHINE_GROUP_ID else newMachineInternalId()
            val id = normalizeMachineInternalId(group.id, fallbackId)
            if (seenIds.add(id)) {
                add(
                    MachineGroupConfiguration(
                        id = id,
                        name = normalizeMachineGroupName(group.name, size)
                    )
                )
            }
        }
    }.ifEmpty { DEFAULT_MACHINE_GROUPS }
    val validGroupIds = normalizedGroups.mapTo(mutableSetOf()) { it.id }
    val requestedDefaultGroupId = normalizeMachineInternalId(
        settings.defaultMachineGroupId,
        normalizedGroups.first().id
    ).takeIf(validGroupIds::contains) ?: normalizedGroups.first().id
    val normalizedAssignments = MachineId.entries.associateWith { machineId ->
        normalizeMachineInternalId(
            settings.machineGroupAssignments[machineId],
            requestedDefaultGroupId
        ).takeIf(validGroupIds::contains) ?: requestedDefaultGroupId
    }
    val usedGroupIds = configuredIds.mapTo(linkedSetOf()) { normalizedAssignments.getValue(it) }
    val retainedGroups = normalizedGroups.filter { it.id in usedGroupIds }.ifEmpty {
        DEFAULT_MACHINE_GROUPS
    }
    val retainedGroupIds = retainedGroups.mapTo(mutableSetOf()) { it.id }
    val fallbackGroupId = retainedGroups.first().id
    val finalAssignments = MachineId.entries.associateWith { machineId ->
        normalizedAssignments.getValue(machineId).takeIf(retainedGroupIds::contains)
            ?: fallbackGroupId
    }
    val defaultGroupId = requestedDefaultGroupId.takeIf(retainedGroupIds::contains)
        ?: fallbackGroupId

    return settings.copy(
        configuredMachineCount = machineCount,
        machineConfigurations = MachineId.entries.associateWith(settings::machineConfiguration),
        machineStableIds = normalizedStableIds,
        machineGroupAssignments = finalAssignments,
        machineGroups = retainedGroups,
        defaultMachineGroupId = defaultGroupId,
        machineConfigurationRevision = settings.machineConfigurationRevision.coerceAtLeast(1L)
    )
}

internal fun appendMachineConfiguration(
    settings: QueueRuleSettings,
    groupId: String = settings.defaultMachineGroupId
): QueueRuleSettings? {
    val normalized = normalizeMachineLayoutSettings(settings)
    if (normalized.configuredMachineCount >= MachineId.entries.size) return null
    val machineId = MachineId.entries[normalized.configuredMachineCount]
    val targetGroupId = groupId.takeIf { requested ->
        normalized.machineGroups.any { it.id == requested }
    } ?: normalized.defaultMachineGroupId
    return normalizeMachineLayoutSettings(
        normalized.copy(
            configuredMachineCount = normalized.configuredMachineCount + 1,
            machineConfigurations = normalized.machineConfigurations +
                (machineId to DEFAULT_MACHINE_CONFIGURATIONS.getValue(machineId)),
            machineStableIds = normalized.machineStableIds +
                (machineId to newMachineInternalId()),
            machineGroupAssignments = normalized.machineGroupAssignments +
                (machineId to targetGroupId)
        )
    )
}

internal fun removeMachineConfiguration(
    settings: QueueRuleSettings,
    machineId: MachineId
): QueueRuleSettings? {
    val normalized = normalizeMachineLayoutSettings(settings)
    val configuredIds = normalized.configuredMachineIds
    val removalIndex = configuredIds.indexOf(machineId)
    if (removalIndex < 0 || configuredIds.size <= 1) return null
    val retainedIds = configuredIds.filterNot { it == machineId }
    val shiftedConfigurations = MachineId.entries.associateWith { targetId ->
        val sourceId = retainedIds.getOrNull(targetId.ordinal)
        sourceId?.let(normalized::machineConfiguration)
            ?: DEFAULT_MACHINE_CONFIGURATIONS.getValue(targetId)
    }
    val shiftedStableIds = MachineId.entries.associateWith { targetId ->
        retainedIds.getOrNull(targetId.ordinal)?.let(normalized::machineStableId)
            ?: defaultMachineStableId(targetId)
    }
    val shiftedAssignments = MachineId.entries.associateWith { targetId ->
        retainedIds.getOrNull(targetId.ordinal)?.let(normalized::machineGroupId)
            ?: normalized.defaultMachineGroupId
    }
    return normalizeMachineLayoutSettings(
        normalized.copy(
            configuredMachineCount = configuredIds.size - 1,
            machineConfigurations = shiftedConfigurations,
            machineStableIds = shiftedStableIds,
            machineGroupAssignments = shiftedAssignments
        )
    )
}

internal fun moveMachineToGroup(
    settings: QueueRuleSettings,
    machineId: MachineId,
    groupId: String
): QueueRuleSettings {
    val normalized = normalizeMachineLayoutSettings(settings)
    if (machineId !in normalized.configuredMachineIds ||
        normalized.machineGroups.none { it.id == groupId }
    ) {
        return normalized
    }
    return normalizeMachineLayoutSettings(
        normalized.copy(
            machineGroupAssignments = normalized.machineGroupAssignments + (machineId to groupId)
        )
    )
}

internal fun createMachineGroupForMachine(
    settings: QueueRuleSettings,
    machineId: MachineId,
    requestedName: String = ""
): QueueRuleSettings {
    val normalized = normalizeMachineLayoutSettings(settings)
    if (machineId !in normalized.configuredMachineIds ||
        normalized.machinesInGroup(normalized.machineGroupId(machineId)).size <= 1
    ) {
        return normalized
    }
    val groupId = newMachineInternalId()
    val newGroup = MachineGroupConfiguration(
        id = groupId,
        name = normalizeMachineGroupName(requestedName, normalized.machineGroups.size)
    )
    return normalizeMachineLayoutSettings(
        normalized.copy(
            machineGroups = normalized.machineGroups + newGroup,
            machineGroupAssignments = normalized.machineGroupAssignments + (machineId to groupId)
        )
    )
}

internal fun renameMachineGroup(
    settings: QueueRuleSettings,
    groupId: String,
    requestedName: String
): QueueRuleSettings {
    val normalized = normalizeMachineLayoutSettings(settings)
    val index = normalized.machineGroups.indexOfFirst { it.id == groupId }
    if (index < 0) return normalized
    val updatedGroups = normalized.machineGroups.toMutableList().apply {
        this[index] = this[index].copy(
            name = limitMachineGroupNameLength(requestedName)
        )
    }
    return normalized.copy(machineGroups = updatedGroups)
}

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

internal fun hasQueueConnectionDraftChanged(
    persistedEndpoint: String,
    persistedToken: String,
    endpointDraft: String,
    tokenDraft: String
): Boolean {
    fun comparableEndpoint(value: String): String =
        normalizeQueueSyncEndpoint(value) ?: value.trim()

    return comparableEndpoint(endpointDraft) != comparableEndpoint(persistedEndpoint) ||
        tokenDraft.trim() != persistedToken.trim()
}

internal fun sameQueueSyncEndpoint(first: String, second: String): Boolean {
    fun comparable(value: String): String =
        normalizeQueueSyncEndpoint(value) ?: value.trim()

    return comparable(first) == comparable(second)
}

internal fun normalizeQueueRuleSettingsForRuntime(
    settings: QueueRuleSettings,
    cloudSyncAvailable: Boolean
): QueueRuleSettings {
    val normalizedSettings = normalizeMachineLayoutSettings(settings)
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
    val normalizedPrevious = normalizeMachineLayoutSettings(previous)
    val normalizedUpdated = normalizeMachineLayoutSettings(updated)
    if (normalizedPrevious.configuredMachineCount != normalizedUpdated.configuredMachineCount) {
        return true
    }
    if (normalizedPrevious.configuredMachineIds.any { machineId ->
            normalizedPrevious.machineStableId(machineId) !=
                normalizedUpdated.machineStableId(machineId)
        }
    ) {
        return true
    }
    return normalizedPrevious.configuredMachineIds.any { machineId ->
        val previousConfiguration = normalizedPrevious.machineConfiguration(machineId)
        val updatedConfiguration = normalizedUpdated.machineConfiguration(machineId)
        previousConfiguration.capacity != updatedConfiguration.capacity ||
            previousConfiguration.soloRoundMinutes != updatedConfiguration.soloRoundMinutes ||
            previousConfiguration.sharedRoundMinutes != updatedConfiguration.sharedRoundMinutes
    }
}

internal fun withUpdatedMachineConfigurationRevision(
    previous: QueueRuleSettings,
    updated: QueueRuleSettings
): QueueRuleSettings {
    val configurationChanged = hasRiskSensitiveMachineConfigurationChange(previous, updated)
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
