package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.dto.*;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WorkflowInspectorService.
 *
 * WorkflowInspectorService extracts API/Tool data for the workflow inspector.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowInspectorService")
class WorkflowInspectorServiceTest {

    @Mock
    private ApiRepository apiRepository;

    @Mock
    private ApiToolRepository apiToolRepository;

    @Mock
    private ApiToolParameterRepository apiToolParameterRepository;

    @Mock
    private ToolNameRepository toolNameRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private CredentialClient credentialClient;

    private WorkflowInspectorService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowInspectorService(
            apiRepository, apiToolRepository, apiToolParameterRepository,
            toolNameRepository, jdbcTemplate, credentialClient
        );
        // Default: no variants disabled. Tests that exercise the filter override this.
        // Lenient so tests not hitting fetchDisabledVariantKeys() don't fail on strict stubs.
        lenient().when(credentialClient.listPlatformCredentials()).thenReturn(List.of());
    }

    /**
     * Helper: configured-then-disabled dto. {@code hasClientSecret=true} marks the row
     * as actually configured by the admin (any of the hasX flags works) - the May 2026
     * placeholder gate in {@code WorkflowInspectorService.fetchDisabledVariantKeys}
     * requires a configured row before the disable takes user-visible effect.
     */
    private static PlatformCredentialStatusDto disabledDto(String name, String variant) {
        PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
        dto.setName(name);
        dto.setVariant(variant);
        dto.setEnabled(false);
        dto.setHasClientSecret(true);
        return dto;
    }

    /**
     * Helper: phantom placeholder dto matching the prod Salesforce shape
     * ({@code is_enabled=false}, ALL hasX flags null). Synthesized by
     * {@code PlatformCredentialRepository.setEnabledForVariant} when the admin flips a
     * per-variant toggle off before ever saving secrets. Must NOT contribute to the
     * disabled-set; the inspector should still show the integration.
     */
    private static PlatformCredentialStatusDto phantomDto(String name, String variant) {
        PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
        dto.setName(name);
        dto.setVariant(variant);
        dto.setEnabled(false);
        // No setHasX - exactly the prod row shape.
        return dto;
    }

    /** Minimal tool row matching the SQL that getToolDetailBySlug expects. */
    private static Map<String, Object> sampleToolRow(UUID toolId, String slug) {
        Map<String, Object> toolRow = new HashMap<>();
        toolRow.put("id", toolId);
        toolRow.put("api_id", UUID.randomUUID());
        toolRow.put("description", "desc");
        toolRow.put("method", "POST");
        toolRow.put("tool_name_id", null);
        toolRow.put("tool_slug", slug);
        toolRow.put("tool_name", "Tool");
        toolRow.put("api_slug", "api");
        toolRow.put("icon_slug", "mcp");
        return toolRow;
    }

    /** Minimal tool_credentials row matching the SELECT in WorkflowInspectorService. */
    private static Map<String, Object> credentialRow(String credentialName, String variant, String displayName, String authType) {
        Map<String, Object> row = new HashMap<>();
        row.put("credential_name", credentialName);
        row.put("variant", variant);
        row.put("is_required", true);
        row.put("usage", "auth");
        row.put("condition", null);
        row.put("metadata", null);
        row.put("display_name", displayName);
        row.put("description", displayName + " credential");
        row.put("credential_type", credentialName + "_" + variant);
        row.put("auth_type", authType);
        row.put("test_endpoint", null);
        row.put("documentation_url", null);
        row.put("icon_url", null);
        row.put("properties", null);
        row.put("extends_", null);
        return row;
    }

    // ========================================================================
    // getAllApisForWorkflow() tests
    // ========================================================================

    @Nested
    @DisplayName("getAllApisForWorkflow()")
    class GetAllApisForWorkflowTests {

        @Test
        @DisplayName("should return all APIs without filter")
        void shouldReturnAllApisWithoutFilter() {
            List<Map<String, Object>> rows = List.of(
                Map.of("api_slug", "gmail-api", "api_name", "Gmail API",
                       "description", "Gmail API", "tools_count", 5L, "icon_slug", "gmail"),
                Map.of("api_slug", "slack-api", "api_name", "Slack API",
                       "description", "Slack API", "tools_count", 3L, "icon_slug", "slack")
            );

            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(rows);

            List<WorkflowApiDTO> result = service.getAllApisForWorkflow(null);

            assertEquals(2, result.size());
            assertEquals("gmail-api", result.get(0).slug());
            assertEquals("Gmail API", result.get(0).apiName());
            assertEquals(5, result.get(0).toolsCount());
        }

        @Test
        @DisplayName("should filter APIs by name")
        void shouldFilterApisByName() {
            List<Map<String, Object>> rows = List.of(
                Map.of("api_slug", "gmail-api", "api_name", "Gmail API",
                       "description", "Gmail API", "tools_count", 5L, "icon_slug", "gmail")
            );

            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(rows);

            List<WorkflowApiDTO> result = service.getAllApisForWorkflow("gmail");

            assertEquals(1, result.size());
            assertEquals("gmail-api", result.get(0).slug());
        }

        @Test
        @DisplayName("should return empty list when no APIs found")
        void shouldReturnEmptyListWhenNoApisFound() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

            List<WorkflowApiDTO> result = service.getAllApisForWorkflow(null);

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // getToolsForApi() tests
    // ========================================================================

    @Nested
    @DisplayName("getToolsForApi()")
    class GetToolsForApiTests {

        @Test
        @DisplayName("should return empty list when API not found")
        void shouldReturnEmptyListWhenApiNotFound() {
            when(apiRepository.findByApiSlug("unknown")).thenReturn(Optional.empty());

            List<WorkflowToolDTO> result = service.getToolsForApi("unknown");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return tools for existing API")
        void shouldReturnToolsForExistingApi() {
            UUID apiId = UUID.randomUUID();
            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setApiSlug("gmail-api");

            UUID toolId = UUID.randomUUID();
            String toolNameId = UUID.randomUUID().toString();
            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setToolNameId(toolNameId);
            tool.setToolSlug("gmail-api-send-email");
            tool.setDescription("Send an email");
            tool.setMethod("POST");

            ToolNameEntity toolName = new ToolNameEntity();
            toolName.setId(UUID.fromString(toolNameId));
            toolName.setName("Send Email");

            when(apiRepository.findByApiSlug("gmail-api")).thenReturn(Optional.of(api));
            when(apiToolRepository.findByApiIdAndIsActiveTrue(apiId)).thenReturn(List.of(tool));
            when(toolNameRepository.findById(UUID.fromString(toolNameId))).thenReturn(Optional.of(toolName));
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString())).thenReturn(List.of("gmail"));

            List<WorkflowToolDTO> result = service.getToolsForApi("gmail-api");

            assertEquals(1, result.size());
            assertEquals("gmail-api-send-email", result.get(0).slug());
            assertEquals("Send Email", result.get(0).name());
            assertEquals("POST", result.get(0).method());
        }

        @Test
        @DisplayName("should generate slug when tool has no slug")
        void shouldGenerateSlugWhenToolHasNoSlug() {
            UUID apiId = UUID.randomUUID();
            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setApiSlug("gmail-api");

            UUID toolId = UUID.randomUUID();
            String toolNameId = UUID.randomUUID().toString();
            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setToolNameId(toolNameId);
            tool.setToolSlug(null); // No slug
            tool.setDescription("Send an email");
            tool.setMethod("POST");

            ToolNameEntity toolName = new ToolNameEntity();
            toolName.setId(UUID.fromString(toolNameId));
            toolName.setName("Send Email");

            when(apiRepository.findByApiSlug("gmail-api")).thenReturn(Optional.of(api));
            when(apiToolRepository.findByApiIdAndIsActiveTrue(apiId)).thenReturn(List.of(tool));
            when(toolNameRepository.findById(UUID.fromString(toolNameId))).thenReturn(Optional.of(toolName));
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString())).thenReturn(List.of("gmail"));
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(apiId), eq(toolId))).thenReturn(List.of());
            when(apiToolRepository.save(any(ApiToolEntity.class))).thenReturn(tool);

            List<WorkflowToolDTO> result = service.getToolsForApi("gmail-api");

            assertEquals(1, result.size());
            assertTrue(result.get(0).slug().startsWith("gmail-api-"));
            verify(apiToolRepository).save(any(ApiToolEntity.class));
        }
    }

    // ========================================================================
    // getToolBySlug() tests
    // ========================================================================

    @Nested
    @DisplayName("getToolBySlug()")
    class GetToolBySlugTests {

        @Test
        @DisplayName("should return empty optional when tool not found")
        void shouldReturnEmptyOptionalWhenToolNotFound() {
            when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());

            Optional<WorkflowToolDTO> result = service.getToolBySlug("unknown-tool");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return tool when found")
        void shouldReturnToolWhenFound() {
            Map<String, Object> row = new HashMap<>();
            row.put("id", UUID.randomUUID());
            row.put("api_id", UUID.randomUUID());
            row.put("description", "Send an email");
            row.put("method", "POST");
            row.put("tool_name_id", UUID.randomUUID().toString());
            row.put("tool_name", "Send Email");
            row.put("api_slug", "gmail-api");
            row.put("icon_slug", "gmail");

            when(jdbcTemplate.queryForList(anyString(), eq("gmail-api-send-email")))
                .thenReturn(List.of(row));

            Optional<WorkflowToolDTO> result = service.getToolBySlug("gmail-api-send-email");

            assertTrue(result.isPresent());
            assertEquals("gmail-api-send-email", result.get().slug());
            assertEquals("Send Email", result.get().name());
            assertEquals("POST", result.get().method());
            assertEquals("gmail-api", result.get().apiSlug());
            assertEquals("gmail", result.get().iconSlug());
        }

        @Test
        @DisplayName("should handle null tool name")
        void shouldHandleNullToolName() {
            Map<String, Object> row = new HashMap<>();
            row.put("id", UUID.randomUUID());
            row.put("api_id", UUID.randomUUID());
            row.put("description", "Description");
            row.put("method", "GET");
            row.put("tool_name_id", null);
            row.put("tool_name", null);
            row.put("api_slug", "api-slug");
            row.put("icon_slug", "mcp");

            when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of(row));

            Optional<WorkflowToolDTO> result = service.getToolBySlug("some-tool");

            assertTrue(result.isPresent());
            assertEquals("Unknown Tool", result.get().name());
        }
    }

    // ========================================================================
    // getToolDetailBySlug() tests
    // ========================================================================

    @Nested
    @DisplayName("getToolDetailBySlug()")
    class GetToolDetailBySlugTests {

        @Test
        @DisplayName("should return empty optional when tool not found")
        void shouldReturnEmptyOptionalWhenToolNotFound() {
            when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug("unknown-tool");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return tool details with parameters")
        void shouldReturnToolDetailsWithParameters() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = new HashMap<>();
            toolRow.put("id", toolId);
            toolRow.put("api_id", UUID.randomUUID());
            toolRow.put("description", "Send an email");
            toolRow.put("method", "POST");
            toolRow.put("tool_name_id", UUID.randomUUID().toString());
            toolRow.put("tool_slug", "gmail-api-send-email");
            toolRow.put("tool_name", "Send Email");
            toolRow.put("api_slug", "gmail-api");
            toolRow.put("icon_slug", "gmail");

            Map<String, Object> paramRow = new HashMap<>();
            paramRow.put("name", "to");
            paramRow.put("description", "Recipient email");
            paramRow.put("data_type", "string");
            paramRow.put("is_required", true);
            paramRow.put("parameter_type", "body");
            paramRow.put("default_value", null);
            paramRow.put("allowed_values", null);

            when(jdbcTemplate.queryForList(contains("api_tools"), eq("gmail-api-send-email")))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId)))
                .thenReturn(List.of(paramRow));
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), eq(toolId)))
                .thenReturn(List.of());

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug("gmail-api-send-email");

            assertTrue(result.isPresent());
            assertEquals("gmail-api-send-email", result.get().slug());
            assertEquals("Send Email", result.get().name());
            assertEquals(1, result.get().parameters().size());
            assertEquals("to", result.get().parameters().get(0).name());
        }

        @Test
        @DisplayName("should propagate defaultValue + allowedValues from DB to DTO (drives inspector dropdown)")
        void shouldPropagateDefaultAndAllowedValues() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = new HashMap<>();
            toolRow.put("id", toolId);
            toolRow.put("api_id", UUID.randomUUID());
            toolRow.put("description", "Chat completion");
            toolRow.put("method", "POST");
            toolRow.put("tool_name_id", UUID.randomUUID().toString());
            toolRow.put("tool_slug", "openai-chat-completion");
            toolRow.put("tool_name", "Chat Completion");
            toolRow.put("api_slug", "openai");
            toolRow.put("icon_slug", "openai");

            Map<String, Object> modelParam = new HashMap<>();
            modelParam.put("name", "model");
            modelParam.put("description", "Model ID");
            modelParam.put("data_type", "string");
            modelParam.put("is_required", true);
            modelParam.put("parameter_type", "body");
            modelParam.put("default_value", "gpt-4o");
            modelParam.put("allowed_values", "[\"gpt-4o\",\"gpt-4o-mini\",\"gpt-4.1\"]");

            Map<String, Object> tempParam = new HashMap<>();
            tempParam.put("name", "temperature");
            tempParam.put("description", "Sampling temperature");
            tempParam.put("data_type", "number");
            tempParam.put("is_required", false);
            tempParam.put("parameter_type", "body");
            tempParam.put("default_value", "1");
            tempParam.put("allowed_values", null);

            when(jdbcTemplate.queryForList(contains("api_tools"), eq("openai-chat-completion")))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId)))
                .thenReturn(List.of(modelParam, tempParam));
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), eq(toolId)))
                .thenReturn(List.of());

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug("openai-chat-completion");

            assertTrue(result.isPresent());
            List<WorkflowParameterDTO> params = result.get().parameters();
            assertEquals(2, params.size());

            WorkflowParameterDTO model = params.stream().filter(p -> "model".equals(p.name())).findFirst().orElseThrow();
            assertEquals("gpt-4o", model.defaultValue(), "Scalar default must reach the DTO so the picker pre-selects it");
            assertNotNull(model.allowedValues());
            assertEquals(3, model.allowedValues().size());
            assertTrue(model.allowedValues().contains("gpt-4o-mini"),
                    "allowed_values JSON column must be parsed back into a typed List<String>");

            WorkflowParameterDTO temperature = params.stream().filter(p -> "temperature".equals(p.name())).findFirst().orElseThrow();
            assertEquals("1", temperature.defaultValue());
            assertNull(temperature.allowedValues(),
                    "Numeric param without a closed enum must not produce an allowedValues array");
        }

        @Test
        @DisplayName("should propagate the Drive picker hint (extras.picker) from the DB to the DTO so the builder can render it")
        void shouldPropagateExtrasPickerHint() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = new HashMap<>();
            toolRow.put("id", toolId);
            toolRow.put("api_id", UUID.randomUUID());
            toolRow.put("description", "Read values");
            toolRow.put("method", "GET");
            toolRow.put("tool_name_id", UUID.randomUUID().toString());
            toolRow.put("tool_slug", "google-sheets-get-values");
            toolRow.put("tool_name", "Get Values");
            toolRow.put("api_slug", "google-sheets");
            toolRow.put("icon_slug", "googlesheets");

            Map<String, Object> sheetIdParam = new HashMap<>();
            sheetIdParam.put("name", "spreadsheetId");
            sheetIdParam.put("description", "The spreadsheet ID");
            sheetIdParam.put("data_type", "string");
            sheetIdParam.put("is_required", true);
            sheetIdParam.put("parameter_type", "path");
            sheetIdParam.put("default_value", null);
            sheetIdParam.put("allowed_values", null);
            sheetIdParam.put("extras",
                    "{\"picker\":{\"provider\":\"google-drive\",\"mimeType\":\"application/vnd.google-apps.spreadsheet\"}}");

            Map<String, Object> rangeParam = new HashMap<>();
            rangeParam.put("name", "range");
            rangeParam.put("description", "A1 range");
            rangeParam.put("data_type", "string");
            rangeParam.put("is_required", true);
            rangeParam.put("parameter_type", "path");
            rangeParam.put("default_value", null);
            rangeParam.put("allowed_values", null);
            rangeParam.put("extras", null);

            when(jdbcTemplate.queryForList(contains("api_tools"), eq("google-sheets-get-values")))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId)))
                .thenReturn(List.of(sheetIdParam, rangeParam));
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), eq(toolId)))
                .thenReturn(List.of());

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug("google-sheets-get-values");

            assertTrue(result.isPresent());
            List<WorkflowParameterDTO> params = result.get().parameters();

            WorkflowParameterDTO sheetId = params.stream()
                    .filter(p -> "spreadsheetId".equals(p.name())).findFirst().orElseThrow();
            assertNotNull(sheetId.extras(), "extras must reach the DTO so the builder can render the Drive picker");
            assertEquals("google-drive", sheetId.extras().path("picker").path("provider").asText());
            assertEquals("application/vnd.google-apps.spreadsheet",
                    sheetId.extras().path("picker").path("mimeType").asText());

            WorkflowParameterDTO range = params.stream()
                    .filter(p -> "range".equals(p.name())).findFirst().orElseThrow();
            assertNull(range.extras(), "a param without extras must yield null, not an empty node");
        }

        @Test
        @DisplayName("should return null allowedValues for malformed JSON (defensive - never crash the inspector)")
        void shouldReturnNullForMalformedAllowedValues() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = new HashMap<>();
            toolRow.put("id", toolId);
            toolRow.put("api_id", UUID.randomUUID());
            toolRow.put("description", "x");
            toolRow.put("method", "GET");
            toolRow.put("tool_name_id", UUID.randomUUID().toString());
            toolRow.put("tool_slug", "broken-tool");
            toolRow.put("tool_name", "Broken");
            toolRow.put("api_slug", "broken");
            toolRow.put("icon_slug", "broken");

            Map<String, Object> paramRow = new HashMap<>();
            paramRow.put("name", "x");
            paramRow.put("description", "x");
            paramRow.put("data_type", "string");
            paramRow.put("is_required", false);
            paramRow.put("parameter_type", "query");
            paramRow.put("default_value", null);
            paramRow.put("allowed_values", "{not valid json");

            when(jdbcTemplate.queryForList(contains("api_tools"), eq("broken-tool")))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId)))
                .thenReturn(List.of(paramRow));
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), eq(toolId)))
                .thenReturn(List.of());

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug("broken-tool");

            assertTrue(result.isPresent());
            assertNull(result.get().parameters().get(0).allowedValues(),
                    "Malformed allowed_values must downgrade to null, not throw - the inspector falls back to free-input");
        }

        @Test
        @DisplayName("should collapse two variants of the same credential to one DTO")
        void shouldCollapseVariantsToOneDto() {
            // Mirrors Ably: tool has two tool_credentials rows (basic_auth + bearer_token)
            // sharing credential_name="ably". The inspector should return ONE credential
            // entry - the wizard handles variant selection itself.
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = sampleToolRow(toolId, "ably-publish");

            Map<String, Object> basicAuth = credentialRow("ably", "basic_auth", "Ably", "basic_auth");
            Map<String, Object> bearer = credentialRow("ably", "bearer_token", "Ably", "bearer_token");

            when(jdbcTemplate.queryForList(contains("api_tools"), eq("ably-publish")))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), eq(toolId)))
                .thenReturn(List.of(basicAuth, bearer));

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug("ably-publish");

            assertTrue(result.isPresent());
            assertEquals(1, result.get().credentials().size(), "Two variants of one credential should collapse to one DTO");
            assertEquals("ably", result.get().credentials().get(0).credentialName());
        }

        @Test
        @DisplayName("should qualify credential detail lookup against catalog schema")
        void shouldQualifyCredentialDetailLookupAgainstCatalogSchema() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = sampleToolRow(toolId, "ably-publish");

            when(jdbcTemplate.queryForList(contains("api_tools"), eq("ably-publish")))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("catalog.tool_credentials"), eq(toolId)))
                .thenReturn(List.of(credentialRow("ably", "basic_auth", "Ably", "basic_auth")));

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug("ably-publish");

            assertTrue(result.isPresent());
            assertEquals(1, result.get().credentials().size());
            verify(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("FROM catalog.tool_credentials tc")
                    && sql.contains("LEFT JOIN catalog.credentials c ON tc.credential_id = c.id")),
                eq(toolId)
            );
        }

        @Test
        @DisplayName("should hide variant disabled by admin and keep the enabled one")
        void shouldHideDisabledVariant() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = sampleToolRow(toolId, "ably-publish");

            Map<String, Object> basicAuth = credentialRow("ably", "basic_auth", "Ably", "basic_auth");
            Map<String, Object> bearer = credentialRow("ably", "bearer_token", "Ably", "bearer_token");

            // Admin disabled bearer_token → filterAndDedupe picks basic_auth (first alphabetically).
            when(credentialClient.listPlatformCredentials())
                .thenReturn(List.of(disabledDto("ably", "bearer_token")));
            when(jdbcTemplate.queryForList(contains("api_tools"), eq("ably-publish")))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), eq(toolId)))
                .thenReturn(List.of(basicAuth, bearer));

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug("ably-publish");

            assertTrue(result.isPresent());
            assertEquals(1, result.get().credentials().size());
            assertEquals("basic_auth", result.get().credentials().get(0).authType(),
                "Only the enabled variant's row should remain");
        }

        @Test
        @DisplayName("should return zero credentials when every variant is disabled")
        void shouldReturnNoCredentialsWhenAllVariantsDisabled() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = sampleToolRow(toolId, "ably-publish");

            when(credentialClient.listPlatformCredentials()).thenReturn(List.of(
                disabledDto("ably", "basic_auth"),
                disabledDto("ably", "bearer_token")
            ));
            when(jdbcTemplate.queryForList(contains("api_tools"), eq("ably-publish")))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), eq(toolId)))
                .thenReturn(List.of(
                    credentialRow("ably", "basic_auth", "Ably", "basic_auth"),
                    credentialRow("ably", "bearer_token", "Ably", "bearer_token")
                ));

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug("ably-publish");

            assertTrue(result.isPresent(), "Tool is still returned - only the credential list is empty");
            assertTrue(result.get().credentials().isEmpty(),
                "Every variant disabled → no credential DTO left for the inspector to render");
        }

        @Test
        @DisplayName("should keep an integration visible when its only platform_credentials row is a phantom placeholder - regression for the May 2026 Salesforce incident where 81 OAuth2 APIs were silently hidden from the workflow inspector's credential picker")
        void shouldNotHideIntegrationOnPhantomPlaceholderRow() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = sampleToolRow(toolId, "salesforce-query");
            Map<String, Object> oauth2Cred = credentialRow("salesforce", "oauth2", "Salesforce", "oauth2");

            // Phantom placeholder from setEnabledForVariant: enabled=false but all hasX
            // flags null (no admin secret saved). Pre-fix the inspector would drop the
            // only variant → empty credentials list. Post-fix isConfigured()==false skips
            // the key and the variant survives.
            when(credentialClient.listPlatformCredentials())
                .thenReturn(List.of(phantomDto("salesforce", "oauth2")));
            when(jdbcTemplate.queryForList(contains("api_tools"), eq("salesforce-query")))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), eq(toolId)))
                .thenReturn(List.of(oauth2Cred));

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug("salesforce-query");

            assertTrue(result.isPresent());
            assertEquals(1, result.get().credentials().size(),
                "Phantom placeholder row must NOT drop the integration from the inspector");
            assertEquals("oauth2", result.get().credentials().get(0).authType());
        }

        @Test
        @DisplayName("should fail-open when auth-service is unreachable")
        void shouldFailOpenWhenAuthServiceUnreachable() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = sampleToolRow(toolId, "ably-publish");

            // Simulate auth-service throwing - the inspector must keep working, showing
            // every variant (after dedup) rather than hiding all credentials.
            when(credentialClient.listPlatformCredentials())
                .thenThrow(new RuntimeException("auth-service down"));
            when(jdbcTemplate.queryForList(contains("api_tools"), eq("ably-publish")))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), eq(toolId)))
                .thenReturn(List.of(
                    credentialRow("ably", "basic_auth", "Ably", "basic_auth"),
                    credentialRow("ably", "bearer_token", "Ably", "bearer_token")
                ));

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug("ably-publish");

            assertTrue(result.isPresent());
            assertEquals(1, result.get().credentials().size(), "Deduped even under fail-open");
        }

        @Test
        @DisplayName("should search by UUID when slug looks like UUID")
        void shouldSearchByUuidWhenSlugLooksLikeUuid() {
            UUID toolId = UUID.randomUUID();
            String uuidStr = toolId.toString();

            Map<String, Object> toolRow = new HashMap<>();
            toolRow.put("id", toolId);
            toolRow.put("api_id", UUID.randomUUID());
            toolRow.put("description", "Description");
            toolRow.put("method", "GET");
            toolRow.put("tool_name_id", null);
            toolRow.put("tool_slug", "generated-slug");
            toolRow.put("tool_name", "Tool Name");
            toolRow.put("api_slug", "api-slug");
            toolRow.put("icon_slug", "mcp");

            when(jdbcTemplate.queryForList(contains("at.id = ?::uuid"), eq(uuidStr)))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), eq(toolId)))
                .thenReturn(List.of());

            Optional<WorkflowToolDetailDTO> result = service.getToolDetailBySlug(uuidStr);

            assertTrue(result.isPresent());
        }
    }

    // ========================================================================
    // getApiBySlug() tests
    // ========================================================================

    @Nested
    @DisplayName("getApiBySlug()")
    class GetApiBySlugTests {

        @Test
        @DisplayName("should return empty optional when API not found")
        void shouldReturnEmptyOptionalWhenApiNotFound() {
            when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());

            Optional<WorkflowApiDTO> result = service.getApiBySlug("unknown-api");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return API when found")
        void shouldReturnApiWhenFound() {
            Map<String, Object> row = new HashMap<>();
            row.put("api_slug", "gmail-api");
            row.put("api_name", "Gmail API");
            row.put("description", "Gmail API description");
            row.put("tools_count", 5L);
            row.put("icon_slug", "gmail");

            when(jdbcTemplate.queryForList(anyString(), eq("gmail-api"))).thenReturn(List.of(row));

            Optional<WorkflowApiDTO> result = service.getApiBySlug("gmail-api");

            assertTrue(result.isPresent());
            assertEquals("gmail-api", result.get().slug());
            assertEquals("Gmail API", result.get().apiName());
            assertEquals(5, result.get().toolsCount());
            assertEquals("gmail", result.get().iconSlug());
        }
    }

    // ========================================================================
    // getApiIconSlug() tests
    // ========================================================================

    @Nested
    @DisplayName("getApiIconSlug()")
    class GetApiIconSlugTests {

        @Test
        @DisplayName("should return empty optional when API not found")
        void shouldReturnEmptyOptionalWhenApiNotFound() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of());

            Optional<String> result = service.getApiIconSlug("unknown-api");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return icon slug when found")
        void shouldReturnIconSlugWhenFound() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("gmail-api")))
                .thenReturn(List.of("gmail"));

            Optional<String> result = service.getApiIconSlug("gmail-api");

            assertTrue(result.isPresent());
            assertEquals("gmail", result.get());
        }
    }

    // ========================================================================
    // getToolIconSlug() tests
    // ========================================================================

    @Nested
    @DisplayName("getToolIconSlug()")
    class GetToolIconSlugTests {

        @Test
        @DisplayName("should return empty optional when tool not found")
        void shouldReturnEmptyOptionalWhenToolNotFound() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of());

            Optional<String> result = service.getToolIconSlug("unknown-tool");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return icon slug when found")
        void shouldReturnIconSlugWhenFound() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("gmail-api-send-email")))
                .thenReturn(List.of("gmail"));

            Optional<String> result = service.getToolIconSlug("gmail-api-send-email");

            assertTrue(result.isPresent());
            assertEquals("gmail", result.get());
        }
    }

    // ========================================================================
    // getToolsBatch() tests
    // ========================================================================

    @Nested
    @DisplayName("getToolsBatch()")
    class GetToolsBatchTests {

        @Test
        @DisplayName("should return empty map for null input")
        void shouldReturnEmptyMapForNullInput() {
            Map<String, WorkflowToolDetailDTO> result = service.getToolsBatch(null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty map for empty list")
        void shouldReturnEmptyMapForEmptyList() {
            Map<String, WorkflowToolDetailDTO> result = service.getToolsBatch(List.of());

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should filter out null and empty slugs")
        void shouldFilterOutNullAndEmptySlugs() {
            List<String> slugs = new ArrayList<>();
            slugs.add(null);
            slugs.add("");
            slugs.add("   ");

            Map<String, WorkflowToolDetailDTO> result = service.getToolsBatch(slugs);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should batch fetch multiple tools")
        void shouldBatchFetchMultipleTools() {
            UUID toolId1 = UUID.randomUUID();
            UUID toolId2 = UUID.randomUUID();

            Map<String, Object> toolRow1 = new HashMap<>();
            toolRow1.put("id", toolId1);
            toolRow1.put("api_id", UUID.randomUUID());
            toolRow1.put("description", "Tool 1");
            toolRow1.put("method", "GET");
            toolRow1.put("tool_name_id", null);
            toolRow1.put("tool_slug", "tool-1");
            toolRow1.put("tool_name", "Tool One");
            toolRow1.put("api_slug", "api-1");
            toolRow1.put("icon_slug", "icon1");

            Map<String, Object> toolRow2 = new HashMap<>();
            toolRow2.put("id", toolId2);
            toolRow2.put("api_id", UUID.randomUUID());
            toolRow2.put("description", "Tool 2");
            toolRow2.put("method", "POST");
            toolRow2.put("tool_name_id", null);
            toolRow2.put("tool_slug", "tool-2");
            toolRow2.put("tool_name", "Tool Two");
            toolRow2.put("api_slug", "api-2");
            toolRow2.put("icon_slug", "icon2");

            when(jdbcTemplate.queryForList(contains("api_tools"), any(Object[].class)))
                .thenReturn(List.of(toolRow1, toolRow2));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), any(Object[].class)))
                .thenReturn(List.of());

            Map<String, WorkflowToolDetailDTO> result = service.getToolsBatch(List.of("tool-1", "tool-2"));

            assertEquals(2, result.size());
            assertTrue(result.containsKey("tool-1"));
            assertTrue(result.containsKey("tool-2"));
            assertEquals("Tool One", result.get("tool-1").name());
            assertEquals("Tool Two", result.get("tool-2").name());
        }

        @Test
        @DisplayName("should apply disabled-variant filter and dedupe in batch path too")
        void shouldFilterAndDedupeInBatch() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = sampleToolRow(toolId, "ably-publish");

            Map<String, Object> basicCred = credentialRow("ably", "basic_auth", "Ably", "basic_auth");
            basicCred.put("api_tool_id", toolId);
            Map<String, Object> bearerCred = credentialRow("ably", "bearer_token", "Ably", "bearer_token");
            bearerCred.put("api_tool_id", toolId);

            // bearer disabled - batch path must also collapse to a single basic_auth entry.
            when(credentialClient.listPlatformCredentials())
                .thenReturn(List.of(disabledDto("ably", "bearer_token")));
            when(jdbcTemplate.queryForList(contains("api_tools"), any(Object[].class)))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), any(Object[].class)))
                .thenReturn(List.of(basicCred, bearerCred));

            Map<String, WorkflowToolDetailDTO> result = service.getToolsBatch(List.of("ably-publish"));

            assertEquals(1, result.size());
            WorkflowToolDetailDTO detail = result.get("ably-publish");
            assertNotNull(detail);
            assertEquals(1, detail.credentials().size(), "Batch path filters + dedupes like the single path");
            assertEquals("basic_auth", detail.credentials().get(0).authType());
            // One auth-service round trip for the whole batch - not one per tool.
            verify(credentialClient, times(1)).listPlatformCredentials();
        }

        @Test
        @DisplayName("should qualify batch credential lookup against catalog schema")
        void shouldQualifyBatchCredentialLookupAgainstCatalogSchema() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = sampleToolRow(toolId, "ably-publish");
            Map<String, Object> credential = credentialRow("ably", "basic_auth", "Ably", "basic_auth");
            credential.put("api_tool_id", toolId);

            when(jdbcTemplate.queryForList(contains("api_tools"), any(Object[].class)))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("catalog.tool_credentials"), any(Object[].class)))
                .thenReturn(List.of(credential));

            Map<String, WorkflowToolDetailDTO> result = service.getToolsBatch(List.of("ably-publish"));

            assertEquals(1, result.size());
            assertEquals(1, result.get("ably-publish").credentials().size());
            verify(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("FROM catalog.tool_credentials tc")
                    && sql.contains("LEFT JOIN catalog.credentials c ON tc.credential_id = c.id")),
                any(Object[].class)
            );
        }

        @Test
        @DisplayName("should keep distinct credential_names side-by-side when a tool requires both")
        void shouldKeepDistinctCredentialNames() {
            // Hypothetical tool that requires TWO different auth systems (not variants of one).
            // The dedup key is credential_name, so both must survive.
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = sampleToolRow(toolId, "multi-auth-tool");

            Map<String, Object> serviceA = credentialRow("service_a", "primary", "Service A", "api_key");
            serviceA.put("api_tool_id", toolId);
            Map<String, Object> serviceB = credentialRow("service_b", "primary", "Service B", "oauth2");
            serviceB.put("api_tool_id", toolId);

            when(jdbcTemplate.queryForList(contains("api_tools"), any(Object[].class)))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), any(Object[].class)))
                .thenReturn(List.of(serviceA, serviceB));

            Map<String, WorkflowToolDetailDTO> result = service.getToolsBatch(List.of("multi-auth-tool"));

            WorkflowToolDetailDTO detail = result.get("multi-auth-tool");
            assertNotNull(detail);
            assertEquals(2, detail.credentials().size(),
                "Distinct credential_names are different requirements - they must not collapse together");
        }

        @Test
        @DisplayName("should deduplicate slugs")
        void shouldDeduplicateSlugs() {
            UUID toolId = UUID.randomUUID();
            Map<String, Object> toolRow = new HashMap<>();
            toolRow.put("id", toolId);
            toolRow.put("api_id", UUID.randomUUID());
            toolRow.put("description", "Tool");
            toolRow.put("method", "GET");
            toolRow.put("tool_name_id", null);
            toolRow.put("tool_slug", "tool-1");
            toolRow.put("tool_name", "Tool One");
            toolRow.put("api_slug", "api");
            toolRow.put("icon_slug", "mcp");

            when(jdbcTemplate.queryForList(contains("api_tools"), any(Object[].class)))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), any(Object[].class)))
                .thenReturn(List.of());

            Map<String, WorkflowToolDetailDTO> result = service.getToolsBatch(
                List.of("tool-1", "tool-1", "tool-1")
            );

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("should resolve a tool referenced by api_tools.id (UUID) and key it by that UUID")
        void shouldResolveToolReferencedByUuid() {
            // Regression: a workflow plan mcp node may reference a tool by its
            // api_tools.id UUID instead of the apiSlug/toolSlug. The pre-fix batch
            // matched tool_slug ONLY, so a UUID-referenced node resolved to nothing
            // - it loaded with no credentials AND was silently dropped from the
            // acquired-application setup wizard (real prod case: a serpapi
            // google_events node referencing api_tools.id never surfaced).
            UUID toolUuid = UUID.fromString("3cb2d6d9-5a9e-4c31-890c-aac330d20033");
            Map<String, Object> toolRow = sampleToolRow(toolUuid, "serpapi-google-events");
            toolRow.put("icon_slug", "serpapi");

            Map<String, Object> cred = credentialRow("serpapi", "api_key", "SerpAPI", "api_key");
            cred.put("api_tool_id", toolUuid);

            when(jdbcTemplate.queryForList(contains("api_tools"), any(Object[].class)))
                .thenReturn(List.of(toolRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), any(Object[].class)))
                .thenReturn(List.of(cred));

            Map<String, WorkflowToolDetailDTO> result =
                service.getToolsBatch(List.of(toolUuid.toString()));

            // Keyed by the UUID the caller passed - this is the line that fails on the
            // pre-fix code, which only ever did result.put(toolSlug, dto).
            assertTrue(result.containsKey(toolUuid.toString()),
                "a UUID-referenced tool must be keyed by the id the caller sent");
            // ...and still keyed by tool_slug for slug-referencing callers.
            assertTrue(result.containsKey("serpapi-google-events"),
                "back-compat: tool also keyed by its tool_slug");
            WorkflowToolDetailDTO detail = result.get(toolUuid.toString());
            assertNotNull(detail);
            assertEquals(1, detail.credentials().size(),
                "credentials must resolve for a UUID-referenced tool (was empty pre-fix → wizard skipped it)");
            assertEquals("serpapi", detail.credentials().get(0).credentialName());

            // Assert the mechanism, not just the symptom: the UUID was routed to the
            // api_tools.id column. The full "WHERE at.id IN (" (with the space) also
            // guards the text-block trailing-whitespace trap that would otherwise emit
            // "WHEREat.id IN (" - a 500 against a real Postgres the mock can't see.
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, atLeastOnce()).queryForList(sqlCaptor.capture(), any(Object[].class));
            assertTrue(sqlCaptor.getAllValues().stream().anyMatch(s -> s.contains("WHERE at.id IN (")),
                "batch must query api_tools.id (with a well-formed WHERE clause) for UUID identifiers");
        }

        @Test
        @DisplayName("should resolve a mix of slug and UUID identifiers in one batch")
        void shouldResolveMixedSlugAndUuidIdentifiers() {
            UUID toolUuid = UUID.randomUUID();
            Map<String, Object> uuidRow = sampleToolRow(toolUuid, "serpapi-google-events");
            Map<String, Object> slugRow = sampleToolRow(UUID.randomUUID(), "apify-run-actor-sync");

            when(jdbcTemplate.queryForList(contains("api_tools"), any(Object[].class)))
                .thenReturn(List.of(uuidRow, slugRow));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), any(Object[].class)))
                .thenReturn(List.of());

            Map<String, WorkflowToolDetailDTO> result = service.getToolsBatch(
                List.of("apify-run-actor-sync", toolUuid.toString()));

            assertTrue(result.containsKey("apify-run-actor-sync"), "slug identifier resolves");
            assertTrue(result.containsKey(toolUuid.toString()), "uuid identifier resolves under the id sent");
            assertTrue(result.containsKey("serpapi-google-events"), "uuid-referenced tool also keyed by slug");
        }
    }

    // ========================================================================
    // Helper method tests (tested indirectly)
    // ========================================================================

    @Nested
    @DisplayName("Helper methods")
    class HelperMethodTests {

        // Helper factories for the Ably-style variant tests.
        // Kept inside the nested class is noisy - hoist them to the outer class below.

        @Test
        @DisplayName("should handle null icon slug with default")
        void shouldHandleNullIconSlugWithDefault() {
            Map<String, Object> row = new HashMap<>();
            row.put("api_slug", "api");
            row.put("api_name", "API");
            row.put("description", "Description");
            row.put("tools_count", 0L);
            row.put("icon_slug", "mcp"); // COALESCE in SQL returns 'mcp' for null

            when(jdbcTemplate.queryForList(anyString(), eq("api"))).thenReturn(List.of(row));

            Optional<WorkflowApiDTO> result = service.getApiBySlug("api");

            assertTrue(result.isPresent());
            assertEquals("mcp", result.get().iconSlug());
        }
    }

    /**
     * Regression: the tool-credential queries MUST schema-qualify catalog tables. An
     * unqualified {@code credentials} resolves - under the monolith's multi-schema
     * search_path - to {@code auth.credentials}/{@code orchestrator.credentials}, whose
     * {@code id} is a {@code bigint}, while {@code catalog.credentials.id} is a {@code uuid}.
     * The resulting {@code operator does not exist: uuid = bigint} threw a 500 from BOTH
     * {@code getToolDetailBySlug} and {@code getToolsBatch}, which silently zeroed every
     * tool's credential list - so the application "missing credentials" surface (and the
     * setup wizard) showed nothing. Captures the SQL handed to the JdbcTemplate and asserts
     * the catalog qualification is present (fails on the pre-fix unqualified SQL).
     */
    @Nested
    @DisplayName("Tool-credential SQL is schema-qualified (catalog.credentials, not the bigint-id sibling)")
    class CredentialSqlSchemaQualification {

        private Map<String, Object> toolRow(UUID toolId) {
            Map<String, Object> r = new HashMap<>();
            r.put("id", toolId);
            r.put("api_id", UUID.randomUUID());
            r.put("description", "List bases");
            r.put("method", "GET");
            r.put("tool_name_id", UUID.randomUUID().toString());
            r.put("tool_slug", "airtable-list-bases");
            r.put("tool_name", "List Bases");
            r.put("api_slug", "airtable");
            r.put("icon_slug", "airtable");
            return r;
        }

        private void assertCredentialSqlQualified(String credSql) {
            assertNotNull(credSql, "a tool_credentials query should have been issued");
            assertTrue(credSql.contains("catalog.tool_credentials"),
                "tool_credentials must be schema-qualified: " + credSql);
            assertTrue(credSql.contains("catalog.credentials"),
                "the credentials JOIN must be catalog-qualified (auth/orchestrator.credentials "
                    + "have a bigint id → uuid=bigint 500): " + credSql);
        }

        @Test
        @DisplayName("getToolDetailBySlug joins catalog.credentials")
        void singleToolCredentialSqlIsSchemaQualified() {
            UUID toolId = UUID.randomUUID();
            when(jdbcTemplate.queryForList(contains("api_tools"), eq("airtable-list-bases")))
                .thenReturn(List.of(toolRow(toolId)));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), eq(toolId))).thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), eq(toolId))).thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), eq(toolId))).thenReturn(List.of());

            service.getToolDetailBySlug("airtable-list-bases");

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, atLeastOnce()).queryForList(sql.capture(), eq(toolId));
            String credSql = sql.getAllValues().stream()
                .filter(s -> s.contains("tool_credentials"))
                .findFirst().orElse(null);
            assertCredentialSqlQualified(credSql);
        }

        @Test
        @DisplayName("getToolsBatch joins catalog.credentials")
        void batchToolCredentialSqlIsSchemaQualified() {
            UUID toolId = UUID.randomUUID();
            when(jdbcTemplate.queryForList(contains("api_tools"), any(Object[].class)))
                .thenReturn(List.of(toolRow(toolId)));
            when(jdbcTemplate.queryForList(contains("api_tool_parameters"), any(Object[].class))).thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_responses"), any(Object[].class))).thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("tool_credentials"), any(Object[].class))).thenReturn(List.of());

            service.getToolsBatch(List.of("airtable-list-bases"));

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, atLeastOnce()).queryForList(sql.capture(), any(Object[].class));
            String credSql = sql.getAllValues().stream()
                .filter(s -> s.contains("tool_credentials"))
                .findFirst().orElse(null);
            assertCredentialSqlQualified(credSql);
        }
    }
}
