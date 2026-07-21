package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueModelsTest {
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
    fun deferSkipsOneOpportunityWithoutRemovingRegistration() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(registration(2, PlayPreference.SOLO))
        ).defer(1)

        assertEquals(listOf(2), queue.playing.map { it.key })
        assertTrue(queue.waiting.any { it.key == 1 })
    }

    @Test
    fun cancelDeferKeepsCurrentOrderAndRestoresNextOpportunity() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO).copy(deferredOnce = true),
                registration(3, PlayPreference.SOLO)
            )
        ).cancelDefer(2)

        assertEquals(listOf(2, 3), queue.waiting.map { it.key })
        assertTrue(queue.waiting.none { it.deferredOnce })
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
    fun overtimeCorrectionReplaysMissedRoundsInPhysicalQueueOrder() {
        val correctedAt = 9_000L
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO).copy(deferredOnce = true)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO).copy(deferredOnce = true),
                registration(3, PlayPreference.SOLO),
                registration(4, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 123L
        ).advanceToWaitingPosition(setOf(3), correctedAt)

        assertEquals(listOf(3), queue.playing.map { it.key })
        assertEquals(listOf(4, 1, 2), queue.waiting.map { it.key })
        assertEquals(correctedAt, queue.playingStartedAtMillis)
        assertEquals(
            listOf(correctedAt, correctedAt),
            queue.waiting.filter { it.key in setOf(1, 2) }.map { it.lastPlayedAtMillis }
        )
        assertTrue(queue.allRegistrations.none { it.deferredOnce })
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
    fun restoringAfterMachineStopKeepsQueueAndRestartsRoundTimer() {
        val queue = MachineQueue(
            playing = listOf(registration(1, PlayPreference.SOLO)),
            waiting = listOf(
                registration(2, PlayPreference.SOLO),
                registration(3, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = 50L
        )

        val restored = queue.restartPlayingTimer(atMillis = 300L)

        assertEquals(queue.playing, restored.playing)
        assertEquals(queue.waiting, restored.waiting)
        assertEquals(300L, restored.playingStartedAtMillis)
    }

    @Test
    fun queueIdUsesFiveCharactersAndEllipsisOnlyWhenLongerThanSixCharacters() {
        assertEquals("一二三四五六", queueDisplayId("一二三四五六"))
        assertEquals("一二三四五…", queueDisplayId("一二三四五六七"))
        assertEquals("甲乙😀丁戊…", queueDisplayId("甲乙😀丁戊己庚"))
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
        val proposed = listOf(original[3], original[0], original[1], original[2])

        assertEquals(listOf(1, 2, 3), delayedRegistrationsForMove(queue, proposed, 4).map { it.key })
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
                registration(4, PlayPreference.OPEN_TO_JOIN)
            )
        )
        val original = queue.allRegistrations
        val proposed = listOf(original[3], original[0], original[1], original[2])

        assertEquals(listOf(2), delayedRegistrationsForMove(queue, proposed, 4).map { it.key })
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
                registration(2, PlayPreference.SOLO).copy(deferredOnce = true),
                registration(3, PlayPreference.SOLO)
            ),
            playingStartedAtMillis = nowMillis
        )

        assertEquals(12L, estimatedMinutesUntilPlaying(queue, setOf(3), nowMillis))
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
}
