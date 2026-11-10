package dev.portfolio.notification.send

/** Selects a single sender by platform. */
class ChannelRegistry(private val channels: List<Channel>) {
    fun find(platform: String): Channel =
        channels.find { it.platform == platform } ?: error("No channel for platform '$platform'")
}
