package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.tools.credential.CredentialToolsProvider;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * credential_selector matches an account NAME, and an agent cannot invent one.
 *
 * <p>The field shipped fully documented on what it MEANS and silent on where a valid
 * value comes from, which leaves an agent one move: set it to an expression and hope.
 * Because the field is deliberately fail-closed, every such guess is a failed run, so
 * the gap does not degrade gracefully. These tests pin the three surfaces that close
 * it, each of which is the only one some agent will read: the discovery tool's own
 * description, the build-time prompt for adding a tool step, and what describe reports
 * back on a node that already carries the field.
 *
 * <p>The first assertion is the load-bearing one. get_connected_services used to state
 * that only isDefault=true credentials are ever used, which stopped being true the day
 * this field shipped: an agent that believes it has no reason to look at the other
 * entries, and the multi-account case is precisely the one where they are the answer.
 */
@DisplayName("credential_selector: an agent can find out which account names exist")
class CredentialSelectorDiscoverabilityTest {

    /**
     * Any phrasing of "only the default credential is ever used", agent-facing or not.
     *
     * <p>Every backslash is DOUBLED because this is a Java string literal, not a regex
     * literal. Written singly it still compiles (\s is a legal escape for a space since
     * Java 15) but the compiler resolves the escapes first, and \b becomes BACKSPACE
     * U+0008. No source file contains a backspace, so the pattern matched nothing and
     * the scan below passed on every input, including the exact sentence it exists to
     * catch. That is why matchesTheClaimItIsMeantToCatch exists.
     *
     * <p>It scans whole files, comments included, so it will also reject a TRUE sentence
     * shaped like the false one ("only the default is used when nothing names another").
     * Rephrase around it. Loosening the pattern to admit one such sentence is how it
     * stops catching the claim it was written for.
     */
    private static final Pattern ONLY_DEFAULTS_ARE_USED = Pattern.compile(
            // "only ... default ... is/are used"
            "only\\s+[^.\\n]{0,40}default[^.\\n]{0,40}\\b(is|are)\\s+used"
            // "... the default ... is the only one used" (the same claim, reordered)
            + "|default[^.\\n]{0,40}\\b(is|are)\\s+the\\s+only"
            // "non-default credentials are never used" (the claim stated negatively)
            + "|non-?default[^.\\n]{0,40}\\b(is|are)\\s+(never|not)\\s+used",
            Pattern.CASE_INSENSITIVE);

    /** The spellings this claim has actually been written in, plus paraphrases of its shape. */
    private static final List<String> RETRACTED_CLAIM_SAMPLES = List.of(
            "Only isDefault=true credentials are used when executing tools.",
            "Only 'isDefault=true' credentials are used when executing tools.",
            "Only default credentials are used for execution.",
            "// Count default credentials (only defaults are used for execution)",
            // These two say the same thing without leading with "only", which the first
            // version of the pattern required. One of them is almost word for word this
            // test's own @DisplayName, so the guard was narrower than its name promised.
            "The default credential is the only one used.",
            "Non-default credentials are never used.");

    private static String discoveryToolText() {
        AgentToolDefinition tool = new CredentialToolsProvider(null).getTools().stream()
                .filter(t -> "get_connected_services".equals(t.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("get_connected_services is not offered at all"));
        return tool.description() + "\n" + tool.helpText();
    }

    @Test
    @DisplayName("the claim pattern matches the claim, so a clean scan means something")
    void matchesTheClaimItIsMeantToCatch() {
        // Without this, de-escaping the pattern turns the scan below into a no-op that
        // reports success forever. A guard has to be shown to be able to fail.
        for (String sample : RETRACTED_CLAIM_SAMPLES) {
            assertThat(ONLY_DEFAULTS_ARE_USED.matcher(sample).find())
                    .as("the scan would not catch: %s", sample)
                    .isTrue();
        }
        assertThat(ONLY_DEFAULTS_ARE_USED.matcher(
                "Executing a tool directly uses the default one unless the call names another with credential_name. The others are selectable.").find())
                .as("the corrected wording must NOT trip the scan, or it can never go green")
                .isFalse();
    }

    @Test
    @DisplayName("no agent-facing string still claims non-default credentials are unusable")
    void nothingStillDismissesNonDefaultCredentials() throws Exception {
        // Scoped to the tool's own description first, this passed while the identical
        // claim survived in the response hint of the same class and in two more strings
        // on the conversation surface. A test narrow enough to match only the text that
        // was fixed certifies the fix instead of the claim, so it scans the sources that
        // can carry it. The response hint matters most: it is state, not documentation,
        // and it is served on every call.
        List<Path> sources = List.of(
                Path.of("src/main/java/com/apimarketplace/orchestrator/tools/credential",
                        "CredentialToolsProvider.java"),
                Path.of("../agent-common/src/main/java/com/apimarketplace/agent/prompt",
                        "ConversationToolDefinitions.java"),
                Path.of("../conversation-service/src/main/java/com/apimarketplace/conversation",
                        "service/ai/ConversationToolExecutionService.java"));

        for (Path source : sources) {
            assertThat(source).as("moved or renamed: this test then guards nothing").exists();
            String text = Files.readString(source);
            // Matching the claim rather than three spellings of it: the narrow version
            // of this assertion passed while "only defaults are used for execution"
            // sat as a comment in one of these very files.
            assertThat(ONLY_DEFAULTS_ARE_USED.matcher(text).find())
                    .as("%s still tells the agent the other accounts cannot run", source)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the discovery tool points at credential_selector and states the matching rule")
    void discoveryToolNamesTheField() {
        String text = discoveryToolText();
        assertThat(text).contains("credential_selector");
        // Without the rule an agent may normalise a name the way it normalises a slug,
        // and "Client A" quietly becomes "client-a", which resolves to nothing. The rule
        // has to be stated as the matcher implements it (trim plus case-insensitive):
        // an earlier draft said "verbatim", which contradicts the matcher's own refusal
        // message and would send an agent hunting for a capitalisation it need not fix.
        assertThat(text).contains("capitalisation and surrounding spaces");
    }

    @Test
    @DisplayName("the discovery tool says only an active account is selectable")
    void discoveryToolStatesTheStatusRule() {
        // 'expiring' reads as usable in this very listing and is refused by the
        // matcher, so enumerating needs_reauth and error is not enough.
        assertThat(discoveryToolText()).contains("'active'");
    }

    @Test
    @DisplayName("the documented Returns example is valid JSON and shows the shape the code emits")
    void documentedExampleParsesAndMatchesRuntime() throws Exception {
        // A Java text block INTERPRETS escapes, so a name quoted with a single-backslash
        // \" renders as a bare quote and the sample stops being parseable JSON. Reading
        // the source cannot catch that: it looks correctly escaped there. This parses
        // what an agent is actually shown.
        String help = new CredentialToolsProvider(null).getTools().stream()
                .filter(t -> "get_connected_services".equals(t.name()))
                .map(AgentToolDefinition::helpText)
                .findFirst()
                .orElseThrow();
        String sample = help.substring(help.indexOf('{'), help.lastIndexOf('}') + 1);

        com.fasterxml.jackson.databind.JsonNode parsed = new ObjectMapper().readTree(sample);
        // The Returns line advertises these two, so the sample has to show them or it
        // documents a response shape no call produces.
        assertThat(parsed.has("count")).as("sample omits count").isTrue();
        assertThat(parsed.has("defaultCount")).as("sample omits defaultCount").isTrue();
        assertThat(parsed.path("connected").size()).isEqualTo(parsed.path("count").asInt());

        String documentedHint = parsed.path("hint").asText();

        // The example must show the shape the code produces, or it teaches a response no
        // call returns. Both of these sentences are appended unconditionally.
        assertThat(documentedHint)
                .contains("Executing a tool directly uses the default one unless the call names another with credential_name.")
                .contains("This is what YOUR workspace holds")
                .contains("credential_selector (active only)");
        // And the quoting the offer applies to every name, which is the reason the
        // escaping in the sample is load-bearing rather than cosmetic.
        assertThat(documentedHint).contains("\"Client B\" (instagram)");
    }

    @Test
    @DisplayName("the discovery tool's example shows two accounts sharing one integration")
    void discoveryToolExampleShowsTheMultiAccountShape() {
        // One entry per integration would read as "one account each", which is the shape
        // that makes the field look pointless. The example has to show the collision.
        // Counted on the integration NAME rather than on an exact JSON fragment: rewrapping
        // or reordering keys in the sample must not fail a test about what it shows.
        assertThat(discoveryToolText().split("instagram", -1).length - 1)
                .as("the sample payload must show two instagram accounts")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("the add_mcp prompt documents the field, so it is findable before a node exists")
    void addMcpPromptDocumentsTheField() {
        // describe() only helps once a node carries the field. An agent asked for a
        // per-account workflow reads this prompt instead, and it named no credential
        // field at all.
        String prompt = WorkflowBuilderPrompts.getActionHelp("add_mcp");
        assertThat(prompt).contains("credential_selector");
        assertThat(prompt).contains("get_connected_services");
        // Asserted on a fragment that cannot straddle a line break: the prompt is a text
        // block and wrapping it differently must not fail an unrelated test.
        assertThat(prompt)
                .as("an agent must not reach for this on ordinary steps")
                .contains("for every ordinary step");
    }

    @Test
    @DisplayName("describe tells the agent where names come from and that nothing falls back")
    void describeCarriesTheDiscoveryHint() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "d67b712e-c76e-4bae-a17e-24fe7f73a589");
        node.put("type", "mcp");
        node.put("label", "Publish");
        node.put("credential_selector", "{{item.ig_account}}");

        NodeDescriptionBuilder.ModifiableField field =
                new NodeDescriptionBuilder(null, null)
                        .buildDescription("mcp:publish", node, "tenant-1")
                        .modifiableFields()
                        .get("credential_selector");

        assertThat(field).isNotNull();
        assertThat(field.description()).contains("get_connected_services");
        assertThat(field.description())
                .as("an agent that expects a fallback will not treat an unresolved value as a bug")
                .contains("FAILS");
    }
}
