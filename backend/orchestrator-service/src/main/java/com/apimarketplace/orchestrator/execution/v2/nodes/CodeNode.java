package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.OutputUnwrapper;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.code.CodeExecutor;
import com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeRequest;
import com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeResult;
import com.apimarketplace.orchestrator.services.expression.JsonOutputUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code node - Executes user code in a sandboxed environment via Piston.
 *
 * Supported languages: javascript, python, typescript, bash
 *
 * The node wraps user code to:
 * 1. Inject upstream data as $input (JS/TS) or _input (Python) or INPUT (Bash)
 * 2. Capture output via __RESULT__ prefix in stdout
 * 3. Parse the result back as structured JSON data
 *
 * Usage:
 * - Transform data with custom logic not possible via Transform node
 * - Call external APIs from code
 * - Implement complex business rules
 * - Generate dynamic content
 *
 * Upstream data reaches the code through $input / _input / INPUT. A code body may still carry
 * {{...}} expressions (saved workflows do), and those are resolved here - but every resolved
 * value is escaped as a string literal of the target language before it is spliced back in,
 * so upstream data can never close the surrounding literal and become executable source.
 * While a plan is being written, workflow validate (CoreValidator, CODE_TEMPLATE_IN_BODY) warns
 * about a body carrying {{...}}, with a stronger wording when it sits OUTSIDE a string literal.
 * It never blocks a save: saved workflows rely on the shape and the run keeps the value data.
 *
 * <p>Where a placeholder sits decides what escaping can promise, so the body is scanned before
 * it is resolved ({@link #scanCodeBody}). Inside a string literal the escape keeps the value as
 * data. Outside one, no escape can: the position IS source, so the value is spliced as a
 * COMPLETE literal of the target language instead ({@link #spliceAtCodePosition}). The two
 * positions therefore behave differently and deliberately so, see {@link #resolveOnePlaceholder}.
 */
public class CodeNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(CodeNode.class);
    private static final String RESULT_PREFIX = "__RESULT__";
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Same shape as {@code TemplateEngine.EXPRESSION_PATTERN} (single-quoted SpEL literals are
     * opaque so a {@code }} or {@code |} inside them does not end the match), mirrored here for
     * the same reason ReferenceValidator and ConsumerTracker mirror it: the engine resolves a
     * whole string in one pass, and this node has to split the body placeholder by placeholder
     * so each resolved value can be escaped on its own.
     */
    private static final Pattern TEMPLATE_EXPRESSION =
        Pattern.compile("\\{\\{((?:'(?:[^'\\\\]|\\\\.)*'|[^}|])+?(?:\\|[^}]*)?)\\}\\}");

    /** Where a {{...}} placeholder sits in a code body. */
    public enum PlaceholderPosition {
        /**
         * Between the delimiters of a string literal the author wrote. The escape applied to the
         * resolved value keeps it inside that literal, so it stays data.
         */
        STRING_LITERAL,
        /**
         * Between the delimiters of a PYTHON F-STRING. Still a literal, but one whose
         * {@code {...}} spans are evaluated as expressions, so the escape has to neutralise the
         * brace as well. The Python twin of the JavaScript {@code ${...}} interpolation, which is
         * reported as {@link #CODE} because a backtick literal's interpolation opens a real code
         * span while an f-string's braces can be escaped in place by doubling them.
         */
        F_STRING_LITERAL,
        /**
         * Between the delimiters of a BASH DOUBLE-QUOTED word. Still a literal, but one that
         * expands {@code $}, a backtick and {@code \}, so the escape has to neutralise those as
         * well as the closing quote. Backslash escapes exactly those four characters inside a
         * double-quoted word, and nothing else there is special, so a value carrying all four
         * still arrives as data. The Bash twin of the Python f-string.
         *
         * <p>A {@code $(...)}, {@code ${...}} or backtick span OPENED inside such a word is a real
         * code span and is reported as {@link #CODE}, exactly like a JavaScript {@code ${...}}
         * interpolation: an escape there would only be data inside a command that still runs.
         */
        SHELL_DOUBLE_QUOTED,
        /**
         * Inside a Bash ANSI-C quoted word ({@code $'...'}). Backslash IS an escape there, so a
         * value is escaped by doubling every backslash and writing a quote as {@code \'}.
         */
        SHELL_ANSI_C_QUOTED,
        /**
         * Inside the body of a Bash here-document whose delimiter is UNQUOTED ({@code <<EOF}).
         * The body expands {@code $}, a backtick and {@code \} exactly like a double-quoted word,
         * but quote characters are plain text there, so the single-quoted-word splice used at a
         * code position would NOT protect a value. Escaped in place for those three characters,
         * and a value line equal to the delimiter is defused so it cannot end the document.
         */
        SHELL_HEREDOC_UNQUOTED,
        /**
         * Inside the body of a Bash here-document whose delimiter is QUOTED ({@code <<'EOF'}):
         * nothing expands, so the value is spliced as is, except that a value line equal to the
         * delimiter is defused so it cannot end the document and turn what follows into commands.
         */
        SHELL_HEREDOC_QUOTED,
        /**
         * Anywhere else: a bare statement or operand, a comment, a regex literal, a JavaScript
         * {@code ${...}} interpolation, a Python f-string replacement field, a Bash command
         * substitution. The position itself IS source, so no escape can make an arbitrary value
         * inert there; the value is spliced as a complete literal instead
         * ({@link #spliceAtCodePosition}).
         */
        CODE
    }

    /**
     * One {{...}} placeholder found in a code body, with the position it occupies.
     *
     * @param heredocDelimiter for the two here-document positions, the delimiter that ends the
     *                         document (so a value cannot reproduce it on a line of its own);
     *                         {@code null} everywhere else
     */
    public record BodyPlaceholder(String expression, int start, int end, PlaceholderPosition position,
                                  String heredocDelimiter) {
        public BodyPlaceholder(String expression, int start, int end, PlaceholderPosition position) {
            this(expression, start, end, position, null);
        }
    }

    private final Core.CodeConfig codeConfig;
    private CodeExecutor codeExecutor;

    public CodeNode(String nodeId, Core.CodeConfig codeConfig) {
        super(nodeId, NodeType.CODE);
        this.codeConfig = codeConfig;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.codeExecutor = registry.getCodeExecutor();
    }

    /**
     * Set CodeExecutor directly (for testing).
     */
    public void setCodeExecutor(CodeExecutor codeExecutor) {
        this.codeExecutor = codeExecutor;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("Code node executing: nodeId={}, itemId={}", nodeId, context.itemId());
        long startTime = System.currentTimeMillis();

        // Build resolved_params early so it's available in all result paths
        String language = codeConfig != null ? codeConfig.language() : "javascript";
        String rawCode = codeConfig != null ? codeConfig.code() : "";
        int timeoutSeconds = codeConfig != null ? codeConfig.timeoutSeconds() : 10;
        timeoutSeconds = Math.min(Math.max(timeoutSeconds, 1), MAX_TIMEOUT_SECONDS);
        // A templated timeout is reported as its template until it has resolved: the default the
        // typed config holds meanwhile is not what this node was configured with.
        String timeoutTemplate = deferredScalar("code", "timeoutSeconds");
        Object reportedTimeout = timeoutTemplate != null ? timeoutTemplate : timeoutSeconds;
        Map<String, Object> resolvedParams = buildInputDataMap(language, rawCode, reportedTimeout);

        try {
            // The config this execution runs with: a {{...}} timeout resolved now, never the default.
            Core.CodeConfig effective = withDeferredScalars("code", codeConfig, Core.CodeConfig.class, context);
            if (effective != codeConfig && effective != null) {
                timeoutSeconds = Math.min(Math.max(effective.timeoutSeconds(), 1), MAX_TIMEOUT_SECONDS);
                reportedTimeout = timeoutTemplate != null
                    ? com.apimarketplace.orchestrator.services.template.ReportedParams.valueFrom(timeoutTemplate, timeoutSeconds)
                    : timeoutSeconds;
                resolvedParams = buildInputDataMap(language, rawCode, reportedTimeout);
            }

            if (codeExecutor == null) {
                throw new IllegalStateException("CodeExecutor is not available. Ensure Piston or embedded executor is configured.");
            }

            String userCode = resolveCodeBody(rawCode, language, context);
            // Every exit from here on measures the code that was resolved and run, not the template.
            resolvedParams = buildInputDataMap(language, userCode, reportedTimeout);

            if (userCode == null || userCode.isBlank()) {
                throw new IllegalArgumentException("Code is required");
            }

            // Build input data from upstream context
            Map<String, Object> inputData = buildInputData(context);
            String inputJson = objectMapper.writeValueAsString(inputData);

            // Wrap user code with input injection and result capture
            String wrappedCode = wrapCode(language, userCode, inputJson);

            // Build execution request
            CodeRequest request = new CodeRequest(
                language,
                "*",    // latest version
                wrappedCode,
                null,   // no stdin
                timeoutSeconds * 1000,
                timeoutSeconds * 1000,
                0       // default memory limit
            );

            // Execute
            CodeResult response = codeExecutor.execute(request);
            long executionTime = System.currentTimeMillis() - startTime;

            // Check for compilation errors
            if (response.hasCompileError()) {
                throw new RuntimeException("Compilation error: " + response.compileStderr());
            }

            // Check for runtime errors (incl. sandbox-keeper death from stdout overflow)
            if (!response.isSuccess()) {
                String reason = response.failureReason();
                if (reason == null || reason.isBlank()) {
                    String runtimeError = response.stderr();
                    if (runtimeError == null || runtimeError.isBlank()) {
                        runtimeError = response.output();
                    }
                    reason = "exit " + response.exitCode() + ": " + runtimeError;
                }
                throw new RuntimeException("Code node failed: " + reason);
            }

            // Parse result from stdout
            String stdout = response.stdout() != null ? response.stdout() : "";
            String stderr = response.stderr() != null ? response.stderr() : "";
            Object parsedResult = extractResult(stdout);
            String consoleOutput = extractConsoleOutput(stdout);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("result", parsedResult);
            result.put("stdout", consoleOutput);
            result.put("stderr", stderr);
            result.put("exitCode", response.exitCode());
            result.put("language", language);
            result.put("executionTime", executionTime);
            result.put("success", true);

            // MANDATORY metadata
            result.put("node_type", "CODE");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            result.put("resolved_params", buildInputDataMap(language, userCode, reportedTimeout));

            logger.info("Code completed: nodeId={}, language={}, exitCode={}, executionTime={}ms",
                nodeId, language, response.exitCode(), executionTime);
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Code execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failureOutput = new LinkedHashMap<>();
            failureOutput.put("node_type", "CODE");
            failureOutput.put("item_index", context.itemIndex());
            failureOutput.put("itemIndex", context.itemIndex());
            failureOutput.put("item_id", context.itemId());
            failureOutput.put("resolved_params", resolvedParams);
            return NodeExecutionResult.failureWithOutput(nodeId, explainedFailure(e.getMessage(), rawCode, language),
                failureOutput, duration);
        }
    }

    /**
     * Wrap user code with language-specific input injection and result capture.
     * Package-private for testing.
     */
    String wrapCode(String language, String userCode, String inputJson) {
        String escapedJson = escapeForString(inputJson, language);

        return switch (language.toLowerCase(Locale.ROOT)) {
            case "javascript", "node" -> wrapJavaScript(userCode, escapedJson);
            case "python", "python3" -> wrapPython(userCode, escapedJson);
            case "typescript" -> wrapTypeScript(userCode, escapedJson);
            case "bash", "shell", "sh" -> wrapBash(userCode, escapedJson);
            default -> throw new IllegalArgumentException("Unsupported language: " + language +
                ". Supported: javascript, python, typescript, bash");
        };
    }

    private String wrapJavaScript(String userCode, String escapedJson) {
        return """
            const $input = JSON.parse('%s');
            let $output = undefined;

            %s

            if (typeof $output !== 'undefined') {
                console.log('%s' + JSON.stringify($output));
            }
            """.formatted(escapedJson, userCode, RESULT_PREFIX);
    }

    private String wrapTypeScript(String userCode, String escapedJson) {
        return """
            const $input: any = JSON.parse('%s');
            let $output: any = undefined;

            %s

            if (typeof $output !== 'undefined') {
                console.log('%s' + JSON.stringify($output));
            }
            """.formatted(escapedJson, userCode, RESULT_PREFIX);
    }

    private String wrapPython(String userCode, String escapedJson) {
        return """
            import json as _json
            _input = _json.loads('%s')
            _output = None

            %s

            if _output is not None:
                print('%s' + _json.dumps(_output))
            """.formatted(escapedJson, userCode, RESULT_PREFIX);
    }

    private String wrapBash(String userCode, String escapedJson) {
        return """
            INPUT='%s'

            %s

            if [ -n "$OUTPUT" ]; then
                echo '%s'"$OUTPUT"
            fi
            """.formatted(escapedJson, userCode, RESULT_PREFIX);
    }

    /**
     * Escape a JSON string for embedding in a string literal.
     * For bash, uses the '\'' idiom (end single quote, literal single quote, start single quote)
     * because bash single-quoted strings do NOT process escape sequences like \'.
     * For other languages (JS, Python, TS), uses backslash escaping inside single quotes.
     */
    String escapeForString(String json, String language) {
        String lang = language.toLowerCase(Locale.ROOT);
        if ("bash".equals(lang) || "shell".equals(lang) || "sh".equals(lang)) {
            // Bash single-quoted strings: only way to include a single quote is to end
            // the single-quoted string, add an escaped single quote, and start a new one:
            // 'foo'\''bar' => foo'bar
            // Newlines are literal in single quotes, so we don't escape them.
            return json.replace("'", "'\\''");
        }
        // JS, Python, TS: backslash escaping inside single quotes
        String escaped = json.replace("\\", "\\\\").replace("'", "\\'");
        escaped = escaped.replace("\n", "\\n").replace("\r", "\\r");
        return escaped;
    }

    /**
     * Extract the structured result from stdout by looking for __RESULT__ prefix.
     */
    Object extractResult(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }

        for (String line : stdout.split("\n")) {
            if (line.startsWith(RESULT_PREFIX)) {
                String jsonStr = line.substring(RESULT_PREFIX.length()).trim();
                if (!jsonStr.isEmpty()) {
                    try {
                        return objectMapper.readValue(jsonStr, new TypeReference<Object>() {});
                    } catch (Exception e) {
                        logger.warn("Failed to parse __RESULT__ JSON: {}", e.getMessage());
                        return jsonStr; // Return raw string if not valid JSON
                    }
                }
            }
        }
        return null;
    }

    /**
     * Extract console output (everything except the __RESULT__ line).
     */
    String extractConsoleOutput(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String line : stdout.split("\n")) {
            if (!line.startsWith(RESULT_PREFIX)) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * Build input data map from execution context (upstream step outputs).
     * Unwraps the {output: {...}} wrapper so code sees direct values:
     *   Template syntax: {{table:label.output.field}}
     *   Code equivalent: $input.label.field  or  $input["table:label"].field
     */
    private Map<String, Object> buildInputData(ExecutionContext context) {
        Map<String, Object> inputData = new LinkedHashMap<>();

        // Include all available step outputs, unwrapped for clean access
        // Uses OutputUnwrapper.unwrapForCodeNode() to strip the {output: {...}} wrapper
        // and any metadata keys (resultStepId, statusValue, etc.)
        if (context.stepOutputs() != null) {
            for (Map.Entry<String, Object> entry : context.stepOutputs().entrySet()) {
                inputData.put(entry.getKey(), OutputUnwrapper.unwrapForCodeNode(entry.getValue()));
            }
        }

        // Include item context for split scenarios
        if (context.itemId() != null) {
            inputData.put("__itemId", context.itemId());
            inputData.put("__itemIndex", context.itemIndex());
        }

        return inputData;
    }

    /**
     * Resolve the {{...}} expressions a saved code body may still carry, ONE placeholder at a
     * time, and escape every resolved value before it is spliced back into the source.
     *
     * <p>Resolving the whole body in a single call (what this node used to do) let upstream data
     * - a webhook payload, a mail body, any tool output - land verbatim inside executable
     * JavaScript, TypeScript, Python or Bash: a value carrying a quote closed the literal the
     * author had written around the placeholder and everything after it was code. The escaping
     * that already protected the injected {@code $input} JSON was never applied to the body.
     *
     * <p>Per-placeholder resolution keeps the stringification the template engine itself uses
     * (Map/Collection/array as JSON, anything else as its string form, an unresolved expression
     * as the empty string) so an existing workflow keeps producing the same value. A body with
     * no {{ is returned untouched without calling the engine.
     *
     * @param language target language, decides which literal escape applies
     */
    String resolveCodeBody(String rawCode, String language, ExecutionContext context) {
        if (rawCode == null || rawCode.isBlank()) {
            return null;
        }
        if (!rawCode.contains("{{") || templateAdapter == null) {
            return rawCode;
        }

        List<BodyPlaceholder> placeholders = scanCodeBody(rawCode, language);
        if (placeholders.isEmpty()) {
            return rawCode;
        }
        StringBuilder resolved = new StringBuilder();
        int lastEnd = 0;
        for (BodyPlaceholder placeholder : placeholders) {
            resolved.append(rawCode, lastEnd, placeholder.start());
            resolved.append(resolveOnePlaceholder(placeholder, language, context));
            lastEnd = placeholder.end();
        }
        resolved.append(rawCode, lastEnd, rawCode.length());
        return resolved.toString();
    }

    /**
     * Resolve a single {{...}} placeholder and splice the result for the target language.
     * On a resolution failure the placeholder is left as written: the run then fails on a
     * syntax error naming the placeholder, which is far easier to act on than a silent blank.
     *
     * <p>The splice depends on the position, because escaping only means something inside a
     * string literal:
     * <ul>
     *   <li>{@link PlaceholderPosition#STRING_LITERAL}: the value is escaped for that literal
     *       (Map/Collection/array first serialised to JSON, exactly as the template engine does).
     *       This is the shape every code body that templates a value is written in.</li>
     *   <li>{@link PlaceholderPosition#CODE} with a Map/Collection/array value in a language whose
     *       object literal syntax accepts JSON (JavaScript, TypeScript, Python): the JSON is
     *       spliced RAW, which is byte for byte what this node produced before the escaping was
     *       added. It is safe without escaping because JSON encoding is self-escaping - no quote,
     *       backslash or newline of the value survives unescaped inside the JSON string it sits
     *       in, so the value cannot leave the literal it is serialised into. Escaping it instead
     *       would emit {@code const o = {\"a\":1};}, a syntax error, and would break saved
     *       workflows that were working.</li>
     *   <li>{@link PlaceholderPosition#F_STRING_LITERAL}: as above, plus the braces of the value
     *       are doubled, which is how an f-string spells a literal brace. Python evaluates the
     *       {@code {...}} spans of an f-string, so without this a value could carry its own
     *       expression into the interpolation without using a single quote.</li>
     *   <li>{@link PlaceholderPosition#SHELL_DOUBLE_QUOTED}: the author wrote the delimiters, so
     *       the value is escaped IN PLACE for the four characters a double-quoted bash word
     *       expands. Re-quoting it instead, which is what the code-position splice does, changed
     *       what an existing node PRINTS: {@code echo "{{...}}"} started emitting the value
     *       wrapped in literal single quotes.</li>
     *   <li>{@link PlaceholderPosition#CODE} otherwise (a scalar, and a bare or substituted
     *       position in Bash): the value is spliced as a COMPLETE literal of the target language,
     *       never as bare text. See
     *       {@link #spliceAtCodePosition}. Escaping it as if it sat inside a literal, which is
     *       what this used to do, left the door open: the escape only neutralises a handful of
     *       characters, so a payload carrying none of them was spliced verbatim at a position
     *       that IS source and ran (LC-018).</li>
     * </ul>
     */
    private String resolveOnePlaceholder(BodyPlaceholder placeholder, String language, ExecutionContext context) {
        Object value;
        try {
            Map<String, Object> out = templateAdapter.resolveTemplates(
                Map.of("__expr__", placeholder.expression()), context);
            value = out.get("__expr__");
        } catch (Exception e) {
            logger.warn("Code body placeholder '{}' could not be resolved: {}",
                placeholder.expression(), e.getMessage());
            return placeholder.expression();
        }
        if (value == null) {
            return "";
        }
        if (placeholder.position() == PlaceholderPosition.CODE) {
            return spliceAtCodePosition(value, language);
        }
        String text = isStructured(value) ? JsonOutputUtil.encode(value) : String.valueOf(value);
        switch (placeholder.position()) {
            case SHELL_ANSI_C_QUOTED -> {
                return text.replace("\\", "\\\\").replace("'", "\\'");
            }
            case SHELL_HEREDOC_UNQUOTED -> {
                return defuseHeredocDelimiter(
                    text.replace("\\", "\\\\").replace("$", "\\$").replace("`", "\\`"),
                    placeholder.heredocDelimiter());
            }
            case SHELL_HEREDOC_QUOTED -> {
                return defuseHeredocDelimiter(text, placeholder.heredocDelimiter());
            }
            default -> { }
        }
        if (placeholder.position() == PlaceholderPosition.SHELL_DOUBLE_QUOTED) {
            // Inside a double-quoted bash word the author already wrote the delimiters, so the
            // value is escaped IN PLACE rather than re-quoted: re-quoting would emit
            // echo "'hello'" for the everyday echo "{{...}}" and change what the node prints.
            return escapeInsideDoubleQuotedShellWord(text);
        }
        String escaped = escapeCodeValue(text, language);
        if (placeholder.position() == PlaceholderPosition.F_STRING_LITERAL) {
            // Python evaluates {...} inside an f-string, so a brace coming from the value has to
            // be doubled: {{ and }} are how an f-string spells a literal brace. Without this the
            // value carries its own expression into the interpolation and it is evaluated, which
            // needs no quote at all to be a payload.
            return escaped.replace("{", "{{").replace("}", "}}");
        }
        return escaped;
    }

    /**
     * Escape a resolved value so it stays DATA inside the double-quoted bash word the author
     * wrote around the placeholder.
     *
     * <p>A double-quoted word treats exactly four characters as special, and a backslash before
     * each of them is the escape the shell accepts there: {@code \} itself, {@code "} (which would
     * close the word), {@code $} (parameter and command expansion) and a backtick (the older
     * command substitution). Nothing else inside the word is source, so escaping those four is
     * complete: a value carrying {@code $(id)} arrives as the eight characters, not as a command.
     *
     * <p>The backslash pass runs FIRST, or it would double the backslashes this method adds.
     */
    static String escapeInsideDoubleQuotedShellWord(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`");
    }

    /**
     * A here-document ends at the first line that equals its delimiter (leading tabs stripped for
     * {@code <<-}). A value carrying such a line would end the document early and everything the
     * author wrote after the placeholder would run as commands. Every value line that would match
     * gets a leading space, which no delimiter line can carry. Only that pathological line changes.
     */
    static String defuseHeredocDelimiter(String value, String delimiter) {
        if (value == null || value.isEmpty() || delimiter == null || delimiter.isEmpty()) {
            return value == null ? "" : value;
        }
        String[] lines = value.split("\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].replaceFirst("^\t+", "");
            if (stripped.equals(delimiter) || stripped.equals(delimiter + "\r")) {
                lines[i] = " " + lines[i];
                changed = true;
            }
        }
        return changed ? String.join("\n", lines) : value;
    }

    /**
     * JSON writer for CODE positions. Standard JSON leaves {@code /}, quote characters other than
     * {@code "}, {@code $}, braces and U+2028/U+2029 raw inside a string, and each of those is
     * structure SOMEWHERE a code position can be: {@code *}{@code /} closes a JavaScript block
     * comment, {@code /} closes a regex literal, U+2028 ends a JavaScript line comment, a quote
     * closes a literal the scanner did not see, {@code ${} opens a template interpolation, a brace
     * opens a Python f-string field, {@code <!--} opens a JavaScript HTML-like comment. Those
     * characters ({@code / ' ` $ { } < >}, U+2028, U+2029) are therefore written as a
     * {@code \}{@code uXXXX} escape INSIDE the JSON strings (never in the structure), which
     * JavaScript, TypeScript and Python all decode back to the same character. The value is
     * unchanged; only its spelling in the source is.
     */
    private static final ObjectMapper CODE_LITERAL_MAPPER = createCodeLiteralMapper();

    private static ObjectMapper createCodeLiteralMapper() {
        ObjectMapper mapper = JsonOutputUtil.mapper().copy();
        mapper.getFactory().setCharacterEscapes(new CodeLiteralEscapes());
        return mapper;
    }

    private static final class CodeLiteralEscapes extends com.fasterxml.jackson.core.io.CharacterEscapes {
        private static final String ESCAPED_PUNCTUATION = "/'`${}<>";
        private final int[] asciiEscapes;

        CodeLiteralEscapes() {
            asciiEscapes = com.fasterxml.jackson.core.io.CharacterEscapes.standardAsciiEscapesForJSON();
            for (int i = 0; i < ESCAPED_PUNCTUATION.length(); i++) {
                asciiEscapes[ESCAPED_PUNCTUATION.charAt(i)] = ESCAPE_STANDARD;
            }
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return asciiEscapes;
        }

        @Override
        public com.fasterxml.jackson.core.SerializableString getEscapeSequence(int ch) {
            if (ch == 0x2028 || ch == 0x2029) {
                return new com.fasterxml.jackson.core.io.SerializedString(String.format("\\u%04x", ch));
            }
            return null;
        }
    }

    /** Map, Collection or array: the shapes serialised as JSON rather than as their string form. */
    private static boolean isStructured(Object value) {
        return value instanceof Map || value instanceof Collection || value.getClass().isArray();
    }

    /**
     * Splice a resolved value at a position that IS source, so it stays DATA there.
     *
     * <p>Escaping cannot help at a code position: there is no surrounding literal to stay inside.
     * The value therefore has to ARRIVE as a literal, complete with its own delimiters. Three
     * cases, in order:
     * <ul>
     *   <li>a value whose text is a plain number or {@code true}/{@code false} is spliced bare.
     *       Those characters cannot form an operator, a call or a statement separator in any of
     *       the four languages, and this is byte for byte what the node already produced for
     *       them, so the saved workflows that splice a count or a flag keep working.</li>
     *   <li>JavaScript, TypeScript and Python take the JSON encoding, for structured values (what
     *       the node already did) and now for scalars too. JSON is self-escaping: a string
     *       arrives as {@code "..."} with every quote, backslash and newline of the value escaped
     *       INSIDE it, so the value cannot leave the literal it is serialised into. This is the
     *       half that was missing: a scalar with no quote, backslash, backtick, {@code $} or
     *       newline in it, say a bare identifier or a call, passed the old escape untouched and
     *       was executed.</li>
     *   <li>Bash takes a single-quoted word, the only bash form that expands nothing at all
     *       ({@code $}, a backtick and {@code \} all survive an unquoted splice and are how a
     *       value becomes a command there). JSON is not usable: an unquoted {@code $(...)}
     *       inside it would be command-substituted. A value INSIDE a double-quoted word never
     *       reaches this method - it is escaped in place, see
     *       {@link #escapeInsideDoubleQuotedShellWord}.</li>
     * </ul>
     *
     * <p>Python spells the JSON literals {@code true}, {@code false} and {@code null} as
     * {@code True}, {@code False} and {@code None}; everything else JSON writes (objects, arrays,
     * strings with the escapes above, numbers) is Python as it stands. Values spliced into Python
     * are written that way at every depth ({@link #pythonLiteral}).
     *
     * <p>Workflow validate warns about a body that reaches this method ({@code CoreValidator},
     * CODE_TEMPLATE_IN_BODY) but saves it, so this is what runs for every such plan.
     */
    String spliceAtCodePosition(Object value, String language) {
        boolean structured = isStructured(value);
        boolean python = isPython(language);
        String text = structured ? JsonOutputUtil.encode(value) : String.valueOf(value);
        if (!structured && jsonIsALiteralIn(language)) {
            // A JSON object or array carried as TEXT (an agent's response, an HTTP body) was spliced
            // raw before LC-018 and became an object: saved code like `const r = {{...}}; r.field`
            // depends on that. Parsed and re-serialised, so only JSON data can come out of it.
            com.fasterxml.jackson.databind.JsonNode container = jsonContainerOrNull(text);
            String literal = container == null ? null : codeLiteralOrNull(container, python);
            if (literal != null) {
                return literal;
            }
        }
        if (!structured && isBareLiteralSafeAtCodePosition(text)) {
            return python && ("true".equals(text) || "false".equals(text))
                    ? ("true".equals(text) ? "True" : "False")
                    : text;
        }
        if (jsonIsALiteralIn(language)) {
            String json = python ? pythonLiteralOrNull(value) : jsonLiteralOrNull(value);
            if (json == null) {
                // Not serialisable as itself: splice its string form, still through the same
                // escaping writer (a String always serialises).
                json = jsonLiteralOrNull(text);
            }
            if (json != null) {
                return json;
            }
        }
        // Bash, and the languages above when the value could not be JSON-encoded: a single-quoted
        // word. escapeForString applies the target language's own escape for that quote form.
        return "'" + escapeForString(text, language) + "'";
    }

    /**
     * True for the text forms that are a self-contained literal in all four languages and carry
     * no character that could act as source: a decimal number, or a boolean. Deliberately narrow.
     */
    private static boolean isBareLiteralSafeAtCodePosition(String text) {
        return "true".equals(text) || "false".equals(text) || NUMERIC_LITERAL.matcher(text).matches();
    }

    private static final Pattern NUMERIC_LITERAL =
        Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

    /**
     * The JSON encoding of a value, or {@code null} when Jackson could not produce one.
     *
     * <p>{@link JsonOutputUtil#encode} falls back to {@code String.valueOf} on a serialisation
     * failure, which is exactly what must NOT be spliced at a code position: it would put raw
     * text there again. So the fallback is detected and reported as "no JSON", and the caller
     * quotes the value instead.
     */
    private static String jsonLiteralOrNull(Object value) {
        try {
            return CODE_LITERAL_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            logger.warn("Code body value could not be JSON-encoded, splicing it as a quoted word: {}",
                e.getMessage());
            return null;
        }
    }

    /**
     * The parsed tree of {@code text} when it is exactly one JSON object or array, else
     * {@code null}. The caller re-serialises it: whatever the text contained, what is spliced is
     * a literal and nothing else, so it cannot carry source into the code body. Parsed with the
     * node's bounded mapper (string, document and nesting caps), like every other JSON it reads.
     */
    private static com.fasterxml.jackson.databind.JsonNode jsonContainerOrNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
            return null;
        }
        try {
            // The WHOLE text must be the JSON value: `{"a":1}; more` is not data, it stays a string.
            com.fasterxml.jackson.databind.JsonNode node = JsonOutputUtil.mapper().reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(trimmed);
            return node != null && (node.isObject() || node.isArray()) ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code node} as a literal of the target language (Python or JSON), or null when it cannot be written. */
    private static String codeLiteralOrNull(com.fasterxml.jackson.databind.JsonNode node, boolean python) {
        try {
            return python ? pythonLiteral(node) : CODE_LITERAL_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }

    /** The Python literal of a value, through its JSON tree; null when it has no JSON form. */
    private static String pythonLiteralOrNull(Object value) {
        try {
            return pythonLiteral(CODE_LITERAL_MAPPER.valueToTree(value));
        } catch (Exception e) {
            logger.warn("Code body value could not be written as a Python literal, splicing it as a quoted word: {}",
                e.getMessage());
            return null;
        }
    }

    /**
     * Python spelling of a JSON tree: {@code True}, {@code False} and {@code None} for the JSON
     * literals, at every depth; objects, arrays, strings and numbers as the escaping writer spells
     * them, which Python reads unchanged (every escape it writes is also a Python string escape).
     */
    static String pythonLiteral(com.fasterxml.jackson.databind.JsonNode node)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "None";
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? "True" : "False";
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(pythonLiteral(node.get(i)));
            }
            return sb.append(']').toString();
        }
        if (node.isObject()) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var field : (Iterable<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>>) node::fields) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(CODE_LITERAL_MAPPER.writeValueAsString(field.getKey()))
                  .append(':')
                  .append(pythonLiteral(field.getValue()));
            }
            return sb.append('}').toString();
        }
        return CODE_LITERAL_MAPPER.writeValueAsString(node);
    }

    private static boolean isPython(String language) {
        String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        return "python".equals(lang) || "python3".equals(lang);
    }

    /** True for the languages whose own object/array literal syntax is a superset of JSON. */
    private static boolean jsonIsALiteralIn(String language) {
        String lang = language == null ? "javascript" : language.toLowerCase(Locale.ROOT);
        return switch (lang) {
            case "javascript", "node", "typescript", "python", "python3" -> true;
            default -> false;
        };
    }

    // ===== Placeholder scanning (shared with the plan-save guard) =====

    /**
     * Every {{...}} placeholder in a code body, in source order, each tagged with the position it
     * occupies. Uses the engine's own expression pattern, so authoring and execution agree on what
     * a placeholder is: a doubled brace the engine would not resolve, such as one holding a nested
     * closing brace, is not reported.
     *
     * @param language decides the literal rules; an unknown language is scanned as JavaScript,
     *                 which is also what {@code Core.CodeConfig} defaults to
     */
    public static List<BodyPlaceholder> scanCodeBody(String body, String language) {
        if (body == null || body.isEmpty() || !body.contains("{{")) {
            return List.of();
        }
        String lang = language == null ? "javascript" : language.toLowerCase(Locale.ROOT);
        PlaceholderPosition[] positions;
        String[] heredocDelimiters = null;
        if (isBash(lang)) {
            BashMask bash = bashScan(body);
            positions = bash.positions();
            heredocDelimiters = bash.heredocDelimiters();
        } else {
            positions = positionMask(body, language);
        }
        List<BodyPlaceholder> found = new ArrayList<>();
        Matcher matcher = TEMPLATE_EXPRESSION.matcher(body);
        while (matcher.find()) {
            int start = matcher.start();
            found.add(new BodyPlaceholder(matcher.group(), start, matcher.end(), positions[start],
                heredocDelimiters == null ? null : heredocDelimiters[start]));
        }
        return found;
    }

    /** True when the body carries an expression the template engine would resolve. */
    public static boolean hasTemplateExpression(String body) {
        return body != null && body.contains("{{") && TEMPLATE_EXPRESSION.matcher(body).find();
    }

    /**
     * The first {{...}} expression the body carries outside any string literal, or {@code null}.
     * This is the shape no escaping can neutralise, so it is what the builder warns about most strongly.
     */
    public static String firstPlaceholderOutsideStringLiteral(String body, String language) {
        for (BodyPlaceholder placeholder : scanCodeBody(body, language)) {
            if (placeholder.position() == PlaceholderPosition.CODE) {
                return placeholder.expression();
            }
        }
        return null;
    }

    /**
     * The sentence appended to a failing code node whose body carries a placeholder at a code
     * position. Without it the author sees only the sandbox's parser error and no reason why a
     * body that used to run stopped running.
     */
    public static String unsafePlaceholderHint(String expression) {
        return "This code body carries the expression " + expression + " outside any string literal. "
            + "Only a placeholder written inside a literal can be escaped into data, so at a code position "
            + "the resolved value is spliced as a complete literal instead (an object, a list or a text "
            + "holding exactly one JSON object or array as a literal: JSON, or True/False/None in python; a number or a boolean as itself; anything else as a quoted string) and does not produce the "
            + "code it reads like. Read upstream outputs from the input object instead: "
            + "$input.<predecessor label>.<field> in javascript and typescript, "
            + "_input['<predecessor label>']['<field>'] in python, INPUT (JSON) in bash.";
    }

    /**
     * For each character of the body, the {@link PlaceholderPosition} it occupies in the target
     * language. Conservative by construction: an unterminated literal keeps its remainder marked,
     * and any position the scanner does not recognise as a literal is reported as code, so a
     * misread can only make the guard stricter, never let a value through unescaped.
     */
    static PlaceholderPosition[] positionMask(String body, String language) {
        String lang = language == null ? "javascript" : language.toLowerCase(Locale.ROOT);
        return switch (lang) {
            case "python", "python3" -> pythonPositionMask(body);
            case "bash", "shell", "sh" -> bashScan(body).positions();
            default -> asPositions(javaScriptLiteralMask(body));
        };
    }

    /** In-literal becomes STRING_LITERAL, everything else CODE. */
    private static PlaceholderPosition[] asPositions(boolean[] inLiteral) {
        PlaceholderPosition[] positions = new PlaceholderPosition[inLiteral.length];
        for (int i = 0; i < inLiteral.length; i++) {
            positions[i] = inLiteral[i] ? PlaceholderPosition.STRING_LITERAL : PlaceholderPosition.CODE;
        }
        return positions;
    }

    /**
     * JavaScript and TypeScript: single, double and backtick literals count. A {@code ${...}}
     * interpolation inside a backtick literal is code again, and comments are code positions too
     * (nothing escapes a block-comment terminator inside a resolved value).
     *
     * <p>Regex literals are tracked as well, and their contents are reported as CODE (a
     * placeholder inside a regex is source, exactly like one inside a comment). Not tracking them
     * was a hole, not a simplification: a quote inside a regex character class, the everyday
     * {@code /['"]/}, opened a string literal that never closed, and every position after it in
     * the body was then reported as being inside that literal. A placeholder sitting at a real
     * code position downstream was therefore classified as a string one, so the run escaped it as
     * if quotes surrounded it.
     */
    private static boolean[] javaScriptLiteralMask(String body) {
        final int CODE = 0, SINGLE = 1, DOUBLE = 2, BACKTICK = 3, LINE_COMMENT = 4, BLOCK_COMMENT = 5,
                  REGEX = 6;
        int n = body.length();
        boolean[] mask = new boolean[n];
        Deque<Integer> interpolationReturn = new ArrayDeque<>();
        int braceDepth = 0;
        boolean inRegexCharClass = false;
        int state = CODE;
        int i = 0;
        while (i < n) {
            char c = body.charAt(i);
            char next = i + 1 < n ? body.charAt(i + 1) : '\0';
            if (state == CODE) {
                if (c == '/' && next == '/') { state = LINE_COMMENT; i += 2; }
                else if (c == '/' && next == '*') { state = BLOCK_COMMENT; i += 2; }
                else if (c == '/' && regexCanStartAt(body, i)) {
                    state = REGEX;
                    inRegexCharClass = false;
                    i++;
                }
                else if (c == '\'') { state = SINGLE; i++; }
                else if (c == '"') { state = DOUBLE; i++; }
                else if (c == '`') { state = BACKTICK; i++; }
                else if (c == '{') { braceDepth++; i++; }
                else if (c == '}') {
                    if (!interpolationReturn.isEmpty() && braceDepth == interpolationReturn.peek()) {
                        interpolationReturn.pop();
                        state = BACKTICK;
                    } else if (braceDepth > 0) {
                        braceDepth--;
                    }
                    i++;
                } else {
                    i++;
                }
            } else if (state == SINGLE || state == DOUBLE || state == BACKTICK) {
                char quote = state == SINGLE ? '\'' : state == DOUBLE ? '"' : '`';
                if (c == '\\' && i + 1 < n) {
                    mask[i] = true;
                    mask[i + 1] = true;
                    i += 2;
                } else if (c == quote) {
                    state = CODE;
                    i++;
                } else if (state == BACKTICK && c == '$' && next == '{') {
                    interpolationReturn.push(braceDepth);
                    state = CODE;
                    i += 2;
                } else {
                    mask[i] = true;
                    i++;
                }
            } else if (state == REGEX) {
                // Contents stay mask=false: a regex body is a code position by definition.
                if (c == '\\' && i + 1 < n) { i += 2; }
                else if (c == '\n') { state = CODE; i++; }   // unterminated: bail out as code
                else if (c == '[') { inRegexCharClass = true; i++; }
                else if (c == ']') { inRegexCharClass = false; i++; }
                else if (c == '/' && !inRegexCharClass) { state = CODE; i++; }
                else { i++; }
            } else if (state == LINE_COMMENT) {
                if (c == '\n') state = CODE;
                i++;
            } else { // BLOCK_COMMENT
                if (c == '*' && next == '/') { state = CODE; i += 2; } else { i++; }
            }
        }
        return mask;
    }

    /**
     * Whether the {@code /} at {@code index} opens a regex literal rather than being a division.
     *
     * <p>Decided from the last non-whitespace character before it, which is the standard rule: a
     * division can only follow an operand, so an operand-ish character ({@code )}, {@code ]}, an
     * identifier, a number) means division, and anything else means a regex is starting. The two
     * corrections that matter in real code are a keyword ({@code return /re/}, where the previous
     * character is an identifier one but a division is impossible) and a postfix {@code ++} /
     * {@code --} (where the previous character is an operator one but a division is exactly what
     * follows).
     *
     * <p>Getting this wrong in the "division read as a regex" direction cannot create the hole it
     * exists to close: it makes the scanner report MORE code positions, which the builder warns
     * about and the runtime splices as a complete literal. It is only wrong in the permissive
     * direction, a regex read as a division, that quotes inside the regex desynchronise the mask.
     */
    private static boolean regexCanStartAt(String body, int index) {
        int i = index - 1;
        while (i >= 0 && Character.isWhitespace(body.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return true;   // first token of the body
        }
        char p = body.charAt(i);
        if (p == ')' || p == ']') {
            return false;  // end of an operand: a division
        }
        if ((p == '+' || p == '-') && i > 0 && body.charAt(i - 1) == p) {
            return false;  // postfix ++ / --, so a division follows
        }
        if (Character.isLetterOrDigit(p) || p == '_' || p == '$') {
            return REGEX_ALLOWING_KEYWORDS.contains(trailingWord(body, i));
        }
        return true;
    }

    /** Keywords a regex literal may directly follow, where the character before {@code /} is an identifier one. */
    private static final Set<String> REGEX_ALLOWING_KEYWORDS = Set.of(
        "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw",
        "case", "do", "else", "yield", "await");

    /** The identifier ending at {@code end} (inclusive), or "" when it ends in a digit. */
    private static String trailingWord(String body, int end) {
        int start = end;
        while (start >= 0) {
            char c = body.charAt(start);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                start--;
            } else {
                break;
            }
        }
        return body.substring(start + 1, end + 1);
    }

    /**
     * Python: {@code '}, {@code "}, {@code '''} and {@code """} literals count; {@code #} starts a
     * comment. A literal opened with an {@code f} prefix is reported as
     * {@link PlaceholderPosition#F_STRING_LITERAL} instead, because Python evaluates the
     * {@code {...}} spans inside it: a value spliced there is escaped as a string AND has its
     * braces doubled, or it would carry its own expression into the interpolation.
     */
    private static PlaceholderPosition[] pythonPositionMask(String body) {
        final int CODE = 0, SINGLE = 1, DOUBLE = 2, TRIPLE_SINGLE = 3, TRIPLE_DOUBLE = 4, COMMENT = 5;
        int n = body.length();
        PlaceholderPosition[] mask = new PlaceholderPosition[n];
        Arrays.fill(mask, PlaceholderPosition.CODE);
        int state = CODE;
        boolean formatted = false;
        int i = 0;
        while (i < n) {
            char c = body.charAt(i);
            PlaceholderPosition inside = formatted
                ? PlaceholderPosition.F_STRING_LITERAL : PlaceholderPosition.STRING_LITERAL;
            if (state == CODE) {
                if (startsWith(body, i, "'''")) { formatted = isFStringPrefix(body, i); state = TRIPLE_SINGLE; i += 3; }
                else if (startsWith(body, i, "\"\"\"")) { formatted = isFStringPrefix(body, i); state = TRIPLE_DOUBLE; i += 3; }
                else if (c == '\'') { formatted = isFStringPrefix(body, i); state = SINGLE; i++; }
                else if (c == '"') { formatted = isFStringPrefix(body, i); state = DOUBLE; i++; }
                else if (c == '#') { state = COMMENT; i++; }
                else i++;
            } else if (state == COMMENT) {
                if (c == '\n') state = CODE;
                i++;
            } else {
                boolean triple = state == TRIPLE_SINGLE || state == TRIPLE_DOUBLE;
                String close = state == TRIPLE_SINGLE ? "'''" : state == TRIPLE_DOUBLE ? "\"\"\""
                    : state == SINGLE ? "'" : "\"";
                if (c == '\\' && i + 1 < n) { mask[i] = inside; mask[i + 1] = inside; i += 2; }
                else if (startsWith(body, i, close)) { state = CODE; i += close.length(); }
                else if (!triple && c == '\n') { state = CODE; i++; }
                else if (formatted && (startsWith(body, i, "{{") || startsWith(body, i, "}}"))) {
                    // A doubled brace is how an f-string spells a literal brace: still text.
                    mask[i] = inside; mask[i + 1] = inside; i += 2;
                }
                else if (formatted && c == '{') {
                    // A REPLACEMENT FIELD: Python evaluates it, so its interior is source (LC-018).
                    // Treating it as part of the literal let a value spliced there run as an
                    // expression. Brace depth is tracked so a nested dict or a placeholder's own
                    // doubled braces do not end the field early.
                    i = markPythonReplacementField(body, i, mask);
                }
                else { mask[i] = inside; i++; }
            }
        }
        return mask;
    }

    /**
     * Marks an f-string replacement field starting at the {@code {} at {@code open} as CODE and
     * returns the index just after its closing brace (or the end of the body when it never
     * closes). Quotes inside the field are skipped over as nested literals so a brace inside
     * them does not count.
     */
    private static int markPythonReplacementField(String body, int open, PlaceholderPosition[] mask) {
        int n = body.length();
        int depth = 0;
        int i = open;
        while (i < n) {
            char c = body.charAt(i);
            mask[i] = PlaceholderPosition.CODE;
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n && body.charAt(j) != c && body.charAt(j) != '\n') {
                    if (body.charAt(j) == '\\') j++;
                    j++;
                }
                for (int k = i; k <= Math.min(j, n - 1); k++) mask[k] = PlaceholderPosition.CODE;
                i = j + 1;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
            else if (c == '\n' && depth <= 0) return i;
            i++;
        }
        return n;
    }

    /**
     * Whether the quote at {@code quoteIndex} is preceded by a Python string prefix containing
     * {@code f}. Reads the identifier characters immediately before the quote: a real prefix is at
     * most three letters drawn from r, b, f and u, so a longer or foreign run is not one.
     */
    private static boolean isFStringPrefix(String body, int quoteIndex) {
        int start = quoteIndex;
        while (start > 0 && Character.isLetter(body.charAt(start - 1))) {
            start--;
        }
        String prefix = body.substring(start, quoteIndex);
        if (prefix.isEmpty() || prefix.length() > 3) {
            return false;
        }
        boolean hasF = false;
        for (int i = 0; i < prefix.length(); i++) {
            char c = Character.toLowerCase(prefix.charAt(i));
            if (c == 'f') {
                hasF = true;
            } else if (c != 'r' && c != 'b' && c != 'u') {
                return false;
            }
        }
        return hasF;
    }

    private static boolean isBash(String lang) {
        return "bash".equals(lang) || "shell".equals(lang) || "sh".equals(lang);
    }

    /** Bash scan result: a position per character, plus the heredoc delimiter where relevant. */
    record BashMask(PlaceholderPosition[] positions, String[] heredocDelimiters) {}

    /** One here-document announced on the current command line, waiting for its body. */
    private record PendingHeredoc(String delimiter, boolean quoted, boolean stripTabs) {}

    /**
     * Bash: a single-quoted word is {@link PlaceholderPosition#STRING_LITERAL} (it expands
     * nothing at all), a double-quoted word is {@link PlaceholderPosition#SHELL_DOUBLE_QUOTED}
     * (a literal whose {@code $}, backtick and {@code \} still have to be escaped), an ANSI-C word
     * {@code $'...'} is {@link PlaceholderPosition#SHELL_ANSI_C_QUOTED} (backslash escapes, so
     * {@code \'} does NOT end it), a here-document body is
     * {@link PlaceholderPosition#SHELL_HEREDOC_UNQUOTED} or
     * {@link PlaceholderPosition#SHELL_HEREDOC_QUOTED} depending on its delimiter, and everything
     * else is {@link PlaceholderPosition#CODE}.
     *
     * <p>Here-documents matter because their body is neither code nor a quoted word: quote
     * characters are plain text there, so a quote in the body used to open a "single-quoted word"
     * in this scanner, a placeholder after it was escaped with the single-quote idiom, and an
     * unquoted document then expanded the {@code $(...)} the value carried (LC-018). Arithmetic
     * ({@code $((...))} and {@code ((...))}) is tracked so a shift operator is not read as a
     * here-document, and an all-digit word after {@code <<} is not taken as a delimiter.
     *
     * <p>The spans a double-quoted word or an unquoted here-document can OPEN ({@code $(...)},
     * {@code ${...}}, a backtick pair) are code: their contents stay CODE.
     */
    static BashMask bashScan(String body) {
        final char TOP = 'c', PAREN = 'p', BRACE = 'e', BACKTICK = 'b', ARITH = 'a',
                   SINGLE = 's', DOUBLE = 'd', ANSI = 'q', COMMENT = '#';
        int n = body.length();
        PlaceholderPosition[] mask = new PlaceholderPosition[n];
        String[] delims = new String[n];
        Arrays.fill(mask, PlaceholderPosition.CODE);
        Deque<Character> stack = new ArrayDeque<>();
        List<PendingHeredoc> pending = new ArrayList<>();
        stack.push(TOP);
        int i = 0;
        while (i < n) {
            char c = body.charAt(i);
            char next = i + 1 < n ? body.charAt(i + 1) : '\0';
            char next2 = i + 2 < n ? body.charAt(i + 2) : '\0';
            char top = stack.peek() == null ? TOP : stack.peek();
            if (top == SINGLE) {
                // Single quotes take no escapes at all: the next ' always ends the word.
                if (c == '\'') { stack.pop(); i++; }
                else { mask[i] = PlaceholderPosition.STRING_LITERAL; i++; }
            } else if (top == ANSI) {
                if (c == '\\' && i + 1 < n) {
                    mask[i] = PlaceholderPosition.SHELL_ANSI_C_QUOTED;
                    mask[i + 1] = PlaceholderPosition.SHELL_ANSI_C_QUOTED;
                    i += 2;
                }
                else if (c == '\'') { stack.pop(); i++; }
                else { mask[i] = PlaceholderPosition.SHELL_ANSI_C_QUOTED; i++; }
            } else if (top == COMMENT) {
                if (c == '\n') {
                    stack.pop();
                    // The newline that ends a comment still starts pending heredoc bodies.
                    continue;
                }
                i++;
            } else if (top == DOUBLE) {
                if (c == '\\' && i + 1 < n) {
                    mask[i] = PlaceholderPosition.SHELL_DOUBLE_QUOTED;
                    mask[i + 1] = PlaceholderPosition.SHELL_DOUBLE_QUOTED;
                    i += 2;
                }
                else if (c == '"') { stack.pop(); i++; }
                else if (c == '$' && next == '(' && next2 == '(') { stack.push(ARITH); i += 3; }
                else if (c == '$' && next == '(') { stack.push(PAREN); i += 2; }
                else if (c == '$' && next == '{') { stack.push(BRACE); i += 2; }
                else if (c == '`') { stack.push(BACKTICK); i++; }
                else { mask[i] = PlaceholderPosition.SHELL_DOUBLE_QUOTED; i++; }
            } else { // TOP, PAREN, BRACE, BACKTICK or ARITH: a code context
                if (c == '\n' && !pending.isEmpty()) {
                    i = markHeredocBodies(body, i + 1, pending, mask, delims);
                    pending.clear();
                }
                else if (c == '\\' && i + 1 < n) { i += 2; }
                else if (c == '\'') { stack.push(SINGLE); i++; }
                else if (c == '"') { stack.push(DOUBLE); i++; }
                else if (c == '$' && next == '\'') { stack.push(ANSI); i += 2; }
                else if (c == '$' && next == '(' && next2 == '(') { stack.push(ARITH); i += 3; }
                else if (c == '(' && next == '(' && top != ARITH) { stack.push(ARITH); i += 2; }
                else if (c == '$' && next == '(') { stack.push(PAREN); i += 2; }
                else if (c == '$' && next == '{') { stack.push(BRACE); i += 2; }
                else if (c == '`') {
                    if (top == BACKTICK) stack.pop(); else stack.push(BACKTICK);
                    i++;
                }
                else if (top == ARITH && c == ')' && next == ')') { stack.pop(); i += 2; }
                else if (c == '(' && top == ARITH) { stack.push(ARITH); i++; }
                else if (c == ')' && top == PAREN) { stack.pop(); i++; }
                else if (c == ')' && top == ARITH) { stack.pop(); i++; }
                else if (c == '}' && top == BRACE) { stack.pop(); i++; }
                else if (c == '<' && next == '<' && next2 != '<' && top != ARITH) {
                    i = readHeredocOperator(body, i + 2, pending);
                }
                else if (c == '<' && next == '<' && next2 == '<') { i += 3; }
                else if (c == '#' && (i == 0 || Character.isWhitespace(body.charAt(i - 1)))) {
                    stack.push(COMMENT); i++;
                }
                else i++;
            }
        }
        return new BashMask(mask, delims);
    }

    /**
     * Parses the word after {@code <<} ({@code -} for tab stripping, optional blanks, then the
     * delimiter word) and queues the here-document. Any quoting in the word makes the body
     * literal. An empty or all-digit word is not treated as a delimiter (arithmetic-looking
     * shifts outside {@code ((...))}), which keeps the rest of the body scanned as code.
     *
     * @return the index just after the delimiter word
     */
    private static int readHeredocOperator(String body, int i, List<PendingHeredoc> pending) {
        int n = body.length();
        boolean stripTabs = false;
        if (i < n && body.charAt(i) == '-') { stripTabs = true; i++; }
        while (i < n && (body.charAt(i) == ' ' || body.charAt(i) == '\t')) i++;
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        while (i < n) {
            char c = body.charAt(i);
            if (Character.isWhitespace(c) || ";|&<>()".indexOf(c) >= 0) break;
            if (c == '\'' || c == '"') {
                quoted = true;
                int close = body.indexOf(c, i + 1);
                if (close < 0) close = n - 1;
                word.append(body, i + 1, Math.max(i + 1, close));
                i = close + 1;
                continue;
            }
            if (c == '\\' && i + 1 < n) { quoted = true; word.append(body.charAt(i + 1)); i += 2; continue; }
            word.append(c);
            i++;
        }
        String delimiter = word.toString();
        if (!delimiter.isEmpty() && !delimiter.chars().allMatch(Character::isDigit)) {
            pending.add(new PendingHeredoc(delimiter, quoted, stripTabs));
        }
        return i;
    }

    /**
     * Marks the bodies of the queued here-documents, one after the other, starting at
     * {@code start} (the character after the newline that ended the command line), and returns
     * the index just after the last delimiter line. An unterminated document runs to the end of
     * the body, which is also what the shell does.
     */
    private static int markHeredocBodies(String body, int start, List<PendingHeredoc> pending,
                                         PlaceholderPosition[] mask, String[] delims) {
        int n = body.length();
        int i = start;
        for (PendingHeredoc doc : pending) {
            while (i < n) {
                int lineEnd = body.indexOf('\n', i);
                if (lineEnd < 0) lineEnd = n;
                String line = body.substring(i, lineEnd);
                String compared = doc.stripTabs() ? line.replaceFirst("^\t+", "") : line;
                if (compared.equals(doc.delimiter()) || compared.equals(doc.delimiter() + "\r")) {
                    i = Math.min(n, lineEnd + 1);
                    break;
                }
                if (doc.quoted()) {
                    for (int k = i; k < lineEnd; k++) {
                        mask[k] = PlaceholderPosition.SHELL_HEREDOC_QUOTED;
                        delims[k] = doc.delimiter();
                    }
                } else {
                    markUnquotedHeredocLine(body, i, lineEnd, mask, delims, doc.delimiter());
                }
                i = Math.min(n, lineEnd + 1);
            }
        }
        return i;
    }

    /**
     * One line of an unquoted here-document: text is {@link PlaceholderPosition#SHELL_HEREDOC_UNQUOTED}
     * except the expansion spans it opens ({@code $(...)}, {@code ${...}}, a backtick pair), which
     * are code. A span that does not close on the line leaves the rest of the line as code.
     */
    private static void markUnquotedHeredocLine(String body, int from, int to, PlaceholderPosition[] mask,
                                                String[] delims, String delimiter) {
        int i = from;
        while (i < to) {
            char c = body.charAt(i);
            char next = i + 1 < to ? body.charAt(i + 1) : '\0';
            if (c == '\\' && i + 1 < to) {
                mask[i] = mask[i + 1] = PlaceholderPosition.SHELL_HEREDOC_UNQUOTED;
                delims[i] = delims[i + 1] = delimiter;
                i += 2;
            } else if ((c == '$' && (next == '(' || next == '{')) || c == '`') {
                char open = c == '`' ? '`' : next;
                char close = open == '(' ? ')' : open == '{' ? '}' : '`';
                int j = c == '`' ? i + 1 : i + 2;
                int depth = 1;
                while (j < to && depth > 0) {
                    char d = body.charAt(j);
                    if (close != '`' && d == open) depth++;
                    else if (d == close) depth--;
                    j++;
                }
                i = j; // mask stays CODE for the whole span (or the rest of the line)
            } else {
                mask[i] = PlaceholderPosition.SHELL_HEREDOC_UNQUOTED;
                delims[i] = delimiter;
                i++;
            }
        }
    }

    private static boolean startsWith(String body, int index, String token) {
        return body.startsWith(token, index);
    }

    /**
     * Escape a resolved value so it stays DATA inside the string literal the author wrote around
     * the placeholder, instead of becoming source. Builds on {@link #escapeForString} - the same
     * escape the injected {@code $input} JSON uses - and adds the quote forms the input map never
     * needs because it is always embedded in single quotes.
     *
     * <p>JS/TS also neutralise the backtick and {@code $} so a value cannot start a template
     * literal or an interpolation; both are identity escapes in a JavaScript string, so the value
     * itself is unchanged. Python omits them: {@code '\$'} keeps its backslash there, which would
     * corrupt the value. Bash keeps the {@code '\''} idiom, the only escape a single-quoted bash
     * string accepts.
     *
     * <p>This protects a placeholder written inside a string literal, which is how every code body
     * that templates a value is written. It is NOT what runs at a code position: escaping cannot
     * make a value inert where there is no literal around it, so {@link #spliceAtCodePosition}
     * gives the value its own delimiters there instead. That is also why {@code CoreValidator}
     * warns about a code body carrying a placeholder outside a string literal while an agent writes
     * the plan.
     */
    String escapeCodeValue(String value, String language) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String lang = language == null ? "javascript" : language.toLowerCase(Locale.ROOT);
        String escaped = escapeForString(value, lang);
        return switch (lang) {
            case "javascript", "node", "typescript" -> escaped
                .replace("\"", "\\\"")
                .replace("`", "\\`")
                .replace("$", "\\$");
            case "python", "python3" -> escaped.replace("\"", "\\\"");
            default -> escaped;
        };
    }

    /**
     * The failure message the run reports, with the placeholder-position explanation appended when
     * the body carries one. A body whose placeholder sits outside a string literal is the one shape
     * that stopped producing source when the resolved value started being escaped, and the sandbox
     * only reports its own parser error for it, which names a line, not a cause.
     */
    String explainedFailure(String message, String rawCode, String language) {
        String base = message == null ? "Code node failed" : message;
        String unsafe = firstPlaceholderOutsideStringLiteral(rawCode, language);
        if (unsafe == null) {
            return base;
        }
        return base + " | " + unsafePlaceholderHint(unsafe);
    }

    private Map<String, Object> buildInputDataMap(String language, String code, Object timeoutSeconds) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("language", language);
        inputData.put("codeLength", code != null ? code.length() : 0);
        inputData.put("timeoutSeconds", timeoutSeconds);
        return inputData;
    }

    // Getters
    public Core.CodeConfig getCodeConfig() { return codeConfig; }

    // Builder
    public static class Builder {
        private String nodeId;
        private Core.CodeConfig codeConfig;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder codeConfig(Core.CodeConfig codeConfig) { this.codeConfig = codeConfig; return this; }
        public CodeNode build() { return new CodeNode(nodeId, codeConfig); }
    }

    public static Builder builder() { return new Builder(); }
}
