package com.abcccc.maimaiqueue

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

interface QueueStateRepository {
    suspend fun getState(): PersistedQueueState?
    suspend fun saveState(state: PersistedQueueState): QueueStateSaveResult
}

enum class QueueStateSaveResult {
    SAVED,
    SUPERSEDED,
    FAILED
}

data class PersistedQueueState(
    val queueId: String,
    val revision: Long,
    val machineA: MachineQueue,
    val machineB: MachineQueue,
    val machineAStatus: MachineStatus,
    val machineBStatus: MachineStatus,
    val registrationOpen: Boolean,
    val nextRegistrationKey: Int,
    val savedAtMillis: Long
) {
    val totalRegistrationCount: Int
        get() = machineA.registrationCount + machineB.registrationCount

    val hasMeaningfulState: Boolean
        get() = totalRegistrationCount > 0 ||
            !machineAStatus.isOperational ||
            !machineBStatus.isOperational ||
            !registrationOpen

    val safeNextRegistrationKey: Int
        get() = maxOf(
            nextRegistrationKey,
            (machineA.allRegistrations + machineB.allRegistrations)
                .maxOfOrNull { it.key + 1 } ?: 1
        )
}

class LocalQueueStateRepository(context: Context) : QueueStateRepository {
    private val saveMutex = Mutex()
    private val preferences = context.applicationContext.getSharedPreferences(
        "queue_state",
        Context.MODE_PRIVATE
    )

    override suspend fun getState(): PersistedQueueState? = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            preferences.getString(KEY_STATE, null)?.let(::decodeState)
        }
    }

    override suspend fun saveState(state: PersistedQueueState): QueueStateSaveResult =
        withContext(Dispatchers.IO) {
        saveMutex.withLock {
            val persistedState = preferences.getString(KEY_STATE, null)?.let(::decodeState)
            if (!shouldPersistQueueState(state, persistedState)) {
                return@withLock QueueStateSaveResult.SUPERSEDED
            }
            val saved = runCatching {
                preferences.edit()
                    .putString(KEY_STATE, encodeState(state).toString())
                    .commit()
            }.getOrDefault(false)
            if (saved) QueueStateSaveResult.SAVED else QueueStateSaveResult.FAILED
        }
    }

    private fun encodeState(state: PersistedQueueState): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("queueId", state.queueId)
        put("revision", state.revision)
        put("savedAtMillis", state.savedAtMillis)
        put("registrationOpen", state.registrationOpen)
        put("nextRegistrationKey", state.nextRegistrationKey)
        put("machineA", encodeQueue(state.machineA))
        put("machineB", encodeQueue(state.machineB))
        put("machineAStatus", encodeStatus(state.machineAStatus))
        put("machineBStatus", encodeStatus(state.machineBStatus))
    }

    private fun encodeQueue(queue: MachineQueue): JSONObject = JSONObject().apply {
        put("playing", encodeRegistrations(queue.playing))
        put("waiting", encodeRegistrations(queue.waiting))
        put("playingStartedAtMillis", queue.playingStartedAtMillis ?: JSONObject.NULL)
    }

    private fun encodeRegistrations(registrations: List<Registration>): JSONArray = JSONArray().apply {
        registrations.forEach { registration ->
            put(
                JSONObject().apply {
                    put("key", registration.key)
                    put("displayId", registration.displayId)
                    put("preference", registration.preference.name)
                    put("absenceStatus", registration.absenceStatus.name)
                    put("temporaryAwaySkippedTurns", registration.temporaryAwaySkippedTurns)
                    put("isTemporary", registration.isTemporary)
                    put("createdAtMillis", registration.createdAtMillis)
                    put("lastPlayedAtMillis", registration.lastPlayedAtMillis ?: JSONObject.NULL)
                    put("noShowCount", registration.noShowCount)
                    put("lastNoShowActionWasDefer", registration.lastNoShowActionWasDefer)
                    put("fixedPartnerKey", registration.fixedPartnerKey ?: JSONObject.NULL)
                    put("gender", registration.gender?.name ?: JSONObject.NULL)
                    put("playerProfileId", registration.playerProfileId ?: JSONObject.NULL)
                    put("requiresOnSiteCheckIn", registration.requiresOnSiteCheckIn)
                    put(
                        "onlineRegistrationCommandId",
                        registration.onlineRegistrationCommandId ?: JSONObject.NULL
                    )
                }
            )
        }
    }

    private fun encodeStatus(status: MachineStatus): JSONObject = JSONObject().apply {
        put("stopReason", status.stopReason?.name ?: JSONObject.NULL)
        put("stopReasonDetail", status.stopReasonDetail ?: JSONObject.NULL)
        put("stoppedAtMillis", status.stoppedAtMillis ?: JSONObject.NULL)
    }

    private fun decodeState(serialized: String): PersistedQueueState? = runCatching {
        val root = JSONObject(serialized)
        val schemaVersion = root.optInt("schemaVersion")
        if (schemaVersion !in MIN_SUPPORTED_SCHEMA_VERSION..SCHEMA_VERSION) return@runCatching null
        val machineA = decodeQueue(root.optJSONObject("machineA")) ?: return@runCatching null
        val machineB = decodeQueue(root.optJSONObject("machineB")) ?: return@runCatching null
        val allRegistrations = machineA.allRegistrations + machineB.allRegistrations
        if (
            machineA.registrationCount > MAX_REGISTRATIONS_PER_MACHINE ||
            machineB.registrationCount > MAX_REGISTRATIONS_PER_MACHINE ||
            allRegistrations.map { it.key }.distinct().size != allRegistrations.size
        ) return@runCatching null
        val savedAtMillis = root.optLong("savedAtMillis").takeIf { it > 0L }
            ?: return@runCatching null
        val queueId = if (schemaVersion >= 2) {
            root.optString("queueId").takeIf(::isValidQueueId) ?: return@runCatching null
        } else {
            newQueueId()
        }
        PersistedQueueState(
            queueId = queueId,
            revision = if (schemaVersion >= 2) {
                root.optLong("revision").coerceAtLeast(0L)
            } else {
                0L
            },
            machineA = machineA,
            machineB = machineB,
            machineAStatus = decodeStatus(root.optJSONObject("machineAStatus")),
            machineBStatus = decodeStatus(root.optJSONObject("machineBStatus")),
            registrationOpen = root.optBoolean("registrationOpen", true),
            nextRegistrationKey = root.optInt("nextRegistrationKey", 1).coerceAtLeast(1),
            savedAtMillis = savedAtMillis
        )
    }.getOrNull()

    private fun decodeQueue(value: JSONObject?): MachineQueue? {
        value ?: return null
        val playing = decodeRegistrations(value.optJSONArray("playing")) ?: return null
        val waiting = decodeRegistrations(value.optJSONArray("waiting")) ?: return null
        if (playing.size > 2) return null
        return normalizeRestoredMachineQueue(MachineQueue(
            playing = playing,
            waiting = waiting,
            playingStartedAtMillis = value.optLongOrNull("playingStartedAtMillis")
        ))
    }

    private fun decodeRegistrations(value: JSONArray?): List<Registration>? {
        value ?: return null
        val registrations = mutableListOf<Registration>()
        repeat(value.length()) { index ->
            val item = value.optJSONObject(index) ?: return null
            val key = item.optInt("key").takeIf { it > 0 } ?: return null
            val displayId = item.optString("displayId").trim().takeIf { it.isNotEmpty() }
                ?: return null
            val preference = enumValues<PlayPreference>().firstOrNull {
                it.name == item.optString("preference")
            } ?: return null
            val absenceStatus = if (item.has("absenceStatus")) {
                enumValues<QueueAbsenceStatus>().firstOrNull {
                    it.name == item.optString("absenceStatus")
                } ?: return null
            } else if (item.optBoolean("deferredOnce")) {
                QueueAbsenceStatus.DEFER_ONE_ROUND
            } else {
                QueueAbsenceStatus.NONE
            }
            val noShowCount = item.optInt("noShowCount").coerceIn(0, MAX_SYNCED_NO_SHOW_COUNT)
            registrations += Registration(
                key = key,
                displayId = displayId,
                preference = preference,
                absenceStatus = absenceStatus,
                temporaryAwaySkippedTurns = if (
                    absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
                ) {
                    item.optInt("temporaryAwaySkippedTurns").coerceIn(0, 3)
                } else {
                    0
                },
                isTemporary = item.optBoolean("isTemporary", true),
                createdAtMillis = item.optLong("createdAtMillis").takeIf { it > 0L }
                    ?: return null,
                lastPlayedAtMillis = item.optLongOrNull("lastPlayedAtMillis")
                    ?.takeIf { it > 0L },
                noShowCount = noShowCount,
                lastNoShowActionWasDefer = noShowCount > 0 &&
                    item.optBoolean("lastNoShowActionWasDefer"),
                fixedPartnerKey = item.optIntOrNull("fixedPartnerKey")?.takeIf { it > 0 },
                gender = item.optNullableString("gender")?.let { name ->
                    enumValues<PlayerGender>().firstOrNull { it.name == name }
                },
                playerProfileId = item.optNullableString("playerProfileId"),
                requiresOnSiteCheckIn = item.optBoolean("requiresOnSiteCheckIn", false),
                onlineRegistrationCommandId = item
                    .optNullableString("onlineRegistrationCommandId")
                    ?.takeIf { value -> runCatching { java.util.UUID.fromString(value) }.isSuccess }
            )
        }
        return registrations
    }

    private fun decodeStatus(value: JSONObject?): MachineStatus {
        value ?: return MachineStatus()
        val reason = value.optNullableString("stopReason")?.let { name ->
            enumValues<MachineStopReason>().firstOrNull { it.name == name }
        } ?: return MachineStatus()
        return normalizeRestoredMachineStatus(MachineStatus(
            stopReason = reason,
            stopReasonDetail = normalizeMachineStopReasonDetail(
                reason,
                value.optNullableString("stopReasonDetail")
            ),
            stoppedAtMillis = value.optLongOrNull("stoppedAtMillis")
        ))
    }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name)

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else optInt(name)

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private companion object {
        const val KEY_STATE = "latest"
        const val MIN_SUPPORTED_SCHEMA_VERSION = 1
        const val SCHEMA_VERSION = 4
        const val MAX_REGISTRATIONS_PER_MACHINE = 20
    }
}

internal fun normalizeRestoredMachineStatus(status: MachineStatus): MachineStatus =
    if (status.isOperational) {
        MachineStatus()
    } else {
        status.copy(
            stopReasonDetail = normalizeMachineStopReasonDetail(
                status.stopReason,
                status.stopReasonDetail
            ),
            stoppedAtMillis = status.stoppedAtMillis?.takeIf { it > 0L }
        )
    }

internal fun normalizeRestoredMachineQueue(queue: MachineQueue): MachineQueue {
    val playing = normalizeRestoredRegistrations(queue.playing).map {
        it.copy(requiresOnSiteCheckIn = false)
    }
    val waiting = normalizeRestoredRegistrations(queue.waiting)
    return queue.copy(
        playing = playing,
        waiting = waiting,
        playingStartedAtMillis = queue.playingStartedAtMillis
            ?.takeIf { it > 0L && playing.isNotEmpty() }
    )
}

private fun normalizeRestoredRegistrations(
    registrations: List<Registration>
): List<Registration> {
    val individuallyNormalized = registrations.map { registration ->
        val displayId = registration.displayId.trim().takeCodePoints(MAX_SYNCED_NICKNAME_CODE_POINTS)
        val noShowCount = registration.noShowCount.coerceIn(0, MAX_SYNCED_NO_SHOW_COUNT)
        registration.copy(
            displayId = displayId,
            temporaryAwaySkippedTurns = if (
                registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
            ) {
                registration.temporaryAwaySkippedTurns.coerceIn(0, 3)
            } else {
                0
            },
            createdAtMillis = registration.createdAtMillis.coerceAtLeast(1L),
            lastPlayedAtMillis = registration.lastPlayedAtMillis?.takeIf { it > 0L },
            noShowCount = noShowCount,
            lastNoShowActionWasDefer = noShowCount > 0 &&
                registration.lastNoShowActionWasDefer,
            fixedPartnerKey = registration.fixedPartnerKey?.takeIf { it > 0 },
            playerProfileId = registration.playerProfileId?.trim()?.takeIf { it.isNotEmpty() }
        )
    }
    val paired = sanitizeFriendPairs(individuallyNormalized)
    val registrationsByKey = paired.associateBy(Registration::key)
    return paired.map { registration ->
        val partner = registration.fixedPartnerKey?.let(registrationsByKey::get)
            ?.takeIf { it.fixedPartnerKey == registration.key }
            ?: return@map registration
        val pairAbsenceStatus = when {
            registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY ||
                partner.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY ->
                QueueAbsenceStatus.TEMPORARILY_AWAY
            registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND ||
                partner.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND ->
                QueueAbsenceStatus.DEFER_ONE_ROUND
            else -> QueueAbsenceStatus.NONE
        }
        registration.copy(
            preference = PlayPreference.OPEN_TO_JOIN,
            absenceStatus = pairAbsenceStatus,
            temporaryAwaySkippedTurns = if (
                pairAbsenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
            ) {
                maxOf(
                    registration.temporaryAwaySkippedTurns,
                    partner.temporaryAwaySkippedTurns
                )
            } else {
                0
            }
        )
    }
}

private fun String.takeCodePoints(maximum: Int): String =
    if (codePointCount(0, length) <= maximum) {
        this
    } else {
        substring(0, offsetByCodePoints(0, maximum))
    }

private const val MAX_SYNCED_NICKNAME_CODE_POINTS = 18
private const val MAX_SYNCED_NO_SHOW_COUNT = 10_000

internal fun newQueueId(): String = UUID.randomUUID().toString()

internal fun shouldPersistQueueState(
    candidate: PersistedQueueState,
    persisted: PersistedQueueState?
): Boolean = persisted == null || candidate.revision > persisted.revision

private fun isValidQueueId(value: String): Boolean = runCatching {
    UUID.fromString(value)
}.isSuccess
