package com.example.otlhelper.shared.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleContractsTest {
    @Test
    fun parsesServerAndLegacyRoleNames() {
        assertEquals(Role.User, Role.fromString(null))
        assertEquals(Role.User, Role.fromString("unknown"))
        assertEquals(Role.Client, Role.fromString("client"))
        assertEquals(Role.Admin, Role.fromString("admin"))
        assertEquals(Role.Admin, Role.fromString("administrator"))
        assertEquals(Role.Developer, Role.fromString("developer"))
        assertEquals(Role.Developer, Role.fromString("superadmin"))
    }

    @Test
    fun keepsWireNamesStable() {
        assertEquals("user", Role.User.wireName)
        assertEquals("client", Role.Client.wireName)
        assertEquals("admin", Role.Admin.wireName)
        assertEquals("developer", Role.Developer.wireName)
    }

    @Test
    fun appliesSharedPermissionMatrix() {
        assertFalse(Permissions.canViewNews(Role.Client))
        assertFalse(Permissions.hasAdminAccess(Role.User))
        assertFalse(Permissions.hasAdminAccess(Role.Client))
        assertTrue(Permissions.hasAdminAccess(Role.Admin))
        assertTrue(Permissions.hasAdminAccess(Role.Developer))
        assertFalse(Permissions.canManageUsers(Role.Admin))
        assertTrue(Permissions.canManageUsers(Role.Developer))
    }
}
