package dev.portfolio.notification.domain

import kotlin.reflect.KClass

class NodeSchema(private val map: Map<NodeType, KClass<*>>) {
    fun kClassOf(type: NodeType): KClass<*> = map.getValue(type)

    companion object {
        val DEFAULT = NodeSchema(
            mapOf(
                NodeType.ORDER to Order::class,
                NodeType.CUSTOMER to Customer::class,
                NodeType.PRODUCT to Product::class,
                NodeType.SHIPMENT to Shipment::class,
                NodeType.CARRIER to Carrier::class,
            )
        )
    }
}
