package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCloudCommandTest {
    @Test
    fun terminalCommandResponseParsesProfileAndQueueOperations() {
        val parsed = parseRemoteTerminalCommands(
            """
            {
              "commands": [
                {
                  "command_id": "00000000-0000-0000-0000-000000000777",
                  "type": "UPDATE_PLAYER_PROFILE",
                  "payload": {
                    "profile_id": "00000000-0000-0000-0000-000000000901",
                    "qq_number": "12345678",
                    "expected_updated_at": 1000,
                    "nickname": "新昵称",
                    "gender": "FEMALE",
                    "default_preference": "SOLO"
                  }
                },
                {
                  "command_id": "00000000-0000-0000-0000-000000000401",
                  "type": "QUEUE_OPERATION",
                  "created_at": 2000,
                  "payload": {
                    "queue_id": "00000000-0000-0000-0000-000000000001",
                    "profile_id": "00000000-0000-0000-0000-000000000901",
                    "actor_qq": "12345678",
                    "operation": "JOIN_QUEUE",
                    "operation_source": "WEBSITE_REMOTE",
                    "machine_id": "A",
                    "preference": "OPEN_TO_JOIN"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, parsed.size)
        assertTrue(parsed[0] is PlayerProfileUpdateCommand)
        val join = parsed[1] as RemoteQueueOperationCommand
        assertEquals(RemoteQueueOperation.JOIN_QUEUE, join.operation)
        assertEquals(RemoteQueueOperationSource.WEBSITE_REMOTE, join.source)
        assertEquals("A", join.machineId)
        assertEquals(PlayPreference.OPEN_TO_JOIN, join.preference)
    }

    @Test
    fun terminalCommandResponseSkipsMalformedOrUnknownCommands() {
        val parsed = parseRemoteTerminalCommands(
            """
            {
              "commands": [
                {"command_id": "not-a-uuid", "type": "QUEUE_OPERATION", "payload": {}},
                {"command_id": "00000000-0000-0000-0000-000000000402", "type": "UNKNOWN", "payload": {}}
              ]
            }
            """.trimIndent()
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun terminalCommandResponseParsesMobileDeviceRegistration() {
        val parsed = parseRemoteTerminalCommands(
            """
            {
              "commands": [{
                "command_id": "00000000-0000-0000-0000-000000000821",
                "type": "MOBILE_DEVICE_REGISTRATION",
                "created_at": 2000,
                "payload": {
                  "queue_id": "00000000-0000-0000-0000-000000000001",
                  "machine_id": "A",
                  "actor_qq": "12345678",
                  "preference": "OPEN_TO_JOIN",
                  "operation_source": "MOBILE_DEVICE",
                  "session_id": "00000000-0000-0000-0000-000000000820",
                  "profile": {
                    "mode": "EXISTING",
                    "profile_id": "00000000-0000-0000-0000-000000000901",
                    "expected_profile_revision": 3,
                    "completion": null
                  }
                }
              }]
            }
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        val command = parsed.single() as MobileDeviceRegistrationCommand
        assertEquals("00000000-0000-0000-0000-000000000820", command.sessionId)
        assertEquals("A", command.machineId)
        assertEquals(3L, command.expectedProfileRevision)
        assertEquals(PlayPreference.OPEN_TO_JOIN, command.preference)
        assertTrue(!command.createsProfile)
    }

    @Test
    fun matchingCommandProducesUpdatedProfile() {
        val original = profile()
        val decision = decidePlayerProfileUpdate(
            command = command(),
            profiles = listOf(original),
            nicknameConflictsWithQueue = { _, _ -> false },
            nowMillis = 2_000L
        )

        assertTrue(decision is PlayerProfileCommandDecision.Apply)
        val updated = (decision as PlayerProfileCommandDecision.Apply).profile
        assertEquals("新昵称", updated.nickname)
        assertEquals(PlayerGender.FEMALE, updated.gender)
        assertEquals(ProfilePlayPreference.SOLO, updated.defaultPreference)
        assertEquals("12345678", updated.qqNumber)
        assertEquals(2_000L, updated.updatedAtMillis)
    }

    @Test
    fun newerDifferentLocalProfileRejectsStaleServerCommand() {
        val decision = decidePlayerProfileUpdate(
            command = command(),
            profiles = listOf(profile().copy(nickname = "现场新昵称", updatedAtMillis = 1_500L)),
            nicknameConflictsWithQueue = { _, _ -> false }
        )

        assertTrue(decision is PlayerProfileCommandDecision.Reject)
        assertTrue((decision as PlayerProfileCommandDecision.Reject).detail.contains("终端发生更新"))
    }

    @Test
    fun matchingDesiredFieldsAreRecognizedAfterLostAcknowledgement() {
        val desired = profile().copy(
            nickname = "新昵称",
            gender = PlayerGender.FEMALE,
            defaultPreference = ProfilePlayPreference.SOLO,
            updatedAtMillis = 1_500L
        )

        val decision = decidePlayerProfileUpdate(
            command = command(),
            profiles = listOf(desired),
            nicknameConflictsWithQueue = { _, _ -> false }
        )

        assertEquals(PlayerProfileCommandDecision.AlreadyApplied, decision)
    }

    @Test
    fun duplicateProfileOrQueueNicknameIsRejected() {
        val duplicateProfile = decidePlayerProfileUpdate(
            command = command(),
            profiles = listOf(profile(), profile(id = "00000000-0000-0000-0000-000000000902").copy(
                nickname = "新昵称",
                qqNumber = "87654321"
            )),
            nicknameConflictsWithQueue = { _, _ -> false }
        )
        val queueConflict = decidePlayerProfileUpdate(
            command = command(),
            profiles = listOf(profile()),
            nicknameConflictsWithQueue = { _, _ -> true }
        )

        assertTrue(duplicateProfile is PlayerProfileCommandDecision.Reject)
        assertTrue(queueConflict is PlayerProfileCommandDecision.Reject)
    }

    @Test
    fun changedQqIdentityRejectsCommand() {
        val decision = decidePlayerProfileUpdate(
            command = command(),
            profiles = listOf(profile().copy(qqNumber = "87654321")),
            nicknameConflictsWithQueue = { _, _ -> false }
        )

        assertTrue(decision is PlayerProfileCommandDecision.Reject)
    }

    private fun profile(
        id: String = "00000000-0000-0000-0000-000000000901"
    ) = PlayerProfile(
        id = id,
        nickname = "原昵称",
        gender = PlayerGender.UNDISCLOSED,
        defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
        qqNumber = "12345678",
        createdAtMillis = 500L,
        updatedAtMillis = 1_000L
    )

    private fun command() = PlayerProfileUpdateCommand(
        commandId = "00000000-0000-0000-0000-000000000777",
        profileId = "00000000-0000-0000-0000-000000000901",
        qqNumber = "12345678",
        expectedUpdatedAtMillis = 1_000L,
        nickname = "新昵称",
        gender = PlayerGender.FEMALE,
        defaultPreference = ProfilePlayPreference.SOLO
    )
}
