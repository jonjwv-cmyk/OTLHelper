package com.example.otlhelper.core.ui

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

private val yekZoneId = TimeZone.getTimeZone("Asia/Yekaterinburg").toZoneId()
// 12-hour display convention across the app — Yekaterinburg time, AM/PM
// marker. Locale pinned so the marker is the standard Latin "AM" / "PM"
// regardless of device language.
private val timeLocale = Locale.ENGLISH
private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", timeLocale)
private val dateTimeFormat = DateTimeFormatter.ofPattern("dd.MM h:mm a", timeLocale)

/**
 * Server ships timestamps in two formats:
 *   • ISO ("2026-04-15T14:32:00Z" or "...+03:00") — some endpoints.
 *   • SQLite-style ("2026-04-15 14:32:00", UTC implied) — most endpoints.
 *
 * Both are normalised to ISO and parsed once. The old fallback of
 * `raw.takeLast(5)` produced "49:05" (mm:ss) for SQLite-style strings
 * instead of "14:49" (HH:mm) — this is the single canonical parser.
 */
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

/** h:mm AM/PM — for chat bubble meta, dates shown in day-separators. */
internal fun formatTime(raw: String): String {
    val zdt = parseServerInstant(raw) ?: return ""
    return zdt.withZoneSameInstant(yekZoneId).format(timeFormat)
}

/** dd.MM h:mm AM/PM — for news cards, inbox rows, stats dialogs. */
internal fun formatDate(raw: String): String {
    val zdt = parseServerInstant(raw) ?: return ""
    return zdt.withZoneSameInstant(yekZoneId).format(dateTimeFormat)
}
