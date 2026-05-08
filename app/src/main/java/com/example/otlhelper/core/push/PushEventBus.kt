package com.example.otlhelper.core.push

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process event bus between [OtlFirebaseMessagingService] (lives in its
 * own lifecycle) and any ViewModel that wants to react to push arrivals.
 *
 * Why a bus instead of a direct call: the Service and HomeViewModel have
 * different lifetimes and Hilt scopes, and the service may fire while no
 * ViewModel exists. `SharedFlow` with `extraBufferCapacity` lets push
 * events queue up without blocking the service.
 *
 * Usage:
 *   - Service: inject [PushEventBus], call [tryEmit] from onMessageReceived.
 *   - ViewModel: collect [events] in viewModelScope to react (refresh feed,
 *     bump unread counter, etc.).
 */
sealed class PushEvent {
    /**
     * A data push arrived while the app is running. The payload's `type`
     * field is surfaced separately so ViewModels can branch on it without
     * parsing `data` again (e.g. `admin_message`, `news`, `poll`).
     */
    data class Received(val type: String, val data: Map<String, String>) : PushEvent()
}

@Singleton
class PushEventBus @Inject constructor() {

    // Small replay buffer so a ViewModel that starts collecting a moment
    // after the service emits still picks up the event. `extraBufferCapacity`
    // keeps `tryEmit` non-suspending — safe to call from a Service callback.
    private val _events = MutableSharedFlow<PushEvent>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val events: SharedFlow<PushEvent> = _events.asSharedFlow()

    fun tryEmit(event: PushEvent): Boolean = _events.tryEmit(event)
}
