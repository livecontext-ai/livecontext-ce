package com.apimarketplace.conversation.service.ai.callback;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.AvailableModel;
import com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry;
import com.apimarketplace.conversation.service.ai.schema.ModelTierMapper;
import com.apimarketplace.conversation.service.ai.schema.SchemaMode;
import com.apimarketplace.conversation.service.ai.schema.SchemaSlimExclusionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Stage 4a wiring - pin the {@link AgentContextBuilder#applyJitSchemaSlim}
 * helper so the end-to-end cost fix doesn't regress.
 *
 * <p>Behaviors locked in (one test per bullet, plus the help-seen / repository
 * variants):
 * <ul>
 *   <li><b>SLIM mode</b> - top/high/mid tier models get every non-excluded
 *   tool replaced by its slim copy (full parameter schema stripped).</li>
 *   <li><b>FULL mode</b> - budget/unknown tiers ship the tools untouched.</li>
 *   <li><b>Always-full exclusion</b> - tools pinned by
 *   {@link SchemaSlimExclusionPolicy#isToolAlwaysFull} survive SLIM intact,
 *   even when the rest of the prefix is slimmed.</li>
 *   <li><b>Unknown model</b> - an RPC miss against the platform catalog
 *   still resolves to FULL via the {@link ModelTierMapper} default and does
 *   not NPE.</li>
 *   <li><b>Mis-mapped provider</b> - strict {@code (provider, modelId)} miss
 *   falls back to a modelId-only catalog lookup so the tier still resolves
 *   instead of degrading to the unknown-model default.</li>
 *   <li><b>Help-seen promotion</b> - a fresh help entry pulls a SLIM-tier
 *   tool back to FULL for the duration of the configured window.</li>
 * </ul>
 *
 * <p>The helper is package-private on purpose: this test drives the actual
 * cost-reduction path without spinning up the full {@code build()} pipeline.
 */
@DisplayName("AgentContextBuilder.applyJitSchemaSlim - Stage 4a wiring")
@ExtendWith(MockitoExtension.class)
class AgentContextBuilderJitSchemaSlimTest {

    @Mock private AgentConfigProvider agentConfigProvider;
    @Mock private SchemaSlimExclusionPolicy schemaSlimExclusionPolicy;
    @Mock private HelpSeenRegistry helpSeenRegistry;
    @Mock private MessageRepository messageRepository;

    /**
     * Real {@link ModelTierMapper} backed by real {@link
     * com.apimarketplace.conversation.service.ai.schema.JitSchemaProperties}
     * defaults (top/high/mid → SLIM, budget → FULL, unknown → FULL). We
     * want to exercise the actual policy, not a mock of it.
     */
    private final ModelTierMapper modelTierMapper = new ModelTierMapper(
            new com.apimarketplace.conversation.service.ai.schema.JitSchemaProperties());

    private AgentContextBuilder newBuilder() {
        return new AgentContextBuilder(
                null, null, null, agentConfigProvider, null, null, null, null,
                modelTierMapper, schemaSlimExclusionPolicy,
                helpSeenRegistry, messageRepository);
    }

    private ToolDefinition tool(String name) {
        return ToolDefinition.builder()
                .name(name)
                .description("Full description for " + name + " - this will be stripped in SLIM mode.")
                .parameters(List.of(
                        ToolParameter.builder().name("action").type("string").build(),
                        ToolParameter.builder().name("payload").type("object")
                                .description("structured input with many subfields").build()
                ))
                .build();
    }

    @Test
    @DisplayName("SLIM tier (top/high/mid) rewrites every non-excluded tool to names-only form")
    void slimsToolsForHighTierModel() {
        when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                new AvailableModel("google", "gemini-3-pro-preview", "top", 1)
        ));
        lenient().when(schemaSlimExclusionPolicy.isToolAlwaysFull(any())).thenReturn(false);

        List<ToolDefinition> out = newBuilder().applyJitSchemaSlim(
                List.of(tool("agent"), tool("workflow"), tool("catalog")),
                "google", "gemini-3-pro-preview", null);

        assertThat(out).hasSize(3);
        assertThat(out).allSatisfy(t -> {
            assertThat(t.description()).startsWith("Call `" + t.name() + "(action='help'");
            assertThat(t.parameters()).noneMatch(p -> p.description() != null && !p.description().isBlank());
        });
    }

    @Test
    @DisplayName("FULL tier (budget) passes tools through untouched so tool accuracy never regresses")
    void preservesToolsForBudgetTierModel() {
        when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                new AvailableModel("openrouter", "glm-4.5-air", "budget", 99)
        ));

        List<ToolDefinition> input = List.of(tool("agent"), tool("workflow"));
        List<ToolDefinition> out = newBuilder().applyJitSchemaSlim(
                input, "openrouter", "glm-4.5-air", null);

        assertThat(out).isSameAs(input);
    }

    @Test
    @DisplayName("Always-full tool survives SLIM intact while neighbours are slimmed")
    void pinsAlwaysFullToolsInSlimMode() {
        when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                new AvailableModel("anthropic", "claude-opus-4-7", "top", 1)
        ));
        when(schemaSlimExclusionPolicy.isToolAlwaysFull("request_credential")).thenReturn(true);
        when(schemaSlimExclusionPolicy.isToolAlwaysFull("workflow")).thenReturn(false);

        ToolDefinition credential = tool("request_credential");
        ToolDefinition workflow = tool("workflow");
        List<ToolDefinition> out = newBuilder().applyJitSchemaSlim(
                List.of(credential, workflow), "anthropic", "claude-opus-4-7", null);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isSameAs(credential);
        assertThat(out.get(1).description()).startsWith("Call `workflow(action='help'");
    }

    @Test
    @DisplayName("Unknown model falls back to FULL via ModelTierMapper default (no NPE on catalog miss)")
    void unknownModelFallsBackToFullWithoutThrowing() {
        when(agentConfigProvider.getAvailableModels()).thenReturn(List.of());

        List<ToolDefinition> input = List.of(tool("agent"));
        List<ToolDefinition> out = newBuilder().applyJitSchemaSlim(
                input, "mystery-provider", "never-seen-model", null);

        assertThat(out).isSameAs(input);
    }

    @Test
    @DisplayName("Empty tools list short-circuits before the mapper is consulted")
    void emptyToolsShortCircuits() {
        List<ToolDefinition> out = newBuilder().applyJitSchemaSlim(List.of(), "any", "any", null);
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("Mixed-case request modelId canonicalises to catalog form so ModelTierMapper override map still hits")
    void canonicalisesModelIdForOverrideMapLookup() {
        // glm-5-turbo is the canonical id registered in the catalog. The
        // request arrives with mixed-case. Without canonicalisation the
        // override map (keyed case-sensitively on the canonical form) would
        // miss and the catalog's "high" tier would incorrectly route to SLIM
        // for this weak-at-tool-use model.
        when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                new AvailableModel("openrouter", "glm-5-turbo", "high", 10)
        ));

        com.apimarketplace.conversation.service.ai.schema.JitSchemaProperties props =
                new com.apimarketplace.conversation.service.ai.schema.JitSchemaProperties();
        props.getModelOverrides().put("glm-5-turbo", SchemaMode.FULL);
        ModelTierMapper mapperWithOverride = new ModelTierMapper(props);

        AgentContextBuilder builder = new AgentContextBuilder(
                null, null, null, agentConfigProvider, null, null, null, null,
                mapperWithOverride, schemaSlimExclusionPolicy,
                helpSeenRegistry, messageRepository);

        List<ToolDefinition> input = List.of(tool("agent"));
        List<ToolDefinition> out = builder.applyJitSchemaSlim(
                input, "OpenRouter", "GLM-5-Turbo", null);

        assertThat(out).isSameAs(input);
    }

    @Test
    @DisplayName("Mis-mapped provider still resolves to the correct tier via modelId-only fallback")
    void misMappedProviderFallsBackToModelIdOnlyLookup() {
        // Client passes provider="google" for a bridge-only model that is
        // actually registered under "gemini-cli". Strict (provider, modelId)
        // lookup misses; the modelId-only fallback must still find the top
        // tier entry so the tools are slimmed instead of leaking full schemas.
        AvailableModel bridgeEntry = new AvailableModel("gemini-cli", "gemini-2.5-flash", "top", 5);
        when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(bridgeEntry));
        when(agentConfigProvider.findAvailableModelByModelId("gemini-2.5-flash"))
                .thenReturn(bridgeEntry);
        lenient().when(schemaSlimExclusionPolicy.isToolAlwaysFull(any())).thenReturn(false);

        List<ToolDefinition> out = newBuilder().applyJitSchemaSlim(
                List.of(tool("agent")), "google", "gemini-2.5-flash", null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).description()).startsWith("Call `agent(action='help'");
    }

    @Test
    @DisplayName("Catalog entry with null tier falls through to unknown-model default (FULL)")
    void nullTierInCatalogFallsBackToUnknownDefault() {
        when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                new AvailableModel("custom", "orphan-model", null, 50)
        ));

        List<ToolDefinition> input = List.of(tool("agent"));
        List<ToolDefinition> out = newBuilder().applyJitSchemaSlim(
                input, "custom", "orphan-model", null);

        assertThat(out).isSameAs(input);
    }

    @Test
    @DisplayName("SLIM is applied even when tools list has been pre-filtered (e.g. TASK_REVIEW agent-only)")
    void slimsPreFilteredToolsList() {
        when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                new AvailableModel("anthropic", "claude-opus-4-7", "top", 1)
        ));
        lenient().when(schemaSlimExclusionPolicy.isToolAlwaysFull(any())).thenReturn(false);

        List<ToolDefinition> preFiltered = List.of(tool("agent"));
        List<ToolDefinition> out = newBuilder().applyJitSchemaSlim(
                preFiltered, "anthropic", "claude-opus-4-7", null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).description()).startsWith("Call `agent(action='help'");
    }

    @Test
    @DisplayName("Fresh help-seen entry promotes a SLIM-tier tool back to FULL for this turn")
    void helpSeenPromotesToolToFullInSlimMode() {
        // Top-tier model → SLIM by default.
        when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                new AvailableModel("anthropic", "claude-opus-4-7", "top", 1)
        ));
        lenient().when(schemaSlimExclusionPolicy.isToolAlwaysFull(any())).thenReturn(false);

        String conversationId = "conv-1";
        // USER message count drives currentTurn - return 3 turns.
        when(messageRepository.countByConversationIdAndRole(
                eq(conversationId), eq(Message.MessageRole.USER))).thenReturn(3L);

        // Only "workflow" has a fresh help entry - "agent" does not.
        when(helpSeenRegistry.isFresh(eq(conversationId), eq("workflow"), eq("help"), anyInt()))
                .thenReturn(true);
        when(helpSeenRegistry.isFresh(eq(conversationId), eq("agent"), eq("help"), anyInt()))
                .thenReturn(false);

        ToolDefinition agent = tool("agent");
        ToolDefinition workflow = tool("workflow");
        List<ToolDefinition> out = newBuilder().applyJitSchemaSlim(
                List.of(agent, workflow), "anthropic", "claude-opus-4-7", conversationId);

        assertThat(out).hasSize(2);
        // agent was slimmed (no fresh help), workflow was promoted back to FULL.
        assertThat(out.get(0).description()).startsWith("Call `agent(action='help'");
        assertThat(out.get(1)).isSameAs(workflow);
    }

    @Test
    @DisplayName("Null conversationId skips the help-seen lookup and falls through to slim")
    void nullConversationIdSkipsHelpSeenLookup() {
        when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                new AvailableModel("anthropic", "claude-opus-4-7", "top", 1)
        ));
        lenient().when(schemaSlimExclusionPolicy.isToolAlwaysFull(any())).thenReturn(false);

        List<ToolDefinition> out = newBuilder().applyJitSchemaSlim(
                List.of(tool("agent")), "anthropic", "claude-opus-4-7", null);

        // Tool is slimmed - registry was never consulted (Mockito strict-stubs would
        // flag any unexpected interaction; no when() stubs were set on helpSeenRegistry).
        assertThat(out).hasSize(1);
        assertThat(out.get(0).description()).startsWith("Call `agent(action='help'");
    }

    @Test
    @DisplayName("MessageRepository failure is swallowed and slim still applies safely")
    void messageRepoFailureFallsBackToSlim() {
        when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                new AvailableModel("anthropic", "claude-opus-4-7", "top", 1)
        ));
        lenient().when(schemaSlimExclusionPolicy.isToolAlwaysFull(any())).thenReturn(false);

        String conversationId = "conv-err";
        when(messageRepository.countByConversationIdAndRole(
                eq(conversationId), eq(Message.MessageRole.USER)))
                .thenThrow(new RuntimeException("db down"));
        // turn resolves to 0 on failure - registry still consulted with turn=0, returns false.
        when(helpSeenRegistry.isFresh(eq(conversationId), eq("agent"), eq("help"), anyInt()))
                .thenReturn(false);

        List<ToolDefinition> out = newBuilder().applyJitSchemaSlim(
                List.of(tool("agent")), "anthropic", "claude-opus-4-7", conversationId);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).description()).startsWith("Call `agent(action='help'");
    }
}
