package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.AgentBrowseInterfaceRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the post-2026-05-22 persistence policy of {@link WebSearchToolsProvider}.
 *
 * <p>Background: prior to this change, every successful web_search tool call
 * (regardless of action: search, fetch, agent_browse, browse_*) was persisted
 * into {@code interface.interfaces} with {@code interface_type='web_search'}.
 * The frontend stopped rendering web_search interfaces in commit f600c8885
 * (favicon-stack inline rendering), so the persistence became dead weight -
 * 50 rows on prod for one tenant, polluting the agent's interface.list view.
 *
 * <p>The fix in this commit:
 * <ul>
 *   <li><b>search / fetch</b> - NO Interface row is created.
 *       {@link InterfaceClient#createOrUpdateAgentBrowseInterface} must never
 *       be invoked from these branches.</li>
 *   <li><b>agent_browse</b> - STILL creates an Interface row, but now as
 *       {@code interface_type='agent_browse'} via the new
 *       {@code createOrUpdateAgentBrowseInterface} method.</li>
 * </ul>
 *
 * <p>These tests are the regression guard for both halves of the policy.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSearchToolsProvider - persistence policy by action")
class WebSearchToolsProviderPersistencePolicyTest {

    @Mock private WebSearchModule searchModule;
    @Mock private WebFetchModule fetchModule;
    @Mock private BrowserAgentModule browserAgentModule;
    @Mock private InterfaceClient interfaceClient;
    @Mock private WebSearchConfig config;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> redisValueOps;
    @Mock private AgentClient agentClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSearchToolsProvider provider;

    @BeforeEach
    void setUp() {
        lenient().when(config.getMaxParallelFetches()).thenReturn(5);
        lenient().when(redisTemplate.opsForValue()).thenReturn(redisValueOps);
        provider = new WebSearchToolsProvider(
            searchModule, fetchModule, browserAgentModule, interfaceClient,
            config, redisTemplate, objectMapper, agentClient);
    }

    private static ToolExecutionContext ctx() {
        return ToolExecutionContext.of("tenant-x");
    }

    private static ToolExecutionContext ctxWithMessage() {
        // ToolResultPersistEnricher.enrichAndPersist short-circuits when
        // conversationId / __messageId__ are absent from the credentials map
        // (no chat context → no Interface row to back the visualization marker).
        // Tests that exercise the persistence path must thread a non-null pair.
        Map<String, Object> credentials = new java.util.HashMap<>();
        credentials.put("conversationId", "conv-test");
        credentials.put("__messageId__", "msg-test");
        credentials.put("__agentId__", "agent-test");
        credentials.put("__toolCallId__", "call-test");
        return new ToolExecutionContext(
            "tenant-x", credentials, Map.of(), java.util.Set.of(),
            null, null, "org-test", null);
    }

    @Test
    @DisplayName("search action does NOT persist an Interface row - results render inline via FaviconStack")
    void searchSkipsPersistence() {
        when(searchModule.canHandle("search")).thenReturn(true);
        ToolExecutionResult moduleResult = ToolExecutionResult.success(
            Map.of("results", java.util.List.of(Map.of("url", "https://example.com"))));
        when(searchModule.execute(anyString(), any(), any(), any()))
            .thenReturn(Optional.of(moduleResult));

        ToolExecutionContext context = ctx();
        ToolExecutionResult result = provider.execute(
            "web_search", Map.of("action", "search", "query", "openai"), context);

        assertThat(result.success()).isTrue();
        verify(searchModule).execute(eq("search"), any(), eq("tenant-x"), same(context));
        verify(interfaceClient, never()).createOrUpdateAgentBrowseInterface(any(), any());
    }

    @Test
    @DisplayName("fetch action does NOT persist an Interface row - single-URL fetch also renders inline")
    void fetchSkipsPersistence() {
        when(fetchModule.canHandle("fetch")).thenReturn(true);
        ToolExecutionResult moduleResult = ToolExecutionResult.success(
            Map.of("url", "https://example.com", "text", "hello"));
        when(fetchModule.execute(anyString(), any(), any(), any()))
            .thenReturn(Optional.of(moduleResult));

        ToolExecutionResult result = provider.execute(
            "web_search", Map.of("action", "fetch", "url", "https://example.com"), ctx());

        assertThat(result.success()).isTrue();
        verify(interfaceClient, never()).createOrUpdateAgentBrowseInterface(any(), any());
    }

    @Test
    @DisplayName("agent_browse action DOES persist via createOrUpdateAgentBrowseInterface (interface_type='agent_browse')")
    void agentBrowsePersistsAsAgentBrowseType() {
        when(browserAgentModule.canHandle("agent_browse")).thenReturn(true);
        ToolExecutionResult moduleResult = ToolExecutionResult.success(
            Map.of("session_id", "sess-1", "page_url", "https://example.com"));
        when(browserAgentModule.execute(anyString(), any(), any(), any()))
            .thenReturn(Optional.of(moduleResult));

        InterfaceDto persisted = new InterfaceDto();
        persisted.setId(UUID.randomUUID());
        persisted.setName("browser-task");
        when(interfaceClient.createOrUpdateAgentBrowseInterface(any(), anyString()))
            .thenReturn(persisted);

        ToolExecutionResult result = provider.execute(
            "web_search",
            Map.of("action", "agent_browse", "query", "browser-task", "url", "https://example.com"),
            ctxWithMessage());

        assertThat(result.success()).isTrue();
        ArgumentCaptor<AgentBrowseInterfaceRequest> reqCaptor =
            ArgumentCaptor.forClass(AgentBrowseInterfaceRequest.class);
        verify(interfaceClient).createOrUpdateAgentBrowseInterface(reqCaptor.capture(), anyString());
        AgentBrowseInterfaceRequest req = reqCaptor.getValue();
        // The DTO is type-locked at the service layer; from the client's
        // perspective, the request just carries the payload and the service
        // stamps interface_type='agent_browse'. We assert the shape here.
        assertThat(req.getName()).isEqualTo("browser-task");
        assertThat(req.getConversationId()).isEqualTo("conv-test");
        assertThat(req.getMessageId()).isEqualTo("msg-test");
        assertThat(req.getOrganizationId()).isEqualTo("org-test");
    }
}
