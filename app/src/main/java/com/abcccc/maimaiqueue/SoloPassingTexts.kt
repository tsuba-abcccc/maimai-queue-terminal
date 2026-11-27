package com.abcccc.maimaiqueue

/*
 * Text about a 单人游玩 registration that lets the registrations around it pass
 * (docs/releases/solo-pass-plan-2026-11.md, section 十一; settled with the maintainer on
 * 2026-11-26). The wording is fixed there; change it only together with the plan and the website.
 */

/** The tick box inside the 单人游玩 choice: a full sentence, not a title. */
internal const val SOLO_PASSING_CHOICE =
    "前方的登记允许他人加入，却因这份登记单人游玩而独自占用一个等待位置时，允许后方的登记越过这份登记与前方的登记共同游玩。"

/** The small text under [SOLO_PASSING_CHOICE]. */
internal const val SOLO_PASSING_HINT =
    "后方的登记也允许他人加入时，可以越过这份登记与前方的登记组成共同游玩，使队列加速推进。" +
        "越过不会推迟这份登记及其他登记的游玩轮次，但这份登记可能因前方一轮改为两人游玩而稍晚进入游玩位置。"

private const val SOLO_PASSING_CANCEL = "可以在“更改游玩偏好”中取消。"

/*
 * The same choice for a fixed pair (docs/releases/fixed-pair-pass-plan-2026-11.md; wording
 * approved by the maintainer on 2026-11-27). One choice for both registrations of the pair.
 */

/** The tick box of a fixed pair: a full sentence, not a title. */
internal const val FIXED_PAIR_PASSING_CHOICE =
    "前方的登记允许他人加入，却因这组固定组合排在其后而独自占用一个等待位置时，允许后方的登记越过这组固定组合与前方的登记共同游玩。"

/** The small text under [FIXED_PAIR_PASSING_CHOICE]. */
internal const val FIXED_PAIR_PASSING_HINT =
    "后方的登记也允许他人加入时，可以越过这组固定组合与前方的登记组成共同游玩，使队列加速推进。" +
        "越过不会推迟这组固定组合及其他登记的游玩轮次，但这组固定组合可能因前方一轮改为两人游玩而稍晚进入游玩位置。"

private const val FIXED_PAIR_PASSING_CANCEL = "可以在位置菜单或“更改游玩偏好”中取消。"

/** The title of the explanation that opens the first time a passing box is ticked. */
internal const val PASSING_DETAILS_TITLE = "允许后方的登记越过"

/**
 * The explanation of letting others pass, for one registration or for a fixed pair
 * ([fixedPair]). Shown by itself on first use, and from the question mark beside the tick box.
 */
internal fun passingDetails(fixedPair: Boolean): List<String> {
    val subject = if (fixedPair) "这组固定组合" else "这份登记"
    return listOf(
        "开启后，${subject}仍在原来的位置排队，真实的等待顺序不变。",
        "前方的登记允许他人加入，却因${subject}而独自占用一个等待位置时，" +
            "后方同样允许他人加入的登记可以越过${subject}，与前方的登记共同游玩。",
        "越过不会使${subject}晚一轮游玩；系统在每次越过前都会核对，会使任何登记晚一轮时不会越过。" +
            "前方一轮由一人游玩改为两人游玩，${subject}进入游玩位置的时间可能稍晚。",
        "越过${subject}的登记会在首页显示“将越过”。" +
            if (fixedPair) "可以随时在位置菜单或“更改游玩偏好”中取消。" else "可以随时在“更改游玩偏好”中取消。"
    )
}

/** Where a registration that lets others pass stands, for the line in its menu. */
internal sealed interface SoloPassingState {
    /** Turned on; nobody passes it at the moment. */
    data object Enabled : SoloPassingState

    /** The positions on screen show [passer] passing it to play with [front]. */
    data class Scheduled(val front: String, val passer: String) : SoloPassingState

    /** [passer] passed it and is playing with [front] in this round. */
    data class Playing(val front: String, val passer: String) : SoloPassingState
}

/**
 * The menu line for [state]; the part about the pass is highlighted. [fixedPair] words it for
 * the two registrations of a fixed pair.
 */
internal fun soloPassingStatusText(
    state: SoloPassingState,
    fixedPair: Boolean = false
): PairedMachineArrangementStatusText {
    val subject = if (fixedPair) "这组固定组合" else "这份登记"
    val cancel = if (fixedPair) FIXED_PAIR_PASSING_CANCEL else SOLO_PASSING_CANCEL
    return when (state) {
        SoloPassingState.Enabled -> PairedMachineArrangementStatusText(
            before = "已允许后方的登记越过${subject}与前方的登记共同游玩。$cancel",
            highlighted = null,
            after = ""
        )
        is SoloPassingState.Scheduled -> PairedMachineArrangementStatusText(
            before = "前方的“${state.front}”与后方的“${state.passer}”均允许他人加入，",
            highlighted = "“${state.passer}”将越过${subject}与“${state.front}”共同游玩",
            after = "，${subject}的游玩轮次不会推迟。$cancel"
        )
        is SoloPassingState.Playing -> PairedMachineArrangementStatusText(
            before = "前方的“${state.front}”与后方的“${state.passer}”均允许他人加入，",
            highlighted = "“${state.passer}”越过${subject}，正在与“${state.front}”共同游玩",
            after = "，${subject}的游玩轮次未推迟。$cancel"
        )
    }
}

/**
 * The state of [registrationKey] in [queue], or null when it does not let others pass. A pass in
 * the playing position lasts only for that round; the real waiting order never changes.
 */
internal fun soloPassingState(queue: MachineQueue, registrationKey: Int): SoloPassingState? {
    val registration = queue.allRegistrations.firstOrNull { it.key == registrationKey } ?: return null
    if (!registration.letsOthersPass) return null
    queue.playing.firstOrNull { registrationKey in it.passedOverKeys }?.let { passer ->
        val front = queue.playing.firstOrNull { it.key != passer.key } ?: return SoloPassingState.Enabled
        return SoloPassingState.Playing(front = front.displayId, passer = passer.displayId)
    }
    val passerKey = queue.projectedPasses().entries.firstOrNull { registrationKey in it.value }?.key
        ?: return SoloPassingState.Enabled
    val position = queue.waitingProjection(includeCommonPlayPreview = false).positions
        .firstOrNull { position -> position.registrations.any { it.key == passerKey } }
    val passer = position?.registrations?.firstOrNull { it.key == passerKey }
    val front = position?.registrations?.firstOrNull { it.key != passerKey }
    if (passer == null || front == null) return SoloPassingState.Enabled
    return SoloPassingState.Scheduled(front = front.displayId, passer = passer.displayId)
}

/**
 * The small line of each waiting registration that the positions on screen show passing
 * others, in place of its play preference: 将越过“小王”, or 将越过“小王”等 2 份登记.
 */
internal fun soloPassingTileNotices(queue: MachineQueue): Map<Int, String> {
    val passes = queue.projectedPasses()
    if (passes.isEmpty()) return emptyMap()
    val byKey = queue.waiting.associateBy(Registration::key)
    return passes.mapNotNull { (passerKey, passedKeys) ->
        val first = passedKeys.firstOrNull()?.let(byKey::get) ?: return@mapNotNull null
        val name = queueDisplayId(first.displayId)
        passerKey to if (passedKeys.size == 1) "将越过“$name”" else "将越过“$name”等 ${passedKeys.size} 份登记"
    }.toMap()
}
