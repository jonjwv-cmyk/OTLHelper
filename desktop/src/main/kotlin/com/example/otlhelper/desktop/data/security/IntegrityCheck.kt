package com.example.otlhelper.desktop.data.security

import java.io.File
import java.security.MessageDigest

/**
 * §TZ-DESKTOP-DIST — self-SHA-256 запускающегося JAR'а.
 *
 * Использование:
 *   val sha = IntegrityCheck.selfSha256
 *   ApiClient.heartbeat(... sha ...)  // сервер может logать / blacklist'ить
 *
 * Что это даёт: единственная реальная защита от tampering на JVM —
 * server-side enforcement. Клиент НЕ может надёжно верифицировать сам
 * себя (любая ветка `if (sha != expected) exit()` обходится Recaf'ом).
 * Зато сервер, получая `binary_sha` в каждом запросе, может:
 *   • Сравнить с whitelist'ом валидных хешей текущего релиза.
 *   • Не выдавать токен на login если хеш чужой.
 *   • Залогать аномалии для разбора.
 *
 * Хеш считается ЛЕНИВО на первом обращении (десятки мс на 100MB JAR
 * → не блокирует UI старт).
 *
 * При запуске из IDE/`./gradlew :desktop:run` (classes из build/classes/)
 * вернёт пустую строку — protectionDomain.codeSource указывает на папку,
 * не на JAR. Сервер должен относиться к пустому хешу как «dev-сборка»
 * и применять менее строгие правила (или вообще не enforce'ить).
 */
object IntegrityCheck {

    val selfSha256: String by lazy { computeSelfSha() }

    val selfPath: String by lazy {
        runCatching {
            val cs = IntegrityCheck::class.java.protectionDomain.codeSource
            cs?.location?.toURI()?.let { File(it).absolutePath }.orEmpty()
        }.getOrDefault("")
    }

    val isRunningFromJar: Boolean by lazy {
        runCatching { File(selfPath).isFile && selfPath.endsWith(".jar") }
            .getOrDefault(false)
    }

    private fun computeSelfSha(): String {
        return runCatching {
            val cs = IntegrityCheck::class.java.protectionDomain.codeSource
                ?: return@runCatching ""
            val loc = cs.location ?: return@runCatching ""
            val file = File(loc.toURI())
            if (!file.isFile) return@runCatching ""  // dev-loop, не bundled JAR
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }
}
