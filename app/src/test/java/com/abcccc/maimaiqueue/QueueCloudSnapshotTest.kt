package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCloudSnapshotTest {
    private val queueId = "00000000-0000-0000-0000-000000000099"

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

        assertEquals("入口侧 · 机台 A", machines.getJSONObject("A").getString("name"))
        assertEquals("墙侧 · 机台 B", machines.getJSONObject("B").getString("name"))
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
    fun publicSnapshotPublishesExactlyOneToFourConfiguredMachines() {
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
                assertEquals("区域${machineId.name} · 机台 ${machineId.name}", machine.getString("name"))
                assertEquals(1, machine.getInt("registration_count"))
            }
        }
    }

    @Test
    fun publicEventsKeepMachineCAndDMappings() {
        val events = listOf(
            AuditLogEntry(
                id = "00000000-0000-0000-0000-000000000701",
                timestampMillis = 1_000L,
                category = AuditLogCategory.MACHINE_C,
                title = "机台 C · 新增登记",
                detail = "已加入机台 C。",
                queueId = queueId,
                publicEventType = PublicQueueEventType.REGISTRATION_ADDED
            ),
            AuditLogEntry(
                id = "00000000-0000-0000-0000-000000000702",
                timestampMillis = 2_000L,
                category = AuditLogCategory.MACHINE_D,
                title = "机台 D · 机台停止使用",
                detail = "机台 D 已停止使用。",
                queueId = queueId,
                publicEventType = PublicQueueEventType.MACHINE_STOPPED
            )
        )
        val state = PersistedQueueState(
            queueId = queueId,
            revision = 9L,
            machines = configuredMachineIds(4).associateWith { PersistedMachineState() },
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

        assertEquals("D", published.getJSONObject(0).getString("machine_id"))
        assertEquals("C", published.getJSONObject(1).getString("machine_id"))
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

        assertEquals(5, snapshot.getInt("schema_version"))
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

        assertEquals(5, snapshot.getInt("schema_version"))
        assertEquals(1, contacts.length())
        assertEquals(3, profiles.length())
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
