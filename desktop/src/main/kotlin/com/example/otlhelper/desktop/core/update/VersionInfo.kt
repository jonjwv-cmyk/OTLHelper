package com.example.otlhelper.desktop.core.update

/**
 * §TZ-DESKTOP-UX-2026-04 — снимок версий с сервера, показывается в правой
 * панели и в Settings.
 *
 * - [androidCurrent] — `app_version.current_version` для scope = main
 *   (последний APK Android-приложения).
 * - [baseVersion] — `base_meta.base_version` (общая для платформ).
 * - [desktopCurrent] — `app_version.current_version` для scope =
 *   "desktop-mac"/"desktop-win" (последний DMG/EXE у нас).
 * - [desktopUpdateUrl] / [desktopUpdateSha] — для тех же scope'ов:
 *   передаются в [AppUpdate.downloadInstaller], чтобы кнопка «Обновить»
 *   из Settings могла триггерить тот же download-flow что
 *   `SoftUpdateDialog`.
 *
 * Пустые поля = ещё не получили / нет сети — UI показывает «—».
 */
data class VersionInfo(
    val androidCurrent: String = "",
    val baseVersion: String = "",
    /** ISO-like timestamp `2026-04-26 14:30:00` (UTC) — server `base_updated_at`. */
    val baseUpdatedAt: String = "",
    val desktopCurrent: String = "",
    val desktopUpdateUrl: String = "",
    val desktopUpdateSha: String = "",
    val desktopForceUpdate: Boolean = false,
)
