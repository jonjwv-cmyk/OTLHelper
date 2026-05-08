package com.example.otlhelper.desktop.data.session

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.concurrent.TimeUnit

internal object DesktopTokenStore {
    private const val SERVICE = "com.otl.otldhelper.session"

    fun save(baseDir: File, login: String, token: String) {
        when (currentOs()) {
            Os.Mac -> if (saveMac(login, token)) return
            Os.Windows -> if (saveWindows(baseDir, token)) return
            Os.Other -> Unit
        }
        saveFallback(baseDir, token)
    }

    fun load(baseDir: File, login: String): String {
        return when (currentOs()) {
            Os.Mac -> loadMac(login)
            Os.Windows -> loadWindows(baseDir)
            Os.Other -> ""
        }.ifBlank { loadFallback(baseDir) }
    }

    fun clear(baseDir: File, login: String) {
        when (currentOs()) {
            Os.Mac -> runCatching {
                ProcessBuilder("security", "delete-generic-password", "-a", login, "-s", SERVICE)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(3, TimeUnit.SECONDS)
            }
            Os.Windows -> runCatching { tokenFile(baseDir, "dpapi").delete() }
            Os.Other -> Unit
        }
        runCatching { tokenFile(baseDir, "fallback").delete() }
    }

    private fun saveMac(login: String, token: String): Boolean = runCatching {
        val process = ProcessBuilder(
            "security",
            "add-generic-password",
            "-a",
            login,
            "-s",
            SERVICE,
            "-w",
            token,
            "-U",
        ).redirectErrorStream(true).start()
        process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun loadMac(login: String): String = runCatching {
        val process = ProcessBuilder(
            "security",
            "find-generic-password",
            "-a",
            login,
            "-s",
            SERVICE,
            "-w",
        ).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching ""
        process.inputStream.bufferedReader().readText().trim()
    }.getOrDefault("")

    private fun saveWindows(baseDir: File, token: String): Boolean = runCatching {
        val file = tokenFile(baseDir, "dpapi")
        val script = """
            ${'$'}plain = [Text.Encoding]::UTF8.GetBytes([Environment]::GetEnvironmentVariable('OTLD_HELPER_TOKEN'))
            ${'$'}enc = [Security.Cryptography.ProtectedData]::Protect(${'$'}plain, ${'$'}null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
            [IO.File]::WriteAllText('${safePath(file)}', [Convert]::ToBase64String(${'$'}enc))
        """.trimIndent()
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true)
            .apply { environment()["OTLD_HELPER_TOKEN"] = token }
            .start()
        process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun loadWindows(baseDir: File): String = runCatching {
        val file = tokenFile(baseDir, "dpapi")
        if (!file.exists()) return@runCatching ""
        val script = """
            ${'$'}enc = [Convert]::FromBase64String([IO.File]::ReadAllText('${safePath(file)}'))
            ${'$'}plain = [Security.Cryptography.ProtectedData]::Unprotect(${'$'}enc, ${'$'}null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
            [Text.Encoding]::UTF8.GetString(${'$'}plain)
        """.trimIndent()
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching ""
        process.inputStream.bufferedReader().readText().trim()
    }.getOrDefault("")

    private fun saveFallback(baseDir: File, token: String) {
        val file = tokenFile(baseDir, "fallback")
        file.writeText(token)
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private fun loadFallback(baseDir: File): String {
        val file = tokenFile(baseDir, "fallback")
        return if (file.exists()) file.readText().trim() else ""
    }

    private fun tokenFile(baseDir: File, kind: String): File = File(baseDir, "session_token.$kind")

    private fun safePath(file: File): String = file.absolutePath.replace("'", "''")

    private fun currentOs(): Os {
        val name = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            name.contains("mac") -> Os.Mac
            name.contains("win") -> Os.Windows
            else -> Os.Other
        }
    }

    private enum class Os { Mac, Windows, Other }
}
