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

    @Test
    @DisplayName("action param keeps the load-bearing one-liners: finish closes the session, pin needs a successful run, both run-control verbs are gated")
    void actionParamKeepsLoadBearingRules() {
        String d = actionParam().description();
        assertThat(d)
                .contains("CLOSES the build session")
                .contains("'create' is a back-compat alias")
                .contains("the version needs a successful run")
                .contains("decision='approved'|'rejected'")
                .contains("gated by the chat authorization card")
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
}
