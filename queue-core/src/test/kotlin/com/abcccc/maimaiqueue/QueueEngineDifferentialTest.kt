package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueEngineDifferentialTest {
    private val machineA = "A"
    private val machineB = "B"
    private val atMillis = 700_000L

    private fun registration(
        key: Int,
        preference: PlayPreference = PlayPreference.OPEN_TO_JOIN,
        pendingCheckIn: Boolean = false,
        profileId: String? = null
    ) = Registration(
        key = key,
        displayId = "玩家$key",
        preference = preference,
        isTemporary = profileId == null,
        createdAtMillis = key * 1_000L,
        gender = PlayerGender.UNDISCLOSED,
        playerProfileId = profileId,
        requiresOnSiteCheckIn = pendingCheckIn,
        onSiteCheckInStartedAtMillis = if (pendingCheckIn) key * 1_000L else null
    )

    private fun appliedQueue(
        queue: MachineQueue,
        action: QueueAction,
        policy: QueueEnginePolicy = QueueEnginePolicy()
    ): MachineQueue {
        val state = QueueEngineState.single(machineA, queue)
        val execution = QueueEngine.execute(
            state,
            action,
            QueueActionContext(atMillis = atMillis, policy = policy)
        )
        assertTrue("动作应成功：$action，实际为 $execution", execution is QueueActionExecution.Applied)
        return (execution as QueueActionExecution.Applied).state.queue(machineA)!!
    }

    @Test
    fun registrationPlacementsMatchExistingQueueOperations() {
        val first = registration(1, PlayPreference.SOLO)
        val second = registration(2)
        val initial = MachineQueue()

        assertEquals(
            initial.joinAll(listOf(first, second), atMillis),
            appliedQueue(
                initial,
                QueueAction.AddRegistrations(
                    machineA,
                    listOf(first, second),
                    RegistrationPlacement.AUTO_ADVANCE
                )
            )
        )

        val occupied = MachineQueue(playing = listOf(first), playingStartedAtMillis = 5_000L)
        assertEquals(
            occupied.receiveAtWaitingTail(listOf(second)),
            appliedQueue(
                occupied,
                QueueAction.AddRegistrations(
                    machineA,
                    listOf(second),
                    RegistrationPlacement.WAITING_TAIL
                )
            )
        )
        assertEquals(
            initial.stageWaiting(first),
            appliedQueue(
                initial,
                QueueAction.AddRegistrations(
                    machineA,
                    listOf(first),
                    RegistrationPlacement.STAGED_WAITING
                )
            )
        )
    }

    @Test
    fun roundActionsMatchRoundPlannerAtTheSameConfirmationTime() {
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO, pendingCheckIn = true),
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(3, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                ),
                registration(4, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 3_000L
        )

        assertEquals(
            RoundPlanner.finishRound(queue).execute(atMillis),
            appliedQueue(queue, QueueAction.FinishRound(machineA))
        )
        assertEquals(
            RoundPlanner.endRoundOnly(queue).execute(atMillis),
            appliedQueue(queue, QueueAction.EndRoundOnly(machineA))
        )
        assertEquals(
            RoundPlanner.removeCurrentRoundAndStartNext(queue).execute(atMillis),
            appliedQueue(queue, QueueAction.RemoveCurrentRoundAndStartNext(machineA))
        )

        val emptyPlaying = queue.copy(playing = emptyList(), playingStartedAtMillis = null)
        assertEquals(
            RoundPlanner.enterPlayingPosition(emptyPlaying).execute(atMillis),
            appliedQueue(emptyPlaying, QueueAction.EnterPlayingPosition(machineA))
        )
    }

    @Test
    fun timerRestartActionsMatchMachineRecoveryBehavior() {
        val playing = registration(1, PlayPreference.SOLO)
        val checkedIn = registration(2, PlayPreference.SOLO)
        val pending = registration(3, PlayPreference.SOLO, pendingCheckIn = true)
        val queue = MachineQueue(
            playing = listOf(playing),
            waiting = listOf(checkedIn, pending),
            playingStartedAtMillis = 2_000L
        )

        assertEquals(
            queue.restartPlayingTimer(atMillis),
            appliedQueue(queue, QueueAction.RestartPlayingTimer(machineA))
        )
        assertEquals(
            queue.restartPendingCheckInTimers(atMillis),
            appliedQueue(queue, QueueAction.RestartPendingCheckInTimers(machineA))
        )
        assertEquals(
            queue.restartPlayingTimer(atMillis).restartPendingCheckInTimers(atMillis),
            appliedQueue(queue, QueueAction.RestartMachineTimers(machineA))
        )
    }

    @Test
    fun absencePreferenceAndCheckInActionsMatchExistingOperations() {
        val normal = registration(1, PlayPreference.SOLO)
        val pending = registration(2, PlayPreference.SOLO, pendingCheckIn = true)
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(normal, pending),
            playingStartedAtMillis = 2_000L
        )

        val deferred = appliedQueue(queue, QueueAction.DeferOneRound(machineA, normal.key))
        assertEquals(queue.deferOneRound(normal.key), deferred)
        assertEquals(
            deferred.cancelDeferOneRound(normal.key),
            appliedQueue(deferred, QueueAction.CancelDeferOneRound(machineA, normal.key))
        )

        val away = appliedQueue(queue, QueueAction.TemporarilyLeave(machineA, normal.key))
        assertEquals(queue.temporarilyLeave(normal.key), away)
        assertEquals(
            away.cancelTemporaryLeave(normal.key),
            appliedQueue(away, QueueAction.CancelTemporaryLeave(machineA, normal.key))
        )

        assertEquals(
            queue.changePreference(normal.key, PlayPreference.OPEN_TO_JOIN),
            appliedQueue(
                queue,
                QueueAction.ChangePreference(machineA, normal.key, PlayPreference.OPEN_TO_JOIN)
            )
        )
        assertEquals(
            queue.checkIn(pending.key),
            appliedQueue(queue, QueueAction.CheckIn(machineA, pending.key))
        )
    }

    @Test
    fun fixedPairActionsMatchExistingOperations() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1),
                registration(2),
                registration(3, PlayPreference.SOLO)
            )
        )
        val plan = requireNotNull(queue.planFriendPair(1, 2))

        assertEquals(
            queue.applyFriendPair(plan),
            appliedQueue(queue, QueueAction.CreateFixedPair(machineA, 1, 2, plan))
        )

        val friend = registration(4)
        assertEquals(
            queue.createFriendPair(1, friend),
            appliedQueue(queue, QueueAction.CreateFixedPairWithRegistration(machineA, 1, friend))
        )
    }

    @Test
    fun fixedPairCreationAndAutomaticAdvanceRemainOneAtomicAction() {
        val first = registration(1)
        val friend = registration(2)
        val queue = MachineQueue(waiting = listOf(first))
        val paired = queue.createFriendPair(first.key, friend)
        val expected = RoundPlanner.enterPlayingPosition(paired).execute(atMillis)

        val actual = appliedQueue(
            queue,
            QueueAction.CreateFixedPairWithRegistration(
                machineId = machineA,
                registrationKey = first.key,
                friend = friend,
                advanceWhenPlayingEmpty = true
            )
        )

        assertEquals(expected, actual)
        assertEquals(listOf(first.key, friend.key), actual.playing.map(Registration::key))
    }

    @Test
    fun profileAndIdentityActionsMatchExistingOperations() {
        val profileId = "profile-1"
        val linked = registration(1, profileId = profileId).copy(displayId = "旧昵称")
        val temporary = registration(2)
        val queue = MachineQueue(waiting = listOf(linked, temporary))

        assertEquals(
            queue.syncPlayerProfileDetails(profileId, "新昵称", PlayerGender.FEMALE),
            appliedQueue(
                queue,
                QueueAction.SyncPlayerProfileDetails(
                    machineA,
                    profileId,
                    "新昵称",
                    PlayerGender.FEMALE
                )
            )
        )
        assertEquals(
            queue.rename(temporary.key, "临时玩家"),
            appliedQueue(queue, QueueAction.RenameRegistration(machineA, temporary.key, "临时玩家"))
        )
        assertEquals(
            queue.claim(temporary.key),
            appliedQueue(queue, QueueAction.ClaimRegistration(machineA, temporary.key))
        )
        assertEquals(
            queue.claimWithPlayerProfile(
                temporary.key,
                "profile-2",
                "正式玩家",
                PlayerGender.MALE,
                PlayPreference.SOLO
            ),
            appliedQueue(
                queue,
                QueueAction.ClaimWithPlayerProfile(
                    machineA,
                    temporary.key,
                    "profile-2",
                    "正式玩家",
                    PlayerGender.MALE,
                    PlayPreference.SOLO
                )
            )
        )
    }

    @Test
    fun noShowRemovalAndTimeoutActionsMatchExistingOperations() {
        val playing = registration(1, PlayPreference.SOLO)
        val pending = registration(2, PlayPreference.SOLO, pendingCheckIn = true).copy(
            onSiteCheckInStartedAtMillis = atMillis - ONLINE_REGISTRATION_CHECK_IN_TIMEOUT_MILLIS
        )
        val next = registration(3, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(playing),
            waiting = listOf(pending, next),
            playingStartedAtMillis = 5_000L
        )

        assertEquals(
            queue.markNoShowMoveToEnd(setOf(playing.key), false),
            appliedQueue(
                queue,
                QueueAction.MarkNoShow(
                    machineA,
                    setOf(playing.key),
                    NoShowResolution.MOVE_TO_TAIL,
                    startNextWhenPlayingBecomesEmpty = false
                )
            )
        )
        assertEquals(
            queue.removeAll(setOf(next.key)),
            appliedQueue(queue, QueueAction.RemoveRegistrations(machineA, setOf(next.key)))
        )
        assertEquals(
            queue.removeExpiredOnlineRegistrations(atMillis),
            appliedQueue(queue, QueueAction.RemoveExpiredOnlineRegistrations(machineA))
        )
    }

    @Test
    fun correctionAndReorderActionsMatchExistingOperations() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO),
                registration(4, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 5_000L
        )

        assertEquals(
            queue.returnPlayingRegistrationsToWaitingFront(setOf(1)),
            appliedQueue(queue, QueueAction.ReturnPlayingToWaitingFront(machineA, setOf(1)))
        )
        assertEquals(
            queue.moveFirstWaitingRegistrationIntoCurrentRound(2),
            appliedQueue(queue, QueueAction.MoveWaitingRegistrationIntoCurrentRound(machineA, 2))
        )
        assertEquals(
            queue.advanceToWaitingPosition(setOf(3), atMillis),
            appliedQueue(queue, QueueAction.AdvanceToWaitingPosition(machineA, setOf(3)))
        )
        assertEquals(
            queue.moveWaitingPosition(0, 2),
            appliedQueue(queue, QueueAction.MoveWaitingPosition(machineA, 0, 2))
        )
        val proposed = queue.playing + queue.waiting.reversed()
        assertEquals(
            queue.replaceOrder(proposed),
            appliedQueue(queue, QueueAction.ReplaceOrder(machineA, proposed))
        )
    }

    @Test
    fun transferIsAtomicAndMatchesExistingTwoQueueOperation() {
        val moving = registration(2, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val source = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(moving),
            playingStartedAtMillis = 4_000L
        )
        val destination = MachineQueue(waiting = listOf(registration(3, PlayPreference.SOLO)))
        val state = QueueEngineState(mapOf(machineA to source, machineB to destination))

        val execution = QueueEngine.execute(
            state,
            QueueAction.TransferRegistrations(machineA, machineB, setOf(moving.key)),
            QueueActionContext(atMillis = atMillis)
        )
        assertTrue(execution is QueueActionExecution.Applied)
        val result = (execution as QueueActionExecution.Applied).state

        assertEquals(source.removeAll(setOf(moving.key)), result.queue(machineA))
        assertEquals(destination.receiveAtWaitingTail(listOf(moving)), result.queue(machineB))
        assertEquals(setOf(machineA, machineB), execution.impact.changedMachineIds)
    }

    @Test
    fun fixedPairAbsenceActionsRemainSymmetricFromEitherMember() {
        val first = registration(1).copy(fixedPartnerKey = 2)
        val second = registration(2).copy(fixedPartnerKey = 1)
        val queue = MachineQueue(waiting = listOf(first, second))

        val deferred = appliedQueue(queue, QueueAction.DeferOneRound(machineA, second.key))
        assertTrue(deferred.waiting.all {
            it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
        })

        val resumed = appliedQueue(
            deferred,
            QueueAction.CancelDeferOneRound(machineA, first.key)
        )
        assertTrue(resumed.waiting.all { it.absenceStatus == QueueAbsenceStatus.NONE })

        val away = appliedQueue(resumed, QueueAction.TemporarilyLeave(machineA, first.key))
        assertTrue(away.waiting.all {
            it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
        })

        val returned = appliedQueue(
            away,
            QueueAction.CancelTemporaryLeave(machineA, second.key)
        )
        assertTrue(returned.waiting.all { it.absenceStatus == QueueAbsenceStatus.NONE })
        assertTrue(returned.invariantViolations().isEmpty())
    }

    @Test
    fun transferringOneFixedPairMemberReleasesBothWithoutLosingAbsenceRules() {
        val first = registration(1).copy(
            fixedPartnerKey = 2,
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 2
        )
        val second = registration(2).copy(
            fixedPartnerKey = 1,
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 2
        )
        val source = MachineQueue(waiting = listOf(first, second))
        val destination = MachineQueue()
        val state = QueueEngineState(mapOf(machineA to source, machineB to destination))

        val result = QueueEngine.execute(
            state,
            QueueAction.TransferRegistrations(machineA, machineB, setOf(first.key)),
            QueueActionContext(atMillis = atMillis)
        ) as QueueActionExecution.Applied

        val remaining = result.state.queue(machineA)!!.waiting.single()
        val transferred = result.state.queue(machineB)!!.waiting.single()
        assertEquals(null, remaining.fixedPartnerKey)
        assertEquals(null, transferred.fixedPartnerKey)
        assertEquals(PlayPreference.OPEN_TO_JOIN, remaining.preference)
        assertEquals(PlayPreference.OPEN_TO_JOIN, transferred.preference)
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, remaining.absenceStatus)
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, transferred.absenceStatus)
        assertEquals(2, remaining.temporaryAwaySkippedTurns)
        assertEquals(2, transferred.temporaryAwaySkippedTurns)
        assertTrue(result.state.invariantViolations().isEmpty())
    }

    @Test
    fun transferClearsOnlyTheTransferredRegistrationsOneRoundDeferState() {
        val first = registration(1).copy(
            fixedPartnerKey = 2,
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val second = registration(2).copy(
            fixedPartnerKey = 1,
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val state = QueueEngineState(
            mapOf(
                machineA to MachineQueue(waiting = listOf(first, second)),
                machineB to MachineQueue()
            )
        )

        val result = QueueEngine.execute(
            state,
            QueueAction.TransferRegistrations(machineA, machineB, setOf(first.key))
        ) as QueueActionExecution.Applied

        assertEquals(
            QueueAbsenceStatus.DEFER_ONE_ROUND,
            result.state.queue(machineA)!!.waiting.single().absenceStatus
        )
        assertEquals(
            QueueAbsenceStatus.NONE,
            result.state.queue(machineB)!!.waiting.single().absenceStatus
        )
    }

    @Test
    fun transferringAWholeFixedPairKeepsThePairAndAppliesTransferAbsenceRules() {
        listOf(
            QueueAbsenceStatus.DEFER_ONE_ROUND to 0,
            QueueAbsenceStatus.TEMPORARILY_AWAY to 2
        ).forEach { (absenceStatus, skippedTurns) ->
            val first = registration(1).copy(
                fixedPartnerKey = 2,
                absenceStatus = absenceStatus,
                temporaryAwaySkippedTurns = skippedTurns
            )
            val second = registration(2).copy(
                fixedPartnerKey = 1,
                absenceStatus = absenceStatus,
                temporaryAwaySkippedTurns = skippedTurns
            )
            val state = QueueEngineState(
                mapOf(
                    machineA to MachineQueue(waiting = listOf(first, second)),
                    machineB to MachineQueue(waiting = listOf(registration(3)))
                )
            )

            val execution = QueueEngine.execute(
                state,
                QueueAction.TransferRegistrations(machineA, machineB, setOf(1, 2))
            ) as QueueActionExecution.Applied
            val transferred = execution.state.queue(machineB)!!.waiting.takeLast(2)
            val expectedAbsence = if (absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND) {
                QueueAbsenceStatus.NONE
            } else {
                absenceStatus
            }

            assertTrue(execution.state.queue(machineA)!!.allRegistrations.isEmpty())
            assertEquals(listOf(2, 1), transferred.map(Registration::fixedPartnerKey))
            assertTrue(transferred.all { it.absenceStatus == expectedAbsence })
            assertTrue(transferred.all {
                it.temporaryAwaySkippedTurns == if (
                    expectedAbsence == QueueAbsenceStatus.TEMPORARILY_AWAY
                ) skippedTurns else 0
            })
            assertTrue(execution.state.invariantViolations().isEmpty())
        }
    }

    @Test
    fun transferPlanRejectsWhenEitherMachineChangesBeforeConfirmation() {
        val moving = registration(1, PlayPreference.SOLO)
        val state = QueueEngineState(
            mapOf(
                machineA to MachineQueue(waiting = listOf(moving)),
                machineB to MachineQueue()
            )
        )
        val plan = QueueEngine.plan(
            state,
            QueueAction.TransferRegistrations(machineA, machineB, setOf(moving.key))
        )
        val currentState = state.replace(
            machineB,
            MachineQueue(waiting = listOf(registration(2, PlayPreference.SOLO)))
        )

        assertEquals(
            QueueActionFailureCode.STALE_STATE,
            (plan.applyTo(currentState) as QueueActionExecution.Rejected).failure.code
        )
        assertEquals(listOf(moving.key), currentState.queue(machineA)!!.waiting.map(Registration::key))
        assertEquals(listOf(2), currentState.queue(machineB)!!.waiting.map(Registration::key))
    }

    @Test
    fun removalAndNoShowFromPlayingPositionLeaveThePositionEmptyWhenRequested() {
        val current = registration(1, PlayPreference.SOLO)
        val next = registration(2, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(next),
            playingStartedAtMillis = 5_000L
        )

        val removed = appliedQueue(
            queue,
            QueueAction.RemoveRegistrations(machineA, setOf(current.key))
        )
        assertTrue(removed.playing.isEmpty())
        assertEquals(listOf(next.key), removed.waiting.map(Registration::key))

        val markedNoShow = appliedQueue(
            queue,
            QueueAction.MarkNoShow(
                machineA,
                setOf(current.key),
                NoShowResolution.MOVE_TO_TAIL,
                startNextWhenPlayingBecomesEmpty = false
            )
        )
        assertTrue(markedNoShow.playing.isEmpty())
        assertEquals(listOf(next.key, current.key), markedNoShow.waiting.map(Registration::key))
        assertEquals(1, markedNoShow.waiting.last().noShowCount)
    }

    @Test
    fun stalePlanCannotOverwriteAChangedTargetQueue() {
        val queue = MachineQueue(waiting = listOf(registration(1, PlayPreference.SOLO)))
        val state = QueueEngineState.single(machineA, queue)
        val policy = QueueEnginePolicy()
        val plan = QueueEngine.plan(
            state,
            QueueAction.EnterPlayingPosition(machineA),
            QueueActionContext(atMillis = atMillis, policy = policy)
        )
        assertTrue(plan.canApply)

        val changedState = state.replace(machineA, queue.copy(waiting = queue.waiting + registration(2)))
        assertEquals(
            QueueActionFailureCode.STALE_STATE,
            (plan.applyTo(changedState) as QueueActionExecution.Rejected).failure.code
        )
    }

    @Test
    fun unrelatedMachineAndPolicyChangesAreRevalidatedWithoutBlockingThePlan() {
        val queueA = MachineQueue(waiting = listOf(registration(1, PlayPreference.SOLO)))
        val state = QueueEngineState(
            mapOf(machineA to queueA, machineB to MachineQueue())
        )
        val policy = QueueEnginePolicy()
        val plan = QueueEngine.plan(
            state,
            QueueAction.EnterPlayingPosition(machineA),
            QueueActionContext(atMillis = atMillis, policy = policy)
        )
        val queueB = MachineQueue(waiting = listOf(registration(2, PlayPreference.SOLO)))
        val currentState = state.replace(machineB, queueB)

        val execution = plan.applyTo(
            currentState,
            policy.copy(allowTemporaryLeave = false)
        ) as QueueActionExecution.Applied

        assertEquals(listOf(1), execution.state.queue(machineA)!!.playing.map(Registration::key))
        assertEquals(queueB, execution.state.queue(machineB))
    }

    @Test
    fun planRechecksCrossMachineConflictsIntroducedAfterPreview() {
        val state = QueueEngineState(
            mapOf(machineA to MachineQueue(), machineB to MachineQueue())
        )
        val incoming = registration(2, PlayPreference.SOLO)
        val plan = QueueEngine.plan(
            state,
            QueueAction.AddRegistrations(
                machineA,
                listOf(incoming),
                RegistrationPlacement.WAITING_TAIL
            )
        )
        val currentState = state.replace(
            machineB,
            MachineQueue(waiting = listOf(incoming))
        )

        assertEquals(
            QueueActionFailureCode.DUPLICATE_REGISTRATION,
            (plan.applyTo(currentState) as QueueActionExecution.Rejected).failure.code
        )
        assertEquals(MachineQueue(), currentState.queue(machineA))
    }

    @Test
    fun planRechecksRulesThatChangedAfterPreview() {
        val queue = MachineQueue(
            waiting = listOf(registration(1, PlayPreference.SOLO))
        )
        val state = QueueEngineState.single(machineA, queue)
        val plan = QueueEngine.plan(
            state,
            QueueAction.DeferOneRound(machineA, 1),
            QueueActionContext(policy = QueueEnginePolicy(allowDeferOneRound = true))
        )

        val execution = plan.applyTo(
            state,
            QueueEnginePolicy(allowDeferOneRound = false)
        )

        assertEquals(
            QueueActionFailureCode.DEFER_ONE_ROUND_DISABLED,
            (execution as QueueActionExecution.Rejected).failure.code
        )
        assertEquals(queue, state.queue(machineA))
    }

    @Test
    fun confirmedRoundPlanKeepsItsPreviewButUsesTheConfirmationTime() {
        val current = registration(1, PlayPreference.SOLO)
        val next = registration(2, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(next),
            playingStartedAtMillis = 10_000L
        )
        val state = QueueEngineState.single(machineA, queue)
        val previewAtMillis = 20_000L
        val confirmedAtMillis = 30_000L
        val plan = QueueEngine.plan(
            state,
            QueueAction.FinishRound(machineA),
            QueueActionContext(atMillis = previewAtMillis)
        )

        val execution = plan.applyTo(state, atMillis = confirmedAtMillis)
            as QueueActionExecution.Applied
        val result = execution.state.queue(machineA)!!

        assertEquals(listOf(next.key), plan.impact.roundPreview?.nextRegistrations?.map { it.key })
        assertEquals(listOf(next.key), execution.impact.roundPreview?.nextRegistrations?.map { it.key })
        assertEquals(confirmedAtMillis, result.playingStartedAtMillis)
        assertEquals(
            confirmedAtMillis,
            result.waiting.first { it.key == current.key }.lastPlayedAtMillis
        )
    }

    @Test
    fun restoreSnapshotNeverRestoresRegistrationsRemovedForMissedCheckIn() {
        val current = registration(1, PlayPreference.SOLO)
        val pending = registration(2, PlayPreference.SOLO, pendingCheckIn = true)
        val before = MachineQueue(
            playing = listOf(current),
            waiting = listOf(pending),
            playingStartedAtMillis = 10_000L
        )
        val after = RoundPlanner.finishRound(before).execute(atMillis)
        assertTrue(after.allRegistrations.none { it.key == pending.key })
        val state = QueueEngineState.single(machineA, after)

        val execution = QueueEngine.execute(
            state,
            QueueAction.RestoreSnapshot(
                machineId = machineA,
                expectedCurrentQueue = after,
                restoredQueue = before,
                excludedRegistrationKeys = setOf(pending.key)
            ),
            QueueActionContext(origin = QueueActionOrigin.SYSTEM)
        ) as QueueActionExecution.Applied
        val restored = execution.state.queue(machineA)!!

        assertTrue(restored.allRegistrations.none { it.key == pending.key })
        assertEquals(listOf(current.key), restored.playing.map(Registration::key))
        assertTrue(restored.invariantViolations().isEmpty())
    }

    @Test
    fun staleRestoreSnapshotCannotOverwriteLaterQueueChanges() {
        val before = MachineQueue(waiting = listOf(registration(1, PlayPreference.SOLO)))
        val expectedCurrent = MachineQueue(waiting = listOf(registration(2, PlayPreference.SOLO)))
        val actualCurrent = expectedCurrent.copy(
            waiting = expectedCurrent.waiting + registration(3, PlayPreference.SOLO)
        )
        val execution = QueueEngine.execute(
            QueueEngineState.single(machineA, actualCurrent),
            QueueAction.RestoreSnapshot(
                machineId = machineA,
                expectedCurrentQueue = expectedCurrent,
                restoredQueue = before
            )
        )

        assertEquals(
            QueueActionFailureCode.STALE_STATE,
            (execution as QueueActionExecution.Rejected).failure.code
        )
    }

    @Test
    fun restoreSnapshotCannotDuplicateAPlayerWhoRejoinedAnotherMachine() {
        val oldRegistration = registration(
            1,
            PlayPreference.SOLO,
            profileId = "profile-1"
        )
        val afterRemoval = MachineQueue()
        val rejoined = registration(
            2,
            PlayPreference.SOLO,
            profileId = "profile-1"
        )
        val state = QueueEngineState(
            mapOf(
                machineA to afterRemoval,
                machineB to MachineQueue(waiting = listOf(rejoined))
            )
        )

        val execution = QueueEngine.execute(
            state,
            QueueAction.RestoreSnapshot(
                machineId = machineA,
                expectedCurrentQueue = afterRemoval,
                restoredQueue = MachineQueue(waiting = listOf(oldRegistration))
            ),
            QueueActionContext(origin = QueueActionOrigin.SYSTEM)
        )

        assertEquals(
            QueueActionFailureCode.DUPLICATE_REGISTRATION,
            (execution as QueueActionExecution.Rejected).failure.code
        )
        assertEquals(afterRemoval, state.queue(machineA))
        assertEquals(listOf(rejoined.key), state.queue(machineB)!!.waiting.map(Registration::key))
    }

    @Test
    fun restoreSnapshotPreservesUnrelatedChangesOnAnotherMachine() {
        val oldRegistration = registration(1, PlayPreference.SOLO)
        val afterRemoval = MachineQueue()
        val unrelated = registration(2, PlayPreference.SOLO)
        val state = QueueEngineState(
            mapOf(
                machineA to afterRemoval,
                machineB to MachineQueue(waiting = listOf(unrelated))
            )
        )

        val execution = QueueEngine.execute(
            state,
            QueueAction.RestoreSnapshot(
                machineId = machineA,
                expectedCurrentQueue = afterRemoval,
                restoredQueue = MachineQueue(waiting = listOf(oldRegistration))
            ),
            QueueActionContext(origin = QueueActionOrigin.SYSTEM)
        ) as QueueActionExecution.Applied

        assertEquals(listOf(oldRegistration.key), execution.state
            .queue(machineA)!!.waiting.map(Registration::key))
        assertEquals(listOf(unrelated.key), execution.state
            .queue(machineB)!!.waiting.map(Registration::key))
    }

    @Test
    fun correctionProcessesEachSkippedAvailabilityStateBeforeReachingTarget() {
        val current = registration(9, PlayPreference.SOLO)
        val pending = registration(1, PlayPreference.SOLO, pendingCheckIn = true)
        val deferred = registration(2, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val away = registration(3, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
        )
        val target = registration(4, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(pending, deferred, away, target),
            playingStartedAtMillis = 5_000L
        )

        val result = appliedQueue(
            queue,
            QueueAction.AdvanceToWaitingPosition(machineA, setOf(target.key))
        )

        assertEquals(listOf(target.key), result.playing.map(Registration::key))
        assertTrue(result.allRegistrations.none { it.key == pending.key })
        assertEquals(
            QueueAbsenceStatus.NONE,
            result.allRegistrations.first { it.key == deferred.key }.absenceStatus
        )
        val awayAfter = result.allRegistrations.first { it.key == away.key }
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, awayAfter.absenceStatus)
        assertTrue(awayAfter.temporaryAwaySkippedTurns > 0)
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun enterPlayingStillAppliesUnavailableRulesWhenNoOneCanEnter() {
        val pending = registration(1, PlayPreference.SOLO, pendingCheckIn = true)
        val away = registration(2, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
        )
        val queue = MachineQueue(waiting = listOf(pending, away))

        val result = appliedQueue(queue, QueueAction.EnterPlayingPosition(machineA))

        assertTrue(result.playing.isEmpty())
        assertTrue(result.allRegistrations.none { it.key == pending.key })
        assertEquals(1, result.waiting.single().temporaryAwaySkippedTurns)
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun policyAndStatePrerequisitesRejectWithoutChangingEitherMachine() {
        val existing = registration(1, PlayPreference.SOLO, profileId = "profile-1")
        val state = QueueEngineState(
            mapOf(
                machineA to MachineQueue(waiting = listOf(existing)),
                machineB to MachineQueue()
            )
        )
        val closed = QueueEngine.execute(
            state,
            QueueAction.AddRegistrations(
                machineA,
                listOf(registration(2)),
                RegistrationPlacement.AUTO_ADVANCE
            ),
            QueueActionContext(policy = QueueEnginePolicy(registrationOpen = false))
        )
        assertEquals(
            QueueActionFailureCode.REGISTRATION_CLOSED,
            (closed as QueueActionExecution.Rejected).failure.code
        )

        val duplicateAcrossMachines = QueueEngine.execute(
            state,
            QueueAction.AddRegistrations(
                machineB,
                listOf(registration(2, profileId = "profile-1")),
                RegistrationPlacement.WAITING_TAIL
            )
        )
        assertEquals(
            QueueActionFailureCode.DUPLICATE_REGISTRATION,
            (duplicateAcrossMachines as QueueActionExecution.Rejected).failure.code
        )
        assertEquals(state, QueueEngine.plan(
            state,
            QueueAction.AddRegistrations(
                machineB,
                listOf(registration(2, profileId = "profile-1")),
                RegistrationPlacement.WAITING_TAIL
            )
        ).resultState)
    }

    @Test
    fun noShowDeferralCannotBypassTheDeferOneRoundSetting() {
        val registrations = listOf(
            registration(1, PlayPreference.OPEN_TO_JOIN),
            registration(2, PlayPreference.OPEN_TO_JOIN)
        )
        val state = QueueEngineState.single(
            machineA,
            MachineQueue(waiting = registrations)
        )
        val policy = QueueEnginePolicy(allowDeferOneRound = false)
        val actions = listOf(
            QueueAction.MarkNoShow(
                machineA,
                setOf(registrations.first().key),
                NoShowResolution.DEFER_ONE_ROUND
            ),
            QueueAction.MarkNoShow(
                machineA,
                registrations.mapTo(mutableSetOf(), Registration::key),
                NoShowResolution.DEFER_GROUP_ONE_ROUND
            )
        )

        actions.forEach { action ->
            val execution = QueueEngine.execute(
                state,
                action,
                QueueActionContext(policy = policy)
            )

            assertEquals(
                QueueActionFailureCode.DEFER_ONE_ROUND_DISABLED,
                (execution as QueueActionExecution.Rejected).failure.code
            )
            assertEquals(state, QueueEngine.plan(
                state,
                action,
                QueueActionContext(policy = policy)
            ).resultState)
        }
    }

    @Test
    fun clearingRegistrationsChangesAllRequestedMachinesAtomically() {
        val state = QueueEngineState(
            mapOf(
                machineA to MachineQueue(waiting = listOf(registration(1))),
                machineB to MachineQueue(playing = listOf(registration(2)))
            )
        )

        val execution = QueueEngine.execute(
            state,
            QueueAction.ClearRegistrations(setOf(machineA, machineB)),
            QueueActionContext(origin = QueueActionOrigin.SYSTEM)
        ) as QueueActionExecution.Applied

        assertEquals(MachineQueue(), execution.state.queue(machineA))
        assertEquals(MachineQueue(), execution.state.queue(machineB))
        assertEquals(setOf(machineA, machineB), execution.impact.changedMachineIds)
        assertEquals(setOf(1, 2), execution.impact.removedRegistrationKeys)
    }

    @Test
    fun clearingRegistrationsRejectsTheWholeActionWhenAnyMachineIsMissing() {
        val state = QueueEngineState.single(
            machineA,
            MachineQueue(waiting = listOf(registration(1)))
        )
        val execution = QueueEngine.execute(
            state,
            QueueAction.ClearRegistrations(setOf(machineA, machineB)),
            QueueActionContext(origin = QueueActionOrigin.SYSTEM)
        )

        assertEquals(
            QueueActionFailureCode.MACHINE_NOT_FOUND,
            (execution as QueueActionExecution.Rejected).failure.code
        )
        assertEquals(listOf(1), state.queue(machineA)!!.waiting.map(Registration::key))
    }

    @Test
    fun failedActionDoesNotIntroduceOrHideQueueChanges() {
        val queue = MachineQueue(waiting = listOf(registration(1, pendingCheckIn = true)))
        val state = QueueEngineState.single(machineA, queue)
        val execution = QueueEngine.execute(
            state,
            QueueAction.TemporarilyLeave(machineA, 1)
        )

        assertTrue(execution is QueueActionExecution.Rejected)
        assertEquals(
            QueueActionFailureCode.PENDING_CHECK_IN,
            (execution as QueueActionExecution.Rejected).failure.code
        )
        assertFalse(QueueEngine.plan(state, QueueAction.TemporarilyLeave(machineA, 1)).canApply)
        assertEquals(queue, state.queue(machineA))
    }

    @Test
    fun rejectedPlanCannotBecomeExecutableOnlyBecauseItsTimestampChanges() {
        val queue = MachineQueue(waiting = listOf(registration(1, PlayPreference.SOLO)))
        val state = QueueEngineState.single(machineA, queue)
        val failedPlan = QueueEngine.plan(
            state,
            QueueAction.RemoveExpiredOnlineRegistrations(machineA),
            QueueActionContext(atMillis = 1_000L)
        )

        val later = failedPlan.applyTo(
            currentState = state,
            atMillis = ONLINE_REGISTRATION_CHECK_IN_TIMEOUT_MILLIS + 10_000L
        )

        assertEquals(
            QueueActionFailureCode.NO_STATE_CHANGE,
            (later as QueueActionExecution.Rejected).failure.code
        )
    }

    @Test
    fun fixedPairPlanRebasesHarmlessProfileDetailChanges() {
        val original = MachineQueue(
            waiting = listOf(registration(1), registration(2))
        )
        val pairPlan = requireNotNull(original.planFriendPair(1, 2))
        val refreshed = original.copy(
            waiting = original.waiting.map {
                if (it.key == 1) it.copy(displayId = "同步后的昵称", gender = PlayerGender.FEMALE)
                else it
            }
        )

        val result = appliedQueue(
            refreshed,
            QueueAction.CreateFixedPair(
                machineId = machineA,
                firstRegistrationKey = 1,
                secondRegistrationKey = 2,
                expectedPlan = pairPlan
            )
        )

        assertEquals("同步后的昵称", result.waiting.first { it.key == 1 }.displayId)
        assertEquals(PlayerGender.FEMALE, result.waiting.first { it.key == 1 }.gender)
        assertEquals(2, result.waiting.first { it.key == 1 }.fixedPartnerKey)
        assertEquals(1, result.waiting.first { it.key == 2 }.fixedPartnerKey)
    }

    @Test
    fun fixedPairActionRejectsAPlanCreatedForDifferentRegistrations() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.OPEN_TO_JOIN),
                registration(2, PlayPreference.OPEN_TO_JOIN),
                registration(3, PlayPreference.SOLO)
            )
        )
        val wrongPlan = requireNotNull(queue.planFriendPair(1, 2))

        val execution = QueueEngine.execute(
            QueueEngineState.single(machineA, queue),
            QueueAction.CreateFixedPair(
                machineId = machineA,
                firstRegistrationKey = 2,
                secondRegistrationKey = 3,
                expectedPlan = wrongPlan
            )
        )

        assertEquals(
            QueueActionFailureCode.INVALID_FIXED_PAIR,
            (execution as QueueActionExecution.Rejected).failure.code
        )
        assertEquals(queue, QueueEngine.plan(
            QueueEngineState.single(machineA, queue),
            QueueAction.CreateFixedPair(
                machineId = machineA,
                firstRegistrationKey = 2,
                secondRegistrationKey = 3,
                expectedPlan = wrongPlan
            )
        ).resultState.queue(machineA))
    }

    @Test
    fun friendRegistrationRechecksClosedAndStoppedRulesAtCommitTime() {
        val queue = MachineQueue(waiting = listOf(registration(1)))
        val action = QueueAction.CreateFixedPairWithRegistration(
            machineId = machineA,
            registrationKey = 1,
            friend = registration(2)
        )
        val closed = QueueEngine.execute(
            QueueEngineState.single(machineA, queue),
            action,
            QueueActionContext(policy = QueueEnginePolicy(registrationOpen = false))
        )
        val stopped = QueueEngine.execute(
            QueueEngineState.single(machineA, queue),
            action,
            QueueActionContext(
                policy = QueueEnginePolicy(
                    machineStatuses = mapOf(
                        machineA to MachineStatus().stop(MachineStopReason.MAINTENANCE, 1_000L)
                    ),
                    requireOperationalForPlayerActions = true
                )
            )
        )

        assertEquals(
            QueueActionFailureCode.REGISTRATION_CLOSED,
            (closed as QueueActionExecution.Rejected).failure.code
        )
        assertEquals(
            QueueActionFailureCode.MACHINE_STOPPED,
            (stopped as QueueActionExecution.Rejected).failure.code
        )
    }

    @Test
    fun stoppedMachineBlocksPlayerRemovalButAllowsSystemCleanup() {
        val queue = MachineQueue(waiting = listOf(registration(1)))
        val state = QueueEngineState.single(machineA, queue)
        val policy = QueueEnginePolicy(
            machineStatuses = mapOf(
                machineA to MachineStatus().stop(MachineStopReason.MAINTENANCE, 1_000L)
            ),
            requireOperationalForPlayerActions = true
        )
        val action = QueueAction.RemoveRegistrations(machineA, setOf(1))

        val playerResult = QueueEngine.execute(
            state,
            action,
            QueueActionContext(
                origin = QueueActionOrigin.ON_SITE_TERMINAL,
                policy = policy
            )
        )
        val cleanupResult = QueueEngine.execute(
            state,
            action,
            QueueActionContext(
                origin = QueueActionOrigin.SYSTEM,
                policy = policy
            )
        )

        assertEquals(
            QueueActionFailureCode.MACHINE_STOPPED,
            (playerResult as QueueActionExecution.Rejected).failure.code
        )
        assertTrue(cleanupResult is QueueActionExecution.Applied)
        assertTrue((cleanupResult as QueueActionExecution.Applied)
            .state.queue(machineA)!!.allRegistrations.isEmpty())
    }

    @Test
    fun anActionCanRepairPartOfAnExistingCrossMachineViolation() {
        val state = QueueEngineState(
            mapOf(
                machineA to MachineQueue(
                    waiting = listOf(registration(1), registration(2))
                ),
                machineB to MachineQueue(
                    waiting = listOf(
                        registration(1).copy(displayId = "另一位玩家1"),
                        registration(2).copy(displayId = "另一位玩家2")
                    )
                )
            )
        )
        assertTrue(state.invariantViolations().any { "1, 2" in it })

        val execution = QueueEngine.execute(
            state,
            QueueAction.RemoveRegistrations(machineA, setOf(1))
        )

        assertTrue(execution is QueueActionExecution.Applied)
        val result = (execution as QueueActionExecution.Applied).state
        assertTrue(result.invariantViolations().any { it.endsWith("2") })
        assertFalse(result.invariantViolations().any { "1, 2" in it })
    }

    @Test
    fun longNormalActionSequenceMatchesExistingQueueBehaviorAtEveryStep() {
        listOf(20260801, 20260701, 610, 4070, 731).forEach(::assertNormalActionSequence)
    }

    private fun assertNormalActionSequence(seed: Int) {
        val random = Random(seed)
        var queue = MachineQueue()
        var nextKey = 1

        repeat(800) { step ->
            val candidates = mutableListOf<Pair<QueueAction, (MachineQueue) -> MachineQueue>>()
            val actionTime = 1_000_000L + step

            if (queue.registrationCount < 16) {
                val key = nextKey
                val added = registration(
                    key = key,
                    preference = if (random.nextBoolean()) {
                        PlayPreference.OPEN_TO_JOIN
                    } else {
                        PlayPreference.SOLO
                    },
                    pendingCheckIn = random.nextInt(6) == 0
                )
                candidates += QueueAction.AddRegistrations(
                    machineA,
                    listOf(added),
                    RegistrationPlacement.AUTO_ADVANCE
                ) to { it.join(added, actionTime) }
            }
            if (queue.playing.isNotEmpty()) {
                candidates += QueueAction.FinishRound(machineA) to {
                    RoundPlanner.finishRound(it).execute(actionTime)
                }
                candidates += QueueAction.EndRoundOnly(machineA) to {
                    RoundPlanner.endRoundOnly(it).execute(actionTime)
                }
                candidates += QueueAction.RemoveCurrentRoundAndStartNext(machineA) to {
                    RoundPlanner.removeCurrentRoundAndStartNext(it).execute(actionTime)
                }
                val playingKeys = queue.playing.mapTo(mutableSetOf(), Registration::key)
                candidates += QueueAction.ReturnPlayingToWaitingFront(machineA, playingKeys) to {
                    it.returnPlayingRegistrationsToWaitingFront(playingKeys)
                }
            } else if (queue.waiting.isNotEmpty()) {
                candidates += QueueAction.EnterPlayingPosition(machineA) to {
                    RoundPlanner.enterPlayingPosition(it).execute(actionTime)
                }
            }

            queue.allRegistrations.filterNot(Registration::requiresOnSiteCheckIn)
                .filter { it.absenceStatus == QueueAbsenceStatus.NONE }
                .randomOrNull(random)
                ?.let { selected ->
                    candidates += QueueAction.DeferOneRound(machineA, selected.key) to {
                        it.deferOneRound(selected.key, actionTime)
                    }
                    candidates += QueueAction.TemporarilyLeave(machineA, selected.key) to {
                        it.temporarilyLeave(selected.key, actionTime)
                    }
                    val nextPreference = if (selected.preference == PlayPreference.SOLO) {
                        PlayPreference.OPEN_TO_JOIN
                    } else {
                        PlayPreference.SOLO
                    }
                    candidates += QueueAction.ChangePreference(
                        machineA,
                        selected.key,
                        nextPreference
                    ) to { it.changePreference(selected.key, nextPreference) }
                }
            queue.allRegistrations.filter {
                it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
            }.randomOrNull(random)?.let { selected ->
                candidates += QueueAction.CancelDeferOneRound(machineA, selected.key) to {
                    it.cancelDeferOneRound(selected.key)
                }
            }
            queue.allRegistrations.filter {
                it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
            }.randomOrNull(random)?.let { selected ->
                candidates += QueueAction.CancelTemporaryLeave(machineA, selected.key) to {
                    it.cancelTemporaryLeave(selected.key)
                }
            }
            queue.allRegistrations.filter(Registration::requiresOnSiteCheckIn)
                .randomOrNull(random)?.let { selected ->
                    candidates += QueueAction.CheckIn(machineA, selected.key) to {
                        it.checkIn(selected.key)
                    }
            }
            queue.allRegistrations.randomOrNull(random)?.let { selected ->
                candidates += QueueAction.RemoveRegistrations(machineA, setOf(selected.key)) to {
                    it.remove(selected.key)
                }
            }
            queue.allRegistrations.filter { queue.canMarkNoShow(it.key) }
                .randomOrNull(random)?.let { selected ->
                    val startNextWhenPlayingBecomesEmpty = random.nextBoolean()
                    candidates += QueueAction.MarkNoShow(
                        machineA,
                        setOf(selected.key),
                        NoShowResolution.MOVE_TO_TAIL,
                        startNextWhenPlayingBecomesEmpty = startNextWhenPlayingBecomesEmpty
                    ) to { current ->
                        current.markNoShowMoveToEnd(
                            setOf(selected.key),
                            startNextWhenPlayingBecomesEmpty = startNextWhenPlayingBecomesEmpty,
                            atMillis = actionTime
                        )
                    }
                }
            val positions = queue.waitingPositions()
            if (positions.size > 1) {
                val sourceIndex = random.nextInt(positions.size)
                var destinationIndex = random.nextInt(positions.size - 1)
                if (destinationIndex >= sourceIndex) destinationIndex++
                candidates += QueueAction.MoveWaitingPosition(
                    machineA,
                    sourceIndex,
                    destinationIndex
                ) to { it.moveWaitingPosition(sourceIndex, destinationIndex) }
            }
            val pairCandidates = queue.waiting.filter {
                it.fixedPartnerKey == null && !it.requiresOnSiteCheckIn
            }
            if (pairCandidates.size >= 2) {
                val first = pairCandidates.random(random)
                val second = pairCandidates.filterNot { it.key == first.key }.random(random)
                queue.planFriendPair(first.key, second.key)
                    ?.takeIf { it.delayedOtherRegistrations.isEmpty() }
                    ?.let { pairPlan ->
                        candidates += QueueAction.CreateFixedPair(
                            machineA,
                            first.key,
                            second.key,
                            pairPlan
                        ) to { it.applyFriendPair(pairPlan) }
                    }
            }

            val (action, existingBehavior) = candidates.random(random)
            val expected = existingBehavior(queue)
            val execution = QueueEngine.execute(
                QueueEngineState.single(machineA, queue),
                action,
                QueueActionContext(atMillis = actionTime)
            )
            if (expected == queue) {
                assertTrue(
                    "种子 $seed，第 ${step + 1} 步应保持不变：$action",
                    execution is QueueActionExecution.Rejected
                )
            } else {
                assertTrue(
                    "种子 $seed，第 ${step + 1} 步应成功：$action，实际为 $execution",
                    execution is QueueActionExecution.Applied
                )
                queue = (execution as QueueActionExecution.Applied).state.queue(machineA)!!
                assertEquals("种子 $seed，第 ${step + 1} 步结果不一致：$action", expected, queue)
                assertTrue(
                    "种子 $seed，第 ${step + 1} 步破坏队列不变量：$action",
                    queue.invariantViolations().isEmpty()
                )
            }
            if (action is QueueAction.AddRegistrations) nextKey++
        }
    }
}
