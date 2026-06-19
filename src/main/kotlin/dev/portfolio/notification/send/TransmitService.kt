package dev.portfolio.notification.send

import kotlinx.coroutines.CancellationException

/** Single transmission. Mirrors the real transmit-service (senderFinder.find(platform).send). */
class TransmitService(private val registry: ChannelRegistry) {
    /**
     * true = success, false = delivery failure. No multi-channel / partial success (1 notification = 1 platform).
     * A config error (unregistered platform) is surfaced via throw.
     *
     * `runCatching` is deliberately NOT used here: it would swallow `CancellationException`, masking a
     * shutdown as a `false` delivery failure (the tracking layer would then record a phantom retry on a
     * cancelled send). Cancellation is rethrown so cooperative cancellation survives; only real delivery
     * exceptions collapse to `false`.
     */
    suspend fun transmit(platform: String, rendered: String): Boolean {
        val channel = registry.find(platform)                  // unregistered -> IllegalStateException (not disguised as a delivery failure)
        return try {
            channel.send(rendered)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation                                 // shutdown signal, not a delivery failure
        } catch (failure: Exception) {
            false                                              // transient delivery failure -> tracking decides retransmission
        }
    }
}
