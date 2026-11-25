package com.abcccc.maimaiqueue

import java.util.UUID

enum class AuditLogCategory {
    MACHINE_A,
    MACHINE_B,
    MACHINE_C,
    MACHINE_D,
    MACHINE_E,
    MACHINE_F,
    MACHINE_G,
    MACHINE_H,
    MACHINE_I,
    MACHINE_J,
    SYSTEM,
    PLAYER_PROFILE
}

enum class AuditLogSource {
    ON_SITE_TERMINAL,
    QQ_BOT,
    SYSTEM_AUTOMATIC,
    WEBSITE_REMOTE,
    MOBILE_DEVICE,
    MANAGEMENT_APP
}

enum class PublicQueueEventType {
    REGISTRATION_ADDED,
    REGISTRATION_REMOVED,
    REGISTRATION_UPDATED,
    QUEUE_REORDERED,
    PLAYING_CHANGED,
    NO_SHOW_DEFERRED,
    NO_SHOW_MOVED_TO_TAIL,
    NO_SHOW_REMOVED,
    TEMPORARY_AWAY_EXPIRED,
    ONLINE_REGISTRATION_ADDED,
    ONLINE_CHECK_IN_COMPLETED,
    ONLINE_CHECK_IN_TIMED_OUT,
    ONLINE_CHECK_IN_MISSED,
    ABSENCE_CHANGED,
    MACHINE_STOPPED,
    MACHINE_RESTORED,
    REGISTRATION_OPENED,
    REGISTRATION_CLOSED,
    QUEUE_RESTORED,
    QUEUE_RESET,
    OTHER
}

enum class PublicQueueNotificationCategory {
    QUEUE_CHANGES,
    PLAYING_POSITION,
    ONLINE_CHECK_IN,
    ABSENCE,
    MACHINE_STATUS
}

data class AuditPlayerContact(
    val registrationKey: Int,
    val profileId: String,
    val qqNumber: String
)

data class AuditMachineIdentity(
    val stableId: String,
    val name: String
)

data class AuditLogEntry(
    val id: String,
    val timestampMillis: Long,
    val category: AuditLogCategory,
    val title: String,
    val detail: String,
    val source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL,
    val queueId: String? = null,
    val publicEventType: PublicQueueEventType? = null,
    val notificationCategories: Set<PublicQueueNotificationCategory> = emptySet(),
    val affectedRegistrationKeys: List<Int> = emptyList(),
    val affectedPlayerContacts: List<AuditPlayerContact> = emptyList(),
    val machineStableId: String? = null,
    val machineName: String? = null
)

internal fun AuditLogEntry.withMachineIdentity(identity: AuditMachineIdentity?): AuditLogEntry {
    if (!category.supportsMachineIdentity()) {
        return if (machineStableId == null && machineName == null) {
            this
        } else {
            copy(machineStableId = null, machineName = null)
        }
    }
    if (identity == null || (machineStableId != null && machineName != null)) return this
    return copy(
        machineStableId = machineStableId ?: identity.stableId,
        machineName = machineName ?: identity.name
    )
}

internal fun AuditLogCategory.supportsMachineIdentity(): Boolean = when (this) {
    AuditLogCategory.SYSTEM,
    AuditLogCategory.PLAYER_PROFILE -> false
    else -> true
}

internal fun AuditLogEntry.withAffectedPlayerContacts(
    registrations: Collection<Registration>,
    playerProfiles: Collection<PlayerProfile>
): AuditLogEntry {
    if (affectedRegistrationKeys.isEmpty()) return this
    val registrationsByKey = registrations.associateBy(Registration::key)
    val profilesById = playerProfiles.associateBy(PlayerProfile::id)
    val discovered = affectedRegistrationKeys.mapNotNull { registrationKey ->
        val registration = registrationsByKey[registrationKey] ?: return@mapNotNull null
        val profileId = registration.playerProfileId ?: return@mapNotNull null
        val qqNumber = profilesById[profileId]
            ?.normalizedQqNumber()
            ?.takeIf(::isValidQqNumber)
            ?: return@mapNotNull null
        AuditPlayerContact(
            registrationKey = registrationKey,
            profileId = profileId,
            qqNumber = qqNumber
        )
    }
    return copy(
        affectedPlayerContacts = (affectedPlayerContacts + discovered)
            .distinctBy(AuditPlayerContact::registrationKey)
    )
}

fun createAuditLogEntry(
    category: AuditLogCategory,
    title: String,
    detail: String,
    source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL,
    timestampMillis: Long = System.currentTimeMillis(),
    publicEventType: PublicQueueEventType? = null,
    notificationCategories: Set<PublicQueueNotificationCategory> = emptySet(),
    affectedRegistrationKeys: Collection<Int> = emptyList()
): AuditLogEntry = AuditLogEntry(
    id = UUID.randomUUID().toString(),
    timestampMillis = timestampMillis,
    category = category,
    title = compactAppMiddleDotSpacing(title),
    detail = compactAppMiddleDotSpacing(detail),
    source = source,
    publicEventType = publicEventType,
    notificationCategories = notificationCategories.ifEmpty {
        publicEventType?.let(::notificationCategoryForEventType)?.let(::setOf).orEmpty()
    },
    affectedRegistrationKeys = affectedRegistrationKeys.distinct()
)

fun createQueueAuditLog(
    category: AuditLogCategory,
    machineLabel: String,
    before: MachineQueue,
    after: MachineQueue,
    titleOverride: String? = null,
    publicEventTypeOverride: PublicQueueEventType? = null,
    affectedRegistrationKeysOverride: Collection<Int> = emptyList(),
    source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL,
    timestampMillis: Long = System.currentTimeMillis(),
    /** The registrations that left were taken out by the player, not by someone else. */
    removedByPlayer: Boolean = false
): AuditLogEntry? {
    if (before == after) return null
    val details = mutableListOf<String>()
    val changeKinds = mutableSetOf<String>()
    val beforeByKey = before.allRegistrations.associateBy { it.key }
    val afterByKey = after.allRegistrations.associateBy { it.key }
    val added = after.allRegistrations.filter { it.key !in beforeByKey }
    val removed = before.allRegistrations.filter { it.key !in afterByKey }
    val removedPendingCheckIn = removed.filter { it.requiresOnSiteCheckIn }
    val temporaryAwayExpired = if (
        publicEventTypeOverride == null ||
        publicEventTypeOverride == PublicQueueEventType.TEMPORARY_AWAY_EXPIRED ||
        publicEventTypeOverride == PublicQueueEventType.ONLINE_CHECK_IN_MISSED
    ) {
        // 轮空一次 counts in the same three skipped turns as 暂时离开.
        removed.filter {
            (it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY ||
                it.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN) &&
                it.temporaryAwaySkippedTurns >= 3
        }
    } else {
        emptyList()
    }
    val temporaryAwayExpiredKeys = temporaryAwayExpired.mapTo(mutableSetOf()) { it.key }
    val otherRemoved = removed.filter {
        !it.requiresOnSiteCheckIn && it.key !in temporaryAwayExpiredKeys
    }
    val unclassifiedRemoved = removed.filter { it.key !in temporaryAwayExpiredKeys }
    val affectedRegistrationKeys = mutableSetOf<Int>().apply {
        addAll(added.map { it.key })
        addAll(removed.map { it.key })
        addAll(affectedRegistrationKeysOverride)
    }

    if (added.isNotEmpty()) {
        changeKinds += "added"
        details += if (publicEventTypeOverride == PublicQueueEventType.QUEUE_RESTORED) {
            "撤销操作后恢复 ${quotedNames(added)}"
        } else {
            "${quotedNames(added)}的登记已创建"
        }
    }
    if (removed.isNotEmpty()) {
        changeKinds += "removed"
        when (publicEventTypeOverride) {
            PublicQueueEventType.NO_SHOW_REMOVED ->
                details += "${quotedNames(removed)}本次未到场，登记已移除"
            PublicQueueEventType.ONLINE_CHECK_IN_TIMED_OUT -> {
                if (removedPendingCheckIn.isNotEmpty()) {
                    details += "${quotedNames(removedPendingCheckIn)}未在本次 30 分钟签到时限内完成现场签到，登记已自动移除"
                }
                if (otherRemoved.isNotEmpty()) details += "${quotedNames(otherRemoved)}的登记已移除"
            }
            PublicQueueEventType.ONLINE_CHECK_IN_MISSED -> {
                if (removedPendingCheckIn.isNotEmpty()) {
                    details += "${quotedNames(removedPendingCheckIn)}轮到进入游玩位置时尚未完成现场签到，登记已自动移除"
                }
                if (otherRemoved.isNotEmpty()) details += "${quotedNames(otherRemoved)}的登记已移除"
            }
            else -> if (unclassifiedRemoved.isNotEmpty()) {
                details += if (removedByPlayer) {
                    "${quotedNames(unclassifiedRemoved)}已退出排队"
                } else {
                    "${quotedNames(unclassifiedRemoved)}的登记已移除"
                }
            }
        }
        val awayExpired = temporaryAwayExpired.filter { it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY }
        val skipExpired = temporaryAwayExpired.filter { it.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN }
        if (awayExpired.isNotEmpty()) {
            details += "${quotedNames(awayExpired)}在暂时离开期间第 4 次轮到，已自动移除"
        }
        if (skipExpired.isNotEmpty()) {
            details += "${quotedNames(skipExpired)}已轮空 3 次，再次轮到时已自动移除"
        }
    }

    beforeByKey.keys.intersect(afterByKey.keys).forEach { key ->
        val old = beforeByKey.getValue(key)
        val new = afterByKey.getValue(key)
        if (old.displayId != new.displayId) {
            affectedRegistrationKeys += key
            changeKinds += "renamed"
            details += "“${old.displayId}”的昵称已改为“${new.displayId}”"
        }
        if (old.gender != new.gender) {
            affectedRegistrationKeys += key
            changeKinds += "profile"
            details += "“${new.displayId}”的性别已修改"
        }
        if (old.preference != new.preference) {
            affectedRegistrationKeys += key
            changeKinds += "preference"
            details += "“${new.displayId}”的本次游玩偏好已改为“${queuePreferenceLabel(new)}”"
        }
        if (old.fixedPartnerKey != new.fixedPartnerKey) {
            affectedRegistrationKeys += key
            changeKinds += "pair"
            details += when {
                old.fixedPartnerKey == null && new.fixedPartnerKey != null -> {
                    val partnerName = afterByKey[new.fixedPartnerKey]?.displayId
                    if (partnerName == null) {
                        "“${new.displayId}”已组成固定组合"
                    } else {
                        "“${new.displayId}”已与“${partnerName}”组成固定组合"
                    }
                }
                old.fixedPartnerKey != null && new.fixedPartnerKey == null ->
                    "“${new.displayId}”的固定组合已解除，本次游玩偏好为“${queuePreferenceLabel(new)}”"
                else -> {
                    val oldPartnerName = old.fixedPartnerKey?.let(beforeByKey::get)?.displayId
                    val newPartnerName = new.fixedPartnerKey?.let(afterByKey::get)?.displayId
                    when {
                        oldPartnerName != null && newPartnerName != null ->
                            "“${new.displayId}”的固定组合已由“${oldPartnerName}”改为“${newPartnerName}”"
                        newPartnerName != null -> "“${new.displayId}”已改为与“${newPartnerName}”组成固定组合"
                        else -> "“${new.displayId}”的固定组合已改变"
                    }
                }
            }
        }
        if (old.absenceStatus != new.absenceStatus) {
            affectedRegistrationKeys += key
            changeKinds += "absence"
            // Before the turn a state is written with 将; once it has happened, with 已
            // (maintainer, 2026-11-25).
            details += when (new.absenceStatus) {
                QueueAbsenceStatus.DEFER_ONE_ROUND -> "“${new.displayId}”将暂缓一次"
                QueueAbsenceStatus.SKIP_ONE_TURN -> "“${new.displayId}”将轮空一次"
                QueueAbsenceStatus.TEMPORARILY_AWAY -> "“${new.displayId}”已设为暂时离开"
                QueueAbsenceStatus.NONE -> when (old.absenceStatus) {
                    QueueAbsenceStatus.DEFER_ONE_ROUND ->
                        if (new.deferOneRoundUsed && !old.deferOneRoundUsed) {
                            "“${new.displayId}”已暂缓一次"
                        } else {
                            "“${new.displayId}”已取消暂缓一次"
                        }
                    QueueAbsenceStatus.SKIP_ONE_TURN ->
                        if (new.temporaryAwaySkippedTurns > old.temporaryAwaySkippedTurns) {
                            "“${new.displayId}”已轮空一次并移至队尾，已轮空 ${new.temporaryAwaySkippedTurns} 次"
                        } else {
                            "“${new.displayId}”已取消轮空一次"
                        }
                    QueueAbsenceStatus.TEMPORARILY_AWAY -> "“${new.displayId}”已取消暂时离开"
                    QueueAbsenceStatus.NONE -> "“${new.displayId}”已恢复正常排队状态"
                }
            }
        }
        if (
            new.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
            new.temporaryAwaySkippedTurns > old.temporaryAwaySkippedTurns
        ) {
            affectedRegistrationKeys += key
            changeKinds += "absence"
            details += "“${new.displayId}”暂时离开期间已轮空 ${new.temporaryAwaySkippedTurns} 次"
        }
        if (old.isTemporary && !new.isTemporary) {
            affectedRegistrationKeys += key
            changeKinds += "claimed"
            details += "“${new.displayId}”已认领登记"
        }
        if (
            old.playerProfileId != new.playerProfileId &&
            new.playerProfileId != null &&
            !(old.isTemporary && !new.isTemporary)
        ) {
            affectedRegistrationKeys += key
            changeKinds += "claimed"
            details += "“${new.displayId}”已关联玩家资料"
        }
        if (new.noShowCount > old.noShowCount) {
            affectedRegistrationKeys += key
            changeKinds += "no_show"
            details += "“${new.displayId}”已记录第 ${new.noShowCount} 次未到场"
        }
        if (old.noShowCount > 0 && new.noShowCount == 0) {
            affectedRegistrationKeys += key
            changeKinds += "no_show_cleared"
            details += "“${new.displayId}”正常完成游玩，未到场记录已清除"
        }
        if (old.requiresOnSiteCheckIn != new.requiresOnSiteCheckIn) {
            affectedRegistrationKeys += key
            changeKinds += "check_in"
            details += if (new.requiresOnSiteCheckIn) {
                "“${new.displayId}”已标记为线上登记，需在现场签到"
            } else {
                "“${new.displayId}”已在现场完成签到"
            }
        }
    }

    if (before.playing.map { it.key } != after.playing.map { it.key }) {
        affectedRegistrationKeys += before.playing.map { it.key }
        affectedRegistrationKeys += after.playing.map { it.key }
        changeKinds += "playing"
        details += "游玩位置由${positionNames(before.playing)}变为${positionNames(after.playing)}"
    }
    val beforeOrder = before.allRegistrations.map { it.key }
    val afterOrder = after.allRegistrations.map { it.key }
    if (beforeOrder.toSet() == afterOrder.toSet() && beforeOrder != afterOrder) {
        affectedRegistrationKeys += afterOrder
        changeKinds += "order"
        if ("playing" !in changeKinds) {
            // The playing position did not change: name the waiting registrations only.
            details += "等待顺序已调整为${quotedNames(after.waiting)}"
        }
    }
    if (
        before.playingStartedAtMillis != after.playingStartedAtMillis &&
        before.playing.map { it.key } == after.playing.map { it.key }
    ) {
        changeKinds += "timer"
        details += "本轮计时已重置"
    }

    val publicEventType = publicEventTypeOverride ?: when {
        temporaryAwayExpired.isNotEmpty() -> PublicQueueEventType.TEMPORARY_AWAY_EXPIRED
        "no_show" in changeKinds -> PublicQueueEventType.REGISTRATION_UPDATED
        "absence" in changeKinds -> PublicQueueEventType.ABSENCE_CHANGED
        "added" in changeKinds && "removed" !in changeKinds -> PublicQueueEventType.REGISTRATION_ADDED
        "removed" in changeKinds && "added" !in changeKinds -> PublicQueueEventType.REGISTRATION_REMOVED
        "playing" in changeKinds -> PublicQueueEventType.PLAYING_CHANGED
        "order" in changeKinds -> PublicQueueEventType.QUEUE_REORDERED
        changeKinds.isNotEmpty() -> PublicQueueEventType.REGISTRATION_UPDATED
        else -> PublicQueueEventType.OTHER
    }
    val generatedTitle = when (publicEventType) {
        PublicQueueEventType.NO_SHOW_DEFERRED -> "未到场，已暂缓一次"
        PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL -> "未到场，已移至队尾"
        PublicQueueEventType.NO_SHOW_REMOVED -> "未到场，已移除登记"
        PublicQueueEventType.TEMPORARY_AWAY_EXPIRED ->
            if (temporaryAwayExpired.all { it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY }) {
                "暂时离开已达轮空上限"
            } else {
                "已达轮空上限，登记已移除"
            }
        PublicQueueEventType.ONLINE_REGISTRATION_ADDED -> "线上登记已创建"
        PublicQueueEventType.ONLINE_CHECK_IN_COMPLETED -> "线上登记已完成签到"
        PublicQueueEventType.ONLINE_CHECK_IN_TIMED_OUT -> "线上登记签到超时"
        PublicQueueEventType.ONLINE_CHECK_IN_MISSED -> "未签到登记已自动移除"
        else -> when {
            "added" in changeKinds && "removed" !in changeKinds -> "登记已创建"
            "removed" in changeKinds && "added" !in changeKinds ->
                if (removedByPlayer) "登记已退出排队" else "登记已移除"
            "claimed" in changeKinds -> "登记已认领"
            "renamed" in changeKinds -> "登记昵称已修改"
            "profile" in changeKinds -> "登记资料已更新"
            "no_show" in changeKinds -> "未到场状态已更新"
            "check_in" in changeKinds -> "线上登记签到状态已更新"
            "absence" in changeKinds -> "暂缓一次或暂时离开状态已更新"
            "pair" in changeKinds -> "固定组合已修改"
            "preference" in changeKinds -> "游玩偏好已更改"
            "playing" in changeKinds -> "游玩位置已更新"
            "order" in changeKinds -> "等待顺序已调整"
            "timer" in changeKinds -> "本轮计时已重置"
            else -> "队列已更新"
        }
    }
    val notificationCategories = buildSet {
        add(notificationCategoryForEventType(publicEventType))
        if ("playing" in changeKinds) add(PublicQueueNotificationCategory.PLAYING_POSITION)
        if (
            "check_in" in changeKinds ||
            removedPendingCheckIn.isNotEmpty()
        ) add(PublicQueueNotificationCategory.ONLINE_CHECK_IN)
        if (
            "absence" in changeKinds ||
            "no_show" in changeKinds ||
            "no_show_cleared" in changeKinds ||
            temporaryAwayExpired.isNotEmpty()
        ) add(PublicQueueNotificationCategory.ABSENCE)
        if (
            changeKinds.any {
                it in setOf(
                    "added",
                    "removed",
                    "renamed",
                    "profile",
                    "preference",
                    "pair",
                    "claimed",
                    "timer"
                )
            } || "order" in changeKinds && "playing" !in changeKinds
        ) add(PublicQueueNotificationCategory.QUEUE_CHANGES)
    }
    return createAuditLogEntry(
        category = category,
        title = titleOverride ?: "${machineLabel}：$generatedTitle",
        detail = if (details.isEmpty()) {
            "队列状态发生变化。"
        } else {
            details.distinct().joinToString(separator = "；", postfix = "。")
        },
        source = source,
        timestampMillis = timestampMillis,
        publicEventType = publicEventType,
        notificationCategories = notificationCategories,
        affectedRegistrationKeys = affectedRegistrationKeys
    )
}

fun createQueueAuditLogs(
    category: AuditLogCategory,
    machineLabel: String,
    before: MachineQueue,
    after: MachineQueue,
    titleOverride: String? = null,
    publicEventTypeOverride: PublicQueueEventType? = null,
    affectedRegistrationKeysOverride: Collection<Int> = emptyList(),
    source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL,
    timestampMillis: Long = System.currentTimeMillis(),
    classifyAvailabilityOutcomes: Boolean = false,
    semanticAction: QueueAction? = null
): List<AuditLogEntry> {
    if (before == after) return emptyList()

    val beforeByKey = before.allRegistrations.associateBy(Registration::key)
    val afterByKey = after.allRegistrations.associateBy(Registration::key)
    val noShowEventTypes = setOf(
        PublicQueueEventType.NO_SHOW_DEFERRED,
        PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL,
        PublicQueueEventType.NO_SHOW_REMOVED
    )
    val noShowKeys = if (publicEventTypeOverride in noShowEventTypes) {
        when (semanticAction) {
            is QueueAction.MarkNoShow -> semanticAction.registrationKeys
            else -> affectedRegistrationKeysOverride.toSet()
        }
    } else {
        emptySet()
    }
    val removed = before.allRegistrations.filter { it.key !in afterByKey }
    val pendingCheckInRemoved = if (classifyAvailabilityOutcomes) {
        removed.filter { it.requiresOnSiteCheckIn && it.key !in noShowKeys }
    } else {
        emptyList()
    }
    val temporaryAwayExpired = if (classifyAvailabilityOutcomes) {
        removed.filter {
            it.key !in noShowKeys &&
                it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
                it.temporaryAwaySkippedTurns >= 3
        }
    } else {
        emptyList()
    }
    // 轮空一次 counts in the same three skipped turns as 暂时离开 (2026-11-25).
    val skipExpired = if (classifyAvailabilityOutcomes) {
        removed.filter {
            it.key !in noShowKeys &&
                it.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN &&
                it.temporaryAwaySkippedTurns >= 3
        }
    } else {
        emptyList()
    }
    val skipCompleted = if (classifyAvailabilityOutcomes) {
        after.allRegistrations.filter { registration ->
            val old = beforeByKey[registration.key]
            registration.key !in noShowKeys &&
                old?.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN &&
                registration.absenceStatus == QueueAbsenceStatus.NONE &&
                registration.temporaryAwaySkippedTurns > old.temporaryAwaySkippedTurns
        }
    } else {
        emptyList()
    }
    val deferredCompleted = if (classifyAvailabilityOutcomes) {
        after.allRegistrations.filter { registration ->
            registration.key !in noShowKeys &&
                beforeByKey[registration.key]?.absenceStatus ==
                QueueAbsenceStatus.DEFER_ONE_ROUND &&
                registration.absenceStatus == QueueAbsenceStatus.NONE
        }
    } else {
        emptyList()
    }
    val noShowRecordsCleared = if (classifyAvailabilityOutcomes) {
        after.allRegistrations.filter { registration ->
            val old = beforeByKey[registration.key]
            old != null && old.noShowCount > 0 && registration.noShowCount == 0
        }
    } else {
        emptyList()
    }
    val temporaryAwayAdvanced = if (classifyAvailabilityOutcomes) {
        after.allRegistrations.filter { registration ->
            val old = beforeByKey[registration.key]
            registration.key !in noShowKeys &&
                old?.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
                registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
                registration.temporaryAwaySkippedTurns > old.temporaryAwaySkippedTurns
        }
    } else {
        emptyList()
    }
    val splitKeys = buildSet {
        addAll(noShowKeys)
        addAll(pendingCheckInRemoved.map(Registration::key))
        addAll(temporaryAwayExpired.map(Registration::key))
        addAll(skipExpired.map(Registration::key))
        addAll(deferredCompleted.map(Registration::key))
        addAll(skipCompleted.map(Registration::key))
        addAll(temporaryAwayAdvanced.map(Registration::key))
    }
    if (splitKeys.isEmpty() && noShowRecordsCleared.isEmpty()) {
        return listOfNotNull(
            createQueueAuditLog(
                category = category,
                machineLabel = machineLabel,
                before = before,
                after = after,
                titleOverride = titleOverride,
                publicEventTypeOverride = publicEventTypeOverride,
                affectedRegistrationKeysOverride = affectedRegistrationKeysOverride,
                source = source,
                timestampMillis = timestampMillis,
                removedByPlayer = semanticAction is QueueAction.LeaveQueue
            )
        )
    }

    fun names(registrations: Collection<Registration>): String =
        registrations.joinToString("、") { "“${it.displayId}”" }

    fun event(
        type: PublicQueueEventType,
        title: String,
        detail: String,
        keys: Collection<Int>
    ): AuditLogEntry = createAuditLogEntry(
        category = category,
        title = "${machineLabel}：$title",
        detail = detail,
        source = source,
        timestampMillis = timestampMillis,
        publicEventType = type,
        affectedRegistrationKeys = keys
    )

    val entries = mutableListOf<AuditLogEntry>()
    if (noShowKeys.isNotEmpty()) {
        val registrations = noShowKeys.mapNotNull { beforeByKey[it] ?: afterByKey[it] }
        val stillDeferred = noShowKeys.any {
            afterByKey[it]?.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
        }
        val detail = when (publicEventTypeOverride) {
            PublicQueueEventType.NO_SHOW_DEFERRED -> {
                if (stillDeferred) {
                    "${names(registrations)}本次未到场，未到场记录已更新并暂缓一次；跳过本次机会后，暂缓状态会自动解除。"
                } else {
                    "${names(registrations)}本次未到场，记录已更新；本次机会已跳过，暂缓状态已自动解除。"
                }
            }
            PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL ->
                if (
                    semanticAction is QueueAction.MarkNoShow &&
                    semanticAction.startNextWhenPlayingBecomesEmpty
                ) {
                    "${names(registrations)}本次未到场，记录已更新并移至队尾；本次自动安排不会再次选中相关登记。"
                } else {
                    "${names(registrations)}本次未到场，记录已更新并移至队尾；游玩位置保持当前状态。"
                }
            else -> "${names(registrations)}本次未到场，相关登记已移除。"
        }
        entries += event(
            type = publicEventTypeOverride ?: PublicQueueEventType.REGISTRATION_UPDATED,
            title = when (publicEventTypeOverride) {
                PublicQueueEventType.NO_SHOW_DEFERRED -> if (stillDeferred) {
                    "未到场，已暂缓一次"
                } else {
                    "未到场，本次机会已跳过"
                }
                PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL -> "未到场，已移至队尾"
                else -> "未到场，已移除登记"
            },
            detail = detail,
            keys = noShowKeys
        )
    }
    if (pendingCheckInRemoved.isNotEmpty()) {
        entries += event(
            PublicQueueEventType.ONLINE_CHECK_IN_MISSED,
            "未签到登记已自动移除",
            "${names(pendingCheckInRemoved)}轮到进入游玩位置时尚未完成现场签到，登记已自动移除。",
            pendingCheckInRemoved.map(Registration::key)
        )
    }
    if (temporaryAwayExpired.isNotEmpty()) {
        entries += event(
            PublicQueueEventType.TEMPORARY_AWAY_EXPIRED,
            "暂时离开已达轮空上限",
            "${names(temporaryAwayExpired)}在暂时离开期间第 4 次轮到，已自动移除。",
            temporaryAwayExpired.map(Registration::key)
        )
    }
    if (skipExpired.isNotEmpty()) {
        entries += event(
            PublicQueueEventType.TEMPORARY_AWAY_EXPIRED,
            "已达轮空上限，登记已移除",
            "${names(skipExpired)}已轮空 3 次，再次轮到时已自动移除。",
            skipExpired.map(Registration::key)
        )
    }
    skipCompleted.groupBy(Registration::temporaryAwaySkippedTurns)
        .toSortedMap()
        .forEach { (skippedTurns, registrations) ->
            entries += event(
                PublicQueueEventType.ABSENCE_CHANGED,
                "轮空一次，已轮空 $skippedTurns 次",
                "${names(registrations)}本次已轮空并移至队尾，累计已轮空 $skippedTurns 次；轮空一次随即自动解除。",
                registrations.map(Registration::key)
            )
        }
    if (deferredCompleted.isNotEmpty()) {
        entries += event(
            PublicQueueEventType.ABSENCE_CHANGED,
            "暂缓一次已完成",
            "${names(deferredCompleted)}已跳过本次机会，暂缓状态随后自动解除；真实等待顺序未改变。",
            deferredCompleted.map(Registration::key)
        )
    }
    if (noShowRecordsCleared.isNotEmpty()) {
        entries += event(
            PublicQueueEventType.ABSENCE_CHANGED,
            "未到场记录已清除",
            "${names(noShowRecordsCleared)}正常完成本次游玩，未到场记录已清除。",
            noShowRecordsCleared.map(Registration::key)
        )
    }
    temporaryAwayAdvanced.groupBy(Registration::temporaryAwaySkippedTurns)
        .toSortedMap()
        .forEach { (skippedTurns, registrations) ->
            entries += event(
                PublicQueueEventType.ABSENCE_CHANGED,
                "暂时离开，已轮空 $skippedTurns 次",
                "${names(registrations)}本次已轮空并移至队尾，累计已轮空 $skippedTurns 次。",
                registrations.map(Registration::key)
            )
        }
    val clearedNoShowByKey = noShowRecordsCleared.associateBy(Registration::key)
    val beforeWithoutSplitOutcomes = before.removeAll(splitKeys)
    val remainingBefore = beforeWithoutSplitOutcomes.copy(
        playing = beforeWithoutSplitOutcomes.playing.map { registration ->
            clearedNoShowByKey[registration.key]?.let { cleared ->
                registration.copy(
                    noShowCount = cleared.noShowCount,
                    lastNoShowActionWasDefer = cleared.lastNoShowActionWasDefer
                )
            } ?: registration
        },
        waiting = beforeWithoutSplitOutcomes.waiting.map { registration ->
            clearedNoShowByKey[registration.key]?.let { cleared ->
                registration.copy(
                    noShowCount = cleared.noShowCount,
                    lastNoShowActionWasDefer = cleared.lastNoShowActionWasDefer
                )
            } ?: registration
        }
    )
    val remainingAfter = after.removeAll(splitKeys)
    createQueueAuditLog(
        category = category,
        machineLabel = machineLabel,
        before = remainingBefore,
        after = remainingAfter,
        titleOverride = titleOverride,
        publicEventTypeOverride = publicEventTypeOverride
            ?.takeUnless { it in noShowEventTypes || it == PublicQueueEventType.ONLINE_CHECK_IN_MISSED },
        affectedRegistrationKeysOverride = affectedRegistrationKeysOverride.filterNot {
            it in splitKeys
        },
        source = source,
        timestampMillis = timestampMillis,
        removedByPlayer = semanticAction is QueueAction.LeaveQueue
    )?.let(entries::add)
    return entries
}

internal fun notificationCategoryForEventType(
    eventType: PublicQueueEventType
): PublicQueueNotificationCategory = when (eventType) {
    PublicQueueEventType.PLAYING_CHANGED -> PublicQueueNotificationCategory.PLAYING_POSITION
    PublicQueueEventType.ONLINE_REGISTRATION_ADDED,
    PublicQueueEventType.ONLINE_CHECK_IN_COMPLETED,
    PublicQueueEventType.ONLINE_CHECK_IN_TIMED_OUT,
    PublicQueueEventType.ONLINE_CHECK_IN_MISSED -> PublicQueueNotificationCategory.ONLINE_CHECK_IN
    PublicQueueEventType.NO_SHOW_DEFERRED,
    PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL,
    PublicQueueEventType.NO_SHOW_REMOVED,
    PublicQueueEventType.TEMPORARY_AWAY_EXPIRED,
    PublicQueueEventType.ABSENCE_CHANGED -> PublicQueueNotificationCategory.ABSENCE
    PublicQueueEventType.MACHINE_STOPPED,
    PublicQueueEventType.MACHINE_RESTORED,
    PublicQueueEventType.REGISTRATION_OPENED,
    PublicQueueEventType.REGISTRATION_CLOSED -> PublicQueueNotificationCategory.MACHINE_STATUS
    else -> PublicQueueNotificationCategory.QUEUE_CHANGES
}

fun createPlayerProfileAuditLog(
    before: PlayerProfile?,
    after: PlayerProfile,
    source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL,
    timestampMillis: Long = System.currentTimeMillis()
): AuditLogEntry {
    if (before == null) {
        return createAuditLogEntry(
            category = AuditLogCategory.PLAYER_PROFILE,
            title = "玩家资料已创建",
            detail = "已创建“${after.nickname}”，默认游玩偏好为“${profilePreferenceAuditLabel(after.defaultPreference)}”。",
            source = source,
            timestampMillis = timestampMillis
        )
    }
    val details = buildList {
        if (before.nickname != after.nickname) add("昵称由“${before.nickname}”改为“${after.nickname}”")
        if (before.gender != after.gender) add("性别设置已修改")
        if (before.defaultPreference != after.defaultPreference) {
            add("默认游玩偏好改为${profilePreferenceAuditLabel(after.defaultPreference)}")
        }
        if (before.qqNumber != after.qqNumber) {
            add("QQ 号已更新")
        }
    }
    return createAuditLogEntry(
        category = AuditLogCategory.PLAYER_PROFILE,
        title = "玩家资料已修改",
        detail = if (details.isEmpty()) "“${after.nickname}”的资料已保存。" else details.joinToString("；") + "。",
        source = source,
        timestampMillis = timestampMillis
    )
}

fun createPlayerProfileDeletionAuditLog(
    profile: PlayerProfile,
    source: AuditLogSource = AuditLogSource.MANAGEMENT_APP,
    timestampMillis: Long = System.currentTimeMillis()
): AuditLogEntry = createAuditLogEntry(
    category = AuditLogCategory.PLAYER_PROFILE,
    title = "玩家资料已删除",
    detail = "已删除玩家资料“${profile.nickname}”（玩家编号 ${profile.publicPlayerId ?: "未分配"}）。",
    source = source,
    timestampMillis = timestampMillis
)

fun createMachineTransferAuditLog(
    category: AuditLogCategory,
    sourceMachineLabel: String,
    destinationMachineLabel: String,
    registrations: List<Registration>,
    releasedPartnerRegistrations: List<Registration> = emptyList(),
    destinationMachineCapacity: Int = DEFAULT_MACHINE_CAPACITY,
    source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL,
    timestampMillis: Long = System.currentTimeMillis()
): AuditLogEntry? {
    if (registrations.isEmpty()) return null
    val affectedRegistrationKeys = (registrations + releasedPartnerRegistrations)
        .map { it.key }
        .distinct()
    val details = buildList {
        add(
            "已将${quotedNames(registrations)}从 $sourceMachineLabel 转至 $destinationMachineLabel 的队尾"
        )
        if (registrations.any { it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND }) {
            if (releasedPartnerRegistrations.any {
                    it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
                }) {
                add("转入登记不再暂缓，留在原机台的登记仍将暂缓一次")
            } else {
                add("转入后不再暂缓")
            }
        }
        registrations.filter {
            it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
        }.maxOfOrNull(Registration::temporaryAwaySkippedTurns)?.let { skippedTurns ->
            add("暂时离开状态和已轮空 $skippedTurns 次会在转入后保留")
        }
        if (registrations.any { it.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN }) {
            // Arriving at the tail already does what 轮空一次 would.
            if (releasedPartnerRegistrations.any {
                    it.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN
                }) {
                add("转入登记已在队尾，不再轮空，留在原机台的登记仍将轮空一次")
            } else {
                add("转入后已在队尾，不再轮空")
            }
        }
        if (releasedPartnerRegistrations.isNotEmpty()) {
            add("原固定组合已解除，双方均恢复为允许他人加入")
        }
        if (destinationMachineCapacity == 1) {
            val preferenceChangedRegistrations = registrations.filter {
                it.preference != PlayPreference.SOLO
            }
            val unchangedSoloRegistrations = registrations.filter {
                it.preference == PlayPreference.SOLO
            }
            add("目标机台仅能容纳一人游玩")
            if (preferenceChangedRegistrations.isNotEmpty()) {
                add("${quotedNames(preferenceChangedRegistrations)}的本次登记已改为“单人游玩”")
            }
            if (unchangedSoloRegistrations.isNotEmpty()) {
                add("${quotedNames(unchangedSoloRegistrations)}的本次登记继续使用“单人游玩”")
            }
            add("玩家资料中的默认游玩偏好不会改变")
            add(
                if (registrations.size > 1) {
                    "之后转回支持共同游玩的机台时，这些登记仍会保持“单人游玩”"
                } else {
                    "之后转回支持共同游玩的机台时，这份登记仍会保持“单人游玩”"
                }
            )
        }
    }
    return createAuditLogEntry(
        category = category,
        title = "登记已转至 $destinationMachineLabel",
        detail = details.joinToString(separator = "；", postfix = "。"),
        source = source,
        timestampMillis = timestampMillis,
        publicEventType = PublicQueueEventType.REGISTRATION_UPDATED,
        affectedRegistrationKeys = affectedRegistrationKeys
    )
}

/**
 * A registration that queues at both machines of a pair was reached first on the other machine
 * and moved there to play.
 */
fun createPairedPlaceArrivalAuditLog(
    category: AuditLogCategory,
    sourceMachineLabel: String,
    destinationMachineLabel: String,
    registrations: List<Registration>,
    source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL,
    timestampMillis: Long = System.currentTimeMillis()
): AuditLogEntry? {
    if (registrations.isEmpty()) return null
    return createAuditLogEntry(
        category = category,
        title = "登记已自动转至 $destinationMachineLabel",
        detail = "${quotedNames(registrations)}已由 $sourceMachineLabel 自动转至 $destinationMachineLabel " +
            "并进入游玩位置。",
        source = source,
        timestampMillis = timestampMillis,
        publicEventType = PublicQueueEventType.REGISTRATION_UPDATED,
        // The player is told when either kind of notice is wanted: the registration was moved,
        // and it is now in a playing position.
        notificationCategories = setOf(
            PublicQueueNotificationCategory.QUEUE_CHANGES,
            PublicQueueNotificationCategory.PLAYING_POSITION
        ),
        affectedRegistrationKeys = registrations.map { it.key }
    )
}

/**
 * The queue to compare a machine's new queue against when writing logs. A registration that
 * moved between the two machines of a pair is neither an exit nor a new registration: it is
 * treated as if it had already been waiting on the machine it moved to.
 */
internal fun pairedAuditBaseline(
    machineName: String,
    beforeQueues: Map<String, MachineQueue>,
    afterQueues: Map<String, MachineQueue>
): MachineQueue {
    val beforeQueue = beforeQueues[machineName] ?: return MachineQueue()
    val afterQueue = afterQueues[machineName] ?: return beforeQueue
    val beforeElsewhere = beforeQueues.filterKeys { it != machineName }.values
        .flatMap { it.allRegistrations }
        .associateBy { it.key }
    val afterKeysElsewhere = afterQueues.filterKeys { it != machineName }.values
        .flatMapTo(mutableSetOf()) { queue -> queue.allRegistrations.map { it.key } }
    val ownBeforeKeys = beforeQueue.allRegistrations.mapTo(mutableSetOf()) { it.key }
    val departedKeys = ownBeforeKeys.filterTo(mutableSetOf()) { it in afterKeysElsewhere }
    // A registration that comes back from the playing position of the other machine was
    // sent back after not showing up there. That has a log entry of its own, so here it
    // counts as already waiting where it now stands.
    val playingElsewhereBefore = beforeQueues.filterKeys { it != machineName }.values
        .flatMapTo(mutableSetOf()) { queue -> queue.playing.map { it.key } }
    val returned = afterQueue.waiting
        .filter { it.key !in ownBeforeKeys && it.key in playingElsewhereBefore }
    val returnedKeys = returned.mapTo(mutableSetOf()) { it.key }
    val arrived = afterQueue.allRegistrations
        .filter { it.key !in ownBeforeKeys && it.key !in returnedKeys }
        .mapNotNull { beforeElsewhere[it.key] }
    if (departedKeys.isEmpty() && arrived.isEmpty() && returned.isEmpty()) return beforeQueue
    val withoutDeparted = beforeQueue.removeAll(departedKeys)
    val waiting = (withoutDeparted.waiting + arrived).toMutableList()
    returned.forEach { registration ->
        waiting.add(
            afterQueue.waiting.indexOf(registration).coerceIn(0, waiting.size),
            registration
        )
    }
    return withoutDeparted.copy(waiting = waiting)
}

/**
 * Tells the player of a registration that a preview at the other machine of its pair has been
 * shown for a while, and from when on the registration may be arranged there.
 */
fun createPairedArrangementNoticeAuditLog(
    category: AuditLogCategory,
    homeMachineLabel: String,
    pairedMachineLabel: String,
    registration: Registration,
    earliestArrangementClockTime: String,
    timestampMillis: Long = System.currentTimeMillis()
): AuditLogEntry = createAuditLogEntry(
    category = category,
    title = "登记可能自动转至 $pairedMachineLabel",
    detail = "$pairedMachineLabel 预计能够更早安排${quotedNames(listOf(registration))}游玩，" +
        "其等待顺序中已显示预览。最早 $earliestArrangementClockTime 之后，这份登记可能由" +
        "$homeMachineLabel 自动转至 ${pairedMachineLabel}，请合理安排时间。" +
        "可以在“更改游玩偏好”中取消。",
    source = AuditLogSource.SYSTEM_AUTOMATIC,
    timestampMillis = timestampMillis,
    publicEventType = PublicQueueEventType.REGISTRATION_UPDATED,
    affectedRegistrationKeys = listOf(registration.key)
)

/**
 * Records that registrations the system had arranged to another machine did not show up there
 * and went back to the machine they came from.
 */
fun createPairedArrivalReturnedAuditLog(
    category: AuditLogCategory,
    originMachineLabel: String,
    arrangedMachineLabel: String,
    registrations: List<Registration>,
    source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL,
    timestampMillis: Long = System.currentTimeMillis()
): AuditLogEntry? {
    if (registrations.isEmpty()) return null
    return createAuditLogEntry(
        category = category,
        title = "登记已回到 $originMachineLabel",
        detail = pairedArrivalReturnedDetail(
            names = quotedNames(registrations),
            originMachineLabel = originMachineLabel,
            arrangedMachineLabel = arrangedMachineLabel
        ),
        source = source,
        timestampMillis = timestampMillis,
        publicEventType = PublicQueueEventType.REGISTRATION_UPDATED,
        notificationCategories = setOf(
            PublicQueueNotificationCategory.QUEUE_CHANGES,
            PublicQueueNotificationCategory.ABSENCE
        ),
        affectedRegistrationKeys = registrations.map { it.key }
    )
}

internal fun pairedArrivalReturnedDetail(
    names: String,
    originMachineLabel: String,
    arrangedMachineLabel: String
): String = "${names}自动转至 $arrangedMachineLabel 后未到场，已回到 $originMachineLabel " +
    "原来的等待顺序，未到场记录不会增加。已取消系统在 $originMachineLabel 和 $arrangedMachineLabel " +
    "之间的安排，可以在“更改游玩偏好”中重新开启。"

private fun quotedNames(registrations: List<Registration>): String =
    registrations.joinToString("、") { "“${it.displayId}”" }.ifEmpty { "空" }

private fun positionNames(registrations: List<Registration>): String =
    if (registrations.isEmpty()) "空" else quotedNames(registrations)

private fun queuePreferenceLabel(registration: Registration): String =
    if (registration.fixedPartnerKey != null) {
        "与朋友共同游玩"
    } else {
        when (registration.preference) {
            PlayPreference.SOLO -> "单人游玩"
            PlayPreference.OPEN_TO_JOIN -> "允许他人加入"
        }
    }

private fun profilePreferenceAuditLabel(preference: ProfilePlayPreference): String = when (preference) {
    ProfilePlayPreference.SOLO -> "单人游玩"
    ProfilePlayPreference.OPEN_TO_JOIN -> "允许他人加入"
    ProfilePlayPreference.ASK_EVERY_TIME -> "每次询问"
}
