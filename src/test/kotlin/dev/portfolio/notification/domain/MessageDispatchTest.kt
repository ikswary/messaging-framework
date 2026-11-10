package dev.portfolio.notification.domain

import dev.portfolio.notification.failover.RetransmissionPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * State machine of one dispatch. Retry and repeat budgets are independent; the snapshot transition is
 * pure and wires RetransmissionPolicy. No event sourcing — there is no event object to accumulate.
 */
class MessageDispatchTest : StringSpec({
    val policy = RetransmissionPolicy()
    fun dispatch(state: MessageState = MessageState.PENDING, retries: Int, repeats: Int) =
        MessageDispatch(specKey = "K", platform = "email", startId = 1L, state = state, retriesLeft = retries, repeatsLeft = repeats, attempts = 0)

    "success with repeat budget -> REPEATING, only the repeat budget is consumed" {
        val next = dispatch(retries = 2, repeats = 2).onResult(success = true, policy)
        next.state shouldBe MessageState.REPEATING
        next.repeatsLeft shouldBe 1
        next.retriesLeft shouldBe 2            // retry budget untouched
        next.attempts shouldBe 1
    }

    "success with no repeat budget -> COMPLETED, budgets unchanged" {
        val next = dispatch(retries = 5, repeats = 0).onResult(success = true, policy)
        next.state shouldBe MessageState.COMPLETED
        next.retriesLeft shouldBe 5            // terminal transition consumes nothing
        next.repeatsLeft shouldBe 0
    }

    "failure with retry budget -> RETRYING, only the retry budget is consumed" {
        val next = dispatch(retries = 2, repeats = 3).onResult(success = false, policy)
        next.state shouldBe MessageState.RETRYING
        next.retriesLeft shouldBe 1
        next.repeatsLeft shouldBe 3            // repeat budget untouched
    }

    "failure with no retry budget -> EXCEEDED, budgets unchanged" {
        val next = dispatch(retries = 0, repeats = 5).onResult(success = false, policy)
        next.state shouldBe MessageState.EXCEEDED
        next.retriesLeft shouldBe 0
        next.repeatsLeft shouldBe 5            // terminal transition consumes nothing
    }

    "terminal state cannot transition again" {
        shouldThrow<IllegalStateException> {
            dispatch(state = MessageState.COMPLETED, retries = 1, repeats = 1).onResult(success = true, policy)
        }
    }

    "retry budget decreases monotonically across re-attempts and terminates at EXCEEDED" {
        var d = dispatch(retries = 2, repeats = 0)
        d = d.onResult(success = false, policy); d.state shouldBe MessageState.RETRYING; d.retriesLeft shouldBe 1
        d = d.onResult(success = false, policy); d.state shouldBe MessageState.RETRYING; d.retriesLeft shouldBe 0
        d = d.onResult(success = false, policy); d.state shouldBe MessageState.EXCEEDED
    }
})
