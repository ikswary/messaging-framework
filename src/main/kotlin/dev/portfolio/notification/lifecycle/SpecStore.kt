package dev.portfolio.notification.lifecycle

import dev.portfolio.notification.domain.MessageSpec

/** Definition store seam. Production = cache / DB; here = in-memory fake. */
interface SpecStore {
    fun find(key: String): MessageSpec
    fun findAll(): List<MessageSpec>     // boot precompute compiles these
}
