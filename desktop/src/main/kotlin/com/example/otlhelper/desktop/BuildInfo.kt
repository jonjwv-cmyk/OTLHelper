package com.example.otlhelper.desktop

/**
 * §TZ-DESKTOP-DIST — version и platform-scope для update-flow.
 *
 * [VERSION] — наша внутренняя версия desktop-сборки (НЕ jpackage packageVersion,
 * который захардкожен 1.0.0 ради совместимости с jpackage MAJOR ≥ 1). При
 * релизе bump'ит otl-release-desktop.sh через sed.
 *
 * [SCOPE] — "desktop-mac" / "desktop-win" / "desktop-linux", соответствует
 * полю app_scope в D1.app_version. Скрипт обновляет нужный scope при
 * `./otl-release-desktop.sh mac` / `... win`.
 */
object BuildInfo {

    const val VERSION = "0.11.14.2"

    val OS: String by lazy {
        val raw = System.getProperty("os.name", "").lowercase()
        when {
            raw.contains("mac") || raw.contains("darwin") -> "mac"
            raw.contains("win") -> "win"
            raw.contains("nux") || raw.contains("nix") -> "linux"
            else -> "unknown"
        }
    }

    val SCOPE: String by lazy { "desktop-$OS" }

    val IS_WINDOWS: Boolean get() = OS == "win"
    val IS_MAC: Boolean get() = OS == "mac"
}
