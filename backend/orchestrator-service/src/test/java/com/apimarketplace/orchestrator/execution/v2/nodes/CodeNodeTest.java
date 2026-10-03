package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import com.apimarketplace.orchestrator.services.template.NamespaceResolver;
import com.apimarketplace.orchestrator.services.template.PathNavigator;
import com.apimarketplace.orchestrator.services.template.SpelEvaluator;
import com.apimarketplace.orchestrator.services.code.CodeExecutor;
import com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeRequest;
import com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CodeNode.
 * CodeNode executes user code in a sandboxed environment via Piston.
 *
 * Tests cover:
 * - Constructor and builder
 * - Code wrapping for each language (JS, Python, TS, Bash)
 * - Result extraction from stdout (__RESULT__ prefix)
 * - Console output extraction (non-__RESULT__ lines)
 * - Successful execution via mocked PistonClient
 * - Error handling (compilation errors, runtime errors, missing PistonClient)
 * - MANDATORY metadata fields
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CodeNode")
class CodeNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private CodeExecutor mockCodeExecutor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("name", "test");
        triggerData.put("value", 42);

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create CodeNode with nodeId and config")
        void shouldCreateCodeNodeWithNodeIdAndConfig() {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "console.log('hello');", 10);
            CodeNode node = new CodeNode("core:my_code", config);

            assertEquals("core:my_code", node.getNodeId());
            assertEquals(NodeType.CODE, node.getType());
            assertNotNull(node.getCodeConfig());
            assertEquals("javascript", node.getCodeConfig().language());
            assertEquals("console.log('hello');", node.getCodeConfig().code());
            assertEquals(10, node.getCodeConfig().timeoutSeconds());
        }

        @Test
        @DisplayName("Should handle null config")
        void shouldHandleNullConfig() {
            CodeNode node = new CodeNode("core:my_code", null);

            assertEquals("core:my_code", node.getNodeId());
            assertNull(node.getCodeConfig());
        }

        @Test
        @DisplayName("Should create via builder")
        void shouldCreateViaBuilder() {
            Core.CodeConfig config = new Core.CodeConfig("python", "print('hi')", 5);
            CodeNode node = CodeNode.builder()
                .nodeId("core:python_code")
                .codeConfig(config)
                .build();

            assertEquals("core:python_code", node.getNodeId());
            assertEquals("python", node.getCodeConfig().language());
        }
    }

    // ===============================================================
    // CodeConfig record tests
    // ===============================================================

    @Nested
    @DisplayName("CodeConfig")
    class CodeConfigTests {

        @Test
        @DisplayName("Should default language to javascript")
        void shouldDefaultLanguageToJavascript() {
            Core.CodeConfig config = new Core.CodeConfig(null, "var x = 1;", 0);
            assertEquals("javascript", config.language());
        }

        @Test
        @DisplayName("Should default code to empty string")
        void shouldDefaultCodeToEmptyString() {
            Core.CodeConfig config = new Core.CodeConfig("javascript", null, 10);
            assertEquals("", config.code());
        }

        @Test
        @DisplayName("Should default timeoutSeconds to 10 when 0")
        void shouldDefaultTimeoutTo10() {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "code", 0);
            assertEquals(10, config.timeoutSeconds());
        }

        @Test
        @DisplayName("Should cap timeoutSeconds at 120")
        void shouldCapTimeoutAt120() {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "code", 300);
            assertEquals(120, config.timeoutSeconds());
        }

        @Test
        @DisplayName("Should normalize language to lowercase")
        void shouldNormalizeLanguage() {
            Core.CodeConfig config = new Core.CodeConfig("JavaScript", "code", 10);
            assertEquals("javascript", config.language());
        }
    }

    // ===============================================================
    // Code wrapping tests
    // ===============================================================

    @Nested
    @DisplayName("Code Wrapping")
    class CodeWrappingTests {

        @Test
        @DisplayName("Should wrap JavaScript code with $input injection")
        void shouldWrapJavaScriptCode() {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "", 10);
            CodeNode node = new CodeNode("core:test", config);

            String wrapped = node.wrapCode("javascript", "$output = $input.value * 2;", "{}");

            assertTrue(wrapped.contains("const $input = JSON.parse"));
            assertTrue(wrapped.contains("let $output = undefined;"));
            assertTrue(wrapped.contains("$output = $input.value * 2;"));
            assertTrue(wrapped.contains("__RESULT__"));
            assertTrue(wrapped.contains("JSON.stringify($output)"));
        }

        @Test
        @DisplayName("Should wrap Python code with _input injection")
        void shouldWrapPythonCode() {
            Core.CodeConfig config = new Core.CodeConfig("python", "", 10);
            CodeNode node = new CodeNode("core:test", config);

            String wrapped = node.wrapCode("python", "_output = _input['value'] * 2", "{}");

            assertTrue(wrapped.contains("import json as _json"));
            assertTrue(wrapped.contains("_input = _json.loads"));
            assertTrue(wrapped.contains("_output = None"));
            assertTrue(wrapped.contains("_output = _input['value'] * 2"));
            assertTrue(wrapped.contains("__RESULT__"));
        }

        @Test
        @DisplayName("Should wrap TypeScript code with $input injection")
        void shouldWrapTypeScriptCode() {
            Core.CodeConfig config = new Core.CodeConfig("typescript", "", 10);
            CodeNode node = new CodeNode("core:test", config);

            String wrapped = node.wrapCode("typescript", "$output = $input.value;", "{}");

            assertTrue(wrapped.contains("const $input: any = JSON.parse"));
            assertTrue(wrapped.contains("let $output: any = undefined;"));
            assertTrue(wrapped.contains("__RESULT__"));
        }

        @Test
        @DisplayName("Should wrap Bash code with INPUT injection")
        void shouldWrapBashCode() {
            Core.CodeConfig config = new Core.CodeConfig("bash", "", 10);
            CodeNode node = new CodeNode("core:test", config);

            String wrapped = node.wrapCode("bash", "OUTPUT='{\"result\": 42}'", "{}");

            assertTrue(wrapped.contains("INPUT="));
            assertTrue(wrapped.contains("OUTPUT='{\"result\": 42}'"));
            assertTrue(wrapped.contains("__RESULT__"));
        }

        @Test
        @DisplayName("Should wrap Bash code with single quotes in input data correctly")
        void shouldWrapBashCodeWithSingleQuotesInInput() {
            Core.CodeConfig config = new Core.CodeConfig("bash", "", 10);
            CodeNode node = new CodeNode("core:test", config);

            // Input JSON containing single quotes (e.g. from user data like "O'Brien")
            String inputJson = "{\"name\":\"O'Brien\",\"msg\":\"it's fine\"}";
            String escaped = node.escapeForString(inputJson, "bash");

            // The escaped output should use the '\'' idiom for bash single-quote escaping:
            // O'Brien becomes O'\''Brien (end quote, escaped quote, reopen quote)
            assertTrue(escaped.contains("'\\''"), "Should use bash '\\'' idiom for single quotes");
            // Verify the original single quote is NOT left unescaped
            // After replacing ' with '\'' the result should not contain bare O'B
            String withoutIdiom = escaped.replace("'\\''", "");
            assertFalse(withoutIdiom.contains("'"), "All single quotes should be escaped via '\\'' idiom");

            // Verify the wrapped code has INPUT assignment
            String wrapped = node.wrapCode("bash", "echo $INPUT", inputJson);
            assertTrue(wrapped.contains("INPUT="));
        }

        @Test
        @DisplayName("Should escape single quotes differently for JS vs Bash")
        void shouldEscapeSingleQuotesDifferentlyForJsVsBash() {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "", 10);
            CodeNode node = new CodeNode("core:test", config);

            String inputWithQuote = "{\"name\":\"O'Brien\"}";

            // JS uses backslash escaping: ' becomes \'
            String jsEscaped = node.escapeForString(inputWithQuote, "javascript");
            assertTrue(jsEscaped.contains("\\'"), "JS should use backslash-quote");
            assertFalse(jsEscaped.contains("'\\''"), "JS should NOT use bash idiom");

            // Bash uses '\'' idiom: ' becomes '\''
            String bashEscaped = node.escapeForString(inputWithQuote, "bash");
            assertTrue(bashEscaped.contains("'\\''"), "Bash should use '\\'' idiom");
        }

        @Test
        @DisplayName("Should escape newlines differently for JS vs Bash")
        void shouldEscapeNewlinesDifferentlyForJsVsBash() {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "", 10);
            CodeNode node = new CodeNode("core:test", config);

            String inputWithNewlines = "{\"text\":\"line1\nline2\r\n\"}";

            // JS escapes \n and \r
            String jsEscaped = node.escapeForString(inputWithNewlines, "javascript");
            assertFalse(jsEscaped.contains("\n"), "JS should escape literal newlines");
            assertTrue(jsEscaped.contains("\\n"), "JS should contain escaped \\n");

            // Bash preserves literal newlines (single-quoted strings are literal)
            String bashEscaped = node.escapeForString(inputWithNewlines, "bash");
            assertTrue(bashEscaped.contains("\n"), "Bash should preserve literal newlines");
        }

        @Test
        @DisplayName("Should escape backslashes differently for JS vs Bash")
        void shouldEscapeBackslashesDifferentlyForJsVsBash() {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "", 10);
            CodeNode node = new CodeNode("core:test", config);

            String inputWithBackslash = "{\"path\":\"C:\\\\Users\"}";

            // JS double-escapes backslashes
            String jsEscaped = node.escapeForString(inputWithBackslash, "javascript");
            assertTrue(jsEscaped.contains("\\\\"), "JS should double-escape backslashes");

            // Bash preserves backslashes as-is in single-quoted strings
            String bashEscaped = node.escapeForString(inputWithBackslash, "bash");
            assertEquals(inputWithBackslash, bashEscaped, "Bash should not modify backslashes");
        }

        @Test
        @DisplayName("Should use bash escape path for sh and shell aliases")
        void shouldUseBashEscapeForShellAliases() {
            Core.CodeConfig config = new Core.CodeConfig("sh", "", 10);
            CodeNode node = new CodeNode("core:test", config);

            String inputWithQuote = "{\"name\":\"O'Brien\"}";

            String shEscaped = node.escapeForString(inputWithQuote, "sh");
            assertTrue(shEscaped.contains("'\\''"), "sh should use bash '\\'' idiom");

            String shellEscaped = node.escapeForString(inputWithQuote, "shell");
            assertTrue(shellEscaped.contains("'\\''"), "shell should use bash '\\'' idiom");
        }

        @Test
        @DisplayName("Should throw for unsupported language")
        void shouldThrowForUnsupportedLanguage() {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "", 10);
            CodeNode node = new CodeNode("core:test", config);

            assertThrows(IllegalArgumentException.class, () ->
                node.wrapCode("rust", "fn main() {}", "{}"));
        }
    }

    // ===============================================================
    // Result extraction tests
    // ===============================================================

    @Nested
    @DisplayName("Result Extraction")
    class ResultExtractionTests {

        private CodeNode node;

        @BeforeEach
        void setUp() {
            node = new CodeNode("core:test", new Core.CodeConfig("javascript", "", 10));
        }

        @Test
        @DisplayName("Should extract JSON result from __RESULT__ line")
        void shouldExtractJsonResult() {
            String stdout = "some console output\n__RESULT__{\"key\":\"value\",\"num\":42}\n";
            Object result = node.extractResult(stdout);

            assertNotNull(result);
            assertTrue(result instanceof Map);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            assertEquals("value", map.get("key"));
            assertEquals(42, map.get("num"));
        }

        @Test
        @DisplayName("Should extract array result")
        void shouldExtractArrayResult() {
            String stdout = "__RESULT__[1,2,3]\n";
            Object result = node.extractResult(stdout);

            assertNotNull(result);
            assertTrue(result instanceof List);
            assertEquals(3, ((List<?>) result).size());
        }

        @Test
        @DisplayName("Should extract string result")
        void shouldExtractStringResult() {
            String stdout = "__RESULT__\"hello world\"\n";
            Object result = node.extractResult(stdout);

            assertEquals("hello world", result);
        }

        @Test
        @DisplayName("Should return null when no __RESULT__ found")
        void shouldReturnNullWhenNoResult() {
            String stdout = "just console output\nno result here\n";
            Object result = node.extractResult(stdout);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for empty stdout")
        void shouldReturnNullForEmptyStdout() {
            assertNull(node.extractResult(""));
            assertNull(node.extractResult(null));
        }

        @Test
        @DisplayName("Should return raw string for invalid JSON")
        void shouldReturnRawStringForInvalidJson() {
            String stdout = "__RESULT__not-valid-json\n";
            Object result = node.extractResult(stdout);

            assertEquals("not-valid-json", result);
        }
    }

    // ===============================================================
    // Console output extraction tests
    // ===============================================================

    @Nested
    @DisplayName("Console Output Extraction")
    class ConsoleOutputTests {

        private CodeNode node;

        @BeforeEach
        void setUp() {
            node = new CodeNode("core:test", new Core.CodeConfig("javascript", "", 10));
        }

        @Test
        @DisplayName("Should extract console output excluding __RESULT__ lines")
        void shouldExtractConsoleOutput() {
            String stdout = "line 1\nline 2\n__RESULT__{\"x\":1}\nline 3\n";
            String consoleOutput = node.extractConsoleOutput(stdout);

            assertTrue(consoleOutput.contains("line 1"));
            assertTrue(consoleOutput.contains("line 2"));
            assertTrue(consoleOutput.contains("line 3"));
            assertFalse(consoleOutput.contains("__RESULT__"));
        }

        @Test
        @DisplayName("Should return empty string for null/empty stdout")
        void shouldReturnEmptyForNullStdout() {
            assertEquals("", node.extractConsoleOutput(null));
            assertEquals("", node.extractConsoleOutput(""));
        }
    }

    // ===============================================================
    // Execution tests (with mocked PistonClient)
    // ===============================================================

    @Nested
    @DisplayName("Execution")
    class ExecutionTests {

        @Test
        @DisplayName("Should execute JavaScript code successfully")
        void shouldExecuteJavaScriptSuccessfully() throws Exception {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "$output = {result: 42};", 10);
            CodeNode node = CodeNode.builder().nodeId("core:my_code").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult codeResult = new CodeResult("javascript", "20.11.1",
                "console log\n__RESULT__{\"result\":42}\n", "", 0, null, "", null, null);
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(codeResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertFalse(result.isFailure());

            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) result.output();
            assertEquals(true, output.get("success"));
            assertEquals("javascript", output.get("language"));
            assertEquals(0, output.get("exitCode"));
            assertNotNull(output.get("result"));
            assertEquals("console log", output.get("stdout"));

            // MANDATORY metadata
            assertEquals("CODE", output.get("node_type"));
            assertEquals(0, output.get("item_index"));
            assertEquals(0, output.get("itemIndex"));
            assertEquals("item-1", output.get("item_id"));
            assertNotNull(output.get("resolved_params"));
        }

        @Test
        @DisplayName("Should capture CodeExecutor request with correct language and wrapped code")
        void shouldCaptureCorrectCodeRequest() throws Exception {
            Core.CodeConfig config = new Core.CodeConfig("python", "_output = 42", 15);
            CodeNode node = CodeNode.builder().nodeId("core:py_code").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult codeResult = new CodeResult("python", "3.12.0",
                "__RESULT__42\n", "", 0, null, "", null, null);
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(codeResult);

            node.execute(context);

            ArgumentCaptor<CodeRequest> captor = ArgumentCaptor.forClass(CodeRequest.class);
            verify(mockCodeExecutor).execute(captor.capture());

            CodeRequest request = captor.getValue();
            assertEquals("python", request.language());
            assertEquals("*", request.version());
            assertTrue(request.code().contains("_output = 42"));
            assertTrue(request.code().contains("import json as _json"));
            assertEquals(15000, request.runTimeoutMs());
        }

        @Test
        @DisplayName("Should fail when CodeExecutor is not available")
        void shouldFailWhenCodeExecutorNotAvailable() {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "var x = 1;", 10);
            CodeNode node = CodeNode.builder().nodeId("core:my_code").codeConfig(config).build();
            // No CodeExecutor set

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("CodeExecutor is not available"));
        }

        @Test
        @DisplayName("Should fail when code is empty")
        void shouldFailWhenCodeIsEmpty() {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "", 10);
            CodeNode node = CodeNode.builder().nodeId("core:my_code").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("Code is required"));
        }

        @Test
        @DisplayName("Should fail on compilation error")
        void shouldFailOnCompilationError() throws Exception {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "invalid(((", 10);
            CodeNode node = CodeNode.builder().nodeId("core:my_code").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult codeResult = new CodeResult("javascript", "20.11.1",
                "", "", 0, null, "", "", "SyntaxError: Unexpected token");
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(codeResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("Compilation error"));
        }

        @Test
        @DisplayName("Should fail on runtime error (non-zero exit code)")
        void shouldFailOnRuntimeError() throws Exception {
            Core.CodeConfig config = new Core.CodeConfig("python", "raise Exception('boom')", 10);
            CodeNode node = CodeNode.builder().nodeId("core:my_code").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult codeResult = new CodeResult("python", "3.12.0",
                "", "Exception: boom", 1, null, "", null, null);
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(codeResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("Code node failed"));
            assertTrue(result.errorMessage().orElse("").contains("exit 1"));
            assertTrue(result.errorMessage().orElse("").contains("boom"));
        }

        @Test
        @DisplayName("Marks node FAILED when Piston returns exit=0 but sandbox-keeper crashed (stdout overflow)")
        void sandboxKeeperFailureMarksNodeFailedEvenWithExitZero() throws Exception {
            // Reproduces the prod bug seen on run_<id> (2026-04-30):
            // user code wrote >PISTON_OUTPUT_MAX_SIZE bytes → sandbox-keeper SIGABRT'd → Piston
            // wrapper still returned code=0. Pre-fix: node marked COMPLETED with empty result,
            // downstream split saw items=[] and silently skipped. Post-fix: node FAILED with
            // explicit reason so the user sees the actual problem.
            Core.CodeConfig config = new Core.CodeConfig("javascript",
                "const big = 'x'.repeat(80000); console.log(big);", 10);
            CodeNode node = CodeNode.builder().nodeId("core:parse_results").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult sandboxKilled = new CodeResult(
                "javascript", "20.11.1",
                "",                                              // stdout was truncated to nothing
                "Sandbox keeper received fatal signal 6\n",      // sandbox-keeper crashed
                0,                                               // wrapper exit OK - the trap
                null,                                            // no signal on user process
                "", null, null
            );
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(sandboxKilled);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure(),
                "Sandbox-killed code must NOT be reported as success - pre-fix this returned isSuccess=true");
            String msg = result.errorMessage().orElse("");
            assertTrue(msg.contains("Code node failed"), "expected new failure prefix, got: " + msg);
            assertTrue(msg.contains("sandbox killed") || msg.contains("PISTON_OUTPUT_MAX_SIZE"),
                "expected sandbox-kill reason, got: " + msg);
        }

        @Test
        @DisplayName("Marks node FAILED when EmbeddedCodeExecutor signals overflow via [lc-sandbox] marker")
        void embeddedOverflowMarkerMarksNodeFailed() throws Exception {
            Core.CodeConfig config = new Core.CodeConfig("javascript",
                "console.log('x'.repeat(2_000_000));", 10);
            CodeNode node = CodeNode.builder().nodeId("core:overflow").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult overflow = new CodeResult(
                "javascript", "20.11.1",
                "",
                "[lc-sandbox] stdout length exceeded",
                0, null, "", null, null
            );
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(overflow);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("MAX_OUTPUT_BYTES"),
                "expected output-cap reason, got: " + result.errorMessage().orElse(""));
        }

        @Test
        @DisplayName("Does NOT mark node FAILED when user code prints 'stdout length exceeded' to stderr (false-positive guard)")
        void userPrintingMarkerStringIsNotSandboxKilled() throws Exception {
            // Without the "[lc-sandbox]" prefix, the substring "stdout length exceeded" is just
            // user text. The node must remain SUCCESS - otherwise any user logging that phrase
            // would crash their workflow.
            Core.CodeConfig config = new Core.CodeConfig("javascript",
                "console.error('stdout length exceeded'); $output = 42;", 10);
            CodeNode node = CodeNode.builder().nodeId("core:user_log").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult userLog = new CodeResult(
                "javascript", "20.11.1",
                "__RESULT__42\n",
                "stdout length exceeded\n",   // user-emitted, NO [lc-sandbox] prefix
                0, null, "", null, null
            );
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(userLog);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess(),
                "user code printing the bare 'stdout length exceeded' phrase must NOT be classified as sandbox kill");
        }

        @Test
        @DisplayName("Marks node FAILED when user process killed by signal (exit=0, signal=SIGKILL)")
        void killedBySignalMarksNodeFailed() throws Exception {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "while(true){}", 10);
            CodeNode node = CodeNode.builder().nodeId("core:infinite").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult killed = new CodeResult(
                "javascript", "20.11.1",
                "", "", 0, "SIGKILL", "", null, null
            );
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(killed);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("SIGKILL"));
        }

        @Test
        @DisplayName("Should fail when executor returns null stdout")
        void shouldFailWhenNullStdout() throws Exception {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "var x = 1;", 10);
            CodeNode node = CodeNode.builder().nodeId("core:my_code").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult codeResult = new CodeResult("javascript", "20.11.1",
                null, null, -1, null, null, null, null);
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(codeResult);

            NodeExecutionResult result = node.execute(context);

            // Should handle gracefully (null stdout = no result)
            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should handle CodeExecutor exception")
        void shouldHandleCodeExecutorException() throws Exception {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "var x = 1;", 10);
            CodeNode node = CodeNode.builder().nodeId("core:my_code").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            when(mockCodeExecutor.execute(any(CodeRequest.class)))
                .thenThrow(new RuntimeException("Connection refused"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("Connection refused"));
        }

        @Test
        @DisplayName("Should succeed with null output (no $output set)")
        void shouldSucceedWithNullOutput() throws Exception {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "console.log('hello');", 10);
            CodeNode node = CodeNode.builder().nodeId("core:my_code").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult codeResult = new CodeResult("javascript", "20.11.1",
                "hello\n", "", 0, null, "", null, null);
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(codeResult);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) result.output();
            assertNull(output.get("result"));
            assertEquals("hello", output.get("stdout"));
        }
    }

    // ===============================================================
    // Timeout handling tests
    // ===============================================================

    @Nested
    @DisplayName("Timeout Handling")
    class TimeoutTests {

        @Test
        @DisplayName("Should cap timeout at 120 seconds")
        void shouldCapTimeoutAt120Seconds() throws Exception {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "var x = 1;", 300);
            CodeNode node = CodeNode.builder().nodeId("core:my_code").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult codeResult = new CodeResult("javascript", "20.11.1",
                "__RESULT__1\n", "", 0, null, "", null, null);
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(codeResult);

            node.execute(context);

            ArgumentCaptor<CodeRequest> captor = ArgumentCaptor.forClass(CodeRequest.class);
            verify(mockCodeExecutor).execute(captor.capture());
            assertEquals(120000, captor.getValue().runTimeoutMs());
        }

        @Test
        @DisplayName("Should enforce minimum timeout of 1 second")
        void shouldEnforceMinimumTimeout() throws Exception {
            Core.CodeConfig config = new Core.CodeConfig("javascript", "var x = 1;", -5);
            // CodeConfig compact constructor sets negative to 10 (default)
            assertEquals(10, config.timeoutSeconds());
        }

        @Test
        @DisplayName("Should unwrap .output wrapper so $input.alias.field works directly")
        void shouldUnwrapOutputWrapperInInput() throws Exception {
            // Simulate a predecessor step output with the {output: {...}} wrapper
            // that ExecutionContext.withResult() creates
            Map<String, Object> innerOutput = new LinkedHashMap<>();
            innerOutput.put("items", List.of(Map.of("name", "Alice"), Map.of("name", "Bob")));
            innerOutput.put("item_count", 2);
            Map<String, Object> wrappedOutput = new LinkedHashMap<>();
            wrappedOutput.put("output", innerOutput);

            // Add step outputs with both alias and full ID (as the real system does)
            ExecutionContext ctxWithOutputs = context
                .withStepOutput("table:find_users", wrappedOutput)
                .withStepOutput("find_users", wrappedOutput);

            // Code that accesses $input.find_users.items directly (no .output)
            Core.CodeConfig config = new Core.CodeConfig("javascript",
                "$output = { count: $input.find_users.items.length }", 10);
            CodeNode node = CodeNode.builder().nodeId("core:process").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult codeResult = new CodeResult("javascript", "20.11.1",
                "__RESULT__{\"count\":2}\n", "", 0, null, "", null, null);
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(codeResult);

            node.execute(ctxWithOutputs);

            // Capture the code sent to Piston and verify $input is unwrapped
            ArgumentCaptor<CodeRequest> captor = ArgumentCaptor.forClass(CodeRequest.class);
            verify(mockCodeExecutor).execute(captor.capture());

            String sentCode = captor.getValue().code();
            // The JSON injected into $input should have items directly under find_users,
            // not nested under find_users.output.items
            assertTrue(sentCode.contains("\"items\""), "Input should contain items field");
            assertTrue(sentCode.contains("\"item_count\""), "Input should contain item_count field");
            // The .output wrapper should NOT be present in the injected JSON
            assertFalse(sentCode.contains("\"output\":{\"items\""),
                "Input should NOT have .output wrapper - fields should be directly under the alias key");
        }

        @Test
        @DisplayName("Should unwrap DB-loaded outputs that have metadata keys alongside output")
        void shouldUnwrapDbLoadedOutputsWithMetadata() throws Exception {
            // DB-loaded step outputs have extra metadata keys: {output: {...}, resultStepId: ..., statusValue: ...}
            Map<String, Object> innerOutput = new LinkedHashMap<>();
            innerOutput.put("response", "Hello world");
            Map<String, Object> dbWrapped = new LinkedHashMap<>();
            dbWrapped.put("output", innerOutput);
            dbWrapped.put("resultStepId", "step-123");
            dbWrapped.put("statusValue", "COMPLETED");

            ExecutionContext ctxWithOutputs = context.withStepOutput("agent:summarize", dbWrapped)
                .withStepOutput("summarize", dbWrapped);

            Core.CodeConfig config = new Core.CodeConfig("javascript",
                "$output = { text: $input.summarize.response }", 10);
            CodeNode node = CodeNode.builder().nodeId("core:process").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult codeResult = new CodeResult("javascript", "20.11.1",
                "__RESULT__{\"text\":\"Hello world\"}\n", "", 0, null, "", null, null);
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(codeResult);

            node.execute(ctxWithOutputs);

            ArgumentCaptor<CodeRequest> captor = ArgumentCaptor.forClass(CodeRequest.class);
            verify(mockCodeExecutor).execute(captor.capture());

            String sentCode = captor.getValue().code();
            // Should see the inner response field directly, not wrapped
            assertTrue(sentCode.contains("\"response\""), "Should contain unwrapped response field");
            // Metadata keys should be stripped
            assertFalse(sentCode.contains("\"resultStepId\""), "Should NOT contain DB metadata keys");
        }

        @Test
        @DisplayName("Should pass through non-Map values unchanged")
        void shouldPassThroughNonMapValues() throws Exception {
            // Step output is a plain string, not a Map - should pass through
            ExecutionContext ctxWithOutputs = context.withStepOutput("trigger:start",
                Map.of("triggeredAt", "2026-04-13"));

            Core.CodeConfig config = new Core.CodeConfig("javascript",
                "$output = { ts: $input['trigger:start'].triggeredAt }", 10);
            CodeNode node = CodeNode.builder().nodeId("core:process").codeConfig(config).build();
            node.setCodeExecutor(mockCodeExecutor);

            CodeResult codeResult = new CodeResult("javascript", "20.11.1",
                "__RESULT__{\"ts\":\"2026-04-13\"}\n", "", 0, null, "", null, null);
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(codeResult);

            node.execute(ctxWithOutputs);

            ArgumentCaptor<CodeRequest> captor = ArgumentCaptor.forClass(CodeRequest.class);
            verify(mockCodeExecutor).execute(captor.capture());

            String sentCode = captor.getValue().code();
            // Without an "output" wrapper, the value should pass through as-is
            assertTrue(sentCode.contains("\"triggeredAt\""), "Should contain trigger data directly");
        }
    }

    // ===============================================================
    // LC-018: upstream data spliced into the code body must stay DATA
    // ===============================================================

    /**
     * The code body is template-resolved, so an upstream value (a webhook payload, a mail body,
     * any tool output) used to be spliced verbatim into executable JavaScript, TypeScript, Python
     * or Bash. A value carrying the quote character closed the literal the author had written
     * around the placeholder, and everything after it ran as code in the sandbox.
     *
     * <p>These tests drive a REAL template stack (TemplateEngine + SpEL + namespace resolution),
     * not a stub, so they exercise the resolution path an actual run takes; only the tainted
     * value itself is planted, as an upstream step output.
     */
    @Nested
    @DisplayName("Template injection into the code body (LC-018)")
    class CodeBodyTemplateInjectionTests {

        /** Closes the author's string literal and appends a call. */
        private static final String JS_BREAKOUT = "Ada'); require('child_process').execSync('id'); ('";

        private ExecutionContext contextWithMail(String subject) {
            // Empty trigger data on purpose: this context only needs the step output the
            // template reads, and the plan is a mock with no triggers.
            ExecutionContext base = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, Map.of(), mockPlan);
            return base.withStepOutput("mcp:fetch_mail",
                Map.of("output", Map.of("subject", subject)));
        }

        private CodeNode nodeWith(String language, String code) {
            CodeNode node = CodeNode.builder()
                .nodeId("core:process")
                .codeConfig(new Core.CodeConfig(language, code, 10))
                .build();
            node.setCodeExecutor(mockCodeExecutor);
            node.setTemplateAdapter(realTemplateAdapter());
            return node;
        }

        private String executeAndCaptureSentCode(CodeNode node, ExecutionContext ctx, String language) throws Exception {
            CodeResult codeResult = new CodeResult(language, "1.0",
                "__RESULT__null\n", "", 0, null, "", null, null);
            when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(codeResult);

            node.execute(ctx);

            ArgumentCaptor<CodeRequest> captor = ArgumentCaptor.forClass(CodeRequest.class);
            verify(mockCodeExecutor).execute(captor.capture());
            return captor.getValue().code();
        }

        /** The line of the wrapped program that carries the author's own statement. */
        private String userLine(String wrappedCode, String marker) {
            return Arrays.stream(wrappedCode.split("\n"))
                .filter(line -> line.contains(marker))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "wrapped code has no line containing '" + marker + "':\n" + wrappedCode));
        }

        @Test
        @DisplayName("A tainted value cannot close the JavaScript string literal it is spliced into")
        void taintedValueCannotBreakOutOfJavaScriptLiteral() throws Exception {
            CodeNode node = nodeWith("javascript",
                "const subject = '{{mcp:fetch_mail.output.subject}}';");

            String sent = executeAndCaptureSentCode(node, contextWithMail(JS_BREAKOUT), "javascript");
            String line = userLine(sent, "const subject");

            // Only the two quotes the author typed are live; every quote from the value is escaped.
            assertEquals(2, unescapedSingleQuotes(line),
                "value quotes must not stay live in: " + line);
            assertTrue(line.contains("Ada\\')"),
                "the value's quote must be backslash-escaped, got: " + line);
        }

        @Test
        @DisplayName("A tainted value cannot open a JavaScript template literal or interpolation")
        void taintedValueCannotOpenJavaScriptInterpolation() throws Exception {
            CodeNode node = nodeWith("javascript",
                "const subject = `{{mcp:fetch_mail.output.subject}}`;");

            String sent = executeAndCaptureSentCode(
                node, contextWithMail("x${process.env.SECRET}`+require('fs')+`"), "javascript");
            String line = userLine(sent, "const subject");

            assertTrue(line.contains("\\${"), "interpolation must be neutralised, got: " + line);
            assertFalse(line.contains("x${"), "raw interpolation survived in: " + line);
            assertTrue(line.contains("\\`"), "backtick must be escaped, got: " + line);
        }

        @Test
        @DisplayName("A tainted value cannot close the Python string literal it is spliced into")
        void taintedValueCannotBreakOutOfPythonLiteral() throws Exception {
            CodeNode node = nodeWith("python",
                "subject = \"{{mcp:fetch_mail.output.subject}}\"");

            String sent = executeAndCaptureSentCode(
                node, contextWithMail("x\" + __import__('os').system('id') + \""), "python");
            String line = userLine(sent, "subject = ");

            assertEquals(2, unescapedDoubleQuotes(line),
                "value quotes must not stay live in: " + line);
            assertEquals(0, unescapedSingleQuotes(line),
                "the value's single quotes must be escaped too in: " + line);
        }

        @Test
        @DisplayName("A tainted value cannot end the single-quoted Bash string it is spliced into")
        void taintedValueCannotBreakOutOfBashLiteral() throws Exception {
            CodeNode node = nodeWith("bash",
                "SUBJECT='{{mcp:fetch_mail.output.subject}}'");

            String sent = executeAndCaptureSentCode(
                node, contextWithMail("x'; id; echo '"), "bash");
            String line = userLine(sent, "SUBJECT=");

            // Bash single quotes take no escapes; the only safe form is the '\'' idiom.
            assertTrue(line.contains("'\\''"),
                "bash quote idiom must be used, got: " + line);
            assertFalse(line.contains("SUBJECT='x'; id"),
                "the command separator escaped the literal in: " + line);
        }

        @Test
        @DisplayName("A newline in the value cannot start a new statement")
        void taintedNewlineCannotStartANewStatement() throws Exception {
            CodeNode node = nodeWith("javascript",
                "const subject = '{{mcp:fetch_mail.output.subject}}';");

            String sent = executeAndCaptureSentCode(
                node, contextWithMail("line one\nrequire('fs').readFileSync('/etc/passwd')"), "javascript");
            String line = userLine(sent, "const subject");

            assertTrue(line.contains("line one\\nrequire"),
                "the newline must be escaped into the literal, got: " + line);
            assertFalse(sent.contains("\nrequire('fs')"),
                "a raw newline split the literal into a second statement:\n" + sent);
        }

        @Test
        @DisplayName("A benign value still resolves into the code body (saved workflows keep working)")
        void benignValueStillResolves() throws Exception {
            CodeNode node = nodeWith("javascript",
                "const subject = '{{mcp:fetch_mail.output.subject}}';");

            String sent = executeAndCaptureSentCode(node, contextWithMail("Weekly report"), "javascript");

            assertTrue(sent.contains("const subject = 'Weekly report';"),
                "the resolved value should be spliced unchanged when it needs no escaping:\n" + sent);
        }

        @Test
        @DisplayName("A code body with no expression is sent through untouched")
        void bodyWithoutExpressionIsUntouched() throws Exception {
            String body = "const total = $input.fetch_mail.output.subject.length; $output = { total };";
            CodeNode node = nodeWith("javascript", body);

            String sent = executeAndCaptureSentCode(node, contextWithMail("Weekly report"), "javascript");

            assertTrue(sent.contains(body), "body should be forwarded verbatim:\n" + sent);
        }

        @Test
        @DisplayName("escapeCodeValue neutralises the breakout characters of each language")
        void escapeCodeValuePerLanguage() {
            CodeNode node = CodeNode.builder()
                .nodeId("core:process")
                .codeConfig(new Core.CodeConfig("javascript", "noop", 10))
                .build();

            assertEquals("a\\'b", node.escapeCodeValue("a'b", "javascript"));
            assertEquals("a\\\"b", node.escapeCodeValue("a\"b", "typescript"));
            assertEquals("a\\`b", node.escapeCodeValue("a`b", "javascript"));
            assertEquals("a\\${b}", node.escapeCodeValue("a${b}", "javascript"));
            assertEquals("a\\\\b", node.escapeCodeValue("a\\b", "javascript"));
            assertEquals("a\\nb", node.escapeCodeValue("a\nb", "javascript"));

            assertEquals("a\\'b", node.escapeCodeValue("a'b", "python"));
            assertEquals("a\\\"b", node.escapeCodeValue("a\"b", "python"));
            // Python keeps an unknown escape's backslash, so '$' must NOT be escaped there.
            assertEquals("a$b", node.escapeCodeValue("a$b", "python"));

            assertEquals("a'\\''b", node.escapeCodeValue("a'b", "bash"));
            assertEquals("", node.escapeCodeValue(null, "javascript"));
        }

        // ---------------------------------------------------------------
        // Placeholder POSITION: what escaping can and cannot promise
        // ---------------------------------------------------------------

        private ExecutionContext contextWithPayload(Object payload) {
            ExecutionContext base = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, Map.of(), mockPlan);
            return base.withStepOutput("core:build",
                Map.of("output", Map.of("obj", payload)));
        }

        /**
         * The availability half of LC-018. Escaping a resolved value only means something inside
         * the string literal the author wrote around the placeholder. Applied to a placeholder
         * standing in BARE STATEMENT position, it turns the JSON this node used to emit into
         * {@code const o = {\"a\":1};}, a syntax error, so a workflow that WAS working stops
         * working. The JSON is spliced raw there instead, which is byte for byte the pre-escaping
         * output and is safe without escaping because JSON encoding is self-escaping.
         */
        @Test
        @DisplayName("An object spliced outside a string literal keeps the raw JSON the node used to emit")
        void objectAtCodePositionKeepsRawJson() throws Exception {
            CodeNode node = nodeWith("javascript", "const o = {{core:build.output.obj}};");

            String sent = executeAndCaptureSentCode(
                node, contextWithPayload(Map.of("a", 1)), "javascript");
            String line = userLine(sent, "const o =");

            assertTrue(line.contains("const o = {\"a\":1};"),
                "the object must stay a JavaScript object literal, got: " + line);
            assertFalse(line.contains("\\\""),
                "escaping the JSON at a code position produces a syntax error, got: " + line);
        }

        @Test
        @DisplayName("The same object spliced INSIDE a string literal is still escaped")
        void objectInsideALiteralIsStillEscaped() throws Exception {
            CodeNode node = nodeWith("javascript", "const o = '{{core:build.output.obj}}';");

            String sent = executeAndCaptureSentCode(
                node, contextWithPayload(Map.of("a", "it's")), "javascript");
            String line = userLine(sent, "const o =");

            // The value's own quote must not close the literal the author typed.
            assertEquals(2, unescapedSingleQuotes(line),
                "value quotes must not stay live in: " + line);
            assertTrue(line.contains("\\\""),
                "the JSON quotes must be escaped inside the literal, got: " + line);
        }

        @Test
        @DisplayName("A list spliced outside a string literal keeps its raw JSON too")
        void listAtCodePositionKeepsRawJson() throws Exception {
            CodeNode node = nodeWith("javascript", "const items = {{core:build.output.obj}};");

            // A value carrying a quote is what tells the two behaviours apart: escaped, the
            // array literal becomes unparseable source.
            String sent = executeAndCaptureSentCode(
                node, contextWithPayload(List.of("it's", "b")), "javascript");

            // Still a JavaScript array literal. The quote inside the element is spelled as a
            // ' escape (LC-018 round 2: a quote is structure in a literal the scanner missed).
            assertTrue(sent.contains("const items = [\"it\\u0027s\",\"b\"];"),
                "the list must stay a JavaScript array literal:\n" + sent);
        }

        /**
         * Bash is excluded from the raw-JSON splice: an unquoted {@code $(...)} inside the JSON
         * would be command-substituted by the shell. At an unquoted bash position the value is
         * spliced as a single-quoted word instead ({@code CodeNode.spliceAtCodePosition}), the one
         * bash form that expands nothing, so a {@code $(...)} in the value stays text.
         */
        @Test
        @DisplayName("Bash never gets a raw JSON splice, even outside a literal")
        void bashNeverGetsRawJson() throws Exception {
            CodeNode node = nodeWith("bash", "OUT={{core:build.output.obj}}");

            String sent = executeAndCaptureSentCode(
                node, contextWithPayload(Map.of("a", "it's")), "bash");
            String line = userLine(sent, "OUT=");

            assertTrue(line.contains("'\\''"),
                "bash must keep its quote idiom instead of a raw JSON splice: " + line);
            assertFalse(line.contains("\"it's\""),
                "a raw JSON splice would leave the value's quote live: " + line);
        }

        @Test
        @DisplayName("An unresolved placeholder still collapses to the empty string")
        void unresolvedPlaceholderBecomesEmpty() throws Exception {
            CodeNode node = nodeWith("javascript",
                "const subject = '{{mcp:fetch_mail.output.absent_field}}';");

            String sent = executeAndCaptureSentCode(node, contextWithMail("Weekly report"), "javascript");

            assertTrue(sent.contains("const subject = '';"),
                "an unresolved expression must resolve to the empty string:\n" + sent);
        }

        @Test
        @DisplayName("A scalar keeps the string form the template engine itself produces")
        void scalarKeepsTemplateEngineStringForm() throws Exception {
            CodeNode node = nodeWith("javascript",
                "const n = Number('{{core:build.output.obj}}');");

            String sent = executeAndCaptureSentCode(node, contextWithPayload(42), "javascript");

            assertTrue(sent.contains("Number('42')"),
                "a number must be spliced as its plain string form:\n" + sent);
        }

        /**
         * A body whose placeholder sits at a code position can only fail with the sandbox's own
         * parser error, which names a line and no cause. The node appends what the position means
         * so the author is not left comparing a working workflow with a failing one.
         */
        @Test
        @DisplayName("A failure on a body with a placeholder outside a literal explains the position")
        void failureExplainsTheUnsafePosition() {
            CodeNode node = nodeWith("javascript", "const o = {{core:build.output.obj}};");

            String explained = node.explainedFailure(
                "exit 1: SyntaxError: Invalid or unexpected token", node.getCodeConfig().code(), "javascript");

            assertTrue(explained.contains("SyntaxError"), "the original error must survive: " + explained);
            assertTrue(explained.contains("{{core:build.output.obj}}"),
                "the offending expression must be named: " + explained);
            assertTrue(explained.contains("$input"), "the fix must be named: " + explained);
        }

        @Test
        @DisplayName("A failure on a body whose placeholder is inside a literal is not annotated")
        void failureOnASafePositionIsNotAnnotated() {
            CodeNode node = nodeWith("javascript", "const s = '{{core:build.output.obj}}';");

            String explained = node.explainedFailure(
                "exit 1: boom", node.getCodeConfig().code(), "javascript");

            assertEquals("exit 1: boom", explained);
        }

        @Test
        @DisplayName("scanCodeBody classifies every position the four languages can put a placeholder in")
        void scanClassifiesPositions() {
            assertEquals(CodeNode.PlaceholderPosition.CODE, onlyPosition("const o = {{x}};", "javascript"));
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL, onlyPosition("const o = '{{x}}';", "javascript"));
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL, onlyPosition("const o = \"{{x}}\";", "typescript"));
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL, onlyPosition("const o = `{{x}}`;", "javascript"));
            assertEquals(CodeNode.PlaceholderPosition.CODE, onlyPosition("const o = `${ {{x}} }`;", "javascript"));
            assertEquals(CodeNode.PlaceholderPosition.CODE, onlyPosition("// {{x}}\nconst o = 1;", "javascript"));
            assertEquals(CodeNode.PlaceholderPosition.CODE, onlyPosition("/* {{x}} */\nconst o = 1;", "javascript"));
            // A regex literal is not a string literal: escaping does not neutralise it either.
            assertEquals(CodeNode.PlaceholderPosition.CODE, onlyPosition("const r = /{{x}}/;", "javascript"));

            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL, onlyPosition("s = '{{x}}'", "python"));
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL, onlyPosition("s = '''{{x}}'''", "python"));
            assertEquals(CodeNode.PlaceholderPosition.CODE, onlyPosition("# {{x}}\ns = 1", "python"));

            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL, onlyPosition("S='{{x}}'", "bash"));
            assertEquals(CodeNode.PlaceholderPosition.SHELL_DOUBLE_QUOTED, onlyPosition("S=\"{{x}}\"", "bash"));
            assertEquals(CodeNode.PlaceholderPosition.CODE, onlyPosition("S={{x}}", "bash"));
            assertEquals(CodeNode.PlaceholderPosition.CODE, onlyPosition("S=$({{x}})", "bash"));
            // A substitution OPENED inside a double-quoted word is source again.
            assertEquals(CodeNode.PlaceholderPosition.CODE, onlyPosition("echo \"$(cat {{x}})\"", "bash"));
        }

        @Test
        @DisplayName("scanCodeBody reports only what the template engine would resolve")
        void scanIgnoresBracesTheEngineIgnores() {
            assertTrue(CodeNode.scanCodeBody("if (ok) {{ a } b }}", "javascript").isEmpty(),
                "a doubled brace holding a nested closing brace is not an expression");
            assertFalse(CodeNode.hasTemplateExpression("if (ok) {{ a } b }}"));
            assertTrue(CodeNode.hasTemplateExpression("const s = '{{trigger:start.output.name}}';"));
            assertNull(CodeNode.firstPlaceholderOutsideStringLiteral(
                "const s = '{{trigger:start.output.name}}';", "javascript"));
            assertEquals("{{trigger:start.output.name}}", CodeNode.firstPlaceholderOutsideStringLiteral(
                "const s = {{trigger:start.output.name}};", "javascript"));
        }

        private CodeNode.PlaceholderPosition onlyPosition(String body, String language) {
            java.util.List<CodeNode.BodyPlaceholder> found = CodeNode.scanCodeBody(body, language);
            assertEquals(1, found.size(), "expected exactly one placeholder in: " + body);
            return found.get(0).position();
        }

        private V2TemplateAdapter realTemplateAdapter() {
            SpelEvaluator spelEvaluator = new SpelEvaluator();
            spelEvaluator.init();
            PathNavigator pathNavigator = new PathNavigator();
            return new V2TemplateAdapter(new TemplateEngine(
                new TypeCastingService(),
                new NamespaceResolver(pathNavigator),
                pathNavigator,
                spelEvaluator));
        }
    }

    /** Single quotes that are still live delimiters, i.e. not preceded by an odd run of backslashes. */
    private static int unescapedSingleQuotes(String s) {
        return unescapedCount(s, '\'');
    }

    private static int unescapedDoubleQuotes(String s) {
        return unescapedCount(s, '"');
    }

    private static int unescapedCount(String s, char quote) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != quote) continue;
            int backslashes = 0;
            for (int j = i - 1; j >= 0 && s.charAt(j) == '\\'; j--) backslashes++;
            if (backslashes % 2 == 0) count++;
        }
        return count;
    }

    @Test
    @DisplayName("a failure AFTER the code resolved measures the RESOLVED code; it used to measure the {{...}} template")
    @SuppressWarnings("unchecked")
    void failureAfterResolutionMeasuresResolvedCode() throws Exception {
        String rawTemplate = "{{core:gen.output.script}}";
        String resolvedCode = "console.log('a much longer script than its template');";
        CodeNode node = new CodeNode("core:code", new Core.CodeConfig("javascript", rawTemplate, 10));
        node.setCodeExecutor(mockCodeExecutor);
        com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter adapter =
            org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
        Map<String, Object> values = new HashMap<>();
        values.put(rawTemplate, resolvedCode);
        org.mockito.Mockito.when(adapter.resolveTemplates(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(TemplateResolutionStubs.resolving(values));
        node.setTemplateAdapter(adapter);
        org.mockito.Mockito.when(mockCodeExecutor.execute(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("sandbox unavailable"));

        // The RESOLVED code as CodeNode itself would produce it (LC-018 code-position splicing
        // quotes/escapes a bare scalar placeholder, so this is NOT the raw upstream string) -
        // computed through the same production method rather than assumed, so the assertion
        // does not depend on the exact escaping scheme.
        String expectedResolved = node.resolveCodeBody(rawTemplate, "javascript", context);

        NodeExecutionResult result = node.execute(context);

        assertFalse(result.isSuccess());
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        assertEquals(expectedResolved.length(), params.get("codeLength"));
        // Never the raw {{...}} template's own length: that is what this test guards against.
        assertNotEquals(rawTemplate.length(), params.get("codeLength"));
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("templated timeoutSeconds")
    class DeferredTimeout {

        private CodeNode nodeWithTemplatedTimeout(Object resolvesTo) {
            CodeNode node = new CodeNode("core:code", new Core.CodeConfig("javascript", "return 1;", 10));
            node.setCodeExecutor(mockCodeExecutor);
            node.setDeferredScalars(Map.of("code", Map.of("timeoutSeconds", "{{core:x.output.n}}")));
            com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter adapter =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
            Map<String, Object> values = new HashMap<>();
            values.put("{{core:x.output.n}}", resolvesTo);
            org.mockito.Mockito.lenient().when(adapter.resolveTemplates(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(TemplateResolutionStubs.resolving(values));
            node.setTemplateAdapter(adapter);
            return node;
        }

        @Test
        @DisplayName("a {{...}} timeoutSeconds runs with the resolved value, not the typed default")
        @SuppressWarnings("unchecked")
        void templatedTimeoutIsResolved() throws Exception {
            CodeNode node = nodeWithTemplatedTimeout(30);
            org.mockito.ArgumentCaptor<com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeRequest> request =
                org.mockito.ArgumentCaptor.forClass(com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeRequest.class);
            org.mockito.Mockito.when(mockCodeExecutor.execute(request.capture()))
                .thenThrow(new IllegalStateException("stop after capture"));

            NodeExecutionResult result = node.execute(context);

            assertEquals(30_000, request.getValue().runTimeoutMs());
            Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals(30, params.get("timeoutSeconds"));
        }

        @Test
        @DisplayName("a {{...}} timeoutSeconds that is not a number fails the node instead of running on the default")
        void nonNumericTemplatedTimeoutFails() throws Exception {
            CodeNode node = nodeWithTemplatedTimeout("abc");

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("code") && message.contains("abc"), message);
            org.mockito.Mockito.verify(mockCodeExecutor, org.mockito.Mockito.never())
                .execute(org.mockito.ArgumentMatchers.any());
        }
    }
}
