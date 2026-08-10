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
        return deserializeAuditLogs(serialized, MAX_LOGS)
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
    runCatching {
        val array = JSONArray(serialized)
        buildList {
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
                add(
                    AuditLogEntry(
                        id = id,
                        timestampMillis = timestampMillis,
                        category = enumValues<AuditLogCategory>().firstOrNull {
                            it.name == item.optString("category")
                        } ?: AuditLogCategory.SYSTEM,
                        title = title,
                        detail = item.optString("detail"),
                        source = enumValues<AuditLogSource>().firstOrNull {
                            it.name == item.optString("source")
                        } ?: AuditLogSource.ON_SITE_TERMINAL,
                        queueId = item.optString("queueId").takeIf { it.isNotBlank() },
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
                        machineStableId = item.optString("machineStableId")
                            .lowercase()
                            .takeIf { MACHINE_STABLE_ID_PATTERN.matches(it) },
                        machineName = item.optString("machineName")
                            .takeIf { it.isNotBlank() }
                    )
                )
            }
        }.sortedByDescending { it.timestampMillis }.take(maxLogs)
    }.getOrDefault(emptyList())

internal fun serializeAuditLogs(logs: List<AuditLogEntry>): String = JSONArray().apply {
    logs.forEach { entry ->
        put(
            JSONObject().apply {
                put("id", entry.id)
                put("timestampMillis", entry.timestampMillis)
                put("category", entry.category.name)
                put("title", entry.title)
                put("detail", entry.detail)
                put("source", entry.source.name)
                put("queueId", entry.queueId ?: JSONObject.NULL)
                put("machineStableId", entry.machineStableId ?: JSONObject.NULL)
                put("machineName", entry.machineName ?: JSONObject.NULL)
                put("publicEventType", entry.publicEventType?.name ?: JSONObject.NULL)
                put(
                    "notificationCategories",
                    JSONArray().apply {
                        entry.notificationCategories
                            .sortedBy(PublicQueueNotificationCategory::name)
                            .forEach { put(it.name) }
                    }
                )
                put(
                    "affectedRegistrationKeys",
                    JSONArray().apply {
                        entry.affectedRegistrationKeys.forEach(::put)
                    }
                )
                put(
                    "affectedPlayerContacts",
                    JSONArray().apply {
                        entry.affectedPlayerContacts.forEach { contact ->
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

private val MACHINE_STABLE_ID_PATTERN = Regex("^[0-9a-f]{32}$")
