package com.apimarketplace.interfaces.tools;

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
 * Tests for InterfacePublishModule - the resource itself IS the landing page,
 * so the request body MUST NOT carry interfaceId. publication-service rejects
 * INTERFACE publications that supply a separate landing interfaceId, but we
 * defend the contract here at the tool boundary too.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfacePublishModule")
class InterfacePublishModuleTest {

    @Mock private PublicationClient publicationClient;

    private InterfacePublishModule module;
    private static final String TENANT = "tenant-1";
    private static final UUID INTERFACE_ID = UUID.randomUUID();
    private static final UUID PUB_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        module = new InterfacePublishModule(publicationClient);
    }

    private ToolExecutionContext ctx() { return ToolExecutionContext.of(TENANT); }

    @Nested
    @DisplayName("publish")
    class PublishTests {

        @Test
        @DisplayName("Sends type=INTERFACE + resourceId only - does NOT pass a separate interfaceId landing")
        void publishOmitsLandingInterfaceId() {
            when(publicationClient.publishResource(any(), eq(TENANT), isNull()))
                    .thenReturn(Map.of("id", PUB_ID.toString()));

            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", INTERFACE_ID.toString());
            params.put("title", "My Interface");
            params.put("visibility", "public");
            params.put("credits_per_use", 0);

            ToolExecutionResult result = module.execute("publish", params, TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isTrue();
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishResource(captor.capture(), eq(TENANT), isNull());
            Map<String, Object> sent = captor.getValue();
            assertThat(sent).containsEntry("type", "INTERFACE");
            assertThat(sent).containsEntry("resourceId", INTERFACE_ID.toString());
            assertThat(sent).containsEntry("visibility", "PUBLIC");
            assertThat(sent).doesNotContainKey("interfaceId"); // critical: would be rejected by publication-service
        }

        @Test @DisplayName("Returns failure when interface_id is missing")
        void publishMissingInterfaceId() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("title", "X"), TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("interface_id is required");
        }

        @Test @DisplayName("Returns failure when title is missing")
        void publishMissingTitle() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("interface_id", INTERFACE_ID.toString()), TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("title is required");
        }

        @Test @DisplayName("Returns failure when interface_id is not a valid UUID")
        void publishInvalidInterfaceId() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("interface_id", "not-a-uuid", "title", "X"), TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Invalid interface_id format");
        }
    }

    @Nested
    @DisplayName("unpublish")
    class UnpublishTests {

        @Test @DisplayName("unpublish threads orgId from ctx (2026-05-21 sweep) - verifies the 4-arg PublicationClient.unpublishResource is used so cross-workspace INTERFACE publications stay isolated")
        void unpublishHappyPath() {
            when(publicationClient.isResourcePublished("INTERFACE", INTERFACE_ID.toString())).thenReturn(true);

            ToolExecutionResult result = module.execute("unpublish",
                    Map.of("interface_id", INTERFACE_ID.toString()), TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isTrue();
            // ctx() has orgId=null; the 4-arg variant must be called regardless.
            verify(publicationClient).unpublishResource(eq("INTERFACE"), eq(INTERFACE_ID.toString()), eq(TENANT), isNull());
        }

        @Test @DisplayName("Returns failure when interface not published - never calls unpublishResource")
        void unpublishNotPublished() {
            when(publicationClient.isResourcePublished("INTERFACE", INTERFACE_ID.toString())).thenReturn(false);

            ToolExecutionResult result = module.execute("unpublish",
                    Map.of("interface_id", INTERFACE_ID.toString()), TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Resource not published");
            verify(publicationClient, never()).unpublishResource(any(), any(), any(), any());
        }
    }
}
