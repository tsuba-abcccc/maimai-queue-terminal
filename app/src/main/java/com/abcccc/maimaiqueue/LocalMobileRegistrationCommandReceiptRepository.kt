package com.abcccc.maimaiqueue

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

internal interface MobileRegistrationCommandReceiptRepository {
    suspend fun getAppliedCommandIds(): Set<String>
    suspend fun recordApplied(commandId: String): Boolean
}

internal class LocalMobileRegistrationCommandReceiptRepository(context: Context) :
    MobileRegistrationCommandReceiptRepository {
    private val writeMutex = Mutex()
    private val preferences = context.applicationContext.getSharedPreferences(
        "mobile_registration_command_receipts",
        Context.MODE_PRIVATE
    )

    override suspend fun getAppliedCommandIds(): Set<String> = withContext(Dispatchers.IO) {
        writeMutex.withLock { loadCommandIds().toSet() }
    }

    override suspend fun recordApplied(commandId: String): Boolean =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                if (!isUuid(commandId)) return@withLock false
                val updated = appendRecentCommandId(loadCommandIds(), commandId)
                preferences.edit()
                    .putString(KEY_COMMAND_IDS, JSONArray(updated).toString())
                    .commit()
            }
        }

    private fun loadCommandIds(): List<String> {
        val serialized = preferences.getString(KEY_COMMAND_IDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(serialized)
            buildList {
                repeat(array.length()) { index ->
                    array.optString(index)
                        .takeIf(::isUuid)
                        ?.let(::add)
                }
            }.takeLast(MAX_RECENT_COMMAND_IDS)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_COMMAND_IDS = "applied_command_ids"
    }
}

internal fun appendRecentCommandId(
    existing: List<String>,
    commandId: String,
    maximumSize: Int = MAX_RECENT_COMMAND_IDS
): List<String> = (existing.filterNot { it == commandId } + commandId)
    .takeLast(maximumSize.coerceAtLeast(1))

private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

private const val MAX_RECENT_COMMAND_IDS = 500
