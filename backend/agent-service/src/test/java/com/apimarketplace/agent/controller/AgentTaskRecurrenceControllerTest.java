package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentTaskRecurrenceEntity;
import com.apimarketplace.agent.dto.CreateRecurrenceRequest;
import com.apimarketplace.agent.dto.RecurrenceResponse;
import com.apimarketplace.agent.dto.UpdateRecurrenceRequest;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.service.AgentTaskRecurrenceService;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentTaskRecurrenceController}, the human-facing recurrence
 * REST API. This controller had ZERO coverage before the 2026-06-23 audit. The
 * service itself is covered by {@code AgentTaskRecurrenceServiceTest} +
 * {@code AgentTaskRecurrenceServiceUpdateDeleteTest}; here the service is mocked and
 * we pin the controller's status-code mapping and the {@code assertAgentInScope}
 * 403 guard.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskRecurrenceController")
class AgentTaskRecurrenceControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-A";

    @Mock private AgentTaskRecurrenceService recurrenceService;
    @Mock private TenantResolver tenantResolver;
    @Mock private AgentRepository agentRepository;

    private AgentTaskRecurrenceController controller;
    private final MockHttpServletRequest req = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        controller = new AgentTaskRecurrenceController(recurrenceService, tenantResolver, agentRepository);
        lenient().when(tenantResolver.resolve(any())).thenReturn(TENANT);
        lenient().when(tenantResolver.resolveOrgId(any())).thenReturn(ORG);
    }

    private static AgentTaskRecurrenceEntity rec() {
        AgentTaskRecurrenceEntity r = new AgentTaskRecurrenceEntity();
        r.setId(UUID.randomUUID());
        r.setTenantId(TENANT);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> res) {
        return (Map<String, Object>) res.getBody();
    }

    private static CreateRecurrenceRequest createReq() {
        return new CreateRecurrenceRequest("Nightly", "do it", "0 0 * * * *", "UTC",
                UUID.randomUUID(), "high", Map.of());
    }

    private static UpdateRecurrenceRequest updateReq() {
        return new UpdateRecurrenceRequest(false, null, "New title", null, null);
    }

    // ── list ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /recurrences without agent_id returns count+recurrences")
    void listNoAgent() {
        when(recurrenceService.list(TENANT, ORG, null, "all_in_tenant")).thenReturn(List.of(rec()));

        ResponseEntity<?> res = controller.list("all_in_tenant", null, req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(body(res)).containsEntry("count", 1).containsKey("recurrences");
        verify(agentRepository, never()).existsByIdAndOrganizationIdStrict(any(), any());
    }

    @Test
    @DisplayName("GET /recurrences with an in-scope agent_id filters via the service")
    void listAgentInScope() {
        UUID agent = UUID.randomUUID();
        when(agentRepository.existsByIdAndOrganizationIdStrict(agent, ORG)).thenReturn(true);
        when(recurrenceService.list(TENANT, ORG, agent, "created_by_me")).thenReturn(List.of());

        ResponseEntity<?> res = controller.list("created_by_me", agent, req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(recurrenceService).list(TENANT, ORG, agent, "created_by_me");
    }

    @Test
    @DisplayName("GET /recurrences with an out-of-scope agent_id -> 403, service untouched")
    void listAgentOutOfScope() {
        UUID agent = UUID.randomUUID();
        when(agentRepository.existsByIdAndOrganizationIdStrict(agent, ORG)).thenReturn(false);

        ResponseEntity<?> res = controller.list("all_in_tenant", agent, req);

        assertThat(res.getStatusCode().value()).isEqualTo(403);
        verify(recurrenceService, never()).list(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("GET /recurrences with an invalid scope -> 400")
    void listBadScope() {
        when(recurrenceService.list(TENANT, ORG, null, "bogus"))
                .thenThrow(new IllegalArgumentException("invalid scope: bogus"));

        ResponseEntity<?> res = controller.list("bogus", null, req);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // ── create ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /recurrences happy -> 200 RecurrenceResponse, forwards (tenant, null, tenant, request)")
    void createHappy() {
        CreateRecurrenceRequest request = createReq();
        when(recurrenceService.create(TENANT, null, TENANT, request)).thenReturn(rec());

        ResponseEntity<?> res = controller.create(request, req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isInstanceOf(RecurrenceResponse.class);
        verify(recurrenceService).create(TENANT, null, TENANT, request);
    }

    @Test
    @DisplayName("POST /recurrences with a validation error -> 400")
    void createBadRequest() {
        CreateRecurrenceRequest request = createReq();
        when(recurrenceService.create(TENANT, null, TENANT, request))
                .thenThrow(new IllegalArgumentException("title is required"));

        ResponseEntity<?> res = controller.create(request, req);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // ── update ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /recurrences/{id} happy -> 200")
    void updateHappy() {
        UUID id = UUID.randomUUID();
        UpdateRecurrenceRequest request = updateReq();
        when(recurrenceService.update(TENANT, id, null, TENANT, request)).thenReturn(rec());

        ResponseEntity<?> res = controller.update(id, request, req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(recurrenceService).update(TENANT, id, null, TENANT, request);
    }

    @Test
    @DisplayName("PUT /recurrences/{id}: not-found IllegalArgumentException -> 404, not-creator IllegalStateException -> 403")
    void updateErrorsMap() {
        UUID id = UUID.randomUUID();
        UpdateRecurrenceRequest request = updateReq();
        when(recurrenceService.update(TENANT, id, null, TENANT, request))
                .thenThrow(new IllegalArgumentException("recurrence not found"))
                .thenThrow(new IllegalStateException("only the creator may update"));

        assertThat(controller.update(id, request, req).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.update(id, request, req).getStatusCode().value()).isEqualTo(403);
    }

    // ── delete ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /recurrences/{id} happy -> 200 {deleted:true}")
    void deleteHappy() {
        UUID id = UUID.randomUUID();

        ResponseEntity<?> res = controller.delete(id, req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(body(res)).containsEntry("deleted", true);
        verify(recurrenceService).delete(TENANT, id, null, TENANT);
    }

    @Test
    @DisplayName("DELETE /recurrences/{id}: not-found -> 404, not-creator -> 403")
    void deleteErrorsMap() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalArgumentException("recurrence not found"))
                .doThrow(new IllegalStateException("only the creator may delete"))
                .when(recurrenceService).delete(TENANT, id, null, TENANT);

        assertThat(controller.delete(id, req).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.delete(id, req).getStatusCode().value()).isEqualTo(403);
    }
}
