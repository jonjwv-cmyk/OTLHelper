package com.example.otlhelper.core.ui

/**
 * Phone display formats:
 *   11 digits starting 73435/83435 (Nizhny Tagil city)  →  "49 02 82"
 *   11 digits (mobile)                                   →  "8 922 168 65 85"
 *   10 digits (no country code)                          →  "922 168 65 85"
 *    7 digits                                            →  "XXX-XX-XX"
 *    6 digits (local)                                    →  "49 69 50"
 *    5 digits (internal)                                 →  "7 14 15"
 *    4 digits                                            →  "XX-XX"
 *    other                                               →  raw string
 *
 * Dial formats:
 *   11-digit 7/8 prefix →  "+7XXXXXXXXXX"
 *   10-digit            →  "+7XXXXXXXXXX"
 *   6-digit local       →  "+73435XXXXXX"  (Nizhny Tagil city code 3435)
 *   ≤5 digits           →  null (internal extension, not dialable)
 */

private const val NT_PREFIX_7 = "73435"  // +7 3435 <local>
private const val NT_PREFIX_8 = "83435"  // 8 3435  <local>

fun formatPhoneDisplay(raw: String): String {
    val d = raw.trim().filter { it.isDigit() }

    // Nizhny Tagil city number encoded as 11 digits (73435XXXXXX / 83435XXXXXX):
    // show only the local part "XX XX XX", the city code is implicit.
    if (d.length == 11 && (d.startsWith(NT_PREFIX_7) || d.startsWith(NT_PREFIX_8))) {
        val local = d.substring(5)
        return "${local.substring(0, 2)} ${local.substring(2, 4)} ${local.substring(4)}"
    }

    return when (d.length) {
        11 -> "${d[0]} ${d.substring(1, 4)} ${d.substring(4, 7)} ${d.substring(7, 9)} ${d.substring(9)}"
        10 -> "${d.substring(0, 3)} ${d.substring(3, 6)} ${d.substring(6, 8)} ${d.substring(8)}"
        7  -> "${d.substring(0, 3)}-${d.substring(3, 5)}-${d.substring(5)}"
        6  -> "${d.substring(0, 2)} ${d.substring(2, 4)} ${d.substring(4)}"
        5  -> "${d[0]} ${d.substring(1, 3)} ${d.substring(3)}"
        4  -> "${d.substring(0, 2)}-${d.substring(2)}"
        else -> raw.trim()
    }
}

/** Returns dial string, or null if the number is an internal extension (≤5 digits). */
fun getDialNumber(raw: String): String? {
    val d = raw.trim().filter { it.isDigit() }
    return when {
        d.length == 11 && (d[0] == '7' || d[0] == '8') -> "+7${d.substring(1)}"
        d.length == 10 -> "+7$d"
        d.length == 6  -> "+73435$d"
        d.length <= 5  -> null
        else           -> d
    }
}

/**
 * Split a raw phones string that may contain multiple numbers separated by
 * commas, semicolons, or newlines. Returns a list of non-blank phone strings.
 */
fun splitPhones(raw: String): List<String> =
    raw.split(",", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }
