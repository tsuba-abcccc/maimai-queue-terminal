package com.abcccc.maimaiqueue

/*
 * Text about letting the system arrange a registration between the two machines of a pair.
 *
 * The wording follows docs/copywriting-guide.md, subsection 自动在两台机台之间安排登记:
 * the feature is named 自动在两台机台之间安排登记; after 允许 / 已允许 the sentence says
 * 系统在……之间安排登记; the move itself is 自动转至. Nothing shown to players or staff speaks of
 * queueing at two machines. A text that is attached to one registration calls that
 * registration's machine 当前机台 and names only the other machine.
 */

/**
 * What a confirmation says before an action changes a waiting place by hand: the registrations
 * named here will not be arranged to the other machine until they have played a round.
 * Returns null when no registration is affected.
 */
internal fun pairedMachinePlaceGivenUpNotice(
    displayIds: List<String>,
    operationWord: String,
    pairedMachineName: String
): String? {
    if (displayIds.isEmpty()) return null
    val names = displayIds.take(3).joinToString("、") { "“$it”" }
    val suffix = if (displayIds.size > 3) "等 ${displayIds.size} 份登记" else ""
    val subject = if (displayIds.size == 1) "这份登记" else "这些登记"
    return "$names${suffix}已允许系统在当前机台和 $pairedMachineName 之间安排登记。" +
        "${operationWord}后，${subject}游玩一轮之前，不会被安排到另一机台。"
}

/** The same notice for a screen that cannot tell in advance which registrations will be moved. */
internal const val PAIRED_MACHINE_PLACE_GIVEN_UP_GENERAL_NOTICE =
    "已允许系统在两台机台之间安排的登记被移动后，游玩一轮之前不会被安排到另一机台。"

/** The label of the tick box. */
internal fun pairedMachineArrangementLabel(pairedMachineName: String): String =
    "允许系统在当前机台和 $pairedMachineName 之间安排登记"

/** The small text under the tick box. */
internal const val PAIRED_MACHINE_ARRANGEMENT_HINT =
    "开启后，这份登记将在符合条件时自动转至能够更早安排游玩的机台。"

/** Said of a registration that the system leaves at its machine until it has played. */
internal const val PAIRED_MACHINE_ARRANGEMENT_NOT_BEFORE_A_ROUND =
    "这份登记游玩一轮之前，不会被安排到另一机台。"

/**
 * The small text under the tick box while it is being turned on for a registration that is
 * already waiting: the choice only counts after that registration has played a round.
 */
internal const val PAIRED_MACHINE_ARRANGEMENT_STARTS_LATER_HINT =
    PAIRED_MACHINE_ARRANGEMENT_HINT + PAIRED_MACHINE_ARRANGEMENT_NOT_BEFORE_A_ROUND

internal const val PAIRED_MACHINE_ARRANGEMENT_FIXED_PAIR_REASON = "固定组合只能在当前机台游玩。"

/** The name of the feature, used where it has to be named: the title of its explanation. */
internal const val PAIRED_MACHINE_ARRANGEMENT_DETAILS_TITLE = "自动在两台机台之间安排登记"

/** One paragraph of the explanation, optionally followed by a short list. */
internal data class PairedMachineArrangementParagraph(
    val text: String,
    val items: List<String> = emptyList()
)

/**
 * How long a preview is shown at least before the registration is moved, in minutes. Players
 * are told this number; the other limits of the rules are internal and stay unnamed.
 */
internal val PAIRED_MACHINE_ARRANGEMENT_LEAD_MINUTES: Long =
    PairedArrangementRules().leadMillis / 60_000L

/**
 * The explanation. It opens by itself the first time the box is turned on, and at any time
 * through the question mark beside the tick box.
 */
internal fun pairedMachineArrangementDetails(
    pairedMachineName: String,
    leadMinutes: Long = PAIRED_MACHINE_ARRANGEMENT_LEAD_MINUTES
): List<PairedMachineArrangementParagraph> = listOf(
    PairedMachineArrangementParagraph("开启后，这份登记仍在当前机台排队。"),
    PairedMachineArrangementParagraph(
        "$pairedMachineName 能够更早安排这份登记游玩时，其等待顺序中会显示预览。" +
            "预览持续显示至少 $leadMinutes 分钟后，登记才会自动转至 $pairedMachineName 并进入游玩位置。"
    ),
    PairedMachineArrangementParagraph(
        "这份登记可能比当前机台的预计时间更早被安排游玩。" +
            "预览显示一段时间后，系统会发送排队通知。请留意现场提示和通知，合理安排时间。"
    ),
    PairedMachineArrangementParagraph(
        "自动转至 $pairedMachineName 后未到场时，登记会回到当前机台原来的等待顺序，" +
            "未到场记录不会增加，这项选择同时取消。"
    ),
    PairedMachineArrangementParagraph(
        "游玩一轮之后，登记会保留在上次游玩的机台，这项选择保持开启。"
    ),
    PairedMachineArrangementParagraph(
        "以下情况，登记不会被安排到另一机台：",
        listOf(
            "暂缓一次、轮空一次、暂时离开和待签到期间。",
            "等待顺序被调整、登记从游玩位置撤回，或在排队期间开启这项选择之后，直到这份登记游玩一轮。",
            "固定组合。"
        )
    ),
    PairedMachineArrangementParagraph("可以在“更改游玩偏好”中取消。")
)

/** Shown under the button while the explanation that opened by itself cannot be closed yet. */
internal fun pairedMachineArrangementCountdown(remainingSeconds: Int): String =
    "$remainingSeconds 秒后可以关闭"

/** What a registration's menu says about a preview that announces an arrangement. */
internal sealed interface PairedMachineArrangementAnnouncement {
    /** The preview is shown; the registration may be moved from [clockTime] on. */
    data class NotBefore(val clockTime: String) : PairedMachineArrangementAnnouncement

    /** The preview has been shown long enough: the move may happen at any moment. */
    data object AnyMoment : PairedMachineArrangementAnnouncement

    /** The preview is shown, but nothing is arranged while a round runs unusually long. */
    data object Held : PairedMachineArrangementAnnouncement
}

/**
 * The sentence that tells of a preview announcing a move, as a registration's own entry shows
 * it; null while nothing is announced. It is the part of the status line that is set in colour.
 */
internal fun pairedMachineArrangementAnnouncementSentence(
    pairedMachineName: String,
    announcement: PairedMachineArrangementAnnouncement?,
    /**
     * The position at which the other machine shows the preview, such as B1. Named when known
     * (maintainer, 2026-11-25: "合理引用位置编号"); otherwise the machine is named.
     */
    previewPosition: String? = null
): String? {
    val previewShown = if (previewPosition != null) {
        "$pairedMachineName 的位置 $previewPosition 已显示这份登记的预览"
    } else {
        "$pairedMachineName 的等待顺序中已显示这份登记的预览"
    }
    // A machine name is set off by a space (docs/copywriting-guide.md); 该位置 is not.
    val destination = if (previewPosition != null) "该位置" else " $pairedMachineName"
    return when (announcement) {
        null -> null
        is PairedMachineArrangementAnnouncement.NotBefore ->
            "$previewShown，最早 ${announcement.clockTime} 之后，" +
                "这份登记可能自动转至$destination，请合理安排时间。"
        PairedMachineArrangementAnnouncement.AnyMoment ->
            "$previewShown，这份登记随时可能自动转至$destination，请合理安排时间。"
        PairedMachineArrangementAnnouncement.Held -> "$previewShown。"
    }
}

/**
 * The line a registration's menu shows while the box is on, in three parts so that the sentence
 * about a running announcement can be set in colour.
 */
internal data class PairedMachineArrangementStatusText(
    val before: String,
    /** The announcement; null while there is none. */
    val highlighted: String?,
    val after: String
) {
    val plain: String get() = before + highlighted.orEmpty() + after
}

internal fun pairedMachineArrangementStatusText(
    pairedMachineName: String,
    waitsForNextRound: Boolean,
    announcement: PairedMachineArrangementAnnouncement? = null,
    previewPosition: String? = null
): PairedMachineArrangementStatusText {
    val allowed = "已允许系统在当前机台和 $pairedMachineName 之间安排登记。"
    val cancel = "可以在“更改游玩偏好”中取消。"
    val announced = pairedMachineArrangementAnnouncementSentence(pairedMachineName, announcement, previewPosition)
    return when {
        waitsForNextRound -> PairedMachineArrangementStatusText(
            before = allowed + PAIRED_MACHINE_ARRANGEMENT_NOT_BEFORE_A_ROUND,
            highlighted = null,
            after = cancel
        )
        announced != null -> PairedMachineArrangementStatusText(allowed, announced, cancel)
        else -> PairedMachineArrangementStatusText(
            before = allowed + "$pairedMachineName 能够更早安排这份登记游玩时，其等待顺序中会显示预览。",
            highlighted = null,
            after = cancel
        )
    }
}

/**
 * The line a registration's menu shows while the box is on, so that it is visible at a glance
 * and says where it can be cancelled.
 */
internal fun pairedMachineArrangementStatus(
    pairedMachineName: String,
    waitsForNextRound: Boolean,
    announcement: PairedMachineArrangementAnnouncement? = null
): String =
    pairedMachineArrangementStatusText(pairedMachineName, waitsForNextRound, announcement).plain

/**
 * The small line under a registration on the queue screen while a preview at the other machine
 * announces a move. It stands in place of the play preference and is set in colour.
 */
internal fun pairedMachineArrangementTileNotice(pairedMachineName: String): String =
    "可能转至 $pairedMachineName"

/**
 * Whether the tile of a waiting registration says so. Not while the count of the announcement is
 * paused (a round has run over): the other machine shows no preview then, and nothing is
 * arranged before the count goes on.
 */
internal fun announcesPairedArrangement(
    registration: Registration,
    record: PairedArrangementRecord?
): Boolean = registration.alsoQueuesAtPairedMachine &&
    record?.previewSinceMillis != null &&
    record.previewPausedAtMillis == null

/** What the grey entry that stands for a registration of the other machine explains. */
internal const val PAIRED_MACHINE_PREVIEW_TITLE = "另一机台登记的预览"

/** One sentence per line, as the maintainer asked for this explanation. */
internal fun pairedMachinePreviewExplanation(
    homeMachineName: String,
    previewMachineName: String,
    leadMinutes: Long = PAIRED_MACHINE_ARRANGEMENT_LEAD_MINUTES
): String = listOf(
    "这份登记在 $homeMachineName 排队，并已允许系统在 $homeMachineName 和 $previewMachineName 之间安排。",
    "按当前等待顺序和预计游玩时间，$previewMachineName 能够更早安排这份登记游玩。",
    "预览持续显示至少 $leadMinutes 分钟后，登记才会自动转至 $previewMachineName。",
    "预览不会改变真实队列。"
).joinToString("\n")

/**
 * What the confirmation of a transfer by hand adds for registrations whose box is on. Within
 * the pair the choice stays; a transfer to any other machine ends it.
 */
internal fun pairedMachineTransferNotice(
    displayIds: List<String>,
    sourceMachineName: String,
    pairedMachineName: String,
    destinationIsPairedMachine: Boolean,
    destinationMachineName: String
): String? {
    if (displayIds.isEmpty()) return null
    val names = displayIds.take(3).joinToString("、") { "“$it”" }
    val suffix = if (displayIds.size > 3) "等 ${displayIds.size} 份登记" else ""
    val allowed = "$names${suffix}已允许系统在 $sourceMachineName 和 $pairedMachineName 之间安排登记。"
    return if (destinationIsPairedMachine) {
        allowed + "登记留在 $sourceMachineName 排队时，也可能被更早安排到 $pairedMachineName 游玩；" +
            "转至 $pairedMachineName 后，只能加入其队尾。"
    } else {
        allowed + "转至 $destinationMachineName 后，将取消系统在 $sourceMachineName 和 $pairedMachineName 之间的安排。"
    }
}

/** The no-show confirmation of registrations that the system had arranged to this machine. */
internal const val PAIRED_MACHINE_ARRIVAL_NO_SHOW_TITLE = "确认未到场？"

internal fun pairedMachineArrivalNoShowDetail(
    names: String,
    group: Boolean,
    arrangedMachineName: String,
    originMachineName: String
): String {
    val subject = if (group) "这些登记" else "这份登记"
    return "${names}已自动转至 $arrangedMachineName。确认后，${subject}将回到 $originMachineName " +
        "原来的等待顺序，未到场记录不会增加；系统在 $originMachineName 和 $arrangedMachineName " +
        "之间的安排同时取消，需要时可以在“更改游玩偏好”中重新开启。"
}

internal fun pairedMachineArrivalNoShowConfirmLabel(originMachineName: String): String =
    "确认回到 $originMachineName"

/** Added to the ordinary no-show dialog of a group in which only some were arranged here. */
internal fun pairedMachineArrivalNoShowMixedNotice(
    names: String,
    arrangedMachineName: String,
    originMachineName: String
): String = "${names}已自动转至 $arrangedMachineName，不按所选方式处理：" +
    "登记会回到 $originMachineName 原来的等待顺序，未到场记录不会增加，系统在两台机台之间的安排同时取消。"

/** The same for a screen that cannot tell which registrations the system arranged here. */
internal const val PAIRED_MACHINE_ARRIVAL_NO_SHOW_GENERAL_NOTICE =
    "自动转至当前机台的登记未到场时，不按所选方式处理：" +
        "登记会回到原机台原来的等待顺序，未到场记录不会增加，系统在两台机台之间的安排同时取消。"
