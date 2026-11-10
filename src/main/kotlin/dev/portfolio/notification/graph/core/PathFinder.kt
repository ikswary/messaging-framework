package dev.portfolio.notification.graph.core

interface PathFinder<KEY> {
    /**
     * Traversal chains (each = an Edge chain) covering all destinations.
     * Throws when a destination is unreachable.
     */
    fun findPath(destinations: Collection<KEY>): List<List<Edge<KEY>>>
}
