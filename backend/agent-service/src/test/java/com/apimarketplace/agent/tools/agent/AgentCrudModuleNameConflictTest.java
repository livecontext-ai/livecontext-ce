package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.service.AgentNameConflictException;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * agent(action='create'|'update') with a name already held by an active agent of the
 * workspace. The agent must read BOTH ways out in the result: update the existing agent, or
 * repeat the call with the free name (suggested_name). Before this fix the create answered a
 * generic EXTERNAL_SERVICE_ERROR, and an index-level refusal "Failed to create agent: could
 * not execute statement ...".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCrudModule - agent name conflict")
class AgentCrudModuleNameConflictTest {

    @Mock private AgentService agentService;
    @Mock private SkillService skillService;
    @Mock private com.apimarketplace.agent.webhook.AgentWebhookTokenService webhookTokenService;
    @Mock private ModelCatalogService modelCatalogService;
    @Mock private org.springframework.web.client.RestTemplate restTemplate;

    private AgentCrudModule module;

    private static final String TENANT = "tenant-name-conflict";
    private static final String ORG = "44444444-4444-4444-8444-444444444444";
    private static final UUID EXISTING = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(modelCatalogService.isModelAvailable(anyString(), anyString())).thenReturn(true);
        lenient().when(modelCatalogService.getEffectiveDefaultModel()).thenReturn("claude-sonnet-4-6");
        lenient().when(modelCatalogService.getEffectiveDefaultProvider()).thenReturn("anthropic");
        module = new AgentCrudModule(agentService, skillService, webhookTokenService,
                new com.apimarketplace.agent.config.AgentDefaultsConfig(), modelCatalogService,
                restTemplate, "http://localhost:8091", "http://localhost:8080");
    }

    /** Cap of ONE create per turn: proves a refused create does not spend it. */
    private ToolExecutionContext ctx(String turn) {
        return new ToolExecutionContext(TENANT,
                Map.of("turnId", turn,
                       com.apimarketplace.agent.config.GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 1),
                Map.of(), null, null, null, null, null);
    }

    private Map<String, Object> createParams(String name) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        p.put("system_prompt", "You are helpful.");
        return p;
    }

    private void createThrows(RuntimeException e) {
        when(agentService.createAgent(any(), eq("Nova"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(e);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(ToolExecutionResult r) {
        return (Map<String, Object>) r.metadata();
    }

    @Test
    @DisplayName("regression: a duplicate create answers RESOURCE_CONFLICT with suggested_name, existing_agent_id and both ways out")
    void duplicateCreateCarriesTheSuggestion() {
        createThrows(new AgentNameConflictException("Nova", EXISTING, "Nova (2)"));

        ToolExecutionResult r = module.execute("create", createParams("Nova"), TENANT, ctx("t1")).orElseThrow();

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_CONFLICT);
        assertThat(r.error()).contains("agent(action='update'").contains("'Nova (2)'");
        assertThat(meta(r))
                .containsEntry("code", "AGENT_NAME_CONFLICT")
                .containsEntry("suggested_name", "Nova (2)")
                .containsEntry("existing_agent_id", EXISTING.toString());
        assertThat((String) meta(r).get("next_action"))
                .contains("agent(action='update', agent_id='" + EXISTING + "')").contains("name='Nova (2)'");
    }

    @Test
    @DisplayName("the refused create does not spend the one create slot of the turn: the retry with the suggested name goes through")
    void refusedCreateDoesNotSpendTheSlot() {
        createThrows(new AgentNameConflictException("Nova", EXISTING, "Nova (2)"));
        AgentEntity created = new AgentEntity();
        created.setId(UUID.randomUUID());
        created.setName("Nova (2)");
        created.setTemperature(BigDecimal.valueOf(0.7));
        created.setMaxTokens(4096);
        when(agentService.createAgent(any(), eq("Nova (2)"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

        assertThat(module.execute("create", createParams("Nova"), TENANT, ctx("t2")).orElseThrow().success()).isFalse();
        ToolExecutionResult retry = module.execute("create", createParams("Nova (2)"), TENANT, ctx("t2")).orElseThrow();

        assertThat(retry.success()).isTrue();
    }

    @Test
    @DisplayName("regression: the index refusing the insert (lost race) reads exactly like a checked duplicate")
    void indexViolationOnCreateIsTheSameConflict() {
        createThrows(new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint "
                        + "\"uq_agents_org_name_active\"\n  Detail: Key (organization_id, name)=("
                        + ORG + ", Nova) already exists.")));
        when(agentService.allocateAgentName(eq(ORG), eq("Nova"))).thenReturn("Nova (2)");

        ToolExecutionResult r = module.execute("create", createParams("Nova"), TENANT, ctx("t3")).orElseThrow();

        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_CONFLICT);
        assertThat(meta(r)).containsEntry("suggested_name", "Nova (2)").doesNotContainKey("existing_agent_id");
        assertThat(r.error()).doesNotContain("could not execute statement").contains("agent(action='list')");
        assertThat((String) meta(r).get("next_action")).contains("agent(action='list')");
    }

    @Test
    @DisplayName("an update renaming onto a taken name answers the same conflict")
    void renameConflict() {
        AgentEntity agent = new AgentEntity();
        agent.setId(UUID.randomUUID());
        agent.setName("Scout");
        agent.setModelProvider("openai");
        agent.setModelName("gpt-4");
        agent.setTemperature(BigDecimal.valueOf(0.7));
        agent.setMaxTokens(4096);
        lenient().when(agentService.getAgent(any(UUID.class), anyString())).thenReturn(Optional.of(agent));
        lenient().when(agentService.getAgent(any(UUID.class), anyString(), any(), any())).thenReturn(Optional.of(agent));
        when(agentService.updateAgent(any(), any(), eq("Nova"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(AgentNameConflictException.forRename("Nova", EXISTING, "Nova (2)"));

        Map<String, Object> p = new HashMap<>();
        p.put("agent_id", agent.getId().toString());
        p.put("name", "Nova");
        ToolExecutionResult r = module.execute("update", p, TENANT, ctx("t4")).orElseThrow();

        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_CONFLICT);
        assertThat(meta(r)).containsEntry("suggested_name", "Nova (2)");
        // Renaming your own agent: the only way out is another name, never "update that agent".
        assertThat((String) meta(r).get("next_action"))
                .isEqualTo("Repeat the same update with name='Nova (2)'.");
        assertThat(r.error()).doesNotContain("agent(action='update'");
    }

    @Test
    @DisplayName("a create that loses the race at the index refunds the turn's create slot: the retry goes through")
    void lostRaceRefundsTheCreateSlot() {
        createThrows(new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint "
                        + "\"uq_agents_org_name_active\"\n  Detail: Key (organization_id, name)=("
                        + ORG + ", Nova) already exists.")));
        AgentEntity created = new AgentEntity();
        created.setId(UUID.randomUUID());
        created.setName("Nova (2)");
        created.setTemperature(BigDecimal.valueOf(0.7));
        created.setMaxTokens(4096);
        when(agentService.createAgent(any(), eq("Nova (2)"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

        assertThat(module.execute("create", createParams("Nova"), TENANT, ctx("t6")).orElseThrow().errorCode())
                .isEqualTo(ToolErrorCode.RESOURCE_CONFLICT);
        // Cap of ONE create per turn: this second create only passes if the first was refunded.
        assertThat(module.execute("create", createParams("Nova (2)"), TENANT, ctx("t6")).orElseThrow().success())
                .isTrue();
    }

    @Test
    @DisplayName("any other integrity error on create keeps the generic failure, not a name conflict")
    void otherIntegrityErrorKeepsGenericFailure() {
        createThrows(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"agents_pkey\""));

        ToolExecutionResult r = module.execute("create", createParams("Nova"), TENANT, ctx("t7")).orElseThrow();

        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(r.error()).startsWith("Failed to create agent: ");
        assertThat(meta(r)).doesNotContainKey("suggested_name");
    }

    @Test
    @DisplayName("an update that loses the race at the index answers the same conflict, not 'Failed to update agent'")
    void renameIndexViolationIsTheSameConflict() {
        AgentEntity agent = new AgentEntity();
        agent.setId(UUID.randomUUID());
        agent.setName("Scout");
        agent.setModelProvider("openai");
        agent.setModelName("gpt-4");
        agent.setTemperature(BigDecimal.valueOf(0.7));
        agent.setMaxTokens(4096);
        lenient().when(agentService.getAgent(any(UUID.class), anyString())).thenReturn(Optional.of(agent));
        lenient().when(agentService.getAgent(any(UUID.class), anyString(), any(), any())).thenReturn(Optional.of(agent));
        when(agentService.updateAgent(any(), any(), eq("Nova"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new RuntimeException("ERROR: duplicate key value violates unique constraint "
                                + "\"uq_agents_org_name_active\"\n  Detail: Key (organization_id, name)=("
                                + ORG + ", Nova) already exists.")));
        // The suggestion counts THIS agent's own current name as free.
        when(agentService.allocateAgentNameForRename(eq(ORG), eq("Nova"), eq(agent.getId()))).thenReturn("Nova (2)");

        Map<String, Object> p = new HashMap<>();
        p.put("agent_id", agent.getId().toString());
        p.put("name", "Nova");
        ToolExecutionResult r = module.execute("update", p, TENANT, ctx("t5")).orElseThrow();

        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_CONFLICT);
        assertThat(meta(r)).containsEntry("suggested_name", "Nova (2)");
        // Rename advice, not the create advice ("find it with agent(action='list') and update it").
        assertThat(r.error())
                .isEqualTo("Another active agent already uses the name 'Nova' in this workspace. "
                        + "Nothing was saved. Retry with name='Nova (2)'.");
        assertThat((String) meta(r).get("next_action")).isEqualTo("Repeat the same update with name='Nova (2)'.");
    }
}
