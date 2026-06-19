package dev.portfolio.notification.support

import dev.portfolio.notification.domain.MessageDispatch
import dev.portfolio.notification.pipeline.MessageStore
import kotlinx.datetime.Instant

/**
 * Snapshot store fake. Records the saved state snapshots for assertions. These are full state snapshots,
 * not an event stream — tests read the saved state directly and never fold a sequence to reconstruct state.
 *
 * `findDue` imitates NONE of the real DB seam (no durable persistence, no `SKIP_LOCKED` concurrent-poll
 * de-duplication) — it only applies the due filter `dueAt != null && dueAt <= now` over the latest saved
 * snapshot per aggregate. That deliberate gap is the §7.2 boundary: the wait-queue mechanics are infra.
 */
class RecordingMessageStore : MessageStore {
    val saved = mutableListOf<MessageDispatch>()

    /** Number of poll reads — lets the scheduler test count firings without imitating any infra. */
    var findDueCalls = 0
        private set

    override suspend fun save(dispatch: MessageDispatch) { saved += dispatch }

    /** Latest snapshot per (specKey, startId), filtered to those due at or before [now]. Filter only — no infra. */
    override suspend fun findDue(now: Instant): List<MessageDispatch> {
        findDueCalls++
        return saved.groupBy { it.specKey to it.startId }
            .values
            .map { it.last() }
            .filter { it.dueAt != null && it.dueAt!! <= now }
    }
}
