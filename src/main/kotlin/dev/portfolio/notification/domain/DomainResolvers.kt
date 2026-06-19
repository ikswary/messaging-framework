package dev.portfolio.notification.domain

import dev.portfolio.notification.graph.core.EdgeResolver
import dev.portfolio.notification.graph.core.KeyResolver

/**
 * Each domain owns its outgoing edges (global-graph ownership is distributed). Mirrors the real *EdgeResolver pattern.
 * The repo is a runtime seam (an in-memory fake is injected in tests). The real system resolved per-node; here it batches.
 */
class NodeRepository(private val data: Map<NodeType, Map<Any, Any>>) {
    val callCount = mutableMapOf<NodeType, Int>()

    suspend fun fetch(type: NodeType, ids: List<Any>): List<Any> {
        callCount.merge(type, 1, Int::plus)            // one batch call = +1 (regardless of count)
        return ids.mapNotNull { data[type]?.get(it) }
    }
}

class OrderToCustomer(private val repo: NodeRepository) : EdgeResolver<NodeType> {
    override fun from() = NodeType.ORDER
    override fun to() = NodeType.CUSTOMER
    override suspend fun resolve(from: List<Any>) = repo.fetch(NodeType.CUSTOMER, from.map { (it as Order).customerId })
}

class OrderToProduct(private val repo: NodeRepository) : EdgeResolver<NodeType> {
    override fun from() = NodeType.ORDER
    override fun to() = NodeType.PRODUCT
    override suspend fun resolve(from: List<Any>) = repo.fetch(NodeType.PRODUCT, from.map { (it as Order).productId })
}

class OrderToShipment(private val repo: NodeRepository) : EdgeResolver<NodeType> {
    override fun from() = NodeType.ORDER
    override fun to() = NodeType.SHIPMENT
    override suspend fun resolve(from: List<Any>) = repo.fetch(NodeType.SHIPMENT, from.map { (it as Order).shipmentId })
}

class ShipmentToCarrier(private val repo: NodeRepository) : EdgeResolver<NodeType> {
    override fun from() = NodeType.SHIPMENT
    override fun to() = NodeType.CARRIER
    override suspend fun resolve(from: List<Any>) = repo.fetch(NodeType.CARRIER, from.map { (it as Shipment).carrierId })
}

class OrderKeyResolver(private val repo: NodeRepository) : KeyResolver<NodeType> {
    override fun key() = NodeType.ORDER
    override suspend fun resolve(startId: Any) = repo.fetch(NodeType.ORDER, listOf(startId)).first()
}

fun defaultEdges(repo: NodeRepository) = listOf(
    OrderToCustomer(repo), OrderToProduct(repo), OrderToShipment(repo), ShipmentToCarrier(repo)
)
