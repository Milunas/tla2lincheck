# tla2lincheck

**Automated generation of [Lincheck](https://github.com/JetBrains/lincheck) concurrent tests from [TLA+](https://lamport.azurewebsites.net/tla/tla.html) specifications.**

> _Bridge the gap between formal verification and implementation testing._

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

---

## Motivation

TLA+ is the gold standard for specifying concurrent and distributed systems.
Lincheck is the state-of-the-art tool for testing concurrent Kotlin/Java code against linearizability.
Yet there is **no automated bridge** between them — engineers write formal specs *and* hand-craft test code,
with no guarantee the two stay aligned.

**tla2lincheck** closes this gap:

```
TLA+ Specification ──► tla2lincheck ──► Lincheck Test Class
     (formal)            (bridge)        (executable)
```

### Key Innovation: Invariant Embedding

TLA+ safety invariants (e.g., `NonNegative == count >= 0`) are properties that must hold in **every reachable state**.
Lincheck natively tests linearizability (operation ordering) but has no built-in invariant mechanism.

tla2lincheck bridges this by **embedding invariant checks** inside every generated `@Operation` method:

```kotlin
@Operation
fun increment(): String = synchronized(lockObj) {
    if (count < maxValue) {
        count++
        checkInvariants()  // ← verifies ALL TLA+ invariants
        return "ok"
    }
    return "at_max"
}

private fun checkInvariants() {
    check(count >= 0) { "Invariant 'NonNegative' violated" }
    check(count <= maxValue) { "Invariant 'BoundedAbove' violated" }
}
```

If **any** interleaving explored by Lincheck's model checker produces a state violating an invariant,
the test fails with the exact trace — connecting TLA+'s state-space safety to implementation testing.

---

## Formal Foundation

A TLA+ specification defines a **Labeled Transition System** (LTS):

$$\text{LTS} = (S,\ S_0,\ \Sigma,\ \rightarrow,\ \Phi)$$

| Component | TLA+ | Lincheck |
|-----------|------|----------|
| $S$ — State space | `VARIABLES` | Class fields |
| $S_0$ — Initial state | `Init` predicate | Field initializers |
| $\Sigma$ — Actions | Named actions in `Next` | `@Operation` methods |
| $\rightarrow$ — Transitions | Guard + primed effects | Method body (if/else + mutations) |
| $\Phi$ — Safety invariants | Named predicates | `checkInvariants()` (embedded) |

The translation functor $F: \mathbf{TLA} \to \mathbf{Lin}$ preserves:
1. **State space isomorphism** (up to type encoding)
2. **Transition semantics** (precondition + effect)
3. **Safety invariants** (embedded as post-operation checks)

---

## Architecture

```
┌──────────────┐     ┌──────────────┐     ┌──────────────────┐
│  TLA+ File   │────►│  TlaParser   │────►│ ConcurrentSystem │
│  (.tla)      │     │  (parser)    │     │ Spec (IR)        │
└──────────────┘     └──────────────┘     └────────┬─────────┘
                                                   │
                                          ┌────────▼─────────┐
                                          │ LincheckGenerator │
                                          │ (generator)       │
                                          └────────┬─────────┘
                                                   │
                                          ┌────────▼─────────┐
                                          │ Kotlin Source     │
                                          │ (Lincheck Test)   │
                                          └──────────────────┘
```

### Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `tla2lincheck-ir` | Core IR types | `ConcurrentSystemSpec` — the semantic bridge |
| `tla2lincheck-parser` | TLA+ → IR | Structural parser with type inference |
| `tla2lincheck-generator` | IR → Kotlin | Lincheck test code generation |
| `tla2lincheck-gradle` | Gradle plugin | Build-time test generation |

---

## Quick Start

### Gradle Plugin (Recommended)

```kotlin
// build.gradle.kts
plugins {
    id("io.github.tla2lincheck") version "0.1.0-SNAPSHOT"
}

tla2lincheck {
    tlaSourceDir.set(file("src/main/tla"))
    packageName.set("com.myproject.generated")
    threads.set(3)
    actorsPerThread.set(2)
    embedInvariants.set(true)
}

dependencies {
    testImplementation("org.jetbrains.kotlinx:lincheck:2.34")
}
```

Place your `.tla` files in `src/main/tla/`. Run:

```bash
./gradlew generateLincheckTests
```

Generated test classes appear in `build/generated/tla2lincheck/` and are automatically
added to the test source set.

### Programmatic API

```kotlin
import io.github.tla2lincheck.parser.TlaParser
import io.github.tla2lincheck.generator.LincheckGenerator

val parser = TlaParser()
val generator = LincheckGenerator()

// Parse a TLA+ specification
val parseResult = parser.parse(File("Counter.tla").readText())
if (!parseResult.isSuccessful) {
    parseResult.errors.forEach { System.err.println("ERROR: ${it.message}") }
    return
}

// Generate a Lincheck test class
val config = LincheckGenerator.Config(
    packageName = "com.myproject.tests",
    threads = 3,
    actorsPerThread = 2,
    embedInvariants = true
)
val generated = generator.generate(parseResult.spec, config)

// Write to file
File("${generated.className}.kt").writeText(generated.code)
```

---

## Supported TLA+ Patterns

| Pattern | Example | Status |
|---------|---------|--------|
| State variables | `VARIABLES x, y, z` | ✅ |
| Constants | `CONSTANTS N, Users` | ✅ |
| Type constraints | `TypeOK == x \in 0..N` | ✅ |
| Init predicate | `Init == x = 0 /\ y = {}` | ✅ |
| Named actions | `Increment == ...` | ✅ |
| Parameterized actions | `Reserve(u, b) == ...` | ✅ |
| ∃-quantified Next | `\E u \in Users : Act(u)` | ✅ |
| Primed assignments | `x' = x + 1` | ✅ |
| UNCHANGED | `UNCHANGED <<x, y>>` | ✅ |
| Set operations | `S' = S \cup {e}`, `S \ {e}` | ✅ |
| Sequence operations | `Append(seq, elem)` | ✅ |
| EXCEPT expressions | `[f EXCEPT ![k] = v]` | ✅ |
| Boolean guards | `/\ x > 0 /\ y \in S` | ✅ |
| Named invariants | `Inv == \A x : P(x)` | ✅ |
| Fairness (WF/SF) | `WF_vars(Action)` | ⚠️ documented only |
| CASE expressions | `CASE p1 -> e1 [] ...` | 🔜 planned |
| LET/IN | `LET x == ... IN ...` | 🔜 planned |
| Recursive operators | `RECURSIVE Op(_)` | ❌ not supported |

---

## Examples

### Counter (Simple)

See [`examples/counter/Counter.tla`](examples/counter/Counter.tla) — a bounded counter with
`Increment`, `Decrement`, `Read` actions and `NonNegative`/`BoundedAbove` invariants.

### Reservation (Parameterized)

See [`examples/reservation/Reservation.tla`](examples/reservation/Reservation.tla) — a concurrent
resource reservation system with parameterized actions, set/function state, and EXCEPT expressions.

---

## IR Design

The core IR type `ConcurrentSystemSpec` models a concurrent system as:

```kotlin
data class ConcurrentSystemSpec(
    val name: String,                    // Module name
    val variables: List<StateVariable>,  // State space S
    val constants: List<SystemConstant>, // Model parameters
    val init: InitPredicate,             // Initial state S₀
    val actions: List<ActionSpec>,        // Action alphabet Σ
    val invariants: List<InvariantSpec>, // Safety properties Φ
    val liveness: List<LivenessSpec>,    // Liveness (documented only)
    val source: SpecSource               // Provenance
)
```

Effects are modeled as a sealed hierarchy:

```kotlin
sealed class EffectExpr {
    data class Assign(val expression: String)
    data class Increment(val amount: Int = 1)
    data class Decrement(val amount: Int = 1)
    data class SetAdd(val element: String)
    data class SetRemove(val element: String)
    data class SeqAppend(val element: String)
    data class FunctionUpdate(val key: String, val value: String)
    data object Unchanged
    data class Custom(val tlaExpr: String, val kotlinExpr: String = "")
}
```

---

## Limitations

1. **Structural parser**: tla2lincheck uses a regex-based structural parser, not a full TLA+ parser
   (like SANY). It handles the patterns listed above but may not parse arbitrary TLA+ modules.

2. **Liveness properties**: TLA+ liveness (`<>[]P`, `[]<>P`, WF/SF) cannot be tested with
   finite traces. They are documented in generated code but not checked.

3. **Custom types**: Complex TLA+ types (records, tuples, nested functions) may require
   manual `Custom(...)` IR nodes with hand-written Kotlin translations.

4. **Atomicity**: TLA+ actions are atomic transitions. The generated Lincheck test wraps each
   `@Operation` in `synchronized(lockObj)` — suitable for testing, but the actual implementation
   under test should use its own concurrency control.

---

## Future Work

- **SANY integration**: Use the official TLA+ parser for full language support
- **Refinement checking**: Verify that implementation (Lincheck test) refines spec (TLA+)
- **Multiple targets**: Generate JCStress, jqwik, or custom harness code
- **IntelliJ plugin**: IDE support with TLA+ → test navigation
- **Counterexample replay**: Convert TLC counterexamples to Lincheck scenarios

---

## Building

```bash
git clone https://github.com/your-username/tla2lincheck.git
cd tla2lincheck
./gradlew build
```

Requires JDK 21+.

---

## License

Apache License 2.0

---

## Citation

If you use tla2lincheck in academic work, please cite:

```bibtex
@software{tla2lincheck,
  title = {tla2lincheck: Automated Generation of Lincheck Tests from TLA+ Specifications},
  author = {Milunas},
  year = {2025},
  url = {https://github.com/milunas/tla2lincheck}
}
```
