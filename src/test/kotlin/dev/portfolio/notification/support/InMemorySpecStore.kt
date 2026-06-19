package dev.portfolio.notification.support

import dev.portfolio.notification.domain.MessageSpec
import dev.portfolio.notification.lifecycle.SpecStore

/** Definition store fake (replaces the real code cache / DB). */
class InMemorySpecStore(specs: List<MessageSpec>) : SpecStore {
    private val byKey = specs.associateBy { it.key }
    override fun find(key: String) = byKey.getValue(key)
    override fun findAll() = byKey.values.toList()
}
