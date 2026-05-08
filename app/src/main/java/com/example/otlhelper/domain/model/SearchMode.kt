package com.example.otlhelper.domain.model

/**
 * Type of search the user performed — determines which UI layout to show
 * for the results card (what fields to reveal, what to hide).
 */
enum class SearchMode {
    NONE,       // blank query
    WAREHOUSE,  // "12A" — warehouse-first layout; contact card shows only FIO/position/phone
    NAME,       // FIO — full contact detail; warehouse pills listed as context
    PHONE,      // phone number — full contact detail
    EMAIL       // email — full contact detail
}
