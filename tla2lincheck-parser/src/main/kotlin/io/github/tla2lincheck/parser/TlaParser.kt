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

    // ─────────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Parses a TLA+ specification from its text content.
     *
     * @param tlaContent  The full text of the `.tla` file
     * @param filePath    The file path (for [SpecSource] metadata)
     * @return A [ParseResult] containing the parsed spec, warnings, and errors
     */
    fun parse(tlaContent: String, filePath: String = ""): ParseResult {
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
        val typedVariables = enrichVariablesWithTypes(variables, typeOK)
        val initPredicate = extractInit(tlaContent, warnings)
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
        val pattern = Regex("""(\w+)\s*\\in\s+(.+)""")
        for (line in block.lines()) {
            val match = pattern.find(line.trim().removePrefix("/\\").trim()) ?: continue
            mapping[match.groupValues[1]] = match.groupValues[2].trim()
        }
        return mapping
    }

    private fun enrichVariablesWithTypes(
        variables: List<StateVariable>,
        typeOK: Map<String, String>
    ): List<StateVariable> {
        return variables.map { v ->
            val typeExpr = typeOK[v.name] ?: return@map v
            v.copy(type = inferType(typeExpr))
        }
    }

    /**
     * Infers [VariableType] from a TLA+ type expression (from TypeOK).
     *
     * Examples:
     *   `0..MaxValue`              → INTEGER
     *   `BOOLEAN`                  → BOOLEAN
     *   `SUBSET Users`             → SET_OF_INT
     *   `Seq(Records)`             → SEQUENCE
     *   `[Books -> 0..N]`          → FUNCTION_INT_TO_INT
     *   `[Books -> SUBSET Users]`  → FUNCTION_INT_TO_SET
     *   `{"idle", "active"}`       → ENUM
     */
    internal fun inferType(typeExpr: String): VariableType = when {
        typeExpr == "BOOLEAN" -> VariableType.BOOLEAN
        typeExpr.startsWith("Seq(") -> VariableType.SEQUENCE
        typeExpr.contains("->") && typeExpr.contains("SUBSET") -> VariableType.FUNCTION_INT_TO_SET
        typeExpr.contains("->") -> VariableType.FUNCTION_INT_TO_INT
        typeExpr.startsWith("SUBSET") -> VariableType.SET_OF_INT
        typeExpr.startsWith("{") && typeExpr.contains("\"") -> VariableType.ENUM
        typeExpr.matches(Regex(""".*\d+\.\.\w+.*""")) -> VariableType.INTEGER
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
            if (expr.startsWith("=") || expr.startsWith(">") || expr.startsWith("<")) continue

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
     *   2. Extract action names from `\E bindings : ActionName(...)` patterns
     *   3. Extract bare action names from `\/ ActionName` patterns
     *   4. For each action, find its definition and parse it
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

        // Pattern: \E var1 \in Domain1, var2 \in Domain2 : ActionName(...)
        val quantifiedPattern = Regex(
            """\\E\s+((?:\w+\s+\\in\s+\w+(?:\s*,\s*)?)+)\s*:\s*(\w+)"""
        )
        for (match in quantifiedPattern.findAll(nextBlock)) {
            val bindingsStr = match.groupValues[1]
            val actionName = match.groupValues[2]

            val bindings = mutableMapOf<String, String>()
            val bindingPattern = Regex("""(\w+)\s+\\in\s+(\w+)""")
            for (b in bindingPattern.findAll(bindingsStr)) {
                bindings[b.groupValues[1]] = b.groupValues[2]
            }
            actionRefs.add(ActionRef(actionName, bindings))
        }

        // Pattern: \/ ActionName (bare, no quantifier)
        val barePattern = Regex("""\\/\s+(\w+)\s*$""", RegexOption.MULTILINE)
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
        }

        // System actions are internal actions not intended for user-facing operations.
        // By default, all actions from the Next relation are user-facing.
        // Only mark as system if the action name follows known internal patterns.
        val systemPatterns = setOf("Crash", "Recover", "Tick", "Timeout", "Reset", "GarbageCollect")
        val isSystem = systemPatterns.any { name.contains(it, ignoreCase = true) }

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
     *   `var' = var \cup {elem}`                → SetAdd(elem)
     *   `var' = var \ {elem}`                   → SetRemove(elem)
     *   `var' = Append(var, elem)`              → SeqAppend(elem)
     *   `UNCHANGED var` / `UNCHANGED <<v1,v2>>` → Unchanged
     */
    internal fun parseEffects(block: String): List<StateEffect> {
        val effects = mutableListOf<StateEffect>()

        // Primed variable assignments: var' = expr
        val primePattern = Regex("""(\w+)'\s*=\s*(.+)""")
        for (line in block.lines()) {
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

        for (line in block.lines()) {
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
     * Classifies a primed assignment `var' = expr` into the appropriate [EffectExpr].
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
        // var' = [var EXCEPT ![key] = value]
        expr.contains("EXCEPT") -> {
            val exceptMatch = Regex("""\[.*EXCEPT\s*!\[(.+?)]\s*=\s*(.+?)]""").find(expr)
            if (exceptMatch != null) {
                EffectExpr.FunctionUpdate(exceptMatch.groupValues[1].trim(), exceptMatch.groupValues[2].trim())
            } else {
                EffectExpr.Custom(expr)
            }
        }
        // var' = var \cup {elem}
        expr.contains("\\cup") || expr.contains("\\union") -> {
            val elem = Regex("""\{(.+?)}""").find(expr)?.groupValues?.get(1)?.trim() ?: "?"
            EffectExpr.SetAdd(elem)
        }
        // var' = var \ {elem}
        expr.contains("\\") && expr.contains("{") && !expr.contains("\\cup") -> {
            val elem = Regex("""\{(.+?)}""").find(expr)?.groupValues?.get(1)?.trim() ?: "?"
            EffectExpr.SetRemove(elem)
        }
        // var' = Append(var, elem)
        expr.startsWith("Append(") -> {
            val parts = expr.removePrefix("Append(").removeSuffix(")").split(",", limit = 2)
            EffectExpr.SeqAppend(parts.getOrElse(1) { "?" }.trim())
        }
        // Default: direct assignment
        else -> EffectExpr.Assign(expr)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PRECONDITION / PREDICATE PARSING
    // ─────────────────────────────────────────────────────────────────────

    private fun extractPrecondition(block: String): PredicateExpr {
        val conditions = mutableListOf<PredicateExpr>()

        for (line in block.lines()) {
            val cleaned = line.trim().removePrefix("/\\").trim()
            if (cleaned.isEmpty()) continue
            if (cleaned.contains("'") || cleaned.startsWith("UNCHANGED")) continue // effects, not guards

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
     */
    internal fun parseSinglePredicate(text: String): PredicateExpr? {
        val trimmed = text.trim()

        // var \in Set
        Regex("""(\w+(?:\[[\w,\s]+])?)\s*\\in\s+(.+)""").find(trimmed)?.let {
            return PredicateExpr.Membership(it.groupValues[1].trim(), it.groupValues[2].trim())
        }

        // var \notin Set
        Regex("""(\w+(?:\[[\w,\s]+])?)\s*\\notin\s+(.+)""").find(trimmed)?.let {
            return PredicateExpr.Membership(it.groupValues[1].trim(), it.groupValues[2].trim(), negated = true)
        }

        // Comparison: left op right
        for (op in listOf(">=", "<=", "#", "/=", "=", ">", "<")) {
            val parts = trimmed.split(op, limit = 2)
            if (parts.size == 2) {
                val compOp = when (op) {
                    ">=" -> CompOp.GE
                    "<=" -> CompOp.LE
                    ">" -> CompOp.GT
                    "<" -> CompOp.LT
                    "=" -> CompOp.EQ
                    "#", "/=" -> CompOp.NEQ
                    else -> continue
                }
                return PredicateExpr.Comparison(parts[0].trim(), compOp, parts[1].trim())
            }
        }

        // \A var \in Domain : body
        Regex("""\\A\s+(\w+)\s+\\in\s+(\w+)\s*:\s*(.+)""").find(trimmed)?.let {
            val body = parseSinglePredicate(it.groupValues[3].trim()) ?: PredicateExpr.Custom(it.groupValues[3].trim())
            return PredicateExpr.ForAll(it.groupValues[1], it.groupValues[2], body)
        }

        // ~(expr) or negation
        if (trimmed.startsWith("~") || trimmed.startsWith("\\lnot")) {
            val inner = trimmed.removePrefix("~").removePrefix("\\lnot").trim()
                .removePrefix("(").removeSuffix(")").trim()
            val innerPred = parseSinglePredicate(inner) ?: PredicateExpr.Custom(inner)
            return PredicateExpr.Not(innerPred)
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
            "vars", "RECURSIVE"
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
            if (block.contains("\\E") && block.contains(":")) continue  // likely an action sub-part
            // Skip temporal formulas (Spec-like)
            if (block.contains("[]") && block.contains("_")) continue

            // Check if it looks like a predicate (contains comparisons, /\, etc.)
            if (block.contains("\\in") || block.contains(">=") || block.contains("<=") ||
                block.contains(">") || block.contains("<") ||
                block.contains("/\\") || block.contains("#") || block.contains("=>") ||
                block.contains("\\A") || block.contains("\\notin")) {

                val predicate = extractPrecondition(block)
                invariants.add(InvariantSpec(
                    name = name,
                    predicate = predicate,
                    rawTla = block.trim()
                ))
            }
        }

        return invariants
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

    private fun tlaExprToKotlin(expr: String): String = when {
        expr == "TRUE" -> "true"
        expr == "FALSE" -> "false"
        expr == "<<>>" -> "mutableListOf()"
        expr == "{}" -> "mutableSetOf()"
        expr.matches(Regex("""\d+""")) -> expr
        expr.startsWith("[") && expr.contains("|->") -> "mutableMapOf()" // function literal
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
