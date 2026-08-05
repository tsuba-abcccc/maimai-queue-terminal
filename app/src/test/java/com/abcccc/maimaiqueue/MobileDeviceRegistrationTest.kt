package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileDeviceRegistrationTest {
    @Test
    fun registrationCanProceedImmediatelyAfterTheTerminalReopensTheQueue() {
        val closed = decideMobileDeviceRegistration(
            command(),
            state().copy(acceptingNewRegistrations = false)
        )
        val reopened = decideMobileDeviceRegistration(
            command(),
            state().copy(acceptingNewRegistrations = true)
        )

        assertEquals(
            "现场当前没有使用登记排队。",
            (closed as MobileDeviceRegistrationDecision.Reject).detail
        )
        assertTrue(reopened is MobileDeviceRegistrationDecision.Apply)
    }

    @Test
    fun existingCompleteProfileCreatesANormalOnSiteRegistration() {
        val result = decideMobileDeviceRegistration(command(), state())

        assertTrue(result is MobileDeviceRegistrationDecision.Apply)
        result as MobileDeviceRegistrationDecision.Apply
        val registration = result.state.queues.getValue("A").playing.single()
        assertEquals("资料玩家", registration.displayId)
        assertFalse(registration.requiresOnSiteCheckIn)
        assertEquals(command().commandId, registration.originatingCommandId)
        assertEquals(1, result.profileToPersist?.usageCount)
    }

    @Test
    fun staleMachineConfigurationRevisionRejectsMobileRegistration() {
        val current = state().copy(machineConfigurationRevision = 12L)

        val result = decideMobileDeviceRegistration(
            command().copy(machineConfigurationRevision = 11L),
            current
        )

        assertTrue(result is MobileDeviceRegistrationDecision.Reject)
        assertEquals(
            "机台配置已经更新，请在终端重新打开移动设备登记。",
            (result as MobileDeviceRegistrationDecision.Reject).detail
        )
        assertTrue(current.queues.values.all { it.allRegistrations.isEmpty() })
    }

    @Test
    fun singlePlayerMachineUsesSoloWithoutChangingTheProfileDefault() {
        val current = state().copy(
            machineCapacities = mapOf("A" to 1, "B" to 2),
            machineConfigurationRevision = 3L
        )

        val result = decideMobileDeviceRegistration(
            command().copy(machineConfigurationRevision = 3L),
            current,
            appliedAtMillis = 5_000L
        ) as MobileDeviceRegistrationDecision.Apply

        val registration = result.state.queues.getValue("A").playing.single()
        assertEquals(PlayPreference.SOLO, registration.preference)
        assertEquals(ProfilePlayPreference.OPEN_TO_JOIN, current.playerProfiles.single().defaultPreference)
        assertTrue(result.detail.contains("仅能容纳一人游玩"))
        assertTrue(result.detail.contains("本次已使用“单人游玩”"))
    }

    @Test
    fun configuredMachinesCAndDAreHandledLikeTheOriginalMachines() {
        val queues = linkedMapOf(
            "A" to MachineQueue(),
            "B" to MachineQueue(),
            "C" to MachineQueue(),
            "D" to MachineQueue()
        )
        val joined = decideMobileDeviceRegistration(
            command().copy(machineId = "C"),
            stateWithQueues(queues),
            appliedAtMillis = 5_000L
        ) as MobileDeviceRegistrationDecision.Apply

        assertEquals("C", joined.changedMachineId)
        assertEquals(1, joined.state.queues.getValue("C").registrationCount)
        assertTrue(joined.state.queues.getValue("A").allRegistrations.isEmpty())

        val stopped = stateWithQueues(queues).copy(
            machineStatuses = queues.keys.associateWith { machineId ->
                if (machineId == "D") {
                    MachineStatus().stop(MachineStopReason.MAINTENANCE, 4_000L)
                } else {
                    MachineStatus()
                }
            }
        )
        val rejected = decideMobileDeviceRegistration(
            command().copy(machineId = "D"),
            stopped
        )

        assertTrue(rejected is MobileDeviceRegistrationDecision.Reject)
        assertTrue((rejected as MobileDeviceRegistrationDecision.Reject).detail.contains("停止使用"))
    }

    @Test
    fun replayedCommandDoesNotCreateASecondRegistrationOrUsageRecord() {
        val first = decideMobileDeviceRegistration(command(), state())
            as MobileDeviceRegistrationDecision.Apply
        val persistedProfile = first.profileToPersist!!
        val replayState = first.state.copy(playerProfiles = listOf(persistedProfile))

        val replay = decideMobileDeviceRegistration(command(), replayState)

        assertTrue(replay is MobileDeviceRegistrationDecision.AlreadyApplied)
        assertEquals(1, replayState.queues.getValue("A").registrationCount)
        assertEquals(1, persistedProfile.usageCount)
    }

    @Test
    fun replayRepairsTheProfileWhenTheQueueWasSavedFirst() {
        val first = decideMobileDeviceRegistration(command(), state())
            as MobileDeviceRegistrationDecision.Apply

        val replay = decideMobileDeviceRegistration(command(), first.state)
            as MobileDeviceRegistrationDecision.AlreadyApplied

        assertEquals(1, replay.profileToPersist?.usageCount)
        assertEquals(command().createdAtMillis, replay.profileToPersist?.lastUsedAtMillis)
        assertEquals(1, first.state.queues.getValue("A").registrationCount)
    }

    @Test
    fun persistedReceiptPreventsReplayAfterThePlayerHasLeftTheQueue() {
        val requested = command()
        val replay = decideMobileDeviceRegistration(
            requested,
            state(),
            appliedCommandIds = setOf(requested.commandId)
        )

        assertTrue(replay is MobileDeviceRegistrationDecision.AlreadyApplied)
    }

    @Test
    fun incompleteProfileRequiresCompletionAndPersistsSelectedDefaults() {
        val incomplete = profile().copy(setupVersion = 0)
        val withoutCompletion = decideMobileDeviceRegistration(
            command(),
            state(profile = incomplete)
        )
        val withCompletion = decideMobileDeviceRegistration(
            command().copy(
                completion = MobileProfileCompletion(
                    qqNumber = "12345678",
                    qqVisibility = QqVisibility.PUBLIC_WEBSITE,
                    notificationPreferences = QueueNotificationPreferences(
                        playingPosition = true
                    ),
                    setupVersion = CURRENT_PLAYER_PROFILE_SETUP_VERSION
                )
            ),
            state(profile = incomplete)
        )

        assertTrue(withoutCompletion is MobileDeviceRegistrationDecision.Reject)
        assertTrue(withCompletion is MobileDeviceRegistrationDecision.Apply)
        val updated = (withCompletion as MobileDeviceRegistrationDecision.Apply)
            .profileToPersist!!
        assertTrue(updated.hasCompleteRequiredDetails)
        assertEquals(QqVisibility.PUBLIC_WEBSITE, updated.qqVisibility)
        assertTrue(updated.notificationPreferences.playingPosition)
    }

    @Test
    fun contactlessLegacyAliasDoesNotBlockTheCanonicalProfile() {
        val canonical = profile().copy(setupVersion = 0)
        val legacyAlias = canonical.copy(
            id = "00000000-0000-0000-0000-000000000902",
            qqNumber = null,
            usageCount = 37
        )
        val requested = command().copy(
            completion = MobileProfileCompletion(
                qqNumber = "12345678",
                qqVisibility = QqVisibility.TERMINAL_ONLY,
                notificationPreferences = QueueNotificationPreferences(),
                setupVersion = CURRENT_PLAYER_PROFILE_SETUP_VERSION
            )
        )

        val result = decideMobileDeviceRegistration(
            requested,
            state(profile = canonical).copy(playerProfiles = listOf(canonical, legacyAlias))
        )

        assertTrue(result is MobileDeviceRegistrationDecision.Apply)
        result as MobileDeviceRegistrationDecision.Apply
        assertEquals(canonical.id, result.profileToPersist?.id)
        assertEquals(canonical.id, result.state.queues.getValue("A")
            .allRegistrations.single().playerProfileId)
    }

    @Test
    fun conflictingProfileWithDifferentIdentityStillBlocksRegistration() {
        val canonical = profile()
        val conflictingProfile = canonical.copy(
            id = "00000000-0000-0000-0000-000000000903",
            qqNumber = null,
            gender = PlayerGender.FEMALE,
            setupVersion = 0
        )

        val result = decideMobileDeviceRegistration(
            command(),
            state(profile = canonical).copy(
                playerProfiles = listOf(canonical, conflictingProfile)
            )
        )

        assertTrue(result is MobileDeviceRegistrationDecision.Reject)
        assertTrue((result as MobileDeviceRegistrationDecision.Reject).detail.contains("昵称"))
    }

    @Test
    fun completedProfileCanResumeAfterProfileWasPersistedBeforeQueueMutation() {
        val incomplete = profile().copy(setupVersion = 0)
        val requested = command().copy(
            completion = MobileProfileCompletion(
                qqNumber = "12345678",
                qqVisibility = QqVisibility.PUBLIC_WEBSITE,
                notificationPreferences = QueueNotificationPreferences(
                    playingPosition = true
                ),
                setupVersion = CURRENT_PLAYER_PROFILE_SETUP_VERSION
            )
        )
        val first = decideMobileDeviceRegistration(requested, state(profile = incomplete))
            as MobileDeviceRegistrationDecision.Apply
        val persistedProfile = first.profileToPersist!!

        val resumed = decideMobileDeviceRegistration(
            requested,
            state(profile = persistedProfile)
        ) as MobileDeviceRegistrationDecision.Apply

        assertEquals(1, persistedProfile.usageCount)
        assertEquals(null, resumed.profileToPersist)
        assertEquals(1, resumed.state.queues.getValue("A").registrationCount)
        assertEquals(requested.commandId, resumed.state.queues.getValue("A")
            .allRegistrations.single().originatingCommandId)
    }

    @Test
    fun newProfileCanResumeAfterProfileWasPersistedBeforeQueueMutation() {
        val requested = command().copy(
            expectedProfileRevision = null,
            newProfile = MobileNewPlayerProfile(
                nickname = "移动新玩家",
                gender = PlayerGender.FEMALE,
                defaultPreference = ProfilePlayPreference.OPEN_TO_JOIN,
                qqNumber = "87654321",
                qqVisibility = QqVisibility.TERMINAL_ONLY,
                notificationPreferences = QueueNotificationPreferences(),
                setupVersion = CURRENT_PLAYER_PROFILE_SETUP_VERSION
            ),
            actorQq = "87654321"
        )
        val first = decideMobileDeviceRegistration(requested, state(profile = null))
            as MobileDeviceRegistrationDecision.Apply
        val persistedProfile = first.profileToPersist!!

        val resumed = decideMobileDeviceRegistration(
            requested,
            state(profile = persistedProfile)
        ) as MobileDeviceRegistrationDecision.Apply

        assertEquals("移动新玩家", persistedProfile.nickname)
        assertEquals(1, persistedProfile.usageCount)
        assertEquals(null, resumed.profileToPersist)
        assertEquals(1, resumed.state.queues.getValue("A").registrationCount)
    }

    @Test
    fun queueIsRevalidatedAfterProfilePersistence() {
        val first = decideMobileDeviceRegistration(command(), state())
            as MobileDeviceRegistrationDecision.Apply
        val persistedProfile = first.profileToPersist!!
        val stoppedState = state(profile = persistedProfile).copy(
            machineStatuses = mapOf(
                "A" to MachineStatus(
                    stopReason = MachineStopReason.MAINTENANCE,
                    stoppedAtMillis = 3_000L
                ),
                "B" to MachineStatus()
            )
        )

        val resumed = decideMobileDeviceRegistration(command(), stoppedState)

        assertTrue(resumed is MobileDeviceRegistrationDecision.Reject)
        assertTrue((resumed as MobileDeviceRegistrationDecision.Reject).detail.contains("停止使用"))
        assertEquals(0, stoppedState.queues.getValue("A").registrationCount)
    }

    @Test
    fun staleProfileRevisionIsRejectedBeforeQueueMutation() {
        val stale = command().copy(expectedProfileRevision = 1L)
        val result = decideMobileDeviceRegistration(stale, state())

        assertTrue(result is MobileDeviceRegistrationDecision.Reject)
        assertTrue((result as MobileDeviceRegistrationDecision.Reject).detail.contains("更新"))
    }

    @Test
    fun unavailablePlayersPreventSilentAutomaticAdvancement() {
        val away = Registration(
            key = 1,
            displayId = "暂离玩家",
            preference = PlayPreference.OPEN_TO_JOIN,
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY
        )
        val current = state(
            machineA = MachineQueue(waiting = listOf(away)),
            nextKey = 2
        )

        val result = decideMobileDeviceRegistration(command(), current)
            as MobileDeviceRegistrationDecision.Apply

        assertTrue(result.needsAvailabilityConfirmation)
        assertTrue(result.state.queues.getValue("A").playing.isEmpty())
        assertEquals(2, result.state.queues.getValue("A").registrationCount)
    }

    @Test
    fun delayedCommandUsesTerminalProcessingTimeForThePlayingTimer() {
        val requested = command()
        val processedAtMillis = 95_000L

        val result = decideMobileDeviceRegistration(
            requested,
            state(),
            appliedAtMillis = processedAtMillis
        ) as MobileDeviceRegistrationDecision.Apply
        val queue = result.state.queues.getValue("A")

        assertEquals(processedAtMillis, queue.playingStartedAtMillis)
        assertEquals(requested.createdAtMillis, queue.playing.single().createdAtMillis)
    }

    @Test
    fun commandMatchesOnlyItsOriginatingMobileSession() {
        val command = command()
        val originatingSession = MobileRegistrationSession(
            sessionId = "00000000-0000-0000-0000-000000000820",
            registrationUrl = "https://example.test/mobile",
            expiresAtMillis = 60_000L
        )
        val newerSession = originatingSession.copy(
            sessionId = "00000000-0000-0000-0000-000000000829"
        )

        assertTrue(command.matchesSession(originatingSession))
        assertTrue(!command.matchesSession(newerSession))
        assertTrue(!command.copy(sessionId = null).matchesSession(originatingSession))
    }

    @Test
    fun mobileRegistrationProducesExactlyTheDirectEngineQueue() {
        val initial = state()
        val requested = command()
        val appliedAtMillis = 75_000L
        val mobile = decideMobileDeviceRegistration(
            requested,
            initial,
            appliedAtMillis = appliedAtMillis
        )
            as MobileDeviceRegistrationDecision.Apply
        val selectedProfile = initial.playerProfiles.single()
        val expectedRegistration = Registration(
            key = initial.nextRegistrationKey,
            displayId = selectedProfile.nickname,
            preference = requested.preference,
            isTemporary = false,
            createdAtMillis = requested.createdAtMillis,
            gender = selectedProfile.gender,
            playerProfileId = selectedProfile.id,
            requiresOnSiteCheckIn = false,
            originatingCommandId = requested.commandId
        )
        val direct = initial.executeQueueAction(
            action = QueueAction.AddRegistrations(
                machineId = requested.machineId,
                registrations = listOf(expectedRegistration),
                placement = RegistrationPlacement.ADVANCE_IF_UNAMBIGUOUS
            ),
            origin = QueueActionOrigin.MOBILE_DEVICE,
            atMillis = appliedAtMillis
        ) as QueueActionExecution.Applied

        assertEquals(direct.state.queues, mobile.state.queues)
        assertEquals(
            direct.impact.requiresAvailabilityConfirmation,
            mobile.needsAvailabilityConfirmation
        )
    }

    private fun command() = MobileDeviceRegistrationCommand(
        commandId = "00000000-0000-0000-0000-000000000821",
        createdAtMillis = 2_000L,
        sessionId = "00000000-0000-0000-0000-000000000820",
        queueId = "00000000-0000-0000-0000-000000000001",
        machineId = "A",
        actorQq = "12345678",
        preference = PlayPreference.OPEN_TO_JOIN,
        profileId = "00000000-0000-0000-0000-000000000901",
        expectedProfileRevision = 3L,
        completion = null,
        newProfile = null
    )

    private fun profile() = PlayerProfile(
        id = "00000000-0000-0000-0000-000000000901",
        nickname = "资料玩家",
        gender = PlayerGender.UNDISCLOSED,
        defaultPreference = ProfilePlayPreference.OPEN_TO_JOIN,
        qqNumber = "12345678",
        setupVersion = CURRENT_PLAYER_PROFILE_SETUP_VERSION,
        revision = 3L,
        createdAtMillis = 500L,
        updatedAtMillis = 1_000L
    )

    private fun state(
        profile: PlayerProfile? = profile(),
        machineA: MachineQueue = MachineQueue(),
        nextKey: Int = 1
    ) = stateWithQueues(
        queues = mapOf("A" to machineA, "B" to MachineQueue()),
        profile = profile,
        nextKey = nextKey
    )

    private fun stateWithQueues(
        queues: Map<String, MachineQueue>,
        profile: PlayerProfile? = profile(),
        nextKey: Int = 1
    ) = RemoteQueueExecutionState(
        queueId = "00000000-0000-0000-0000-000000000001",
        queues = queues,
        machineStatuses = queues.keys.associateWith { MachineStatus() },
        playerProfiles = listOfNotNull(profile),
        nextRegistrationKey = nextKey,
        acceptingNewRegistrations = true,
        websiteRemoteEnabled = true,
        oneBotSyncEnabled = true,
        allowOnlineRegistration = true,
        allowDeferOneRound = true,
        allowTemporaryLeave = true
    )
}
