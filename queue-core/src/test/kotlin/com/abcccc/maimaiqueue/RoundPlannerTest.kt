package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundPlannerTest {
    @Test
    fun waitingProjectionMovesDeferredPositionWithoutChangingPhysicalOrder() {
        val current = registration(9, PlayPreference.SOLO)
        val first = registration(1, PlayPreference.SOLO)
        val deferred = registration(2, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val next = registration(3, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(first, deferred, next),
            playingStartedAtMillis = 100L
        )

        val projection = queue.waitingProjection(includeCommonPlayPreview = false)

        assertEquals(listOf(1, 3, 2), projection.positions.flattenKeys())
        assertEquals(listOf(1, 2, 3), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.DEFER_ONE_ROUND, queue.waiting[1].absenceStatus)
    }

    @Test
    fun deferredOpenRegistrationUsesReturningPlayerAsPreviewInsteadOfRealGroupMember() {
        val deferred = registration(1).copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val available = registration(2)
        val queue = MachineQueue(waiting = listOf(deferred, available))

        val projection = queue.waitingProjection()

        assertEquals(
            listOf(listOf(2), listOf(1)),
            projection.positions.map { position -> position.registrations.map { it.key } }
        )
        assertNull(projection.positions.first().commonPlayPreview)
        assertEquals(2, projection.positions.last().commonPlayPreview?.key)
        assertEquals(listOf(1, 2), queue.waiting.map { it.key })
    }

    @Test
    fun cancellingDeferredPositionReturnsProjectionToPhysicalOrder() {
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO),
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(3, PlayPreference.SOLO)
            )
        )

        val cancelled = queue.cancelDeferOneRound(2)

        assertEquals(listOf(1, 2, 3), cancelled.waitingProjection().positions.flattenKeys())
        assertEquals(listOf(1, 2, 3), cancelled.waiting.map { it.key })
    }

    @Test
    fun pendingCheckInRegistrationGroupsLikeANormalOpenRegistrationOnlyInProjection() {
        val pending = registration(1).copy(requiresOnSiteCheckIn = true)
        val available = registration(2)
        val queue = MachineQueue(waiting = listOf(pending, available))

        val projection = queue.waitingProjection(includeCommonPlayPreview = false)

        assertEquals(listOf(listOf(1, 2)), projection.positions.map { position ->
            position.registrations.map { it.key }
        })
        assertEquals(listOf(listOf(1), listOf(2)), queue.waitingPositions().map { position ->
            position.map { it.key }
        })
        assertTrue(queue.waiting.first().requiresOnSiteCheckIn)
    }

    @Test
    fun projectedGroupingCanCrossATemporarilyAwayRegistrationWithoutMovingIt() {
        val first = registration(1)
        val away = registration(2).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
        )
        val second = registration(3)
        val later = registration(4)
        val queue = MachineQueue(waiting = listOf(first, away, second, later))

        val projection = queue.waitingProjection()

        assertEquals(
            listOf(listOf(1, 3), listOf(2), listOf(4)),
            projection.positions.map { position -> position.registrations.map { it.key } }
        )
        assertEquals(1, projection.positions.last().commonPlayPreview?.key)
        assertEquals(listOf(1, 2, 3, 4), queue.waiting.map { it.key })
    }

    @Test
    fun currentOpenPlayerIsPreviewedForTheNextSingleOpenRegistration() {
        val current = registration(9)
        val waiting = registration(1)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(waiting),
            playingStartedAtMillis = 100L
        )

        val projection = queue.waitingProjection()

        assertEquals(listOf(1), projection.positions.single().registrations.map { it.key })
        assertEquals(9, projection.positions.single().commonPlayPreview?.key)
    }

    @Test
    fun firstReturningOpenPlayerIsPreviewedWhenTwoPlayersAreCurrentlyPlaying() {
        val queue = MachineQueue(
            playing = listOf(registration(9), registration(10)),
            waiting = listOf(registration(1)),
            playingStartedAtMillis = 100L
        )

        val projection = queue.waitingProjection()

        assertEquals(listOf(1), projection.positions.first().registrations.map { it.key })
        assertEquals(9, projection.positions.first().commonPlayPreview?.key)
    }

    @Test
    fun returningFixedPairIsNeverSplitIntoACommonPlayPreview() {
        val first = registration(9).copy(fixedPartnerKey = 10)
        val second = registration(10).copy(fixedPartnerKey = 9)
        val queue = MachineQueue(
            playing = listOf(first, second),
            waiting = listOf(registration(1)),
            playingStartedAtMillis = 100L
        )

        val projection = queue.waitingProjection()
        val advanced = queue.finishRound(atMillis = 200L)

        assertNull(projection.positions.first().commonPlayPreview)
        assertEquals(listOf(1), advanced.playing.map { it.key })
        assertEquals(listOf(9, 10), advanced.waiting.map { it.key })
    }

    @Test
    fun soloReturningPlayerIsNotShownAsACommonPlayPreview() {
        val current = registration(9, PlayPreference.SOLO)
        val waiting = registration(1)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(waiting),
            playingStartedAtMillis = 100L
        )

        val projection = queue.waitingProjection()

        assertNull(projection.positions.single().commonPlayPreview)
    }

    @Test
    fun projectedFixedPairMovesTogether() {
        val first = registration(2).copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
            fixedPartnerKey = 3
        )
        val second = registration(3).copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
            fixedPartnerKey = 2
        )
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO),
                first,
                second,
                registration(4, PlayPreference.SOLO)
            )
        )

        val projection = queue.waitingProjection(includeCommonPlayPreview = false)

        assertEquals(listOf(listOf(1), listOf(4), listOf(2, 3)), projection.positions.map {
            position -> position.registrations.map { it.key }
        })
        assertEquals(listOf(1, 2, 3, 4), queue.waiting.map { it.key })
    }

    @Test
    fun deferredPositionStaysPutWhenThereIsNoOpportunityToConsume() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                )
            )
        )

        val projection = queue.waitingProjection()

        assertEquals(listOf(1), projection.positions.flattenKeys())
        assertEquals(QueueAbsenceStatus.DEFER_ONE_ROUND, projection.positions.single()
            .registrations.single().absenceStatus)
    }

    @Test
    fun openTailPreviewsReturningPlayerEvenWhenPlayingPositionStartsEmpty() {
        val queue = MachineQueue(
            waiting = (1..5).map { registration(it) }
        )

        val projection = queue.waitingProjection()

        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), projection.positions.map {
            position -> position.registrations.map { it.key }
        })
        assertEquals(1, projection.positions.last().commonPlayPreview?.key)
        assertEquals(5, projection.positions.sumOf { it.registrations.size })
    }

    @Test
    fun commonPlayPreviewCanBeDisabledWithoutChangingProjectedPositions() {
        val queue = MachineQueue(waiting = (1..5).map { registration(it) })

        val enabled = queue.waitingProjection(includeCommonPlayPreview = true)
        val disabled = queue.waitingProjection(includeCommonPlayPreview = false)

        assertEquals(
            enabled.positions.map { it.registrations.map(Registration::key) },
            disabled.positions.map { it.registrations.map(Registration::key) }
        )
        assertTrue(disabled.positions.all { it.commonPlayPreview == null })
    }

    @Test
    fun projectionMatchesFirstPlayingTurnsAcrossMixedAvailabilityStates() {
        val modes = ProjectionRegistrationMode.entries
        val playingVariants = listOf(
            emptyList(),
            listOf(registration(90)),
            listOf(registration(91, PlayPreference.SOLO))
        )

        for (waitingSize in 1..4) {
            forEachModeCombination(modes, waitingSize) { combination ->
                playingVariants.forEach { playing ->
                    val waiting = combination.mapIndexed { index, mode ->
                        mode.create(index + 1)
                    }
                    val queue = MachineQueue(
                        playing = playing,
                        waiting = waiting,
                        playingStartedAtMillis = playing.takeIf { it.isNotEmpty() }?.let { 100L }
                    )
                    assertProjectionMatchesObservedTurns(queue)
                }
            }
        }
    }

    @Test
    fun finishPlanPreviewMatchesExecutedPlayersAcrossMixedAvailabilityStates() {
        val current = registration(9, PlayPreference.SOLO)
        val pendingCheckIn = registration(1, PlayPreference.SOLO).copy(
            requiresOnSiteCheckIn = true
        )
        val deferred = registration(2, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val temporarilyAway = registration(3, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
        )
        val available = registration(4, PlayPreference.SOLO)
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(pendingCheckIn, deferred, temporarilyAway, available),
            playingStartedAtMillis = 100L
        )

        val plan = RoundPlanner.finishRound(queue)
        val result = plan.execute(atMillis = 5_000L)

        assertEquals(listOf(4), plan.preview?.nextRegistrations?.map { it.key })
        assertEquals(listOf(4), result.playing.map { it.key })
        assertTrue(result.allRegistrations.none { it.key == 1 })
        assertEquals(QueueAbsenceStatus.NONE, result.waiting.first { it.key == 2 }.absenceStatus)
        assertEquals(1, result.waiting.first { it.key == 3 }.temporaryAwaySkippedTurns)
        assertEquals(5_000L, result.waiting.first { it.key == 9 }.lastPlayedAtMillis)
        assertEquals(5_000L, result.playingStartedAtMillis)
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun removalPlanPreviewMatchesExecutedPlayers() {
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(2, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 100L
        )

        val plan = RoundPlanner.removeCurrentRoundAndStartNext(queue)
        val result = plan.execute(atMillis = 6_000L)

        assertEquals(listOf(2), plan.preview?.nextRegistrations?.map { it.key })
        assertEquals(listOf(2), result.playing.map { it.key })
        assertTrue(result.allRegistrations.none { it.key == 9 })
        assertEquals(QueueAbsenceStatus.NONE, result.waiting.single().absenceStatus)
        assertEquals(6_000L, result.playingStartedAtMillis)
    }

    @Test
    fun endOnlyUsesConfirmationTimeWithoutStartingAnotherRound() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO)),
            playingStartedAtMillis = 100L
        )

        val result = RoundPlanner.endRoundOnly(queue).execute(atMillis = 7_000L)

        assertTrue(result.playing.isEmpty())
        assertEquals(listOf(2, 1), result.waiting.map { it.key })
        assertEquals(7_000L, result.waiting.last().lastPlayedAtMillis)
        assertNull(result.playingStartedAtMillis)
    }

    @Test
    fun stalePlanCannotOverwriteAChangedQueue() {
        val registration = registration(1, PlayPreference.SOLO)
        val queue = MachineQueue(waiting = listOf(registration))
        val plan = RoundPlanner.enterPlayingPosition(queue)
        val changedQueue = queue.copy(
            waiting = listOf(registration.copy(displayId = "更新后的昵称"))
        )

        assertNull(plan.applyTo(changedQueue, atMillis = 8_000L))
    }

    @Test
    fun temporarilyAwayTurnIsConsumedOnlyOnceByOnePlan() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                ),
                registration(2, PlayPreference.SOLO)
            )
        )
        val plan = RoundPlanner.enterPlayingPosition(queue)

        val result = plan.execute(atMillis = 9_000L)

        assertEquals(1, result.waiting.single().temporaryAwaySkippedTurns)
        assertNull(plan.applyTo(result, atMillis = 10_000L))
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun fixedPairRemainsTheSameGroupInPreviewAndExecution() {
        val first = registration(1).copy(fixedPartnerKey = 2)
        val second = registration(2).copy(fixedPartnerKey = 1)
        val queue = MachineQueue(
            waiting = listOf(
                registration(3, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                first,
                second
            )
        )

        val plan = RoundPlanner.enterPlayingPosition(queue)
        val result = plan.execute(atMillis = 11_000L)

        assertEquals(listOf(1, 2), plan.preview?.nextRegistrations?.map { it.key })
        assertEquals(listOf(1, 2), result.playing.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, result.waiting.single().absenceStatus)
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun noAvailableGroupStillAppliesEachUnavailableRule() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(requiresOnSiteCheckIn = true),
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                )
            )
        )

        val plan = RoundPlanner.enterPlayingPosition(queue)
        val result = plan.execute(atMillis = 12_000L)

        assertTrue(plan.preview?.nextRegistrations?.isEmpty() == true)
        assertEquals(setOf(1, 2), plan.preview?.unavailableRegistrations?.map { it.key }?.toSet())
        assertTrue(result.playing.isEmpty())
        assertEquals(listOf(2), result.waiting.map { it.key })
        assertEquals(1, result.waiting.single().temporaryAwaySkippedTurns)
        assertNull(result.playingStartedAtMillis)
    }

    @Test
    fun removalWithoutCurrentRoundDoesNotUnexpectedlyAdvance() {
        val queue = MachineQueue(waiting = listOf(registration(1, PlayPreference.SOLO)))

        val result = RoundPlanner.removeCurrentRoundAndStartNext(queue).execute(13_000L)

        assertEquals(queue, result)
    }

    private fun registration(
        key: Int,
        preference: PlayPreference = PlayPreference.OPEN_TO_JOIN
    ): Registration = Registration(
        key = key,
        displayId = "玩家-$key",
        preference = preference,
        createdAtMillis = 100L
    )

    private fun List<WaitingPositionProjection>.flattenKeys(): List<Int> =
        flatMap { position -> position.registrations.map { it.key } }

    private fun assertProjectionMatchesObservedTurns(queue: MachineQueue) {
        val originalQueue = queue.copy(
            playing = queue.playing.toList(),
            waiting = queue.waiting.toList()
        )
        val projection = queue.waitingProjection(includeCommonPlayPreview = true)
        val withoutPreviews = queue.waitingProjection(includeCommonPlayPreview = false)
        val observedTurns = observeFirstPlayingTurns(queue)
        val projectedKeys = projection.positions.flattenKeys()
        val scenario = queue.waiting.joinToString { registration ->
            "${registration.key}:${registration.preference}:${registration.absenceStatus}:" +
                registration.requiresOnSiteCheckIn
        }

        assertEquals("投影必须恰好包含全部真实等待登记：$scenario", queue.waiting.size, projectedKeys.size)
        assertEquals(
            "投影不能新增、删除或重复真实登记：$scenario",
            queue.waiting.map { it.key }.toSet(),
            projectedKeys.toSet()
        )
        assertTrue("每个预计位置最多包含两份真实登记：$scenario", projection.positions.all {
            it.registrations.size in 1..2
        })
        assertEquals(
            "关闭共同游玩预览不能改变真实位置：$scenario",
            projection.positions.map { it.registrations.map(Registration::key) },
            withoutPreviews.positions.map { it.registrations.map(Registration::key) }
        )
        assertTrue(withoutPreviews.positions.all { it.commonPlayPreview == null })

        val scheduledPositionTurns = mutableListOf<Int>()
        projection.positions.forEach { position ->
            val turns = position.registrations.mapNotNull { observedTurns[it.key] }
            if (turns.isEmpty()) {
                assertNull("无法预计游玩机会的位置不能生成共同游玩预览：$scenario", position.commonPlayPreview)
                return@forEach
            }
            assertEquals("同一位置不能混入无法预计游玩机会的登记：$scenario", position.registrations.size, turns.size)
            assertEquals("真实同组登记必须在同一轮首次游玩：$scenario", 1, turns.map { it.turnIndex }.distinct().size)
            val turn = turns.first()
            scheduledPositionTurns += turn.turnIndex
            val positionKeys = position.registrations.map { it.key }
            assertTrue(
                "真实同组登记必须确实在同一轮共同游玩：$scenario",
                turn.participantKeys.containsAll(positionKeys)
            )

            val expectedPreviewKey = if (position.registrations.size == 1) {
                val registration = position.registrations.single()
                turn.participantKeys
                    .singleOrNull { it != registration.key }
                    ?.let { partnerKey -> queue.allRegistrations.first { it.key == partnerKey } }
                    ?.takeIf { partner ->
                        registration.preference == PlayPreference.OPEN_TO_JOIN &&
                            registration.fixedPartnerKey == null &&
                            partner.preference == PlayPreference.OPEN_TO_JOIN &&
                            partner.fixedPartnerKey == null
                    }
                    ?.key
            } else {
                null
            }
            assertEquals(
                "共同游玩预览必须来自同一轮的实际搭档：$scenario",
                expectedPreviewKey,
                position.commonPlayPreview?.key
            )
        }
        assertEquals(
            "可预计位置必须按照首次游玩轮次显示：$scenario",
            scheduledPositionTurns.sorted(),
            scheduledPositionTurns
        )
        assertEquals("生成投影不能修改真实队列：$scenario", originalQueue, queue)
    }

    private fun observeFirstPlayingTurns(queue: MachineQueue): Map<Int, ObservedPlayingTurn> {
        val targetKeys = queue.waiting.mapTo(linkedSetOf(), Registration::key)
        var simulated = queue.copy(
            waiting = queue.waiting.map { registration ->
                if (registration.requiresOnSiteCheckIn) {
                    registration.copy(requiresOnSiteCheckIn = false)
                } else {
                    registration
                }
            }
        )
        val observed = mutableMapOf<Int, ObservedPlayingTurn>()
        val maximumTurns = targetKeys.size * 4 + queue.playing.size * 2 + 8

        repeat(maximumTurns) { turnIndex ->
            val plan = when {
                simulated.playing.isNotEmpty() -> RoundPlanner.finishRound(simulated)
                simulated.firstAvailableWaitingPositionIndex() != null ->
                    RoundPlanner.enterPlayingPosition(simulated)
                else -> return observed
            }
            simulated = plan.execute(atMillis = turnIndex + 1L)
            val participantKeys = simulated.playing.map(Registration::key)
            simulated.playing.forEachIndexed { orderInTurn, registration ->
                if (registration.key in targetKeys && registration.key !in observed) {
                    observed[registration.key] = ObservedPlayingTurn(
                        turnIndex = turnIndex,
                        orderInTurn = orderInTurn,
                        participantKeys = participantKeys
                    )
                }
            }
        }
        return observed
    }

    private fun <T> forEachModeCombination(
        values: List<T>,
        size: Int,
        consume: (List<T>) -> Unit
    ) {
        val current = ArrayList<T>(size)
        fun visit() {
            if (current.size == size) {
                consume(current.toList())
                return
            }
            values.forEach { value ->
                current += value
                visit()
                current.removeAt(current.lastIndex)
            }
        }
        visit()
    }

    private data class ObservedPlayingTurn(
        val turnIndex: Int,
        val orderInTurn: Int,
        val participantKeys: List<Int>
    )

    private enum class ProjectionRegistrationMode {
        NORMAL_OPEN,
        NORMAL_SOLO,
        DEFERRED_OPEN,
        DEFERRED_SOLO,
        PENDING_OPEN,
        PENDING_SOLO,
        AWAY_OPEN,
        AWAY_SOLO;

        fun create(key: Int): Registration {
            val preference = if (this in setOf(
                    NORMAL_SOLO,
                    DEFERRED_SOLO,
                    PENDING_SOLO,
                    AWAY_SOLO
                )) {
                PlayPreference.SOLO
            } else {
                PlayPreference.OPEN_TO_JOIN
            }
            val absenceStatus = when (this) {
                DEFERRED_OPEN, DEFERRED_SOLO -> QueueAbsenceStatus.DEFER_ONE_ROUND
                AWAY_OPEN, AWAY_SOLO -> QueueAbsenceStatus.TEMPORARILY_AWAY
                else -> QueueAbsenceStatus.NONE
            }
            return Registration(
                key = key,
                displayId = "玩家-$key",
                preference = preference,
                absenceStatus = absenceStatus,
                createdAtMillis = 100L,
                requiresOnSiteCheckIn = this == PENDING_OPEN || this == PENDING_SOLO
            )
        }
    }
}
