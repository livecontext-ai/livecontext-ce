package com.apimarketplace.agent.tools.memory;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The help payload is a contract with the model, not prose: each assertion below
 * pins a rule that, if it silently disappeared from the text, would change agent
 * behaviour in a way no other test would catch.
 */
@DisplayName("MemoryHelpModule")
class MemoryHelpModuleTest {

    private static final com.apimarketplace.agent.memory.MemoryLimitsConfig LIMITS =
        new com.apimarketplace.agent.memory.MemoryLimitsConfig();

    private MemoryHelpModule module;

    @BeforeEach
    void setUp() {
        module = new MemoryHelpModule(LIMITS);
    }

    private Map<?, ?> help() {
        ToolExecutionResult result = module.execute("help", Map.of(), "42",
            new com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext(
                "42", Map.of(), Map.of(), Set.of(), null, null, "org", "MEMBER")).orElseThrow();
        assertThat(result.success()).isTrue();
        return (Map<?, ?>) result.data();
    }

    private static String flatten(Object value) {
        return String.valueOf(value);
    }

    @Test
    @DisplayName("answers only the help action, leaving the data actions to the CRUD module")
    void handlesOnlyHelp() {
        assertThat(module.canHandle("help")).isTrue();
        assertThat(module.canHandle("save")).isFalse();
        assertThat(module.getToolDefinitions()).isEmpty();
    }

    @Test
    @DisplayName("states the declarative-not-imperative rule, the one that silently corrupts later conversations")
    void pinsTheDeclarativeRule() {
        String whatNot = flatten(help().get("what_not_to_save"));

        assertThat(whatNot)
            .contains("the user prefers X")
            .contains("always do X")
            .contains("override what the user is asking for");
    }

    @Test
    @DisplayName("separates memory from skills, so a procedure is not stored as a fact")
    void distinguishesMemoryFromSkills() {
        assertThat(flatten(help().get("description")))
            .contains("skills")
            .contains("procedur");
    }

    @Test
    @DisplayName("warns that memory is shared with the workspace, so no secret is stored in it")
    void warnsAboutSharedVisibility() {
        assertThat(flatten(help().get("what_not_to_save")))
            .contains("Secrets")
            .contains("shared with everyone in this workspace");
    }

    @Test
    @DisplayName("explains that a save lands in context only on the next run, so the agent does not save twice")
    void explainsTheFrozenSnapshot() {
        assertThat(flatten(help().get("when_it_takes_effect")))
            .contains("NEXT run")
            .contains("nothing went wrong");
    }

    @Test
    @DisplayName("says the same slug updates rather than duplicates, which is the whole dedup contract")
    void pinsTheUpsertContract() {
        assertThat(flatten(help().get("actions")))
            .contains("Same slug = update, not a duplicate");
    }

    @Test
    @DisplayName("guides selective recall and corrections without duplicating or broadening memories")
    void guidesRecallAndCorrection() {
        assertThat(flatten(help().get("decision_guide")))
            .contains("avoid asking the user to repeat themselves")
            .contains("same slug AND scope")
            .contains("contradictory content")
            .contains("current user's request takes priority")
            .contains("CONTINUE the original task")
            .contains("Check the save result");
    }

    @Test
    @DisplayName("does not mistake agent scope for privacy from people or pinning for instruction priority")
    void statesVisibilityAndPinningLimits() {
        assertThat(flatten(help().get("scope")))
            .contains("workspace members can still manage it")
            .doesNotContain("Nothing else in this workspace sees it");
        assertThat(flatten(help().get("tips")))
            .contains("unpinned by default")
            .contains("never instructions")
            .doesNotContain("rules you must never violate");
    }

    @Test
    @DisplayName("says the index is in context only WHEN there is one, and names the fallback for when there is not")
    void tellsTheAgentWhereItsIndexIsWithoutPromisingIt() {
        String description = flatten(help().get("description"));

        // The heading is conditional at the source: MemoryPromptSection renders
        // nothing for an empty workspace, and an external-CLI session may receive no
        // system prompt at all. Promising it unconditionally here sends the agent
        // looking for a section that is not there, and an agent that believes it has
        // an index does not go and fetch one.
        assertThat(description)
            .as("conditional, matching what the prompt module actually renders")
            .contains("WHEN this workspace has any");
        assertThat(description)
            .as("and the way out when the heading is absent")
            .contains("list(as_index=true)");
        assertThat(description)
            .as("the old wording promised the index on every path")
            .doesNotContain("already in your context");
    }

    @Test
    @DisplayName("explains what switching workspace does to memory, the question the feature exists to answer")
    void explainsWorkspaceSwitching() {
        assertThat(flatten(help().get("scope")))
            .contains("belongs to the workspace it was written in");
    }

    @Test
    @DisplayName("documents every parameter the SCHEMA advertises, so none is discoverable but unexplained")
    void documentsEveryParameter() {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) help().get("parameters");

        // Read off the tool definition rather than a list retyped here. A hand-kept
        // list drifts the moment a parameter is added, which is exactly what left
        // 'offset' in the schema and out of the documentation: an agent could send
        // it, and had nothing telling it what it did.
        List<String> advertised = new MemoryToolsProvider(null, module, LIMITS)
            .getTools().get(0).parameters().stream()
            .map(p -> p.name()).toList();

        assertThat(params.keySet())
            .as("the help must cover every parameter the model can see")
            .containsAll(advertised);
    }

    @Test
    @DisplayName("gives the REAL default and ceiling for limit, which differ between search and list")
    void limitDocumentsBothDefaults() {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) help().get("parameters");
        String limit = params.get("limit").toString();

        // They are genuinely different: search takes its own default of 10 and caps
        // at 50 in the service; list goes through the shared list envelope, whose
        // STANDARD caps are 25 and 50. One number for both was wrong for one of
        // them, and a wrong default is a silently truncated result set.
        assertThat(limit).contains("10").contains("25").contains("50");
    }

    @Test
    @DisplayName("gives a worked call for every action an agent will reach for")
    void examplesCoverTheCommonPaths() {
        String examples = flatten(help().get("examples"));

        assertThat(examples)
            .contains("memory(action='save'")
            .contains("memory(action='get'")
            .contains("memory(action='search'")
            .contains("pinned=true");
    }

    @Test
    @DisplayName("mentions no REST path, table or UI screen, since the agent has only this tool")
    void staysWithinTheAgentsWorld() {
        String all = flatten(help());

        assertThat(all)
            .doesNotContain("/api/")
            .doesNotContain("POST ")
            .doesNotContain("SELECT ")
            .doesNotContain("agent_memories")
            .doesNotContain(".java");
    }

    @Test
    @DisplayName("names every action the tool actually implements, and no action it does not")
    void actionListMatchesTheTool() {
        @SuppressWarnings("unchecked")
        Map<String, Object> actions = (Map<String, Object>) help().get("actions");

        assertThat(actions.keySet())
            .containsExactlyInAnyOrderElementsOf(List.of("save", "get", "list", "search", "delete", "help"));
    }

    @Test
    @DisplayName("quotes the caps that are actually enforced, not a copy of the defaults")
    void quotesTheConfiguredCaps() {
        com.apimarketplace.agent.memory.MemoryLimitsConfig tuned =
            new com.apimarketplace.agent.memory.MemoryLimitsConfig();
        tuned.setMaxSummaryChars(90);
        tuned.setMaxContentChars(1500);
        tuned.setMaxPinnedEntries(2);

        String all = flatten(new MemoryHelpModule(tuned)
            .execute("help", java.util.Map.of(), "t", null).orElseThrow().data());

        // An operator lowering a cap used to leave the help promising the old number:
        // the agent writes to the documented limit, the write is refused with a
        // different one, and nothing tells it which to believe.
        // The SENTENCE, not the digits: "90" and "1500" match almost anything in a
        // help payload, so a version that dropped the interpolation entirely could
        // still pass on an unrelated number.
        assertThat(all).contains("up to 90 characters").contains("up to 1500 characters");
        assertThat(all).doesNotContain("up to 240 characters").doesNotContain("up to 8000 characters");
    }
}
