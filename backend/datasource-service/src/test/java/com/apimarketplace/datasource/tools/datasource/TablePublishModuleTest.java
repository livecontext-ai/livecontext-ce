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
        module = new TablePublishModule(publicationClient, org.mockito.Mockito.mock(com.apimarketplace.datasource.services.DataSourceService.class));
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
        @DisplayName("regression (budget): a table over the row limit is an INVALID_PARAMETER_VALUE naming the table, with the trim action")
        void tooLargeTableIsAnActionableRefusal() {
            String reason = "Table 'Orders' has more than 5000 rows (max 5000 rows per published table).";
            when(publicationClient.publishResource(any(), eq(TENANT), isNull())).thenThrow(
                    new com.apimarketplace.publication.client.PublicationValidationException(
                            "PUBLICATION_SNAPSHOT_TOO_LARGE", reason + " Delete rows from the table, then publish again.",
                            Map.of("reason", reason, "maxTableRows", 5000, "breakdown",
                                    List.of(Map.of("type", "datasource", "id", "42", "name", "Orders"))),
                            null));
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 42);
            params.put("title", "T");
            params.put("interface_id", INTERFACE_ID.toString());

            ToolExecutionResult result = module.execute("publish", params, TENANT, ctx()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            // One reason, one fix (in this tool's actions), no share-modal sentence, no "Heaviest" list.
            assertThat(result.error()).isEqualTo("Publish refused: " + reason + " Fix: delete rows with "
                    + "table(action='delete_rows', table_id=42, where={...}), then call publish again.");
        }

        @Test
        @DisplayName("a transient copy failure is a retryable EXTERNAL_SERVICE_ERROR carrying the service's sentence")
        void tableCopyFailureIsRetryable() {
            when(publicationClient.publishResource(any(), eq(TENANT), isNull())).thenThrow(
                    new com.apimarketplace.publication.client.PublicationValidationException(
                            "TABLE_COPY_FAILED", "The rows of table 'Orders' (id 42) could not be read just now, "
                                    + "so nothing was published. This is temporary: publish again in a moment.",
                            Map.of("retryable", true), null));
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 42);
            params.put("title", "T");
            params.put("interface_id", INTERFACE_ID.toString());

            ToolExecutionResult result = module.execute("publish", params, TENANT, ctx()).orElseThrow();

            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXTERNAL_SERVICE_ERROR);
            assertThat(result.error()).startsWith("Publish failed: The rows of table 'Orders' (id 42)");
        }

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
    @DisplayName("restricted calling context (LC-066)")
    class RestrictedContextTests {

        private final Map<String, Object> restricted = Map.of(
                com.apimarketplace.common.classification.DataSensitivity.CREDENTIAL_KEY,
                com.apimarketplace.common.classification.DataSensitivity.RESTRICTED.name());

        private Map<String, Object> publishParams() {
            return Map.of("table_id", "42", "title", "T", "interface_id", INTERFACE_ID.toString());
        }

        @Test
        @DisplayName("regression: publish from a conversation that read Gmail / Drive is refused before publication-service")
        void restrictedPublishIsRefused() {
            ToolExecutionResult result = module.execute("publish", publishParams(), TENANT, ctx(restricted)).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error())
                    .startsWith(com.apimarketplace.common.classification.RestrictedDataPolicy.REFUSAL_CODE)
                    .contains("no table can be published");
            verifyNoInteractions(publicationClient);
        }

        @Test
        @DisplayName("unpublish still works from a restricted conversation")
        void restrictedUnpublishStillWorks() {
            when(publicationClient.isResourcePublished("TABLE", "42")).thenReturn(true);

            ToolExecutionResult result = module.execute("unpublish", Map.of("table_id", 42), TENANT, ctx(restricted))
                    .orElseThrow();

            assertThat(result.success()).isTrue();
            verify(publicationClient).unpublishResource(eq("TABLE"), eq("42"), eq(TENANT), isNull());
        }

        @Test
        @DisplayName("an explicit NORMAL tag is not a refusal (no over-refusal)")
        void normalTagPublishes() {
            when(publicationClient.publishResource(any(), eq(TENANT), isNull())).thenReturn(Map.of("id", PUB_ID.toString()));

            ToolExecutionResult result = module.execute("publish", publishParams(), TENANT,
                    ctx(Map.of(com.apimarketplace.common.classification.DataSensitivity.CREDENTIAL_KEY, "NORMAL")))
                    .orElseThrow();

            assertThat(result.success()).isTrue();
            verify(publicationClient).publishResource(any(), eq(TENANT), isNull());
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
