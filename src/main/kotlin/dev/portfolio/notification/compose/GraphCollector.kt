package dev.portfolio.notification.compose

import dev.portfolio.notification.domain.NodeType
import dev.portfolio.notification.graph.core.KeyResolver

/**
 * Collects with the cached plan, one batch call per type per level. Improves the real
 * GraphFinder.recursiveResolve into depth batching (removes N+1).
 *
 * Assumes 1:1 references (single reference). one-to-many is intentionally out of scope (the real
 * system handled multiples via joinToString; the reconstruction simplifies).
 */
class GraphCollector(private val keyResolver: KeyResolver<NodeType>) {
    suspend fun collect(plan: CollectionPlan, startId: Any): Map<NodeType, List<Any>> {
        val byType = linkedMapOf(plan.startType to listOf(keyResolver.resolve(startId)))
        for (level in plan.levels) {                       // depth order (depth-to-depth dependency)
            for (edge in level) {                          // same depth = independent (parallelizable)
                val fromObjs = byType[edge.from()].orEmpty()
                if (fromObjs.isEmpty()) continue
                val fetched = edge.resolve(fromObjs)        // one batch per type (1 call for N rows)
                byType.merge(edge.to(), fetched) { a, b -> a + b }
            }
        }
        return byType
    }
}
