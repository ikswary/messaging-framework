package dev.portfolio.notification.lifecycle

import dev.portfolio.notification.domain.MessageSpec

/** composer = fill engine (strategy). */
interface SpecComposer {
    fun supports(key: String?): Boolean                            // null = default
    suspend fun compose(def: MessageSpec, startId: Any): String    // definition -> filled string
}

class ComposerFinder(private val composers: List<SpecComposer>) {
    fun find(key: String): SpecComposer =
        composers.find { it.supports(key) }                        // special form first (injected)
            ?: composers.find { it.supports(null) }                // default fallback
            ?: error("No composer for '$key'")
}
