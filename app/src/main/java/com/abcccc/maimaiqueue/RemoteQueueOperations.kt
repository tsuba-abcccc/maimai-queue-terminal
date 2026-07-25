package com.abcccc.maimaiqueue

internal enum class RemoteQueueOperation {
    JOIN_QUEUE,
    DEFER_ONE_ROUND,
    CANCEL_DEFER_ONE_ROUND,
    TEMPORARILY_LEAVE,
    CANCEL_TEMPORARY_LEAVE,
    TRANSFER_MACHINE,
    CHANGE_PLAY_PREFERENCE,
    LEAVE_QUEUE
}

internal enum class RemoteQueueOperationSource {
    QQ_BOT,
    WEBSITE_REMOTE;

    val auditLogSource: AuditLogSource
        get() = when (this) {
            QQ_BOT -> AuditLogSource.QQ_BOT
            WEBSITE_REMOTE -> AuditLogSource.WEBSITE_REMOTE
        }
}

internal data class RemoteQueueOperationCommand(
    override val commandId: String,
    val createdAtMillis: Long,
    val queueId: String,
    val profileId: String,
    val actorQq: String,
    val operation: RemoteQueueOperation,
    val source: RemoteQueueOperationSource,
    val machineId: String? = null,
    val targetMachineId: String? = null,
    val registrationId: String? = null,
    val preference: PlayPreference? = null
) : RemoteTerminalCommand

internal data class RemoteQueueExecutionState(
    val queueId: String,
    val queues: Map<String, MachineQueue>,
    val machineStatuses: Map<String, MachineStatus>,
    val playerProfiles: List<PlayerProfile>,
    val nextRegistrationKey: Int,
    val acceptingNewRegistrations: Boolean,
    val websiteRemoteEnabled: Boolean,
    val oneBotSyncEnabled: Boolean,
    val allowDeferOneRound: Boolean,
    val allowTemporaryLeave: Boolean
)

internal sealed interface RemoteQueueOperationDecision {
    data class Apply(
        val state: RemoteQueueExecutionState,
        val detail: String,
        val changedMachineIds: Set<String>,
        val updatedProfile: PlayerProfile? = null
    ) : RemoteQueueOperationDecision

    data class AlreadyApplied(val detail: String) : RemoteQueueOperationDecision
    data class Reject(val detail: String) : RemoteQueueOperationDecision
}

internal fun decideRemoteQueueOperation(
    command: RemoteQueueOperationCommand,
    state: RemoteQueueExecutionState
): RemoteQueueOperationDecision {
    fun reject(detail: String) = RemoteQueueOperationDecision.Reject(detail)
    fun already(detail: String) = RemoteQueueOperationDecision.AlreadyApplied(detail)

    if (command.queueId != state.queueId) {
        return reject("排队批次已经变化，请重新查询后再操作。")
    }
    when (command.source) {
        RemoteQueueOperationSource.WEBSITE_REMOTE -> if (!state.websiteRemoteEnabled) {
            return reject("现场终端已关闭网站同步，暂不能在线操作。")
        }
        RemoteQueueOperationSource.QQ_BOT -> if (!state.oneBotSyncEnabled) {
            return reject("现场终端已关闭 QQ Bot 联动。")
        }
    }

    val profile = state.playerProfiles.firstOrNull { it.id == command.profileId }
        ?: return reject("玩家资料已不存在。")
    if (profile.normalizedQqNumber() != command.actorQq) {
        return reject("玩家资料绑定的 QQ 已发生变化，请重新查询后再操作。")
    }

    if (command.operation == RemoteQueueOperation.JOIN_QUEUE) {
        val exactRegistration = state.queues.values.asSequence()
            .flatMap { it.allRegistrations.asSequence() }
            .firstOrNull { it.onlineRegistrationCommandId == command.commandId }
        if (exactRegistration != null) {
            return already("线上登记已经加入等待顺序。")
        }
        if (!state.acceptingNewRegistrations) {
            return reject("现场当前没有使用登记排队，暂不能线上加入排队。")
        }
        val machineId = command.machineId ?: return reject("没有指定排队机台。")
        val queue = state.queues[machineId] ?: return reject("所选机台不存在。")
        if (state.machineStatuses[machineId]?.isOperational != true) {
            return reject("机台 $machineId 已停止使用，暂不能加入。")
        }
        if (queue.registrationCount >= MAX_REGISTRATIONS_PER_MACHINE) {
            return reject("机台 $machineId 的登记已满，请选择其他机台。")
        }
        val alreadyRegistered = state.queues.values.any { currentQueue ->
            currentQueue.allRegistrations.any { registration ->
                registration.playerProfileId == profile.id ||
                    registration.displayId.equals(profile.nickname, ignoreCase = true)
            }
        }
        if (alreadyRegistered) {
            return reject("你已经有一份正在排队的登记，不能重复加入。")
        }
        val resolvedPreference = when (profile.defaultPreference) {
            ProfilePlayPreference.ASK_EVERY_TIME -> command.preference
                ?: return reject("请选择本次游玩偏好。")
            ProfilePlayPreference.SOLO -> PlayPreference.SOLO
            ProfilePlayPreference.OPEN_TO_JOIN -> PlayPreference.OPEN_TO_JOIN
        }
        if (
            profile.defaultPreference != ProfilePlayPreference.ASK_EVERY_TIME &&
            command.preference != null && command.preference != resolvedPreference
        ) {
            return reject("玩家资料的默认游玩偏好已经变化，请重新确认后再加入。")
        }
        val registration = Registration(
            key = state.nextRegistrationKey,
            displayId = profile.nickname,
            preference = resolvedPreference,
            isTemporary = false,
            createdAtMillis = command.createdAtMillis,
            gender = profile.gender,
            playerProfileId = profile.id,
            requiresOnSiteCheckIn = true,
            onlineRegistrationCommandId = command.commandId
        )
        // A remote join may arrive while the playing position was deliberately left empty.
        // It must only append the pending registration and never advance another group.
        val updatedQueue = queue.receiveAtWaitingTail(listOf(registration))
        if (updatedQueue.registrationCount != queue.registrationCount + 1) {
            return reject("终端未能建立线上登记，请重新查询后再试。")
        }
        val usedProfile = if ((profile.lastUsedAtMillis ?: Long.MIN_VALUE) < command.createdAtMillis) {
            profile.recordUsage(
                atMillis = maxOf(command.createdAtMillis, profile.updatedAtMillis + 1L)
            )
        } else {
            null
        }
        return RemoteQueueOperationDecision.Apply(
            state = state.copy(
                queues = state.queues + (machineId to updatedQueue),
                nextRegistrationKey = state.nextRegistrationKey + 1
            ),
            detail = "线上登记已加入机台 $machineId 的等待顺序，等待现场签到。",
            changedMachineIds = setOf(machineId),
            updatedProfile = usedProfile
        )
    }

    val registrationId = command.registrationId
        ?: return reject("命令缺少登记编号，请重新操作。")
    val located = state.queues.asSequence().mapNotNull { (machineId, queue) ->
        queue.allRegistrations.firstOrNull {
            publicRegistrationId(state.queueId, it.key) == registrationId
        }?.let { Triple(machineId, queue, it) }
    }.firstOrNull()
    if (located == null) {
        return if (command.operation == RemoteQueueOperation.LEAVE_QUEUE) {
            already("这份登记已经离开队列。")
        } else {
            reject("这份登记已经不在当前队列中，请重新查询。")
        }
    }
    val (actualMachineId, queue, registration) = located
    if (registration.playerProfileId != profile.id) {
        return reject("这份登记已不再关联当前玩家资料。")
    }
    if (registration.requiresOnSiteCheckIn && command.operation != RemoteQueueOperation.LEAVE_QUEUE) {
        return reject("线上登记完成现场签到后，才能进行这项操作。")
    }

    if (command.operation == RemoteQueueOperation.TRANSFER_MACHINE) {
        val targetMachineId = command.targetMachineId
            ?: return reject("没有指定要转入的机台。")
        if (actualMachineId == targetMachineId) {
            return already("登记已经位于机台 $targetMachineId。")
        }
        if (command.machineId != actualMachineId) {
            return reject("登记所在机台已经变化，请重新查询后再操作。")
        }
        val targetQueue = state.queues[targetMachineId]
            ?: return reject("要转入的机台不存在。")
        if (state.machineStatuses[actualMachineId]?.isOperational != true) {
            return reject("当前机台已停止使用，暂不能切换机台。")
        }
        if (state.machineStatuses[targetMachineId]?.isOperational != true) {
            return reject("机台 $targetMachineId 已停止使用，暂不能转入。")
        }
        if (queue.playing.any { it.key == registration.key }) {
            return reject("处于游玩位置的登记暂不能切换机台。")
        }
        if (targetQueue.registrationCount >= MAX_REGISTRATIONS_PER_MACHINE) {
            return reject("机台 $targetMachineId 的登记已满，暂不能转入。")
        }
        val updatedSource = queue.remove(registration.key)
        val updatedTarget = targetQueue.receiveAtWaitingTail(listOf(registration))
        if (
            updatedSource == queue ||
            updatedTarget.registrationCount != targetQueue.registrationCount + 1
        ) {
            return reject("终端未能切换机台，请重新查询后再试。")
        }
        return RemoteQueueOperationDecision.Apply(
            state = state.copy(
                queues = state.queues +
                    (actualMachineId to updatedSource) +
                    (targetMachineId to updatedTarget)
            ),
            detail = "登记已转至机台 $targetMachineId 的等待顺序末端。",
            changedMachineIds = setOf(actualMachineId, targetMachineId)
        )
    }

    if (command.machineId != actualMachineId) {
        return reject("登记所在机台已经变化，请重新查询后再操作。")
    }

    val updatedQueue = when (command.operation) {
        RemoteQueueOperation.DEFER_ONE_ROUND -> {
            when {
                !state.allowDeferOneRound -> return reject("系统规则不允许暂缓一轮。")
                registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND ->
                    return already("这份登记已经暂缓一轮。")
                registration.absenceStatus != QueueAbsenceStatus.NONE ->
                    return reject("请先取消当前的暂缓或暂时离开状态。")
            }
            queue.deferOneRound(registration.key)
        }
        RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND -> {
            when (registration.absenceStatus) {
                QueueAbsenceStatus.DEFER_ONE_ROUND -> queue.cancelDeferOneRound(registration.key)
                QueueAbsenceStatus.NONE -> return already("这份登记已经取消暂缓一轮。")
                QueueAbsenceStatus.TEMPORARILY_AWAY ->
                    return reject("这份登记当前处于暂时离开状态。")
            }
        }
        RemoteQueueOperation.TEMPORARILY_LEAVE -> {
            when {
                !state.allowTemporaryLeave -> return reject("系统规则不允许暂时离开。")
                registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY ->
                    return already("这份登记已经处于暂时离开状态。")
                registration.absenceStatus != QueueAbsenceStatus.NONE ->
                    return reject("请先取消当前的暂缓或暂时离开状态。")
            }
            queue.temporarilyLeave(registration.key)
        }
        RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE -> {
            when (registration.absenceStatus) {
                QueueAbsenceStatus.TEMPORARILY_AWAY -> queue.cancelTemporaryLeave(registration.key)
                QueueAbsenceStatus.NONE -> return already("这份登记已经取消暂时离开。")
                QueueAbsenceStatus.DEFER_ONE_ROUND ->
                    return reject("这份登记当前处于暂缓一轮状态。")
            }
        }
        RemoteQueueOperation.CHANGE_PLAY_PREFERENCE -> {
            val preference = command.preference ?: return reject("请选择本次游玩偏好。")
            if (registration.preference == preference && registration.fixedPartnerKey == null) {
                return already("这份登记已经使用所选游玩偏好。")
            }
            queue.changePreference(registration.key, preference)
        }
        RemoteQueueOperation.LEAVE_QUEUE -> queue.remove(registration.key)
        RemoteQueueOperation.JOIN_QUEUE,
        RemoteQueueOperation.TRANSFER_MACHINE -> error("operation handled above")
    }
    if (updatedQueue == queue) {
        return reject("队列状态已经变化，这项操作没有执行。")
    }
    val detail = when (command.operation) {
        RemoteQueueOperation.DEFER_ONE_ROUND -> "登记已暂缓一轮。"
        RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND -> "登记已取消暂缓一轮。"
        RemoteQueueOperation.TEMPORARILY_LEAVE -> "登记已设为暂时离开。"
        RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE -> "登记已取消暂时离开。"
        RemoteQueueOperation.CHANGE_PLAY_PREFERENCE -> "本次游玩偏好已更新。"
        RemoteQueueOperation.LEAVE_QUEUE -> "登记已退出排队。"
        else -> "队列操作已完成。"
    }
    return RemoteQueueOperationDecision.Apply(
        state = state.copy(queues = state.queues + (actualMachineId to updatedQueue)),
        detail = detail,
        changedMachineIds = setOf(actualMachineId)
    )
}

private const val MAX_REGISTRATIONS_PER_MACHINE = 20
