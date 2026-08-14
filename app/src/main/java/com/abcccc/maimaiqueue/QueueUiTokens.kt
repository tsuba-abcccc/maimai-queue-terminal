package com.abcccc.maimaiqueue

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
internal val AbsenceStatusBackground = Color(0xFFFFF1DC)
internal val NoShowStatusColor = Color(0xFFC9342C)
internal val NoShowStatusBackground = Color(0xFFFFEFEE)
internal val OnlineRegistrationStatusColor = Color(0xFF087F73)
internal val OnlineRegistrationStatusBackground = Color(0xFFE8F7F4)
internal val DisabledBackground = Color(0xFFE8E8ED)
internal val PositionBackground = Color(0xFFFAFAFC)

internal val ControlRadius = 11.dp
internal val CardRadius = 16.dp
internal val DialogRadius = 20.dp
internal val QueueViewportHeight = 154.dp
internal val QueueRegistrationTileHeight = 96.dp

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
