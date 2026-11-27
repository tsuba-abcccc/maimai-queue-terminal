package com.abcccc.maimaiqueue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoloPassingTextsTest {
    private fun registration(key: Int, name: String, open: Boolean, passable: Boolean = false) = Registration(
        key = key,
        displayId = name,
        preference = if (open) PlayPreference.OPEN_TO_JOIN else PlayPreference.SOLO,
        isTemporary = true,
        createdAtMillis = key * 1_000L,
        allowsPassing = passable
    )

    // Real order 小林, 小王 (lets others pass), 小张, 小陈: 小张 passes 小王 to play with 小林.
    private val queue = MachineQueue(
        playing = listOf(registration(9, "游玩中", open = false)),
        waiting = listOf(
            registration(1, "小林", open = true),
            registration(2, "小王", open = false, passable = true),
            registration(3, "小张", open = true),
            registration(4, "小陈", open = false)
        ),
        playingStartedAtMillis = 1_000L
    )

    @Test
    fun thePassingRegistrationNamesWhomItWillPass() {
        assertEquals(mapOf(3 to "将越过“小王”"), soloPassingTileNotices(queue))
    }

    @Test
    fun theMenuLineFollowsThePass() {
        assertEquals(SoloPassingState.Scheduled(front = "小林", passer = "小张"), soloPassingState(queue, 2))
        assertEquals(
            "前方的“小林”与后方的“小张”均允许他人加入，“小张”将越过这份登记与“小林”共同游玩，" +
                "这份登记的游玩轮次不会推迟。可以在“更改游玩偏好”中取消。",
            soloPassingStatusText(soloPassingState(queue, 2)!!).plain
        )
        val playing = queue.finishRound(2_000_000L)
        assertEquals(SoloPassingState.Playing(front = "小林", passer = "小张"), soloPassingState(playing, 2))
        assertEquals(
            "前方的“小林”与后方的“小张”均允许他人加入，“小张”越过这份登记，正在与“小林”共同游玩，" +
                "这份登记的游玩轮次未推迟。可以在“更改游玩偏好”中取消。",
            soloPassingStatusText(soloPassingState(playing, 2)!!).plain
        )
        // After that round, only the choice itself is told.
        val after = playing.finishRound(3_000_000L).finishRound(4_000_000L)
        assertEquals(SoloPassingState.Enabled, soloPassingState(after, 2))
        assertEquals(
            "已允许后方的登记越过这份登记与前方的登记共同游玩。可以在“更改游玩偏好”中取消。",
            soloPassingStatusText(SoloPassingState.Enabled).plain
        )
        // A registration that does not let others pass has no line.
        assertNull(soloPassingState(queue, 4))
    }
}
