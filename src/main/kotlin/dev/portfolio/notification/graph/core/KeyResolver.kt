package dev.portfolio.notification.graph.core

/** Resolves a seed (startId) into the starting node object. Mirrors the real KeyResolver<KEY, R>. */
interface KeyResolver<KEY> {
    fun key(): KEY
    suspend fun resolve(startId: Any): Any
}
