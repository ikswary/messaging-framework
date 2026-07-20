package dev.portfolio.notification.graph.core

/**
 * Thrown by [PathFinder.findPath] when some destinations cannot be reached from the start node.
 *
 * Typed on purpose. The finder's job is to report the fact; whether that fact aborts a boot or only drops one
 * spec is the caller's policy. Carrying [from] and [unreachable] as fields is what lets a caller act on the
 * fact without parsing [message] — a string-matched policy is a policy that breaks when a message is reworded.
 *
 * Stays an IllegalArgumentException: an unreachable destination is still a bad argument to findPath, and
 * callers that only care about that coarser contract keep working unchanged.
 */
class UnreachableDestinationsException(
    val from: Any?,
    val unreachable: Set<Any?>,
    message: String,
) : IllegalArgumentException(message)
