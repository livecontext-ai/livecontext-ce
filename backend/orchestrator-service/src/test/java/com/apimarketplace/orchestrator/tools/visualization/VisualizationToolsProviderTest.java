package com.apimarketplace.orchestrator.tools.visualization;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link VisualizationToolsProvider}. This class had ZERO coverage
 * before the 2026-06-23 test-strategy audit.
 * <p>
 * The {@code visualize} tool is deprecated and NO LONGER exposed to the LLM (the
 * model emits {@code [visualize:type:id]} markers instead), so the only LIVE
 * responsibilities are: (1) the deprecation contract on {@code getTools()}/
 * {@code execute()}, and (2) the per-conversation anti-duplicate cache
 * ({@code markAsVisualized}/{@code wasAlreadyVisualized}) with its 30-minute
 * expiry. The private {@code visualize*} helpers are unreachable through the public
 * surface (dead code reachable only via reflection) and are intentionally not
 * exercised here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VisualizationToolsProvider")
class VisualizationToolsProviderTest {

    private static final String TENANT = "tenant-1";

    @Mock private WorkflowManagementService workflowManagementService;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private AgentClient agentClient;
    @InjectMocks private VisualizationToolsProvider provider;

    // ── deprecation contract ────────────────────────────────────────────

    @Test
    @DisplayName("category is VISUALIZATION and no tools are exposed to the LLM")
    void metadata() {
        assertThat(provider.getCategory()).isEqualTo(ToolCategory.VISUALIZATION);
        assertThat(provider.getTools()).isEmpty();
    }

    @Test
    @DisplayName("execute always fails with the deprecation guidance and never touches any collaborator")
    void executeIsDeprecated() {
        ToolExecutionResult r = provider.execute(
                "visualize", Map.of("type", "workflow", "id", "abc-123"), ToolExecutionContext.of(TENANT));

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(r.error()).contains("[visualize:").contains("deprecated");
        verifyNoInteractions(workflowManagementService, dataSourceClient, interfaceClient, agentClient);
    }

    @Test
    @DisplayName("execute with null arguments does not throw (returns the deprecation failure)")
    void executeNullArgsNoNpe() {
        ToolExecutionResult r = provider.execute(null, null, null);
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
    }

    // ── anti-duplicate cache ────────────────────────────────────────────

    @Test
    @DisplayName("mark then check round-trips true for the same conversation + key")
    void markThenCheckRoundTrip() {
        provider.markAsVisualized("conv-1", "workflow", "id-1");
        assertThat(provider.wasAlreadyVisualized("conv-1", "workflow", "id-1")).isTrue();
    }

    @Test
    @DisplayName("the cache isolates by key and by conversation")
    void cacheIsolation() {
        provider.markAsVisualized("conv-A", "workflow", "id-1");

        assertThat(provider.wasAlreadyVisualized("conv-A", "workflow", "id-1")).isTrue();
        assertThat(provider.wasAlreadyVisualized("conv-A", "workflow", "id-2")).isFalse(); // different key
        assertThat(provider.wasAlreadyVisualized("conv-A", "table", "id-1")).isFalse();    // different type
        assertThat(provider.wasAlreadyVisualized("conv-B", "workflow", "id-1")).isFalse(); // different conversation
    }

    @Test
    @DisplayName("a null conversationId is a no-op on mark and always false on check")
    void nullConversationGuards() {
        provider.markAsVisualized(null, "workflow", "id-1"); // must not throw
        assertThat(provider.wasAlreadyVisualized(null, "workflow", "id-1")).isFalse();
    }

    @Test
    @DisplayName("an entry idle past the 30-minute expiry is evicted on the next check; a fresh entry survives")
    void cacheExpiry() {
        provider.markAsVisualized("stale-conv", "workflow", "id-1");
        provider.markAsVisualized("fresh-conv", "table", "9");
        assertThat(provider.wasAlreadyVisualized("stale-conv", "workflow", "id-1")).isTrue(); // sanity, still fresh

        // Age the stale conversation's last-access past CACHE_EXPIRY_MS (30 min).
        @SuppressWarnings("unchecked")
        Map<String, Long> accessTime =
                (Map<String, Long>) ReflectionTestUtils.getField(provider, "cacheAccessTime");
        accessTime.put("stale-conv", System.currentTimeMillis() - (31L * 60 * 1000));

        // The next check triggers cleanupExpiredCache(): the stale entry is gone, the fresh one stays.
        assertThat(provider.wasAlreadyVisualized("stale-conv", "workflow", "id-1")).isFalse();
        assertThat(provider.wasAlreadyVisualized("fresh-conv", "table", "9")).isTrue();
    }

    @Test
    @DisplayName("re-marking a conversation refreshes its access time, so an otherwise-stale entry is NOT evicted")
    void reMarkRefreshesAccessTime() {
        provider.markAsVisualized("conv", "workflow", "id-1");

        // Age the conversation past the expiry window...
        @SuppressWarnings("unchecked")
        Map<String, Long> accessTime =
                (Map<String, Long>) ReflectionTestUtils.getField(provider, "cacheAccessTime");
        accessTime.put("conv", System.currentTimeMillis() - (31L * 60 * 1000));

        // ...then re-mark it, which must reset cacheAccessTime to now (impl: markAsVisualized).
        provider.markAsVisualized("conv", "table", "5");

        // Eviction is purely time-driven: the refresh keeps BOTH keys alive.
        assertThat(provider.wasAlreadyVisualized("conv", "workflow", "id-1")).isTrue();
        assertThat(provider.wasAlreadyVisualized("conv", "table", "5")).isTrue();
    }
}
