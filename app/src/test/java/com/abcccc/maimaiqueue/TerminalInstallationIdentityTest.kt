package com.abcccc.maimaiqueue

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInstallationIdentityTest {

    @Test
    fun `disable snapshot is captured only for a previously verified endpoint`() {
        val endpoint = "https://example.com/api/queue-status"
        val venueId = "00000000-0000-0000-0000-000000000101"
        val registered = TerminalInstallationIdentity(
            venueId = venueId,
            venueCode = "ABCDEFGH",
            venueName = "测试机厅",
            terminalName = "主终端",
            registrationState = TerminalInstallationRegistrationState.REGISTERED,
            onboardingCompleted = true,
            venueBindingServerEndpoint = endpoint,
            verifiedServerEndpoint = endpoint
        )

        val captured = pendingSyncDisableSnapshotForVerifiedEndpoint(
            current = registered,
            queueStatusEndpoint = endpoint,
            token = "test-token-1234567890"
        )
        val unverified = pendingSyncDisableSnapshotForVerifiedEndpoint(
            current = prepareTerminalInstallationForEndpointChange(
                current = registered,
                queueStatusEndpoint = "https://new.example.com/api/queue-status"
            ),
            queueStatusEndpoint = "https://new.example.com/api/queue-status",
            token = "test-token-1234567890"
        )

        assertEquals(venueId, captured?.venueId)
        assertEquals("主终端", captured?.terminalName)
        assertEquals(CURRENT_SCHEMA_VERSION, captured?.schemaVersion)
        assertNull(unverified)
    }

    @Test
    fun `legacy verified endpoint keeps a schema seven disable snapshot`() {
        val endpoint = "https://legacy.example.com/api/queue-status"
        val legacy = TerminalInstallationIdentity(
            venueName = "测试机厅",
            terminalName = "现场终端",
            registrationState = TerminalInstallationRegistrationState.WAITING_FOR_SERVER,
            onboardingCompleted = true,
            verifiedServerEndpoint = endpoint
        )

        val captured = pendingSyncDisableSnapshotForVerifiedEndpoint(
            current = legacy,
            queueStatusEndpoint = endpoint,
            token = "test-token-1234567890"
        )

        assertNotNull(captured)
        assertNull(captured?.venueId)
        assertEquals(LEGACY_SCHEMA_VERSION, captured?.schemaVersion)
    }

    private val endpoint = "https://example.com/api/queue-status"

    @Test
    fun existingCloudInstallationStaysOfflineUntilItsServerIdentityIsChecked() {
        val migrated = prepareExistingInstallationMigration(
            current = TerminalInstallationIdentity(),
            isExistingInstallation = true,
            cloudConfigured = true,
            nowMillis = 100L
        )

        assertTrue(migrated.onboardingCompleted)
        assertEquals(TerminalInstallationRegistrationState.WAITING_FOR_SERVER, migrated.registrationState)
        assertNull(migrated.verifiedServerEndpoint)
        assertFalse(migrated.allowsOnlineAccess(endpoint))
        assertEquals(
            "正在核对服务器所属机厅，完成前不会上传队列或处理远程操作。",
            migrated.lastError
        )
        assertEquals(100L, migrated.lastUpdatedAtMillis)
    }

    @Test
    fun successfulProbeUnlocksAMigratedInstallation() {
        val migrated = prepareExistingInstallationMigration(
            current = TerminalInstallationIdentity(venueName = "原机厅"),
            isExistingInstallation = true,
            cloudConfigured = true
        )

        val reconciled = reconcileTerminalInstallationIdentity(
            current = migrated,
            remote = registeredIdentity(),
            queueStatusEndpoint = endpoint
        )

        assertEquals(TerminalInstallationRegistrationState.REGISTERED, reconciled.registrationState)
        assertEquals(registeredIdentity().venueId, reconciled.venueId)
        assertTrue(reconciled.allowsOnlineAccess(endpoint))
    }

    @Test
    fun diagnosticRefreshDoesNotChangeTheRuntimeEffectBoundary() {
        val registered = registeredIdentity()

        assertEquals(
            registered.runtimeEffectBoundary(),
            registered.copy(
                lastError = "刚刚完成新一轮服务端核对。",
                lastUpdatedAtMillis = 30_000L
            ).runtimeEffectBoundary()
        )
        assertFalse(
            registered.runtimeEffectBoundary() ==
                registered.copy(terminalName = "另一台终端").runtimeEffectBoundary()
        )
    }

    @Test
    fun unsupportedLegacyServerUnlocksOnlyAfterTheProbeResult() {
        val migrated = prepareExistingInstallationMigration(
            current = TerminalInstallationIdentity(),
            isExistingInstallation = true,
            cloudConfigured = true
        )
        assertFalse(migrated.allowsOnlineAccess(endpoint))

        val compatible = markLegacyInstallationEndpointVerified(migrated, endpoint)

        assertTrue(compatible.allowsOnlineAccess(endpoint))
        assertEquals(endpoint, compatible.verifiedServerEndpoint)
    }

    @Test
    fun migrationNeverChangesANewOrAlreadyCompletedInstallation() {
        val fresh = TerminalInstallationIdentity()
        val completed = registeredIdentity()

        assertEquals(
            fresh,
            prepareExistingInstallationMigration(
                current = fresh,
                isExistingInstallation = false,
                cloudConfigured = true,
                nowMillis = 100L
            )
        )
        assertEquals(
            completed,
            prepareExistingInstallationMigration(
                current = completed,
                isExistingInstallation = true,
                cloudConfigured = true,
                nowMillis = 100L
            )
        )
    }

    @Test
    fun localOnboardingBackNavigationNeverOpensTheHiddenServerStep() {
        assertEquals(0, previousOnboardingStep(currentStep = 2, cloudSyncAvailable = false))
        assertEquals(2, previousOnboardingStep(currentStep = 3, cloudSyncAvailable = false))
    }

    @Test
    fun connectedOnboardingBackNavigationVisitsEveryStep() {
        assertEquals(1, previousOnboardingStep(currentStep = 2, cloudSyncAvailable = true))
        assertEquals(2, previousOnboardingStep(currentStep = 3, cloudSyncAvailable = true))
    }

    @Test
    fun registeredBindingStillRejectsAnotherVenueWhileWaitingForVerification() {
        val current = registeredIdentity().copy(
            registrationState = TerminalInstallationRegistrationState.WAITING_FOR_SERVER,
            verifiedServerEndpoint = null
        )
        val remote = registeredIdentity().copy(
            venueId = "00000000-0000-0000-0000-000000000002",
            venueCode = "BCDE2345"
        )

        val reconciled = reconcileTerminalInstallationIdentity(current, remote, endpoint)

        assertEquals(TerminalInstallationRegistrationState.VENUE_MISMATCH, reconciled.registrationState)
        assertEquals(current.venueId, reconciled.venueId)
        assertFalse(reconciled.allowsOnlineAccess(endpoint))
    }

    @Test
    fun venueMismatchDoesNotDiscardAnOfflineNameEdit() {
        val current = registeredIdentity().copy(
            venueName = "本地新名称",
            pendingServerNameUpdate = true,
            registrationState = TerminalInstallationRegistrationState.WAITING_FOR_SERVER,
            verifiedServerEndpoint = null
        )
        val remote = registeredIdentity().copy(
            venueId = "00000000-0000-0000-0000-000000000002",
            venueCode = "BCDE2345"
        )

        val reconciled = reconcileTerminalInstallationIdentity(current, remote, endpoint)

        assertEquals(
            TerminalInstallationRegistrationState.VENUE_MISMATCH,
            reconciled.registrationState
        )
        assertTrue(reconciled.pendingServerNameUpdate)
        assertEquals("本地新名称", reconciled.venueName)
    }

    @Test
    fun localOrLegacyIdentityCanAdoptTheServerVenue() {
        val local = TerminalInstallationIdentity(
            venueId = "00000000-0000-0000-0000-000000000099",
            venueName = "本地机厅",
            registrationState = TerminalInstallationRegistrationState.WAITING_FOR_SERVER,
            onboardingCompleted = true
        )
        val remote = registeredIdentity()

        val reconciled = reconcileTerminalInstallationIdentity(local, remote, endpoint)

        assertEquals(remote.venueId, reconciled.venueId)
        assertEquals(remote.venueCode, reconciled.venueCode)
        assertEquals(TerminalInstallationRegistrationState.REGISTERED, reconciled.registrationState)
        assertTrue(reconciled.allowsOnlineAccess(endpoint))
    }

    @Test
    fun onlyServerIssuedIdentityIsSentAsAnExpectedVenue() {
        val local = TerminalInstallationIdentity(
            venueId = "00000000-0000-0000-0000-000000000099"
        )

        assertNull(local.expectedServerVenueId)
        assertEquals(registeredIdentity().venueId, registeredIdentity().expectedServerVenueId)
    }

    @Test
    fun legacyServerStopsRetryingAnUnsupportedNameUpdate() {
        val pending = TerminalInstallationIdentity(
            venueName = "新名称",
            registrationState = TerminalInstallationRegistrationState.WAITING_FOR_SERVER,
            onboardingCompleted = true,
            pendingServerNameUpdate = true
        )

        val compatible = markLegacyInstallationEndpointVerified(pending, endpoint)

        assertFalse(compatible.pendingServerNameUpdate)
        assertTrue(compatible.allowsOnlineAccess(endpoint))
    }

    @Test
    fun switchingToLegacyServerDropsTheFormerVenueBinding() {
        val replacement = "https://legacy.example.com/api/queue-status"
        val current = registeredIdentity()

        val compatible = markLegacyInstallationEndpointVerified(current, replacement)

        assertNull(compatible.venueId)
        assertNull(compatible.venueCode)
        assertNull(compatible.venueBindingServerEndpoint)
        assertEquals(replacement, compatible.verifiedServerEndpoint)
        assertTrue(compatible.allowsOnlineAccess(replacement))
    }

    @Test
    fun completingLegacyOnboardingDoesNotInventAServerVenueId() {
        val waiting = TerminalInstallationIdentity(
            venueName = "旧服务端机厅",
            registrationState = TerminalInstallationRegistrationState.WAITING_FOR_SERVER,
            onboardingCompleted = false
        )

        assertNull(completePreparedOnboardingVenueId(waiting, "generated-local-id"))
        assertEquals(
            "generated-local-id",
            completePreparedOnboardingVenueId(
                waiting.copy(registrationState = TerminalInstallationRegistrationState.LOCAL_ONLY),
                "generated-local-id"
            )
        )
    }

    @Test
    fun endpointChangePreservesAnOutstandingNameUpdate() {
        val pending = registeredIdentity().copy(
            venueName = "更新后的机厅名称",
            terminalName = "入口终端",
            registrationState = TerminalInstallationRegistrationState.VENUE_MISMATCH,
            pendingServerNameUpdate = true,
            verifiedServerEndpoint = null
        )

        val prepared = prepareTerminalInstallationForEndpointChange(
            current = pending,
            queueStatusEndpoint = "https://correct.example.com/api/queue-status",
            nowMillis = 123L
        )

        assertTrue(prepared.pendingServerNameUpdate)
        assertEquals(pending.venueName, prepared.venueName)
        assertEquals(pending.terminalName, prepared.terminalName)
        assertEquals(pending.venueId, prepared.venueId)
        assertEquals(
            TerminalInstallationRegistrationState.WAITING_FOR_SERVER,
            prepared.registrationState
        )
        assertNull(prepared.verifiedServerEndpoint)
        assertEquals(123L, prepared.lastUpdatedAtMillis)
    }

    @Test
    fun endpointChangeUsesAReadOnlyProbeWhenNamesAreAlreadyCurrent() {
        val prepared = prepareTerminalInstallationForEndpointChange(
            current = registeredIdentity(),
            queueStatusEndpoint = "https://replacement.example.com/api/queue-status"
        )

        assertFalse(prepared.pendingServerNameUpdate)
        assertEquals(registeredIdentity().venueId, prepared.venueId)
    }

    @Test
    fun reEnablingSyncAlwaysRequiresAFreshReadOnlyVenueProbe() {
        val prepared = prepareTerminalInstallationForSyncEnable(
            current = registeredIdentity(),
            queueStatusEndpoint = endpoint,
            nowMillis = 321L
        )

        assertEquals(registeredIdentity().venueId, prepared.venueId)
        assertEquals(registeredIdentity().venueCode, prepared.venueCode)
        assertEquals(
            TerminalInstallationRegistrationState.WAITING_FOR_SERVER,
            prepared.registrationState
        )
        assertNull(prepared.verifiedServerEndpoint)
        assertFalse(prepared.pendingServerNameUpdate)
        assertFalse(prepared.allowsOnlineAccess(endpoint))
        assertEquals(321L, prepared.lastUpdatedAtMillis)
    }

    @Test
    fun reEnablingSyncRegistersAnInstallationWithoutAServerVenue() {
        val prepared = prepareTerminalInstallationForSyncEnable(
            current = TerminalInstallationIdentity(
                venueName = "本地机厅",
                onboardingCompleted = true
            ),
            queueStatusEndpoint = endpoint
        )

        assertNull(prepared.expectedServerVenueId)
        assertTrue(prepared.pendingServerNameUpdate)
        assertFalse(prepared.allowsOnlineAccess(endpoint))
    }

    @Test
    fun offlineNameEditRemainsPendingAfterSyncIsReEnabled() {
        val edited = prepareTerminalInstallationNameUpdate(
            current = registeredIdentity(),
            venueName = "新机厅名称",
            terminalName = "入口终端",
            syncConfigured = false,
            nowMillis = 111L
        )

        assertTrue(edited.pendingServerNameUpdate)
        assertEquals("新机厅名称", edited.venueName)
        assertEquals("入口终端", edited.terminalName)
        assertEquals(111L, edited.lastUpdatedAtMillis)

        val prepared = prepareTerminalInstallationForSyncEnable(
            current = edited,
            queueStatusEndpoint = endpoint
        )

        assertTrue(prepared.pendingServerNameUpdate)
    }

    @Test
    fun localOnlyNameEditDoesNotInventAServerUpdate() {
        val edited = prepareTerminalInstallationNameUpdate(
            current = TerminalInstallationIdentity(),
            venueName = "本地机厅",
            terminalName = "现场终端",
            syncConfigured = false
        )

        assertFalse(edited.pendingServerNameUpdate)
    }

    @Test
    fun pendingCloseKeepsIdentityWhileTheSameServerIsBeingRechecked() {
        val waiting = prepareTerminalInstallationForSyncEnable(
            current = registeredIdentity(),
            queueStatusEndpoint = endpoint
        )

        assertEquals(
            registeredIdentity().venueId,
            terminalInstallationIdentityForEndpoint(waiting, endpoint)?.venueId
        )
    }

    @Test
    fun pendingCloseNeverCarriesAnOldVenueIdentityToANewServer() {
        val waiting = prepareTerminalInstallationForEndpointChange(
            current = registeredIdentity(),
            queueStatusEndpoint = "https://replacement.example.com/api/queue-status"
        )

        assertNull(
            terminalInstallationIdentityForEndpoint(
                waiting,
                "https://replacement.example.com/api/queue-status"
            )
        )
        assertEquals(
            registeredIdentity().venueId,
            terminalInstallationIdentityForEndpoint(waiting, endpoint)?.venueId
        )
    }

    @Test
    fun missingServerNamesNeverOverwriteLocalInstallationNames() {
        val remote = parseTerminalInstallationIdentity(
            JSONObject(
                """
                {
                  "venue": {
                    "id": "00000000-0000-0000-0000-000000000001",
                    "code": "ABCD2345",
                    "name": null
                  },
                  "terminal": {
                    "id": "terminal-1",
                    "name": null
                  }
                }
                """.trimIndent()
            )
        )
        val current = registeredIdentity().copy(
            venueName = "本机保存的机厅名称",
            terminalName = "入口终端"
        )

        val reconciled = reconcileTerminalInstallationIdentity(current, remote, endpoint)

        assertEquals("本机保存的机厅名称", reconciled.venueName)
        assertEquals("入口终端", reconciled.terminalName)
    }

    @Test
    fun choosingLocalUseAfterRegistrationClearsTheServerVenueBinding() {
        val local = prepareOnboardingIdentityForConnectionChoice(
            current = registeredIdentity().copy(
                onboardingCompleted = false,
                pendingServerNameUpdate = true
            ),
            useServer = false
        )

        assertNull(local.venueId)
        assertNull(local.venueCode)
        assertNull(local.expectedServerVenueId)
        assertEquals(TerminalInstallationRegistrationState.LOCAL_ONLY, local.registrationState)
        assertNull(local.verifiedServerEndpoint)
        assertFalse(local.pendingServerNameUpdate)
    }

    @Test
    fun returningToConnectedOnboardingKeepsTheExpectedVenueForAReadOnlyRetry() {
        val waiting = prepareOnboardingIdentityForConnectionChoice(
            current = registeredIdentity().copy(onboardingCompleted = false),
            useServer = true,
            queueStatusEndpoint = endpoint
        )

        assertEquals(registeredIdentity().venueId, waiting.expectedServerVenueId)
        assertEquals(TerminalInstallationRegistrationState.WAITING_FOR_SERVER, waiting.registrationState)
        assertNull(waiting.verifiedServerEndpoint)
    }

    @Test
    fun changingServerDuringOnboardingDoesNotSendTheFormerVenueBinding() {
        val waiting = prepareOnboardingIdentityForConnectionChoice(
            current = registeredIdentity().copy(onboardingCompleted = false),
            useServer = true,
            queueStatusEndpoint = "https://replacement.example.com/api/queue-status"
        )

        assertNull(waiting.venueId)
        assertNull(waiting.venueCode)
        assertNull(waiting.expectedServerVenueId)
        assertEquals(TerminalInstallationRegistrationState.WAITING_FOR_SERVER, waiting.registrationState)
    }

    @Test
    fun explicitVenueRebindClearsOnlyTheFormerServerBinding() {
        val current = registeredIdentity().copy(
            venueName = "保留的机厅名称",
            terminalName = "保留的终端名称"
        )

        val pending = prepareTerminalInstallationForExplicitVenueRebind(
            current = current,
            queueStatusEndpoint = "https://replacement.example.com/api/queue-status",
            nowMillis = 456L
        )

        assertNull(pending.venueId)
        assertNull(pending.venueCode)
        assertEquals(current.venueName, pending.venueName)
        assertEquals(current.terminalName, pending.terminalName)
        assertEquals(TerminalInstallationRegistrationState.WAITING_FOR_SERVER, pending.registrationState)
        assertTrue(pending.pendingServerNameUpdate)
        assertFalse(pending.allowsOnlineAccess("https://replacement.example.com/api/queue-status"))
        assertEquals(456L, pending.lastUpdatedAtMillis)
    }

    private fun registeredIdentity() = TerminalInstallationIdentity(
        venueId = "00000000-0000-0000-0000-000000000001",
        venueCode = "ABCD2345",
        venueName = "测试机厅",
        terminalName = "现场终端",
        registrationState = TerminalInstallationRegistrationState.REGISTERED,
        onboardingCompleted = true,
        venueBindingServerEndpoint = endpoint,
        verifiedServerEndpoint = endpoint
    )
}
