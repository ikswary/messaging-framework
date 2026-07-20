package dev.portfolio.notification.compose

import dev.portfolio.notification.domain.NodeRepository
import dev.portfolio.notification.domain.NodeType
import dev.portfolio.notification.domain.defaultEdges
import dev.portfolio.notification.graph.core.BfsPathFinder
import dev.portfolio.notification.graph.core.Edge
import dev.portfolio.notification.graph.core.PathFinder
import dev.portfolio.notification.support.CountingPathFinder
import dev.portfolio.notification.support.TestGraph
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class BootPrecompileTest : StringSpec({
    // (1) registration path = Fail-Fast: in the real system compileOne is what the spec-registration API calls,
    // so a bad spec is rejected at write time and never reaches the store. The reconstruction stops at the store
    // seam, so these tests stand in for that caller.
    "(1) unreachable variable is rejected on registration" {
        val repo = NodeRepository(TestGraph.data)
        val edges = defaultEdges(repo).filterNot { it.to() == NodeType.CARRIER }
        shouldThrow<IllegalArgumentException> {
            PathPlanRegistry(edges).compileOne(TestGraph.spec("{{carrier.name}}", start = NodeType.ORDER))
        }
    }

    "(1b) missing property is rejected on registration" {
        val repo = NodeRepository(TestGraph.data)
        shouldThrow<IllegalStateException> {
            PathPlanRegistry(defaultEdges(repo)).compileOne(TestGraph.spec("{{customer.zzz}}", start = NodeType.ORDER))
        }
    }

    "(1c) nullable field on required slot is rejected on registration" {
        val repo = NodeRepository(TestGraph.data)
        shouldThrow<IllegalArgumentException> {
            PathPlanRegistry(defaultEdges(repo)).compileOne(TestGraph.spec("{{customer.nickname}}", start = NodeType.ORDER))
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

    // (3) boot path = availability: a bad spec that slipped into the store (e.g. direct DB edit) degrades
    // to a warning instead of blocking the whole shared service from starting.
    "(3) boot compile drops a bad spec and keeps the good ones" {
        val repo = NodeRepository(TestGraph.data)
        val edges = defaultEdges(repo).filterNot { it.to() == NodeType.CARRIER }
        val unreachable = TestGraph.spec("{{carrier.name}}", start = NodeType.ORDER)
        val missingProp = TestGraph.spec("{{customer.zzz}}", start = NodeType.ORDER)
        val good = TestGraph.spec("Hi {{customer.name}}", start = NodeType.ORDER)

        val registry = PathPlanRegistry(edges)
        registry.compile(listOf(unreachable, good, missingProp))          // must not throw

        registry.planFor(good).bindings.map { it.token } shouldBe listOf("customer.name")
        registry.excludedSpecs().keys shouldBe setOf(unreachable.key, missingProp.key)
    }

    "(3b) exclusion reason identifies the original cause" {
        val repo = NodeRepository(TestGraph.data)
        val edges = defaultEdges(repo).filterNot { it.to() == NodeType.CARRIER }
        val unreachable = TestGraph.spec("{{carrier.name}}", start = NodeType.ORDER)
        val good = TestGraph.spec("Hi {{customer.name}}", start = NodeType.ORDER)   // keeps the boot above the floor

        val registry = PathPlanRegistry(edges).apply { compile(listOf(unreachable, good)) }

        registry.excludedSpecs() shouldContainKey unreachable.key
        registry.excludedSpecs().getValue(unreachable.key) shouldContain "Unreachable"
    }

    "(4) planFor on an excluded spec throws with the exclusion reason" {
        val repo = NodeRepository(TestGraph.data)
        val edges = defaultEdges(repo).filterNot { it.to() == NodeType.CARRIER }
        val unreachable = TestGraph.spec("{{carrier.name}}", start = NodeType.ORDER)
        val good = TestGraph.spec("Hi {{customer.name}}", start = NodeType.ORDER)   // keeps the boot above the floor
        val registry = PathPlanRegistry(edges).apply { compile(listOf(unreachable, good)) }

        val ex = shouldThrow<SpecPlanUnavailableException> { registry.planFor(unreachable) }
        ex.key shouldBe unreachable.key
        ex.absence shouldBe PlanAbsence.EXCLUDED
        ex.reason shouldNotBe null                                        // excluded, not merely absent
        ex.message!! shouldContain "excluded"
        ex.message!! shouldContain "Unreachable"
    }

    "(4b) planFor on a spec that was never compiled is distinguishable from an exclusion" {
        val repo = NodeRepository(TestGraph.data)
        val never = TestGraph.spec("Hi {{customer.name}}", start = NodeType.ORDER)
        val registry = PathPlanRegistry(defaultEdges(repo)).apply { compile(emptyList()) }

        val ex = shouldThrow<SpecPlanUnavailableException> { registry.planFor(never) }
        ex.absence shouldBe PlanAbsence.NOT_COMPILED
        ex.reason shouldBe null
        registry.excludedSpecs().isEmpty() shouldBe true
    }

    // (4c) the third absence case: no compile() at all. Without this the caller got an
    // UninitializedPropertyAccessException, which says nothing about specs.
    "(4c) planFor before the boot compile reports NOT_BOOTED rather than crashing on lateinit" {
        val repo = NodeRepository(TestGraph.data)
        val registry = PathPlanRegistry(defaultEdges(repo))                // compile() never called

        val ex = shouldThrow<SpecPlanUnavailableException> { registry.planFor(TestGraph.SHIPPED_SPEC) }
        ex.key shouldBe TestGraph.SHIPPED_SPEC.key
        ex.absence shouldBe PlanAbsence.NOT_BOOTED
        ex.reason shouldBe null
    }

    // (5) the degrade is for bad specs only. A wiring bug filed as "bad spec" would empty the cache while the
    // service reports a clean boot, so it has to escape compile().
    "(5) a wiring bug is not swallowed as a spec exclusion" {
        val repo = NodeRepository(TestGraph.data)
        // ORDER -> CARRIER has no EdgeResolver, so toLevels' resolverOf lookup fails: a wiring bug, not a bad spec.
        val ghostEdge = object : Edge<NodeType> {
            override fun from() = NodeType.ORDER
            override fun to() = NodeType.CARRIER
        }
        val miswired = object : PathFinder<NodeType> {
            override fun findPath(destinations: Collection<NodeType>) = listOf(listOf(ghostEdge))
        }
        val registry = PathPlanRegistry(defaultEdges(repo), pathFinder = miswired)

        shouldThrow<NoSuchElementException> {
            registry.compile(listOf(TestGraph.spec("Hi {{customer.name}}", start = NodeType.ORDER)))
        }
        registry.excludedSpecs().isEmpty() shouldBe true                   // not recorded as a spec defect
    }

    // (6) the floor under the availability policy: degrade by spec, never by service.
    "(6) boot is refused when every spec failed to compile" {
        val repo = NodeRepository(TestGraph.data)
        val edges = defaultEdges(repo).filterNot { it.to() == NodeType.CARRIER }
        val unreachable = TestGraph.spec("{{carrier.name}}", start = NodeType.ORDER)
        val missingProp = TestGraph.spec("{{customer.zzz}}", start = NodeType.ORDER)
        val registry = PathPlanRegistry(edges)

        val ex = shouldThrow<AllSpecsExcludedException> { registry.compile(listOf(unreachable, missingProp)) }
        ex.excluded.keys shouldBe setOf(unreachable.key, missingProp.key)  // evidence survives the refusal
        registry.excludedSpecs().keys shouldBe setOf(unreachable.key, missingProp.key)
    }

    "(6b) an empty spec list is a normal boot, not a total failure" {
        val repo = NodeRepository(TestGraph.data)
        val registry = PathPlanRegistry(defaultEdges(repo))

        registry.compile(emptyList())                                      // must not throw
        registry.excludedSpecs().isEmpty() shouldBe true
    }
})
