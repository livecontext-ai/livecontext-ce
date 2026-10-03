package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import com.apimarketplace.orchestrator.services.code.CodeExecutor;
import com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeRequest;
import com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeResult;
import com.apimarketplace.orchestrator.services.template.NamespaceResolver;
import com.apimarketplace.orchestrator.services.template.PathNavigator;
import com.apimarketplace.orchestrator.services.template.SpelEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-018, the two holes the first pass left in the escaping itself.
 *
 * <p>The first pass classified every {{...}} in a code body as either inside a string literal
 * (escapable, stays data) or at a code position (source), and escaped accordingly. Two things
 * were wrong with that:
 *
 * <ol>
 *   <li>the JavaScript literal scanner did not model REGEX literals, so the quote in the
 *       everyday {@code /['"]/} opened a string literal that never closed. Every position after
 *       it in the body was then reported as being inside that literal, which is the permissive
 *       direction: a placeholder at a real code position downstream was classified as a string
 *       one, so the plan-save guard did not refuse it and the run escaped it as if quotes
 *       surrounded it, splicing it into source;</li>
 *   <li>a SCALAR at a code position was escaped as if it sat inside a literal. That escape only
 *       neutralises quotes, backslashes, newlines, backticks and {@code $}, so a value made of
 *       none of them, an identifier and a call for instance, was spliced verbatim at a position
 *       that IS source and ran.</li>
 * </ol>
 *
 * <p>Both are reachable from data the tenant does not control (a mail body, a webhook payload,
 * any tool output), which is what makes them injection rather than the author's own code.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CodeNode - a value at a code position is a literal, never source (LC-018)")
class CodeNodeCodePositionSpliceTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private CodeExecutor mockCodeExecutor;

    /**
     * A payload carrying NONE of the characters the string escape neutralises: no quote, no
     * backslash, no backtick, no dollar, no newline. This is the shape that walked straight
     * through the old escape and executed.
     */
    private static final String QUOTE_FREE_PAYLOAD = "process.mainModule.require(String.fromCharCode(102))";

    private ExecutionContext contextWithValue(Object value) {
        ExecutionContext base = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, Map.of(), mockPlan);
        return base.withStepOutput("mcp:fetch_mail", Map.of("output", Map.of("subject", value)));
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

    private String sentCode(CodeNode node, ExecutionContext ctx, String language) throws Exception {
        when(mockCodeExecutor.execute(any(CodeRequest.class))).thenReturn(
                new CodeResult(language, "1.0", "__RESULT__null\n", "", 0, null, "", null, null));

        node.execute(ctx);

        ArgumentCaptor<CodeRequest> captor = ArgumentCaptor.forClass(CodeRequest.class);
        verify(mockCodeExecutor).execute(captor.capture());
        return captor.getValue().code();
    }

    private static V2TemplateAdapter realTemplateAdapter() {
        SpelEvaluator spelEvaluator = new SpelEvaluator();
        spelEvaluator.init();
        PathNavigator pathNavigator = new PathNavigator();
        return new V2TemplateAdapter(new TemplateEngine(
                new TypeCastingService(),
                new NamespaceResolver(pathNavigator),
                pathNavigator,
                spelEvaluator));
    }

    @Nested
    @DisplayName("Hole 1 - a regex literal used to desynchronise the whole body")
    class RegexLiteralMask {

        /** A regex character class holding both quote characters: ordinary validation code. */
        private static final String BODY_WITH_REGEX = """
                const quoted = /['"]/;
                const cmd = {{mcp:fetch_mail.output.subject}};
                """;

        @Test
        @DisplayName("a placeholder after a regex holding a quote is reported at a CODE position")
        void placeholderAfterARegexIsCode() {
            List<CodeNode.BodyPlaceholder> found = CodeNode.scanCodeBody(BODY_WITH_REGEX, "javascript");

            assertEquals(1, found.size(), "expected exactly one placeholder");
            assertEquals(CodeNode.PlaceholderPosition.CODE, found.get(0).position(),
                    "the quote inside the regex must not open a string literal that swallows the "
                            + "rest of the body");
        }

        @Test
        @DisplayName("and the builder's CODE_TEMPLATE_IN_BODY warning therefore reports that body")
        void planSaveGuardRefusesIt() {
            assertNotNull(CodeNode.firstPlaceholderOutsideStringLiteral(BODY_WITH_REGEX, "javascript"),
                    "a body the scanner mis-reads is a body the save guard silently accepts");
        }

        @Test
        @DisplayName("a DIVISION is not mistaken for a regex, so a real literal after it stays a literal")
        void divisionIsNotARegex() {
            String body = "const ratio = total / count;\nconst s = '{{mcp:fetch_mail.output.subject}}';";

            List<CodeNode.BodyPlaceholder> found = CodeNode.scanCodeBody(body, "javascript");

            assertEquals(1, found.size());
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL, found.get(0).position(),
                    "reading a division as a regex would swallow the author's quotes and turn a "
                            + "correctly written body into a refused one");
        }

        @Test
        @DisplayName("a regex directly after a keyword is still a regex")
        void regexAfterAKeywordIsARegex() {
            String body = "function f(s) { return /['\"]/.test(s); }\nconst s = '{{mcp:fetch_mail.output.subject}}';";

            List<CodeNode.BodyPlaceholder> found = CodeNode.scanCodeBody(body, "javascript");

            assertEquals(1, found.size());
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL, found.get(0).position());
        }

        @Test
        @DisplayName("a postfix ++ is followed by a division, not by a regex")
        void postfixIncrementIsFollowedByDivision() {
            String body = "let i = 0; const half = i++ / 2;\nconst s = '{{mcp:fetch_mail.output.subject}}';";

            List<CodeNode.BodyPlaceholder> found = CodeNode.scanCodeBody(body, "javascript");

            assertEquals(1, found.size());
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL, found.get(0).position());
        }

        @Test
        @DisplayName("a placeholder INSIDE the regex is a code position, as it always was")
        void placeholderInsideARegexIsCode() {
            assertEquals(CodeNode.PlaceholderPosition.CODE,
                    CodeNode.scanCodeBody("const r = /{{x}}/;", "javascript").get(0).position());
        }

        @Test
        @DisplayName("a slash inside a string literal does not start a regex")
        void slashInsideAStringIsNotARegex() {
            String body = "const url = 'https://example.com/a';\nconst s = '{{mcp:fetch_mail.output.subject}}';";

            List<CodeNode.BodyPlaceholder> found = CodeNode.scanCodeBody(body, "javascript");

            assertEquals(1, found.size());
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL, found.get(0).position());
        }

        @Test
        @DisplayName("an unterminated regex bails out at the newline instead of eating the file")
        void unterminatedRegexBailsAtTheNewline() {
            // Nothing closes the slash on the first line. Reporting the rest of the body as regex
            // would hide a real string literal; reporting it as code is the strict direction.
            String body = "const bad = /abc\nconst s = '{{mcp:fetch_mail.output.subject}}';";

            List<CodeNode.BodyPlaceholder> found = CodeNode.scanCodeBody(body, "javascript");

            assertEquals(1, found.size());
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL, found.get(0).position());
        }
    }

    @Nested
    @DisplayName("Hole 2 - a scalar at a code position was spliced as source")
    class ScalarAtACodePosition {

        @Test
        @DisplayName("a quote-free payload arrives as a quoted string, not as a call")
        void quoteFreePayloadBecomesAStringLiteral() throws Exception {
            CodeNode node = nodeWith("javascript", "const cmd = {{mcp:fetch_mail.output.subject}};");

            String sent = sentCode(node, contextWithValue(QUOTE_FREE_PAYLOAD), "javascript");

            assertTrue(sent.contains("const cmd = \"" + QUOTE_FREE_PAYLOAD + "\";"),
                    "the value must arrive as a complete string literal:\n" + sent);
            assertFalse(sent.contains("const cmd = " + QUOTE_FREE_PAYLOAD + ";"),
                    "the value was spliced as executable source:\n" + sent);
        }

        @Test
        @DisplayName("a payload carrying a quote cannot close the literal it is given")
        void payloadCarryingAQuoteStaysInside() throws Exception {
            CodeNode node = nodeWith("javascript", "const cmd = {{mcp:fetch_mail.output.subject}};");

            String sent = sentCode(node, contextWithValue("a\"; process.exit(1); \""), "javascript");

            assertTrue(sent.contains("const cmd = \"a\\\"; process.exit(1); \\\"\";"),
                    "JSON encoding must escape the value's own quotes:\n" + sent);
        }

        @Test
        @DisplayName("a number is still spliced bare, so the workflows that splice a count keep working")
        void numberIsStillSplicedBare() throws Exception {
            CodeNode node = nodeWith("javascript", "const n = {{mcp:fetch_mail.output.subject}};");

            String sent = sentCode(node, contextWithValue(42), "javascript");

            assertTrue(sent.contains("const n = 42;"), "a number must stay a number:\n" + sent);
        }

        @Test
        @DisplayName("a boolean is still spliced bare")
        void booleanIsStillSplicedBare() throws Exception {
            CodeNode node = nodeWith("javascript", "const flag = {{mcp:fetch_mail.output.subject}};");

            String sent = sentCode(node, contextWithValue(true), "javascript");

            assertTrue(sent.contains("const flag = true;"), "a boolean must stay a boolean:\n" + sent);
        }

        @Test
        @DisplayName("a value that only LOOKS numeric-adjacent is quoted, not spliced bare")
        void almostNumericIsQuoted() throws Exception {
            CodeNode node = nodeWith("javascript", "const n = {{mcp:fetch_mail.output.subject}};");

            String sent = sentCode(node, contextWithValue("1;process.exit(1)"), "javascript");

            assertTrue(sent.contains("const n = \"1;process.exit(1)\";"),
                    "the bare-splice set must be numbers and booleans only:\n" + sent);
        }

        @Test
        @DisplayName("Python gets the same treatment - JSON is a Python literal too")
        void pythonScalarIsQuoted() throws Exception {
            CodeNode node = nodeWith("python", "cmd = {{mcp:fetch_mail.output.subject}}");

            String sent = sentCode(node, contextWithValue("__import__(chr(111)+chr(115))"), "python");

            assertTrue(sent.contains("cmd = \"__import__(chr(111)+chr(115))\""),
                    "the value must arrive as a Python string literal:\n" + sent);
        }

        @Test
        @DisplayName("Bash gets a single-quoted word, the only bash form that expands nothing")
        void bashScalarIsSingleQuoted() throws Exception {
            CodeNode node = nodeWith("bash", "OUT={{mcp:fetch_mail.output.subject}}");

            String sent = sentCode(node, contextWithValue("$(id); rm -rf /tmp/x"), "bash");

            assertTrue(sent.contains("OUT='$(id); rm -rf /tmp/x'"),
                    "an unquoted splice command-substitutes and separates statements:\n" + sent);
            assertFalse(sent.contains("OUT=$(id)"),
                    "the command substitution was left live:\n" + sent);
        }

        @Test
        @DisplayName("a bash payload with its own quote cannot end the word it is given")
        void bashPayloadCannotEndTheWord() throws Exception {
            CodeNode node = nodeWith("bash", "OUT={{mcp:fetch_mail.output.subject}}");

            String sent = sentCode(node, contextWithValue("x'; id; echo '"), "bash");

            assertTrue(sent.contains("'\\''"), "bash needs its quote idiom here too:\n" + sent);
            assertFalse(sent.contains("OUT='x'; id"), "the value ended the word:\n" + sent);
        }

        @Test
        @DisplayName("an object at a code position still keeps the raw JSON the node has always emitted")
        void objectAtACodePositionIsUnchanged() throws Exception {
            CodeNode node = nodeWith("javascript", "const o = {{mcp:fetch_mail.output.subject}};");

            String sent = sentCode(node, contextWithValue(Map.of("a", 1)), "javascript");

            assertTrue(sent.contains("const o = {\"a\":1};"),
                    "an object literal must not be quoted into a string:\n" + sent);
        }

        @Test
        @DisplayName("a placeholder INSIDE a literal is untouched by this change")
        void insideALiteralIsUnchanged() throws Exception {
            CodeNode node = nodeWith("javascript", "const s = '{{mcp:fetch_mail.output.subject}}';");

            String sent = sentCode(node, contextWithValue("Weekly report"), "javascript");

            assertTrue(sent.contains("const s = 'Weekly report';"),
                    "the string-literal path must keep splicing the plain value:\n" + sent);
        }
    }

    @Nested
    @DisplayName("Hole 3 - a Python f-string evaluates the braces of the value spliced into it")
    class PythonFString {

        /**
         * The Python twin of the JavaScript {@code ${...}} interpolation, which the scanner
         * already reported as a code position. An f-string looks like an ordinary literal to a
         * quote-counting scanner, so a value spliced into one was escaped for quotes only, and
         * Python then EVALUATED the {@code {...}} it carried. No quote is needed to build such a
         * payload, so the quote escaping was not in the way.
         */
        private static final String BRACE_PAYLOAD = "{__import__(chr(111)+chr(115)).system(chr(105))}";

        @Test
        @DisplayName("a value spliced into an f-string has its braces doubled, so nothing is evaluated")
        void bracesAreDoubled() throws Exception {
            CodeNode node = nodeWith("python", "msg = f\"subject: {{mcp:fetch_mail.output.subject}}\"");

            String sent = sentCode(node, contextWithValue(BRACE_PAYLOAD), "python");

            assertTrue(sent.contains("{{__import__"), "the opening brace must be doubled:\n" + sent);
            assertTrue(sent.contains("(chr(105))}}"), "the closing brace must be doubled:\n" + sent);
            assertFalse(sent.contains("\"subject: {__import__"),
                    "a single brace is an f-string interpolation and is evaluated:\n" + sent);
        }

        @Test
        @DisplayName("the scanner tags an f-string position for every prefix spelling")
        void everyPrefixSpellingIsDetected() {
            assertEquals(CodeNode.PlaceholderPosition.F_STRING_LITERAL,
                    CodeNode.scanCodeBody("msg = f\"{{x}}\"", "python").get(0).position());
            assertEquals(CodeNode.PlaceholderPosition.F_STRING_LITERAL,
                    CodeNode.scanCodeBody("msg = F'{{x}}'", "python").get(0).position());
            assertEquals(CodeNode.PlaceholderPosition.F_STRING_LITERAL,
                    CodeNode.scanCodeBody("msg = rf'''{{x}}'''", "python").get(0).position());
            // Not an f-string: an ordinary literal, and a variable whose name merely ends in f.
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL,
                    CodeNode.scanCodeBody("msg = '{{x}}'", "python").get(0).position());
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL,
                    CodeNode.scanCodeBody("msg = r'{{x}}'", "python").get(0).position());
            assertEquals(CodeNode.PlaceholderPosition.STRING_LITERAL,
                    CodeNode.scanCodeBody("msg = conf['{{x}}']", "python").get(0).position());
        }

        @Test
        @DisplayName("an f-string placeholder is NOT refused on save - escaping it in place still works")
        void fStringIsNotRefusedOnSave() {
            assertNull(CodeNode.firstPlaceholderOutsideStringLiteral(
                    "msg = f\"subject: {{mcp:fetch_mail.output.subject}}\"", "python"),
                    "doubling the braces keeps the value data, so the body stays saveable");
        }

        @Test
        @DisplayName("a benign value still renders inside the f-string")
        void benignValueStillRenders() throws Exception {
            CodeNode node = nodeWith("python", "msg = f\"subject: {{mcp:fetch_mail.output.subject}}\"");

            String sent = sentCode(node, contextWithValue("Weekly report"), "python");

            assertTrue(sent.contains("msg = f\"subject: Weekly report\""),
                    "a value with no brace must be unchanged:\n" + sent);
        }
    }

    @Nested
    @DisplayName("Hole 4 - a double-quoted bash word is a literal, and re-quoting it changed what the node printed")
    class BashDoubleQuotedWord {

        /**
         * The regression this class pins: {@code echo "{{...}}"} is how bash bodies are written,
         * and classifying that position as CODE sent it through the code-position splice, which
         * gives the value its own delimiters. Every such node started printing {@code 'hello'}
         * instead of {@code hello}. The value is data either way, so this was a silent output
         * change, not a security hole, which is exactly why nothing caught it.
         */
        @Test
        @DisplayName("a benign value inside a double-quoted word arrives without added quotes")
        void doubleQuotedWordKeepsThePlainValue() throws Exception {
            CodeNode node = nodeWith("bash", "echo \"{{mcp:fetch_mail.output.subject}}\"");

            String sent = sentCode(node, contextWithValue("hello"), "bash");

            assertTrue(sent.contains("echo \"hello\""),
                    "the value must be spliced in place, not re-quoted:\n" + sent);
            assertFalse(sent.contains("'hello'"),
                    "the code-position splice must not run inside a word the author quoted:\n" + sent);
        }

        @Test
        @DisplayName("a bare position is still given its own quotes")
        void barePositionIsStillQuoted() throws Exception {
            CodeNode node = nodeWith("bash", "OUT={{mcp:fetch_mail.output.subject}}");

            String sent = sentCode(node, contextWithValue("$(id)"), "bash");

            assertTrue(sent.contains("OUT='$(id)'"), "a bare word has no delimiters to escape into:\n" + sent);
        }

        @Test
        @DisplayName("the four characters a double-quoted word expands are escaped in place")
        void expansionsAreNeutralisedInPlace() throws Exception {
            CodeNode node = nodeWith("bash", "echo \"{{mcp:fetch_mail.output.subject}}\"");

            String sent = sentCode(node, contextWithValue("$(id) `id` \"x\" \\ ${HOME}"), "bash");

            assertTrue(sent.contains("\\$(id)"), "command substitution must be escaped:\n" + sent);
            assertTrue(sent.contains("\\`id\\`"), "backtick substitution must be escaped:\n" + sent);
            assertTrue(sent.contains("\\\"x\\\""), "the value must not close the word:\n" + sent);
            assertTrue(sent.contains("\\\\ "), "a backslash must be escaped first:\n" + sent);
            assertTrue(sent.contains("\\${HOME}"), "parameter expansion must be escaped:\n" + sent);
        }

        @Test
        @DisplayName("a substitution opened inside the word is still a code position")
        void substitutionInsideTheWordIsStillCode() throws Exception {
            CodeNode node = nodeWith("bash", "echo \"$(cat {{mcp:fetch_mail.output.subject}})\"");

            String sent = sentCode(node, contextWithValue("/etc/passwd; id"), "bash");

            assertTrue(sent.contains("cat '/etc/passwd; id'"),
                    "the shell re-parses inside $(...), so the value needs its own quotes there:\n" + sent);
            assertEquals("{{mcp:fetch_mail.output.subject}}", CodeNode.firstPlaceholderOutsideStringLiteral(
                    "echo \"$(cat {{mcp:fetch_mail.output.subject}})\"", "bash"),
                    "and the plan-save guard must still refuse that shape");
        }

        @Test
        @DisplayName("a double-quoted placeholder is NOT refused on save - the idiom keeps working")
        void doubleQuotedIsNotRefusedOnSave() {
            assertNull(CodeNode.firstPlaceholderOutsideStringLiteral(
                    "echo \"{{mcp:fetch_mail.output.subject}}\"", "bash"),
                    "escaping in place keeps the value data, so the body stays saveable");
        }
    }

    @Nested
    @DisplayName("spliceAtCodePosition, unit level")
    class SpliceUnit {

        private CodeNode plainNode() {
            return CodeNode.builder()
                    .nodeId("core:process")
                    .codeConfig(new Core.CodeConfig("javascript", "noop", 10))
                    .build();
        }

        @Test
        @DisplayName("every scalar shape maps to a literal of the target language")
        void scalarShapes() {
            CodeNode node = plainNode();

            assertEquals("42", node.spliceAtCodePosition(42, "javascript"));
            assertEquals("-1.5", node.spliceAtCodePosition(-1.5, "python"));
            assertEquals("true", node.spliceAtCodePosition(true, "bash"));
            assertEquals("\"abc\"", node.spliceAtCodePosition("abc", "typescript"));
            assertEquals("\"\"", node.spliceAtCodePosition("", "javascript"));
            assertEquals("'abc'", node.spliceAtCodePosition("abc", "bash"));
        }

        @Test
        @DisplayName("a structured value keeps its JSON in the languages whose literals are JSON")
        void structuredShapes() {
            CodeNode node = plainNode();

            assertEquals("{\"a\":1}", node.spliceAtCodePosition(Map.of("a", 1), "javascript"));
            assertEquals("[1,2]", node.spliceAtCodePosition(List.of(1, 2), "python"));
            assertEquals("'{\"a\":1}'", node.spliceAtCodePosition(Map.of("a", 1), "bash"),
                    "bash must quote the JSON: an unquoted $(...) inside it would substitute");
        }

        @Test
        @DisplayName("firstPlaceholderOutsideStringLiteral still ignores a body with no expression")
        void noExpressionIsStillNull() {
            assertNull(CodeNode.firstPlaceholderOutsideStringLiteral(
                    "const r = /['\"]/; const s = $input.a.b;", "javascript"));
        }
    }
    /**
     * Regression review 2026-09-29: before LC-018 a JSON object or array carried as TEXT (an agent
     * response, an HTTP body) was spliced raw and became an object, and saved code relies on it
     * ({@code const r = {{...}}; r.field}). LC-018 turned it into a quoted string, so r.field silently
     * became undefined. It is an object again, re-serialised from the parsed JSON, so no source can
     * ride along.
     */
    @Nested
    @DisplayName("JSON carried as text is spliced as that JSON again (regression 2026-09-29)")
    class JsonText {

        @Test
        @DisplayName("a JSON object string becomes an object literal in javascript")
        void jsonObjectTextBecomesObject() throws Exception {
            CodeNode node = nodeWith("javascript", "const r = {{mcp:fetch_mail.output.subject}}; $output = r.a;");
            String sent = sentCode(node, contextWithValue("{\"a\": 1, \"b\": [2, 3]}"), "javascript");
            assertTrue(sent.contains("const r = {\"a\":1,\"b\":[2,3]};"), sent);
        }

        @Test
        @DisplayName("a JSON array string becomes a list literal in python")
        void jsonArrayTextBecomesListInPython() throws Exception {
            CodeNode node = nodeWith("python", "items = {{mcp:fetch_mail.output.subject}}");
            String sent = sentCode(node, contextWithValue(" [1, 2] "), "python");
            assertTrue(sent.contains("items = [1,2]"), sent);
        }

        @Test
        @DisplayName("JSON followed by code is NOT data: the whole text stays a quoted string")
        void jsonWithTrailingCodeStaysQuoted() throws Exception {
            CodeNode node = nodeWith("javascript", "const r = {{mcp:fetch_mail.output.subject}};");
            String sent = sentCode(node, contextWithValue("{\"a\":1}; process.exit(1)"), "javascript");
            // A quoted string (the literal escapes its braces as { / }), never an object.
            assertTrue(sent.contains("const r = \"\\u007B\\\"a\\\":1\\u007D; process.exit(1)\";"), sent);
            assertFalse(sent.contains("const r = {"), sent);
        }

        @Test
        @DisplayName("a string that is not JSON is still a quoted string (the LC-018 protection holds)")
        void plainTextStaysQuoted() throws Exception {
            CodeNode node = nodeWith("javascript", "const r = {{mcp:fetch_mail.output.subject}};");
            String sent = sentCode(node, contextWithValue(QUOTE_FREE_PAYLOAD), "javascript");
            assertTrue(sent.contains("const r = \"" + QUOTE_FREE_PAYLOAD + "\";"), sent);
        }

        @Test
        @DisplayName("bash never takes JSON at a code position: JSON text stays a single-quoted word")
        void bashKeepsSingleQuotedWord() throws Exception {
            CodeNode node = nodeWith("bash", "echo {{mcp:fetch_mail.output.subject}}");
            String sent = sentCode(node, contextWithValue("{\"a\":1}"), "bash");
            assertTrue(sent.contains("echo '{\"a\":1}'"), sent);
        }

        @Test
        @DisplayName("hostile content inside JSON text is escaped like any other value: it cannot end a script, a comment or a literal")
        void hostileContentInsideJsonTextIsEscaped() throws Exception {
            CodeNode node = nodeWith("javascript", "const r = {{mcp:fetch_mail.output.subject}};");
            // </script>, a template interpolation, a line separator (ends a JS line comment) and a quote.
            String sent = sentCode(node, contextWithValue("{\"x\":\"</script>${y} '\"}"), "javascript");
            assertTrue(sent.contains(
                    "const r = {\"x\":\"\\u003C\\u002Fscript\\u003E\\u0024\\u007By\\u007D\\u2028\\u0027\"};"), sent);
            // The spliced statement itself (the $input prelude carries the raw value inside its own
            // string literal, a separate mechanism covered by CodeNodeHostilePayloadTest).
            String spliced = sent.substring(sent.indexOf("const r = "));
            spliced = spliced.substring(0, spliced.indexOf(';') + 1);
            assertFalse(spliced.contains("</script>"), spliced);
            assertFalse(spliced.contains(" "), spliced);
        }

        @Test
        @DisplayName("regression: JSON text with true/false/null becomes a Python literal (True/False/None), not a NameError")
        void jsonLiteralsBecomePythonLiterals() throws Exception {
            CodeNode node = nodeWith("python", "items = {{mcp:fetch_mail.output.subject}}");
            String sent = sentCode(node, contextWithValue("[true, false, null, {\"ok\": true, \"s\": \"it's\"}]"), "python");
            assertTrue(sent.contains("items = [True,False,None,{\"ok\":True,\"s\":\"it\\u0027s\"}]"), sent);
        }

        @Test
        @DisplayName("a structured value with booleans and null is a Python literal too, at every depth")
        void structuredValueBecomesPythonLiteral() throws Exception {
            CodeNode node = nodeWith("python", "cfg = {{mcp:fetch_mail.output.subject}}");
            java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("enabled", true);
            value.put("limit", null);
            value.put("tags", java.util.List.of(false, 2));
            String sent = sentCode(node, contextWithValue(value), "python");
            assertTrue(sent.contains("cfg = {\"enabled\":True,\"limit\":None,\"tags\":[False,2]}"), sent);
        }

        @Test
        @DisplayName("JSON text deeper than the node's parser allows is not parsed: it stays a quoted string")
        void jsonTextBeyondTheParserCapsStaysQuoted() throws Exception {
            CodeNode node = nodeWith("javascript", "const r = {{mcp:fetch_mail.output.subject}};");
            // 100 levels: over the bounded mapper's nesting cap (64), well under Jackson's default (1000).
            String deep = "[".repeat(100) + "]".repeat(100);
            String sent = sentCode(node, contextWithValue(deep), "javascript");
            assertTrue(sent.contains("const r = \"" + "[".repeat(100)), sent);
        }

        @Test
        @DisplayName("a bare boolean is True/False in python, still true/false in javascript")
        void bareBooleanFollowsTheLanguage() throws Exception {
            CodeNode python = nodeWith("python", "flag = {{mcp:fetch_mail.output.subject}}");
            assertTrue(sentCode(python, contextWithValue(false), "python").contains("flag = False"));
        }
    }
}
