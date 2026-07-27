package com.abcccc.maimaiqueue

internal data class MobileProfileCompletion(
    val qqNumber: String,
    val qqVisibility: QqVisibility,
    val notificationPreferences: QueueNotificationPreferences,
    val setupVersion: Int
)

internal data class MobileNewPlayerProfile(
    val nickname: String,
    val gender: PlayerGender,
    val defaultPreference: ProfilePlayPreference,
    val qqNumber: String,
    val qqVisibility: QqVisibility,
    val notificationPreferences: QueueNotificationPreferences,
    val setupVersion: Int
)

internal data class MobileDeviceRegistrationCommand(
    override val commandId: String,
    val createdAtMillis: Long,
    val queueId: String,
    val machineId: String,
    val actorQq: String,
    val preference: PlayPreference,
    val profileId: String,
    val expectedProfileRevision: Long?,
    val completion: MobileProfileCompletion?,
    val newProfile: MobileNewPlayerProfile?
) : RemoteTerminalCommand {
    val createsProfile: Boolean
        get() = newProfile != null
}

internal sealed interface MobileDeviceRegistrationDecision {
    data class Apply(
        val state: RemoteQueueExecutionState,
        val profileToPersist: PlayerProfile?,
        val changedMachineId: String,
        val needsAvailabilityConfirmation: Boolean,
        val detail: String
    ) : MobileDeviceRegistrationDecision

    data class AlreadyApplied(val detail: String) : MobileDeviceRegistrationDecision
    data class Reject(val detail: String) : MobileDeviceRegistrationDecision
}

internal fun decideMobileDeviceRegistration(
    command: MobileDeviceRegistrationCommand,
    state: RemoteQueueExecutionState
): MobileDeviceRegistrationDecision {
    fun reject(detail: String) = MobileDeviceRegistrationDecision.Reject(detail)
    val exactRegistration = state.queues.values.asSequence()
        .flatMap { it.allRegistrations.asSequence() }
        .firstOrNull { it.originatingCommandId == command.commandId }
    if (exactRegistration != null) {
        return MobileDeviceRegistrationDecision.AlreadyApplied(
            "已通过移动设备加入排队。"
        )
    }
    if (command.queueId != state.queueId) {
        return reject("排队批次已经变化，请在终端重新打开移动设备登记。")
    }
    if (!state.websiteRemoteEnabled) {
        return reject("网站同步已关闭，暂不能使用移动设备登记。")
    }
    if (!state.acceptingNewRegistrations) {
        return reject("现场当前没有使用登记排队。")
    }
    val queue = state.queues[command.machineId] ?: return reject("目标机台不存在。")
    if (state.machineStatuses[command.machineId]?.isOperational != true) {
        return reject("目标机台已停止使用，暂不能加入。")
    }
    if (queue.registrationCount >= MAX_MOBILE_REGISTRATIONS_PER_MACHINE) {
        return reject("目标机台的登记已满。")
    }

    val existingProfile = state.playerProfiles.firstOrNull { it.id == command.profileId }
    val profileBeforeUsage = if (command.createsProfile) {
        val requested = command.newProfile ?: return reject("新玩家资料内容不完整。")
        if (existingProfile == null) {
            PlayerProfile(
                id = command.profileId,
                nickname = requested.nickname.trim(),
                gender = requested.gender,
                defaultPreference = requested.defaultPreference,
                qqNumber = requested.qqNumber,
                qqVisibility = requested.qqVisibility,
                notificationPreferences = requested.notificationPreferences,
                setupVersion = requested.setupVersion,
                revision = 1L,
                createdAtMillis = command.createdAtMillis,
                updatedAtMillis = command.createdAtMillis
            ).withCanonicalContact()
        } else {
            val createdByThisCommand = existingProfile.nickname == requested.nickname.trim() &&
                existingProfile.gender == requested.gender &&
                existingProfile.defaultPreference == requested.defaultPreference &&
                existingProfile.normalizedQqNumber() == requested.qqNumber &&
                existingProfile.qqVisibility == requested.qqVisibility &&
                existingProfile.notificationPreferences == requested.notificationPreferences &&
                existingProfile.setupVersion >= requested.setupVersion &&
                (existingProfile.lastUsedAtMillis ?: Long.MIN_VALUE) >= command.createdAtMillis
            if (!createdByThisCommand) {
                return reject("玩家资料编号已经用于其他资料，请重新扫码。")
            }
            existingProfile
        }
    } else {
        val profile = existingProfile ?: return reject("所选玩家资料已经不存在。")
        val expectedRevision = command.expectedProfileRevision
            ?: return reject("所选玩家资料缺少版本信息，请重新扫码。")
        val completion = command.completion
        when {
            profile.revision == expectedRevision && completion != null -> {
                if (profile.hasCompleteRequiredDetails && profile.hasValidContact) {
                    return reject("这份玩家资料已经完整，不能通过登记页面修改。")
                }
                profile.copy(
                    qqNumber = completion.qqNumber,
                    qqVisibility = completion.qqVisibility,
                    notificationPreferences = completion.notificationPreferences,
                    setupVersion = completion.setupVersion,
                    revision = profile.revision + 1L,
                    updatedAtMillis = maxOf(
                        command.createdAtMillis,
                        profile.updatedAtMillis + 1L
                    )
                ).withCanonicalContact()
            }

            profile.revision == expectedRevision && completion == null -> {
                if (!profile.hasCompleteRequiredDetails || !profile.hasValidContact) {
                    return reject("请先补全玩家资料后再加入排队。")
                }
                profile
            }

            completion != null &&
                profile.normalizedQqNumber() == completion.qqNumber &&
                profile.qqVisibility == completion.qqVisibility &&
                profile.notificationPreferences == completion.notificationPreferences &&
                profile.setupVersion >= completion.setupVersion &&
                (profile.lastUsedAtMillis ?: Long.MIN_VALUE) >= command.createdAtMillis -> profile

            completion == null &&
                profile.hasCompleteRequiredDetails &&
                profile.hasValidContact &&
                (profile.lastUsedAtMillis ?: Long.MIN_VALUE) >= command.createdAtMillis -> profile

            else -> return reject("玩家资料已经更新，请重新扫码并确认。")
        }
    }

    if (!profileBeforeUsage.hasValidContact ||
        profileBeforeUsage.normalizedQqNumber() != command.actorQq
    ) {
        return reject("玩家资料绑定的 QQ 已经变化，请重新扫码。")
    }
    val duplicateProfile = state.playerProfiles.any { profile ->
        profile.id != profileBeforeUsage.id &&
            !profile.isContactlessLegacyAliasOf(profileBeforeUsage) && (
            profile.nickname.equals(profileBeforeUsage.nickname, ignoreCase = true) ||
                profile.normalizedQqNumber() == profileBeforeUsage.normalizedQqNumber()
            )
    }
    if (duplicateProfile) {
        return reject("昵称或 QQ 已用于其他玩家资料。")
    }
    val alreadyRegistered = state.queues.values.any { currentQueue ->
        currentQueue.allRegistrations.any { registration ->
            registration.playerProfileId == profileBeforeUsage.id ||
                registration.displayId.equals(profileBeforeUsage.nickname, ignoreCase = true)
        }
    }
    if (alreadyRegistered) {
        return reject("这名玩家已经有一份正在排队的登记。")
    }
    val expectedPreference = profileBeforeUsage.defaultPreference.toPlayPreferenceOrNull()
        ?: command.preference
    if (command.preference != expectedPreference) {
        return reject("玩家资料的默认游玩偏好已经变化，请重新确认。")
    }

    val usedProfile = if (
        (profileBeforeUsage.lastUsedAtMillis ?: Long.MIN_VALUE) < command.createdAtMillis
    ) {
        profileBeforeUsage.recordUsage(
            atMillis = maxOf(
                command.createdAtMillis,
                profileBeforeUsage.updatedAtMillis + 1L
            )
        )
    } else {
        profileBeforeUsage
    }
    val registration = Registration(
        key = state.nextRegistrationKey,
        displayId = usedProfile.nickname,
        preference = command.preference,
        isTemporary = false,
        createdAtMillis = command.createdAtMillis,
        gender = usedProfile.gender,
        playerProfileId = usedProfile.id,
        requiresOnSiteCheckIn = false,
        originatingCommandId = command.commandId
    )
    val stagedQueue = queue.receiveAtWaitingTail(listOf(registration))
    if (stagedQueue.registrationCount != queue.registrationCount + 1) {
        return reject("终端未能建立登记，请重新扫码后再试。")
    }
    val preview = stagedQueue.nextPlayingPositionPreview()
    val needsAvailabilityConfirmation = stagedQueue.playing.isEmpty() &&
        preview?.changedByAvailability == true
    val updatedQueue = if (stagedQueue.playing.isEmpty() && !needsAvailabilityConfirmation) {
        stagedQueue.enterPlayingPosition()
    } else {
        stagedQueue
    }
    return MobileDeviceRegistrationDecision.Apply(
        state = state.copy(
            queues = state.queues + (command.machineId to updatedQueue),
            nextRegistrationKey = state.nextRegistrationKey + 1
        ),
        profileToPersist = usedProfile.takeIf { it != existingProfile },
        changedMachineId = command.machineId,
        needsAvailabilityConfirmation = needsAvailabilityConfirmation,
        detail = "已通过移动设备加入排队。"
    )
}

private const val MAX_MOBILE_REGISTRATIONS_PER_MACHINE = 20
