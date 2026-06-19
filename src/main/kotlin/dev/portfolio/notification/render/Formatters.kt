package dev.portfolio.notification.render

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlin.reflect.KType

object Formatters {
    val DEFAULT = ValueFormatter { it?.toString() ?: "" }

    private val byType: Map<KClass<*>, ValueFormatter> = mapOf(
        OffsetDateTime::class to ValueFormatter {
            (it as OffsetDateTime).atZoneSameInstant(ZoneOffset.ofHours(9)).toString().substringBeforeLast('.')
        },
    )

    /** Formatter by type — falls back to toString when none is registered (not a boot rejection). */
    fun forType(type: KType): ValueFormatter = (type.classifier as? KClass<*>)?.let { byType[it] } ?: DEFAULT
}
