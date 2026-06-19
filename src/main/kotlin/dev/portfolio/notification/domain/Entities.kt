package dev.portfolio.notification.domain

data class Order(val id: Long, val customerId: Long, val productId: Long, val shipmentId: Long)
data class Customer(val id: Long, val name: String, val email: String, val nickname: String? = null)
data class Product(val id: Long, val name: String, val price: Int)
data class Shipment(val id: Long, val carrierId: Long, val trackingNo: String)
data class Carrier(val id: Long, val name: String)
