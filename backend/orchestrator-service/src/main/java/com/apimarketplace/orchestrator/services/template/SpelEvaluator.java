package com.apimarketplace.orchestrator.services.template;

import com.apimarketplace.orchestrator.services.expression.ExpressionFunctions;
import com.apimarketplace.orchestrator.services.expression.JsonParseException;
import com.apimarketplace.orchestrator.utils.ConcurrentLruCache;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.ConstructorResolver;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.spel.SpelEvaluationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.TypeLocator;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.ReflectiveMethodResolver;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for SpEL (Spring Expression Language) evaluation.
 *
 * Manages custom function registration and provides evaluation methods
 * for both WorkflowExecutionContext-based and Map-based evaluations.
 */
@Service
public class SpelEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(SpelEvaluator.class);

    // Reserved words that should not be treated as variables
    public static final Set<String> RESERVED_WORDS = Set.of(
        "true", "false", "null", "and", "or", "not", "eq", "ne", "lt", "gt", "le", "ge",
        "instanceof", "matches", "between", "T", "new"
    );

    // Set of supported custom function names (lowercase)
    // Note: Function names are chosen to avoid conflicts with common variable names
    // (e.g., "formatcurrency" instead of "currency", "typeof" instead of "type")
    public static final Set<String> CUSTOM_FUNCTION_NAMES = Set.of(
        "int", "double", "string", "bool", "long", "float",
        "size", "len", "typeof", "default", "coalesce", "ifempty", "isnull", "isempty",
        "abs", "round", "floor", "ceil", "min", "max", "pow", "sqrt",
        "uppercase", "lowercase", "capitalize", "trim", "truncate",
        "padleft", "padright", "replace", "substring", "split", "join",
        "startswith", "endswith", "contains", "matches", "length",
        "formatdate", "formatnumber", "formatcurrency", "now", "today",
        "json", "fromjson", "tojson"
    );

    // Bounded cache for parsed SpEL expressions. Unbounded was a latent memory leak
    // for workflows that produce high-cardinality dynamic expression strings.
    // Size 10_000 mirrors TemplateEngine.templateCache.
    static final int EXPRESSION_CACHE_MAX_SIZE = 10_000;

    private final ExpressionParser spelParser;
    private final ConcurrentLruCache<String, Expression> expressionCache =
        new ConcurrentLruCache<>(EXPRESSION_CACHE_MAX_SIZE);
    private final Map<String, Method> customFunctions = new ConcurrentHashMap<>();
    private final CollectionLengthPropertyAccessor collectionLengthAccessor = new CollectionLengthPropertyAccessor();
    private final MapAccessor mapAccessor = new MapAccessor();
    private final ListMapProjectionPropertyAccessor listMapProjectionAccessor = new ListMapProjectionPropertyAccessor();
    private final SafeMethodResolver safeMethodResolver = new SafeMethodResolver();

    private static final TypeLocator BLOCKING_TYPE_LOCATOR = typeName -> {
        throw new EvaluationException("SpEL type references are disabled");
    };

    private static final ConstructorResolver BLOCKING_CONSTRUCTOR_RESOLVER =
        (context, typeName, argumentTypes) -> {
            throw new AccessException("SpEL constructors are disabled");
        };

    public SpelEvaluator() {
        this.spelParser = new SpelExpressionParser();
    }

    @PostConstruct
    public void init() {
        registerCustomFunctions();
        logger.info("SpelEvaluator initialized with {} custom functions", customFunctions.size());
    }

    /**
     * Register all custom functions for SpEL evaluation.
     */
    private void registerCustomFunctions() {
        try {
            // Type casting functions
            customFunctions.put("int", ExpressionFunctions.class.getMethod("toInt", Object.class));
            customFunctions.put("double", ExpressionFunctions.class.getMethod("toDouble", Object.class));
            customFunctions.put("string", ExpressionFunctions.class.getMethod("toString", Object.class));
            customFunctions.put("bool", ExpressionFunctions.class.getMethod("toBool", Object.class));
            customFunctions.put("long", ExpressionFunctions.class.getMethod("toLong", Object.class));
            customFunctions.put("float", ExpressionFunctions.class.getMethod("toFloat", Object.class));

            // Utility functions
            customFunctions.put("size", ExpressionFunctions.class.getMethod("size", Object.class));
            customFunctions.put("len", ExpressionFunctions.class.getMethod("size", Object.class)); // Alias for size
            customFunctions.put("typeof", ExpressionFunctions.class.getMethod("typeof", Object.class));
            customFunctions.put("default", ExpressionFunctions.class.getMethod("defaultValue", Object.class, Object.class));
            customFunctions.put("coalesce", ExpressionFunctions.class.getMethod("coalesce", Object[].class));
            customFunctions.put("ifempty", ExpressionFunctions.class.getMethod("ifEmpty", Object.class, Object.class));
            customFunctions.put("isnull", ExpressionFunctions.class.getMethod("isNull", Object.class));
            customFunctions.put("isempty", ExpressionFunctions.class.getMethod("isEmpty", Object.class));

            // Math functions
            customFunctions.put("abs", ExpressionFunctions.class.getMethod("abs", Object.class));
            customFunctions.put("round", ExpressionFunctions.class.getMethod("round", Object.class, Object.class));
            customFunctions.put("floor", ExpressionFunctions.class.getMethod("floor", Object.class));
            customFunctions.put("ceil", ExpressionFunctions.class.getMethod("ceil", Object.class));
            customFunctions.put("min", ExpressionFunctions.class.getMethod("min", Object.class, Object.class));
            customFunctions.put("max", ExpressionFunctions.class.getMethod("max", Object.class, Object.class));
            customFunctions.put("pow", ExpressionFunctions.class.getMethod("pow", Object.class, Object.class));
            customFunctions.put("sqrt", ExpressionFunctions.class.getMethod("sqrt", Object.class));

            // String functions
            customFunctions.put("uppercase", ExpressionFunctions.class.getMethod("uppercase", Object.class));
            customFunctions.put("lowercase", ExpressionFunctions.class.getMethod("lowercase", Object.class));
            customFunctions.put("capitalize", ExpressionFunctions.class.getMethod("capitalize", Object.class));
            customFunctions.put("trim", ExpressionFunctions.class.getMethod("trim", Object.class));
            customFunctions.put("truncate", ExpressionFunctions.class.getMethod("truncate", Object.class, Object.class, Object.class));
            customFunctions.put("padleft", ExpressionFunctions.class.getMethod("padLeft", Object.class, Object.class, Object.class));
            customFunctions.put("padright", ExpressionFunctions.class.getMethod("padRight", Object.class, Object.class, Object.class));
            customFunctions.put("replace", ExpressionFunctions.class.getMethod("replace", Object.class, Object.class, Object.class));
            customFunctions.put("substring", ExpressionFunctions.class.getMethod("substring", Object.class, Object.class, Object.class));
            customFunctions.put("split", ExpressionFunctions.class.getMethod("split", Object.class, Object.class));
            customFunctions.put("join", ExpressionFunctions.class.getMethod("join", Object.class, Object.class));
            customFunctions.put("startswith", ExpressionFunctions.class.getMethod("startsWith", Object.class, Object.class));
            customFunctions.put("endswith", ExpressionFunctions.class.getMethod("endsWith", Object.class, Object.class));
            customFunctions.put("contains", ExpressionFunctions.class.getMethod("contains", Object.class, Object.class));
            customFunctions.put("matches", ExpressionFunctions.class.getMethod("matches", Object.class, Object.class));
            customFunctions.put("length", ExpressionFunctions.class.getMethod("length", Object.class));

            // Date/Formatting functions
            customFunctions.put("formatdate", ExpressionFunctions.class.getMethod("formatDate", Object.class, Object.class));
            customFunctions.put("formatnumber", ExpressionFunctions.class.getMethod("formatNumber", Object.class, Object.class));
            customFunctions.put("formatcurrency", ExpressionFunctions.class.getMethod("formatCurrency", Object.class, Object.class));
            customFunctions.put("now", ExpressionFunctions.class.getMethod("now"));
            customFunctions.put("today", ExpressionFunctions.class.getMethod("today"));

            // JSON functions
            customFunctions.put("json", ExpressionFunctions.class.getMethod("json", Object.class));
            customFunctions.put("fromjson", ExpressionFunctions.class.getMethod("fromJson", Object.class));
            customFunctions.put("tojson", ExpressionFunctions.class.getMethod("toJson", Object.class));

            logger.debug("Registered {} custom functions for SpEL", customFunctions.size());
        } catch (NoSuchMethodException e) {
            logger.error("Failed to register custom functions", e);
            throw new RuntimeException("Failed to initialize expression functions", e);
        }
    }

    /**
     * Get the registered custom functions map.
     */
    public Map<String, Method> getCustomFunctions() {
        return customFunctions;
    }

    /**
     * Test-only accessor - exposes the parsed-expression cache so tests can pin its
     * size, eviction, and hit semantics without touching internal state via reflection.
     */
    ConcurrentLruCache<String, Expression> getExpressionCache() {
        return expressionCache;
    }

    /**
     * Create a standard evaluation context with all custom functions registered.
     * Registers:
     * <ul>
     *   <li>{@link CollectionLengthPropertyAccessor} - {@code .length} on Collections and Maps</li>
     *   <li>{@link MapAccessor} - {@code map.key} dotted access into Map entries,
     *       which SpEL does <b>not</b> support out of the box. Required for
     *       selection predicates like {@code headers.?[name == 'From']} where
     *       {@code name} must resolve against the current Map element.</li>
     *   <li>{@link ListMapProjectionPropertyAccessor} - JSONPath-style
     *       projection: {@code list.prop} is read as {@code list.![prop]} when
     *       the list holds Maps. Makes {@code headers.?[name=='Subject'].value}
     *       return {@code ['hi']} instead of throwing {@code EL1008E}.</li>
     * </ul>
     */
    public StandardEvaluationContext createEvaluationContext() {
        StandardEvaluationContext evalContext = new StandardEvaluationContext();
        evalContext.setTypeLocator(BLOCKING_TYPE_LOCATOR);
        evalContext.setConstructorResolvers(List.of(BLOCKING_CONSTRUCTOR_RESOLVER));
        evalContext.setMethodResolvers(List.of(safeMethodResolver));
        evalContext.setBeanResolver((context, beanName) -> {
            throw new AccessException("SpEL bean references are disabled");
        });
        evalContext.setPropertyAccessors(List.of(
            collectionLengthAccessor,
            mapAccessor,
            listMapProjectionAccessor,
            DataBindingPropertyAccessor.forReadOnlyAccess()
        ));
        for (Map.Entry<String, Method> entry : customFunctions.entrySet()) {
            evalContext.registerFunction(entry.getKey(), entry.getValue());
        }
        return evalContext;
    }

    /**
     * Create a standard evaluation context with all custom functions and variables.
     */
    public StandardEvaluationContext createEvaluationContext(Map<String, Object> variables) {
        StandardEvaluationContext evalContext = createEvaluationContext();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            evalContext.setVariable(entry.getKey(), entry.getValue());
        }
        return evalContext;
    }

    /**
     * Parse and evaluate a SpEL expression with the given context.
     *
     * <p>{@link JsonParseException} thrown by the {@code json()} / {@code fromjson()} SpEL
     * functions is intentionally rethrown so {@code V2TemplateAdapter} can enrich the message
     * with the field name and the inspector can surface a structured error to the user.
     * SpEL wraps method-call exceptions in {@link SpelEvaluationException}; we unwrap one
     * level to recover the typed cause.
     */
    public Object evaluate(String expression, StandardEvaluationContext context) {
        try {
            Expression exp = expressionCache.computeIfAbsent(expression, spelParser::parseExpression);
            return exp.getValue(context);
        } catch (SpelEvaluationException e) {
            // SpEL wraps custom-function failures in SpelEvaluationException; surface the typed
            // cause so consumers (V2TemplateAdapter) can act on it.
            if (e.getCause() instanceof JsonParseException jpe) {
                throw jpe;
            }
            logger.warn("SpEL evaluation error for '{}': {}", expression, e.getMessage());
            return null;
        } catch (JsonParseException jpe) {
            // Direct throw (e.g. if SpEL doesn't wrap on this path); propagate verbatim.
            throw jpe;
        } catch (Exception e) {
            logger.warn("SpEL evaluation error for '{}': {}", expression, e.getMessage());
            return null;
        }
    }

    /**
     * Evaluate expression with SpEL using Map-based context.
     */
    @SuppressWarnings("unchecked")
    public Object evaluateWithMap(String expression, Map<String, Object> context, PathNavigator pathNavigator) {
        try {
            StandardEvaluationContext evalContext = createEvaluationContext();

            // Transform expression: replace variable references with #varName.
            // Side-effect: the transform also calls evalContext.setVariable(...) for every
            // resolved identifier - so this call must run for each evaluation even when the
            // parsed Expression is cache-hit. The cache key is the transformed string, since
            // the same raw expression can transform differently depending on which identifiers
            // resolve in the given context.
            String spelExpression = transformExpressionForMap(expression, context, evalContext, pathNavigator);

            Expression exp = expressionCache.computeIfAbsent(spelExpression, spelParser::parseExpression);
            return exp.getValue(evalContext);

        } catch (SpelEvaluationException e) {
            // Surface json() / fromjson() typed errors so V2TemplateAdapter can enrich them
            // with the field name. Other SpEL failures stay null-on-error.
            if (e.getCause() instanceof JsonParseException jpe) {
                throw jpe;
            }
            logger.warn("SpEL evaluation error for '{}': {}", expression, e.getMessage());
            return null;
        } catch (JsonParseException jpe) {
            throw jpe;
        } catch (Exception e) {
            logger.warn("SpEL evaluation error for '{}': {}", expression, e.getMessage());
            return null;
        }
    }

    /**
     * Transform expression for Map context: extract variables and add to SpEL context.
     *
     * This method parses the expression character-by-character to:
     * 1. Skip identifiers inside protected regions (string literals, SpEL
     *    selection {@code .?[...]} / projection {@code .![...]}) - identifiers
     *    there refer to string content or the current element (#this), not
     *    outer variables
     * 2. Only prefix custom function names with # when followed by ( - prevents
     *    confusing property access (.length) with function calls (length(...))
     * 3. Resolve dotted/namespaced identifiers (mcp:step.output.field) as variables
     */
    private String transformExpressionForMap(String expression, Map<String, Object> context,
                                              StandardEvaluationContext evalContext, PathNavigator pathNavigator) {
        // Matches variable paths including array access: core:split.output.edges[0].node.text
        java.util.regex.Pattern varPattern = java.util.regex.Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*(?::[a-zA-Z_][a-zA-Z0-9_]*)?(?:\\[\\d+]|\\.[a-zA-Z_][a-zA-Z0-9_]*)*)(?![\\w:])"
        );

        // First pass: find protected regions (strings, selection/projection brackets)
        java.util.List<SpelProtectedRegions.Region> protectedRegions = SpelProtectedRegions.find(expression);

        java.util.regex.Matcher varMatcher = varPattern.matcher(expression);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (varMatcher.find()) {
            int matchStart = varMatcher.start();
            int matchEnd = varMatcher.end();

            // Skip identifiers inside protected regions
            if (SpelProtectedRegions.isProtected(matchStart, protectedRegions)) {
                result.append(expression, lastEnd, matchEnd);
                lastEnd = matchEnd;
                continue;
            }

            result.append(expression, lastEnd, matchStart);
            lastEnd = matchEnd;

            String identifier = varMatcher.group(1);
            String identifierLower = identifier.toLowerCase();

            // Check if this identifier is in property access position (preceded by . or ?.)
            boolean isPropertyAccess = false;
            if (matchStart > 0) {
                char prev = expression.charAt(matchStart - 1);
                if (prev == '.') {
                    isPropertyAccess = true;
                }
            }

            if (RESERVED_WORDS.contains(identifierLower)) {
                result.append(identifier);
            } else if (isPropertyAccess) {
                // Property access position: leave as-is (e.g., messages?.length, data.name)
                result.append(identifier);
            } else if (CUSTOM_FUNCTION_NAMES.contains(identifierLower)) {
                // Only prefix with # if followed by ( - function call, not variable/property
                boolean isFollowedByParen = isFollowedByOpenParen(expression, matchEnd);
                if (isFollowedByParen) {
                    result.append("#").append(identifierLower);
                } else {
                    // Not a function call - treat as variable
                    Object value = resolveVariableFromMap(identifier, context, pathNavigator);
                    String safeVarName = toSafeVarName(identifier);
                    if (value != null) {
                        evalContext.setVariable(safeVarName, value);
                    }
                    result.append("#").append(safeVarName);
                }
            } else {
                // Try to resolve the variable from context
                Object value = resolveVariableFromMap(identifier, context, pathNavigator);

                if (value != null) {
                    // Full path resolved - set as variable
                    String safeVarName = toSafeVarName(identifier);
                    evalContext.setVariable(safeVarName, value);
                    result.append("#").append(safeVarName);
                } else if (identifier.contains(".")) {
                    // Full dotted path resolved to null - try splitting: resolve base, let SpEL handle rest.
                    // This handles cases like "items.size()", "items.length", "items.empty" where
                    // "items" is a List/String (not a Map), so dotted Map navigation fails, but
                    // SpEL can call methods/access properties on the resolved base object.
                    SplitResolution split = trySplitResolution(identifier, context, pathNavigator);
                    if (split != null) {
                        String safeBase = toSafeVarName(split.basePath);
                        evalContext.setVariable(safeBase, split.baseValue);
                        result.append("#").append(safeBase).append(".").append(split.remainingPath);
                    } else {
                        // Nothing resolvable - set full path as null variable
                        result.append("#").append(toSafeVarName(identifier));
                    }
                } else {
                    // Simple identifier not found - set as null variable
                    result.append("#").append(toSafeVarName(identifier));
                }
            }
        }

        if (lastEnd < expression.length()) {
            result.append(expression.substring(lastEnd));
        }

        return result.toString();
    }

    /**
     * Sanitize an identifier for use as a SpEL variable name.
     * Replaces characters illegal in SpEL variable names (: . [ ]) with underscores.
     */
    private String toSafeVarName(String identifier) {
        return identifier.replace(":", "_").replace(".", "_")
            .replace("[", "_").replace("]", "_");
    }

    /**
     * Result of splitting a dotted path into a resolved base + remaining SpEL path.
     */
    private record SplitResolution(String basePath, Object baseValue, String remainingPath) {}

    /**
     * Try to split a dotted identifier into a resolvable base path and a remaining SpEL suffix.
     * Progressively removes trailing segments until a non-null value is found.
     *
     * Example: "mcp:api.output.items.size" with items=[1,2,3]
     *   → try "mcp:api.output.items.size" → null (items is a List, not Map)
     *   → try "mcp:api.output.items" → [1,2,3] ✓
     *   → returns SplitResolution("mcp:api.output.items", [1,2,3], "size")
     *
     * This allows SpEL to handle method/property access on the resolved object:
     *   #mcp_api_output_items.size() or #items.length
     */
    @SuppressWarnings("unchecked")
    private SplitResolution trySplitResolution(String identifier, Map<String, Object> context,
                                                PathNavigator pathNavigator) {
        // Try progressively shorter base paths
        int lastDot = identifier.lastIndexOf('.');
        while (lastDot > 0) {
            String basePath = identifier.substring(0, lastDot);
            String remaining = identifier.substring(lastDot + 1);

            Object baseValue = resolveVariableFromMap(basePath, context, pathNavigator);
            if (baseValue != null && !(baseValue instanceof Map)) {
                // Found a non-Map value - SpEL can handle the remaining path as method/property
                return new SplitResolution(basePath, baseValue, remaining);
            }

            lastDot = identifier.lastIndexOf('.', lastDot - 1);
        }
        return null;
    }

    /**
     * Check if the character after optional whitespace is '('.
     */
    private boolean isFollowedByOpenParen(String expression, int fromIndex) {
        int i = fromIndex;
        while (i < expression.length() && Character.isWhitespace(expression.charAt(i))) {
            i++;
        }
        return i < expression.length() && expression.charAt(i) == '(';
    }

    /**
     * Resolve a variable path from a Map context.
     *
     * UNIFIED EXPRESSION PATTERN:
     * All node types use {{type:label.output.field}} format:
     * - {{trigger:webhook.output.user_id}}
     * - {{mcp:api_call.output.data}}
     * - {{agent:assistant.output.response}}
     * - {{core:decision.output.selected_branch}}
     *
     * Supports:
     * - Simple keys: "name" -> context.get("name")
     * - Nested paths: "user.name" -> context.get("user").get("name")
     * - Prefixed keys: "mcp:api_call.output.data" -> context.get("mcp:api_call").get("output").get("data")
     * - Backwards compatibility: searches in "output" sub-map if key not found directly
     */
    /** Pattern to detect array access: "key[0]" → group(1)="key", group(2)="0" */
    private static final java.util.regex.Pattern ARRAY_ACCESS = java.util.regex.Pattern.compile(
        "^([^\\[]+)\\[(\\d+)]$"
    );

    /**
     * Resolves a base key that may contain array access (e.g., "items[1]").
     * Falls back to plain context lookup if no array notation is found.
     */
    private Object resolveBaseKey(String key, Map<String, Object> context) {
        Object value = context.get(key);
        if (value != null) {
            return value;
        }

        java.util.regex.Matcher m = ARRAY_ACCESS.matcher(key);
        if (m.matches()) {
            String arrayKey = m.group(1);
            int index = Integer.parseInt(m.group(2));
            Object arrayValue = context.get(arrayKey);
            if (arrayValue instanceof java.util.List<?> list) {
                return (index >= 0 && index < list.size()) ? list.get(index) : null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object resolveVariableFromMap(String variablePath, Map<String, Object> context, PathNavigator pathNavigator) {
        if (variablePath == null || variablePath.isEmpty() || context == null) {
            return null;
        }

        // Try direct lookup first
        if (context.containsKey(variablePath)) {
            return context.get(variablePath);
        }

        // Handle dotted paths
        if (variablePath.contains(".")) {
            String[] parts = variablePath.split("\\.", 2);
            String baseKey = parts[0];
            String remainingPath = parts[1];

            Object baseValue = resolveBaseKey(baseKey, context);
            if (baseValue instanceof Map) {
                return pathNavigator.navigateMapPath((Map<String, Object>) baseValue, remainingPath);
            }
        }

        // Handle standalone array access: "items[1]" without dots
        if (variablePath.contains("[")) {
            Object result = resolveBaseKey(variablePath, context);
            if (result != null) {
                return result;
            }
        }

        // Handle prefixed keys (mcp:xxx, trigger:xxx, etc.)
        if (variablePath.contains(":")) {
            String[] prefixParts = variablePath.split(":", 2);
            String prefix = prefixParts[0];
            String rest = prefixParts[1];

            // Try with full prefixed key
            String fullKey = prefix + ":" + rest.split("\\.")[0];
            if (context.containsKey(fullKey)) {
                Object value = context.get(fullKey);
                if (rest.contains(".") && value instanceof Map) {
                    String path = rest.substring(rest.indexOf(".") + 1);
                    return pathNavigator.navigateMapPath((Map<String, Object>) value, path);
                }
                return value;
            }
        }

        // Search in step outputs or nested structures
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            if (entry.getValue() instanceof Map) {
                Map<String, Object> nested = (Map<String, Object>) entry.getValue();

                // Check in nested map
                if (nested.containsKey(variablePath)) {
                    return nested.get(variablePath);
                }

                // Check in "output" sub-map (common pattern for step outputs)
                if (nested.containsKey("output") && nested.get("output") instanceof Map) {
                    Map<String, Object> output = (Map<String, Object>) nested.get("output");
                    if (output.containsKey(variablePath)) {
                        return output.get(variablePath);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Pre-process custom function calls to use SpEL function syntax (#func).
     */
    public String preprocessCustomFunctions(String expression) {
        if (expression == null) return null;

        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < expression.length()) {
            char c = expression.charAt(i);

            // Skip string literals (handles SpEL doubled '' escape and \" escape)
            if (c == '\'') {
                result.append(c);
                i++;
                while (i < expression.length()) {
                    char sc = expression.charAt(i);
                    if (sc == '\'') {
                        result.append(sc);
                        i++;
                        // SpEL doubled single-quote escape: '' inside '...'
                        if (i < expression.length() && expression.charAt(i) == '\'') {
                            result.append(expression.charAt(i));
                            i++;
                            continue; // still inside the string
                        }
                        break; // end of string
                    }
                    result.append(sc);
                    i++;
                }
                continue;
            }
            if (c == '"') {
                result.append(c);
                i++;
                while (i < expression.length()) {
                    char sc = expression.charAt(i);
                    if (sc == '\\' && i + 1 < expression.length()) {
                        // Backslash escape in double-quoted string
                        result.append(sc);
                        result.append(expression.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (sc == '"') {
                        result.append(sc);
                        i++;
                        break; // end of string
                    }
                    result.append(sc);
                    i++;
                }
                continue;
            }

            // Check for function name or identifier
            if (Character.isLetter(c) || c == '_') {
                StringBuilder funcName = new StringBuilder();

                while (i < expression.length() && (Character.isLetterOrDigit(expression.charAt(i)) || expression.charAt(i) == '_')) {
                    funcName.append(expression.charAt(i));
                    i++;
                }

                String name = funcName.toString();
                String nameLower = name.toLowerCase();

                // Skip whitespace
                int whitespaceStart = i;
                while (i < expression.length() && Character.isWhitespace(expression.charAt(i))) {
                    i++;
                }

                // Check if followed by (
                if (i < expression.length() && expression.charAt(i) == '(') {
                    if (CUSTOM_FUNCTION_NAMES.contains(nameLower)) {
                        // Custom function: add # prefix
                        result.append("#").append(nameLower);
                    } else {
                        // Not a custom function, keep original
                        result.append(name);
                        // Add back any whitespace
                        for (int j = whitespaceStart; j < i; j++) {
                            result.append(expression.charAt(j));
                        }
                    }
                } else {
                    result.append(name);
                    // Add back any whitespace
                    for (int j = whitespaceStart; j < i; j++) {
                        result.append(expression.charAt(j));
                    }
                }
                continue;
            }

            result.append(c);
            i++;
        }

        return result.toString();
    }

    /**
     * Evaluate expression with SpEL using cached expressions.
     */
    public Object evaluateWithCache(String expression, Map<String, Object> context) {
        try { // (JsonParseException is rethrown below to preserve typed errors)
            logger.debug("SpEL evaluation: {} with context: {}", expression, context.keySet());

            // Preprocess custom function calls
            String processed = preprocessCustomFunctions(expression);

            // Build evaluation context
            StandardEvaluationContext evalContext = createEvaluationContext();

            // Add all context variables
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                evalContext.setVariable(entry.getKey(), entry.getValue());
            }

            // Parse and evaluate (LRU-bounded cache)
            Expression exp = expressionCache.computeIfAbsent(processed, spelParser::parseExpression);
            Object result = exp.getValue(evalContext);

            logger.debug("SpEL result: {} -> {}", expression, result);
            return result;
        } catch (SpelEvaluationException e) {
            if (e.getCause() instanceof JsonParseException jpe) {
                throw jpe;
            }
            logger.warn("SpEL evaluation error for '{}': {}. Returning raw value.", expression, e.getMessage());
            return expression;
        } catch (JsonParseException jpe) {
            throw jpe;
        } catch (Exception e) {
            logger.warn("SpEL evaluation error for '{}': {}. Returning raw value.", expression, e.getMessage());
            return expression;
        }
    }

    /**
     * Convert any value to boolean.
     */
    public boolean toBoolean(Object result) {
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        if (result instanceof Number) {
            return ((Number) result).doubleValue() != 0.0;
        }
        if (result instanceof String) {
            String str = (String) result;
            return !str.isBlank() && !"false".equalsIgnoreCase(str) && !"0".equals(str);
        }
        return result != null;
    }

    /**
     * Format value for SpEL expression.
     */
    public String formatValueForSpel(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            // SpEL uses doubled single quotes for escaping: 'it''s'
            String strValue = value.toString().replace("'", "''");
            return "'" + strValue + "'";
        } else if (value instanceof Long) {
            // Long values need L suffix for SpEL to parse them correctly
            return value + "L";
        } else if (value instanceof Double) {
            // Double values need D suffix for SpEL
            return value + "D";
        } else if (value instanceof Float) {
            // Float values need F suffix for SpEL
            return value + "F";
        } else if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        } else {
            // Escape single quotes in the toString() representation
            String strValue = value.toString().replace("'", "''");
            return "'" + strValue + "'";
        }
    }

    /**
     * Restricts SpEL instance method calls to data-shaping helpers on JSON-like values.
     * Type references, constructors, beans, and reflective escape hatches stay unavailable.
     */
    private static final class SafeMethodResolver implements MethodResolver {
        private static final Set<String> BLOCKED_METHODS = Set.of(
            "getClass", "forName", "getClassLoader", "loadClass", "newInstance",
            "getMethod", "getMethods", "getDeclaredMethod", "getDeclaredMethods",
            "getConstructor", "getConstructors", "getDeclaredConstructor", "getDeclaredConstructors",
            "invoke", "setAccessible", "exec", "start"
        );

        private static final Set<String> STRING_METHODS = Set.of(
            "length", "isEmpty", "isBlank", "trim", "strip", "toLowerCase", "toUpperCase",
            "contains", "startsWith", "endsWith", "substring", "replace", "replaceAll",
            "replaceFirst", "matches", "split", "indexOf", "lastIndexOf", "charAt", "toString"
        );

        private static final Set<String> COLLECTION_METHODS = Set.of(
            "size", "isEmpty", "contains", "get", "toString"
        );

        private static final Set<String> MAP_METHODS = Set.of(
            "get", "getOrDefault", "containsKey", "containsValue", "size", "isEmpty", "toString"
        );

        private static final Set<String> NUMBER_BOOLEAN_METHODS = Set.of(
            "toString", "intValue", "longValue", "doubleValue", "floatValue", "booleanValue"
        );

        private final ReflectiveMethodResolver delegate = new ReflectiveMethodResolver();

        @Override
        public MethodExecutor resolve(
                EvaluationContext context,
                Object targetObject,
                String name,
                List<TypeDescriptor> argumentTypes) throws AccessException {
            if (!isAllowed(targetObject, name)) {
                throw new AccessException("SpEL method is not allowed: " + name);
            }
            return delegate.resolve(context, targetObject, name, argumentTypes);
        }

        private boolean isAllowed(Object targetObject, String methodName) {
            if (targetObject == null || methodName == null || BLOCKED_METHODS.contains(methodName)) {
                return false;
            }

            if (targetObject instanceof Class<?>
                    || targetObject instanceof ClassLoader
                    || targetObject instanceof Method
                    || targetObject instanceof Constructor<?>
                    || targetObject instanceof Runtime
                    || targetObject instanceof ProcessBuilder) {
                return false;
            }

            if (targetObject instanceof String) {
                return STRING_METHODS.contains(methodName);
            }
            if (targetObject instanceof Map<?, ?>) {
                return MAP_METHODS.contains(methodName);
            }
            if (targetObject instanceof List<?>) {
                return COLLECTION_METHODS.contains(methodName);
            }
            if (targetObject instanceof Collection<?>) {
                return COLLECTION_METHODS.contains(methodName) && !"get".equals(methodName);
            }
            if (targetObject instanceof Number || targetObject instanceof Boolean) {
                return NUMBER_BOOLEAN_METHODS.contains(methodName);
            }
            return false;
        }
    }
}
