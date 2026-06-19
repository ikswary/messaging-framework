package dev.portfolio.notification.pipeline

import dev.portfolio.notification.compose.GraphCollector
import dev.portfolio.notification.compose.PathPlanRegistry
import dev.portfolio.notification.domain.MessageDispatch
import dev.portfolio.notification.domain.MessageState
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
import dev.portfolio.notification.support.SuspendingChannel
import dev.portfolio.notification.support.TestGraph
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes

/**
 * Polling driver over the aggregate wait queue. Virtual clock = reproducible due crossing. Covers: due-gating
 * (before/after), prior continuity (monotonic budget decrease), termination guarantee (retry -> EXCEEDED,
 * repeat -> COMPLETED, terminal leaves the queue), and the prior-omission mutation guard. Also covers the
 * structured-concurrency drain: per-aggregate failure containment, cooperative cancellation, bounded fan-out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetransmissionDriverTest : StringSpec({

    // Wires a pipeline + driver sharing one store and clock. retryInterval/repeatInterval default below.
    fun fixture(
        spec: dev.portfolio.notification.domain.MessageSpec,
        deliveries: List<Boolean>,
        clock: MutableClock,
    ): Triple<NotificationPipeline, RetransmissionDriver, RecordingMessageStore> {
        val repo = NodeRepository(TestGraph.data)
        val specStore = InMemorySpecStore(listOf(spec))
        val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(specStore.findAll()) }
        val default = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
        val email = RecordingChannel("email", deliveries)
        val msgStore = RecordingMessageStore()
        val pipeline = NotificationPipeline(
            specStore, ComposerFinder(listOf(default)), TransmitService(ChannelRegistry(listOf(email))),
            RetransmissionPolicy(), msgStore, clock,
        )
        return Triple(pipeline, RetransmissionDriver(msgStore, pipeline, clock), msgStore)
    }

    "tick before dueAt collects nothing; tick at/after dueAt re-injects" {
        runTest {
            val clock = MutableClock()
            val spec = TestGraph.SHIPPED_SPEC.copy(retryBudget = 3, retryInterval = 5.minutes)
            val (pipeline, driver, store) = fixture(spec, deliveries = listOf(false), clock)

            pipeline.dispatch("SHIPPED", startId = 1L) shouldBe MessageState.RETRYING   // dueAt = now + 5m
            val savesAfterFirst = store.saved.size

            clock += 4.minutes                       // still before dueAt
            driver.tick()
            store.saved shouldHaveSize savesAfterFirst                                  // nothing collected
            store.findDue(clock.now()).shouldBeEmpty()

            clock += 1.minutes                       // now == dueAt
            driver.tick()
            store.saved.size shouldBe savesAfterFirst + 1                               // re-injected once
        }
    }

    "prior continuity: budgets decrease monotonically across ticks and terminate at EXCEEDED" {
        runTest {
            val clock = MutableClock()
            val spec = TestGraph.SHIPPED_SPEC.copy(retryBudget = 2, retryInterval = 1.minutes)
            val (pipeline, driver, store) = fixture(spec, deliveries = listOf(false), clock)  // every delivery fails

            pipeline.dispatch("SHIPPED", startId = 1L) shouldBe MessageState.RETRYING
            store.saved.last().retriesLeft shouldBe 1

            // Tick 1: advance to the aggregate's own dueAt (retry backs off, so it is not a fixed delta),
            // then re-inject with prior -> 1 -> 0, NOT reset to 2.
            clock.current = store.saved.last().dueAt!!; driver.tick()
            store.saved.last().state shouldBe MessageState.RETRYING
            store.saved.last().retriesLeft shouldBe 0

            // Tick 2: budget exhausted -> EXCEEDED, dueAt cleared (leaves the queue).
            clock.current = store.saved.last().dueAt!!; driver.tick()
            store.saved.last().state shouldBe MessageState.EXCEEDED
            store.saved.last().dueAt shouldBe null

            // Tick 3: terminal aggregate is no longer due -> no further re-injection.
            val terminalSaves = store.saved.size
            clock += 10.minutes; driver.tick()
            store.saved.size shouldBe terminalSaves
        }
    }

    "repeat: fixed-period re-injection terminates at COMPLETED when the repeat budget is exhausted" {
        runTest {
            val clock = MutableClock()
            val spec = TestGraph.SHIPPED_SPEC.copy(repeatBudget = 1, repeatInterval = 10.minutes)
            val (pipeline, driver, store) = fixture(spec, deliveries = listOf(true), clock)  // every delivery succeeds

            pipeline.dispatch("SHIPPED", startId = 1L) shouldBe MessageState.REPEATING
            store.saved.last().repeatsLeft shouldBe 0

            clock += 10.minutes; driver.tick()       // re-injects success -> repeat exhausted -> COMPLETED
            store.saved.last().state shouldBe MessageState.COMPLETED
            store.saved.last().dueAt shouldBe null

            val terminalSaves = store.saved.size
            clock += 10.minutes; driver.tick()
            store.saved.size shouldBe terminalSaves                                     // no further re-injection
        }
    }

    "mutation guard: a driver that drops prior resets the budget and never terminates" {
        runTest {
            val clock = MutableClock()
            val spec = TestGraph.SHIPPED_SPEC.copy(retryBudget = 2, retryInterval = 1.minutes)
            val (pipeline, _, store) = fixture(spec, deliveries = listOf(false), clock)

            pipeline.dispatch("SHIPPED", startId = 1L) shouldBe MessageState.RETRYING

            // Buggy re-injection: omits `prior`, so MessageDispatch.start() resets retriesLeft back to 2
            // every tick. The real driver passes prior=due (see RetransmissionDriver.tick) and would reach
            // EXCEEDED in <= retryBudget ticks. This loop proves the guard: dropping prior never terminates.
            var reachedTerminal = false
            repeat(spec.retryBudget + 5) {
                clock.current = store.saved.last().dueAt!!                             // advance to the pending due time
                val due: List<MessageDispatch> = store.findDue(clock.now())
                for (d in due) pipeline.dispatch(d.specKey, d.startId /* prior dropped */)
                if (store.saved.last().state.terminal) reachedTerminal = true
            }
            reachedTerminal shouldBe false                                             // infinite retransmission reproduced
            store.saved.last().retriesLeft shouldBe 1                                  // reset to budget then -1, every tick
        }
    }

    "a poisoned aggregate is contained: the rest of the batch re-injects and the poller survives" {
        runTest {
            val clock = MutableClock()
            val good = TestGraph.SHIPPED_SPEC.copy(retryBudget = 3, retryInterval = 5.minutes)   // platform "email"
            val bad = good.copy(key = "BAD", platform = "sms")                                    // no channel for "sms" -> transmit throws
            val repo = NodeRepository(TestGraph.data)
            val specStore = InMemorySpecStore(listOf(good, bad))
            val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(specStore.findAll()) }
            val composer = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
            val email = RecordingChannel("email", listOf(false, false))                          // good keeps failing -> stays RETRYING
            val msgStore = RecordingMessageStore()
            val pipeline = NotificationPipeline(
                specStore, ComposerFinder(listOf(composer)),
                TransmitService(ChannelRegistry(listOf(email))), RetransmissionPolicy(), msgStore, clock,
            )
            val errors = mutableListOf<String>()
            val driver = RetransmissionDriver(msgStore, pipeline, clock, onError = { d, _ -> errors += d.specKey })

            pipeline.dispatch("SHIPPED", startId = 1L) shouldBe MessageState.RETRYING            // good -> due (dueAt = now + 5m)
            msgStore.save(MessageDispatch.start(bad, 1L).copy(state = MessageState.RETRYING, retriesLeft = 1, dueAt = clock.now()))

            val savesBefore = msgStore.saved.size
            clock += 5.minutes
            driver.tick()                                                                         // must NOT throw

            errors shouldBe listOf("BAD")                                                         // bad contained, not propagated
            msgStore.saved.size shouldBe savesBefore + 1                                          // only good re-injected (bad threw before save)
            msgStore.saved.last().specKey shouldBe "SHIPPED"
            msgStore.saved.last().retriesLeft shouldBe 1                                          // good advanced 2 -> 1
        }
    }

    "cancellation during a tick propagates and is not delivered to onError" {
        runTest {
            val clock = MutableClock()
            val spec = TestGraph.SHIPPED_SPEC.copy(retryBudget = 3, retryInterval = 5.minutes)
            val repo = NodeRepository(TestGraph.data)
            val specStore = InMemorySpecStore(listOf(spec))
            val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(specStore.findAll()) }
            val composer = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
            val slow = SuspendingChannel("email", hold = 1.minutes)
            val msgStore = RecordingMessageStore()
            val pipeline = NotificationPipeline(
                specStore, ComposerFinder(listOf(composer)),
                TransmitService(ChannelRegistry(listOf(slow))), RetransmissionPolicy(), msgStore, clock,
            )
            val errors = mutableListOf<Throwable>()
            val driver = RetransmissionDriver(msgStore, pipeline, clock, onError = { _, e -> errors += e })

            msgStore.save(MessageDispatch.start(spec, 1L).copy(state = MessageState.RETRYING, retriesLeft = 2, dueAt = clock.now()))
            val job = launch { driver.tick() }
            runCurrent()                       // tick -> dispatch -> transmit -> send suspends in delay
            slow.inFlight shouldBe 1
            job.cancel(); advanceUntilIdle()

            job.isCancelled shouldBe true
            errors.shouldBeEmpty()             // cancellation propagated, NOT masked into onError
            slow.completed shouldBe 0
        }
    }

    "maxConcurrency > 1 drains the due batch concurrently" {
        runTest {
            val clock = MutableClock()
            val specs = listOf("S1", "S2", "S3").map {
                TestGraph.SHIPPED_SPEC.copy(key = it, retryBudget = 3, retryInterval = 5.minutes)
            }
            val repo = NodeRepository(TestGraph.data)
            val specStore = InMemorySpecStore(specs)
            val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(specStore.findAll()) }
            val composer = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
            val slow = SuspendingChannel("email", hold = 1.minutes)
            val msgStore = RecordingMessageStore()
            val pipeline = NotificationPipeline(
                specStore, ComposerFinder(listOf(composer)),
                TransmitService(ChannelRegistry(listOf(slow))), RetransmissionPolicy(), msgStore, clock,
            )
            val driver = RetransmissionDriver(msgStore, pipeline, clock, maxConcurrency = 3)

            for (s in specs) {
                msgStore.save(MessageDispatch.start(s, 1L).copy(state = MessageState.RETRYING, retriesLeft = 2, dueAt = clock.now()))
            }
            val job = launch { driver.tick() }
            runCurrent()                       // all three sends start before any completes
            slow.maxInFlight shouldBe 3        // bounded fan-out reached the limit (would be 1 at maxConcurrency = 1)
            advanceUntilIdle(); job.join()
        }
    }

    "maxConcurrency = 1 serializes the drain (maxInFlight stays 1)" {
        runTest {
            val clock = MutableClock()
            val specs = listOf("S1", "S2", "S3").map {
                TestGraph.SHIPPED_SPEC.copy(key = it, retryBudget = 3, retryInterval = 5.minutes)
            }
            val repo = NodeRepository(TestGraph.data)
            val specStore = InMemorySpecStore(specs)
            val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(specStore.findAll()) }
            val composer = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
            val slow = SuspendingChannel("email", hold = 1.minutes)
            val msgStore = RecordingMessageStore()
            val pipeline = NotificationPipeline(
                specStore, ComposerFinder(listOf(composer)),
                TransmitService(ChannelRegistry(listOf(slow))), RetransmissionPolicy(), msgStore, clock,
            )
            val driver = RetransmissionDriver(msgStore, pipeline, clock, maxConcurrency = 1)

            for (s in specs) {
                msgStore.save(MessageDispatch.start(s, 1L).copy(state = MessageState.RETRYING, retriesLeft = 2, dueAt = clock.now()))
            }
            val job = launch { driver.tick() }
            runCurrent()                       // Semaphore(1) lets only one send in flight at a time
            slow.maxInFlight shouldBe 1        // serialized (vs 3 at maxConcurrency = 3) — pins the discriminator
            advanceUntilIdle(); job.join()
        }
    }

    "a config-error aggregate re-surfaces via onError each tick (no silent drop) until disposed" {
        runTest {
            val clock = MutableClock()
            val bad = TestGraph.SHIPPED_SPEC.copy(key = "BAD", platform = "sms", retryBudget = 3)   // no "sms" channel -> transmit throws before save
            val repo = NodeRepository(TestGraph.data)
            val specStore = InMemorySpecStore(listOf(bad))
            val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(specStore.findAll()) }
            val composer = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
            val msgStore = RecordingMessageStore()
            val pipeline = NotificationPipeline(
                specStore, ComposerFinder(listOf(composer)),
                TransmitService(ChannelRegistry(listOf(RecordingChannel("email", listOf(true))))),
                RetransmissionPolicy(), msgStore, clock,
            )
            val errors = mutableListOf<String>()
            val driver = RetransmissionDriver(msgStore, pipeline, clock, onError = { d, _ -> errors += d.specKey })

            msgStore.save(MessageDispatch.start(bad, 1L).copy(state = MessageState.RETRYING, retriesLeft = 1, dueAt = clock.now()))
            driver.tick(); driver.tick()                          // throws before save -> dueAt unchanged -> still due next tick

            errors shouldBe listOf("BAD", "BAD")                  // re-surfaced both ticks, never silently dropped
        }
    }

    "maxConcurrency below 1 is rejected at construction" {
        val (pipeline, _, store) = fixture(TestGraph.SHIPPED_SPEC, deliveries = listOf(true), MutableClock())
        shouldThrow<IllegalArgumentException> {
            RetransmissionDriver(store, pipeline, MutableClock(), maxConcurrency = 0)
        }
    }
})
