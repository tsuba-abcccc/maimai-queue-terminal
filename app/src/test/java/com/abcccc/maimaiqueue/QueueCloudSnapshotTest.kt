package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCloudSnapshotTest {
    private val queueId = "00000000-0000-0000-0000-000000000099"

    @Test
    fun schemaSevenFallbackOmitsSchemaEightIdentityFields() {
        val profile = PlayerProfile(
            id = "00000000-0000-0000-0000-000000000111",
            publicPlayerId = "001234",
            nickname = "测试玩家",
            gender = PlayerGender.UNDISCLOSED,
            defaultPreference = ProfilePlayPreference.SOLO,
            lastUsedAtMillis = 90L,
            recentUsageAtMillis = listOf(70L, 80L, 90L),
            createdAtMillis = 100L,
            updatedAtMillis = 100L
        )

        val snapshot = buildQueueSyncSnapshot(
            state = state(),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L,
            playerProfiles = listOf(profile),
            schemaVersion = 7,
            venueId = "00000000-0000-0000-0000-000000000222",
            terminalName = "入口终端"
        )
        val terminal = snapshot.getJSONObject("terminal")
        val serializedProfile = snapshot.getJSONArray("private_player_profiles")
            .getJSONObject(0)

        assertEquals(7, snapshot.getInt("schema_version"))
        assertEquals("左侧 · 机台 A", snapshot.getJSONObject("machines")
            .getJSONObject("A").getString("name"))
        assertFalse(snapshot.has("venue"))
        assertFalse(terminal.has("name"))
        assertFalse(serializedProfile.has("public_player_id"))
        assertFalse(serializedProfile.has("recent_usage_at"))
    }

    @Test
    fun schemaEightSnapshotIncludesInstallationIdentity() {
        val venueId = "00000000-0000-0000-0000-000000000222"
        val profile = PlayerProfile(
            id = "00000000-0000-0000-0000-000000000111",
            nickname = "测试玩家",
            gender = PlayerGender.UNDISCLOSED,
            defaultPreference = ProfilePlayPreference.SOLO,
            lastUsedAtMillis = 90L,
            recentUsageAtMillis = listOf(70L, 80L, 90L),
            createdAtMillis = 50L,
            updatedAtMillis = 100L
        )
        val snapshot = buildQueueSyncSnapshot(
            state = state(),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L,
            playerProfiles = listOf(profile),
            schemaVersion = 8,
            venueId = venueId,
            terminalName = "入口终端"
        )

        assertEquals(venueId, snapshot.getJSONObject("venue").getString("id"))
        assertEquals("入口终端", snapshot.getJSONObject("terminal").getString("name"))
        val recentUsage = snapshot.getJSONArray("private_player_profiles")
            .getJSONObject(0)
            .getJSONArray("recent_usage_at")
        assertEquals(listOf(70L, 80L, 90L), List(recentUsage.length()) { recentUsage.getLong(it) })
    }

    @Test
    fun publicSnapshotContainsQueueLayoutAndWaitEstimate() {
        val nowMillis = 1_000_000L
        val playing = Registration(
            key = 1,
            displayId = "正在游玩",
            preference = PlayPreference.SOLO,
            createdAtMillis = 100L
        )
        val waiting = Registration(
            key = 2,
            displayId = "等待玩家",
            preference = PlayPreference.SOLO,
            createdAtMillis = 200L
        )
        val state = state(
            machineA = MachineQueue(
                playing = listOf(playing),
                waiting = listOf(waiting),
                playingStartedAtMillis = nowMillis - 5 * 60_000L
            )
        )

        val snapshot = buildPublicQueueSnapshot(state, "terminal-1", nowMillis)
        val machineA = snapshot.getJSONObject("machines").getJSONObject("A")
        val firstWaitingPosition = machineA.getJSONArray("waiting_positions").getJSONObject(0)

        assertEquals(queueId, snapshot.getString("queue_id"))
        assertEquals(9L, snapshot.getLong("revision"))
        assertEquals(2, machineA.getInt("registration_count"))
        assertEquals(7L, firstWaitingPosition.getLong("estimated_wait_minutes"))
        assertEquals("等待玩家", firstWaitingPosition.getJSONArray("registrations")
            .getJSONObject(0).getString("display_id"))
    }

    @Test
    fun publicSnapshotUsesConfiguredMachineRemarks() {
        val snapshot = buildPublicQueueSnapshot(
            state = state(),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L,
            displaySettings = QueuePublicDisplaySettings(
                machineRemarks = DEFAULT_MACHINE_REMARKS + mapOf(
                    MachineId.A to "入口侧",
                    MachineId.B to "墙侧"
                ),
                oneBotSyncEnabled = false,
                allowOnlineRegistration = false,
                businessHours = QueuePublicBusinessHours(
                    enabled = true,
                    outsideBusinessHours = true,
                    closingSoon = false,
                    closingGracePeriod = true,
                    registrationClosesAtMillis = 2_000L
                )
            )
        )
        val machines = snapshot.getJSONObject("machines")
        val businessHours = snapshot.getJSONObject("business_hours")

        assertEquals("入口侧·机台 A", machines.getJSONObject("A").getString("name"))
        assertEquals("墙侧·机台 B", machines.getJSONObject("B").getString("name"))
        assertFalse(snapshot.getBoolean("onebot_sync_enabled"))
        assertFalse(snapshot.getBoolean("registration_open"))
        assertFalse(
            snapshot.getJSONObject("queue_rules").getBoolean("allow_online_registration")
        )
        assertTrue(businessHours.getBoolean("enabled"))
        assertTrue(businessHours.getBoolean("outside"))
        assertFalse(businessHours.getBoolean("closing_soon"))
        assertTrue(businessHours.getBoolean("closing_grace"))
        assertTrue(businessHours.isNull("closes_at"))
        assertEquals(2_000L, businessHours.getLong("registration_closes_at"))
    }

    @Test
    fun publicSnapshotPublishesMachineConfigurationRevisionAndPlannedDuration() {
        val nowMillis = 1_000_000L
        val machineConfiguration = MachineConfiguration(
            remark = "入口侧",
            gameType = MachineGameType.OTHER,
            customGameType = "其他音游",
            server = MachineServer.HIDDEN,
            gameVersion = "1.2",
            showGameVersion = true,
            capacity = 1,
            soloRoundMinutes = 20,
            sharedRoundMinutes = 30
        )
        val snapshot = buildPublicQueueSnapshot(
            state = state(
                machineA = MachineQueue(
                    playing = listOf(registration(1, "正在游玩")),
                    waiting = listOf(registration(2, "等待玩家")),
                    playingStartedAtMillis = nowMillis - 5 * 60_000L
                )
            ),
            terminalId = "terminal-1",
            capturedAtMillis = nowMillis,
            displaySettings = QueuePublicDisplaySettings(
                machineConfigurations = mapOf(MachineId.A to machineConfiguration),
                machineConfigurationRevision = 7L
            )
        )
        val machine = snapshot.getJSONObject("machines").getJSONObject("A")
        val configuration = machine.getJSONObject("configuration")

        assertEquals(8, snapshot.getInt("schema_version"))
        assertEquals(7L, snapshot.getLong("machine_configuration_revision"))
        assertEquals("入口侧", machine.getString("remark"))
        assertEquals("OTHER", configuration.getString("game_type"))
        assertEquals("其他音游", configuration.getString("custom_game_type"))
        assertEquals("HIDDEN", configuration.getString("server"))
        assertEquals("1.2", configuration.getString("game_version"))
        assertTrue(configuration.getBoolean("game_version_visible"))
        assertEquals(1, configuration.getInt("capacity"))
        assertEquals(20, configuration.getInt("solo_round_minutes"))
        assertEquals(30, configuration.getInt("shared_round_minutes"))
        assertEquals(
            15L,
            machine.getJSONArray("waiting_positions").getJSONObject(0)
                .getLong("estimated_wait_minutes")
        )
    }

    @Test
    fun authenticatedSyncSnapshotKeepsSchemaSevenMachineConfiguration() {
        val configuration = MachineConfiguration(
            remark = "入口侧",
            capacity = 1,
            soloRoundMinutes = 18,
            sharedRoundMinutes = 24
        )

        val snapshot = buildQueueSyncSnapshot(
            state = state(),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L,
            displaySettings = QueuePublicDisplaySettings(
                machineConfigurations = mapOf(MachineId.A to configuration),
                machineConfigurationRevision = 9L
            )
        )
        val published = snapshot.getJSONObject("machines").getJSONObject("A")
            .getJSONObject("configuration")

        assertEquals(8, snapshot.getInt("schema_version"))
        assertEquals(9L, snapshot.getLong("machine_configuration_revision"))
        assertEquals(1, published.getInt("capacity"))
        assertEquals(18, published.getInt("solo_round_minutes"))
        assertEquals(24, published.getInt("shared_round_minutes"))
    }

    @Test
    fun publicSnapshotPublishesExactlyOneToTenConfiguredMachines() {
        (1..MachineId.entries.size).forEach { machineCount ->
            val machineIds = configuredMachineIds(machineCount)
            val state = PersistedQueueState(
                queueId = queueId,
                revision = 9L,
                machines = machineIds.associateWith { machineId ->
                    PersistedMachineState(
                        queue = MachineQueue(
                            waiting = listOf(
                                registration(machineId.ordinal + 1, "玩家${machineId.name}")
                            )
                        )
                    )
                },
                registrationOpen = true,
                nextRegistrationKey = machineCount + 1,
                savedAtMillis = 900L
            )
            val snapshot = buildPublicQueueSnapshot(
                state = state,
                terminalId = "terminal-1",
                capturedAtMillis = 1_000L,
                displaySettings = QueuePublicDisplaySettings(
                    machineRemarks = MachineId.entries.associateWith { "区域${it.name}" }
                )
            )
            val machines = snapshot.getJSONObject("machines")
            val publishedIds = buildSet {
                val keys = machines.keys()
                while (keys.hasNext()) add(keys.next())
            }

            assertEquals("machine count $machineCount", machineIds.map(MachineId::name).toSet(), publishedIds)
            machineIds.forEach { machineId ->
                val machine = machines.getJSONObject(machineId.name)
                assertEquals("区域${machineId.name}·机台 ${machineId.name}", machine.getString("name"))
                assertEquals(1, machine.getInt("registration_count"))
            }
        }
    }

    @Test
    fun publicSnapshotPublishesStableMachineAndGroupMetadata() {
        val secondGroup = MachineGroupConfiguration(
            id = "10000000000000000000000000000002",
            name = "二楼"
        )
        val machineIds = configuredMachineIds(5)
        val state = PersistedQueueState(
            queueId = queueId,
            revision = 10L,
            machines = machineIds.associateWith { PersistedMachineState() },
            registrationOpen = true,
            nextRegistrationKey = 1,
            savedAtMillis = 900L
        )
        val machineEStableId = "20000000000000000000000000000005"

        val snapshot = buildPublicQueueSnapshot(
            state = state,
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L,
            displaySettings = QueuePublicDisplaySettings(
                machineStableIds = DEFAULT_MACHINE_STABLE_IDS +
                    (MachineId.E to machineEStableId),
                machineGroupAssignments = DEFAULT_MACHINE_GROUP_ASSIGNMENTS +
                    (MachineId.E to secondGroup.id),
                machineGroups = DEFAULT_MACHINE_GROUPS + secondGroup,
                defaultMachineGroupId = secondGroup.id
            )
        )
        val groups = snapshot.getJSONArray("machine_groups")
        val machineE = snapshot.getJSONObject("machines").getJSONObject("E")

        assertEquals(2, groups.length())
        assertEquals(DEFAULT_MACHINE_GROUP_ID, groups.getJSONObject(0).getString("id"))
        assertEquals("二楼", groups.getJSONObject(1).getString("name"))
        assertEquals(secondGroup.id, snapshot.getString("default_machine_group_id"))
        assertEquals(machineEStableId, machineE.getString("stable_id"))
        assertEquals(secondGroup.id, machineE.getString("group_id"))
    }

    @Test
    fun publicEventsKeepMachineEThroughJMappings() {
        val events = listOf(
            AuditLogEntry(
                id = "00000000-0000-0000-0000-000000000701",
                timestampMillis = 1_000L,
                category = AuditLogCategory.MACHINE_E,
                title = "机台 E · 新增登记",
                detail = "已加入机台 E。",
                queueId = queueId,
                publicEventType = PublicQueueEventType.REGISTRATION_ADDED,
                machineStableId = "20000000000000000000000000000005",
                machineName = "二楼 · 机台 E"
            ),
            AuditLogEntry(
                id = "00000000-0000-0000-0000-000000000702",
                timestampMillis = 2_000L,
                category = AuditLogCategory.MACHINE_J,
                title = "机台 J · 机台停止使用",
                detail = "机台 J 已停止使用。",
                queueId = queueId,
                publicEventType = PublicQueueEventType.MACHINE_STOPPED
            )
        )
        val state = PersistedQueueState(
            queueId = queueId,
            revision = 9L,
            machines = configuredMachineIds(10).associateWith { PersistedMachineState() },
            registrationOpen = true,
            nextRegistrationKey = 1,
            savedAtMillis = 900L
        )

        val published = buildPublicQueueSnapshot(
            state = state,
            terminalId = "terminal-1",
            capturedAtMillis = 3_000L,
            auditLogs = events
        ).getJSONArray("recent_events")

        assertEquals("J", published.getJSONObject(0).getString("machine_id"))
        assertEquals("E", published.getJSONObject(1).getString("machine_id"))
        assertEquals(
            defaultMachineStableId(MachineId.J),
            published.getJSONObject(0).getString("machine_stable_id")
        )
        assertEquals(
            "20000000000000000000000000000005",
            published.getJSONObject(1).getString("machine_stable_id")
        )
        assertEquals("二楼·机台 E", published.getJSONObject(1).getString("machine_name"))
    }

    @Test
    fun publicEventKeepsAllRegistrationsAcrossTenMachines() {
        val affectedKeys = (1..200).toList()
        val event = AuditLogEntry(
            id = "00000000-0000-0000-0000-000000000703",
            timestampMillis = 2_000L,
            category = AuditLogCategory.SYSTEM,
            title = "关闭登记排队",
            detail = "登记排队已关闭，并清除了所有机台的 200 份登记。",
            queueId = queueId,
            publicEventType = PublicQueueEventType.REGISTRATION_CLOSED,
            affectedRegistrationKeys = affectedKeys
        )
        val state = PersistedQueueState(
            queueId = queueId,
            revision = 10L,
            machines = configuredMachineIds(10).associateWith { PersistedMachineState() },
            registrationOpen = false,
            nextRegistrationKey = 201,
            savedAtMillis = 1_000L
        )

        val registrationIds = buildPublicQueueSnapshot(
            state = state,
            terminalId = "terminal-1",
            capturedAtMillis = 3_000L,
            auditLogs = listOf(event)
        ).getJSONArray("recent_events")
            .getJSONObject(0)
            .getJSONArray("registration_ids")

        assertEquals(200, registrationIds.length())
        assertEquals(publicRegistrationId(queueId, 1), registrationIds.getString(0))
        assertEquals(publicRegistrationId(queueId, 200), registrationIds.getString(199))
    }

    @Test
    fun systemAndPlayerProfileEventsNeverPublishDirtyMachineIdentity() {
        val dirtyEvents = listOf(
            AuditLogEntry(
                id = "00000000-0000-0000-0000-000000000704",
                timestampMillis = 2_000L,
                category = AuditLogCategory.SYSTEM,
                title = "关闭登记排队",
                detail = "登记排队已关闭。",
                queueId = queueId,
                publicEventType = PublicQueueEventType.REGISTRATION_CLOSED,
                machineStableId = "10000000000000000000000000000001",
                machineName = "null"
            ),
            AuditLogEntry(
                id = "00000000-0000-0000-0000-000000000705",
                timestampMillis = 1_000L,
                category = AuditLogCategory.PLAYER_PROFILE,
                title = "错误公开的玩家资料事件",
                detail = "用于验证上传前最终防护。",
                queueId = queueId,
                publicEventType = PublicQueueEventType.OTHER,
                machineStableId = "10000000000000000000000000000001",
                machineName = "入口侧 · 机台 A"
            )
        )

        val events = buildQueueSyncSnapshot(
            state = state(),
            terminalId = "terminal-1",
            capturedAtMillis = 3_000L,
            auditLogs = dirtyEvents
        ).getJSONArray("recent_events")

        repeat(events.length()) { index ->
            val event = events.getJSONObject(index)
            assertTrue(event.isNull("machine_id"))
            assertTrue(event.isNull("machine_stable_id"))
            assertTrue(event.isNull("machine_name"))
        }
    }

    @Test
    fun publicSnapshotExcludesPrivatePlayerProfileFields() {
        val privateProfileId = "private-profile-id-should-never-leave-device"
        val registration = Registration(
            key = 7,
            displayId = "公开昵称",
            preference = PlayPreference.OPEN_TO_JOIN,
            isTemporary = false,
            createdAtMillis = 100L,
            gender = PlayerGender.FEMALE,
            playerProfileId = privateProfileId
        )

        val serialized = buildPublicQueueSnapshot(
            state(machineB = MachineQueue(waiting = listOf(registration))),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L
        ).toString()

        assertTrue(serialized.contains("公开昵称"))
        assertFalse(serialized.contains(privateProfileId))
        assertFalse(serialized.contains("\"gender\""))
        assertFalse(serialized.contains("\"player_profile_id\""))
        assertFalse(serialized.contains("\"qq_number\""))
        assertFalse(serialized.contains("\"phone_number\""))
    }

    @Test
    fun publicSnapshotIncludesTemporaryAwayStateAndSkippedTurns() {
        val registration = Registration(
            key = 8,
            displayId = "暂离玩家",
            preference = PlayPreference.SOLO,
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 2,
            createdAtMillis = 100L
        )

        val snapshot = buildPublicQueueSnapshot(
            state(machineA = MachineQueue(waiting = listOf(registration))),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L
        )
        val publicRegistration = snapshot.getJSONObject("machines").getJSONObject("A")
            .getJSONArray("waiting_positions").getJSONObject(0)
            .getJSONArray("registrations").getJSONObject(0)

        assertTrue(publicRegistration.getBoolean("temporarily_away"))
        assertEquals(2, publicRegistration.getInt("temporary_away_skipped_turns"))
        assertFalse(publicRegistration.getBoolean("deferred_once"))
    }

    @Test
    fun publicSnapshotIncludesMaintenanceAndOptionalOtherStopDetail() {
        val snapshot = buildPublicQueueSnapshot(
            state().copy(
                machines = linkedMapOf(
                    MachineId.A to PersistedMachineState(
                        status = MachineStatus().stop(
                            MachineStopReason.OTHER,
                            800L,
                            "按钮失灵"
                        )
                    ),
                    MachineId.B to PersistedMachineState(
                        status = MachineStatus().stop(
                            MachineStopReason.MAINTENANCE,
                            900L
                        )
                    )
                )
            ),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L
        )
        val machines = snapshot.getJSONObject("machines")
        val machineA = machines.getJSONObject("A")
        val machineB = machines.getJSONObject("B")

        assertEquals("OTHER", machineA.getString("stop_reason"))
        assertEquals("按钮失灵", machineA.getString("stop_reason_detail"))
        assertEquals("MAINTENANCE", machineB.getString("stop_reason"))
        assertTrue(machineB.isNull("stop_reason_detail"))
    }

    @Test
    fun stoppedMachineDoesNotPublishARunningTimerOrWaitEstimate() {
        val nowMillis = 1_000_000L
        val state = state(
            machineA = MachineQueue(
                playing = listOf(registration(1, "正在游玩")),
                waiting = listOf(registration(2, "等待玩家")),
                playingStartedAtMillis = nowMillis - 5 * 60_000L
            )
        ).let { state ->
            state.copy(
                machines = state.machines + (
                    MachineId.A to state.machine(MachineId.A).copy(
                        status = MachineStatus().stop(
                            reason = MachineStopReason.MAINTENANCE,
                            atMillis = nowMillis - 60_000L
                        )
                    )
                )
            )
        }

        val machine = buildPublicQueueSnapshot(state, "terminal-1", nowMillis)
            .getJSONObject("machines")
            .getJSONObject("A")

        assertFalse(machine.getBoolean("operational"))
        assertTrue(machine.isNull("playing_started_at"))
        assertTrue(
            machine.getJSONArray("waiting_positions")
                .getJSONObject(0)
                .isNull("estimated_wait_minutes")
        )
        assertEquals(2, machine.getInt("registration_count"))
    }

    @Test
    fun publicSnapshotKeepsCreationTimeSeparateFromRestartedCheckInTimer() {
        val registration = Registration(
            key = 3,
            displayId = "线上玩家",
            preference = PlayPreference.SOLO,
            isTemporary = false,
            createdAtMillis = 100L,
            playerProfileId = "profile-3",
            requiresOnSiteCheckIn = true,
            onSiteCheckInStartedAtMillis = 500L
        )

        val publicRegistration = buildPublicQueueSnapshot(
            state(machineA = MachineQueue(waiting = listOf(registration))),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L
        ).getJSONObject("machines").getJSONObject("A")
            .getJSONArray("waiting_positions").getJSONObject(0)
            .getJSONArray("registrations").getJSONObject(0)

        assertEquals(100L, publicRegistration.getLong("created_at"))
        assertEquals(500L, publicRegistration.getLong("online_check_in_started_at"))
    }

    @Test
    fun publicSnapshotGroupsPendingCheckInLikeANormalRegistration() {
        val pending = Registration(
            key = 1,
            displayId = "待签到",
            preference = PlayPreference.OPEN_TO_JOIN,
            createdAtMillis = 100L,
            requiresOnSiteCheckIn = true
        )
        val available = Registration(
            key = 2,
            displayId = "现场玩家",
            preference = PlayPreference.OPEN_TO_JOIN,
            createdAtMillis = 200L
        )

        val machine = buildPublicQueueSnapshot(
            state(machineA = MachineQueue(waiting = listOf(pending, available))),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L
        ).getJSONObject("machines").getJSONObject("A")
        val positions = machine.getJSONArray("waiting_positions")

        assertEquals(1, positions.length())
        assertEquals(2, positions.getJSONObject(0).getJSONArray("registrations").length())
        assertEquals(0L, positions.getJSONObject(0).getLong("estimated_wait_minutes"))
        val registrationIds = positions.getJSONObject(0).getJSONArray("registrations")
        assertEquals(
            listOf(publicRegistrationId(queueId, 1), publicRegistrationId(queueId, 2)),
            (0 until registrationIds.length()).map { index ->
                registrationIds.getJSONObject(index).getString("registration_id")
            }
        )
    }

    @Test
    fun publicSnapshotPublishesCommonPlayPreviewWithoutCountingItAsARegistration() {
        val queue = MachineQueue(
            waiting = (1..5).map { key ->
                Registration(
                    key = key,
                    displayId = "玩家-$key",
                    preference = PlayPreference.OPEN_TO_JOIN,
                    createdAtMillis = 100L + key
                )
            }
        )

        val machine = buildPublicQueueSnapshot(
            state(machineA = queue),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L
        ).getJSONObject("machines").getJSONObject("A")
        val lastPosition = machine.getJSONArray("waiting_positions").getJSONObject(2)
        val preview = lastPosition.getJSONObject("common_play_preview")

        assertEquals(5, machine.getInt("registration_count"))
        assertEquals(3, machine.getInt("waiting_position_count"))
        assertEquals(1, lastPosition.getJSONArray("registrations").length())
        assertEquals("玩家-1", preview.getString("display_id"))
        assertEquals(publicRegistrationId(queueId, 1), preview.getString("registration_id"))
    }

    @Test
    fun commonPlayPreviewSettingDoesNotChangeProjectedRealPositions() {
        val queue = MachineQueue(
            waiting = (1..5).map { key ->
                Registration(
                    key = key,
                    displayId = "玩家-$key",
                    preference = PlayPreference.OPEN_TO_JOIN,
                    createdAtMillis = 100L + key
                )
            }
        )

        val machine = buildPublicQueueSnapshot(
            state(machineA = queue),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L,
            displaySettings = QueuePublicDisplaySettings(showCommonPlayPreview = false)
        ).getJSONObject("machines").getJSONObject("A")
        val positions = machine.getJSONArray("waiting_positions")

        assertEquals(3, positions.length())
        assertTrue(positions.getJSONObject(2).isNull("common_play_preview"))
        assertEquals(5, machine.getInt("registration_count"))
    }

    @Test
    fun publicSnapshotProjectsDeferredOpenRegistrationAtItsActualNextOpportunity() {
        val deferred = Registration(
            key = 1,
            displayId = "暂缓玩家",
            preference = PlayPreference.OPEN_TO_JOIN,
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
            createdAtMillis = 100L
        )
        val next = Registration(
            key = 2,
            displayId = "先行玩家",
            preference = PlayPreference.OPEN_TO_JOIN,
            createdAtMillis = 200L
        )
        val queue = MachineQueue(waiting = listOf(deferred, next))

        val machine = buildPublicQueueSnapshot(
            state(machineA = queue),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L
        ).getJSONObject("machines").getJSONObject("A")
        val positions = machine.getJSONArray("waiting_positions")
        val first = positions.getJSONObject(0)
        val second = positions.getJSONObject(1)

        assertEquals("先行玩家", first.getJSONArray("registrations")
            .getJSONObject(0).getString("display_id"))
        assertEquals("暂缓玩家", second.getJSONArray("registrations")
            .getJSONObject(0).getString("display_id"))
        assertEquals(0L, first.getLong("estimated_wait_minutes"))
        assertEquals(12L, second.getLong("estimated_wait_minutes"))
        assertEquals("先行玩家", second.getJSONObject("common_play_preview")
            .getString("display_id"))
        assertEquals(listOf(1, 2), queue.waiting.map { it.key })
        assertEquals(QueueAbsenceStatus.DEFER_ONE_ROUND, queue.waiting.first().absenceStatus)
    }

    @Test
    fun publicSnapshotIncludesOnlyQueueScopedPublicEvents() {
        val state = state(machineA = MachineQueue(waiting = listOf(
            Registration(
                key = 8,
                displayId = "日志玩家",
                preference = PlayPreference.OPEN_TO_JOIN,
                createdAtMillis = 100L
            )
        )))
        val publicLog = createAuditLogEntry(
            category = AuditLogCategory.MACHINE_A,
            title = "机台 A · 未到场状态已更新",
            detail = "“日志玩家”已移至队尾。",
            source = AuditLogSource.QQ_BOT,
            timestampMillis = 900L,
            publicEventType = PublicQueueEventType.NO_SHOW_MOVED_TO_TAIL,
            affectedRegistrationKeys = setOf(8)
        ).copy(queueId = queueId)
        val privateLog = createPlayerProfileAuditLog(
            before = null,
            after = PlayerProfile(
                id = "private-profile-id",
                nickname = "私有资料",
                gender = PlayerGender.UNDISCLOSED,
                defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
                qqNumber = "12345678",
                createdAtMillis = 100L,
                updatedAtMillis = 100L
            ),
            timestampMillis = 800L
        ).copy(queueId = queueId)

        val snapshot = buildPublicQueueSnapshot(
            state,
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L,
            auditLogs = listOf(publicLog, privateLog)
        )
        val events = snapshot.getJSONArray("recent_events")

        assertEquals(8, snapshot.getInt("schema_version"))
        assertEquals(1, events.length())
        assertEquals("NO_SHOW_MOVED_TO_TAIL", events.getJSONObject(0).getString("type"))
        assertEquals(
            "ABSENCE",
            events.getJSONObject(0).getJSONArray("notification_categories").getString(0)
        )
        assertEquals("QQ_BOT", events.getJSONObject(0).getString("operation_source"))
        assertEquals(
            publicRegistrationId(queueId, 8),
            events.getJSONObject(0).getJSONArray("registration_ids").getString(0)
        )
        assertFalse(snapshot.toString().contains("12345678"))
        assertFalse(snapshot.toString().contains("private-profile-id"))
    }

    @Test
    fun syncSnapshotIncludesFullLibraryAndOnlyValidActiveQqBindings() {
        val qqProfileId = "00000000-0000-0000-0000-000000000910"
        val invalidQqProfileId = "00000000-0000-0000-0000-000000000911"
        val inactiveProfileId = "00000000-0000-0000-0000-000000000912"
        val qqRegistration = Registration(
            key = 10,
            displayId = "QQ 玩家",
            preference = PlayPreference.OPEN_TO_JOIN,
            isTemporary = false,
            playerProfileId = qqProfileId,
            createdAtMillis = 100L
        )
        val invalidQqRegistration = Registration(
            key = 11,
            displayId = "待补资料玩家",
            preference = PlayPreference.SOLO,
            isTemporary = false,
            playerProfileId = invalidQqProfileId,
            createdAtMillis = 200L
        )

        val snapshot = buildQueueSyncSnapshot(
            state = state(machineA = MachineQueue(waiting = listOf(qqRegistration, invalidQqRegistration))),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L,
            playerProfiles = listOf(
                PlayerProfile(
                    id = qqProfileId,
                    nickname = "QQ 玩家",
                    gender = PlayerGender.UNDISCLOSED,
                    defaultPreference = ProfilePlayPreference.OPEN_TO_JOIN,
                    qqNumber = "12345678"
                ),
                PlayerProfile(
                    id = invalidQqProfileId,
                    nickname = "待补资料玩家",
                    gender = PlayerGender.UNDISCLOSED,
                    defaultPreference = ProfilePlayPreference.SOLO,
                    qqNumber = "1234"
                ),
                PlayerProfile(
                    id = inactiveProfileId,
                    nickname = "未登记玩家",
                    gender = PlayerGender.UNDISCLOSED,
                    defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
                    qqNumber = "87654321"
                )
            )
        )
        val contacts = snapshot.getJSONArray("private_player_contacts")
        val profiles = snapshot.getJSONArray("private_player_profiles")
        val contact = contacts.getJSONObject(0)

        assertEquals(8, snapshot.getInt("schema_version"))
        assertEquals(1, contacts.length())
        assertEquals(3, profiles.length())
        assertTrue(profiles.getJSONObject(0).has("public_player_id"))
        assertEquals(publicRegistrationId(queueId, 10), contact.getString("registration_id"))
        assertEquals(qqProfileId, contact.getString("profile_id"))
        assertEquals("12345678", contact.getString("qq_number"))
        assertTrue(snapshot.toString().contains(qqProfileId))
        assertTrue(snapshot.toString().contains(invalidQqProfileId))
        assertTrue(snapshot.toString().contains(inactiveProfileId))
        assertTrue(snapshot.toString().contains("87654321"))
        assertTrue(profiles.getJSONObject(1).isNull("qq_number"))
    }

    @Test
    fun shortLivedRegistrationEventsKeepTheirPrivateContactAfterLeavingTheQueue() {
        val profileId = "00000000-0000-0000-0000-000000000930"
        val contact = AuditPlayerContact(
            registrationKey = 21,
            profileId = profileId,
            qqNumber = "12345678"
        )
        val events = listOf(
            AuditLogEntry(
                id = "event-removed",
                timestampMillis = 2_000L,
                category = AuditLogCategory.MACHINE_A,
                title = "登记已退出排队",
                detail = "登记已退出排队。",
                queueId = queueId,
                publicEventType = PublicQueueEventType.REGISTRATION_REMOVED,
                affectedRegistrationKeys = listOf(21),
                affectedPlayerContacts = listOf(contact)
            ),
            AuditLogEntry(
                id = "event-added",
                timestampMillis = 1_000L,
                category = AuditLogCategory.MACHINE_A,
                title = "线上登记已创建",
                detail = "线上登记已加入等待顺序。",
                queueId = queueId,
                publicEventType = PublicQueueEventType.ONLINE_REGISTRATION_ADDED,
                affectedRegistrationKeys = listOf(21),
                affectedPlayerContacts = listOf(contact)
            )
        )

        val snapshot = buildQueueSyncSnapshot(
            state = state(),
            terminalId = "terminal-1",
            capturedAtMillis = 3_000L,
            auditLogs = events,
            playerProfiles = listOf(
                PlayerProfile(
                    id = profileId,
                    nickname = "线上玩家",
                    gender = PlayerGender.UNDISCLOSED,
                    defaultPreference = ProfilePlayPreference.OPEN_TO_JOIN,
                    qqNumber = "12345678"
                )
            )
        )

        assertEquals(2, snapshot.getJSONArray("recent_events").length())
        val contacts = snapshot.getJSONArray("private_player_contacts")
        assertEquals(1, contacts.length())
        assertEquals(
            publicRegistrationId(queueId, 21),
            contacts.getJSONObject(0).getString("registration_id")
        )
        assertEquals("12345678", contacts.getJSONObject(0).getString("qq_number"))
    }

    @Test
    fun publicRegistrationIdIsStableWithinQueueAndChangesForNewQueue() {
        val first = publicRegistrationId(queueId, 12)

        assertEquals(first, publicRegistrationId(queueId, 12))
        assertEquals(24, first.length)
        assertNotEquals(
            first,
            publicRegistrationId("00000000-0000-0000-0000-000000000100", 12)
        )
    }

    @Test
    fun malformedLegacyProfilesCannotBlockTheQueueSnapshot() {
        val validId = "00000000-0000-0000-0000-000000000920"
        val profiles = playerProfilesForCloudSync(
            listOf(
                PlayerProfile(
                    id = validId,
                    nickname = "  一二三四五六七八九十一二三四五六七八九十  ",
                    gender = PlayerGender.UNDISCLOSED,
                    defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
                    qqNumber = "1234",
                    usageCount = -1,
                    lastUsedAtMillis = 0L,
                    createdAtMillis = 0L,
                    updatedAtMillis = 0L
                ),
                PlayerProfile(
                    id = "旧版资料编号",
                    nickname = "旧资料",
                    gender = PlayerGender.UNDISCLOSED,
                    defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
                    qqNumber = "12345678",
                    createdAtMillis = 100L,
                    updatedAtMillis = 100L
                )
            )
        )
        val profile = profiles.single()

        assertEquals(validId, profile.id)
        assertEquals(18, profile.nickname.codePointCount(0, profile.nickname.length))
        assertEquals(null, profile.qqNumber)
        assertEquals(0, profile.usageCount)
        assertEquals(null, profile.lastUsedAtMillis)
        assertEquals(1L, profile.createdAtMillis)
        assertEquals(1L, profile.updatedAtMillis)
    }

    @Test
    fun ambiguousLegacyProfileBindingsAreNotSentToTheCloud() {
        val firstId = "00000000-0000-0000-0000-000000000921"
        val secondId = "00000000-0000-0000-0000-000000000922"
        val profiles = playerProfilesForCloudSync(
            listOf(
                PlayerProfile(
                    id = firstId,
                    nickname = "同名玩家",
                    gender = PlayerGender.UNDISCLOSED,
                    defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
                    qqNumber = "12345678",
                    createdAtMillis = 100L,
                    updatedAtMillis = 100L
                ),
                PlayerProfile(
                    id = secondId,
                    nickname = "同名玩家",
                    gender = PlayerGender.UNDISCLOSED,
                    defaultPreference = ProfilePlayPreference.ASK_EVERY_TIME,
                    qqNumber = "87654321",
                    createdAtMillis = 200L,
                    updatedAtMillis = 200L
                )
            )
        )

        assertEquals(listOf(secondId), profiles.map(PlayerProfile::id))
    }

    @Test
    fun recordOfHavingSeenTheExplanationIsUploadedOnlyOnceItIsSet() {
        fun uploaded(seen: Boolean) = buildQueueSyncSnapshot(
            state = state(),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L,
            playerProfiles = listOf(
                PlayerProfile(
                    id = "00000000-0000-0000-0000-000000000111",
                    nickname = "测试玩家",
                    gender = PlayerGender.UNDISCLOSED,
                    defaultPreference = ProfilePlayPreference.SOLO,
                    createdAtMillis = 100L,
                    updatedAtMillis = 100L,
                    pairedMachineQueueingIntroduced = seen
                )
            ),
            schemaVersion = 7,
            venueId = "00000000-0000-0000-0000-000000000222",
            terminalName = "入口终端"
        ).getJSONArray("private_player_profiles").getJSONObject(0)

        assertTrue(uploaded(seen = true).getBoolean("paired_machine_queueing_introduced"))
        // Left out while it is not set, so that an upload can never clear it on the server.
        assertFalse(uploaded(seen = false).has("paired_machine_queueing_introduced"))
    }

    @Test
    fun registrationThatGaveUpItsPlaceForThisRoundIsPublishedWithoutAPreview() {
        val nowMillis = 1_000_000L
        val pairedState = pairedState(
            machineA = MachineQueue(
                playing = listOf(registration(1, "机台甲玩家")),
                waiting = listOf(
                    registration(3, "被调整的玩家").copy(
                        isTemporary = false,
                        alsoQueuesAtPairedMachine = true,
                        skipsPairedMachineThisRound = true
                    ),
                    registration(4, "只排一台玩家")
                ),
                playingStartedAtMillis = nowMillis
            ),
            machineB = MachineQueue(
                playing = listOf(registration(2, "机台乙玩家")),
                playingStartedAtMillis = nowMillis - 6 * 60_000L
            ),
            nowMillis = nowMillis
        )
        // It holds no place at machine B, although the player's choice is still on.
        assertTrue(pairedState.machine(MachineId.B).pairedOrder.isEmpty())

        val machines = buildPublicQueueSnapshot(
            state = pairedState,
            terminalId = "terminal-1",
            capturedAtMillis = nowMillis,
            displaySettings = pairedDisplaySettings
        ).getJSONObject("machines")
        val waitingA = machines.getJSONObject("A").getJSONArray("waiting_positions")
        val moved = waitingA.getJSONObject(0).getJSONArray("registrations").getJSONObject(0)
        assertTrue(moved.getBoolean("also_queues_at_paired_machine"))
        assertTrue(moved.getBoolean("skips_paired_machine_this_round"))
        // The key is only sent when it is set.
        assertFalse(
            waitingA.getJSONObject(1).getJSONArray("registrations").getJSONObject(0)
                .has("skips_paired_machine_this_round")
        )
        // The server refuses a preview of such a registration, so none may be sent.
        assertTrue(machines.getJSONObject("B").isNull("paired_waiting_positions"))
        // It waits for its own machine only: 12 minutes until machine A ends its round.
        assertEquals(12L, waitingA.getJSONObject(0).getLong("estimated_wait_minutes"))
    }

    @Test
    fun pairedMachinesPublishPreviewPositionsAndJointEstimates() {
        val nowMillis = 1_000_000L
        val pairedState = pairedState(
            machineA = MachineQueue(
                playing = listOf(registration(1, "机台甲玩家")),
                waiting = listOf(
                    registration(3, "同时排队玩家").copy(isTemporary = false, alsoQueuesAtPairedMachine = true),
                    registration(4, "只排一台玩家")
                ),
                playingStartedAtMillis = nowMillis
            ),
            machineB = MachineQueue(
                playing = listOf(registration(2, "机台乙玩家")),
                playingStartedAtMillis = nowMillis - 6 * 60_000L
            ),
            nowMillis = nowMillis,
            // A preview has announced the arrangement for seven minutes: five more to go.
            pairedArrangements = mapOf(
                3 to PairedArrangementRecord(previewSinceMillis = nowMillis - 7 * 60_000L)
            )
        )
        assertEquals(listOf(3), pairedState.machine(MachineId.B).pairedOrder)

        val machines = buildPublicQueueSnapshot(
            state = pairedState,
            terminalId = "terminal-1",
            capturedAtMillis = nowMillis,
            displaySettings = pairedDisplaySettings
        ).getJSONObject("machines")
        val machineA = machines.getJSONObject("A")
        val machineB = machines.getJSONObject("B")
        val waitingA = machineA.getJSONArray("waiting_positions")
        val pairedRegistrationId = publicRegistrationId(queueId, 3)

        assertEquals("B", machineA.getString("paired_machine_id"))
        assertEquals("A", machineB.getString("paired_machine_id"))
        // The registration stays listed once, under the machine it is registered at.
        assertEquals(3, machineA.getInt("registration_count"))
        assertEquals(1, machineB.getInt("registration_count"))
        assertEquals(0, machineB.getJSONArray("waiting_positions").length())
        val pairedRegistration = waitingA.getJSONObject(0).getJSONArray("registrations")
            .getJSONObject(0)
        assertEquals(pairedRegistrationId, pairedRegistration.getString("registration_id"))
        assertTrue(pairedRegistration.getBoolean("also_queues_at_paired_machine"))
        assertTrue(pairedRegistration.getBoolean("paired_arrangement_announced"))
        assertEquals(
            nowMillis + 5 * 60_000L,
            pairedRegistration.getLong("paired_arrangement_not_before")
        )
        val plainRegistration = waitingA.getJSONObject(1).getJSONArray("registrations")
            .getJSONObject(0)
        assertFalse(plainRegistration.getBoolean("also_queues_at_paired_machine"))
        assertFalse(plainRegistration.has("paired_arrangement_announced"))
        assertFalse(plainRegistration.has("paired_arrangement_not_before"))
        // Machine B ends its round in 6 minutes, by which time the announcement has run long
        // enough; machine A ends in 12. The registration is expected on B, and the one behind
        // it moves up on A.
        assertEquals(6L, waitingA.getJSONObject(0).getLong("estimated_wait_minutes"))
        assertEquals(12L, waitingA.getJSONObject(1).getLong("estimated_wait_minutes"))
        assertTrue(machineA.isNull("paired_waiting_positions"))

        val previewPositions = machineB.getJSONArray("paired_waiting_positions")
        assertEquals(1, previewPositions.length())
        val previewPosition = previewPositions.getJSONObject(0)
        assertEquals(1, previewPosition.getInt("index"))
        assertEquals(publicPositionId(queueId, setOf(3)), previewPosition.getString("position_id"))
        assertEquals(6L, previewPosition.getLong("estimated_wait_minutes"))
        assertTrue(previewPosition.isNull("common_play_preview"))
        val previewRegistrations = previewPosition.getJSONArray("registrations")
        assertEquals(1, previewRegistrations.length())
        assertEquals(
            pairedRegistrationId,
            previewRegistrations.getJSONObject(0).getString("registration_id")
        )
        assertTrue(previewRegistrations.getJSONObject(0).getBoolean("paired_place_preview"))
        // A reference only: the registration's details are not repeated.
        assertEquals(2, previewRegistrations.getJSONObject(0).length())

        // A new registration waits behind the place held on B, and behind both on A. It is told
        // the middle value: a round lasts 10 to 15 minutes, 12 most often, about 12.3 in the
        // middle, so B's remaining 6.3 and one more round give about 18.5 minutes.
        assertEquals(19L, machineB.getLong("new_registration_estimated_wait_minutes"))
        // On A: the round now playing and registration 4's, about 12.3 each in the middle.
        assertEquals(25L, machineA.getLong("new_registration_estimated_wait_minutes"))
    }

    @Test
    fun registrationWithoutAnAnnouncementIsNotPreviewedAtTheOtherMachine() {
        val nowMillis = 1_000_000L
        fun machines(pairedArrangements: Map<Int, PairedArrangementRecord>) = buildPublicQueueSnapshot(
            state = pairedState(
                machineA = MachineQueue(
                    playing = listOf(registration(1, "机台甲玩家")),
                    waiting = listOf(
                        registration(3, "资料玩家")
                            .copy(isTemporary = false, alsoQueuesAtPairedMachine = true)
                    ),
                    playingStartedAtMillis = nowMillis
                ),
                machineB = MachineQueue(
                    playing = listOf(registration(2, "机台乙玩家")),
                    playingStartedAtMillis = nowMillis - 6 * 60_000L
                ),
                nowMillis = nowMillis,
                pairedArrangements = pairedArrangements
            ),
            terminalId = "terminal-1",
            capturedAtMillis = nowMillis,
            displaySettings = pairedDisplaySettings
        ).getJSONObject("machines")

        // Nothing has been announced: the registration is expected at its own machine only.
        val unannounced = machines(emptyMap())
        assertTrue(unannounced.getJSONObject("B").isNull("paired_waiting_positions"))
        val waiting = unannounced.getJSONObject("A").getJSONArray("waiting_positions")
            .getJSONObject(0)
        assertEquals(12L, waiting.getLong("estimated_wait_minutes"))
        val registration = waiting.getJSONArray("registrations").getJSONObject(0)
        assertTrue(registration.getBoolean("also_queues_at_paired_machine"))
        assertFalse(registration.has("paired_arrangement_announced"))

        // The count is held: the preview is published, but no time is promised.
        val held = machines(
            mapOf(
                3 to PairedArrangementRecord(
                    previewSinceMillis = nowMillis - 7 * 60_000L,
                    previewPausedAtMillis = nowMillis - 60_000L
                )
            )
        ).getJSONObject("A").getJSONArray("waiting_positions").getJSONObject(0)
            .getJSONArray("registrations").getJSONObject(0)
        assertTrue(held.getBoolean("paired_arrangement_announced"))
        assertFalse(held.has("paired_arrangement_not_before"))
    }

    @Test
    fun snapshotReportsThePairSettingOnlyWhenTheTerminalProvidesIt() {
        val stableA = "1".repeat(32)
        val stableB = "3".repeat(32)
        val reported = buildPublicQueueSnapshot(
            state = state(),
            terminalId = "terminal-1",
            capturedAtMillis = 1_000L,
            displaySettings = QueuePublicDisplaySettings(
                pairedMachineQueueSettings = PairedMachineQueueConfiguration(
                    enabled = false,
                    pairs = listOf(stableA to stableB)
                )
            )
        ).getJSONObject("paired_machine_queue_settings")

        assertFalse(reported.getBoolean("enabled"))
        assertEquals(1, reported.getJSONArray("pairs").length())
        assertEquals(stableA, reported.getJSONArray("pairs").getJSONArray(0).getString(0))
        assertEquals(stableB, reported.getJSONArray("pairs").getJSONArray(0).getString(1))
        assertFalse(
            buildPublicQueueSnapshot(state(), "terminal-1", 1_000L)
                .has("paired_machine_queue_settings")
        )
    }

    @Test
    fun machinesWithoutPairPublishNoPairedFields() {
        val nowMillis = 1_000_000L
        val unpaired = state(
            machineA = MachineQueue(
                playing = listOf(registration(1, "机台甲玩家")),
                waiting = listOf(registration(3, "等待玩家")),
                playingStartedAtMillis = nowMillis - 5 * 60_000L
            )
        )
        val machineA = buildPublicQueueSnapshot(unpaired, "terminal-1", nowMillis)
            .getJSONObject("machines").getJSONObject("A")

        assertTrue(machineA.isNull("paired_machine_id"))
        assertTrue(machineA.isNull("paired_waiting_positions"))
        val waiting = machineA.getJSONArray("waiting_positions").getJSONObject(0)
        assertEquals(7L, waiting.getLong("estimated_wait_minutes"))
        assertFalse(
            waiting.getJSONArray("registrations").getJSONObject(0)
                .getBoolean("also_queues_at_paired_machine")
        )
    }

    @Test
    fun stoppedMachineOfPairShowsNoPreviewAndNoEstimate() {
        val nowMillis = 1_000_000L
        val pairedState = pairedState(
            machineA = MachineQueue(
                playing = listOf(registration(1, "机台甲玩家")),
                waiting = listOf(
                    registration(3, "同时排队玩家").copy(isTemporary = false, alsoQueuesAtPairedMachine = true)
                ),
                playingStartedAtMillis = nowMillis
            ),
            machineB = MachineQueue(waiting = listOf(registration(2, "机台乙玩家"))),
            machineBStatus = MachineStatus().stop(MachineStopReason.MAINTENANCE, nowMillis),
            nowMillis = nowMillis
        )
        val machines = buildPublicQueueSnapshot(
            state = pairedState,
            terminalId = "terminal-1",
            capturedAtMillis = nowMillis,
            displaySettings = pairedDisplaySettings
        ).getJSONObject("machines")
        val machineA = machines.getJSONObject("A")
        val machineB = machines.getJSONObject("B")

        assertEquals("A", machineB.getString("paired_machine_id"))
        assertTrue(machineB.isNull("paired_waiting_positions"))
        assertTrue(machineB.isNull("new_registration_estimated_wait_minutes"))
        assertTrue(
            machineB.getJSONArray("waiting_positions").getJSONObject(0)
                .isNull("estimated_wait_minutes")
        )
        // The stopped machine does not take the registration; it waits for its own machine.
        assertEquals(
            12L,
            machineA.getJSONArray("waiting_positions").getJSONObject(0)
                .getLong("estimated_wait_minutes")
        )
    }

    private val pairedDisplaySettings = QueuePublicDisplaySettings(
        machinePartners = mapOf(MachineId.A to MachineId.B, MachineId.B to MachineId.A)
    )

    /** A two-machine state in which A and B form a pair, settled as the engine leaves it. */
    private fun pairedState(
        machineA: MachineQueue,
        machineB: MachineQueue,
        machineBStatus: MachineStatus = MachineStatus(),
        nowMillis: Long,
        pairedArrangements: Map<Int, PairedArrangementRecord> = emptyMap()
    ): PersistedQueueState {
        val settled = QueueEngine.settlePairedMachines(
            state = QueueEngineState(linkedMapOf("A" to machineA, "B" to machineB)),
            policy = QueueEnginePolicy(
                machineStatuses = mapOf("A" to MachineStatus(), "B" to machineBStatus),
                machinePartners = mapOf("A" to "B", "B" to "A")
            ),
            atMillis = nowMillis
        )
        return PersistedQueueState(
            queueId = queueId,
            revision = 9L,
            machines = linkedMapOf(
                MachineId.A to PersistedMachineState(
                    queue = settled.queue("A")!!,
                    status = MachineStatus(),
                    pairedOrder = settled.pairedMachineOrders["A"].orEmpty()
                ),
                MachineId.B to PersistedMachineState(
                    queue = settled.queue("B")!!,
                    status = machineBStatus,
                    pairedOrder = settled.pairedMachineOrders["B"].orEmpty()
                )
            ),
            registrationOpen = true,
            nextRegistrationKey = 20,
            savedAtMillis = 900L,
            pairedArrangements = pairedArrangements
        )
    }

    private fun state(
        machineA: MachineQueue = MachineQueue(),
        machineB: MachineQueue = MachineQueue()
    ) = PersistedQueueState(
        queueId = queueId,
        revision = 9L,
        machineA = machineA,
        machineB = machineB,
        machineAStatus = MachineStatus(),
        machineBStatus = MachineStatus(),
        registrationOpen = true,
        nextRegistrationKey = 20,
        savedAtMillis = 900L
    )

    private fun registration(key: Int, displayId: String) = Registration(
        key = key,
        displayId = displayId,
        preference = PlayPreference.SOLO,
        createdAtMillis = 100L + key
    )
}
