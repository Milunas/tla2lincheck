package io.github.tla2lincheck.parser

import io.github.tla2lincheck.ir.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

/**
 * Tests for [TlaParser].
 *
 * Uses a self-contained Counter TLA+ spec as the primary fixture.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TlaParserTest {

    private val parser = TlaParser()

    // ═══════════════════════════════════════════════════════════════════
    //  FIXTURE: A simple Counter specification
    // ═══════════════════════════════════════════════════════════════════

    private val counterSpec = """
        ---- MODULE Counter ----
        EXTENDS Naturals, Sequences

        CONSTANTS MaxValue

        VARIABLES count, history

        TypeOK ==
            /\ count \in 0..MaxValue
            /\ history \in Seq({"inc", "dec"})

        Init ==
            /\ count = 0
            /\ history = <<>>

        Increment ==
            /\ count < MaxValue
            /\ count' = count + 1
            /\ history' = Append(history, "inc")

        Decrement ==
            /\ count > 0
            /\ count' = count - 1
            /\ history' = Append(history, "dec")

        Read ==
            /\ UNCHANGED <<count, history>>

        Next ==
            \/ Increment
            \/ Decrement
            \/ Read

        NonNegative == count >= 0

        BoundedAbove == count <= MaxValue

        Spec == Init /\ [][Next]_<<count, history>>
        ====
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════════
    //  1. Module name extraction
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("Extracts module name from ---- MODULE Name ----")
    fun extractsModuleName() {
        val result = parser.parse(counterSpec)
        assertThat(result.spec.name).isEqualTo("Counter")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2. Constants extraction
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("Extracts CONSTANTS section")
    fun extractsConstants() {
        val result = parser.parse(counterSpec)
        assertThat(result.spec.constants).hasSize(1)
        assertThat(result.spec.constants.first().name).isEqualTo("MaxValue")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  3. Variables extraction
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("Extracts VARIABLES section")
    fun extractsVariables() {
        val result = parser.parse(counterSpec)
        val varNames = result.spec.variables.map { it.name }
        assertThat(varNames).containsExactlyInAnyOrder("count", "history")
    }

    @Test
    @Order(4)
    @DisplayName("Infers variable types from TypeOK")
    fun infersTypes() {
        val result = parser.parse(counterSpec)
        val countVar = result.spec.variables.find { it.name == "count" }
        val histVar = result.spec.variables.find { it.name == "history" }

        assertThat(countVar).isNotNull
        assertThat(countVar!!.type).isEqualTo(VariableType.INTEGER)
        assertThat(histVar).isNotNull
        assertThat(histVar!!.type).isEqualTo(VariableType.SEQUENCE)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4. Init predicate
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("Extracts Init assignments")
    fun extractsInit() {
        val result = parser.parse(counterSpec)
        val initVars = result.spec.init.assignments.map { it.variable }
        assertThat(initVars).contains("count")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5. Action extraction
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("Extracts actions from Next relation")
    fun extractsActions() {
        val result = parser.parse(counterSpec)
        val actionNames = result.spec.actions.map { it.name }

        println("  Actions: $actionNames")
        assertThat(actionNames).containsExactlyInAnyOrder("Increment", "Decrement", "Read")
    }

    @Test
    @Order(7)
    @DisplayName("Parses Increment effects correctly")
    fun parsesIncrementEffects() {
        val result = parser.parse(counterSpec)
        val increment = result.spec.actions.find { it.name == "Increment" }

        assertThat(increment).isNotNull
        val effects = increment!!.branches.flatMap { it.effects }
        val countEffect = effects.find { it.variable == "count" }

        println("  Increment effects: ${effects.map { "${it.variable}: ${it.effect}" }}")
        assertThat(countEffect).isNotNull
        assertThat(countEffect!!.effect).isInstanceOf(EffectExpr.Increment::class.java)
    }

    @Test
    @Order(8)
    @DisplayName("Parses Decrement effects correctly")
    fun parsesDecrementEffects() {
        val result = parser.parse(counterSpec)
        val decrement = result.spec.actions.find { it.name == "Decrement" }

        assertThat(decrement).isNotNull
        val effects = decrement!!.branches.flatMap { it.effects }
        val countEffect = effects.find { it.variable == "count" }

        assertThat(countEffect).isNotNull
        assertThat(countEffect!!.effect).isInstanceOf(EffectExpr.Decrement::class.java)
    }

    @Test
    @Order(9)
    @DisplayName("Parses UNCHANGED in Read action")
    fun parsesUnchanged() {
        val result = parser.parse(counterSpec)
        val read = result.spec.actions.find { it.name == "Read" }

        assertThat(read).isNotNull
        val effects = read!!.branches.flatMap { it.effects }
        val unchangedEffects = effects.filter { it.effect is EffectExpr.Unchanged }

        assertThat(unchangedEffects).isNotEmpty
    }

    // ═══════════════════════════════════════════════════════════════════
    //  6. Invariants
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("Extracts named invariants")
    fun extractsInvariants() {
        val result = parser.parse(counterSpec)
        val invNames = result.spec.invariants.map { it.name }

        println("  Invariants: $invNames")
        assertThat(invNames).contains("NonNegative")
        assertThat(invNames).contains("BoundedAbove")
    }

    @Test
    @Order(11)
    @DisplayName("Invariant predicates are parsed as Comparison expressions")
    fun invariantPredicatesParsed() {
        val result = parser.parse(counterSpec)
        val nonNeg = result.spec.invariants.find { it.name == "NonNegative" }

        assertThat(nonNeg).isNotNull
        // count >= 0 should be a Comparison
        assertThat(nonNeg!!.predicate).isInstanceOf(PredicateExpr.Comparison::class.java)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  7. Parameterized actions (with \E quantifiers)
    // ═══════════════════════════════════════════════════════════════════

    private val parameterizedSpec = """
        ---- MODULE Reservation ----
        EXTENDS Naturals

        CONSTANTS Users, Books

        VARIABLES reserved, available

        Init ==
            /\ reserved = {}
            /\ available = 5

        Reserve(u, b) ==
            /\ available > 0
            /\ available' = available - 1
            /\ reserved' = reserved \cup {u}

        Return(u, b) ==
            /\ u \in reserved
            /\ available' = available + 1
            /\ reserved' = reserved \ {u}

        Next ==
            \/ \E u \in Users, b \in Books : Reserve(u, b)
            \/ \E u \in Users, b \in Books : Return(u, b)

        NoOverReservation == available >= 0

        Spec == Init /\ [][Next]_<<reserved, available>>
        ====
    """.trimIndent()

    @Test
    @Order(12)
    @DisplayName("Extracts parameter domains from \\E quantifiers in Next")
    fun extractsParameterDomains() {
        val result = parser.parse(parameterizedSpec)
        val reserve = result.spec.actions.find { it.name == "Reserve" }

        assertThat(reserve).isNotNull
        assertThat(reserve!!.parameters).hasSize(2)

        val userParam = reserve.parameters.find { it.name == "u" }
        assertThat(userParam).isNotNull
        assertThat(userParam!!.domain).isEqualTo("Users")

        val bookParam = reserve.parameters.find { it.name == "b" }
        assertThat(bookParam).isNotNull
        assertThat(bookParam!!.domain).isEqualTo("Books")
    }

    @Test
    @Order(13)
    @DisplayName("Parameterized actions are not marked as system actions")
    fun parameterizedNotSystem() {
        val result = parser.parse(parameterizedSpec)
        val reserve = result.spec.actions.find { it.name == "Reserve" }
        assertThat(reserve!!.isSystemAction).isFalse()
    }

    @Test
    @Order(14)
    @DisplayName("Set effects are classified correctly")
    fun setEffectsClassified() {
        val result = parser.parse(parameterizedSpec)
        val reserve = result.spec.actions.find { it.name == "Reserve" }

        val effects = reserve!!.branches.flatMap { it.effects }
        val reservedEffect = effects.find { it.variable == "reserved" }

        assertThat(reservedEffect).isNotNull
        assertThat(reservedEffect!!.effect).isInstanceOf(EffectExpr.SetAdd::class.java)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  8. Parse result metadata
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(15)
    @DisplayName("ParseResult reports success for valid spec")
    fun parseResultSuccess() {
        val result = parser.parse(counterSpec)
        assertThat(result.isSuccessful).isTrue()
        assertThat(result.errors).isEmpty()
    }

    @Test
    @Order(16)
    @DisplayName("ParseResult reports error for missing module name")
    fun parseResultMissingModule() {
        val result = parser.parse("VARIABLES x\nInit == x = 0\nNext == x' = x + 1")
        assertThat(result.errors).isNotEmpty
        assertThat(result.errors.first().message).contains("module name")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  9. Type inference
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(17)
    @DisplayName("inferType handles standard TLA+ type expressions")
    fun inferTypeExpressions() {
        assertThat(parser.inferType("BOOLEAN")).isEqualTo(VariableType.BOOLEAN)
        assertThat(parser.inferType("Seq(Records)")).isEqualTo(VariableType.SEQUENCE)
        assertThat(parser.inferType("SUBSET Users")).isEqualTo(VariableType.SET_OF_INT)
        assertThat(parser.inferType("[Books -> 0..N]")).isEqualTo(VariableType.FUNCTION_INT_TO_INT)
        assertThat(parser.inferType("[Books -> SUBSET Users]")).isEqualTo(VariableType.FUNCTION_INT_TO_SET)
        assertThat(parser.inferType("{\"idle\", \"active\"}")).isEqualTo(VariableType.ENUM)
        assertThat(parser.inferType("0..MaxValue")).isEqualTo(VariableType.INTEGER)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  10. Effect classification
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(18)
    @DisplayName("classifyEffect handles common TLA+ patterns")
    fun classifyEffects() {
        assertThat(parser.classifyEffect("count", "count + 1")).isInstanceOf(EffectExpr.Increment::class.java)
        assertThat(parser.classifyEffect("count", "count - 1")).isInstanceOf(EffectExpr.Decrement::class.java)
        assertThat(parser.classifyEffect("x", "0")).isInstanceOf(EffectExpr.Assign::class.java)
        assertThat(parser.classifyEffect("log", "Append(log, entry)")).isInstanceOf(EffectExpr.SeqAppend::class.java)
    }

    @Test
    @Order(19)
    @DisplayName("classifyEffect handles EXCEPT expressions")
    fun classifyExcept() {
        val effect = parser.classifyEffect("f", "[f EXCEPT ![k] = v]")
        assertThat(effect).isInstanceOf(EffectExpr.FunctionUpdate::class.java)
        val funcUpdate = effect as EffectExpr.FunctionUpdate
        assertThat(funcUpdate.key).isEqualTo("k")
        assertThat(funcUpdate.value).isEqualTo("v")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  11. FUNCTION_EXCEPT in actions
    // ═══════════════════════════════════════════════════════════════════

    private val functionSpec = """
        ---- MODULE FuncSpec ----
        EXTENDS Naturals

        CONSTANTS Keys

        VARIABLES store

        TypeOK == store \in [Keys -> 0..10]

        Init == store = [k \in Keys |-> 0]

        Update(k) ==
            /\ store[k] < 10
            /\ store' = [store EXCEPT ![k] = @ + 1]

        Next == \E k \in Keys : Update(k)

        AllBounded == \A k \in Keys : store[k] <= 10

        Spec == Init /\ [][Next]_store
        ====
    """.trimIndent()

    @Test
    @Order(20)
    @DisplayName("Parses function-typed variables and EXCEPT effects")
    fun parsesFunctionSpec() {
        val result = parser.parse(functionSpec)

        // store should be FUNCTION_INT_TO_INT
        val store = result.spec.variables.find { it.name == "store" }
        assertThat(store).isNotNull
        assertThat(store!!.type).isEqualTo(VariableType.FUNCTION_INT_TO_INT)

        // Update action should exist with parameter domain "Keys"
        val update = result.spec.actions.find { it.name == "Update" }
        assertThat(update).isNotNull
        assertThat(update!!.parameters).hasSize(1)
        assertThat(update.parameters.first().domain).isEqualTo("Keys")

        // AllBounded invariant should be found
        val inv = result.spec.invariants.find { it.name == "AllBounded" }
        assertThat(inv).isNotNull
    }
}
