package dev.portfolio.notification.compose

import dev.portfolio.notification.domain.NodeRepository
import dev.portfolio.notification.domain.NodeType
import dev.portfolio.notification.domain.OrderKeyResolver
import dev.portfolio.notification.domain.defaultEdges
import dev.portfolio.notification.support.TestGraph
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GraphCollectorTest : StringSpec({
    "fetches once per type per level regardless of seed count" {
        val repo = NodeRepository(TestGraph.data)
        val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(listOf(TestGraph.SHIPPED_SPEC)) }
        val plan = registry.planFor(TestGraph.SHIPPED_SPEC)
        val collector = GraphCollector(OrderKeyResolver(repo))
        collector.collect(plan, startId = 1L)
        repo.callCount[NodeType.CUSTOMER] shouldBe 1
        repo.callCount[NodeType.SHIPMENT] shouldBe 1
        repo.callCount[NodeType.CARRIER] shouldBe 1
    }
})
