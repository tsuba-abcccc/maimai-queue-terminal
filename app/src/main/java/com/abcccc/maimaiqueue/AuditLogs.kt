package com.abcccc.maimaiqueue

import java.util.UUID

enum class AuditLogCategory {
    MACHINE_A,
    MACHINE_B,
    SYSTEM,
    PLAYER_PROFILE
}

enum class AuditLogSource {
    ON_SITE_TERMINAL,
    QQ_BOT,
    SYSTEM_AUTOMATIC,
    WEBSITE_REMOTE
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
    ABSENCE_CHANGED,
    MACHINE_STOPPED,
    MACHINE_RESTORED,
    REGISTRATION_OPENED,
    REGISTRATION_CLOSED,
    QUEUE_RESTORED,
    QUEUE_RESET,
    OTHER
}

data class AuditLogEntry(
    val id: String,
    val timestampMillis: Long,
    val category: AuditLogCategory,
    val title: String,
    val detail: String,
    val source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL,
    val queueId: String? = null,
    val publicEventType: PublicQueueEventType? = null,
    val affectedRegistrationKeys: List<Int> = emptyList()
)

fun createAuditLogEntry(
    category: AuditLogCategory,
    title: String,
    detail: String,
    source: AuditLogSource = AuditLogSource.ON_SITE_TERMINAL,
    timestampMillis: Long = System.currentTimeMillis(),
    publicEventType: PublicQueueEventType? = null,
    affectedRegistrationKeys: Collection<Int> = emptyList()
): AuditLogEntry = AuditLogEntry(
    id = UUID.randomUUID().toString(),
    timestampMillis = timestampMillis,
    category = category,
    title = title,
    detail = detail,
    source = source,
    publicEventType = publicEventType,
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
    val temporaryAwayExpired = removed.filter {
        it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
            it.temporaryAwaySkippedTurns >= 3
    }
    val affectedRegistrationKeys = mutableSetOf<Int>().apply {
        addAll(added.map { it.key })
        addAll(removed.map { it.key })
        addAll(affectedRegistrationKeysOverride)
    }

    if (added.isNotEmpty()) {
        changeKinds += "added"
        details += "新增 ${quotedNames(added)}"
    }
    if (removed.isNotEmpty()) {
        changeKinds += "removed"
        details += if (publicEventTypeOverride == PublicQueueEventType.NO_SHOW_REMOVED) {
            "${quotedNames(removed)}本次未到场，登记已移除"
        } else {
            "移除 ${quotedNames(removed)}"
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
            details += "“${new.displayId}”的固定组合关系已变更"
        }
        if (old.absenceStatus != new.absenceStatus) {
            affectedRegistrationKeys += key
            changeKinds += "absence"
            details += when (new.absenceStatus) {
                QueueAbsenceStatus.DEFER_ONE_ROUND -> "“${new.displayId}”已暂缓一轮"
                QueueAbsenceStatus.TEMPORARILY_AWAY -> "“${new.displayId}”已设为暂时离开"
                QueueAbsenceStatus.NONE -> when (old.absenceStatus) {
                    QueueAbsenceStatus.DEFER_ONE_ROUND -> "“${new.displayId}”的暂缓一轮已解除"
                    QueueAbsenceStatus.TEMPORARILY_AWAY -> "“${new.displayId}”已取消暂时离开"
                    QueueAbsenceStatus.NONE -> "“${new.displayId}”的暂缓或暂离状态已恢复"
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
        details += "登记顺序已调整为${quotedNames(after.allRegistrations)}"
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
        PublicQueueEventType.NO_SHOW_DEFERRED -> "未到场 · 已暂缓一轮"
        PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL -> "未到场 · 已移至队尾"
        PublicQueueEventType.NO_SHOW_REMOVED -> "未到场 · 已移除登记"
        PublicQueueEventType.TEMPORARY_AWAY_EXPIRED -> "暂时离开已达轮空上限"
        else -> when {
            "added" in changeKinds && "removed" !in changeKinds -> "新增登记"
            "removed" in changeKinds && "added" !in changeKinds -> "移除登记"
            "claimed" in changeKinds -> "登记已认领"
            "renamed" in changeKinds -> "登记昵称已修改"
            "profile" in changeKinds -> "登记资料已更新"
            "no_show" in changeKinds -> "未到场状态已更新"
            "check_in" in changeKinds -> "线上登记签到状态已更新"
            "absence" in changeKinds -> "暂缓或暂离状态已修改"
            "pair" in changeKinds -> "固定组合已修改"
            "preference" in changeKinds -> "游玩偏好已修改"
            "playing" in changeKinds -> "游玩位置已更新"
            "order" in changeKinds -> "登记顺序已调整"
            "timer" in changeKinds -> "本轮计时已重置"
            else -> "队列已更新"
        }
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
        affectedRegistrationKeys = affectedRegistrationKeys
    )
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
            add("转入后，暂缓一轮状态已解除")
        }
        if (releasedPartnerRegistrations.isNotEmpty()) {
            add("原固定组合已解除，双方均恢复为允许他人加入")
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
