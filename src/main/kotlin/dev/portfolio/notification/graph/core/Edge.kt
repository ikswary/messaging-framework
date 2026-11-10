package dev.portfolio.notification.graph.core

/** Unweighted edge (weight fixed to 1). */
interface Edge<KEY> {
    fun from(): KEY
    fun to(): KEY
    fun weight(): Int = 1
}
