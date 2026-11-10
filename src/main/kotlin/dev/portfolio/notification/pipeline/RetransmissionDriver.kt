package dev.portfolio.notification.pipeline

import dev.portfolio.notification.domain.MessageDispatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock

/**
 * Polling driver that unifies retry and periodic (repeat) retransmission under one pass. It reads the due
 * aggregates from the wait queue (MessageStore.findDue) and re-injects each into the pipeline. It does NOT
 * branch on retry vs repeat — the aggregate's `dueAt` already decided when, and `onResult` decides what
 * happens next; the driver is indifferent (state-agnostic re-injection). The periodic firing of `tick` is
 * application code too (RetransmissionScheduler) — not an infra-provided @Scheduled/cron.
 *
 * A `tick` drains the batch with structured concurrency (supervisorScope):
 * - each due aggregate is re-injected as an independent child, so one poisoned aggregate (e.g. a config
 *   error that throws) is contained via [onError] and neither aborts the batch nor kills the poller —
 *   this resilience is application logic, not an infra guarantee;
 * - [maxConcurrency] (a [Semaphore]) bounds the fan-out, so a large due batch drains in parallel without
 *   swamping downstream (default 1 = ordered, one-at-a-time);
 * - `CancellationException` is rethrown rather than contained, so a shutdown propagates immediately instead
 *   of being masked as a handled failure (cooperative cancellation).
 *
 * [onError] is a containment sink — it must not throw (a throw there escapes the child uncaught). Its job is
 * to dispose the failure (log / metric / dead-letter). A failure that throws BEFORE `dispatch` reaches its
 * save leaves the aggregate's `dueAt` unchanged, so it re-surfaces through [onError] on every subsequent
 * tick until the policy disposes it or the spec is fixed — surfaced, never silently dropped.
 *
 * Termination-guarantee latch: each due aggregate is re-injected as `prior`, so budgets decrease
 * monotonically instead of resetting to the spec defaults. Dropping `prior` here would reset budgets and
 * loop forever — this is the contract the type system cannot enforce (covered by a mutation-guard test).
 */
class RetransmissionDriver(
    private val store: MessageStore,
    private val pipeline: NotificationPipeline,
    private val clock: Clock,
    private val maxConcurrency: Int = 1,
    private val onError: (MessageDispatch, Throwable) -> Unit = { _, _ -> },
) {
    init {
        require(maxConcurrency >= 1) { "maxConcurrency must be >= 1, was $maxConcurrency" }
    }

    /** One polling tick: collect everything due now and re-inject it concurrently, carrying the prior snapshot. */
    suspend fun tick() = supervisorScope {
        val permits = Semaphore(maxConcurrency)
        for (due in store.findDue(clock.now())) {
            launch { permits.withPermit { reinject(due) } }
        }
    }

    private suspend fun reinject(due: MessageDispatch) {
        try {
            pipeline.dispatch(due.specKey, due.startId, prior = due)
        } catch (cancellation: CancellationException) {
            throw cancellation                  // never mask a shutdown as a handled failure
        } catch (failure: Throwable) {
            onError(due, failure)               // one poisoned aggregate must not abort the batch or kill the poller
        }
    }
}
