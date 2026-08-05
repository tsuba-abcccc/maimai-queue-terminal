package com.abcccc.maimaiqueue

import java.util.UUID

enum class AuditLogCategory {
    MACHINE_A,
    MACHINE_B,
    MACHINE_C,
    MACHINE_D,
    SYSTEM,
    PLAYER_PROFILE
}

enum class AuditLogSource {
    ON_SITE_TERMINAL,
    QQ_BOT,
    SYSTEM_AUTOMATIC,
    WEBSITE_REMOTE,
    MOBILE_DEVICE
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
    val affectedPlayerContacts: List<AuditPlayerContact> = emptyList()
)

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
    title = title,
    detail = detail,
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
    timestampMillis: Long = System.currentTimeMillis()
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
        removed.filter {
            it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
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
            "新增 ${quotedNames(added)}"
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
                if (otherRemoved.isNotEmpty()) details += "移除 ${quotedNames(otherRemoved)}"
            }
            PublicQueueEventType.ONLINE_CHECK_IN_MISSED -> {
                if (removedPendingCheckIn.isNotEmpty()) {
                    details += "${quotedNames(removedPendingCheckIn)}轮到进入游玩位置时尚未完成现场签到，登记已自动移除"
                }
                if (otherRemoved.isNotEmpty()) details += "移除 ${quotedNames(otherRemoved)}"
            }
            else -> if (unclassifiedRemoved.isNotEmpty()) {
                details += "移除 ${quotedNames(unclassifiedRemoved)}"
            }
        }
        if (temporaryAwayExpired.isNotEmpty()) {
            details += "${quotedNames(temporaryAwayExpired)}在暂时离开期间第四次轮到，已自动退出排队"
        }
    }

    beforeByKey.keys.intersect(afterByKey.keys).forEach { key ->
        val old = beforeByKey.getValue(key)
        val new = afterByKey.getValue(key)
        if (old.displayId != new.displayId) {
            affectedRegistrationKeys += key
            changeKinds += "renamed"
            details += "“${old.displayId}”更名为“${new.displayId}”"
        }
        if (old.gender != new.gender) {
            affectedRegistrationKeys += key
            changeKinds += "profile"
            details += "“${new.displayId}”的性别标识已更新"
        }
        if (old.preference != new.preference) {
            affectedRegistrationKeys += key
            changeKinds += "preference"
            details += "“${new.displayId}”改为${queuePreferenceLabel(new)}"
        }
        if (old.fixedPartnerKey != new.fixedPartnerKey) {
            affectedRegistrationKeys += key
            changeKinds += "pair"
            details += when {
                old.fixedPartnerKey == null && new.fixedPartnerKey != null -> {
                    val partnerName = afterByKey[new.fixedPartnerKey]?.displayId
                    if (partnerName == null) {
                        "“${new.displayId}”已建立固定组合"
                    } else {
                        "“${new.displayId}”已与“$partnerName”建立固定组合"
                    }
                }
                old.fixedPartnerKey != null && new.fixedPartnerKey == null ->
                    "“${new.displayId}”的固定组合已解除，当前游玩偏好为${queuePreferenceLabel(new)}"
                else -> {
                    val oldPartnerName = old.fixedPartnerKey?.let(beforeByKey::get)?.displayId
                    val newPartnerName = new.fixedPartnerKey?.let(afterByKey::get)?.displayId
                    "“${new.displayId}”的固定组合已由“${oldPartnerName ?: "原搭档"}”改为“${newPartnerName ?: "新搭档"}”"
                }
            }
        }
        if (old.absenceStatus != new.absenceStatus) {
            affectedRegistrationKeys += key
            changeKinds += "absence"
            details += when (new.absenceStatus) {
                QueueAbsenceStatus.DEFER_ONE_ROUND -> "“${new.displayId}”已暂缓一次"
                QueueAbsenceStatus.TEMPORARILY_AWAY -> "“${new.displayId}”已设为暂时离开"
                QueueAbsenceStatus.NONE -> when (old.absenceStatus) {
                    QueueAbsenceStatus.DEFER_ONE_ROUND -> "“${new.displayId}”已结束暂缓"
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
            details += "登记顺序已调整为${quotedNames(after.allRegistrations)}"
        }
    }
    if (
        before.playingStartedAtMillis != after.playingStartedAtMillis &&
        before.playing.map { it.key } == after.playing.map { it.key }
    ) {
        changeKinds += "timer"
        details += "本轮计时已更新"
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
        PublicQueueEventType.NO_SHOW_DEFERRED -> "未到场 · 已暂缓一次"
        PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL -> "未到场 · 已移至队尾"
        PublicQueueEventType.NO_SHOW_REMOVED -> "未到场 · 已移除登记"
        PublicQueueEventType.TEMPORARY_AWAY_EXPIRED -> "暂时离开已达轮空上限"
        PublicQueueEventType.ONLINE_REGISTRATION_ADDED -> "线上登记已创建"
        PublicQueueEventType.ONLINE_CHECK_IN_COMPLETED -> "线上登记签到状态已更新"
        PublicQueueEventType.ONLINE_CHECK_IN_TIMED_OUT -> "线上登记签到超时"
        PublicQueueEventType.ONLINE_CHECK_IN_MISSED -> "未签到登记已自动移除"
        else -> when {
            "added" in changeKinds && "removed" !in changeKinds -> "新增登记"
            "removed" in changeKinds && "added" !in changeKinds -> "移除登记"
            "claimed" in changeKinds -> "登记已认领"
            "renamed" in changeKinds -> "登记昵称已修改"
            "profile" in changeKinds -> "登记资料已更新"
            "no_show" in changeKinds -> "未到场状态已更新"
            "check_in" in changeKinds -> "线上登记签到状态已更新"
            "absence" in changeKinds -> "暂缓一次或暂时离开状态已修改"
            "pair" in changeKinds -> "固定组合已修改"
            "preference" in changeKinds -> "游玩偏好已修改"
            "playing" in changeKinds -> "游玩位置已更新"
            "order" in changeKinds -> "登记顺序已调整"
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
        title = titleOverride ?: "$machineLabel · $generatedTitle",
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
    fun fixedGroupKeys(registrationKey: Int): Set<Int> {
        val registration = before.allRegistrations.firstOrNull { it.key == registrationKey }
            ?: return emptySet()
        val partner = registration.fixedPartnerKey?.let { partnerKey ->
            before.allRegistrations.firstOrNull { it.key == partnerKey }
        }
        return if (partner?.fixedPartnerKey == registration.key) {
            setOf(registration.key, partner.key)
        } else {
            setOf(registration.key)
        }
    }
    val semanticAbsenceKeys = when (semanticAction) {
        is QueueAction.DeferOneRound -> fixedGroupKeys(semanticAction.registrationKey)
        is QueueAction.TemporarilyLeave -> fixedGroupKeys(semanticAction.registrationKey)
        else -> emptySet()
    }.filterTo(mutableSetOf()) { key -> before.playing.any { it.key == key } }

    val splitKeys = buildSet {
        addAll(noShowKeys)
        addAll(pendingCheckInRemoved.map(Registration::key))
        addAll(temporaryAwayExpired.map(Registration::key))
        addAll(deferredCompleted.map(Registration::key))
        addAll(temporaryAwayAdvanced.map(Registration::key))
        addAll(semanticAbsenceKeys)
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
                timestampMillis = timestampMillis
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
        title = "$machineLabel · $title",
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
                    "${names(registrations)}本次未到场，记录已更新并暂缓一次；相关登记会在跳过本次机会后自动恢复。"
                } else {
                    "${names(registrations)}本次未到场，记录已更新；本次机会已跳过，暂缓状态已自动解除。"
                }
            }
            PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL ->
                if (
                    semanticAction is QueueAction.MarkNoShow &&
                    semanticAction.startNextWhenPlayingBecomesEmpty
                ) {
                    "${names(registrations)}本次未到场，记录已更新并移至等待顺序末端；本次自动安排不会再次选中相关登记。"
                } else {
                    "${names(registrations)}本次未到场，记录已更新并移至等待顺序末端；游玩位置保持当前状态，等待工作人员安排。"
                }
            else -> "${names(registrations)}本次未到场，相关登记已移除。"
        }
        entries += event(
            type = publicEventTypeOverride ?: PublicQueueEventType.REGISTRATION_UPDATED,
            title = when (publicEventTypeOverride) {
                PublicQueueEventType.NO_SHOW_DEFERRED -> if (stillDeferred) {
                    "未到场 · 已暂缓一次"
                } else {
                    "未到场 · 本次机会已跳过"
                }
                PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL -> "未到场 · 已移至队尾"
                else -> "未到场 · 已移除登记"
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
            "${names(temporaryAwayExpired)}在暂时离开期间第四次轮到，已自动退出排队。",
            temporaryAwayExpired.map(Registration::key)
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
                "暂时离开 · 已轮空 $skippedTurns 次",
                "${names(registrations)}本次已轮空并移至等待顺序末端，累计已轮空 $skippedTurns 次。",
                registrations.map(Registration::key)
            )
        }
    if (semanticAbsenceKeys.isNotEmpty()) {
        val registrations = semanticAbsenceKeys.mapNotNull(beforeByKey::get)
        when (semanticAction) {
            is QueueAction.DeferOneRound -> entries += event(
                PublicQueueEventType.ABSENCE_CHANGED,
                "暂缓一次已执行",
                "${names(registrations)}已跳过当前游玩机会并回到等待顺序前端，暂缓状态已随即解除。",
                semanticAbsenceKeys
            )
            is QueueAction.TemporarilyLeave -> entries += event(
                PublicQueueEventType.ABSENCE_CHANGED,
                "已设为暂时离开",
                "${names(registrations)}已离开游玩位置并移至等待顺序末端，累计已轮空 1 次；返回后需要手动取消暂时离开。",
                semanticAbsenceKeys
            )
            else -> Unit
        }
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
        timestampMillis = timestampMillis
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
            title = "新建玩家资料",
            detail = "已创建“${after.nickname}”，默认偏好为${profilePreferenceAuditLabel(after.defaultPreference)}。",
            source = source,
            timestampMillis = timestampMillis
        )
    }
    val details = buildList {
        if (before.nickname != after.nickname) add("昵称由“${before.nickname}”改为“${after.nickname}”")
        if (before.gender != after.gender) add("性别设置已修改")
        if (before.defaultPreference != after.defaultPreference) {
            add("默认偏好改为${profilePreferenceAuditLabel(after.defaultPreference)}")
        }
        if (before.qqNumber != after.qqNumber) {
            add("QQ 号已更新")
        }
    }
    return createAuditLogEntry(
        category = AuditLogCategory.PLAYER_PROFILE,
        title = "编辑玩家资料",
        detail = if (details.isEmpty()) "“${after.nickname}”的资料已保存。" else details.joinToString("；") + "。",
        source = source,
        timestampMillis = timestampMillis
    )
}

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
            "已将${quotedNames(registrations)}从$sourceMachineLabel 转至$destinationMachineLabel，" +
                "并加入目标机台的等待顺序末端"
        )
        if (registrations.any { it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND }) {
            if (releasedPartnerRegistrations.any {
                    it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
                }) {
                add("转入登记不再暂缓，留在原机台的登记仍会暂缓一次")
            } else {
                add("转入后不再暂缓")
            }
        }
        registrations.filter {
            it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
        }.maxOfOrNull(Registration::temporaryAwaySkippedTurns)?.let { skippedTurns ->
            add("暂时离开状态和已轮空 $skippedTurns 次会在转入后保留")
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
        }
    }
    return createAuditLogEntry(
        category = category,
        title = "登记已转至$destinationMachineLabel",
        detail = details.joinToString(separator = "；", postfix = "。"),
        source = source,
        timestampMillis = timestampMillis,
        publicEventType = PublicQueueEventType.REGISTRATION_UPDATED,
        affectedRegistrationKeys = affectedRegistrationKeys
    )
}

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
