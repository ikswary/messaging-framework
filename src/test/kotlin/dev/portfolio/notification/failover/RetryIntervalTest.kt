package dev.portfolio.notification.failover

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Pure interval calculation. No clock, no side effects, no domain types — retry grows exponentially off the
 * base, repeat is a fixed period, and terminal actions yield ZERO.
 */
class RetryIntervalTest : StringSpec({
    val retry = 1.minutes
    val repeat = 10.minutes

    "RETRY backs off exponentially off the base interval (base, x2, x4, ...)" {
        nextInterval(RetransmissionAction.RETRY, attempt = 1, retry, repeat) shouldBe 1.minutes
        nextInterval(RetransmissionAction.RETRY, attempt = 2, retry, repeat) shouldBe 2.minutes
        nextInterval(RetransmissionAction.RETRY, attempt = 3, retry, repeat) shouldBe 4.minutes
        nextInterval(RetransmissionAction.RETRY, attempt = 4, retry, repeat) shouldBe 8.minutes
    }

    "REREQUEST is a fixed period regardless of attempt" {
        nextInterval(RetransmissionAction.REREQUEST, attempt = 1, retry, repeat) shouldBe 10.minutes
        nextInterval(RetransmissionAction.REREQUEST, attempt = 5, retry, repeat) shouldBe 10.minutes
    }

    "terminal actions have no next interval" {
        nextInterval(RetransmissionAction.COMPLETE, attempt = 1, retry, repeat) shouldBe Duration.ZERO
        nextInterval(RetransmissionAction.EXCEEDED, attempt = 3, retry, repeat) shouldBe Duration.ZERO
    }
})
