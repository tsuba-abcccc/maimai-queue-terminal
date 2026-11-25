package com.abcccc.maimaiqueue

/**
 * Management actions mirror the public queue controls of the on-site terminal.
 * Website and QQ Bot operations deliberately do not use this protocol.
 */
internal enum class ManagementQueueAction {
    ADD_TEMPORARY_REGISTRATION,
    ADD_PROFILE_REGISTRATION,
    FINISH_ROUND,
    END_ROUND_ONLY,
    REMOVE_CURRENT_ROUND_AND_START_NEXT,
    ENTER_PLAYING_POSITION,
    RESTART_PLAYING_TIMER,
    RESTART_PENDING_CHECK_IN_TIMERS,
    RESTART_MACHINE_TIMERS,
    RETURN_PLAYING_TO_WAITING_FRONT,
    MOVE_WAITING_REGISTRATION_INTO_CURRENT_ROUND,
    ADVANCE_TO_WAITING_POSITION,
    DEFER_ONE_ROUND,
    CANCEL_DEFER_ONE_ROUND,
    SKIP_ONE_TURN,
    CANCEL_SKIP_ONE_TURN,
    TEMPORARILY_LEAVE,
    CANCEL_TEMPORARY_LEAVE,
    CHANGE_PREFERENCE,
    CREATE_FIXED_PAIR,
    CREATE_FIXED_PAIR_WITH_REGISTRATION,
    RELEASE_FIXED_PAIR,
    RENAME_REGISTRATION,
    CLAIM_WITH_PLAYER_PROFILE,
    MARK_NO_SHOW,
    CHECK_IN,
    REMOVE_REGISTRATIONS,
    MOVE_WAITING_POSITION,
    REPLACE_WAITING_POSITIONS,
    REPLACE_REGISTRATION_ORDER,
    TRANSFER_REGISTRATIONS,
    SET_PAIRED_MACHINE_QUEUEING
}

internal data class ManagementQueueActionCommand(
    override val commandId: String,
    val createdAtMillis: Long,
    val queueId: String,
    val queueRevision: Long,
    val machineConfigurationRevision: Long,
    val expectedPolicyRevision: Long? = null,
    val action: ManagementQueueAction,
    val machineId: String,
    val machineStableId: String,
    val expectedPlayingRegistrationIds: List<String>,
    val expectedWaitingPositionRegistrationIds: List<List<String>>,
    val registrationIds: List<String> = emptyList(),
    val profileId: String? = null,
    val friendProfileId: String? = null,
    val displayId: String? = null,
    val preference: PlayPreference? = null,
    val targetMachineId: String? = null,
    val targetMachineStableId: String? = null,
    val sourcePositionIndex: Int? = null,
    val destinationPositionIndex: Int? = null,
    val desiredWaitingPositionRegistrationIds: List<List<String>>? = null,
    val desiredRegistrationOrder: List<String>? = null,
    val noShowResolution: NoShowResolution? = null,
    val startNextWhenPlayingBecomesEmpty: Boolean = true,
    val advanceWhenPlayingEmpty: Boolean = false,
    val reason: String? = null,
    /**
     * For [ManagementQueueAction.SET_PAIRED_MACHINE_QUEUEING] the state asked for; for a new
     * profile registration the answer of a profile that asks every time. Null when absent.
     */
    val alsoQueuesAtPairedMachine: Boolean? = null
) : RemoteTerminalCommand

internal sealed interface ManagementQueueActionDecision {
    data class Apply(
        val state: RemoteQueueExecutionState,
        val detail: String,
        val changedMachineIds: Set<String>,
        val action: QueueAction,
        val updatedProfile: PlayerProfile? = null
    ) : ManagementQueueActionDecision

    data class AlreadyApplied(val detail: String) : ManagementQueueActionDecision
    data class Reject(val detail: String) : ManagementQueueActionDecision
}

internal fun decideManagementQueueAction(
    command: ManagementQueueActionCommand,
    state: RemoteQueueExecutionState,
    appliedAtMillis: Long = System.currentTimeMillis(),
    appliedCommandIds: Set<String> = emptySet()
): ManagementQueueActionDecision {
    fun reject(detail: String) = ManagementQueueActionDecision.Reject(detail)
    if (command.commandId in appliedCommandIds) {
        return ManagementQueueActionDecision.AlreadyApplied(
            "这项管理后台队列操作已经由现场终端执行。"
        )
    }
    if (command.queueId != state.queueId) {
        return reject("现场已开始新的队列，请刷新管理后台后再操作。")
    }
    if (command.machineConfigurationRevision != state.machineConfigurationRevision) {
        return reject("机台配置已经更新，请刷新管理后台后再操作。")
    }
    if (
        command.expectedPolicyRevision != null &&
        command.expectedPolicyRevision != state.managementPolicyRevision
    ) {
        return reject("终端敏感策略已经更新，请刷新管理后台后再操作。")
    }
    if (state.machineStableIds[command.machineId] != command.machineStableId) {
        return reject("目标机台已经变化，请刷新管理后台后再操作。")
    }
    if (
        command.targetMachineId != null &&
        state.machineStableIds[command.targetMachineId] != command.targetMachineStableId
    ) {
        return reject("要转入的机台已经变化，请刷新管理后台后再操作。")
    }
    val queue = state.queues[command.machineId] ?: return reject("目标机台不存在。")
    fun publicId(registration: Registration): String =
        publicRegistrationId(state.queueId, registration.key)

    val currentPlayingIds = queue.playing.map(::publicId)
    val currentWaitingPositionIds = queue.waitingPositions().map { position ->
        position.map(::publicId)
    }
    if (
        currentPlayingIds != command.expectedPlayingRegistrationIds ||
        currentWaitingPositionIds != command.expectedWaitingPositionRegistrationIds
    ) {
        return reject("现场队列位置已经变化，请刷新管理后台后再操作。")
    }

    val registrationsById = queue.allRegistrations.associateBy(::publicId)
    fun selectedRegistrations(required: Boolean = true): List<Registration>? {
        if (command.registrationIds.distinct().size != command.registrationIds.size) return null
        val selected = command.registrationIds.mapNotNull(registrationsById::get)
        return selected.takeIf { it.size == command.registrationIds.size && (!required || it.isNotEmpty()) }
    }

    fun resolveProfilePreference(profile: PlayerProfile): PlayPreference? =
        if (state.machineCapacity(command.machineId) == 1) {
            PlayPreference.SOLO
        } else {
            when (profile.defaultPreference) {
                ProfilePlayPreference.ASK_EVERY_TIME -> command.preference
                ProfilePlayPreference.SOLO -> PlayPreference.SOLO
                ProfilePlayPreference.OPEN_TO_JOIN -> PlayPreference.OPEN_TO_JOIN
            }
        }

    var addedRegistrationCount = 0
    var profileUsed: PlayerProfile? = null
    val queueAction: QueueAction = when (command.action) {
        ManagementQueueAction.ADD_TEMPORARY_REGISTRATION -> {
            if (!state.acceptingNewRegistrations) return reject("现场当前未使用登记排队。")
            val displayId = command.displayId?.trim()?.takeIf { it.isNotEmpty() }
                ?: return reject("请输入临时登记名称。")
            val preference = if (state.machineCapacity(command.machineId) == 1) {
                PlayPreference.SOLO
            } else {
                command.preference ?: return reject("请选择本次游玩偏好。")
            }
            addedRegistrationCount = 1
            QueueAction.AddRegistrations(
                command.machineId,
                listOf(
                    Registration(
                        key = state.nextRegistrationKey,
                        displayId = displayId,
                        preference = preference,
                        createdAtMillis = appliedAtMillis,
                        originatingCommandId = command.commandId
                    )
                ),
                RegistrationPlacement.ADVANCE_IF_UNAMBIGUOUS
            )
        }

        ManagementQueueAction.ADD_PROFILE_REGISTRATION -> {
            if (!state.acceptingNewRegistrations) return reject("现场当前未使用登记排队。")
            val profile = command.profileId?.let { profileId ->
                state.playerProfiles.firstOrNull { it.id == profileId }
            } ?: return reject("玩家资料已不存在。")
            val preference = resolveProfilePreference(profile)
                ?: return reject("请选择本次游玩偏好。")
            val queuesAtPairedMachine = state.resolvePairedMachineQueueing(
                machineId = command.machineId,
                requested = command.alsoQueuesAtPairedMachine
            ) ?: return reject("一对机台的设置已经变化，请重新确认后再创建。")
            profileUsed = profile
            addedRegistrationCount = 1
            QueueAction.AddRegistrations(
                command.machineId,
                listOf(
                    Registration(
                        key = state.nextRegistrationKey,
                        displayId = profile.nickname,
                        preference = preference,
                        isTemporary = false,
                        createdAtMillis = appliedAtMillis,
                        gender = profile.gender,
                        playerProfileId = profile.id,
                        alsoQueuesAtPairedMachine = queuesAtPairedMachine,
                        originatingCommandId = command.commandId
                    )
                ),
                RegistrationPlacement.ADVANCE_IF_UNAMBIGUOUS
            )
        }

        ManagementQueueAction.FINISH_ROUND -> QueueAction.FinishRound(command.machineId)
        ManagementQueueAction.END_ROUND_ONLY -> QueueAction.EndRoundOnly(command.machineId)
        ManagementQueueAction.REMOVE_CURRENT_ROUND_AND_START_NEXT ->
            QueueAction.RemoveCurrentRoundAndStartNext(command.machineId)
        ManagementQueueAction.ENTER_PLAYING_POSITION ->
            QueueAction.EnterPlayingPosition(command.machineId)
        ManagementQueueAction.RESTART_PLAYING_TIMER ->
            QueueAction.RestartPlayingTimer(command.machineId)
        ManagementQueueAction.RESTART_PENDING_CHECK_IN_TIMERS ->
            QueueAction.RestartPendingCheckInTimers(command.machineId)
        ManagementQueueAction.RESTART_MACHINE_TIMERS ->
            QueueAction.RestartMachineTimers(command.machineId)

        ManagementQueueAction.RETURN_PLAYING_TO_WAITING_FRONT -> {
            val selected = selectedRegistrations() ?: return reject("游玩位置已经变化，请刷新管理后台后再操作。")
            QueueAction.ReturnPlayingToWaitingFront(
                command.machineId,
                selected.mapTo(mutableSetOf(), Registration::key)
            )
        }

        ManagementQueueAction.MOVE_WAITING_REGISTRATION_INTO_CURRENT_ROUND -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份等待登记。")
            QueueAction.MoveWaitingRegistrationIntoCurrentRound(command.machineId, registration.key)
        }

        ManagementQueueAction.ADVANCE_TO_WAITING_POSITION -> {
            val selected = selectedRegistrations() ?: return reject("等待位置已经变化。")
            QueueAction.AdvanceToWaitingPosition(
                command.machineId,
                selected.mapTo(mutableSetOf(), Registration::key)
            )
        }

        ManagementQueueAction.DEFER_ONE_ROUND -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份登记。")
            if (queue.playing.any { it.key == registration.key }) {
                return reject("处于游玩位置的登记不能暂缓一次，请先撤回至等待顺序。")
            }
            QueueAction.DeferOneRound(command.machineId, registration.key)
        }

        ManagementQueueAction.CANCEL_DEFER_ONE_ROUND -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份登记。")
            QueueAction.CancelDeferOneRound(command.machineId, registration.key)
        }

        ManagementQueueAction.SKIP_ONE_TURN -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份登记。")
            if (queue.playing.any { it.key == registration.key }) {
                return reject("处于游玩位置的登记不能轮空一次，请先撤回至等待顺序。")
            }
            QueueAction.SkipOneTurn(command.machineId, registration.key)
        }

        ManagementQueueAction.CANCEL_SKIP_ONE_TURN -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份登记。")
            QueueAction.CancelSkipOneTurn(command.machineId, registration.key)
        }

        ManagementQueueAction.TEMPORARILY_LEAVE -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份登记。")
            if (queue.playing.any { it.key == registration.key }) {
                return reject("处于游玩位置的登记不能暂时离开，请先撤回至等待顺序。")
            }
            QueueAction.TemporarilyLeave(command.machineId, registration.key)
        }

        ManagementQueueAction.CANCEL_TEMPORARY_LEAVE -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份登记。")
            QueueAction.CancelTemporaryLeave(command.machineId, registration.key)
        }

        ManagementQueueAction.CHANGE_PREFERENCE -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份登记。")
            QueueAction.ChangePreference(
                command.machineId,
                registration.key,
                command.preference ?: return reject("请选择本次游玩偏好。")
            )
        }

        ManagementQueueAction.CREATE_FIXED_PAIR -> {
            val selected = selectedRegistrations()
                ?.takeIf { it.size == 2 } ?: return reject("请选择两份等待登记。")
            QueueAction.CreateFixedPair(
                command.machineId,
                selected[0].key,
                selected[1].key,
                advanceWhenPlayingEmpty = command.advanceWhenPlayingEmpty
            )
        }

        ManagementQueueAction.CREATE_FIXED_PAIR_WITH_REGISTRATION -> {
            if (!state.acceptingNewRegistrations) return reject("现场当前未使用登记排队。")
            val target = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择要组成固定组合的登记。")
            val friendProfile = command.friendProfileId?.let { profileId ->
                state.playerProfiles.firstOrNull { it.id == profileId }
                    ?: return reject("朋友的玩家资料已不存在。")
            }
            val friendDisplayId = friendProfile?.nickname
                ?: command.displayId?.trim()?.takeIf { it.isNotEmpty() }
                ?: return reject("请输入朋友的登记名称。")
            val friendPreference = if (state.machineCapacity(command.machineId) == 1) {
                PlayPreference.SOLO
            } else if (friendProfile != null) {
                resolveProfilePreference(friendProfile)
                    ?: return reject("请选择朋友本次的游玩偏好。")
            } else {
                command.preference ?: PlayPreference.SOLO
            }
            profileUsed = friendProfile
            addedRegistrationCount = 1
            QueueAction.CreateFixedPairWithRegistration(
                command.machineId,
                target.key,
                Registration(
                    key = state.nextRegistrationKey,
                    displayId = friendDisplayId,
                    preference = friendPreference,
                    isTemporary = friendProfile == null,
                    createdAtMillis = appliedAtMillis,
                    gender = friendProfile?.gender,
                    playerProfileId = friendProfile?.id,
                    originatingCommandId = command.commandId
                ),
                advanceWhenPlayingEmpty = command.advanceWhenPlayingEmpty
            )
        }

        ManagementQueueAction.RELEASE_FIXED_PAIR -> {
            val selected = selectedRegistrations()
                ?.takeIf { it.size == 2 } ?: return reject("固定组合位置已经变化。")
            val first = selected.first()
            if (first.fixedPartnerKey !in selected.map(Registration::key)) {
                return reject("所选位置已不是固定组合。")
            }
            QueueAction.ChangePreference(
                command.machineId,
                first.key,
                PlayPreference.OPEN_TO_JOIN
            )
        }

        ManagementQueueAction.RENAME_REGISTRATION -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份登记。")
            val displayId = command.displayId?.trim()?.takeIf { it.isNotEmpty() }
                ?: return reject("请输入新的登记名称。")
            QueueAction.RenameRegistration(command.machineId, registration.key, displayId)
        }

        ManagementQueueAction.CLAIM_WITH_PLAYER_PROFILE -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份临时登记。")
            val profile = command.profileId?.let { profileId ->
                state.playerProfiles.firstOrNull { it.id == profileId }
            } ?: return reject("玩家资料已不存在。")
            profileUsed = profile
            QueueAction.ClaimWithPlayerProfile(
                command.machineId,
                registration.key,
                profile.id,
                profile.nickname,
                profile.gender,
                preferenceOverride = command.preference
            )
        }

        ManagementQueueAction.MARK_NO_SHOW -> {
            val selected = selectedRegistrations() ?: return reject("所选位置已经变化。")
            QueueAction.MarkNoShow(
                command.machineId,
                selected.mapTo(mutableSetOf(), Registration::key),
                command.noShowResolution ?: return reject("请选择未到场处理方式。"),
                command.startNextWhenPlayingBecomesEmpty
            )
        }

        ManagementQueueAction.CHECK_IN -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份线上登记。")
            QueueAction.CheckIn(command.machineId, registration.key)
        }

        ManagementQueueAction.SET_PAIRED_MACHINE_QUEUEING -> {
            val registration = selectedRegistrations()?.singleOrNull()
                ?: return reject("请选择一份登记。")
            val enabled = command.alsoQueuesAtPairedMachine
                ?: return reject("未能确定是否允许系统在两台机台之间安排登记，请重新操作。")
            if (registration.alsoQueuesAtPairedMachine == enabled) {
                return ManagementQueueActionDecision.AlreadyApplied(
                    if (enabled) "这份登记已经允许系统在两台机台之间安排。" else "这份登记当前未允许系统在两台机台之间安排。"
                )
            }
            if (enabled) {
                if (state.machinePartners[command.machineId] == null) {
                    return reject("系统规则不允许自动在两台机台之间安排登记。")
                }
                if (registration.fixedPartnerKey != null) {
                    return reject("固定组合只能在当前机台游玩。")
                }
                if (registration.isTemporary) {
                    return reject("临时登记只能在当前机台游玩。")
                }
            }
            QueueAction.SetPairedMachineQueueing(command.machineId, registration.key, enabled)
        }

        ManagementQueueAction.REMOVE_REGISTRATIONS -> {
            val selected = selectedRegistrations() ?: return reject("所选登记已经变化。")
            QueueAction.RemoveRegistrations(
                command.machineId,
                selected.mapTo(mutableSetOf(), Registration::key)
            )
        }

        ManagementQueueAction.MOVE_WAITING_POSITION -> QueueAction.MoveWaitingPosition(
            command.machineId,
            command.sourcePositionIndex ?: return reject("没有指定原等待位置。"),
            command.destinationPositionIndex ?: return reject("没有指定目标等待位置。")
        )

        ManagementQueueAction.REPLACE_WAITING_POSITIONS -> {
            val desiredPositions = command.desiredWaitingPositionRegistrationIds
                ?: return reject("没有提交新的等待位置顺序。")
            if (
                desiredPositions.map(List<String>::toSet).toSet() !=
                currentWaitingPositionIds.map(List<String>::toSet).toSet() ||
                desiredPositions.any { it.isEmpty() }
            ) {
                return reject("新的等待位置必须完整保留当前每个位置。")
            }
            val desiredIds = currentPlayingIds + desiredPositions.flatten()
            val reordered = desiredIds.mapNotNull(registrationsById::get)
            if (reordered.size != queue.registrationCount) {
                return reject("新的等待位置包含不存在的登记。")
            }
            QueueAction.ReplaceOrder(command.machineId, reordered)
        }

        ManagementQueueAction.REPLACE_REGISTRATION_ORDER -> {
            val desiredIds = command.desiredRegistrationOrder
                ?: return reject("没有提交新的登记顺序。")
            val currentIds = currentPlayingIds + currentWaitingPositionIds.flatten()
            if (
                desiredIds.take(currentPlayingIds.size) != currentPlayingIds ||
                desiredIds.toSet() != currentIds.toSet() ||
                desiredIds.size != currentIds.size
            ) {
                return reject("新的登记顺序与当前队列不一致。")
            }
            val reordered = desiredIds.mapNotNull(registrationsById::get)
            if (reordered.size != queue.registrationCount) {
                return reject("新的登记顺序包含不存在的登记。")
            }
            QueueAction.ReplaceOrder(command.machineId, reordered)
        }

        ManagementQueueAction.TRANSFER_REGISTRATIONS -> {
            val selected = selectedRegistrations() ?: return reject("所选登记已经变化。")
            val targetMachineId = command.targetMachineId ?: return reject("请选择目标机台。")
            QueueAction.TransferRegistrations(
                command.machineId,
                targetMachineId,
                selected.mapTo(mutableSetOf(), Registration::key)
            )
        }
    }

    val executed = state.executeQueueAction(
        action = queueAction,
        origin = QueueActionOrigin.MANAGEMENT_APP,
        atMillis = appliedAtMillis
    )
    val execution = executed as? QueueActionExecution.Applied ?: return reject(
        when ((executed as? QueueActionExecution.Rejected)?.failure?.code) {
            QueueActionFailureCode.NO_SHOW_REMOVAL_ONLY -> ONLINE_REGISTRATION_NO_SHOW_REMOVAL_ONLY
            QueueActionFailureCode.DEFER_ONE_ROUND_ALREADY_USED -> DEFER_ONE_ROUND_USED_REASON
            else -> "现场终端未执行这项队列操作，请按当前队列重新确认。"
        }
    )
    if (execution.state.queues == state.queues) {
        return reject("队列状态已经变化，这项操作没有执行。")
    }
    val changedMachineIds = execution.state.queues.keys.filterTo(mutableSetOf()) { machineId ->
        execution.state.queues[machineId] != state.queues[machineId]
    }
    val updatedProfile = profileUsed?.takeIf {
        (it.lastUsedAtMillis ?: Long.MIN_VALUE) < appliedAtMillis
    }?.recordUsage(maxOf(appliedAtMillis, profileUsed!!.updatedAtMillis + 1L))
    return ManagementQueueActionDecision.Apply(
        state = state.copy(
            queues = execution.state.queues,
            pairedMachineOrders = execution.state.pairedMachineOrders,
            pairedArrangements = execution.state.pairedArrangements,
            nextRegistrationKey = state.nextRegistrationKey + addedRegistrationCount
        ),
        detail = managementQueueActionDetail(command.action, command.machineId),
        changedMachineIds = changedMachineIds,
        action = queueAction,
        updatedProfile = updatedProfile
    )
}

/** Why a no-show of a checked-in online registration that has not played yet offers one outcome. */
internal const val ONLINE_REGISTRATION_NO_SHOW_REMOVAL_ONLY =
    "线上登记第一次轮到时未到场，只能移除登记。"

/** Under the two outcomes that are not offered in that case. */
internal const val ONLINE_REGISTRATION_NO_SHOW_CHOICE_UNAVAILABLE = "线上登记第一次轮到时不提供。"

internal fun managementQueueActionDetail(action: ManagementQueueAction, machineId: String): String =
    when (action) {
        ManagementQueueAction.ADD_TEMPORARY_REGISTRATION -> "已在 机台 $machineId 创建临时登记。"
        ManagementQueueAction.ADD_PROFILE_REGISTRATION -> "已在 机台 $machineId 创建玩家登记。"
        ManagementQueueAction.FINISH_ROUND -> "已结束本轮并开始下一轮。"
        ManagementQueueAction.END_ROUND_ONLY -> "已仅结束本轮，游玩位置保持空缺。"
        ManagementQueueAction.REMOVE_CURRENT_ROUND_AND_START_NEXT -> "已移除本轮登记并开始下一轮。"
        ManagementQueueAction.ENTER_PLAYING_POSITION -> "等待位置已进入游玩位置。"
        ManagementQueueAction.RESTART_PLAYING_TIMER -> "已重新开始本轮游玩计时。"
        ManagementQueueAction.RESTART_PENDING_CHECK_IN_TIMERS -> "已重新开始待签到计时。"
        ManagementQueueAction.RESTART_MACHINE_TIMERS -> "已重新开始机台全部计时。"
        ManagementQueueAction.RETURN_PLAYING_TO_WAITING_FRONT -> "游玩登记已撤回至等待顺序。"
        ManagementQueueAction.MOVE_WAITING_REGISTRATION_INTO_CURRENT_ROUND -> "等待登记已补入当前游玩位置。"
        ManagementQueueAction.ADVANCE_TO_WAITING_POSITION -> "指定等待位置已进入游玩位置。"
        ManagementQueueAction.DEFER_ONE_ROUND -> "登记将暂缓一次。"
        ManagementQueueAction.CANCEL_DEFER_ONE_ROUND -> "已取消暂缓一次。"
        ManagementQueueAction.SKIP_ONE_TURN -> "登记将轮空一次。"
        ManagementQueueAction.CANCEL_SKIP_ONE_TURN -> "已取消轮空一次。"
        ManagementQueueAction.TEMPORARILY_LEAVE -> "已设为暂时离开。"
        ManagementQueueAction.CANCEL_TEMPORARY_LEAVE -> "已取消暂时离开。"
        ManagementQueueAction.CHANGE_PREFERENCE -> "已更改本次游玩偏好。"
        ManagementQueueAction.CREATE_FIXED_PAIR -> "已将两份登记组成固定组合。"
        ManagementQueueAction.CREATE_FIXED_PAIR_WITH_REGISTRATION -> "已创建朋友登记并组成固定组合。"
        ManagementQueueAction.RELEASE_FIXED_PAIR -> "已解除固定组合。"
        ManagementQueueAction.RENAME_REGISTRATION -> "已修改登记名称。"
        ManagementQueueAction.CLAIM_WITH_PLAYER_PROFILE -> "已将临时登记与玩家资料关联。"
        ManagementQueueAction.MARK_NO_SHOW -> "已完成未到场处理。"
        ManagementQueueAction.CHECK_IN -> "已立即完成现场签到。"
        ManagementQueueAction.REMOVE_REGISTRATIONS -> "已移除所选登记。"
        ManagementQueueAction.MOVE_WAITING_POSITION,
        ManagementQueueAction.REPLACE_WAITING_POSITIONS -> "已调整等待顺序。"
        ManagementQueueAction.REPLACE_REGISTRATION_ORDER -> "已调整等待顺序。"
        ManagementQueueAction.TRANSFER_REGISTRATIONS -> "已将所选登记转至其他机台。"
        ManagementQueueAction.SET_PAIRED_MACHINE_QUEUEING -> "已更改是否允许系统在两台机台之间安排。"
    }
