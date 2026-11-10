package dev.portfolio.notification.support

import dev.portfolio.notification.graph.core.PathFinder

/** Counts findPath invocations (proves caching = 0 BFS at runtime). */
class CountingPathFinder<KEY>(private val delegate: PathFinder<KEY>) : PathFinder<KEY> {
    var calls = 0
    override fun findPath(destinations: Collection<KEY>) = delegate.findPath(destinations).also { calls++ }
}
