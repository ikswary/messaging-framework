package dev.portfolio.notification.compose

import dev.portfolio.notification.domain.NodeRepository
import dev.portfolio.notification.domain.NodeType
import dev.portfolio.notification.domain.defaultEdges
import dev.portfolio.notification.graph.core.BfsPathFinder
import dev.portfolio.notification.support.CountingPathFinder
import dev.portfolio.notification.support.TestGraph
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BootPrecompileTest : StringSpec({
    "(1) unreachable variable fails at boot" {
        val repo = NodeRepository(TestGraph.data)
        val edges = defaultEdges(repo).filterNot { it.to() == NodeType.CARRIER }
        shouldThrow<IllegalArgumentException> {
            PathPlanRegistry(edges).compile(listOf(TestGraph.spec("{{carrier.name}}", start = NodeType.ORDER)))
        }
    }

    "(1b) missing property fails at boot" {
        val repo = NodeRepository(TestGraph.data)
        shouldThrow<IllegalStateException> {
            PathPlanRegistry(defaultEdges(repo)).compile(listOf(TestGraph.spec("{{customer.zzz}}", start = NodeType.ORDER)))
        }
    }

    "(2) runtime reads cache, no BFS re-run" {
        val repo = NodeRepository(TestGraph.data)
        val counting = CountingPathFinder(BfsPathFinder(defaultEdges(repo)))
        val registry = PathPlanRegistry(defaultEdges(repo), pathFinder = counting)
            .apply { compile(listOf(TestGraph.SHIPPED_SPEC)) }
        val before = counting.calls
        repeat(100) { registry.planFor(TestGraph.SHIPPED_SPEC) }
        counting.calls shouldBe before
    }

    "(3) nullable field on required slot fails at boot" {
        val repo = NodeRepository(TestGraph.data)
        shouldThrow<IllegalArgumentException> {
            PathPlanRegistry(defaultEdges(repo)).compile(listOf(TestGraph.spec("{{customer.nickname}}", start = NodeType.ORDER)))
        }
    }
})
