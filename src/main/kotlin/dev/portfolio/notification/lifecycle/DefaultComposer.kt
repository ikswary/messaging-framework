package dev.portfolio.notification.lifecycle

import dev.portfolio.notification.compose.GraphCollector
import dev.portfolio.notification.compose.PathPlanRegistry
import dev.portfolio.notification.compose.TemplateParser
import dev.portfolio.notification.domain.MessageSpec

/**
 * General-purpose graph-fill. Mirrors the real DefaultMessageSpecComposeService.
 * Uses the cached plan (boot precompute) to collect, then reflection renders. Absorbs the old NotificationComposer.
 */
class DefaultComposer(
    private val registry: PathPlanRegistry,
    private val collector: GraphCollector,
) : SpecComposer {
    override fun supports(key: String?) = key == null              // default

    override suspend fun compose(def: MessageSpec, startId: Any): String {
        val plan = registry.planFor(def)                           // cached plan (0 runtime BFS)
        val byType = collector.collect(plan, startId)              // level-batched collection
        val values = plan.bindings.associate { b ->                // (simplification: 1:1 references)
            b.token to b.formatter.format(b.property.get(byType.getValue(b.type).first()))
        }
        // Mirrors the real StringParameterReplacer: regex substitution (inner spaces allowed; missing token -> "")
        return TemplateParser.RE.replace(def.template) { m -> values[m.groupValues[1]] ?: "" }
    }
}
