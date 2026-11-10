package dev.portfolio.notification.support

import dev.portfolio.notification.send.Channel

/** Single-platform fake. Succeeds/fails per injected script, records the transmitted arguments. */
class RecordingChannel(override val platform: String, private val script: List<Boolean>) : Channel {
    val sent = mutableListOf<String>()
    private var i = 0
    override suspend fun send(rendered: String) {
        val ok = script.getOrElse(i++) { script.lastOrNull() ?: true }
        if (ok) sent += rendered else error("injected failure on $platform")
    }
}
