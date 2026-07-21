package com.abcccc.maimaiqueue

import java.util.UUID

enum class AuditLogCategory {
    MACHINE_A,
    MACHINE_B,
    SYSTEM,
    PLAYER_PROFILE
}

data class AuditLogEntry(
    val id: String,
    val timestampMillis: Long,
    val category: AuditLogCategory,
    val title: String,
    val detail: String
)

fun createAuditLogEntry(
    category: AuditLogCategory,
    title: String,
    detail: String,
    timestampMillis: Long = System.currentTimeMillis()
): AuditLogEntry = AuditLogEntry(
    id = UUID.randomUUID().toString(),
    timestampMillis = timestampMillis,
    category = category,
    title = title,
    detail = detail
)

fun createQueueAuditLog(
    category: AuditLogCategory,
    machineLabel: String,
    before: MachineQueue,
    after: MachineQueue,
    titleOverride: String? = null,
    timestampMillis: Long = System.currentTimeMillis()
): AuditLogEntry? {
    if (before == after) return null
    val details = mutableListOf<String>()
    val changeKinds = mutableSetOf<String>()
    val beforeByKey = before.allRegistrations.associateBy { it.key }
    val afterByKey = after.allRegistrations.associateBy { it.key }
    val added = after.allRegistrations.filter { it.key !in beforeByKey }
    val removed = before.allRegistrations.filter { it.key !in afterByKey }

    if (added.isNotEmpty()) {
        changeKinds += "added"
        details += "新增 ${quotedNames(added)}"
    }
    if (removed.isNotEmpty()) {
        changeKinds += "removed"
        details += "移除 ${quotedNames(removed)}"
    }

    beforeByKey.keys.intersect(afterByKey.keys).forEach { key ->
        val old = beforeByKey.getValue(key)
        val new = afterByKey.getValue(key)
        if (old.displayId != new.displayId) {
            changeKinds += "renamed"
            details += "“${old.displayId}”更名为“${new.displayId}”"
        }
        if (old.preference != new.preference) {
            changeKinds += "preference"
            details += "“${new.displayId}”改为${queuePreferenceLabel(new.preference)}"
        }
        if (old.fixedPartnerKey != new.fixedPartnerKey) {
            changeKinds += "pair"
            details += "“${new.displayId}”的固定组合关系已变更"
        }
        if (old.deferredOnce != new.deferredOnce) {
            changeKinds += "deferred"
            details += if (new.deferredOnce) {
                "“${new.displayId}”已暂缓一次"
            } else {
                "“${new.displayId}”已取消暂缓"
            }
        }
        if (old.isTemporary && !new.isTemporary) {
            changeKinds += "claimed"
            details += "“${new.displayId}”已认领登记"
        }
        if (
            old.playerProfileId != new.playerProfileId &&
            new.playerProfileId != null &&
            !(old.isTemporary && !new.isTemporary)
        ) {
            changeKinds += "claimed"
            details += "“${new.displayId}”已关联玩家资料"
        }
        if (new.noShowCount > old.noShowCount) {
            changeKinds += "no_show"
            details += "“${new.displayId}”已记录第 ${new.noShowCount} 次未到场"
        }
    }

    if (before.playing.map { it.key } != after.playing.map { it.key }) {
        changeKinds += "playing"
        details += "游玩位置由${positionNames(before.playing)}变为${positionNames(after.playing)}"
    }
    val beforeOrder = before.allRegistrations.map { it.key }
    val afterOrder = after.allRegistrations.map { it.key }
    if (beforeOrder.toSet() == afterOrder.toSet() && beforeOrder != afterOrder) {
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

    val generatedTitle = when {
        "added" in changeKinds && "removed" !in changeKinds -> "新增登记"
        "removed" in changeKinds && "added" !in changeKinds -> "移除登记"
        "renamed" in changeKinds -> "登记名称已修改"
        "claimed" in changeKinds -> "登记已认领"
        "deferred" in changeKinds -> "暂缓状态已修改"
        "no_show" in changeKinds -> "未到场状态已更新"
        "preference" in changeKinds -> "游玩偏好已修改"
        "pair" in changeKinds -> "固定组合已修改"
        "playing" in changeKinds -> "游玩位置已更新"
        "order" in changeKinds -> "登记顺序已调整"
        "timer" in changeKinds -> "本轮计时已重置"
        else -> "队列已更新"
    }
    return createAuditLogEntry(
        category = category,
        title = titleOverride ?: "$machineLabel · $generatedTitle",
        detail = if (details.isEmpty()) {
            "队列状态发生变化。"
        } else {
            details.distinct().joinToString(separator = "；", postfix = "。")
        },
        timestampMillis = timestampMillis
    )
}

fun createPlayerProfileAuditLog(
    before: PlayerProfile?,
    after: PlayerProfile,
    timestampMillis: Long = System.currentTimeMillis()
): AuditLogEntry {
    if (before == null) {
        return createAuditLogEntry(
            category = AuditLogCategory.PLAYER_PROFILE,
            title = "新建玩家资料",
            detail = "已创建“${after.nickname}”，默认偏好为${profilePreferenceAuditLabel(after.defaultPreference)}。",
            timestampMillis = timestampMillis
        )
    }
    val details = buildList {
        if (before.nickname != after.nickname) add("昵称由“${before.nickname}”改为“${after.nickname}”")
        if (before.gender != after.gender) add("性别设置已修改")
        if (before.defaultPreference != after.defaultPreference) {
            add("默认偏好改为${profilePreferenceAuditLabel(after.defaultPreference)}")
        }
        if (before.qqNumber != after.qqNumber || before.phoneNumber != after.phoneNumber) {
            add("联系方式已更新")
        }
    }
    return createAuditLogEntry(
        category = AuditLogCategory.PLAYER_PROFILE,
        title = "编辑玩家资料",
        detail = if (details.isEmpty()) "“${after.nickname}”的资料已保存。" else details.joinToString("；") + "。",
        timestampMillis = timestampMillis
    )
}

private fun quotedNames(registrations: List<Registration>): String =
    registrations.joinToString("、") { "“${it.displayId}”" }.ifEmpty { "空" }

private fun positionNames(registrations: List<Registration>): String =
    if (registrations.isEmpty()) "空" else quotedNames(registrations)

private fun queuePreferenceLabel(preference: PlayPreference): String = when (preference) {
    PlayPreference.SOLO -> "单人游玩"
    PlayPreference.OPEN_TO_JOIN -> "允许他人加入"
}

private fun profilePreferenceAuditLabel(preference: ProfilePlayPreference): String = when (preference) {
    ProfilePlayPreference.SOLO -> "单人游玩"
    ProfilePlayPreference.OPEN_TO_JOIN -> "允许他人加入"
    ProfilePlayPreference.ASK_EVERY_TIME -> "每次询问"
}
