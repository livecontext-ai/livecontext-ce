package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.tools.ToolErrorCode;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for TablePublishModule - wraps publication-service's generic
 * publishResource endpoint for the TABLE type. Table IDs are integer-shaped
 * but transmitted as strings to match the publication-service contract
 * (resourceId is a String column).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TablePublishModule")
class TablePublishModuleTest {

    @Mock private PublicationClient publicationClient;

    private TablePublishModule module;
    private static final String TENANT = "tenant-1";
    private static final UUID INTERFACE_ID = UUID.randomUUID();
    private static final UUID PUB_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        module = new TablePublishModule(publicationClient);
    }

    private ToolExecutionContext ctx() { return ToolExecutionContext.of(TENANT); }

    private ToolExecutionContext ctx(Map<String, Object> credentials) {
        return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(), null, null, null, null);
    }

    @Nested
    @DisplayName("access enforcement (publish/unpublish are WRITE actions)")
    class AccessEnforcementTests {

        @Test
        @DisplayName("read-only mode (tableAccessMode='read') blocks publish before publication-service")
        void readModeBlocksPublish() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 42);
            params.put("title", "T");
            params.put("interface_id", INTERFACE_ID.toString());

            ToolExecutionResult result = module.execute("publish", params, TENANT,
                    ctx(Map.of("tableAccessMode", "read"))).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(publicationClient);
        }

        @Test
        @DisplayName("read-only mode blocks unpublish too")
        void readModeBlocksUnpublish() {
            ToolExecutionResult result = module.execute("unpublish", Map.of("table_id", 42), TENANT,
                    ctx(Map.of("tableAccessMode", "read"))).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(publicationClient);
        }

        @Test
        @DisplayName("allow-list: publishing a table_id NOT in allowedTableIds is denied (escalation leak)")
        void outOfAllowListBlocksPublish() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 99);
            params.put("title", "T");
            params.put("interface_id", INTERFACE_ID.toString());

            ToolExecutionResult result = module.execute("publish", params, TENANT,
                    ctx(Map.of("allowedTableIds", List.of("5")))).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("approved table list");
            verifyNoInteractions(publicationClient);
        }

        @Test
        @DisplayName("allow-list: unpublishing a table_id NOT in allowedTableIds is denied too")
        void outOfAllowListBlocksUnpublish() {
            ToolExecutionResult result = module.execute("unpublish", Map.of("table_id", 99), TENANT,
                    ctx(Map.of("allowedTableIds", List.of("5")))).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(publicationClient);
        }

        @Test
        @DisplayName("allow-list: publishing an allowed table_id passes through to publication-service")
        void inAllowListAllowsPublish() {
            when(publicationClient.publishResource(any(), eq(TENANT), isNull()))
                    .thenReturn(Map.of("id", PUB_ID.toString()));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 5);
            params.put("title", "T");
            params.put("interface_id", INTERFACE_ID.toString());

            ToolExecutionResult result = module.execute("publish", params, TENANT,
                    ctx(Map.of("allowedTableIds", List.of("5")))).orElseThrow();

            assertThat(result.success()).isTrue();
            verify(publicationClient).publishResource(any(), eq(TENANT), isNull());
        }
    }

    @Nested
    @DisplayName("publish")
    class PublishTests {

        @Test
        @DisplayName("Accepts numeric table_id and stringifies it for resourceId")
        void publishCoercesNumericIdToString() {
            when(publicationClient.publishResource(any(), eq(TENANT), isNull()))
                    .thenReturn(Map.of("id", PUB_ID.toString()));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 42);  // numeric (JSON deserializes ints as Integer)
            params.put("title", "T");
            params.put("interface_id", INTERFACE_ID.toString());

            ToolExecutionResult result = module.execute("publish", params, TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isTrue();
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishResource(captor.capture(), eq(TENANT), isNull());
            assertThat(captor.getValue()).containsEntry("type", "TABLE");
            assertThat(captor.getValue()).containsEntry("resourceId", "42");
            assertThat(captor.getValue()).containsEntry("interfaceId", INTERFACE_ID.toString());
        }

        @Test @DisplayName("Falls back to datasource_id when table_id is absent")
        void publishAcceptsDatasourceIdAlias() {
            when(publicationClient.publishResource(any(), eq(TENANT), isNull()))
                    .thenReturn(Map.of("id", PUB_ID.toString()));

            Map<String, Object> params = Map.of(
                    "datasource_id", "99",
                    "title", "T",
                    "interface_id", INTERFACE_ID.toString());

            module.execute("publish", params, TENANT, ctx()).orElseThrow();

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishResource(captor.capture(), eq(TENANT), isNull());
            assertThat(captor.getValue()).containsEntry("resourceId", "99");
        }

        @Test @DisplayName("Returns failure when table_id is missing")
        void publishMissingTableId() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("title", "X", "interface_id", INTERFACE_ID.toString()), TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("table_id is required");
        }

        @Test @DisplayName("Returns failure when interface_id is missing - tables need a landing page")
        void publishMissingInterfaceId() {
            ToolExecutionResult result = module.execute("publish",
                    Map.of("table_id", "1", "title", "X"), TENANT, ctx()).orElseThrow();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("interface_id is required");
        }
    }

    @Nested
    @DisplayName("unpublish")
    class UnpublishTests {

        @Test @DisplayName("unpublish threads orgId from ctx (2026-05-21 sweep) - verifies the 4-arg PublicationClient.unpublishResource is used so cross-workspace TABLE publications stay isolated")
        void unpublishHappyPath() {
            when(publicationClient.isResourcePublished("TABLE", "42")).thenReturn(true);

            ToolExecutionResult result = module.execute("unpublish",
                    Map.of("table_id", 42), TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isTrue();
            // ctx() has orgId=null; the 4-arg variant must be called regardless.
            verify(publicationClient).unpublishResource(eq("TABLE"), eq("42"), eq(TENANT), isNull());
        }

        @Test @DisplayName("Returns failure when table not published - never calls unpublishResource")
        void unpublishNotPublished() {
            when(publicationClient.isResourcePublished("TABLE", "42")).thenReturn(false);

            ToolExecutionResult result = module.execute("unpublish",
                    Map.of("table_id", 42), TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Resource not published");
            verify(publicationClient, never()).unpublishResource(any(), any(), any(), any());
        }
    }
}
