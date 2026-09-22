package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.domain.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("CoreToolsProvider")
@ExtendWith(MockitoExtension.class)
class CoreToolsProviderTest {

    @Mock
    private RestTemplate restTemplate;

    private CoreToolsProvider coreToolsProvider;

    @BeforeEach
    void setUp() throws Exception {
        coreToolsProvider = new CoreToolsProvider(restTemplate);
        Field urlField = CoreToolsProvider.class.getDeclaredField("orchestratorUrl");
        urlField.setAccessible(true);
        urlField.set(coreToolsProvider, "http://localhost:8099");
    }

    private Map<String, Object> buildToolData(String name, String description) {
        return Map.of(
                "name", name,
                "description", description,
                "requiredParams", List.of("param1"),
                "parameters", List.of(
                        Map.of("name", "param1", "type", "string", "description", "A param", "required", true)
                )
        );
    }

    private void setFeatureFlag(String fieldName, boolean enabled) throws Exception {
        Field field = CoreToolsProvider.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(coreToolsProvider, enabled);
    }

    @Nested
    @DisplayName("refreshCoreTools")
    class RefreshCoreTools {

        @Test
        @DisplayName("should load core tools from orchestrator")
        void shouldLoadCoreTools() {
            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Search catalog"),
                    buildToolData("workflow", "Workflow tool"),
                    buildToolData("non_core_tool", "Should be filtered out")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            coreToolsProvider.refreshCoreTools();

            assertThat(coreToolsProvider.isInitialized()).isTrue();
            assertThat(coreToolsProvider.getToolCount()).isEqualTo(2); // only core tools
        }

        @Test
        @DisplayName("should handle orchestrator unavailability gracefully")
        void shouldHandleUnavailability() {
            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenThrow(new RuntimeException("Connection refused"));

            coreToolsProvider.refreshCoreTools();

            // Should not throw, just log warning
            // initialized remains false if cache is empty
        }

        @Test
        @DisplayName("should handle empty response body")
        void shouldHandleEmptyBody() {
            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

            coreToolsProvider.refreshCoreTools();
        }

        @Test
        @DisplayName("CE feature gate: disabled web_search is not expected or cached")
        void disabledWebSearchIsNotExpectedOrCached() throws Exception {
            setFeatureFlag("webSearchEnabled", false);
            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Catalog"),
                    buildToolData("workflow", "Workflow"),
                    buildToolData("table", "Table"),
                    buildToolData("interface", "Interface"),
                    buildToolData("agent", "Agent"),
                    buildToolData("skill", "Skill"),
                    buildToolData("application", "Application"),
                    buildToolData("web_search", "Web search")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            coreToolsProvider.refreshCoreTools();

            assertThat(coreToolsProvider.activeCoreToolNames()).doesNotContain("web_search");
            assertThat(coreToolsProvider.getMissingTools()).doesNotContain("web_search");
            assertThat(coreToolsProvider.getCoreTools(Set.of("catalog", "web_search"), false).stream()
                    .map(ToolDefinition::name))
                    .contains("catalog")
                    .doesNotContain("web_search");
        }

        /**
         * generation debits credits on every create, so the install-level flag has to
         * reach the chat cache too. With the flag off catalog-service never registers
         * the provider; a cache that still expected the tool would hand it to chat the
         * moment any source advertised the name.
         */
        @Test
        @DisplayName("spend gate off: generation is neither expected nor served to chat")
        void disabledGenerationIsNotExpectedOrCached() throws Exception {
            setFeatureFlag("generationEnabled", false);
            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Catalog"),
                    buildToolData("generation", "Generation")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            coreToolsProvider.refreshCoreTools();

            assertThat(coreToolsProvider.activeCoreToolNames()).doesNotContain("generation");
            assertThat(coreToolsProvider.getMissingTools()).doesNotContain("generation");
            assertThat(coreToolsProvider.getCoreTools(Set.of("catalog", "generation"), false).stream()
                    .map(ToolDefinition::name))
                    .contains("catalog")
                    .doesNotContain("generation");
        }

        @Test
        @DisplayName("spend gate on: generation is expected, cached, and served when its module is granted")
        void enabledGenerationIsExpectedAndServed() {
            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Catalog"),
                    buildToolData("generation", "Generation")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            coreToolsProvider.refreshCoreTools();

            assertThat(coreToolsProvider.activeCoreToolNames()).contains("generation");
            assertThat(coreToolsProvider.getCoreTools(Set.of("generation"), false).stream()
                    .map(ToolDefinition::name))
                    .contains("generation");
            // and an agent whose modules do NOT include it never sees it
            assertThat(coreToolsProvider.getCoreTools(Set.of("catalog"), false).stream()
                    .map(ToolDefinition::name))
                    .doesNotContain("generation");
        }

        @Test
        @DisplayName("CE→cloud relay wired: web_search stays an active core tool even with the local engine disabled")
        void relayWiredKeepsWebSearchActiveAndCached() throws Exception {
            setFeatureFlag("webSearchEnabled", false);
            com.apimarketplace.agent.cloud.CeWebSearchRelayGate gate =
                    org.mockito.Mockito.mock(com.apimarketplace.agent.cloud.CeWebSearchRelayGate.class);
            when(gate.isWebSearchExposable()).thenReturn(true);
            Field gateField = CoreToolsProvider.class.getDeclaredField("webSearchRelayGate");
            gateField.setAccessible(true);
            gateField.set(coreToolsProvider, gate);

            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Catalog"),
                    buildToolData("web_search", "Web search")
            );
            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            coreToolsProvider.refreshCoreTools();

            assertThat(coreToolsProvider.activeCoreToolNames()).contains("web_search");
            assertThat(coreToolsProvider.getCoreTools(Set.of("catalog", "web_search"), false).stream()
                    .map(ToolDefinition::name))
                    .contains("catalog", "web_search");
        }
    }

    @Nested
    @DisplayName("getCoreTools")
    class GetCoreTools {

        @Test
        @DisplayName("should include set_conversation_title for new conversation")
        void shouldIncludeTitleToolForNewConversation() {
            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Search")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            List<ToolDefinition> result = coreToolsProvider.getCoreTools(true);

            List<String> names = result.stream().map(ToolDefinition::name).toList();
            assertThat(names).contains("set_conversation_title");
            assertThat(names).doesNotContain("tasks");
            assertThat(names).contains("get_tool_result");
            assertThat(names).contains("credential");
            // Legacy routing alias must never be advertised to the LLM.
            assertThat(names).doesNotContain("request_credential");
        }

        /**
         * "No toolsConfig" is not "everything". AgentModuleResolver.resolveEnabledModules(null)
         * is the platform's definition of unrestricted and leaves the two credit-spending
         * opt-in modules out; the unfiltered accessor used to ignore that and hand a general
         * chat, where nobody enabled anything, a tool that spends the customer's credits.
         */
        @Test
        @DisplayName("no-config caller gets every ordinary tool but NEITHER credit-spending opt-in")
        void noConfigCallerNeverGetsTheCreditSpendingTools() {
            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Catalog"),
                    buildToolData("workflow", "Workflow"),
                    buildToolData("table", "Table"),
                    buildToolData("interface", "Interface"),
                    buildToolData("agent", "Agent"),
                    buildToolData("skill", "Skill"),
                    buildToolData("application", "Application"),
                    buildToolData("web_search", "Web search"),
                    buildToolData("files", "Files"),
                    buildToolData("wait", "Wait"),
                    buildToolData("generation", "Generation")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            List<String> names = coreToolsProvider.getCoreTools(false).stream()
                    .map(ToolDefinition::name).toList();

            assertThat(names).contains("catalog", "workflow", "table", "interface", "agent",
                    "skill", "application", "web_search", "files", "wait");
            assertThat(names).doesNotContain("generation", "image_generation");
            // The cache still HOLDS it - it is only withheld from a caller that granted
            // nothing, and is served the moment a resolved module set asks for it.
            assertThat(coreToolsProvider.getCoreTools(Set.of("generation"), false)
                    .stream().map(ToolDefinition::name))
                    .contains("generation");
        }

        @Test
        @DisplayName("should not include set_conversation_title for follow-up")
        void shouldNotIncludeTitleToolForFollowUp() {
            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Search")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            List<ToolDefinition> result = coreToolsProvider.getCoreTools(false);

            List<String> names = result.stream().map(ToolDefinition::name).toList();
            assertThat(names).doesNotContain("set_conversation_title");
            assertThat(names).doesNotContain("tasks");
        }

        @Test
        @DisplayName("should always include conversation-specific tools")
        void shouldAlwaysIncludeConversationTools() {
            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", List.of()), HttpStatus.OK));

            List<ToolDefinition> result = coreToolsProvider.getCoreTools(false);

            List<String> names = result.stream().map(ToolDefinition::name).toList();
            assertThat(names).doesNotContain("tasks");
            assertThat(names).contains("get_tool_result");
            assertThat(names).contains("credential");
            assertThat(names).doesNotContain("request_credential");
        }
    }

    @Nested
    @DisplayName("getCoreTools with filtered modules")
    class GetCoreToolsFiltered {

        @Test
        @DisplayName("should return only tools in the enabled set")
        void shouldReturnOnlyEnabledTools() {
            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Search"),
                    buildToolData("workflow", "Workflow"),
                    buildToolData("table", "Table"),
                    buildToolData("web_search", "Web search")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            // Only request catalog and web_search
            List<ToolDefinition> result = coreToolsProvider.getCoreTools(
                    Set.of("catalog", "web_search"), false);

            List<String> names = result.stream().map(ToolDefinition::name).toList();
            assertThat(names).contains("catalog", "web_search");
            assertThat(names).doesNotContain("workflow", "table");
            // Conversation tools should still be included
            assertThat(names).contains("get_tool_result", "credential");
            assertThat(names).doesNotContain("request_credential");
        }

        @Test
        @DisplayName("should include set_conversation_title when isNewConversation is true")
        void shouldIncludeTitleForNewConversation() {
            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Search")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            List<ToolDefinition> result = coreToolsProvider.getCoreTools(Set.of("catalog"), true);

            List<String> names = result.stream().map(ToolDefinition::name).toList();
            assertThat(names).contains("set_conversation_title");
        }

        @Test
        @DisplayName("should return empty core tools when no matches but still include conversation tools")
        void shouldReturnConversationToolsWhenNoMatches() {
            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Search")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            List<ToolDefinition> result = coreToolsProvider.getCoreTools(
                    Set.of("nonexistent_tool"), false);

            List<String> names = result.stream().map(ToolDefinition::name).toList();
            assertThat(names).doesNotContain("catalog");
            // Conversation tools always present
            assertThat(names).contains("get_tool_result", "credential");
            assertThat(names).doesNotContain("request_credential");
        }
    }

    @Nested
    @DisplayName("deterministic ordering (Stage 1a.1-D)")
    class DeterministicOrdering {

        // Core tools cache is a ConcurrentHashMap; iteration order is undefined
        // and varies with JVM hash-seed randomization. Prompt caching requires
        // a byte-stable tools prefix, so `getCoreTools(...)` must sort before
        // returning - these tests pin that contract.

        @Test
        @DisplayName("getCoreTools returns tools in name-ascending order regardless of load order")
        void unfilteredGetCoreToolsIsNameSorted() {
            List<Map<String, Object>> tools = List.of(
                    buildToolData("workflow", "Workflow"),
                    buildToolData("catalog", "Catalog"),
                    buildToolData("table", "Table"),
                    buildToolData("agent", "Agent")
            );
            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            List<String> names = coreToolsProvider.getCoreTools(false).stream()
                    .map(ToolDefinition::name)
                    .toList();

            // Core tools appear before/after conversation tools, but the full list
            // must be sorted - a byte-stable prefix is what Anthropic caches.
            List<String> sorted = new java.util.ArrayList<>(names);
            sorted.sort(java.util.Comparator.naturalOrder());
            assertThat(names).isEqualTo(sorted);
        }

        @Test
        @DisplayName("filtered getCoreTools also returns name-sorted tools")
        void filteredGetCoreToolsIsNameSorted() {
            List<Map<String, Object>> tools = List.of(
                    buildToolData("workflow", "Workflow"),
                    buildToolData("catalog", "Catalog"),
                    buildToolData("web_search", "Web search"),
                    buildToolData("table", "Table")
            );
            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            List<String> names = coreToolsProvider
                    .getCoreTools(Set.of("workflow", "catalog", "web_search"), false)
                    .stream()
                    .map(ToolDefinition::name)
                    .toList();

            List<String> sorted = new java.util.ArrayList<>(names);
            sorted.sort(java.util.Comparator.naturalOrder());
            assertThat(names).isEqualTo(sorted);
        }
    }

    @Nested
    @DisplayName("isInitialized and getToolCount")
    class InitializationState {

        @Test
        @DisplayName("should not be initialized before refresh")
        void shouldNotBeInitializedBeforeRefresh() {
            assertThat(coreToolsProvider.isInitialized()).isFalse();
            assertThat(coreToolsProvider.getToolCount()).isZero();
        }

        @Test
        @DisplayName("should be initialized after successful refresh")
        void shouldBeInitializedAfterRefresh() {
            List<Map<String, Object>> tools = List.of(
                    buildToolData("catalog", "Search"),
                    buildToolData("workflow", "Workflow")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", tools), HttpStatus.OK));

            coreToolsProvider.refreshCoreTools();

            assertThat(coreToolsProvider.isInitialized()).isTrue();
            assertThat(coreToolsProvider.getToolCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Tool parsing")
    class ToolParsing {

        @Test
        @DisplayName("should parse tool with full parameter details")
        void shouldParseToolWithFullParams() {
            Map<String, Object> toolData = Map.of(
                    "name", "catalog",
                    "description", "Search the catalog",
                    "requiredParams", List.of("query"),
                    "parameters", List.of(
                            Map.of("name", "query", "type", "string",
                                    "description", "Search query", "required", true,
                                    "enum", List.of("tools", "apis"))
                    )
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", List.of(toolData)), HttpStatus.OK));

            coreToolsProvider.refreshCoreTools();

            List<ToolDefinition> tools = coreToolsProvider.getCoreTools(false);
            ToolDefinition catalogSearch = tools.stream()
                    .filter(t -> "catalog".equals(t.name()))
                    .findFirst().orElse(null);

            assertThat(catalogSearch).isNotNull();
            assertThat(catalogSearch.description()).isEqualTo("Search the catalog");
            assertThat(catalogSearch.parameters()).hasSize(1);
            assertThat(catalogSearch.parameters().get(0).name()).isEqualTo("query");
            assertThat(catalogSearch.requiredParameters()).containsExactly("query");
        }

        @Test
        @DisplayName("should keep an array parameter's item type, which decides what the model is told to send")
        void shouldKeepArrayItemType() {
            // The schema this service hands the model is rebuilt from what is parsed here, so
            // dropping the item type turns an array of objects back into an array of strings for
            // every direct-API chat agent, with nothing failing in between.
            Map<String, Object> toolData = Map.of(
                    "name", "catalog",
                    "description", "Search the catalog",
                    "parameters", List.of(
                            Map.of("name", "rows", "type", "array", "description", "Rows",
                                    "required", false, "itemType", "object"),
                            Map.of("name", "tags", "type", "array", "description", "Tags",
                                    "required", false)
                    )
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", List.of(toolData)), HttpStatus.OK));

            coreToolsProvider.refreshCoreTools();

            ToolDefinition catalog = coreToolsProvider.getCoreTools(false).stream()
                    .filter(t -> "catalog".equals(t.name())).findFirst().orElseThrow();

            assertThat(catalog.parameters().get(0).itemType()).isEqualTo("object");
            // Absent on the wire means the reader's default, not a value invented here.
            assertThat(catalog.parameters().get(1).itemType()).isNull();
        }

        @Test
        @DisplayName("should parse tool with only requiredParams fallback")
        void shouldParseToolWithFallback() {
            Map<String, Object> toolData = Map.of(
                    "name", "catalog",
                    "description", "Search",
                    "requiredParams", List.of("query", "limit")
            );

            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("tools", List.of(toolData)), HttpStatus.OK));

            coreToolsProvider.refreshCoreTools();

            assertThat(coreToolsProvider.getToolCount()).isEqualTo(1);
        }
    }
@org.junit.jupiter.api.Nested
    @DisplayName("the set that decides which tools may enter the cache at all")
    class ActiveToolNameParity {

        /**
         * The layer under the module list, and the one that silently drops a tool.
         *
         * <p>{@code fetchToolsFrom} only caches a tool whose name is in this set, so a tool
         * present in {@code ALL_RESOURCE_MODULES}, resolved by {@code AgentModuleResolver} and
         * routed by {@code ToolServiceTopology} is STILL invisible in the web chat if the set
         * does not name it. Nothing errors: the tool is fetched from its service and thrown
         * away. That is what happened to {@code mailbox}, which reached CLI and workflow agents
         * (their cache derives the set from the modules) and not the main chat (this one held a
         * hand-written copy).
         */
        @Test
        @DisplayName("every module tool can enter the cache, so none is fetched and dropped")
        void coversEveryModuleTool() {
            assertThat(coreToolsProvider.activeCoreToolNames())
                    .containsAll(com.apimarketplace.agent.prompt.DefaultSystemPrompts.getAllCoreToolNames());
        }

        @Test
        @DisplayName("a tool the modules do not declare cannot enter the cache either")
        void declaresNothingExtra() {
            assertThat(com.apimarketplace.agent.prompt.DefaultSystemPrompts.getAllCoreToolNames())
                    .as("an entry here that no module declares is a tool no prompt ever mentions")
                    .containsAll(coreToolsProvider.activeCoreToolNames());
        }

        @Test
        @DisplayName("mailbox is one of them: the chat surface can offer it")
        void mailboxIsAmongThem() {
            assertThat(coreToolsProvider.activeCoreToolNames()).contains("mailbox");
        }
    }
}
