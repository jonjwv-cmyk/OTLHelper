package com.example.otlhelper.desktop.data.security

/**
 * §TZ-DESKTOP-DIST — обфусцированные константы.
 *
 * Список ключей (значения хранятся как XOR'нутые byte arrays ниже):
 *   - VPS_HOST_IP      — IP reverse-proxy перед CF
 *   - BASE_URL         — основной HTTPS endpoint
 *   - WS_URL           — WebSocket endpoint
 *   - HOST_BASE        — apex domain (для DNS lookup compare)
 *   - HOST_DOT_SUFFIX  — `.<apex>` (для hostname.endsWith check)
 *   - CERT_PATH        — путь к pinned self-signed VPS cert в resources
 *
 * Plaintext значения здесь не списком — они нужны только для runtime
 * (через [Obfuscator.decode]). При reverse-engineering EXE по обфусцированным
 * байтам всё равно можно их восстановить (это не секреты, а bootstrap-
 * данные), но бессмысленно дарить grep'у.
 *
 * Декодинг lazy — XOR-цикл выполняется один раз на JVM, результат закэширован
 * в `by lazy`. Память держит plaintext String, но JAR-pool обфусцирован.
 */
internal object Secrets {

    val VPS_HOST_IP: String by lazy {
        Obfuscator.decode(byteArrayOf(
            0x03, 0x6F, 0xBF.toByte(), 0xF5.toByte(),
            0x5C, 0x0D, 0xBA.toByte(), 0xCE.toByte(),
            0x0E, 0x74, 0xA4.toByte()
        ))
    }

    val BASE_URL: String by lazy {
        Obfuscator.decode(byteArrayOf(
            0x5F, 0x2E, 0xE5.toByte(), 0xB4.toByte(),
            0x1D, 0x19, 0xA7.toByte(), 0xD2.toByte(),
            0x56, 0x2A, 0xF8.toByte(), 0xEA.toByte(),
            0x01, 0x57, 0xE4.toByte(), 0x95.toByte(),
            0x52, 0x36, 0xE1.toByte(), 0xA1.toByte(),
            0x1C, 0x0D, 0xEB.toByte(), 0x92.toByte(),
            0x5A
        ))
    }

    val WS_URL: String by lazy {
        Obfuscator.decode(byteArrayOf(
            0x40, 0x29, 0xE2.toByte(), 0xFE.toByte(),
            0x41, 0x0C, 0xE9.toByte(), 0x8D.toByte(),
            0x5E, 0x74, 0xFE.toByte(), 0xB0.toByte(),
            0x02, 0x4B, 0xED.toByte(), 0x91.toByte(),
            0x47, 0x3F, 0xE3.toByte(), 0xEA.toByte(),
            0x0D, 0x4C, 0xE5.toByte(), 0xD2.toByte(),
            0x40, 0x29
        ))
    }

    val HOST_BASE: String by lazy {
        Obfuscator.decode(byteArrayOf(
            0x58, 0x2E, 0xFD.toByte(), 0xAC.toByte(),
            0x0B, 0x4F, 0xF8.toByte(), 0x98.toByte(),
            0x45, 0x74, 0xF2.toByte(), 0xAB.toByte(),
            0x03
        ))
    }

    val HOST_DOT_SUFFIX: String by lazy {
        Obfuscator.decode(byteArrayOf(
            0x19, 0x35, 0xE5.toByte(), 0xA8.toByte(),
            0x06, 0x46, 0xE4.toByte(), 0x8D.toByte(),
            0x52, 0x28, 0xBF.toByte(), 0xA7.toByte(),
            0x01, 0x4E
        ))
    }

    val CERT_PATH: String by lazy {
        Obfuscator.decode(byteArrayOf(
            0x58, 0x2E, 0xFD.toByte(), 0x9B.toByte(),
            0x18, 0x53, 0xFB.toByte(), 0xA2.toByte(),
            0x54, 0x3F, 0xE3.toByte(), 0xB0.toByte(),
            0x40, 0x53, 0xED.toByte(), 0x90.toByte()
        ))
    }
}
