package io.github.tla2lincheck.parser

import io.github.tla2lincheck.ir.*
import tla2sany.drivers.SANY
import tla2sany.modanalyzer.SpecObj
import tla2sany.semantic.*
import util.SimpleFilenameToStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/**
 * SANY-based TLA+ parser.
 *
 * Uses SANY (Syntactic Analyzer by Lamport) to produce a fully-typed AST,
 * then walks it to build a [ConcurrentSystemSpec] intermediate representation.
 */
class SanyParser {

    // ─── PUBLIC API ──────────────────────────────────────────────────────

    fun parse(tlaContent: String, filePath: String = ""): ParseResult {
        val warnings = mutableListOf<ParseWarning>()
        val errors = mutableListOf<ParseError>()

        // Check for module header
        if (filePath.isEmpty() && !Regex("""-{4,}\s*MODULE\s+\w+\s*-{4,}""").containsMatchIn(tlaContent)) {
            errors.add(ParseError("Missing module name: TLA+ spec must start with ---- MODULE <Name> ----"))
            return errorResult("Missing module name", errors)
        }

        val tlaFile = prepareTlaFile(tlaContent, filePath)
        val module: ModuleNode
        try {
            module = runSany(tlaFile, errors)
                ?: return errorResult("SANY parse failed", errors)
        } catch (e: Exception) {
            errors.add(ParseError("SANY error: ${e.message}"))
            return errorResult("SANY error: ${e.message}", errors)
        } finally {
            if (filePath.isEmpty()) {
                tlaFile.delete()
                tlaFile.parentFile?.let { parent ->
                    if (parent.name.startsWith("tla2lincheck-sany-")) {
                        parent.deleteRecursively()
                    }
                }
            }
        }

        val moduleName = module.name.toString()
        val opDefs = module.opDefs ?: emptyArray()

        val constants = extractConstants(module)
        val variables = extractVariables(module)

        if (variables.isEmpty()) {
            warnings.add(ParseWarning("No VARIABLES section found"))
        }

        // Filter to only locally defined operators (exclude imported ones from EXTENDS)
        // location.source() returns the module name where the operator was defined
        val opDefMap = opDefs
            .filter { !it.isLocal && it.location?.source()?.toString() == moduleName }
            .associateBy { it.name.toString() }

        val typeOK = extractTypeOK(opDefMap["TypeOK"])
        val initDef = opDefMap["Init"]
        val initPredicate = extractInit(initDef, warnings)

        val typedVariables = enrichVariablesWithTypes(variables, typeOK, initPredicate)

        val nextDef = opDefMap["Next"]
        val actions = extractActions(nextDef, opDefMap, warnings)
        val actionNames = actions.map { it.name }.toSet()

        val invariants = extractInvariants(opDefMap, actionNames, variables.map { it.name }.toSet())
        val liveness = extractLiveness(opDefMap)

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

    // ─── SANY INVOCATION ─────────────────────────────────────────────────

    private fun prepareTlaFile(content: String, filePath: String): File {
        if (filePath.isNotEmpty()) {
            val file = File(filePath)
            if (file.exists()) return file
        }
        val tmpDir = Files.createTempDirectory("tla2lincheck-sany-").toFile()
        val moduleNameMatch = Regex("""-{4,}\s*MODULE\s+(\w+)\s*-{4,}""").find(content)
        val moduleName = moduleNameMatch?.groupValues?.get(1) ?: "Spec"
        val tmpFile = File(tmpDir, "$moduleName.tla")
        tmpFile.writeText(content)
        return tmpFile
    }

    private fun runSany(tlaFile: File, errors: MutableList<ParseError>): ModuleNode? {
        val parentPath = tlaFile.parentFile?.absolutePath ?: "."
        val resolver = SimpleFilenameToStream(parentPath)
        val specObj = SpecObj(tlaFile.absolutePath, resolver)

        val devNull = PrintStream(java.io.OutputStream.nullOutputStream())
        try {
            val exitCode = SANY.frontEndMain(specObj, tlaFile.absolutePath, devNull)

            if (exitCode != 0) {
                specObj.parseErrors?.let { parseErrs ->
                    if (!parseErrs.isSuccess) {
                        errors.add(ParseError("SANY parse errors: ${parseErrs.toString().take(500)}"))
                    }
                }
                specObj.semanticErrors?.let { semErrs ->
                    if (!semErrs.isSuccess) {
                        errors.add(ParseError("SANY semantic errors: ${semErrs.toString().take(500)}"))
                    }
                }
                return null
            }
        } catch (e: Exception) {
            errors.add(ParseError("SANY exception: ${e.message}"))
            return null
        }

        return specObj.externalModuleTable?.rootModule
    }

    // ─── CONSTANTS ───────────────────────────────────────────────────────

    private fun extractConstants(module: ModuleNode): List<SystemConstant> {
        val decls = module.constantDecls ?: return emptyList()
        return decls.map { SystemConstant(name = it.name.toString()) }
    }

    // ─── VARIABLES ───────────────────────────────────────────────────────

    private fun extractVariables(module: ModuleNode): List<StateVariable> {
        val decls = module.variableDecls ?: return emptyList()
        return decls.map {
            StateVariable(name = it.name.toString(), type = VariableType.INTEGER)
        }
    }

    // ─── TypeOK → TYPE INFERENCE ─────────────────────────────────────────

    private fun extractTypeOK(typeOKDef: OpDefNode?): Map<String, VariableType> {
        if (typeOKDef == null) return emptyMap()
        val body = typeOKDef.body ?: return emptyMap()

        val mapping = mutableMapOf<String, VariableType>()
        forEachConjunct(body) { conjunct ->
            if (conjunct is OpApplNode) {
                val opName = conjunct.operator?.name?.toString() ?: return@forEachConjunct
                if (opName == "\\in") {
                    val args = conjunct.args ?: return@forEachConjunct
                    if (args.size >= 2) {
                        val varName = nodeToName(args[0])
                        val typeExpr = args[1]
                        if (varName != null && typeExpr != null) {
                            mapping[varName] = inferTypeFromNode(typeExpr)
                        }
                    }
                } else if (opName == "\\subseteq") {
                    val args = conjunct.args ?: return@forEachConjunct
                    if (args.size >= 2) {
                        val varName = nodeToName(args[0])
                        if (varName != null) {
                            mapping[varName] = VariableType.SET_OF_INT
                        }
                    }
                }
            }
        }
        return mapping
    }

    private fun inferTypeFromNode(node: ExprOrOpArgNode): VariableType {
        if (node !is OpApplNode) {
            val name = nodeToName(node)
            return when (name) {
                "BOOLEAN" -> VariableType.BOOLEAN
                "Nat", "Int" -> VariableType.INTEGER
                "STRING" -> VariableType.STRING
                else -> VariableType.INTEGER
            }
        }

        val opName = node.operator?.name?.toString() ?: return VariableType.CUSTOM
        val args = node.args ?: emptyArray()

        return when (opName) {
            "Seq" -> VariableType.SEQUENCE
            "SUBSET" -> VariableType.SET_OF_INT
            ".." -> VariableType.INTEGER
            OP_FCN_SET, OP_SET_OF_FCNS -> {
                if (args.size >= 2) {
                    when (inferTypeFromNode(args[1])) {
                        VariableType.SET_OF_INT -> VariableType.FUNCTION_INT_TO_SET
                        VariableType.STRING, VariableType.ENUM -> VariableType.FUNCTION_INT_TO_STRING
                        else -> VariableType.FUNCTION_INT_TO_INT
                    }
                } else VariableType.FUNCTION_INT_TO_INT
            }
            OP_SET_ENUM -> {
                if (args.all { it is StringNode }) VariableType.ENUM
                else VariableType.SET_OF_INT
            }
            "\\X" -> VariableType.SET_OF_INT
            else -> VariableType.CUSTOM
        }
    }

    private fun enrichVariablesWithTypes(
        variables: List<StateVariable>,
        typeOK: Map<String, VariableType>,
        init: InitPredicate
    ): List<StateVariable> {
        return variables.map { v ->
            val typeFromTypeOK = typeOK[v.name]
            if (typeFromTypeOK != null) return@map v.copy(type = typeFromTypeOK)
            val initAssign = init.assignments.find { it.variable == v.name }
            if (initAssign != null) {
                val inferred = inferTypeFromInit(initAssign.tlaExpression)
                if (inferred != VariableType.CUSTOM) return@map v.copy(type = inferred)
            }
            v
        }
    }

    private fun inferTypeFromInit(expr: String): VariableType = when {
        expr == "<<>>" -> VariableType.SEQUENCE
        expr == "{}" -> VariableType.SET_OF_INT
        expr.matches(Regex("""\d+""")) -> VariableType.INTEGER
        expr == "TRUE" || expr == "FALSE" -> VariableType.BOOLEAN
        expr.startsWith("\"") -> VariableType.ENUM
        expr.contains("|->") && expr.contains("{}") -> VariableType.FUNCTION_INT_TO_SET
        expr.contains("|->") && expr.contains("<<>>") -> VariableType.FUNCTION_INT_TO_INT
        expr.contains("|->") && expr.contains("\"") -> VariableType.FUNCTION_INT_TO_STRING
        expr.contains("|->") -> VariableType.FUNCTION_INT_TO_INT
        expr.matches(Regex("""\w+""")) && expr[0].isUpperCase() -> VariableType.SET_OF_INT
        else -> VariableType.CUSTOM
    }

    // ─── INIT PREDICATE ──────────────────────────────────────────────────

    private fun extractInit(initDef: OpDefNode?, warnings: MutableList<ParseWarning>): InitPredicate {
        if (initDef == null) {
            warnings.add(ParseWarning("No Init predicate found"))
            return InitPredicate(emptyList())
        }

        val body = initDef.body ?: return InitPredicate(emptyList())
        val assignments = mutableListOf<InitAssignment>()

        forEachConjunct(body) { conjunct ->
            if (conjunct is OpApplNode) {
                val opName = conjunct.operator?.name?.toString() ?: return@forEachConjunct
                if (opName == "=") {
                    val args = conjunct.args ?: return@forEachConjunct
                    if (args.size >= 2) {
                        val varName = nodeToName(args[0])
                        if (varName != null) {
                            val tlaExpr = nodeToTlaString(args[1])
                            val kotlinExpr = tlaExprToKotlin(tlaExpr)
                            assignments.add(InitAssignment(
                                variable = varName,
                                expression = kotlinExpr,
                                tlaExpression = tlaExpr
                            ))
                        }
                    }
                }
            }
        }

        return InitPredicate(assignments, rawTla = nodeToTlaString(body))
    }

    // ─── ACTIONS ─────────────────────────────────────────────────────────

    private fun extractActions(
        nextDef: OpDefNode?,
        opDefMap: Map<String, OpDefNode>,
        warnings: MutableList<ParseWarning>
    ): List<ActionSpec> {
        if (nextDef == null) {
            warnings.add(ParseWarning("No Next state relation found"))
            return emptyList()
        }

        val body = nextDef.body ?: return emptyList()
        val actionRefs = mutableListOf<ActionRef>()
        collectActionRefs(body, emptyMap(), actionRefs)

        return actionRefs.mapNotNull { ref ->
            val actionDef = opDefMap[ref.name]
            if (actionDef == null) {
                warnings.add(ParseWarning("Action '${ref.name}' referenced in Next but not found"))
                return@mapNotNull null
            }
            parseActionDef(ref.name, actionDef, ref.bindings)
        }
    }

    private data class ActionRef(val name: String, val bindings: Map<String, String>)

    private fun collectActionRefs(
        node: ExprOrOpArgNode?,
        bindings: Map<String, String>,
        refs: MutableList<ActionRef>
    ) {
        if (node == null) return
        if (node !is OpApplNode) return

        val opName = node.operator?.name?.toString() ?: return
        val args = node.args ?: emptyArray()

        when (opName) {
            "\\/", OP_DISJ_LIST -> {
                for (arg in args) collectActionRefs(arg, bindings, refs)
            }

            OP_BOUNDED_EXISTS -> {
                val newBindings = bindings.toMutableMap()
                val quantSymbols = node.bdedQuantSymbolLists ?: emptyArray()
                val quantBounds = node.bdedQuantBounds ?: emptyArray()

                for (i in quantSymbols.indices) {
                    if (i < quantBounds.size) {
                        val symbols = quantSymbols[i] ?: continue
                        val boundExpr = nodeToTlaString(quantBounds[i])
                        for (sym in symbols) {
                            newBindings[sym.name.toString()] = boundExpr
                        }
                    }
                }
                if (args.isNotEmpty()) collectActionRefs(args[0], newBindings, refs)
            }

            "/\\", OP_CONJ_LIST -> {
                // inline conjunction — not an action reference
            }

            else -> {
                val op = node.operator
                if (op is OpDefNode && !op.isLocal) {
                    val actionName = op.name.toString()
                    if (actionName !in EXCLUDED_DEFINITIONS && refs.none { it.name == actionName }) {
                        refs.add(ActionRef(actionName, bindings))
                    }
                }
            }
        }
    }

    private fun parseActionDef(
        name: String,
        def: OpDefNode,
        quantifierBindings: Map<String, String>
    ): ActionSpec {
        val body = def.body ?: return ActionSpec(name = name)

        val formalParams = def.params ?: emptyArray()
        val parameters = formalParams.map { param ->
            ActionParameter(
                name = param.name.toString(),
                type = VariableType.INTEGER,
                domain = quantifierBindings[param.name.toString()] ?: ""
            )
        }.toMutableList()

        collectInnerQuantifiers(body, parameters, formalParams.map { it.name.toString() }.toSet())

        val isSystem = SYSTEM_ACTION_NAMES.any { name.equals(it, ignoreCase = true) }
        val branches = parseBranches(body)
        val returnValues = branches.map { it.returnValue }.distinct()

        return ActionSpec(
            name = name,
            parameters = parameters,
            returnType = "String",
            returnValues = returnValues,
            precondition = extractPrecondition(body),
            branches = branches,
            isSystemAction = isSystem,
            description = "TLA+ action: $name"
        )
    }

    private fun collectInnerQuantifiers(
        node: ExprOrOpArgNode?,
        parameters: MutableList<ActionParameter>,
        existingNames: Set<String>
    ) {
        if (node == null || node !is OpApplNode) return
        val opName = node.operator?.name?.toString() ?: return

        if (opName == OP_BOUNDED_EXISTS) {
            val quantSymbols = node.bdedQuantSymbolLists ?: emptyArray()
            val quantBounds = node.bdedQuantBounds ?: emptyArray()
            for (i in quantSymbols.indices) {
                if (i < quantBounds.size) {
                    val symbols = quantSymbols[i] ?: continue
                    val domain = nodeToTlaString(quantBounds[i])
                    for (sym in symbols) {
                        val paramName = sym.name.toString()
                        if (paramName !in existingNames && parameters.none { it.name == paramName }) {
                            parameters.add(ActionParameter(
                                name = paramName,
                                type = VariableType.INTEGER,
                                domain = domain
                            ))
                        }
                    }
                }
            }
        }

        val args = node.args ?: return
        for (arg in args) collectInnerQuantifiers(arg, parameters, existingNames)
    }

    // ─── BRANCH PARSING ──────────────────────────────────────────────────

    private fun parseBranches(body: ExprNode): List<ActionBranch> {
        if (body is OpApplNode) {
            val opName = body.operator?.name?.toString()
            if (opName == "\\/" || opName == OP_DISJ_LIST) {
                val args = body.args ?: emptyArray()
                return args.mapIndexed { idx, arg -> parseSingleBranch(arg, "branch_$idx") }
            }
        }
        return listOf(parseSingleBranch(body, "default"))
    }

    private fun parseSingleBranch(node: ExprOrOpArgNode?, branchName: String): ActionBranch {
        if (node == null) return ActionBranch(name = branchName, returnValue = "ok")
        val actual = unwrapQuantifiers(node)
        val precondition = extractBranchPrecondition(actual)
        val effects = extractEffects(actual)
        val returnValue = extractReturnValue(actual)

        return ActionBranch(
            name = returnValue.ifEmpty { branchName },
            precondition = precondition,
            effects = effects,
            returnValue = returnValue.ifEmpty { "ok" },
            description = "Branch $branchName"
        )
    }

    private fun unwrapQuantifiers(node: ExprOrOpArgNode): ExprOrOpArgNode {
        if (node is OpApplNode) {
            val opName = node.operator?.name?.toString()
            if (opName == OP_BOUNDED_EXISTS) {
                val args = node.args
                if (args != null && args.isNotEmpty()) return unwrapQuantifiers(args[0])
            }
        }
        return node
    }

    // ─── EFFECT EXTRACTION ───────────────────────────────────────────────

    private fun extractEffects(node: ExprOrOpArgNode?): List<StateEffect> {
        if (node == null) return emptyList()
        val effects = mutableListOf<StateEffect>()

        forEachConjunct(node) { conjunct ->
            if (conjunct is OpApplNode) {
                val opName = conjunct.operator?.name?.toString() ?: return@forEachConjunct
                val args = conjunct.args ?: emptyArray()

                when (opName) {
                    "=" -> {
                        if (args.size >= 2 && isPrimedVar(args[0])) {
                            val varName = primedVarName(args[0])
                            if (varName != null) {
                                val tlaExpr = nodeToTlaString(args[1])
                                effects.add(StateEffect(
                                    variable = varName,
                                    effect = classifyEffect(varName, args[1], tlaExpr),
                                    tlaExpression = "$varName' = $tlaExpr"
                                ))
                            }
                        }
                    }

                    "UNCHANGED" -> {
                        if (args.isNotEmpty()) {
                            extractUnchangedVars(args[0]).forEach { v ->
                                effects.add(StateEffect(v, EffectExpr.Unchanged, "UNCHANGED $v"))
                            }
                        }
                    }
                }
            }

            if (conjunct is LetInNode) {
                for (letDef in conjunct.lets) {
                    val letName = letDef.name.toString()
                    val letExpr = nodeToTlaString(letDef.body)
                    val kotlinExpr = translateLetBinding(letName, letExpr)
                    effects.add(StateEffect(
                        variable = letName,
                        effect = EffectExpr.Custom(
                            tlaExpr = "LET $letName == $letExpr",
                            kotlinExpr = kotlinExpr
                        ),
                        tlaExpression = "LET $letName == $letExpr"
                    ))
                }
                effects.addAll(extractEffects(conjunct.body))
            }
        }

        return effects
    }

    private fun isPrimedVar(node: ExprOrOpArgNode?): Boolean {
        if (node !is OpApplNode) return false
        return node.operator?.name?.toString() == "'"
    }

    private fun primedVarName(node: ExprOrOpArgNode?): String? {
        if (node !is OpApplNode) return null
        if (node.operator?.name?.toString() != "'") return null
        val args = node.args
        return if (args != null && args.isNotEmpty()) nodeToName(args[0]) else null
    }

    private fun extractUnchangedVars(node: ExprOrOpArgNode?): List<String> {
        if (node == null) return emptyList()
        val name = nodeToName(node)
        if (name != null) return listOf(name)
        if (node is OpApplNode) {
            val opName = node.operator?.name?.toString()
            if (opName == OP_TUPLE) {
                val args = node.args ?: return emptyList()
                return args.mapNotNull { nodeToName(it) }
            }
        }
        return emptyList()
    }

    private fun classifyEffect(varName: String, exprNode: ExprOrOpArgNode?, tlaExpr: String): EffectExpr {
        if (exprNode == null) return EffectExpr.Assign(tlaExprToKotlin(tlaExpr))

        if (exprNode is OpApplNode) {
            val opName = exprNode.operator?.name?.toString() ?: return EffectExpr.Assign(tlaExprToKotlin(tlaExpr))
            val args = exprNode.args ?: emptyArray()

            when (opName) {
                "+" -> {
                    if (args.size >= 2 && nodeToName(args[0]) == varName) {
                        val n = nodeToInt(args[1])
                        if (n != null) return EffectExpr.Increment(n)
                    }
                    if (args.size >= 2 && nodeToName(args[1]) == varName) {
                        val n = nodeToInt(args[0])
                        if (n != null) return EffectExpr.Increment(n)
                    }
                }

                "-" -> {
                    if (args.size >= 2 && nodeToName(args[0]) == varName) {
                        val n = nodeToInt(args[1])
                        if (n != null) return EffectExpr.Decrement(n)
                    }
                }

                "\\cup", "\\union" -> {
                    if (args.size >= 2 && nodeToName(args[0]) == varName) {
                        val elemStr = extractSingleSetElement(args[1])
                        if (elemStr != null) return EffectExpr.SetAdd(elemStr)
                    }
                }

                "\\" -> {
                    if (args.size >= 2 && nodeToName(args[0]) == varName) {
                        val elemStr = extractSingleSetElement(args[1])
                        if (elemStr != null) return EffectExpr.SetRemove(elemStr)
                    }
                }

                OP_EXCEPT -> return parseExceptNode(varName, exprNode)

                "Append" -> {
                    if (args.size >= 2) {
                        val elem = nodeToTlaString(args[1])
                        val kotlinElem = translateTupleExpr(elem)
                        return EffectExpr.SeqAppend(kotlinElem)
                    }
                }

                "Tail" -> return EffectExpr.Custom("Tail($varName)", "$varName.removeFirst()")
                "Head" -> {
                    val seqName = if (args.isNotEmpty()) nodeToName(args[0]) ?: varName else varName
                    return EffectExpr.Custom("Head($seqName)", "$seqName.first()")
                }
            }
        }

        return EffectExpr.Assign(tlaExprToKotlin(tlaExpr))
    }

    private fun parseExceptNode(varName: String, node: OpApplNode): EffectExpr {
        val args = node.args ?: return EffectExpr.Custom(nodeToTlaString(node))

        if (args.size >= 2) {
            val pair = args[1]
            if (pair is OpApplNode) {
                val pairOp = pair.operator?.name?.toString()
                if (pairOp == OP_PAIR) {
                    val pairArgs = pair.args ?: return EffectExpr.Custom(nodeToTlaString(node))
                    if (pairArgs.size >= 2) {
                        val keyExpr = extractExceptKey(pairArgs[0])
                        val valueExpr = pairArgs[1]
                        val valueStr = translateExceptValue(varName, keyExpr, valueExpr)
                        return EffectExpr.FunctionUpdate(keyExpr, valueStr)
                    }
                }
            }

            val tlaStr = nodeToTlaString(node)
            val exceptMatch = Regex("""\[.*EXCEPT\s*!\[(.+?)]\s*=\s*(.+?)\s*]""").find(tlaStr)
            if (exceptMatch != null) {
                val key = exceptMatch.groupValues[1].trim()
                val value = exceptMatch.groupValues[2].trim()
                val kotlinValue = translateExceptValueStr(varName, key, value)
                return EffectExpr.FunctionUpdate(key, kotlinValue)
            }
        }

        return EffectExpr.Custom(nodeToTlaString(node))
    }

    private fun extractExceptKey(node: ExprOrOpArgNode?): String {
        if (node == null) return "?"
        if (node is OpApplNode) {
            val opName = node.operator?.name?.toString()
            if (opName == OP_TUPLE || opName == OP_SEQ) {
                val args = node.args
                if (args != null && args.size == 1) return nodeToTlaString(args[0])
            }
        }
        return nodeToTlaString(node)
    }

    private fun translateExceptValue(varName: String, key: String, valueNode: ExprOrOpArgNode?): String {
        if (valueNode == null) return "?"

        if (valueNode is OpApplNode) {
            val opName = valueNode.operator?.name?.toString() ?: return nodeToTlaString(valueNode)
            val args = valueNode.args ?: emptyArray()

            if (opName == "+" && args.size >= 2) {
                // Check if first arg references the current value (@ in TLA+ or $FcnApply)
                val firstArg = args[0]
                if (firstArg is AtNode ||
                    (firstArg is OpApplNode && firstArg.operator?.name?.toString() == OP_FCN_APPLY)) {
                    val n = nodeToTlaString(args[1])
                    return "$varName[$key] + $n"
                }
            }
            if (opName == "-" && args.size >= 2) {
                val firstArg = args[0]
                if (firstArg is AtNode ||
                    (firstArg is OpApplNode && firstArg.operator?.name?.toString() == OP_FCN_APPLY)) {
                    val n = nodeToTlaString(args[1])
                    return "$varName[$key] - $n"
                }
            }
            if ((opName == "\\cup" || opName == "\\union") && args.size >= 2) {
                val firstArg = args[0]
                if (firstArg is AtNode ||
                    (firstArg is OpApplNode && firstArg.operator?.name?.toString() == OP_FCN_APPLY)) {
                    val elem = extractSingleSetElement(args[1])
                    if (elem != null) return "$varName[$key]!!.add($elem)"
                }
            }
            if (opName == "\\" && args.size >= 2) {
                val firstArg = args[0]
                if (firstArg is AtNode ||
                    (firstArg is OpApplNode && firstArg.operator?.name?.toString() == OP_FCN_APPLY)) {
                    val elem = extractSingleSetElement(args[1])
                    if (elem != null) return "$varName[$key]!!.remove($elem)"
                }
            }
        }

        return nodeToTlaString(valueNode)
    }

    private fun translateExceptValueStr(varName: String, key: String, value: String): String {
        return when {
            value.matches(Regex("""@\s*\+\s*\d+""")) -> {
                val n = Regex("""\+\s*(\d+)""").find(value)!!.groupValues[1]
                "$varName[$key] + $n"
            }
            value.matches(Regex("""@\s*-\s*\d+""")) -> {
                val n = Regex("""-\s*(\d+)""").find(value)!!.groupValues[1]
                "$varName[$key] - $n"
            }
            value == "@" -> "$varName[$key]"
            else -> value
        }
    }

    private fun extractSingleSetElement(node: ExprOrOpArgNode?): String? {
        if (node == null) return null
        if (node is OpApplNode) {
            val opName = node.operator?.name?.toString()
            if (opName == OP_SET_ENUM) {
                val args = node.args
                if (args != null && args.size == 1) return nodeToTlaString(args[0])
            }
        }
        return null
    }

    // ─── PRECONDITION EXTRACTION ─────────────────────────────────────────

    private fun extractPrecondition(body: ExprNode): PredicateExpr {
        val conditions = mutableListOf<PredicateExpr>()

        forEachConjunct(body) { conjunct ->
            if (isGuardConjunct(conjunct)) {
                val pred = nodeToPredicate(conjunct)
                if (pred != null) conditions.add(pred)
            }
        }

        return when {
            conditions.isEmpty() -> PredicateExpr.True
            conditions.size == 1 -> conditions.first()
            else -> conditions.reduce { acc, p -> PredicateExpr.And(acc, p) }
        }
    }

    private fun extractBranchPrecondition(node: ExprOrOpArgNode?): PredicateExpr {
        if (node == null) return PredicateExpr.True
        if (node is ExprNode) return extractPrecondition(node)
        return PredicateExpr.True
    }

    private fun isGuardConjunct(node: ExprOrOpArgNode?): Boolean {
        if (node == null) return false
        if (node is LetInNode) return false
        if (node is OpApplNode) {
            val opName = node.operator?.name?.toString() ?: return false
            if (opName == "UNCHANGED") return false
            if (opName == "=") {
                val args = node.args ?: return false
                if (args.size >= 2 && isPrimedVar(args[0])) return false
            }
            if (containsPrime(node)) return false
            if (opName == OP_BOUNDED_EXISTS) return false
        }
        return true
    }

    private fun containsPrime(node: ExprOrOpArgNode?): Boolean {
        if (node == null) return false
        if (node is OpApplNode) {
            if (node.operator?.name?.toString() == "'") return true
            if (node.operator?.name?.toString() == "UNCHANGED") return true
            return node.args?.any { containsPrime(it) } ?: false
        }
        return false
    }

    private fun nodeToPredicate(node: ExprOrOpArgNode?): PredicateExpr? {
        if (node == null) return null
        if (node !is OpApplNode) return null

        val opName = node.operator?.name?.toString() ?: return null
        val args = node.args ?: emptyArray()

        return when (opName) {
            ">", ">=", "\\geq", "<", "<=", "\\leq", "=" -> {
                if (args.size >= 2) {
                    val op = when (opName) {
                        ">" -> CompOp.GT; ">=", "\\geq" -> CompOp.GE
                        "<" -> CompOp.LT; "<=", "\\leq" -> CompOp.LE
                        else -> CompOp.EQ
                    }
                    PredicateExpr.Comparison(nodeToTlaString(args[0]), op, nodeToTlaString(args[1]))
                } else null
            }

            "#", "/=" -> {
                if (args.size >= 2) PredicateExpr.Comparison(nodeToTlaString(args[0]), CompOp.NEQ, nodeToTlaString(args[1]))
                else null
            }

            "\\in" -> {
                if (args.size >= 2) PredicateExpr.Membership(nodeToTlaString(args[0]), nodeToTlaString(args[1]))
                else null
            }

            "\\notin" -> {
                if (args.size >= 2) PredicateExpr.Membership(nodeToTlaString(args[0]), nodeToTlaString(args[1]), negated = true)
                else null
            }

            "/\\", OP_CONJ_LIST -> {
                val preds = args.mapNotNull { nodeToPredicate(it) }
                when {
                    preds.isEmpty() -> PredicateExpr.True
                    preds.size == 1 -> preds.first()
                    else -> preds.reduce { a, b -> PredicateExpr.And(a, b) }
                }
            }

            "\\/", OP_DISJ_LIST -> {
                val preds = args.mapNotNull { nodeToPredicate(it) }
                when {
                    preds.isEmpty() -> PredicateExpr.False
                    preds.size == 1 -> preds.first()
                    else -> preds.reduce { a, b -> PredicateExpr.Or(a, b) }
                }
            }

            "\\lnot", "\\neg", "~" -> {
                if (args.isNotEmpty()) {
                    val inner = nodeToPredicate(args[0]) ?: return null
                    PredicateExpr.Not(inner)
                } else null
            }

            OP_BOUNDED_FORALL -> {
                val quantSymbols = node.bdedQuantSymbolLists ?: return null
                val quantBounds = node.bdedQuantBounds ?: return null
                if (quantSymbols.isNotEmpty() && quantBounds.isNotEmpty() && args.isNotEmpty()) {
                    val body = nodeToPredicate(args[0]) ?: PredicateExpr.Custom(nodeToTlaString(args[0]))
                    var result = body
                    for (i in quantSymbols.indices.reversed()) {
                        if (i < quantBounds.size) {
                            val symbols = quantSymbols[i] ?: continue
                            val domain = nodeToTlaString(quantBounds[i])
                            for (sym in symbols.reversed()) {
                                result = PredicateExpr.ForAll(sym.name.toString(), domain, result)
                            }
                        }
                    }
                    result
                } else null
            }

            OP_BOUNDED_EXISTS -> {
                val quantSymbols = node.bdedQuantSymbolLists ?: return null
                val quantBounds = node.bdedQuantBounds ?: return null
                if (quantSymbols.isNotEmpty() && quantBounds.isNotEmpty() && args.isNotEmpty()) {
                    val body = nodeToPredicate(args[0]) ?: PredicateExpr.Custom(nodeToTlaString(args[0]))
                    var result = body
                    for (i in quantSymbols.indices.reversed()) {
                        if (i < quantBounds.size) {
                            val symbols = quantSymbols[i] ?: continue
                            val domain = nodeToTlaString(quantBounds[i])
                            for (sym in symbols.reversed()) {
                                result = PredicateExpr.Exists(sym.name.toString(), domain, result)
                            }
                        }
                    }
                    result
                } else null
            }

            "=>" -> {
                if (args.size >= 2) {
                    val ante = nodeToPredicate(args[0]) ?: return null
                    val cons = nodeToPredicate(args[1]) ?: return null
                    PredicateExpr.Or(PredicateExpr.Not(ante), cons)
                } else null
            }

            "\\subseteq" -> {
                if (args.size >= 2) {
                    val left = nodeToTlaString(args[0])
                    val right = nodeToTlaString(args[1])
                    PredicateExpr.Custom("$left \\subseteq $right", "$right.containsAll($left)")
                } else null
            }

            else -> PredicateExpr.Custom(nodeToTlaString(node))
        }
    }

    // ─── RETURN VALUE EXTRACTION ─────────────────────────────────────────

    private fun extractReturnValue(node: ExprOrOpArgNode?): String {
        if (node == null) return "ok"
        val tlaStr = nodeToTlaString(node)
        val resultPattern = Regex("""(?:result|threadResult)'\s*=.*?"(\w+)"""")
        resultPattern.find(tlaStr)?.let { return it.groupValues[1] }
        return "ok"
    }

    // ─── INVARIANTS ──────────────────────────────────────────────────────

    private fun extractInvariants(
        opDefMap: Map<String, OpDefNode>,
        actionNames: Set<String>,
        variableNames: Set<String>
    ): List<InvariantSpec> {
        val invariants = mutableListOf<InvariantSpec>()

        for ((name, def) in opDefMap) {
            if (name in EXCLUDED_DEFINITIONS || name in actionNames) continue
            val body = def.body ?: continue
            if (containsPrime(body)) continue

            val tlaStr = nodeToTlaString(body)
            if (tlaStr.contains("[]") && tlaStr.contains("_")) continue
            if (!looksLikePredicate(body, variableNames)) continue

            val predicate = nodeToPredicate(body) ?: PredicateExpr.Custom(tlaStr)

            invariants.add(InvariantSpec(
                name = name,
                predicate = predicate,
                description = tlaStr.lines().first().trim().take(80),
                rawTla = tlaStr
            ))
        }

        return invariants
    }

    private fun looksLikePredicate(node: ExprOrOpArgNode, variableNames: Set<String>): Boolean {
        val tlaStr = nodeToTlaString(node)
        return variableNames.any { tlaStr.contains(it) } ||
            tlaStr.contains(">=") || tlaStr.contains("<=") ||
            tlaStr.contains(">") || tlaStr.contains("<") ||
            tlaStr.contains("\\A") || tlaStr.contains("\\E")
    }

    // ─── LIVENESS ────────────────────────────────────────────────────────

    private fun extractLiveness(opDefMap: Map<String, OpDefNode>): List<LivenessSpec> {
        val liveness = mutableListOf<LivenessSpec>()

        for ((name, def) in opDefMap) {
            if (name in EXCLUDED_DEFINITIONS) continue
            val body = def.body ?: continue
            val tlaStr = nodeToTlaString(body)

            if (tlaStr.contains("WF_") || tlaStr.contains("SF_") ||
                tlaStr.contains("<>") || tlaStr.contains("~>")) {

                val wfPattern = Regex("""WF_\w*\(([^)]+)\)""")
                val sfPattern = Regex("""SF_\w*\(([^)]+)\)""")

                wfPattern.findAll(tlaStr).forEach {
                    liveness.add(LivenessSpec(
                        name = "WF(${it.groupValues[1].trim()})",
                        formula = it.value,
                        description = "Weak fairness: ${it.groupValues[1].trim()}"
                    ))
                }
                sfPattern.findAll(tlaStr).forEach {
                    liveness.add(LivenessSpec(
                        name = "SF(${it.groupValues[1].trim()})",
                        formula = it.value,
                        description = "Strong fairness: ${it.groupValues[1].trim()}"
                    ))
                }

                if (liveness.isEmpty() || (name != "Spec" && name != "Fairness")) {
                    if (liveness.none { it.name == name }) {
                        liveness.add(LivenessSpec(name = name, formula = tlaStr))
                    }
                }
            }
        }

        return liveness
    }

    // ─── AST NODE → STRING ───────────────────────────────────────────────

    private fun nodeToName(node: ExprOrOpArgNode?): String? {
        if (node == null) return null
        if (node is OpApplNode) {
            val op = node.operator
            if (op != null && (node.args == null || node.args.isEmpty())) {
                return op.name?.toString()
            }
        }
        return null
    }

    private fun nodeToInt(node: ExprOrOpArgNode?): Int? {
        if (node is NumeralNode) return node.`val`()
        return null
    }

    internal fun nodeToTlaString(node: ExprOrOpArgNode?): String {
        if (node == null) return ""

        return when (node) {
            is NumeralNode -> node.`val`().toString()
            is StringNode -> "\"${node.rep}\""
            is LetInNode -> {
                val lets = node.lets.joinToString("\n") { letDef ->
                    "LET ${letDef.name} == ${nodeToTlaString(letDef.body)}"
                }
                "$lets\nIN ${nodeToTlaString(node.body)}"
            }
            is OpApplNode -> opApplToTlaString(node)
            is AtNode -> "@"
            else -> nodeToName(node) ?: node.toString()
        }
    }

    private fun opApplToTlaString(node: OpApplNode): String {
        val opName = node.operator?.name?.toString() ?: return "?"
        val args = node.args ?: emptyArray()

        return when (opName) {
            OP_CONJ_LIST -> args.joinToString(" /\\ ") { nodeToTlaString(it) }
            OP_DISJ_LIST -> args.joinToString(" \\/ ") { nodeToTlaString(it) }

            "'" -> "${nodeToTlaString(args.getOrNull(0))}'"

            "+", "-", "*", "\\div", "%", ">", ">=", "\\geq", "<", "<=", "\\leq", "=", "#", "/=" -> {
                val displayOp = when (opName) {
                    "\\geq" -> ">="
                    "\\leq" -> "<="
                    else -> opName
                }
                if (args.size >= 2) "${nodeToTlaString(args[0])} $displayOp ${nodeToTlaString(args[1])}"
                else displayOp
            }

            "/\\", "\\/" -> {
                if (args.size >= 2) "${nodeToTlaString(args[0])} $opName ${nodeToTlaString(args[1])}"
                else opName
            }

            "\\lnot", "\\neg", "~" -> "~(${nodeToTlaString(args.getOrNull(0))})"

            "=>" -> "${nodeToTlaString(args.getOrNull(0))} => ${nodeToTlaString(args.getOrNull(1))}"

            "\\in" -> "${nodeToTlaString(args.getOrNull(0))} \\in ${nodeToTlaString(args.getOrNull(1))}"
            "\\notin" -> "${nodeToTlaString(args.getOrNull(0))} \\notin ${nodeToTlaString(args.getOrNull(1))}"
            "\\cup", "\\union" -> "${nodeToTlaString(args.getOrNull(0))} \\cup ${nodeToTlaString(args.getOrNull(1))}"
            "\\" -> "${nodeToTlaString(args.getOrNull(0))} \\ ${nodeToTlaString(args.getOrNull(1))}"
            "\\cap", "\\intersect" -> "${nodeToTlaString(args.getOrNull(0))} \\cap ${nodeToTlaString(args.getOrNull(1))}"
            "\\subseteq" -> "${nodeToTlaString(args.getOrNull(0))} \\subseteq ${nodeToTlaString(args.getOrNull(1))}"
            "SUBSET" -> "SUBSET ${nodeToTlaString(args.getOrNull(0))}"
            "UNION" -> "UNION ${nodeToTlaString(args.getOrNull(0))}"

            OP_SET_ENUM -> "{${args.joinToString(", ") { nodeToTlaString(it) }}}"
            OP_TUPLE -> "<<${args.joinToString(", ") { nodeToTlaString(it) }}>>"

            ".." -> "${nodeToTlaString(args.getOrNull(0))}..${nodeToTlaString(args.getOrNull(1))}"

            OP_FCN_APPLY -> {
                if (args.size >= 2) "${nodeToTlaString(args[0])}[${nodeToTlaString(args[1])}]"
                else opName
            }

            OP_EXCEPT -> {
                if (args.size >= 2) {
                    val base = nodeToTlaString(args[0])
                    val updates = args.drop(1).joinToString(", ") { nodeToTlaString(it) }
                    "[$base EXCEPT $updates]"
                } else opName
            }

            OP_PAIR -> {
                if (args.size >= 2) "![${nodeToTlaString(args[0])}] = ${nodeToTlaString(args[1])}"
                else opName
            }

            OP_SEQ -> {
                // $Seq wraps EXCEPT path components — render as the inner content
                args.joinToString(", ") { nodeToTlaString(it) }
            }

            OP_FCN_CONSTRUCTOR -> {
                val quantSymbols = node.bdedQuantSymbolLists ?: emptyArray()
                val quantBounds = node.bdedQuantBounds ?: emptyArray()
                if (quantSymbols.isNotEmpty() && quantBounds.isNotEmpty() && args.isNotEmpty()) {
                    val params = flattenQuantifiers(quantSymbols, quantBounds)
                    "[${params.joinToString(", ")} |-> ${nodeToTlaString(args[0])}]"
                } else opName
            }

            OP_FCN_SET -> {
                if (args.size >= 2) "[${nodeToTlaString(args[0])} -> ${nodeToTlaString(args[1])}]"
                else opName
            }

            OP_BOUNDED_FORALL -> {
                val quantSymbols = node.bdedQuantSymbolLists ?: emptyArray()
                val quantBounds = node.bdedQuantBounds ?: emptyArray()
                val params = flattenQuantifiers(quantSymbols, quantBounds)
                val bodyStr = if (args.isNotEmpty()) nodeToTlaString(args[0]) else "?"
                "\\A ${params.joinToString(", ")} : $bodyStr"
            }

            OP_BOUNDED_EXISTS -> {
                val quantSymbols = node.bdedQuantSymbolLists ?: emptyArray()
                val quantBounds = node.bdedQuantBounds ?: emptyArray()
                val params = flattenQuantifiers(quantSymbols, quantBounds)
                val bodyStr = if (args.isNotEmpty()) nodeToTlaString(args[0]) else "?"
                "\\E ${params.joinToString(", ")} : $bodyStr"
            }

            OP_SET_OF_ALL -> {
                val quantSymbols = node.bdedQuantSymbolLists ?: emptyArray()
                val quantBounds = node.bdedQuantBounds ?: emptyArray()
                val params = flattenQuantifiers(quantSymbols, quantBounds)
                val bodyStr = if (args.isNotEmpty()) nodeToTlaString(args[0]) else "?"
                "{$bodyStr : ${params.joinToString(", ")}}"
            }

            OP_SUBSET_OF -> {
                val quantSymbols = node.bdedQuantSymbolLists ?: emptyArray()
                val quantBounds = node.bdedQuantBounds ?: emptyArray()
                val params = flattenQuantifiers(quantSymbols, quantBounds)
                val bodyStr = if (args.isNotEmpty()) nodeToTlaString(args[0]) else "?"
                "{${params.joinToString(", ")} : $bodyStr}"
            }

            OP_IF_THEN_ELSE -> {
                if (args.size >= 3) "IF ${nodeToTlaString(args[0])} THEN ${nodeToTlaString(args[1])} ELSE ${nodeToTlaString(args[2])}"
                else opName
            }

            OP_CASE -> "CASE ..."

            "UNCHANGED" -> "UNCHANGED ${nodeToTlaString(args.getOrNull(0))}"

            OP_TEMPORAL_ALWAYS -> "[][${nodeToTlaString(args.getOrNull(0))}]_${nodeToTlaString(args.getOrNull(1))}"
            OP_WF -> "WF_${nodeToTlaString(args.getOrNull(1))}(${nodeToTlaString(args.getOrNull(0))})"
            OP_SF -> "SF_${nodeToTlaString(args.getOrNull(1))}(${nodeToTlaString(args.getOrNull(0))})"

            "Append" -> "Append(${args.joinToString(", ") { nodeToTlaString(it) }})"
            "Head" -> "Head(${nodeToTlaString(args.getOrNull(0))})"
            "Tail" -> "Tail(${nodeToTlaString(args.getOrNull(0))})"
            "Len" -> "Len(${nodeToTlaString(args.getOrNull(0))})"
            "Cardinality" -> "Cardinality(${nodeToTlaString(args.getOrNull(0))})"
            "Seq" -> "Seq(${nodeToTlaString(args.getOrNull(0))})"
            "DOMAIN" -> "DOMAIN ${nodeToTlaString(args.getOrNull(0))}"
            "\\X" -> args.joinToString(" \\X ") { nodeToTlaString(it) }

            else -> {
                if (args.isEmpty()) opName
                else "$opName(${args.joinToString(", ") { nodeToTlaString(it) }})"
            }
        }
    }

    // ─── HELPERS ─────────────────────────────────────────────────────────

    private fun flattenQuantifiers(
        quantSymbols: Array<Array<FormalParamNode>?>,
        quantBounds: Array<ExprNode?>
    ): List<String> {
        return quantSymbols.flatMapIndexed { i, symbols ->
            symbols?.map { sym ->
                "${sym.name} \\in ${if (i < quantBounds.size) nodeToTlaString(quantBounds[i]) else "?"}"
            } ?: emptyList()
        }
    }

    private fun forEachConjunct(node: ExprOrOpArgNode?, action: (ExprOrOpArgNode) -> Unit) {
        if (node == null) return
        if (node is OpApplNode) {
            val opName = node.operator?.name?.toString()
            if (opName == "/\\" || opName == OP_CONJ_LIST) {
                val args = node.args ?: return
                for (arg in args) { if (arg != null) action(arg) }
                return
            }
        }
        action(node)
    }

    private fun tlaExprToKotlin(expr: String): String = when {
        expr == "TRUE" -> "true"
        expr == "FALSE" -> "false"
        expr == "<<>>" -> "mutableListOf()"
        expr == "{}" -> "mutableSetOf()"
        expr.matches(Regex("""-?\d+""")) -> expr
        expr.startsWith("[") && expr.contains("|->") -> "mutableMapOf()"
        else -> expr
    }

    private fun translateLetBinding(name: String, expr: String): String {
        var kotlinExpr = expr
        kotlinExpr = kotlinExpr.replace(Regex("""Head\((\w+)\)""")) { "${it.groupValues[1]}.first()" }
        kotlinExpr = kotlinExpr.replace(Regex("""Tail\((\w+)\)""")) { "${it.groupValues[1]}.drop(1).toMutableList()" }
        kotlinExpr = kotlinExpr.replace(Regex("""Len\((\w+)\)""")) { "${it.groupValues[1]}.size" }
        return "val $name = $kotlinExpr"
    }

    private fun translateTupleExpr(expr: String): String {
        val trimmed = expr.trim()
        if (trimmed.startsWith("<<") && trimmed.endsWith(">>")) {
            val inner = trimmed.removePrefix("<<").removeSuffix(">>").trim()
            return "listOf($inner)"
        }
        return trimmed
    }

    private fun errorResult(message: String, errors: List<ParseError>): ParseResult {
        return ParseResult(
            spec = ConcurrentSystemSpec(
                name = "",
                variables = emptyList(),
                constants = emptyList(),
                init = InitPredicate(emptyList()),
                actions = emptyList(),
                invariants = emptyList()
            ),
            errors = errors.ifEmpty { listOf(ParseError(message)) }
        )
    }

    companion object {
        // SANY internal operator names (prefixed with $ in JVM)
        private const val OP_CONJ_LIST = "\$ConjList"
        private const val OP_DISJ_LIST = "\$DisjList"
        private const val OP_BOUNDED_EXISTS = "\$BoundedExists"
        private const val OP_BOUNDED_FORALL = "\$BoundedForall"
        private const val OP_SET_ENUM = "\$SetEnumerate"
        private const val OP_TUPLE = "\$Tuple"
        private const val OP_FCN_APPLY = "\$FcnApply"
        private const val OP_EXCEPT = "\$Except"
        private const val OP_PAIR = "\$Pair"
        private const val OP_FCN_CONSTRUCTOR = "\$FcnConstructor"
        private const val OP_FCN_SET = "\$FcnSet"
        private const val OP_SET_OF_FCNS = "\$SetOfFcns"
        private const val OP_SEQ = "\$Seq"
        private const val OP_SET_OF_ALL = "\$SetOfAll"
        private const val OP_SUBSET_OF = "\$SubsetOf"
        private const val OP_IF_THEN_ELSE = "\$IfThenElse"
        private const val OP_CASE = "\$Case"
        private const val OP_TEMPORAL_ALWAYS = "\$TemporalWhile"
        private const val OP_WF = "\$WF"
        private const val OP_SF = "\$SF"

        private val EXCLUDED_DEFINITIONS = setOf(
            "Init", "Next", "Spec", "TypeOK", "Fairness",
            "vars", "RECURSIVE", "States"
        )

        private val SYSTEM_ACTION_NAMES = setOf(
            "Crash", "Recover", "Tick", "GarbageCollect", "ThreadReset"
        )
    }
}
