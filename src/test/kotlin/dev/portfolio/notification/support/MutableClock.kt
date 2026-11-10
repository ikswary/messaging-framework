package dev.portfolio.notification.support

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * Deterministic virtual clock for tests. `now()` returns the held instant; advance it explicitly to cross
 * a `dueAt`. No wall-clock, no flakiness — every time-dependent assertion is reproducible.
 */
class MutableClock(start: Instant = Instant.fromEpochSeconds(0)) : Clock {
    var current: Instant = start
    override fun now(): Instant = current
    operator fun plusAssign(delta: Duration) { current += delta }
}
