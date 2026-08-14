package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSchemaNegotiatorTest {
    private val firstConfiguration = QueueConnectionConfiguration(
        endpoint = "https://example.com/api/queue-status",
        token = "a".repeat(32)
    )

    @Test
    fun explicitVersionFailureDowngradesOnlyOnce() {
        val negotiator = QueueSchemaNegotiator()

        assertEquals(8, negotiator.versionFor(firstConfiguration, currentSchemaAllowed = true))
        assertTrue(negotiator.downgrade(firstConfiguration, currentSchemaAllowed = true))
        assertEquals(7, negotiator.versionFor(firstConfiguration, currentSchemaAllowed = true))
        assertFalse(negotiator.downgrade(firstConfiguration, currentSchemaAllowed = true))
    }

    @Test
    fun changingConnectionProbesCurrentVersionAgain() {
        val negotiator = QueueSchemaNegotiator()
        negotiator.downgrade(firstConfiguration, currentSchemaAllowed = true)
        val changed = firstConfiguration.copy(token = "b".repeat(32))

        assertEquals(8, negotiator.versionFor(changed, currentSchemaAllowed = true))
    }

    @Test
    fun delayedLegacyRequestNeverProbesSchemaEight() {
        val negotiator = QueueSchemaNegotiator(initialVersion = 7)

        assertEquals(7, negotiator.versionFor(firstConfiguration, currentSchemaAllowed = true))
        assertFalse(negotiator.downgrade(firstConfiguration, currentSchemaAllowed = true))
        assertEquals(
            7,
            negotiator.versionFor(
                firstConfiguration.copy(token = "b".repeat(32)),
                currentSchemaAllowed = true
            )
        )
    }

    @Test
    fun missingVenueIdentityUsesLegacySchemaWithoutARejectedProbe() {
        val negotiator = QueueSchemaNegotiator()

        assertEquals(
            7,
            negotiator.versionFor(firstConfiguration, currentSchemaAllowed = false)
        )
        assertFalse(
            negotiator.downgrade(firstConfiguration, currentSchemaAllowed = false)
        )
    }

    @Test
    fun obtainingVenueIdentityProbesCurrentSchemaAgain() {
        val negotiator = QueueSchemaNegotiator()

        assertEquals(
            7,
            negotiator.versionFor(firstConfiguration, currentSchemaAllowed = false)
        )
        assertEquals(
            8,
            negotiator.versionFor(firstConfiguration, currentSchemaAllowed = true)
        )
    }

    @Test
    fun losingVenueIdentityDropsBackToLegacySchema() {
        val negotiator = QueueSchemaNegotiator()

        assertEquals(
            8,
            negotiator.versionFor(firstConfiguration, currentSchemaAllowed = true)
        )
        assertEquals(
            7,
            negotiator.versionFor(firstConfiguration, currentSchemaAllowed = false)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun negotiatorRejectsAnUnknownInitialVersion() {
        QueueSchemaNegotiator(initialVersion = 6)
    }

    @Test
    fun ordinaryBadRequestIsNotAProtocolFailure() {
        assertTrue(
            isUnsupportedQueueSchemaVersion(
                QueueEndpointException(400, "不支持的队列数据版本")
            )
        )
        assertFalse(
            isUnsupportedQueueSchemaVersion(
                QueueEndpointException(400, "系统事件不能包含机台状态")
            )
        )
        assertFalse(
            isUnsupportedQueueSchemaVersion(
                QueueEndpointException(409, "不支持的队列数据版本")
            )
        )
    }

    @Test
    fun venueHeaderIsOnlyGeneratedForSchemaEight() {
        val venueId = "00000000-0000-0000-0000-000000000222"

        assertEquals(venueId, queueVenueHeaderValue(8, venueId))
        assertNull(queueVenueHeaderValue(7, venueId))
        assertNull(queueVenueHeaderValue(8, null))
        assertNull(queueVenueHeaderValue(8, "  "))
    }

    @Test
    fun venueHeaderValueIsTrimmedBeforeSending() {
        val venueId = "00000000-0000-0000-0000-000000000222"

        assertEquals(venueId, queueVenueHeaderValue(8, "  $venueId  "))
    }

    @Test
    fun delayedCloseUsesLegacySchemaOnlyWithoutATrustedVenue() {
        assertEquals(7, pendingSyncDisableSchemaVersion(null))
        assertEquals(7, pendingSyncDisableSchemaVersion("  "))
        assertEquals(8, pendingSyncDisableSchemaVersion("venue-id"))
    }
}
