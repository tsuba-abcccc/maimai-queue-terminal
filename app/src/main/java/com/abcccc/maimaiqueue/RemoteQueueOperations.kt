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

internal enum class RemoteRegistrationPosition {
    WAITING,
    PLAYING
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
    val preference: PlayPreference? = null,
    val expectedPosition: RemoteRegistrationPosition? = null,
    val expectedFixedPairId: String? = null,
    val expectedAbsenceStatus: QueueAbsenceStatus? = null,
    val expectedTemporaryAwaySkippedTurns: Int? = null,
    val expectedPendingCheckIn: Boolean? = null
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
    val allowOnlineRegistration: Boolean,
    val allowDeferOneRound: Boolean,
    val allowTemporaryLeave: Boolean
)

internal fun RemoteQueueExecutionState.executeQueueAction(
    action: QueueAction,
    origin: QueueActionOrigin,
    atMillis: Long
): QueueActionExecution = QueueEngine.execute(
    state = QueueEngineState(queues),
    action = action,
    context = QueueActionContext(
        atMillis = atMillis,
        origin = origin,
        policy = QueueEnginePolicy(
            registrationOpen = acceptingNewRegistrations,
            allowOnlineRegistration = allowOnlineRegistration,
            allowDeferOneRound = allowDeferOneRound,
            allowTemporaryLeave = allowTemporaryLeave,
            machineStatuses = machineStatuses,
            maxRegistrationsPerMachine = MAX_REGISTRATIONS_PER_MACHINE,
            requireOperationalForPlayerActions = true
        )
    )
)

private val RemoteQueueOperationSource.queueActionOrigin: QueueActionOrigin
    get() = when (this) {
        RemoteQueueOperationSource.QQ_BOT -> QueueActionOrigin.QQ_BOT
        RemoteQueueOperationSource.WEBSITE_REMOTE -> QueueActionOrigin.WEBSITE
    }

internal sealed interface RemoteQueueOperationDecision {
    data class Apply(
        val state: RemoteQueueExecutionState,
        val detail: String,
        val changedMachineIds: Set<String>,
        val action: QueueAction,
        val updatedProfile: PlayerProfile? = null
    ) : RemoteQueueOperationDecision

    data class AlreadyApplied(
        val detail: String,
        val updatedProfile: PlayerProfile? = null
    ) : RemoteQueueOperationDecision
    data class Reject(val detail: String) : RemoteQueueOperationDecision
}

internal fun decideRemoteQueueOperation(
    command: RemoteQueueOperationCommand,
    state: RemoteQueueExecutionState,
    appliedAtMillis: Long = System.currentTimeMillis(),
    appliedRegistrationCommandIds: Set<String> = emptySet()
): RemoteQueueOperationDecision {
    fun reject(detail: String) = RemoteQueueOperationDecision.Reject(detail)
    fun already(
        detail: String,
        updatedProfile: PlayerProfile? = null
    ) = RemoteQueueOperationDecision.AlreadyApplied(detail, updatedProfile)

    if (command.queueId != state.queueId) {
        return reject("排队批次已经变化，请重新查询后再操作。")
    }
    if (command.operation == RemoteQueueOperation.JOIN_QUEUE) {
        val exactRegistration = state.queues.values.asSequence()
            .flatMap { it.allRegistrations.asSequence() }
            .firstOrNull { it.originatingCommandId == command.commandId }
        if (exactRegistration != null) {
            val profileToPersist = state.playerProfiles
                .firstOrNull { it.id == exactRegistration.playerProfileId }
                ?.takeIf {
                    (it.lastUsedAtMillis ?: Long.MIN_VALUE) < exactRegistration.createdAtMillis
                }
                ?.let { profile ->
                    profile.recordUsage(
                        atMillis = maxOf(
                            exactRegistration.createdAtMillis,
                            profile.updatedAtMillis + 1L
                        )
                    )
                }
            return already(
                "线上登记已经加入等待顺序。请在创建登记后的 30 分钟内到现场终端完成签到；超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动退出排队。",
                updatedProfile = profileToPersist
            )
        }
    }
    if (command.commandId in appliedRegistrationCommandIds) {
        return already(
            if (command.operation == RemoteQueueOperation.JOIN_QUEUE) {
                "这条线上登记命令已经执行过，相关登记当前已不在队列中。"
            } else {
                "这项排队操作已经由现场终端执行，不会重复处理。"
            }
        )
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
        if (!state.allowOnlineRegistration) {
            return reject("现场规则暂不允许线上登记。")
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
            createdAtMillis = appliedAtMillis,
            gender = profile.gender,
            playerProfileId = profile.id,
            requiresOnSiteCheckIn = true,
            originatingCommandId = command.commandId
        )
        // A remote join may arrive while the playing position was deliberately left empty.
        // It must only append the pending registration and never advance another group.
        val queueAction = QueueAction.AddRegistrations(
            machineId = machineId,
            registrations = listOf(registration),
            placement = RegistrationPlacement.WAITING_TAIL
        )
        val execution = state.executeQueueAction(
            action = queueAction,
            origin = command.source.queueActionOrigin,
            atMillis = appliedAtMillis
        ) as? QueueActionExecution.Applied
        val updatedQueue = execution?.state?.queue(machineId)
        if (updatedQueue == null || updatedQueue.registrationCount != queue.registrationCount + 1) {
            return reject("终端未能建立线上登记，请重新查询后再试。")
        }
        val usedProfile = if ((profile.lastUsedAtMillis ?: Long.MIN_VALUE) < appliedAtMillis) {
            profile.recordUsage(
                atMillis = maxOf(appliedAtMillis, profile.updatedAtMillis + 1L)
            )
        } else {
            null
        }
        return RemoteQueueOperationDecision.Apply(
            state = state.copy(
                queues = execution.state.queues,
                nextRegistrationKey = state.nextRegistrationKey + 1
            ),
            detail = "线上登记已加入机台 $machineId 的等待顺序。请在创建登记后的 30 分钟内到现场终端完成签到；超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动退出排队。",
            changedMachineIds = setOf(machineId),
            action = queueAction,
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
    val affectsFixedGroup = registration.fixedPartnerKey != null
    val fixedPartner = registration.fixedPartnerKey?.let { partnerKey ->
        queue.allRegistrations.firstOrNull { it.key == partnerKey }
    }
    fun registrationSubject(single: String, fixedGroup: String): String =
        if (affectsFixedGroup) fixedGroup else single
    if (registration.playerProfileId != profile.id) {
        return reject("这份登记已不再关联当前玩家资料。")
    }
    val alreadyAppliedDetail = when (command.operation) {
        RemoteQueueOperation.DEFER_ONE_ROUND ->
            if (registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND) {
                registrationSubject(
                    "这份登记已经暂缓一次。",
                    "固定组合的两份登记已经暂缓一次。"
                )
            } else null
        RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND ->
            if (registration.absenceStatus == QueueAbsenceStatus.NONE) {
                registrationSubject(
                    "这份登记已经取消暂缓一次。",
                    "固定组合的两份登记已经取消暂缓一次。"
                )
            } else null
        RemoteQueueOperation.TEMPORARILY_LEAVE ->
            if (registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY) {
                registrationSubject(
                    "这份登记已经处于暂时离开状态。",
                    "固定组合的两份登记已经处于暂时离开状态。"
                )
            } else null
        RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE ->
            if (registration.absenceStatus == QueueAbsenceStatus.NONE) {
                registrationSubject(
                    "这份登记已经取消暂时离开。",
                    "固定组合的两份登记已经取消暂时离开。"
                )
            } else null
        RemoteQueueOperation.TRANSFER_MACHINE ->
            if (command.targetMachineId == actualMachineId) {
                "登记已经位于机台 $actualMachineId。"
            } else null
        RemoteQueueOperation.CHANGE_PLAY_PREFERENCE ->
            if (
                registration.preference == command.preference &&
                registration.fixedPartnerKey == null
            ) {
                "这份登记已经使用所选游玩偏好。"
            } else null
        RemoteQueueOperation.JOIN_QUEUE,
        RemoteQueueOperation.LEAVE_QUEUE -> null
    }
    if (alreadyAppliedDetail != null) return already(alreadyAppliedDetail)

    if (command.expectedPosition != null) {
        val actualPosition = if (queue.playing.any { it.key == registration.key }) {
            RemoteRegistrationPosition.PLAYING
        } else {
            RemoteRegistrationPosition.WAITING
        }
        if (actualPosition != command.expectedPosition) {
            return reject("登记所在位置已经变化，请重新查询并确认后再操作。")
        }
        val actualFixedPairId = registration.fixedPartnerKey?.let { partnerKey ->
            publicPositionId(state.queueId, setOf(registration.key, partnerKey))
        }
        if (actualFixedPairId != command.expectedFixedPairId) {
            return reject("登记的固定组合状态已经变化，请重新查询并确认后再操作。")
        }
        val locksAbsenceState = command.operation !in setOf(
            RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND,
            RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE
        )
        if (
            locksAbsenceState && (
                command.expectedAbsenceStatus != registration.absenceStatus ||
                    command.expectedTemporaryAwaySkippedTurns !=
                    registration.temporaryAwaySkippedTurns ||
                    command.expectedPendingCheckIn != registration.requiresOnSiteCheckIn
                )
        ) {
            return reject("登记状态已经变化，请重新查询并确认后再操作。")
        }
    }
    if (state.machineStatuses[actualMachineId]?.isOperational != true) {
        return reject("机台 $actualMachineId 已停止使用，恢复正常使用后才能操作这份登记。")
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
        val queueAction = QueueAction.TransferRegistrations(
            sourceMachineId = actualMachineId,
            destinationMachineId = targetMachineId,
            registrationKeys = setOf(registration.key)
        )
        val execution = state.executeQueueAction(
            action = queueAction,
            origin = command.source.queueActionOrigin,
            atMillis = appliedAtMillis
        ) as? QueueActionExecution.Applied
        val updatedTarget = execution?.state?.queue(targetMachineId)
        if (execution == null ||
            updatedTarget == null ||
            updatedTarget.registrationCount != targetQueue.registrationCount + 1
        ) {
            return reject("终端未能切换机台，请重新查询后再试。")
        }
        return RemoteQueueOperationDecision.Apply(
            state = state.copy(queues = execution.state.queues),
            detail = buildString {
                append("登记已转至机台 $targetMachineId 的等待顺序末端。")
                if (registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND) {
                    if (fixedPartner != null) {
                        append("转入登记不再暂缓；留在原机台的登记仍会暂缓一次。")
                    } else {
                        append("转入后不再暂缓。")
                    }
                }
                if (registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY) {
                    append(
                        if (fixedPartner != null) {
                            "两份登记的暂时离开状态和已轮空 ${registration.temporaryAwaySkippedTurns} 次均会保留，返回后仍需手动取消。"
                        } else {
                            "暂时离开状态和已轮空 ${registration.temporaryAwaySkippedTurns} 次会保留，返回后仍需手动取消。"
                        }
                    )
                }
                if (fixedPartner != null) {
                    append("与“${fixedPartner.displayId}”的固定组合已解除；两份登记均恢复为允许他人加入，对方保留原位。")
                }
            },
            changedMachineIds = setOf(actualMachineId, targetMachineId),
            action = queueAction
        )
    }

    if (command.machineId != actualMachineId) {
        return reject("登记所在机台已经变化，请重新查询后再操作。")
    }

    val action = when (command.operation) {
        RemoteQueueOperation.DEFER_ONE_ROUND -> {
            when {
                !state.allowDeferOneRound -> return reject("系统规则不允许暂缓一次。")
                registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND ->
                    return already(
                        registrationSubject(
                            "这份登记已经暂缓一次。",
                            "固定组合的两份登记已经暂缓一次。"
                        )
                    )
                registration.absenceStatus != QueueAbsenceStatus.NONE ->
                    return reject("请先取消当前的暂缓一次或暂时离开状态。")
            }
            QueueAction.DeferOneRound(actualMachineId, registration.key)
        }
        RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND -> {
            when (registration.absenceStatus) {
                QueueAbsenceStatus.DEFER_ONE_ROUND -> Unit
                QueueAbsenceStatus.NONE -> return already(
                    registrationSubject(
                        "这份登记已经取消暂缓一次。",
                        "固定组合的两份登记已经取消暂缓一次。"
                    )
                )
                QueueAbsenceStatus.TEMPORARILY_AWAY ->
                    return reject(
                        registrationSubject(
                            "这份登记当前处于暂时离开状态。",
                            "固定组合的两份登记当前处于暂时离开状态。"
                        )
                    )
            }
            QueueAction.CancelDeferOneRound(actualMachineId, registration.key)
        }
        RemoteQueueOperation.TEMPORARILY_LEAVE -> {
            when {
                !state.allowTemporaryLeave -> return reject("系统规则不允许暂时离开。")
                registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY ->
                    return already(
                        registrationSubject(
                            "这份登记已经处于暂时离开状态。",
                            "固定组合的两份登记已经处于暂时离开状态。"
                        )
                    )
                registration.absenceStatus != QueueAbsenceStatus.NONE ->
                    return reject("请先取消当前的暂缓一次或暂时离开状态。")
            }
            QueueAction.TemporarilyLeave(actualMachineId, registration.key)
        }
        RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE -> {
            when (registration.absenceStatus) {
                QueueAbsenceStatus.TEMPORARILY_AWAY -> Unit
                QueueAbsenceStatus.NONE -> return already(
                    registrationSubject(
                        "这份登记已经取消暂时离开。",
                        "固定组合的两份登记已经取消暂时离开。"
                    )
                )
                QueueAbsenceStatus.DEFER_ONE_ROUND ->
                    return reject(
                        registrationSubject(
                            "这份登记已经设置为暂缓一次。",
                            "固定组合的两份登记已经设置为暂缓一次。"
                        )
                    )
            }
            QueueAction.CancelTemporaryLeave(actualMachineId, registration.key)
        }
        RemoteQueueOperation.CHANGE_PLAY_PREFERENCE -> {
            val preference = command.preference ?: return reject("请选择本次游玩偏好。")
            if (registration.preference == preference && registration.fixedPartnerKey == null) {
                return already("这份登记已经使用所选游玩偏好。")
            }
            QueueAction.ChangePreference(actualMachineId, registration.key, preference)
        }
        RemoteQueueOperation.LEAVE_QUEUE -> QueueAction.RemoveRegistrations(
            actualMachineId,
            setOf(registration.key)
        )
        RemoteQueueOperation.JOIN_QUEUE,
        RemoteQueueOperation.TRANSFER_MACHINE -> error("operation handled above")
    }
    val execution = state.executeQueueAction(
        action = action,
        origin = command.source.queueActionOrigin,
        atMillis = appliedAtMillis
    ) as? QueueActionExecution.Applied
    val updatedQueue = execution?.state?.queue(actualMachineId)
    if (execution == null || updatedQueue == null || updatedQueue == queue) {
        return reject("队列状态已经变化，这项操作没有执行。")
    }
    val updatedRegistration = updatedQueue.allRegistrations.firstOrNull {
        it.key == registration.key
    }
    val movedFromPlaying = queue.playing.any { it.key == registration.key }
    val deferCompletedImmediately = command.operation == RemoteQueueOperation.DEFER_ONE_ROUND &&
        movedFromPlaying && updatedRegistration?.absenceStatus == QueueAbsenceStatus.NONE
    val detail = when (command.operation) {
        RemoteQueueOperation.DEFER_ONE_ROUND -> if (deferCompletedImmediately) {
            registrationSubject(
                "本次游玩机会已暂缓并完成；登记已回到等待顺序前端，当前不再处于暂缓状态。",
                "固定组合本次游玩机会已暂缓并完成；两份登记已回到等待顺序前端，当前不再处于暂缓状态。"
            )
        } else {
            registrationSubject(
                "登记已暂缓一次。",
                "固定组合的两份登记已同时暂缓一次。"
            )
        }
        RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND -> registrationSubject(
            "登记已取消暂缓一次。",
            "固定组合的两份登记已同时取消暂缓一次。"
        )
        RemoteQueueOperation.TEMPORARILY_LEAVE -> if (movedFromPlaying) {
            registrationSubject(
                "登记已设为暂时离开，已离开游玩位置并累计轮空 1 次。",
                "固定组合的两份登记已同时设为暂时离开，已离开游玩位置并累计轮空 1 次。"
            )
        } else {
            registrationSubject(
                "登记已设为暂时离开。",
                "固定组合的两份登记已同时设为暂时离开。"
            )
        }
        RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE -> registrationSubject(
            "登记已取消暂时离开。",
            "固定组合的两份登记已同时取消暂时离开。"
        )
        RemoteQueueOperation.CHANGE_PLAY_PREFERENCE -> buildString {
            val preference = command.preference ?: registration.preference
            append("本次游玩偏好已改为“${remotePreferenceLabel(preference)}”。玩家资料中的默认偏好没有改变。")
            if (fixedPartner != null) {
                append("与“${fixedPartner.displayId}”的固定组合已解除；对方保留原位，并恢复为允许他人加入。")
                append(fixedPairAbsenceRetentionDetail(registration))
            }
        }
        RemoteQueueOperation.LEAVE_QUEUE -> buildString {
            append("登记已退出排队。")
            if (fixedPartner != null) {
                append("与“${fixedPartner.displayId}”的固定组合已解除；对方保留原位，并恢复为允许他人加入。")
                append(remainingPartnerAbsenceDetail(fixedPartner))
            }
            if (queue.playing.any { it.key == registration.key }) {
                append("游玩位置中的空缺不会自动由等待登记补入。")
            }
        }
        else -> "队列操作已完成。"
    }
    return RemoteQueueOperationDecision.Apply(
        state = state.copy(queues = execution.state.queues),
        detail = detail,
        changedMachineIds = setOf(actualMachineId),
        action = action
    )
}

private fun remotePreferenceLabel(preference: PlayPreference): String = when (preference) {
    PlayPreference.SOLO -> "单人游玩"
    PlayPreference.OPEN_TO_JOIN -> "允许他人加入"
}

private fun fixedPairAbsenceRetentionDetail(registration: Registration): String =
    when (registration.absenceStatus) {
        QueueAbsenceStatus.DEFER_ONE_ROUND ->
            "两份登记的“暂缓一次”安排不会因解除组合而取消。"
        QueueAbsenceStatus.TEMPORARILY_AWAY ->
            "两份登记当前的暂时离开状态和已轮空 ${registration.temporaryAwaySkippedTurns} 次不会因解除组合而清除。"
        QueueAbsenceStatus.NONE -> ""
    }

private fun remainingPartnerAbsenceDetail(partner: Registration): String =
    when (partner.absenceStatus) {
        QueueAbsenceStatus.DEFER_ONE_ROUND ->
            "对方仍保持暂缓一次，并会在下一次轮到后自动解除。"
        QueueAbsenceStatus.TEMPORARILY_AWAY ->
            "对方仍保持暂时离开和已轮空 ${partner.temporaryAwaySkippedTurns} 次，返回后需要手动取消。"
        QueueAbsenceStatus.NONE -> ""
    }

private const val MAX_REGISTRATIONS_PER_MACHINE = 20
