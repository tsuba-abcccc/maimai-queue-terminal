package com.abcccc.maimaiqueue

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAuditLogPersistenceTest {
    @Test
    fun notificationCategoriesSurviveLocalPersistence() {
        val entry = AuditLogEntry(
            id = "event-1",
            timestampMillis = 1_000L,
            category = AuditLogCategory.MACHINE_A,
            title = "机台 A · 游玩位置已更新",
            detail = "测试。",
            source = AuditLogSource.SYSTEM_AUTOMATIC,
            queueId = "queue-1",
            publicEventType = PublicQueueEventType.PLAYING_CHANGED,
            notificationCategories = setOf(
                PublicQueueNotificationCategory.PLAYING_POSITION,
                PublicQueueNotificationCategory.ONLINE_CHECK_IN,
                PublicQueueNotificationCategory.QUEUE_CHANGES
            ),
            affectedRegistrationKeys = listOf(1, 2),
            affectedPlayerContacts = listOf(
                AuditPlayerContact(
                    registrationKey = 1,
                    profileId = "00000000-0000-0000-0000-000000000901",
                    qqNumber = "12345678"
                )
            ),
            machineStableId = "10000000000000000000000000000001",
            machineName = "入口侧 · 机台 A"
        )

        assertEquals(listOf(entry), deserializeAuditLogs(serializeAuditLogs(listOf(entry))))
    }

    @Test
    fun unknownNotificationCategoriesAreIgnored() {
        val serialized = JSONArray().put(
            validLogJson().put(
                "notificationCategories",
                JSONArray()
                    .put("ABSENCE")
                    .put("FUTURE_CATEGORY")
            )
        ).toString()

        assertEquals(
            setOf(PublicQueueNotificationCategory.ABSENCE),
            deserializeAuditLogs(serialized).single().notificationCategories
        )
    }

    @Test
    fun legacyLogDerivesItsNotificationCategoryFromTheEventType() {
        val serialized = JSONArray().put(
            validLogJson().put("publicEventType", "ONLINE_CHECK_IN_TIMED_OUT")
        ).toString()

        assertEquals(
            setOf(PublicQueueNotificationCategory.ONLINE_CHECK_IN),
            deserializeAuditLogs(serialized).single().notificationCategories
        )
    }

    @Test
    fun legacyLogWithoutMachineIdentityRemainsReadable() {
        val entry = deserializeAuditLogs(JSONArray().put(validLogJson()).toString()).single()

        assertEquals(null, entry.machineStableId)
        assertEquals(null, entry.machineName)
        assertEquals(
            AuditLogEntry(
                id = "legacy-event",
                timestampMillis = 1_000L,
                category = AuditLogCategory.MACHINE_A,
                title = "旧日志",
                detail = "测试。",
                affectedRegistrationKeys = listOf(1)
            ),
            entry
        )
    }

    @Test
    fun jsonNullMachineIdentityDoesNotBecomeLiteralNullText() {
        val serialized = JSONArray().put(
            validLogJson()
                .put("category", "SYSTEM")
                .put("machineStableId", JSONObject.NULL)
                .put("machineName", JSONObject.NULL)
        ).toString()

        val entry = deserializeAuditLogs(serialized).single()

        assertEquals(null, entry.machineStableId)
        assertEquals(null, entry.machineName)
    }

    @Test
    fun legacyLiteralNullAndUnexpectedSystemMachineIdentityAreMigratedAway() {
        val serialized = JSONArray()
            .put(
                validLogJson()
                    .put("id", "system-null-event")
                    .put("category", "SYSTEM")
                    .put("machineName", "null")
            )
            .put(
                validLogJson()
                    .put("id", "profile-dirty-event")
                    .put("category", "PLAYER_PROFILE")
                    .put("machineStableId", "10000000000000000000000000000001")
                    .put("machineName", "入口侧 · 机台 A")
            )
            .toString()

        val entries = deserializeAuditLogs(serialized)
        val canonical = serializeAuditLogs(entries)

        assertTrue(entries.all { it.machineStableId == null && it.machineName == null })
        assertTrue(canonical.contains("\"machineName\":null"))
        assertTrue(!canonical.contains("\"machineName\":\"null\""))
        assertTrue(!canonical.contains("入口侧 · 机台 A"))
    }

    @Test
    fun machineEventStillKeepsItsMachineIdentity() {
        val serialized = JSONArray().put(
            validLogJson()
                .put("machineStableId", "10000000000000000000000000000001")
                .put("machineName", "入口侧 · 机台 A")
        ).toString()

        val entry = deserializeAuditLogs(serialized).single()

        assertEquals("10000000000000000000000000000001", entry.machineStableId)
        assertEquals("入口侧 · 机台 A", entry.machineName)
    }

    private fun validLogJson() = JSONObject()
        .put("id", "legacy-event")
        .put("timestampMillis", 1_000L)
        .put("category", "MACHINE_A")
        .put("title", "旧日志")
        .put("detail", "测试。")
        .put("source", "ON_SITE_TERMINAL")
        .put("affectedRegistrationKeys", JSONArray().put(1))
}
