package dev.portfolio.notification.graph.core

/**
 * Strong default. Unweighted BFS + set-cover greedy, an anonymized rewrite of the real
 * findPath + getMostEfficientPath.
 *
 * F-5: the constructor [edges] is kept local (only the adjacency map is retained) instead of a
 * private val field — nothing else needs the raw edge collection after construction.
 */
open class BfsPathFinder<KEY>(edges: Collection<Edge<KEY>>) : PathFinder<KEY> {
    private val adjacency: Map<KEY, List<Edge<KEY>>> = edges.groupBy { it.from() }

    override fun findPath(destinations: Collection<KEY>): List<List<Edge<KEY>>> {
        val targets = destinations.toList()
        require(targets.isNotEmpty()) { "destinations is empty" }
        if (targets.size == 1) return emptyList()
        val start = targets.first()
        val rest = targets.drop(1).toSet()

        val chainTo = bfsChains(start, rest)
        val unreachable = rest - chainTo.keys
        require(unreachable.isEmpty()) { "Unreachable from $start: $unreachable" }
        return coverApprox(targets, rest, chainTo)
    }

    private fun bfsChains(start: KEY, targets: Set<KEY>): Map<KEY, List<Edge<KEY>>> {
        val chain = HashMap<KEY, List<Edge<KEY>>>()
        val visited = hashSetOf(start)
        val queue = ArrayDeque<KEY>().apply { add(start) }
        var remaining = targets.size
        while (queue.isNotEmpty() && remaining > 0) {
            val node = queue.removeFirst()
            val outgoing = adjacency[node].orEmpty().sortedByDescending { it.to() in targets }
            for (edge in outgoing) {
                val next = edge.to()
                if (!visited.add(next)) continue
                chain[next] = chain[node].orEmpty() + edge
                if (next in targets) remaining--
                queue.addLast(next)
            }
        }
        return chain
    }

    // Set-cover greedy: repeatedly pick the chain covering the most still-uncovered destinations.
    // Only the number of chains shrinks; correctness is guaranteed by BFS.
    private fun coverApprox(ordered: List<KEY>, targets: Set<KEY>, chainTo: Map<KEY, List<Edge<KEY>>>): List<List<Edge<KEY>>> {
        val coversOf = targets.associateWith { dest ->
            (chainTo.getValue(dest).map { it.to() }.toSet() + dest) intersect targets
        }
        val uncovered = targets.toMutableSet()
        val chosen = mutableListOf<List<Edge<KEY>>>()
        while (uncovered.isNotEmpty()) {
            val best = ordered.filter { it in uncovered }
                .maxBy { (coversOf.getValue(it) intersect uncovered).size }
            chosen += chainTo.getValue(best)
            uncovered -= coversOf.getValue(best)
        }
        return chosen
    }
}
