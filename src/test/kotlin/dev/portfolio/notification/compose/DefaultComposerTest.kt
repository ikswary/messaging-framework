package dev.portfolio.notification.compose

import dev.portfolio.notification.domain.NodeRepository
import dev.portfolio.notification.domain.NodeType
import dev.portfolio.notification.domain.OrderKeyResolver
import dev.portfolio.notification.domain.defaultEdges
import dev.portfolio.notification.lifecycle.DefaultComposer
import dev.portfolio.notification.support.TestGraph
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class DefaultComposerTest : StringSpec({
    "DefaultComposer renders template from graph collection" {
        runTest {
            val repo = NodeRepository(TestGraph.data)
            val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(listOf(TestGraph.SHIPPED_SPEC)) }
            val composer = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
            composer.compose(TestGraph.SHIPPED_SPEC, startId = 1L) shouldBe "Hi Alice, your Widget shipped via FedEx"
        }
    }

    "renders tokens with inner spaces" {
        runTest {
            val repo = NodeRepository(TestGraph.data)
            val s = TestGraph.spec("Hi {{ customer.name }}!", start = NodeType.ORDER)
            val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(listOf(s)) }
            val composer = DefaultComposer(registry, GraphCollector(OrderKeyResolver(repo)))
            composer.compose(s, startId = 1L) shouldBe "Hi Alice!"
        }
    }
})
