package dev.portfolio.notification.lifecycle

import dev.portfolio.notification.domain.MessageSpec

/** Definition store seam. Real = code cache (←DB); reconstruction = in-memory fake. */
interface SpecStore {
    fun find(key: String): MessageSpec
    fun findAll(): List<MessageSpec>     // boot precompute compiles these (mirrors the real queryAll cache load)
}
