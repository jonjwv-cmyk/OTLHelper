package com.example.otlhelper.desktop.sap

/**
 * §0.11.9 — SAP target type из clipboard'а.
 *
 * Заказ (Order) — ME23N, 10 цифр, начинается на 42/43/44.
 * Поставка (Delivery) — VL02N, 7 или 8 цифр, начинается на 5 или 6.
 */
sealed class SapTarget(val number: String) {
    class Order(number: String) : SapTarget(number)
    class Delivery(number: String) : SapTarget(number)
}

object SapNumberParser {
    /**
     * Парсит текст из буфера обмена. Чистит whitespace, проверяет что
     * только цифры, потом матчит формат.
     *
     * Возвращает null если не подходит ни под заказ, ни под поставку.
     */
    fun parse(text: String?): SapTarget? {
        if (text.isNullOrBlank()) return null
        // Убираем пробелы, табы, CR/LF, NBSP
        val cleaned = text.trim().replace(Regex("[\\s\\u00A0]"), "")
        if (cleaned.isEmpty() || !cleaned.all { it.isDigit() }) return null
        val len = cleaned.length
        return when {
            len == 10 && (
                cleaned.startsWith("42") ||
                    cleaned.startsWith("43") ||
                    cleaned.startsWith("44")
                ) -> SapTarget.Order(cleaned)
            (len == 7 || len == 8) && (
                cleaned.startsWith("5") ||
                    cleaned.startsWith("6")
                ) -> SapTarget.Delivery(cleaned)
            else -> null
        }
    }
}
