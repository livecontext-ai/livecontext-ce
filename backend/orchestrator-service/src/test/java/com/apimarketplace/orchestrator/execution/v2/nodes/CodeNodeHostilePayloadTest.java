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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * LC-018, second audit round: code positions where the first escaping pass still let a hostile
 * upstream value become source. Every case asserts the EXACT spliced source (runs everywhere) and,
 * where the interpreter is installed on the test machine, actually runs the result and checks the
 * payload did not execute. The payloads print a sentinel; a green run proves the value stayed data.
 */
@DisplayName("CodeNode - hostile values stay inert in every position (LC-018 round 2)")
class CodeNodeHostilePayloadTest {

    private static final String PLACEHOLDER = "{{mcp:fetch_mail.output.subject}}";

    private static String resolve(String language, String body, Object value) {
        CodeNode node = CodeNode.builder()
                .nodeId("core:process")
                .codeConfig(new Core.CodeConfig(language, body, 10))
                .build();
        node.setTemplateAdapter(realTemplateAdapter());
        ExecutionContext ctx = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, Map.of(), mock(WorkflowPlan.class))
                .withStepOutput("mcp:fetch_mail", Map.of("output", Map.of("subject", value)));
        return node.resolveCodeBody(body, language, ctx);
    }

    private static V2TemplateAdapter realTemplateAdapter() {
        SpelEvaluator spelEvaluator = new SpelEvaluator();
        spelEvaluator.init();
        PathNavigator pathNavigator = new PathNavigator();
        return new V2TemplateAdapter(new TemplateEngine(
                new TypeCastingService(), new NamespaceResolver(pathNavigator), pathNavigator, spelEvaluator));
    }

    // ---- interpreters (optional: exact-source assertions always run) ----

    private static boolean available(String... versionCommand) {
        try {
            Process p = new ProcessBuilder(versionCommand).redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Feeds the source on stdin ({@code node -} / {@code bash -s}): no temp-file path to translate. */
    private static String run(String interpreter, String suffix, String source) throws Exception {
        String stdinFlag = "node".equals(interpreter) ? "-" : "-s";
        Process p = new ProcessBuilder(interpreter, stdinFlag).redirectErrorStream(true).start();
        try (var in = p.getOutputStream()) {
            in.write(source.getBytes(StandardCharsets.UTF_8));
        }
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("interpreter timed out");
        }
        return new String(out, StandardCharsets.UTF_8).replace("\r", "");
    }

    @Nested
    @DisplayName("JavaScript code positions")
    class JavaScript {

        private static final String SENTINEL_CALL = "process.stdout.write(\"PWNED\")";

        private void assertNodeDoesNotRunPayload(String source) throws Exception {
            assumeTrue(available("node", "--version"), "node not installed");
            assertThat(run("node", ".js", "let $output;\n" + source)).doesNotContain("PWNED");
        }

        @Test
        @DisplayName("a block comment cannot be closed by the value (Jackson does not escape '/')")
        void blockCommentStaysClosed() throws Exception {
            String source = resolve("javascript", "/* " + PLACEHOLDER + " */\n$output = 1;",
                    "*/ " + SENTINEL_CALL + "; /*");

            assertThat(source).doesNotContain("*/ process").contains("\\u002F");
            assertNodeDoesNotRunPayload(source);
        }

        @Test
        @DisplayName("a regex literal cannot be closed by the value")
        void regexLiteralStaysClosed() throws Exception {
            String source = resolve("javascript", "const r = /" + PLACEHOLDER + "/;",
                    "/; " + SENTINEL_CALL + "; //");

            assertThat(source).doesNotContain("/; process");
            assertNodeDoesNotRunPayload(source);
        }

        @Test
        @DisplayName("a line comment cannot be ended by a U+2028 in the value")
        void lineCommentIsNotEndedByLineSeparator() throws Exception {
            String source = resolve("javascript", "// " + PLACEHOLDER + "\n$output = 1;",
                    "x " + SENTINEL_CALL);

            assertThat(source).doesNotContain(" ").contains("\\u2028");
            assertNodeDoesNotRunPayload(source);
        }

        @Test
        @DisplayName("the escaped value still decodes to the original string at a real code position")
        void valueIsUnchanged() throws Exception {
            String value = "a/b '`${x}` <tag>   end";
            String source = resolve("javascript", "const v = " + PLACEHOLDER + ";\nprocess.stdout.write(v);", value);

            assumeTrue(available("node", "--version"), "node not installed");
            assertThat(run("node", ".js", source)).isEqualTo(value.replace("\r", ""));
        }
    }

    @Nested
    @DisplayName("Python f-string replacement fields")
    class Python {

        @Test
        @DisplayName("a placeholder inside a replacement field is CODE, so the value is a string, not an expression")
        void replacementFieldIsCode() {
            String body = "print(f\"{ " + PLACEHOLDER + " }\")";
            String payload = "exec(chr(112)+chr(114)+chr(105)+chr(110)+chr(116)+chr(40)+chr(41))";

            assertThat(CodeNode.scanCodeBody(body, "python").get(0).position())
                    .isEqualTo(CodeNode.PlaceholderPosition.CODE);
            assertThat(resolve("python", body, payload))
                    .isEqualTo("print(f\"{ \"" + payload + "\" }\")");
        }

        @Test
        @DisplayName("the literal part of an f-string still gets the brace-doubling escape")
        void literalPartStaysEscaped() {
            String body = "print(f\"hello " + PLACEHOLDER + " {name}\")";

            assertThat(CodeNode.scanCodeBody(body, "python").get(0).position())
                    .isEqualTo(CodeNode.PlaceholderPosition.F_STRING_LITERAL);
            assertThat(resolve("python", body, "{__import__('os')}"))
                    .isEqualTo("print(f\"hello {{__import__(\\'os\\')}} {name}\")");
        }
    }

    @Nested
    @DisplayName("Bash here-documents and ANSI-C words")
    class Bash {

        private String runBash(String source) throws Exception {
            assumeTrue(available("bash", "--version"), "bash not installed");
            return run("bash", ".sh", source);
        }

        @Test
        @DisplayName("an unquoted heredoc: a quote in the body does not turn $(...) in the value into a command")
        void unquotedHeredocDoesNotExpandTheValue() throws Exception {
            String body = "cat <<EOF\nit's " + PLACEHOLDER + "\nEOF\n";

            assertThat(CodeNode.scanCodeBody(body, "bash").get(0).position())
                    .isEqualTo(CodeNode.PlaceholderPosition.SHELL_HEREDOC_UNQUOTED);
            String source = resolve("bash", body, "$(echo PWNED) `echo PWNED` \\");
            assertThat(source).isEqualTo("cat <<EOF\nit's \\$(echo PWNED) \\`echo PWNED\\` \\\\\nEOF\n");
            assertThat(runBash(source)).isEqualTo("it's $(echo PWNED) `echo PWNED` \\\n");
        }

        @Test
        @DisplayName("a value line equal to the delimiter cannot end the document early")
        void valueCannotEndTheDocument() throws Exception {
            String body = "cat <<'EOF'\n" + PLACEHOLDER + "\nEOF\n";

            String source = resolve("bash", body, "x\nEOF\necho PWNED");
            assertThat(source).isEqualTo("cat <<'EOF'\nx\n EOF\necho PWNED\nEOF\n");
            assertThat(runBash(source)).isEqualTo("x\n EOF\necho PWNED\n");
        }

        @Test
        @DisplayName("a legitimate heredoc value is rendered unchanged")
        void legitimateHeredocValue() throws Exception {
            String source = resolve("bash", "cat <<-EOF\n\tHello " + PLACEHOLDER + "\n\tEOF\n", "World");
            assertThat(runBash(source)).isEqualTo("Hello World\n");
        }

        @Test
        @DisplayName("an ANSI-C word: \\' does not end it, and the value stays inside")
        void ansiCWordKeepsTheValue() throws Exception {
            String body = "echo $'it\\'s " + PLACEHOLDER + " end'";

            assertThat(CodeNode.scanCodeBody(body, "bash").get(0).position())
                    .isEqualTo(CodeNode.PlaceholderPosition.SHELL_ANSI_C_QUOTED);
            String payload = "$(echo PWNED)'; echo PWNED2 #\\x27";
            String source = resolve("bash", body, payload);
            assertThat(runBash(source)).isEqualTo("it's " + payload + " end\n");
        }

        @Test
        @DisplayName("a shift inside arithmetic is not mistaken for a heredoc")
        void arithmeticShiftIsNotAHeredoc() {
            String body = "x=$((1<<2))\necho " + PLACEHOLDER;

            assertThat(CodeNode.scanCodeBody(body, "bash").get(0).position())
                    .isEqualTo(CodeNode.PlaceholderPosition.CODE);
        }
    }
}
