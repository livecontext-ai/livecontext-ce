package com.apimarketplace.agent.tools;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression for the async fail-open in {@link ToolsRegistrationService#executeToolAsync}:
 * the worker thread bound only the org <em>id</em> (2-arg {@code runWithOrgScope}), leaving
 * {@link TenantResolver#currentRequestOrganizationRole()} null on the worker. Every
 * service-layer role gate read from {@code TenantResolver} on the async tool path (the agent
 * VIEWER write gate, the {@code OrgAccessGuard} deny-list, the {@code getAgent} self-heal)
 * therefore silently fell to its null-role / fail-open branch. The fix binds the
 * gateway-validated {@code orgRole} from the {@code ToolExecutionContext} onto the worker too.
 */
@DisplayName("ToolsRegistrationService.executeToolAsync - worker-thread org scope")
class ToolsRegistrationServiceAsyncScopeTest {

    /** A provider that records the org id + role visible from the worker thread. */
    private static final class CapturingProvider implements ToolsProvider {
        final AtomicReference<String> seenRole = new AtomicReference<>("<<unset>>");
        final AtomicReference<String> seenOrgId = new AtomicReference<>("<<unset>>");

        @Override public ToolCategory getCategory() { return null; }
        @Override public List<AgentToolDefinition> getTools() { return List.of(); }
        @Override public boolean canHandle(String toolName) { return true; }

        @Override
        public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
            seenRole.set(TenantResolver.currentRequestOrganizationRole());
            seenOrgId.set(TenantResolver.currentRequestOrganizationId());
            return ToolExecutionResult.success(Map.of());
        }
    }

    private static ToolsProvider.ToolExecutionContext contextWith(String orgId, String orgRole) {
        // Canonical record constructor: (tenantId, credentials, metadata, approvedServices,
        //                                 viewingWorkflowId, viewingWorkflowName, orgId, orgRole)
        return new ToolsProvider.ToolExecutionContext(
                "tenant-x", Map.of(), Map.of(), Set.of(), null, null, orgId, orgRole);
    }

    @Test
    @DisplayName("binds BOTH org id AND org role onto the async worker thread (so role gates are honored, not fail-open)")
    void bindsOrgIdAndRoleOnWorkerThread() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        // validator + slimCoercer + interceptors null → executeToolAsync is a clean
        // alias/coerce/validate pass-through straight to the provider on the worker.
        ToolsRegistrationService service = new ToolsRegistrationService(
                mock(AgentToolRegistry.class), List.of(provider), null, null, null);

        service.executeToolAsync("any_tool", Map.of(), contextWith("org-42", "VIEWER"))
                .get(5, TimeUnit.SECONDS);

        // Pre-fix the worker saw role=null (only the id was bound) → fail-open.
        assertThat(provider.seenRole.get()).isEqualTo("VIEWER");
        assertThat(provider.seenOrgId.get()).isEqualTo("org-42");
    }

    @Test
    @DisplayName("a null context role binds null (fail-open preserved for legacy/system callers - no NPE)")
    void nullRoleBindsNullWithoutError() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        ToolsRegistrationService service = new ToolsRegistrationService(
                mock(AgentToolRegistry.class), List.of(provider), null, null, null);

        service.executeToolAsync("any_tool", Map.of(), contextWith("org-42", null))
                .get(5, TimeUnit.SECONDS);

        assertThat(provider.seenRole.get()).isNull();
        assertThat(provider.seenOrgId.get()).isEqualTo("org-42");
    }
}
