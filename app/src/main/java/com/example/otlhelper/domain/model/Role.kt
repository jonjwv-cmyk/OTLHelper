package com.example.otlhelper.domain.model

/**
 * Android compatibility alias for the canonical shared role contract.
 *
 * New shared business rules belong in `:shared`, while Android UI can keep the
 * historic import path during migration.
 */
typealias Role = com.example.otlhelper.shared.auth.Role

fun Role.displayName(): String = displayName

fun Role.wireName(): String = wireName
