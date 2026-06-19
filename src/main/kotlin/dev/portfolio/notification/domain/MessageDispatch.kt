package dev.portfolio.notification.domain

import dev.portfolio.notification.failover.RetransmissionAction
import dev.portfolio.notification.failover.RetransmissionPolicy
import kotlinx.datetime.Instant

/**
 * State snapshot of one dispatch execution (immutable). The current state is held directly as a field —
 * there is NO event sourcing here (durable storage / ordered history / replay = infrastructure).
 *
 * retriesLeft (failure re-send budget) and repeatsLeft (success repeat-send budget) are INDEPENDENT —
 * a retry never consumes the repeat budget and vice versa.
 *
 * The aggregate carries its own re-injection schedule: `dueAt` is the absolute time the next
 * retransmission is due (null = not waiting; terminal states are never re-injected). `startId` is the
 * seed the pipeline composes from — persisted so the polling driver can re-inject without external state.
 */
data class MessageDispatch(
    val specKey: String,
    val platform: String,
    val startId: Any,
    val state: MessageState,
    val retriesLeft: Int,
    val repeatsLeft: Int,
    val attempts: Int,
    val dueAt: Instant? = null,
) {
    /**
     * One transmission result -> next state snapshot. Pure (no side effects). Wires RetransmissionPolicy:
     * success consults the repeat budget, failure the retry budget; only the consulted budget decrements.
     */
    fun onResult(success: Boolean, policy: RetransmissionPolicy): MessageDispatch {
        check(!state.terminal) { "terminal state ($state) cannot transition" }
        val budget = if (success) repeatsLeft else retriesLeft
        return when (policy.next(success, repeatable = budget >= 1)) {
            RetransmissionAction.COMPLETE  -> to(MessageState.COMPLETED, retriesLeft, repeatsLeft)
            RetransmissionAction.REREQUEST -> to(MessageState.REPEATING, retriesLeft, repeatsLeft - 1)
            RetransmissionAction.RETRY     -> to(MessageState.RETRYING, retriesLeft - 1, repeatsLeft)
            RetransmissionAction.EXCEEDED  -> to(MessageState.EXCEEDED, retriesLeft, repeatsLeft)
        }
    }

    private fun to(s: MessageState, retries: Int, repeats: Int) =
        copy(state = s, retriesLeft = retries, repeatsLeft = repeats, attempts = attempts + 1)

    companion object {
        fun start(spec: MessageSpec, startId: Any) =
            MessageDispatch(spec.key, spec.platform, startId, MessageState.PENDING, spec.retryBudget, spec.repeatBudget, 0)
    }
}

/**
 * The action implied by a non-terminal state, used purely to pick the next interval (see nextInterval).
 * RETRYING <-> RETRY and REPEATING <-> REREQUEST are 1:1 with the policy matrix; terminal states have no
 * next interval and map to their terminal action.
 */
val MessageState.retransmissionAction: RetransmissionAction
    get() = when (this) {
        MessageState.RETRYING  -> RetransmissionAction.RETRY
        MessageState.REPEATING -> RetransmissionAction.REREQUEST
        MessageState.COMPLETED -> RetransmissionAction.COMPLETE
        MessageState.EXCEEDED  -> RetransmissionAction.EXCEEDED
        MessageState.PENDING   -> RetransmissionAction.RETRY  // never reached: PENDING is pre-transition
    }
