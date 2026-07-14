package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.ModelCatalogService.AvailableModel;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCrudModule Tests")
class AgentCrudModuleTest {

    @Mock private AgentService agentService;
    @Mock private SkillService skillService;
    @Mock private com.apimarketplace.agent.webhook.AgentWebhookTokenService webhookTokenService;
    @Mock private ModelCatalogService modelCatalogService;
    @Mock private org.springframework.web.client.RestTemplate restTemplate;

    private AgentCrudModule module;

    private static final String TENANT = "tenant-123";
    private static final UUID AGENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var agentDefaults = new com.apimarketplace.agent.config.AgentDefaultsConfig();
        // Default stub: accept the yml defaults (anthropic/claude-sonnet-4-6) + the
        // mockAgent() defaults (openai/gpt-4) so existing tests don't have to care
        // about the new validation. Individual tests that exercise the validation
        // path override this behaviour explicitly. Lenient because not every test
        // method exercises model validation (list/get/delete skip it entirely).
        lenient().when(modelCatalogService.isModelAvailable(anyString(), anyString())).thenReturn(true);
        lenient().when(modelCatalogService.getEffectiveDefaultModel()).thenReturn("claude-sonnet-4-6");
        lenient().when(modelCatalogService.getEffectiveDefaultProvider()).thenReturn("anthropic");
        module = new AgentCrudModule(agentService, skillService, webhookTokenService,
                agentDefaults, modelCatalogService, restTemplate,
                "http://localhost:8091", "http://localhost:8080");
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(TENANT, Map.of("turnId", "turn-1"), Map.of(), null, null, null, null, null);
    }

    private ToolExecutionContext ctxWithAllowedAgents(List<String> ids) {
        return new ToolExecutionContext(TENANT, Map.of("allowedAgentIds", ids, "turnId", "turn-1"), Map.of(), null, null, null, null, null);
    }

    private AgentEntity mockAgent(UUID id, String name) {
        AgentEntity e = new AgentEntity();
        e.setId(id);
        e.setName(name);
        e.setModelProvider("openai");
        e.setModelName("gpt-4");
        e.setTemperature(BigDecimal.valueOf(0.7));
        e.setMaxTokens(4096);
        e.setIsPublic(false);
        e.setIsActive(true);
        return e;
    }

    @Nested
    @DisplayName("canHandle")
    class CanHandle {
        @Test void handlesCreate() { assertThat(module.canHandle("create")).isTrue(); }
        @Test void handlesGet() { assertThat(module.canHandle("get")).isTrue(); }
        @Test void handlesList() { assertThat(module.canHandle("list")).isTrue(); }
        @Test void handlesUpdate() { assertThat(module.canHandle("update")).isTrue(); }
        @Test void handlesDelete() { assertThat(module.canHandle("delete")).isTrue(); }
        @Test void rejectsExecute() { assertThat(module.canHandle("execute")).isFalse(); }
        @Test void rejectsHelp() { assertThat(module.canHandle("help")).isFalse(); }
    }

    @Nested
    @DisplayName("Create")
    class Create {

        @Test
        @DisplayName("Success with required params")
        void createSuccess() {
            AgentEntity created = mockAgent(AGENT_ID, "My Agent");
            when(agentService.createAgent(eq(TENANT), eq("My Agent"), isNull(), eq("You are helpful."),
                eq("anthropic"), eq("claude-sonnet-4-6"), any(), eq(16000),
                isNull(), isNull(), any(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(false), eq(true), isNull(), isNull(), isNull()))
                .thenReturn(created);

            Map<String, Object> params = Map.of(
                "action", "create", "name", "My Agent", "system_prompt", "You are helpful."
            );
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("CREATED");
        }

        @Test
        @DisplayName("create response echoes a SUMMARIZED tools_config (grants + list sizes), never the raw id lists")
        @SuppressWarnings("unchecked")
        void createEchoesSummarizedToolsConfig() {
            AgentEntity created = mockAgent(AGENT_ID, "My Agent");
            java.util.List<String> manyIds = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) manyIds.add(UUID.randomUUID().toString());
            Map<String, Object> toolsConfig = new java.util.LinkedHashMap<>();
            toolsConfig.put("mode", "custom");
            toolsConfig.put("tablesGrant", "custom");
            toolsConfig.put("tables", manyIds);
            toolsConfig.put("workflowsGrant", "none");
            toolsConfig.put("webSearch", true);
            created.setToolsConfig(toolsConfig);
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

            Optional<ToolExecutionResult> result = module.execute("create",
                Map.of("action", "create", "name", "My Agent", "system_prompt", "hi"), TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.get().toMap().get("data");
            Map<String, Object> echoed = (Map<String, Object>) data.get("tools_config");
            // Summary shape: grants + sizes, actionable for the LLM without the noise.
            assertThat(echoed).containsEntry("mode", "custom");
            assertThat(echoed).containsEntry("tablesGrant", "custom");
            assertThat(echoed).containsEntry("tables_count", 40);
            assertThat(echoed).containsEntry("workflowsGrant", "none");
            assertThat(echoed).containsEntry("webSearch", true);
            // The raw 40-id list must NOT be echoed back (that is what get is for).
            assertThat(echoed).doesNotContainKey("tables");
            assertThat(echoed.toString()).doesNotContain(manyIds.get(0));
        }

        @Test
        @DisplayName("an invalid avatar returns a clean failure (validation caught, createAgent never called)")
        void createWithInvalidAvatarFailsCleanly() {
            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "A", "system_prompt", "hi", "avatar", "preset:crimson"));

            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Unknown avatar preset");
            verify(agentService, never()).createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a valid recolored preset avatar is passed through to createAgent")
        void createPassesRecoloredAvatarThrough() {
            AgentEntity created = mockAgent(AGENT_ID, "A");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), eq("preset:blue?c1=FF6600&c2=003366"),
                any(), any(), any(), any(), any())).thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "A", "system_prompt", "hi",
                "avatar", "preset:blue?c1=FF6600&c2=003366"));

            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            verify(agentService).createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), eq("preset:blue?c1=FF6600&c2=003366"),
                any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Fails without name")
        void createFailsWithoutName() {
            Map<String, Object> params = Map.of(
                "action", "create", "system_prompt", "hello"
            );
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().toMap().toString()).contains("name");
        }

        @Test
        @DisplayName("Fails without system_prompt")
        void createFailsWithoutPrompt() {
            Map<String, Object> params = Map.of(
                "action", "create", "name", "Agent"
            );
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().toMap().toString()).contains("system_prompt");
        }

        @Test
        @DisplayName("Assigns skills if skill_ids provided")
        void createWithSkills() {
            AgentEntity created = mockAgent(AGENT_ID, "Skilled Agent");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

            UUID skillId = UUID.randomUUID();
            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Skilled Agent", "system_prompt", "hello",
                "skill_ids", List.of(skillId.toString())
            ));
            module.execute("create", params, TENANT, ctx());

            verify(skillService).setAgentSkills(eq(AGENT_ID), eq(TENANT), anyList());
        }

        @Test
        @DisplayName("V340: an agent can create a backlog-worker - backlog_enabled=true applies setBacklogEnabled(true)")
        void createWithBacklogEnabled() {
            AgentEntity created = mockAgent(AGENT_ID, "Worker");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);
            when(agentService.setBacklogEnabled(eq(AGENT_ID), eq(TENANT), isNull(), eq(true)))
                .thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Worker", "system_prompt", "hello",
                "backlog_enabled", true));
            module.execute("create", params, TENANT, ctx());

            verify(agentService).setBacklogEnabled(eq(AGENT_ID), eq(TENANT), isNull(), eq(true));
        }

        @Test
        @DisplayName("V340: backlog_enabled absent on create → setBacklogEnabled never called (stays default false)")
        void createWithoutBacklogEnabledLeavesDefault() {
            AgentEntity created = mockAgent(AGENT_ID, "Worker");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

            Map<String, Object> params = Map.of(
                "action", "create", "name", "Worker", "system_prompt", "hello");
            module.execute("create", params, TENANT, ctx());

            verify(agentService, never()).setBacklogEnabled(any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Grant: *_grant params put the explicit GRANT sentinel in toolsConfig - the only way to express grant='all' via the agent tool")
        void createWithExplicitGrants() {
            AgentEntity created = mockAgent(AGENT_ID, "Builder");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Builder", "system_prompt", "hello",
                "workflows_grant", "all", "tables_grant", "custom", "tables", List.of(7),
                "interfaces_grant", "none"));
            module.execute("create", params, TENANT, ctx());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> toolsConfig = ArgumentCaptor.forClass(Map.class);
            verify(agentService).createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), toolsConfig.capture(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            Map<String, Object> tc = toolsConfig.getValue();
            assertThat(tc).containsEntry("workflowsGrant", "all");   // grant='all' now expressible via MCP (the fix)
            assertThat(tc).containsEntry("tablesGrant", "custom");
            assertThat(tc).containsEntry("interfacesGrant", "none");
        }

        @Test
        @DisplayName("Grant: omitting *_grant leaves NO grant key in toolsConfig - normalizeToolsConfig derives it from the list downstream")
        void createWithoutGrantParamsOmitsGrantKeys() {
            AgentEntity created = mockAgent(AGENT_ID, "Plain");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Plain", "system_prompt", "hello",
                "workflows", List.of(UUID.randomUUID().toString())));
            module.execute("create", params, TENANT, ctx());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> toolsConfig = ArgumentCaptor.forClass(Map.class);
            verify(agentService).createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), toolsConfig.capture(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            assertThat(toolsConfig.getValue()).doesNotContainKey("workflowsGrant"); // derivation deferred to normalize
        }

        @Test
        @DisplayName("Grant: an invalid grant value is DROPPED (not stored) - only none/all/custom are honored, never a garbage sentinel")
        void createWithInvalidGrantDropsIt() {
            AgentEntity created = mockAgent(AGENT_ID, "Edge");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Edge", "system_prompt", "hello",
                "workflows_grant", "bogus", "tables_grant", "all"));
            module.execute("create", params, TENANT, ctx());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> toolsConfig = ArgumentCaptor.forClass(Map.class);
            verify(agentService).createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), toolsConfig.capture(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            Map<String, Object> tc = toolsConfig.getValue();
            assertThat(tc).doesNotContainKey("workflowsGrant");      // 'bogus' dropped, not persisted
            assertThat(tc).containsEntry("tablesGrant", "all");      // valid sibling still honored
        }
    }

    @Nested
    @DisplayName("Get")
    class Get {

        @Test
        @DisplayName("Returns agent details")
        void getSuccess() {
            AgentEntity agent = mockAgent(AGENT_ID, "Test Agent");
            agent.setDescription("A test agent");
            agent.setSystemPrompt("Be helpful");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(agent));

            Map<String, Object> params = Map.of("action", "get", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("Test Agent");
        }

        @Test
        @DisplayName("Grant: the 'resources' summary is GRANT-aware - grant='all' shows '<resource> (all)' even though its id list is empty; 'none' is omitted")
        @SuppressWarnings("unchecked")
        void getResourcesSummaryIsGrantAware() {
            AgentEntity agent = mockAgent(AGENT_ID, "Builder");
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("mode", "all");
            tc.put("workflowsGrant", "all");   tc.put("workflows", List.of());   // all → list intentionally empty
            tc.put("tablesGrant", "custom");   tc.put("tables", List.of(1, 2));  // custom → specific ids
            tc.put("interfacesGrant", "none"); tc.put("interfaces", List.of());  // none → omitted
            agent.setToolsConfig(tc);
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(agent));

            Map<String, Object> params = Map.of("action", "get", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());

            Map<String, Object> data = (Map<String, Object>) result.get().toMap().get("data");
            Map<String, Object> resourcesObj = (Map<String, Object>) data.get("resources");
            List<String> resources = (List<String>) resourcesObj.get("resources");
            // Pre-fix: grant=all → empty list → omitted (reported as no access). Post-fix: "(all)".
            assertThat(resources).contains("workflow (all)", "table"); // grant=all surfaced; custom shown by name
            assertThat(resources).doesNotContain("interface", "interface (all)", "workflow"); // none omitted; not bare "workflow"
        }

        @Test
        @DisplayName("Fails without agent_id")
        void getFailsWithoutId() {
            Map<String, Object> params = Map.of("action", "get");
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
        }

        @Test
        @DisplayName("Fails when agent not found")
        void getFailsNotFound() {
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.empty());

            Map<String, Object> params = Map.of("action", "get", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().toMap().toString()).contains("not found");
        }

        @Test
        @DisplayName("Blocked by allowedAgentIds")
        void getBlockedByAllowedIds() {
            Map<String, Object> params = Map.of("action", "get", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT,
                ctxWithAllowedAgents(List.of("other-id")));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().toMap().toString()).contains("not in your approved");
        }
    }

    @Nested
    @DisplayName("List")
    class ListAgents {

        @Test
        @DisplayName("Returns paginated list")
        void listSuccess() {
            AgentEntity a1 = mockAgent(UUID.randomUUID(), "Agent 1");
            AgentEntity a2 = mockAgent(UUID.randomUUID(), "Agent 2");
            when(agentService.listAgents(TENANT, null, null)).thenReturn(List.of(a1, a2));

            Map<String, Object> params = Map.of("action", "list");
            Optional<ToolExecutionResult> result = module.execute("list", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("Agent 1", "Agent 2");
        }

        @Test
        @DisplayName("Filters by allowedAgentIds")
        void listFilteredByAllowed() {
            UUID allowed = UUID.randomUUID();
            UUID blocked = UUID.randomUUID();
            when(agentService.listAgents(TENANT, null, null))
                .thenReturn(List.of(mockAgent(allowed, "Allowed"), mockAgent(blocked, "Blocked")));

            Map<String, Object> params = Map.of("action", "list");
            Optional<ToolExecutionResult> result = module.execute("list", params, TENANT,
                ctxWithAllowedAgents(List.of(allowed.toString())));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("Allowed");
            // The blocked agent should be filtered out (total=1)
        }

        @Test
        @DisplayName("query filters agents by name OR description (case-insensitive) before pagination")
        @SuppressWarnings("unchecked")
        void queryFiltersByNameAndDescription() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            AgentEntity byName = mockAgent(a, "Invoice Assistant");
            AgentEntity byDesc = mockAgent(b, "Ops Bot");
            byDesc.setDescription("handles invoices too");
            AgentEntity noMatch = mockAgent(c, "Weather Bot");
            when(agentService.listAgents(TENANT, null, null))
                    .thenReturn(List.of(byName, byDesc, noMatch));

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of("query", "invoice"), TENANT, ctx()).get().data();
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("agents");
            assertThat(items).extracting(m -> m.get("id"))
                    .containsExactlyInAnyOrder(a.toString(), b.toString());
            assertThat(data.get("total")).isEqualTo(2L);
            assertThat(data.get("count")).isEqualTo(2);
        }

        @Test
        @DisplayName("query with no matches returns empty + broaden hint")
        @SuppressWarnings("unchecked")
        void queryNoMatchReturnsEmpty() {
            when(agentService.listAgents(TENANT, null, null))
                    .thenReturn(List.of(mockAgent(UUID.randomUUID(), "Invoice Assistant")));

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of("query", "zzz-no-such"), TENANT, ctx()).get().data();
            assertThat(data.get("count")).isEqualTo(0);
            assertThat(data.get("total")).isEqualTo(0L);
            Map<String, Object> hint = (Map<String, Object>) data.get("hint");
            assertThat(hint.get("action")).isEqualTo("broaden");
        }

        @Test
        @DisplayName("Emits canonical AgentListEnvelope keys (PR3 migration)")
        @SuppressWarnings("unchecked")
        void listEmitsCanonicalEnvelope() {
            AgentEntity a1 = mockAgent(UUID.randomUUID(), "A1");
            when(agentService.listAgents(TENANT, null, null)).thenReturn(List.of(a1));

            Optional<ToolExecutionResult> result = module.execute("list", Map.of(), TENANT, ctx());
            Map<String, Object> data = (Map<String, Object>) result.get().data();

            assertThat(data).containsKeys("status", "kind", "agents",
                    "count", "total", "offset", "limit", "hasMore");
            assertThat(data.get("kind")).isEqualTo("agents");
            assertThat(data.get("total")).isEqualTo(1L);
            // No more `message` field - replaced by `hint` when actionable, absent otherwise.
            assertThat(data).doesNotContainKey("message");
        }
    }

    @Nested
    @DisplayName("Update")
    class Update {

        @Test
        @DisplayName("Updates agent fields")
        void updateSuccess() {
            AgentEntity existing = mockAgent(AGENT_ID, "Old Name");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));

            AgentEntity updated = mockAgent(AGENT_ID, "New Name");
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), eq("New Name"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull()))
                .thenReturn(updated);

            Map<String, Object> params = Map.of("action", "update", "agent_id", AGENT_ID.toString(), "name", "New Name");
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("UPDATED");
        }

        @Test
        @DisplayName("update response echoes a SUMMARIZED tools_config (grants + list sizes), never the raw id lists")
        @SuppressWarnings("unchecked")
        void updateEchoesSummarizedToolsConfig() {
            AgentEntity existing = mockAgent(AGENT_ID, "Old Name");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));

            AgentEntity updated = mockAgent(AGENT_ID, "New Name");
            java.util.List<String> manyIds = new java.util.ArrayList<>();
            for (int i = 0; i < 25; i++) manyIds.add(UUID.randomUUID().toString());
            Map<String, Object> toolsConfig = new java.util.LinkedHashMap<>();
            toolsConfig.put("mode", "all");
            toolsConfig.put("workflowsGrant", "custom");
            toolsConfig.put("workflows", manyIds);
            updated.setToolsConfig(toolsConfig);
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenReturn(updated);

            Map<String, Object> params = Map.of("action", "update", "agent_id", AGENT_ID.toString(), "name", "New Name");
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.get().toMap().get("data");
            Map<String, Object> echoed = (Map<String, Object>) data.get("tools_config");
            assertThat(echoed).containsEntry("workflowsGrant", "custom");
            assertThat(echoed).containsEntry("workflows_count", 25);
            assertThat(echoed).doesNotContainKey("workflows");
            assertThat(echoed.toString()).doesNotContain(manyIds.get(0));
        }

        @Test
        @DisplayName("Fails when agent not found")
        void updateFailsNotFound() {
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.empty());

            Map<String, Object> params = Map.of("action", "update", "agent_id", AGENT_ID.toString(), "name", "X");
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().toMap().toString()).contains("not found");
        }

        @Test
        @DisplayName("V340: an agent can toggle a backlog-worker OFF - backlog_enabled=false applies setBacklogEnabled(false)")
        void updateWithBacklogEnabledFalse() {
            AgentEntity existing = mockAgent(AGENT_ID, "Worker");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            AgentEntity updated = mockAgent(AGENT_ID, "Worker");
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(updated);
            when(agentService.setBacklogEnabled(eq(AGENT_ID), eq(TENANT), isNull(), eq(false)))
                .thenReturn(updated);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "update", "agent_id", AGENT_ID.toString(), "backlog_enabled", false));
            module.execute("update", params, TENANT, ctx());

            verify(agentService).setBacklogEnabled(eq(AGENT_ID), eq(TENANT), isNull(), eq(false));
        }

        @Test
        @DisplayName("V340: backlog_enabled absent on update → setBacklogEnabled never called (flag unchanged)")
        void updateWithoutBacklogEnabledLeavesUnchanged() {
            AgentEntity existing = mockAgent(AGENT_ID, "Worker");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(mockAgent(AGENT_ID, "Worker"));

            Map<String, Object> params = Map.of("action", "update", "agent_id", AGENT_ID.toString(), "name", "Worker");
            module.execute("update", params, TENANT, ctx());

            verify(agentService, never()).setBacklogEnabled(any(), any(), any(), anyBoolean());
        }
    }

    // ==================== Merge / Resource Preservation Tests ====================

    @Nested
    @DisplayName("ToolsConfig patch shape forwarded to AgentService")
    class ToolsConfigMerge {
        // Architecture note: this nested class previously verified that
        // AgentCrudModule built a fully-merged toolsConfig before calling
        // AgentService.updateAgent. As of the "absent ≠ all" fix, MERGE +
        // normalize live in AgentService.updateAgent (single chokepoint
        // shared by REST PUT and the LLM tool path). AgentCrudModule now
        // forwards just the PATCH - these tests verify that contract.
        //
        // The merge semantics (preserves keys not in patch, backfills the 5
        // internal keys with []) are verified in AgentServiceTest:
        // restMergePreservesUnrelatedResourceKeys + updateNormalizesAbsentInternalKeysToEmpty.

        private AgentEntity agentWithResources() {
            AgentEntity e = mockAgent(AGENT_ID, "ResourceAgent");
            Map<String, Object> existing = new LinkedHashMap<>();
            existing.put("mode", "all");
            existing.put("workflows", List.of("wf-1", "wf-2"));
            existing.put("tables", List.of(1, 2, 3));
            existing.put("interfaces", List.of("iface-1"));
            existing.put("agents", List.of("agent-a"));
            existing.put("applications", List.of("app-1"));
            existing.put("webSearch", true);
            existing.put("maxIterations", 20);
            existing.put("tableAccessMode", "write");
            existing.put("workflowAccessMode", "read");
            e.setToolsConfig(existing);
            return e;
        }

        private void stubUpdateReturns(AgentEntity returned) {
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(returned);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> capturePatch() {
            var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(agentService).updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), captor.capture(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull());
            return (Map<String, Object>) captor.getValue();
        }

        @Test
        @DisplayName("Grant: *_grant on UPDATE forwards the explicit sentinel in the patch (update has its OWN param block - guards it from regressing)")
        void updateWithExplicitGrantsForwardsPatch() {
            AgentEntity existing = agentWithResources();
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            stubUpdateReturns(existing);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "update", "agent_id", AGENT_ID.toString(),
                "workflows_grant", "all", "interfaces_grant", "none"));
            module.execute("update", params, TENANT, ctx());

            Map<String, Object> patch = capturePatch();
            assertThat(patch).containsEntry("workflowsGrant", "all");   // 'all' reaches the patch via the update param block
            assertThat(patch).containsEntry("interfacesGrant", "none");
        }

        @Test
        @DisplayName("Name-only update sends null patch (AgentService preserves existing)")
        void nameOnlyForwardsNullPatch() {
            AgentEntity existing = agentWithResources();
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            stubUpdateReturns(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("name", "Renamed");

            module.execute("update", params, TENANT, ctx());

            // Empty patch → null → AgentService.updateAgent leaves toolsConfig untouched.
            assertThat(capturePatch()).isNull();
        }

        @Test
        @DisplayName("Single-resource update forwards a patch containing only that key")
        void partialResourceUpdateForwardsOnlyThatKey() {
            AgentEntity existing = agentWithResources();
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            stubUpdateReturns(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("tables", List.of(10, 20));

            module.execute("update", params, TENANT, ctx());

            Map<String, Object> patch = capturePatch();
            assertThat(patch).isNotNull();
            assertThat(patch.get("tables")).isEqualTo(List.of(10, 20));
            // Patch contains only the changed key - merge happens in AgentService
            assertThat(patch.containsKey("workflows")).isFalse();
            assertThat(patch.containsKey("interfaces")).isFalse();
            assertThat(patch.containsKey("agents")).isFalse();
            assertThat(patch.containsKey("applications")).isFalse();
            assertThat(patch.containsKey("mode")).isFalse();
        }

        @Test
        @DisplayName("tools_mode change forwards only the mode key")
        void changingModeForwardsOnlyMode() {
            AgentEntity existing = agentWithResources();
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            stubUpdateReturns(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("tools_mode", "none");

            module.execute("update", params, TENANT, ctx());

            Map<String, Object> patch = capturePatch();
            assertThat(patch.get("mode")).isEqualTo("none");
            assertThat(patch.containsKey("workflows")).isFalse();
            assertThat(patch.containsKey("tables")).isFalse();
        }

        @Test
        @DisplayName("tools_mode='off' forwards mode='off' - the no-tools (zero tool schemas) judge mode reaches the stored toolsConfig")
        void changingModeToOffForwardsOff() {
            AgentEntity existing = agentWithResources();
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            stubUpdateReturns(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("tools_mode", "off");

            module.execute("update", params, TENANT, ctx());

            Map<String, Object> patch = capturePatch();
            assertThat(patch.get("mode"))
                .as("tools_mode='off' must reach toolsConfig.mode so the agent advertises zero tools")
                .isEqualTo("off");
        }

        @Test
        @DisplayName("Access-mode change forwards only the access-mode key")
        void changingAccessModeForwardsOnlyAccessMode() {
            AgentEntity existing = agentWithResources();
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            stubUpdateReturns(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("table_access_mode", "read");

            module.execute("update", params, TENANT, ctx());

            Map<String, Object> patch = capturePatch();
            assertThat(patch.get("tableAccessMode")).isEqualTo("read");
            assertThat(patch.containsKey("tables")).isFalse();
            assertThat(patch.containsKey("workflowAccessMode")).isFalse();
        }

        @Test
        @DisplayName("file_access_mode is forwarded as fileAccessMode - an agent can make a child's file access read-only")
        void fileAccessModeForwardedFromAgentTool() {
            AgentEntity existing = agentWithResources();
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            stubUpdateReturns(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("file_access_mode", "read");

            module.execute("update", params, TENANT, ctx());

            Map<String, Object> patch = capturePatch();
            assertThat(patch.get("fileAccessMode")).isEqualTo("read");
            // Only the file mode is forwarded - no unrelated access-mode keys leak in.
            assertThat(patch.containsKey("tableAccessMode")).isFalse();
            assertThat(patch.containsKey("skillAccessMode")).isFalse();
        }

        @Test
        @DisplayName("Empty list [] in params is forwarded as [] (caller intent: clear access)")
        void emptyListForwardedExplicitly() {
            AgentEntity existing = agentWithResources();
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            stubUpdateReturns(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("tables", List.of());

            module.execute("update", params, TENANT, ctx());

            Map<String, Object> patch = capturePatch();
            assertThat((List<?>) patch.get("tables")).isEmpty();
            assertThat(patch.containsKey("workflows")).isFalse();
        }

        @Test
        @DisplayName("Multiple changes are batched into one patch")
        void multipleChangesBatched() {
            AgentEntity existing = agentWithResources();
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            stubUpdateReturns(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("tables", List.of(99));
            params.put("workflows", List.of("wf-new"));
            params.put("web_search", false);

            module.execute("update", params, TENANT, ctx());

            Map<String, Object> patch = capturePatch();
            assertThat(patch.get("tables")).isEqualTo(List.of(99));
            assertThat(patch.get("workflows")).isEqualTo(List.of("wf-new"));
            assertThat(patch.get("webSearch")).isEqualTo(false);
            // Untouched fields aren't in the patch
            assertThat(patch.containsKey("interfaces")).isFalse();
            assertThat(patch.containsKey("agents")).isFalse();
        }

        @Test
        @DisplayName("Update on agent with null prior toolsConfig forwards just the new patch (no NPE)")
        void updateFromNullToolsConfig() {
            AgentEntity existing = mockAgent(AGENT_ID, "Fresh");
            existing.setToolsConfig(null);
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            stubUpdateReturns(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("tables", List.of(1, 2));
            params.put("tools_mode", "custom");

            module.execute("update", params, TENANT, ctx());

            Map<String, Object> patch = capturePatch();
            assertThat(patch).isNotNull();
            assertThat(patch.get("tables")).isEqualTo(List.of(1, 2));
            assertThat(patch.get("mode")).isEqualTo("custom");
        }

        @Test
        @DisplayName("max_iterations is forwarded in the patch (override of existing happens in service)")
        void patchForwardsMaxIterations() {
            AgentEntity existing = agentWithResources();
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            stubUpdateReturns(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("max_iterations", 50);

            module.execute("update", params, TENANT, ctx());

            Map<String, Object> patch = capturePatch();
            assertThat(patch.get("maxIterations")).isEqualTo(50);
        }
    }

    // ==================== Field preservation in AgentService.updateAgent ====================

    @Nested
    @DisplayName("Field preservation: null params keep existing values")
    class FieldPreservation {

        @Test
        @DisplayName("Name falls back to existing when not provided")
        void nameDefaultsToExisting() {
            AgentEntity existing = mockAgent(AGENT_ID, "Keep This Name");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));

            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), eq("Keep This Name"),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            // No name in params - should use existing
            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("description", "New desc");

            module.execute("update", params, TENANT, ctx());

            // Verify name passed to service is the existing name
            verify(agentService).updateAgent(eq(AGENT_ID), eq(TENANT), eq("Keep This Name"),
                eq("New desc"), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull());
        }

        @Test
        @DisplayName("conversationId preserved when not in params")
        void conversationIdPreserved() {
            UUID convId = UUID.randomUUID();
            AgentEntity existing = mockAgent(AGENT_ID, "WithConv");
            existing.setConversationId(convId);
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), eq(convId),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("name", "Updated");

            module.execute("update", params, TENANT, ctx());

            // conversationId should be the existing one
            verify(agentService).updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), eq(convId),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull());
        }

        @Test
        @DisplayName("creditBudget preserved when not in params")
        void creditBudgetPreserved() {
            AgentEntity existing = mockAgent(AGENT_ID, "WithBudget");
            existing.setCreditBudget(BigDecimal.valueOf(100));
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(BigDecimal.valueOf(100)), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("name", "Updated");

            module.execute("update", params, TENANT, ctx());

            verify(agentService).updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(BigDecimal.valueOf(100)), any(),
                isNull(), isNull());
        }

        @Test
        @DisplayName("workflowId preserved when not in params")
        void workflowIdPreserved() {
            UUID wfId = UUID.randomUUID();
            AgentEntity existing = mockAgent(AGENT_ID, "WithWf");
            existing.setWorkflowId(wfId);
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), eq(wfId), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("name", "Updated");

            module.execute("update", params, TENANT, ctx());

            verify(agentService).updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), eq(wfId), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull());
        }
    }

    @Nested
    @DisplayName("Credentials vs Variables (regression)")
    class CredentialsRegression {

        @Test
        @DisplayName("allowedAgentIds must be read from credentials, not variables")
        void allowedAgentIdsFromCredentials() {
            // Put allowedAgentIds in credentials (where AgentToolsController puts it)
            ToolExecutionContext ctxCredentials = new ToolExecutionContext(
                TENANT, Map.of("allowedAgentIds", List.of("other-id")), Map.of(), null, null, null, null, null);

            Map<String, Object> params = Map.of("action", "get", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctxCredentials);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("not in your approved");
        }

        @Test
        @DisplayName("namespaced allowedAgentIds must restrict agent reads")
        void namespacedAllowedAgentIdsRestrictsReads() {
            ToolExecutionContext ctxCredentials = new ToolExecutionContext(
                TENANT, Map.of("__allowedAgentIds__", List.of("other-id")), Map.of(), null, null, null, null, null);

            Map<String, Object> params = Map.of("action", "get", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctxCredentials);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("not in your approved");
        }

        @Test
        @DisplayName("allowedAgentIds in variables should NOT restrict (only credentials matter)")
        void allowedAgentIdsInVariablesIgnored() {
            // Put allowedAgentIds in variables - should be ignored
            ToolExecutionContext ctxVars = new ToolExecutionContext(
                TENANT, Map.of(), Map.of("allowedAgentIds", List.of("other-id")), null, null, null, null, null);

            AgentEntity agent = mockAgent(AGENT_ID, "Test");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(agent));

            Map<String, Object> params = Map.of("action", "get", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctxVars);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue(); // Not blocked - variables ignored
        }

        @Test
        @DisplayName("turnId must be read from credentials for rate limiting")
        void turnIdFromCredentials() {
            // Put turnId in credentials (where AgentToolsController puts it)
            ToolExecutionContext ctxWithTurn = new ToolExecutionContext(
                TENANT, Map.of("turnId", "turn-rate-test"), Map.of(), null, null, null, null, null);

            // Create MAX_CREATES_PER_CONVERSATION + 1 agents to trigger rate limit
            AgentEntity created = mockAgent(UUID.randomUUID(), "Agent");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Agent", "system_prompt", "hello"
            ));

            // Create 5 agents (max limit)
            for (int i = 0; i < 5; i++) {
                Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctxWithTurn);
                assertThat(result.get().success()).isTrue();
            }

            // 6th should be rate-limited
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctxWithTurn);
            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("LIMIT REACHED");
        }

        @Test
        @DisplayName("delete refunds a slot so a maxed-out turn can create again")
        void deleteRefundsCreateSlot() {
            // Same turnId for the whole test - emulates all calls happening within one user turn
            ToolExecutionContext ctxWithTurn = new ToolExecutionContext(
                TENANT, Map.of("turnId", "refund-turn"), Map.of(), null, null, null, null, null);

            AgentEntity created = mockAgent(UUID.randomUUID(), "Agent");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(created);
            when(agentService.getAgent(any(), eq(TENANT), isNull())).thenReturn(Optional.of(created));

            Map<String, Object> createParams = new HashMap<>(Map.of(
                "action", "create", "name", "Agent", "system_prompt", "hello"
            ));

            // Max out the per-turn cap (5 creates)
            for (int i = 0; i < 5; i++) {
                Optional<ToolExecutionResult> r = module.execute("create", createParams, TENANT, ctxWithTurn);
                assertThat(r.get().success()).isTrue();
            }

            // 6th create must be blocked
            Optional<ToolExecutionResult> blocked = module.execute("create", createParams, TENANT, ctxWithTurn);
            assertThat(blocked.get().success()).isFalse();
            assertThat(blocked.get().error()).contains("LIMIT REACHED");

            // Delete one agent - refunds a slot
            Map<String, Object> deleteParams = Map.of(
                "action", "delete", "agent_id", created.getId().toString());
            Optional<ToolExecutionResult> deleted = module.execute("delete", deleteParams, TENANT, ctxWithTurn);
            assertThat(deleted.get().success()).isTrue();

            // Next create in the same turn must now succeed
            Optional<ToolExecutionResult> afterRefund = module.execute("create", createParams, TENANT, ctxWithTurn);
            assertThat(afterRefund.get().success()).isTrue();

            // But a second create without another delete must fail again
            Optional<ToolExecutionResult> reblocked = module.execute("create", createParams, TENANT, ctxWithTurn);
            assertThat(reblocked.get().success()).isFalse();
            assertThat(reblocked.get().error()).contains("LIMIT REACHED");
        }

        @Test
        @DisplayName("turnId in variables should NOT trigger rate limiting")
        void turnIdInVariablesNoRateLimit() {
            // Put turnId only in variables - should be ignored, no rate limiting
            ToolExecutionContext ctxVarsTurn = new ToolExecutionContext(
                TENANT, Map.of(), Map.of("turnId", "turn-var-test"), null, null, null, null, null);

            AgentEntity created = mockAgent(UUID.randomUUID(), "Agent");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Agent", "system_prompt", "hello"
            ));

            // Create 6 agents - should all succeed since turnId is in variables (ignored)
            for (int i = 0; i < 6; i++) {
                Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctxVarsTurn);
                assertThat(result.get().success()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("CreditBudget")
    class CreditBudget {

        @Test
        @DisplayName("Create with credit_budget returns nested budget map")
        void createWithBudget() {
            AgentEntity created = mockAgent(AGENT_ID, "Budget Agent");
            created.setCreditBudget(BigDecimal.TEN);
            created.setCreditsConsumed(BigDecimal.ZERO);
            created.setCreditsReserved(BigDecimal.ZERO);
            created.setBudgetResetMode("cumulative");

            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Budget Agent", "system_prompt", "hello",
                "credit_budget", BigDecimal.TEN, "budget_reset_mode", "cumulative"
            ));
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().toMap().get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> budget = (Map<String, Object>) data.get("budget");
            assertThat(budget).isNotNull();
            assertThat(budget).containsEntry("unlimited", false);
            assertThat(budget).containsEntry("total", BigDecimal.TEN);
            assertThat(budget).containsEntry("consumed", BigDecimal.ZERO);
            assertThat(budget).containsEntry("reserved_for_subagents", BigDecimal.ZERO);
            assertThat(budget).containsEntry("free", BigDecimal.TEN);
            assertThat(budget).containsEntry("reset_mode", "cumulative");
        }

        @Test
        @DisplayName("Get agent with credit_budget returns nested budget map with free = total - consumed - reserved")
        void getWithBudget() {
            AgentEntity agent = mockAgent(AGENT_ID, "Budget Agent");
            agent.setCreditBudget(BigDecimal.valueOf(50));
            agent.setCreditsConsumed(BigDecimal.valueOf(12));
            agent.setCreditsConsumedFromSubagents(BigDecimal.valueOf(7));
            agent.setCreditsReserved(BigDecimal.valueOf(8));
            agent.setBudgetResetMode("cumulative");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(agent));

            Map<String, Object> params = Map.of("action", "get", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().toMap().get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> budget = (Map<String, Object>) data.get("budget");
            assertThat(budget).isNotNull();
            assertThat(budget).containsEntry("unlimited", false);
            assertThat(budget).containsEntry("total", BigDecimal.valueOf(50));
            assertThat(budget).containsEntry("consumed", BigDecimal.valueOf(12));
            // Cascade breakdown: 7 came from sub-agent settles, 5 is the agent's own LLM spend.
            // consumed_own is derived, never read from DB - verify the subtraction is correct.
            assertThat(budget).containsEntry("consumed_from_subagents", BigDecimal.valueOf(7));
            assertThat(budget).containsEntry("consumed_own", BigDecimal.valueOf(5));
            assertThat(budget).containsEntry("reserved_for_subagents", BigDecimal.valueOf(8));
            assertThat(budget).containsEntry("free", BigDecimal.valueOf(30)); // 50 - 12 - 8
            assertThat(budget).containsEntry("reset_mode", "cumulative");
        }

        @Test
        @DisplayName("Get agent clamps consumed_own at zero when DB invariant is violated")
        void getClampsConsumedOwnAtZero() {
            // Defensive: if consumed_from_subagents > consumed (manual DB fix-up or partial reset),
            // consumed_own must not go negative. We'd rather surface 0 than a nonsense value.
            AgentEntity agent = mockAgent(AGENT_ID, "Weird state");
            agent.setCreditBudget(BigDecimal.valueOf(50));
            agent.setCreditsConsumed(BigDecimal.valueOf(3));
            agent.setCreditsConsumedFromSubagents(BigDecimal.valueOf(10)); // > consumed, violates invariant
            agent.setCreditsReserved(BigDecimal.ZERO);
            agent.setBudgetResetMode("cumulative");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(agent));

            Optional<ToolExecutionResult> result = module.execute(
                "get", Map.of("action", "get", "agent_id", AGENT_ID.toString()), TENANT, ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().toMap().get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> budget = (Map<String, Object>) data.get("budget");
            assertThat(budget).containsEntry("consumed_own", BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Get agent without credit_budget returns unlimited=true with null total/free")
        void getWithoutBudget() {
            AgentEntity agent = mockAgent(AGENT_ID, "No Budget Agent");
            // creditBudget is null by default → unlimited agent
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(agent));

            Map<String, Object> params = Map.of("action", "get", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().toMap().get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> budget = (Map<String, Object>) data.get("budget");
            assertThat(budget).isNotNull();
            assertThat(budget).containsEntry("unlimited", true);
            assertThat(budget).containsEntry("total", null);
            assertThat(budget).containsEntry("free", null);
            assertThat(budget).containsEntry("reserved_for_subagents", BigDecimal.ZERO);
            assertThat(budget).containsEntry("reset_mode", "cumulative");
            // Flat legacy keys must NOT appear - LLM/frontend accessors are `budget.*` only.
            assertThat(data).doesNotContainKeys("credit_budget", "credits_consumed", "budget_reset_mode");
        }

        @Test
        @DisplayName("Get agent clamps free at zero when consumed+reserved exceeds total")
        void getClampsFreeToZero() {
            AgentEntity agent = mockAgent(AGENT_ID, "Overspent");
            agent.setCreditBudget(BigDecimal.valueOf(100));
            agent.setCreditsConsumed(BigDecimal.valueOf(80));
            agent.setCreditsReserved(BigDecimal.valueOf(40));  // over-reserved
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(agent));

            Optional<ToolExecutionResult> result = module.execute(
                "get", Map.of("action", "get", "agent_id", AGENT_ID.toString()), TENANT, ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().toMap().get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> budget = (Map<String, Object>) data.get("budget");
            assertThat(budget).containsEntry("free", BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Update with credit_budget returns nested budget map")
        void updateWithBudget() {
            AgentEntity existing = mockAgent(AGENT_ID, "Old Name");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));

            AgentEntity updated = mockAgent(AGENT_ID, "Old Name");
            updated.setCreditBudget(BigDecimal.valueOf(25));
            updated.setCreditsConsumed(BigDecimal.ZERO);
            updated.setCreditsReserved(BigDecimal.ZERO);
            updated.setBudgetResetMode("cumulative");
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), isNull(), isNull()))
                .thenReturn(updated);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "update", "agent_id", AGENT_ID.toString(),
                "credit_budget", BigDecimal.valueOf(25), "budget_reset_mode", "cumulative"
            ));
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().toMap().get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> budget = (Map<String, Object>) data.get("budget");
            assertThat(budget).isNotNull();
            assertThat(budget).containsEntry("unlimited", false);
            assertThat(budget).containsEntry("total", BigDecimal.valueOf(25));
            assertThat(budget).containsEntry("consumed", BigDecimal.ZERO);
            assertThat(budget).containsEntry("reserved_for_subagents", BigDecimal.ZERO);
            assertThat(budget).containsEntry("free", BigDecimal.valueOf(25));
            assertThat(budget).containsEntry("reset_mode", "cumulative");
        }
    }

    @Nested
    @DisplayName("Delete")
    class Delete {

        @Test
        @DisplayName("Deletes agent by ID")
        void deleteSuccess() {
            AgentEntity existing = mockAgent(AGENT_ID, "ToDelete");
            when(agentService.getAgent(AGENT_ID, TENANT, null)).thenReturn(Optional.of(existing));

            Map<String, Object> params = Map.of("action", "delete", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("delete", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("DELETED");
            verify(agentService).deleteAgent(AGENT_ID, TENANT, null, null);
        }

        @Test
        @DisplayName("Blocked by allowedAgentIds")
        void deleteBlockedByAllowed() {
            Map<String, Object> params = Map.of("action", "delete", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("delete", params, TENANT,
                ctxWithAllowedAgents(List.of("other")));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            verify(agentService, never()).deleteAgent(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Org Filtering")
    class OrgFiltering {

        @Test
        @DisplayName("list passes orgId/orgRole to service")
        void listPassesOrgContext() {
            when(agentService.listAgents(TENANT, "org-abc", "MEMBER")).thenReturn(List.of());

            var ctx = new ToolExecutionContext(TENANT,
                Map.of("turnId", "t1"), Map.of(), null, null, null, "org-abc", "MEMBER");
            module.execute("list", Map.of(), TENANT, ctx);

            verify(agentService).listAgents(TENANT, "org-abc", "MEMBER");
        }

        @Test
        @DisplayName("list without org context passes null")
        void listNoOrgContext() {
            when(agentService.listAgents(TENANT, null, null)).thenReturn(List.of());

            module.execute("list", Map.of(), TENANT, ctx());

            verify(agentService).listAgents(TENANT, null, null);
        }

        @Test
        @DisplayName("list with empty allowedAgentIds returns zero agents")
        void listModeNoneReturnsEmpty() {
            AgentEntity a1 = mockAgent(UUID.randomUUID(), "Agent 1");
            when(agentService.listAgents(eq(TENANT), any(), any())).thenReturn(List.of(a1));

            var ctx = new ToolExecutionContext(TENANT,
                Map.of("allowedAgentIds", List.of(), "turnId", "t1"),
                Map.of(), null, null, null, null, null);
            var result = module.execute("list", Map.of(), TENANT, ctx);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().data().toString()).contains("total=0");
        }

        @Test
        @DisplayName("get with empty allowedAgentIds rejects")
        void getModeNoneRejects() {
            var ctx = new ToolExecutionContext(TENANT,
                Map.of("allowedAgentIds", List.of(), "turnId", "t1"),
                Map.of(), null, null, null, null, null);
            var result = module.execute("get",
                Map.of("agent_id", AGENT_ID.toString()), TENANT, ctx);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("not in your approved agent list");
        }

        @Test
        @DisplayName("get threads ctx orgId/orgRole into the 4-arg getAgent so an org-scoped agent stays visible (org-scoping invariant)")
        void getPassesOrgContextToFourArgGetAgent() {
            // When the caller carries an org context, executeGet MUST resolve the agent
            // via the org-aware 4-arg overload. Pre-fix it used the 2-arg form, which
            // treats orgId=null as "personal workspace" and rejects org-scoped rows
            // (false "Agent not found" - prod fire 2026-05-20). Stub ONLY the 4-arg
            // overload: if executeGet wrongly fell back to the 2-arg form the result
            // would be empty and the assertion below would fail.
            AgentEntity agent = mockAgent(AGENT_ID, "Org Agent");
            when(agentService.getAgent(AGENT_ID, TENANT, "org-77", "MEMBER"))
                .thenReturn(Optional.of(agent));

            var orgCtx = new ToolExecutionContext(TENANT,
                Map.of("turnId", "t1"), Map.of(), null, null, null, "org-77", "MEMBER");
            var result = module.execute("get",
                Map.of("agent_id", AGENT_ID.toString()), TENANT, orgCtx);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("Org Agent");
            // The org-scoped overload was the one consulted; the bare 2-arg form was not.
            verify(agentService).getAgent(AGENT_ID, TENANT, "org-77", "MEMBER");
            verify(agentService, never()).getAgent(AGENT_ID, TENANT);
        }

        @Test
        @DisplayName("get without org context falls back to the 2-arg getAgent (back-compat for null-org callers)")
        void getWithoutOrgContextUsesTwoArgGetAgent() {
            // The fallback branch: when orgId is null the 4-arg overload must NOT be
            // invoked, so pre-org-scoping stubs (and personal-workspace callers) keep
            // working unchanged against the 2-arg form.
            AgentEntity agent = mockAgent(AGENT_ID, "Personal Agent");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(agent));

            var result = module.execute("get",
                Map.of("agent_id", AGENT_ID.toString()), TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(agentService).getAgent(AGENT_ID, TENANT);
            verify(agentService, never()).getAgent(any(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("Model Catalog Validation")
    class ModelCatalogValidation {

        @Test
        @DisplayName("create substitutes unknown provider/model pair with catalog default and surfaces model_substituted")
        void createSubstitutesUnknownModel() {
            // Requested pair (openai/gpt-5-ultra) is invalid. The catalog default
            // (anthropic/claude-sonnet-4-6 from the lenient stub at setUp) is
            // valid, so create must succeed with the default substituted in and
            // emit a model_substituted notice - never silently mismatch.
            when(modelCatalogService.isModelAvailable("openai", "gpt-5-ultra")).thenReturn(false);
            AgentEntity created = mockAgent(UUID.randomUUID(), "Bad Agent");
            created.setModelProvider("anthropic");
            created.setModelName("claude-sonnet-4-6");
            when(agentService.createAgent(any(), any(), any(), any(), eq("anthropic"), eq("claude-sonnet-4-6"),
                any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any())).thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Bad Agent", "system_prompt", "hello",
                "model_provider", "openai", "model_name", "gpt-5-ultra"
            ));
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data.get("model_provider")).isEqualTo("anthropic");
            assertThat(data.get("model_name")).isEqualTo("claude-sonnet-4-6");
            @SuppressWarnings("unchecked")
            Map<String, String> sub = (Map<String, String>) data.get("model_substituted");
            assertThat(sub).isNotNull();
            assertThat(sub.get("requested")).isEqualTo("openai/gpt-5-ultra");
            assertThat(sub.get("actual")).isEqualTo("anthropic/claude-sonnet-4-6");
            assertThat(sub.get("reason")).contains("not available");
            // AgentService.createAgent must have been called with the substituted pair.
            verify(agentService).createAgent(any(), any(), any(), any(), eq("anthropic"), eq("claude-sonnet-4-6"),
                any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any());
        }

        @Test
        @DisplayName("create fails when both requested AND fallback default model are unavailable")
        void createRejectsWhenDefaultAlsoDisabled() {
            // Edge case: the requested pair AND the catalog's effective default
            // resolve to the SAME unavailable pair (admin disabled the yml
            // default after a deploy and didn't pick a new one). Substitution
            // can't rescue this - the failure must surface so the LLM gets the
            // full catalog and can pick something else explicitly.
            when(modelCatalogService.isModelAvailable("anthropic", "claude-sonnet-4-6")).thenReturn(false);
            when(modelCatalogService.listAvailableModels()).thenReturn(List.of(
                new AvailableModel("openai", "gpt-5", "top", 1)
            ));

            Map<String, Object> params = Map.of(
                "action", "create", "name", "Default Agent", "system_prompt", "hello"
            );
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("anthropic/claude-sonnet-4-6");
        }

        @Test
        @DisplayName("create substitutes when LLM pairs a model with the wrong provider")
        void createSubstitutesWrongProvider() {
            // Classic LLM error: pairs 'gpt-5' with 'anthropic'. Substitution
            // kicks in to the catalog default - no hint needed because the LLM
            // sees the swap directly in model_substituted.
            when(modelCatalogService.isModelAvailable("anthropic", "gpt-5")).thenReturn(false);
            AgentEntity created = mockAgent(UUID.randomUUID(), "Agent");
            created.setModelProvider("anthropic");
            created.setModelName("claude-sonnet-4-6");
            when(agentService.createAgent(any(), any(), any(), any(), eq("anthropic"), eq("claude-sonnet-4-6"),
                any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any())).thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Agent", "system_prompt", "hello",
                "model_provider", "anthropic", "model_name", "gpt-5"
            ));
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, String> sub = (Map<String, String>) data.get("model_substituted");
            assertThat(sub).isNotNull();
            assertThat(sub.get("requested")).isEqualTo("anthropic/gpt-5");
            assertThat(sub.get("actual")).isEqualTo("anthropic/claude-sonnet-4-6");
        }

        @Test
        @DisplayName("create with ONLY model_provider passed: model_substituted.requested shows '(none)' for the unspecified model_name - never the yml-default the LLM never typed")
        void createSubstitutionRequestedShowsRawLlmInputNotInferredDefault() {
            // Audit C MINOR 1 regression. Bug: when the LLM passes only
            // model_provider, the substitution notice used to display
            // `requested=openai/claude-sonnet-4-6` (claude-sonnet-4-6 being
            // the yml-default the resolver inferred to make a complete pair).
            // The LLM never typed claude-sonnet-4-6 and gets confused. Fix:
            // capture the RAW LLM input (with `(none)` placeholder for the
            // missing half) so the notice is honest.
            when(modelCatalogService.isModelAvailable("openai", "claude-sonnet-4-6")).thenReturn(false);
            AgentEntity created = mockAgent(UUID.randomUUID(), "Agent");
            created.setModelProvider("anthropic");
            created.setModelName("claude-sonnet-4-6");
            when(agentService.createAgent(any(), any(), any(), any(), eq("anthropic"), eq("claude-sonnet-4-6"),
                any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any())).thenReturn(created);

            // Only model_provider passed - model_name omitted entirely.
            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Agent", "system_prompt", "hello",
                "model_provider", "openai"
            ));
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, String> sub = (Map<String, String>) data.get("model_substituted");
            assertThat(sub).isNotNull();
            // The honest version: show what the LLM actually typed, not the
            // yml-default the validator silently inferred.
            assertThat(sub.get("requested")).isEqualTo("openai/(none)");
            // Belt-and-suspenders: assert the inferred default name does NOT
            // appear in `requested`. If a future refactor re-introduces the
            // pre-fix behaviour we'll catch it.
            assertThat(sub.get("requested")).doesNotContain("claude-sonnet-4-6");
        }

        @Test
        @DisplayName("create fails (no infinite-substitute loop) when LLM passes the EXACT same disabled pair the platform default points at")
        void createRejectsWhenRequestedEqualsDisabledDefault() {
            // Edge case from Audit A MINOR 8: the LLM explicitly passes
            // (provider, model) = the catalog's effective default, but the
            // admin disabled exactly that pair. Without a guard, the
            // substitution path would tag a `model_substituted: requested=X
            // → actual=X` notice - meaningless and confusing. The fallback
            // pair lookup must reject identically (same isModelAvailable miss),
            // and the failure path must surface a real error with the actionable
            // advice (call help_models or omit the fields).
            when(modelCatalogService.isModelAvailable("anthropic", "claude-sonnet-4-6")).thenReturn(false);
            when(modelCatalogService.listAvailableModels()).thenReturn(List.of(
                new AvailableModel("openai", "gpt-5", "top", 1)
            ));

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Agent", "system_prompt", "hello",
                "model_provider", "anthropic", "model_name", "claude-sonnet-4-6"
            ));
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            String err = result.get().error();
            assertThat(err).contains("anthropic/claude-sonnet-4-6");
            // Failure must point the agent at actionable next steps, not pretend
            // the call succeeded with the same disabled pair.
            assertThat(err).contains("help_models");
            // AgentService must never have been called - no agent created with
            // a disabled model.
            verify(agentService, never()).createAgent(any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
        }

        @Test
        @DisplayName("create with empty catalog tells the agent to stop and inform the user (no admin-UI references)")
        void createWithEmptyCatalog() {
            when(modelCatalogService.isModelAvailable(anyString(), anyString())).thenReturn(false);
            when(modelCatalogService.listAvailableModels()).thenReturn(List.of());

            Map<String, Object> params = Map.of(
                "action", "create", "name", "Agent", "system_prompt", "hello"
            );
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            String err = result.get().error();
            assertThat(err).contains("No models are currently enabled");
            // Per CLAUDE.md "Write from the Agent's POV": never reference admin
            // UI paths the agent cannot navigate. The agent's only useful action
            // here is to stop and tell the user - assert the error says exactly
            // that, and assert the old admin-UI hint is GONE.
            assertThat(err).contains("admin");
            assertThat(err).containsIgnoringCase("tell the user");
            assertThat(err).doesNotContain("/settings/");
            assertThat(err).doesNotContain("ai-providers");
        }

        @Test
        @DisplayName("create with NO model_provider/model_name uses platform default and emits NO substitution notice")
        void createPassesWithDefault() {
            // The LLM is allowed to omit both model fields. They resolve to the
            // catalog default and create succeeds - without model_substituted,
            // because no swap happened (the LLM didn't request anything specific
            // that needed correcting).
            AgentEntity created = mockAgent(UUID.randomUUID(), "Agent");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(created);

            Map<String, Object> params = Map.of(
                "action", "create", "name", "Agent", "system_prompt", "hello"
            );
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(modelCatalogService).isModelAvailable("anthropic", "claude-sonnet-4-6");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).doesNotContainKey("model_substituted");
        }

        @Test
        @DisplayName("update substitutes when switching to an unknown model - surfaces model_substituted")
        void updateSubstitutesUnknownModel() {
            AgentEntity existing = mockAgent(AGENT_ID, "Existing");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(modelCatalogService.isModelAvailable("openai", "gpt-99")).thenReturn(false);
            AgentEntity updated = mockAgent(AGENT_ID, "Existing");
            updated.setModelProvider("anthropic");
            updated.setModelName("claude-sonnet-4-6");
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(), any(), any(),
                eq("anthropic"), eq("claude-sonnet-4-6"), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(updated);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "update", "agent_id", AGENT_ID.toString(),
                "model_provider", "openai", "model_name", "gpt-99"
            ));
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, String> sub = (Map<String, String>) data.get("model_substituted");
            assertThat(sub).isNotNull();
            assertThat(sub.get("requested")).isEqualTo("openai/gpt-99");
            assertThat(sub.get("actual")).isEqualTo("anthropic/claude-sonnet-4-6");
        }

        @Test
        @DisplayName("update with only model_name uses existing provider for the pair check")
        void updatePartialUsesExistingProvider() {
            // Agent is openai/gpt-4 today, LLM only passes model_name='gpt-5'.
            // Validator should check (openai, gpt-5), not (null, gpt-5).
            AgentEntity existing = mockAgent(AGENT_ID, "Existing");
            existing.setModelProvider("openai");
            existing.setModelName("gpt-4");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(modelCatalogService.isModelAvailable("openai", "gpt-5")).thenReturn(true);

            AgentEntity updated = mockAgent(AGENT_ID, "Existing");
            updated.setModelProvider("openai");
            updated.setModelName("gpt-5");
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), isNull(), isNull())).thenReturn(updated);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "update", "agent_id", AGENT_ID.toString(), "model_name", "gpt-5"
            ));
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(modelCatalogService).isModelAvailable("openai", "gpt-5");
        }

        @Test
        @DisplayName("update with only model_provider validates the reconstructed pair, then substitutes if invalid")
        void updatePartialUsesExistingModelName() {
            // Symmetric counterpart of updatePartialUsesExistingProvider -
            // LLM passes only model_provider='anthropic' on an existing
            // openai/gpt-4 agent. Validator must check (anthropic, gpt-4)
            // NOT (anthropic, null). Then because (anthropic, gpt-4) is invalid,
            // substitution kicks in to the catalog default.
            AgentEntity existing = mockAgent(AGENT_ID, "Existing");
            existing.setModelProvider("openai");
            existing.setModelName("gpt-4");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(modelCatalogService.isModelAvailable("anthropic", "gpt-4")).thenReturn(false);
            AgentEntity updated = mockAgent(AGENT_ID, "Existing");
            updated.setModelProvider("anthropic");
            updated.setModelName("claude-sonnet-4-6");
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(), any(), any(),
                eq("anthropic"), eq("claude-sonnet-4-6"), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(updated);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "update", "agent_id", AGENT_ID.toString(), "model_provider", "anthropic"
            ));
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            // Validator was called with the RECONSTRUCTED pair, not (anthropic, null).
            verify(modelCatalogService).isModelAvailable("anthropic", "gpt-4");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, String> sub = (Map<String, String>) data.get("model_substituted");
            assertThat(sub).isNotNull();
            assertThat(sub.get("requested")).isEqualTo("anthropic/gpt-4");
        }

        @Test
        @DisplayName("update with blank model_name (only) treats it as no model change and skips validation")
        void updateBlankModelNameNormalised() {
            // Defensive check: LLM sends {"model_name": ""} (empty string).
            // With no provider also passed, there is effectively no model
            // change - blank must normalise to null and the skip-validation
            // path must fire. The previous (pre-normalisation) behaviour would
            // have called isModelAvailable(existingProvider, "") and produced
            // an ugly error like "anthropic/".
            AgentEntity existing = mockAgent(AGENT_ID, "Existing");
            existing.setModelProvider("openai");
            existing.setModelName("gpt-4");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));

            AgentEntity updated = mockAgent(AGENT_ID, "Renamed");
            when(agentService.updateAgent(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), isNull(), isNull())).thenReturn(updated);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "update", "agent_id", AGENT_ID.toString(),
                "name", "Renamed", "model_name", ""
            ));
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            // Blank → null → skipped: no call at all. The key invariant is
            // that isModelAvailable is NEVER invoked with an empty-string
            // model id, which would leak into the error message.
            verify(modelCatalogService, never()).isModelAvailable(anyString(), eq(""));
        }

        @Test
        @DisplayName("update with blank model_name + real model_provider validates against existing model_name then substitutes")
        void updateBlankModelNamePartialFallback() {
            // If the LLM sends a real provider but a blank model_name,
            // the blank must normalise to null and fall back to the existing
            // model_name - producing the pair (newProvider, existingModelName).
            // The substitution path then takes over because the reconstructed
            // pair is invalid, replacing it with the catalog default.
            AgentEntity existing = mockAgent(AGENT_ID, "Existing");
            existing.setModelProvider("openai");
            existing.setModelName("gpt-4");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(modelCatalogService.isModelAvailable("anthropic", "gpt-4")).thenReturn(false);
            AgentEntity updated = mockAgent(AGENT_ID, "Existing");
            updated.setModelProvider("anthropic");
            updated.setModelName("claude-sonnet-4-6");
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(), any(), any(),
                eq("anthropic"), eq("claude-sonnet-4-6"), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(updated);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "update", "agent_id", AGENT_ID.toString(),
                "model_provider", "anthropic", "model_name", ""
            ));
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            // Validator was called with (anthropic, gpt-4), NOT (anthropic, "").
            verify(modelCatalogService).isModelAvailable("anthropic", "gpt-4");
            verify(modelCatalogService, never()).isModelAvailable(anyString(), eq(""));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data.get("model_substituted")).isNotNull();
        }

        @Test
        @DisplayName("update with no model change skips validation entirely")
        void updateNoModelSkipsValidation() {
            AgentEntity existing = mockAgent(AGENT_ID, "Existing");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            AgentEntity updated = mockAgent(AGENT_ID, "Renamed");
            when(agentService.updateAgent(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), isNull(), isNull())).thenReturn(updated);

            Map<String, Object> params = Map.of(
                "action", "update", "agent_id", AGENT_ID.toString(), "name", "Renamed"
            );
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            // No model_provider / model_name passed → no validation call
            verify(modelCatalogService, never()).isModelAvailable(any(), any());
        }
    }

    // ==================== Schedule Cleanup Tests ====================

    @Nested
    @DisplayName("Schedule lifecycle during update")
    class ScheduleLifecycle {

        @Test
        @DisplayName("Update with schedule_cron creates schedule via trigger-service")
        void updateWithScheduleCronCreatesSchedule() {
            AgentEntity existing = mockAgent(AGENT_ID, "Scheduled");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("schedule_cron", "0 9 * * *");
            params.put("schedule_timezone", "Europe/Paris");

            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            // Verify POST to trigger-service to create/update schedule
            verify(restTemplate).exchange(
                eq("http://localhost:8091/api/internal/trigger/schedules/agent"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        }

        @Test
        @DisplayName("Update with cron only does NOT send schedule_prompt/with_memory in the POST body (prevents the 2026-06-14 clobber)")
        void updateCronOnly_omitsPromptAndMemoryFromBody() {
            AgentEntity existing = mockAgent(AGENT_ID, "Scheduled");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("schedule_cron", "0 * * * *");
            // No schedule_prompt, no schedule_memory - a cadence-only edit.

            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());
            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();

            ArgumentCaptor<org.springframework.http.HttpEntity<?>> entityCaptor =
                    ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(restTemplate).exchange(
                    eq("http://localhost:8091/api/internal/trigger/schedules/agent"),
                    eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
            // Pre-fix sent schedulePrompt="" + withMemory=false, which trigger-service then
            // persisted, wiping the stored prompt/memory. They must be absent so the
            // backend merge preserves the existing values.
            assertThat(body).doesNotContainKey("schedulePrompt");
            assertThat(body).doesNotContainKey("withMemory");
            assertThat(body.get("cronExpression")).isEqualTo("0 * * * *");
        }

        @Test
        @DisplayName("Update forwards schedule_prompt/with_memory when the caller supplies them")
        void updateWithPromptAndMemory_forwardsThemInBody() {
            AgentEntity existing = mockAgent(AGENT_ID, "Scheduled");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("schedule_cron", "0 * * * *");
            params.put("schedule_prompt", "Build one app");
            params.put("schedule_memory", true);

            module.execute("update", params, TENANT, ctx());

            ArgumentCaptor<org.springframework.http.HttpEntity<?>> entityCaptor =
                    ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(restTemplate).exchange(
                    eq("http://localhost:8091/api/internal/trigger/schedules/agent"),
                    eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
            assertThat(body.get("schedulePrompt")).isEqualTo("Build one app");
            assertThat(body.get("withMemory")).isEqualTo(true);
        }

        @Test
        @DisplayName("Update with empty schedule_cron deletes schedule via trigger-service")
        void updateWithEmptyScheduleCronDeletesSchedule() {
            AgentEntity existing = mockAgent(AGENT_ID, "Unschedule");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("schedule_cron", "");

            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            // Verify DELETE to trigger-service
            verify(restTemplate).exchange(
                eq("http://localhost:8091/api/internal/trigger/schedules/by-agent/" + AGENT_ID),
                eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class));
            // Verify NO POST was made (should not create a schedule)
            verify(restTemplate, never()).exchange(
                eq("http://localhost:8091/api/internal/trigger/schedules/agent"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        }

        @Test
        @DisplayName("Update with blank schedule_cron deletes schedule via trigger-service")
        void updateWithBlankScheduleCronDeletesSchedule() {
            AgentEntity existing = mockAgent(AGENT_ID, "Unschedule");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("schedule_cron", "   ");

            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            // Blank string is still non-null → triggers delete path
            verify(restTemplate).exchange(
                eq("http://localhost:8091/api/internal/trigger/schedules/by-agent/" + AGENT_ID),
                eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class));
        }

        @Test
        @DisplayName("Update without schedule_cron at all does nothing to schedule")
        void updateWithoutScheduleCronDoesNothing() {
            AgentEntity existing = mockAgent(AGENT_ID, "Untouched");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), eq("Renamed"),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            Map<String, Object> params = Map.of("action", "update", "agent_id", AGENT_ID.toString(), "name", "Renamed");
            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            // schedule_cron is null (not present) → no schedule create or delete
            verify(restTemplate, never()).exchange(
                contains("/schedules"), any(HttpMethod.class), any(), any(Class.class));
        }

        @Test
        @DisplayName("Schedule delete failure is silently ignored on update")
        void scheduleDeleteFailureIgnored() {
            AgentEntity existing = mockAgent(AGENT_ID, "Unschedule");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);
            when(restTemplate.exchange(contains("/schedules/by-agent/"), eq(HttpMethod.DELETE),
                any(), eq(Void.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("schedule_cron", "");

            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            // Update still succeeds even when schedule delete fails
            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
        }

        @Test
        @DisplayName("Schedule POST outbound headers carry X-User-ID + X-Organization-ID + X-Organization-Role - regression: 'Access restricted' on non-OWNER schedule fan-out (2026-05-21)")
        void scheduleCreateForwardsFullScopeHeaders() {
            AgentEntity existing = mockAgent(AGENT_ID, "ScheduledTeam");
            // 4-arg stub: AgentCrudModule.executeUpdate now passes ctx orgId/Role explicitly.
            when(agentService.getAgent(AGENT_ID, TENANT, "org-77", "MEMBER")).thenReturn(Optional.of(existing));
            // 23-arg updateAgent stub - last two: guardOverrides=null, callerOrgId="org-77" (from teamCtx).
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), eq("org-77")))
                .thenReturn(existing);

            // ctx carries orgId + orgRole - the buildScopeHeaders helper
            // must stamp them on the outbound RestTemplate exchange.
            ToolExecutionContext teamCtx = new ToolExecutionContext(TENANT,
                    Map.of("turnId", "turn-1"), Map.of(), null, null, null,
                    "org-77", "MEMBER");

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("schedule_cron", "0 9 * * *");

            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, teamCtx);
            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();

            ArgumentCaptor<org.springframework.http.HttpEntity<?>> entityCaptor =
                    ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(restTemplate).exchange(
                    eq("http://localhost:8091/api/internal/trigger/schedules/agent"),
                    eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
            org.springframework.http.HttpHeaders headers = entityCaptor.getValue().getHeaders();
            assertThat(headers.getFirst("X-User-ID")).isEqualTo(TENANT);
            assertThat(headers.getFirst("X-Organization-ID")).isEqualTo("org-77");
            assertThat(headers.getFirst("X-Organization-Role")).isEqualTo("MEMBER");
        }

        @Test
        @DisplayName("Schedule DELETE outbound headers carry X-User-ID + X-Organization-ID + X-Organization-Role - regression: schedule delete dropped role pre-2026-05-21")
        void scheduleDeleteForwardsFullScopeHeaders() {
            AgentEntity existing = mockAgent(AGENT_ID, "UnscheduleTeam");
            when(agentService.getAgent(AGENT_ID, TENANT, "org-77", "MEMBER")).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), eq("org-77")))
                .thenReturn(existing);

            ToolExecutionContext teamCtx = new ToolExecutionContext(TENANT,
                    Map.of("turnId", "turn-1"), Map.of(), null, null, null,
                    "org-77", "MEMBER");

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("schedule_cron", "");

            module.execute("update", params, TENANT, teamCtx);

            ArgumentCaptor<org.springframework.http.HttpEntity<?>> entityCaptor =
                    ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(restTemplate).exchange(
                    eq("http://localhost:8091/api/internal/trigger/schedules/by-agent/" + AGENT_ID),
                    eq(HttpMethod.DELETE), entityCaptor.capture(), eq(Void.class));
            org.springframework.http.HttpHeaders headers = entityCaptor.getValue().getHeaders();
            assertThat(headers.getFirst("X-User-ID")).isEqualTo(TENANT);
            assertThat(headers.getFirst("X-Organization-ID")).isEqualTo("org-77");
            assertThat(headers.getFirst("X-Organization-Role")).isEqualTo("MEMBER");
        }
    }

    // ==================== Webhook Lifecycle Tests ====================

    @Nested
    @DisplayName("Webhook lifecycle during update")
    class WebhookLifecycle {

        @Test
        @DisplayName("Update with webhook_enabled=true creates webhook")
        void updateWithWebhookEnabledCreatesWebhook() {
            AgentEntity existing = mockAgent(AGENT_ID, "WithWebhook");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            var webhookToken = new com.apimarketplace.agent.domain.AgentWebhookTokenEntity();
            webhookToken.setToken("ag_test123");
            when(webhookTokenService.createOrUpdateWebhook(eq(AGENT_ID), any(), any(), any(), anyBoolean()))
                .thenReturn(webhookToken);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("webhook_enabled", true);

            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(webhookTokenService).createOrUpdateWebhook(eq(AGENT_ID), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Update with webhook_enabled=false deletes webhook")
        void updateWithWebhookDisabledDeletesWebhook() {
            AgentEntity existing = mockAgent(AGENT_ID, "NoWebhook");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "update");
            params.put("agent_id", AGENT_ID.toString());
            params.put("webhook_enabled", false);

            Optional<ToolExecutionResult> result = module.execute("update", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(webhookTokenService).deleteWebhook(AGENT_ID);
        }

        @Test
        @DisplayName("Update without webhook_enabled does not touch webhook")
        void updateWithoutWebhookDoesNothing() {
            AgentEntity existing = mockAgent(AGENT_ID, "Untouched");
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
            when(agentService.updateAgent(eq(AGENT_ID), eq(TENANT), eq("Renamed"),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                isNull(), isNull()))
                .thenReturn(existing);

            Map<String, Object> params = Map.of("action", "update", "agent_id", AGENT_ID.toString(), "name", "Renamed");
            module.execute("update", params, TENANT, ctx());

            verify(webhookTokenService, never()).createOrUpdateWebhook(any(), any(), any(), any(), anyBoolean());
            verify(webhookTokenService, never()).deleteWebhook(any());
        }
    }

    // ==================== Delete delegates to AgentService ====================

    @Nested
    @DisplayName("Delete delegates cleanup to AgentService")
    class DeleteCleanup {

        @Test
        @DisplayName("Delete delegates to agentService.deleteAgent which owns all cleanup")
        void deleteDelegatesToService() {
            AgentEntity existing = mockAgent(AGENT_ID, "FullAgent");
            when(agentService.getAgent(AGENT_ID, TENANT, null)).thenReturn(Optional.of(existing));

            Map<String, Object> params = Map.of("action", "delete", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("delete", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(agentService).deleteAgent(AGENT_ID, TENANT, null, null);
            // CrudModule should NOT do cleanup itself - AgentService owns it
            verify(restTemplate, never()).exchange(contains("/schedules/"), any(HttpMethod.class), any(), any(Class.class));
            verify(webhookTokenService, never()).deleteWebhook(any());
        }
    }

    @Nested
    @DisplayName("DOC-drifts - max_iterations in get + credit_budget warn")
    class DocDrifts {

        @SuppressWarnings("unchecked")
        private Map<String, Object> data(ToolExecutionResult r) {
            return (Map<String, Object>) r.toMap().get("data");
        }

        @Test
        @DisplayName("DOC-4: get exposes max_iterations flat on the response")
        void getExposesMaxIterations() {
            AgentEntity agent = mockAgent(AGENT_ID, "IterAgent");
            agent.setMaxIterations(17);
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(agent));

            Map<String, Object> params = Map.of("action", "get", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(data(result.get())).containsEntry("max_iterations", 17);
        }

        @Test
        @DisplayName("DOC-4: get falls back to config default when entity's max_iterations is null")
        void getUsesDefaultMaxIterationsWhenNull() {
            AgentEntity agent = mockAgent(AGENT_ID, "DefaultIter");
            agent.setMaxIterations(null);
            when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(agent));

            Map<String, Object> params = Map.of("action", "get", "agent_id", AGENT_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("get", params, TENANT, ctx());

            assertThat(result).isPresent();
            Map<String, Object> body = data(result.get());
            assertThat(body).containsKey("max_iterations");
            assertThat((Integer) body.get("max_iterations")).isPositive();
        }

        @Test
        @DisplayName("DOC-5: create warns when credit_budget <= max_iterations (physically unreachable)")
        void createWarnsWhenBudgetTooLow() {
            AgentEntity created = mockAgent(AGENT_ID, "Tiny");
            created.setMaxIterations(5);
            created.setCreditBudget(BigDecimal.valueOf(3));
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Tiny", "system_prompt", "hello",
                "max_iterations", 5, "credit_budget", 3
            ));
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            List<String> warnings = (List<String>) data(result.get()).get("warnings");
            assertThat(warnings).isNotNull().hasSize(1);
            assertThat(warnings.get(0))
                .contains("credit_budget=3")
                .contains("max_iterations=5")
                .contains("BUDGET_EXHAUSTED");
        }

        @Test
        @DisplayName("DOC-5: create does NOT warn when credit_budget > max_iterations")
        void createSkipsWarningWhenBudgetHealthy() {
            AgentEntity created = mockAgent(AGENT_ID, "Healthy");
            created.setMaxIterations(5);
            created.setCreditBudget(BigDecimal.valueOf(100));
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Healthy", "system_prompt", "hello",
                "max_iterations", 5, "credit_budget", 100
            ));
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(data(result.get())).doesNotContainKey("warnings");
        }

        @Test
        @DisplayName("DOC-5: create does NOT warn when credit_budget is null (unlimited)")
        void createSkipsWarningForUnlimitedBudget() {
            AgentEntity created = mockAgent(AGENT_ID, "Unlimited");
            created.setMaxIterations(5);
            created.setCreditBudget(null);
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

            Map<String, Object> params = new HashMap<>(Map.of(
                "action", "create", "name", "Unlimited", "system_prompt", "hello",
                "max_iterations", 5
            ));
            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(data(result.get())).doesNotContainKey("warnings");
        }
    }

    // ==========================================================================
    // V100: per-agent unified maxPerResourcePerTurn override resolution
    // ==========================================================================
    @Nested
    @DisplayName("resolveMaxPerResourcePerTurn (V100 unified per-resource cap)")
    class ResolveMaxPerResourcePerTurn {

        @Test
        @DisplayName("Returns YAML default when no __agentId__ in credentials")
        void fallsBackWhenNoAgentId() {
            // ctx() has no __agentId__, AgentDefaultsConfig default = 5
            assertThat(module.resolveMaxPerResourcePerTurn(ctx())).isEqualTo(5);
        }

        @Test
        @DisplayName("Returns per-agent override when entity has non-null positive value")
        void usesPerAgentOverride() {
            UUID callerId = UUID.randomUUID();
            AgentEntity caller = mockAgent(callerId, "Caller");
            caller.setMaxPerResourcePerTurn(12);
            when(agentService.findById(callerId)).thenReturn(Optional.of(caller));

            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__agentId__", callerId.toString(), "turnId", "turn-x"),
                Map.of(), null, null, null, null, null);

            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(12);
        }

        @Test
        @DisplayName("Falls back to default when entity override is null")
        void fallsBackWhenOverrideNull() {
            UUID callerId = UUID.randomUUID();
            AgentEntity caller = mockAgent(callerId, "Caller");
            caller.setMaxPerResourcePerTurn(null);
            when(agentService.findById(callerId)).thenReturn(Optional.of(caller));

            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__agentId__", callerId.toString(), "turnId", "turn-x"),
                Map.of(), null, null, null, null, null);

            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(5);
        }

        @Test
        @DisplayName("Falls back to default when agent not found")
        void fallsBackWhenAgentMissing() {
            UUID callerId = UUID.randomUUID();
            when(agentService.findById(callerId)).thenReturn(Optional.empty());

            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__agentId__", callerId.toString(), "turnId", "turn-x"),
                Map.of(), null, null, null, null, null);

            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(5);
        }

        @Test
        @DisplayName("Falls back to default on lookup exception (soft-fail)")
        void softFailsOnException() {
            UUID callerId = UUID.randomUUID();
            when(agentService.findById(callerId)).thenThrow(new RuntimeException("DB down"));

            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__agentId__", callerId.toString(), "turnId", "turn-x"),
                Map.of(), null, null, null, null, null);

            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(5);
        }

        @Test
        @DisplayName("Falls back on malformed __agentId__ string")
        void fallsBackOnMalformedUuid() {
            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__agentId__", "not-a-uuid", "turnId", "turn-x"),
                Map.of(), null, null, null, null, null);

            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Compaction overrides (V350)")
    class Compaction {

        @Test
        @DisplayName("create routes native compaction params to setCompactionOverrides")
        void createForwardsCompaction() {
            AgentEntity created = mockAgent(AGENT_ID, "My Agent");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);
            when(agentService.setCompactionOverrides(eq(AGENT_ID), any(), any(),
                eq(true), eq(true), eq(true), eq(8))).thenReturn(created);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "create");
            params.put("name", "My Agent");
            params.put("system_prompt", "You are helpful.");
            params.put("compaction_enabled", true);
            params.put("compaction_after_turns", 8);

            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(agentService).setCompactionOverrides(eq(AGENT_ID), any(), any(),
                eq(true), eq(true), eq(true), eq(8));
        }

        @Test
        @DisplayName("create coerces string compaction params ('true' / '5')")
        void createCoercesStringCompaction() {
            AgentEntity created = mockAgent(AGENT_ID, "My Agent");
            when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);
            when(agentService.setCompactionOverrides(eq(AGENT_ID), any(), any(),
                eq(true), eq(true), eq(true), eq(5))).thenReturn(created);

            Map<String, Object> params = new HashMap<>();
            params.put("action", "create");
            params.put("name", "My Agent");
            params.put("system_prompt", "You are helpful.");
            params.put("compaction_enabled", "true");
            params.put("compaction_after_turns", "5");

            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(agentService).setCompactionOverrides(eq(AGENT_ID), any(), any(),
                eq(true), eq(true), eq(true), eq(5));
        }

        @Test
        @DisplayName("create with compaction_after_turns < 1 fails BEFORE creating the agent (no orphan)")
        void createRejectsInvalidCadence() {
            Map<String, Object> params = new HashMap<>();
            params.put("action", "create");
            params.put("name", "My Agent");
            params.put("system_prompt", "You are helpful.");
            params.put("compaction_after_turns", 0);

            Optional<ToolExecutionResult> result = module.execute("create", params, TENANT, ctx());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            verify(agentService, never()).createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(agentService, never()).setCompactionOverrides(any(), any(), any(),
                anyBoolean(), any(), anyBoolean(), any());
        }
    }

    @Nested
    @DisplayName("avatar param validation (MCP create/update)")
    class AvatarParam {
        @Test
        @DisplayName("null/blank passes through (create -> random preset; update -> unchanged)")
        void nullOrBlankPassesThrough() {
            assertThat(AgentCrudModule.validateAvatarParam(null)).isNull();
            assertThat(AgentCrudModule.validateAvatarParam("   ")).isNull();
        }

        @Test
        @DisplayName("known preset, its recolored form, and an http URL are accepted verbatim")
        void knownPresetAndCustomColorsAccepted() {
            assertThat(AgentCrudModule.validateAvatarParam("preset:blue")).isEqualTo("preset:blue");
            assertThat(AgentCrudModule.validateAvatarParam("preset:blue?c1=FF6600&c2=003366"))
                    .isEqualTo("preset:blue?c1=FF6600&c2=003366");
            assertThat(AgentCrudModule.validateAvatarParam("https://cdn/x.png")).isEqualTo("https://cdn/x.png");
        }

        @Test
        @DisplayName("unknown preset is rejected with the valid list (actionable for the agent)")
        void unknownPresetRejected() {
            assertThatThrownBy(() -> AgentCrudModule.validateAvatarParam("preset:crimson"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown avatar preset")
                    .hasMessageContaining("preset:blue");
        }

        @Test
        @DisplayName("malformed custom colors are rejected (bad hex, missing c2, empty/garbled query)")
        void malformedColorsRejected() {
            for (String bad : new String[] {
                    "preset:blue?c1=xyz&c2=003366",   // non-hex
                    "preset:blue?c1=FF6600",           // missing c2
                    "preset:blue?",                    // empty query
                    "preset:blue?c1=FF6600&c2=003366&x=1", // trailing garbage
                    // Param NAMES are lowercase: the frontend reads them case-sensitively, so
                    // 'C1=' would be stored but never render - reject with guidance instead.
                    "preset:blue?C1=FF6600&C2=003366",
            }) {
                assertThatThrownBy(() -> AgentCrudModule.validateAvatarParam(bad))
                        .as("should reject %s", bad)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("c1=RRGGBB");
            }
        }

        @Test
        @DisplayName("tool-badge forms are accepted verbatim (tool alone, colors + tool)")
        void toolBadgeFormsAccepted() {
            assertThat(AgentCrudModule.validateAvatarParam("preset:blue?tool=wrench"))
                    .isEqualTo("preset:blue?tool=wrench");
            assertThat(AgentCrudModule.validateAvatarParam("preset:blue?c1=FF6600&c2=003366&tool=git-branch"))
                    .isEqualTo("preset:blue?c1=FF6600&c2=003366&tool=git-branch");
        }

        @Test
        @DisplayName("unknown tool id is rejected with the valid list (actionable for the agent)")
        void unknownToolRejected() {
            assertThatThrownBy(() -> AgentCrudModule.validateAvatarParam("preset:blue?tool=sword"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown avatar tool")
                    .hasMessageContaining("wrench");
        }

        @Test
        @DisplayName("malformed tool forms are rejected (tool before colors, uppercase id, empty value)")
        void malformedToolRejected() {
            for (String bad : new String[] {
                    "preset:blue?tool=wrench&c1=FF6600&c2=003366", // colors must come before tool
                    "preset:blue?tool=Wrench",                     // ids are lowercase (frontend parse is case-sensitive)
                    "preset:blue?tool=",                           // empty value
            }) {
                assertThatThrownBy(() -> AgentCrudModule.validateAvatarParam(bad))
                        .as("should reject %s", bad)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("a bare scheme with no host is rejected")
        void bareHttpSchemeRejected() {
            assertThatThrownBy(() -> AgentCrudModule.validateAvatarParam("https://"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a non-preset non-URL value is rejected")
        void garbageRejected() {
            assertThatThrownBy(() -> AgentCrudModule.validateAvatarParam("just some text"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("preset:");
        }
    }
}
