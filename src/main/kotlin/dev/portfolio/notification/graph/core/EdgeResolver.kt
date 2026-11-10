package dev.portfolio.notification.graph.core

/** An edge that also resolves references in batch. A List input enables depth batching (no per-node N+1). */
interface EdgeResolver<KEY> : Edge<KEY> {
    /** from objects -> to objects (batch). */
    suspend fun resolve(from: List<Any>): List<Any>
}
