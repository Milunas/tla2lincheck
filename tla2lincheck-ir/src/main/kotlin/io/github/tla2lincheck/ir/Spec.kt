package io.github.tla2lincheck.ir

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * tla2lincheck — Core Intermediate Representation
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This file defines the typed intermediate representation (IR) that serves as
 * the semantic bridge between TLA+ specifications and generated Lincheck test
 * code.  Every translation in the library passes through this IR:
 *
 *     TLA+ source  ──P──▶  ConcurrentSystemSpec  ──G──▶  Lincheck source
 *          (parser)              (this IR)            (generator)
 *
 * ─── FORMAL FOUNDATION ────────────────────────────────────────────────────────
 *
 *   A TLA+ specification defines a Labeled Transition System (LTS):
 *
 *       LTS = (S, S₀, Σ, →, Φ)
 *
 *   where:
 *     S   = Set of states (Cartesian product of variable domains)
 *     S₀  = Initial state (defined by the Init predicate)
 *     Σ   = Action alphabet (set of named actions)
 *     →   = Transition relation (action guard ∧ effect ⇒ successor state)
 *     Φ   = Safety invariants (predicates that hold in every reachable state)
 *
 *   A Lincheck test class encodes the SAME LTS:
 *
 *     S   = Field values of the test class
 *     S₀  = Constructor / field initializers
 *     Σ   = @Operation-annotated methods
 *     →   = Method body (guard → mutation → return value)
 *     Φ   = Embedded via checkInvariants() called after every operation
 *
 *   The translation functor  G ∘ P : TLA_LTS → Lincheck_LTS  preserves:
 *     1. State space isomorphism (up to type encoding)
 *     2. Transition semantics (precondition ∧ effect)
 *     3. Safety invariants (embedded as post-operation checks)
 *
 *   SOUNDNESS CLAIM:
 *     If the TLA+ spec S satisfies an invariant φ, then the generated
 *     Lincheck test detects every implementation that violates φ under
 *     any linearizable interleaving explored by Lincheck's model checker.
 *
 * ─── CATEGORICAL PERSPECTIVE ──────────────────────────────────────────────────
 *
 *   Let TLA be the category whose objects are TLA+ specifications and whose
 *   morphisms are refinement mappings.  Let Lin be the category whose objects
 *   are Lincheck test classes and whose morphisms are behavioral subtypings.
 *
 *   [ConcurrentSystemSpec] is an object in a "bridge category" Bridge, with:
 *     P : TLA → Bridge    (the parser functor)
 *     G : Bridge → Lin    (the generator functor)
 *
 *   The composition G ∘ P is a functor TLA → Lin.
 *   Refinement mappings in TLA are preserved as behavioral subtyping in Lin:
 *     if S₁ refines S₂, then G(P(S₁)) is behaviorally a subtype of G(P(S₂)).
 *
 * ─── DESIGN DECISIONS ─────────────────────────────────────────────────────────
 *
 *   1. All types are immutable data classes — the IR is a value, not a process.
 *   2. Sealed hierarchies (EffectExpr, PredicateExpr) are exhaustive in `when`.
 *   3. Every node carries optional raw TLA+ text for traceability.
 *   4. A `Custom` variant in each sealed hierarchy serves as an escape hatch
 *      for TLA+ constructs the structural parser cannot fully decompose.
 *   5. `VariableType` is a closed enum covering the most common TLA+ types;
 *      CUSTOM is used for anything beyond the standard set.
 */

// ─────────────────────────────────────────────────────────────────────────────
//  ROOT SPECIFICATION
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The central data structure: a fully-parsed concurrent system specification.
 *
 * Instances are created by [io.github.tla2lincheck.parser.TlaParser] and
 * consumed by [io.github.tla2lincheck.generator.LincheckGenerator].
 *
 * @property name       Module name (from `---- MODULE Name ----`)
 * @property variables  State variables with inferred types
 * @property constants  Named constants (e.g., `MaxCopies`, `Users`)
 * @property init       Initial state predicate (assignments to variables)
 * @property actions    Named actions extracted from the `Next` relation
 * @property invariants Safety invariants (must hold in every reachable state)
 * @property liveness   Liveness properties (temporal, for documentation only)
 * @property source     Provenance metadata
 */
data class ConcurrentSystemSpec(
    val name: String,
    val variables: List<StateVariable>,
    val constants: List<SystemConstant>,
    val init: InitPredicate,
    val actions: List<ActionSpec>,
    val invariants: List<InvariantSpec>,
    val liveness: List<LivenessSpec> = emptyList(),
    val source: SpecSource = SpecSource.Manual
)

// ─────────────────────────────────────────────────────────────────────────────
//  STATE VARIABLES & TYPES
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A TLA+ state variable with its inferred Kotlin type.
 *
 * Type inference is performed by analyzing the `TypeOK` predicate:
 *   `count \in 0..MaxValue`    → [VariableType.INTEGER]
 *   `active \in BOOLEAN`       → [VariableType.BOOLEAN]
 *   `items \in SUBSET Books`   → [VariableType.SET_OF_INT]
 */
data class StateVariable(
    val name: String,
    val type: VariableType,
    val description: String = ""
)

/**
 * Closed enumeration of variable types that the generator knows how to map
 * to Kotlin field declarations and initializers.
 *
 * The [CUSTOM] variant is an escape hatch for types outside this set.
 *
 * @property tlaPattern  Representative TLA+ type expression
 * @property kotlinType  Generated Kotlin type
 * @property defaultInit Generated Kotlin initializer expression
 */
enum class VariableType(
    val tlaPattern: String,
    val kotlinType: String,
    val defaultInit: String
) {
    INTEGER("Int", "Int", "0"),
    BOOLEAN("BOOLEAN", "Boolean", "false"),
    STRING("STRING", "String", "\"\""),
    SET_OF_INT("SUBSET Int", "MutableSet<Int>", "mutableSetOf()"),
    SEQUENCE("Seq(...)", "MutableList<Any>", "mutableListOf()"),
    FUNCTION_INT_TO_INT("[... -> Int]", "MutableMap<Int, Int>", "mutableMapOf()"),
    FUNCTION_INT_TO_STRING("[... -> String]", "MutableMap<Int, String>", "mutableMapOf()"),
    FUNCTION_INT_TO_SET("[... -> SUBSET ...]", "MutableMap<Int, MutableSet<Int>>", "mutableMapOf()"),
    ENUM("{ ... }", "String", "\"\""),
    CUSTOM("custom", "Any", "null")
}

// ─────────────────────────────────────────────────────────────────────────────
//  CONSTANTS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A named constant from the TLA+ `CONSTANTS` section.
 *
 * Constants are either:
 *   - Domain-defining sets (e.g., `Users`, `Books`) used in `\E u \in Users`
 *   - Scalar bounds (e.g., `MaxValue`, `MaxCopies`) used in guards
 *
 * @property domain  For set-typed constants, the TLA+ set expression (e.g., `1..3`)
 */
data class SystemConstant(
    val name: String,
    val type: VariableType = VariableType.INTEGER,
    val defaultValue: String = "",
    val domain: String = "",
    val description: String = ""
)

// ─────────────────────────────────────────────────────────────────────────────
//  INIT PREDICATE
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The initial-state predicate, decomposed into per-variable assignments.
 *
 * TLA+:  `Init == /\ count = 0 /\ history = <<>>`
 * IR:    `InitPredicate(assignments = [count→"0", history→"<<>>"])`
 */
data class InitPredicate(
    val assignments: List<InitAssignment>,
    val rawTla: String = ""
)

data class InitAssignment(
    val variable: String,
    val expression: String,
    val tlaExpression: String = expression
)

// ─────────────────────────────────────────────────────────────────────────────
//  ACTIONS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A named action from the TLA+ specification.
 *
 * Each action becomes one `@Operation` method in the generated Lincheck test.
 * Actions with [isSystemAction]=true (e.g., `WalCommit`, `Crash`) are excluded
 * from Lincheck generation since they represent environment behavior, not
 * client-callable operations.
 *
 * @property parameters    Formal parameters (from `Action(u, b) ==`)
 * @property branches      Disjunctive sub-cases (`\/ guard /\ effects`)
 * @property isSystemAction  True if no user/client parameter → environment action
 */
data class ActionSpec(
    val name: String,
    val parameters: List<ActionParameter> = emptyList(),
    val returnType: String = "String",
    val returnValues: List<String> = listOf("ok"),
    val precondition: PredicateExpr = PredicateExpr.True,
    val effects: List<StateEffect> = emptyList(),
    val branches: List<ActionBranch> = emptyList(),
    val isSystemAction: Boolean = false,
    val description: String = ""
)

/**
 * A formal parameter of an action.
 *
 * The [domain] is inferred from the existential quantifier in the `Next`
 * relation: `\E u \in Users : Reserve(u)` → `domain = "Users"`.
 *
 * In the generated Lincheck code, the domain determines the `@Param`
 * annotation's gen/conf attributes.
 */
data class ActionParameter(
    val name: String,
    val type: VariableType = VariableType.INTEGER,
    val domain: String = "",
    val description: String = ""
)

/**
 * A disjunctive branch within an action.
 *
 * TLA+ actions often have the form:
 * ```
 * Reserve(u, b) ==
 *     \/ /\ avail[b] > 0 /\ avail' = [avail EXCEPT ![b] = @ - 1]   (* success *)
 *     \/ /\ avail[b] = 0 /\ UNCHANGED avail                          (* no_copies *)
 * ```
 * Each `\/` becomes an [ActionBranch].
 */
data class ActionBranch(
    val name: String = "default",
    val precondition: PredicateExpr = PredicateExpr.True,
    val effects: List<StateEffect> = emptyList(),
    val returnValue: String = "ok",
    val description: String = ""
)

// ─────────────────────────────────────────────────────────────────────────────
//  STATE EFFECTS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * An update to a single state variable within an action branch.
 */
data class StateEffect(
    val variable: String,
    val effect: EffectExpr,
    val tlaExpression: String = ""
)

/**
 * The effect applied to a variable.  Sealed hierarchy — exhaustive in `when`.
 *
 * Common patterns:
 *   `count' = count + 1`                       → [Increment]
 *   `count' = count - 1`                       → [Decrement]
 *   `count' = 0`                               → [Assign]("0")
 *   `reserved' = reserved \cup {u}`            → [SetAdd]("u")
 *   `reserved' = reserved \ {u}`               → [SetRemove]("u")
 *   `log' = Append(log, entry)`                → [SeqAppend]("entry")
 *   `[f EXCEPT ![k] = v]`                      → [FunctionUpdate]("k", "v")
 *   `UNCHANGED count`                          → [Unchanged]
 *   anything else                               → [Custom]
 */
sealed class EffectExpr {
    /** Direct assignment: `var' = expression` */
    data class Assign(val expression: String) : EffectExpr()
    /** `var' = var + amount` */
    data class Increment(val amount: Int = 1) : EffectExpr()
    /** `var' = var - amount` */
    data class Decrement(val amount: Int = 1) : EffectExpr()
    /** `var' = var \cup {element}` */
    data class SetAdd(val element: String) : EffectExpr()
    /** `var' = var \ {element}` */
    data class SetRemove(val element: String) : EffectExpr()
    /** `var' = Append(var, element)` */
    data class SeqAppend(val element: String) : EffectExpr()
    /** `[var EXCEPT ![key] = value]` — function/map update */
    data class FunctionUpdate(val key: String, val value: String) : EffectExpr()
    /** `UNCHANGED var` */
    data object Unchanged : EffectExpr()
    /** Escape hatch for expressions the parser cannot decompose */
    data class Custom(val tlaExpr: String, val kotlinExpr: String = "") : EffectExpr()
}

// ─────────────────────────────────────────────────────────────────────────────
//  PREDICATE EXPRESSIONS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A boolean predicate expression.  Used for:
 *   - Action preconditions (guards)
 *   - Branch conditions
 *   - Invariant bodies
 *
 * Sealed hierarchy — exhaustive in `when`.
 */
sealed class PredicateExpr {
    /** `left op right` (e.g., `count > 0`) */
    data class Comparison(val left: String, val op: CompOp, val right: String) : PredicateExpr()
    /** `element \in set` or `element \notin set` */
    data class Membership(val element: String, val set: String, val negated: Boolean = false) : PredicateExpr()
    /** `left /\ right` */
    data class And(val left: PredicateExpr, val right: PredicateExpr) : PredicateExpr()
    /** `left \/ right` */
    data class Or(val left: PredicateExpr, val right: PredicateExpr) : PredicateExpr()
    /** `~pred` */
    data class Not(val inner: PredicateExpr) : PredicateExpr()
    /** `\A var \in domain : body` */
    data class ForAll(val variable: String, val domain: String, val body: PredicateExpr) : PredicateExpr()
    /** `\E var \in domain : body` */
    data class Exists(val variable: String, val domain: String, val body: PredicateExpr) : PredicateExpr()
    /** Constant TRUE */
    data object True : PredicateExpr()
    /** Constant FALSE */
    data object False : PredicateExpr()
    /** Escape hatch for expressions the parser cannot decompose */
    data class Custom(val tlaExpr: String, val kotlinExpr: String = "") : PredicateExpr()
}

/**
 * Comparison operator with TLA+ and Kotlin surface syntax.
 */
enum class CompOp(val tla: String, val kotlin: String) {
    GT(">", ">"),
    GE(">=", ">="),
    LT("<", "<"),
    LE("<=", "<="),
    EQ("=", "=="),
    NEQ("#", "!=")
}

// ─────────────────────────────────────────────────────────────────────────────
//  INVARIANTS & LIVENESS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A safety invariant.
 *
 * In the generated Lincheck test, each invariant becomes a check inside
 * `checkInvariants()`, which is called at the end of every `@Operation`.
 * If an invariant fails under ANY interleaving, Lincheck reports the
 * exact execution trace.
 *
 * @property predicate  Structured predicate (if parseable)
 * @property rawTla     Original TLA+ text (always available)
 */
data class InvariantSpec(
    val name: String,
    val predicate: PredicateExpr = PredicateExpr.True,
    val description: String = "",
    val rawTla: String = ""
)

/**
 * A liveness property (temporal formula).
 *
 * Liveness properties cannot be tested with finite traces, so they appear
 * only as documentation comments in the generated test class.
 * Full liveness checking requires TLC model checking on the TLA+ spec itself.
 */
data class LivenessSpec(
    val name: String,
    val formula: String,
    val description: String = ""
)

// ─────────────────────────────────────────────────────────────────────────────
//  SOURCE METADATA
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Provenance information: where did this spec come from?
 */
sealed class SpecSource {
    /** Parsed from a `.tla` file */
    data class TlaFile(val filePath: String, val moduleName: String = "") : SpecSource()
    /** Constructed programmatically (e.g., in tests) */
    data object Manual : SpecSource()
}
