package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRuleSettingsTest {
    @Test
    fun capacityAndMachineCountAreRiskSensitiveButMetadataIsNot() {
        val original = QueueRuleSettings()

        assertTrue(
            hasRiskSensitiveMachineConfigurationChange(
                original,
                original.copy(configuredMachineCount = 3)
            )
        )
        assertTrue(
            hasRiskSensitiveMachineConfigurationChange(
                original,
                original.copy(
                    machineConfigurations = original.machineConfigurations +
                        (MachineId.A to original.machineConfiguration(MachineId.A).copy(capacity = 1))
                )
            )
        )
        assertFalse(
            hasRiskSensitiveMachineConfigurationChange(
                original,
                original.copy(
                    machineConfigurations = original.machineConfigurations +
                        (MachineId.A to original.machineConfiguration(MachineId.A).copy(remark = "窗口侧"))
                )
            )
        )
    }

    @Test
    fun machineConfigurationRevisionOnlyAdvancesForRiskSensitiveChanges() {
        val original = QueueRuleSettings(machineConfigurationRevision = 7L)
        val ruleOnly = original.copy(allowTemporaryLeave = false)
        val metadataChange = original.copy(
            machineConfigurations = original.machineConfigurations +
                (MachineId.A to original.machineConfiguration(MachineId.A).copy(gameVersion = "1.50"))
        )
        val capacityChange = original.copy(
            machineConfigurations = original.machineConfigurations +
                (MachineId.A to original.machineConfiguration(MachineId.A).copy(capacity = 1))
        )
        val machineCountChange = original.copy(configuredMachineCount = 3)

        assertEquals(7L, withUpdatedMachineConfigurationRevision(original, ruleOnly).machineConfigurationRevision)
        assertEquals(7L, withUpdatedMachineConfigurationRevision(original, metadataChange).machineConfigurationRevision)
        assertEquals(8L, withUpdatedMachineConfigurationRevision(original, capacityChange).machineConfigurationRevision)
        assertEquals(8L, withUpdatedMachineConfigurationRevision(original, machineCountChange).machineConfigurationRevision)
    }

    @Test
    fun machineConfigurationLogDescriptionsPreserveEveryChangedField() {
        val previous = MachineConfiguration(remark = "左侧")
        val updated = MachineConfiguration(
            remark = "入口侧",
            gameType = MachineGameType.CHUNITHM,
            server = MachineServer.CHINA,
            gameVersion = "NEW!!",
            showGameVersion = true,
            capacity = 1,
            soloRoundMinutes = 10,
            sharedRoundMinutes = 14
        )

        assertEquals(
            listOf(
                "机台 A 备注改为“入口侧”",
                "机台 A 类型改为“中二节奏”",
                "机台 A 服务器改为“中国”",
                "机台 A 游戏版本显示为“NEW!!”",
                "机台 A 游玩容量改为 1 人",
                "机台 A 计划游玩时间改为单人 10 分钟、共同游玩 14 分钟"
            ),
            machineConfigurationChangeDescriptions(MachineId.A, previous, updated)
        )
    }
    @Test
    fun queueRuleSettingsUseStableDefaultMachineRemarks() {
        val settings = QueueRuleSettings()

        assertEquals(true, settings.websiteSyncEnabled)
        assertEquals(true, settings.allowOnlineRegistration)
        assertEquals(true, settings.showCommonPlayPreview)
        assertEquals(2, settings.configuredMachineCount)
        assertEquals(listOf(MachineId.A, MachineId.B), settings.configuredMachineIds)
        assertEquals("左侧", settings.machineARemark)
        assertEquals("右侧", settings.machineBRemark)
        assertEquals("中间左侧", settings.machineRemark(MachineId.C))
        assertEquals("中间右侧", settings.machineRemark(MachineId.D))
        MachineId.entries.forEach { machineId ->
            val configuration = settings.machineConfiguration(machineId)
            assertEquals(MachineGameType.MAIMAI_DX, configuration.gameType)
            assertEquals(MachineServer.HIDDEN, configuration.server)
            assertEquals(2, configuration.capacity)
            assertEquals(12, configuration.soloRoundMinutes)
            assertEquals(15, configuration.sharedRoundMinutes)
        }
        assertEquals(1L, settings.machineConfigurationRevision)
    }

    @Test
    fun configuredMachineIdsAreAlwaysContiguousFromAThroughJ() {
        assertEquals(listOf(MachineId.A), configuredMachineIds(0))
        assertEquals(listOf(MachineId.A), configuredMachineIds(1))
        assertEquals(listOf(MachineId.A, MachineId.B), configuredMachineIds(2))
        assertEquals(listOf(MachineId.A, MachineId.B, MachineId.C), configuredMachineIds(3))
        assertEquals(listOf(MachineId.A, MachineId.B, MachineId.C, MachineId.D), configuredMachineIds(4))
        assertEquals(MachineId.entries.take(5), configuredMachineIds(5))
        assertEquals(MachineId.entries, configuredMachineIds(10))
        assertEquals(MachineId.entries, configuredMachineIds(11))
    }

    @Test
    fun legacySettingsNormalizeToStableMachinesAndOneDefaultGroup() {
        val settings = normalizeMachineLayoutSettings(
            QueueRuleSettings(
                configuredMachineCount = 4,
                machineStableIds = emptyMap(),
                machineGroupAssignments = emptyMap(),
                machineGroups = emptyList(),
                defaultMachineGroupId = ""
            )
        )

        assertEquals(MachineId.entries.take(4).map(::defaultMachineStableId), settings.configuredMachineIds.map(settings::machineStableId))
        assertEquals(DEFAULT_MACHINE_GROUPS, settings.configuredMachineGroups)
        assertEquals(DEFAULT_MACHINE_GROUP_ID, settings.defaultMachineGroupId)
        assertTrue(settings.configuredMachineIds.all { settings.machineGroupId(it) == DEFAULT_MACHINE_GROUP_ID })
    }

    @Test
    fun addingMachinesAppendsFreshStableIdentitiesUpToJ() {
        var settings = QueueRuleSettings(configuredMachineCount = 1)

        repeat(9) {
            settings = appendMachineConfiguration(settings)!!
        }

        assertEquals(10, settings.configuredMachineCount)
        assertEquals(MachineId.entries, settings.configuredMachineIds)
        assertEquals(10, settings.configuredMachineIds.map(settings::machineStableId).toSet().size)
        assertNull(appendMachineConfiguration(settings))
    }

    @Test
    fun removingMiddleMachineShiftsConfigurationIdentityAndGroupTogether() {
        val secondGroup = MachineGroupConfiguration(
            id = "10000000000000000000000000000002",
            name = "楼上"
        )
        val original = normalizeMachineLayoutSettings(
            QueueRuleSettings(
                configuredMachineCount = 4,
                machineConfigurations = DEFAULT_MACHINE_CONFIGURATIONS +
                    (MachineId.C to MachineConfiguration(remark = "后侧", capacity = 1)),
                machineStableIds = DEFAULT_MACHINE_STABLE_IDS +
                    (MachineId.C to "20000000000000000000000000000003"),
                machineGroups = DEFAULT_MACHINE_GROUPS + secondGroup,
                machineGroupAssignments = DEFAULT_MACHINE_GROUP_ASSIGNMENTS +
                    (MachineId.C to secondGroup.id)
            )
        )

        val removed = removeMachineConfiguration(original, MachineId.B)!!

        assertEquals(3, removed.configuredMachineCount)
        assertEquals("后侧", removed.machineRemark(MachineId.B))
        assertEquals(1, removed.machineConfiguration(MachineId.B).capacity)
        assertEquals("20000000000000000000000000000003", removed.machineStableId(MachineId.B))
        assertEquals(secondGroup.id, removed.machineGroupId(MachineId.B))
        assertEquals(original.machineStableId(MachineId.D), removed.machineStableId(MachineId.C))
    }

    @Test
    fun machineLayoutLogsDeletionAndReindexingWithoutInventingConfigurationChanges() {
        val original = QueueRuleSettings(
            configuredMachineCount = 4,
            machineConfigurations = DEFAULT_MACHINE_CONFIGURATIONS + mapOf(
                MachineId.B to MachineConfiguration(remark = "墙侧"),
                MachineId.C to MachineConfiguration(remark = "后侧", capacity = 1),
                MachineId.D to MachineConfiguration(remark = "窗侧")
            )
        )
        val removed = removeMachineConfiguration(original, MachineId.B)!!

        val descriptions = machineLayoutChangeDescriptions(original, removed)

        assertTrue(descriptions.contains("删除机台：原机台 B（墙侧）"))
        assertTrue(
            descriptions.contains(
                "后续机台编号已重排：原机台 C 改为机台 B、原机台 D 改为机台 C"
            )
        )
        assertFalse(descriptions.any { "备注改为" in it || "游玩容量改为" in it })
    }

    @Test
    fun machineLayoutLogsAddedMachinesAndEveryGroupChange() {
        val original = QueueRuleSettings(configuredMachineCount = 3)
        val grouped = createMachineGroupForMachine(original, MachineId.C, "二楼")
        val secondGroup = grouped.configuredMachineGroups.last()
        val updated = normalizeMachineLayoutSettings(
            grouped.copy(defaultMachineGroupId = secondGroup.id)
        )

        val groupDescriptions = machineLayoutChangeDescriptions(original, updated)

        assertTrue(groupDescriptions.contains("新增首页分组“二楼”"))
        assertTrue(groupDescriptions.contains("机台 C 移至首页分组“二楼”"))
        assertTrue(groupDescriptions.contains("本终端默认分组改为“二楼”"))

        val appended = appendMachineConfiguration(updated, secondGroup.id)!!
        val appendDescriptions = machineLayoutChangeDescriptions(updated, appended)
        assertEquals(1, appendDescriptions.count { it.startsWith("添加机台：") })
        assertTrue(appendDescriptions.single { it.startsWith("添加机台：") }.contains("机台 D"))
    }

    @Test
    fun machineGroupsRemainNonEmptyAndDefaultAlwaysReferencesAVisibleGroup() {
        val original = QueueRuleSettings(configuredMachineCount = 3)
        val withSecondGroup = createMachineGroupForMachine(original, MachineId.C, "二楼")
        val secondGroup = withSecondGroup.configuredMachineGroups.last()
        val defaulted = normalizeMachineLayoutSettings(
            withSecondGroup.copy(defaultMachineGroupId = secondGroup.id)
        )
        val movedBack = moveMachineToGroup(defaulted, MachineId.C, DEFAULT_MACHINE_GROUP_ID)

        assertEquals(listOf(DEFAULT_MACHINE_GROUP_ID), movedBack.configuredMachineGroups.map { it.id })
        assertEquals(DEFAULT_MACHINE_GROUP_ID, movedBack.defaultMachineGroupId)
        assertTrue(movedBack.configuredMachineGroups.all { movedBack.machinesInGroup(it.id).isNotEmpty() })
    }

    @Test
    fun groupChangesAreDisplayOnlyButStableSlotChangesAreRiskSensitive() {
        val original = QueueRuleSettings(configuredMachineCount = 3)
        val grouped = createMachineGroupForMachine(original, MachineId.C, "二楼")
        val swappedIdentity = original.copy(
            machineStableIds = original.machineStableIds +
                (MachineId.B to original.machineStableId(MachineId.C))
        )

        assertFalse(hasRiskSensitiveMachineConfigurationChange(original, grouped))
        assertTrue(hasRiskSensitiveMachineConfigurationChange(original, swappedIdentity))
    }

    @Test
    fun runtimeStatesFollowStableMachineIdentityAfterReindexing() {
        val original = QueueRuleSettings(configuredMachineCount = 3)
        val states = mapOf(
            MachineId.A to PersistedMachineState(),
            MachineId.B to PersistedMachineState(
                status = MachineStatus().stop(MachineStopReason.MAINTENANCE, atMillis = 100L)
            ),
            MachineId.C to PersistedMachineState(
                queue = MachineQueue(waiting = listOf(Registration(7, "青空", PlayPreference.SOLO)))
            )
        )
        val removed = removeMachineConfiguration(original, MachineId.A)!!

        val remapped = remapMachineStatesByStableIdentity(original, removed, states)

        assertFalse(remapped.getValue(MachineId.A).status.isOperational)
        assertEquals(listOf("青空"), remapped.getValue(MachineId.B).queue.waiting.map { it.displayId })
        assertEquals(setOf(MachineId.A, MachineId.B), remapped.keys)
    }

    @Test
    fun machineRemarkNormalizationTrimsAndFallsBackForLegacyMissingValues() {
        assertEquals("入口侧", normalizeMachineRemark("  入口侧  ", DEFAULT_MACHINE_A_REMARK))
        assertEquals("左侧", normalizeMachineRemark(null, DEFAULT_MACHINE_A_REMARK))
        assertEquals("左侧", normalizeMachineRemark("   ", DEFAULT_MACHINE_A_REMARK))
    }

    @Test
    fun machineRemarkLengthUsesVisibleUnicodeCharacters() {
        assertEquals(
            "一二三四五六七八",
            limitMachineRemarkLength("一二三四五六七八九")
        )
        assertEquals(
            "A区入口侧",
            limitMachineRemarkLength("A区入口侧")
        )
    }

    @Test
    fun machineConfigurationNormalizesCapacityTimesAndConditionalFields() {
        val configuration = normalizeMachineConfiguration(
            MachineId.A,
            MachineConfiguration(
                remark = "  入口侧  ",
                gameType = MachineGameType.TAIKO_NO_TATSUJIN,
                server = MachineServer.JAPAN,
                customServer = "不应保留",
                gameVersion = " 2026 夏季版 ",
                showGameVersion = true,
                capacity = 4,
                soloRoundMinutes = 0,
                sharedRoundMinutes = 999
            )
        )

        assertEquals("入口侧", configuration.remark)
        assertEquals(MachineServer.HIDDEN, configuration.server)
        assertEquals("", configuration.customServer)
        assertEquals("2026 夏季版", configuration.gameVersion)
        assertTrue(configuration.showGameVersion)
        assertEquals(2, configuration.capacity)
        assertEquals(MIN_PLANNED_ROUND_MINUTES, configuration.soloRoundMinutes)
        assertEquals(MAX_PLANNED_ROUND_MINUTES, configuration.sharedRoundMinutes)
    }

    @Test
    fun customMachineAndServerRequireUsableNames() {
        val configuration = normalizeMachineConfiguration(
            MachineId.B,
            MachineConfiguration(
                remark = "右侧",
                gameType = MachineGameType.OTHER,
                customGameType = "   ",
                server = MachineServer.OTHER,
                customServer = "   ",
                gameVersion = "   ",
                showGameVersion = true,
                capacity = 1
            )
        )

        assertEquals("其他", configuration.customGameType)
        assertEquals(MachineServer.HIDDEN, configuration.server)
        assertEquals("", configuration.customServer)
        assertFalse(configuration.showGameVersion)
        assertEquals(1, configuration.capacity)
    }

    @Test
    fun runtimeSettingsNormalizeEveryMachineAndRevision() {
        val settings = normalizeQueueRuleSettingsForRuntime(
            settings = QueueRuleSettings(
                configuredMachineCount = 99,
                machineConfigurations = mapOf(
                    MachineId.A to MachineConfiguration(
                        remark = "A 区",
                        capacity = 1,
                        soloRoundMinutes = 8
                    )
                ),
                machineConfigurationRevision = 0
            ),
            cloudSyncAvailable = false
        )

        assertEquals(MachineId.entries.size, settings.configuredMachineCount)
        assertEquals(1, settings.machineConfiguration(MachineId.A).capacity)
        assertEquals(8, settings.machineConfiguration(MachineId.A).soloRoundMinutes)
        assertEquals("右侧", settings.machineConfiguration(MachineId.B).remark)
        assertEquals(1L, settings.machineConfigurationRevision)
    }

    @Test
    fun queueSyncEndpointAcceptsSiteRootAndCanonicalEndpoint() {
        assertEquals(
            "https://example.com/api/queue-status",
            normalizeQueueSyncEndpoint(" https://example.com/ ")
        )
        assertEquals(
            "https://example.com/api/queue-status",
            normalizeQueueSyncEndpoint("https://example.com/api/queue-status/")
        )
        assertEquals(
            "https://example.com/custom/api/queue-status",
            normalizeQueueSyncEndpoint("https://example.com/custom/api/queue-status")
        )
    }

    @Test
    fun queueSyncEndpointRejectsUnsafeOrIncompleteAddresses() {
        assertNull(normalizeQueueSyncEndpoint("http://example.com"))
        assertNull(normalizeQueueSyncEndpoint("https://example.com/api/queue-status?debug=1"))
        assertNull(normalizeQueueSyncEndpoint("https:///api/queue-status"))
        assertNull(normalizeQueueSyncEndpoint("example.com"))
    }

    @Test
    fun queueSyncTokenUsesUtf8ByteMinimumAndCharacterMaximum() {
        assertFalse(isValidQueueSyncToken("a".repeat(31)))
        assertTrue(isValidQueueSyncToken("a".repeat(32)))
        assertTrue(isValidQueueSyncToken("中".repeat(11)))
        assertFalse(isValidQueueSyncToken("a".repeat(MAX_QUEUE_SYNC_TOKEN_CHARACTERS + 1)))
    }

    @Test
    fun runtimeSettingsNormalizeConnectionAndDisableInvalidSync() {
        val valid = normalizeQueueRuleSettingsForRuntime(
            settings = QueueRuleSettings(
                queueSyncEndpoint = "https://example.com/",
                queueSyncToken = "a".repeat(32)
            ),
            cloudSyncAvailable = true
        )
        assertEquals("https://example.com/api/queue-status", valid.queueSyncEndpoint)
        assertTrue(valid.websiteSyncEnabled)
        assertTrue(valid.oneBotSyncEnabled)

        val invalid = normalizeQueueRuleSettingsForRuntime(
            settings = QueueRuleSettings(
                queueSyncEndpoint = "https://example.com",
                queueSyncToken = "short"
            ),
            cloudSyncAvailable = true
        )
        assertFalse(invalid.websiteSyncEnabled)
        assertFalse(invalid.oneBotSyncEnabled)
    }

    @Test
    fun localRuntimeAlwaysClearsCloudConnection() {
        val settings = normalizeQueueRuleSettingsForRuntime(
            settings = QueueRuleSettings(
                queueSyncEndpoint = "https://example.com/api/queue-status",
                queueSyncToken = "a".repeat(32)
            ),
            cloudSyncAvailable = false
        )

        assertFalse(settings.websiteSyncEnabled)
        assertFalse(settings.oneBotSyncEnabled)
        assertEquals("", settings.queueSyncEndpoint)
        assertEquals("", settings.queueSyncToken)
    }
}
