package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.AgentBrowseInterfaceRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.apimarketplace.orchestrator.controllers.internal.WebSearchCallbackController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebSearchToolsProvider} - the unified {@code web_search} tool facade
 * (search / fetch / agent_browse + browse_* / help / help_models). Previously untested at the
 * provider level. Unlike the pure facade routers this provider carries real inline logic:
 * {@code help} and {@code help_models} payloads are built in-class, search/fetch short-circuit
 * with no persistence, and the browser-agent path persists an {@code agent_browse} Interface +
 * visualization marker on success. The three modules, {@link InterfaceClient},
 * {@link WebSearchConfig}, the redis template and the (optional) {@link AgentClient} are mocked.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSearchToolsProvider (web_search tool facade)")
class WebSearchToolsProviderTest {

    private static final String TENANT = "tenant-1";

    @Mock private WebSearchModule searchModule;
    @Mock private WebFetchModule fetchModule;
    @Mock private BrowserAgentModule browserAgentModule;
    @Mock private InterfaceClient interfaceClient;
    @Mock private WebSearchConfig config;
    @Mock private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    @Mock private AgentClient agentClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSearchToolsProvider provider;

    @BeforeEach
    void setUp() {
        lenient().when(config.getMaxParallelFetches()).thenReturn(5);
        provider = newProvider(agentClient);
    }

    private WebSearchToolsProvider newProvider(AgentClient client) {
        return new WebSearchToolsProvider(searchModule, fetchModule, browserAgentModule,
                interfaceClient, config, redisTemplate, objectMapper, client);
    }

    private ToolExecutionResult exec(Map<String, Object> params) {
        return exec(params, ToolExecutionContext.of(TENANT));
    }

    private ToolExecutionResult exec(Map<String, Object> params, ToolExecutionContext ctx) {
        return provider.execute("web_search", params, ctx);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static ToolExecutionContext ctxWithCredentials(Map<String, Object> creds) {
        return new ToolExecutionContext(TENANT, creds, Map.of(), Set.of(), null, null, "org-1", null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolExecutionResult r) {
        return (Map<String, Object>) r.data();
    }

    private static ToolExecutionResult ok() {
        return ToolExecutionResult.success(new HashMap<>(Map.of("status", "OK")));
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("tool definition")
    class ToolDefinitions {

        @Test
        @DisplayName("getCategory() is WEB_SEARCH")
        void category() {
            assertThat(provider.getCategory()).isEqualTo(ToolCategory.WEB_SEARCH);
        }

        @Test
        @DisplayName("getTools() returns one 'web_search' tool, no auth, with the browse-sized timeout")
        void singleTool() {
            AgentToolDefinition tool = provider.getTools().get(0);
            assertThat(provider.getTools()).hasSize(1);
            assertThat(tool.name()).isEqualTo("web_search");
            assertThat(tool.requiresAuth()).isFalse();
            assertThat(tool.requiredParameters()).containsExactly("action");
            assertThat(tool.timeoutMs()).isEqualTo(640_000L);
            assertThat(tool.inputSchema()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("the action enum advertises every valid action")
        void actionEnum() {
            var actionParam = provider.getTools().get(0).parameters().stream()
                    .filter(p -> "action".equals(p.name())).findFirst().orElseThrow();
            assertThat(actionParam.enumValues()).containsExactlyInAnyOrder(
                    "search", "fetch", "agent_browse", "browse_status", "browse_intervene",
                    "browse_abort", "browse_screenshot", "help", "help_models");
        }

        @Test
        @DisplayName("the tool exposes the search/fetch/agent_browse/browse_* parameters")
        void allParams() {
            List<String> names = provider.getTools().get(0).parameters().stream().map(p -> p.name()).toList();
            assertThat(names).contains("action", "query", "max_results", "time_range", "url", "urls",
                    "task", "start_url", "llm", "max_steps", "expected_output_schema", "interaction_mode",
                    "domain_allowlist", "domain_denylist", "screenshot_policy", "session", "session_id",
                    "hint", "topics");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("execute framing")
    class Framing {

        @Test
        @DisplayName("an unknown tool name → TOOL_NOT_FOUND")
        void unknownTool() {
            ToolExecutionResult r = provider.execute("catalog", params("action", "search"),
                    ToolExecutionContext.of(TENANT));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("a missing action → MISSING_PARAMETER")
        void missingAction() {
            ToolExecutionResult r = exec(params());
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("action is required");
        }

        @Test
        @DisplayName("a blank action → MISSING_PARAMETER")
        void blankAction() {
            assertThat(exec(params("action", "  ")).errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("an action no module claims → VALIDATION_ERROR")
        void invalidAction() {
            when(searchModule.canHandle("bogus")).thenReturn(false);
            when(fetchModule.canHandle("bogus")).thenReturn(false);
            when(browserAgentModule.canHandle("bogus")).thenReturn(false);
            ToolExecutionResult r = exec(params("action", "bogus"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.error()).contains("Invalid action").contains("bogus");
        }

        @Test
        @DisplayName("a module exception is caught → EXECUTION_FAILED")
        void moduleExceptionCaught() {
            when(searchModule.canHandle("search")).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq(TENANT), any()))
                    .thenThrow(new RuntimeException("boom"));
            ToolExecutionResult r = exec(params("action", "search"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("boom");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("search / fetch (inline, no persistence)")
    class SearchAndFetch {

        @Test
        @DisplayName("search delegates to the search module and returns its result directly")
        void searchDelegates() {
            when(searchModule.canHandle("search")).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "search", "query", "cats")).success()).isTrue();
            verify(searchModule).execute(eq("search"), anyMap(), eq(TENANT), any());
            // search renders inline (FaviconStack) - never persisted as an Interface.
            verifyNoInteractions(interfaceClient);
        }

        @Test
        @DisplayName("an empty search result → EXTERNAL_SERVICE_ERROR 'Search failed'")
        void searchEmpty() {
            when(searchModule.canHandle("search")).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "search"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXTERNAL_SERVICE_ERROR);
            assertThat(r.error()).isEqualTo("Search failed");
        }

        @Test
        @DisplayName("fetch falls through search to the fetch module")
        void fetchDelegates() {
            when(searchModule.canHandle("fetch")).thenReturn(false);
            when(fetchModule.canHandle("fetch")).thenReturn(true);
            when(fetchModule.execute(eq("fetch"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "fetch", "url", "https://x")).success()).isTrue();
            verify(fetchModule).execute(eq("fetch"), anyMap(), eq(TENANT), any());
            verifyNoInteractions(interfaceClient);
        }

        @Test
        @DisplayName("an empty fetch result → EXTERNAL_SERVICE_ERROR 'Fetch failed'")
        void fetchEmpty() {
            when(searchModule.canHandle("fetch")).thenReturn(false);
            when(fetchModule.canHandle("fetch")).thenReturn(true);
            when(fetchModule.execute(eq("fetch"), anyMap(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "fetch"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXTERNAL_SERVICE_ERROR);
            assertThat(r.error()).isEqualTo("Fetch failed");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("agent_browse / browse_* (persisted on success)")
    class BrowserAgent {

        private void browserClaims(String action) {
            when(searchModule.canHandle(action)).thenReturn(false);
            when(fetchModule.canHandle(action)).thenReturn(false);
            when(browserAgentModule.canHandle(action)).thenReturn(true);
        }

        @Test
        @DisplayName("an empty browser result → EXECUTION_FAILED 'Browser agent action failed'")
        void browserEmpty() {
            browserClaims("agent_browse");
            when(browserAgentModule.execute(eq("agent_browse"), anyMap(), eq(TENANT), any()))
                    .thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "agent_browse", "task", "t"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Browser agent action failed");
        }

        @Test
        @DisplayName("a FAILED browser result is returned as-is, never persisted")
        void browserFailureNotPersisted() {
            browserClaims("agent_browse");
            ToolExecutionResult failure = ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "runner died");
            when(browserAgentModule.execute(eq("agent_browse"), anyMap(), eq(TENANT), any()))
                    .thenReturn(Optional.of(failure));
            ToolExecutionResult r = exec(params("action", "agent_browse", "task", "t"));
            assertThat(r.success()).isFalse();
            assertThat(r.error()).isEqualTo("runner died");
            verifyNoInteractions(interfaceClient);
        }

        @Test
        @DisplayName("a successful browser result with no conversation context skips persistence (returned as-is)")
        void browserSuccessNoConversationContextSkipsPersist() {
            browserClaims("browse_status");
            when(browserAgentModule.execute(eq("browse_status"), anyMap(), eq(TENANT), any()))
                    .thenReturn(Optional.of(ok()));
            // Default context credentials lack conversationId/messageId → enricher skips persistence.
            ToolExecutionResult r = exec(params("action", "browse_status", "session_id", "s1"));
            assertThat(r.success()).isTrue();
            verifyNoInteractions(interfaceClient);
        }

        @Test
        @DisplayName("a successful browser result with conversation context persists an agent_browse Interface")
        void browserSuccessPersists() {
            browserClaims("agent_browse");
            when(browserAgentModule.execute(eq("agent_browse"), anyMap(), eq(TENANT), any()))
                    .thenReturn(Optional.of(ok()));

            InterfaceDto dto = new InterfaceDto();
            dto.setId(UUID.randomUUID());
            dto.setName("Find the price");
            when(interfaceClient.createOrUpdateAgentBrowseInterface(any(), eq(TENANT))).thenReturn(dto);

            ToolExecutionContext ctx = ctxWithCredentials(Map.of(
                    "conversationId", "conv-1", "__messageId__", "msg-1", "__agentId__", "agent-7"));

            ToolExecutionResult r = exec(params("action", "agent_browse", "task", "Find the price"), ctx);

            assertThat(r.success()).isTrue();
            ArgumentCaptor<AgentBrowseInterfaceRequest> captor =
                    ArgumentCaptor.forClass(AgentBrowseInterfaceRequest.class);
            verify(interfaceClient).createOrUpdateAgentBrowseInterface(captor.capture(), eq(TENANT));
            // The interface name is derived from query/url; an agent_browse carries only `task`,
            // so it falls back to "Browser Agent". The org + agent are stamped so teammates can read the card.
            assertThat(captor.getValue().getName()).isEqualTo("Browser Agent");
            assertThat(captor.getValue().getOrganizationId()).isEqualTo("org-1");
            assertThat(captor.getValue().getAgentId()).isEqualTo("agent-7");
            // The enricher adds the side-panel visualization marker (titled from the persisted DTO).
            assertThat(r.metadata()).containsKey("visualization");
            assertThat(data(r)).containsKey("marker");
        }

        @Test
        @DisplayName("on persist, the toolCallId→interface mapping is written to redis as 'tenant|id' (10-min TTL)")
        void browserSuccessWritesRedisToolMapping() {
            browserClaims("agent_browse");
            when(browserAgentModule.execute(eq("agent_browse"), anyMap(), eq(TENANT), any()))
                    .thenReturn(Optional.of(ok()));

            UUID interfaceId = UUID.randomUUID();
            InterfaceDto dto = new InterfaceDto();
            dto.setId(interfaceId);
            dto.setName("Browser Agent");
            when(interfaceClient.createOrUpdateAgentBrowseInterface(any(), eq(TENANT))).thenReturn(dto);

            @SuppressWarnings("unchecked")
            org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                    org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            ToolExecutionContext ctx = ctxWithCredentials(Map.of(
                    "conversationId", "conv-1", "__messageId__", "msg-1", "__toolCallId__", "tc-9"));

            ToolExecutionResult r = exec(params("action", "agent_browse", "task", "t"), ctx);

            assertThat(r.success()).isTrue();
            verify(valueOps).set(
                    eq(WebSearchCallbackController.TOOL_IFACE_PREFIX + "tc-9"),
                    eq(TENANT + "|" + interfaceId),
                    eq(Duration.ofMinutes(10)));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("help (inline, no module dispatch)")
    class Help {

        @Test
        @DisplayName("help returns the full payload and never touches a module")
        void helpFull() {
            ToolExecutionResult r = exec(params("action", "help"));
            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsKeys("description", "actions", "available_models", "concepts", "examples");
            verifyNoInteractions(searchModule, fetchModule, browserAgentModule);
        }

        @Test
        @DisplayName("help with topics=['actions'] returns only that section (+ always-on description/models)")
        void helpFilteredByTopic() {
            ToolExecutionResult r = exec(params("action", "help", "topics", List.of("actions")));
            assertThat(data(r)).containsKeys("description", "actions", "available_models");
            assertThat(data(r)).doesNotContainKeys("concepts", "examples");
        }

        @Test
        @DisplayName("help with only unrecognized topics falls back to the full payload")
        void helpUnknownTopicFallsBackToAll() {
            ToolExecutionResult r = exec(params("action", "help", "topics", List.of("nonsense")));
            assertThat(data(r)).containsKeys("actions", "concepts", "examples");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("help_models (live model catalog)")
    class HelpModels {

        @Test
        @DisplayName("with no AgentClient wired, help_models still answers with a fallback note")
        void noClientWired() {
            WebSearchToolsProvider unwired = newProvider(null);
            ToolExecutionResult r = unwired.execute("web_search", params("action", "help_models"),
                    ToolExecutionContext.of(TENANT));
            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsKey("note");
            assertThat((String) data(r).get("note")).contains("not wired");
        }

        @Test
        @DisplayName("when the catalog client throws, help_models degrades to a 'could not reach' note")
        void clientThrows() {
            when(agentClient.getModelsInfo("browser_agent")).thenThrow(new RuntimeException("agent-service down"));
            ToolExecutionResult r = exec(params("action", "help_models"));
            assertThat(r.success()).isTrue();
            assertThat((String) data(r).get("note")).contains("Could not reach");
        }

        @Test
        @DisplayName("the catalog is flattened by priority, bridges are hidden, and the direct default is surfaced")
        void catalogFlattenedBridgesHidden() {
            Map<String, Object> catalog = new HashMap<>();
            catalog.put("providers", List.of(
                    Map.of("name", "openai", "providerKind", "direct",
                            "models", List.of(Map.of("id", "gpt-4", "displayOrder", 1))),
                    Map.of("name", "claude-code", "providerKind", "bridge",
                            "models", List.of(Map.of("id", "cc-1", "displayOrder", 2)))));
            catalog.put("defaultDirectProvider", "openai");
            catalog.put("defaultDirectModel", "gpt-4");
            when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalog);

            ToolExecutionResult r = exec(params("action", "help_models"));

            assertThat(r.success()).isTrue();
            @SuppressWarnings("unchecked")
            List<String> pairs = (List<String>) data(r).get("pairs");
            assertThat(pairs).containsExactly("openai/gpt-4 (#1)");
            assertThat(data(r)).containsEntry("total_enabled", 1).containsEntry("returned", 1);
            @SuppressWarnings("unchecked")
            Map<String, Object> def = (Map<String, Object>) data(r).get("default");
            assertThat(def).containsEntry("provider", "openai").containsEntry("model", "gpt-4");
            // per-provider grouping is surfaced too
            @SuppressWarnings("unchecked")
            Map<String, Object> byProvider = (Map<String, Object>) data(r).get("providers");
            assertThat(byProvider).containsKey("openai");
            @SuppressWarnings("unchecked")
            List<String> openaiModels = (List<String>) byProvider.get("openai");
            assertThat(openaiModels).containsExactly("gpt-4 (#1)");
            // The single bridge model is hidden and reported in the note.
            assertThat((String) data(r).get("note")).contains("bridge");
            verify(searchModule, never()).canHandle(anyString());
        }

        @Test
        @DisplayName("when there is no direct default, help_models falls back to the overall default pair")
        void nullDirectDefaultFallsBackToOverall() {
            Map<String, Object> catalog = new HashMap<>();
            catalog.put("providers", List.of(
                    Map.of("name", "google", "providerKind", "direct",
                            "models", List.of(Map.of("id", "gemini", "displayOrder", 1)))));
            // defaultDirect* intentionally absent → null → fallback to defaultProvider/defaultModel.
            catalog.put("defaultProvider", "google");
            catalog.put("defaultModel", "gemini");
            when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalog);

            ToolExecutionResult r = exec(params("action", "help_models"));

            assertThat(r.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> def = (Map<String, Object>) data(r).get("default");
            assertThat(def).containsEntry("provider", "google").containsEntry("model", "gemini");
        }

        @Test
        @DisplayName("the catalog is capped at the top 30 by priority (total_enabled > returned)")
        void truncatesAtCap() {
            List<Map<String, Object>> models = new ArrayList<>();
            for (int i = 1; i <= 35; i++) {
                models.add(Map.of("id", "m" + i, "displayOrder", i));
            }
            Map<String, Object> catalog = new HashMap<>();
            catalog.put("providers", List.of(
                    Map.of("name", "openai", "providerKind", "direct", "models", models)));
            catalog.put("defaultDirectProvider", "openai");
            catalog.put("defaultDirectModel", "m1");
            when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalog);

            ToolExecutionResult r = exec(params("action", "help_models"));

            assertThat(data(r)).containsEntry("total_enabled", 35).containsEntry("returned", 30);
            assertThat((List<?>) data(r).get("pairs")).hasSize(30);
        }
    }
}
