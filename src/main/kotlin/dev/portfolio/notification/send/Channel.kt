package dev.portfolio.notification.send

/**
 * Single-platform transmission — application code. Channel selection, the send call, and success/failure
 * interpretation are the framework's. This interface only isolates the external channel client (the
 * Slack/Email/Push SDK), an external-library dependency swapped for a fake in tests — it is NOT an
 * infra-provided guarantee. Mirrors the real sender.
 */
interface Channel {
    val platform: String
    suspend fun send(rendered: String)          // success = normal return, failure = exception
}
