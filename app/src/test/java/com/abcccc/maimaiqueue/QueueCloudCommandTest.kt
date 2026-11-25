package com.abcccc.maimaiqueue

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCloudCommandTest {
    @Test
    fun mobileSessionRetriesLegacyFormatOnlyForTheOldServerFieldRejection() {
        assertTrue(
            shouldRetryMobileSessionWithoutStableMachineId(
                400,
                "移动设备登记会话参数不完整"
            )
        )
        assertTrue(
            !shouldRetryMobileSessionWithoutStableMachineId(
                409,
                "所选机台已经变化，请重新打开登记页面"
            )
        )
        assertTrue(
            !shouldRetryMobileSessionWithoutStableMachineId(
                400,
                "终端编号无效"
            )
        )
    }

    @Test
    fun playerProfileSyncParsesOnlyAliasesThatResolveToReturnedProfiles() {
        val canonicalId = "00000000-0000-0000-0000-000000000902"
        val validSourceId = "00000000-0000-0000-0000-000000000901"
        val cycleFirstId = "00000000-0000-0000-0000-000000000903"
        val cycleSecondId = "00000000-0000-0000-0000-000000000904"
        val parsed = parsePlayerProfileSyncPayload(
            """
            {
              "profiles": [{
                "profile_id": "$canonicalId",
                "nickname": "当前资料",
                "gender": "UNDISCLOSED",
                "default_preference": "OPEN_TO_JOIN",
                "qq_number": null,
                "avatar_url": "https://queue.example.test/api/player-avatars/${"a".repeat(64)}.webp",
                "usage_count": 3,
                "last_used_at": 300,
                "recent_usage_at": [100, 200, 300],
                "qq_visibility": "TERMINAL_ONLY",
                "notification_enabled": true,
                "notify_queue_changes": true,
                "notify_playing_position": false,
                "notify_online_check_in": true,
                "notify_absence": true,
                "notify_machine_status": false,
                "setup_version": 0,
                "profile_revision": 1,
                "created_at": 100,
                "updated_at": 100
              }],
              "profile_aliases": {
                "$validSourceId": "$canonicalId",
                "$cycleFirstId": "$cycleSecondId",
                "$cycleSecondId": "$cycleFirstId",
                "not-a-uuid": "$canonicalId"
              },
              "bot_qq": "12345678"
            }
            """.trimIndent()
        )

        assertEquals(mapOf(validSourceId to canonicalId), parsed.profileAliases)
        assertEquals(canonicalId, parsed.profiles.single().id)
        assertEquals(listOf(100L, 200L, 300L), parsed.profiles.single().recentUsageAtMillis)
        assertEquals(
            "https://queue.example.test/api/player-avatars/${"a".repeat(64)}.webp",
            parsed.profiles.single().avatarReference
        )
        assertEquals("12345678", parsed.botQqNumber)
    }

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
                    "machine_stable_id": "10000000000000000000000000000001",
                    "preference": "OPEN_TO_JOIN"
                  }
                },
                {
                  "command_id": "00000000-0000-0000-0000-000000000402",
                  "type": "QUEUE_OPERATION",
                  "created_at": 2001,
                  "payload": {
                    "queue_id": "00000000-0000-0000-0000-000000000001",
                    "profile_id": "00000000-0000-0000-0000-000000000901",
                    "actor_qq": "87654321",
                    "profile_identity_verified": true,
                    "operation": "LEAVE_QUEUE",
                    "operation_source": "WEBSITE_REMOTE",
                    "machine_id": "A",
                    "registration_id": "000000000000000000000001"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(3, parsed.size)
        assertTrue(parsed[0] is PlayerProfileUpdateCommand)
        val join = parsed[1] as RemoteQueueOperationCommand
        assertEquals(RemoteQueueOperation.JOIN_QUEUE, join.operation)
        assertEquals(RemoteQueueOperationSource.WEBSITE_REMOTE, join.source)
        assertEquals("A", join.machineId)
        assertEquals("10000000000000000000000000000001", join.machineStableId)
        assertEquals(PlayPreference.OPEN_TO_JOIN, join.preference)
        val accountOperation = parsed[2] as RemoteQueueOperationCommand
        assertEquals(RemoteQueueOperation.LEAVE_QUEUE, accountOperation.operation)
        assertEquals(RemoteQueueOperationSource.WEBSITE_REMOTE, accountOperation.source)
        assertTrue(accountOperation.profileIdentityVerified)
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
    fun terminalCommandResponseRejectsUnverifiedOrMislabelledWebsiteIdentity() {
        val parsed = parseRemoteTerminalCommands(
            """
            {
              "commands": [
                {
                  "command_id": "00000000-0000-0000-0000-000000000410",
                  "type": "QUEUE_OPERATION",
                  "created_at": 2000,
                  "payload": {
                    "queue_id": "00000000-0000-0000-0000-000000000001",
                    "profile_id": "00000000-0000-0000-0000-000000000901",
                    "actor_qq": "12345678",
                    "operation": "LEAVE_QUEUE",
                    "operation_source": "WEBSITE_REMOTE",
                    "machine_id": "A",
                    "registration_id": "000000000000000000000001"
                  }
                },
                {
                  "command_id": "00000000-0000-0000-0000-000000000411",
                  "type": "QUEUE_OPERATION",
                  "created_at": 2000,
                  "payload": {
                    "queue_id": "00000000-0000-0000-0000-000000000001",
                    "profile_id": "00000000-0000-0000-0000-000000000901",
                    "actor_qq": "12345678",
                    "profile_identity_verified": true,
                    "operation": "LEAVE_QUEUE",
                    "operation_source": "QQ_BOT",
                    "machine_id": "A",
                    "registration_id": "000000000000000000000001"
                  }
                },
                {
                  "command_id": "00000000-0000-0000-0000-000000000412",
                  "type": "QUEUE_OPERATION",
                  "created_at": 2000,
                  "payload": {
                    "queue_id": "00000000-0000-0000-0000-000000000001",
                    "profile_id": "00000000-0000-0000-0000-000000000901",
                    "actor_qq": "12345678",
                    "profile_identity_verified": true,
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
                  "machine_stable_id": "10000000000000000000000000000001",
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
        assertEquals("10000000000000000000000000000001", command.machineStableId)
        assertEquals(3L, command.expectedProfileRevision)
        assertEquals(PlayPreference.OPEN_TO_JOIN, command.preference)
        assertTrue(!command.createsProfile)
    }

    @Test
    fun terminalCommandResponseSkipsMalformedStableMachineIdentity() {
        val parsed = parseRemoteTerminalCommands(
            """
            {
              "commands": [{
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
                  "machine_stable_id": "not-a-stable-id",
                  "preference": "OPEN_TO_JOIN"
                }
              }]
            }
            """.trimIndent()
        )

        assertTrue(parsed.isEmpty())
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
        assertTrue((decision as PlayerProfileCommandDecision.Reject).detail.contains("玩家资料已经更新"))
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

    @Test
    fun managementProfileUpdateCanChangeSensitivePolicyWithoutPlayerQqIdentity() {
        val original = profile().copy(qqNumber = null)
        val decision = decidePlayerProfileUpdate(
            command = command().copy(
                qqNumber = "",
                source = RemoteQueueOperationSource.MANAGEMENT_APP,
                terminalEditingAllowed = false,
                visitedVenuesPublic = false
            ),
            profiles = listOf(original),
            nicknameConflictsWithQueue = { _, _ -> false },
            nowMillis = 2_000L
        )

        assertTrue(decision is PlayerProfileCommandDecision.Apply)
        val updated = (decision as PlayerProfileCommandDecision.Apply).profile
        assertTrue(!updated.terminalEditingAllowed)
        assertTrue(!updated.visitedVenuesPublic)
    }

    @Test
    fun managementQueueCommandParserAcceptsCreateRegistrationWithoutQq() {
        val parsed = parseRemoteTerminalCommands(
            """
            {
              "commands": [{
                "command_id": "00000000-0000-0000-0000-000000000498",
                "type": "QUEUE_OPERATION",
                "created_at": 2000,
                "payload": {
                  "queue_id": "00000000-0000-0000-0000-000000000001",
                  "profile_id": "00000000-0000-0000-0000-000000000901",
                  "actor_qq": "",
                  "operation": "CREATE_REGISTRATION",
                  "operation_source": "MANAGEMENT_APP",
                  "machine_id": "A",
                  "machine_stable_id": "10000000000000000000000000000001",
                  "machine_configuration_revision": 1,
                  "preference": "OPEN_TO_JOIN"
                }
              }]
            }
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        val command = parsed.single() as RemoteQueueOperationCommand
        assertEquals(RemoteQueueOperation.CREATE_REGISTRATION, command.operation)
        assertEquals(RemoteQueueOperationSource.MANAGEMENT_APP, command.source)
        assertEquals("", command.actorQq)
    }

    @Test
    fun managementSettingsCommandsParseCompleteValidPayloads() {
        val parsed = parseRemoteTerminalCommands(
            JSONObject().put(
                "commands",
                JSONArray()
                    .put(managementRegistrationAvailabilityCommand())
                    .put(managementTerminalSettingsCommand())
                    .put(managementMachineStatusCommand())
            ).toString()
        )

        assertEquals(3, parsed.size)
        val availability = parsed[0] as RegistrationAvailabilityCommand
        assertTrue(!availability.registrationOpen)
        assertEquals(setOf("a".repeat(24)), availability.expectedRegistrationIds)
        assertEquals(2L, availability.expectedPolicyRevision)

        val settings = parsed[1] as TerminalSettingsUpdateCommand
        assertEquals(1, settings.configuredMachineCount)
        assertEquals("1".repeat(32), settings.machineStableIds[MachineId.A])
        assertEquals(2, settings.machineConfigurations[MachineId.A]?.capacity)

        val machine = parsed[2] as MachineStatusUpdateCommand
        assertEquals(MachineId.A, machine.machineId)
        assertEquals(2L, machine.expectedPolicyRevision)
        assertEquals(MachineStopReason.OTHER, machine.stopReason)
        assertEquals("临时检修", machine.stopReasonDetail)
    }

    /** The settings fixture with a second machine, so that A and B can form a pair. */
    private fun twoMachineSettingsCommand(pairedSettings: JSONObject?): JSONObject =
        managementTerminalSettingsCommand().apply {
            val payload = getJSONObject("payload")
            val machines = payload.getJSONObject("machines")
            machines.put(
                "B",
                JSONObject(machines.getJSONObject("A").toString())
                    .put("stable_id", "3".repeat(32))
                    .put("remark", "右侧")
            )
            pairedSettings?.let { payload.put("paired_machine_queue_settings", it) }
        }

    private fun pairedSettingsJson(enabled: Boolean, vararg pairs: Pair<String, String>) =
        JSONObject().put("enabled", enabled).put(
            "pairs",
            JSONArray().apply {
                pairs.forEach { (first, second) -> put(JSONArray().put(first).put(second)) }
            }
        )

    @Test
    fun managementSettingsCommandCarriesThePairSettingOnlyWhenPresent() {
        val stableA = "1".repeat(32)
        val stableB = "3".repeat(32)
        fun parse(command: JSONObject) = parseRemoteTerminalCommands(
            JSONObject().put("commands", JSONArray().put(command)).toString()
        ).filterIsInstance<TerminalSettingsUpdateCommand>()

        assertEquals(null, parse(twoMachineSettingsCommand(null)).single().pairedMachineQueue)

        // The management app may name the machines of a pair in either order.
        val paired = parse(
            twoMachineSettingsCommand(pairedSettingsJson(true, stableB to stableA))
        ).single().pairedMachineQueue
        assertEquals(true, paired?.enabled)
        assertEquals(setOf(setOf(stableA, stableB)), paired?.unorderedPairs)

        val switchedOff = parse(
            twoMachineSettingsCommand(pairedSettingsJson(false))
        ).single().pairedMachineQueue
        assertEquals(PairedMachineQueueConfiguration(enabled = false, pairs = emptyList()), switchedOff)

        // A pair that would not survive on the terminal makes the whole command invalid.
        listOf(
            pairedSettingsJson(true, stableA to "9".repeat(32)),
            pairedSettingsJson(true, stableA to stableA),
            pairedSettingsJson(true, stableA to stableB, stableB to stableA),
            JSONObject().put("enabled", true)
        ).forEach { invalid ->
            assertTrue(parse(twoMachineSettingsCommand(invalid)).isEmpty())
        }
        val differentCapacity = twoMachineSettingsCommand(pairedSettingsJson(true, stableA to stableB))
        differentCapacity.getJSONObject("payload").getJSONObject("machines").getJSONObject("B")
            .getJSONObject("configuration").put("capacity", 1)
        assertTrue(parse(differentCapacity).isEmpty())
    }

    @Test
    fun settingsCommandAppliesThePairSettingOrKeepsTheTerminalsOwn() {
        val stableA = "1".repeat(32)
        val stableB = "3".repeat(32)
        fun parse(command: JSONObject) = parseRemoteTerminalCommands(
            JSONObject().put("commands", JSONArray().put(command)).toString()
        ).filterIsInstance<TerminalSettingsUpdateCommand>().single()
        val current = QueueRuleSettings(
            configuredMachineCount = 2,
            machineStableIds = DEFAULT_MACHINE_STABLE_IDS + mapOf(
                MachineId.A to stableA,
                MachineId.B to stableB
            ),
            pairedMachineQueueEnabled = true,
            pairedMachineStableIds = listOf(stableA to stableB),
            managementAppBound = true,
            managementPolicyRevision = 2L,
            managementSettingsRevision = 3L
        )

        val untouched = parse(twoMachineSettingsCommand(null)).requestedSettings(current)
        assertTrue(untouched.pairedMachineQueueEnabled)
        assertEquals(listOf(stableA to stableB), untouched.pairedMachineStableIds)
        assertEquals(4L, untouched.managementSettingsRevision)

        val switchedOff = parse(twoMachineSettingsCommand(pairedSettingsJson(false)))
            .requestedSettings(current)
        assertTrue(!switchedOff.pairedMachineQueueEnabled)
        assertTrue(switchedOff.pairedMachineStableIds.isEmpty())

        val command = parse(twoMachineSettingsCommand(null))
        assertEquals(
            null,
            command.rejectionDetail(
                currentQueueId = "00000000-0000-0000-0000-000000000001",
                settings = current,
                registrationOpen = false
            )
        )
        assertEquals(
            "现场终端已经解除管理后台接管，请重新绑定后再提交。",
            command.rejectionDetail(
                currentQueueId = "00000000-0000-0000-0000-000000000001",
                settings = current.copy(managementAppBound = false),
                registrationOpen = false
            )
        )
        assertEquals(
            "终端设置已经更新，请刷新后重试。",
            command.rejectionDetail(
                currentQueueId = "00000000-0000-0000-0000-000000000001",
                settings = current.copy(managementSettingsRevision = 5L),
                registrationOpen = false
            )
        )
        assertEquals(
            "登记排队状态已经更新，请管理后台刷新后重试。",
            command.rejectionDetail(
                currentQueueId = "00000000-0000-0000-0000-000000000001",
                settings = current,
                registrationOpen = true
            )
        )
    }

    @Test
    fun managementRegistrationAvailabilityRejectsUnsafeConfirmationPayloads() {
        val wrongSource = managementRegistrationAvailabilityCommand().apply {
            getJSONObject("payload").put("operation_source", "QQ_BOT")
        }
        val duplicateRegistrations = managementRegistrationAvailabilityCommand().apply {
            getJSONObject("payload").put(
                "expected_registration_ids",
                JSONArray().put("a".repeat(24)).put("a".repeat(24))
            )
        }
        val missingConfirmation = managementRegistrationAvailabilityCommand().apply {
            getJSONObject("payload").remove("confirm_clear_queue")
        }
        val incorrectConfirmation = managementRegistrationAvailabilityCommand().apply {
            getJSONObject("payload").put("confirm_clear_queue", false)
        }
        val opensWithExistingRegistrations = managementRegistrationAvailabilityCommand().apply {
            getJSONObject("payload")
                .put("expected_registration_open", false)
                .put("registration_open", true)
                .put("confirm_clear_queue", false)
        }

        listOf(
            wrongSource,
            duplicateRegistrations,
            missingConfirmation,
            incorrectConfirmation,
            opensWithExistingRegistrations
        ).forEach { command ->
            assertTrue(parseManagementCommand(command).isEmpty())
        }
    }

    @Test
    fun managementTerminalSettingsRejectMalformedMachineLayouts() {
        val wrongSource = managementTerminalSettingsCommand().apply {
            getJSONObject("payload").put("operation_source", "ON_SITE_TERMINAL")
        }
        val skippedMachine = managementTerminalSettingsCommand().apply {
            val machines = getJSONObject("payload").getJSONObject("machines")
            machines.put("B", machines.remove("A"))
        }
        val invalidStableId = managementTerminalSettingsCommand().apply {
            getJSONObject("payload").getJSONObject("machines")
                .getJSONObject("A").put("stable_id", "not-a-stable-id")
        }
        val nonContiguousRevision = managementTerminalSettingsCommand().apply {
            getJSONObject("payload").put("next_settings_revision", 5)
        }
        val emptyGroup = managementTerminalSettingsCommand().apply {
            getJSONObject("payload").getJSONArray("machine_groups")
                .put(JSONObject().put("id", "2".repeat(32)).put("name", "空分组"))
        }

        listOf(
            wrongSource,
            skippedMachine,
            invalidStableId,
            nonContiguousRevision,
            emptyGroup
        ).forEach { command ->
            assertTrue(parseManagementCommand(command).isEmpty())
        }
    }

    @Test
    fun managementMachineStatusRejectsUnsafeOrInconsistentPayloads() {
        val wrongSource = managementMachineStatusCommand().apply {
            getJSONObject("payload").put("operation_source", "WEBSITE_REMOTE")
        }
        val invalidStableId = managementMachineStatusCommand().apply {
            getJSONObject("payload").put("machine_stable_id", "1".repeat(31))
        }
        val unchangedState = managementMachineStatusCommand().apply {
            getJSONObject("payload").put("expected_operational", false)
        }
        val missingOtherDetail = managementMachineStatusCommand().apply {
            getJSONObject("payload").put("stop_reason_detail", JSONObject.NULL)
        }
        val overlongDetail = managementMachineStatusCommand().apply {
            getJSONObject("payload").put("stop_reason_detail", "字".repeat(41))
        }
        val negativePolicyRevision = managementMachineStatusCommand().apply {
            getJSONObject("payload").put("expected_management_policy_revision", -1)
        }

        listOf(
            wrongSource,
            invalidStableId,
            unchangedState,
            missingOtherDetail,
            overlongDetail,
            negativePolicyRevision
        ).forEach { command ->
            assertTrue(parseManagementCommand(command).isEmpty())
        }
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

    private fun parseManagementCommand(command: JSONObject): List<RemoteTerminalCommand> =
        parseRemoteTerminalCommands(JSONObject().put("commands", JSONArray().put(command)).toString())

    private fun managementRegistrationAvailabilityCommand() = JSONObject(
        """
        {
          "command_id": "00000000-0000-0000-0000-000000000981",
          "type": "SET_REGISTRATION_AVAILABILITY",
          "created_at": 2000,
          "payload": {
            "operation_source": "MANAGEMENT_APP",
            "queue_id": "00000000-0000-0000-0000-000000000001",
            "expected_queue_revision": 4,
            "expected_management_policy_revision": 2,
            "expected_machine_configuration_revision": 1,
            "expected_registration_open": true,
            "registration_open": false,
            "expected_registration_ids": ["${"a".repeat(24)}"],
            "confirm_clear_queue": true,
            "reason": "管理后台关闭登记"
          }
        }
        """.trimIndent()
    )

    private fun managementTerminalSettingsCommand() = JSONObject(
        """
        {
          "command_id": "00000000-0000-0000-0000-000000000982",
          "type": "UPDATE_TERMINAL_SETTINGS",
          "created_at": 2000,
          "payload": {
            "operation_source": "MANAGEMENT_APP",
            "queue_id": "00000000-0000-0000-0000-000000000001",
            "expected_settings_revision": 3,
            "next_settings_revision": 4,
            "expected_policy_revision": 2,
            "expected_machine_configuration_revision": 1,
            "expected_registration_open": false,
            "show_common_play_preview": true,
            "business_hours": {
              "enabled": false,
              "use_weekly_schedule": false,
              "default_hours": {"opening_minutes": 600, "closing_minutes": 1320},
              "weekly_hours": {}
            },
            "machine_groups": [{"id": "${"2".repeat(32)}", "name": "主区域"}],
            "default_machine_group_id": "${"2".repeat(32)}",
            "machines": {
              "A": {
                "stable_id": "${"1".repeat(32)}",
                "group_id": "${"2".repeat(32)}",
                "remark": "左侧",
                "configuration": {
                  "game_type": "MAIMAI_DX",
                  "custom_game_type": null,
                  "server": "HIDDEN",
                  "custom_server": null,
                  "game_version": null,
                  "game_version_visible": false,
                  "capacity": 2,
                  "solo_round_minutes": 12,
                  "shared_round_minutes": 15
                }
              }
            },
            "reason": "管理后台更新终端设置"
          }
        }
        """.trimIndent()
    )

    private fun managementMachineStatusCommand() = JSONObject(
        """
        {
          "command_id": "00000000-0000-0000-0000-000000000983",
          "type": "UPDATE_MACHINE_STATUS",
          "created_at": 2000,
          "payload": {
            "operation_source": "MANAGEMENT_APP",
            "queue_id": "00000000-0000-0000-0000-000000000001",
            "expected_management_policy_revision": 2,
            "expected_machine_configuration_revision": 1,
            "machine_id": "A",
            "machine_stable_id": "${"1".repeat(32)}",
            "expected_operational": true,
            "operational": false,
            "stop_reason": "OTHER",
            "stop_reason_detail": "临时检修",
            "reason": "管理后台停止机台"
          }
        }
        """.trimIndent()
    )
}
