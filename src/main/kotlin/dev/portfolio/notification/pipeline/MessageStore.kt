package dev.portfolio.notification.pipeline

import dev.portfolio.notification.domain.MessageDispatch
import kotlinx.datetime.Instant

/**
 * State snapshot persistence seam AND the wait queue. This is NOT event sourcing — it saves the current
 * state snapshot. The aggregate table itself is the retransmission wait queue (there is no separate queue):
 * a non-terminal save with `dueAt != null` enqueues the next re-injection; a terminal save (`dueAt == null`)
 * leaves the queue. real = DB; fake = records the save call. Ordered history / replay is out of scope.
 *
 * `findDue` is the polling read of that queue. In the real system, durable persistence, `SKIP_LOCKED`
 * concurrent-poll de-duplication, and the due lookup are the DB implementation's responsibility (seam +
 * diagram). The in-memory fake imitates NONE of that — it only filters `dueAt != null && dueAt <= now`.
 *
 * The termination-guarantee latch — re-injecting each due aggregate as `prior` so budgets decrease
 * monotonically instead of resetting to the spec defaults — is held by RetransmissionDriver, not here.
 */
interface MessageStore {
    suspend fun save(dispatch: MessageDispatch)

    /** Aggregates whose next retransmission is due at or before [now] (i.e. dueAt != null && dueAt <= now). */
    suspend fun findDue(now: Instant): List<MessageDispatch>
}
