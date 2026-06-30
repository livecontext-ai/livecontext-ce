package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.client.dto.ActiveAgentWebhookTokenDto;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentWebhookTokenEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentSkillRepository;
import com.apimarketplace.agent.repository.AgentWebhookTokenRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.SkillFolderService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.service.execution.AgentActivitySnapshotService;
import com.apimarketplace.agent.service.execution.ConversationStopCascadeService;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link InternalAgentController#getActiveAgentWebhookTokens(HttpServletRequest)} -
 * the batch endpoint that backs orchestrator's home-page "active automations" widget.
 *
 * <p>Contract: returns one {@link ActiveAgentWebhookTokenDto} per active webhook
 * row owned by the resolved tenant. The repository's tenant filter is the gate;
 * inactive rows are dropped at the SQL layer (see
 * {@code AgentWebhookTokenRepository#findActiveByTenantId}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAgentController - GET /active-webhook-tokens")
class InternalAgentControllerActiveWebhooksTest {

    @Mock private AgentService agentService;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentSkillRepository agentSkillRepository;
    @Mock private AgentWebhookTokenRepository webhookTokenRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private SkillService skillService;
    @Mock private SkillFolderService skillFolderService;
    @Mock private AgentObservabilityService observabilityService;
    @Mock private TenantResolver tenantResolver;
    @Mock private RequestParameterExtractor extractor;
    @Mock private ConversationStopCascadeService conversationStopCascadeService;
    @Mock private AgentActivitySnapshotService agentActivitySnapshotService;

    private InternalAgentController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAgentController(
                agentService, agentRepository, agentSkillRepository, webhookTokenRepository,
                skillRepository, skillService, skillFolderService, observabilityService,
                tenantResolver, extractor, conversationStopCascadeService,
                agentActivitySnapshotService);
    }

    @Test
    @DisplayName("Maps repository entities to DTOs preserving agentId, token, httpMethod, isActive, updatedAt")
    void mapsRepositoryRowsToDtos() {
        UUID agentA = UUID.randomUUID();
        UUID agentB = UUID.randomUUID();
        Instant updated = Instant.parse("2026-05-07T12:00:00Z");

        AgentWebhookTokenEntity tokA = entity(agentA, "tok_a", "POST", true, updated);
        AgentWebhookTokenEntity tokB = entity(agentB, "tok_b", "PUT", true, updated);

        MockHttpServletRequest req = new MockHttpServletRequest();
        when(tenantResolver.resolve(req)).thenReturn("user-7");
        when(webhookTokenRepository.findActiveByTenantId("user-7")).thenReturn(List.of(tokA, tokB));

        ResponseEntity<List<ActiveAgentWebhookTokenDto>> res = controller.getActiveAgentWebhookTokens(req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).hasSize(2);

        ActiveAgentWebhookTokenDto first = res.getBody().get(0);
        assertThat(first.getAgentId()).isEqualTo(agentA);
        assertThat(first.getToken()).isEqualTo("tok_a");
        assertThat(first.getHttpMethod()).isEqualTo("POST");
        assertThat(first.getIsActive()).isTrue();
        assertThat(first.getUpdatedAt()).isEqualTo(updated);
    }

    @Test
    @DisplayName("Repository order is preserved - controller does not re-sort or shuffle the list")
    void preservesRepositoryOrdering() {
        // The repository's JPQL applies ORDER BY t.createdAt ASC. The controller
        // must NOT re-sort or use a Set; otherwise the deterministic "oldest token
        // wins" contract for agents with multiple tokens silently breaks.
        UUID agent = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-05-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-05-05T00:00:00Z");
        AgentWebhookTokenEntity older = entity(agent, "tok_older", "POST", true, t1);
        AgentWebhookTokenEntity newer = entity(agent, "tok_newer", "PUT", true, t2);

        MockHttpServletRequest req = new MockHttpServletRequest();
        when(tenantResolver.resolve(req)).thenReturn("user-7");
        when(webhookTokenRepository.findActiveByTenantId("user-7")).thenReturn(List.of(older, newer));

        ResponseEntity<List<ActiveAgentWebhookTokenDto>> res = controller.getActiveAgentWebhookTokens(req);

        assertThat(res.getBody()).extracting(ActiveAgentWebhookTokenDto::getToken)
                .containsExactly("tok_older", "tok_newer");
    }

    @Test
    @DisplayName("Empty repository result → 200 with empty list (no NullPointerException)")
    void emptyRepositoryReturnsEmptyList() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        when(tenantResolver.resolve(req)).thenReturn("solo-user");
        when(webhookTokenRepository.findActiveByTenantId("solo-user")).thenReturn(List.of());

        ResponseEntity<List<ActiveAgentWebhookTokenDto>> res = controller.getActiveAgentWebhookTokens(req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEmpty();
    }

    @Test
    @DisplayName("internal get lookup forwards active organization scope to AgentService")
    void getAgentInternalUsesOrganizationScope() {
        UUID agentId = UUID.randomUUID();
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTenantId("user-7");
        agent.setOrganizationId("org-7");
        agent.setName("Org Agent");
        agent.setModelProvider("deepseek");
        agent.setModelName("deepseek-chat");

        // 2026-05-21 - controller reads X-Organization-ID/Role directly from
        // the request (defense-in-depth on top of AgentService self-heal).
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Organization-ID", "org-7");
        req.addHeader("X-Organization-Role", "MEMBER");
        when(tenantResolver.resolve(req)).thenReturn("user-7");
        when(agentService.getAgent(agentId, "user-7", "org-7", "MEMBER"))
                .thenReturn(Optional.of(agent));

        ResponseEntity<com.apimarketplace.agent.client.dto.AgentDto> res =
                controller.getAgentInternal(agentId, req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getId()).isEqualTo(agentId);
        assertThat(res.getBody().getModelProvider()).isEqualTo("deepseek");
        assertThat(res.getBody().getModelName()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("by-config lookup forwards active organization scope to AgentService")
    void resolveAgentConfigUsesOrganizationScope() {
        UUID agentId = UUID.randomUUID();
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTenantId("user-7");
        agent.setOrganizationId("org-7");
        agent.setName("Org Agent");
        agent.setModelProvider("deepseek");
        agent.setModelName("deepseek-chat");

        // 2026-05-21 - by-config also reads X-Organization-ID/Role directly.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Organization-ID", "org-7");
        req.addHeader("X-Organization-Role", "MEMBER");
        when(tenantResolver.resolve(req)).thenReturn("user-7");
        when(agentService.getAgent(agentId, "user-7", "org-7", "MEMBER"))
                .thenReturn(Optional.of(agent));

        ResponseEntity<com.apimarketplace.agent.client.dto.AgentDto> res =
                controller.resolveAgentConfig(agentId, req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getId()).isEqualTo(agentId);
        assertThat(res.getBody().getModelProvider()).isEqualTo("deepseek");
        assertThat(res.getBody().getModelName()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("bulkFind threads X-Organization-ID + X-Organization-Role to each AgentService.getAgent - regression: pre-fix 2-arg call returned 404 for non-OWNER org members on the dashboard widget")
    void bulkFindForwardsOrgScopeToEachLookup() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        AgentEntity e1 = new AgentEntity(); e1.setId(a1); e1.setTenantId("user-7"); e1.setOrganizationId("org-7"); e1.setName("A1");
                                              e1.setModelProvider("deepseek"); e1.setModelName("deepseek-chat");
        AgentEntity e2 = new AgentEntity(); e2.setId(a2); e2.setTenantId("user-7"); e2.setOrganizationId("org-7"); e2.setName("A2");
                                              e2.setModelProvider("deepseek"); e2.setModelName("deepseek-chat");

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Organization-ID", "org-7");
        req.addHeader("X-Organization-Role", "MEMBER");
        when(tenantResolver.resolve(req)).thenReturn("user-7");
        when(agentService.getAgent(a1, "user-7", "org-7", "MEMBER")).thenReturn(Optional.of(e1));
        when(agentService.getAgent(a2, "user-7", "org-7", "MEMBER")).thenReturn(Optional.of(e2));

        ResponseEntity<List<com.apimarketplace.agent.client.dto.AgentDto>> res =
                controller.bulkFind(List.of(a1, a2), req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).hasSize(2);
        assertThat(res.getBody().get(0).getId()).isEqualTo(a1);
        assertThat(res.getBody().get(1).getId()).isEqualTo(a2);
    }

    private static AgentWebhookTokenEntity entity(UUID agentId, String token, String method, boolean active, Instant updated) {
        AgentWebhookTokenEntity e = new AgentWebhookTokenEntity(agentId, token);
        e.setHttpMethod(method);
        e.setIsActive(active);
        e.setUpdatedAt(updated);
        return e;
    }
}
