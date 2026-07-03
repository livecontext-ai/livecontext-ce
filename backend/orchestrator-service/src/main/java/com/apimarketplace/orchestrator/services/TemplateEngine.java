package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
import com.apimarketplace.orchestrator.services.expression.JsonOutputUtil;
import com.apimarketplace.orchestrator.services.expression.JsonParseException;
import com.apimarketplace.orchestrator.services.interfaces.TemplateResolver;
import com.apimarketplace.orchestrator.services.template.NamespaceResolver;
import com.apimarketplace.orchestrator.services.template.PathNavigator;
import com.apimarketplace.orchestrator.services.template.SpelEvaluator;
import com.apimarketplace.orchestrator.services.template.SpelProtectedRegions;
import com.apimarketplace.orchestrator.services.template.VarsSyntaxNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.apimarketplace.orchestrator.utils.ConcurrentLruCache;

/**
 * Unified Template Engine using {{...}} syntax with SpEL (Spring Expression Language)
 *
 * === UNIFIED EXPRESSION PATTERN ===
 *
 * ALL node types use the same pattern: {{type:label.output.field}}
 *
 * | Prefix     | Pattern                        | Example                                    |
 * |------------|--------------------------------|---------------------------------------------|
 * | trigger:   | {{trigger:label.output.field}} | {{trigger:webhook.output.user_id}}          |
 * | mcp:       | {{mcp:label.output.field}}     | {{mcp:api_call.output.data}}                |
 * | agent:     | {{agent:label.output.field}}   | {{agent:assistant.output.response}}         |
 * | core:      | {{core:label.output.field}}    | {{core:decision.output.selected_branch}}    |
 *
 * Core Node Specific Outputs:
 * - Decision: {{core:check.output.selected_branch}}, {{core:check.output.evaluations}}
 * - Switch: {{core:router.output.switch_value}}, {{core:router.output.selected_case}}
 * - Loop: {{core:process.output.iteration}}, {{core:process.output.max_iterations}}
 * - Split: {{core:batch.output.current_item}}, {{core:batch.output.current_index}}
 * - Merge: {{core:wait_all.output.merged_data}}
 * - Fork: {{core:parallel.output.branches_count}}
 *
 * Supports SpEL operations for conditions and complex evaluations:
 * - Arithmetic: +, -, *, /, %
 * - Comparison: ==, !=, <, >, <=, >=
 * - Logical: &&, ||, !
 * - Ternary: condition ? trueValue : falseValue
 * - Custom functions: int(), double(), string(), bool(), size(), len(), typeof(), abs(), round(), formatcurrency(), etc.
 *
 * Note: Function names avoid conflicts with common variable names (e.g., "typeof" not "type", "formatcurrency" not "currency")
 */
@Service
public class TemplateEngine implements TemplateResolver {

    private static final Logger logger = LoggerFactory.getLogger(TemplateEngine.class);

    // Unified {{...}} expression pattern.
    // Accepts any chars (non-greedy) inside the braces, but treats single-quoted SpEL string
    // literals as opaque so `}` and `|` characters inside them don't break the match.
    // This allows expressions like {{json('{"a":1}')}} or {{json('[{"x":1}]')}} where the
    // SpEL argument is a JSON literal containing braces/pipes.
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("\\{\\{((?:'(?:[^'\\\\]|\\\\.)*'|[^}|])+?(?:\\|[^}]*)?)\\}\\}");

    // Pattern to extract variable identifiers from expressions (including namespace:variable.path format)
    // Supports array access: mcp:step.output.data[0].embedding
    private static final Pattern VARIABLE_IDENTIFIER_PATTERN = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*(?::[a-zA-Z_][a-zA-Z0-9_]*)?(?:\\[\\d+]|\\.[a-zA-Z_][a-zA-Z0-9_]*)*)(?![\\w:])");

    private final ConcurrentLruCache<String, String> templateCache;
    private final TypeCastingService typeCastingService;
    private final NamespaceResolver namespaceResolver;
    private final PathNavigator pathNavigator;
    private final SpelEvaluator spelEvaluator;

    public TemplateEngine(TypeCastingService typeCastingService,
                          NamespaceResolver namespaceResolver,
                          PathNavigator pathNavigator,
                          SpelEvaluator spelEvaluator) {
        this.typeCastingService = typeCastingService;
        this.namespaceResolver = namespaceResolver;
        this.pathNavigator = pathNavigator;
        this.spelEvaluator = spelEvaluator;
        this.templateCache = new ConcurrentLruCache<>(10_000);
        logger.info("TemplateEngine initialized with unified {{}} syntax and SpEL");
    }

    /**
     * Evaluate a template with the execution context.
     * Resolves all {{...}} expressions.
     *
     * - Pure expression "{{...}}": returns the evaluated object (Number, List, Boolean, etc.)
     * - Template with text "Hello {{name}}": returns a String
     */
    public Object evaluateTemplate(String template, WorkflowExecutionContext context) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        logger.debug("Evaluating template: {}", template);

        // Check if this is a pure expression (only {{...}} with no text outside)
        String trimmed = template.trim();
        if (isPureExpression(trimmed)) {
            String innerExpression = trimmed.substring(2, trimmed.length() - 2).trim();
            Object result = evaluateExpressionWithContext(innerExpression, context);
            logger.debug("Pure expression '{}' evaluated to: {} (type: {})",
                innerExpression, result, result != null ? result.getClass().getSimpleName() : "null");
            return result;
        }

        // Template with text outside ${...} - return string
        String resolvedTemplate = resolveExpressions(template, context);
        logger.debug("Template '{}' resolved to '{}'", template, resolvedTemplate);
        return resolvedTemplate;
    }

    /**
     * Check if the template is a pure expression (only {{...}} with no text outside)
     */
    private boolean isPureExpression(String template) {
        return template.startsWith("{{") && template.endsWith("}}") &&
               template.indexOf("{{", 2) == -1; // No nested {{
    }

    /**
     * Evaluate a condition expression and return boolean result.
     */
    public boolean evaluateCondition(String condition, WorkflowExecutionContext context) {
        ConditionEvaluationResult result = evaluateConditionWithDetails(condition, context);
        return result.result();
    }

    /**
     * Evaluate a condition with detailed result for debugging.
     * Supports both:
     * - Pure expression: {{score > 100}}
     * - Mixed: {{score}} > 100 (resolves {{score}} then evaluates)
     */
    public ConditionEvaluationResult evaluateConditionWithDetails(String condition, WorkflowExecutionContext context) {
        if (condition == null || condition.isEmpty()) {
            logger.debug("Empty or null condition");
            return new ConditionEvaluationResult(condition, condition, false, null);
        }

        logger.debug("Evaluating condition: {}", condition);

        try {
            String expression = condition.trim();

            // Case 1: Pure expression {{...}} - extract and evaluate
            if (isPureExpression(expression)) {
                String innerExpression = expression.substring(2, expression.length() - 2).trim();
                Map<String, Object> variables = new LinkedHashMap<>();
                TransformResult transform = transformToSpelExpressionWithHumanReadable(innerExpression, context, variables);
                Object raw = evaluateExpressionWithContext(innerExpression, context);
                return buildConditionResult(condition, transform.humanReadable(), raw);
            }

            // Case 2: Contains {{...}} blocks - resolve each block first, then evaluate
            if (expression.contains("{{")) {
                Map<String, Object> variables = new LinkedHashMap<>();
                ResolveConditionResult resolved = resolveConditionBlocksWithHumanReadable(expression, context, variables);

                logger.debug("Condition SpEL: {} with variables: {}", resolved.spelExpression(), variables.keySet());

                // Evaluate the complete expression
                StandardEvaluationContext evalContext = spelEvaluator.createEvaluationContext(variables);

                Expression exp = org.springframework.expression.spel.standard.SpelExpressionParser
                    .class.cast(new org.springframework.expression.spel.standard.SpelExpressionParser())
                    .parseExpression(resolved.spelExpression());
                Object raw = exp.getValue(evalContext);
                return buildConditionResult(condition, resolved.humanReadable(), raw);
            }

            // Case 3: Raw expression without {{}} - evaluate directly
            Map<String, Object> variables = new LinkedHashMap<>();
            TransformResult transform = transformToSpelExpressionWithHumanReadable(expression, context, variables);
            Object raw = evaluateExpressionWithContext(expression, context);
            return buildConditionResult(condition, transform.humanReadable(), raw);

        } catch (JsonParseException jpe) {
            // Conditions that embed json('...') must surface the typed error too - same
            // contract as input-mapping. Don't silently fall back to "false" with a Jackson
            // message in the result, that masks the bug exactly like the original swallow.
            throw jpe;
        } catch (Exception e) {
            logger.error("Error evaluating condition '{}': {}", condition, e.getMessage(), e);
            return new ConditionEvaluationResult(condition, condition, false, e.getMessage());
        }
    }

    /**
     * Result of resolving condition blocks.
     */
    private record ResolveConditionResult(
        String spelExpression,
        String humanReadable
    ) {}

    /**
     * Resolve all {{...}} blocks in a condition, building both SpEL and human-readable versions.
     * Example: "{{score}} > 100" -> spelExpression="#score > 100", humanReadable="42 > 100"
     */
    private ResolveConditionResult resolveConditionBlocksWithHumanReadable(String condition,
                                                                            WorkflowExecutionContext context,
                                                                            Map<String, Object> variables) {
        Matcher matcher = EXPRESSION_PATTERN.matcher(condition);
        StringBuilder spelResult = new StringBuilder();
        StringBuilder humanResult = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            // Append text between matches
            String between = condition.substring(lastEnd, matcher.start());
            spelResult.append(between);
            humanResult.append(between);
            lastEnd = matcher.end();

            String innerExpression = matcher.group(1).trim();
            TransformResult transform = transformToSpelExpressionWithHumanReadable(innerExpression, context, variables);
            spelResult.append(transform.spelExpression());
            humanResult.append(transform.humanReadable());
        }

        // Append remaining text
        if (lastEnd < condition.length()) {
            String remaining = condition.substring(lastEnd);
            spelResult.append(remaining);
            humanResult.append(remaining);
        }

        return new ResolveConditionResult(spelResult.toString(), humanResult.toString());
    }

    /**
     * Build condition result with human-readable expression already computed.
     */
    private ConditionEvaluationResult buildConditionResult(String original, String humanReadable, Object raw) {
        boolean result = spelEvaluator.toBoolean(raw);

        String error = null;
        if (raw == null) {
            error = "expression_evaluated_to_null";
            result = false;
        }

        logger.debug("Condition '{}' evaluated to {} (raw={}, resolved={})", original, result, raw, humanReadable);
        String resolvedExpressionWithResult = humanReadable + " = " + result;
        return new ConditionEvaluationResult(original, resolvedExpressionWithResult, result, error);
    }

    /**
     * Format a value for display in the resolved expression.
     */
    private String formatValueForDisplay(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "'" + value + "'";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof List || value instanceof Map || value.getClass().isArray()) {
            return JsonOutputUtil.encode(value);
        }
        return String.valueOf(value);
    }

    /**
     * Condition evaluation result record.
     */
    public record ConditionEvaluationResult(
        String originalExpression,
        String resolvedExpression,
        boolean result,
        String errorMessage
    ) {}

    // ========================================
    // Expression Resolution
    // ========================================

    /**
     * Resolve all {{...}} expressions in the template.
     * Each {{...}} block is evaluated independently with SpEL.
     * Variables are passed to SpEL context (not converted to strings) to support complex objects.
     */
    private String resolveExpressions(String expression, WorkflowExecutionContext context) {
        if (!hasExpressions(expression) || context == null) {
            return expression;
        }

        Matcher matcher = EXPRESSION_PATTERN.matcher(expression);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String innerExpression = matcher.group(1).trim();

            // Evaluate the expression with SpEL, passing variables to context
            Object evaluated = evaluateExpressionWithContext(innerExpression, context);

            String replacement;
            if (evaluated == null) {
                replacement = "";
                logger.warn("Expression not resolved: {{}}", innerExpression);
            } else if (evaluated instanceof Map || evaluated instanceof java.util.Collection
                       || evaluated.getClass().isArray()) {
                // Map/List/array → JSON literal so embedding in a JSON template stays parseable.
                // Pre-fix produced Java toString ({a=1}); post-fix produces {"a":1}.
                replacement = JsonOutputUtil.encode(evaluated);
                logger.debug("Expression {{}} evaluated to JSON: {}", innerExpression, replacement);
            } else {
                replacement = String.valueOf(evaluated);
                logger.debug("Expression {{}} evaluated to: {}", innerExpression, replacement);
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Evaluate a single expression with SpEL.
     * Variables are extracted, resolved, and added to SpEL context directly.
     * This allows complex objects (List, Map) to be used in functions like size().
     */
    private Object evaluateExpressionWithContext(String expression, WorkflowExecutionContext context) {
        try {
            // Find all variable identifiers in the expression
            Map<String, Object> variables = new LinkedHashMap<>();
            String spelExpression = transformToSpelExpression(expression, context, variables);

            logger.debug("SpEL expression: {} with variables: {}", spelExpression, variables.keySet());

            // Build SpEL evaluation context
            StandardEvaluationContext evalContext = spelEvaluator.createEvaluationContext(variables);

            // Parse and evaluate
            return spelEvaluator.evaluate(spelExpression, evalContext);

        } catch (JsonParseException jpe) {
            // Let json() / fromjson() typed errors propagate so V2TemplateAdapter enriches
            // with the field name and the inspector can show a structured failure.
            throw jpe;
        } catch (Exception e) {
            logger.warn("SpEL evaluation error for '{}': {}", expression, e.getMessage());
            return null;
        }
    }

    /**
     * Result of transforming an expression to SpEL format.
     * Contains both the SpEL expression and the human-readable version with values substituted.
     */
    private record TransformResult(
        String spelExpression,      // e.g., "#user_id%2==0"
        String humanReadable        // e.g., "5%2==0"
    ) {}

    /**
     * Transform expression to SpEL format and build human-readable version in a single pass.
     * Both outputs are built simultaneously by tracking positions manually.
     */
    private TransformResult transformToSpelExpressionWithHumanReadable(String expression,
                                                                        WorkflowExecutionContext context,
                                                                        Map<String, Object> variables) {
        // $vars.name / vars:name -> vars.name BEFORE the token scan: the leading
        // '$' is not an identifier char and would otherwise leak into the SpEL
        // output as a literal, producing an unparseable expression.
        expression = VarsSyntaxNormalizer.normalize(expression);
        Matcher varMatcher = VARIABLE_IDENTIFIER_PATTERN.matcher(expression);
        StringBuilder spelResult = new StringBuilder();
        StringBuilder humanResult = new StringBuilder();
        int lastEnd = 0;

        // Protected regions: identifiers inside string literals or SpEL
        // selection/projection brackets refer to the current element (#this)
        // or to string content, not to outer variables - skip them.
        java.util.List<SpelProtectedRegions.Region> protectedRegions =
            SpelProtectedRegions.find(expression);

        while (varMatcher.find()) {
            int matchStart = varMatcher.start();
            int matchEnd = varMatcher.end();

            // Append text between matches to both buffers
            String between = expression.substring(lastEnd, matchStart);
            spelResult.append(between);
            humanResult.append(between);
            lastEnd = matchEnd;

            String identifier = varMatcher.group(1);

            // Inside a protected region (string literal, selection, projection):
            // leave the identifier verbatim in both outputs.
            if (SpelProtectedRegions.isProtected(matchStart, protectedRegions)) {
                spelResult.append(identifier);
                humanResult.append(identifier);
                continue;
            }

            // Property-access position (immediately preceded by '.'): leave as-is
            // so that method calls / property chains on a resolved base object
            // parse correctly ('.#x' is not valid SpEL, '.x' is).
            boolean isPropertyAccess = matchStart > 0 && expression.charAt(matchStart - 1) == '.';

            String spelReplacement;
            String humanReplacement;

            String identifierLower = identifier.toLowerCase();

            if (SpelEvaluator.RESERVED_WORDS.contains(identifierLower)) {
                spelReplacement = identifier;
                humanReplacement = identifier;
            } else if (isPropertyAccess) {
                spelReplacement = identifier;
                humanReplacement = identifier;
            } else if (SpelEvaluator.CUSTOM_FUNCTION_NAMES.contains(identifierLower)
                       && isFollowedByOpenParen(expression, matchEnd)) {
                spelReplacement = "#" + identifierLower;
                humanReplacement = identifierLower;
            } else {
                Object value = namespaceResolver.resolveVariable(identifier, context);

                if (value != null) {
                    String safeVarName = toSafeVarName(identifier);
                    variables.put(safeVarName, value);
                    spelReplacement = "#" + safeVarName;
                    humanReplacement = formatValueForDisplay(value);
                    logger.debug("Variable {} resolved to {}, added to context as #{}", identifier, value, safeVarName);
                } else if (identifier.contains(".") && isFollowedByOpenParen(expression, matchEnd)) {
                    // Method-call shape (e.g. items.size()): the full path may have
                    // resolved to null because the suffix is a method on a non-Map
                    // base (List.size(), String.length(), …). Try shorter prefixes.
                    // Gated on the following '(' so a plain property access that
                    // legitimately resolves to null still makes exactly one lookup.
                    SplitResolution split = trySplitResolution(identifier, context);
                    if (split != null) {
                        String safeBase = toSafeVarName(split.basePath());
                        variables.put(safeBase, split.baseValue());
                        spelReplacement = "#" + safeBase + "." + split.remainingPath();
                        humanReplacement = formatValueForDisplay(split.baseValue()) + "." + split.remainingPath();
                    } else {
                        String safeVarName = toSafeVarName(identifier);
                        variables.put(safeVarName, null);
                        spelReplacement = "#" + safeVarName;
                        humanReplacement = identifier;
                    }
                } else {
                    // Value is null - still sanitize the identifier for SpEL safety.
                    // Raw identifiers with ':' (e.g. mcp:step.output.field) would be
                    // parsed as a ternary operator by SpEL, causing EL1041E errors.
                    String safeVarName = toSafeVarName(identifier);
                    variables.put(safeVarName, null);
                    spelReplacement = "#" + safeVarName;
                    humanReplacement = identifier;
                }
            }

            spelResult.append(spelReplacement);
            humanResult.append(humanReplacement);
        }

        // Append remaining text after last match
        if (lastEnd < expression.length()) {
            String remaining = expression.substring(lastEnd);
            spelResult.append(remaining);
            humanResult.append(remaining);
        }

        return new TransformResult(spelResult.toString(), humanResult.toString());
    }

    private static String toSafeVarName(String identifier) {
        return identifier.replace(":", "_").replace(".", "_")
            .replace("[", "_").replace("]", "_");
    }

    private static boolean isFollowedByOpenParen(String expression, int fromIndex) {
        int i = fromIndex;
        while (i < expression.length() && Character.isWhitespace(expression.charAt(i))) {
            i++;
        }
        return i < expression.length() && expression.charAt(i) == '(';
    }

    private record SplitResolution(String basePath, Object baseValue, String remainingPath) {}

    /**
     * Try to split a dotted identifier into a resolvable base + remaining
     * suffix that SpEL will handle as method/property access.
     *
     * Example: {@code items.size} with items=[1,2,3] (a List, not a Map)
     *   → try "items.size" → null
     *   → try "items" → [1,2,3] ✓
     *   → SplitResolution("items", [1,2,3], "size")
     */
    private SplitResolution trySplitResolution(String identifier, WorkflowExecutionContext context) {
        int lastDot = identifier.lastIndexOf('.');
        while (lastDot > 0) {
            String basePath = identifier.substring(0, lastDot);
            String remaining = identifier.substring(lastDot + 1);

            Object baseValue = namespaceResolver.resolveVariable(basePath, context);
            if (baseValue != null && !(baseValue instanceof Map)) {
                return new SplitResolution(basePath, baseValue, remaining);
            }

            lastDot = identifier.lastIndexOf('.', lastDot - 1);
        }
        return null;
    }

    /**
     * Legacy method for compatibility - delegates to new method and returns only SpEL expression.
     */
    private String transformToSpelExpression(String expression, WorkflowExecutionContext context,
                                              Map<String, Object> variables) {
        return transformToSpelExpressionWithHumanReadable(expression, context, variables).spelExpression();
    }

    // ========================================
    // Utility Methods
    // ========================================

    /**
     * Check if expression contains {{...}} expressions.
     */
    private boolean hasExpressions(String expression) {
        return expression != null && expression.contains("{{");
    }

    /**
     * Evaluate step input template.
     */
    public Map<String, Object> evaluateStepInput(Map<String, Object> inputTemplate, WorkflowExecutionContext context) {
        if (inputTemplate == null || inputTemplate.isEmpty()) {
            return inputTemplate;
        }

        Map<String, Object> resolvedInput = new HashMap<>();

        for (Map.Entry<String, Object> entry : inputTemplate.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String) {
                resolvedInput.put(key, evaluateTemplate((String) value, context));
            } else {
                resolvedInput.put(key, value);
            }
        }

        return resolvedInput;
    }

    /**
     * Simple template resolution (without CEL).
     * Returns the template with {{...}} expressions resolved to their literal values.
     */
    public String resolveTemplatesSimple(String template, Map<String, Object> variables) {
        if (template == null || template.isEmpty() || !hasExpressions(template)) {
            return template;
        }

        Matcher matcher = EXPRESSION_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String variablePath = VarsSyntaxNormalizer.normalize(matcher.group(1).trim());
            Object value = pathNavigator.getVariableValueFromMap(variablePath, variables);

            if (value != null) {
                // Convert complex objects to JSON via shared encoder for consistency
                // with resolveExpressions / resolveExpressionsWithMap.
                if (value instanceof Map || value instanceof List) {
                    matcher.appendReplacement(result, Matcher.quoteReplacement(JsonOutputUtil.encode(value)));
                } else {
                    matcher.appendReplacement(result, Matcher.quoteReplacement(value.toString()));
                }
            } else {
                String unresolvedToken = "{{__UNRESOLVED__:" + variablePath + "}}";
                matcher.appendReplacement(result, Matcher.quoteReplacement(unresolvedToken));
                logger.warn("Variable not found in template: {}", variablePath);
            }
        }
        matcher.appendTail(result);

        return result.toString();
    }

    // ========================================
    // Map-based methods (for compatibility with code using raw Map<String, Object>)
    // These methods provide the same functionality as WorkflowExecutionContext-based methods
    // but accept a simple Map, useful for Cores (Loop, Decision) that build their own context
    // ========================================

    /**
     * Evaluate a condition expression with a Map context.
     * This is the Map-based equivalent of evaluateCondition(String, WorkflowExecutionContext).
     *
     * Supports:
     * - Pure expressions: {{score > 100}}
     * - Mixed expressions: {{core:label.output.iteration}} <= 5 (preferred) or {{iterations}} <= 5 (legacy)
     * - Raw expressions: iterations <= 5
     *
     * @param condition The condition string
     * @param context Map of variable values
     * @return boolean result of evaluation
     */
    public boolean evaluateConditionWithMap(String condition, Map<String, Object> context) {
        if (condition == null || condition.isEmpty()) {
            return false;
        }

        logger.debug("evaluateConditionWithMap: input={}", condition);

        try {
            String expression = condition.trim();

            // Case 1: Pure expression {{...}}
            if (isPureExpression(expression)) {
                String innerExpression = expression.substring(2, expression.length() - 2).trim();
                Object result = spelEvaluator.evaluateWithMap(innerExpression, context, pathNavigator);
                return spelEvaluator.toBoolean(result);
            }

            // Case 2: Mixed expression with {{...}} blocks
            if (expression.contains("{{")) {
                Map<String, Object> resolvedContext = new HashMap<>(context);
                StringBuilder spelResult = new StringBuilder();
                int lastEnd = 0;
                Matcher matcher = EXPRESSION_PATTERN.matcher(expression);

                while (matcher.find()) {
                    spelResult.append(expression, lastEnd, matcher.start());
                    lastEnd = matcher.end();

                    String innerExpression = matcher.group(1).trim();
                    String varName = "_resolved_" + lastEnd;
                    Object value = spelEvaluator.evaluateWithMap(innerExpression, context, pathNavigator);
                    resolvedContext.put(varName, value);
                    // Don't add # prefix here - evaluateWithMap adds it via transformExpressionForMap
                    spelResult.append(varName);
                }

                if (lastEnd < expression.length()) {
                    spelResult.append(expression.substring(lastEnd));
                }

                String fullSpelExpression = spelResult.toString();
                Object result = spelEvaluator.evaluateWithMap(fullSpelExpression, resolvedContext, pathNavigator);
                return spelEvaluator.toBoolean(result);
            }

            // Case 3: Raw expression without {{}}
            Object result = spelEvaluator.evaluateWithMap(expression, context, pathNavigator);
            return spelEvaluator.toBoolean(result);

        } catch (Exception e) {
            logger.error("evaluateConditionWithMap error: condition={}, error={}", condition, e.getMessage());
            return false;
        }
    }

    /**
     * Evaluate a condition with Map context and return detailed result including resolved expression.
     * This is useful for debugging and displaying how expressions were evaluated.
     *
     * Example:
     * - condition: "{{trigger:test.output.user_id%2==1}}"
     * - context: { "trigger:test": { "output": { "user_id": 5 } } }
     * - returns: ConditionEvaluationResult(
     *     originalExpression="{{trigger:test.output.user_id%2==1}}",
     *     resolvedExpression="5%2==1",
     *     result=true,
     *     errorMessage=null
     *   )
     *
     * @param condition The condition string with {{...}} expressions
     * @param context Map of variable values
     * @return ConditionEvaluationResult with original, resolved expression and result
     */
    public ConditionEvaluationResult evaluateConditionWithDetailsWithMap(String condition, Map<String, Object> context) {
        if (condition == null || condition.isEmpty()) {
            return new ConditionEvaluationResult(condition, condition, false, null);
        }

        logger.debug("evaluateConditionWithDetailsWithMap: input={}", condition);

        try {
            String expression = condition.trim();

            // Case 1: Pure expression {{...}}
            if (isPureExpression(expression)) {
                String innerExpression = expression.substring(2, expression.length() - 2).trim();
                String resolvedExpr = resolveExpressionToHumanReadableWithMap(innerExpression, context);
                Object result = spelEvaluator.evaluateWithMap(innerExpression, context, pathNavigator);
                boolean boolResult = spelEvaluator.toBoolean(result);
                return new ConditionEvaluationResult(condition, resolvedExpr, boolResult, null);
            }

            // Case 2: Mixed expression with {{...}} blocks
            if (expression.contains("{{")) {
                Map<String, Object> resolvedContext = new HashMap<>(context);
                StringBuilder spelResult = new StringBuilder();
                StringBuilder humanReadable = new StringBuilder();
                int lastEnd = 0;
                Matcher matcher = EXPRESSION_PATTERN.matcher(expression);

                while (matcher.find()) {
                    spelResult.append(expression, lastEnd, matcher.start());
                    humanReadable.append(expression, lastEnd, matcher.start());
                    lastEnd = matcher.end();

                    String innerExpression = matcher.group(1).trim();
                    String varName = "_resolved_" + lastEnd;
                    Object value = spelEvaluator.evaluateWithMap(innerExpression, context, pathNavigator);
                    resolvedContext.put(varName, value);
                    // Don't add # prefix here - evaluateWithMap adds it via transformExpressionForMap
                    spelResult.append(varName);
                    humanReadable.append(formatValueForDisplay(value));
                }

                if (lastEnd < expression.length()) {
                    spelResult.append(expression.substring(lastEnd));
                    humanReadable.append(expression.substring(lastEnd));
                }

                String fullSpelExpression = spelResult.toString();
                Object result = spelEvaluator.evaluateWithMap(fullSpelExpression, resolvedContext, pathNavigator);
                boolean boolResult = spelEvaluator.toBoolean(result);
                return new ConditionEvaluationResult(condition, humanReadable.toString(), boolResult, null);
            }

            // Case 3: Raw expression without {{}}
            String resolvedExpr = resolveExpressionToHumanReadableWithMap(expression, context);
            Object result = spelEvaluator.evaluateWithMap(expression, context, pathNavigator);
            boolean boolResult = spelEvaluator.toBoolean(result);
            return new ConditionEvaluationResult(condition, resolvedExpr, boolResult, null);

        } catch (Exception e) {
            logger.error("evaluateConditionWithDetailsWithMap error: condition={}, error={}", condition, e.getMessage());
            return new ConditionEvaluationResult(condition, condition, false, e.getMessage());
        }
    }

    /**
     * Resolve an expression to its human-readable form by substituting variable values.
     * Example: "trigger:test.output.user_id%2==1" with user_id=5 becomes "5%2==1"
     */
    private String resolveExpressionToHumanReadableWithMap(String expression, Map<String, Object> context) {
        // Find all variable references and replace with their values
        Matcher matcher = VARIABLE_IDENTIFIER_PATTERN.matcher(expression);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            result.append(expression, lastEnd, matcher.start());
            lastEnd = matcher.end();

            String varPath = matcher.group(1);

            // Try to resolve the variable
            Object value = spelEvaluator.evaluateWithMap(varPath, context, pathNavigator);
            if (value != null) {
                result.append(formatValueForDisplay(value));
            } else {
                // Keep original if not found
                result.append(varPath);
            }
        }

        if (lastEnd < expression.length()) {
            result.append(expression.substring(lastEnd));
        }

        return result.toString();
    }

    /**
     * Evaluate a template and return the result with Map context.
     * For pure expressions {{...}}, returns the typed result.
     * For mixed templates, returns a String.
     *
     * @param template The template string
     * @param context Map of variable values
     * @return The evaluated result (typed for pure expressions, String for mixed)
     */
    public Object evaluateTemplateWithMap(String template, Map<String, Object> context) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        String trimmed = template.trim();

        // Pure expression - return typed result
        if (isPureExpression(trimmed)) {
            String innerExpression = trimmed.substring(2, trimmed.length() - 2).trim();
            return spelEvaluator.evaluateWithMap(innerExpression, context, pathNavigator);
        }

        // Mixed template - return string
        return resolveExpressionsWithMap(template, context);
    }

    /**
     * Resolve all {{...}} expressions in a template with Map context.
     * Always returns a String with all expressions resolved.
     *
     * @param template The template string
     * @param context Map of variable values
     * @return The resolved string
     */
    public String resolveWithMap(String template, Map<String, Object> context) {
        if (template == null || template.isEmpty() || !template.contains("{{")) {
            return template;
        }

        return resolveExpressionsWithMap(template, context);
    }

    /**
     * Resolve expressions with Map context, internal method.
     */
    private String resolveExpressionsWithMap(String template, Map<String, Object> context) {
        Matcher matcher = EXPRESSION_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String innerExpression = matcher.group(1).trim();
            Object evaluated = spelEvaluator.evaluateWithMap(innerExpression, context, pathNavigator);

            String replacement;
            if (evaluated == null) {
                replacement = "";
            } else if (evaluated instanceof Map || evaluated instanceof java.util.Collection
                       || evaluated.getClass().isArray()) {
                // Mirror of resolveExpressions: encode Map/List/array as JSON, not Java toString.
                replacement = JsonOutputUtil.encode(evaluated);
            } else {
                replacement = String.valueOf(evaluated);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        return result.toString();
    }
}
