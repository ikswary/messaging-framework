package dev.portfolio.notification.pipeline

import dev.portfolio.notification.domain.MessageDispatch
import kotlinx.datetime.Instant

/**
 * State snapshot persistence seam AND the wait queue. This is NOT event sourcing — it saves the current
 * state snapshot. The aggregate table itself is the retransmission wait queue (there is no separate queue):
 * a non-terminal save with `dueAt != null` enqueues the next re-injection; a terminal save (`dueAt == null`)
 * leaves the queue. real = DB; fake = records the save call. Ordered history / replay is out of scope.
 *
 * `findDue` is the polling read of that queue. Durable persistence, concurrent-claim control, and the due lookup
 * are a DB adapter's responsibility; that adapter is outside this reconstruction. The in-memory fake imitates
 * none of those guarantees — it only filters `dueAt != null && dueAt <= now`.
 *
 * The termination-guarantee latch — re-injecting each due aggregate as `prior` so budgets decrease
 * monotonically instead of resetting to the spec defaults — is held by RetransmissionDriver, not here.
 */
interface MessageStore {
    suspend fun save(dispatch: MessageDispatch)

    /** Aggregates whose next retransmission is due at or before [now] (i.e. dueAt != null && dueAt <= now). */
    suspend fun findDue(now: Instant): List<MessageDispatch>
}
