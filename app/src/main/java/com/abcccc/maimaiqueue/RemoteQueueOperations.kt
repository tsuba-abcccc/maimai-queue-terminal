package com.abcccc.maimaiqueue

internal enum class RemoteQueueOperation {
    JOIN_QUEUE,
    DEFER_ONE_ROUND,
    CANCEL_DEFER_ONE_ROUND,
    TEMPORARILY_LEAVE,
    CANCEL_TEMPORARY_LEAVE,
    /** 轮空一次 (2026-11-25). */
    SKIP_ONE_TURN,
    CANCEL_SKIP_ONE_TURN,
    TRANSFER_MACHINE,
    CHANGE_PLAY_PREFERENCE,
    LEAVE_QUEUE,
    CHECK_IN,
    CREATE_REGISTRATION,
    REORDER_QUEUE,
    SET_PAIRED_MACHINE_QUEUEING,
    /** 单人游玩 that lets the registrations around it pass (2026-11-26). */
    SET_ALLOWS_PASSING,
    /** 与朋友共同游玩 with a registration that already waits at the same machine (2026-11-28). */
    CREATE_FIXED_PAIR,
    /** 修改登记昵称: the name this one registration shows, not the profile's (2026-11-28). */
    RENAME_REGISTRATION
}

internal enum class RemoteQueueOperationSource {
    QQ_BOT,
    WEBSITE_REMOTE,
    MANAGEMENT_APP;

    val auditLogSource: AuditLogSource
        get() = when (this) {
            QQ_BOT -> AuditLogSource.QQ_BOT
            WEBSITE_REMOTE -> AuditLogSource.WEBSITE_REMOTE
            MANAGEMENT_APP -> AuditLogSource.MANAGEMENT_APP
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
    val profileId: String?,
    val actorQq: String,
    val profileIdentityVerified: Boolean = false,
    val operation: RemoteQueueOperation,
    val source: RemoteQueueOperationSource,
    val machineId: String? = null,
    val machineStableId: String? = null,
    val targetMachineId: String? = null,
    val targetMachineStableId: String? = null,
    val registrationId: String? = null,
    val preference: PlayPreference? = null,
    val expectedPosition: RemoteRegistrationPosition? = null,
    val expectedFixedPairId: String? = null,
    val expectedAbsenceStatus: QueueAbsenceStatus? = null,
    val expectedTemporaryAwaySkippedTurns: Int? = null,
    val expectedPendingCheckIn: Boolean? = null,
    val machineConfigurationRevision: Long? = null,
    val expectedRegistrationOrder: List<String>? = null,
    val registrationOrder: List<String>? = null,
    /**
     * Whether the registration should also queue at the other machine of its pair: the choice
     * made while joining, or the state asked for by [RemoteQueueOperation.SET_PAIRED_MACHINE_QUEUEING].
     * Null when the request does not mention it.
     */
    val alsoQueuesAtPairedMachine: Boolean? = null,
    /**
     * Whether a 单人游玩 registration lets the registrations around it pass: the choice made
     * while joining, or the state asked for by [RemoteQueueOperation.SET_ALLOWS_PASSING]. Null
     * when the request does not mention it.
     */
    val allowsPassing: Boolean? = null,
    /** The friend's registration for [RemoteQueueOperation.CREATE_FIXED_PAIR], as a public id. */
    val partnerRegistrationId: String? = null,
    /** The new nickname for [RemoteQueueOperation.RENAME_REGISTRATION]. */
    val displayId: String? = null,
    /**
     * When joining: the chosen play preference becomes the profile's default (“将本次选择保存为
     * 默认游玩偏好，以后不再询问。”).
     */
    val rememberPreference: Boolean = false
) : RemoteTerminalCommand

/**
 * Whether a registration created remotely on [machineId] lets the system arrange it between the
 * two machines of the pair. Only the tick box of that one request decides; a client that does
 * not mention it gets a registration that stays at its machine. Returns null when the request
 * asks for it on a machine that has no pair.
 */
internal fun RemoteQueueExecutionState.resolvePairedMachineQueueing(
    machineId: String,
    requested: Boolean?
): Boolean? {
    val resolved = machinePartners[machineId] != null && requested == true
    return resolved.takeIf { requested == null || requested == resolved }
}

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
    val allowTemporaryLeave: Boolean,
    val machineCapacities: Map<String, Int> = emptyMap(),
    val machineStableIds: Map<String, String> = emptyMap(),
    val machineConfigurationRevision: Long = 1L,
    val managementPolicyRevision: Long = 0L,
    /** Orders shared by paired machines, and the pairs themselves, as the queue engine uses them. */
    val pairedMachineOrders: Map<String, List<Int>> = emptyMap(),
    val pairedArrangements: Map<Int, PairedArrangementRecord> = emptyMap(),
    val machinePartners: Map<String, String> = emptyMap(),
    /** The names the venue gave its machines, such as 左侧·机台 A, for the answers to players. */
    val machineNames: Map<String, String> = emptyMap()
)

/**
 * The name of [machineId] as the terminal and the website show it. The answers used to say
 * 机台 B where the confirmation the player had just read said 右侧·机台 B (review 2026-11-27).
 */
internal fun RemoteQueueExecutionState.machineLabel(machineId: String): String =
    machineNames[machineId]?.takeIf(String::isNotBlank) ?: "机台 $machineId"

/**
 * The machine that holds [registrationKey]. A registration that queues at two machines can be
 * taken by the other machine the moment it is created, so this is not always the machine it
 * was created on.
 */
internal fun QueueEngineState.machineHolding(registrationKey: Int): String? =
    queues.entries.firstOrNull { (_, queue) ->
        queue.allRegistrations.any { it.key == registrationKey }
    }?.key

internal fun RemoteQueueExecutionState.machineCapacity(machineId: String): Int =
    machineCapacities[machineId]?.takeIf { it == 1 || it == 2 } ?: 2

internal fun QueueAction.normalizedForMachineCapacities(
    machineCapacities: Map<String, Int>
): QueueAction = when (this) {
    is QueueAction.AddRegistrations -> if (machineCapacities[machineId] == 1) {
        copy(
            registrations = registrations.map { registration ->
                registration.copy(
                    preference = PlayPreference.SOLO,
                    fixedPartnerKey = null
                )
            }
        )
    } else {
        this
    }
    else -> this
}

internal fun RemoteQueueExecutionState.executeQueueAction(
    action: QueueAction,
    origin: QueueActionOrigin,
    atMillis: Long
): QueueActionExecution = QueueEngine.execute(
    state = QueueEngineState(queues, pairedMachineOrders, pairedArrangements),
    action = action.normalizedForMachineCapacities(machineCapacities),
    context = QueueActionContext(
        atMillis = atMillis,
        origin = origin,
        policy = QueueEnginePolicy(
            registrationOpen = acceptingNewRegistrations,
            allowOnlineRegistration = allowOnlineRegistration,
            allowDeferOneRound = allowDeferOneRound,
            allowTemporaryLeave = allowTemporaryLeave,
            machineStatuses = machineStatuses,
            machineCapacities = machineCapacities,
            maxRegistrationsPerMachine = MAX_REGISTRATIONS_PER_MACHINE,
            requireOperationalForPlayerActions = true,
            machinePartners = machinePartners,
            // An arrangement to the other machine is announced before it happens.
            pairedArrangementRules = PairedArrangementRules()
        )
    )
)

private val RemoteQueueOperationSource.queueActionOrigin: QueueActionOrigin
    get() = when (this) {
        RemoteQueueOperationSource.QQ_BOT -> QueueActionOrigin.QQ_BOT
        RemoteQueueOperationSource.WEBSITE_REMOTE -> QueueActionOrigin.WEBSITE
        RemoteQueueOperationSource.MANAGEMENT_APP -> QueueActionOrigin.MANAGEMENT_APP
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
        return reject("现场已开始新的队列，请重新查询后再操作。")
    }
    if (
        command.operation == RemoteQueueOperation.CREATE_REGISTRATION &&
        command.commandId in appliedRegistrationCommandIds
    ) {
        return already("管理后台的这项创建登记操作已经由现场终端执行，不会重复处理。")
    }
    if (
        command.operation == RemoteQueueOperation.JOIN_QUEUE ||
        command.operation == RemoteQueueOperation.CREATE_REGISTRATION
    ) {
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
                        ),
                        passingChoice = exactRegistration.allowsPassing.takeIf {
                            command.allowsPassing != null &&
                                exactRegistration.preference == PlayPreference.SOLO
                        }
                    )
                }
            return already(
                if (command.operation == RemoteQueueOperation.CREATE_REGISTRATION) {
                    "管理后台创建的登记已经在队列中。"
                } else {
                    "线上登记已经加入等待顺序。请在创建登记后的 30 分钟内到现场终端完成签到；超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动移除。"
                },
                updatedProfile = profileToPersist
            )
        }
    }

    if (command.operation == RemoteQueueOperation.CREATE_REGISTRATION) {
        if (command.source != RemoteQueueOperationSource.MANAGEMENT_APP) {
            return reject("只有管理后台可以创建登记。")
        }
        val profile = state.playerProfiles.firstOrNull { it.id == command.profileId }
            ?: return reject("玩家资料已不存在。")
        if (
            command.machineConfigurationRevision != null &&
            command.machineConfigurationRevision != state.machineConfigurationRevision
        ) {
            return reject("机台配置已经更新，请重新查询后再操作。")
        }
        if (
            command.machineStableId != null &&
            state.machineStableIds[command.machineId] != command.machineStableId
        ) {
            return reject("所选机台已经变化，请重新查询后再操作。")
        }
        if (!state.acceptingNewRegistrations) {
            return reject("现场当前未使用登记排队，暂不能创建登记。")
        }
        val machineId = command.machineId ?: return reject("没有指定登记机台。")
        val queue = state.queues[machineId] ?: return reject("所选机台不存在。")
        if (state.machineStatuses[machineId]?.isOperational != true) {
            return reject("${state.machineLabel(machineId)} 已停止使用，暂不能创建登记。")
        }
        if (queue.registrationCount >= MAX_REGISTRATIONS_PER_MACHINE) {
            return reject("${state.machineLabel(machineId)} 的登记已满，请选择其他机台。")
        }
        val alreadyRegistered = state.queues.values.any { currentQueue ->
            currentQueue.allRegistrations.any { registration ->
                registration.playerProfileId == profile.id ||
                    registration.displayId.equals(profile.nickname, ignoreCase = true)
            }
        }
        if (alreadyRegistered) {
            return reject("这名玩家已经有一份正在排队的登记。")
        }
        val singlePlayerMachine = state.machineCapacity(machineId) == 1
        val resolvedPreference = if (singlePlayerMachine) {
            PlayPreference.SOLO
        } else {
            when (profile.defaultPreference) {
                ProfilePlayPreference.ASK_EVERY_TIME -> command.preference
                    ?: return reject("请选择本次游玩偏好。")
                ProfilePlayPreference.SOLO -> PlayPreference.SOLO
                ProfilePlayPreference.OPEN_TO_JOIN -> PlayPreference.OPEN_TO_JOIN
            }
        }
        if (
            !singlePlayerMachine &&
            profile.defaultPreference != ProfilePlayPreference.ASK_EVERY_TIME &&
            command.preference != null && command.preference != resolvedPreference
        ) {
            return reject("玩家资料的默认游玩偏好已经变化，请重新确认后再创建。")
        }
        val queuesAtPairedMachine = state.resolvePairedMachineQueueing(
            machineId = machineId,
            requested = command.alsoQueuesAtPairedMachine
        ) ?: return reject("一对机台的设置已经变化，请重新确认后再创建。")
        val allowsPassing = command.allowsPassing == true &&
            resolvedPreference == PlayPreference.SOLO && !singlePlayerMachine
        val registration = Registration(
            key = state.nextRegistrationKey,
            displayId = profile.nickname,
            preference = resolvedPreference,
            isTemporary = false,
            createdAtMillis = appliedAtMillis,
            gender = profile.gender,
            playerProfileId = profile.id,
            requiresOnSiteCheckIn = false,
            originatingCommandId = command.commandId,
            alsoQueuesAtPairedMachine = queuesAtPairedMachine,
            allowsPassing = allowsPassing
        )
        val queueAction = QueueAction.AddRegistrations(
            machineId = machineId,
            registrations = listOf(registration),
            placement = RegistrationPlacement.ADVANCE_IF_UNAMBIGUOUS
        )
        val execution = state.executeQueueAction(
            action = queueAction,
            origin = command.source.queueActionOrigin,
            atMillis = appliedAtMillis
        ) as? QueueActionExecution.Applied
        val holdingMachineId = execution?.state?.machineHolding(registration.key)
            ?: return reject("终端未能创建登记，请重新查询后再试。")
        val usedProfile = if ((profile.lastUsedAtMillis ?: Long.MIN_VALUE) < appliedAtMillis) {
            profile.recordUsage(
                atMillis = maxOf(appliedAtMillis, profile.updatedAtMillis + 1L),
                // As the retry above records it (review 2026-11-27).
                passingChoice = allowsPassing.takeIf {
                    command.allowsPassing != null && resolvedPreference == PlayPreference.SOLO && !singlePlayerMachine
                }
            )
        } else null
        return RemoteQueueOperationDecision.Apply(
            state = state.copy(
                queues = execution.state.queues,
                pairedMachineOrders = execution.state.pairedMachineOrders,
                pairedArrangements = execution.state.pairedArrangements,
                nextRegistrationKey = state.nextRegistrationKey + 1
            ),
            detail = buildString {
                append("管理后台已在 ${state.machineLabel(machineId)} 创建登记。")
                if (singlePlayerMachine) append("这台机台仅能容纳一人游玩，本次已使用“单人游玩”。")
                if (holdingMachineId != machineId) {
                    append(
                        "${state.machineLabel(holdingMachineId)} 当前可以游玩，" +
                            "登记已自动转至 ${state.machineLabel(holdingMachineId)} 并进入游玩位置。"
                    )
                }
            },
            changedMachineIds = setOf(machineId, holdingMachineId),
            action = queueAction,
            updatedProfile = usedProfile
        )
    }
    if (command.commandId in appliedRegistrationCommandIds) {
        return already(
            if (command.operation == RemoteQueueOperation.JOIN_QUEUE) {
                "这份线上登记已经创建过，当前已不在队列中。"
            } else {
                "这项排队操作已经由现场终端执行，不会重复处理。"
            }
        )
    }
    if (
        command.machineConfigurationRevision != null &&
        command.machineConfigurationRevision != state.machineConfigurationRevision
    ) {
        return reject("机台配置已经更新，请重新查询后再操作。")
    }
    if (
        command.machineStableId != null &&
        state.machineStableIds[command.machineId] != command.machineStableId
    ) {
        return reject("所选机台已经变化，请重新查询后再操作。")
    }
    if (
        command.targetMachineStableId != null &&
        state.machineStableIds[command.targetMachineId] != command.targetMachineStableId
    ) {
        return reject("要转入的机台已经变化，请重新查询后再操作。")
    }
    when (command.source) {
        RemoteQueueOperationSource.WEBSITE_REMOTE -> if (!state.websiteRemoteEnabled) {
            return reject("现场终端已关闭与服务端同步，暂不能在线操作。")
        }
        RemoteQueueOperationSource.QQ_BOT -> if (!state.oneBotSyncEnabled) {
            return reject("现场终端已关闭 QQ Bot 联动。")
        }
        RemoteQueueOperationSource.MANAGEMENT_APP -> Unit
    }

    if (command.operation == RemoteQueueOperation.REORDER_QUEUE) {
        if (command.source != RemoteQueueOperationSource.MANAGEMENT_APP) {
            return reject("只有管理后台可以调整等待顺序。")
        }
        val machineId = command.machineId ?: return reject("没有指定要调整的机台。")
        val queue = state.queues[machineId] ?: return reject("目标机台不存在。")
        val expectedOrder = command.expectedRegistrationOrder
            ?: return reject("命令缺少当前登记顺序。")
        val desiredOrder = command.registrationOrder
            ?: return reject("命令缺少新的登记顺序。")
        // The order as the snapshot publishes it (the home screen's), which is what the
        // management app and the server know; see decideManagementQueueAction.
        val currentOrder = (queue.playing.map(Registration::key) + queue.shownWaitingPositionKeys().flatten())
            .map { publicRegistrationId(state.queueId, it) }
        if (currentOrder != expectedOrder) {
            return reject("现场队列已经更新，请刷新后重试。")
        }
        if (desiredOrder.toSet() != currentOrder.toSet()) {
            return reject("新的登记顺序与当前队列不一致。")
        }
        val playingCount = queue.playing.size
        if (desiredOrder.take(playingCount) != currentOrder.take(playingCount)) {
            return reject("当前游玩位置不能调整顺序。")
        }
        if (desiredOrder == currentOrder) {
            return already("队列已经是所选顺序。")
        }
        val registrationsById = queue.allRegistrations.associateBy {
            publicRegistrationId(state.queueId, it.key)
        }
        // Only what was moved changes place; the real order of the rest stays.
        val desiredWaitingKeys = desiredOrder.drop(playingCount).map { id ->
            registrationsById[id]?.key ?: return reject("新的登记顺序包含不存在的登记。")
        }
        val reorderedQueue = queue.withShownRegistrationOrder(desiredWaitingKeys)
            ?: return reject("新的登记顺序与当前队列不一致。")
        val queueAction = QueueAction.ReplaceOrder(machineId, reorderedQueue.allRegistrations)
        val execution = state.executeQueueAction(
            action = queueAction,
            origin = command.source.queueActionOrigin,
            atMillis = appliedAtMillis
        ) as? QueueActionExecution.Applied
        val updatedQueue = execution?.state?.queue(machineId)
        if (execution == null || updatedQueue == null || updatedQueue == queue) {
            return reject("队列状态已经变化，这次调整没有执行。")
        }
        return RemoteQueueOperationDecision.Apply(
            state = state.copy(
                queues = execution.state.queues,
                pairedMachineOrders = execution.state.pairedMachineOrders,
                pairedArrangements = execution.state.pairedArrangements
            ),
            detail = "管理后台已调整 ${state.machineLabel(machineId)} 的等待顺序。",
            changedMachineIds = setOf(machineId),
            action = queueAction
        )
    }

    val profile = command.profileId?.let { profileId ->
        state.playerProfiles.firstOrNull { it.id == profileId }
    }
    if (profile == null && (
            command.source != RemoteQueueOperationSource.MANAGEMENT_APP ||
                command.operation == RemoteQueueOperation.JOIN_QUEUE ||
                command.operation == RemoteQueueOperation.CREATE_REGISTRATION
            )
    ) {
        return reject("玩家资料已不存在。")
    }
    val websiteAccountIdentityVerified =
        command.source == RemoteQueueOperationSource.WEBSITE_REMOTE &&
            command.operation != RemoteQueueOperation.JOIN_QUEUE &&
            command.profileIdentityVerified
    if (
        profile != null && profile.normalizedQqNumber() != command.actorQq &&
        !websiteAccountIdentityVerified &&
        command.source != RemoteQueueOperationSource.MANAGEMENT_APP
    ) {
        return reject("玩家资料绑定的 QQ 已发生变化，请重新查询后再操作。")
    }

    if (command.operation == RemoteQueueOperation.JOIN_QUEUE) {
        val profile = profile ?: return reject("玩家资料已不存在。")
        if (!state.allowOnlineRegistration) {
            return reject("系统规则不允许线上登记。")
        }
        if (!state.acceptingNewRegistrations) {
            return reject("现场当前未使用登记排队，暂不能线上加入排队。")
        }
        val machineId = command.machineId ?: return reject("没有指定排队机台。")
        val queue = state.queues[machineId] ?: return reject("所选机台不存在。")
        if (state.machineStatuses[machineId]?.isOperational != true) {
            return reject("${state.machineLabel(machineId)} 已停止使用，暂不能加入。")
        }
        if (queue.registrationCount >= MAX_REGISTRATIONS_PER_MACHINE) {
            return reject("${state.machineLabel(machineId)} 的登记已满，请选择其他机台。")
        }
        val alreadyRegistered = state.queues.values.any { currentQueue ->
            currentQueue.allRegistrations.any { registration ->
                registration.playerProfileId == profile.id ||
                    registration.displayId.equals(profile.nickname, ignoreCase = true)
            }
        }
        if (alreadyRegistered) {
            return reject("已经有一份正在排队的登记，不能重复加入。")
        }
        val singlePlayerMachine = state.machineCapacity(machineId) == 1
        val resolvedPreference = if (singlePlayerMachine) {
            PlayPreference.SOLO
        } else {
            when (profile.defaultPreference) {
                ProfilePlayPreference.ASK_EVERY_TIME -> command.preference
                    ?: return reject("请选择本次游玩偏好。")
                ProfilePlayPreference.SOLO -> PlayPreference.SOLO
                ProfilePlayPreference.OPEN_TO_JOIN -> PlayPreference.OPEN_TO_JOIN
            }
        }
        if (
            !singlePlayerMachine &&
            profile.defaultPreference != ProfilePlayPreference.ASK_EVERY_TIME &&
            command.preference != null && command.preference != resolvedPreference
        ) {
            return reject("玩家资料的默认游玩偏好已经变化，请重新确认后再加入。")
        }
        // The registration takes its place at the other machine once it has checked in.
        val queuesAtPairedMachine = state.resolvePairedMachineQueueing(
            machineId = machineId,
            requested = command.alsoQueuesAtPairedMachine
        ) ?: return reject("一对机台的设置已经变化，请重新确认后再加入。")
        val allowsPassing = command.allowsPassing == true &&
            resolvedPreference == PlayPreference.SOLO && !singlePlayerMachine
        val registration = Registration(
            key = state.nextRegistrationKey,
            displayId = profile.nickname,
            preference = resolvedPreference,
            isTemporary = false,
            createdAtMillis = appliedAtMillis,
            gender = profile.gender,
            playerProfileId = profile.id,
            requiresOnSiteCheckIn = true,
            wasOnlineRegistration = true,
            originatingCommandId = command.commandId,
            alsoQueuesAtPairedMachine = queuesAtPairedMachine,
            allowsPassing = allowsPassing
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
        // Judged by the new registration itself: the same execution may also let a waiting
        // registration of the other machine of a pair move here, which changes the counts
        // (review 2026-11-27).
        if (execution?.state?.machineHolding(registration.key) == null) {
            return reject("终端未能创建线上登记，请重新查询后再试。")
        }
        val usedProfile = if ((profile.lastUsedAtMillis ?: Long.MIN_VALUE) < appliedAtMillis) {
            profile.recordUsage(
                atMillis = maxOf(appliedAtMillis, profile.updatedAtMillis + 1L),
                // 将本次选择保存为默认游玩偏好，以后不再询问。
                preferenceToRemember = resolvedPreference.takeIf {
                    command.rememberPreference && !singlePlayerMachine
                },
                // The website opens the explanation before it sends such a request; the Bot
                // has nothing to show and therefore records nothing.
                pairedMachineQueueingIntroducedNow = queuesAtPairedMachine &&
                    command.source == RemoteQueueOperationSource.WEBSITE_REMOTE,
                // The website sends the answer, ticked or not, wherever it offered the box.
                passingChoice = command.allowsPassing.takeIf {
                    resolvedPreference == PlayPreference.SOLO && !singlePlayerMachine
                }
            )
        } else {
            null
        }
        return RemoteQueueOperationDecision.Apply(
            state = state.copy(
                queues = execution.state.queues,
                pairedMachineOrders = execution.state.pairedMachineOrders,
                pairedArrangements = execution.state.pairedArrangements,
                nextRegistrationKey = state.nextRegistrationKey + 1
            ),
            detail = buildString {
                append("线上登记已加入 ${state.machineLabel(machineId)} 的等待顺序。")
                if (singlePlayerMachine) {
                    append("这台机台仅能容纳一人游玩，本次已使用“单人游玩”。")
                }
                append("请在创建登记后的 30 分钟内到现场终端完成签到；超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动移除。")
            },
            changedMachineIds = setOf(machineId),
            action = queueAction,
            updatedProfile = usedProfile
        )
    }

    val registrationId = command.registrationId
        ?: return reject("未能确定要操作的登记，请重新查询后再操作。")
    val located = state.queues.asSequence().mapNotNull { (machineId, queue) ->
        queue.allRegistrations.firstOrNull {
            publicRegistrationId(state.queueId, it.key) == registrationId
        }?.let { Triple(machineId, queue, it) }
    }.firstOrNull()
    if (located == null) {
        return if (command.operation == RemoteQueueOperation.LEAVE_QUEUE) {
            already("这份登记已不在队列中。")
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
    if (profile != null && registration.playerProfileId != profile.id) {
        return reject("这份登记已不再关联当前玩家资料。")
    }
    // A cancel that arrives after the 轮空一次 or 暂缓一次 was carried out is not a repeated
    // cancel: the registration is already at the tail, or has already let the others go first
    // (review 2026-11-27). The skip count and the used deferral tell the two apart.
    val carriedOutDetail = when (command.operation) {
        RemoteQueueOperation.CANCEL_SKIP_ONE_TURN -> {
            val expectedSkips = command.expectedTemporaryAwaySkippedTurns
            if (
                registration.absenceStatus == QueueAbsenceStatus.NONE &&
                expectedSkips != null &&
                registration.temporaryAwaySkippedTurns > expectedSkips
            ) {
                registrationSubject(
                    "这份登记已轮空一次并移至队尾，无法再取消。",
                    "固定组合的两份登记已轮空一次并移至队尾，无法再取消。"
                )
            } else null
        }
        RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND ->
            if (registration.absenceStatus == QueueAbsenceStatus.NONE && registration.deferOneRoundUsed) {
                registrationSubject(
                    "这份登记的暂缓一次已完成，无法再取消。",
                    "固定组合两份登记的暂缓一次已完成，无法再取消。"
                )
            } else null
        else -> null
    }
    if (carriedOutDetail != null) return reject(carriedOutDetail)
    val alreadyAppliedDetail = when (command.operation) {
        RemoteQueueOperation.DEFER_ONE_ROUND ->
            if (registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND) {
                registrationSubject(
                    "这份登记已经设置为暂缓一次。",
                    "固定组合的两份登记已经设置为暂缓一次。"
                )
            } else null
        RemoteQueueOperation.SKIP_ONE_TURN ->
            if (registration.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN) {
                registrationSubject(
                    "这份登记已经设置为轮空一次。",
                    "固定组合的两份登记已经设置为轮空一次。"
                )
            } else null
        RemoteQueueOperation.CANCEL_SKIP_ONE_TURN ->
            if (registration.absenceStatus == QueueAbsenceStatus.NONE) {
                registrationSubject(
                    "这份登记已经取消轮空一次。",
                    "固定组合的两份登记已经取消轮空一次。"
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
                "这份登记已经在 ${state.machineLabel(actualMachineId)} 排队。"
            } else null
        RemoteQueueOperation.CHANGE_PLAY_PREFERENCE ->
            if (
                registration.preference == command.preference &&
                registration.fixedPartnerKey == null
            ) {
                "这份登记已经使用所选游玩偏好。"
            } else null
        RemoteQueueOperation.CHECK_IN ->
            if (!registration.requiresOnSiteCheckIn) {
                "这份线上登记已经完成现场签到。"
            } else null
        RemoteQueueOperation.SET_PAIRED_MACHINE_QUEUEING ->
            when (command.alsoQueuesAtPairedMachine) {
                registration.alsoQueuesAtPairedMachine ->
                    if (registration.alsoQueuesAtPairedMachine) {
                        "这份登记已经允许系统在两台机台之间安排。"
                    } else {
                        "这份登记已经只在当前机台排队。"
                    }
                else -> null
            }
        RemoteQueueOperation.SET_ALLOWS_PASSING ->
            when (command.allowsPassing) {
                registration.allowsPassing -> {
                    val subject = if (registration.fixedPartnerKey != null) "这组固定组合" else "这份登记"
                    if (registration.allowsPassing) {
                        "${subject}已经允许后方的登记越过。"
                    } else {
                        "${subject}已经不允许后方的登记越过。"
                    }
                }
                else -> null
            }
        RemoteQueueOperation.CREATE_REGISTRATION -> null
        RemoteQueueOperation.REORDER_QUEUE -> null
        // Both say "already" themselves, further down.
        RemoteQueueOperation.CREATE_FIXED_PAIR,
        RemoteQueueOperation.RENAME_REGISTRATION -> null
        RemoteQueueOperation.JOIN_QUEUE,
        RemoteQueueOperation.LEAVE_QUEUE -> null
    }
    if (alreadyAppliedDetail != null) {
        // The answer may not have reached the profile the first time (its write failed after
        // the queue was saved); a retry still remembers it (review 2026-11-27).
        val profileWithPassingChoice = command.allowsPassing
            ?.takeIf { command.operation == RemoteQueueOperation.SET_ALLOWS_PASSING }
            ?.let { enabled ->
                state.playerProfiles.firstOrNull { it.id == registration.playerProfileId }
                    ?.let { profile -> profile.rememberingPassingAnswer(registration, enabled, appliedAtMillis) }
            }
        return already(alreadyAppliedDetail, profileWithPassingChoice)
    }

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
            RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE,
            RemoteQueueOperation.CANCEL_SKIP_ONE_TURN
        )
        if (
            locksAbsenceState && (
                command.expectedAbsenceStatus != registration.absenceStatus ||
                    command.expectedTemporaryAwaySkippedTurns !=
                    registration.publishedTemporaryAwaySkippedTurns ||
                    command.expectedPendingCheckIn != registration.requiresOnSiteCheckIn
                )
        ) {
            return reject("登记状态已经变化，请重新查询并确认后再操作。")
        }
    }
    // A stopped machine keeps its registrations, and nobody is held there: leaving the queue and
    // moving to another machine stay possible. Everything else waits until it runs again.
    if (
        state.machineStatuses[actualMachineId]?.isOperational != true &&
        command.operation != RemoteQueueOperation.LEAVE_QUEUE &&
        command.operation != RemoteQueueOperation.TRANSFER_MACHINE
    ) {
        return reject("${state.machineLabel(actualMachineId)} 已停止使用，恢复正常使用前只能退出排队或转至其他机台。")
    }
    if (
        registration.requiresOnSiteCheckIn &&
        command.operation != RemoteQueueOperation.LEAVE_QUEUE &&
        !(command.source == RemoteQueueOperationSource.MANAGEMENT_APP &&
            command.operation == RemoteQueueOperation.CHECK_IN)
    ) {
        return reject("线上登记完成现场签到后，才能进行这项操作。")
    }

    if (command.operation == RemoteQueueOperation.RENAME_REGISTRATION) {
        val newName = command.displayId?.trim().orEmpty()
        if (newName.isBlank() || newName.codePointCount(0, newName.length) > 18) {
            return reject("登记昵称应为 1 至 18 个字符，请重新填写。")
        }
        if (newName == registration.displayId) {
            return already("这份登记的昵称已经是“$newName”。")
        }
        // Every machine is one venue: no two registrations share a nickname.
        val taken = state.queues.values.any { machineQueue ->
            machineQueue.allRegistrations.any {
                it.key != registration.key && it.displayId.trim().equals(newName, ignoreCase = true)
            }
        }
        if (taken) return reject("当前队列中已经有相同昵称的登记。")
        val queueAction = QueueAction.RenameRegistration(actualMachineId, registration.key, newName)
        val execution = state.executeQueueAction(
            action = queueAction,
            origin = command.source.queueActionOrigin,
            atMillis = appliedAtMillis
        ) as? QueueActionExecution.Applied
        val renamed = execution?.state?.queue(actualMachineId)?.allRegistrations
            ?.firstOrNull { it.key == registration.key }
        if (execution == null || renamed?.displayId != newName) {
            return reject("队列状态已经变化，这项操作没有执行。")
        }
        return RemoteQueueOperationDecision.Apply(
            state = state.copy(
                queues = execution.state.queues,
                pairedMachineOrders = execution.state.pairedMachineOrders,
                pairedArrangements = execution.state.pairedArrangements
            ),
            detail = if (registration.playerProfileId != null) {
                "登记昵称已改为“$newName”。玩家资料中的昵称没有改变。"
            } else {
                "登记昵称已改为“$newName”。"
            },
            changedMachineIds = setOf(actualMachineId),
            action = queueAction
        )
    }

    if (command.operation == RemoteQueueOperation.CREATE_FIXED_PAIR) {
        if (state.machineCapacity(actualMachineId) == 1) {
            return reject("${state.machineLabel(actualMachineId)} 仅能容纳一人游玩，不能组成固定组合。")
        }
        if (queue.waiting.none { it.key == registration.key }) {
            return reject("这份登记处于游玩位置。请在本轮结束、这份登记回到等待顺序后，再组成固定组合。")
        }
        val partnerId = command.partnerRegistrationId
            ?: return reject("未能确定朋友的登记，请重新选择。")
        val partner = queue.waiting.firstOrNull {
            publicRegistrationId(state.queueId, it.key) == partnerId
        } ?: return reject("朋友的登记已经不在这台机台的等待顺序中，请重新选择。")
        if (partner.key == registration.key) {
            return reject("未能确定朋友的登记，请重新选择。")
        }
        if (registration.fixedPartnerKey != null) {
            return if (registration.fixedPartnerKey == partner.key) {
                already("这两份登记已经组成固定组合。")
            } else {
                reject("这份登记已经属于固定组合，请先解除固定组合。")
            }
        }
        if (partner.fixedPartnerKey != null) {
            return reject("“${partner.displayId}”已经属于固定组合。")
        }
        if (partner.requiresOnSiteCheckIn) {
            return reject("这份线上登记尚未完成现场签到，签到后才能组成固定组合。")
        }
        if (
            registration.absenceStatus != QueueAbsenceStatus.NONE ||
            partner.absenceStatus != QueueAbsenceStatus.NONE
        ) {
            return reject(FIXED_PAIR_WHILE_POSTPONED_REASON)
        }
        val plan = queue.planFriendPair(registration.key, partner.key)
            ?: return reject("无法确认这两份登记当前所在的等待顺序，请重新查询后再操作。")
        fun names(registrations: List<Registration>) =
            registrations.joinToString("、") { "“${it.displayId}”" }
        if (plan.delayedOtherRegistrations.isNotEmpty()) {
            return reject(
                "如果直接组成固定组合，${names(plan.delayedOtherRegistrations)}会延后游玩，因此不能组成固定组合。"
            )
        }
        val pairAllowsPassing = command.allowsPassing == true
        val queueAction = QueueAction.CreateFixedPair(
            machineId = actualMachineId,
            firstRegistrationKey = plan.firstRegistration.key,
            secondRegistrationKey = plan.secondRegistration.key,
            expectedPlan = plan,
            allowsPassing = pairAllowsPassing
        )
        val execution = state.executeQueueAction(
            action = queueAction,
            origin = command.source.queueActionOrigin,
            atMillis = appliedAtMillis
        ) as? QueueActionExecution.Applied
        val paired = execution?.state?.queue(actualMachineId)?.waiting
            ?.firstOrNull { it.key == registration.key }
        if (execution == null || paired?.fixedPartnerKey != partner.key) {
            return reject("队列状态已经变化，这项操作没有执行。")
        }
        return RemoteQueueOperationDecision.Apply(
            state = state.copy(
                queues = execution.state.queues,
                pairedMachineOrders = execution.state.pairedMachineOrders,
                pairedArrangements = execution.state.pairedArrangements
            ),
            detail = buildString {
                append("“${registration.displayId}”与“${partner.displayId}”已组成固定组合。")
                if (plan.movedBackRegistrations.isNotEmpty()) {
                    append(
                        "为避免延后其他玩家，${names(plan.movedBackRegistrations)}已后移至" +
                            "位置 $actualMachineId${plan.pairPositionIndex + 1}。"
                    )
                }
                if (pairAllowsPassing) {
                    append("已允许后方的登记越过这组固定组合与前方的登记共同游玩。")
                }
            },
            changedMachineIds = setOf(actualMachineId),
            action = queueAction,
            // Ticking the pair's box online showed the explanation there.
            updatedProfile = profile?.takeIf { pairAllowsPassing }
                ?.withPassingIntroduced(appliedAtMillis)
                ?.takeIf { it != profile }
        )
    }

    if (command.operation == RemoteQueueOperation.CHECK_IN) {
        if (command.source != RemoteQueueOperationSource.MANAGEMENT_APP) {
            return reject("只有管理后台可以立即完成线上登记签到。")
        }
        if (command.expectedPosition != RemoteRegistrationPosition.WAITING) {
            return reject("只有等待顺序中的线上登记可以立即签到。")
        }
        if (!registration.requiresOnSiteCheckIn) {
            return already("这份线上登记已经完成现场签到。")
        }
        val queueAction = QueueAction.CheckIn(actualMachineId, registration.key)
        val execution = state.executeQueueAction(
            action = queueAction,
            origin = command.source.queueActionOrigin,
            atMillis = appliedAtMillis
        ) as? QueueActionExecution.Applied
        val updatedQueue = execution?.state?.queue(actualMachineId)
        val updatedRegistration = updatedQueue?.allRegistrations?.firstOrNull {
            it.key == registration.key
        }
        if (
            execution == null ||
            updatedQueue == null ||
            updatedRegistration == null ||
            updatedRegistration.requiresOnSiteCheckIn
        ) {
            return reject("终端未能完成线上登记签到，请重新查询后再试。")
        }
        return RemoteQueueOperationDecision.Apply(
            state = state.copy(
                queues = execution.state.queues,
                pairedMachineOrders = execution.state.pairedMachineOrders,
                pairedArrangements = execution.state.pairedArrangements
            ),
            detail = "管理后台已为这份线上登记完成签到。",
            changedMachineIds = setOf(actualMachineId),
            action = queueAction
        )
    }

    if (command.operation == RemoteQueueOperation.TRANSFER_MACHINE) {
        val targetMachineId = command.targetMachineId
            ?: return reject("没有指定要转入的机台。")
        if (actualMachineId == targetMachineId) {
            return already("这份登记已经在 ${state.machineLabel(targetMachineId)} 排队。")
        }
        if (command.machineId != actualMachineId) {
            return reject("登记所在机台已经变化，请重新查询后再操作。")
        }
        val targetQueue = state.queues[targetMachineId]
            ?: return reject("要转入的机台不存在。")
        if (state.machineStatuses[targetMachineId]?.isOperational != true) {
            return reject("${state.machineLabel(targetMachineId)} 已停止使用，暂不能转入。")
        }
        if (queue.playing.any { it.key == registration.key }) {
            return reject("处于游玩位置的登记不能转至其他机台。")
        }
        if (targetQueue.registrationCount >= MAX_REGISTRATIONS_PER_MACHINE) {
            return reject("${state.machineLabel(targetMachineId)} 的登记已满，暂不能转入。")
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
        // Judged by the registration itself, not by counts that an arrangement between the two
        // machines of a pair may also change in the same execution (review 2026-11-27).
        if (execution == null || execution.state.machineHolding(registration.key) != targetMachineId) {
            return reject("终端未能将登记转至其他机台，请重新查询后再试。")
        }
        return RemoteQueueOperationDecision.Apply(
            state = state.copy(
                queues = execution.state.queues,
                pairedMachineOrders = execution.state.pairedMachineOrders,
                pairedArrangements = execution.state.pairedArrangements
            ),
            detail = buildString {
                append("登记已转至 ${state.machineLabel(targetMachineId)} 的队尾。")
                if (state.machineCapacity(targetMachineId) == 1) {
                    append("目标机台仅能容纳一人游玩，")
                    append(
                        if (registration.preference == PlayPreference.SOLO) {
                            "本次登记继续使用“单人游玩”"
                        } else {
                            "本次登记已改为“单人游玩”"
                        }
                    )
                    append("；玩家资料中的默认游玩偏好不会改变。")
                    append("之后转回支持共同游玩的机台时，本次登记仍会保持“单人游玩”；需要时，请手动更改本次游玩偏好。")
                }
                if (registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND) {
                    if (fixedPartner != null) {
                        append("转入登记不再暂缓；留在原机台的登记仍将暂缓一次。")
                    } else {
                        append("转入后不再暂缓。")
                    }
                }
                if (registration.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN) {
                    // Arriving at the tail already does what 轮空一次 would.
                    if (fixedPartner != null) {
                        append("转入登记已在队尾，不再轮空；留在原机台的登记仍将轮空一次。")
                    } else {
                        append("转入后已在队尾，不再轮空。")
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

    // Deferring and leaving temporarily are for registrations that wait; see QueueEngine.
    val inPlayingPosition = queue.playing.any {
        it.key == registration.key || it.key == registration.fixedPartnerKey
    }
    // A checked-in online registration that has not played cannot be postponed, except from
    // the management app (2026-11-25).
    val postponeLocked = command.source != RemoteQueueOperationSource.MANAGEMENT_APP &&
        (registration.mustBeRemovedWhenAbsent || fixedPartner?.mustBeRemovedWhenAbsent == true)
    val action = when (command.operation) {
        RemoteQueueOperation.DEFER_ONE_ROUND -> {
            when {
                !state.allowDeferOneRound -> return reject("系统规则不允许暂缓一次。")
                inPlayingPosition ->
                    return reject("处于游玩位置的登记不能暂缓一次，请先在现场终端撤回至等待顺序。")
                registration.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND ->
                    return already(
                        registrationSubject(
                            "这份登记已经设置为暂缓一次。",
                            "固定组合的两份登记已经设置为暂缓一次。"
                        )
                    )
                registration.absenceStatus != QueueAbsenceStatus.NONE ->
                    return reject("请先取消当前的暂缓一次、轮空一次或暂时离开。")
                postponeLocked -> return reject(ONLINE_REGISTRATION_POSTPONE_REASON)
                registration.deferOneRoundUsed || fixedPartner?.deferOneRoundUsed == true ->
                    return reject(DEFER_ONE_ROUND_USED_REASON)
            }
            QueueAction.DeferOneRound(actualMachineId, registration.key)
        }
        RemoteQueueOperation.SKIP_ONE_TURN -> {
            when {
                inPlayingPosition ->
                    return reject("处于游玩位置的登记不能轮空一次，请先在现场终端撤回至等待顺序。")
                registration.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN ->
                    return already(
                        registrationSubject(
                            "这份登记已经设置为轮空一次。",
                            "固定组合的两份登记已经设置为轮空一次。"
                        )
                    )
                registration.absenceStatus != QueueAbsenceStatus.NONE ->
                    return reject("请先取消当前的暂缓一次、轮空一次或暂时离开。")
                postponeLocked -> return reject(ONLINE_REGISTRATION_POSTPONE_REASON)
            }
            QueueAction.SkipOneTurn(actualMachineId, registration.key)
        }
        RemoteQueueOperation.CANCEL_SKIP_ONE_TURN -> {
            when (registration.absenceStatus) {
                QueueAbsenceStatus.SKIP_ONE_TURN -> Unit
                QueueAbsenceStatus.NONE -> return already(
                    registrationSubject(
                        "这份登记已经取消轮空一次。",
                        "固定组合的两份登记已经取消轮空一次。"
                    )
                )
                QueueAbsenceStatus.DEFER_ONE_ROUND ->
                    return reject(
                        registrationSubject(
                            "这份登记已经设置为暂缓一次。",
                            "固定组合的两份登记已经设置为暂缓一次。"
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
            QueueAction.CancelSkipOneTurn(actualMachineId, registration.key)
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
                QueueAbsenceStatus.SKIP_ONE_TURN ->
                    return reject(
                        registrationSubject(
                            "这份登记已经设置为轮空一次。",
                            "固定组合的两份登记已经设置为轮空一次。"
                        )
                    )
            }
            QueueAction.CancelDeferOneRound(actualMachineId, registration.key)
        }
        RemoteQueueOperation.TEMPORARILY_LEAVE -> {
            when {
                !state.allowTemporaryLeave -> return reject("系统规则不允许暂时离开。")
                inPlayingPosition ->
                    return reject("处于游玩位置的登记不能暂时离开，请先在现场终端撤回至等待顺序。")
                registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY ->
                    return already(
                        registrationSubject(
                            "这份登记已经处于暂时离开状态。",
                            "固定组合的两份登记已经处于暂时离开状态。"
                        )
                    )
                registration.absenceStatus != QueueAbsenceStatus.NONE ->
                    return reject("请先取消当前的暂缓一次、轮空一次或暂时离开。")
                postponeLocked -> return reject(ONLINE_REGISTRATION_POSTPONE_REASON)
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
                QueueAbsenceStatus.SKIP_ONE_TURN ->
                    return reject(
                        registrationSubject(
                            "这份登记已经设置为轮空一次。",
                            "固定组合的两份登记已经设置为轮空一次。"
                        )
                    )
            }
            QueueAction.CancelTemporaryLeave(actualMachineId, registration.key)
        }
        RemoteQueueOperation.CHANGE_PLAY_PREFERENCE -> {
            if (state.machineCapacity(actualMachineId) == 1) {
                return reject("${state.machineLabel(actualMachineId)} 仅能容纳一人游玩，本次登记不能更改游玩偏好。")
            }
            val preference = command.preference ?: return reject("请选择本次游玩偏好。")
            if (registration.preference == preference && registration.fixedPartnerKey == null) {
                return already("这份登记已经使用所选游玩偏好。")
            }
            QueueAction.ChangePreference(actualMachineId, registration.key, preference)
        }
        RemoteQueueOperation.LEAVE_QUEUE -> QueueAction.LeaveQueue(actualMachineId, registration.key)
        RemoteQueueOperation.SET_PAIRED_MACHINE_QUEUEING -> {
            val enabled = command.alsoQueuesAtPairedMachine
                ?: return reject("未能确定是否允许系统在两台机台之间安排登记，请重新操作。")
            if (enabled) {
                if (state.machinePartners[actualMachineId] == null) {
                    return reject("系统规则不允许自动在两台机台之间安排登记。")
                }
                if (registration.fixedPartnerKey != null) {
                    return reject("固定组合只能在当前机台游玩。")
                }
                if (registration.isTemporary) {
                    return reject("临时登记只能在当前机台游玩。")
                }
            }
            QueueAction.SetPairedMachineQueueing(actualMachineId, registration.key, enabled)
        }
        RemoteQueueOperation.SET_ALLOWS_PASSING -> {
            val enabled = command.allowsPassing
                ?: return reject("未能确定是否允许后方的登记越过这份登记，请重新操作。")
            if (enabled) {
                if (state.machineCapacities[actualMachineId] == 1) {
                    return reject("当前机台仅能容纳一人游玩，不能设置这项选择。")
                }
                // 单人游玩 on its own, or a fixed pair (one choice for both registrations).
                if (registration.fixedPartnerKey == null && registration.preference != PlayPreference.SOLO) {
                    return reject("只有单人游玩的登记或固定组合可以允许后方的登记越过。")
                }
            }
            QueueAction.SetAllowsPassing(actualMachineId, registration.key, enabled)
        }
        RemoteQueueOperation.JOIN_QUEUE,
        RemoteQueueOperation.TRANSFER_MACHINE,
        RemoteQueueOperation.CHECK_IN,
        RemoteQueueOperation.CREATE_REGISTRATION,
        RemoteQueueOperation.CREATE_FIXED_PAIR,
        RemoteQueueOperation.RENAME_REGISTRATION,
        RemoteQueueOperation.REORDER_QUEUE -> error("operation handled above")
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
    val registrationIsPlaying = queue.playing.any { it.key == registration.key } &&
        updatedQueue.playing.any { it.key == registration.key }
    val detail = when (command.operation) {
        RemoteQueueOperation.DEFER_ONE_ROUND -> registrationSubject(
            "登记将暂缓一次。",
            "固定组合的两份登记将同时暂缓一次。"
        )
        RemoteQueueOperation.SKIP_ONE_TURN -> registrationSubject(
            "登记将轮空一次。下一次轮到时会移至队尾，计一次轮空，随后自动解除。",
            "固定组合的两份登记将同时轮空一次。下一次轮到时会一起移至队尾，计一次轮空，随后自动解除。"
        ) + skipOneTurnLimitNotice(
            registrationSubject("这份登记", "这组登记"),
            maxOf(registration.temporaryAwaySkippedTurns, fixedPartner?.temporaryAwaySkippedTurns ?: 0)
        ).orEmpty()
        RemoteQueueOperation.CANCEL_SKIP_ONE_TURN -> registrationSubject(
            "登记已取消轮空一次。",
            "固定组合的两份登记已同时取消轮空一次。"
        )
        RemoteQueueOperation.CANCEL_DEFER_ONE_ROUND -> registrationSubject(
            "登记已取消暂缓一次。",
            "固定组合的两份登记已同时取消暂缓一次。"
        )
        RemoteQueueOperation.TEMPORARILY_LEAVE -> registrationSubject(
            "登记已设为暂时离开。",
            "固定组合的两份登记已同时设为暂时离开。"
        )
        RemoteQueueOperation.CANCEL_TEMPORARY_LEAVE -> registrationSubject(
            "登记已取消暂时离开。",
            "固定组合的两份登记已同时取消暂时离开。"
        )
        RemoteQueueOperation.CHANGE_PLAY_PREFERENCE -> buildString {
            val preference = command.preference ?: registration.preference
            append("本次游玩偏好已改为“${remotePreferenceLabel(preference)}”")
            // Changed while it plays: this round stays as it is (maintainer, 2026-11-26).
            if (registrationIsPlaying) append("，本轮游玩结束后生效")
            append("。玩家资料中的默认游玩偏好没有改变。")
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
        RemoteQueueOperation.SET_PAIRED_MACHINE_QUEUEING -> {
            val partnerMachineId = state.machinePartners[actualMachineId]
            when {
                command.alsoQueuesAtPairedMachine != true ->
                    "已取消系统在 ${state.machineLabel(actualMachineId)} 和 ${state.machineLabel(partnerMachineId.orEmpty())} 之间的安排，" +
                        "这份登记只在 ${state.machineLabel(actualMachineId)} 排队。"
                // Turned on for a registration that is already waiting: it counts from the
                // round after the one it is waiting for.
                updatedRegistration?.skipsPairedMachineThisRound == true ->
                    "已允许系统在 ${state.machineLabel(actualMachineId)} 和 ${state.machineLabel(partnerMachineId.orEmpty())} 之间安排这份登记。" +
                        "这份登记游玩一轮之前，不会被安排到 ${state.machineLabel(partnerMachineId.orEmpty())}。"
                else ->
                    "已允许系统在 ${state.machineLabel(actualMachineId)} 和 ${state.machineLabel(partnerMachineId.orEmpty())} 之间安排这份登记，" +
                        "本轮游玩结束后生效。"
            }
        }
        RemoteQueueOperation.SET_ALLOWS_PASSING -> {
            val fromNextRound = if (registrationIsPlaying) "，本轮游玩结束后生效" else ""
            val subject = if (registration.fixedPartnerKey != null) "这组固定组合" else "这份登记"
            if (command.allowsPassing == true) {
                "已允许后方的登记越过${subject}与前方的登记共同游玩$fromNextRound。"
            } else {
                "${subject}不再允许后方的登记越过$fromNextRound。"
            }
        }
        RemoteQueueOperation.CHECK_IN -> "管理员已立即完成现场签到。"
        RemoteQueueOperation.CREATE_REGISTRATION -> "管理后台已创建登记。"
        RemoteQueueOperation.REORDER_QUEUE -> "管理后台已调整等待顺序。"
        else -> "队列操作已完成。"
    }
    // The player's answer to the passing tick box is remembered by their profile.
    val profileWithPassingChoice = command.allowsPassing
        ?.takeIf { command.operation == RemoteQueueOperation.SET_ALLOWS_PASSING }
        ?.let { enabled ->
            state.playerProfiles.firstOrNull { it.id == registration.playerProfileId }
                ?.let { profile -> profile.rememberingPassingAnswer(registration, enabled, appliedAtMillis) }
        }
    return RemoteQueueOperationDecision.Apply(
        state = state.copy(
                queues = execution.state.queues,
                pairedMachineOrders = execution.state.pairedMachineOrders,
                pairedArrangements = execution.state.pairedArrangements
            ),
        // With an empty playing position the change can let the next group in at once. On the
        // terminal the confirmation says so beforehand; a player far away is told in the answer.
        detail = detail + if (queue.playing.isEmpty() && updatedQueue.playing.isNotEmpty()) {
            updatedQueue.playing.joinToString(
                prefix = "现在由",
                postfix = "进入游玩位置 $actualMachineId。",
                separator = "、"
            ) { "“${it.displayId}”" }
        } else {
            ""
        },
        changedMachineIds = setOf(actualMachineId),
        action = action,
        updatedProfile = profileWithPassingChoice
    )
}

private fun remotePreferenceLabel(preference: PlayPreference): String = when (preference) {
    PlayPreference.SOLO -> "单人游玩"
    PlayPreference.OPEN_TO_JOIN -> "允许他人加入"
}

private fun fixedPairAbsenceRetentionDetail(registration: Registration): String =
    when (registration.absenceStatus) {
        QueueAbsenceStatus.DEFER_ONE_ROUND ->
            "两份登记的暂缓一次不会因解除固定组合而取消。"
        QueueAbsenceStatus.SKIP_ONE_TURN ->
            "两份登记的轮空一次不会因解除固定组合而取消。"
        QueueAbsenceStatus.TEMPORARILY_AWAY ->
            "两份登记当前的暂时离开状态和已轮空 ${registration.temporaryAwaySkippedTurns} 次不会因解除固定组合而清除。"
        QueueAbsenceStatus.NONE -> ""
    }

private fun remainingPartnerAbsenceDetail(partner: Registration): String =
    when (partner.absenceStatus) {
        QueueAbsenceStatus.DEFER_ONE_ROUND ->
            "另一份登记仍将暂缓一次，并会在下一次轮到后自动解除。"
        QueueAbsenceStatus.SKIP_ONE_TURN ->
            "另一份登记仍将轮空一次，并会在下一次轮到时移至队尾。"
        QueueAbsenceStatus.TEMPORARILY_AWAY ->
            "另一份登记仍保持暂时离开和已轮空 ${partner.temporaryAwaySkippedTurns} 次，返回后需要手动取消。"
        QueueAbsenceStatus.NONE -> ""
    }

private const val MAX_REGISTRATIONS_PER_MACHINE = 20

/**
 * 轮空一次 shares the count of skipped turns with 暂时离开, and three are the limit. Few skip three
 * times in a row, so the limit is only stressed once it is near (maintainer, 2026-11-25): when this
 * skip will be the third, and when the next turn removes the registration. Null otherwise.
 */
internal fun skipOneTurnLimitNotice(subject: String, skippedTurns: Int): String? = when {
    skippedTurns >= 3 ->
        "${subject}已轮空 $skippedTurns 次。下一次轮到时，登记会被自动移除，而不会移至队尾。"
    skippedTurns == 2 ->
        "${subject}已轮空 2 次，这次将是第 3 次。此后再轮到时，如果仍设置了轮空一次或暂时离开，登记会被自动移除。"
    else -> null
}

/** Why 暂缓一次 is grey (2026-11-25); the same sentence on the terminal, website and Bot. */
internal const val DEFER_ONE_ROUND_USED_REASON = "这份登记已暂缓过一次，游玩一轮之后才能再次暂缓。"

/** Why a checked-in online registration that has not played cannot be postponed. */
internal const val ONLINE_REGISTRATION_POSTPONE_REASON =
    "已签到的线上登记在游玩一轮之前不能延后。"

/**
 * The profile after its player answered a passing tick box for [registration] from afar, or
 * null when nothing about it changes. The answer for a 单人游玩 registration is remembered for
 * the next time; the choice of a fixed pair is made by two and is not, but ticking it still
 * records that the explanation was shown.
 */
private fun PlayerProfile.rememberingPassingAnswer(
    registration: Registration,
    enabled: Boolean,
    atMillis: Long
): PlayerProfile? {
    val updated = if (registration.fixedPartnerKey != null) {
        if (enabled) withPassingIntroduced(atMillis) else this
    } else {
        withPassingChoice(enabled, atMillis)
    }
    return updated.takeIf { it != this }
}
