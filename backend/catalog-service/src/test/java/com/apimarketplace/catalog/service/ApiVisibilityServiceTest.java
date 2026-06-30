package com.apimarketplace.catalog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiVisibilityServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ApiVisibilityService service;

    @BeforeEach
    void setUp() {
        service = new ApiVisibilityService(jdbcTemplate);
    }

    @Test
    @DisplayName("listIntegrations returns mapped data from query")
    void listIntegrations() {
        UUID apiId = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("api_id", apiId);
        row.put("api_name", "Google Sheets");
        row.put("icon_slug", "googlesheets");
        row.put("auth_type", "oauth2");
        row.put("credential_name", "googlesheets");
        row.put("is_active", true);
        row.put("tool_count", 12L);
        row.put("active_tool_count", 5L);
        row.put("category_name", "Productivity");

        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));

        List<Map<String, Object>> result = service.listIntegrations();

        assertThat(result).hasSize(1);
        Map<String, Object> item = result.get(0);
        assertThat(item.get("apiId")).isEqualTo(apiId.toString());
        assertThat(item.get("apiName")).isEqualTo("Google Sheets");
        assertThat(item.get("iconSlug")).isEqualTo("googlesheets");
        assertThat(item.get("authType")).isEqualTo("oauth2");
        assertThat(item.get("isActive")).isEqualTo(true);
        assertThat(item.get("toolCount")).isEqualTo(12);
        assertThat(item.get("activeToolCount")).isEqualTo(5);
        assertThat(item.get("category")).isEqualTo("Productivity");
    }

    @Test
    @DisplayName("toggleApi enables API and cascades to tools")
    void toggleApiEnable() {
        UUID apiId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(true), eq(apiId))).thenReturn(1);

        service.toggleApi(apiId, true);

        verify(jdbcTemplate, times(2)).update(anyString(), eq(true), eq(apiId));
    }

    @Test
    @DisplayName("toggleApi throws when API not found")
    void toggleApiNotFound() {
        UUID apiId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(false), eq(apiId))).thenReturn(0);

        assertThatThrownBy(() -> service.toggleApi(apiId, false))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(apiId.toString());
    }

    @Test
    @DisplayName("listApiTools returns mapped tool data including description")
    void listApiTools() {
        UUID apiId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("id", toolId);
        row.put("tool_name", "Create Spreadsheet");
        row.put("tool_slug", "create_spreadsheet");
        row.put("description", "Creates a new blank spreadsheet.");
        row.put("is_active", true);
        row.put("method", "POST");

        when(jdbcTemplate.queryForList(anyString(), eq(apiId))).thenReturn(List.of(row));

        List<Map<String, Object>> result = service.listApiTools(apiId);

        assertThat(result).hasSize(1);
        Map<String, Object> item = result.get(0);
        assertThat(item.get("toolId")).isEqualTo(toolId.toString());
        assertThat(item.get("toolName")).isEqualTo("Create Spreadsheet");
        assertThat(item.get("toolSlug")).isEqualTo("create_spreadsheet");
        assertThat(item.get("description")).isEqualTo("Creates a new blank spreadsheet.");
        assertThat(item.get("isActive")).isEqualTo(true);
        assertThat(item.get("method")).isEqualTo("POST");
    }

    @Test
    @DisplayName("toggleTool updates tool is_active flag")
    void toggleTool() {
        UUID toolId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(false), eq(toolId))).thenReturn(1);

        service.toggleTool(toolId, false);

        verify(jdbcTemplate).update(anyString(), eq(false), eq(toolId));
    }

    @Test
    @DisplayName("toggleTool throws when tool not found")
    void toggleToolNotFound() {
        UUID toolId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), eq(true), eq(toolId))).thenReturn(0);

        assertThatThrownBy(() -> service.toggleTool(toolId, true))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(toolId.toString());
    }

    // ========== credentialFields edge cases ==========

    @Test
    @DisplayName("listIntegrations includes credentialFields when present")
    void listIntegrationsWithCredentialFields() {
        UUID apiId = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("api_id", apiId);
        row.put("api_name", "AWS S3");
        row.put("icon_slug", "amazonaws");
        row.put("auth_type", "custom");
        row.put("credential_name", "amazonaws");
        row.put("is_active", false);
        row.put("tool_count", 25L);
        row.put("active_tool_count", 0L);
        row.put("category_name", "Cloud");
        row.put("credential_fields", "[{\"name\":\"access_key_id\",\"type\":\"string\",\"required\":true}]");

        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));

        List<Map<String, Object>> result = service.listIntegrations();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("credentialFields")).isEqualTo(
                "[{\"name\":\"access_key_id\",\"type\":\"string\",\"required\":true}]");
    }

    @Test
    @DisplayName("listIntegrations returns null credentialFields when no credential match")
    void listIntegrationsNullCredentialFields() {
        UUID apiId = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("api_id", apiId);
        row.put("api_name", "Tavily");
        row.put("icon_slug", null);
        row.put("auth_type", "api_key");
        row.put("credential_name", "tavily");
        row.put("is_active", false);
        row.put("tool_count", 2L);
        row.put("active_tool_count", 0L);
        row.put("category_name", null);
        row.put("credential_fields", null);

        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));

        List<Map<String, Object>> result = service.listIntegrations();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("credentialFields")).isNull();
    }

    @Test
    @DisplayName("listIntegrations handles empty result set")
    void listIntegrationsEmpty() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        List<Map<String, Object>> result = service.listIntegrations();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listIntegrations handles null tool_count gracefully")
    void listIntegrationsNullToolCount() {
        UUID apiId = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("api_id", apiId);
        row.put("api_name", "No Tools API");
        row.put("icon_slug", "notool");
        row.put("auth_type", "none");
        row.put("credential_name", null);
        row.put("is_active", true);
        row.put("tool_count", 0L);
        row.put("active_tool_count", 0L);
        row.put("category_name", null);
        row.put("credential_fields", null);

        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));

        List<Map<String, Object>> result = service.listIntegrations();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("toolCount")).isEqualTo(0);
        assertThat(result.get(0).get("activeToolCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("listApiTools handles empty tool list")
    void listApiToolsEmpty() {
        UUID apiId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(anyString(), eq(apiId))).thenReturn(List.of());

        List<Map<String, Object>> result = service.listApiTools(apiId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listApiTools handles null method field")
    void listApiToolsNullMethod() {
        UUID apiId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("id", toolId);
        row.put("tool_name", "SomeTool");
        row.put("tool_slug", "some_tool");
        row.put("is_active", false);
        row.put("method", null);

        when(jdbcTemplate.queryForList(anyString(), eq(apiId))).thenReturn(List.of(row));

        List<Map<String, Object>> result = service.listApiTools(apiId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("method")).isNull();
        assertThat(result.get(0).get("isActive")).isEqualTo(false);
    }
}
