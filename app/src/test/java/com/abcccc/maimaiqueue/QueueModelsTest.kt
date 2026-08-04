package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueModelsTest {
    @Test
    fun playerProfileAliasUpdatesQueueReferenceAndVisibleDetails() {
        val oldId = "00000000-0000-0000-0000-000000000901"
        val currentId = "00000000-0000-0000-0000-000000000902"
        val profile = PlayerProfile(
            id = currentId,
            nickname = "当前昵称",
            gender = PlayerGender.FEMALE,
            defaultPreference = ProfilePlayPreference.OPEN_TO_JOIN,
            createdAtMillis = 100L,
            updatedAtMillis = 100L
        )
        val queue = MachineQueue(
            waiting = listOf(
                Registration(
                    key = 1,
                    displayId = "旧昵称",
                    preference = PlayPreference.OPEN_TO_JOIN,
                    isTemporary = false,
                    createdAtMillis = 100L,
                    playerProfileId = oldId
                )
            )
        )

        val updated = queue.resolvePlayerProfileAliases(
            mapOf(oldId to currentId),
            listOf(profile)
        ).waiting.single()

        assertEquals(currentId, updated.playerProfileId)
        assertEquals("当前昵称", updated.displayId)
        assertEquals(PlayerGender.FEMALE, updated.gender)
    }

    @Test
    fun roundEndPreviewIncludesCurrentPlayersWhenEveryWaitingRegistrationIsUnavailable() {
        val current = registration(1, PlayPreference.SOLO)
        val deferred = registration(2, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
        )
        val away = registration(3, PlayPreference.SOLO).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
        )
        val queue = MachineQueue(
            playing = listOf(current),
            waiting = listOf(deferred, away),
            playingStartedAtMillis = 100L
        )

        val preview = queue.nextPlayingPositionPreviewAfterRoundEnd()

        assertEquals(listOf(1), preview?.nextRegistrations?.map { it.key })
        assertEquals(setOf(2, 3), preview?.unavailableRegistrations?.map { it.key }?.toSet())
        assertTrue(preview?.changedByAvailability == true)
    }

    @Test
    fun removalPreviewDoesNotReuseCurrentPlayersWhenWaitingRegistrationsAreUnavailable() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(3, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                )
            )
        )

        val preview = queue.nextPlayingPositionPreviewAfterCurrentRoundRemoved()

        assertTrue(preview?.nextRegistrations?.isEmpty() == true)
        assertEquals(setOf(2, 3), preview?.unavailableRegistrations?.map { it.key }?.toSet())
    }

    private fun registration(
        key: Int,
        preference: PlayPreference = PlayPreference.OPEN_TO_JOIN
    ) = Registration(key, "玩家-$key", preference)

    @Test
    fun adjacentOpenRegistrationsShareOnePosition() {
        val positions = groupIntoPositions(listOf(registration(1), registration(2), registration(3)))

        assertEquals(listOf(2, 1), positions.map { it.size })
    }

    @Test
    fun soloRegistrationAlwaysOccupiesItsOwnPosition() {
        val positions = groupIntoPositions(
            listOf(
                registration(1),
                registration(2, PlayPreference.SOLO),
                registration(3)
            )
        )

        assertEquals(listOf(1, 1, 1), positions.map { it.size })
    }

    @Test
    fun finishedPlayersReturnToEndInOriginalOrder() {
        val queue = MachineQueue(
            playing = listOf(registration(1), registration(2)),
            waiting = listOf(registration(3, PlayPreference.SOLO))
        ).finishRound()

        assertEquals(listOf(3), queue.playing.map { it.key })
        assertEquals(listOf(1, 2), queue.waiting.map { it.key })
    }

    @Test
    fun completingARoundClearsOnlyTheCompletedPlayersCurrentNoShowState() {
        val completed = registration(1, PlayPreference.SOLO).copy(
            noShowCount = 2,
            lastNoShowActionWasDefer = true
        )
        val next = registration(2, PlayPreference.SOLO).copy(
            noShowCount = 1,
            lastNoShowActionWasDefer = true
        )

        val queue = MachineQueue(
            playing = listOf(completed),
            waiting = listOf(next)
        ).finishRound(8_000L)

        assertEquals(1, queue.playing.single().noShowCount)
        assertTrue(queue.playing.single().lastNoShowActionWasDefer)
        assertEquals(0, queue.waiting.single().noShowCount)
        assertTrue(!queue.waiting.single().lastNoShowActionWasDefer)
        assertEquals(8_000L, queue.waiting.single().lastPlayedAtMillis)
    }

    @Test
    fun removingCurrentRoundRegistrationsStartsTheNextRound() {
        val queue = MachineQueue(
            playing = listOf(registration(1), registration(2)),
            waiting = listOf(registration(3, PlayPreference.SOLO))
        )

        val advanced = queue.removeCurrentRoundAndStartNext(4_000L)

        assertEquals(listOf(3), advanced.playing.map { it.key })
        assertTrue(advanced.waiting.isEmpty())
        assertEquals(4_000L, advanced.playingStartedAtMillis)
        assertTrue(advanced.allRegistrations.none { it.key == 1 || it.key == 2 })
    }

    @Test
    fun removingCurrentRoundAndStartingNextKeepsAbsenceRules() {
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                ),
                registration(3, PlayPreference.SOLO)
            )
        )

        val advanced = queue.removeCurrentRoundAndStartNext(5_000L)

        assertEquals(listOf(3), advanced.playing.map { it.key })
        assertEquals(5_000L, advanced.playingStartedAtMillis)
        assertEquals(listOf(1, 2), advanced.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, advanced.waiting[0].absenceStatus)
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, advanced.waiting[1].absenceStatus)
        assertEquals(1, advanced.waiting[1].temporaryAwaySkippedTurns)
        assertTrue(advanced.allRegistrations.none { it.key == 9 })
    }

    @Test
    fun correctingAnErroneousPlayingPlacementDoesNotClearNoShowState() {
        val registration = registration(1, PlayPreference.SOLO).copy(
            noShowCount = 2,
            lastNoShowActionWasDefer = true
        )

        val corrected = MachineQueue(playing = listOf(registration))
            .returnPlayingRegistrationsToWaitingFront(setOf(1))
            .waiting
            .single()

        assertEquals(2, corrected.noShowCount)
        assertTrue(corrected.lastNoShowActionWasDefer)
    }

    @Test
    fun deferOneRoundFromPlayingKeepsRegistrationAtFrontAndStartsFollowingGroup() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO))
        ).deferOneRound(1)

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertEquals(listOf(1), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, queue.waiting.single().absenceStatus)
    }

    @Test
    fun deferOneRoundFromPlayingDoesNotReserveAnOpenPartnerSlot() {
        val queue = MachineQueue(
            playing = listOf(registration(1)),
            waiting = listOf(registration(2), registration(3))
        ).deferOneRound(1)

        assertEquals(listOf(2, 3), queue.playing.map { it.key })
        assertEquals(listOf(1), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, queue.waiting.single().absenceStatus)
    }

    @Test
    fun deferOneRoundWithNoFollowingGroupConsumesTheDeferralAndStaysAtFront() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO))
        ).deferOneRound(1)

        assertTrue(queue.playing.isEmpty())
        assertEquals(listOf(1), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, queue.waiting.single().absenceStatus)
    }

    @Test
    fun waitingDeferralLetsFollowingGroupPlayThenClearsWithoutMovingToTail() {
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(2, PlayPreference.SOLO)
            )
        ).finishRound(1_000L)

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertEquals(listOf(1, 9), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, queue.waiting.first().absenceStatus)
    }

    @Test
    fun cancelDeferOneRoundKeepsCurrentOrderAndRestoresNextOpportunity() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(3, PlayPreference.SOLO)
            )
        ).cancelDeferOneRound(2)

        assertEquals(listOf(2, 3), queue.waiting.map { it.key })
        assertTrue(queue.waiting.all { it.absenceStatus == QueueAbsenceStatus.NONE })
    }

    @Test
    fun consecutiveOneRoundDeferralsAreConsumedTogetherWithoutChangingTheirOrder() {
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(3, PlayPreference.SOLO)
            )
        ).finishRound(1_000L)

        assertEquals(listOf(3), queue.playing.map { it.key })
        assertEquals(listOf(1, 2, 9), queue.waiting.map { it.key })
        assertTrue(queue.waiting.take(2).all { it.absenceStatus == QueueAbsenceStatus.NONE })
    }

    @Test
    fun temporarilyAwayRegistrationMovesToTailAndIncrementsSkippedTurns() {
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                ),
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            )
        ).finishRound(1_000L)

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertEquals(listOf(3, 9, 1), queue.waiting.map { it.key })
        assertEquals(1, queue.waiting.last().temporaryAwaySkippedTurns)
    }

    @Test
    fun temporarilyLeavingFromPlayingCountsOnceAndDoesNotRepeatInSameAdvance() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO))
        ).temporarilyLeave(1)

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertEquals(listOf(1), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, queue.waiting.single().absenceStatus)
        assertEquals(1, queue.waiting.single().temporaryAwaySkippedTurns)
    }

    @Test
    fun fourthTemporarilyAwayOpportunityRemovesRegistration() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                    temporaryAwaySkippedTurns = 3
                ),
                registration(2, PlayPreference.SOLO)
            )
        ).enterPlayingPosition()

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertTrue(queue.allRegistrations.none { it.key == 1 })
    }

    @Test
    fun allTemporarilyAwayRegistrationsOnlyCountOncePerAdvanceAttempt() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                ),
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                )
            )
        ).enterPlayingPosition()

        assertTrue(queue.playing.isEmpty())
        assertEquals(listOf(1, 2), queue.waiting.map { it.key })
        assertTrue(queue.waiting.all { it.temporaryAwaySkippedTurns == 1 })
    }

    @Test
    fun cancellingTemporaryLeaveClearsCountWithoutChangingOrder() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                    temporaryAwaySkippedTurns = 2
                ),
                registration(2, PlayPreference.SOLO)
            )
        ).cancelTemporaryLeave(1)

        assertEquals(listOf(1, 2), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, queue.waiting.first().absenceStatus)
        assertEquals(0, queue.waiting.first().temporaryAwaySkippedTurns)
    }

    @Test
    fun erroneousPlayingPositionReturnsToWaitingFrontWithoutStartingNextRound() {
        val queue = MachineQueue(
            playing = listOf(registration(1), registration(2)),
            waiting = listOf(registration(3, PlayPreference.SOLO)),
            playingStartedAtMillis = 123L
        ).returnPlayingRegistrationsToWaitingFront(setOf(1, 2))

        assertTrue(queue.playing.isEmpty())
        assertEquals(listOf(1, 2, 3), queue.waiting.map { it.key })
        assertEquals(null, queue.playingStartedAtMillis)
    }

    @Test
    fun onePlayerCanReturnToWaitingFrontWhileTheOtherContinuesTheRound() {
        val queue = MachineQueue(
            playing = listOf(registration(1), registration(2)),
            waiting = listOf(registration(3, PlayPreference.SOLO)),
            playingStartedAtMillis = 123L
        ).returnPlayingRegistrationsToWaitingFront(setOf(1))

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertEquals(PlayPreference.OPEN_TO_JOIN, queue.playing.single().preference)
        assertEquals(listOf(1, 3), queue.waiting.map { it.key })
        assertEquals(PlayPreference.OPEN_TO_JOIN, queue.waiting.first().preference)
        assertEquals(123L, queue.playingStartedAtMillis)
    }

    @Test
    fun returningOneFixedPartnerReleasesBothRegistrations() {
        val first = registration(1).copy(fixedPartnerKey = 2)
        val second = registration(2).copy(fixedPartnerKey = 1)
        val queue = MachineQueue(
            playing = listOf(first, second),
            waiting = listOf(registration(3, PlayPreference.SOLO)),
            playingStartedAtMillis = 123L
        ).returnPlayingRegistrationsToWaitingFront(setOf(1))

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertEquals(listOf(1, 3), queue.waiting.map { it.key })
        assertTrue(queue.allRegistrations.all { it.fixedPartnerKey == null })
        assertTrue(queue.allRegistrations.all { it.preference == PlayPreference.OPEN_TO_JOIN || it.key == 3 })
    }

    @Test
    fun firstWaitingPlayerCanJoinCurrentSoloRound() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 123L
        ).moveFirstWaitingRegistrationIntoCurrentRound(2)

        assertEquals(listOf(1, 2), queue.playing.map { it.key })
        assertTrue(queue.playing.all { it.preference == PlayPreference.OPEN_TO_JOIN })
        assertEquals(listOf(3), queue.waiting.map { it.key })
        assertEquals(123L, queue.playingStartedAtMillis)
    }

    @Test
    fun playerOutsideFirstWaitingPositionCannotJoinCurrentRound() {
        val original = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            )
        )

        assertEquals(original, original.moveFirstWaitingRegistrationIntoCurrentRound(3))
    }

    @Test
    fun movingOneFixedPartnerIntoCurrentRoundReleasesTheOtherPartner() {
        val firstPartner = registration(2).copy(fixedPartnerKey = 3)
        val secondPartner = registration(3).copy(fixedPartnerKey = 2)
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(firstPartner, secondPartner)
        ).moveFirstWaitingRegistrationIntoCurrentRound(3)

        assertEquals(listOf(1, 3), queue.playing.map { it.key })
        assertTrue(queue.playing.all { it.fixedPartnerKey == null })
        assertEquals(listOf(2), queue.waiting.map { it.key })
        assertEquals(PlayPreference.OPEN_TO_JOIN, queue.waiting.single().preference)
        assertEquals(null, queue.waiting.single().fixedPartnerKey)
    }

    @Test
    fun overtimeCorrectionConsumesDeferralWithoutTreatingItAsPlayed() {
        val correctedAt = 9_000L
        val queue = MachineQueue(
            playing = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                )
            ),
            waiting = listOf(
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(3, PlayPreference.SOLO),
                registration(4, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 123L
        ).advanceToWaitingPosition(setOf(3), correctedAt)

        assertEquals(listOf(3), queue.playing.map { it.key })
        assertEquals(listOf(2, 4, 1), queue.waiting.map { it.key })
        assertEquals(correctedAt, queue.playingStartedAtMillis)
        assertEquals(correctedAt, queue.waiting.first { it.key == 1 }.lastPlayedAtMillis)
        assertEquals(null, queue.waiting.first { it.key == 2 }.lastPlayedAtMillis)
        assertTrue(queue.allRegistrations.all { it.absenceStatus == QueueAbsenceStatus.NONE })
    }

    @Test
    fun overtimeCorrectionKeepsTemporaryLeaveAndCountsTheSkippedOpportunity() {
        val correctedAt = 9_000L
        val original = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                    temporaryAwaySkippedTurns = 1
                ),
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            )
        )
        val queue = original.advanceToWaitingPosition(setOf(3), correctedAt)
        val sequential = original.finishRound(correctedAt).finishRound(correctedAt)

        assertEquals(listOf(3), queue.playing.map { it.key })
        assertEquals(listOf(9, 1, 2), queue.waiting.map { it.key })
        assertEquals(sequential, queue)
        val temporarilyAway = queue.waiting.first { it.key == 1 }
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, temporarilyAway.absenceStatus)
        assertEquals(2, temporarilyAway.temporaryAwaySkippedTurns)
        assertEquals(null, temporarilyAway.lastPlayedAtMillis)
    }

    @Test
    fun overtimeCorrectionRemovesOnlyPendingRegistrationFromAMixedPosition() {
        val correctedAt = 9_000L
        val pending = registration(1, PlayPreference.SOLO).copy(requiresOnSiteCheckIn = true)
        val availablePartner = registration(2, PlayPreference.SOLO)
        val corrected = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                pending,
                availablePartner,
                registration(3, PlayPreference.SOLO),
                registration(4, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 123L
        ).advanceToWaitingPosition(setOf(3), correctedAt)

        assertEquals(listOf(3), corrected.playing.map { it.key })
        assertFalse(corrected.allRegistrations.any { it.key == pending.key })
        assertEquals(listOf(4, 9, 2), corrected.waiting.map { it.key })
        assertEquals(
            correctedAt,
            corrected.waiting.first { it.key == availablePartner.key }.lastPlayedAtMillis
        )
    }

    @Test
    fun overtimeCorrectionRequiresACompletePositionAfterTheFirstWaitingPosition() {
        val pairedTarget = listOf(registration(3), registration(4))
        val original = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO)) + pairedTarget,
            playingStartedAtMillis = 123L
        )

        assertEquals(original, original.advanceToWaitingPosition(setOf(2), 9_000L))
        assertEquals(original, original.advanceToWaitingPosition(setOf(3), 9_000L))
        assertEquals(listOf(3, 4), original.advanceToWaitingPosition(setOf(3, 4), 9_000L).playing.map { it.key })
    }

    @Test
    fun overtimeCorrectionDoesNotApplyWhenNormalRotationRegroupsTheSelectedPosition() {
        val original = MachineQueue(
            playing = listOf(registration(1)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO),
                registration(3)
            ),
            playingStartedAtMillis = 123L
        )

        // After registration 2 plays, registration 1 returns to the tail and
        // shares the next position with registration 3.
        assertEquals(
            original,
            original.advanceToWaitingPosition(setOf(3), 9_000L)
        )
    }

    @Test
    fun noShowIsOnlyAvailableInPlayingAndFirstWaitingPosition() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            )
        )

        assertTrue(queue.canMarkNoShow(1))
        assertTrue(queue.canMarkNoShow(2))
        assertTrue(!queue.canMarkNoShow(3))
        assertTrue(!queue.canMarkNoShow(999))
    }

    @Test
    fun noShowDeferOneRoundKeepsFirstWaitingPositionUntilItIsSkipped() {
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO),
                registration(2, PlayPreference.SOLO)
            )
        ).markNoShowDeferOneRound(1)

        assertEquals(listOf(1, 2), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.DEFER_ONE_ROUND, queue.waiting.first().absenceStatus)
        assertEquals(1, queue.waiting.first().noShowCount)

        val advanced = queue.finishRound(1_000L)
        assertEquals(listOf(2), advanced.playing.map { it.key })
        assertEquals(listOf(1, 9), advanced.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, advanced.waiting.first().absenceStatus)
    }

    @Test
    fun noShowInPlayingPositionConsumesDeferralAndAdvancesExactlyOnce() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO)
            )
        ).markNoShowDeferOneRound(1)

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertEquals(listOf(1), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, queue.waiting.single().absenceStatus)
        assertEquals(1, queue.waiting.single().noShowCount)
    }

    @Test
    fun matchingAbsenceStatesKeepOpenRegistrationsInOnePosition() {
        val deferred = listOf(
            registration(1).copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND),
            registration(2).copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND),
            registration(3, PlayPreference.SOLO)
        )

        assertEquals(listOf(2, 1), groupIntoPositions(deferred).map { it.size })
    }

    @Test
    fun unavailableOpenRegistrationDoesNotBreakFollowingSharedPosition() {
        val registrations = listOf(
            registration(1),
            registration(2).copy(absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY),
            registration(3),
            registration(4)
        )

        assertEquals(
            listOf(listOf(1, 3), listOf(2), listOf(4)),
            groupIntoPositions(registrations).map { position -> position.map { it.key } }
        )
    }

    @Test
    fun nextPlayingPreviewExplainsOpenPlayersRegroupedAroundTemporaryLeave() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1),
                registration(2).copy(absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY),
                registration(3),
                registration(4)
            )
        )

        val preview = queue.nextPlayingPositionPreview()!!

        assertEquals(listOf(1, 2), preview.nominalRegistrations.map { it.key })
        assertEquals(listOf(1, 3), preview.nextRegistrations.map { it.key })
        assertEquals(listOf(2), preview.unavailableRegistrations.map { it.key })
        assertTrue(preview.changedByAvailability)
    }

    @Test
    fun fixedPairIsSkippedTogetherWithoutBlockingFollowingOpenPlayers() {
        val firstPartner = registration(1).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            fixedPartnerKey = 2
        )
        val secondPartner = registration(2).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            fixedPartnerKey = 1
        )
        val queue = MachineQueue(
            waiting = listOf(firstPartner, secondPartner, registration(3), registration(4))
        )

        assertEquals(
            listOf(listOf(1, 2), listOf(3, 4)),
            queue.waitingPositions().map { position -> position.map { it.key } }
        )
        assertEquals(listOf(3, 4), queue.nextPlayingPositionPreview()!!.nextRegistrations.map { it.key })
    }

    @Test
    fun fixedPairTemporaryLeaveAdvancesAndExpiresAsOneGroup() {
        val firstPartner = registration(1).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 1,
            fixedPartnerKey = 2
        )
        val secondPartner = registration(2).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 2,
            fixedPartnerKey = 1
        )
        val queue = MachineQueue(
            waiting = listOf(firstPartner, secondPartner, registration(3, PlayPreference.SOLO))
        ).enterPlayingPosition()

        assertEquals(listOf(3), queue.playing.map { it.key })
        assertEquals(listOf(1, 2), queue.waiting.map { it.key })
        assertTrue(queue.waiting.all {
            it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
                it.temporaryAwaySkippedTurns == 3
        })
        val expired = queue.finishRound(1_000L)
        assertTrue(expired.allRegistrations.none { it.key == 1 || it.key == 2 })
    }

    @Test
    fun deferredOpenRegistrationDoesNotBreakFollowingSharedPosition() {
        val registrations = listOf(
            registration(1),
            registration(2).copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND),
            registration(3),
            registration(4)
        )

        assertEquals(
            listOf(listOf(1, 3), listOf(2), listOf(4)),
            groupIntoPositions(registrations).map { position -> position.map { it.key } }
        )
    }

    @Test
    fun regroupedRoundConsumesCrossedOneRoundDeferralImmediately() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1),
                registration(2).copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND),
                registration(3),
                registration(4)
            )
        ).enterPlayingPosition()

        assertEquals(listOf(1, 3), queue.playing.map { it.key })
        assertEquals(listOf(2, 4), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, queue.waiting.first().absenceStatus)
    }

    @Test
    fun regroupedRoundMovesCrossedTemporaryLeaveToTailAndCountsItImmediately() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1),
                registration(2).copy(absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY),
                registration(3),
                registration(4)
            )
        ).enterPlayingPosition()

        assertEquals(listOf(1, 3), queue.playing.map { it.key })
        assertEquals(listOf(4, 2), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, queue.waiting.last().absenceStatus)
        assertEquals(1, queue.waiting.last().temporaryAwaySkippedTurns)
    }

    @Test
    fun regroupedRoundRemovesCrossedTemporaryLeaveOnFourthOpportunity() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1),
                registration(2).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                    temporaryAwaySkippedTurns = 3
                ),
                registration(3),
                registration(4)
            )
        ).enterPlayingPosition()

        assertEquals(listOf(1, 3), queue.playing.map { it.key })
        assertEquals(listOf(4), queue.waiting.map { it.key })
        assertTrue(queue.allRegistrations.none { it.key == 2 })
    }

    @Test
    fun regroupedRoundCountsCrossedTemporarilyAwayFixedPairTogether() {
        val firstPartner = registration(2).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 1,
            fixedPartnerKey = 3
        )
        val secondPartner = registration(3).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 2,
            fixedPartnerKey = 2
        )
        val queue = MachineQueue(
            waiting = listOf(registration(1), firstPartner, secondPartner, registration(4))
        ).enterPlayingPosition()

        assertEquals(listOf(1, 4), queue.playing.map { it.key })
        assertEquals(listOf(2, 3), queue.waiting.map { it.key })
        assertTrue(queue.waiting.all {
            it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
                it.temporaryAwaySkippedTurns == 3
        })
    }

    @Test
    fun unavailableRegistrationsAfterNextSoloRoundRemainUnchanged() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO),
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(3, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                    temporaryAwaySkippedTurns = 2
                )
            )
        ).enterPlayingPosition()

        assertEquals(listOf(1), queue.playing.map { it.key })
        assertEquals(QueueAbsenceStatus.DEFER_ONE_ROUND, queue.waiting[0].absenceStatus)
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, queue.waiting[1].absenceStatus)
        assertEquals(2, queue.waiting[1].temporaryAwaySkippedTurns)
    }

    @Test
    fun noShowCanTargetFirstAvailablePositionAfterSkippedPositions() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1).copy(absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY),
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            )
        )

        assertTrue(!queue.canMarkNoShow(1))
        assertTrue(queue.canMarkNoShow(2))
        assertTrue(!queue.canMarkNoShow(3))
    }

    @Test
    fun noShowCanPassSeveralDeferredOrTemporarilyAwayPositions() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                ),
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(3, PlayPreference.SOLO),
                registration(4, PlayPreference.SOLO)
            )
        )

        assertTrue(!queue.canMarkNoShow(1))
        assertTrue(!queue.canMarkNoShow(2))
        assertTrue(queue.canMarkNoShow(3))
        assertTrue(!queue.canMarkNoShow(4))
    }

    @Test
    fun deferredAndTemporarilyAwayRegistrationsCannotBeMarkedNoShow() {
        val queue = MachineQueue(
            playing = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                )
            ),
            waiting = listOf(
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                ),
                registration(3, PlayPreference.SOLO)
            )
        )

        assertTrue(!queue.canMarkNoShow(1))
        assertTrue(!queue.canMarkNoShow(2))
        assertTrue(queue.canMarkNoShow(3))
        assertEquals(queue, queue.markNoShowDeferOneRound(1))
        assertEquals(queue, queue.markNoShowMoveToEnd(setOf(2)))
        assertEquals(queue, queue.markNoShowGroupDeferOneRound(setOf(1, 2)))
    }

    @Test
    fun noShowDefersAnOpenSharedPositionAsOneGroup() {
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1),
                registration(2),
                registration(3, PlayPreference.SOLO)
            )
        ).markNoShowGroupDeferOneRound(setOf(1, 2))

        assertEquals(2, queue.waitingPositions().first().size)
        assertTrue(
            queue.waiting.take(2).all {
                it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND && it.noShowCount == 1
            }
        )

        val advanced = queue.finishRound(1_000L)
        assertEquals(listOf(3), advanced.playing.map { it.key })
        assertEquals(listOf(1, 2, 9), advanced.waiting.map { it.key })
        assertTrue(advanced.waiting.take(2).all { it.absenceStatus == QueueAbsenceStatus.NONE })
    }

    @Test
    fun noShowDefersAPlayingGroupAndAdvancesTheNextPositionExactlyOnce() {
        val queue = MachineQueue(
            playing = listOf(registration(1), registration(2)),
            waiting = listOf(registration(3, PlayPreference.SOLO))
        ).markNoShowGroupDeferOneRound(setOf(1, 2))

        assertEquals(listOf(3), queue.playing.map { it.key })
        assertEquals(listOf(1, 2), queue.waiting.map { it.key })
        assertTrue(queue.waiting.all { it.absenceStatus == QueueAbsenceStatus.NONE })
        assertTrue(queue.waiting.all { it.noShowCount == 1 })
    }

    @Test
    fun movingNoShowFromPlayingToTailAdvancesTheNextPosition() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO))
        ).markNoShowMoveToEnd(setOf(1))

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertEquals(listOf(1), queue.waiting.map { it.key })
        assertEquals(1, queue.waiting.single().noShowCount)
    }

    @Test
    fun noShowDeferralFromPlayingCanLeavePositionEmptyUntilManualAdvance() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO)),
            playingStartedAtMillis = 100L
        ).markNoShowDeferOneRound(
            1,
            startNextWhenPlayingBecomesEmpty = false
        )

        assertTrue(queue.playing.isEmpty())
        assertEquals(null, queue.playingStartedAtMillis)
        assertEquals(listOf(1, 2), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.DEFER_ONE_ROUND, queue.waiting.first().absenceStatus)

        val manuallyAdvanced = queue.enterPlayingPosition()
        assertEquals(listOf(2), manuallyAdvanced.playing.map { it.key })
        assertEquals(listOf(1), manuallyAdvanced.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.NONE, manuallyAdvanced.waiting.single().absenceStatus)
    }

    @Test
    fun groupNoShowDeferralFromPlayingCanLeavePositionEmptyUntilManualAdvance() {
        val queue = MachineQueue(
            playing = listOf(registration(1), registration(2)),
            waiting = listOf(registration(3, PlayPreference.SOLO)),
            playingStartedAtMillis = 100L
        ).markNoShowGroupDeferOneRound(
            setOf(1, 2),
            startNextWhenPlayingBecomesEmpty = false
        )

        assertTrue(queue.playing.isEmpty())
        assertEquals(null, queue.playingStartedAtMillis)
        assertEquals(listOf(1, 2, 3), queue.waiting.map { it.key })
        assertTrue(queue.waiting.take(2).all {
            it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND && it.noShowCount == 1
        })

        val manuallyAdvanced = queue.enterPlayingPosition()
        assertEquals(listOf(3), manuallyAdvanced.playing.map { it.key })
        assertEquals(listOf(1, 2), manuallyAdvanced.waiting.map { it.key })
        assertTrue(manuallyAdvanced.waiting.all { it.absenceStatus == QueueAbsenceStatus.NONE })
    }

    @Test
    fun movingNoShowFromPlayingToTailCanLeavePositionEmptyUntilManualAdvance() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO)),
            playingStartedAtMillis = 100L
        ).markNoShowMoveToEnd(
            setOf(1),
            startNextWhenPlayingBecomesEmpty = false
        )

        assertTrue(queue.playing.isEmpty())
        assertEquals(null, queue.playingStartedAtMillis)
        assertEquals(listOf(2, 1), queue.waiting.map { it.key })
        assertEquals(1, queue.waiting.last().noShowCount)

        val manuallyAdvanced = queue.enterPlayingPosition()
        assertEquals(listOf(2), manuallyAdvanced.playing.map { it.key })
        assertEquals(listOf(1), manuallyAdvanced.waiting.map { it.key })
    }

    @Test
    fun removingNoShowFromPlayingCanLeavePositionEmptyUntilManualAdvance() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO)),
            playingStartedAtMillis = 100L
        ).markNoShowAndRemove(
            setOf(1),
            startNextWhenPlayingBecomesEmpty = false
        )

        assertTrue(queue.playing.isEmpty())
        assertEquals(null, queue.playingStartedAtMillis)
        assertEquals(listOf(2), queue.waiting.map { it.key })

        val manuallyAdvanced = queue.enterPlayingPosition()
        assertEquals(listOf(2), manuallyAdvanced.playing.map { it.key })
        assertTrue(manuallyAdvanced.waiting.isEmpty())
    }

    @Test
    fun claimingWithPlayerProfileKeepsOrderAndCurrentPreferenceByDefault() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2), registration(3, PlayPreference.SOLO)),
            playingStartedAtMillis = 100L
        )

        val claimed = queue.claimWithPlayerProfile(
            registrationKey = 2,
            playerProfileId = "profile-2",
            playerNickname = "  资料昵称  ",
            gender = PlayerGender.FEMALE
        )
        val claimedRegistration = claimed.allRegistrations.first { it.key == 2 }

        assertEquals(queue.allRegistrations.map { it.key }, claimed.allRegistrations.map { it.key })
        assertEquals(PlayPreference.OPEN_TO_JOIN, claimedRegistration.preference)
        assertEquals("资料昵称", claimedRegistration.displayId)
        assertEquals("profile-2", claimedRegistration.playerProfileId)
        assertEquals(PlayerGender.FEMALE, claimedRegistration.gender)
        assertTrue(!claimedRegistration.isTemporary)
    }

    @Test
    fun claimingRejectsANicknameAlreadyUsedByAnotherRegistration() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1).copy(displayId = "现有昵称"),
                registration(2)
            )
        )

        val claimed = queue.claimWithPlayerProfile(
            registrationKey = 2,
            playerProfileId = "profile-2",
            playerNickname = "现有昵称",
            gender = PlayerGender.FEMALE
        )

        assertEquals(queue, claimed)
    }

    @Test
    fun claimingCanExplicitlyUsePlayerProfilePreference() {
        val queue = MachineQueue(
            waiting = listOf(registration(1), registration(2)),
            playingStartedAtMillis = null
        )

        val claimed = queue.claimWithPlayerProfile(
            registrationKey = 1,
            playerProfileId = "profile-1",
            playerNickname = "资料昵称",
            gender = PlayerGender.MALE,
            preferenceOverride = PlayPreference.SOLO
        )

        assertEquals(PlayPreference.SOLO, claimed.allRegistrations.first { it.key == 1 }.preference)
        assertEquals(listOf(1, 2), claimed.allRegistrations.map { it.key })
    }

    @Test
    fun usingProfilePreferenceWhileClaimingReleasesAnExistingFixedPair() {
        val first = registration(1).copy(fixedPartnerKey = 2)
        val second = registration(2).copy(fixedPartnerKey = 1)
        val queue = MachineQueue(waiting = listOf(first, second))

        val claimed = queue.claimWithPlayerProfile(
            registrationKey = 1,
            playerProfileId = "profile-1",
            playerNickname = "资料昵称",
            gender = PlayerGender.MALE,
            preferenceOverride = PlayPreference.SOLO
        )

        assertEquals(PlayPreference.SOLO, claimed.waiting.first { it.key == 1 }.preference)
        assertTrue(claimed.waiting.all { it.fixedPartnerKey == null })
        assertEquals(PlayPreference.OPEN_TO_JOIN, claimed.waiting.first { it.key == 2 }.preference)
    }

    @Test
    fun stoppedMachineCannotBeStoppedAgainUntilRestored() {
        val stopped = MachineStatus().stop(MachineStopReason.NOT_POWERED_ON, 100L)
        val repeatedStop = stopped.stop(MachineStopReason.OTHER, 200L)

        assertEquals(MachineStopReason.NOT_POWERED_ON, repeatedStop.stopReason)
        assertEquals(100L, repeatedStop.stoppedAtMillis)
        assertTrue(repeatedStop.restore().isOperational)
    }

    @Test
    fun machineStopDetailIsNormalizedAndKeptOnlyForOtherReason() {
        val other = MachineStatus().stop(
            MachineStopReason.OTHER,
            100L,
            "  按钮\n失灵  "
        )
        val maintenance = MachineStatus().stop(
            MachineStopReason.MAINTENANCE,
            200L,
            "不应保留"
        )

        assertEquals("按钮失灵", other.stopReasonDetail)
        assertEquals(null, maintenance.stopReasonDetail)
        assertEquals(MachineStopReason.MAINTENANCE, maintenance.stopReason)
        assertEquals(
            "😀".repeat(MAX_MACHINE_STOP_REASON_DETAIL_CHARACTERS),
            normalizeMachineStopReasonDetail(
                MachineStopReason.OTHER,
                "😀".repeat(MAX_MACHINE_STOP_REASON_DETAIL_CHARACTERS + 1)
            )
        )
    }

    @Test
    fun restoringAfterMachineStopKeepsQueueAndRestartsRoundTimer() {
        val pending = registration(3, PlayPreference.SOLO).copy(
            createdAtMillis = 75L,
            requiresOnSiteCheckIn = true
        )
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO),
                pending
            ),
            playingStartedAtMillis = 50L
        )

        val restored = queue.restartPlayingTimer(atMillis = 300L)
            .restartPendingCheckInTimers(atMillis = 300L)

        assertFalse(pending.hasRestartedOnSiteCheckInWindow)
        assertEquals(queue.playing, restored.playing)
        assertEquals(queue.waiting.first(), restored.waiting.first())
        assertEquals(75L, restored.waiting.last().createdAtMillis)
        assertEquals(300L, restored.waiting.last().onSiteCheckInStartedAtMillis)
        assertTrue(restored.waiting.last().hasRestartedOnSiteCheckInWindow)
        assertEquals(
            300L + ONLINE_REGISTRATION_CHECK_IN_TIMEOUT_MILLIS,
            restored.waiting.last().onSiteCheckInDeadlineMillis
        )
        assertTrue(restored.waiting.last().requiresOnSiteCheckIn)
        assertEquals(300L, restored.playingStartedAtMillis)
    }

    @Test
    fun queueIdUsesFiveCharactersAndEllipsisOnlyWhenLongerThanSixCharacters() {
        assertEquals("一二三四五六", queueDisplayId("一二三四五六"))
        assertEquals("一二三四五…", queueDisplayId("一二三四五六七"))
        assertEquals("甲乙😀丁戊…", queueDisplayId("甲乙😀丁戊己庚"))
    }

    @Test
    fun nicknameLengthLimitDoesNotSplitSupplementaryUnicodeCharacters() {
        assertEquals("甲乙😀丁", limitCodePointLength("甲乙😀丁", 4))
        assertEquals("甲乙😀", limitCodePointLength("甲乙😀丁", 3))
        assertEquals("", limitCodePointLength("甲乙", 0))
    }

    @Test
    fun joiningRejectsDuplicateAndInvalidRegistrationKeys() {
        val queue = MachineQueue().joinAll(
            listOf(
                registration(1, PlayPreference.SOLO),
                Registration(1, "另一名玩家", PlayPreference.SOLO),
                Registration(0, "无效登记", PlayPreference.SOLO),
                registration(2, PlayPreference.SOLO)
            )
        )

        assertEquals(listOf(1, 2), queue.allRegistrations.map { it.key })
        assertEquals(2, queue.allRegistrations.map { it.key }.distinct().size)
    }

    @Test
    fun replacingWithTheOriginalOrderDoesNotResetTheCurrentRound() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO)),
            playingStartedAtMillis = 123L
        )

        assertEquals(queue, queue.replaceOrder(queue.allRegistrations))
    }

    @Test
    fun replacingWithAnIncompleteOrderDoesNotDropRegistrations() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1),
                registration(2, PlayPreference.SOLO)
            )
        )

        assertEquals(queue, queue.replaceOrder(listOf(queue.waiting.first())))
        assertEquals(queue, queue.replaceOrder(queue.allRegistrations + queue.waiting.first()))
    }

    @Test
    fun reorderingWaitingPlayersDoesNotMovePlayingPositionOrResetTimer() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 123L
        )

        val reordered = queue.replaceOrder(listOf(queue.playing.single(), queue.waiting[1], queue.waiting[0]))

        assertEquals(listOf(1), reordered.playing.map { it.key })
        assertEquals(listOf(3, 2), reordered.waiting.map { it.key })
        assertEquals(123L, reordered.playingStartedAtMillis)
    }

    @Test
    fun reorderingCannotReplaceTheCurrentPlayingPosition() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 123L
        )

        val invalidOrder = listOf(queue.waiting[0], queue.playing[0], queue.waiting[1])

        assertEquals(queue, queue.replaceOrder(invalidOrder))
    }

    @Test
    fun reorderingCannotSwapRegistrationsInsideTheCurrentPlayingPosition() {
        val queue = MachineQueue(
            playing = listOf(
                registration(1, PlayPreference.OPEN_TO_JOIN),
                registration(2, PlayPreference.OPEN_TO_JOIN)
            ),
            waiting = listOf(registration(3, PlayPreference.SOLO)),
            playingStartedAtMillis = 123L
        )

        val invalidOrder = listOf(queue.playing[1], queue.playing[0], queue.waiting[0])

        assertEquals(queue, queue.replaceOrder(invalidOrder))
    }

    @Test
    fun reorderingAnIntentionallyEmptyPlayingPositionDoesNotStartARound() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO),
                registration(2, PlayPreference.SOLO)
            )
        ).copy(playing = emptyList(), playingStartedAtMillis = null)

        val reordered = queue.replaceOrder(listOf(queue.waiting[1], queue.waiting[0]))

        assertTrue(reordered.playing.isEmpty())
        assertEquals(listOf(2, 1), reordered.waiting.map { it.key })
    }

    @Test
    fun reorderUsesCurrentRegistrationDetailsInsteadOfStaleProposalCopies() {
        val original = MachineQueue(
            waiting = listOf(
                registration(1).copy(
                    displayId = "旧昵称",
                    playerProfileId = "profile-1",
                    gender = PlayerGender.MALE
                ),
                registration(2, PlayPreference.SOLO)
            )
        )
        val staleProposal = original.waiting.reversed()
        val updated = original.syncPlayerProfileDetails(
            playerProfileId = "profile-1",
            playerNickname = "新昵称",
            gender = PlayerGender.FEMALE
        )

        val reordered = updated.replaceOrder(staleProposal)

        assertEquals(listOf(2, 1), reordered.waiting.map { it.key })
        assertEquals("新昵称", reordered.waiting.last().displayId)
        assertEquals(PlayerGender.FEMALE, reordered.waiting.last().gender)
    }

    @Test
    fun movingAWaitingPositionInsertsItAtTheChosenPosition() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO),
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            )
        )

        val reordered = queue.moveWaitingPosition(sourceIndex = 2, destinationIndex = 0)

        assertEquals(listOf(3, 1, 2), reordered.waiting.map { it.key })
    }

    @Test
    fun movingAWaitingPositionKeepsItsRegistrationsTogether() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.OPEN_TO_JOIN),
                registration(2, PlayPreference.OPEN_TO_JOIN),
                registration(3, PlayPreference.SOLO),
                registration(4, PlayPreference.SOLO)
            )
        )

        val reordered = queue.moveWaitingPosition(sourceIndex = 0, destinationIndex = 2)

        assertEquals(listOf(3, 4, 1, 2), reordered.waiting.map { it.key })
        assertEquals(listOf(1, 2), reordered.waitingPositions()[2].map { it.key })
    }

    @Test
    fun movingAWaitingPositionNeverChangesThePlayingPositionOrAcceptsAnInvalidDrop() {
        val queue = MachineQueue(
            playing = listOf(registration(10, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO),
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 123L
        )

        val reordered = queue.moveWaitingPosition(sourceIndex = 0, destinationIndex = 2)

        assertEquals(listOf(10), reordered.playing.map { it.key })
        assertEquals(listOf(2, 3, 1), reordered.waiting.map { it.key })
        assertEquals(123L, reordered.playingStartedAtMillis)
        assertEquals(queue, queue.moveWaitingPosition(sourceIndex = 1, destinationIndex = 1))
        assertEquals(queue, queue.moveWaitingPosition(sourceIndex = -1, destinationIndex = 1))
        assertEquals(queue, queue.moveWaitingPosition(sourceIndex = 1, destinationIndex = 3))
    }

    @Test
    fun staleFriendPairPlanCannotOverwriteAChangedWaitingQueue() {
        val original = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO),
                registration(2, PlayPreference.SOLO)
            )
        )
        val plan = original.planFriendPair(1, 2)!!
        val changed = original.copy(
            waiting = original.waiting + registration(3, PlayPreference.SOLO)
        )

        assertEquals(changed, changed.applyFriendPair(plan))
        assertEquals(setOf(1, 2, 3), changed.applyFriendPair(plan).waiting.map { it.key }.toSet())
    }

    @Test
    fun friendPairPlanUsesCurrentProfileDetailsInsteadOfStaleCopies() {
        val original = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    displayId = "旧昵称",
                    playerProfileId = "profile-1",
                    gender = PlayerGender.MALE
                ),
                registration(2, PlayPreference.SOLO)
            )
        )
        val plan = original.planFriendPair(1, 2)!!
        val updated = original.syncPlayerProfileDetails(
            playerProfileId = "profile-1",
            playerNickname = "新昵称",
            gender = PlayerGender.FEMALE
        )

        val paired = updated.applyFriendPair(plan)

        assertEquals(listOf(1, 2), paired.waiting.map { it.key })
        assertEquals("新昵称", paired.waiting.first().displayId)
        assertEquals(PlayerGender.FEMALE, paired.waiting.first().gender)
        assertEquals(2, paired.waiting.first().fixedPartnerKey)
        assertEquals(1, paired.waiting.last().fixedPartnerKey)
    }

    @Test
    fun creatingFriendPairFromPlayerProfileKeepsProfileIdentityAndVisibleDetails() {
        val queue = MachineQueue(
            waiting = listOf(registration(1, PlayPreference.OPEN_TO_JOIN))
        )
        val profileRegistration = registration(2, PlayPreference.SOLO).copy(
            displayId = "资料玩家",
            isTemporary = false,
            gender = PlayerGender.FEMALE,
            playerProfileId = "profile-2",
            createdAtMillis = 456_000L
        )

        val paired = queue.createFriendPair(1, profileRegistration)
        val created = paired.waiting.single { it.key == 2 }

        assertEquals("资料玩家", created.displayId)
        assertFalse(created.isTemporary)
        assertEquals(PlayerGender.FEMALE, created.gender)
        assertEquals("profile-2", created.playerProfileId)
        assertEquals(456_000L, created.createdAtMillis)
        assertEquals(1, created.fixedPartnerKey)
    }

    @Test
    fun staleFriendPairPlanIsRejectedWhenQueueBehaviorChanged() {
        val original = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO),
                registration(2, PlayPreference.SOLO)
            )
        )
        val plan = original.planFriendPair(1, 2)!!
        val changed = original.changePreference(1, PlayPreference.OPEN_TO_JOIN)

        assertEquals(changed, changed.applyFriendPair(plan))
    }

    @Test
    fun queueOperationStateIgnoresProfileDetailsButNotQueueBehavior() {
        val original = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    displayId = "旧昵称",
                    isTemporary = true,
                    gender = PlayerGender.MALE
                )
            )
        )
        val profileUpdated = original.copy(
            waiting = listOf(
                original.waiting.single().copy(
                    displayId = "新昵称",
                    isTemporary = false,
                    gender = PlayerGender.FEMALE,
                    playerProfileId = "profile-1"
                )
            )
        )
        val preferenceUpdated = profileUpdated.changePreference(1, PlayPreference.OPEN_TO_JOIN)

        assertTrue(original.hasSameQueueOperationState(profileUpdated))
        assertFalse(original.hasSameQueueOperationState(preferenceUpdated))
    }

    @Test
    fun exactPositionMatchRejectsAGroupThatWasSplitWhileConfirmationWasOpen() {
        val first = registration(1)
        val second = registration(2)
        val selection = PositionSelection(
            machineId = MachineId.A,
            label = "位置 A1",
            registrationKeys = listOf(first.key, second.key),
            isPlayingPosition = false,
            waitingPositionIndex = 0
        )
        val original = MachineQueue(waiting = listOf(first, second))
        val regrouped = original.changePreference(first.key, PlayPreference.SOLO)

        assertTrue(original.matchesExactPosition(selection))
        assertFalse(regrouped.matchesExactPosition(selection))
    }

    @Test
    fun exactPositionMatchRejectsTheSameGroupAfterItsPositionNumberChanges() {
        val leading = registration(1, PlayPreference.SOLO)
        val first = registration(2)
        val second = registration(3)
        val selection = PositionSelection(
            machineId = MachineId.A,
            label = "位置 A2",
            registrationKeys = listOf(first.key, second.key),
            isPlayingPosition = false,
            waitingPositionIndex = 1
        )
        val original = MachineQueue(waiting = listOf(leading, first, second))
        val shifted = original.remove(leading.key)

        assertTrue(original.matchesExactPosition(selection))
        assertFalse(shifted.matchesExactPosition(selection))
    }

    @Test
    fun exactPlayingPositionMatchRejectsPartialCurrentRoundChanges() {
        val first = registration(1)
        val second = registration(2)
        val selection = PositionSelection(
            machineId = MachineId.A,
            label = "游玩位置 A",
            registrationKeys = listOf(first.key, second.key),
            isPlayingPosition = true
        )
        val original = MachineQueue(playing = listOf(first, second))
        val changed = original.copy(playing = listOf(first))

        assertTrue(original.matchesExactPosition(selection))
        assertFalse(changed.matchesExactPosition(selection))
    }

    @Test
    fun fixedPairPositionMatchRequiresTheRelationshipToRemainMutual() {
        val first = registration(1).copy(fixedPartnerKey = 2)
        val second = registration(2).copy(fixedPartnerKey = 1)
        val selection = PositionSelection(
            machineId = MachineId.A,
            label = "位置 A1 · 固定组合",
            registrationKeys = listOf(first.key, second.key),
            isPlayingPosition = false,
            waitingPositionIndex = 0
        )
        val original = MachineQueue(waiting = listOf(first, second))
        val released = original.copy(
            waiting = listOf(
                first.copy(fixedPartnerKey = null),
                second.copy(fixedPartnerKey = null)
            )
        )

        assertTrue(original.matchesFixedPairPosition(selection))
        assertFalse(released.matchesFixedPairPosition(selection))
    }

    @Test
    fun noShowConfirmationDoesNotFollowARegistrationBetweenWaitingAndPlaying() {
        val playing = registration(1)
        val waiting = registration(2)
        val original = MachineQueue(playing = listOf(playing), waiting = listOf(waiting))
        val waitingSelection = SelectedRegistration(
            machineId = MachineId.A,
            registrationKey = waiting.key,
            fromPlayingPosition = false
        )
        val movedIntoPlaying = MachineQueue(playing = listOf(waiting), waiting = listOf(playing))

        assertTrue(original.matchesNoShowLocation(waitingSelection))
        assertFalse(movedIntoPlaying.matchesNoShowLocation(waitingSelection))
    }

    @Test
    fun dragReorderCrossesSeveralVariableWidthItemsWithoutLosingFingerOffset() {
        val update = calculateDragReorder(
            sourceIndex = 0,
            dragOffset = 251f,
            itemSizes = listOf(100f, 140f, 80f),
            spacing = 10f
        )

        assertEquals(2, update.destinationIndex)
        assertEquals(11f, update.remainingOffset)
    }

    @Test
    fun dragReorderKeepsFingerOffsetWhenCrossingVariableWidthItemsToTheLeft() {
        val update = calculateDragReorder(
            sourceIndex = 2,
            dragOffset = -251f,
            itemSizes = listOf(100f, 140f, 80f),
            spacing = 10f
        )

        assertEquals(0, update.destinationIndex)
        assertEquals(9f, update.remainingOffset)
    }

    @Test
    fun dragReorderUsesHysteresisInsteadOfOscillatingAfterAPlacementChange() {
        val movedRight = calculateDragReorder(
            sourceIndex = 0,
            dragOffset = 111f,
            itemSizes = listOf(100f, 100f),
            spacing = 10f
        )
        val stillRight = calculateDragReorder(
            sourceIndex = movedRight.destinationIndex,
            dragOffset = -110f,
            itemSizes = listOf(100f, 100f),
            spacing = 10f
        )

        assertEquals(1, movedRight.destinationIndex)
        assertEquals(1f, movedRight.remainingOffset)
        assertEquals(1, stillRight.destinationIndex)
        assertEquals(-110f, stillRight.remainingOffset)
    }

    @Test
    fun dragReorderDoesNotBounceBackAfterCrossingAMuchWiderItem() {
        val movedRight = calculateDragReorder(
            sourceIndex = 0,
            dragOffset = 211f,
            itemSizes = listOf(100f, 300f),
            spacing = 10f
        )
        val remainsRight = calculateDragReorder(
            sourceIndex = movedRight.destinationIndex,
            dragOffset = -210f,
            itemSizes = listOf(300f, 100f),
            spacing = 10f
        )

        assertEquals(1, movedRight.destinationIndex)
        assertEquals(-99f, movedRight.remainingOffset)
        assertEquals(1, remainsRight.destinationIndex)
        assertEquals(-210f, remainsRight.remainingOffset)
    }

    @Test
    fun dragReorderKeepsVacancyUnderOverlayUntilAdjacentCenterIsPassed() {
        val beforeCenter = calculateDragReorder(
            sourceIndex = 0,
            dragOffset = 209f,
            itemSizes = listOf(100f, 300f),
            spacing = 10f
        )
        val atCenter = calculateDragReorder(
            sourceIndex = 0,
            dragOffset = 210f,
            itemSizes = listOf(100f, 300f),
            spacing = 10f
        )
        val pastCenter = calculateDragReorder(
            sourceIndex = 0,
            dragOffset = 211f,
            itemSizes = listOf(100f, 300f),
            spacing = 10f
        )

        assertEquals(0, beforeCenter.destinationIndex)
        assertEquals(0, atCenter.destinationIndex)
        assertEquals(1, pastCenter.destinationIndex)
    }

    @Test
    fun dragReorderCannotEnterTheLockedPrefixOrPassTheListBoundary() {
        val lockedPrefix = calculateDragReorder(
            sourceIndex = 1,
            dragOffset = -500f,
            itemSizes = listOf(100f, 100f, 100f),
            spacing = 10f,
            minimumIndex = 1
        )
        val listEnd = calculateDragReorder(
            sourceIndex = 2,
            dragOffset = 500f,
            itemSizes = listOf(100f, 100f, 100f),
            spacing = 10f,
            minimumIndex = 1
        )

        assertEquals(1, lockedPrefix.destinationIndex)
        assertEquals(-500f, lockedPrefix.remainingOffset)
        assertEquals(2, listEnd.destinationIndex)
        assertEquals(500f, listEnd.remainingOffset)
    }

    @Test
    fun dragReorderKeepsTheLeftmostPlacementThroughOverscrollAndReverseDrag() {
        val movedToStart = calculateDragReorder(
            sourceIndex = 2,
            dragOffset = -251f,
            itemSizes = listOf(100f, 140f, 80f),
            spacing = 10f
        )
        val beyondStart = calculateDragReorder(
            sourceIndex = movedToStart.destinationIndex,
            dragOffset = movedToStart.remainingOffset - 200f,
            itemSizes = listOf(80f, 100f, 140f),
            spacing = 10f
        )
        val draggedBackRight = calculateDragReorder(
            sourceIndex = beyondStart.destinationIndex,
            dragOffset = beyondStart.remainingOffset + 302f,
            itemSizes = listOf(80f, 100f, 140f),
            spacing = 10f
        )

        assertEquals(0, movedToStart.destinationIndex)
        assertEquals(0, beyondStart.destinationIndex)
        assertEquals(-191f, beyondStart.remainingOffset)
        assertEquals(1, draggedBackRight.destinationIndex)
        assertEquals(1f, draggedBackRight.remainingOffset)
    }

    @Test
    fun confirmationSnapshotLocksTheReciprocalFixedPartnerAndAbsenceState() {
        val first = registration(1).copy(
            fixedPartnerKey = 2,
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 1
        )
        val second = registration(2).copy(
            fixedPartnerKey = 1,
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 1
        )
        val queue = MachineQueue(waiting = listOf(first, second))
        val snapshots = queue.registrationConfirmationSnapshots(setOf(first.key))

        assertEquals(listOf(1, 2), snapshots.map { it.registrationKey })
        assertTrue(queue.matchesRegistrationConfirmationSnapshots(snapshots))
        assertFalse(
            queue.copy(
                waiting = listOf(
                    first.copy(fixedPartnerKey = null),
                    second.copy(fixedPartnerKey = null)
                )
            ).matchesRegistrationConfirmationSnapshots(snapshots)
        )
        assertFalse(
            queue.copy(
                waiting = listOf(
                    first,
                    second.copy(temporaryAwaySkippedTurns = 2)
                )
            ).matchesRegistrationConfirmationSnapshots(snapshots)
        )
    }

    @Test
    fun confirmationSnapshotAllowsWaitingReorderButRejectsEnteringPlayingPosition() {
        val first = registration(1, PlayPreference.SOLO)
        val second = registration(2, PlayPreference.SOLO)
        val queue = MachineQueue(waiting = listOf(first, second))
        val snapshots = queue.registrationConfirmationSnapshots(setOf(first.key))

        assertTrue(
            queue.copy(waiting = listOf(second, first))
                .matchesRegistrationConfirmationSnapshots(snapshots)
        )
        assertFalse(
            MachineQueue(playing = listOf(first), waiting = listOf(second))
                .matchesRegistrationConfirmationSnapshots(snapshots)
        )
    }

    @Test
    fun moveIntoPlayingConfirmationLocksBothCurrentPlayerAndJoiningRegistration() {
        val currentPlayer = registration(1)
        val joiningPlayer = registration(2)
        val queue = MachineQueue(
            playing = listOf(currentPlayer),
            waiting = listOf(joiningPlayer)
        )
        val snapshots = queue.registrationConfirmationSnapshots(
            setOf(currentPlayer.key, joiningPlayer.key)
        )

        assertTrue(queue.matchesRegistrationConfirmationSnapshots(snapshots))
        assertFalse(
            MachineQueue(
                playing = listOf(registration(3)),
                waiting = listOf(currentPlayer, joiningPlayer)
            ).matchesRegistrationConfirmationSnapshots(snapshots)
        )
        assertFalse(
            queue.copy(
                waiting = listOf(joiningPlayer.copy(requiresOnSiteCheckIn = true))
            ).matchesRegistrationConfirmationSnapshots(snapshots)
        )
    }

    @Test
    fun absenceConfirmationRejectsAChangedFixedPartnerOrCheckInState() {
        val first = registration(1).copy(fixedPartnerKey = 2)
        val second = registration(2).copy(fixedPartnerKey = 1)
        val queue = MachineQueue(waiting = listOf(first, second))
        val snapshots = queue.registrationConfirmationSnapshots(setOf(first.key))

        assertFalse(
            queue.copy(
                waiting = listOf(
                    first.copy(fixedPartnerKey = 3),
                    second.copy(fixedPartnerKey = null),
                    registration(3).copy(fixedPartnerKey = 1)
                )
            ).matchesRegistrationConfirmationSnapshots(snapshots)
        )
        assertFalse(
            queue.copy(
                waiting = listOf(first.copy(requiresOnSiteCheckIn = true), second)
            ).matchesRegistrationConfirmationSnapshots(snapshots)
        )
    }

    @Test
    fun deferringOneFixedPartnerDefersAndCancelsTheWholePair() {
        val first = registration(1).copy(fixedPartnerKey = 2)
        val second = registration(2).copy(fixedPartnerKey = 1)
        val queue = MachineQueue(
            waiting = listOf(first, second, registration(3, PlayPreference.SOLO))
        )

        val deferred = queue.deferOneRound(1)
        assertTrue(
            deferred.waiting.take(2).all {
                it.absenceStatus == QueueAbsenceStatus.DEFER_ONE_ROUND
            }
        )
        val restored = deferred.cancelDeferOneRound(2)
        assertTrue(restored.waiting.all { it.absenceStatus == QueueAbsenceStatus.NONE })
    }

    @Test
    fun temporarilyLeavingOneFixedPartnerAppliesToAndCancelsForTheWholePair() {
        val first = registration(1).copy(fixedPartnerKey = 2)
        val second = registration(2).copy(fixedPartnerKey = 1)
        val queue = MachineQueue(
            waiting = listOf(first, second, registration(3, PlayPreference.SOLO))
        )

        val away = queue.temporarilyLeave(1)
        assertTrue(
            away.waiting.take(2).all {
                it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY
            }
        )
        val restored = away.cancelTemporaryLeave(2)
        assertTrue(restored.waiting.all { it.absenceStatus == QueueAbsenceStatus.NONE })
        assertTrue(restored.waiting.all { it.temporaryAwaySkippedTurns == 0 })
    }

    @Test
    fun editingAPlayerProfileUpdatesItsActiveRegistrationsWithoutChangingPreference() {
        val registration = registration(1, PlayPreference.SOLO).copy(
            displayId = "旧昵称",
            playerProfileId = "profile-1",
            gender = PlayerGender.MALE,
            isTemporary = false
        )
        val queue = MachineQueue(waiting = listOf(registration))

        val updated = queue.syncPlayerProfileDetails(
            playerProfileId = "profile-1",
            playerNickname = "新昵称",
            gender = PlayerGender.FEMALE
        )

        assertEquals("新昵称", updated.waiting.single().displayId)
        assertEquals(PlayerGender.FEMALE, updated.waiting.single().gender)
        assertEquals(PlayPreference.SOLO, updated.waiting.single().preference)
        assertEquals("profile-1", updated.waiting.single().playerProfileId)
    }

    @Test
    fun editingAPlayerProfileDoesNotCreateDuplicateNickname() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1).copy(displayId = "旧昵称", playerProfileId = "profile-1"),
                registration(2).copy(displayId = "现有昵称")
            )
        )

        assertEquals(
            queue,
            queue.syncPlayerProfileDetails("profile-1", "现有昵称", PlayerGender.UNDISCLOSED)
        )
    }

    @Test
    fun movingForwardIdentifiesEveryRegistrationWhoseTurnIsDelayed() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO),
                registration(4, PlayPreference.SOLO)
            )
        )
        val original = queue.allRegistrations
        val proposed = listOf(original[0], original[3], original[1], original[2])

        assertEquals(listOf(2, 3), delayedRegistrationsForMove(queue, proposed, 4).map { it.key })
        assertTrue(hasRegistrationOrderChanged(original, proposed))
        assertTrue(!hasRegistrationOrderChanged(original, original.toList()))
    }

    @Test
    fun delayedPlayersAreCalculatedByPlayingTurnInsteadOfListOffset() {
        val queue = MachineQueue(
            playing = listOf(
                registration(1, PlayPreference.OPEN_TO_JOIN),
                registration(2, PlayPreference.OPEN_TO_JOIN)
            ),
            waiting = listOf(
                registration(3, PlayPreference.OPEN_TO_JOIN),
                registration(4, PlayPreference.OPEN_TO_JOIN),
                registration(5, PlayPreference.SOLO)
            )
        )
        val original = queue.allRegistrations
        val proposed = listOf(original[0], original[1], original[4], original[2], original[3])

        assertEquals(listOf(3, 4), delayedRegistrationsForMove(queue, proposed, 5).map { it.key })
    }

    @Test
    fun waitEstimateSubtractsElapsedTimeFromCurrentSoloRound() {
        val nowMillis = 1_000_000L
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO)),
            playingStartedAtMillis = nowMillis - 5 * 60_000L
        )

        assertEquals(7L, estimatedMinutesUntilPlaying(queue, setOf(2), nowMillis))
    }

    @Test
    fun waitEstimateUsesFifteenMinutesForSharedRounds() {
        val nowMillis = 2_000_000L
        val queue = MachineQueue(
            playing = listOf(registration(1), registration(2)),
            waiting = listOf(
                registration(3, PlayPreference.SOLO),
                registration(4, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = nowMillis - 4 * 60_000L
        )

        assertEquals(23L, estimatedMinutesUntilPlaying(queue, setOf(4), nowMillis))
    }

    @Test
    fun waitEstimateFollowsOneTimeDeferralWhenAdvancingQueue() {
        val nowMillis = 3_000_000L
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(3, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = nowMillis
        )

        assertEquals(12L, estimatedMinutesUntilPlaying(queue, setOf(3), nowMillis))
    }

    @Test
    fun waitEstimateAddsTheSkippedRoundForDeferredRegistrationItself() {
        val nowMillis = 3_100_000L
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(2, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = nowMillis
        )

        assertEquals(24L, estimatedMinutesUntilPlaying(queue, setOf(1), nowMillis))
    }

    @Test
    fun waitEstimateUsesNextRoundAfterCrossedOpenDeferralIsConsumed() {
        val nowMillis = 3_150_000L
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1),
                registration(2).copy(absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND),
                registration(3),
                registration(4)
            ),
            playingStartedAtMillis = nowMillis
        )

        assertEquals(27L, estimatedMinutesUntilPlaying(queue, setOf(2), nowMillis))
        assertEquals(27L, estimatedMinutesUntilPlaying(queue, setOf(4), nowMillis))
    }

    @Test
    fun waitEstimateSkipsTemporarilyAwayRegistrationAndDeductsElapsedRoundTime() {
        val nowMillis = 3_200_000L
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
                ),
                registration(2, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = nowMillis - 5 * 60_000L
        )

        assertEquals(7L, estimatedMinutesUntilPlaying(queue, setOf(2), nowMillis))
        assertEquals(null, estimatedMinutesUntilPlaying(queue, setOf(1), nowMillis))
    }

    @Test
    fun waitEstimateUsesRegroupedOpenPlayersAndDoesNotChargeForTemporaryLeave() {
        val nowMillis = 3_250_000L
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1),
                registration(2).copy(absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY),
                registration(3),
                registration(4)
            ),
            playingStartedAtMillis = nowMillis
        )

        assertEquals(12L, estimatedMinutesUntilPlaying(queue, setOf(1), nowMillis))
        assertEquals(12L, estimatedMinutesUntilPlaying(queue, setOf(3), nowMillis))
        assertEquals(27L, estimatedMinutesUntilPlaying(queue, setOf(4), nowMillis))
        assertEquals(null, estimatedMinutesUntilPlaying(queue, setOf(2), nowMillis))
    }

    @Test
    fun waitEstimateProcessesSeveralTemporarilyAwayPositionsWithoutDelayingFollowingPlayer() {
        val nowMillis = 3_300_000L
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                    temporaryAwaySkippedTurns = 2
                ),
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                    temporaryAwaySkippedTurns = 1
                ),
                registration(3, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = nowMillis - 5 * 60_000L
        )

        assertEquals(7L, estimatedMinutesUntilPlaying(queue, setOf(3), nowMillis))
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, queue.waiting[0].absenceStatus)
        assertEquals(2, queue.waiting[0].temporaryAwaySkippedTurns)
        assertEquals(1, queue.waiting[1].temporaryAwaySkippedTurns)
    }

    @Test
    fun waitEstimateDoesNotMutateDeferredOrTemporarilyAwayState() {
        val nowMillis = 3_400_000L
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND
                ),
                registration(2, PlayPreference.SOLO).copy(
                    absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                    temporaryAwaySkippedTurns = 2
                ),
                registration(3, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = nowMillis
        )

        estimatedMinutesUntilPlaying(queue, setOf(3), nowMillis)

        assertEquals(QueueAbsenceStatus.DEFER_ONE_ROUND, queue.waiting[0].absenceStatus)
        assertEquals(QueueAbsenceStatus.TEMPORARILY_AWAY, queue.waiting[1].absenceStatus)
        assertEquals(2, queue.waiting[1].temporaryAwaySkippedTurns)
    }

    @Test
    fun newOpenRegistrationEstimateIncludesAutomaticSharedPosition() {
        val nowMillis = 4_000_000L
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2)),
            playingStartedAtMillis = nowMillis
        )

        assertEquals(12L, estimatedWaitForNewOpenRegistration(queue, nowMillis))
    }

    @Test
    fun overtimeCurrentRoundDoesNotAddNegativeWaitingTime() {
        val nowMillis = 5_000_000L
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO)),
            playingStartedAtMillis = nowMillis - 14 * 60_000L
        )

        assertEquals(0L, estimatedMinutesUntilPlaying(queue, setOf(2), nowMillis))
    }

    @Test
    fun pendingOnlineRegistrationIsProjectedNormallyButRemovedIfStillUnsignedAtItsTurn() {
        val nowMillis = 6_000_000L
        val pending = registration(2).copy(requiresOnSiteCheckIn = true)
        val queue = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                registration(1),
                pending,
                registration(3),
                registration(4, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = nowMillis - 5 * 60_000L
        )

        assertEquals(listOf(1, 3), queue.waitingPositions().first().map { it.key })
        assertEquals(22L, estimatedMinutesUntilPlaying(queue, setOf(3), nowMillis))
        assertEquals(7L, estimatedMinutesUntilPlaying(queue, setOf(2), nowMillis))

        val advanced = queue.finishRound(nowMillis)

        assertEquals(listOf(1, 3), advanced.playing.map { it.key })
        assertEquals(listOf(4, 9), advanced.waiting.map { it.key })
        assertFalse(advanced.allRegistrations.any { it.key == pending.key })
    }

    @Test
    fun removedPendingOnlineRegistrationDoesNotReappearAcrossLaterRounds() {
        val pending = registration(1, PlayPreference.SOLO).copy(requiresOnSiteCheckIn = true)
        val initial = MachineQueue(
            playing = listOf(registration(9, PlayPreference.SOLO)),
            waiting = listOf(
                pending,
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            )
        )

        val afterFirstRound = initial.finishRound(1_000L)
        val afterSecondRound = afterFirstRound.finishRound(2_000L)

        assertEquals(listOf(2), afterFirstRound.playing.map { it.key })
        assertEquals(listOf(3, 9), afterFirstRound.waiting.map { it.key })
        assertEquals(listOf(3), afterSecondRound.playing.map { it.key })
        assertEquals(listOf(9, 2), afterSecondRound.waiting.map { it.key })
        assertFalse(afterSecondRound.allRegistrations.any { it.key == pending.key })
    }

    @Test
    fun pendingOnlineRegistrationExpiresAfterThirtyMinutesWithoutStartingNextRound() {
        val createdAtMillis = 8_000_000L
        val pending = registration(1).copy(
            createdAtMillis = createdAtMillis,
            requiresOnSiteCheckIn = true
        )
        val available = registration(2, PlayPreference.SOLO)
        val queue = MachineQueue(waiting = listOf(pending, available))

        assertEquals(
            queue,
            queue.removeExpiredOnlineRegistrations(
                createdAtMillis + ONLINE_REGISTRATION_CHECK_IN_TIMEOUT_MILLIS - 1L
            )
        )

        val expired = queue.removeExpiredOnlineRegistrations(
            createdAtMillis + ONLINE_REGISTRATION_CHECK_IN_TIMEOUT_MILLIS
        )

        assertTrue(expired.playing.isEmpty())
        assertEquals(listOf(2), expired.waiting.map { it.key })
    }

    @Test
    fun completedCheckInCancelsTheThirtyMinuteExpiry() {
        val createdAtMillis = 9_000_000L
        val queue = MachineQueue(
            waiting = listOf(
                registration(1).copy(
                    createdAtMillis = createdAtMillis,
                    requiresOnSiteCheckIn = true
                )
            )
        ).checkIn(1)

        assertEquals(
            queue,
            queue.removeExpiredOnlineRegistrations(
                createdAtMillis + ONLINE_REGISTRATION_CHECK_IN_TIMEOUT_MILLIS
            )
        )
    }

    @Test
    fun checkInOnlyUnlocksRegistrationAndDoesNotEnterPlayingPositionAutomatically() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1, PlayPreference.SOLO).copy(requiresOnSiteCheckIn = true)
            )
        )

        val checkedIn = queue.checkIn(1)

        assertTrue(checkedIn.playing.isEmpty())
        assertEquals(listOf(1), checkedIn.waiting.map { it.key })
        assertFalse(checkedIn.waiting.single().requiresOnSiteCheckIn)
        assertTrue(checkedIn.waiting.single().canEnterPlayingPosition)
    }

    @Test
    fun pendingOnlineRegistrationRejectsActionsReservedForOnSitePlayers() {
        val pending = registration(1).copy(
            requiresOnSiteCheckIn = true,
            playerProfileId = "profile-1",
            isTemporary = false
        )
        val queue = MachineQueue(waiting = listOf(pending, registration(2, PlayPreference.SOLO)))

        assertFalse(queue.canMarkNoShow(1))
        assertEquals(queue, queue.deferOneRound(1))
        assertEquals(queue, queue.temporarilyLeave(1))
        assertEquals(queue, queue.changePreference(1, PlayPreference.SOLO))
        assertEquals(queue, queue.rename(1, "新昵称"))
        assertEquals(null, queue.planFriendPair(1, 2))
    }

    @Test
    fun nextRoundPreviewExplainsPendingOnlineRegistrationReplacement() {
        val queue = MachineQueue(
            waiting = listOf(
                registration(1),
                registration(2).copy(requiresOnSiteCheckIn = true),
                registration(3)
            )
        )

        val preview = queue.nextPlayingPositionPreview()!!

        assertEquals(listOf(1, 2), preview.nominalRegistrations.map { it.key })
        assertEquals(listOf(1, 3), preview.nextRegistrations.map { it.key })
        assertEquals(listOf(2), preview.unavailableRegistrations.map { it.key })
        assertTrue(preview.changedByAvailability)
    }
}
