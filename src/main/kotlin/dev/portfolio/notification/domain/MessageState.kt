package dev.portfolio.notification.domain

/**
 * Lifecycle state of one message dispatch (a snapshot — NOT event-sourced).
 * Durable storage / history is infrastructure; the code tracks only the current state.
 */
enum class MessageState {
    PENDING, RETRYING, REPEATING, COMPLETED, EXCEEDED ;

    val terminal: Boolean get() = this == COMPLETED || this == EXCEEDED
}
