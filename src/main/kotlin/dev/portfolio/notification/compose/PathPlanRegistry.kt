package dev.portfolio.notification.compose

import dev.portfolio.notification.domain.MessageSpec
import dev.portfolio.notification.domain.NodeSchema
import dev.portfolio.notification.domain.NodeType
import dev.portfolio.notification.graph.core.BfsPathFinder
import dev.portfolio.notification.graph.core.Edge
import dev.portfolio.notification.graph.core.EdgeResolver
import dev.portfolio.notification.graph.core.PathFinder
import dev.portfolio.notification.graph.core.UnreachableDestinationsException
import dev.portfolio.notification.render.Formatters
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/** Why [PathPlanRegistry.planFor] has no plan to hand back. The three cases page different people. */
enum class PlanAbsence {
    /** [PathPlanRegistry.compile] never completed, so there is no cache to read at all — a lifecycle bug. */
    NOT_BOOTED,

    /** The spec was in the boot compile and its own compile failed, so it was dropped. Fix the spec. */
    EXCLUDED,

    /** Boot was fine, this spec simply was not among the compiled ones — a store/cache mismatch. */
    NOT_COMPILED,
}

/**
 * Thrown by [PathPlanRegistry.planFor] when no cached plan is available for a spec.
 *
 * [absence] is the machine-readable cause; [reason] carries the original failure text and is non-null exactly
 * when [absence] is [PlanAbsence.EXCLUDED].
 */
class SpecPlanUnavailableException(
    val key: String,
    val absence: PlanAbsence,
    val reason: String? = null,
) : IllegalStateException(
    when (absence) {
        PlanAbsence.NOT_BOOTED -> "No compiled plan for spec '$key' (the registry has not completed a boot compile)"
        PlanAbsence.EXCLUDED -> "Spec '$key' was excluded at boot compile: $reason"
        PlanAbsence.NOT_COMPILED -> "No compiled plan for spec '$key' (spec was not part of the boot compile)"
    }
)

/**
 * Thrown by [PathPlanRegistry.compile] when it was handed specs and not one of them compiled.
 *
 * Degrading per spec is the point of the availability policy; degrading to zero specs is not a degraded
 * service, it is a service that boots "healthy" and then fails every single send. That is worse than not
 * booting, so this is the floor under the policy: degrade by spec, never by service.
 */
class AllSpecsExcludedException(val excluded: Map<String, String>) : IllegalStateException(
    "All ${excluded.size} spec(s) failed to compile; refusing to boot with an empty plan cache: $excluded"
)

/** Template token is malformed, names an unknown node type, or binds a nullable property to a required slot. */
class InvalidBindingException(message: String) : IllegalArgumentException(message)

/** Template binds a property the node type does not have. */
class UnknownPropertyException(message: String) : IllegalStateException(message)

/**
 * The real system ran graphFinder.find (runtime BFS) on every compose; the reconstruction does a single
 * boot-time pass that validates reachability, caches the plan, and type-checks bindings.
 *
 * Availability policy: this is a shared messaging service fronting many upstream services. One bad spec
 * (e.g. hand-edited straight into the DB) must not keep the whole service from booting, so [compile] degrades
 * per spec instead of aborting startup. The safety net is observability, not Fail-Fast: every dropped spec is
 * warned about and stays queryable via [excludedSpecs]. Fail-Fast is kept where it is cheap and correct, on the
 * normal write path: in the real system the spec-registration API is what calls [compileOne], so a bad spec is
 * rejected at write time and never reaches the store. The reconstruction stops at the store seam (see SpecStore),
 * so that caller has no counterpart here and [compileOne] is exercised by tests only.
 *
 * The degrade has two bounds, and both are types rather than string matching:
 *  - Only the spec's own defects are degradable — [UnreachableDestinationsException], [InvalidBindingException],
 *    [UnknownPropertyException]. A wiring bug (a missing resolver, an incomplete [NodeSchema]) is not a bad spec
 *    and must not be recorded as one; it propagates out of [compile] and fails the boot.
 *  - [compile] refuses to boot at all if nothing compiled (see [AllSpecsExcludedException]).
 *
 * Layering: [BfsPathFinder] only reports the fact (unreachable, with from/unreachable on the exception);
 * whether that fact is a boot failure or a warning is policy, and policy lives here.
 */
class PathPlanRegistry(
    edges: Collection<EdgeResolver<NodeType>>,
    private val pathFinder: PathFinder<NodeType> = BfsPathFinder(edges),
    private val schema: NodeSchema = NodeSchema.DEFAULT,
) {
    // (from, to) -> EdgeResolver. Assumes exactly one resolver per (from, to) pair.
    private val resolverOf = edges.associateBy { it.from() to it.to() }
    private lateinit var plans: Map<String, CollectionPlan>
    private var excluded: Map<String, String> = emptyMap()   // spec.key -> why it was dropped (empty before boot)

    /**
     * Validate + compile a single spec, throwing on any defect (unreachable variable, missing property,
     * nullable binding). In the real system this is the entry point the spec-registration API calls: rejecting
     * at write time is what keeps a broken spec out of the store, so the degraded path below stays rare.
     */
    fun compileOne(spec: MessageSpec): CollectionPlan {
        val tokens = TemplateParser.variables(spec.template)
        val bindings = tokens.map { resolveBinding(it) }                  // (c)(d) mapping + type/null check
        val destinations = (listOf(spec.startType) + bindings.map { it.type }).distinct()
        val paths = pathFinder.findPath(destinations)                     // (a) BFS reachability (unreachable -> throw)
        return CollectionPlan(spec.key, spec.startType, toLevels(spec.startType, paths), bindings)
    }

    /**
     * Boot once. Compiles per spec and caches the results; a spec that fails on its own defect is dropped with
     * a warning and recorded in [excludedSpecs] rather than aborting startup (see the availability policy above).
     */
    fun compile(specs: List<MessageSpec>) {
        val compiled = LinkedHashMap<String, CollectionPlan>()
        val dropped = LinkedHashMap<String, String>()
        for (spec in specs) {
            // The catch list is the policy. It names only defects of the spec itself, so anything else —
            // a NoSuchElementException from resolverOf/schema, say — walks straight out and fails the boot
            // instead of being filed as "bad spec" and silently emptying the cache.
            try {
                compiled[spec.key] = compileOne(spec)                     // (b) cache
            } catch (e: UnreachableDestinationsException) {
                dropped[spec.key] = exclude(spec, e)
            } catch (e: InvalidBindingException) {
                dropped[spec.key] = exclude(spec, e)
            } catch (e: UnknownPropertyException) {
                dropped[spec.key] = exclude(spec, e)
            }
        }
        excluded = dropped                                                // publish diagnostics even if the floor trips
        // Floor: an empty spec list is a normal (if unusual) boot; specs that all failed is not.
        if (specs.isNotEmpty() && compiled.isEmpty()) throw AllSpecsExcludedException(dropped)
        plans = compiled
    }

    fun planFor(spec: MessageSpec): CollectionPlan {                      // runtime = lookup only, 0 BFS
        if (!::plans.isInitialized) throw SpecPlanUnavailableException(spec.key, PlanAbsence.NOT_BOOTED)
        plans[spec.key]?.let { return it }
        val reason = excluded[spec.key]
        throw if (reason != null) SpecPlanUnavailableException(spec.key, PlanAbsence.EXCLUDED, reason)
        else SpecPlanUnavailableException(spec.key, PlanAbsence.NOT_COMPILED)
    }

    /**
     * Specs dropped by the last [compile], as spec.key -> failure reason. Monitoring surface: this is the
     * machine-readable half of the safety net (alert on non-empty / expose as a health detail), since a boot
     * that degrades quietly is worse than one that fails loudly. Populated even when [compile] then refused to
     * boot, so the failure has evidence attached.
     */
    fun excludedSpecs(): Map<String, String> = excluded

    private fun exclude(spec: MessageSpec, e: RuntimeException): String {
        val reason = "${e::class.simpleName}: ${e.message}"               // keep the original cause identifiable
        warn("spec '${spec.key}' excluded from the compiled plans: $reason")
        return reason
    }

    // No logging dependency in this build; stderr keeps the warning dependency-free. excludedSpecs() is the
    // structured surface, so this line only has to be human-readable in the boot log.
    private fun warn(message: String) = System.err.println("[WARN] PathPlanRegistry: $message")

    private fun resolveBinding(token: String): VariableBinding {
        val parts = token.split(".", limit = 2)
        if (parts.size != 2) throw InvalidBindingException("Bad token '$token'")
        val (typeName, propName) = parts
        // Not NodeType.valueOf: it throws a bare IllegalArgumentException, indistinguishable from a wiring bug.
        val type = NodeType.entries.firstOrNull { it.name == typeName.uppercase() }
            ?: throw InvalidBindingException("Unknown node type '$typeName' ('$token')")
        val klass = schema.kClassOf(type)
        @Suppress("UNCHECKED_CAST")
        val prop = klass.memberProperties.find { it.name == propName } as? KProperty1<Any, *>
            ?: throw UnknownPropertyException("No property '$propName' on $type ('$token')")           // missing -> reject
        if (prop.returnType.isMarkedNullable) {
            throw InvalidBindingException("Nullable field bound to required slot: '$token'")           // null -> reject
        }
        return VariableBinding(token, type, prop, Formatters.forType(prop.returnType))
    }

    // Derive explicit BFS depth from start using the chosen edges (no reliance on flatten order / fallbacks). Returns EdgeResolver (0 casts).
    private fun toLevels(start: NodeType, paths: List<List<Edge<NodeType>>>): List<List<EdgeResolver<NodeType>>> {
        val adj = paths.flatten().distinct().groupBy { it.from() }
        val levels = mutableListOf<List<EdgeResolver<NodeType>>>()
        var frontier = listOf(start)
        val seen = hashSetOf(start)
        while (true) {
            val atLevel = frontier.flatMap { adj[it].orEmpty() }
            if (atLevel.isEmpty()) break
            levels += atLevel.map { resolverOf.getValue(it.from() to it.to()) }
            frontier = atLevel.map { it.to() }.filter { seen.add(it) }        // only new nodes advance (cycle-safe)
        }
        return levels
    }
}
