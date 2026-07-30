package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueStatePersistenceTest {
    private fun state(
        machineA: MachineQueue = MachineQueue(),
        machineB: MachineQueue = MachineQueue(),
        machineAStatus: MachineStatus = MachineStatus(),
        machineBStatus: MachineStatus = MachineStatus(),
        registrationOpen: Boolean = true,
        nextRegistrationKey: Int = 1
    ) = PersistedQueueState(
        queueId = "00000000-0000-0000-0000-000000000001",
        revision = 3L,
        machineA = machineA,
        machineB = machineB,
        machineAStatus = machineAStatus,
        machineBStatus = machineBStatus,
        registrationOpen = registrationOpen,
        nextRegistrationKey = nextRegistrationKey,
        savedAtMillis = 1_000L
    )

    @Test
    fun emptyOperationalStateHasNoMeaningfulQueueContent() {
        assertFalse(state().hasMeaningfulState)
    }

    @Test
    fun restoredMachineStatusDropsFieldsRejectedByTheCloudContract() {
        val stopped = normalizeRestoredMachineStatus(
            MachineStatus(
                stopReason = MachineStopReason.OTHER,
                stopReasonDetail = "  等待配件  ",
                stoppedAtMillis = 0L
            )
        )
        val operational = normalizeRestoredMachineStatus(
            MachineStatus(
                stopReasonDetail = "不应保留",
                stoppedAtMillis = 100L
            )
        )

        assertEquals(MachineStopReason.OTHER, stopped.stopReason)
        assertEquals("等待配件", stopped.stopReasonDetail)
        assertEquals(null, stopped.stoppedAtMillis)
        assertEquals(MachineStatus(), operational)
    }

    @Test
    fun registrationsStoppedMachinesAndClosedRegistrationAreRestorable() {
        val registration = Registration(
            key = 7,
            displayId = "玩家",
            preference = PlayPreference.SOLO,
            createdAtMillis = 100L
        )

        assertTrue(state(machineA = MachineQueue(waiting = listOf(registration))).hasMeaningfulState)
        assertTrue(
            state(
                machineBStatus = MachineStatus().stop(MachineStopReason.OTHER, 200L)
            ).hasMeaningfulState
        )
        assertTrue(state(registrationOpen = false).hasMeaningfulState)
    }

    @Test
    fun restoredRegistrationKeysCannotCollideWithNewRegistrations() {
        val restored = state(
            machineA = MachineQueue(
                waiting = listOf(
                    Registration(12, "玩家", PlayPreference.SOLO, createdAtMillis = 100L)
                )
            ),
            nextRegistrationKey = 3
        )

        assertEquals(13, restored.safeNextRegistrationKey)
    }

    @Test
    fun olderRevisionCannotOverwriteNewerState() {
        val newer = state().copy(revision = 8L)
        val older = state().copy(revision = 7L)

        assertFalse(shouldPersistQueueState(older, newer))
        assertFalse(shouldPersistQueueState(newer, newer))
        assertTrue(shouldPersistQueueState(newer.copy(revision = 9L), newer))
    }

    @Test
    fun newestValidSnapshotWinsAcrossPrimaryAndBackup() {
        val primary = state().copy(revision = 8L, savedAtMillis = 2_000L)
        val olderBackup = state().copy(revision = 7L, savedAtMillis = 3_000L)
        val newerBackup = state().copy(revision = 9L, savedAtMillis = 1_000L)

        assertEquals(primary, newestPersistedQueueState(primary, olderBackup))
        assertEquals(newerBackup, newestPersistedQueueState(primary, newerBackup))
        assertEquals(primary, newestPersistedQueueState(primary, null))
        assertEquals(olderBackup, newestPersistedQueueState(null, olderBackup))
    }

    @Test
    fun anOlderSnapshotCannotOverwriteANewerQueueBatch() {
        val previous = state().copy(revision = 80L)
        val stalePreviousBatchSnapshot = state().copy(
            queueId = "00000000-0000-0000-0000-000000000002",
            revision = 79L
        )

        assertFalse(shouldPersistQueueState(stalePreviousBatchSnapshot, previous))
        assertTrue(
            shouldPersistQueueState(
                stalePreviousBatchSnapshot.copy(revision = 81L),
                previous
            )
        )
    }

    @Test
    fun emptyOpenQueueStillPreservesTheNextRegistrationKey() {
        val saved = state().copy(
            machineA = MachineQueue(),
            machineB = MachineQueue(),
            registrationOpen = true,
            nextRegistrationKey = 27
        )

        assertFalse(saved.hasMeaningfulState)
        assertEquals(27, saved.safeNextRegistrationKey)
    }

    @Test
    fun restoredQueueRepairsFieldsRejectedByTheCloudContract() {
        val restored = normalizeRestoredMachineQueue(
            MachineQueue(
                playing = listOf(
                    Registration(
                        key = 1,
                        displayId = "  一二三四五六七八九十一二三四五六七八九十  ",
                        preference = PlayPreference.SOLO,
                        createdAtMillis = 0L,
                        lastPlayedAtMillis = 0L,
                        noShowCount = 0,
                        lastNoShowActionWasDefer = true
                    )
                ),
                playingStartedAtMillis = 0L
            )
        )
        val registration = restored.playing.single()

        assertEquals(18, registration.displayId.codePointCount(0, registration.displayId.length))
        assertEquals(1L, registration.createdAtMillis)
        assertEquals(null, registration.lastPlayedAtMillis)
        assertFalse(registration.lastNoShowActionWasDefer)
        assertEquals(null, restored.playingStartedAtMillis)
    }

    @Test
    fun restoredQueueClearsBrokenPairsAndHarmonizesValidPairAbsence() {
        val validFirst = Registration(
            key = 1,
            displayId = "小雨",
            preference = PlayPreference.SOLO,
            absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
            fixedPartnerKey = 2,
            createdAtMillis = 100L
        )
        val validSecond = Registration(
            key = 2,
            displayId = "青空",
            preference = PlayPreference.SOLO,
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 2,
            fixedPartnerKey = 1,
            createdAtMillis = 200L
        )
        val broken = Registration(
            key = 3,
            displayId = "北川",
            preference = PlayPreference.SOLO,
            fixedPartnerKey = 99,
            createdAtMillis = 300L
        )

        val restored = normalizeRestoredMachineQueue(
            MachineQueue(waiting = listOf(validFirst, validSecond, broken))
        )

        assertTrue(restored.waiting.take(2).all {
            it.preference == PlayPreference.OPEN_TO_JOIN &&
                it.absenceStatus == QueueAbsenceStatus.TEMPORARILY_AWAY &&
                it.temporaryAwaySkippedTurns == 2
        })
        assertEquals(null, restored.waiting[2].fixedPartnerKey)
        assertEquals(PlayPreference.OPEN_TO_JOIN, restored.waiting[2].preference)
    }

    @Test
    fun restoredQueueKeepsPendingCheckInOnlyInWaitingOrder() {
        val pendingPlaying = Registration(
            key = 1,
            displayId = "游玩玩家",
            preference = PlayPreference.SOLO,
            requiresOnSiteCheckIn = true,
            createdAtMillis = 100L
        )
        val pendingWaiting = Registration(
            key = 2,
            displayId = "线上玩家",
            preference = PlayPreference.SOLO,
            requiresOnSiteCheckIn = true,
            createdAtMillis = 200L
        )

        val restored = normalizeRestoredMachineQueue(
            MachineQueue(
                playing = listOf(pendingPlaying),
                waiting = listOf(pendingWaiting),
                playingStartedAtMillis = 300L
            )
        )

        assertFalse(restored.playing.single().requiresOnSiteCheckIn)
        assertTrue(restored.waiting.single().requiresOnSiteCheckIn)
    }

    @Test
    fun restoredQueueRemovesStatesThatConflictWithPlayingOrPendingCheckIn() {
        val restored = normalizeRestoredMachineQueue(
            MachineQueue(
                playing = listOf(
                    Registration(
                        key = 1,
                        displayId = "游玩玩家",
                        preference = PlayPreference.SOLO,
                        absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
                        temporaryAwaySkippedTurns = 2,
                        createdAtMillis = 100L
                    )
                ),
                waiting = listOf(
                    Registration(
                        key = 2,
                        displayId = "线上玩家",
                        preference = PlayPreference.OPEN_TO_JOIN,
                        absenceStatus = QueueAbsenceStatus.DEFER_ONE_ROUND,
                        fixedPartnerKey = 3,
                        requiresOnSiteCheckIn = true,
                        createdAtMillis = 200L
                    ),
                    Registration(
                        key = 3,
                        displayId = "现场玩家",
                        preference = PlayPreference.OPEN_TO_JOIN,
                        fixedPartnerKey = 2,
                        createdAtMillis = 300L
                    )
                )
            )
        )

        assertEquals(QueueAbsenceStatus.NONE, restored.playing.single().absenceStatus)
        assertEquals(0, restored.playing.single().temporaryAwaySkippedTurns)
        assertEquals(QueueAbsenceStatus.NONE, restored.waiting.first().absenceStatus)
        assertEquals(null, restored.waiting.first().fixedPartnerKey)
        assertEquals(null, restored.waiting.last().fixedPartnerKey)
        assertTrue(restored.invariantViolations().isEmpty())
    }
}
