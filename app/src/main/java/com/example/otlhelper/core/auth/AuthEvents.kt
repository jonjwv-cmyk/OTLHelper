package com.example.otlhelper.core.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Global auth-event channel.
 *
 * ApiClient publishes here when the server answers any action with a dead-
 * token error (`token_revoked`, `invalid_token`, `token_expired`, or
 * `password_reset`). The root composable collects the stream and force-
 * logs out the session — otherwise the app sits in a zombie state where
 * UI looks logged in but every request returns 401 and features silently
 * stop working (symptom: stats don't load, messages don't send, etc.).
 *
 * Implemented as a top-level Kotlin object rather than a Hilt singleton
 * because ApiClient itself is a Kotlin object (no @Inject scope) and this
 * keeps the producer side trivial.
 */
object AuthEvents {

    private val _events = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun emit(reason: String) {
        _events.tryEmit(reason)
    }

    /** Server error codes we treat as "the current token is dead, re-auth". */
    val DEAD_TOKEN_ERRORS: Set<String> = setOf(
        "token_revoked",
        "invalid_token",
        "token_expired",
        "password_reset"
    )
}
