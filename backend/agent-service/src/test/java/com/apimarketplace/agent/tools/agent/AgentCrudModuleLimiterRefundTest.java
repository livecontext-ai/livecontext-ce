package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A refusal must not spend the budget of the call it refused.
 *
 * <p>Both CRUD paths count the call against a rate limiter and then keep validating. Every
 * parameter-only check that returned after that count spent a slot on a call that created or
 * changed nothing, and the limiter's refusal is not a shrug: it tells the agent to STOP and says
 * the configuration is COMPLETE. Three malformed calls therefore made the fourth, VALID one
 * answer "you have updated this agent 3 times already", which is false, and the agent believed
 * it.
 *
 * <p>The fix is the pattern this file already used for the bridge check, whose comment states
 * the rule: a check that reads only the request parameters is judged BEFORE the cap. Nothing is
 * refunded because nothing is counted. These tests pin the OBSERVABLE consequence, a valid call
 * succeeding after N refusals, rather than the counter itself, so they survive a change of
 * mechanism and fail for the reason a user would notice.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCrudModule - a refusal does not spend the caller's budget")
class AgentCrudModuleLimiterRefundTest {

    @Mock private AgentService agentService;
    @Mock private SkillService skillService;
    @Mock private com.apimarketplace.agent.webhook.AgentWebhookTokenService webhookTokenService;
    @Mock private ModelCatalogService modelCatalogService;
    @Mock private org.springframework.web.client.RestTemplate restTemplate;

    private AgentCrudModule module;

    private static final String TENANT = "tenant-refund";
    private static final UUID AGENT_ID = UUID.randomUUID();

    /** MAX_CONSECUTIVE_UPDATES in the module under test. */
    private static final int MAX_UPDATES = 3;

    @BeforeEach
    void setUp() {
        lenient().when(modelCatalogService.isModelAvailable(anyString(), anyString())).thenReturn(true);
        lenient().when(modelCatalogService.getEffectiveDefaultModel()).thenReturn("claude-sonnet-4-6");
        lenient().when(modelCatalogService.getEffectiveDefaultProvider()).thenReturn("anthropic");
        module = new AgentCrudModule(agentService, skillService, webhookTokenService,
                new com.apimarketplace.agent.config.AgentDefaultsConfig(), modelCatalogService,
                restTemplate, "http://localhost:8091", "http://localhost:8080");
    }

    /**
     * A turn id is what both limiters key on, so every call in one test must share one. A
     * DIFFERENT turn per test keeps the tests independent: the limiter is a field of the module,
     * but the key carries the turn, and these run in one JVM.
     */
    private ToolExecutionContext ctx(String turn) {
        return new ToolExecutionContext(TENANT, Map.of("turnId", turn), Map.of(),
                null, null, null, null, null);
    }

    /**
     * The same, with the per-turn create cap pinned to ONE.
     *
     * <p>Without it the create tests prove nothing: the YAML default is several, so a slot
     * wasted on a refusal never reaches the cap and the test passes against the unfixed code
     * too. A cap of one makes the second create the one that has to be allowed, which is the
     * whole claim. Verified: with this context the two create tests fail before the fix and
     * pass after; with the default context they passed both ways.
     */
    private ToolExecutionContext ctxWithOneCreateAllowed(String turn) {
        return new ToolExecutionContext(TENANT,
                Map.of("turnId", turn,
                       com.apimarketplace.agent.config.GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 1),
                Map.of(), null, null, null, null, null);
    }

    private void agentExists() {
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setName("Night Publisher");
        agent.setModelProvider("openai");
        agent.setModelName("gpt-4");
        agent.setTemperature(BigDecimal.valueOf(0.7));
        agent.setMaxTokens(4096);
        agent.setRequireToolAuthorization(true);
        lenient().when(agentService.getAgent(any(UUID.class), anyString())).thenReturn(Optional.of(agent));
        lenient().when(agentService.getAgent(any(UUID.class), anyString(), any(), any()))
                .thenReturn(Optional.of(agent));
        lenient().when(agentService.setRequireToolAuthorization(any(), anyString(), any(), anyBoolean()))
                .thenReturn(agent);
    }

    private ToolExecutionResult update(String turn, Map<String, Object> extra) {
        agentExists();
        Map<String, Object> params = new java.util.HashMap<>(extra);
        params.put("agent_id", AGENT_ID.toString());
        return module.execute("update", params, TENANT, ctx(turn)).orElseThrow();
    }

    private void refuseUpdateTimes(String turn, Map<String, Object> malformed, int times) {
        for (int i = 0; i < times; i++) {
            ToolExecutionResult refusal = update(turn, malformed);
            assertThat(refusal.success())
                    .as("call %s of the arrange phase must be the refusal under test, not something else", i + 1)
                    .isFalse();
            // Not "contains Nothing was changed": the three refusals do not word themselves
            // alike, and the compaction one says only what is wrong with the value. What every
            // one of them must NOT be is the cap, which is the refusal these tests are about.
            assertThat(refusal.error())
                    .as("must be refused for the parameter, not by the cap")
                    .doesNotContain("updated this agent");
        }
    }

    @Test
    @DisplayName("three malformed flags do not spend the update budget")
    void threeMalformedFlagUpdatesDoNotSpendTheUpdateBudget() {
        String turn = "turn-flag";
        refuseUpdateTimes(turn, Map.of("require_tool_authorization", "maybe"), MAX_UPDATES);

        ToolExecutionResult valid = update(turn, Map.of("require_tool_authorization", true));

        // Pre-fix this is the cap's refusal: "STOP: You have updated this agent 3 times
        // already. The agent configuration is COMPLETE." The agent then stops asking, on a
        // sentence that is false.
        assertThat(valid.success())
                .as("three refusals changed nothing, so the budget they spent was a fiction")
                .isTrue();
        verify(agentService, times(1))
                .setRequireToolAuthorization(eq(AGENT_ID), eq(TENANT), any(), eq(true));
    }

    @Test
    @DisplayName("three unusable grants do not spend the update budget either")
    void unusableGrantOnUpdateDoesNotSpendTheUpdateBudget() {
        String turn = "turn-grant";
        refuseUpdateTimes(turn, Map.of("web_search", "maybe"), MAX_UPDATES);

        ToolExecutionResult valid = update(turn, Map.of("require_tool_authorization", true));

        assertThat(valid.success()).isTrue();
        verify(agentService, times(1))
                .setRequireToolAuthorization(eq(AGENT_ID), eq(TENANT), any(), eq(true));
    }

    @Test
    @DisplayName("three invalid compaction cadences do not spend the update budget either")
    void invalidCompactionCadenceOnUpdateDoesNotSpendTheUpdateBudget() {
        // Not in the reported list. Same method, same shape, found while moving the other two:
        // the contract is every parameter-only early return between the count and the write.
        String turn = "turn-cadence";
        refuseUpdateTimes(turn, Map.of("compaction_after_turns", "soon"), MAX_UPDATES);

        ToolExecutionResult valid = update(turn, Map.of("require_tool_authorization", true));

        assertThat(valid.success()).isTrue();
        verify(agentService, times(1))
                .setRequireToolAuthorization(eq(AGENT_ID), eq(TENANT), any(), eq(true));
    }

    @Test
    @DisplayName("the cap still fires on three calls that really did change the agent")
    void theCapStillFiresOnRealUpdates() {
        // The guard against over-correcting: moving checks above the cap must not stop the cap
        // counting the calls it exists to count. Without this, a fix that simply stopped
        // counting would pass every test above.
        String turn = "turn-real";
        for (int i = 0; i < MAX_UPDATES; i++) {
            assertThat(update(turn, Map.of("require_tool_authorization", true)).success())
                    .as("update %s should be allowed", i + 1)
                    .isTrue();
        }

        ToolExecutionResult capped = update(turn, Map.of("require_tool_authorization", true));

        assertThat(capped.success()).isFalse();
        assertThat(capped.error()).contains("updated this agent");
    }

    @Test
    @DisplayName("a malformed flag on create does not spend a create slot")
    void malformedFlagOnCreateDoesNotSpendACreateSlot() {
        String turn = "turn-create-flag";
        ToolExecutionResult refused = module.execute("create", Map.of(
                "name", "Refused", "system_prompt", "You refuse.",
                "require_tool_authorization", true), TENANT, ctxWithOneCreateAllowed(turn)).orElseThrow();

        assertThat(refused.success())
                .as("create never applies this flag, so it refuses it")
                .isFalse();
        assertThat(refused.error()).contains("Nothing was created");

        // Pre-fix the refusal above consumed a slot, so this one could answer
        // "LIMIT REACHED: You have already created N agents", about an agent that never existed.
        AgentEntity created = new AgentEntity();
        created.setId(UUID.randomUUID());
        created.setName("Real");
        lenient().when(agentService.createAgent(anyString(), anyString(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(created);

        ToolExecutionResult valid = module.execute("create", Map.of("name", "Real", "system_prompt", "You work."), TENANT, ctxWithOneCreateAllowed(turn))
                .orElseThrow();

        assertThat(valid.error())
                .as("the second create must not be refused by a cap the first one filled for nothing")
                .doesNotContain("LIMIT REACHED");
    }

    @Test
    @DisplayName("an invalid compaction cadence on create does not spend a create slot")
    void invalidCompactionCadenceOnCreateDoesNotSpendACreateSlot() {
        String turn = "turn-create-cadence";
        ToolExecutionResult refused = module.execute("create", Map.of(
                "name", "Refused", "system_prompt", "You refuse.",
                "compaction_after_turns", "soon"), TENANT, ctxWithOneCreateAllowed(turn)).orElseThrow();

        assertThat(refused.success()).isFalse();

        AgentEntity created = new AgentEntity();
        created.setId(UUID.randomUUID());
        created.setName("Real");
        lenient().when(agentService.createAgent(anyString(), anyString(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(created);

        ToolExecutionResult valid = module.execute("create", Map.of("name", "Real", "system_prompt", "You work."), TENANT, ctxWithOneCreateAllowed(turn))
                .orElseThrow();

        assertThat(valid.error()).doesNotContain("LIMIT REACHED");
    }
}
