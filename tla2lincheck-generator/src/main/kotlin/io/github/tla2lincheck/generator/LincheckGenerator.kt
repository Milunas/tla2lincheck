package io.github.tla2lincheck.generator

import io.github.tla2lincheck.ir.*

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LINCHECK TEST GENERATOR
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Given a [ConcurrentSystemSpec] (parsed from TLA+), generates a complete
 * Lincheck test class that verifies an implementation is linearizable with
 * respect to the specification's sequential semantics AND that all TLA+
 * safety invariants hold under every explored interleaving.
 *
 * ─── THE TRANSLATION ──────────────────────────────────────────────────────────
 *
 *   TLA+ Concept              │ Generated Lincheck Code
 *   ──────────────────────────┼──────────────────────────────────────────────
 *   Module                    │ Test class
 *   VARIABLES                 │ Class fields
 *   CONSTANTS                 │ Class constants + @Param gen ranges
 *   Init                      │ Field initializers
 *   Action(u, b) ==           │ @Operation fun action(@Param ...)
 *   Precondition /\ cond      │ if (cond) { ... } in method body
 *   Effect var' = expr        │ Field mutation
 *   Next == \/ A1 \/ A2       │ Multiple @Operation methods
 *   Invariant                 │ checkInvariants() called after each operation
 *
 * ─── KEY DESIGN DECISION: INVARIANT EMBEDDING ─────────────────────────────────
 *
 *   Lincheck natively checks LINEARIZABILITY — that every concurrent execution
 *   is equivalent to some sequential execution.  But TLA+ specs also define
 *   SAFETY INVARIANTS that must hold in every reachable state.
 *
 *   We bridge this gap by generating a `checkInvariants()` method that is
 *   called at the end of every @Operation.  If any invariant is violated
 *   under any interleaving, Lincheck reports the exact execution trace.
 *
 *   This transforms Lincheck from a pure linearizability checker into a
 *   full safety-property verifier — which is exactly what we need for
 *   TLA+ conformance testing.
 *
 * ─── GENERATED CODE STRUCTURE ─────────────────────────────────────────────────
 *
 *   ```kotlin
 *   @Param(name = "bookId", gen = IntGen::class, conf = "1:5")
 *   class ReservationLincheckTest {
 *       // Fields (from TLA+ VARIABLES + Init)
 *       private var count = 0
 *       private val lockObj = Any()
 *
 *       // Invariant checker (from TLA+ invariants)
 *       private fun checkInvariants() { ... }
 *
 *       // Operations (from TLA+ actions)
 *       @Operation fun reserve(@Param(...) bookId: Int): String = synchronized(lockObj) { ... }
 *
 *       // Test methods
 *       @Test fun modelCheckingTest() = ModelCheckingOptions()...check(this::class)
 *       @Test fun stressTest() = StressOptions()...check(this::class)
 *   }
 *   ```
 */
class LincheckGenerator {

    /**
     * The output of code generation: a complete Kotlin source file.
     */
    data class GeneratedTest(
        val className: String,
        val packageName: String,
        val code: String
    )

    /**
     * Configuration for the generated test class.
     */
    data class Config(
        val packageName: String = "io.github.tla2lincheck.generated",
        val threads: Int = 3,
        val actorsPerThread: Int = 2,
        val iterations: Int = 50,
        val invocationsPerIteration: Int = 1000,
        val generateStressTest: Boolean = true,
        val generateModelCheckingTest: Boolean = true,
        val embedInvariants: Boolean = true
    )

    /**
     * Generates a complete Lincheck test class from a specification.
     */
    fun generate(
        spec: ConcurrentSystemSpec,
        config: Config = Config()
    ): GeneratedTest {
        val className = "${spec.name}LincheckTest"
        val code = buildString {
            appendLine(generateHeader(config.packageName, spec))
            appendLine()
            appendLine(generateClassDoc(spec))
            appendLine(generateParamAnnotations(spec))
            appendLine("class $className {")
            appendLine()
            appendLine(generateFields(spec))
            if (config.embedInvariants && spec.invariants.isNotEmpty()) {
                appendLine(generateInvariantChecker(spec))
            }
            appendLine(generateOperations(spec, config.embedInvariants && spec.invariants.isNotEmpty()))
            appendLine(generateTestMethods(className, config))
            appendLine("}")
        }

        return GeneratedTest(className, config.packageName, code)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HEADER + IMPORTS
    // ─────────────────────────────────────────────────────────────────────

    private fun generateHeader(packageName: String, spec: ConcurrentSystemSpec): String = buildString {
        appendLine("package $packageName")
        appendLine()
        appendLine("import org.jetbrains.kotlinx.lincheck.*")
        appendLine("import org.jetbrains.kotlinx.lincheck.annotations.*")
        appendLine("import org.jetbrains.kotlinx.lincheck.paramgen.*")
        appendLine("import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*")
        appendLine("import org.jetbrains.kotlinx.lincheck.strategy.stress.*")
        appendLine("import org.junit.jupiter.api.Test")
    }

    private fun generateClassDoc(spec: ConcurrentSystemSpec): String = buildString {
        appendLine("/**")
        appendLine(" * AUTO-GENERATED Lincheck test from TLA+ specification: ${spec.name}")
        appendLine(" *")
        appendLine(" * Source: ${spec.source}")
        appendLine(" *")
        appendLine(" * This test verifies that an implementation of the ${spec.name} protocol")
        appendLine(" * is linearizable with respect to the TLA+ specification's sequential semantics")
        if (spec.invariants.isNotEmpty()) {
            appendLine(" * AND that all ${spec.invariants.size} safety invariant(s) hold under every")
            appendLine(" * explored interleaving.")
            appendLine(" *")
            appendLine(" * Invariants checked:")
            spec.invariants.forEach { inv ->
                appendLine(" *   - ${inv.name}: ${inv.description.ifEmpty { inv.rawTla.take(60) }}")
            }
        }
        appendLine(" *")
        if (spec.liveness.isNotEmpty()) {
            appendLine(" * Liveness properties (NOT checked — require TLC model checking):")
            spec.liveness.forEach { lp ->
                appendLine(" *   - ${lp.name}: ${lp.description.ifEmpty { lp.formula.take(60) }}")
            }
            appendLine(" *")
        }
        appendLine(" * Generated by tla2lincheck v${VERSION}")
        appendLine(" */")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  @Param ANNOTATIONS (class-level)
    // ─────────────────────────────────────────────────────────────────────

    private fun generateParamAnnotations(spec: ConcurrentSystemSpec): String = buildString {
        // Collect all unique parameter domains across actions
        val paramDomains = mutableMapOf<String, String>()
        for (action in spec.actions.filter { !it.isSystemAction }) {
            for (param in action.parameters) {
                if (param.domain.isNotEmpty() && param.name !in paramDomains) {
                    paramDomains[param.name] = param.domain
                }
            }
        }

        for ((paramName, domain) in paramDomains) {
            // Try to resolve domain to a range for IntGen
            val range = resolveDomainRange(domain, spec.constants)
            appendLine("@Param(name = \"$paramName\", gen = IntGen::class, conf = \"$range\")")
        }
    }

    /**
     * Resolves a TLA+ domain expression to a Lincheck IntGen range string.
     * Handles:
     *   - Constant name: `Actors` → "1:N" (where N is default value)
     *   - Range expression: `1..MaxMessages` → "1:N"
     */
    private fun resolveDomainRange(domain: String, constants: List<SystemConstant>): String {
        // Direct constant name (e.g., "Actors", "Clients")
        val domainConst = constants.find { it.name == domain }
        if (domainConst != null && domainConst.defaultValue.isNotEmpty()) {
            return "1:${domainConst.defaultValue}"
        }

        // Range expression: 1..MaxMessages or 1..N
        val rangeMatch = Regex("""(\d+)\.\.(\w+)""").find(domain)
        if (rangeMatch != null) {
            val lower = rangeMatch.groupValues[1]
            val upper = rangeMatch.groupValues[2]
            // Resolve upper bound if it refers to a constant
            val upperConst = constants.find { it.name == upper }
            val resolvedUpper = if (upper.matches(Regex("""\d+"""))) {
                upper // already a number
            } else {
                upperConst?.defaultValue?.ifEmpty { null } ?: "5" // fallback to 5
            }
            return "$lower:$resolvedUpper"
        }

        return "1:5" // safe default
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FIELDS (from VARIABLES + Init)
    // ─────────────────────────────────────────────────────────────────────

    private fun generateFields(spec: ConcurrentSystemSpec): String = buildString {
        // Constants FIRST — state field initializers may reference them (e.g., alive = Actors.toMutableSet())
        if (spec.constants.isNotEmpty()) {
            appendLine("    // ── Constants (from TLA+ CONSTANTS) ────────────────────────────")
            for (c in spec.constants) {
                val value = c.defaultValue.ifEmpty { "5" }
                // If constant is used as a Set domain, generate a range
                val isSetDomain = spec.actions.any { a ->
                    a.parameters.any { p -> p.domain == c.name }
                }
                if (isSetDomain) {
                    appendLine("    private val ${c.name} = (1..$value).toSet()")
                } else {
                    appendLine("    private val ${c.name} = $value")
                }
            }
            appendLine()
        }

        appendLine("    // ── State fields (from TLA+ VARIABLES) ─────────────────────────")
        appendLine()

        // Find initialization expressions from Init
        val initMap = spec.init.assignments.associate { it.variable to it }

        for (v in spec.variables) {
            val (kotlinType, initExpr) = kotlinTypeAndInit(v.type, initMap[v.name]?.expression)
            if (v.description.isNotEmpty()) {
                appendLine("    /** ${v.description} */")
            }
            // Use val for collections (they're mutated in-place), var for scalars
            val modifier = when (v.type) {
                VariableType.SET_OF_INT, VariableType.SEQUENCE,
                VariableType.FUNCTION_INT_TO_INT, VariableType.FUNCTION_INT_TO_STRING,
                VariableType.FUNCTION_INT_TO_SET -> "val"
                else -> "var"
            }
            appendLine("    private $modifier ${v.name}: $kotlinType = $initExpr")
        }

        appendLine()
        appendLine("    // ── Synchronization ────────────────────────────────────────────")
        appendLine("    private val lockObj = Any()")
        appendLine()

        // Generate init block for map/set fields that need population
        val needsInit = spec.variables.filter { v ->
            v.type in setOf(
                VariableType.FUNCTION_INT_TO_INT,
                VariableType.FUNCTION_INT_TO_STRING,
                VariableType.FUNCTION_INT_TO_SET
            ) && initMap.containsKey(v.name)
        }
        if (needsInit.isNotEmpty()) {
            appendLine("    init {")
            for (v in needsInit) {
                val init = initMap[v.name]!!
                // Parse [x \in Domain |-> defaultValue] pattern
                val funcLiteral = Regex("""\[\s*\w+\s+\\in\s+(\w+)\s*\|->\s*(.+)]""")
                    .find(init.tlaExpression)
                if (funcLiteral != null) {
                    val domain = funcLiteral.groupValues[1]
                    val defaultVal = funcLiteral.groupValues[2].trim()
                    val kotlinDefault = when {
                        defaultVal == "{}" -> "mutableSetOf<Int>()"
                        defaultVal == "<<>>" -> "mutableListOf()"
                        defaultVal.matches(Regex("""\d+""")) -> defaultVal
                        defaultVal.startsWith("\"") -> defaultVal
                        else -> "0"
                    }
                    appendLine("        for (id in $domain) { ${v.name}[id] = $kotlinDefault }")
                }
            }
            appendLine("    }")
            appendLine()
        }
    }

    private fun kotlinTypeAndInit(type: VariableType, initExpr: String?): Pair<String, String> {
        val init = initExpr ?: type.defaultInit
        return when (type) {
            VariableType.INTEGER -> "Int" to init
            VariableType.BOOLEAN -> "Boolean" to init
            VariableType.STRING -> "String" to init
            VariableType.SET_OF_INT -> "MutableSet<Int>" to when {
                init == "{}" || init == "mutableSetOf()" || init.isEmpty() -> "mutableSetOf()"
                // If init is a constant name (like Actors), convert to mutable
                init.matches(Regex("""\w+""")) && init[0].isUpperCase() -> "$init.toMutableSet()"
                else -> init
            }
            VariableType.SEQUENCE -> "MutableList<Any>" to (
                if (init == "<<>>" || init == "mutableListOf()" || init.isEmpty()) "mutableListOf()" else init
            )
            VariableType.FUNCTION_INT_TO_INT -> "MutableMap<Int, Int>" to "mutableMapOf()"
            VariableType.FUNCTION_INT_TO_STRING -> "MutableMap<Int, String>" to "mutableMapOf()"
            VariableType.FUNCTION_INT_TO_SET -> "MutableMap<Int, MutableSet<Int>>" to "mutableMapOf()"
            VariableType.ENUM -> "String" to (if (init.startsWith("\"")) init else "\"${init.ifEmpty { "" }}\"")
            VariableType.CUSTOM -> "Any" to init.ifEmpty { "null" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  INVARIANT CHECKER
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Generates the `checkInvariants()` method that verifies all TLA+
     * safety invariants hold in the current state.
     *
     * Called at the end of every @Operation method.
     */
    private fun generateInvariantChecker(spec: ConcurrentSystemSpec): String = buildString {
        appendLine("    // ── Invariant verification (from TLA+ safety invariants) ───────")
        appendLine()
        appendLine("    /**")
        appendLine("     * Checks all TLA+ safety invariants after each operation.")
        appendLine("     *")
        appendLine("     * If any invariant is violated under ANY interleaving explored by")
        appendLine("     * Lincheck's model checker, the test fails with the exact trace.")
        appendLine("     */")
        appendLine("    private fun checkInvariants() {")

        for (inv in spec.invariants) {
            val kotlinCondition = predicateToKotlin(inv.predicate)
            if (kotlinCondition != null) {
                appendLine("        // TLA+ invariant: ${inv.name}")
                appendLine("        check($kotlinCondition) {")
                appendLine("            \"Invariant '${inv.name}' violated\"")
                appendLine("        }")
            } else {
                appendLine("        // TODO: TLA+ invariant '${inv.name}' — complex expression, needs manual implementation")
                appendLine("        // Raw TLA+: ${inv.rawTla.lines().first().trim()}")
            }
        }

        appendLine("    }")
        appendLine()
    }

    /**
     * Translates a [PredicateExpr] to a Kotlin boolean expression string.
     *
     * Returns null for predicates too complex to translate automatically.
     */
    internal fun predicateToKotlin(pred: PredicateExpr): String? = when (pred) {
        is PredicateExpr.True -> "true"
        is PredicateExpr.False -> "false"
        is PredicateExpr.Comparison -> {
            val left = translatePredicateOperand(pred.left)
            val right = translatePredicateOperand(pred.right)
            "$left ${pred.op.kotlin} $right"
        }
        is PredicateExpr.Membership -> {
            val elem = translatePredicateOperand(pred.element)
            val set = translatePredicateOperand(pred.set)
            if (pred.negated) "$elem !in $set" else "$elem in $set"
        }
        is PredicateExpr.And -> {
            val left = predicateToKotlin(pred.left) ?: return null
            val right = predicateToKotlin(pred.right) ?: return null
            "($left) && ($right)"
        }
        is PredicateExpr.Or -> {
            val left = predicateToKotlin(pred.left) ?: return null
            val right = predicateToKotlin(pred.right) ?: return null
            "($left) || ($right)"
        }
        is PredicateExpr.Not -> {
            val inner = predicateToKotlin(pred.inner) ?: return null
            "!($inner)"
        }
        is PredicateExpr.ForAll -> {
            val body = predicateToKotlin(pred.body) ?: return null
            val domain = translatePredicateOperand(pred.domain)
            "$domain.all { ${pred.variable} -> $body }"
        }
        is PredicateExpr.Exists -> {
            val body = predicateToKotlin(pred.body) ?: return null
            val domain = translatePredicateOperand(pred.domain)
            "$domain.any { ${pred.variable} -> $body }"
        }
        is PredicateExpr.Custom -> if (pred.kotlinExpr.isNotEmpty() && !containsTlaResidue(pred.kotlinExpr)) {
            addNullAssertions(pred.kotlinExpr)
        } else null
    }

    /**
     * Splits a compound precondition into parts that don't reference LET variables
     * (outer) and parts that do (inner).
     */
    private fun splitPrecondition(pred: PredicateExpr, letVarNames: Set<String>): Pair<PredicateExpr, PredicateExpr> {
        if (pred !is PredicateExpr.And) {
            // Single predicate — check if it references LET vars
            val kotlinExpr = predicateToKotlin(pred) ?: ""
            return if (letVarNames.any { kotlinExpr.contains(it) }) {
                PredicateExpr.True to pred
            } else {
                pred to PredicateExpr.True
            }
        }

        // Flatten nested And predicates
        val flattened = mutableListOf<PredicateExpr>()
        fun flatten(p: PredicateExpr) {
            if (p is PredicateExpr.And) { flatten(p.left); flatten(p.right) }
            else flattened.add(p)
        }
        flatten(pred)

        val outer = mutableListOf<PredicateExpr>()
        val inner = mutableListOf<PredicateExpr>()
        for (p in flattened) {
            val kotlinExpr = predicateToKotlin(p) ?: ""
            if (letVarNames.any { kotlinExpr.contains(it) }) {
                inner.add(p)
            } else {
                outer.add(p)
            }
        }

        fun combine(parts: List<PredicateExpr>): PredicateExpr = when {
            parts.isEmpty() -> PredicateExpr.True
            parts.size == 1 -> parts.first()
            else -> parts.reduce { a, b -> PredicateExpr.And(a, b) }
        }

        return combine(outer) to combine(inner)
    }

    /**
     * Translates a TLA+ operand (in a comparison/membership) to Kotlin.
     * Handles: Len(x) → x.size, Cardinality(x) → x.size,
     * function application f[k] → f[k]!! (nullable map access),
     * set operations, etc.
     */
    private fun translatePredicateOperand(operand: String): String {
        var result = operand
        // Len(x) → x.size
        result = result.replace(Regex("""Len\((\w+)\)""")) { "${it.groupValues[1]}.size" }
        // Cardinality(x) → x.size
        result = result.replace(Regex("""Cardinality\(([^)]+)\)""")) { "${it.groupValues[1]}.size" }
        // TLA+ set literal {"a", "b"} → setOf("a", "b")
        if (result.matches(Regex("""\{[^}]*}"""))) {
            result = result.replace("{", "setOf(").replace("}", ")")
        }
        // \cup → union, \cap → intersect
        result = result.replace(Regex("""\s*\\cup\s*"""), " union ")
        result = result.replace(Regex("""\s*\\cap\s*"""), " intersect ")
        // Map access: f[k] → f[k]!! (but not if already has !!)
        result = addNullAssertions(result)
        return result
    }

    /**
     * Adds `!!` to map access expressions like `map[key]` to handle Kotlin's nullable return.
     * Won't double-add if already present.
     */
    private fun addNullAssertions(expr: String): String {
        // Pattern: word[word_or_expr] not followed by !! or = (assignment)
        // Allow !! before . (for chained calls like map[key]!!.size)
        return expr.replace(Regex("""(\w+\[\w+])(?!\s*[!=]|!!)""")) { "${it.value}!!" }
    }

    /**
     * Detects if a "Kotlin" expression still contains TLA+ syntax residue,
     * meaning translation was incomplete and the expression shouldn't be emitted.
     */
    private fun containsTlaResidue(expr: String): Boolean {
        val tlaPatterns = listOf("\\in", "\\/", "/\\", "\\E ", "\\A ", "\\cup", "\\cap",
            "\\notin", "\\subseteq", "|->", "EXCEPT", "UNCHANGED")
        return tlaPatterns.any { expr.contains(it) }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  OPERATIONS (from actions)
    // ─────────────────────────────────────────────────────────────────────

    private fun generateOperations(spec: ConcurrentSystemSpec, withInvariantCheck: Boolean): String = buildString {
        appendLine("    // ── Operations (from TLA+ actions) ─────────────────────────────")
        appendLine()

        // Only user-facing actions (not system actions like WalCommit, Crash)
        val userActions = spec.actions.filter { !it.isSystemAction }

        // Group related actions by base name (e.g., StartReserve + ExecuteReserve → Reserve)
        val actionGroups = groupRelatedActions(userActions)

        for ((opName, actions) in actionGroups) {
            appendLine(generateOperation(opName, actions, spec, withInvariantCheck))
        }
    }

    /**
     * Groups related TLA+ actions into logical operations.
     *
     * In multi-step protocols, a single user operation may span multiple
     * TLA+ actions (e.g., StartReserve → AcquireLock → ExecuteReserve).
     * The Lincheck test needs ONE @Operation method that captures the
     * combined sequential semantics.
     */
    private fun groupRelatedActions(actions: List<ActionSpec>): Map<String, List<ActionSpec>> {
        val groups = mutableMapOf<String, MutableList<ActionSpec>>()

        for (action in actions) {
            val baseName = when {
                action.name.startsWith("Start") && action.name.length > 5 -> action.name.removePrefix("Start")
                action.name.startsWith("Execute") && action.name.length > 7 -> action.name.removePrefix("Execute")
                action.name.startsWith("CommitTimeout") -> "Timeout${action.name.removePrefix("CommitTimeout")}"
                action.name.startsWith("LockTimeout") -> "LockTimeout"
                action.name == "ThreadReset" -> continue  // lifecycle, skip
                else -> action.name
            }
            groups.getOrPut(baseName) { mutableListOf() }.add(action)
        }

        return groups
    }

    private fun generateOperation(
        opName: String,
        actions: List<ActionSpec>,
        spec: ConcurrentSystemSpec,
        withInvariantCheck: Boolean
    ): String = buildString {
        // Use the "Execute" action if available (has core logic), else first action
        val primaryAction = actions.find { it.name.startsWith("Execute") } ?: actions.first()

        val params = primaryAction.parameters.joinToString(", ") { param ->
            if (param.domain.isNotEmpty()) {
                "@Param(name = \"${param.name}\") ${param.name}: Int"
            } else {
                "${param.name}: Int"
            }
        }

        // KDoc
        appendLine("    /**")
        appendLine("     * TLA+ action${if (actions.size > 1) "s" else ""}: ${actions.joinToString(", ") { it.name }}")
        for (action in actions) {
            appendLine("     * - ${action.name}: ${action.description}")
        }
        appendLine("     */")

        // Method signature
        appendLine("    @Operation")
        appendLine("    fun ${camelCase(opName)}($params): String = synchronized(lockObj) {")

        // Generate branch logic
        if (primaryAction.branches.isNotEmpty()) {
            val branches = primaryAction.branches
            for ((idx, branch) in branches.withIndex()) {
                // Separate LET val declarations from regular effects
                val allEffects = branch.effects.filter { it.effect !is EffectExpr.Unchanged }
                val letEffects = allEffects.filter { e ->
                    e.effect is EffectExpr.Custom && (e.effect as EffectExpr.Custom).kotlinExpr.startsWith("val ")
                }
                val regularEffects = allEffects.filter { e ->
                    !(e.effect is EffectExpr.Custom && (e.effect as EffectExpr.Custom).kotlinExpr.startsWith("val "))
                }

                // If LET vars exist, check if precondition references them
                val letVarNames = letEffects.map { it.variable }.toSet()
                val condition = predicateToKotlin(branch.precondition)

                // Split precondition: outer conditions (no LET deps) vs inner (LET deps)
                val hasLetDependentPrecondition = letVarNames.isNotEmpty() && condition != null &&
                        letVarNames.any { condition.contains(it) }

                if (hasLetDependentPrecondition) {
                    // Split the And precondition into outer and inner parts
                    val (outerPred, innerPred) = splitPrecondition(branch.precondition, letVarNames)
                    val outerCond = predicateToKotlin(outerPred)
                    val innerCond = predicateToKotlin(innerPred)

                    when {
                        idx == 0 && outerCond != null && outerCond != "true" -> {
                            appendLine("        if ($outerCond) {")
                        }
                        else -> {}
                    }
                    // Emit LET val declarations
                    for (le in letEffects) {
                        appendLine("            ${effectToKotlin(le)}")
                    }
                    // Inner condition check
                    if (innerCond != null && innerCond != "true") {
                        appendLine("            if ($innerCond) {")
                        val effects = regularEffects.joinToString("\n") { "                ${effectToKotlin(it)}" }
                        if (effects.isNotEmpty()) appendLine(effects)
                        if (withInvariantCheck) appendLine("                checkInvariants()")
                        appendLine("                return \"${branch.returnValue}\"")
                        appendLine("            }")
                    } else {
                        val effects = regularEffects.joinToString("\n") { "            ${effectToKotlin(it)}" }
                        if (effects.isNotEmpty()) appendLine(effects)
                        if (withInvariantCheck) appendLine("            checkInvariants()")
                        appendLine("            return \"${branch.returnValue}\"")
                    }
                } else {
                    // Standard generation: no LET dependency in precondition
                    val effects = allEffects.joinToString("\n") { "            ${effectToKotlin(it)}" }
                    val returnVal = "\"${branch.returnValue}\""

                    when {
                        idx == 0 && condition != null && condition != "true" -> {
                            appendLine("        if ($condition) {")
                        }
                        condition != null && condition != "true" -> {
                            appendLine("        } else if ($condition) {")
                        }
                        branches.size > 1 && idx > 0 -> {
                            appendLine("        } else {")
                        }
                        else -> {} // single branch with true condition
                    }

                    if (effects.isNotEmpty()) {
                        appendLine(effects)
                    }
                    if (withInvariantCheck) {
                        appendLine("            checkInvariants()")
                    }
                    appendLine("            return $returnVal")
                }
            }
            if (branches.size > 1) {
                appendLine("        }")
            } else if (branches.size == 1) {
                val cond = predicateToKotlin(branches[0].precondition)
                if (cond != null && cond != "true") {
                    // Close the single if-block and add default return for disabled action
                    appendLine("        }")
                    appendLine("        return \"noop\"")
                }
            }
        } else {
            // No branches — simple operation
            if (withInvariantCheck) {
                appendLine("        checkInvariants()")
            }
            appendLine("        return \"ok\"")
        }

        appendLine("    }")
        appendLine()
    }

    /**
     * Translates a [StateEffect] into a Kotlin statement.
     *
     * Handles:
     *   - Simple mutations: increment, decrement, assign
     *   - Map updates: `map[key] = value` (from EXCEPT)
     *   - Set operations: add, remove
     *   - Sequence operations: add (append), removeFirst (tail)
     *   - Custom with kotlinExpr: direct passthrough
     *   - Custom without kotlinExpr: TODO comment with raw TLA+
     */
    private fun effectToKotlin(effect: StateEffect): String = when (val e = effect.effect) {
        is EffectExpr.Increment -> "${effect.variable} += ${e.amount}"
        is EffectExpr.Decrement -> "${effect.variable} -= ${e.amount}"
        is EffectExpr.Assign -> {
            val kotlinExpr = translateAssignExpr(effect.variable, e.expression)
            "${effect.variable} = $kotlinExpr"
        }
        is EffectExpr.SetAdd -> "${effect.variable}.add(${e.element})"
        is EffectExpr.SetRemove -> "${effect.variable}.remove(${e.element})"
        is EffectExpr.SeqAppend -> "${effect.variable}.add(${e.element})"
        is EffectExpr.FunctionUpdate -> {
            val safeValue = addNullAssertions(e.value)
            "${effect.variable}[${e.key}] = $safeValue"
        }
        is EffectExpr.Unchanged -> "// UNCHANGED ${effect.variable}"
        is EffectExpr.Custom -> if (e.kotlinExpr.isNotEmpty()) {
            addNullAssertions(e.kotlinExpr)
        } else {
            "// TODO: ${effect.variable} — ${e.tlaExpr}"
        }
    }

    /**
     * Translates a TLA+ assignment expression to Kotlin.
     * Handles Len, Head, Tail, Cardinality in expressions.
     */
    private fun translateAssignExpr(varName: String, expr: String): String {
        var result = expr
        // Len(x) → x.size
        result = result.replace(Regex("""Len\((\w+)\)""")) { "${it.groupValues[1]}.size" }
        // Head(x) → x.first()
        result = result.replace(Regex("""Head\((\w+)\)""")) { "${it.groupValues[1]}.first()" }
        // Tail(x) → x.drop(1).toMutableList()
        result = result.replace(Regex("""Tail\((\w+)\)""")) { "${it.groupValues[1]}.drop(1).toMutableList()" }
        // Cardinality(x) → x.size
        result = result.replace(Regex("""Cardinality\((\w+)\)""")) { "${it.groupValues[1]}.size" }
        // Add null assertions for map accesses
        result = addNullAssertions(result)
        return result
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TEST METHODS
    // ─────────────────────────────────────────────────────────────────────

    private fun generateTestMethods(className: String, config: Config): String = buildString {
        appendLine("    // ── Lincheck test methods ──────────────────────────────────────")
        appendLine()

        if (config.generateModelCheckingTest) {
            appendLine("    /**")
            appendLine("     * MODEL CHECKING MODE: Exhaustively explores ALL possible thread")
            appendLine("     * interleavings.  If any interleaving produces a non-linearizable")
            appendLine("     * result or violates an invariant, Lincheck reports the exact trace.")
            appendLine("     */")
            appendLine("    @Test")
            appendLine("    fun modelCheckingTest() = ModelCheckingOptions()")
            appendLine("        .threads(${config.threads})")
            appendLine("        .actorsPerThread(${config.actorsPerThread})")
            appendLine("        .invocationsPerIteration(${config.invocationsPerIteration})")
            appendLine("        .iterations(${config.iterations})")
            appendLine("        .checkObstructionFreedom(false)")
            appendLine("        .actorsBefore(0)")
            appendLine("        .actorsAfter(0)")
            appendLine("        .check(this::class)")
        }

        if (config.generateStressTest) {
            appendLine()
            appendLine("    /**")
            appendLine("     * STRESS TEST MODE: Runs under real concurrent load.")
            appendLine("     * Catches JVM-specific issues that model checking may miss.")
            appendLine("     */")
            appendLine("    @Test")
            appendLine("    fun stressTest() = StressOptions()")
            appendLine("        .threads(${config.threads})")
            appendLine("        .actorsPerThread(${config.actorsPerThread})")
            appendLine("        .invocationsPerIteration(${config.invocationsPerIteration / 10})")
            appendLine("        .iterations(${config.iterations / 2})")
            appendLine("        .actorsBefore(0)")
            appendLine("        .actorsAfter(0)")
            appendLine("        .check(this::class)")
        }

        appendLine()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  UTILITIES
    // ─────────────────────────────────────────────────────────────────────

    private fun camelCase(name: String): String {
        if (name.isEmpty()) return "op"
        return name.first().lowercaseChar() + name.drop(1)
    }

    companion object {
        const val VERSION = "0.1.0"
    }
}
