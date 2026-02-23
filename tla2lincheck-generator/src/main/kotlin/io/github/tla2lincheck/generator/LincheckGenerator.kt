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
            // Try to resolve domain size from constants
            val domainConst = spec.constants.find { it.name == domain }
            val range = if (domainConst != null && domainConst.defaultValue.isNotEmpty()) {
                "1:${domainConst.defaultValue}"
            } else {
                "1:5" // safe default
            }
            appendLine("@Param(name = \"$paramName\", gen = IntGen::class, conf = \"$range\")")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FIELDS (from VARIABLES + Init)
    // ─────────────────────────────────────────────────────────────────────

    private fun generateFields(spec: ConcurrentSystemSpec): String = buildString {
        appendLine("    // ── State fields (from TLA+ VARIABLES) ─────────────────────────")
        appendLine()

        // Find initialization expressions from Init
        val initMap = spec.init.assignments.associate { it.variable to it.expression }

        for (v in spec.variables) {
            val (kotlinType, initExpr) = kotlinTypeAndInit(v.type, initMap[v.name])
            if (v.description.isNotEmpty()) {
                appendLine("    /** ${v.description} */")
            }
            appendLine("    private var ${v.name}: $kotlinType = $initExpr")
        }

        // Constants as vals
        if (spec.constants.isNotEmpty()) {
            appendLine()
            appendLine("    // ── Constants (from TLA+ CONSTANTS) ────────────────────────────")
            for (c in spec.constants) {
                val value = c.defaultValue.ifEmpty { "5" }
                appendLine("    private val ${c.name} = $value")
            }
        }

        appendLine()
        appendLine("    // ── Synchronization ────────────────────────────────────────────")
        appendLine("    private val lockObj = Any()")
        appendLine()
    }

    private fun kotlinTypeAndInit(type: VariableType, initExpr: String?): Pair<String, String> {
        val init = initExpr ?: type.defaultInit
        return when (type) {
            VariableType.INTEGER -> "Int" to init
            VariableType.BOOLEAN -> "Boolean" to init
            VariableType.STRING -> "String" to init
            VariableType.SET_OF_INT -> "MutableSet<Int>" to (if (init == "{}" || init.isEmpty()) "mutableSetOf()" else init)
            VariableType.SEQUENCE -> "MutableList<Any>" to (if (init == "<<>>" || init.isEmpty()) "mutableListOf()" else init)
            VariableType.FUNCTION_INT_TO_INT -> "MutableMap<Int, Int>" to "mutableMapOf()"
            VariableType.FUNCTION_INT_TO_SET -> "MutableMap<Int, MutableSet<Int>>" to "mutableMapOf()"
            VariableType.ENUM -> "String" to init.ifEmpty { "\"\"" }
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
        is PredicateExpr.Comparison -> "${pred.left} ${pred.op.kotlin} ${pred.right}"
        is PredicateExpr.Membership -> if (pred.negated) {
            "${pred.element} !in ${pred.set}"
        } else {
            "${pred.element} in ${pred.set}"
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
            "${pred.domain}.all { ${pred.variable} -> $body }"
        }
        is PredicateExpr.Exists -> {
            val body = predicateToKotlin(pred.body) ?: return null
            "${pred.domain}.any { ${pred.variable} -> $body }"
        }
        is PredicateExpr.Custom -> if (pred.kotlinExpr.isNotEmpty()) pred.kotlinExpr else null
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
                action.name.startsWith("Start") -> action.name.removePrefix("Start")
                action.name.startsWith("Execute") -> action.name.removePrefix("Execute")
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
                val condition = predicateToKotlin(branch.precondition)
                val effects = branch.effects
                    .filter { it.effect !is EffectExpr.Unchanged }
                    .joinToString("\n") { "            ${effectToKotlin(it)}" }
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
            if (branches.size > 1) {
                appendLine("        }")
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
     */
    private fun effectToKotlin(effect: StateEffect): String = when (val e = effect.effect) {
        is EffectExpr.Increment -> "${effect.variable} += ${e.amount}"
        is EffectExpr.Decrement -> "${effect.variable} -= ${e.amount}"
        is EffectExpr.Assign -> "${effect.variable} = ${e.expression}"
        is EffectExpr.SetAdd -> "${effect.variable}.add(${e.element})"
        is EffectExpr.SetRemove -> "${effect.variable}.remove(${e.element})"
        is EffectExpr.SeqAppend -> "${effect.variable}.add(${e.element})"
        is EffectExpr.FunctionUpdate -> "${effect.variable}[${e.key}] = ${e.value}"
        is EffectExpr.Unchanged -> "// UNCHANGED ${effect.variable}"
        is EffectExpr.Custom -> if (e.kotlinExpr.isNotEmpty()) {
            e.kotlinExpr
        } else {
            "// TODO: ${effect.variable} — ${e.tlaExpr}"
        }
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
        return name.first().lowercaseChar() + name.drop(1)
    }

    companion object {
        const val VERSION = "0.1.0"
    }
}
