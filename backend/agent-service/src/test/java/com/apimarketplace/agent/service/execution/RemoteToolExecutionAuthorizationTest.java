package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the tool-authorization gate decision in isolation (no downstream
 * HTTP / local execution) via the package-private {@code checkToolAuthorization}.
 */
@DisplayName("RemoteToolExecutionService - tool authorization gate")
class RemoteToolExecutionAuthorizationTest {

    private RemoteToolExecutionService service;

    @BeforeEach
    void setUp() {
        service = new RemoteToolExecutionService(new ObjectMapper());
    }

    private static Map<String, Object> chatCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "stream-1");
        creds.put("__agent_depth__", 0);
        return creds;
    }

    @Test
    @DisplayName("Sensitive action in interactive chat (not yet approved) yields a pause result")
    void sensitiveActionInChatYieldsPause() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-1", "application", Map.of("action", "acquire"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.metadata()).containsEntry("toolAuthorizationRequired", true);
        assertThat(result.metadata()).containsEntry("rule", "application:acquire");
        assertThat(result.metadata()).containsEntry("toolCallId", "call-1");
    }

    @Test
    @DisplayName("agent:execute is gated before the sub-agent interception path")
    void agentExecuteIsGated() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-2", "agent", Map.of("action", "execute"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("rule", "agent:execute");
    }

    @Test
    @DisplayName("Non-sensitive action proceeds (no gate)")
    void nonSensitiveActionProceeds() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-3", "files", Map.of("action", "list"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Already-authorized rule proceeds (transient resume or persisted 'always authorize')")
    void alreadyAuthorizedProceeds() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__approvedToolActions__", List.of("application:acquire"));

        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-4", "application", Map.of("action", "acquire"), null),
                creds, System.currentTimeMillis());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Sensitive action in an exempt (workflow) context proceeds without a card")
    void sensitiveActionInExemptContextProceeds() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__workflowRunId__", "run-1");

        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-5", "catalog", Map.of("action", "execute"), null),
                creds, System.currentTimeMillis());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("workflow:execute is gated - regression: running a workflow by id (workflow tool) was ungated and ran with no card")
    void workflowExecuteIsGated() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-wf", "workflow", Map.of("action", "execute", "id", "wf-1"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("toolAuthorizationRequired", true);
        assertThat(result.metadata()).containsEntry("rule", "workflow:execute");
    }

    @Test
    @DisplayName("application:acquire pause surfaces the publication id (application_id) for the install modal")
    void acquirePauseCarriesApplicationId() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-6", "application",
                        Map.of("action", "acquire", "application_id", "pub-123"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("rule", "application:acquire");
        assertThat(result.metadata()).containsEntry("applicationId", "pub-123");
    }

    @Test
    @DisplayName("application:execute pause carries no applicationId (install modal is acquire-only)")
    void executePauseHasNoApplicationId() {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-7", "application",
                        Map.of("action", "execute", "application_id", "pub-123"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.metadata()).containsEntry("rule", "application:execute");
        assertThat(result.metadata()).doesNotContainKey("applicationId");
    }

    @Test
    @DisplayName("Gate content carries executed=false and forbids inventing an outcome (workflow:execute)")
    void gateContentMarksNotExecuted() throws Exception {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-ex", "workflow", Map.of("action", "execute", "id", "wf-1"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        Map<String, Object> content = new ObjectMapper().readValue(result.content(), Map.class);
        assertThat(content).containsEntry("status", "authorization_required");
        assertThat(content).containsEntry("executed", false);
        assertThat(String.valueOf(content.get("message")))
                .contains("has NOT run")
                .contains("NO result exists");
    }

    @Test
    @DisplayName("application:acquire gate message frames install as a user-driven, out-of-band step")
    void acquireGateMessageIsUserDrivenInstall() throws Exception {
        ToolResult result = service.checkToolAuthorization(
                new ToolCall("call-acq", "application",
                        Map.of("action", "acquire", "application_id", "pub-123"), null),
                chatCredentials(), System.currentTimeMillis());

        assertThat(result).isNotNull();
        Map<String, Object> content = new ObjectMapper().readValue(result.content(), Map.class);
        assertThat(content).containsEntry("executed", false);
        assertThat(String.valueOf(content.get("message")))
                .contains("NOT been installed")
                .contains("USER");
    }

    @Test
    @DisplayName("Wildcard '*' grant (chatConfig.autoAuthorizeTools) bypasses any sensitive rule")
    void wildcardGrantBypassesEveryRule() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__approvedToolActions__", List.of("*"));

        assertThat(service.checkToolAuthorization(
                new ToolCall("call-8", "application", Map.of("action", "acquire"), null),
                creds, System.currentTimeMillis())).isNull();
        assertThat(service.checkToolAuthorization(
                new ToolCall("call-9", "catalog", Map.of("action", "execute"), null),
                creds, System.currentTimeMillis())).isNull();
    }

    @Test
    @DisplayName("A specific grant bypasses only its own rule, not siblings")
    void specificGrantDoesNotBypassOtherRules() {
        Map<String, Object> creds = chatCredentials();
        creds.put("__approvedToolActions__", List.of("application:acquire"));

        // The granted rule proceeds…
        assertThat(service.checkToolAuthorization(
                new ToolCall("call-10", "application", Map.of("action", "acquire"), null),
                creds, System.currentTimeMillis())).isNull();
        // …but a different sensitive rule still pauses.
        ToolResult other = service.checkToolAuthorization(
                new ToolCall("call-11", "agent", Map.of("action", "execute"), null),
                creds, System.currentTimeMillis());
        assertThat(other).isNotNull();
        assertThat(other.metadata()).containsEntry("rule", "agent:execute");
    }
}
