package com.abcccc.maimaiqueue

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class TerminalCommandReceipt(
    val commandId: String,
    val applied: Boolean,
    val detail: String,
    val resultRegistrationId: String? = null
)

internal interface TerminalCommandReceiptRepository {
    suspend fun getReceipts(): Map<String, TerminalCommandReceipt>
    suspend fun record(receipt: TerminalCommandReceipt): Boolean
}

internal class LocalTerminalCommandReceiptRepository(context: Context) :
    TerminalCommandReceiptRepository {
    private val writeMutex = Mutex()
    private val preferences = context.applicationContext.getSharedPreferences(
        "mobile_registration_command_receipts",
        Context.MODE_PRIVATE
    )

    override suspend fun getReceipts(): Map<String, TerminalCommandReceipt> =
        withContext(Dispatchers.IO) {
            writeMutex.withLock { loadReceipts().associateBy(TerminalCommandReceipt::commandId) }
        }

    override suspend fun record(receipt: TerminalCommandReceipt): Boolean =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                if (!isUuid(receipt.commandId)) return@withLock false
                val updated = appendRecentCommandReceipt(loadReceipts(), receipt)
                val serializedReceipts = JSONArray().apply {
                    updated.forEach { item ->
                        put(JSONObject().apply {
                            put("commandId", item.commandId)
                            put("applied", item.applied)
                            put("detail", item.detail)
                            put(
                                "resultRegistrationId",
                                item.resultRegistrationId ?: JSONObject.NULL
                            )
                        })
                    }
                }
                val appliedCommandIds = JSONArray(
                    updated.filter(TerminalCommandReceipt::applied)
                        .map(TerminalCommandReceipt::commandId)
                )
                preferences.edit()
                    .putString(KEY_COMMAND_RECEIPTS, serializedReceipts.toString())
                    // Keep this list so an older app still recognizes completed join commands.
                    .putString(KEY_COMMAND_IDS, appliedCommandIds.toString())
                    .commit()
            }
        }

    private fun loadReceipts(): List<TerminalCommandReceipt> {
        val persisted = preferences.getString(KEY_COMMAND_RECEIPTS, null)?.let { serialized ->
            runCatching {
                val array = JSONArray(serialized)
                buildList {
                    repeat(array.length()) { index ->
                        val item = array.optJSONObject(index) ?: return@repeat
                        val commandId = item.optString("commandId")
                        if (!isUuid(commandId) || !item.has("applied")) return@repeat
                        add(
                            TerminalCommandReceipt(
                                commandId = commandId,
                                applied = item.optBoolean("applied"),
                                detail = item.optString("detail"),
                                resultRegistrationId = if (item.isNull("resultRegistrationId")) {
                                    null
                                } else {
                                    item.optString("resultRegistrationId")
                                        .takeIf(String::isNotBlank)
                                }
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        val persistedIds = persisted.mapTo(mutableSetOf(), TerminalCommandReceipt::commandId)
        val migrated = loadLegacyAppliedCommandIds()
            .filterNot { it in persistedIds }
            .map { commandId ->
                TerminalCommandReceipt(
                    commandId = commandId,
                    applied = true,
                    detail = "这条命令已经由现场终端执行。"
                )
            }
        return (migrated + persisted).takeLast(MAX_RECENT_COMMAND_IDS)
    }

    private fun loadLegacyAppliedCommandIds(): List<String> {
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
        const val KEY_COMMAND_RECEIPTS = "terminal_command_receipts"
    }
}

internal fun appendRecentCommandReceipt(
    existing: List<TerminalCommandReceipt>,
    receipt: TerminalCommandReceipt,
    maximumSize: Int = MAX_RECENT_COMMAND_IDS
): List<TerminalCommandReceipt> =
    (existing.filterNot { it.commandId == receipt.commandId } + receipt)
        .takeLast(maximumSize.coerceAtLeast(1))

internal fun appendRecentCommandId(
    existing: List<String>,
    commandId: String,
    maximumSize: Int = MAX_RECENT_COMMAND_IDS
): List<String> = (existing.filterNot { it == commandId } + commandId)
    .takeLast(maximumSize.coerceAtLeast(1))

private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

private const val MAX_RECENT_COMMAND_IDS = 500
