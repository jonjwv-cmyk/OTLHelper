package com.example.otlhelper.shared.auth

/**
 * Single permission matrix for all clients.
 *
 * UI code should ask this object for capabilities instead of comparing role
 * strings or enum constants locally.
 */
object Permissions {
    private fun isRegularUser(role: Role): Boolean = role == Role.User || role == Role.Client

    fun canPostNews(role: Role): Boolean = !isRegularUser(role)
    fun canCreatePoll(role: Role): Boolean = !isRegularUser(role)
    fun canSeeFullStats(role: Role): Boolean = !isRegularUser(role)
    fun canSeeVoteCounts(role: Role): Boolean = !isRegularUser(role)
    fun canPin(role: Role): Boolean = !isRegularUser(role)
    fun canOpenCardMenu(role: Role): Boolean = !isRegularUser(role)
    fun canSeeNewsSearch(role: Role): Boolean = !isRegularUser(role)
    fun canSeeContactsList(role: Role): Boolean = !isRegularUser(role)
    fun canViewNews(role: Role): Boolean = role != Role.Client

    fun canManageUsers(role: Role): Boolean = role == Role.Developer
    fun canControlSystem(role: Role): Boolean = role == Role.Developer
    fun canSeeAppStats(role: Role): Boolean = role == Role.Developer
    fun canSendUrgent(role: Role): Boolean = role == Role.Developer
    fun canWipeDevice(role: Role): Boolean = role == Role.Developer
    fun canSeeAuditLog(role: Role): Boolean = role == Role.Developer

    fun canVote(role: Role): Boolean = true
    fun canSeeAuthor(role: Role): Boolean = true
    fun canSeeOwnVote(role: Role): Boolean = true

    fun hasAdminAccess(role: Role): Boolean = !isRegularUser(role)
    fun isDeveloper(role: Role): Boolean = role == Role.Developer
}
