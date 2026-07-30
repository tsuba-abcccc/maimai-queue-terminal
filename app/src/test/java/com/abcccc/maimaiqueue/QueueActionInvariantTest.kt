package com.abcccc.maimaiqueue

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueActionInvariantTest {
    private fun registration(
        key: Int,
        preference: PlayPreference = PlayPreference.OPEN_TO_JOIN,
        pendingCheckIn: Boolean = false
    ) = Registration(
        key = key,
        displayId = "玩家$key",
        preference = preference,
        isTemporary = false,
        createdAtMillis = key * 1_000L,
        requiresOnSiteCheckIn = pendingCheckIn
    )

    private fun MachineQueue.assertConsistent(step: String): MachineQueue = apply {
        assertTrue("$step: ${invariantViolations().joinToString()} ", invariantViolations().isEmpty())
    }

    @Test
    fun invariantCheckReportsConflictingQueueStates() {
        val pendingPlaying = registration(1, pendingCheckIn = true).copy(
            absenceStatus = QueueAbsenceStatus.TEMPORARILY_AWAY,
            temporaryAwaySkippedTurns = 4,
            fixedPartnerKey = 2
        )
        val unpaired = registration(2, PlayPreference.SOLO)
        val violations = MachineQueue(
            playing = listOf(pendingPlaying, unpaired, registration(2))
        ).invariantViolations()

        assertTrue(violations.any { "超过 2 个" in it })
        assertTrue(violations.any { "重复登记编号" in it })
        assertTrue(violations.any { "待签到线上登记不能进入" in it })
        assertTrue(violations.any { "轮空次数" in it })
        assertTrue(violations.any { "固定组合关系必须双向" in it })
    }

    @Test
    fun normalQueueActionsRemainStructurallyConsistentWhenCombined() {
        var queue = MachineQueue()
            .join(registration(1, PlayPreference.SOLO))
            .assertConsistent("首份登记")
            .joinAll(
                listOf(
                    registration(2),
                    registration(3),
                    registration(4, PlayPreference.SOLO),
                    registration(5),
                    registration(6)
                )
            )
            .assertConsistent("批量登记")

        queue = queue.finishRound(atMillis = 20_000L).assertConsistent("结束本轮")
        queue = queue.deferOneRound(2).assertConsistent("暂缓一轮")
        queue = queue.cancelDeferOneRound(2).assertConsistent("取消暂缓")
        queue = queue.temporarilyLeave(3).assertConsistent("暂时离开")
        queue = queue.cancelTemporaryLeave(3).assertConsistent("取消暂离")
        queue = queue.changePreference(4, PlayPreference.OPEN_TO_JOIN)
            .assertConsistent("修改游玩偏好")
        queue = queue.moveWaitingPosition(1, 0).assertConsistent("移动队列位置")
        queue = queue.remove(6).assertConsistent("退出排队")
        queue = queue.endRoundWithoutStartingNext(atMillis = 30_000L)
            .assertConsistent("仅结束本轮")
        queue = queue.enterPlayingPosition().assertConsistent("进入游玩位置")
    }

    @Test
    fun fixedPairActionsRemainConsistentAcrossAbsenceAndRelease() {
        var queue = MachineQueue(
            waiting = listOf(
                registration(1),
                registration(2),
                registration(3, PlayPreference.SOLO)
            )
        )
        val plan = requireNotNull(queue.planFriendPair(1, 2))
        queue = queue.applyFriendPair(plan).assertConsistent("建立固定组合")
        queue = queue.deferOneRound(1).assertConsistent("固定组合暂缓")
        queue = queue.cancelDeferOneRound(2).assertConsistent("固定组合取消暂缓")
        queue = queue.temporarilyLeave(1).assertConsistent("固定组合暂离")
        queue = queue.cancelTemporaryLeave(2).assertConsistent("固定组合取消暂离")
        queue = queue.changePreference(1, PlayPreference.SOLO)
            .assertConsistent("解除固定组合")
    }

    @Test
    fun onlineCheckInAndNoShowActionsRemainConsistent() {
        val pending = registration(1, PlayPreference.SOLO, pendingCheckIn = true)
        var queue = MachineQueue(waiting = listOf(pending, registration(2, PlayPreference.SOLO)))
            .assertConsistent("线上登记")
        queue = queue.enterPlayingPosition().assertConsistent("跳过未签到登记")

        var checkedInQueue = MachineQueue(waiting = listOf(pending))
            .checkIn(1)
            .assertConsistent("现场签到")
        checkedInQueue = checkedInQueue.enterPlayingPosition().assertConsistent("签到后进入游玩位置")
        checkedInQueue = checkedInQueue.markNoShowMoveToEnd(
            registrationKeys = setOf(1),
            startNextWhenPlayingBecomesEmpty = false
        ).assertConsistent("未到场移至队尾")
        checkedInQueue = checkedInQueue.markNoShowAndRemove(setOf(1))
            .assertConsistent("未到场退出排队")
    }

    @Test
    fun repeatedNormalActionsNeverBreakQueueInvariants() {
        val random = Random(20260731)
        var nextKey = 1
        var queue = MachineQueue()

        repeat(600) { step ->
            val allKeys = queue.allRegistrations.map(Registration::key)
            val selectedKey = allKeys.takeIf { it.isNotEmpty() }?.random(random)
            queue = when (random.nextInt(16)) {
                0 -> if (queue.registrationCount < 12) {
                    val key = nextKey++
                    queue.join(
                        registration(
                            key = key,
                            preference = if (random.nextBoolean()) {
                                PlayPreference.OPEN_TO_JOIN
                            } else {
                                PlayPreference.SOLO
                            },
                            pendingCheckIn = random.nextInt(5) == 0
                        )
                    )
                } else {
                    queue
                }
                1 -> queue.finishRound(atMillis = 100_000L + step)
                2 -> queue.endRoundWithoutStartingNext(atMillis = 100_000L + step)
                3 -> queue.enterPlayingPosition()
                4 -> selectedKey?.let(queue::deferOneRound) ?: queue
                5 -> selectedKey?.let(queue::cancelDeferOneRound) ?: queue
                6 -> selectedKey?.let(queue::temporarilyLeave) ?: queue
                7 -> selectedKey?.let(queue::cancelTemporaryLeave) ?: queue
                8 -> selectedKey?.let {
                    queue.changePreference(
                        it,
                        if (random.nextBoolean()) PlayPreference.SOLO else PlayPreference.OPEN_TO_JOIN
                    )
                } ?: queue
                9 -> selectedKey?.let(queue::checkIn) ?: queue
                10 -> selectedKey?.let(queue::remove) ?: queue
                11 -> {
                    val eligible = allKeys.filter(queue::canMarkNoShow)
                    eligible.randomOrNull(random)?.let {
                        queue.markNoShowMoveToEnd(
                            registrationKeys = setOf(it),
                            startNextWhenPlayingBecomesEmpty = random.nextBoolean()
                        )
                    } ?: queue
                }
                12 -> {
                    val positions = queue.waitingPositions()
                    if (positions.size > 1) {
                        queue.moveWaitingPosition(
                            sourceIndex = random.nextInt(positions.size),
                            destinationIndex = random.nextInt(positions.size)
                        )
                    } else {
                        queue
                    }
                }
                13 -> {
                    val pairCandidates = queue.waiting.filter {
                        it.fixedPartnerKey == null && !it.requiresOnSiteCheckIn
                    }
                    if (pairCandidates.size >= 2) {
                        val first = pairCandidates.random(random)
                        val second = pairCandidates.filterNot { it.key == first.key }.random(random)
                        queue.planFriendPair(first.key, second.key)?.let(queue::applyFriendPair) ?: queue
                    } else {
                        queue
                    }
                }
                14 -> {
                    val playingKeys = queue.playing.mapTo(mutableSetOf(), Registration::key)
                    if (playingKeys.isNotEmpty()) {
                        queue.returnPlayingRegistrationsToWaitingFront(playingKeys)
                    } else {
                        queue
                    }
                }
                else -> queue.removeExpiredOnlineRegistrations(atMillis = 31L * 60L * 1_000L)
            }
            queue.assertConsistent("组合动作第 ${step + 1} 步")
        }
    }
}
