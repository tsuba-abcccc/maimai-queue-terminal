package com.abcccc.maimaiqueue

internal data class QueueUndoFeedbackOutcome(
    val detailLines: List<String>,
    val tone: HomeSidePanelFeedbackTone,
    val nonRestorableRegistrationKeys: Set<Int>
)

internal fun queueUndoFeedbackOutcome(
    beforeQueue: MachineQueue,
    afterQueue: MachineQueue
): QueueUndoFeedbackOutcome {
    val beforeByKey = beforeQueue.allRegistrations.associateBy { it.key }
    val afterByKey = afterQueue.allRegistrations.associateBy { it.key }
    val removed = beforeQueue.allRegistrations.filter { it.key !in afterByKey }
    val pendingCheckInRemoved = removed.filter { it.requiresOnSiteCheckIn }
    val temporaryAwayExpired = removed.filter { registration ->
        registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
            registration.temporaryAwaySkippedTurns >= 3
    }
    val deferredCleared = afterQueue.allRegistrations.filter { registration ->
        beforeByKey[registration.key]?.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND &&
            registration.absenceStatus == QueueAbsenceStatus.NONE
    }
    val temporaryAwayAdvanced = afterQueue.allRegistrations.filter { registration ->
        val before = beforeByKey[registration.key]
        registration.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
            before?.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
            registration.temporaryAwaySkippedTurns > before.temporaryAwaySkippedTurns
    }

    fun quotedNames(registrations: List<Registration>): String =
        registrations.joinToString("、") { "“${it.displayId}”" }

    val detailLines = buildList {
        if (pendingCheckInRemoved.isNotEmpty()) {
            add(
                "${quotedNames(pendingCheckInRemoved)}尚未完成现场签到，相关登记已退出排队；撤销本轮操作时不会恢复。"
            )
        }
        if (deferredCleared.isNotEmpty()) {
            add(
                "${quotedNames(deferredCleared)}已跳过本次机会，随后自动恢复；真实等待顺序未改变，画面位置已按后续轮换重新计算。"
            )
        }
        temporaryAwayAdvanced
            .groupBy { it.temporaryAwaySkippedTurns }
            .toSortedMap()
            .forEach { (skippedTurns, registrations) ->
                add(
                    "${quotedNames(registrations)}本轮已轮空，累计已轮空 $skippedTurns 次，并移至等待顺序末端。"
                )
            }
        if (temporaryAwayExpired.isNotEmpty()) {
            add(
                "${quotedNames(temporaryAwayExpired)}在暂时离开期间第四次轮到，已自动退出排队；撤销时会恢复到操作前的状态。"
            )
        }
    }
    val nonRestorableRegistrationKeys = pendingCheckInRemoved.mapTo(mutableSetOf()) { it.key }
    return QueueUndoFeedbackOutcome(
        detailLines = detailLines,
        tone = if (pendingCheckInRemoved.isNotEmpty() || temporaryAwayExpired.isNotEmpty()) {
            HomeSidePanelFeedbackTone.WARNING
        } else {
            HomeSidePanelFeedbackTone.SUCCESS
        },
        nonRestorableRegistrationKeys = nonRestorableRegistrationKeys
    )
}
