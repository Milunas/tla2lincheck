package io.github.tla2lincheck.parser

import io.github.tla2lincheck.ir.*

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TLA+ STRUCTURAL PARSER
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Parses a TLA+ specification file into a [ConcurrentSystemSpec].
 *
 * This is a STRUCTURAL parser — it extracts the key semantic elements
 * (VARIABLES, CONSTANTS, Init, actions, invariants) using pattern matching
 * on standard TLA+ conventions.  It is NOT a full TLA+ grammar parser
 * (that would require implementing the entire TLA+ syntax, which is the
 * domain of SANY — the official TLA+ Syntax Analyzer).
 *
 * WHAT IT HANDLES:
 *   ✓ Module name (---- MODULE Name ----)
 *   ✓ CONSTANTS / VARIABLES sections
 *   ✓ TypeOK predicates for type inference
 *   ✓ Init predicate (conjunctive assignments)
 *   ✓ Actions referenced from Next (with \E quantifier bindings)
 *   ✓ Disjunctive action branches (\/ ... /\ ... /\ ...)
 *   ✓ EXCEPT expressions ([f EXCEPT ![k] = v])
 *   ✓ Common effect patterns (increment, decrement, set ops, UNCHANGED)
 *   ✓ Named invariants
 *   ✓ Liveness / fairness properties
 *
 * WHAT IT DEFERS TO [Custom]:
 *   - Nested CASE / IF-THEN-ELSE expressions
 *   - Recursive function definitions
 *   - Higher-order operators
 *   - Complex LET/IN blocks
 *
 * DESIGN:
 *   The parser returns a [ParseResult] containing the spec, a list of
 *   warnings (for constructs it approximated), and a list of errors
 *   (for constructs it could not parse at all).
 *
 * EXTENDING:
 *   To handle additional TLA+ constructs, add new cases to
 *   [parseEffects] and [parsePrecondition].  The [Custom] variants
 *   in [EffectExpr] and [PredicateExpr] ensure that unrecognized
 *   expressions are captured rather than silently dropped.
 */
class TlaParser {

    private val sanyParser = SanyParser()

    // ─────────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Parses a TLA+ specification from its text content.
     *
     * Delegates to [SanyParser] which uses the SANY (Syntax Analyzer by Lamport)
     * to build a fully-typed AST, then walks it to produce a [ConcurrentSystemSpec].
     *
     * @param tlaContent  The full text of the `.tla` file
     * @param filePath    The file path (for [SpecSource] metadata)
     * @return A [ParseResult] containing the parsed spec, warnings, and errors
     */
    fun parse(tlaContent: String, filePath: String = ""): ParseResult {
        return sanyParser.parse(tlaContent, filePath)
    }

    /**
     * Parses using the legacy regex-based parser (kept for comparison/fallback).
     */
    fun parseLegacy(tlaContent: String, filePath: String = ""): ParseResult {
        val warnings = mutableListOf<ParseWarning>()
        val errors = mutableListOf<ParseError>()
        val lines = tlaContent.lines()

        val moduleName = extractModuleName(lines)
        if (moduleName.isEmpty()) {
            errors.add(ParseError("Could not find module name (expected ---- MODULE Name ----)"))
        }

        val constants = extractConstants(lines)
        val variables = extractVariables(lines)
        if (variables.isEmpty()) {
            warnings.add(ParseWarning("No VARIABLES section found or no variables extracted"))
        }

        val typeOK = extractTypeOK(tlaContent)
        val initPredicate = extractInit(tlaContent, warnings)
        val typedVariables = enrichVariablesWithTypes(variables, typeOK, initPredicate)
        val actions = extractActions(tlaContent, warnings)
        val actionNames = actions.map { it.name }.toSet()
        val invariants = extractInvariants(tlaContent, actionNames)
        val liveness = extractLiveness(tlaContent)

        val spec = ConcurrentSystemSpec(
            name = moduleName,
            variables = typedVariables,
            constants = constants,
            init = initPredicate,
            actions = actions,
            invariants = invariants,
            liveness = liveness,
            source = SpecSource.TlaFile(filePath, moduleName)
        )

        return ParseResult(spec, warnings, errors)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  MODULE NAME
    // ─────────────────────────────────────────────────────────────────────

    private fun extractModuleName(lines: List<String>): String {
        val pattern = Regex("""-{4,}\s*MODULE\s+(\w+)\s*-{4,}""")
        for (line in lines) {
            val match = pattern.find(line)
            if (match != null) return match.groupValues[1]
        }
        return ""
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CONSTANTS
    // ─────────────────────────────────────────────────────────────────────

    private fun extractConstants(lines: List<String>): List<SystemConstant> {
        val constants = mutableListOf<SystemConstant>()
        var inConstants = false

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("CONSTANTS") || trimmed.startsWith("CONSTANT")) {
                inConstants = true
                // Handle inline: CONSTANTS A, B, C
                val inline = trimmed
                    .removePrefix("CONSTANTS").removePrefix("CONSTANT").trim()
                if (inline.isNotEmpty()) {
                    inline.split(",").map { it.trim() }
                        .filter { it.isNotEmpty() && it.matches(Regex("""\w+""")) }
                        .forEach { constants.add(SystemConstant(name = it)) }
                }
                continue
            }

            if (inConstants) {
                // End of CONSTANTS section
                if (trimmed.isEmpty() && constants.isNotEmpty()) continue
                if (trimmed.startsWith("VARIABLES") || trimmed.startsWith("VARIABLE") ||
                    trimmed.startsWith("ASSUME") || trimmed.startsWith("----") ||
                    trimmed.matches(Regex("""^\w+\s*==.*""")) ||
                    trimmed.startsWith("(*") || trimmed.startsWith("\\*")) {
                    inConstants = false
                    continue
                }

                val cleaned = trimmed
                    .replace(Regex("""\\\*.*"""), "")
                    .replace(Regex("""\(\*.*?\*\)"""), "")
                    .trim().trimEnd(',')

                if (cleaned.isNotEmpty()) {
                    cleaned.split(",").map { it.trim() }
                        .filter { it.isNotEmpty() && it.matches(Regex("""\w+""")) }
                        .forEach { constants.add(SystemConstant(name = it)) }
                }
            }
        }
        return constants
    }

    // ─────────────────────────────────────────────────────────────────────
    //  VARIABLES
    // ─────────────────────────────────────────────────────────────────────

    private fun extractVariables(lines: List<String>): List<StateVariable> {
        val variables = mutableListOf<StateVariable>()
        var inVariables = false
        var inBlockComment = false
        val descriptionBuffer = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("VARIABLES") || trimmed.startsWith("VARIABLE")) {
                inVariables = true
                // Handle inline: VARIABLES a, b, c
                val inline = trimmed
                    .removePrefix("VARIABLES").removePrefix("VARIABLE").trim()
                if (inline.isNotEmpty()) {
                    parseVariableNames(inline).forEach {
                        variables.add(StateVariable(name = it, type = VariableType.INTEGER))
                    }
                }
                continue
            }

            if (!inVariables) continue

            // ── Block comment handling ──
            if (inBlockComment) {
                descriptionBuffer.add(trimmed)
                if (trimmed.contains("*)")) inBlockComment = false
                continue
            }
            if (trimmed.startsWith("(*")) {
                descriptionBuffer.clear()
                descriptionBuffer.add(trimmed)
                if (!trimmed.contains("*)")) inBlockComment = true
                continue
            }

            // ── Section termination ──
            if (trimmed.isEmpty() && variables.isNotEmpty()) continue
            if (isVariableSectionTerminator(trimmed)) {
                inVariables = false
                continue
            }

            // ── Variable name extraction ──
            val cleaned = trimmed
                .replace(Regex("""\\\*.*"""), "")       // inline comments
                .replace(Regex("""\(\*.*?\*\)"""), "")   // block comments
                .trim().trimEnd(',')

            if (cleaned.isNotEmpty()) {
                val desc = descriptionBuffer.joinToString(" ")
                    .replace("(*", "").replace("*)", "").trim()

                parseVariableNames(cleaned).forEach { name ->
                    variables.add(StateVariable(
                        name = name,
                        type = VariableType.INTEGER, // placeholder — enriched by TypeOK
                        description = desc
                    ))
                }
                descriptionBuffer.clear()
            }
        }
        return variables
    }

    private fun parseVariableNames(text: String): List<String> {
        return text.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.matches(Regex("""\w+""")) }
            .filterNot { it in TLA_KEYWORDS }
    }

    private fun isVariableSectionTerminator(trimmed: String): Boolean =
        trimmed.startsWith("CONSTANTS") || trimmed.startsWith("CONSTANT") ||
        trimmed.startsWith("ASSUME") || trimmed.startsWith("----") ||
        trimmed.matches(Regex("""^\w+\s*==.*""")) ||
        trimmed.startsWith("/\\") || trimmed.startsWith("\\/ ")

    // ─────────────────────────────────────────────────────────────────────
    //  TypeOK → TYPE ENRICHMENT
    // ─────────────────────────────────────────────────────────────────────

    private fun extractTypeOK(content: String): Map<String, String> {
        val block = extractDefinitionBlock(content, "TypeOK") ?: return emptyMap()
        val mapping = mutableMapOf<String, String>()

        // Pattern: varName \in TypeExpr
        val inPattern = Regex("""(\w+)\s*\\in\s+(.+)""")
        // Pattern: varName \subseteq SetExpr (means var is a subset of that set)
        val subsetPattern = Regex("""(\w+)\s*\\subseteq\s+(.+)""")

        for (line in block.lines()) {
            val cleaned = line.trim().removePrefix("/\\").trim()
            if (cleaned.isEmpty()) continue

            subsetPattern.find(cleaned)?.let { match ->
                // \subseteq means this is a SET variable
                mapping[match.groupValues[1]] = "SUBSET ${match.groupValues[2].trim()}"
            } ?: inPattern.find(cleaned)?.let { match ->
                mapping[match.groupValues[1]] = match.groupValues[2].trim()
            }
        }
        return mapping
    }

    private fun enrichVariablesWithTypes(
        variables: List<StateVariable>,
        typeOK: Map<String, String>,
        init: InitPredicate? = null
    ): List<StateVariable> {
        return variables.map { v ->
            val typeExpr = typeOK[v.name]
            if (typeExpr != null) {
                return@map v.copy(type = inferType(typeExpr))
            }
            // Fallback: infer type from Init expression
            if (init != null) {
                val initAssign = init.assignments.find { it.variable == v.name }
                if (initAssign != null) {
                    val inferredType = inferTypeFromInit(initAssign.tlaExpression)
                    if (inferredType != VariableType.CUSTOM) {
                        return@map v.copy(type = inferredType)
                    }
                }
            }
            v
        }
    }

    /**
     * Infers [VariableType] from a TLA+ Init assignment expression.
     *
     * Examples:
     *   `<<>>`                            → SEQUENCE
     *   `{}`                              → SET_OF_INT
     *   `0`                               → INTEGER
     *   `Actors`                          → SET_OF_INT (uppercase, looks like a set constant)
     *   `[c \in Clients |-> {}]`          → FUNCTION_INT_TO_SET
     */
    private fun inferTypeFromInit(expr: String): VariableType = when {
        expr == "<<>>" -> VariableType.SEQUENCE
        expr == "{}" -> VariableType.SET_OF_INT
        expr.matches(Regex("""\d+""")) -> VariableType.INTEGER
        expr == "TRUE" || expr == "FALSE" -> VariableType.BOOLEAN
        expr.startsWith("\"") -> VariableType.ENUM
        // [x \in Set |-> {}] → FUNCTION_INT_TO_SET
        expr.contains("|->") && expr.contains("{}") -> VariableType.FUNCTION_INT_TO_SET
        // [x \in Set |-> <<>>] → FUNCTION_INT_TO_INT (map to sequence, but close enough)
        expr.contains("|->") && expr.contains("<<>>") -> VariableType.FUNCTION_INT_TO_INT
        // [x \in Set |-> "string"] → FUNCTION_INT_TO_STRING
        expr.contains("|->") && expr.contains("\"") -> VariableType.FUNCTION_INT_TO_STRING
        // [x \in Set |-> 0] → FUNCTION_INT_TO_INT
        expr.contains("|->") -> VariableType.FUNCTION_INT_TO_INT
        // Uppercase word looks like a constant set → SET_OF_INT
        expr.matches(Regex("""\w+""")) && expr[0].isUpperCase() -> VariableType.SET_OF_INT
        else -> VariableType.CUSTOM
    }

    /**
     * Infers [VariableType] from a TLA+ type expression (from TypeOK).
     *
     * Examples:
     *   `0..MaxValue`                     → INTEGER
     *   `BOOLEAN`                         → BOOLEAN
     *   `SUBSET Users`                    → SET_OF_INT
     *   `Seq(Records)`                    → SEQUENCE
     *   `[Books -> 0..N]`                 → FUNCTION_INT_TO_INT
     *   `[Books -> SUBSET Users]`         → FUNCTION_INT_TO_SET
     *   `[Actors -> States]`              → FUNCTION_INT_TO_STRING (if States is an enum)
     *   `[Actors -> {"a","b"}]`           → FUNCTION_INT_TO_STRING
     *   `{"idle", "active"}`              → ENUM
     *   `Nat`                             → INTEGER
     */
    internal fun inferType(typeExpr: String): VariableType = when {
        typeExpr == "BOOLEAN" -> VariableType.BOOLEAN
        typeExpr == "Nat" -> VariableType.INTEGER
        typeExpr.startsWith("Seq(") -> VariableType.SEQUENCE
        // [Domain -> SUBSET Range] — function to set
        typeExpr.contains("->") && typeExpr.contains("SUBSET") -> VariableType.FUNCTION_INT_TO_SET
        // [Domain -> {string literals}] — function to string enum
        typeExpr.contains("->") && typeExpr.contains("\"") -> VariableType.FUNCTION_INT_TO_STRING
        // [Domain -> Range] — check if Range looks like integers or not
        typeExpr.contains("->") -> {
            val rangePart = typeExpr.substringAfter("->").trim().trimEnd(']')
            if (rangePart.contains("..") || rangePart == "Nat" || rangePart.matches(Regex("""\d+"""))) {
                VariableType.FUNCTION_INT_TO_INT
            } else {
                // Range is a named set — could be enum/string values
                VariableType.FUNCTION_INT_TO_STRING
            }
        }
        typeExpr.startsWith("SUBSET") -> VariableType.SET_OF_INT
        typeExpr.startsWith("{") && typeExpr.contains("\"") -> VariableType.ENUM
        typeExpr.matches(Regex(""".*\d+\.\.\w+.*""")) -> VariableType.INTEGER
        typeExpr.matches(Regex(""".*\d+\.\.\(.*\)""")) -> VariableType.INTEGER  // 1..(MaxPending + 1)
        typeExpr.matches(Regex("""\w+""")) -> VariableType.INTEGER // bare set name
        else -> VariableType.CUSTOM
    }

    // ─────────────────────────────────────────────────────────────────────
    //  INIT PREDICATE
    // ─────────────────────────────────────────────────────────────────────

    private fun extractInit(content: String, warnings: MutableList<ParseWarning>): InitPredicate {
        val block = extractDefinitionBlock(content, "Init")
        if (block == null) {
            warnings.add(ParseWarning("No Init predicate found"))
            return InitPredicate(emptyList())
        }

        val assignments = mutableListOf<InitAssignment>()
        val assignPattern = Regex("""(\w+)\s*=\s*(.+)""")

        for (line in block.lines()) {
            val cleaned = line.trim().removePrefix("/\\").trim()
            if (cleaned.isEmpty()) continue

            val match = assignPattern.find(cleaned) ?: continue
            val varName = match.groupValues[1]
            val expr = match.groupValues[2].trim()

            // Skip if this looks like a comparison rather than assignment
            // (Init should only have = not == or >= etc., but TLA+ uses = for both)
            // Be careful not to skip <<>> (empty sequence) or <<a, b>> (tuples)
            if (expr.startsWith("=") || expr.startsWith(">=") || expr.startsWith("<=") ||
                (expr.startsWith("<") && !expr.startsWith("<<")) ||
                (expr.startsWith(">") && !expr.startsWith(">>"))) continue

            assignments.add(InitAssignment(
                variable = varName,
                expression = tlaExprToKotlin(expr),
                tlaExpression = expr
            ))
        }

        return InitPredicate(assignments, rawTla = block.trim())
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ACTIONS (via Next relation)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Extracts all actions referenced from the Next state relation.
     *
     * Strategy:
     *   1. Find the `Next ==` definition block
     *   2. Handle `\E a \in Set :` wrapping multiple `\/ Action(a)` disjuncts
     *   3. Extract action names from `\E bindings : ActionName(...)` patterns
     *   4. Extract bare action names from `\/ ActionName` patterns
     *   5. For each action, find its definition and parse it
     */
    private fun extractActions(
        content: String,
        warnings: MutableList<ParseWarning>
    ): List<ActionSpec> {
        val nextBlock = extractDefinitionBlock(content, "Next")
        if (nextBlock == null) {
            warnings.add(ParseWarning("No Next state relation found"))
            return emptyList()
        }

        // Map: actionName → Map(paramName → domainName) from \E quantifiers
        val actionRefs = mutableListOf<ActionRef>()

        // ── NEW: Handle \E a \in Set : \/ Action1(a) \/ Action2(a) ... ──
        // This is the common pattern in ActorLifecycle.tla where one quantifier wraps
        // multiple disjuncts. We detect this when \E ... : is followed by \/ lines.
        val wrappingQuantifierPattern = Regex(
            """\\E\s+((?:\w+\s+\\in\s+[\w.]+(?:\s*,\s*)?)+)\s*:\s*\n((?:\s*\\/\s+.+\n?)+)""",
            RegexOption.MULTILINE
        )
        for (wrapMatch in wrappingQuantifierPattern.findAll(nextBlock)) {
            val bindingsStr = wrapMatch.groupValues[1]
            val disjunctsBlock = wrapMatch.groupValues[2]

            val bindings = mutableMapOf<String, String>()
            val bindingPattern = Regex("""(\w+)\s+\\in\s+([\w.]+)""")
            for (b in bindingPattern.findAll(bindingsStr)) {
                bindings[b.groupValues[1]] = b.groupValues[2]
            }

            // Extract action names from each \/ line inside the quantifier
            val innerActionPattern = Regex("""\\/\s+(\w+)(?:\(([^)]*)\))?""")
            for (innerMatch in innerActionPattern.findAll(disjunctsBlock)) {
                val actionName = innerMatch.groupValues[1]
                if (actionRefs.none { it.name == actionName }) {
                    actionRefs.add(ActionRef(actionName, bindings))
                }
            }
        }

        // Pattern: \E var1 \in Domain1, var2 \in Domain2 : ActionName(...)
        // (single action per quantifier — the original pattern)
        val quantifiedPattern = Regex(
            """\\E\s+((?:\w+\s+\\in\s+[\w.]+(?:\s*,\s*)?)+)\s*:\s*(\w+)"""
        )
        for (match in quantifiedPattern.findAll(nextBlock)) {
            val bindingsStr = match.groupValues[1]
            val actionName = match.groupValues[2]

            // Skip if already found via wrapping quantifier
            if (actionRefs.any { it.name == actionName }) continue

            val bindings = mutableMapOf<String, String>()
            val bindingPattern = Regex("""(\w+)\s+\\in\s+([\w.]+)""")
            for (b in bindingPattern.findAll(bindingsStr)) {
                bindings[b.groupValues[1]] = b.groupValues[2]
            }
            actionRefs.add(ActionRef(actionName, bindings))
        }

        // Pattern: \/ ActionName or \/ ActionName(...) (bare, no quantifier)
        val barePattern = Regex("""\\/\s+(\w+)(?:\(([^)]*)\))?\s*$""", RegexOption.MULTILINE)
        for (match in barePattern.findAll(nextBlock)) {
            val actionName = match.groupValues[1]
            if (actionRefs.none { it.name == actionName }) {
                actionRefs.add(ActionRef(actionName, emptyMap()))
            }
        }

        return actionRefs.mapNotNull { ref ->
            val block = extractDefinitionBlock(content, ref.name)
            if (block == null) {
                warnings.add(ParseWarning("Action '${ref.name}' referenced in Next but definition not found"))
                return@mapNotNull null
            }
            parseActionBlock(ref.name, block, ref.bindings, content)
        }
    }

    private data class ActionRef(val name: String, val bindings: Map<String, String>)

    /**
     * Parses a single action block into an [ActionSpec].
     *
     * @param name        Action name
     * @param block       The body text after `ActionName(...) ==`
     * @param bindings    Parameter → domain mapping from Next's \E quantifier
     * @param fullContent Full TLA+ source (for signature extraction)
     */
    private fun parseActionBlock(
        name: String,
        block: String,
        bindings: Map<String, String>,
        fullContent: String
    ): ActionSpec {
        // Extract formal parameters from the signature in full content
        val sigPattern = Regex("""$name\(([^)]*)\)\s*==""")
        val paramNames = sigPattern.find(fullContent)?.let { match ->
            match.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } ?: emptyList()

        val parameters = paramNames.map { p ->
            ActionParameter(
                name = p,
                type = VariableType.INTEGER,
                domain = bindings[p] ?: ""
            )
        }.toMutableList()

        // Detect inner existential quantifiers (e.g., `\E msg \in 1..MaxMessages :`)
        // These represent nondeterministic choice and become additional parameters
        val innerExistentialPattern = Regex("""\\E\s+(\w+)\s+\\in\s+([\w.]+(?:\.\.\w+)?)\s*:""")
        for (match in innerExistentialPattern.findAll(block)) {
            val varName = match.groupValues[1]
            val domain = match.groupValues[2]
            // Don't duplicate if already a parameter
            if (parameters.none { it.name == varName }) {
                parameters.add(ActionParameter(
                    name = varName,
                    type = VariableType.INTEGER,
                    domain = domain
                ))
            }
        }

        // System actions are internal actions not intended for user-facing operations.
        // Only mark as system if the action name matches EXACT known internal patterns.
        // Note: "Timeout" alone is NOT a system action (it models user-visible request timeout).
        val systemPatterns = setOf("Crash", "Recover", "Tick", "GarbageCollect", "ThreadReset")
        val isSystem = systemPatterns.any { name.equals(it, ignoreCase = true) }

        val branches = parseBranches(block)
        val returnValues = branches.map { it.returnValue }.distinct()

        return ActionSpec(
            name = name,
            parameters = parameters,
            returnType = "String",
            returnValues = returnValues,
            precondition = extractPrecondition(block),
            branches = branches,
            isSystemAction = isSystem,
            description = "TLA+ action: $name"
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BRANCH PARSING
    // ─────────────────────────────────────────────────────────────────────

    private fun parseBranches(block: String): List<ActionBranch> {
        val branches = mutableListOf<ActionBranch>()

        // Look for disjunctive branches: \/ /\ condition /\ effect
        val branchPattern = Regex(
            """\\/\s*(?:\(\*[^*]*\*\))?\s*(/\\.+?)(?=\\/|\z)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val disjuncts = branchPattern.findAll(block).toList()

        if (disjuncts.isEmpty()) {
            // Single branch — the entire block is one branch
            branches.add(ActionBranch(
                name = "default",
                precondition = extractPrecondition(block),
                effects = parseEffects(block),
                returnValue = extractReturnValue(block)
            ))
        } else {
            disjuncts.forEachIndexed { idx, disjunct ->
                val branchBlock = disjunct.groupValues[1]
                val returnValue = extractReturnValue(branchBlock)
                branches.add(ActionBranch(
                    name = returnValue.ifEmpty { "branch_$idx" },
                    precondition = extractBranchPrecondition(branchBlock),
                    effects = parseEffects(branchBlock),
                    returnValue = returnValue,
                    description = "Branch $idx"
                ))
            }
        }

        return branches
    }

    // ─────────────────────────────────────────────────────────────────────
    //  EFFECT PARSING
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Parses state effects from a TLA+ block.
     *
     * Recognized patterns:
     *   `var' = var + N`                        → Increment(N)
     *   `var' = var - N`                        → Decrement(N)
     *   `var' = expr`                           → Assign(expr)
     *   `var' = [var EXCEPT ![k] = v]`          → FunctionUpdate(k, v)
     *   `var' = [var EXCEPT ![k] = @ + N]`      → FunctionUpdate(k, "@ + N")
     *   `var' = [var EXCEPT ![k] = @ \cup {e}]` → FunctionUpdate with SetAdd
     *   `var' = [var EXCEPT ![k] = @ \ {e}]`    → FunctionUpdate with SetRemove
     *   `var' = var \cup {elem}`                → SetAdd(elem)
     *   `var' = var \ {elem}`                   → SetRemove(elem)
     *   `var' = Append(var, elem)`              → SeqAppend(elem)
     *   `var' = Tail(var)`                      → Assign with Kotlin translation
     *   `UNCHANGED var` / `UNCHANGED <<v1,v2>>` → Unchanged
     */
    internal fun parseEffects(block: String): List<StateEffect> {
        val effects = mutableListOf<StateEffect>()

        // Extract LET bindings and emit as local val declarations
        val letBindings = extractLetBindings(block)
        for ((name, expr) in letBindings) {
            val kotlinExpr = translateLetBinding(name, expr)
            effects.add(StateEffect(
                variable = name,
                effect = EffectExpr.Custom(
                    tlaExpr = "LET $name == $expr",
                    kotlinExpr = kotlinExpr
                ),
                tlaExpression = "LET $name == $expr"
            ))
        }

        // Flatten multi-line LET/IN into single-line for processing
        val normalizedBlock = normalizeLETIN(block)

        // Primed variable assignments: var' = expr
        val primePattern = Regex("""(\w+)'\s*=\s*(.+)""")
        for (line in normalizedBlock.lines()) {
            val cleaned = line.trim().removePrefix("/\\").trim()
            val match = primePattern.find(cleaned) ?: continue
            val varName = match.groupValues[1]
            val expr = match.groupValues[2].trim()

            effects.add(StateEffect(
                variable = varName,
                effect = classifyEffect(varName, expr),
                tlaExpression = cleaned
            ))
        }

        // UNCHANGED: single variable or tuple
        val unchangedSingle = Regex("""UNCHANGED\s+(\w+)""")
        val unchangedTuple = Regex("""UNCHANGED\s*<<([^>]+)>>""")

        for (line in normalizedBlock.lines()) {
            val cleaned = line.trim().removePrefix("/\\").trim()

            unchangedTuple.find(cleaned)?.let { match ->
                match.groupValues[1].split(",").map { it.trim() }
                    .filter { it.isNotEmpty() && it.matches(Regex("""\w+""")) }
                    .forEach { v ->
                        effects.add(StateEffect(v, EffectExpr.Unchanged, cleaned))
                    }
            } ?: unchangedSingle.find(cleaned)?.let { match ->
                effects.add(StateEffect(match.groupValues[1], EffectExpr.Unchanged, cleaned))
            }
        }

        return effects
    }

    /**
     * Translates a TLA+ LET binding expression to a Kotlin val declaration.
     * Examples:
     *   `rid == nextRequestId[c]` → `val rid = nextRequestId[c]!!`
     *   `req == Head(serverQueue)` → `val req = serverQueue.first()`
     *   `client == req[1]` → `val client = req[1]`  (tuple indexed access)
     *   `rid == req[2]` → `val rid = req[2]`
     */
    private fun translateLetBinding(name: String, expr: String): String {
        var kotlinExpr = expr
        // Head(x) → x.first()
        kotlinExpr = kotlinExpr.replace(Regex("""Head\((\w+)\)""")) { "${it.groupValues[1]}.first()" }
        // Tail(x) → x.drop(1).toMutableList()
        kotlinExpr = kotlinExpr.replace(Regex("""Tail\((\w+)\)""")) { "${it.groupValues[1]}.drop(1).toMutableList()" }
        // Len(x) → x.size
        kotlinExpr = kotlinExpr.replace(Regex("""Len\((\w+)\)""")) { "${it.groupValues[1]}.size" }
        // Map access: f[k] → f[k]!! (but not array index)
        // Only add !! for map access patterns (word[word]), not tuple index (word[digit])
        kotlinExpr = kotlinExpr.replace(Regex("""(\w+)\[(\w+)]""")) { m ->
            val mapName = m.groupValues[1]
            val key = m.groupValues[2]
            if (key.matches(Regex("""\d+"""))) {
                // Tuple/list index — cast from Any to List, then index (TLA+ 1-based → 0-based)
                "($mapName as List<*>)[${key.toInt() - 1}] as Int"
            } else {
                "${mapName}[$key]!!"
            }
        }
        return "val $name = $kotlinExpr"
    }

    /**
     * Normalizes LET/IN blocks by extracting bindings and preserving content.
     *
     * For patterns like:
     *   LET rid == nextRequestId[c]
     *   IN /\ pendingRequests' = [pendingRequests EXCEPT ![c] = @ \cup {rid}]
     *
     * Returns a pair of:
     *   1. The list of LET bindings as `name == expression` pairs
     *   2. The normalized block with LET/IN keywords removed
     */
    private fun extractLetBindings(block: String): List<Pair<String, String>> {
        val bindings = mutableListOf<Pair<String, String>>()
        val letPattern = Regex("""(?:LET\s+)?(\w+)\s*==\s*(.+)""")

        var inLetBlock = false
        for (line in block.lines()) {
            val trimmed = line.trim().removePrefix("/\\").trim()
            if (trimmed.startsWith("LET ")) {
                inLetBlock = true
                val afterLet = trimmed.removePrefix("LET").trim()
                letPattern.find(afterLet)?.let {
                    bindings.add(it.groupValues[1] to it.groupValues[2].trim())
                }
                continue
            }
            if (inLetBlock && !trimmed.startsWith("IN")) {
                // Additional LET bindings (multi-line LET)
                letPattern.find(trimmed)?.let {
                    bindings.add(it.groupValues[1] to it.groupValues[2].trim())
                }
                continue
            }
            if (trimmed == "IN" || trimmed.startsWith("IN ")) {
                inLetBlock = false
                continue
            }
        }

        return bindings
    }

    private fun normalizeLETIN(block: String): String {
        // Remove LET and IN keywords, keep content
        val lines = block.lines().toMutableList()
        val result = mutableListOf<String>()
        var i = 0
        var inLetBlock = false
        while (i < lines.size) {
            val trimmed = lines[i].trim().removePrefix("/\\").trim()
            if (trimmed.startsWith("LET ")) {
                inLetBlock = true
                i++
                continue
            }
            if (inLetBlock && !trimmed.startsWith("IN") && !trimmed.contains("'")) {
                // Skip LET binding lines
                i++
                continue
            }
            if (trimmed == "IN" || trimmed.startsWith("IN ")) {
                inLetBlock = false
                if (trimmed.length > 2) {
                    // "IN /\ something" — keep the content after IN
                    result.add(trimmed.removePrefix("IN").trim())
                }
                i++
                continue
            }
            result.add(lines[i])
            i++
        }
        return result.joinToString("\n")
    }

    /**
     * Classifies a primed assignment `var' = expr` into the appropriate [EffectExpr].
     *
     * Handles:
     *   - Simple arithmetic: `var + N`, `var - N`
     *   - EXCEPT patterns: `[var EXCEPT ![k] = v]`, `![k] = @ + 1`, `@ \cup {e}`, `@ \ {e}`
     *   - Set operations: `var \cup {e}`, `var \ {e}`
     *   - Sequence operations: `Append(var, e)`, `Tail(var)`, `Head(var)`
     *   - Direct assignment: anything else
     */
    internal fun classifyEffect(varName: String, expr: String): EffectExpr = when {
        // var' = var + N
        expr.matches(Regex("""$varName\s*\+\s*(\d+)""")) -> {
            val n = Regex("""\+\s*(\d+)""").find(expr)!!.groupValues[1].toInt()
            EffectExpr.Increment(n)
        }
        // var' = var - N
        expr.matches(Regex("""$varName\s*-\s*(\d+)""")) -> {
            val n = Regex("""-\s*(\d+)""").find(expr)!!.groupValues[1].toInt()
            EffectExpr.Decrement(n)
        }
        // var' = [var EXCEPT ![key] = value] — function/map update
        expr.contains("EXCEPT") -> parseExceptExpr(varName, expr)
        // var' = var \cup {elem} — set add
        expr.contains("\\cup") || expr.contains("\\union") -> {
            val elem = Regex("""\{(.+?)}""").find(expr)?.groupValues?.get(1)?.trim() ?: "?"
            EffectExpr.SetAdd(elem)
        }
        // var' = var \ {elem} — set remove (but not \cup)
        expr.contains("\\") && expr.contains("{") && !expr.contains("\\cup") && !expr.contains("\\union") -> {
            val elem = Regex("""\{(.+?)}""").find(expr)?.groupValues?.get(1)?.trim() ?: "?"
            EffectExpr.SetRemove(elem)
        }
        // var' = Append(var, elem) — sequence append
        expr.startsWith("Append(") -> {
            val inner = expr.removePrefix("Append(").removeSuffix(")")
            // Handle nested tuples like <<c, rid>> by finding the comma after the first arg
            val firstComma = findTopLevelComma(inner)
            val elem = if (firstComma >= 0) inner.substring(firstComma + 1).trim() else "?"
            // Translate TLA+ tuple <<a, b>> to Kotlin listOf(a, b)
            val kotlinElem = translateTupleExpr(elem)
            EffectExpr.SeqAppend(kotlinElem)
        }
        // var' = Tail(var) — remove head from sequence
        expr.matches(Regex("""Tail\(\s*$varName\s*\)""")) -> {
            EffectExpr.Custom("Tail($varName)", "${varName}.removeFirst()")
        }
        // var' = Head(var) — get head of sequence (as assignment)
        expr.matches(Regex("""Head\(\s*\w+\s*\)""")) -> {
            val seqName = Regex("""Head\(\s*(\w+)\s*\)""").find(expr)!!.groupValues[1]
            EffectExpr.Custom("Head($seqName)", "${seqName}.first()")
        }
        // Default: direct assignment with TLA→Kotlin translation
        else -> EffectExpr.Assign(tlaExprToKotlin(expr))
    }

    /**
     * Parses EXCEPT expressions into the appropriate [EffectExpr].
     *
     * Handles patterns:
     *   `[var EXCEPT ![k] = v]`           → FunctionUpdate(k, v)
     *   `[var EXCEPT ![k] = @ + 1]`       → FunctionUpdate(k, "@+1") → generator translates @
     *   `[var EXCEPT ![k] = @ \cup {e}]`  → FunctionUpdate with set-add semantics
     *   `[var EXCEPT ![k] = @ \ {e}]`     → FunctionUpdate with set-remove semantics
     */
    private fun parseExceptExpr(varName: String, expr: String): EffectExpr {
        val exceptMatch = Regex("""\[.*EXCEPT\s*!\[(.+?)]\s*=\s*(.+?)\s*]""").find(expr)
        if (exceptMatch != null) {
            val key = exceptMatch.groupValues[1].trim()
            val value = exceptMatch.groupValues[2].trim()

            // Translate @ (current value) patterns to Kotlin
            val kotlinValue = when {
                value.matches(Regex("""@\s*\+\s*\d+""")) -> {
                    val n = Regex("""\+\s*(\d+)""").find(value)!!.groupValues[1]
                    "$varName[$key] + $n"
                }
                value.matches(Regex("""@\s*-\s*\d+""")) -> {
                    val n = Regex("""-\s*(\d+)""").find(value)!!.groupValues[1]
                    "$varName[$key] - $n"
                }
                value.contains("@") && (value.contains("\\cup") || value.contains("\\union")) -> {
                    val elem = Regex("""\{(.+?)}""").find(value)?.groupValues?.get(1)?.trim() ?: "?"
                    // In-place set mutation: map[key]!!.add(elem)
                    return EffectExpr.Custom(
                        "$varName EXCEPT ![$key] = $value",
                        "$varName[$key]!!.add($elem)"
                    )
                }
                value.contains("@") && value.contains("\\") && value.contains("{") -> {
                    val elem = Regex("""\{(.+?)}""").find(value)?.groupValues?.get(1)?.trim() ?: "?"
                    // In-place set mutation: map[key]!!.remove(elem)
                    return EffectExpr.Custom(
                        "$varName EXCEPT ![$key] = $value",
                        "$varName[$key]!!.remove($elem)"
                    )
                }
                value == "@" -> "$varName[$key]" // identity
                else -> value
            }

            return EffectExpr.FunctionUpdate(key, kotlinValue)
        }
        return EffectExpr.Custom(expr)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PRECONDITION / PREDICATE PARSING
    // ─────────────────────────────────────────────────────────────────────

    private fun extractPrecondition(block: String): PredicateExpr {
        val conditions = mutableListOf<PredicateExpr>()
        val normalizedBlock = normalizeLETIN(block)

        for (line in normalizedBlock.lines()) {
            var cleaned = line.trim().removePrefix("/\\").trim()
            if (cleaned.isEmpty()) continue
            // Strip TLA+ inline comments: \* comment text
            cleaned = cleaned.replace(Regex("""\s*\\\*.*$"""), "").trim()
            if (cleaned.isEmpty()) continue
            if (cleaned.contains("'") || cleaned.startsWith("UNCHANGED")) continue // effects, not guards
            if (cleaned.startsWith("\\E ")) continue // existential quantifiers inside actions are part of effects

            val pred = parseSinglePredicate(cleaned) ?: continue
            conditions.add(pred)
        }

        return when {
            conditions.isEmpty() -> PredicateExpr.True
            conditions.size == 1 -> conditions.first()
            else -> conditions.reduce { acc, p -> PredicateExpr.And(acc, p) }
        }
    }

    private fun extractBranchPrecondition(block: String): PredicateExpr {
        // For a branch, only lines WITHOUT primed vars and WITHOUT UNCHANGED are guards
        return extractPrecondition(block)
    }

    /**
     * Parses a single predicate line into a [PredicateExpr].
     *
     * Handles:
     *   - Membership: `x \in S`, `x \notin S`
     *   - Comparisons: `a > b`, `a >= b`, `a = b`, `a # b`, etc.
     *   - Universal quantifiers: `\A x \in S : P`
     *   - Existential quantifiers: `\E x \in S : P`
     *   - Negation: `~P`, `\lnot P`
     *   - Implications: `P => Q`
     *   - Conjunctions within a line: `P /\ Q`
     *   - Disjunctions within a line: `P \/ Q`
     *   - Function application: `f[k]` in comparisons
     *   - Len/Cardinality in comparisons
     *   - Set intersection emptiness: `A \cap B = {}`
     *   - Subset: `A \subseteq B`
     */
    internal fun parseSinglePredicate(text: String): PredicateExpr? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // ── Multi-variable universal quantifier: \A x \in S, y \in T : body ──
        // Must come BEFORE implication check, because the quantifier body may contain =>
        Regex("""\\A\s+(\w+)\s+\\in\s+([\w.]+(?:\([^)]*\))?)\s*,\s*(\w+)\s+\\in\s+([\w.]+(?:\([^)]*\))?)\s*:\s*(.+)""").find(trimmed)?.let {
            val body = parseSinglePredicate(it.groupValues[5].trim())
                ?: PredicateExpr.Custom(it.groupValues[5].trim())
            // Nest as ForAll(x, S, ForAll(y, T, body))
            val inner = PredicateExpr.ForAll(it.groupValues[3], it.groupValues[4], body)
            return PredicateExpr.ForAll(it.groupValues[1], it.groupValues[2], inner)
        }

        // ── Universal quantifier: \A x \in S : body ──
        Regex("""\\A\s+(\w+)\s+\\in\s+([\w.]+(?:\([^)]*\))?)\s*:\s*(.+)""").find(trimmed)?.let {
            val body = parseSinglePredicate(it.groupValues[3].trim())
                ?: PredicateExpr.Custom(it.groupValues[3].trim())
            return PredicateExpr.ForAll(it.groupValues[1], it.groupValues[2], body)
        }

        // ── Existential quantifier: \E x \in S : body ──
        Regex("""\\E\s+(\w+)\s+\\in\s+([\w.]+(?:\([^)]*\))?)\s*:\s*(.+)""").find(trimmed)?.let {
            val body = parseSinglePredicate(it.groupValues[3].trim())
                ?: PredicateExpr.Custom(it.groupValues[3].trim())
            return PredicateExpr.Exists(it.groupValues[1], it.groupValues[2], body)
        }

        // ── Implication: P => Q ──
        if (trimmed.contains("=>")) {
            val parts = trimmed.split("=>", limit = 2)
            if (parts.size == 2) {
                val antecedent = parseSinglePredicate(parts[0].trim())
                val consequent = parseSinglePredicate(parts[1].trim())
                if (antecedent != null && consequent != null) {
                    // P => Q  ≡  ¬P ∨ Q
                    return PredicateExpr.Or(PredicateExpr.Not(antecedent), consequent)
                }
            }
        }

        // ── Set intersection emptiness: A \cap B = {} ──
        Regex("""([\w\[\]]+)\s*\\cap\s+([\w\[\]]+)\s*=\s*\{}""").find(trimmed)?.let {
            val kotlinExpr = "(${it.groupValues[1]} intersect ${it.groupValues[2]}).isEmpty()"
            return PredicateExpr.Custom(trimmed, kotlinExpr)
        }

        // ── Cardinality comparison: Cardinality(expr) op N ──
        Regex("""Cardinality\((.+?)\)\s*(<=|>=|<|>|=|#)\s*(.+)""").find(trimmed)?.let {
            val setExpr = translateSetExpr(it.groupValues[1].trim())
            val op = it.groupValues[2]
            val right = it.groupValues[3].trim()
            val kotlinOp = when (op) {
                "=" -> "=="; "#" -> "!="; else -> op
            }
            val kotlinExpr = "$setExpr.size $kotlinOp $right"
            return PredicateExpr.Custom(trimmed, kotlinExpr)
        }

        // ── Len comparison: Len(var) op N ──
        Regex("""Len\((\w+)\)\s*(<=|>=|<|>|=|#)\s*(.+)""").find(trimmed)?.let {
            val seqVar = it.groupValues[1]
            val op = it.groupValues[2]
            val right = it.groupValues[3].trim()
            val kotlinOp = when (op) {
                "=" -> "=="; "#" -> "!="; else -> op
            }
            return PredicateExpr.Comparison("${seqVar}.size", CompOp.entries.first { c -> c.kotlin == kotlinOp }, right)
        }

        // ── Membership: var \in Set ──
        Regex("""([\w\[\],\s]+?)\s*\\in\s+(.+)""").find(trimmed)?.let {
            val elem = it.groupValues[1].trim()
            val set = it.groupValues[2].trim()
            // Skip if this looks like a quantifier part (handled above)
            if (!trimmed.startsWith("\\A") && !trimmed.startsWith("\\E")) {
                return PredicateExpr.Membership(elem, set)
            }
        }

        // ── Negated membership: var \notin Set ──
        Regex("""([\w\[\],\s]+?)\s*\\notin\s+(.+)""").find(trimmed)?.let {
            return PredicateExpr.Membership(it.groupValues[1].trim(), it.groupValues[2].trim(), negated = true)
        }

        // ── Subset: A \subseteq B ──
        Regex("""([\w\[\]]+)\s*\\subseteq\s+([\w\[\]]+)""").find(trimmed)?.let {
            val kotlinExpr = "${it.groupValues[2]}.containsAll(${it.groupValues[1]})"
            return PredicateExpr.Custom(trimmed, kotlinExpr)
        }

        // ── Negation: ~P or \lnot P ──
        if (trimmed.startsWith("~") || trimmed.startsWith("\\lnot")) {
            val inner = trimmed.removePrefix("~").removePrefix("\\lnot").trim()
                .removePrefix("(").removeSuffix(")").trim()
            val innerPred = parseSinglePredicate(inner) ?: PredicateExpr.Custom(inner)
            return PredicateExpr.Not(innerPred)
        }

        // ── Inequality /= ──
        if (trimmed.contains("/=")) {
            val parts = trimmed.split("/=", limit = 2)
            if (parts.size == 2) {
                return PredicateExpr.Comparison(parts[0].trim(), CompOp.NEQ, parts[1].trim())
            }
        }

        // ── Comparison: left op right ──
        for (op in listOf(">=", "<=", "#", "=", ">", "<")) {
            // Skip if this is inside a function application like f[k] = v
            val parts = trimmed.split(op, limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                val compOp = when (op) {
                    ">=" -> CompOp.GE
                    "<=" -> CompOp.LE
                    ">" -> CompOp.GT
                    "<" -> CompOp.LT
                    "=" -> CompOp.EQ
                    "#" -> CompOp.NEQ
                    else -> continue
                }
                return PredicateExpr.Comparison(parts[0].trim(), compOp, parts[1].trim())
            }
        }

        // If we can't parse it, return null (the caller decides whether to use Custom)
        return null
    }

    // ─────────────────────────────────────────────────────────────────────
    //  RETURN VALUE EXTRACTION
    // ─────────────────────────────────────────────────────────────────────

    private fun extractReturnValue(block: String): String {
        // Look for a threadResult' = "value" pattern
        val resultPattern = Regex("""(?:result|threadResult)'\s*=.*?"(\w+)"""")
        resultPattern.find(block)?.let { return it.groupValues[1] }

        // Look for a comment hint: (* success *), (* fail *), etc.
        val commentHint = Regex("""\(\*\s*(\w+)\s*\*\)""")
        commentHint.find(block)?.let { return it.groupValues[1].lowercase() }

        return "ok"
    }

    // ─────────────────────────────────────────────────────────────────────
    //  INVARIANTS
    // ─────────────────────────────────────────────────────────────────────

    private fun extractInvariants(content: String, actionNames: Set<String> = emptySet()): List<InvariantSpec> {
        val invariants = mutableListOf<InvariantSpec>()

        // Strategy: Definitions that look like invariants:
        //   - Named predicates that are NOT actions (no primed variables)
        //   - Not Init, Next, Spec, TypeOK, Fairness, or known action names
        val excludeNames = setOf(
            "Init", "Next", "Spec", "TypeOK", "Fairness",
            "vars", "RECURSIVE", "States"
        ) + actionNames

        // Find all top-level definitions (both single-line and multi-line)
        val defPattern = Regex("""^(\w+)\s*==\s""", RegexOption.MULTILINE)
        for (match in defPattern.findAll(content)) {
            val name = match.groupValues[1]
            if (name in excludeNames) continue
            if (name in TLA_KEYWORDS) continue

            val block = extractDefinitionBlock(content, name) ?: continue

            // An invariant has NO primed variables and is a boolean expression
            if (block.contains("'")) continue  // has effects → not an invariant
            // Skip temporal formulas (Spec-like)
            if (block.contains("[]") && block.contains("_")) continue

            // Check if it looks like a predicate (contains comparisons, /\, quantifiers, etc.)
            if (block.contains("\\in") || block.contains(">=") || block.contains("<=") ||
                block.contains(">") || block.contains("<") ||
                block.contains("/\\") || block.contains("#") || block.contains("=>") ||
                block.contains("\\A") || block.contains("\\notin") ||
                block.contains("Cardinality") || block.contains("\\cap") ||
                block.contains("\\subseteq") || block.contains("/=") ||
                block.contains("Len(")) {

                val predicate = parseInvariantPredicate(block)
                invariants.add(InvariantSpec(
                    name = name,
                    predicate = predicate,
                    description = block.trim().lines().first().trim(),
                    rawTla = block.trim()
                ))
            }
        }

        return invariants
    }

    /**
     * Parses an invariant predicate block, handling multi-line expressions.
     * Unlike extractPrecondition (for actions), this handles the full block
     * as a single predicate, including complex constructs like:
     *   - `\A c \in Clients : Cardinality(...) <= N`
     *   - `\A c \in Clients : replies[c] \cap timedOut[c] = {}`
     *   - LET/IN blocks (treated as Custom with raw TLA+ description)
     *   - Multi-line quantified: `\A a \in Actors :\n    body`
     */
    private fun parseInvariantPredicate(block: String): PredicateExpr {
        val trimmedBlock = block.trim()

        // If it contains LET/IN, it's too complex for structural parsing
        // but we can still extract a description
        if (trimmedBlock.contains("LET") && trimmedBlock.contains("IN")) {
            return PredicateExpr.Custom(trimmedBlock)
        }

        // First, join multi-line quantified expressions.
        // Pattern: `\A x \in S :` on one line, body on next line(s).
        // We need to collapse these into a single logical unit.
        val joined = joinQuantifiedLines(trimmedBlock)

        // Try to join multi-line /\ into a single expression
        val lines = joined.lines()
            .map { it.trim().removePrefix("/\\").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("\\*") }

        if (lines.size == 1) {
            return parseSinglePredicate(lines[0]) ?: PredicateExpr.Custom(lines[0])
        }

        // Multiple conjuncts
        val conditions = lines.mapNotNull { parseSinglePredicate(it) }
        return when {
            conditions.isEmpty() -> PredicateExpr.Custom(trimmedBlock)
            conditions.size == 1 -> conditions.first()
            else -> conditions.reduce { acc, p -> PredicateExpr.And(acc, p) }
        }
    }

    /**
     * Joins multi-line quantified expressions into single lines.
     *
     * Handles patterns like:
     * ```
     * \A a \in Actors :
     *     actorState[a] = "stopped" => a \notin alive
     * ```
     * becomes:
     * ```
     * \A a \in Actors : actorState[a] = "stopped" => a \notin alive
     * ```
     *
     * Also handles multi-variable quantifiers:
     * ```
     * \A c \in Clients, rid \in 1..MaxPending :
     *     Cardinality(...) <= 1
     * ```
     */
    private fun joinQuantifiedLines(text: String): String {
        val lines = text.lines()
        val result = mutableListOf<String>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            // Skip TLA+ comments
            if (line.startsWith("\\*")) { i++; continue }

            // Check if this line ends a quantifier (ends with ':') and the body is on next line(s)
            val quantEndsWithColon = line.matches(Regex(""".*\\[AE]\s+\w+\s+\\in\s+.+:\s*$"""))

            if (quantEndsWithColon && i + 1 < lines.size) {
                // Collect continuation lines (indented body of the quantifier)
                val bodyLines = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size) {
                    val rawLine = lines[j]
                    val nextLine = rawLine.trim()
                    if (nextLine.isEmpty()) { j++; break } // empty line ends the body
                    if (nextLine.startsWith("\\*")) { j++; break } // comment ends the body
                    // Stop if we hit a new definition or non-indented content
                    val isIndented = rawLine.startsWith("    ") || rawLine.startsWith("\t")
                    if (!isIndented) break
                    if (nextLine.matches(Regex("""\w+\s*==.*"""))) break
                    bodyLines.add(nextLine.removePrefix("/\\").trim())
                    j++
                }
                if (bodyLines.isNotEmpty()) {
                    result.add("$line ${bodyLines.joinToString(" /\\ ")}")
                    i = j
                } else {
                    result.add(line)
                    i++
                }
            } else {
                result.add(line)
                i++
            }
        }

        return result.joinToString("\n")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  LIVENESS
    // ─────────────────────────────────────────────────────────────────────

    private fun extractLiveness(content: String): List<LivenessSpec> {
        val liveness = mutableListOf<LivenessSpec>()

        // Find weak/strong fairness and temporal formulas
        val fairnessBlock = extractDefinitionBlock(content, "Fairness")
        if (fairnessBlock != null) {
            val wfPattern = Regex("""WF_\w*\(([^)]+)\)""")
            val sfPattern = Regex("""SF_\w*\(([^)]+)\)""")

            wfPattern.findAll(fairnessBlock).forEach {
                liveness.add(LivenessSpec(
                    name = "WF(${it.groupValues[1].trim()})",
                    formula = it.value,
                    description = "Weak fairness: ${it.groupValues[1].trim()} must eventually execute if continuously enabled"
                ))
            }
            sfPattern.findAll(fairnessBlock).forEach {
                liveness.add(LivenessSpec(
                    name = "SF(${it.groupValues[1].trim()})",
                    formula = it.value,
                    description = "Strong fairness: ${it.groupValues[1].trim()} must eventually execute if infinitely often enabled"
                ))
            }
        }

        // Also look for standalone liveness properties ([]<>P, <>[]P, P ~> Q)
        val temporalPattern = Regex("""^(\w+)\s*==\s*.*(?:<>|~>|\[\]).*$""", RegexOption.MULTILINE)
        for (match in temporalPattern.findAll(content)) {
            val name = match.groupValues[1]
            if (liveness.none { it.name == name }) {
                liveness.add(LivenessSpec(
                    name = name,
                    formula = match.value.substringAfter("==").trim()
                ))
            }
        }

        return liveness
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPER: Definition block extraction
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Extracts the body of a named definition: `Name(...) == body`
     *
     * Returns everything after `==` until the next top-level definition
     * or section separator.
     */
    internal fun extractDefinitionBlock(content: String, name: String): String? {
        val defPattern = Regex("""(?:^|\n)$name(?:\([^)]*\))?\s*==\s*""")
        val match = defPattern.find(content) ?: return null

        val startIdx = match.range.first + match.value.indexOf("==") + 2
        val rest = content.substring(startIdx)

        // End at the next top-level definition or separator
        val endPattern = Regex("""(?:^|\n)\w+(?:\([^)]*\))?\s*==\s""")
        val endMatch = endPattern.find(rest)
        val endIdx = endMatch?.range?.first ?: rest.length

        return rest.substring(0, endIdx)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPER: TLA+ → Kotlin expression translation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Translates TLA+ set expressions to Kotlin.
     * Handles: `\cup` → `union`, `\cap` → `intersect`, 
     * and preserves function application `f[k]`.
     */
    private fun translateSetExpr(expr: String): String {
        var result = expr
        result = result.replace(Regex("""\s*\\cup\s*"""), " union ")
        result = result.replace(Regex("""\s*\\cap\s*"""), " intersect ")
        // Wrap compound expressions in parentheses
        if (result.contains(" union ") || result.contains(" intersect ")) {
            result = "($result)"
        }
        return result
    }

    /**
     * Translates a TLA+ tuple expression `<<a, b>>` to Kotlin `listOf(a, b)`.
     * If not a tuple, returns the expression unchanged.
     */
    private fun translateTupleExpr(expr: String): String {
        val trimmed = expr.trim()
        if (trimmed.startsWith("<<") && trimmed.endsWith(">>")) {
            val inner = trimmed.removePrefix("<<").removeSuffix(">>").trim()
            return "listOf($inner)"
        }
        return trimmed
    }

    /**
     * Finds the first top-level comma in a string, skipping nested delimiters.
     * Returns the index or -1 if not found.
     */
    private fun findTopLevelComma(text: String): Int {
        var depth = 0
        for ((i, ch) in text.withIndex()) {
            when (ch) {
                '(', '<', '[', '{' -> depth++
                ')', '>', ']', '}' -> depth--
                ',' -> if (depth == 0) return i
            }
        }
        return -1
    }

    private fun tlaExprToKotlin(expr: String): String = when {
        expr == "TRUE" -> "true"
        expr == "FALSE" -> "false"
        expr == "<<>>" -> "mutableListOf()"
        expr == "{}" -> "mutableSetOf()"
        expr.matches(Regex("""\d+""")) -> expr
        // [s \in Set |-> value] — function literal for maps
        expr.startsWith("[") && expr.contains("|->") -> {
            val mapMatch = Regex("""\[\w+\s+\\in\s+\w+\s*\|->\s*(.+)]""").find(expr)
            val defaultVal = mapMatch?.groupValues?.get(1)?.trim()
            if (defaultVal == "{}" || defaultVal == "{}") {
                "mutableMapOf()" // map of sets
            } else {
                "mutableMapOf()" // will be populated by test infrastructure
            }
        }
        else -> expr
    }

    companion object {
        /** TLA+ keywords that should not be treated as variable or constant names. */
        val TLA_KEYWORDS = setOf(
            "EXTENDS", "CONSTANTS", "CONSTANT", "VARIABLES", "VARIABLE",
            "ASSUME", "THEOREM", "LEMMA", "INSTANCE", "LOCAL",
            "Init", "Next", "Spec", "TRUE", "FALSE", "BOOLEAN",
            "IF", "THEN", "ELSE", "CASE", "OTHER",
            "LET", "IN", "EXCEPT", "UNCHANGED", "RECURSIVE",
            "ENABLED", "SUBSET", "UNION", "DOMAIN", "CHOOSE",
            "WF_", "SF_"
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PARSE RESULT
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Result of parsing a TLA+ specification.
 *
 * @property spec      The parsed specification (always present, may be partial)
 * @property warnings  Non-fatal issues (approximated expressions, missing sections)
 * @property errors    Fatal issues (missing module name, parse failures)
 */
data class ParseResult(
    val spec: ConcurrentSystemSpec,
    val warnings: List<ParseWarning> = emptyList(),
    val errors: List<ParseError> = emptyList()
) {
    val isSuccessful: Boolean get() = errors.isEmpty()
}

data class ParseWarning(val message: String, val line: Int? = null)
data class ParseError(val message: String, val line: Int? = null)
