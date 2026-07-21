package com.abcccc.maimaiqueue

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface AuditLogRepository {
    suspend fun getLogs(): List<AuditLogEntry>
    suspend fun append(entry: AuditLogEntry)
}

class LocalAuditLogRepository(context: Context) : AuditLogRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        "audit_logs",
        Context.MODE_PRIVATE
    )

    override suspend fun getLogs(): List<AuditLogEntry> = loadLogs()

    override suspend fun append(entry: AuditLogEntry) {
        saveLogs((listOf(entry) + loadLogs().filterNot { it.id == entry.id }).take(MAX_LOGS))
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
                            detail = item.optString("detail")
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
                }
            )
        }
        preferences.edit().putString(KEY_LOGS, array.toString()).apply()
    }

    private companion object {
        const val KEY_LOGS = "entries"
        const val MAX_LOGS = 1_000
    }
}
