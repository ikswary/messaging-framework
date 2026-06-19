package dev.portfolio.notification.send

import dev.portfolio.notification.support.RecordingChannel
import dev.portfolio.notification.support.SuspendingChannel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class TransmitServiceTest : StringSpec({
    "transmits to the single platform's channel" {
        runTest {
            val email = RecordingChannel("email", listOf(true))
            val svc = TransmitService(ChannelRegistry(listOf(email)))
            svc.transmit("email", "hi") shouldBe true
            email.sent shouldBe listOf("hi")
        }
    }

    "delivery failure is reported as false (not thrown)" {
        runTest {
            val email = RecordingChannel("email", listOf(false))
            TransmitService(ChannelRegistry(listOf(email))).transmit("email", "hi") shouldBe false
        }
    }

    "unknown platform is a config error, not a transient failure" {
        runTest {
            val svc = TransmitService(ChannelRegistry(listOf(RecordingChannel("email", listOf(true)))))
            shouldThrow<IllegalStateException> { svc.transmit("sms", "hi") }
        }
    }

    "a cancelled send propagates cancellation instead of masking it as a delivery failure" {
        runTest {
            val slow = SuspendingChannel("email", hold = 1.minutes)
            val svc = TransmitService(ChannelRegistry(listOf(slow)))
            var result: Boolean? = null
            var cancelled = false
            val job = launch {
                try {
                    result = svc.transmit("email", "hi")
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }
            runCurrent()                       // send started, suspended in delay
            slow.inFlight shouldBe 1
            job.cancel(); advanceUntilIdle()
            job.isCancelled shouldBe true
            cancelled shouldBe true             // cancellation propagated...
            result shouldBe null                // ...not swallowed into a false delivery result
            slow.completed shouldBe 0
        }
    }
})
