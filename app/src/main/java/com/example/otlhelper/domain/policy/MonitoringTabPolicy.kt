package com.example.otlhelper.domain.policy

import com.example.otlhelper.domain.model.Role
import com.example.otlhelper.shared.auth.RolePolicies

/**
 * Политика именования вкладки MONITORING (§3.3 паспорта v3).
 *
 * - [Role.User] → «Чат» (один чат со всеми админами)
 * - [Role.Admin], [Role.Developer] → «Чаты» (список пользователей как контактов)
 *
 * Правило паспорта: **запрещено хардкодить метку вкладки в UI**.
 * Любой композабл, рендерящий метку MONITORING, обязан получать её через [chatTabLabel]
 * (а не через локальное `if (isAdmin) ...`).
 */
object MonitoringTabPolicy {

    /** Заголовок вкладки MONITORING в bottom bar. */
    fun chatTabLabel(role: Role): String = RolePolicies.chatTabLabel(role)
}
