package dev.portfolio.notification.failover

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RetransmissionPolicyTest : StringSpec({
    "success x repeatable matrix" {
        val p = RetransmissionPolicy()
        p.next(success = true, repeatable = true) shouldBe RetransmissionAction.REREQUEST
        p.next(success = true, repeatable = false) shouldBe RetransmissionAction.COMPLETE
        p.next(success = false, repeatable = true) shouldBe RetransmissionAction.RETRY
        p.next(success = false, repeatable = false) shouldBe RetransmissionAction.EXCEEDED
    }
})
