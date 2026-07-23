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

    private fun loadLogs(): List<AuditLogEntry> {
        val serialized = preferences.getString(KEY_LOGS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(serialized)
            buildList {
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@repeat
                    val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@repeat
                    val timestampMillis = item.optLong("timestampMillis", 0L)
                    if (timestampMillis <= 0L) return@repeat
                    add(
                        AuditLogEntry(
                            id = id,
                            timestampMillis = timestampMillis,
                            category = enumValues<AuditLogCategory>().firstOrNull {
                                it.name == item.optString("category")
                            } ?: AuditLogCategory.SYSTEM,
                            title = title,
                            detail = item.optString("detail"),
                            queueId = item.optString("queueId").takeIf { it.isNotBlank() },
                            publicEventType = enumValues<PublicQueueEventType>().firstOrNull {
                                it.name == item.optString("publicEventType")
                            },
                            affectedRegistrationKeys = item.optJSONArray("affectedRegistrationKeys")
                                ?.let { keys ->
                                    buildList {
                                        repeat(keys.length()) { keyIndex ->
                                            keys.optInt(keyIndex).takeIf { it > 0 }?.let(::add)
                                        }
                                    }
                                }
                                .orEmpty()
                        )
                    )
                }
            }.sortedByDescending { it.timestampMillis }.take(MAX_LOGS)
        }.getOrDefault(emptyList())
    }

    private fun saveLogs(logs: List<AuditLogEntry>) {
        val array = JSONArray()
        logs.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("timestampMillis", entry.timestampMillis)
                    put("category", entry.category.name)
                    put("title", entry.title)
                    put("detail", entry.detail)
                    put("queueId", entry.queueId ?: JSONObject.NULL)
                    put("publicEventType", entry.publicEventType?.name ?: JSONObject.NULL)
                    put(
                        "affectedRegistrationKeys",
                        JSONArray().apply {
                            entry.affectedRegistrationKeys.forEach(::put)
                        }
                    )
                }
            )
        }
        preferences.edit().putString(KEY_LOGS, array.toString()).commit()
    }

    private companion object {
        const val KEY_LOGS = "entries"
        const val MAX_LOGS = 1_000
    }
}
