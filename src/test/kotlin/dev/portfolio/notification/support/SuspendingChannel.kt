package dev.portfolio.notification.support

import dev.portfolio.notification.send.Channel
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Channel whose `send` suspends for [hold] before succeeding. Lets a test cancel a send mid-flight (the
 * `delay` is a cancellation point) and observe concurrent drain via [maxInFlight] (the peak number of
 * sends suspended at once).
 */
class SuspendingChannel(override val platform: String, private val hold: Duration) : Channel {
    var inFlight = 0
        private set
    var maxInFlight = 0
        private set
    var completed = 0
        private set

    override suspend fun send(rendered: String) {
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            delay(hold)
            completed++
        } finally {
            inFlight--
        }
    }
}
