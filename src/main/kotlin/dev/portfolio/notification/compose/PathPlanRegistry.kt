package dev.portfolio.notification.compose

import dev.portfolio.notification.domain.MessageSpec
import dev.portfolio.notification.domain.NodeSchema
import dev.portfolio.notification.domain.NodeType
import dev.portfolio.notification.graph.core.BfsPathFinder
import dev.portfolio.notification.graph.core.Edge
import dev.portfolio.notification.graph.core.EdgeResolver
import dev.portfolio.notification.graph.core.PathFinder
import dev.portfolio.notification.render.Formatters
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * The real system ran graphFinder.find (runtime BFS) on every compose; the reconstruction does a single
 * boot-time pass that validates reachability, caches the plan, and type-checks bindings (Fail-Fast).
 */
class PathPlanRegistry(
    edges: Collection<EdgeResolver<NodeType>>,
    private val pathFinder: PathFinder<NodeType> = BfsPathFinder(edges),
    private val schema: NodeSchema = NodeSchema.DEFAULT,
) {
    // (from, to) -> EdgeResolver. Assumes exactly one resolver per (from, to) pair.
    private val resolverOf = edges.associateBy { it.from() to it.to() }
    private lateinit var plans: Map<String, CollectionPlan>

    /** Boot once. On failure the application aborts startup (Fail-Fast). Same pass validates, caches, and type-checks. */
    fun compile(specs: List<MessageSpec>) {
        plans = specs.associate { spec ->
            val tokens = TemplateParser.variables(spec.template)
            val bindings = tokens.map { resolveBinding(it) }                  // (c)(d) mapping + type/null check
            val destinations = (listOf(spec.startType) + bindings.map { it.type }).distinct()
            val paths = pathFinder.findPath(destinations)                     // (a) BFS reachability (unreachable -> throw)
            spec.key to CollectionPlan(spec.key, spec.startType, toLevels(spec.startType, paths), bindings)  // (b) cache
        }
    }

    fun planFor(spec: MessageSpec): CollectionPlan = plans.getValue(spec.key)  // runtime = lookup only, 0 BFS

    private fun resolveBinding(token: String): VariableBinding {
        val (typeName, propName) = token.split(".", limit = 2)
            .also { require(it.size == 2) { "Bad token '$token'" } }
        val type = NodeType.valueOf(typeName.uppercase())
        val klass = schema.kClassOf(type)
        @Suppress("UNCHECKED_CAST")
        val prop = klass.memberProperties.find { it.name == propName } as? KProperty1<Any, *>
            ?: error("No property '$propName' on $type ('$token')")           // missing -> Fail-Fast
        require(!prop.returnType.isMarkedNullable) { "Nullable field bound to required slot: '$token'" } // null -> Fail-Fast
        return VariableBinding(token, type, prop, Formatters.forType(prop.returnType))
    }

    // Derive explicit BFS depth from start using the chosen edges (no reliance on flatten order / fallbacks). Returns EdgeResolver (0 casts).
    private fun toLevels(start: NodeType, paths: List<List<Edge<NodeType>>>): List<List<EdgeResolver<NodeType>>> {
        val adj = paths.flatten().distinct().groupBy { it.from() }
        val levels = mutableListOf<List<EdgeResolver<NodeType>>>()
        var frontier = listOf(start)
        val seen = hashSetOf(start)
        while (true) {
            val atLevel = frontier.flatMap { adj[it].orEmpty() }
            if (atLevel.isEmpty()) break
            levels += atLevel.map { resolverOf.getValue(it.from() to it.to()) }
            frontier = atLevel.map { it.to() }.filter { seen.add(it) }        // only new nodes advance (cycle-safe)
        }
        return levels
    }
}
