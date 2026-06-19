package dev.portfolio.notification.graph

import dev.portfolio.notification.domain.NodeRepository
import dev.portfolio.notification.domain.NodeType
import dev.portfolio.notification.domain.defaultEdges
import dev.portfolio.notification.graph.core.BfsPathFinder
import dev.portfolio.notification.graph.core.Edge
import dev.portfolio.notification.support.TestGraph
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class BfsPathFinderTest : StringSpec({
    "single destination yields empty path" {
        val repo = NodeRepository(TestGraph.data)
        BfsPathFinder(defaultEdges(repo)).findPath(listOf(NodeType.ORDER)) shouldBe emptyList()
    }

    "single edge path to one destination" {
        val repo = NodeRepository(TestGraph.data)
        val paths = BfsPathFinder(defaultEdges(repo)).findPath(listOf(NodeType.ORDER, NodeType.CUSTOMER))
        paths.flatMap { c -> c.map { it.to() } } shouldBe listOf(NodeType.CUSTOMER)
    }

    "disjoint targets split into two chains" {
        val repo = NodeRepository(TestGraph.data)
        val paths = BfsPathFinder(defaultEdges(repo))
            .findPath(listOf(NodeType.ORDER, NodeType.CUSTOMER, NodeType.PRODUCT))
        paths.size shouldBe 2
        paths.all { it.size == 1 } shouldBe true
    }

    "set-cover merges shared waypoint" {
        val repo = NodeRepository(TestGraph.data)
        val pf = BfsPathFinder(defaultEdges(repo))
        val paths = pf.findPath(listOf(NodeType.ORDER, NodeType.SHIPMENT, NodeType.CARRIER))
        paths.size shouldBe 1
        paths.single().map { it.to() } shouldBe listOf(NodeType.SHIPMENT, NodeType.CARRIER)
    }

    "terminates on cycle" {
        fun e(f: String, t: String) = object : Edge<String> {
            override fun from() = f
            override fun to() = t
        }
        val a2b = e("A", "B")
        val b2a = e("B", "A")
        BfsPathFinder(listOf(a2b, b2a)).findPath(listOf("A", "B")).flatten().map { it.to() } shouldBe listOf("B")
    }

    "throws on unreachable" {
        val repo = NodeRepository(TestGraph.data)
        val pf = BfsPathFinder(defaultEdges(repo).filterNot { it.to() == NodeType.CARRIER })
        shouldThrow<IllegalArgumentException> { pf.findPath(listOf(NodeType.ORDER, NodeType.CARRIER)) }
    }

    "greedy uses fewer chains than per-target when a target lies on another's path" {
        fun e(f: String, t: String) = object : Edge<String> {
            override fun from() = f
            override fun to() = t
        }
        val pf = BfsPathFinder(listOf(e("S", "A"), e("A", "D"), e("S", "C")))
        val paths = pf.findPath(listOf("S", "A", "C", "D"))
        paths.size shouldBe 2
        paths.flatMap { c -> c.map { it.to() } }.toSet() shouldContainAll setOf("A", "C", "D")
    }
})
