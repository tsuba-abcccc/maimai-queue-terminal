package com.abcccc.maimaiqueue

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

internal const val MINUTES_PER_DAY = 24 * 60
internal const val DEFAULT_OPENING_MINUTES = 10 * 60
internal const val DEFAULT_CLOSING_MINUTES = 22 * 60
internal const val CLOSING_SOON_MINUTES = 15L

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

data class BusinessHoursStatus(
    val enabled: Boolean,
    val outsideBusinessHours: Boolean,
    val closingSoon: Boolean,
    val activeClosingAtMillis: Long?,
    val mostRecentClosingAtMillis: Long?,
    val mostRecentClosingOccurrenceId: String?
) {
    companion object {
        val Disabled = BusinessHoursStatus(
            enabled = false,
            outsideBusinessHours = false,
            closingSoon = false,
            activeClosingAtMillis = null,
            mostRecentClosingAtMillis = null,
            mostRecentClosingOccurrenceId = null
        )
    }
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
    val minutesUntilClosing = activeInterval?.let { interval ->
        ChronoUnit.MINUTES.between(now, interval.closing)
    }
    val fingerprint = businessHoursFingerprint(normalized, zoneId)

    return BusinessHoursStatus(
        enabled = true,
        outsideBusinessHours = activeInterval == null,
        closingSoon = minutesUntilClosing != null &&
            minutesUntilClosing in 0..CLOSING_SOON_MINUTES,
        activeClosingAtMillis = activeInterval?.closing?.toInstant()?.toEpochMilli(),
        mostRecentClosingAtMillis = mostRecentClosing?.toInstant()?.toEpochMilli(),
        mostRecentClosingOccurrenceId = mostRecentClosing?.let { closing ->
            "$fingerprint:${closing.toInstant().toEpochMilli()}"
        }
    )
}

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
