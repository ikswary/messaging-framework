package dev.portfolio.notification.pipeline

import dev.portfolio.notification.compose.GraphCollector
import dev.portfolio.notification.compose.PathPlanRegistry
import dev.portfolio.notification.domain.NodeRepository
import dev.portfolio.notification.domain.OrderKeyResolver
import dev.portfolio.notification.domain.defaultEdges
import dev.portfolio.notification.failover.RetransmissionPolicy
import dev.portfolio.notification.lifecycle.ComposerFinder
import dev.portfolio.notification.lifecycle.DefaultComposer
import dev.portfolio.notification.send.ChannelRegistry
import dev.portfolio.notification.send.TransmitService
import dev.portfolio.notification.support.InMemorySpecStore
import dev.portfolio.notification.support.MutableClock
import dev.portfolio.notification.support.RecordingChannel
import dev.portfolio.notification.support.RecordingMessageStore
import dev.portfolio.notification.support.TestGraph
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes

/**
 * The periodic firing loop is application code, not an infra-provided @Scheduled/cron. Under
 * kotlinx-coroutines-test virtual time the cadence is fully deterministic: the t=0 tick plus one tick per
 * elapsed [pollInterval], and cancelling the job stops it — no wall-clock, no flakiness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetransmissionSchedulerTest : StringSpec({

    // A real driver whose store starts empty: every tick is an empty poll we can count via findDueCalls,
    // and dispatch is never reached (findDue returns nothing), so this isolates the scheduler's only job.
    fun emptyDriver(store: RecordingMessageStore): RetransmissionDriver {
        val repo = NodeRepository(TestGraph.data)
        val specStore = InMemorySpecStore(listOf(TestGraph.SHIPPED_SPEC))
        val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(specStore.findAll()) }
        val composer = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
        val pipeline = NotificationPipeline(
            specStore, ComposerFinder(listOf(composer)),
            TransmitService(ChannelRegistry(listOf(RecordingChannel("email", listOf(true))))),
            RetransmissionPolicy(), store, MutableClock(),
        )
        return RetransmissionDriver(store, pipeline, MutableClock())
    }

    "fires exactly one tick per poll interval while active" {
        runTest {
            val store = RecordingMessageStore()
            val scheduler = RetransmissionScheduler(emptyDriver(store), pollInterval = 1.minutes)

            val job = launch { scheduler.run() }
            runCurrent()                                          // t=0: first tick
            store.findDueCalls shouldBe 1

            repeat(4) { advanceTimeBy(1.minutes); runCurrent() }  // t=1..4: one tick each
            store.findDueCalls shouldBe 5

            job.cancel()
        }
    }

    "stops firing once the job is cancelled" {
        runTest {
            val store = RecordingMessageStore()
            val scheduler = RetransmissionScheduler(emptyDriver(store), pollInterval = 1.minutes)

            val job = launch { scheduler.run() }
            runCurrent()
            repeat(2) { advanceTimeBy(1.minutes); runCurrent() }
            val firedBeforeCancel = store.findDueCalls            // t=0,1,2 -> 3

            job.cancel()
            advanceTimeBy(10.minutes); runCurrent()               // virtual time keeps moving...
            store.findDueCalls shouldBe firedBeforeCancel         // ...but a cancelled loop fires no more
        }
    }
})
