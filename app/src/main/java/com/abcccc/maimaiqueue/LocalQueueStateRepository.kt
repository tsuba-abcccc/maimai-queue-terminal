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

data class PersistedMachineState(
    val queue: MachineQueue = MachineQueue(),
    val status: MachineStatus = MachineStatus()
)

data class PersistedQueueState(
    val queueId: String,
    val revision: Long,
    val machines: Map<MachineId, PersistedMachineState>,
    val registrationOpen: Boolean,
    val nextRegistrationKey: Int,
    val savedAtMillis: Long,
    val terminalCommandReceipts: List<TerminalCommandReceipt> = emptyList()
) {
    constructor(
        queueId: String,
        revision: Long,
        machineA: MachineQueue,
        machineB: MachineQueue,
        machineAStatus: MachineStatus,
        machineBStatus: MachineStatus,
        registrationOpen: Boolean,
        nextRegistrationKey: Int,
        savedAtMillis: Long,
        terminalCommandReceipts: List<TerminalCommandReceipt> = emptyList()
    ) : this(
        queueId = queueId,
        revision = revision,
        machines = linkedMapOf(
            MachineId.A to PersistedMachineState(machineA, machineAStatus),
            MachineId.B to PersistedMachineState(machineB, machineBStatus)
        ),
        registrationOpen = registrationOpen,
        nextRegistrationKey = nextRegistrationKey,
        savedAtMillis = savedAtMillis,
        terminalCommandReceipts = terminalCommandReceipts
    )

    val configuredMachineIds: List<MachineId>
        get() = MachineId.entries.filter(machines::containsKey)

    val machineA: MachineQueue
        get() = machine(MachineId.A).queue

    val machineB: MachineQueue
        get() = machine(MachineId.B).queue

    val machineAStatus: MachineStatus
        get() = machine(MachineId.A).status

    val machineBStatus: MachineStatus
        get() = machine(MachineId.B).status

    fun machine(machineId: MachineId): PersistedMachineState =
        machines[machineId] ?: PersistedMachineState()

    val totalRegistrationCount: Int
        get() = machines.values.sumOf { it.queue.registrationCount }

    val hasMeaningfulState: Boolean
        get() = totalRegistrationCount > 0 ||
            machines.values.any { !it.status.isOperational } ||
            !registrationOpen

    val safeNextRegistrationKey: Int
        get() = maxOf(
            nextRegistrationKey,
            machines.values.flatMap { it.queue.allRegistrations }
                .maxOfOrNull { it.key + 1 } ?: 1
        )
}

internal fun machineCountNeededToRestore(
    configuredMachineCount: Int,
    savedState: PersistedQueueState
): Int {
    val highestMeaningfulMachineIndex = savedState.machines.entries
        .filter { (_, state) ->
            state.queue.registrationCount > 0 || !state.status.isOperational
        }
        .maxOfOrNull { (machineId, _) -> machineId.ordinal }
        ?: -1
    return maxOf(configuredMachineCount, highestMeaningfulMachineIndex + 1)
        .coerceIn(1, MachineId.entries.size)
}

class LocalQueueStateRepository(context: Context) : QueueStateRepository {
    private data class StoredQueueState(
        val serialized: String,
        val state: PersistedQueueState
    )

    private val saveMutex = Mutex()
    private val preferences = context.applicationContext.getSharedPreferences(
        "queue_state",
        Context.MODE_PRIVATE
    )

    override suspend fun getState(): PersistedQueueState? = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            newestStoredQueueState(
                readStoredState(KEY_STATE),
                readStoredState(KEY_BACKUP_STATE)
            )?.state
        }
    }

    override suspend fun saveState(state: PersistedQueueState): QueueStateSaveResult =
        withContext(Dispatchers.IO) {
        saveMutex.withLock {
            if (!isValidPersistedMachineStates(state.machines)) {
                return@withLock QueueStateSaveResult.FAILED
            }
            val primaryState = readStoredState(KEY_STATE)
            val backupState = readStoredState(KEY_BACKUP_STATE)
            val persistedState = newestStoredQueueState(primaryState, backupState)
            if (!shouldPersistQueueState(state, persistedState?.state)) {
                return@withLock QueueStateSaveResult.SUPERSEDED
            }
            val serialized = Codec.encodeState(state).toString()
            val previousValidSnapshot = persistedState?.serialized ?: serialized
            val saved = runCatching {
                preferences.edit()
                    .putString(KEY_BACKUP_STATE, previousValidSnapshot)
                    .putString(KEY_STATE, serialized)
                    .commit()
            }.getOrDefault(false)
            if (saved) QueueStateSaveResult.SAVED else QueueStateSaveResult.FAILED
        }
    }

    private fun readStoredState(key: String): StoredQueueState? {
        val serialized = preferences.getString(key, null) ?: return null
        val state = Codec.decodeState(serialized) ?: return null
        return StoredQueueState(serialized, state)
    }

    private fun newestStoredQueueState(
        primary: StoredQueueState?,
        backup: StoredQueueState?
    ): StoredQueueState? = when (newestPersistedQueueState(primary?.state, backup?.state)) {
        primary?.state -> primary
        backup?.state -> backup
        else -> null
    }

    internal object Codec {
    internal fun encodeState(state: PersistedQueueState): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("queueId", state.queueId)
        put("revision", state.revision)
        put("savedAtMillis", state.savedAtMillis)
        put("registrationOpen", state.registrationOpen)
        put("nextRegistrationKey", state.nextRegistrationKey)
        put(
            "machines",
            JSONObject().apply {
                state.configuredMachineIds.forEach { machineId ->
                    val machine = state.machine(machineId)
                    put(machineId.name, JSONObject().apply {
                        put("queue", encodeQueue(machine.queue))
                        put("status", encodeStatus(machine.status))
                    })
                }
            }
        )
        put(
            "terminalCommandReceipts",
            JSONArray().apply {
                state.terminalCommandReceipts.forEach { receipt ->
                    put(JSONObject().apply {
                        put("commandId", receipt.commandId)
                        put("applied", receipt.applied)
                        put("detail", receipt.detail)
                        put(
                            "resultRegistrationId",
                            receipt.resultRegistrationId ?: JSONObject.NULL
                        )
                    })
                }
            }
        )
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
                        "onSiteCheckInStartedAtMillis",
                        registration.onSiteCheckInStartedAtMillis ?: JSONObject.NULL
                    )
                    put(
                        "originatingCommandId",
                        registration.originatingCommandId ?: JSONObject.NULL
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

    internal fun decodeState(serialized: String): PersistedQueueState? = runCatching {
        val root = JSONObject(serialized)
        val schemaVersion = root.optInt("schemaVersion")
        if (schemaVersion !in MIN_SUPPORTED_SCHEMA_VERSION..SCHEMA_VERSION) return@runCatching null
        val machines = if (schemaVersion >= 6) {
            decodeMachines(root.optJSONObject("machines")) ?: return@runCatching null
        } else {
            linkedMapOf(
                MachineId.A to PersistedMachineState(
                    queue = decodeQueue(root.optJSONObject("machineA"))
                        ?: return@runCatching null,
                    status = decodeStatus(root.optJSONObject("machineAStatus"))
                ),
                MachineId.B to PersistedMachineState(
                    queue = decodeQueue(root.optJSONObject("machineB"))
                        ?: return@runCatching null,
                    status = decodeStatus(root.optJSONObject("machineBStatus"))
                )
            )
        }
        val allRegistrations = machines.values.flatMap { it.queue.allRegistrations }
        if (
            machines.values.any { it.queue.registrationCount > MAX_REGISTRATIONS_PER_MACHINE } ||
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
            machines = machines,
            registrationOpen = root.optBoolean("registrationOpen", true),
            nextRegistrationKey = root.optInt("nextRegistrationKey", 1).coerceAtLeast(1),
            savedAtMillis = savedAtMillis,
            terminalCommandReceipts = if (schemaVersion >= 5) {
                decodeTerminalCommandReceipts(root.optJSONArray("terminalCommandReceipts"))
            } else {
                emptyList()
            }
        )
    }.getOrNull()

    private fun decodeMachines(value: JSONObject?): Map<MachineId, PersistedMachineState>? {
        value ?: return null
        val providedNames = buildSet {
            val keys = value.keys()
            while (keys.hasNext()) add(keys.next())
        }
        val configuredIds = MachineId.entries.take(providedNames.size.coerceAtMost(MachineId.entries.size))
        if (
            providedNames.isEmpty() ||
            providedNames.size > MachineId.entries.size ||
            providedNames != configuredIds.map(MachineId::name).toSet()
        ) return null
        return linkedMapOf<MachineId, PersistedMachineState>().apply {
            configuredIds.forEach { machineId ->
                val item = value.optJSONObject(machineId.name) ?: return null
                val queue = decodeQueue(item.optJSONObject("queue")) ?: return null
                put(
                    machineId,
                    PersistedMachineState(
                        queue = queue,
                        status = decodeStatus(item.optJSONObject("status"))
                    )
                )
            }
        }
    }

    private fun decodeTerminalCommandReceipts(value: JSONArray?): List<TerminalCommandReceipt> {
        value ?: return emptyList()
        val receipts = buildList {
            repeat(value.length()) { index ->
                val item = value.optJSONObject(index) ?: return@repeat
                val commandId = item.optString("commandId")
                if (!isValidQueueId(commandId) || !item.has("applied")) return@repeat
                add(
                    TerminalCommandReceipt(
                        commandId = commandId,
                        applied = item.optBoolean("applied"),
                        detail = item.optString("detail"),
                        resultRegistrationId = item.optNullableString("resultRegistrationId")
                    )
                )
            }
        }
        return mergeRecentCommandReceipts(receipts)
    }

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
                onSiteCheckInStartedAtMillis = item
                    .optLongOrNull("onSiteCheckInStartedAtMillis")
                    ?.takeIf { it > 0L },
                originatingCommandId = (
                    item.optNullableString("originatingCommandId")
                        ?: item.optNullableString("onlineRegistrationCommandId")
                    )
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

    private const val MIN_SUPPORTED_SCHEMA_VERSION = 1
    private const val SCHEMA_VERSION = 7
    private const val MAX_REGISTRATIONS_PER_MACHINE = 20
    }

    private companion object {
        const val KEY_STATE = "latest"
        const val KEY_BACKUP_STATE = "previous_valid"
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
        it.copy(
            absenceStatus = QueueAbsenceStatus.NONE,
            temporaryAwaySkippedTurns = 0,
            requiresOnSiteCheckIn = false
        )
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
        val normalizedAbsenceStatus = if (registration.requiresOnSiteCheckIn) {
            QueueAbsenceStatus.NONE
        } else {
            registration.absenceStatus
        }
        registration.copy(
            displayId = displayId,
            temporaryAwaySkippedTurns = if (
                normalizedAbsenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
            ) {
                registration.temporaryAwaySkippedTurns.coerceIn(0, 3)
            } else {
                0
            },
            absenceStatus = normalizedAbsenceStatus,
            createdAtMillis = registration.createdAtMillis.coerceAtLeast(1L),
            onSiteCheckInStartedAtMillis = registration.onSiteCheckInStartedAtMillis
                ?.coerceAtLeast(1L),
            lastPlayedAtMillis = registration.lastPlayedAtMillis?.takeIf { it > 0L },
            noShowCount = noShowCount,
            lastNoShowActionWasDefer = noShowCount > 0 &&
                registration.lastNoShowActionWasDefer,
            fixedPartnerKey = registration.fixedPartnerKey
                ?.takeIf { it > 0 && !registration.requiresOnSiteCheckIn },
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

internal fun newestPersistedQueueState(
    primary: PersistedQueueState?,
    backup: PersistedQueueState?
): PersistedQueueState? = when {
    primary == null -> backup
    backup == null -> primary
    backup.revision > primary.revision -> backup
    backup.revision == primary.revision && backup.savedAtMillis > primary.savedAtMillis -> backup
    else -> primary
}

private fun isValidQueueId(value: String): Boolean = runCatching {
    UUID.fromString(value)
}.isSuccess

internal fun isValidPersistedMachineStates(
    machines: Map<MachineId, PersistedMachineState>
): Boolean {
    val configuredIds = MachineId.entries.take(machines.size.coerceAtMost(MachineId.entries.size))
    if (machines.isEmpty() || machines.size > MachineId.entries.size) return false
    if (machines.keys.toSet() != configuredIds.toSet()) return false
    val registrations = machines.values.flatMap { it.queue.allRegistrations }
    return machines.values.none { it.queue.registrationCount > 20 } &&
        registrations.map(Registration::key).distinct().size == registrations.size
}
