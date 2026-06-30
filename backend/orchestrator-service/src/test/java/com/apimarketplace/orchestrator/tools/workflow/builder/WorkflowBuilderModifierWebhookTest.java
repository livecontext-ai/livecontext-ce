package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowBuilderModifier webhook param harmonization.
 *
 * Verifies that:
 * - Webhook params (httpMethod, authType, etc.) are merged into trigger.params
 * - Parameter aliases are normalized (snake_case, short names)
 * - httpMethod is uppercased, authType is lowercased
 * - Existing params are preserved during merge
 * - Undo correctly restores previous params
 * - Non-webhook triggers pass params through unchanged
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - Webhook Harmonization")
class WorkflowBuilderModifierWebhookTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderModifier modifier;

    @BeforeEach
    void setUp() {
        modifier = new WorkflowBuilderModifier(sessionStore);
    }

    private WorkflowBuilderSession createSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private void addWebhookTrigger(WorkflowBuilderSession session, String label,
                                    String httpMethod, String authType) {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", label);
        String nodeId = "trigger:" + WorkflowBuilderSession.normalizeLabel(label);
        trigger.put("id", nodeId);
        trigger.put("type", "webhook");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("httpMethod", httpMethod);
        params.put("authType", authType);
        trigger.put("params", params);

        session.getTriggers().add(trigger);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getTriggerParams(WorkflowBuilderSession session, String label) {
        String nodeId = "trigger:" + WorkflowBuilderSession.normalizeLabel(label);
        for (Map<String, Object> trigger : session.getTriggers()) {
            if (nodeId.equals(trigger.get("id"))) {
                return (Map<String, Object>) trigger.get("params");
            }
        }
        return null;
    }

    // ==================== Basic Webhook Modify ====================

    @Nested
    @DisplayName("Basic webhook param modification")
    class BasicModify {

        @Test
        @DisplayName("Should modify httpMethod")
        void shouldModifyHttpMethod() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of("httpMethod", "GET"));

            ToolExecutionResult result = modifier.executeModifyNode(session, modifyParams);
            assertThat(result.success()).isTrue();

            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params).isNotNull();
            assertThat(params.get("httpMethod")).isEqualTo("GET");
        }

        @Test
        @DisplayName("Should modify authType")
        void shouldModifyAuthType() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of("authType", "header"));

            ToolExecutionResult result = modifier.executeModifyNode(session, modifyParams);
            assertThat(result.success()).isTrue();

            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params.get("authType")).isEqualTo("header");
        }

        @Test
        @DisplayName("Should modify multiple webhook params at once")
        void shouldModifyMultipleParams() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of(
                    "httpMethod", "PUT",
                    "authType", "header",
                    "authHeaderName", "X-Key",
                    "authHeaderValue", "secret"
            ));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params.get("httpMethod")).isEqualTo("PUT");
            assertThat(params.get("authType")).isEqualTo("header");
            assertThat(params.get("authHeaderName")).isEqualTo("X-Key");
            assertThat(params.get("authHeaderValue")).isEqualTo("secret");
        }
    }

    // ==================== Param Normalization ====================

    @Nested
    @DisplayName("Param key normalization")
    class ParamNormalization {

        @Test
        @DisplayName("Should normalize http_method to httpMethod")
        void shouldNormalizeHttpMethodSnakeCase() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of("http_method", "DELETE"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params.get("httpMethod")).isEqualTo("DELETE");
        }

        @Test
        @DisplayName("Should normalize method alias to httpMethod")
        void shouldNormalizeMethodAlias() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of("method", "PATCH"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params.get("httpMethod")).isEqualTo("PATCH");
        }

        @Test
        @DisplayName("Should normalize auth_type to authType")
        void shouldNormalizeAuthTypeSnakeCase() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of("auth_type", "jwt"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params.get("authType")).isEqualTo("jwt");
        }

        @Test
        @DisplayName("Should uppercase httpMethod during modify")
        void shouldUppercaseHttpMethod() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of("httpMethod", "get"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params.get("httpMethod")).isEqualTo("GET");
        }

        @Test
        @DisplayName("Should lowercase authType during modify")
        void shouldLowercaseAuthType() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of("authType", "BASIC"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params.get("authType")).isEqualTo("basic");
        }

        @Test
        @DisplayName("Should normalize basic_username to basicUsername")
        void shouldNormalizeBasicUsernameSnakeCase() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "basic");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of("basic_username", "admin"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params.get("basicUsername")).isEqualTo("admin");
        }

        @Test
        @DisplayName("Should normalize username shorthand to basicUsername")
        void shouldNormalizeUsernameShorthand() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "basic");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of("username", "user1"));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params.get("basicUsername")).isEqualTo("user1");
        }
    }

    // ==================== Merge Behavior ====================

    @Nested
    @DisplayName("Param merge behavior")
    class MergeBehavior {

        @Test
        @DisplayName("Should preserve existing params when modifying one")
        void shouldPreserveExistingParams() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            // Modify only httpMethod
            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of("httpMethod", "GET"));

            modifier.executeModifyNode(session, modifyParams);

            // authType should still be preserved
            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params.get("httpMethod")).isEqualTo("GET");
            assertThat(params.get("authType")).isEqualTo("none");
        }

        @Test
        @DisplayName("Should add new auth params to existing params map")
        void shouldAddNewAuthParams() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of(
                    "authType", "header",
                    "authHeaderName", "X-API-Key",
                    "authHeaderValue", "my-secret"
            ));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> params = getTriggerParams(session, "My Hook");
            assertThat(params.get("httpMethod")).isEqualTo("POST"); // preserved
            assertThat(params.get("authType")).isEqualTo("header"); // updated
            assertThat(params.get("authHeaderName")).isEqualTo("X-API-Key"); // added
            assertThat(params.get("authHeaderValue")).isEqualTo("my-secret"); // added
        }
    }

    // ==================== Undo Support ====================

    @Nested
    @DisplayName("Undo support for webhook params")
    class UndoSupport {

        @Test
        @DisplayName("Should undo httpMethod change")
        void shouldUndoHttpMethodChange() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            // Modify
            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of("httpMethod", "GET"));
            modifier.executeModifyNode(session, modifyParams);

            assertThat(getTriggerParams(session, "My Hook").get("httpMethod")).isEqualTo("GET");

            // Undo
            ToolExecutionResult undoResult = modifier.executeUndo(session);
            assertThat(undoResult.success()).isTrue();

            assertThat(getTriggerParams(session, "My Hook").get("httpMethod")).isEqualTo("POST");
        }

        @Test
        @DisplayName("Should undo auth type change with credentials")
        void shouldUndoAuthTypeChangeWithCredentials() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            // Modify: add header auth
            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of(
                    "authType", "header",
                    "authHeaderName", "X-Key",
                    "authHeaderValue", "secret"
            ));
            modifier.executeModifyNode(session, modifyParams);

            // Verify auth was added
            Map<String, Object> paramsAfterModify = getTriggerParams(session, "My Hook");
            assertThat(paramsAfterModify.get("authType")).isEqualTo("header");
            assertThat(paramsAfterModify.get("authHeaderName")).isEqualTo("X-Key");

            // Undo
            modifier.executeUndo(session);

            // Should restore original params (no auth credentials)
            Map<String, Object> paramsAfterUndo = getTriggerParams(session, "My Hook");
            assertThat(paramsAfterUndo.get("authType")).isEqualTo("none");
            assertThat(paramsAfterUndo).doesNotContainKey("authHeaderName");
            assertThat(paramsAfterUndo).doesNotContainKey("authHeaderValue");
        }
    }

    // ==================== Non-webhook Triggers ====================

    @Nested
    @DisplayName("Non-webhook triggers should not use webhook harmonization")
    class NonWebhookTriggers {

        @Test
        @DisplayName("httpMethod on manual trigger should pass through as regular param")
        void httpMethodOnManualShouldPassThrough() {
            WorkflowBuilderSession session = createSession();

            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("label", "Start");
            trigger.put("id", "trigger:start");
            trigger.put("type", "manual");
            session.getTriggers().add(trigger);

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Start");
            modifyParams.put("params", Map.of("httpMethod", "GET"));

            modifier.executeModifyNode(session, modifyParams);

            // For non-webhook triggers, httpMethod should be set directly on the node
            // (not inside a params sub-map)
            Map<String, Object> modifiedTrigger = session.getTriggers().get(0);
            assertThat(modifiedTrigger.get("httpMethod")).isEqualTo("GET");
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle webhook trigger with no existing params map")
        void shouldHandleNoExistingParams() {
            WorkflowBuilderSession session = createSession();

            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("label", "Hook");
            trigger.put("id", "trigger:hook");
            trigger.put("type", "webhook");
            // No params map at all
            session.getTriggers().add(trigger);

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "Hook");
            modifyParams.put("params", Map.of("httpMethod", "GET"));

            ToolExecutionResult result = modifier.executeModifyNode(session, modifyParams);
            assertThat(result.success()).isTrue();

            Map<String, Object> params = getTriggerParams(session, "Hook");
            assertThat(params).isNotNull();
            assertThat(params.get("httpMethod")).isEqualTo("GET");
        }

        @Test
        @DisplayName("Should handle webhook modify with mixed webhook and non-webhook params")
        void shouldHandleMixedParams() {
            WorkflowBuilderSession session = createSession();
            addWebhookTrigger(session, "My Hook", "POST", "none");

            Map<String, Object> modifyParams = new LinkedHashMap<>();
            modifyParams.put("node", "My Hook");
            modifyParams.put("params", Map.of(
                    "httpMethod", "GET",       // webhook param -> goes to params map
                    "label", "Renamed Hook"    // non-webhook param -> goes to node directly
            ));

            modifier.executeModifyNode(session, modifyParams);

            Map<String, Object> params = getTriggerParams(session, "Renamed Hook");
            assertThat(params).isNotNull();
            assertThat(params.get("httpMethod")).isEqualTo("GET");

            // Label should have been updated on the node itself
            Map<String, Object> trigger = session.getTriggers().get(0);
            assertThat(trigger.get("label")).isEqualTo("Renamed Hook");
        }
    }
}
