package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.SmartDefaultsEngine;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests the "chat" branch of TriggerCreator, covering the chatMatch round-trip fix.
 *
 * Chat triggers carry an optional {@code chatMatch} block on the TRIGGER NODE TOP-LEVEL
 * (not inside step.params) to match the frontend exporter shape in
 * {@code triggerProcessor.buildChatMatchConfig}. Before this fix the agent-written
 * chatMatch was silently discarded - {@link TriggerCreator#addTypeSpecificInput} had
 * no {@code chat} branch, so the agent's config was dropped between add_trigger and
 * {@code workflow_versions.plan}, causing every agent-authored chat trigger to
 * fire on any message regardless of the requested match rule.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerCreator - Chat trigger chatMatch round-trip")
class TriggerCreatorChatTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private SmartDefaultsEngine smartDefaultsEngine;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private TriggerClient triggerClient;

    private TriggerCreator creator;

    @BeforeEach
    void setUp() {
        creator = new TriggerCreator(sessionStore, dataSourceClient, smartDefaultsEngine, responseOptimizer, triggerClient);
        lenient().when(smartDefaultsEngine.applyTriggerDefaults(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(responseOptimizer.buildTriggerResponse(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LinkedHashMap<>());
        // createChatEndpoint returning null is tolerated by autoCreateStandaloneChatEndpoint
        // (it logs a warning and moves on). We do not want to pull in the DTO here.
        lenient().when(triggerClient.createChatEndpoint(anyString(), any(), any())).thenReturn(null);
    }

    private WorkflowBuilderSession createSession() {
        return WorkflowBuilderSession.create("tenant-1", "conv-1", "Test Workflow", null);
    }

    private Map<String, Object> chatParams(String label, Map<String, Object> chatMatch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", label);
        p.put("trigger_type", "chat");
        if (chatMatch != null) {
            p.put("chatMatch", chatMatch);
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getLastTrigger(WorkflowBuilderSession session) {
        List<Map<String, Object>> triggers = session.getTriggers();
        return triggers.get(triggers.size() - 1);
    }

    @Test
    @DisplayName("chatMatch absent: trigger node stores no chatMatch field - domain defaults to ANY")
    void shouldOmitChatMatchWhenNotProvided() {
        WorkflowBuilderSession session = createSession();
        ToolExecutionResult result = creator.executeAddTrigger(session, chatParams("Help Chat", null), "tenant-1");

        assertThat(result.success()).isTrue();
        Map<String, Object> trigger = getLastTrigger(session);
        assertThat(trigger).doesNotContainKey("chatMatch");
    }

    @Test
    @DisplayName("chatMatch with backend snake_case type persisted verbatim at trigger top-level")
    void shouldPersistSnakeCaseChatMatchAtTopLevel() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> chatMatch = new LinkedHashMap<>();
        chatMatch.put("type", "starts_with");
        chatMatch.put("value", "/help");
        chatMatch.put("caseSensitive", false);

        ToolExecutionResult result = creator.executeAddTrigger(session, chatParams("Command Chat", chatMatch), "tenant-1");

        assertThat(result.success()).isTrue();
        Map<String, Object> trigger = getLastTrigger(session);
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) trigger.get("chatMatch");
        assertThat(stored).isNotNull();
        assertThat(stored.get("type")).isEqualTo("starts_with");
        assertThat(stored.get("value")).isEqualTo("/help");
        assertThat(stored.get("caseSensitive")).isEqualTo(false);
    }

    @Test
    @DisplayName("chatMatch with frontend-alias 'startsWith' is accepted and preserved")
    void shouldAcceptFrontendCamelCaseAlias() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> chatMatch = new LinkedHashMap<>();
        chatMatch.put("type", "startsWith");
        chatMatch.put("value", "/run");

        ToolExecutionResult result = creator.executeAddTrigger(session, chatParams("Alias Chat", chatMatch), "tenant-1");

        assertThat(result.success()).isTrue();
        Map<String, Object> trigger = getLastTrigger(session);
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) trigger.get("chatMatch");
        assertThat(stored).isNotNull();
        // Pass-through - ChatMatchConfig.fromMap normalizes the alias at parse time.
        assertThat(stored.get("type")).isEqualTo("startsWith");
        assertThat(stored.get("value")).isEqualTo("/run");
    }

    @Test
    @DisplayName("chatMatch with type='any' omits value - still persisted as-is")
    void shouldAcceptAnyTypeWithoutValue() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> chatMatch = new LinkedHashMap<>();
        chatMatch.put("type", "any");

        ToolExecutionResult result = creator.executeAddTrigger(session, chatParams("Open Chat", chatMatch), "tenant-1");

        assertThat(result.success()).isTrue();
        Map<String, Object> trigger = getLastTrigger(session);
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) trigger.get("chatMatch");
        assertThat(stored).isNotNull();
        assertThat(stored.get("type")).isEqualTo("any");
    }

    @Test
    @DisplayName("Non-map chatMatch fails fast with actionable error")
    void shouldFailWhenChatMatchIsNotMap() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("label", "Broken Chat");
        params.put("trigger_type", "chat");
        params.put("chatMatch", "starts_with");

        ToolExecutionResult result = creator.executeAddTrigger(session, params, "tenant-1");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("chatMatch must be an object");
        // Side-effect: no trigger should have been added.
        assertThat(session.getTriggers()).isEmpty();
    }

    @Test
    @DisplayName("Unknown chatMatch.type rejected with list of allowed values")
    void shouldRejectUnknownType() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> chatMatch = new LinkedHashMap<>();
        chatMatch.put("type", "fuzzymatch");
        chatMatch.put("value", "hi");

        ToolExecutionResult result = creator.executeAddTrigger(session, chatParams("Fuzzy Chat", chatMatch), "tenant-1");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid chatMatch.type");
        assertThat(result.error()).contains("starts_with");
    }

    @Test
    @DisplayName("Non-any type without value rejected (value is required except for 'any')")
    void shouldRejectNonAnyTypeWithoutValue() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> chatMatch = new LinkedHashMap<>();
        chatMatch.put("type", "contains");

        ToolExecutionResult result = creator.executeAddTrigger(session, chatParams("No-Value Chat", chatMatch), "tenant-1");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("value is required");
    }

    @Test
    @DisplayName("Invalid regex pattern in chatMatch.value rejected before build")
    void shouldRejectInvalidRegex() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> chatMatch = new LinkedHashMap<>();
        chatMatch.put("type", "regex");
        chatMatch.put("value", "[unclosed");

        ToolExecutionResult result = creator.executeAddTrigger(session, chatParams("Bad Regex Chat", chatMatch), "tenant-1");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid regex pattern");
    }

    @Test
    @DisplayName("chatMatch lives at top-level of node, NOT inside step.params")
    void chatMatchStaysOutOfParams() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> chatMatch = new LinkedHashMap<>();
        chatMatch.put("type", "equals");
        chatMatch.put("value", "ping");

        ToolExecutionResult result = creator.executeAddTrigger(session, chatParams("Equals Chat", chatMatch), "tenant-1");

        assertThat(result.success()).isTrue();
        Map<String, Object> trigger = getLastTrigger(session);
        assertThat(trigger).containsKey("chatMatch");
        Object paramsObj = trigger.get("params");
        if (paramsObj instanceof Map<?, ?> params) {
            assertThat(params.containsKey("chatMatch")).isFalse();
        }
    }
}
