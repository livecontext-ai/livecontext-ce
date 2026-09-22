package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.bridge.BridgeAccessDecision;
import com.apimarketplace.agent.bridge.BridgeAccessGuard;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.BridgeProviderSaveGuard;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code agent} MCP tool is a WRITE path, and it was ungated.
 *
 * <p>This matters more than the REST path it was found on. The sibling {@code AgentHelpModule}
 * in this very package already filters admin-only CLI-bridge models out of the model LISTING,
 * so the reading half was gated while the writing half beside it accepted anything an LLM
 * named. That asymmetry is the likeliest origin of the 11 production agents pinned to
 * {@code claude-code} under non-admin owners, each denied at every run.
 *
 * <p>The rule itself lives in {@link BridgeProviderSaveGuard} and is pinned by its own test;
 * these assertions cover only that this tool consults it, and with the caller's own identity.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCrudModule - an agent cannot be written onto an unusable CLI bridge")
class AgentCrudModuleBridgeAccessTest {

    private static final String TENANT = "121";
    private static final String BRIDGE = "claude-code";
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock private AgentService agentService;
    @Mock private SkillService skillService;
    @Mock private com.apimarketplace.agent.webhook.AgentWebhookTokenService webhookTokenService;
    @Mock private ModelCatalogService modelCatalogService;
    @Mock private org.springframework.web.client.RestTemplate restTemplate;
    @Mock private BridgeAccessGuard bridgeAccessGuard;

    private AgentCrudModule module;

    @BeforeEach
    void setUp() {
        lenient().when(modelCatalogService.isModelAvailable(anyString(), anyString())).thenReturn(true);
        lenient().when(modelCatalogService.getEffectiveDefaultModel()).thenReturn("claude-sonnet-4-6");
        lenient().when(modelCatalogService.getEffectiveDefaultProvider()).thenReturn("anthropic");
        module = new AgentCrudModule(agentService, skillService, webhookTokenService,
                new com.apimarketplace.agent.config.AgentDefaultsConfig(), modelCatalogService,
                restTemplate, "http://localhost:8091", "http://localhost:8080");

        BridgeProviderSaveGuard saveGuard = new BridgeProviderSaveGuard();
        saveGuard.setBridgeAccessGuard(bridgeAccessGuard);
        // CE, same reason as AgentControllerBridgeAccessTest: these assert the access policy,
        // which only the self-hosted edition consults.
        saveGuard.setAuthMode("embedded");
        module.setBridgeProviderSaveGuard(saveGuard);
    }

    @Test
    @DisplayName("create on an admin-only bridge is refused and no agent is persisted")
    void createOnAdminOnlyBridgeIsRefused() {
        denyWith(BridgeAccessDecision.REASON_NOT_ADMIN);
        // Stub the persistence so that WITHOUT the gate this create would SUCCEED. Otherwise
        // the assertion below passes merely because an unstubbed service returned null, and
        // the test would stay green with the gate removed.
        AgentEntity wouldHaveBeenCreated = new AgentEntity();
        wouldHaveBeenCreated.setId(AGENT_ID);
        wouldHaveBeenCreated.setName("Agenda Scout");
        lenient().when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(wouldHaveBeenCreated);

        Optional<ToolExecutionResult> result =
            module.execute("create", createParams(BRIDGE), TENANT, plainUserContext());

        assertThat(result).isPresent();
        assertThat(result.get().success())
            .as("an LLM naming a bridge it cannot dispatch would otherwise mint exactly the "
                + "agents found stuck in production")
            .isFalse();
        assertThat(result.get().toMap().toString()).contains(BRIDGE);
        verify(agentService, never()).createAgent(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any());
    }

    @Test
    @DisplayName("create on an ordinary provider never consults the access guard")
    void createOnOrdinaryProviderIsUntouched() {
        AgentEntity created = new AgentEntity();
        created.setId(AGENT_ID);
        created.setName("Worker");
        lenient().when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        module.execute("create", createParams("openai"), TENANT, plainUserContext());

        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("an update that does not change the provider is not judged")
    void updateKeepingTheSameProviderIsNotJudged() {
        AgentEntity existing = new AgentEntity();
        existing.setId(AGENT_ID);
        existing.setName("Agenda Scout");
        existing.setTenantId(TENANT);
        existing.setModelProvider(BRIDGE);
        existing.setModelName("claude-fable-5");
        // executeUpdate reads via the 2-arg overload when the context carries no orgId, and the
        // 4-arg one when it does. plainUserContext() has none, so stub exactly what runs -
        // a 3-arg stub would be dead and would hide an unstubbed read if orgId ever appeared.
        when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));

        Map<String, Object> params = new HashMap<>();
        params.put("agent_id", AGENT_ID.toString());
        params.put("model_provider", BRIDGE);
        params.put("model_name", "claude-fable-5");
        module.execute("update", params, TENANT, plainUserContext());

        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("moving an agent ONTO a forbidden bridge is refused and nothing is updated")
    void updateMovingOntoAForbiddenBridgeIsRefused() {
        AgentEntity existing = new AgentEntity();
        existing.setId(AGENT_ID);
        existing.setName("Agenda Scout");
        existing.setTenantId(TENANT);
        existing.setModelProvider("openai");
        existing.setModelName("gpt-4o");
        // executeUpdate reads via the 2-arg overload when the context carries no orgId, and the
        // 4-arg one when it does. plainUserContext() has none, so stub exactly what runs -
        // a 3-arg stub would be dead and would hide an unstubbed read if orgId ever appeared.
        when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
        denyWith(BridgeAccessDecision.REASON_NOT_ADMIN);

        Map<String, Object> params = new HashMap<>();
        params.put("agent_id", AGENT_ID.toString());
        params.put("model_provider", BRIDGE);
        // A complete, available pair, so the substitution block leaves it alone and the bridge
        // this test asks for is the one that would be stored. The substitution path on THIS
        // action is covered by substitutedAwayBridgeIsNotRefusedOnUpdate below.
        params.put("model_name", "claude-fable-5");
        Optional<ToolExecutionResult> result =
            module.execute("update", params, TENANT, plainUserContext());

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        verify(agentService, never()).updateAgent(any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the caller's own id and roles are what is judged, not some other pair")
    void theCallersOwnIdentityIsJudged() {
        // any(), any(), any() would stay green if userId and userRoles were transposed, and a
        // transposed pair judges the wrong person: every USER would read as an unknown role.
        when(bridgeAccessGuard.check(TENANT, "USER", BRIDGE))
            .thenReturn(new BridgeAccessDecision(false, BridgeAccessDecision.REASON_NOT_ADMIN,
                BRIDGE, null));

        Optional<ToolExecutionResult> result =
            module.execute("create", createParams(BRIDGE), TENANT, plainUserContext());

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        verify(bridgeAccessGuard).check(TENANT, "USER", BRIDGE);
    }

    @Test
    @DisplayName("a transient denial does not block a create through this tool either")
    void transientDenialStillAllowsTheCreate() {
        denyWith(BridgeAccessDecision.REASON_QUOTA_EXHAUSTED);
        AgentEntity created = new AgentEntity();
        created.setId(AGENT_ID);
        created.setName("Agenda Scout");
        lenient().when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        Optional<ToolExecutionResult> result =
            module.execute("create", createParams(BRIDGE), TENANT, plainUserContext());

        assertThat(result).isPresent();
        assertThat(result.get().success())
            .as("the quota resets, so refusing here would make a passing limit permanent - the "
                + "REST path and this one must not disagree about that")
            .isTrue();
    }

    @Test
    @DisplayName("a create with no provider is judged on the default this tool would persist")
    void createWithNoProviderIsJudgedOnTheResolvedDefault() {
        // Unlike REST, which stores null when the body carries no provider, this tool
        // substitutes the catalog default and stores THAT. So the default is what has to be
        // judged: leaving it unjudged is how a bridge default would slip through here.
        lenient().when(modelCatalogService.getEffectiveDefaultProvider()).thenReturn(BRIDGE);
        // Stubbed so that WITHOUT the gate this create would SUCCEED; otherwise the assertion
        // below passes merely because an unstubbed service returned null.
        AgentEntity wouldHaveBeenCreated = new AgentEntity();
        wouldHaveBeenCreated.setId(AGENT_ID);
        wouldHaveBeenCreated.setName("Agenda Scout");
        lenient().when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(wouldHaveBeenCreated);
        when(bridgeAccessGuard.check(TENANT, "USER", BRIDGE))
            .thenReturn(new BridgeAccessDecision(false, BridgeAccessDecision.REASON_NOT_ADMIN,
                BRIDGE, null));

        Map<String, Object> params = new HashMap<>();
        params.put("name", "Agenda Scout");
        params.put("system_prompt", "watch the agenda");
        Optional<ToolExecutionResult> result =
            module.execute("create", params, TENANT, plainUserContext());

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        verify(bridgeAccessGuard).check(TENANT, "USER", BRIDGE);
    }

    @Test
    @DisplayName("a bridge request the catalog substitutes away is ALLOWED - the gate judges what is stored")
    void substitutedAwayBridgeIsNotRefused() {
        // This pins the ORDER of the guard against the model-substitution block, which nothing
        // else does. The requested provider is a forbidden bridge, but its model is unavailable,
        // so the catalog rewrites the pair onto the default before anything is persisted - no
        // bridge ever reaches the row. Judge the REQUEST instead of the RESULT (move the guard
        // above the substitution block) and this refuses a save that was never dangerous.
        lenient().when(modelCatalogService.isModelAvailable(BRIDGE, "claude-fable-5"))
            .thenReturn(false);
        lenient().when(modelCatalogService.getEffectiveDefaultProvider()).thenReturn("anthropic");
        lenient().when(modelCatalogService.isModelAvailable("anthropic", "claude-sonnet-4-6"))
            .thenReturn(true);
        AgentEntity created = new AgentEntity();
        created.setId(AGENT_ID);
        created.setName("Agenda Scout");
        lenient().when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(created);

        Optional<ToolExecutionResult> result =
            module.execute("create", createParams(BRIDGE), TENANT, plainUserContext());

        assertThat(result).isPresent();
        assertThat(result.get().success())
            .as("the stored provider is the substituted default, not the bridge that was asked "
                + "for, so there is nothing to refuse")
            .isTrue();
        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("on UPDATE too, a bridge the catalog substitutes away is allowed, not refused")
    void substitutedAwayBridgeIsNotRefusedOnUpdate() {
        // The update gate sits after the substitution block for the same reason the create one
        // does. Nothing else pins that on this action: both other update tests pass an available
        // pair, so they never enter substitution and stay green if the gate is moved above it.
        AgentEntity existing = new AgentEntity();
        existing.setId(AGENT_ID);
        existing.setName("Agenda Scout");
        existing.setTenantId(TENANT);
        existing.setModelProvider("openai");
        existing.setModelName("gpt-4o");
        when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
        lenient().when(modelCatalogService.isModelAvailable(BRIDGE, "claude-fable-5"))
            .thenReturn(false);
        lenient().when(modelCatalogService.getEffectiveDefaultProvider()).thenReturn("anthropic");
        lenient().when(modelCatalogService.isModelAvailable("anthropic", "claude-sonnet-4-6"))
            .thenReturn(true);
        AgentEntity updated = new AgentEntity();
        updated.setId(AGENT_ID);
        updated.setName("Agenda Scout");
        lenient().when(agentService.updateAgent(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any())).thenReturn(updated);

        Map<String, Object> params = new HashMap<>();
        params.put("agent_id", AGENT_ID.toString());
        params.put("model_provider", BRIDGE);
        params.put("model_name", "claude-fable-5");
        Optional<ToolExecutionResult> result =
            module.execute("update", params, TENANT, plainUserContext());

        assertThat(result).isPresent();
        assertThat(result.get().success())
            .as("the stored provider is the substituted default, so there is no bridge to refuse")
            .isTrue();
        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("refused bridge updates do not burn the update cap - the 4th real update works")
    void refusedUpdatesDoNotConsumeTheUpdateCap() {
        // The refusal invites the caller to pick another provider. If each refusal kept its cap
        // slot, the 4th attempt would answer "STOP: You have updated this agent 3 times already,
        // the configuration is COMPLETE, DO NOT call update again" - telling the agent to give up
        // at the exact moment it had not yet succeeded once. Delete the decrement and this fails.
        AgentEntity existing = new AgentEntity();
        existing.setId(AGENT_ID);
        existing.setName("Agenda Scout");
        existing.setTenantId(TENANT);
        existing.setModelProvider("openai");
        existing.setModelName("gpt-4o");
        when(agentService.getAgent(AGENT_ID, TENANT)).thenReturn(Optional.of(existing));
        denyWith(BridgeAccessDecision.REASON_NOT_ADMIN);
        AgentEntity updated = new AgentEntity();
        updated.setId(AGENT_ID);
        updated.setName("Agenda Scout");
        lenient().when(agentService.updateAgent(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any())).thenReturn(updated);

        for (int attempt = 1; attempt <= 3; attempt++) {
            Optional<ToolExecutionResult> refused =
                module.execute("update", updateParams(BRIDGE), TENANT, plainUserContext());
            assertThat(refused).isPresent();
            assertThat(refused.get().success()).as("refusal %d", attempt).isFalse();
        }

        Optional<ToolExecutionResult> allowed =
            module.execute("update", updateParams("openai"), TENANT, plainUserContext());

        assertThat(allowed).isPresent();
        assertThat(allowed.get().toMap().toString())
            .as("three refusals must leave the cap untouched")
            .doesNotContain("STOP: You have updated this agent");
        assertThat(allowed.get().success()).isTrue();
    }

    @Test
    @DisplayName("a refused bridge create does not burn a create slot either")
    void refusedCreatesDoNotConsumeTheCreateCap() {
        // Same reasoning on the create side, where the guard is placed BEFORE the cap rather
        // than refunding after it. Move it below the cap and the retry this refusal invites
        // comes back "LIMIT REACHED: You have already created N agents", which is false.
        denyWith(BridgeAccessDecision.REASON_NOT_ADMIN);
        AgentEntity created = new AgentEntity();
        created.setId(AGENT_ID);
        created.setName("Agenda Scout");
        lenient().when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        for (int attempt = 1; attempt <= 5; attempt++) {
            Optional<ToolExecutionResult> refused =
                module.execute("create", createParams(BRIDGE), TENANT, plainUserContext());
            assertThat(refused).isPresent();
            assertThat(refused.get().success()).as("refusal %d", attempt).isFalse();
        }

        Optional<ToolExecutionResult> allowed =
            module.execute("create", createParams("openai"), TENANT, plainUserContext());

        assertThat(allowed).isPresent();
        assertThat(allowed.get().toMap().toString())
            .as("refusals never reached the cap, so the first real create still has its slots")
            .doesNotContain("LIMIT REACHED");
        assertThat(allowed.get().success()).isTrue();
    }

    private static Map<String, Object> updateParams(String provider) {
        Map<String, Object> params = new HashMap<>();
        params.put("agent_id", AGENT_ID.toString());
        params.put("model_provider", provider);
        params.put("model_name", "claude-fable-5");
        return params;
    }

    private void denyWith(String reason) {
        when(bridgeAccessGuard.check(any(), any(), any()))
            .thenReturn(new BridgeAccessDecision(false, reason, BRIDGE, null));
    }

    private static Map<String, Object> createParams(String provider) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", "Agenda Scout");
        params.put("system_prompt", "watch the agenda");
        params.put("model_provider", provider);
        params.put("model_name", "claude-fable-5");
        return params;
    }

    /** A caller whose roles say USER, exactly as the gateway forwards them into tool credentials. */
    private static ToolExecutionContext plainUserContext() {
        return new ToolExecutionContext(TENANT,
            Map.of("turnId", "turn-1", "__userRoles__", "USER"),
            Map.of(), null, null, null, null, null);
    }
}
