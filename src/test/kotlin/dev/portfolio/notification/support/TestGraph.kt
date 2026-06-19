package dev.portfolio.notification.support

import dev.portfolio.notification.domain.Carrier
import dev.portfolio.notification.domain.Customer
import dev.portfolio.notification.domain.MessageSpec
import dev.portfolio.notification.domain.NodeType
import dev.portfolio.notification.domain.Order
import dev.portfolio.notification.domain.Product
import dev.portfolio.notification.domain.Shipment

/**
 * Shared test fixture: sample graph data + MessageSpec factory.
 * data layout = Map<NodeType, Map<id, entity>> consumed by NodeRepository.
 */
object TestGraph {
    val data: Map<NodeType, Map<Any, Any>> = mapOf(
        NodeType.ORDER to mapOf(1L to Order(id = 1L, customerId = 10L, productId = 20L, shipmentId = 30L)),
        NodeType.CUSTOMER to mapOf(10L to Customer(id = 10L, name = "Alice", email = "alice@example.com")),
        NodeType.PRODUCT to mapOf(20L to Product(id = 20L, name = "Widget", price = 100)),
        NodeType.SHIPMENT to mapOf(30L to Shipment(id = 30L, carrierId = 40L, trackingNo = "TRK-1")),
        NodeType.CARRIER to mapOf(40L to Carrier(id = 40L, name = "FedEx")),
    )

    private var seq = 0

    /** Unique-key MessageSpec factory (platform defaults to "email"). */
    fun spec(template: String, start: NodeType, platform: String = "email"): MessageSpec =
        MessageSpec(key = "TEST_${seq++}", startType = start, template = template, platform = platform)

    val SHIPPED_SPEC = MessageSpec(
        key = "SHIPPED",
        startType = NodeType.ORDER,
        template = "Hi {{customer.name}}, your {{product.name}} shipped via {{carrier.name}}",
        platform = "email",
    )
}
