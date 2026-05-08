package com.example.otlhelper.domain.permissions

import com.example.otlhelper.domain.model.Role
import com.example.otlhelper.shared.auth.Permissions as SharedPermissions

/**
 * Android compatibility facade for the shared permission matrix.
 *
 * Keep UI code pointed here for now; the actual rules live in `:shared` so
 * desktop and Android cannot drift apart.
 */
object Permissions {
    fun canPostNews(role: Role): Boolean = SharedPermissions.canPostNews(role)
    fun canCreatePoll(role: Role): Boolean = SharedPermissions.canCreatePoll(role)
    fun canSeeFullStats(role: Role): Boolean = SharedPermissions.canSeeFullStats(role)
    fun canSeeVoteCounts(role: Role): Boolean = SharedPermissions.canSeeVoteCounts(role)
    fun canPin(role: Role): Boolean = SharedPermissions.canPin(role)
    fun canOpenCardMenu(role: Role): Boolean = SharedPermissions.canOpenCardMenu(role)
    fun canSeeNewsSearch(role: Role): Boolean = SharedPermissions.canSeeNewsSearch(role)
    fun canSeeContactsList(role: Role): Boolean = SharedPermissions.canSeeContactsList(role)
    fun canViewNews(role: Role): Boolean = SharedPermissions.canViewNews(role)
    fun canManageUsers(role: Role): Boolean = SharedPermissions.canManageUsers(role)
    fun canControlSystem(role: Role): Boolean = SharedPermissions.canControlSystem(role)
    fun canSeeAppStats(role: Role): Boolean = SharedPermissions.canSeeAppStats(role)
    fun canSendUrgent(role: Role): Boolean = SharedPermissions.canSendUrgent(role)
    fun canWipeDevice(role: Role): Boolean = SharedPermissions.canWipeDevice(role)
    fun canSeeAuditLog(role: Role): Boolean = SharedPermissions.canSeeAuditLog(role)
    fun canVote(role: Role): Boolean = SharedPermissions.canVote(role)
    fun canSeeAuthor(role: Role): Boolean = SharedPermissions.canSeeAuthor(role)
    fun canSeeOwnVote(role: Role): Boolean = SharedPermissions.canSeeOwnVote(role)
    fun hasAdminAccess(role: Role): Boolean = SharedPermissions.hasAdminAccess(role)
    fun isDeveloper(role: Role): Boolean = SharedPermissions.isDeveloper(role)
}
