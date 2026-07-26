package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileDeviceRegistrationTest {
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

    private fun command() = MobileDeviceRegistrationCommand(
        commandId = "00000000-0000-0000-0000-000000000821",
        createdAtMillis = 2_000L,
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
    ) = RemoteQueueExecutionState(
        queueId = "00000000-0000-0000-0000-000000000001",
        queues = mapOf("A" to machineA, "B" to MachineQueue()),
        machineStatuses = mapOf("A" to MachineStatus(), "B" to MachineStatus()),
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
