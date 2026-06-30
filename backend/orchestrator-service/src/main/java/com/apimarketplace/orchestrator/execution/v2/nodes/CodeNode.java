package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.OutputUnwrapper;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.code.CodeExecutor;
import com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeRequest;
import com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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
 */
public class CodeNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(CodeNode.class);
    private static final String RESULT_PREFIX = "__RESULT__";
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final ObjectMapper objectMapper = new ObjectMapper();

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
        Map<String, Object> resolvedParams = buildInputDataMap(language, rawCode, timeoutSeconds);

        try {
            if (codeExecutor == null) {
                throw new IllegalStateException("CodeExecutor is not available. Ensure Piston or embedded executor is configured.");
            }

            String userCode = resolveExpression(rawCode, context);

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
            result.put("resolved_params", buildInputDataMap(language, userCode, timeoutSeconds));

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
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failureOutput, duration);
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
     * Resolve a SpEL expression using the template adapter.
     */
    private String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object result = resolved.get("__expr__");
                return result != null ? String.valueOf(result) : expression;
            } catch (Exception e) {
                logger.warn("Failed to resolve expression '{}': {}", expression, e.getMessage());
                return expression;
            }
        }

        return expression;
    }

    private Map<String, Object> buildInputDataMap(String language, String code, int timeoutSeconds) {
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
