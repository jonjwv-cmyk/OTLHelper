package com.example.otlhelper.shared.api.dto

import com.example.otlhelper.shared.auth.Role

/**
 * §TZ-CLEANUP-2026-04-25 — wire-protocol DTO юзера.
 *
 * Минимальный набор полей которые сервер возвращает в `me` / `get_users`
 * / `create_user` ответах. Pure Kotlin/JVM — никаких JSON/Android зависимостей.
 *
 * **Конверсия из/в JSON** — на стороне Android/Desktop клиента (где
 * `org.json` доступен). Этот DTO — каноническая модель, общая для обеих
 * сторон. Платформо-специфичные поля (например, `LiveData` на Android,
 * `presence: PresenceState` на Desktop) **не должны** просачиваться сюда.
 *
 * При расширении — сначала проверить, общее ли это поле (Android+Desktop).
 * Если только одна сторона — оставить в платформенной модели.
 */
data class UserDto(
    /** Логин — primary key пользователя. */
    val login: String,

    /** ФИО (display name). */
    val fullName: String,

    /** Роль (canonical `Role` enum). */
    val role: Role,

    /** Активный пользователь (false = soft-deleted/disabled). */
    val isActive: Boolean = true,

    /** Временно приостановлен (отображается как «paused», но может вернуться). */
    val isSuspended: Boolean = false,

    /**
     * Маркер «должен сменить пароль при следующем логине».
     * Сервер выставляет при reset_password / create_user с initial password.
     */
    val mustChangePassword: Boolean = false,
)
