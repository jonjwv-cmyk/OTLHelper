package com.example.otlhelper.desktop.util

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * §TZ-DESKTOP-0.1.0 — прямая копия app/core/ui/DateTimeFormatters.kt.
 * Часовой пояс Екатеринбург. 12-часовой формат AM/PM, Latin-marker.
 */

private val yekZoneId = TimeZone.getTimeZone("Asia/Yekaterinburg").toZoneId()
private val timeLocale = Locale.ENGLISH
private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", timeLocale)
private val dateTimeFormat = DateTimeFormatter.ofPattern("dd.MM h:mm a", timeLocale)

private fun parseServerInstant(raw: String): ZonedDateTime? {
    if (raw.isBlank()) return null
    val iso = if (raw.contains('T')) {
        if (raw.endsWith("Z") || raw.contains('+') || raw.lastIndexOf('-') > 10) raw
        else "${raw}Z"
    } else {
        "${raw.replace(' ', 'T')}Z"
    }
    return runCatching { ZonedDateTime.parse(iso) }.getOrNull()
}

/** h:mm AM/PM — для bubble meta, day-separators. */
fun formatTime(raw: String): String {
    val zdt = parseServerInstant(raw) ?: return ""
    return zdt.withZoneSameInstant(yekZoneId).format(timeFormat)
}

/** dd.MM h:mm AM/PM — для news cards, inbox rows, stats dialogs. */
fun formatDate(raw: String): String {
    val zdt = parseServerInstant(raw) ?: return ""
    return zdt.withZoneSameInstant(yekZoneId).format(dateTimeFormat)
}
