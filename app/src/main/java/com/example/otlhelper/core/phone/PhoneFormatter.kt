package com.example.otlhelper.core.phone

/**
 * Форматирование телефонов для отображения в UI. Единый «русский» стиль:
 * `8 XXX XXX XX XX` — 8 + кодовая группа + 3/2/2 цифры. Раньше показывалось
 * raw вида `+7XXXXXXXXXX` — плохо читается, трудно продиктовать.
 *
 * Принимает любой формат на входе (+7, 8, +8, со скобками, с дефисами,
 * даже «пятизнак» локальный), приводит к 11 цифрам начиная с 8 если
 * возможно, иначе возвращает собранные цифры как есть.
 */
object PhoneFormatter {

    /** «+71234567890» / «+7 (123) 456-78-90» / «81234567890» → «8 123 456 78 90». */
    fun pretty(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        // Нормализация к 11 цифрам начиная с 8.
        val normalized = when {
            digits.length == 11 && digits.startsWith("7") -> "8${digits.substring(1)}"
            digits.length == 11 && digits.startsWith("8") -> digits
            digits.length == 10 -> "8$digits"
            else -> digits
        }
        return if (normalized.length == 11) {
            // 8 XXX XXX XX XX
            "${normalized[0]} ${normalized.substring(1, 4)} ${normalized.substring(4, 7)} " +
                "${normalized.substring(7, 9)} ${normalized.substring(9, 11)}"
        } else {
            // Нестандартная длина (короткий внутренний — 5-6 цифр) — оставляем
            // как есть с пробелами по три.
            normalized.chunked(3).joinToString(" ")
        }
    }

    /** Для `tel:` URI — всегда E.164-подобный формат с `+7`. */
    fun toTelUri(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val normalized = when {
            digits.length == 11 && digits.startsWith("8") -> "+7${digits.substring(1)}"
            digits.length == 11 && digits.startsWith("7") -> "+$digits"
            digits.length == 10 -> "+7$digits"
            else -> digits  // internal short — набирается как есть
        }
        return normalized
    }
}
