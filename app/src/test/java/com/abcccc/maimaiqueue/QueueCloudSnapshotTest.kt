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
                machineARemark = "入口侧",
                machineBRemark = "墙侧",
                oneBotSyncEnabled = false,
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
        assertTrue(businessHours.getBoolean("enabled"))
        assertTrue(businessHours.getBoolean("outside"))
        assertFalse(businessHours.getBoolean("closing_soon"))
        assertTrue(businessHours.getBoolean("closing_grace"))
        assertTrue(businessHours.isNull("closes_at"))
        assertEquals(2_000L, businessHours.getLong("registration_closes_at"))
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
                machineAStatus = MachineStatus().stop(
                    MachineStopReason.OTHER,
                    800L,
                    "按钮失灵"
                ),
                machineBStatus = MachineStatus().stop(
                    MachineStopReason.MAINTENANCE,
                    900L
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

        assertEquals(3, snapshot.getInt("schema_version"))
        assertEquals(1, events.length())
        assertEquals("NO_SHOW_MOVED_TO_TAIL", events.getJSONObject(0).getString("type"))
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

        assertEquals(3, snapshot.getInt("schema_version"))
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
    fun publicRegistrationIdIsStableWithinQueueAndChangesForNewQueue() {
        val first = publicRegistrationId(queueId, 12)

        assertEquals(first, publicRegistrationId(queueId, 12))
        assertEquals(24, first.length)
        assertNotEquals(
            first,
            publicRegistrationId("00000000-0000-0000-0000-000000000100", 12)
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
}
