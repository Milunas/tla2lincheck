---- MODULE Counter ----
\* ═══════════════════════════════════════════════════════════════════
\* Example: Bounded Counter with History
\* ═══════════════════════════════════════════════════════════════════
\*
\* A simple concurrent counter bounded by MaxValue.
\* Demonstrates the core tla2lincheck pipeline:
\*   1. This TLA+ spec defines the abstract concurrent system
\*   2. tla2lincheck parses it into an intermediate representation
\*   3. The generator emits a Lincheck test class that verifies
\*      linearizability of any Kotlin/Java implementation
\*
\* Safety invariants (NonNegative, BoundedAbove) are automatically
\* embedded as post-operation checks in the generated test.

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

\* ─── Safety Invariants ───────────────────────────────────────────
\* These are automatically verified in every reachable state by TLC,
\* and embedded as runtime checks in the generated Lincheck test.

NonNegative == count >= 0

BoundedAbove == count <= MaxValue

\* ─── Temporal Specification ──────────────────────────────────────

Spec == Init /\ [][Next]_<<count, history>>

====
