package com.example.otlhelper.desktop.data.security

/**
 * §TZ-DESKTOP-DIST — лёгкая XOR-обфускация для строковых констант.
 *
 * Назначение: убрать plaintext-секреты из бинаря Windows EXE/macOS DMG.
 * После ProGuard'а классы переименованы в a/b/c, но строки (`http://...`,
 * IP'шники, имена ресурсов) остаются в pool'е JAR'а — `strings file.exe`
 * мгновенно их находит. XOR-кодинг прячет их от такого grep'а.
 *
 * Это НЕ криптография. Reverse-engineer с декомпилятором достанет ключ
 * из bytecode за 10 секунд. Реальная защита — server-side enforcement
 * (HMAC signing, cert pinning, /apk и /desktop через VPS только). XOR —
 * только cosmetic barrier.
 *
 * Использование:
 *   1. Запустить `./gradlew :desktop:test --tests "*ObfuscatorTest*"` —
 *      печатает encoded byte literal для каждой строки в [bootstrapStrings].
 *   2. Скопировать вывод в нужный `byteArrayOf(...)` в коде.
 *   3. Расшифровать в runtime через [decode].
 */
internal object Obfuscator {

    /** Псевдо-случайный keystream. Длиннее → меньше повтор'а паттерна
     *  на длинных строках. 8 байт достаточно для большинства констант. */
    private val key = intArrayOf(0x37, 0x5A, 0x91, 0xC4, 0x6E, 0x23, 0x88, 0xFD)

    fun decode(encoded: ByteArray): String {
        val out = ByteArray(encoded.size)
        for (i in encoded.indices) {
            out[i] = (encoded[i].toInt() xor key[i % key.size]).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    /** Build-time helper. Запускается из теста, не из production-кода. */
    fun encode(plain: String): ByteArray {
        val src = plain.toByteArray(Charsets.UTF_8)
        return ByteArray(src.size) { i ->
            (src[i].toInt() xor key[i % key.size]).toByte()
        }
    }

    /** Форматирует [bytes] как Kotlin literal `byteArrayOf(0x.., ...)`. */
    fun toKotlinLiteral(bytes: ByteArray): String {
        val cells = bytes.joinToString(", ") { b ->
            val v = b.toInt() and 0xFF
            // Negative-byte literals (0x80+) требуют явного toByte().
            if (v >= 0x80) "0x%02X.toByte()".format(v) else "0x%02X".format(v)
        }
        return "byteArrayOf($cells)"
    }
}
