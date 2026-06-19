package dev.portfolio.notification.support

import dev.portfolio.notification.domain.MessageSpec
import dev.portfolio.notification.lifecycle.SpecComposer

/** Special composer demo (doesn't break tokens): delegates to default, then prefixes. Stands in for a concrete *MessageSpecComposeService. */
class PrefixComposer(
    private val key: String,
    private val prefix: String,
    private val delegate: SpecComposer,
) : SpecComposer {
    override fun supports(key: String?) = key == this.key
    override suspend fun compose(def: MessageSpec, startId: Any) = prefix + delegate.compose(def, startId)
}
