package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for AgentPublishModule - the publish/unpublish dispatcher for the
 * {@code agent} tool. Verifies request shaping, parameter validation, and
 * the "check before unpublish" guard that prevents silent no-ops on already-
 * unpublished agents (PublicationClient.unpublishByAgentConfigId swallows
 * exceptions internally, so we cannot rely on it to surface 404).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentPublishModule")
class AgentPublishModuleTest {

    @Mock private PublicationClient publicationClient;

    private AgentPublishModule module;
    private static final String TENANT = "tenant-1";
    private static final String TEST_ORG_ID = "org-77";
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID INTERFACE_ID = UUID.randomUUID();
    private static final UUID PUB_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        module = new AgentPublishModule(publicationClient);
    }

    private ToolExecutionContext ctx() {
        // 2026-05-21 - context carries TEST_ORG_ID so unpublish regression
        // test (executeUnpublish should thread ctx.orgId() to
        // publicationClient.unpublishByAgentConfigId 3-arg) actually asserts
        // the wiring rather than the all-nulls default.
        return new ToolExecutionContext(TENANT, java.util.Map.of(), java.util.Map.of(),
                java.util.Set.of(), null, null, TEST_ORG_ID, null);
    }

    private ToolExecutionContext ctx(Map<String, Object> credentials) {
        return new ToolExecutionContext(TENANT, credentials, java.util.Map.of(),
                java.util.Set.of(), null, null, TEST_ORG_ID, null);
    }

    @Nested
    @DisplayName("canHandle")
    class CanHandleTests {
        @Test @DisplayName("Handles publish and unpublish only")
        void handlesPublishAndUnpublish() {
            assertThat(module.canHandle("publish")).isTrue();
            assertThat(module.canHandle("unpublish")).isTrue();
            assertThat(module.canHandle("share")).isFalse();
            assertThat(module.canHandle("create")).isFalse();
        }
    }

    @Nested
    @DisplayName("access modes")
    class AccessModeTests {

        @Test
        @DisplayName("read-only agent access rejects publish before publication-service mutation")
        void readOnlyAgentAccessRejectsPublish() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("agent_id", AGENT_ID.toString(), "title", "X", "interface_id", INTERFACE_ID.toString()),
                    TENANT,
                    ctx(Map.of("__agentAccessMode__", "read"))).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("read-only").contains("publish");
            verify(publicationClient, never()).publishAgent(any(), any(), any());
        }

        @Test
        @DisplayName("read-only agent access rejects unpublish before publication-service lookup")
        void readOnlyAgentAccessRejectsUnpublish() {
            ToolExecutionResult result = module.execute("unpublish",
                    Map.of("agent_id", AGENT_ID.toString()),
                    TENANT,
                    ctx(Map.of("__agentAccessMode__", "read"))).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("read-only").contains("unpublish");
            verify(publicationClient, never()).isAgentPublished(any(), any());
        }
    }

    @Nested
    @DisplayName("publish")
    class PublishTests {

        @Test
        @DisplayName("Sends agentConfigId + interfaceId + visibility to publication-service and echoes the publication id")
        void publishHappyPath() {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", PUB_ID.toString());
            response.put("title", "My Agent");
            response.put("status", "PENDING_REVIEW");
            when(publicationClient.publishAgent(any(), eq(TENANT), eq(TEST_ORG_ID))).thenReturn(response);

            Map<String, Object> params = new HashMap<>();
            params.put("agent_id", AGENT_ID.toString());
            params.put("title", "My Agent");
            params.put("interface_id", INTERFACE_ID.toString());
            params.put("visibility", "public");
            params.put("credits_per_use", 5);
            params.put("description", "Cool agent");

            ToolExecutionResult result = module.execute("publish", params, TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).containsEntry("status", "PENDING_REVIEW");
            assertThat(data.get("message").toString()).contains("submitted for review");
            assertThat(data).containsEntry("publication_id", PUB_ID.toString());
            assertThat(data).containsEntry("interface_id", INTERFACE_ID.toString());
            assertThat(data).containsEntry("visibility", "PUBLIC");
            assertThat(data).containsEntry("credits_per_use", 5);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishAgent(captor.capture(), eq(TENANT), eq(TEST_ORG_ID));
            Map<String, Object> sent = captor.getValue();
            assertThat(sent).containsEntry("agentConfigId", AGENT_ID.toString());
            assertThat(sent).containsEntry("interfaceId", INTERFACE_ID.toString());
            assertThat(sent).containsEntry("title", "My Agent");
            assertThat(sent).containsEntry("description", "Cool agent");
            assertThat(sent).containsEntry("visibility", "PUBLIC");
            assertThat(sent).containsEntry("creditsPerUse", 5);
        }

        @Test
        @DisplayName("Defaults visibility to PRIVATE, creditsPerUse to 0, and status echoes ACTIVE when server omits it")
        void publishDefaults() {
            when(publicationClient.publishAgent(any(), eq(TENANT), eq(TEST_ORG_ID)))
                    .thenReturn(Map.of("id", PUB_ID.toString(), "status", "ACTIVE"));

            Map<String, Object> params = Map.of(
                    "agent_id", AGENT_ID.toString(),
                    "title", "X",
                    "interface_id", INTERFACE_ID.toString());

            ToolExecutionResult result = module.execute("publish", params, TENANT, ctx()).orElseThrow();

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishAgent(captor.capture(), eq(TENANT), eq(TEST_ORG_ID));
            assertThat(captor.getValue()).containsEntry("visibility", "PRIVATE");
            assertThat(captor.getValue()).containsEntry("creditsPerUse", 0);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).containsEntry("status", "ACTIVE");
            assertThat(data.get("message").toString()).contains("Agent published");
        }

        @Test @DisplayName("Returns failure when agent_id is missing")
        void publishMissingAgentId() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("title", "X", "interface_id", INTERFACE_ID.toString()), TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("agent_id is required");
        }

        @Test @DisplayName("Returns failure when title is missing")
        void publishMissingTitle() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("agent_id", AGENT_ID.toString(), "interface_id", INTERFACE_ID.toString()),
                    TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("title is required");
        }

        @Test @DisplayName("Returns failure when interface_id is missing - agents need a landing page")
        void publishMissingInterfaceId() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("agent_id", AGENT_ID.toString(), "title", "X"), TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("interface_id is required");
        }

        @Test @DisplayName("Returns failure when agent_id is not a valid UUID")
        void publishInvalidAgentId() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("agent_id", "not-a-uuid", "title", "X", "interface_id", INTERFACE_ID.toString()),
                    TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Invalid agent_id format");
        }

        @Test @DisplayName("Rejects invalid visibility before calling publication-service")
        void publishRejectsInvalidVisibility() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("agent_id", AGENT_ID.toString(), "title", "X",
                           "interface_id", INTERFACE_ID.toString(), "visibility", "BOGUS"),
                    TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Invalid visibility");
            verify(publicationClient, never()).publishAgent(any(), any(), any());
        }

        @Test
        @DisplayName("Surfaces backend error message when publication-service throws (e.g. PUBLIC requires creditsPerUse > 0)")
        void publishSurfacesBackendError() {
            when(publicationClient.publishAgent(any(), eq(TENANT), eq(TEST_ORG_ID)))
                    .thenThrow(new RuntimeException("Failed to publish agent: PUBLIC publications must charge credits"));

            ToolExecutionResult result = module.execute("publish",
                    Map.of("agent_id", AGENT_ID.toString(), "title", "X",
                           "interface_id", INTERFACE_ID.toString(), "visibility", "PUBLIC"),
                    TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("PUBLIC publications must charge credits");
        }
    }

    @Nested
    @DisplayName("unpublish")
    class UnpublishTests {

        @Test @DisplayName("unpublish threads ctx.orgId() to unpublishByAgentConfigId - regression for org-bleed when two members publish the same agent config")
        void unpublishHappyPath() {
            when(publicationClient.isAgentPublished(AGENT_ID, TENANT)).thenReturn(true);

            ToolExecutionResult result = module.execute("unpublish",
                    Map.of("agent_id", AGENT_ID.toString()), TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).containsEntry("status", "UNPUBLISHED");
            assertThat(data).containsEntry("agent_id", AGENT_ID.toString());

            verify(publicationClient).isAgentPublished(AGENT_ID, TENANT);
            // The orgId must equal the value from ctx() (TEST_ORG_ID) - not any()/null -
            // so a regression that drops the threading would fail this assertion.
            verify(publicationClient).unpublishByAgentConfigId(AGENT_ID, TENANT, TEST_ORG_ID);
        }

        @Test
        @DisplayName("Returns failure when agent has no active publication - never calls unpublish (which silently swallows errors)")
        void unpublishNotPublishedSurfacesFailure() {
            when(publicationClient.isAgentPublished(AGENT_ID, TENANT)).thenReturn(false);

            ToolExecutionResult result = module.execute("unpublish",
                    Map.of("agent_id", AGENT_ID.toString()), TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Resource not published");
            verify(publicationClient, never()).unpublishByAgentConfigId(any(), any(), any());
        }

        @Test @DisplayName("Returns failure when agent_id is missing")
        void unpublishMissingAgentId() {
            ToolExecutionResult result = module.execute("unpublish", Map.of(), TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("agent_id is required");
            verify(publicationClient, never()).isAgentPublished(any(), any());
        }

        @Test @DisplayName("Returns failure when agent_id is not a valid UUID")
        void unpublishInvalidAgentId() {
            ToolExecutionResult result = module.execute("unpublish",
                    Map.of("agent_id", "garbage"), TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Invalid agent_id format");
            verify(publicationClient, never()).isAgentPublished(any(), any());
        }
    }
}
