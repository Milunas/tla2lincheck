---- MODULE Reservation ----
\* ═══════════════════════════════════════════════════════════════════
\* Example: Concurrent Resource Reservation System
\* ═══════════════════════════════════════════════════════════════════
\*
\* A generic concurrent reservation system with:
\*   - Parameterized actions (\E u \in Users, r \in Resources : ...)
\*   - Set-valued state (reserved ⊆ Users × Resources)
\*   - Function-valued state (available : Resources → Nat)
\*   - EXCEPT expressions for map updates
\*
\* This example demonstrates tla2lincheck's ability to handle:
\*   - Parameter domain extraction from \E quantifiers
\*   - Set add/remove effect classification
\*   - Function update (EXCEPT) translation
\*   - ForAll invariants

EXTENDS Naturals, FiniteSets

CONSTANTS Users, Resources, Capacity

VARIABLES reserved, available

TypeOK ==
    /\ reserved \in SUBSET (Users \X Resources)
    /\ available \in [Resources -> 0..Capacity]

Init ==
    /\ reserved = {}
    /\ available = [r \in Resources |-> Capacity]

Reserve(u, r) ==
    /\ available[r] > 0
    /\ <<u, r>> \notin reserved
    /\ reserved' = reserved \cup {<<u, r>>}
    /\ available' = [available EXCEPT ![r] = @ - 1]

Release(u, r) ==
    /\ <<u, r>> \in reserved
    /\ reserved' = reserved \ {<<u, r>>}
    /\ available' = [available EXCEPT ![r] = @ + 1]

CheckAvailability(r) ==
    /\ UNCHANGED <<reserved, available>>

Next ==
    \/ \E u \in Users, r \in Resources : Reserve(u, r)
    \/ \E u \in Users, r \in Resources : Release(u, r)
    \/ \E r \in Resources : CheckAvailability(r)

\* ─── Safety Invariants ───────────────────────────────────────────

NoOverReservation == \A r \in Resources : available[r] >= 0

CapacityBound == \A r \in Resources : available[r] <= Capacity

ConsistentCounts ==
    \A r \in Resources :
        available[r] = Capacity - Cardinality({u \in Users : <<u, r>> \in reserved})

\* ─── Temporal Specification ──────────────────────────────────────

Spec == Init /\ [][Next]_<<reserved, available>>

====
