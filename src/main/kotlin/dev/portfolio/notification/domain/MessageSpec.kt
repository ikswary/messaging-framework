package dev.portfolio.notification.domain

import kotlin.time.Duration

/**
 * Definition (stored model). In production this would be persisted in a cache / DB.
 * A token {{TYPE.prop}} names a source node type and one of its properties.
 *
 * Lives in domain (shared by compose & lifecycle) to avoid a package cycle.
 */
data class MessageSpec(
    val key: String,
    val startType: NodeType,
    val template: String,
    val platform: String,
    val retryBudget: Int = 0,   // failure re-send budget (independent from repeatBudget)
    val repeatBudget: Int = 0,  // success repeat-send budget
    val retryInterval: Duration = Duration.ZERO,   // failure backoff base (grows per attempt; see nextInterval)
    val repeatInterval: Duration = Duration.ZERO,  // success repeat fixed period
)
