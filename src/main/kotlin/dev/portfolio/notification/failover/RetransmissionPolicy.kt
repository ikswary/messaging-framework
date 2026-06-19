package dev.portfolio.notification.failover

enum class RetransmissionAction { REREQUEST, COMPLETE, RETRY, EXCEEDED }

/**
 * success x repeatable matrix pure function. Anonymizes the real NotificationRetransmissionService.handle().
 * row-lock / event-sourcing / scheduler = diagram (§7.2).
 */
class RetransmissionPolicy {
    /** repeatable = repeat-send remaining (real repetitionCountLeft >= 1). No alternate-channel concept. */
    fun next(success: Boolean, repeatable: Boolean): RetransmissionAction = when {
        success && repeatable  -> RetransmissionAction.REREQUEST   // T x repeatable -> re-request
        success && !repeatable -> RetransmissionAction.COMPLETE    // T x !repeatable -> complete
        !success && repeatable -> RetransmissionAction.RETRY       // F x repeatable -> RetrySet(interval)
        else                   -> RetransmissionAction.EXCEEDED    // F x !repeatable -> RetryCountExceeded
    }
}
