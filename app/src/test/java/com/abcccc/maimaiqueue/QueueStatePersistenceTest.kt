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
}
