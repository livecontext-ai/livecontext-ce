package com.apimarketplace.interfaces.tools;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.config.InterfaceAgentDefaultsConfig;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.service.InterfaceService;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceToolsProvider")
class InterfaceToolsProviderTest {

    @Mock private InterfaceService interfaceService;
    @Mock private PublicationClient publicationClient;
    private InterfaceToolsProvider provider;

    private static final String TENANT = "test-tenant";

    @BeforeEach
    void setUp() {
        InterfaceCrudModule crudModule = new InterfaceCrudModule(interfaceService, new InterfaceAgentDefaultsConfig());
        InterfaceHelpModule helpModule = new InterfaceHelpModule();
        InterfacePublishModule publishModule = new InterfacePublishModule(publicationClient);
        provider = new InterfaceToolsProvider(crudModule, helpModule, publishModule);
    }

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.of(TENANT);
    }

    // ==================== getCategory ====================

    @Test
    @DisplayName("Should return INTERFACE category")
    void shouldReturnInterfaceCategory() {
        assertThat(provider.getCategory()).isEqualTo(ToolCategory.INTERFACE);
    }

    // ==================== getTools ====================

    @Test
    @DisplayName("Should expose one unified interface tool")
    void shouldExposeOneUnifiedTool() {
        List<AgentToolDefinition> tools = provider.getTools();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("interface");
    }

    @Test
    @DisplayName("Tool definition should have correct schema")
    void toolDefinitionShouldHaveCorrectSchema() {
        AgentToolDefinition tool = provider.getTools().get(0);
        assertThat(tool.category()).isEqualTo(ToolCategory.INTERFACE);
        assertThat(tool.requiredParameters()).containsExactly("action");
        assertThat(tool.parameters()).extracting("name")
            .contains("action", "interface_id", "name", "description",
                       "html_template", "css_template", "js_template", "limit", "offset",
                       "target", "edits", "replace_all");
    }

    @Test
    @DisplayName("Action parameter should have enum values")
    void actionParamShouldHaveEnumValues() {
        AgentToolDefinition tool = provider.getTools().get(0);
        var actionParam = tool.parameters().stream()
            .filter(p -> "action".equals(p.name()))
            .findFirst().orElseThrow();
        assertThat(actionParam.enumValues()).containsExactly(
            "create", "get", "list", "update", "patch", "delete", "publish", "unpublish", "help");
    }

    // ==================== execute routing ====================

    @Test
    @DisplayName("Should reject unknown tool name")
    void shouldRejectUnknownToolName() {
        ToolExecutionResult result = provider.execute("unknown", Map.of("action", "help"), ctx());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown tool");
    }

    @Test
    @DisplayName("Should reject missing action")
    void shouldRejectMissingAction() {
        ToolExecutionResult result = provider.execute("interface", Map.of(), ctx());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("action is required");
    }

    @Test
    @DisplayName("Should reject blank action")
    void shouldRejectBlankAction() {
        ToolExecutionResult result = provider.execute("interface", Map.of("action", "  "), ctx());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("action is required");
    }

    @Test
    @DisplayName("Should reject invalid action")
    void shouldRejectInvalidAction() {
        ToolExecutionResult result = provider.execute("interface", Map.of("action", "invalid_action"), ctx());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid action");
    }

    @Test
    @DisplayName("Should reject null tenantId for non-help action")
    void shouldRejectNullTenantForNonHelp() {
        ToolExecutionResult result = provider.execute("interface",
            Map.of("action", "list"), ToolExecutionContext.empty());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("tenantId is required");
    }

    @Test
    @DisplayName("Should allow null tenantId for help action")
    void shouldAllowNullTenantForHelp() {
        ToolExecutionResult result = provider.execute("interface",
            Map.of("action", "help"), ToolExecutionContext.empty());
        assertThat(result.success()).isTrue();
    }

    // ==================== routing to modules ====================

    @Test
    @DisplayName("Should route help to HelpModule")
    void shouldRouteHelpToHelpModule() {
        ToolExecutionResult result = provider.execute("interface", Map.of("action", "help"), ctx());
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data).containsKey("1_concept");
    }

    @Test
    @DisplayName("Should route list to CrudModule")
    void shouldRouteListToCrudModule() {
        when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
            .thenReturn(List.of());

        ToolExecutionResult result = provider.execute("interface", Map.of("action", "list"), ctx());
        assertThat(result.success()).isTrue();
        verify(interfaceService).listInterfaces(eq(TENANT), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("Should route create to CrudModule")
    void shouldRouteCreateToCrudModule() {
        InterfaceEntity entity = fakeEntity(UUID.randomUUID(), "TestUI");
        when(interfaceService.createInterface(any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
            .thenReturn(entity);

        Map<String, Object> params = new HashMap<>();
        params.put("action", "create");
        params.put("name", "TestUI");
        params.put("html_template", "<div>Hello</div>");

        ToolExecutionResult result = provider.execute("interface", params, ctx());
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("Should route get to CrudModule")
    void shouldRouteGetToCrudModule() {
        UUID id = UUID.randomUUID();
        InterfaceEntity entity = fakeEntity(id, "MyUI");
        when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(entity));

        Map<String, Object> params = new HashMap<>();
        params.put("action", "get");
        params.put("interface_id", id.toString());

        ToolExecutionResult result = provider.execute("interface", params, ctx());
        assertThat(result.success()).isTrue();
    }

    // ==================== helpers ====================

    private InterfaceEntity fakeEntity(UUID id, String name) {
        InterfaceEntity entity = new InterfaceEntity();
        entity.setId(id);
        entity.setTenantId(TENANT);
        entity.setName(name);
        entity.setDescription("Test description");
        entity.setHtmlTemplate("<div>Test</div>");
        entity.setCssTemplate("body { margin: 0; }");
        entity.setJsTemplate("// js");
        entity.setIsPublic(false);
        entity.setIsActive(true);
        entity.setInterfaceType("html");
        entity.setTemplateVariables(List.of());
        return entity;
    }
}
