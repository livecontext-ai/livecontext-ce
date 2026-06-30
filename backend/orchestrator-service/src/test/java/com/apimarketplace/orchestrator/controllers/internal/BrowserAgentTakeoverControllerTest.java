package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.tools.websearch.CdpTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the takeover-resume endpoint.
 *
 * <p>Mirrors the {@code InterfaceActionController.__continue} pattern:
 * find the active blocking signal for (runId, nodeId), resolve it with
 * CONTINUE, return 200; or return 404 when no signal is active.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserAgentTakeoverController")
class BrowserAgentTakeoverControllerTest {

    @Mock
    UnifiedSignalService signalService;

    BrowserAgentTakeoverController controller;

    @BeforeEach
    void setUp() {
        controller = new BrowserAgentTakeoverController(signalService);
    }

    @Test
    @DisplayName("Resolves the active BROWSER_USER_TAKEOVER signal and returns status=resumed")
    void resolvesActiveTakeoverSignal() {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(42L);
        entity.setRunId("run_1");
        entity.setNodeId("node_1");
        entity.setSignalType(SignalType.BROWSER_USER_TAKEOVER);
        entity.setEpoch(3);

        when(signalService.getActiveSignals("run_1")).thenReturn(List.of(entity));
        when(signalService.resolveSignal(eq(42L), eq(SignalResolution.CONTINUE), any(), anyString()))
                .thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.resume(
                "run_1", "node_1",
                Map.of("memory_injection", "user pasted a 2FA code"),
                "user_99", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "resumed");
        assertThat(response.getBody()).containsEntry("nodeId", "node_1");

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(signalService).resolveSignal(eq(42L), eq(SignalResolution.CONTINUE),
                dataCaptor.capture(), eq("user_99"));
        assertThat(dataCaptor.getValue())
                .containsEntry("resolved_by", "user_99")
                .containsEntry("memory_injection", "user pasted a 2FA code");
    }

    @Test
    @DisplayName("Returns 404 when no active takeover signal exists")
    void returns404WhenNoSignal() {
        when(signalService.getActiveSignals("run_1")).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.resume(
                "run_1", "node_1", null, "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(signalService).getActiveSignals("run_1");
    }

    @Test
    @DisplayName("Filters by signal type - INTERFACE_SIGNAL on the same node is ignored")
    void filtersBySignalType() {
        SignalWaitEntity ifaceEntity = new SignalWaitEntity();
        ifaceEntity.setId(7L);
        ifaceEntity.setRunId("run_1");
        ifaceEntity.setNodeId("node_1");
        ifaceEntity.setSignalType(SignalType.INTERFACE_SIGNAL);
        ifaceEntity.setEpoch(5);

        when(signalService.getActiveSignals("run_1")).thenReturn(List.of(ifaceEntity));

        ResponseEntity<Map<String, Object>> response = controller.resume(
                "run_1", "node_1", null, "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Picks the highest-epoch signal when several are active for the node")
    void picksHighestEpochSignal() {
        SignalWaitEntity oldEpoch = new SignalWaitEntity();
        oldEpoch.setId(1L);
        oldEpoch.setRunId("run_1");
        oldEpoch.setNodeId("node_1");
        oldEpoch.setSignalType(SignalType.BROWSER_USER_TAKEOVER);
        oldEpoch.setEpoch(1);

        SignalWaitEntity newEpoch = new SignalWaitEntity();
        newEpoch.setId(2L);
        newEpoch.setRunId("run_1");
        newEpoch.setNodeId("node_1");
        newEpoch.setSignalType(SignalType.BROWSER_USER_TAKEOVER);
        newEpoch.setEpoch(7);

        when(signalService.getActiveSignals("run_1")).thenReturn(List.of(oldEpoch, newEpoch));
        when(signalService.resolveSignal(eq(2L), any(), any(), anyString())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.resume(
                "run_1", "node_1", null, "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(signalService).resolveSignal(eq(2L), any(), any(), anyString());
    }

    @Test
    @DisplayName("Returns status=already_resolved when resolveSignal returns false (idempotent)")
    void returnsAlreadyResolvedWhenSignalRaceLost() {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(99L);
        entity.setRunId("run_1");
        entity.setNodeId("node_1");
        entity.setSignalType(SignalType.BROWSER_USER_TAKEOVER);
        entity.setEpoch(2);

        when(signalService.getActiveSignals("run_1")).thenReturn(List.of(entity));
        when(signalService.resolveSignal(eq(99L), any(), any(), anyString())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.resume(
                "run_1", "node_1", null, "user_2", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "already_resolved");
    }

    @Test
    @DisplayName("resume: no signal but body session_id -> pushes runner RESUME to that session (chat agent_browse hold release)")
    void resumeChatUsesBodySessionIdForRunnerResume() {
        // A chat agent_browse raises no workflow signal, so the controller has
        // no signal config to read the sessionId from. The live panel sends it
        // in the body; the controller must use it to wake the held runner.
        RestTemplate rest = org.mockito.Mockito.mock(RestTemplate.class);
        WebSearchConfig cfg = org.mockito.Mockito.mock(WebSearchConfig.class);
        when(cfg.getServiceUrl()).thenReturn("http://websearch:8085");
        when(signalService.getActiveSignals("run_chat")).thenReturn(List.of());

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, rest, cfg, null);

        ResponseEntity<Map<String, Object>> response = c.resume(
                "run_chat", "node_chat", Map.of("session_id", "ses_abc"), "user_1", null);

        // Still 404 (no workflow signal to resolve), but the runner-level
        // RESUME fired against the body's session so the hold actually releases.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(rest).postForObject(urlCaptor.capture(), any(), eq(Map.class));
        assertThat(urlCaptor.getValue())
                .isEqualTo("http://websearch:8085/agent/sessions/ses_abc/resume");
    }

    @Test
    @DisplayName("resume: no signal AND no body session_id -> no runner RESUME pushed (nothing to target)")
    void resumeNoSignalNoBodySessionIdPushesNothing() {
        RestTemplate rest = org.mockito.Mockito.mock(RestTemplate.class);
        WebSearchConfig cfg = org.mockito.Mockito.mock(WebSearchConfig.class);
        when(signalService.getActiveSignals("run_x")).thenReturn(List.of());

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, rest, cfg, null);

        ResponseEntity<Map<String, Object>> response = c.resume(
                "run_x", "node_x", null, "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(rest, org.mockito.Mockito.never()).postForObject(anyString(), any(), eq(Map.class));
    }

    // ── /cdp-token-refresh ─────────────────────────────────────────────

    @Test
    @DisplayName("refresh: mints fresh token + cdp_ws_url when signal is active and session_id matches")
    void refreshMintsTokenWhenSignalActive() {
        CdpTokenIssuer issuer = org.mockito.Mockito.mock(CdpTokenIssuer.class);
        WebSearchConfig cfg = org.mockito.Mockito.mock(WebSearchConfig.class);
        when(issuer.isConfigured()).thenReturn(true);
        when(issuer.issue("sess_xyz", "user_1", "run_1", "node_1")).thenReturn("eyJfresh.token");
        when(cfg.getPublicWsBase()).thenReturn("https://websearch-host.example.com");

        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(1L);
        entity.setRunId("run_1");
        entity.setNodeId("node_1");
        entity.setSignalType(SignalType.BROWSER_USER_TAKEOVER);
        entity.setEpoch(1);
        entity.setSignalConfig(Map.of("sessionId", "sess_xyz"));
        when(signalService.getActiveSignals("run_1")).thenReturn(List.of(entity));

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, null, cfg, issuer);

        // Body uses snake_case "session_id" (controller reads
        // body.get("session_id")); signal storage uses camelCase
        // "sessionId" (SignalConfig.browserTakeover writes that key).
        ResponseEntity<Map<String, Object>> response = c.refreshToken(
                "run_1", "node_1", Map.of("session_id", "sess_xyz"), "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("cdp_token", "eyJfresh.token")
                .containsEntry("cdp_ws_url", "wss://websearch-host.example.com/cdp/sess_xyz")
                .containsEntry("session_id", "sess_xyz");

        // Explicit verify so a regression that drops X-User-ID into the
        // issuer (or substitutes a constant) gets caught - the JWT 'sub'
        // claim must reflect the actual requester.
        verify(issuer).issue(eq("sess_xyz"), eq("user_1"), eq("run_1"), eq("node_1"));
    }

    @Test
    @DisplayName("refresh: 400 when signal config has no sessionId - fail closed, do NOT pass through")
    void refresh400WhenSignalConfigMissingSessionId() {
        // A signal that registered without a sessionId is malformed; the
        // refresh endpoint refuses rather than allowing the requester to
        // bind any session_id of their choosing.
        CdpTokenIssuer issuer = org.mockito.Mockito.mock(CdpTokenIssuer.class);
        when(issuer.isConfigured()).thenReturn(true);

        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(1L);
        entity.setRunId("run_1");
        entity.setNodeId("node_1");
        entity.setSignalType(SignalType.BROWSER_USER_TAKEOVER);
        entity.setEpoch(1);
        entity.setSignalConfig(Map.of("type", "BROWSER_USER_TAKEOVER")); // no sessionId
        when(signalService.getActiveSignals("run_1")).thenReturn(List.of(entity));

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, null, null, issuer);

        ResponseEntity<Map<String, Object>> response = c.refreshToken(
                "run_1", "node_1", Map.of("session_id", "sess_xyz"), "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Issuer must NOT be called when the signal can't prove a sessionId.
        org.mockito.Mockito.verify(issuer, org.mockito.Mockito.never())
                .issue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("refresh: 404 when no active takeover signal - frontend stops retrying")
    void refresh404WhenWorkflowAdvanced() {
        CdpTokenIssuer issuer = org.mockito.Mockito.mock(CdpTokenIssuer.class);
        when(issuer.isConfigured()).thenReturn(true);
        when(signalService.getActiveSignals("run_1")).thenReturn(List.of());

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, null, null, issuer);

        ResponseEntity<Map<String, Object>> response = c.refreshToken(
                "run_1", "node_1", Map.of("session_id", "sess_xyz"), "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("refresh: 400 when session_id missing from body")
    void refresh400WhenSessionIdMissing() {
        CdpTokenIssuer issuer = org.mockito.Mockito.mock(CdpTokenIssuer.class);
        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, null, null, issuer);

        ResponseEntity<Map<String, Object>> response = c.refreshToken(
                "run_1", "node_1", Map.of(), "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("refresh: 400 when body session_id mismatches the signal's stored session_id")
    void refresh400WhenSessionIdMismatch() {
        CdpTokenIssuer issuer = org.mockito.Mockito.mock(CdpTokenIssuer.class);
        when(issuer.isConfigured()).thenReturn(true);

        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(1L);
        entity.setRunId("run_1");
        entity.setNodeId("node_1");
        entity.setSignalType(SignalType.BROWSER_USER_TAKEOVER);
        entity.setEpoch(1);
        entity.setSignalConfig(Map.of("sessionId", "sess_real"));
        when(signalService.getActiveSignals("run_1")).thenReturn(List.of(entity));

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, null, null, issuer);

        ResponseEntity<Map<String, Object>> response = c.refreshToken(
                "run_1", "node_1", Map.of("session_id", "sess_attacker"), "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("refresh: 503 when issuer is unconfigured (secret blank)")
    void refresh503WhenIssuerUnconfigured() {
        CdpTokenIssuer issuer = org.mockito.Mockito.mock(CdpTokenIssuer.class);
        when(issuer.isConfigured()).thenReturn(false);

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, null, null, issuer);

        ResponseEntity<Map<String, Object>> response = c.refreshToken(
                "run_1", "node_1", Map.of("session_id", "sess_xyz"), "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ── run-ownership gate (CE-DIV-1) ──────────────────────────────────
    // Without this gate any authenticated caller who learns a runId could resume
    // (or mint a live CDP token for) another tenant's browser-agent run. The gate
    // requires the caller's (X-User-ID, X-Organization-ID) to scope-match the run.

    private static com.apimarketplace.orchestrator.domain.WorkflowRunEntity runOwnedBy(String tenantId, String orgId) {
        com.apimarketplace.orchestrator.domain.WorkflowRunEntity run =
                new com.apimarketplace.orchestrator.domain.WorkflowRunEntity();
        run.setTenantId(tenantId);
        run.setOrganizationId(orgId);
        return run;
    }

    @Test
    @DisplayName("resume: 404 and NO signal resolution when the caller does not own the run (cross-tenant runId guess)")
    void resumeReturns404WhenCallerDoesNotOwnRun() {
        WorkflowRunRepository runRepo = org.mockito.Mockito.mock(WorkflowRunRepository.class);
        when(runRepo.findByRunIdPublic("run_victim")).thenReturn(Optional.of(runOwnedBy("user_victim", null)));
        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, null, null, null, runRepo);

        ResponseEntity<Map<String, Object>> response = c.resume(
                "run_victim", "node_1", Map.of("memory_injection", "attacker text"), "user_attacker", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The gate fires BEFORE any signal lookup/resolution - no cross-tenant resume or memory injection.
        verify(signalService, org.mockito.Mockito.never()).getActiveSignals(anyString());
        verify(signalService, org.mockito.Mockito.never()).resolveSignal(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("resume: proceeds and resolves when the caller owns the run (gate does not over-block the owner)")
    void resumeProceedsWhenCallerOwnsRun() {
        WorkflowRunRepository runRepo = org.mockito.Mockito.mock(WorkflowRunRepository.class);
        when(runRepo.findByRunIdPublic("run_mine")).thenReturn(Optional.of(runOwnedBy("user_owner", null)));

        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(55L);
        entity.setRunId("run_mine");
        entity.setNodeId("node_1");
        entity.setSignalType(SignalType.BROWSER_USER_TAKEOVER);
        entity.setEpoch(1);
        when(signalService.getActiveSignals("run_mine")).thenReturn(List.of(entity));
        when(signalService.resolveSignal(eq(55L), eq(SignalResolution.CONTINUE), any(), anyString())).thenReturn(true);

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, null, null, null, runRepo);

        ResponseEntity<Map<String, Object>> response = c.resume(
                "run_mine", "node_1", null, "user_owner", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "resumed");
    }

    @Test
    @DisplayName("resume: 404 when the run is unknown (no existence leak)")
    void resumeReturns404WhenRunUnknown() {
        WorkflowRunRepository runRepo = org.mockito.Mockito.mock(WorkflowRunRepository.class);
        when(runRepo.findByRunIdPublic("run_ghost")).thenReturn(Optional.empty());
        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, null, null, null, runRepo);

        ResponseEntity<Map<String, Object>> response = c.resume(
                "run_ghost", "node_1", null, "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(signalService, org.mockito.Mockito.never()).getActiveSignals(anyString());
    }

    @Test
    @DisplayName("resume: CHAT run (no workflow_runs row) - owner matches agent:browse:meta userId -> gate PASSES + runner RESUME pushed")
    void resumeChatOwnerPassesGate() {
        // A chat agent_browse has no workflow_runs row; ownership is the
        // submitter recorded in the meta hash. Owner -> gate passes -> the
        // no-signal path runs and pushes the runner RESUME (the hold releases).
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOps = org.mockito.Mockito.mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.get("agent:browse:meta:run_chat:node_chat", "userId")).thenReturn("user_owner");

        WorkflowRunRepository runRepo = org.mockito.Mockito.mock(WorkflowRunRepository.class);
        when(runRepo.findByRunIdPublic("run_chat")).thenReturn(Optional.empty());
        when(signalService.getActiveSignals("run_chat")).thenReturn(List.of());

        RestTemplate rest = org.mockito.Mockito.mock(RestTemplate.class);
        WebSearchConfig cfg = org.mockito.Mockito.mock(WebSearchConfig.class);
        when(cfg.getServiceUrl()).thenReturn("http://websearch:8085");

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, rest, cfg, null, runRepo, redis);

        ResponseEntity<Map<String, Object>> response = c.resume(
                "run_chat", "node_chat", Map.of("session_id", "ses_abc"), "user_owner", null);

        // No workflow signal -> 404, but the gate PASSED (owner) so the runner
        // RESUME fired against the body's session -> the chat hold releases.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(rest).postForObject(
                eq("http://websearch:8085/agent/sessions/ses_abc/resume"), any(), eq(Map.class));
    }

    @Test
    @DisplayName("resume: CHAT run - caller userId != meta hash userId -> gate 404s, NO signal lookup, NO resume pushed")
    void resumeChatNonOwnerFailsGate() {
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOps = org.mockito.Mockito.mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.get("agent:browse:meta:run_chat:node_chat", "userId")).thenReturn("user_owner");

        WorkflowRunRepository runRepo = org.mockito.Mockito.mock(WorkflowRunRepository.class);
        when(runRepo.findByRunIdPublic("run_chat")).thenReturn(Optional.empty());
        RestTemplate rest = org.mockito.Mockito.mock(RestTemplate.class);

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, rest, null, null, runRepo, redis);

        ResponseEntity<Map<String, Object>> response = c.resume(
                "run_chat", "node_chat", Map.of("session_id", "ses_abc"), "user_attacker", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(signalService, org.mockito.Mockito.never()).getActiveSignals(anyString());
        verify(rest, org.mockito.Mockito.never()).postForObject(anyString(), any(), eq(Map.class));
    }

    @Test
    @DisplayName("refresh: 404 and NO token minted when the caller does not own the run")
    void refreshReturns404WhenCallerDoesNotOwnRun() {
        CdpTokenIssuer issuer = org.mockito.Mockito.mock(CdpTokenIssuer.class);
        WorkflowRunRepository runRepo = org.mockito.Mockito.mock(WorkflowRunRepository.class);
        when(runRepo.findByRunIdPublic("run_victim")).thenReturn(Optional.of(runOwnedBy("user_victim", null)));

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, null, null, issuer, runRepo);

        ResponseEntity<Map<String, Object>> response = c.refreshToken(
                "run_victim", "node_1", Map.of("session_id", "sess_xyz"), "user_attacker", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // No live CDP token is minted for a run the caller doesn't own.
        verify(issuer, org.mockito.Mockito.never()).issue(anyString(), anyString(), anyString(), anyString());
        verify(signalService, org.mockito.Mockito.never()).getActiveSignals(anyString());
    }

    // ── /final-screenshot (WS-independent last-page fallback) ──────────
    // The runner stores the final page (base64 JPEG) in Redis keyed by
    // (runId,nodeId); this endpoint proxies websearch behind the same
    // run-ownership gate so the panel can show the last page when the
    // live CDP screencast never connected.

    @Test
    @DisplayName("final-screenshot: 200 proxies mime + data_base64 from the (runId,nodeId)-keyed websearch path")
    void finalScreenshotProxiesCapture() {
        RestTemplate rest = org.mockito.Mockito.mock(RestTemplate.class);
        WebSearchConfig cfg = org.mockito.Mockito.mock(WebSearchConfig.class);
        when(cfg.getServiceUrl()).thenReturn("http://websearch:8085");
        when(rest.getForObject(anyString(), eq(Map.class)))
                .thenReturn(Map.of("mime", "image/jpeg", "data_base64", "aGVsbG8="));

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, rest, cfg, null);

        ResponseEntity<Map<String, Object>> response = c.finalScreenshot(
                "run_1", "node_1", "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("mime", "image/jpeg")
                .containsEntry("data_base64", "aGVsbG8=")
                .containsEntry("runId", "run_1")
                .containsEntry("nodeId", "node_1");

        // Keyed by (runId,nodeId) - NO client-supplied session id - so an
        // owner of run A cannot read run B's capture.
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(rest).getForObject(urlCaptor.capture(), eq(Map.class));
        assertThat(urlCaptor.getValue())
                .isEqualTo("http://websearch:8085/agent/runs/run_1/nodes/node_1/final-screenshot");
    }

    @Test
    @DisplayName("final-screenshot: 404 when websearch has no capture yet (frontend keeps polling)")
    void finalScreenshot404WhenWebsearchNotFound() {
        RestTemplate rest = org.mockito.Mockito.mock(RestTemplate.class);
        WebSearchConfig cfg = org.mockito.Mockito.mock(WebSearchConfig.class);
        when(cfg.getServiceUrl()).thenReturn("http://websearch:8085");
        when(rest.getForObject(anyString(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND,
                        "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, rest, cfg, null);

        ResponseEntity<Map<String, Object>> response = c.finalScreenshot(
                "run_1", "node_1", "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("final-screenshot: 404 when the websearch reply carries no data_base64")
    void finalScreenshot404WhenNoData() {
        RestTemplate rest = org.mockito.Mockito.mock(RestTemplate.class);
        WebSearchConfig cfg = org.mockito.Mockito.mock(WebSearchConfig.class);
        when(cfg.getServiceUrl()).thenReturn("http://websearch:8085");
        when(rest.getForObject(anyString(), eq(Map.class))).thenReturn(Map.of("mime", "image/jpeg"));

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, rest, cfg, null);

        ResponseEntity<Map<String, Object>> response = c.finalScreenshot(
                "run_1", "node_1", "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("final-screenshot: 404 and NO websearch call when the caller does not own the run")
    void finalScreenshot404WhenCallerDoesNotOwnRun() {
        RestTemplate rest = org.mockito.Mockito.mock(RestTemplate.class);
        WebSearchConfig cfg = org.mockito.Mockito.mock(WebSearchConfig.class);
        WorkflowRunRepository runRepo = org.mockito.Mockito.mock(WorkflowRunRepository.class);
        when(runRepo.findByRunIdPublic("run_victim"))
                .thenReturn(Optional.of(runOwnedBy("user_victim", null)));

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, rest, cfg, null, runRepo);

        ResponseEntity<Map<String, Object>> response = c.finalScreenshot(
                "run_victim", "node_1", "user_attacker", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Gate fires BEFORE any proxy call - no cross-tenant capture read.
        verify(rest, org.mockito.Mockito.never()).getForObject(anyString(), eq(Map.class));
    }

    @Test
    @DisplayName("final-screenshot: 404 (not 500) on a non-404 websearch error - proxy failure is swallowed")
    void finalScreenshot404OnGenericError() {
        RestTemplate rest = org.mockito.Mockito.mock(RestTemplate.class);
        WebSearchConfig cfg = org.mockito.Mockito.mock(WebSearchConfig.class);
        when(cfg.getServiceUrl()).thenReturn("http://websearch:8085");
        when(rest.getForObject(anyString(), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("connect timeout"));

        BrowserAgentTakeoverController c = new BrowserAgentTakeoverController(
                signalService, rest, cfg, null);

        ResponseEntity<Map<String, Object>> response = c.finalScreenshot(
                "run_1", "node_1", "user_1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
