package io.github.tla2lincheck.generator

import io.github.tla2lincheck.ir.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

/**
 * Tests for [LincheckGenerator].
 *
 * Verifies that generated Lincheck test code is syntactically correct
 * and contains all expected structural elements.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LincheckGeneratorTest {

    private val generator = LincheckGenerator()

    // ═══════════════════════════════════════════════════════════════════
    //  TEST FIXTURE
    // ═══════════════════════════════════════════════════════════════════

    private fun buildCounterSpec(): ConcurrentSystemSpec = ConcurrentSystemSpec(
        name = "Counter",
        variables = listOf(
            StateVariable("count", VariableType.INTEGER, "current count"),
            StateVariable("history", VariableType.SEQUENCE, "operation log")
        ),
        constants = listOf(
            SystemConstant("MaxValue", VariableType.INTEGER, defaultValue = "10")
        ),
        init = InitPredicate(
            assignments = listOf(
                InitAssignment("count", "0"),
                InitAssignment("history", "mutableListOf()")
            )
        ),
        actions = listOf(
            ActionSpec(
                name = "Increment",
                branches = listOf(
                    ActionBranch(
                        name = "success",
                        precondition = PredicateExpr.Comparison("count", CompOp.LT, "MaxValue"),
                        effects = listOf(StateEffect("count", EffectExpr.Increment())),
                        returnValue = "ok"
                    ),
                    ActionBranch(
                        name = "at_max",
                        precondition = PredicateExpr.Comparison("count", CompOp.GE, "MaxValue"),
                        effects = listOf(StateEffect("count", EffectExpr.Unchanged)),
                        returnValue = "at_max"
                    )
                )
            ),
            ActionSpec(
                name = "Decrement",
                branches = listOf(
                    ActionBranch(
                        name = "success",
                        precondition = PredicateExpr.Comparison("count", CompOp.GT, "0"),
                        effects = listOf(StateEffect("count", EffectExpr.Decrement())),
                        returnValue = "ok"
                    ),
                    ActionBranch(
                        name = "at_zero",
                        precondition = PredicateExpr.True,
                        effects = listOf(StateEffect("count", EffectExpr.Unchanged)),
                        returnValue = "at_zero"
                    )
                )
            ),
            ActionSpec(
                name = "Read",
                branches = listOf(
                    ActionBranch(
                        precondition = PredicateExpr.True,
                        effects = listOf(StateEffect("count", EffectExpr.Unchanged)),
                        returnValue = "ok"
                    )
                )
            )
        ),
        invariants = listOf(
            InvariantSpec("NonNegative", PredicateExpr.Comparison("count", CompOp.GE, "0"),
                description = "Count must never be negative", rawTla = "count >= 0"),
            InvariantSpec("BoundedAbove", PredicateExpr.Comparison("count", CompOp.LE, "MaxValue"),
                description = "Count must not exceed MaxValue", rawTla = "count <= MaxValue")
        ),
        source = SpecSource.Manual
    )

    // ═══════════════════════════════════════════════════════════════════
    //  1. Basic code structure
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("Generated code contains package declaration")
    fun containsPackage() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("package io.github.tla2lincheck.generated")
    }

    @Test
    @Order(2)
    @DisplayName("Generated code contains Lincheck imports")
    fun containsImports() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("import org.jetbrains.kotlinx.lincheck.annotations.*")
        assertThat(result.code).contains("import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*")
    }

    @Test
    @Order(3)
    @DisplayName("Class name follows convention: {SpecName}LincheckTest")
    fun classNameConvention() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.className).isEqualTo("CounterLincheckTest")
        assertThat(result.code).contains("class CounterLincheckTest")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2. Field generation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("Generates fields for state variables")
    fun generatesFields() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("private var count: Int = 0")
    }

    @Test
    @Order(5)
    @DisplayName("Generates constants as vals")
    fun generatesConstants() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("private val MaxValue = 10")
    }

    @Test
    @Order(6)
    @DisplayName("Generates lockObj for synchronization")
    fun generatesLockObj() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("private val lockObj = Any()")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  3. @Operation method generation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("Generates @Operation methods for each action")
    fun generatesOperations() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("@Operation")
        assertThat(result.code).contains("fun increment(")
        assertThat(result.code).contains("fun decrement(")
        assertThat(result.code).contains("fun read(")
    }

    @Test
    @Order(8)
    @DisplayName("Operations use synchronized(lockObj)")
    fun operationsSynchronized() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("synchronized(lockObj)")
    }

    @Test
    @Order(9)
    @DisplayName("Operations contain branch conditions")
    fun operationsContainConditions() {
        val result = generator.generate(buildCounterSpec())
        // Increment has a condition: count < MaxValue
        assertThat(result.code).contains("count < MaxValue")
    }

    @Test
    @Order(10)
    @DisplayName("Operations contain state mutations")
    fun operationsContainMutations() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("count += 1")
        assertThat(result.code).contains("count -= 1")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4. Invariant checker generation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(11)
    @DisplayName("Generates checkInvariants() method")
    fun generatesInvariantChecker() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("private fun checkInvariants()")
    }

    @Test
    @Order(12)
    @DisplayName("Invariant checker contains all invariant names")
    fun invariantCheckerContainsNames() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("NonNegative")
        assertThat(result.code).contains("BoundedAbove")
    }

    @Test
    @Order(13)
    @DisplayName("Invariant checker uses Kotlin check() calls")
    fun invariantCheckerUsesCheck() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("check(count >= 0)")
        assertThat(result.code).contains("check(count <= MaxValue)")
    }

    @Test
    @Order(14)
    @DisplayName("checkInvariants() is called inside @Operation methods")
    fun invariantCheckCalledInOperations() {
        val result = generator.generate(buildCounterSpec())
        // Count occurrences of checkInvariants() — should be called in each branch
        val callCount = "checkInvariants()".toRegex().findAll(result.code).count()
        // At least once (definition) + operations
        assertThat(callCount).isGreaterThan(1)
    }

    @Test
    @Order(15)
    @DisplayName("Invariant checking can be disabled via config")
    fun invariantCheckDisabled() {
        val config = LincheckGenerator.Config(embedInvariants = false)
        val result = generator.generate(buildCounterSpec(), config)
        assertThat(result.code).doesNotContain("checkInvariants()")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5. Test method generation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(16)
    @DisplayName("Generates ModelCheckingOptions test")
    fun generatesModelChecking() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("ModelCheckingOptions()")
        assertThat(result.code).contains("fun modelCheckingTest()")
    }

    @Test
    @Order(17)
    @DisplayName("Generates StressOptions test")
    fun generatesStress() {
        val result = generator.generate(buildCounterSpec())
        assertThat(result.code).contains("StressOptions()")
        assertThat(result.code).contains("fun stressTest()")
    }

    @Test
    @Order(18)
    @DisplayName("Config controls thread count and iterations")
    fun configControlsParams() {
        val config = LincheckGenerator.Config(threads = 5, actorsPerThread = 4, iterations = 100)
        val result = generator.generate(buildCounterSpec(), config)
        assertThat(result.code).contains(".threads(5)")
        assertThat(result.code).contains(".actorsPerThread(4)")
        assertThat(result.code).contains(".iterations(100)")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  6. @Param annotation generation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(19)
    @DisplayName("Generates @Param annotations for parameterized actions")
    fun generatesParamAnnotations() {
        val spec = ConcurrentSystemSpec(
            name = "ParamTest",
            variables = listOf(StateVariable("x", VariableType.INTEGER)),
            constants = listOf(SystemConstant("Items", defaultValue = "3")),
            init = InitPredicate(listOf(InitAssignment("x", "0"))),
            actions = listOf(
                ActionSpec(
                    name = "DoSomething",
                    parameters = listOf(ActionParameter("item", VariableType.INTEGER, domain = "Items")),
                    branches = listOf(ActionBranch(returnValue = "ok"))
                )
            ),
            invariants = emptyList()
        )

        val result = generator.generate(spec)
        assertThat(result.code).contains("@Param(name = \"item\"")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  7. Predicate → Kotlin translation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("predicateToKotlin translates comparisons correctly")
    fun predicateToKotlinComparison() {
        assertThat(generator.predicateToKotlin(PredicateExpr.Comparison("x", CompOp.GE, "0")))
            .isEqualTo("x >= 0")
        assertThat(generator.predicateToKotlin(PredicateExpr.Comparison("a", CompOp.NEQ, "b")))
            .isEqualTo("a != b")
    }

    @Test
    @Order(21)
    @DisplayName("predicateToKotlin translates logical connectives")
    fun predicateToKotlinLogical() {
        val and = PredicateExpr.And(
            PredicateExpr.Comparison("x", CompOp.GT, "0"),
            PredicateExpr.Comparison("x", CompOp.LT, "10")
        )
        assertThat(generator.predicateToKotlin(and)).isEqualTo("(x > 0) && (x < 10)")
    }

    @Test
    @Order(22)
    @DisplayName("predicateToKotlin translates membership")
    fun predicateToKotlinMembership() {
        assertThat(generator.predicateToKotlin(PredicateExpr.Membership("x", "items")))
            .isEqualTo("x in items")
        assertThat(generator.predicateToKotlin(PredicateExpr.Membership("x", "items", negated = true)))
            .isEqualTo("x !in items")
    }

    @Test
    @Order(23)
    @DisplayName("predicateToKotlin returns null for Custom without kotlinExpr")
    fun predicateToKotlinCustom() {
        assertThat(generator.predicateToKotlin(PredicateExpr.Custom("DOMAIN f"))).isNull()
        assertThat(generator.predicateToKotlin(PredicateExpr.Custom("", "f.keys"))).isEqualTo("f.keys")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  8. End-to-end with parser
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(24)
    @DisplayName("End-to-end: parse Counter spec and generate valid test")
    fun endToEnd() {
        val parser = io.github.tla2lincheck.parser.TlaParser()
        val parseResult = parser.parse("""
            ---- MODULE E2ECounter ----
            EXTENDS Naturals

            CONSTANTS MaxValue

            VARIABLES count

            TypeOK == count \in 0..MaxValue

            Init == count = 0

            Increment ==
                /\ count < MaxValue
                /\ count' = count + 1

            Decrement ==
                /\ count > 0
                /\ count' = count - 1

            Next ==
                \/ Increment
                \/ Decrement

            NonNegative == count >= 0

            Spec == Init /\ [][Next]_count
            ====
        """.trimIndent())

        assertThat(parseResult.isSuccessful).isTrue()

        val generated = generator.generate(parseResult.spec)
        println("Generated class: ${generated.className}")
        println("Generated code:\n${generated.code}")

        assertThat(generated.code).contains("@Operation")
        assertThat(generated.code).contains("fun increment(")
        assertThat(generated.code).contains("fun decrement(")
        assertThat(generated.code).contains("checkInvariants()")
        assertThat(generated.code).contains("count >= 0")
        assertThat(generated.code).contains("ModelCheckingOptions()")
    }
}
