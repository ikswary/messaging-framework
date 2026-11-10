package dev.portfolio.notification.compose

import dev.portfolio.notification.domain.NodeType
import dev.portfolio.notification.graph.core.EdgeResolver
import dev.portfolio.notification.render.ValueFormatter
import kotlin.reflect.KProperty1

data class VariableBinding(
    val token: String,                       // "customer.email"
    val type: NodeType,
    val property: KProperty1<Any, *>,        // resolved & cached once at boot
    val formatter: ValueFormatter,           // resolved once at boot (toString default; not a gate)
)

data class CollectionPlan(
    val key: String,                                 // spec.key (avoids relying on interface equals)
    val startType: NodeType,
    val levels: List<List<EdgeResolver<NodeType>>>,  // edges grouped by depth — no casts
    val bindings: List<VariableBinding>,
)
