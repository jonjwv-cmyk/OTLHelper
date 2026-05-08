package com.example.otlhelper.shared.auth

object RolePolicies {
    fun chatTabLabel(role: Role): String = if (role == Role.User) "Чат" else "Чаты"
}
