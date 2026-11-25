package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 首页分组 table in the settings (docs/releases/ui-redesign-2026-11.md, 第十三节). */
class MachineGroupTableTest {
    private val threeMachines = normalizeMachineLayoutSettings(QueueRuleSettings(configuredMachineCount = 3))

    @Test
    fun aNewGroupMovesNoMachineAndNeverRepeatsAName() {
        val added = addEmptyMachineGroup(threeMachines)!!
        assertEquals(listOf("分组 1", "分组 2"), added.machineGroups.map { it.name })
        // Every machine stays where it was.
        assertTrue(added.configuredMachineIds.all { added.machineGroupId(it) == added.machineGroups.first().id })

        val renamed = renameMachineGroup(added, added.machineGroups.first().id, "分组 2 号")
        val third = addEmptyMachineGroup(renameMachineGroup(renamed, renamed.machineGroups[1].id, "分组 1"))!!
        // 分组 1 is taken and “分组 2 号” is another name, so the smallest free one is 分组 2.
        assertEquals("分组 2", third.machineGroups.last().name)
    }

    @Test
    fun anEmptyGroupStaysWhileEditingAndIsDroppedOnSaving() {
        val added = addEmptyMachineGroup(threeMachines)!!
        val second = added.machineGroups[1].id
        val moved = assignMachineToGroup(added, MachineId.C, second)
        assertEquals(listOf(MachineId.C), moved.machinesInGroup(second))
        // Moving C back leaves 分组 2 empty; it is kept for now.
        val back = assignMachineToGroup(moved, MachineId.C, moved.machineGroups.first().id)
        assertEquals(2, back.machineGroups.size)
        // Saving drops it.
        assertEquals(1, normalizeMachineLayoutSettings(back).machineGroups.size)
    }

    @Test
    fun onlyAnEmptyGroupCanBeDeleted() {
        val added = addEmptyMachineGroup(threeMachines)!!
        val second = added.machineGroups[1].id
        val filled = assignMachineToGroup(added, MachineId.B, second)
        assertEquals(2, deleteEmptyMachineGroup(filled, second).machineGroups.size)
        val emptied = assignMachineToGroup(filled, MachineId.B, filled.machineGroups.first().id)
        assertEquals(1, deleteEmptyMachineGroup(emptied, second).machineGroups.size)
    }

    @Test
    fun anEmptyGroupCannotBeTheDefaultAndNoMoreGroupsThanMachines() {
        val added = addEmptyMachineGroup(threeMachines)!!
        val second = added.machineGroups[1].id
        assertEquals(added.machineGroups.first().id, setDefaultMachineGroup(added, second).defaultMachineGroupId)
        val filled = assignMachineToGroup(added, MachineId.B, second)
        assertEquals(second, setDefaultMachineGroup(filled, second).defaultMachineGroupId)

        val full = addEmptyMachineGroup(filled)!!
        assertNull(addEmptyMachineGroup(full))
    }
}
