package com.example.otlhelper.desktop.model

/**
 * Desktop compatibility alias for the canonical shared role contract.
 *
 * Existing desktop UI keeps this import path, while role parsing and permission
 * rules now come from `:shared`.
 */
typealias Role = com.example.otlhelper.shared.auth.Role
