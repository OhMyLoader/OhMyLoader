package org.ohmyloader.api.event

/**
 * Base class for all events.
 *
 * Threading contract: events are constructed and dispatched by the version
 * adapter from game hooks, synchronously on the same thread the game calls the
 * hook (e.g. the client tick event runs on the main thread); handlers must not
 * perform blocking operations.
 */
abstract class Event {
    /** Whether this event can be canceled. */
    open val cancellable: Boolean get() = false

    var canceled: Boolean = false
        private set

    /** Cancels the event (only valid when [cancellable] is true). Handlers registered later observe the canceled state. */
    fun cancel() {
        check(cancellable) { "${this::class.simpleName} does not support cancellation" }
        canceled = true
    }
}

/**
 * Typed event definition: one [EventDefinition] per event, with listeners
 * registered directly as `(T) -> Unit` lambdas — registration and dispatch are
 * entirely reflection-free and type-scan-free.
 *
 * Dispatch path: `fire` -> invoker (a single lambda rebuilt at registration
 * time from the current listener snapshot) -> ordered traversal. Registration is
 * a low-frequency operation, so the rebuild cost is incurred only at registration.
 */
class EventDefinition<T : Event>(val id: String) {

    private val listeners = mutableListOf<(T) -> Unit>()

    @Volatile
    private var invoker: (T) -> Unit = {}

    /** Registers a handler (dispatched in registration order). */
    fun register(listener: (T) -> Unit) {
        listeners += listener
        val snapshot = listeners.toList()
        invoker = { event -> snapshot.forEach { it(event) } }
    }

    /** Dispatches the event (usually called by the version adapter). */
    fun fire(event: T) = invoker(event)
}
