package com.abcccc.maimaiqueue

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
            )
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

    private fun validLogJson() = JSONObject()
        .put("id", "legacy-event")
        .put("timestampMillis", 1_000L)
        .put("category", "MACHINE_A")
        .put("title", "旧日志")
        .put("detail", "测试。")
        .put("source", "ON_SITE_TERMINAL")
        .put("affectedRegistrationKeys", JSONArray().put(1))
}
