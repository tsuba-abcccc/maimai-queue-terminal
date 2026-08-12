package com.abcccc.maimaiqueue

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface AuditLogRepository {
    suspend fun getLogs(): List<AuditLogEntry>
    suspend fun append(entry: AuditLogEntry)
    suspend fun backfillMachineIdentities(
        identities: Map<AuditLogCategory, AuditMachineIdentity>
    )
}

class LocalAuditLogRepository(context: Context) : AuditLogRepository {
    private val writeMutex = Mutex()
    private val preferences = context.applicationContext.getSharedPreferences(
        "audit_logs",
        Context.MODE_PRIVATE
    )

    override suspend fun getLogs(): List<AuditLogEntry> = withContext(Dispatchers.IO) {
        writeMutex.withLock { loadLogs() }
    }

    override suspend fun append(entry: AuditLogEntry) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            saveLogs((listOf(entry) + loadLogs().filterNot { it.id == entry.id }).take(MAX_LOGS))
        }
    }

    override suspend fun backfillMachineIdentities(
        identities: Map<AuditLogCategory, AuditMachineIdentity>
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val existing = loadLogs()
            val updated = existing.map { entry ->
                entry.withMachineIdentity(identities[entry.category])
            }
            if (updated != existing) saveLogs(updated)
        }
    }

    private fun loadLogs(): List<AuditLogEntry> {
        val serialized = preferences.getString(KEY_LOGS, null) ?: return emptyList()
        val result = deserializeAuditLogsWithCanonicalSerialization(serialized, MAX_LOGS)
            ?: return emptyList()
        if (result.canonicalSerialization != serialized) {
            preferences.edit().putString(KEY_LOGS, result.canonicalSerialization).commit()
        }
        return result.logs
    }

    private fun saveLogs(logs: List<AuditLogEntry>) {
        preferences.edit().putString(KEY_LOGS, serializeAuditLogs(logs)).commit()
    }

    private companion object {
        const val KEY_LOGS = "entries"
        const val MAX_LOGS = 1_000
    }
}

internal fun deserializeAuditLogs(serialized: String, maxLogs: Int = 1_000): List<AuditLogEntry> =
    deserializeAuditLogsWithCanonicalSerialization(serialized, maxLogs)?.logs.orEmpty()

private data class DeserializedAuditLogs(
    val logs: List<AuditLogEntry>,
    val canonicalSerialization: String
)

private fun deserializeAuditLogsWithCanonicalSerialization(
    serialized: String,
    maxLogs: Int
): DeserializedAuditLogs? = runCatching {
        val array = JSONArray(serialized)
        val logs = buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@repeat
                val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@repeat
                val timestampMillis = item.optLong("timestampMillis", 0L)
                if (timestampMillis <= 0L) return@repeat
                val publicEventType = enumValues<PublicQueueEventType>().firstOrNull {
                    it.name == item.optString("publicEventType")
                }
                val notificationCategories = item.optJSONArray("notificationCategories")
                    ?.let { categories ->
                        buildSet {
                            repeat(categories.length()) { categoryIndex ->
                                enumValues<PublicQueueNotificationCategory>().firstOrNull {
                                    it.name == categories.optString(categoryIndex)
                                }?.let(::add)
                            }
                        }
                    }
                    ?: publicEventType
                        ?.let(::notificationCategoryForEventType)
                        ?.let(::setOf)
                        .orEmpty()
                val category = enumValues<AuditLogCategory>().firstOrNull {
                    it.name == item.optString("category")
                } ?: AuditLogCategory.SYSTEM
                add(
                    AuditLogEntry(
                        id = id,
                        timestampMillis = timestampMillis,
                        category = category,
                        title = title,
                        detail = item.optString("detail"),
                        source = enumValues<AuditLogSource>().firstOrNull {
                            it.name == item.optString("source")
                        } ?: AuditLogSource.ON_SITE_TERMINAL,
                        queueId = item.optionalPersistedString("queueId"),
                        publicEventType = publicEventType,
                        notificationCategories = notificationCategories,
                        affectedRegistrationKeys = item.optJSONArray("affectedRegistrationKeys")
                            ?.let { keys ->
                                buildList {
                                    repeat(keys.length()) { keyIndex ->
                                        keys.optInt(keyIndex).takeIf { it > 0 }?.let(::add)
                                    }
                                }
                            }
                            .orEmpty(),
                        affectedPlayerContacts = item.optJSONArray("affectedPlayerContacts")
                            ?.let { contacts ->
                                buildList {
                                    repeat(contacts.length()) { contactIndex ->
                                        val contact = contacts.optJSONObject(contactIndex)
                                            ?: return@repeat
                                        val registrationKey = contact.optInt("registrationKey")
                                            .takeIf { it > 0 }
                                            ?: return@repeat
                                        val profileId = contact.optString("profileId")
                                            .takeIf(String::isNotBlank)
                                            ?: return@repeat
                                        val qqNumber = contact.optString("qqNumber")
                                            .takeIf { it.isNotBlank() && isValidQqNumber(it) }
                                            ?: return@repeat
                                        add(
                                            AuditPlayerContact(
                                                registrationKey = registrationKey,
                                                profileId = profileId,
                                                qqNumber = qqNumber
                                            )
                                        )
                                    }
                                }.distinctBy(AuditPlayerContact::registrationKey)
                            }
                            .orEmpty(),
                        machineStableId = item.optionalPersistedString("machineStableId")
                            ?.lowercase()
                            ?.takeIf { MACHINE_STABLE_ID_PATTERN.matches(it) },
                        machineName = item.optionalPersistedString("machineName")
                    ).withMachineIdentity(null)
                )
            }
        }.sortedByDescending { it.timestampMillis }.take(maxLogs)
        DeserializedAuditLogs(
            logs = logs,
            canonicalSerialization = serializeAuditLogs(logs)
        )
    }.getOrNull()

internal fun serializeAuditLogs(logs: List<AuditLogEntry>): String = JSONArray().apply {
    logs.forEach { entry ->
        val normalizedEntry = entry.withMachineIdentity(null)
        put(
            JSONObject().apply {
                put("id", normalizedEntry.id)
                put("timestampMillis", normalizedEntry.timestampMillis)
                put("category", normalizedEntry.category.name)
                put("title", normalizedEntry.title)
                put("detail", normalizedEntry.detail)
                put("source", normalizedEntry.source.name)
                put("queueId", normalizedEntry.queueId ?: JSONObject.NULL)
                put("machineStableId", normalizedEntry.machineStableId ?: JSONObject.NULL)
                put("machineName", normalizedEntry.machineName ?: JSONObject.NULL)
                put("publicEventType", normalizedEntry.publicEventType?.name ?: JSONObject.NULL)
                put(
                    "notificationCategories",
                    JSONArray().apply {
                        normalizedEntry.notificationCategories
                            .sortedBy(PublicQueueNotificationCategory::name)
                            .forEach { put(it.name) }
                    }
                )
                put(
                    "affectedRegistrationKeys",
                    JSONArray().apply {
                        normalizedEntry.affectedRegistrationKeys.forEach(::put)
                    }
                )
                put(
                    "affectedPlayerContacts",
                    JSONArray().apply {
                        normalizedEntry.affectedPlayerContacts.forEach { contact ->
                            put(JSONObject().apply {
                                put("registrationKey", contact.registrationKey)
                                put("profileId", contact.profileId)
                                put("qqNumber", contact.qqNumber)
                            })
                        }
                    }
                )
            }
        )
    }
}.toString()

private fun JSONObject.optionalPersistedString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return (opt(key) as? String)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != LEGACY_NULL_STRING }
}

private val MACHINE_STABLE_ID_PATTERN = Regex("^[0-9a-f]{32}$")
private const val LEGACY_NULL_STRING = "null"
