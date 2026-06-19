package dev.portfolio.notification.render

/** A single value -> String. Default is toString; only special types get a custom formatter. Not a gate (unregistered = toString fallback). */
fun interface ValueFormatter {
    fun format(value: Any?): String
}
