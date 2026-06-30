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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for TriggerCreator webhook parameter storage.
 *
 * Verifies that:
 * - Webhook triggers store httpMethod, authType in params
 * - Auth-specific params are stored for basic, header, jwt
 * - Defaults are applied (POST, none)
 * - Parameter aliases (snake_case, short names) are accepted
 * - Edge cases (null, empty, missing) are handled
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerCreator - Webhook Params")
class TriggerCreatorWebhookTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    @Mock
    private DataSourceClient dataSourceService;

    @Mock
    private SmartDefaultsEngine smartDefaultsEngine;

    @Mock
    private ResponseOptimizer responseOptimizer;

    @Mock
    private TriggerClient triggerClient;

    private TriggerCreator creator;

    @BeforeEach
    void setUp() {
        creator = new TriggerCreator(sessionStore, dataSourceService, smartDefaultsEngine, responseOptimizer, triggerClient);
    }

    private WorkflowBuilderSession createSession() {
        return WorkflowBuilderSession.create("tenant-1", "conv-1", "Test Workflow", null);
    }

    private Map<String, Object> webhookParams(String label) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("label", label);
        params.put("trigger_type", "webhook");
        return params;
    }

    private void stubDefaults() {
        when(smartDefaultsEngine.applyTriggerDefaults(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(responseOptimizer.buildTriggerResponse(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getParams(WorkflowBuilderSession session) {
        Map<String, Object> trigger = session.getTriggers().get(session.getTriggers().size() - 1);
        return (Map<String, Object>) trigger.get("params");
    }

    // ==================== Default Values ====================

    @Nested
    @DisplayName("Default webhook params")
    class DefaultParams {

        @Test
        @DisplayName("Should default httpMethod to POST")
        void shouldDefaultHttpMethodToPost() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult result = creator.executeAddTrigger(session,
                    webhookParams("My Hook"), "tenant-1");
            assertThat(result.success()).isTrue();

            Map<String, Object> params = getParams(session);
            assertThat(params).isNotNull();
            assertThat(params.get("httpMethod")).isEqualTo("POST");
        }

        @Test
        @DisplayName("Should default authType to none")
        void shouldDefaultAuthTypeToNone() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            creator.executeAddTrigger(session, webhookParams("My Hook"), "tenant-1");

            Map<String, Object> params = getParams(session);
            assertThat(params.get("authType")).isEqualTo("none");
        }

        @Test
        @DisplayName("Should store params map on trigger node")
        void shouldStoreParamsOnNode() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            creator.executeAddTrigger(session, webhookParams("My Hook"), "tenant-1");

            Map<String, Object> trigger = session.getTriggers().get(0);
            assertThat(trigger).containsKey("params");
            assertThat(trigger.get("params")).isInstanceOf(Map.class);
        }
    }

    // ==================== HTTP Method ====================

    @Nested
    @DisplayName("HTTP method configuration")
    class HttpMethodConfig {

        @Test
        @DisplayName("Should accept httpMethod param")
        void shouldAcceptHttpMethod() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("httpMethod", "GET");
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getParams(session).get("httpMethod")).isEqualTo("GET");
        }

        @Test
        @DisplayName("Should accept http_method snake_case alias")
        void shouldAcceptHttpMethodSnakeCase() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("http_method", "PUT");
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getParams(session).get("httpMethod")).isEqualTo("PUT");
        }

        @Test
        @DisplayName("Should accept method short alias")
        void shouldAcceptMethodAlias() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("method", "DELETE");
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getParams(session).get("httpMethod")).isEqualTo("DELETE");
        }

        @Test
        @DisplayName("Should uppercase httpMethod")
        void shouldUppercaseHttpMethod() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("httpMethod", "patch");
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getParams(session).get("httpMethod")).isEqualTo("PATCH");
        }
    }

    // ==================== Auth Type ====================

    @Nested
    @DisplayName("Auth type configuration")
    class AuthTypeConfig {

        @Test
        @DisplayName("Should accept authType param")
        void shouldAcceptAuthType() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "basic");
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getParams(session).get("authType")).isEqualTo("basic");
        }

        @Test
        @DisplayName("Should accept auth_type snake_case alias")
        void shouldAcceptAuthTypeSnakeCase() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("auth_type", "header");
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getParams(session).get("authType")).isEqualTo("header");
        }

        @Test
        @DisplayName("Should lowercase authType")
        void shouldLowercaseAuthType() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "BASIC");
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getParams(session).get("authType")).isEqualTo("basic");
        }
    }

    // ==================== Basic Auth ====================

    @Nested
    @DisplayName("Basic auth params")
    class BasicAuthParams {

        @Test
        @DisplayName("Should store basicUsername and basicPassword")
        void shouldStoreBasicCredentials() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "basic");
            params.put("basicUsername", "admin");
            params.put("basicPassword", "secret123");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored.get("basicUsername")).isEqualTo("admin");
            assertThat(stored.get("basicPassword")).isEqualTo("secret123");
        }

        @Test
        @DisplayName("Should accept snake_case basic_username alias")
        void shouldAcceptSnakeCaseBasicUsername() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "basic");
            params.put("basic_username", "user1");
            params.put("basic_password", "pass1");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored.get("basicUsername")).isEqualTo("user1");
            assertThat(stored.get("basicPassword")).isEqualTo("pass1");
        }

        @Test
        @DisplayName("Should accept short username/password aliases")
        void shouldAcceptShortAliases() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "basic");
            params.put("username", "u");
            params.put("password", "p");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored.get("basicUsername")).isEqualTo("u");
            assertThat(stored.get("basicPassword")).isEqualTo("p");
        }

        @Test
        @DisplayName("Should not store basic auth when authType is not basic")
        void shouldNotStoreBasicAuthForOtherTypes() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "header");
            params.put("basicUsername", "admin");
            params.put("basicPassword", "secret");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored).doesNotContainKey("basicUsername");
            assertThat(stored).doesNotContainKey("basicPassword");
        }
    }

    // ==================== Header Auth ====================

    @Nested
    @DisplayName("Header auth params")
    class HeaderAuthParams {

        @Test
        @DisplayName("Should store authHeaderName and authHeaderValue")
        void shouldStoreHeaderAuth() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "header");
            params.put("authHeaderName", "X-API-Key");
            params.put("authHeaderValue", "my-secret-key");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored.get("authHeaderName")).isEqualTo("X-API-Key");
            assertThat(stored.get("authHeaderValue")).isEqualTo("my-secret-key");
        }

        @Test
        @DisplayName("Should accept snake_case header aliases")
        void shouldAcceptSnakeCaseHeaderAliases() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "header");
            params.put("auth_header_name", "Authorization");
            params.put("auth_header_value", "Bearer xyz");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored.get("authHeaderName")).isEqualTo("Authorization");
            assertThat(stored.get("authHeaderValue")).isEqualTo("Bearer xyz");
        }

        @Test
        @DisplayName("Should accept short headerName/headerValue aliases")
        void shouldAcceptShortHeaderAliases() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "header");
            params.put("headerName", "X-Token");
            params.put("headerValue", "abc");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored.get("authHeaderName")).isEqualTo("X-Token");
            assertThat(stored.get("authHeaderValue")).isEqualTo("abc");
        }

        @Test
        @DisplayName("Should not store header auth when authType is not header")
        void shouldNotStoreHeaderAuthForOtherTypes() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "basic");
            params.put("authHeaderName", "X-Key");
            params.put("authHeaderValue", "val");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored).doesNotContainKey("authHeaderName");
            assertThat(stored).doesNotContainKey("authHeaderValue");
        }
    }

    // ==================== JWT Auth ====================

    @Nested
    @DisplayName("JWT auth params")
    class JwtAuthParams {

        @Test
        @DisplayName("Should store jwtSecretKey and jwtAlgorithm")
        void shouldStoreJwtAuth() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "jwt");
            params.put("jwtSecretKey", "my-jwt-secret");
            params.put("jwtAlgorithm", "HS256");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored.get("jwtSecretKey")).isEqualTo("my-jwt-secret");
            assertThat(stored.get("jwtAlgorithm")).isEqualTo("HS256");
        }

        @Test
        @DisplayName("Should accept snake_case jwt aliases")
        void shouldAcceptSnakeCaseJwtAliases() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "jwt");
            params.put("jwt_secret_key", "secret");
            params.put("jwt_algorithm", "RS256");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored.get("jwtSecretKey")).isEqualTo("secret");
            assertThat(stored.get("jwtAlgorithm")).isEqualTo("RS256");
        }

        @Test
        @DisplayName("Should accept short secretKey/algorithm aliases")
        void shouldAcceptShortJwtAliases() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "jwt");
            params.put("secretKey", "k");
            params.put("algorithm", "HS384");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored.get("jwtSecretKey")).isEqualTo("k");
            assertThat(stored.get("jwtAlgorithm")).isEqualTo("HS384");
        }

        @Test
        @DisplayName("Should not store jwt auth when authType is not jwt")
        void shouldNotStoreJwtAuthForOtherTypes() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "none");
            params.put("jwtSecretKey", "secret");
            params.put("jwtAlgorithm", "HS256");
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored).doesNotContainKey("jwtSecretKey");
            assertThat(stored).doesNotContainKey("jwtAlgorithm");
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle no extra params (defaults only)")
        void shouldHandleNoExtraParams() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            creator.executeAddTrigger(session, webhookParams("Hook"), "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored).containsEntry("httpMethod", "POST");
            assertThat(stored).containsEntry("authType", "none");
            assertThat(stored).hasSize(2); // Only defaults, no auth-specific params
        }

        @Test
        @DisplayName("Should handle basic auth with missing password")
        void shouldHandleBasicAuthMissingPassword() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "basic");
            params.put("basicUsername", "admin");
            // No password provided
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored.get("basicUsername")).isEqualTo("admin");
            assertThat(stored).doesNotContainKey("basicPassword");
        }

        @Test
        @DisplayName("Should handle header auth with only header name")
        void shouldHandleHeaderAuthMissingValue() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = webhookParams("Hook");
            params.put("authType", "header");
            params.put("authHeaderName", "X-Key");
            // No value provided
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> stored = getParams(session);
            assertThat(stored.get("authHeaderName")).isEqualTo("X-Key");
            assertThat(stored).doesNotContainKey("authHeaderValue");
        }

        @Test
        @DisplayName("Non-webhook triggers should not store webhook params")
        void nonWebhookShouldNotStoreWebhookParams() {
            stubDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Manual Run");
            params.put("trigger_type", "manual");
            params.put("httpMethod", "GET"); // Should be ignored
            creator.executeAddTrigger(session, params, "tenant-1");

            Map<String, Object> trigger = session.getTriggers().get(0);
            assertThat(trigger).doesNotContainKey("params");
        }
    }
}
