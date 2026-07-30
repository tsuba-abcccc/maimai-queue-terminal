package com.abcccc.maimaiqueue

import kotlin.math.abs

/**
 * Describes structural queue problems that must never be produced by a user action.
 * The engine tests use this after action sequences; later QueueEngine work can reuse it
 * at command boundaries without duplicating the rules.
 */
internal fun MachineQueue.invariantViolations(): List<String> = buildList {
    val registrations = allRegistrations
    val registrationsByKey = registrations.associateBy(Registration::key)

    if (playing.size > 2) add("游玩位置不能超过 2 个登记")
    if (registrations.any { it.key <= 0 }) add("登记编号必须为正数")
    if (registrations.any { it.displayId.isBlank() }) add("登记昵称不能为空")
    if (registrations.map(Registration::key).distinct().size != registrations.size) {
        add("同一机台不能出现重复登记编号")
    }
    if (playing.any(Registration::requiresOnSiteCheckIn)) {
        add("待签到线上登记不能进入游玩位置")
    }

    registrations.forEach { registration ->
        if (registration.temporaryAwaySkippedTurns !in 0..3) {
            add("暂时离开轮空次数必须在 0 到 3 次之间")
        }
        if (
            registration.absenceStatus != QueueAbsenceStatus.TEMPORARILY_AWAY &&
            registration.temporaryAwaySkippedTurns != 0
        ) {
            add("非暂时离开登记不能保留轮空次数")
        }
        if (registration.lastNoShowActionWasDefer && registration.noShowCount <= 0) {
            add("没有未到场记录时不能保留未到场处理方式")
        }
        if (
            registration.requiresOnSiteCheckIn &&
            registration.absenceStatus != QueueAbsenceStatus.NONE
        ) {
            add("待签到线上登记不能同时暂缓或暂时离开")
        }

        val partnerKey = registration.fixedPartnerKey ?: return@forEach
        val partner = registrationsByKey[partnerKey]
        if (partner == null || partner.fixedPartnerKey != registration.key) {
            add("固定组合关系必须双向对应")
            return@forEach
        }
        val sameSection = (registration in playing) == (partner in playing)
        val section = if (registration in playing) playing else waiting
        val adjacent = abs(
            section.indexOfFirst { it.key == registration.key } -
                section.indexOfFirst { it.key == partner.key }
        ) == 1
        if (!sameSection || !adjacent) add("固定组合必须位于同一位置且彼此相邻")
        if (
            registration.preference != PlayPreference.OPEN_TO_JOIN ||
            partner.preference != PlayPreference.OPEN_TO_JOIN
        ) {
            add("固定组合必须使用允许加入的队列结构")
        }
        if (
            registration.absenceStatus != partner.absenceStatus ||
            registration.temporaryAwaySkippedTurns != partner.temporaryAwaySkippedTurns
        ) {
            add("固定组合的暂缓或暂离状态必须一致")
        }
        if (registration.requiresOnSiteCheckIn || partner.requiresOnSiteCheckIn) {
            add("待签到线上登记不能建立固定组合")
        }
    }
}.distinct()
