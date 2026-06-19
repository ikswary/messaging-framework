package dev.portfolio.notification.domain

import kotlin.time.Duration

/**
 * Definition (stored model). In the real system this is a completion-spec persisted in a code cache / DB.
 * A token {{TYPE.prop}} is the inline compressed form of the real CompletionValue(from=source, key=prop).
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
