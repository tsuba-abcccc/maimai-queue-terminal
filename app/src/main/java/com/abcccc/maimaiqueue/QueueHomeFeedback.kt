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
    // 轮空一次 shares the count of skipped turns with 暂时离开 (2026-11-25).
    val skipExpired = removed.filter { registration ->
        registration.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN &&
            registration.temporaryAwaySkippedTurns >= 3
    }
    val skipCompleted = afterQueue.allRegistrations.filter { registration ->
        val before = beforeByKey[registration.key]
        before?.absenceStatus == QueueAbsenceStatus.SKIP_ONE_TURN &&
            registration.absenceStatus == QueueAbsenceStatus.NONE &&
            registration.temporaryAwaySkippedTurns > before.temporaryAwaySkippedTurns
    }

    fun quotedNames(registrations: List<Registration>): String =
        registrations.joinToString("、") { "“${it.displayId}”" }

    val detailLines = buildList {
        if (pendingCheckInRemoved.isNotEmpty()) {
            add(
                "${quotedNames(pendingCheckInRemoved)}尚未完成现场签到，相关登记已自动移除；撤销本轮操作时不会恢复。"
            )
        }
        if (deferredCleared.isNotEmpty()) {
            add(
                "${quotedNames(deferredCleared)}已跳过本次机会，暂缓状态随后自动解除；真实等待顺序未改变。"
            )
        }
        temporaryAwayAdvanced
            .groupBy { it.temporaryAwaySkippedTurns }
            .toSortedMap()
            .forEach { (skippedTurns, registrations) ->
                add(
                    "${quotedNames(registrations)}本次已轮空并移至队尾，累计已轮空 $skippedTurns 次。"
                )
            }
        if (temporaryAwayExpired.isNotEmpty()) {
            add(
                "${quotedNames(temporaryAwayExpired)}在暂时离开期间第 4 次轮到，已自动移除；撤销时会恢复到操作前的状态。"
            )
        }
        skipCompleted
            .groupBy { it.temporaryAwaySkippedTurns }
            .toSortedMap()
            .forEach { (skippedTurns, registrations) ->
                add(
                    "${quotedNames(registrations)}本次已轮空并移至队尾，累计已轮空 $skippedTurns 次；轮空一次随即自动解除。"
                )
            }
        if (skipExpired.isNotEmpty()) {
            add(
                "${quotedNames(skipExpired)}已轮空 3 次，再次轮到时已自动移除；撤销时会恢复到操作前的状态。"
            )
        }
    }
    val nonRestorableRegistrationKeys = pendingCheckInRemoved.mapTo(mutableSetOf()) { it.key }
    return QueueUndoFeedbackOutcome(
        detailLines = detailLines,
        tone = if (
            pendingCheckInRemoved.isNotEmpty() ||
            temporaryAwayExpired.isNotEmpty() ||
            skipExpired.isNotEmpty()
        ) {
            HomeSidePanelFeedbackTone.WARNING
        } else {
            HomeSidePanelFeedbackTone.SUCCESS
        },
        nonRestorableRegistrationKeys = nonRestorableRegistrationKeys
    )
}
