package com.abcccc.maimaiqueue

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject

internal const val MINUTES_PER_DAY = 24 * 60
internal const val DEFAULT_OPENING_MINUTES = 10 * 60
internal const val DEFAULT_CLOSING_MINUTES = 22 * 60
internal const val CLOSING_WARNING_MINUTES = 30L
internal const val CLOSING_GRACE_MINUTES = 20L
internal const val CLOSING_GRACE_MILLIS = CLOSING_GRACE_MINUTES * 60_000L

data class DailyBusinessHours(
    val openingMinutes: Int = DEFAULT_OPENING_MINUTES,
    val closingMinutes: Int = DEFAULT_CLOSING_MINUTES
) {
    fun normalized(): DailyBusinessHours = copy(
        openingMinutes = openingMinutes.coerceIn(0, MINUTES_PER_DAY - 1),
        closingMinutes = closingMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
    )
}

data class BusinessHoursSettings(
    val enabled: Boolean = false,
    val useWeeklySchedule: Boolean = false,
    val defaultHours: DailyBusinessHours = DailyBusinessHours(),
    val weeklyHours: Map<DayOfWeek, DailyBusinessHours> = defaultWeeklyBusinessHours()
) {
    fun hoursFor(dayOfWeek: DayOfWeek): DailyBusinessHours =
        if (useWeeklySchedule) weeklyHours[dayOfWeek] ?: defaultHours else defaultHours

    fun normalized(): BusinessHoursSettings = copy(
        defaultHours = defaultHours.normalized(),
        weeklyHours = DayOfWeek.entries.associateWith { day ->
            (weeklyHours[day] ?: defaultHours).normalized()
        }
    )
}

internal fun BusinessHoursSettings.toVenueSettingsJson(): JSONObject = JSONObject().apply {
    val normalized = normalized()
    put("enabled", normalized.enabled)
    put("use_weekly_schedule", normalized.useWeeklySchedule)
    put("default_hours", normalized.defaultHours.toVenueSettingsJson())
    put("weekly_hours", JSONObject().apply {
        DayOfWeek.entries.forEach { day ->
            put(day.name, normalized.hoursFor(day).toVenueSettingsJson())
        }
    })
}

private fun DailyBusinessHours.toVenueSettingsJson(): JSONObject = JSONObject().apply {
    val normalized = normalized()
    put("opening_minutes", normalized.openingMinutes)
    put("closing_minutes", normalized.closingMinutes)
}

internal fun JSONObject.toBusinessHoursSettingsOrNull(): BusinessHoursSettings? {
    if (isNull("enabled")) return null
    fun readDaily(source: JSONObject?, fallback: DailyBusinessHours): DailyBusinessHours {
        if (source == null) return fallback
        return DailyBusinessHours(
            openingMinutes = source.optInt("opening_minutes", fallback.openingMinutes),
            closingMinutes = source.optInt("closing_minutes", fallback.closingMinutes)
        ).normalized()
    }
    val defaultHours = readDaily(optJSONObject("default_hours"), DailyBusinessHours())
    val weekly = optJSONObject("weekly_hours")
    return BusinessHoursSettings(
        enabled = optBoolean("enabled", false),
        useWeeklySchedule = optBoolean("use_weekly_schedule", false),
        defaultHours = defaultHours,
        weeklyHours = DayOfWeek.entries.associateWith { day ->
            readDaily(weekly?.optJSONObject(day.name), defaultHours)
        }
    ).normalized()
}

data class BusinessHoursStatus(
    val enabled: Boolean,
    val outsideBusinessHours: Boolean,
    val closingSoon: Boolean,
    val closingGracePeriod: Boolean,
    val activeClosingAtMillis: Long?,
    val registrationClosesAtMillis: Long?,
    val mostRecentClosingAtMillis: Long?,
    val mostRecentClosingOccurrenceId: String?
) {
    companion object {
        val Disabled = BusinessHoursStatus(
            enabled = false,
            outsideBusinessHours = false,
            closingSoon = false,
            closingGracePeriod = false,
            activeClosingAtMillis = null,
            registrationClosesAtMillis = null,
            mostRecentClosingAtMillis = null,
            mostRecentClosingOccurrenceId = null
        )
    }
}

internal enum class BusinessHoursCloseTrigger {
    QUEUE_EMPTY_DURING_GRACE,
    GRACE_PERIOD_EXPIRED
}

internal fun defaultWeeklyBusinessHours(): Map<DayOfWeek, DailyBusinessHours> =
    DayOfWeek.entries.associateWith { DailyBusinessHours() }

internal fun evaluateBusinessHours(
    settings: BusinessHoursSettings,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): BusinessHoursStatus {
    if (!settings.enabled) return BusinessHoursStatus.Disabled

    val normalized = settings.normalized()
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val intervals = buildList {
        // Eight previous start dates cover the preceding weekly closing occurrence.
        for (dayOffset in -8L..0L) {
            add(businessInterval(normalized, now.toLocalDate().plusDays(dayOffset), zoneId))
        }
    }
    val activeInterval = intervals.lastOrNull { interval ->
        !now.isBefore(interval.opening) && now.isBefore(interval.closing)
    }
    val mostRecentClosing = intervals.asSequence()
        .map(BusinessInterval::closing)
        .filter { closing -> !closing.isAfter(now) }
        .maxOrNull()
    val closingGraceEndsAt = mostRecentClosing
        ?.plusMinutes(CLOSING_GRACE_MINUTES)
        ?.takeIf { graceEnd ->
            activeInterval == null && now.isBefore(graceEnd)
        }
    val closingGracePeriod = closingGraceEndsAt != null
    val fingerprint = businessHoursFingerprint(normalized, zoneId)

    return BusinessHoursStatus(
        enabled = true,
        outsideBusinessHours = activeInterval == null,
        closingSoon = activeInterval?.let { interval ->
            !now.isBefore(interval.closing.minusMinutes(CLOSING_WARNING_MINUTES))
        } == true,
        closingGracePeriod = closingGracePeriod,
        activeClosingAtMillis = activeInterval?.closing?.toInstant()?.toEpochMilli(),
        registrationClosesAtMillis = closingGraceEndsAt?.toInstant()?.toEpochMilli(),
        mostRecentClosingAtMillis = mostRecentClosing?.toInstant()?.toEpochMilli(),
        mostRecentClosingOccurrenceId = mostRecentClosing?.let { closing ->
            "$fingerprint:${closing.toInstant().toEpochMilli()}"
        }
    )
}

internal fun estimatedWaitExtendsPastClosing(
    status: BusinessHoursStatus,
    nowMillis: Long,
    estimatedWaitMinutes: Long?
): Boolean {
    val closingAtMillis = status.activeClosingAtMillis ?: return false
    val waitMinutes = estimatedWaitMinutes?.takeIf { it >= 0L } ?: return false
    val estimatedPlayingAtMillis = nowMillis + waitMinutes * 60_000L
    return estimatedPlayingAtMillis > closingAtMillis
}

internal fun businessHoursCloseTrigger(
    status: BusinessHoursStatus,
    nowMillis: Long,
    registrationCount: Int,
    lastHandledOccurrenceId: String?
): BusinessHoursCloseTrigger? {
    if (!status.enabled || !status.outsideBusinessHours) return null
    val occurrenceId = status.mostRecentClosingOccurrenceId ?: return null
    if (occurrenceId == lastHandledOccurrenceId) return null
    val closingAtMillis = status.mostRecentClosingAtMillis ?: return null
    val graceEndsAtMillis = closingAtMillis + CLOSING_GRACE_MILLIS
    return when {
        nowMillis >= graceEndsAtMillis -> BusinessHoursCloseTrigger.GRACE_PERIOD_EXPIRED
        status.closingGracePeriod && registrationCount == 0 ->
            BusinessHoursCloseTrigger.QUEUE_EMPTY_DURING_GRACE
        else -> null
    }
}

internal fun hasUnhandledClosingOccurrence(
    status: BusinessHoursStatus,
    lastHandledOccurrenceId: String?
): Boolean = status.enabled &&
    status.outsideBusinessHours &&
    status.mostRecentClosingOccurrenceId != null &&
    status.mostRecentClosingOccurrenceId != lastHandledOccurrenceId

internal fun isActiveClosingGracePeriod(
    status: BusinessHoursStatus,
    lastHandledOccurrenceId: String?
): Boolean = status.closingGracePeriod &&
    hasUnhandledClosingOccurrence(status, lastHandledOccurrenceId)

internal fun formatBusinessTime(minutes: Int): String {
    val normalized = minutes.coerceIn(0, MINUTES_PER_DAY - 1)
    return "%02d:%02d".format(normalized / 60, normalized % 60)
}

private data class BusinessInterval(
    val opening: ZonedDateTime,
    val closing: ZonedDateTime
)

private fun businessInterval(
    settings: BusinessHoursSettings,
    startDate: LocalDate,
    zoneId: ZoneId
): BusinessInterval {
    val hours = settings.hoursFor(startDate.dayOfWeek).normalized()
    val opening = startDate.atTime(minutesToLocalTime(hours.openingMinutes)).atZone(zoneId)
    val closingDate = if (hours.closingMinutes > hours.openingMinutes) {
        startDate
    } else {
        startDate.plusDays(1)
    }
    val closing = closingDate.atTime(minutesToLocalTime(hours.closingMinutes)).atZone(zoneId)
    return BusinessInterval(opening = opening, closing = closing)
}

private fun minutesToLocalTime(minutes: Int): LocalTime {
    val normalized = minutes.coerceIn(0, MINUTES_PER_DAY - 1)
    return LocalTime.of(normalized / 60, normalized % 60)
}

private fun businessHoursFingerprint(settings: BusinessHoursSettings, zoneId: ZoneId): String =
    buildString {
        append(zoneId.id)
        append('|')
        append(if (settings.useWeeklySchedule) 'W' else 'D')
        append('|')
        append(settings.defaultHours.openingMinutes)
        append('-')
        append(settings.defaultHours.closingMinutes)
        if (settings.useWeeklySchedule) {
            DayOfWeek.entries.forEach { day ->
                val hours = settings.hoursFor(day)
                append('|')
                append(day.value)
                append(':')
                append(hours.openingMinutes)
                append('-')
                append(hours.closingMinutes)
            }
        }
    }
