package com.abcccc.maimaiqueue

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val PageBackground = Color(0xFFF5F5F7)
internal val CardBackground = Color.White
internal val PrimaryText = Color(0xFF1D1D1F)
internal val SecondaryText = Color(0xFF6E6E73)
internal val TertiaryText = Color(0xFF8E8E93)
internal val Separator = Color(0xFFD2D2D7)
internal val SystemBlue = Color(0xFF007AFF)
internal val SoftBlue = Color(0xFFEAF3FF)
internal val PlayingRegistrationBackground = Color(0xFFF8FBFF)
internal val Destructive = Color(0xFFFF3B30)
internal val AbsenceStatusColor = Color(0xFFB85C00)
/** Reminders: a stopped machine, business hours, an overlong round (docs/ui-guide.md, 颜色). */
internal val WarningText = Color(0xFF9A5B00)
internal val WarningBackground = Color(0xFFFFF4E5)
/** The heading mark and reason line of a machine that has stopped. */
internal val MachineStoppedColor = WarningText
/** Done, or just created. */
internal val SuccessText = Color(0xFF248A4B)
internal val SuccessBackground = Color(0xFFEAF8EF)
/** Grey preview tiles (共同游玩预览, a registration of the other machine). */
internal val PreviewBackground = Color(0xFFEDEDF1)
/** Category marks that only label, such as the machine of a log entry. */
internal val NeutralBadgeBackground = Color(0xFFEEEEF0)
internal val InputBorder = Color(0xFFA7A7AC)
/** Small status dots that ask for attention (sync retrying, closing soon). */
internal val AttentionDot = Color(0xFFFF9500)
internal val AbsenceStatusBackground = Color(0xFFFFF1DC)
internal val NoShowStatusColor = Color(0xFFC9342C)
internal val NoShowStatusBackground = Color(0xFFFFEFEE)
internal val OnlineRegistrationStatusColor = Color(0xFF087F73)
// A registration that a preview at the other machine of its pair announces a move for.
internal val PairedArrangementNoticeColor = SystemBlue
internal val OnlineRegistrationStatusBackground = Color(0xFFE8F7F4)
internal val DisabledBackground = Color(0xFFE8E8ED)
internal val PositionBackground = Color(0xFFFAFAFC)

internal val ControlRadius = 12.dp
internal val CardRadius = 16.dp
internal val DialogRadius = 20.dp
internal val QueueViewportHeight = 154.dp
internal val QueueRegistrationTileHeight = 96.dp

/** Home queue colours from the redesign mockup (docs/releases/ui-redesign-2026-11.md, 十). */
internal val PlayingPositionBorderColor = Color(0xFFCFE3FF)
internal val OvertimeBorderColor = Color(0xFFF0B35B)
internal val JoinCardBackground = Color(0xFFF4F9FF)
internal val JoinCardOutlineColor = Color(0xFF9CC3F5)

/** A selected row or the recommended choice: lighter than [SoftBlue] (mockup #F5F9FF). */
internal val SelectedRowBackground = Color(0xFFF5F9FF)

/** Placeholder text inside a field. */
internal val PlaceholderText = Color(0xFFAEAEB2)

/** The faintest marks: an empty value in a summary, the › at the end of a row. */
internal val FaintMarkColor = Color(0xFFC7C7CC)

/** Registration tiles let a little of their position's colour through. */
internal const val RegistrationTileAlpha = .72f

internal const val SOLO_ROUND_DURATION_MILLIS = 12 * 60_000L
internal const val SHARED_ROUND_DURATION_MILLIS = 15 * 60_000L
internal const val REMOTE_COMMAND_POLL_INTERVAL_MILLIS = 3_000L
internal const val INSTALLATION_PROBE_INITIAL_RETRY_MILLIS = 2_000L
internal const val INSTALLATION_PROBE_MAX_RETRY_MILLIS = 60_000L
internal const val INSTALLATION_IDENTITY_REFRESH_INTERVAL_MILLIS = 30_000L
internal const val LEGACY_INSTALLATION_REPROBE_INTERVAL_MILLIS = 5 * 60_000L
internal const val CLOUD_PROFILE_REFRESH_INTERVAL_MILLIS = 30_000L
internal const val INACTIVITY_TIMEOUT_MILLIS = 30_000L
internal const val INACTIVITY_WARNING_MILLIS = 5_000L
internal const val NEW_REGISTRATION_FEEDBACK_MILLIS = 8_000L
internal const val HOME_OPERATION_FEEDBACK_MILLIS = 6_000L
internal const val HOME_WARNING_FEEDBACK_MILLIS = 10_000L
internal const val HOME_UNDO_FEEDBACK_MILLIS = 10_000L

/** The only text sizes the terminal uses (docs/ui-guide.md, 字号). */
internal object QueueTextSize {
    val PageTitle = 30.sp
    val DialogTitle = 22.sp
    val CardTitle = 17.sp
    val Control = 15.sp
    val Body = 14.sp
    val Caption = 12.sp
    val Label = 11.sp
}

/** The only spacings the terminal uses (docs/ui-guide.md, 间距与圆角). */
internal object QueueSpacing {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
}

/** Every tappable control is at least this tall (docs/ui-guide.md, 屏幕尺寸与放大). */
internal val MinTouchTarget = 48.dp
internal val MenuItemMinHeight = 56.dp
