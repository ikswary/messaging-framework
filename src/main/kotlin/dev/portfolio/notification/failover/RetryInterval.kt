package dev.portfolio.notification.failover

import kotlin.time.Duration

/**
 * Pure interval calculation for the next retransmission. No side effects, no clock, no domain types — given
 * an action, the attempt count, and the two configured intervals, it returns the delay before the next
 * re-injection. Taking the intervals (not the spec) keeps `failover` a dependency leaf: it never reaches
 * back into `domain`.
 *
 * - RETRY     -> exponential backoff off retryInterval (base, base*2, base*4, ...), capped per attempt.
 * - REREQUEST -> fixed repeatInterval (periodic send, no growth).
 * - terminal actions (COMPLETE / EXCEEDED) have no next interval -> Duration.ZERO.
 *
 * `attempt` is the dispatch's attempt count AFTER the transition (>= 1 for a non-terminal action). The
 * retry exponent uses the pre-transition step so the first retry waits exactly the base interval.
 */
fun nextInterval(
    action: RetransmissionAction,
    attempt: Int,
    retryInterval: Duration,
    repeatInterval: Duration,
): Duration = when (action) {
    RetransmissionAction.RETRY     -> retryInterval * (1 shl (attempt - 1).coerceIn(0, 30))
    RetransmissionAction.REREQUEST -> repeatInterval
    RetransmissionAction.COMPLETE,
    RetransmissionAction.EXCEEDED  -> Duration.ZERO
}
