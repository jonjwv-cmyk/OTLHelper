package com.example.otlhelper.shared.auth

/**
 * Canonical user role shared by Android and desktop clients.
 *
 * Server wire values:
 * - `user` -> [User]
 * - `client` -> [Client]
 * - `admin` / legacy `administrator` -> [Admin]
 * - `developer` / legacy `superadmin` -> [Developer]
 */
enum class Role(
    val wireName: String,
    val displayName: String,
) {
    User("user", "Пользователь"),
    Client("client", "Клиент"),
    Admin("admin", "Администратор"),
    Developer("developer", "Разработчик");

    val isAdmin: Boolean get() = this == Admin || this == Developer
    val isSuperAdmin: Boolean get() = this == Developer
    val canCreateNews: Boolean get() = isAdmin
    val canReact: Boolean get() = true
    val canScheduleMessages: Boolean get() = isAdmin
    val canSeeReactionVoters: Boolean get() = isSuperAdmin
    val canManageUsers: Boolean get() = isSuperAdmin

    companion object {
        fun fromString(raw: String?): Role = when (raw?.trim()?.lowercase()) {
            "developer", "superadmin" -> Developer
            "admin", "administrator" -> Admin
            "client" -> Client
            else -> User
        }

        fun fromServer(raw: String?): Role = fromString(raw)
    }
}
