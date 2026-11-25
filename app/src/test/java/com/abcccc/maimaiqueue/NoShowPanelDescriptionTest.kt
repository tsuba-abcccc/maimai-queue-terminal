package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Test

class NoShowPanelDescriptionTest {
    @Test
    fun beforeTheirTurnThePanelSaysNobodyCanActYet() {
        assertEquals(
            "现在还没有轮到这名玩家，即使其不在现场，现场玩家也不能处理这份登记。",
            noShowPanelDescription(group = false, notYetTheirTurn = true, alreadyPlaying = false)
        )
        assertEquals(
            "现在还没有轮到这组玩家，即使其不在现场，现场玩家也不能处理这组登记。",
            noShowPanelDescription(group = true, notYetTheirTurn = true, alreadyPlaying = false)
        )
    }

    @Test
    fun onTheirTurnThePanelKeepsItsInstruction() {
        assertEquals(
            "即将轮到的这名玩家不在现场。现场玩家应将其记录为未到场，并选择如何处理这份登记。",
            noShowPanelDescription(group = false, notYetTheirTurn = false, alreadyPlaying = false)
        )
        assertEquals(
            "已经轮到的这组玩家不在现场。现场玩家应将其记录为未到场，并选择如何处理这组登记。",
            noShowPanelDescription(group = true, notYetTheirTurn = false, alreadyPlaying = true)
        )
    }
}
