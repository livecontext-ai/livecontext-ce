package com.apimarketplace.catalog.controllers.tools;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.catalog.tools.CatalogExecuteModule;
import com.apimarketplace.catalog.tools.CatalogHelpModule;
import com.apimarketplace.catalog.tools.CatalogRegisterModule;
import com.apimarketplace.catalog.tools.CatalogSchemaModule;
import com.apimarketplace.catalog.tools.CatalogSearchModule;
import com.apimarketplace.catalog.tools.CatalogToolsProvider;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * LC-055 (security audit 2026-08-13), catalog half: the receiving end of the per-tool access
 * modes an API key's scopes derive.
 *
 * <p>The orchestrator's MCP surface derives {@code <category>AccessMode} from the calling key's
 * scopes and puts them in the {@code /api/agent-tools/execute} body. This controller rebuilds its
 * {@code ToolExecutionContext} credentials key by key, so a key it does not copy simply does not
 * exist at the far end, and {@code ToolAccessControl} reads an absent mode as UNRESTRICTED. The
 * pre-fix controller copied a FIXED list of seven categories that did not include
 * {@code catalogAccessMode}, which is the one gating {@code catalog(action='execute')}: the action
 * that spends the owner's stored OAuth credentials against a third party.
 *
 * <p>{@link EnforcementReachesTheRealModule} drives the real {@link CatalogToolsProvider} and the
 * real {@link CatalogExecuteModule}, so it asserts an actual refusal rather than that a stub was
 * handed the value it wanted. On the pre-fix controller it fails with
 * {@code MISSING_PARAMETER} ({@code tool_id is required}), which is exactly the point: execution
 * had gone PAST the access gate.
 */
@DisplayName("ServiceToolsController (catalog) - per-tool access modes survive the service hop")
class ServiceToolsControllerAccessModeForwardingTest {

    /** Captures the context that actually reached the tool, rather than what was sent. */
    private static class CapturingProvider implements ToolsProvider {
        ToolExecutionContext lastContext;

        @Override
        public ToolCategory getCategory() {
            return ToolCategory.CATALOG;
        }

        @Override
        public List<AgentToolDefinition> getTools() {
            return List.of(AgentToolDefinition.builder()
                    .name("catalog")
                    .description("catalog tool")
                    .category(ToolCategory.CATALOG)
                    .parameters(List.of(ToolParameter.builder()
                            .name("action").type("string").description("action").required(true).build()))
                    .requiredParameters(List.of("action"))
                    .build());
        }

        @Override
        public ToolExecutionResult execute(String name, Map<String, Object> parameters,
                                            ToolExecutionContext context) {
            lastContext = context;
            return ToolExecutionResult.success(Map.of("ran", name));
        }
    }

    private static MockHttpServletRequest tenantRequest() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "tenant-1");
        return httpRequest;
    }

    private static Map<String, Object> bodyWithModes(Map<String, Object> modes) {
        Map<String, Object> body = new HashMap<>();
        body.put("tool", "catalog");
        body.put("parameters", Map.of("action", "list"));
        body.putAll(modes);
        return body;
    }

    private static Map<String, Object> credentialsAfterExecute(Map<String, Object> modes) {
        CapturingProvider provider = new CapturingProvider();
        ServiceToolsController controller = new ServiceToolsController(List.of(provider));
        controller.executeTool(tenantRequest(), bodyWithModes(modes));
        return provider.lastContext.credentials();
    }

    @Test
    @DisplayName("catalogAccessMode reaches the tool, the category the fixed seven-key list dropped")
    void catalogAccessModeReachesTheTool() {
        assertThat(credentialsAfterExecute(Map.of("catalogAccessMode", "read")))
                .containsEntry("catalogAccessMode", "read");
    }

    @Test
    @DisplayName("every category ToolAccessControl gates survives the hop, not only the seven that were listed")
    void everyGatedCategorySurvivesTheHop() {
        Map<String, Object> allModes = new HashMap<>();
        for (String category : List.of("table", "workflow", "interface", "agent", "application",
                "skill", "file", "catalog", "web_search", "agent_browse", "generation")) {
            allModes.put(category + "AccessMode", "read");
        }

        Map<String, Object> credentials = credentialsAfterExecute(allModes);

        assertThat(credentials).containsAllEntriesOf(allModes);
    }

    @Test
    @DisplayName("a body key that is not an access mode is still not copied into credentials")
    void nonAccessModeBodyKeysAreNotCopied() {
        Map<String, Object> body = bodyWithModes(Map.of("catalogAccessMode", "read"));
        body.put("orgRole", "OWNER");
        body.put("AccessMode", "write");

        CapturingProvider provider = new CapturingProvider();
        new ServiceToolsController(List.of(provider)).executeTool(tenantRequest(), body);

        assertThat(provider.lastContext.credentials())
                .containsEntry("catalogAccessMode", "read")
                .doesNotContainKeys("orgRole", "AccessMode");
    }

    @Nested
    @DisplayName("the forwarded mode is enforced by the real module, not just carried")
    class EnforcementReachesTheRealModule {

        private ServiceToolsController controllerWithRealCatalogTool() {
            CatalogExecuteModule executeModule =
                    new CatalogExecuteModule(new ObjectMapper(), mock(CredentialClient.class));
            CatalogToolsProvider provider = new CatalogToolsProvider(
                    mock(CatalogSearchModule.class),
                    executeModule,
                    mock(CatalogSchemaModule.class),
                    mock(CatalogRegisterModule.class),
                    new CatalogHelpModule());
            return new ServiceToolsController(List.of(provider));
        }

        private Map<String, Object> execute(Map<String, Object> body) {
            ResponseEntity<Map<String, Object>> response =
                    controllerWithRealCatalogTool().executeTool(tenantRequest(), body);
            return response.getBody();
        }

        @Test
        @DisplayName("a read-only catalog key is refused catalog(action='execute'), the call that spends stored credentials")
        void readOnlyKeyIsRefusedCatalogExecute() {
            Map<String, Object> body = new HashMap<>();
            body.put("tool", "catalog");
            body.put("parameters", Map.of("action", "execute"));
            body.put("catalogAccessMode", "read");

            Map<String, Object> result = execute(body);

            assertThat(result).containsEntry("success", false);
            assertThat(String.valueOf(result.get("error")))
                    .contains("catalog is configured as read-only");
        }

        @Test
        @DisplayName("a write-mode catalog key is NOT refused, so the gate denies on the mode and not on its presence")
        void writeModeKeyIsNotRefused() {
            Map<String, Object> body = new HashMap<>();
            body.put("tool", "catalog");
            body.put("parameters", Map.of("action", "execute"));
            body.put("catalogAccessMode", "write");

            Map<String, Object> result = execute(body);

            // Execution proceeds past the gate and stops on the missing tool_id instead.
            assertThat(String.valueOf(result.get("error")))
                    .doesNotContain("read-only")
                    .contains("tool_id is required");
        }

        @Test
        @DisplayName("a read-only catalog key may still run the read action it was granted")
        void readOnlyKeyMayStillRunAReadAction() {
            Map<String, Object> body = new HashMap<>();
            body.put("tool", "catalog");
            body.put("parameters", Map.of("action", "help"));
            body.put("catalogAccessMode", "read");

            Map<String, Object> result = execute(body);

            assertThat(result).containsEntry("success", true);
        }
    }
}
