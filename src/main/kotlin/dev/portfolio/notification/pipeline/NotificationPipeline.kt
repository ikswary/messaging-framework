package dev.portfolio.notification.pipeline

import dev.portfolio.notification.domain.MessageDispatch
import dev.portfolio.notification.domain.MessageState
import dev.portfolio.notification.domain.retransmissionAction
import dev.portfolio.notification.failover.RetransmissionPolicy
import dev.portfolio.notification.failover.nextInterval
import dev.portfolio.notification.lifecycle.ComposerFinder
import dev.portfolio.notification.lifecycle.SpecStore
import dev.portfolio.notification.send.TransmitService
import kotlinx.datetime.Clock

/**
 * Entry flow (up to the real SenderFinder), anonymized. The definition is looked up from the store
 * (no inline injection), a composer fills it, it is transmitted on a single platform, and the resulting
 * lifecycle state is tracked.
 *
 * The framework owns retransmission CONTROL in code: it decides the next state, computes the next due time
 * (now + nextInterval), records it on the aggregate, and drives the periodic re-injection
 * (RetransmissionScheduler). The only parts left to infrastructure are inside MessageStore — durable
 * persistence and `SKIP_LOCKED` concurrent polling. Saving a non-terminal aggregate with a `dueAt` IS the
 * wait registration; saving a terminal one (dueAt = null) leaves the queue.
 */
class NotificationPipeline(
    private val store: SpecStore,                  // definition store (real code cache / DB)
    private val composerFinder: ComposerFinder,    // generation: fill-engine selection (default / special)
    private val transmit: TransmitService,         // transmission: single platform
    private val policy: RetransmissionPolicy,      // retransmission decision (wired)
    private val messageStore: MessageStore,        // state snapshot persistence + wait queue (infra seam)
    private val clock: Clock,                       // now() for the next-due computation (test = fake Clock)
) {
    /**
     * Look up definition -> fill via composer -> transmit -> transition lifecycle state -> register next due.
     * prior == null = first entry (start); prior != null = driver re-injection (carries budgets, no reset).
     */
    suspend fun dispatch(specKey: String, startId: Any, prior: MessageDispatch? = null): MessageState {
        val def = store.find(specKey)
        val current = prior ?: MessageDispatch.start(def, startId)
        val rendered = composerFinder.find(specKey).compose(def, startId)   // config error throws -> fail-fast (outside state model)
        val ok = transmit.transmit(def.platform, rendered)                  // delivery result only (true/false)
        val next = current.onResult(ok, policy)                             // pure transition (no events)
        val due =                                                           // terminal -> null (leaves the queue); else now + interval
            if (next.state.terminal) null
            else clock.now() + nextInterval(next.state.retransmissionAction, next.attempts, def.retryInterval, def.repeatInterval)
        messageStore.save(next.copy(dueAt = due))                           // (infra) save = state snapshot + wait registration
        return next.state
    }
}
