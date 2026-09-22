package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.bridge.BridgeAccessDecision;
import com.apimarketplace.agent.bridge.BridgeAccessGuard;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.BridgeProviderSaveGuard;
import com.apimarketplace.agent.service.AgentWidgetConfigService;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.agent.webhook.AgentWebhookTokenService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An agent may not be SAVED on a CLI bridge its owner can never dispatch.
 *
 * <p>{@link BridgeAccessGuard} was enforced at dispatch and applied to the model listing, but
 * never at save. Production therefore accumulated 11 active agents, owned by 8 non-admin users,
 * pinned to {@code claude-code} - every one of them denied
 * ({@code admin_only_requires_admin_role}) at every run. One of them, "Agenda Scout", carried a
 * {@code * /30} cron, so it failed on every fire indefinitely: two consecutive fires 30 minutes
 * apart were observed failing identically, with the schedule still ACTIVE.
 *
 * <p>The nuance that matters: only a STANDING denial blocks a save, and only for a provider that
 * is actually CHANGING. A daily quota resets, an unreachable guard proves nothing, and an agent
 * already sitting on a forbidden bridge has to stay editable or its owner cannot move it off.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController - an agent cannot be saved onto an unusable CLI bridge")
class AgentControllerBridgeAccessTest {

    private static final UUID AGENT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String TENANT_ID = "121";
    private static final String ORG_ID = "66666666-6666-4666-8666-666666666666";
    private static final String BRIDGE = "claude-code";

    @Mock private AgentService agentService;
    @Mock private AgentWebhookTokenService webhookTokenService;
    @Mock private AgentWidgetConfigService widgetConfigService;
    @Mock private TenantResolver tenantResolver;
    @Mock private HttpServletRequest request;
    @Mock private BridgeAccessGuard bridgeAccessGuard;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(
            agentService, webhookTokenService, widgetConfigService, tenantResolver,
            new RequestParameterExtractor(), "", "http://widget.test", "http://trigger.test");
        // The REAL rule object wrapping a mocked access guard: the rule itself is pinned by
        // BridgeProviderSaveGuardTest, so this class asserts only what the HTTP layer does
        // with its verdict.
        BridgeProviderSaveGuard saveGuard = new BridgeProviderSaveGuard();
        saveGuard.setBridgeAccessGuard(bridgeAccessGuard);
        // CE: every scenario below turns on the ACCESS POLICY, which cloud reaches only for an
        // ADMIN - a user is refused before any policy is read. The cloud branch has its own
        // tests in BridgeProviderSaveGuardTest.CloudRefusesNonAdminBridgeSaves.
        saveGuard.setAuthMode("embedded");
        controller.setBridgeProviderSaveGuard(saveGuard);
        lenient().when(tenantResolver.resolveOrNull(request)).thenReturn(TENANT_ID);
        lenient().when(tenantResolver.resolveOrgId(request)).thenReturn(ORG_ID);
    }

    // ---------------------------------------------------------------- create

    @Test
    @DisplayName("create on an admin-only bridge as a plain user is refused with 403")
    void createOnAdminOnlyBridgeIsRefused() {
        denyWith(BridgeAccessDecision.REASON_NOT_ADMIN);

        var response = controller.createAgent(request, bodyWithProvider(BRIDGE));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(bodyOf(response))
            // The SAME discriminator GlobalExceptionHandler emits for a dispatch-time refusal,
            // so a client keying on it sees save-time denials too.
            .containsEntry("error", "BRIDGE_ACCESS_DENIED")
            .containsEntry("reason", BridgeAccessDecision.REASON_NOT_ADMIN);
        verifyNothingWasCreated();
    }

    @Test
    @DisplayName("create on the same bridge as an allowed user goes through")
    void createOnBridgeIsAllowedWhenTheGuardAllows() {
        when(bridgeAccessGuard.check(any(), any(), any()))
            .thenReturn(BridgeAccessDecision.allow(BRIDGE, null));
        stubCreate();

        var response = controller.createAgent(request, bodyWithProvider(BRIDGE));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("a non-bridge provider is never submitted to the guard")
    void nonBridgeProviderSkipsTheGuardEntirely() {
        stubCreate();

        var response = controller.createAgent(request, bodyWithProvider("openai"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("a daily quota is transient, so it does not block a save")
    void exhaustedDailyQuotaDoesNotBlockASave() {
        denyWith(BridgeAccessDecision.REASON_QUOTA_EXHAUSTED);
        stubCreate();

        var response = controller.createAgent(request, bodyWithProvider(BRIDGE));

        assertThat(response.getStatusCode().is2xxSuccessful())
            .as("the quota resets; refusing the save would make a temporary limit permanent")
            .isTrue();
    }

    // ---------------------------------------------------------------- update

    @Test
    @DisplayName("an agent already on a forbidden bridge stays editable (the provider is unchanged)")
    void unchangedForbiddenProviderStaysEditable() {
        existingAgentOn(BRIDGE);
        stubUpdate();

        Map<String, Object> body = bodyWithProvider(BRIDGE);
        body.put("name", "Agenda Scout renamed");
        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        assertThat(response.getStatusCode().is2xxSuccessful())
            .as("blocking this would trap the agent: its owner could no longer edit it, nor "
                + "move it off the bridge")
            .isTrue();
        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("moving an agent ONTO a forbidden bridge is refused with 403")
    void movingOntoAForbiddenBridgeIsRefused() {
        existingAgentOn("openai");
        denyWith(BridgeAccessDecision.REASON_NOT_ADMIN);

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, bodyWithProvider(BRIDGE));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNothingWasUpdated();
    }

    @Test
    @DisplayName("moving an agent OFF a forbidden bridge is always allowed")
    void movingOffAForbiddenBridgeIsAllowed() {
        existingAgentOn(BRIDGE);
        stubUpdate();

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, bodyWithProvider("openai"));

        assertThat(response.getStatusCode().is2xxSuccessful())
            .as("this is the repair path for the agents already stuck on a bridge - it must "
                + "never be gated")
            .isTrue();
        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("a body that does not carry modelProvider leaves the provider unjudged")
    void absentModelProviderIsNotJudged() {
        stubUpdate();

        Map<String, Object> body = new HashMap<>();
        body.put("name", "just a rename");
        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("cloning an agent that sits on a forbidden bridge is refused, not duplicated")
    void cloningAnAgentOnAForbiddenBridgeIsRefused() {
        AgentEntity source = new AgentEntity();
        source.setId(AGENT_ID);
        source.setModelProvider(BRIDGE);
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, null)).thenReturn(Optional.of(source));
        denyWith(BridgeAccessDecision.REASON_NOT_ADMIN);

        var response = controller.cloneAgent(AGENT_ID, request, ORG_ID, null);

        assertThat(response.getStatusCode().value())
            .as("a clone inherits the provider, so cloning one of the stuck agents would "
                + "simply mint a second agent that can never run")
            .isEqualTo(403);
        verify(agentService, never()).cloneAgent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("cloning an agent on an ordinary provider is untouched")
    void cloningAnOrdinaryAgentStillWorks() {
        AgentEntity source = new AgentEntity();
        source.setId(AGENT_ID);
        source.setModelProvider("openai");
        when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, null)).thenReturn(Optional.of(source));
        when(agentService.cloneAgent(any(), any(), any(), any())).thenReturn(source);

        var response = controller.cloneAgent(AGENT_ID, request, ORG_ID, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("the roles judged are the ones on X-User-Roles, in that argument position")
    void rolesComeFromTheHeaderAndAreNotTransposed() {
        // Every other test here stubs check(any(), any(), any()), which stays green if userId and
        // the header value swap places - and a transposed pair judges a role string as a user id,
        // so every caller reads as unknown. Nothing else in this file names the header at all.
        when(request.getHeader("X-User-Roles")).thenReturn("USER,ADMIN");
        when(bridgeAccessGuard.check(TENANT_ID, "USER,ADMIN", BRIDGE))
            .thenReturn(BridgeAccessDecision.allow(BRIDGE, null));
        stubCreate();

        var response = controller.createAgent(request, bodyWithProvider(BRIDGE));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(bridgeAccessGuard).check(TENANT_ID, "USER,ADMIN", BRIDGE);
    }

    @Test
    @DisplayName("an update whose agent this caller cannot read is left unjudged, not refused")
    void unreadableAgentIsLeftUnjudged() {
        // Read and write scopes are resolved separately, so treating 'unreadable' as 'forbidden'
        // would 403 an owner on their own agent. Inverting that branch breaks no other test.
        lenient().when(agentService.getAgent(any(), any(), any(), any())).thenReturn(Optional.empty());
        stubUpdate();

        var response = controller.updateAgent(AGENT_ID, request, ORG_ID, bodyWithProvider(BRIDGE));

        assertThat(response.getStatusCode().value())
            .as("the update itself decides what an unreadable agent means; the bridge gate must "
                + "not turn it into a bridge refusal")
            .isNotEqualTo(403);
        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    // ---------------------------------------------------------------- helpers

    private void denyWith(String reason) {
        when(bridgeAccessGuard.check(any(), any(), any()))
            .thenReturn(new BridgeAccessDecision(false, reason, BRIDGE, null));
    }

    private void existingAgentOn(String provider) {
        AgentEntity existing = new AgentEntity();
        existing.setId(AGENT_ID);
        existing.setModelProvider(provider);
        lenient().when(agentService.getAgent(AGENT_ID, TENANT_ID, ORG_ID, null))
            .thenReturn(Optional.of(existing));
    }

    private static Map<String, Object> bodyWithProvider(String provider) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Agenda Scout");
        body.put("modelProvider", provider);
        body.put("modelName", "claude-fable-5");
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(org.springframework.http.ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private void stubCreate() {
        AgentEntity created = new AgentEntity();
        created.setId(AGENT_ID);
        lenient().when(agentService.createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(created);
    }

    @SuppressWarnings("unchecked")
    private void stubUpdate() {
        AgentEntity updated = new AgentEntity();
        updated.setId(AGENT_ID);
        lenient().when(agentService.updateAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean()))
            .thenReturn(updated);
    }

    @SuppressWarnings("unchecked")
    private void verifyNothingWasCreated() {
        verify(agentService, never()).createAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private void verifyNothingWasUpdated() {
        verify(agentService, never()).updateAgent(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean());
    }
}
