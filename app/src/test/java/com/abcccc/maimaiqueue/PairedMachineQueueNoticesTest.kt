package com.abcccc.maimaiqueue

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedMachineQueueNoticesTest {
    @Test
    fun noRegistrationAffectedMeansNoNotice() {
        assertNull(pairedMachinePlaceGivenUpNotice(emptyList(), "调整", "机台 B"))
    }

    @Test
    fun oneRegistrationIsNamed() {
        assertEquals(
            "“小明”已允许系统在当前机台和 机台 B 之间安排登记。" +
                "调整后，这份登记游玩一轮之前，不会被安排到另一机台。",
            pairedMachinePlaceGivenUpNotice(listOf("小明"), "调整", "机台 B")
        )
    }

    @Test
    fun longListsAreShortenedLikeTheOtherConfirmations() {
        assertEquals(
            "“甲”、“乙”、“丙”等 4 份登记已允许系统在当前机台和 机台 B 之间安排登记。" +
                "撤回后，这些登记游玩一轮之前，不会被安排到另一机台。",
            pairedMachinePlaceGivenUpNotice(listOf("甲", "乙", "丙", "丁"), "撤回", "机台 B")
        )
    }

    @Test
    fun tickBoxAndItsStatusFollowTheApprovedWording() {
        assertEquals(
            "允许系统在当前机台和 机台 B 之间安排登记",
            pairedMachineArrangementLabel("机台 B")
        )
        assertEquals(
            "开启后，这份登记将在符合条件时自动转至能够更早安排游玩的机台。" +
                "这份登记游玩一轮之前，不会被安排到另一机台。",
            PAIRED_MACHINE_ARRANGEMENT_STARTS_LATER_HINT
        )
        assertEquals(
            "已允许系统在当前机台和 机台 B 之间安排登记。" +
                "机台 B 能够更早安排这份登记游玩时，其等待顺序中会显示预览。" +
                "可以在“更改游玩偏好”中取消。",
            pairedMachineArrangementStatus("机台 B", waitsForNextRound = false)
        )
        assertEquals(
            "已允许系统在当前机台和 机台 B 之间安排登记。" +
                "这份登记游玩一轮之前，不会被安排到另一机台。" +
                "可以在“更改游玩偏好”中取消。",
            pairedMachineArrangementStatus("机台 B", waitsForNextRound = true)
        )
        assertEquals("5 秒后可以关闭", pairedMachineArrangementCountdown(5))
    }

    @Test
    fun statusLineSaysWhenTheRegistrationMayBeMoved() {
        val allowed = "已允许系统在当前机台和 机台 B 之间安排登记。"
        val cancel = "可以在“更改游玩偏好”中取消。"
        assertEquals(
            allowed + "机台 B 的等待顺序中已显示这份登记的预览，最早 14:32 之后，" +
                "这份登记可能自动转至 机台 B，请合理安排时间。" + cancel,
            pairedMachineArrangementStatus(
                "机台 B",
                waitsForNextRound = false,
                announcement = PairedMachineArrangementAnnouncement.NotBefore("14:32")
            )
        )
        assertEquals(
            allowed + "机台 B 的等待顺序中已显示这份登记的预览，" +
                "这份登记随时可能自动转至 机台 B，请合理安排时间。" + cancel,
            pairedMachineArrangementStatus(
                "机台 B",
                waitsForNextRound = false,
                announcement = PairedMachineArrangementAnnouncement.AnyMoment
            )
        )
        // While a round runs unusually long nothing is arranged, so no time is promised.
        assertEquals(
            allowed + "机台 B 的等待顺序中已显示这份登记的预览。" + cancel,
            pairedMachineArrangementStatus(
                "机台 B",
                waitsForNextRound = false,
                announcement = PairedMachineArrangementAnnouncement.Held
            )
        )
    }

    @Test
    fun announcedRegistrationSaysSoOnItsOwnEntry() {
        // On the queue screen this line stands in place of the play preference.
        assertEquals("可能转至 机台 B", pairedMachineArrangementTileNotice("机台 B"))

        // In the registration's menu the sentence about the move is the part set in colour.
        val announced = pairedMachineArrangementStatusText(
            "机台 B",
            waitsForNextRound = false,
            announcement = PairedMachineArrangementAnnouncement.NotBefore("14:32")
        )
        assertEquals("已允许系统在当前机台和 机台 B 之间安排登记。", announced.before)
        assertEquals(
            "机台 B 的等待顺序中已显示这份登记的预览，最早 14:32 之后，" +
                "这份登记可能自动转至 机台 B，请合理安排时间。",
            announced.highlighted
        )
        assertEquals("可以在“更改游玩偏好”中取消。", announced.after)
        assertEquals(
            announced.plain,
            pairedMachineArrangementStatus(
                "机台 B",
                waitsForNextRound = false,
                announcement = PairedMachineArrangementAnnouncement.NotBefore("14:32")
            )
        )

        // Where the other machine shows the preview, its position is named (2026-11-25).
        assertEquals("可能转至 B1", pairedMachineArrangementTileNotice("B1"))
        assertEquals(
            "机台 B 的位置 B1 已显示这份登记的预览，最早 14:32 之后，" +
                "这份登记可能自动转至该位置，请合理安排时间。",
            pairedMachineArrangementAnnouncementSentence(
                "机台 B",
                PairedMachineArrangementAnnouncement.NotBefore("14:32"),
                previewPosition = "B1"
            )
        )
        assertEquals(
            "机台 B 的位置 B1 已显示这份登记的预览，这份登记随时可能自动转至该位置，请合理安排时间。",
            pairedMachineArrangementAnnouncementSentence(
                "机台 B",
                PairedMachineArrangementAnnouncement.AnyMoment,
                previewPosition = "B1"
            )
        )

        // Nothing is announced: nothing is set in colour.
        assertNull(pairedMachineArrangementStatusText("机台 B", waitsForNextRound = false).highlighted)
        assertNull(pairedMachineArrangementAnnouncementSentence("机台 B", null))
        // A registration that stays at its machine until it has played is not announced.
        assertNull(
            pairedMachineArrangementStatusText(
                "机台 B",
                waitsForNextRound = true,
                announcement = PairedMachineArrangementAnnouncement.AnyMoment
            ).highlighted
        )
    }

    @Test
    fun explanationNamesTheFeatureAndCoversTheAnnouncementRules() {
        assertEquals("自动在两台机台之间安排登记", PAIRED_MACHINE_ARRANGEMENT_DETAILS_TITLE)
        assertEquals(12L, PAIRED_MACHINE_ARRANGEMENT_LEAD_MINUTES)
        val paragraphs = pairedMachineArrangementDetails("机台 B")
        assertEquals(7, paragraphs.size)
        assertEquals(
            "机台 B 能够更早安排这份登记游玩时，其等待顺序中会显示预览。" +
                "预览持续显示至少 12 分钟后，登记才会自动转至 机台 B 并进入游玩位置。",
            paragraphs[1].text
        )
        assertTrue("提醒玩家合理安排时间", paragraphs[2].text.endsWith("合理安排时间。"))
        assertEquals(
            "自动转至 机台 B 后未到场时，登记会回到当前机台原来的等待顺序，" +
                "未到场记录不会增加，这项选择同时取消。",
            paragraphs[3].text
        )
        assertEquals(3, paragraphs[5].items.size)
        (paragraphs.map { it.text } + paragraphs.flatMap { it.items }).forEach { text ->
            assertTrue(text, text.endsWith("。") || text.endsWith("："))
        }
        // The internal limits of the rules are not named to players.
        val everything = paragraphs.joinToString("") { it.text + it.items.joinToString("") }
        listOf("3 分钟", "1 分钟", "5 分钟", "20 分钟").forEach { limit ->
            assertFalse(limit, everything.contains(limit))
        }
    }

    @Test
    fun previewExplanationHasOneSentencePerLine() {
        assertEquals(
            listOf(
                "这份登记在 机台 A 排队，并已允许系统在 机台 A 和 机台 B 之间安排。",
                "按当前等待顺序和预计游玩时间，机台 B 能够更早安排这份登记游玩。",
                "预览持续显示至少 12 分钟后，登记才会自动转至 机台 B。",
                "预览不会改变真实队列。"
            ),
            pairedMachinePreviewExplanation("机台 A", "机台 B").split("\n")
        )
    }

    @Test
    fun transferByHandSaysWhatBecomesOfTheChoice() {
        assertNull(
            pairedMachineTransferNotice(emptyList(), "机台 A", "机台 B", true, "机台 B")
        )
        assertEquals(
            "“小明”已允许系统在 机台 A 和 机台 B 之间安排登记。" +
                "登记留在 机台 A 排队时，也可能被更早安排到 机台 B 游玩；" +
                "转至 机台 B 后，只能加入其队尾。",
            pairedMachineTransferNotice(listOf("小明"), "机台 A", "机台 B", true, "机台 B")
        )
        assertEquals(
            "“小明”已允许系统在 机台 A 和 机台 B 之间安排登记。" +
                "转至 机台 C 后，将取消系统在 机台 A 和 机台 B 之间的安排。",
            pairedMachineTransferNotice(listOf("小明"), "机台 A", "机台 B", false, "机台 C")
        )
    }

    @Test
    fun noShowOfAnArrangedRegistrationHasOneOutcome() {
        assertEquals("确认未到场？", PAIRED_MACHINE_ARRIVAL_NO_SHOW_TITLE)
        assertEquals("确认回到 机台 A", pairedMachineArrivalNoShowConfirmLabel("机台 A"))
        assertEquals(
            "“小明”已自动转至 机台 B。确认后，这份登记将回到 机台 A 原来的等待顺序，" +
                "未到场记录不会增加；系统在 机台 A 和 机台 B 之间的安排同时取消，" +
                "需要时可以在“更改游玩偏好”中重新开启。",
            pairedMachineArrivalNoShowDetail("“小明”", false, "机台 B", "机台 A")
        )
        assertEquals(
            "“小明”已自动转至 机台 B，不按所选方式处理：" +
                "登记会回到 机台 A 原来的等待顺序，未到场记录不会增加，系统在两台机台之间的安排同时取消。",
            pairedMachineArrivalNoShowMixedNotice("“小明”", "机台 B", "机台 A")
        )
    }

    /**
     * Nothing that players or staff read may describe this as queueing at two machines; the
     * maintainer settled the vocabulary in docs/copywriting-guide.md. The check reads the
     * sources, because most of the text lives in screens that unit tests cannot render.
     */
    @Test
    fun noScreenTextSpeaksOfQueueingAtTwoMachines() {
        val sources = File("src/main/java/com/abcccc/maimaiqueue")
        assertTrue("找不到源文件目录：${sources.absolutePath}", sources.isDirectory)
        val banned = listOf(
            Regex("同时在[^\"，。]{0,24}排队"),
            Regex("同时排队"),
            Regex("系统安排[^登这]"),
            Regex("保留(一个)?位置"),
            // After 允许 the sentence names who arranges: 允许系统在……之间安排.
            Regex("允许在(两台|一对)机台之间")
        )
        val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val offences = sources.listFiles { file -> file.extension == "kt" }.orEmpty()
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        return@mapIndexedNotNull null
                    }
                    literal.findAll(line)
                        .map { it.groupValues[1] }
                        .firstOrNull { text -> banned.any { it.containsMatchIn(text) } }
                        ?.let { "${file.name}:${index + 1} $it" }
                }
            }
        assertTrue(offences.joinToString("\n"), offences.isEmpty())
        assertFalse(sources.listFiles().isNullOrEmpty())
    }

    @Test
    fun aPausedAnnouncementIsNotShownOnTheTile() {
        val allowed = Registration(
            key = 1,
            displayId = "小明",
            preference = PlayPreference.OPEN_TO_JOIN,
            alsoQueuesAtPairedMachine = true
        )
        assertTrue(announcesPairedArrangement(allowed, PairedArrangementRecord(previewSinceMillis = 1_000L)))
        assertFalse(
            announcesPairedArrangement(
                allowed,
                PairedArrangementRecord(previewSinceMillis = 1_000L, previewPausedAtMillis = 2_000L)
            )
        )
        assertFalse(announcesPairedArrangement(allowed, PairedArrangementRecord()))
        assertFalse(announcesPairedArrangement(allowed, null))
        assertFalse(
            announcesPairedArrangement(
                allowed.copy(alsoQueuesAtPairedMachine = false),
                PairedArrangementRecord(previewSinceMillis = 1_000L)
            )
        )
    }
}
