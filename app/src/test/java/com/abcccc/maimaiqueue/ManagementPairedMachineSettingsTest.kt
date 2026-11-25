package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementPairedMachineSettingsTest {
    private val stableA = "1".repeat(32)
    private val stableB = "2".repeat(32)
    private val stableC = "3".repeat(32)

    private fun machine(id: String, stableId: String?, capacity: Int = 2) = ManagementMachine(
        id = id,
        name = "机台 $id",
        stableId = stableId,
        groupId = DEFAULT_MACHINE_GROUP_ID,
        capacity = capacity,
        operational = true,
        stopReason = null,
        stopReasonDetail = null,
        configuration = MachineConfiguration(remark = "机台 $id", capacity = capacity),
        registrationCount = 0,
        playingStartedAtMillis = null,
        playing = emptyList(),
        waitingPositions = emptyList()
    )

    @Test
    fun pairsStayWhileBothMachinesExistWithTheSameCapacity() {
        val machines = listOf(machine("A", stableA), machine("B", stableB), machine("C", stableC))

        assertEquals(
            listOf(stableA to stableB),
            validMachinePairs(listOf(stableA to stableB), machines)
        )
    }

    @Test
    fun pairsThatBecameImpossibleAreDropped() {
        val machines = listOf(
            machine("A", stableA),
            machine("B", stableB, capacity = 1),
            machine("C", stableC)
        )

        // B was changed to single play, so it can no longer share registrations with A.
        assertTrue(validMachinePairs(listOf(stableA to stableB), machines).isEmpty())
        // A removed machine, a machine paired with itself, and a machine already in a pair.
        assertEquals(
            listOf(stableA to stableC),
            validMachinePairs(
                listOf(
                    stableA to "9".repeat(32),
                    stableA to stableA,
                    stableA to stableC,
                    stableC to stableA
                ),
                machines
            )
        )
        // A machine the terminal reported without a stable id cannot be named in a pair.
        assertTrue(
            validMachinePairs(
                listOf(stableA to stableB),
                listOf(machine("A", stableA), machine("B", null))
            ).isEmpty()
        )
    }
}
