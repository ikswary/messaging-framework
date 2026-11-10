package dev.portfolio.notification.pipeline

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration

/**
 * Periodic firing loop for retransmission — application code, NOT an infra-provided @Scheduled/cron. The
 * cadence (how often the wait queue is polled) is a framework decision held here: each pass runs
 * RetransmissionDriver.tick() once, then waits [pollInterval]. The only parts that stay infrastructure are
 * inside MessageStore — durable persistence and `SKIP_LOCKED` concurrent-poll de-duplication.
 *
 * `delay` suspends on the coroutine dispatcher, so the loop runs on virtual time under
 * kotlinx-coroutines-test — deterministic, no real wall-clock (see RetransmissionSchedulerTest).
 */
class RetransmissionScheduler(
    private val driver: RetransmissionDriver,
    private val pollInterval: Duration,
) {
    /** Poll once, wait [pollInterval], repeat — until the surrounding coroutine is cancelled. */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            driver.tick()
            delay(pollInterval)
        }
    }
}
