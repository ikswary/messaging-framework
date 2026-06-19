package dev.portfolio.notification.graph.core

/** An edge that also resolves references in batch. The real EdgeResolver resolved per-node (N+1); here a List input enables depth batching. */
interface EdgeResolver<KEY> : Edge<KEY> {
    /** from objects -> to objects (batch). */
    suspend fun resolve(from: List<Any>): List<Any>
}
