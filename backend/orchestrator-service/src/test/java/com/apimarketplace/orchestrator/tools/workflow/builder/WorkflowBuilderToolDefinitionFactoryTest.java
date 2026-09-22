package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Pins the LLM-facing workflow tool definition after the 2026-07 action-param
 * compaction (rare-action prose trimmed, publish detail deferred to help).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderToolDefinitionFactory definition")
class WorkflowBuilderToolDefinitionFactoryTest {

    @Mock private NodeLibraryService nodeLibraryService;

    private WorkflowBuilderToolDefinitionFactory factory;

    @BeforeEach
    void setUp() {
        lenient().when(nodeLibraryService.getQuickReference()).thenReturn("quick-ref");
        lenient().when(nodeLibraryService.getAlwaysAvailableHelp()).thenReturn("full-help");
        factory = new WorkflowBuilderToolDefinitionFactory(nodeLibraryService);
    }

    private ToolParameter actionParam() {
        AgentToolDefinition tool = factory.buildToolDefinition();
        return tool.parameters().stream()
                .filter(p -> "action".equals(p.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("action param not found"));
    }

    @Test
    @DisplayName("action list enumerates resolve_approval and continue_interface (previously described but not listed)")
    void actionListIncludesRunControlActions() {
        String d = actionParam().description();
        // Pre-fix, the leading "Action: init, load, ..." enumeration omitted both
        // run-control actions even though their semantics were described below -
        // an agent scanning the list could conclude they don't exist.
        assertThat(d).contains("unpublish, resolve_approval, continue_interface, mock_suggest, help");
    }

    /**
     * The tool definition is the ONLY place a fresh agent learns an action exists: if
     * stop_run or its parameters fall out of the schema, the action becomes unreachable
     * in practice however well the help documents it. The application tool pins the mirror
     * image of these assertions.
     */
    @Test
    @DisplayName("stop_run is enumerated with its own parameters and its two load-bearing warnings")
    void stopRunIsAdvertisedWithItsParameters() {
        AgentToolDefinition tool = factory.buildToolDefinition();
        assertThat(tool.parameters().stream().map(ToolParameter::name))
                .contains("run_id", "reason", "mode");

        String action = actionParam().description();
        assertThat(action)
                .contains("stop_run")
                .contains("counterpart of execute")
                // the two facts an agent cannot discover by trying
                .contains("can omit run_id to stop its own run");

        String mode = tool.parameters().stream()
                .filter(p -> "mode".equals(p.name()))
                .findFirst().orElseThrow().description();
        assertThat(mode)
                .contains("suspends the schedules")
                .contains("PENDING or WAITING_TRIGGER");
    }

    @Test
    @DisplayName("action param keeps the load-bearing one-liners: finish closes the session, pin needs no prior run, both run-control verbs are gated")
    void actionParamKeepsLoadBearingRules() {
        String d = actionParam().description();
        assertThat(d)
                .contains("CLOSES the build session")
                .contains("'create' is a back-compat alias")
                // Pin prepares its own production run (2026-08-25). Stating it here is
                // load-bearing in the negative: the agent cannot discover by trying that a
                // never-executed version is pinnable, and the previous wording sent it off
                // to execute the workflow first - a real fire, with real side effects.
                .contains("does NOT need to have been run first")
                .contains("pin prepares the production run itself")
                .contains("decision='approved'|'rejected'")
                // Both run-control verbs need the user's authorization, and the agent must be
                // told HOW that arrives: the ask happens inside the call, so the response is
                // what settles it. Pinning only the words "authorization card" let the text
                // keep describing the flow that shipped before the call was held open, which
                // is what makes an agent stop and wait for a turn that never comes.
                .contains("need the user's authorization in an interactive chat")
                .contains("executed:false")
                // Per-action, not a substring the three gated verbs happen to share: matching
                // once let restart_from_node's sentence be deleted with the suite still green.
                .contains("executed:false means nothing replayed")
                .contains("executed:false means nothing was resolved or advanced")
                // wait_run (2026-07-03 feature) must survive any future compaction of this param
                .contains("get_run, wait_run, get_node_output")
                .contains("prefer ONE wait_run over a get_run poll loop");
    }

    @Test
    @DisplayName("publish -> help cross-reference resolves: the default help payload carries the application auto-promotion rules")
    void publishHelpCrossRefResolves() {
        // The action param now says publish's "full rules incl. application auto-promotion"
        // live in workflow(action='help'). Guard both ends so the pointer never dangles.
        assertThat(actionParam().description()).contains("auto-promotion in workflow(action='help')");

        WorkflowHelpProvider helpProvider = org.mockito.Mockito.mock(WorkflowHelpProvider.class);
        WorkflowBuilderHelpModule helpModule = new WorkflowBuilderHelpModule(helpProvider);
        var result = helpModule.execute("help", Map.of(), "tenant-1", ToolExecutionContext.of("tenant-1"))
                .orElseThrow(() -> new AssertionError("help module returned empty"));
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        @SuppressWarnings("unchecked")
        Map<String, Object> actions = (Map<String, Object>) data.get("actions");
        @SuppressWarnings("unchecked")
        Map<String, String> marketplace = (Map<String, String>) actions.get("marketplace");
        assertThat(marketplace.get("publish"))
                .as("the help target of the publish cross-reference")
                .contains("APPLICATION AUTO-PROMOTION");
    }

    @Test
    @DisplayName("tool description and helpText come from NodeLibraryService (quick reference / always-available help)")
    void descriptionAndHelpTextWiring() {
        AgentToolDefinition tool = factory.buildToolDefinition();
        assertThat(tool.description()).isEqualTo("quick-ref");
        assertThat(tool.helpText()).isEqualTo("full-help");
    }

    // ==================== nodePolicy ====================

    private ToolParameter param(String name) {
        return factory.buildToolDefinition().parameters().stream()
                .filter(p -> name.equals(p.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " param not found"));
    }

    @Test
    @DisplayName("nodePolicy is in the SCHEMA, not only in the help: an agent cannot pass an "
            + "argument the tool does not declare")
    void nodePolicyIsDeclaredInTheSchema() {
        // This project has shipped an action whose required argument lived only in its help text.
        // The agent could read about it and had no way to send it, and the feature was green and
        // dead. The schema is the only place a fresh agent learns a parameter exists.
        ToolParameter policy = param("nodePolicy");

        assertThat(policy.type()).isEqualTo("object");
        assertThat(policy.required()).isFalse();
    }

    @Test
    @DisplayName("its description says it belongs to BOTH add_node and modify")
    void nodePolicyIsAdvertisedForBothActions() {
        // Setting it on creation is what saves a second call for every node that needs it, so an
        // agent that reads "(for: modify)" would do twice the work for no reason.
        assertThat(param("nodePolicy").description())
                .contains("(for: add_node, modify)");
    }

    @Test
    @DisplayName("it says the policy goes OUTSIDE params, the mistake a model makes by default")
    void nodePolicySaysItIsOutsideParams() {
        assertThat(param("nodePolicy").description()).contains("OUTSIDE params");
    }

    @Test
    @DisplayName("it names EVERY field the record actually has, so a seventh one cannot ship "
            + "undocumented")
    void nodePolicyNamesEveryFieldAndDefault() {
        // Derived from the record, not a hand-written list. A literal list is the trap this repo
        // keeps meeting: it certifies the author's idea of the fields, so adding a component to
        // NodePolicy leaves the agent with a knob it can never learn about while this stays green.
        String d = param("nodePolicy").description();

        for (var component : com.apimarketplace.orchestrator.domain.workflow.NodePolicy.class
                .getRecordComponents()) {
            assertThat(d)
                    .as("nodePolicy.%s is a real field and must be described to the agent",
                            component.getName())
                    .contains(component.getName());
        }
        assertThat(d).as("and its default, so no trial call is needed to find it").contains("default 0");
    }

    @Test
    @DisplayName("it states that the two retry layers MULTIPLY, which is the one thing an author "
            + "cannot deduce")
    void nodePolicyStatesTheMultiplication() {
        String d = param("nodePolicy").description();

        assertThat(d).contains("MULTIPLY");
        assertThat(d)
                .as("and that it is handled automatically when the node itself retries")
                .contains("retryCount > 0 implies it");
    }

    @Test
    @DisplayName("it says how to remove a policy and where the full guide is")
    void nodePolicySaysHowToClearAndWhereToRead() {
        String d = param("nodePolicy").description();

        assertThat(d).contains("nodePolicy={} to REMOVE");
        assertThat(d).contains("topics=['node_policy']");
    }
}
