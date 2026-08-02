package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueHomeFeedbackTest {
    private fun registration(
        key: Int,
        name: String,
        absenceStatus: QueueAbsenceStatus = QueueAbsenceStatus.NONE,
        skippedTurns: Int = 0,
        pendingCheckIn: Boolean = false
    ) = Registration(
        key = key,
        displayId = name,
        preference = PlayPreference.OPEN_TO_JOIN,
        absenceStatus = absenceStatus,
        temporaryAwaySkippedTurns = skippedTurns,
        requiresOnSiteCheckIn = pendingCheckIn
    )

    @Test
    fun pendingCheckInRemovalIsWarnedAndCannotBeRestored() {
        val pending = registration(1, "青空", pendingCheckIn = true)
        val remaining = registration(2, "北川")

        val outcome = queueUndoFeedbackOutcome(
            beforeQueue = MachineQueue(waiting = listOf(pending, remaining)),
            afterQueue = MachineQueue(waiting = listOf(remaining))
        )

        assertEquals(HomeSidePanelFeedbackTone.WARNING, outcome.tone)
        assertEquals(setOf(1), outcome.nonRestorableRegistrationKeys)
        assertTrue(outcome.detailLines.single().contains("撤销本轮操作时不会恢复"))
    }

    @Test
    fun deferredRegistrationExplainsAutomaticCancellation() {
        val deferred = registration(
            1,
            "青空",
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )

        val outcome = queueUndoFeedbackOutcome(
            beforeQueue = MachineQueue(waiting = listOf(deferred)),
            afterQueue = MachineQueue(waiting = listOf(deferred.copy(absenceStatus = QueueAbsenceStatus.NONE)))
        )

        assertEquals(HomeSidePanelFeedbackTone.SUCCESS, outcome.tone)
        assertTrue(outcome.detailLines.single().contains("已跳过本次机会，随后自动恢复"))
        assertTrue(outcome.detailLines.single().contains("真实等待顺序未改变"))
    }

    @Test
    fun temporaryAwayAdvancesAreGroupedBySkippedCount() {
        val first = registration(
            1,
            "青空",
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            skippedTurns = 1
        )
        val second = registration(
            2,
            "北川",
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            skippedTurns = 1
        )

        val outcome = queueUndoFeedbackOutcome(
            beforeQueue = MachineQueue(waiting = listOf(first, second)),
            afterQueue = MachineQueue(
                waiting = listOf(
                    first.copy(temporaryAwaySkippedTurns = 2),
                    second.copy(temporaryAwaySkippedTurns = 2)
                )
            )
        )

        assertEquals(1, outcome.detailLines.size)
        assertTrue(outcome.detailLines.single().contains("“青空”、“北川”"))
        assertTrue(outcome.detailLines.single().contains("累计已轮空 2 次"))
    }

    @Test
    fun fourthTemporaryAwayTurnWarnsButRemainsUndoable() {
        val expired = registration(
            1,
            "青空",
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            skippedTurns = 3
        )

        val outcome = queueUndoFeedbackOutcome(
            beforeQueue = MachineQueue(waiting = listOf(expired)),
            afterQueue = MachineQueue()
        )

        assertEquals(HomeSidePanelFeedbackTone.WARNING, outcome.tone)
        assertTrue(outcome.nonRestorableRegistrationKeys.isEmpty())
        assertTrue(outcome.detailLines.single().contains("第四次轮到"))
        assertTrue(outcome.detailLines.single().contains("撤销时会恢复"))
    }

    @Test
    fun mixedAvailabilityResultsStaySeparateAndUndoExcludesOnlyMissedCheckIn() {
        val current = registration(9, "本轮玩家").copy(preference = PlayPreference.SOLO)
        val pending = registration(1, "未签到玩家", pendingCheckIn = true)
            .copy(preference = PlayPreference.SOLO)
        val deferred = registration(
            2,
            "暂缓玩家",
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        ).copy(preference = PlayPreference.SOLO)
        val away = registration(
            3,
            "暂离玩家",
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            skippedTurns = 1
        ).copy(preference = PlayPreference.SOLO)
        val next = registration(4, "下一位玩家").copy(preference = PlayPreference.SOLO)
        val before = MachineQueue(
            playing = listOf(current),
            waiting = listOf(pending, deferred, away, next),
            playingStartedAtMillis = 1_000L
        )
        val after = RoundPlanner.finishRound(before).execute(atMillis = 10_000L)

        val outcome = queueUndoFeedbackOutcome(before, after)

        assertEquals(3, outcome.detailLines.size)
        assertTrue(outcome.detailLines.any {
            "未签到玩家" in it && "暂缓玩家" !in it && "暂离玩家" !in it
        })
        assertTrue(outcome.detailLines.any {
            "暂缓玩家" in it && "未签到玩家" !in it && "暂离玩家" !in it
        })
        assertTrue(outcome.detailLines.any {
            "暂离玩家" in it && "未签到玩家" !in it && "暂缓玩家" !in it
        })
        assertEquals(setOf(pending.key), outcome.nonRestorableRegistrationKeys)

        val restored = QueueEngine.execute(
            QueueEngineState.single("A", after),
            QueueAction.RestoreSnapshot(
                machineId = "A",
                expectedCurrentQueue = after,
                restoredQueue = before,
                excludedRegistrationKeys = outcome.nonRestorableRegistrationKeys
            ),
            QueueActionContext(origin = QueueActionOrigin.SYSTEM)
        ) as QueueActionExecution.Applied
        val restoredQueue = restored.state.queue("A")!!

        assertTrue(restoredQueue.allRegistrations.none { it.key == pending.key })
        assertEquals(
            QueueAbsenceStatus.DEFER_ONE_ROUND,
            restoredQueue.allRegistrations.first { it.key == deferred.key }.absenceStatus
        )
        assertEquals(
            QueueAbsenceStatus.TEMPORARILY_AWAY,
            restoredQueue.allRegistrations.first { it.key == away.key }.absenceStatus
        )
        assertEquals(1, restoredQueue.allRegistrations
            .first { it.key == away.key }.temporaryAwaySkippedTurns)
    }
}
