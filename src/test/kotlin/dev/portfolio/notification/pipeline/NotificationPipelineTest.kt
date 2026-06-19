package dev.portfolio.notification.pipeline

import dev.portfolio.notification.compose.GraphCollector
import dev.portfolio.notification.compose.PathPlanRegistry
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
import dev.portfolio.notification.support.PrefixComposer
import dev.portfolio.notification.support.RecordingChannel
import dev.portfolio.notification.support.RecordingMessageStore
import dev.portfolio.notification.support.TestGraph
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes

class NotificationPipelineTest : StringSpec({
    "dispatch: store lookup then default compose then single-platform transmit -> COMPLETED" {
        runTest {
            val repo = NodeRepository(TestGraph.data)
            val store = InMemorySpecStore(listOf(TestGraph.SHIPPED_SPEC))
            val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(store.findAll()) }
            val default = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
            val email = RecordingChannel("email", listOf(true))
            val msgStore = RecordingMessageStore()
            val pipeline = NotificationPipeline(
                store, ComposerFinder(listOf(default)), TransmitService(ChannelRegistry(listOf(email))),
                RetransmissionPolicy(), msgStore, MutableClock(),
            )
            pipeline.dispatch(specKey = "SHIPPED", startId = 1L) shouldBe MessageState.COMPLETED
            email.sent.single() shouldBe "Hi Alice, your Widget shipped via FedEx"
            msgStore.saved.last().state shouldBe MessageState.COMPLETED   // state snapshot persisted
            msgStore.saved.last().dueAt.shouldBeNull()                    // terminal -> not enqueued (no dueAt)
        }
    }

    "finder selects special composer by key end-to-end, else default" {
        runTest {
            val repo = NodeRepository(TestGraph.data)
            val store = InMemorySpecStore(listOf(TestGraph.SHIPPED_SPEC))
            val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(store.findAll()) }
            val default = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
            val urgent = PrefixComposer(key = "SHIPPED", prefix = "[URGENT] ", delegate = default)
            val email = RecordingChannel("email", listOf(true))
            val pipeline = NotificationPipeline(
                store, ComposerFinder(listOf(urgent, default)), TransmitService(ChannelRegistry(listOf(email))),
                RetransmissionPolicy(), RecordingMessageStore(), MutableClock(),
            )
            pipeline.dispatch("SHIPPED", startId = 1L) shouldBe MessageState.COMPLETED
            email.sent.single() shouldBe "[URGENT] Hi Alice, your Widget shipped via FedEx"
        }
    }

    "failed delivery with retry budget -> RETRYING, persisted with a future dueAt (wait registration)" {
        runTest {
            val repo = NodeRepository(TestGraph.data)
            val now = MutableClock()
            val store = InMemorySpecStore(listOf(TestGraph.SHIPPED_SPEC.copy(retryBudget = 1, retryInterval = 5.minutes)))
            val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(store.findAll()) }
            val default = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
            val email = RecordingChannel("email", listOf(false))   // delivery fails
            val msgStore = RecordingMessageStore()
            val pipeline = NotificationPipeline(
                store, ComposerFinder(listOf(default)), TransmitService(ChannelRegistry(listOf(email))),
                RetransmissionPolicy(), msgStore, now,
            )
            pipeline.dispatch("SHIPPED", startId = 1L) shouldBe MessageState.RETRYING
            val saved = msgStore.saved.last()
            saved.state shouldBe MessageState.RETRYING
            saved.dueAt.shouldNotBeNull()                       // non-terminal -> enqueued
            saved.dueAt shouldBe now.now() + 5.minutes          // first retry waits exactly the base interval
        }
    }

    "successful delivery with repeat budget -> REPEATING, persisted with a fixed-period dueAt" {
        runTest {
            val repo = NodeRepository(TestGraph.data)
            val now = MutableClock()
            val store = InMemorySpecStore(listOf(TestGraph.SHIPPED_SPEC.copy(repeatBudget = 1, repeatInterval = 30.minutes)))
            val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(store.findAll()) }
            val default = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
            val email = RecordingChannel("email", listOf(true))
            val msgStore = RecordingMessageStore()
            val pipeline = NotificationPipeline(
                store, ComposerFinder(listOf(default)), TransmitService(ChannelRegistry(listOf(email))),
                RetransmissionPolicy(), msgStore, now,
            )
            pipeline.dispatch("SHIPPED", startId = 1L) shouldBe MessageState.REPEATING
            val saved = msgStore.saved.last()
            saved.state shouldBe MessageState.REPEATING
            saved.dueAt shouldBe now.now() + 30.minutes         // repeat = fixed period
        }
    }
})
