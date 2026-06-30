package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.SkillFolderService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.service.SkillService.SkillAssignment;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the per-resource WRITE gate on
 * {@link SkillController#setAgentSkills} (PUT /api/agents/{agentId}/skills).
 *
 * <p>Before the fix the endpoint was gated only by the coarse org-wide
 * {@code isViewerRole} check, which blocks ONLY the VIEWER role. A non-VIEWER
 * org MEMBER restricted to READ/DENY on the specific agent could still rewrite
 * its skill set. The fix adds {@code agentService.assertCanWriteAgent(...)}
 * (same helper the webhook/schedule/widget endpoints use) BEFORE the
 * skillService mutation, so a per-resource deny → {@link OrgAccessDeniedException}
 * (mapped to HTTP 403 by the auth-client advice) and the mutation never runs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillController.setAgentSkills - per-resource write gate")
class SkillControllerSetAgentSkillsWriteGateTest {

    @Mock
    private SkillService skillService;
    @Mock
    private SkillFolderService skillFolderService;
    @Mock
    private AgentService agentService;
    @Mock
    private TenantResolver tenantResolver;
    @Mock
    private HttpServletRequest httpRequest;
    @Mock
    private com.apimarketplace.auth.client.access.OrgAccessGuard orgAccessGuard;

    private SkillController controller;

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SKILL_ID = UUID.randomUUID();
    private static final String TENANT_ID = "user-7";
    private static final String ORG_ID = "org-42";

    private static final List<Map<String, Object>> BODY =
            List.of(Map.of("skillId", SKILL_ID.toString()));

    @BeforeEach
    void setUp() {
        // Real RequestParameterExtractor - pure helper, no collaborators to mock.
        controller = new SkillController(
                skillService, skillFolderService, agentService,
                tenantResolver, new RequestParameterExtractor(), orgAccessGuard);
    }

    /** Caller resolves to a non-VIEWER MEMBER in org-42 (passes the coarse role check). */
    private void memberCaller() {
        when(tenantResolver.resolveOrNull(httpRequest)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(httpRequest)).thenReturn("MEMBER");
    }

    @Test
    @DisplayName("restricted MEMBER (canWrite=false on this agent) → OrgAccessDeniedException, skillService never mutated")
    void deniedForRestrictedMember() {
        memberCaller();
        // assertCanWriteAgent is the gate: a READ/DENY-restricted MEMBER trips it.
        doThrow(new OrgAccessDeniedException("agent", AGENT_ID.toString()))
                .when(agentService).assertCanWriteAgent(AGENT_ID, TENANT_ID, ORG_ID);

        assertThatThrownBy(() -> controller.setAgentSkills(AGENT_ID, httpRequest, BODY))
                .isInstanceOf(OrgAccessDeniedException.class)
                .hasMessageContaining("agent");

        // The write must NOT have happened.
        verify(skillService, never()).setAgentSkills(any(), any(), any(), anyList());
    }

    @Test
    @DisplayName("MEMBER with write access (canWrite=true) → gate passes, skillService.setAgentSkills runs, 200")
    void allowedWhenCanWriteTrue() {
        memberCaller();
        // assertCanWriteAgent returns void (no throw) == authorized.

        ResponseEntity<Void> response = controller.setAgentSkills(AGENT_ID, httpRequest, BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Gate is consulted BEFORE the mutation.
        InOrder order = inOrder(agentService, skillService);
        order.verify(agentService).assertCanWriteAgent(AGENT_ID, TENANT_ID, ORG_ID);
        order.verify(skillService).setAgentSkills(
                eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), anyList());
    }

    @Test
    @DisplayName("the single assignment is forwarded with the body's skillId")
    void forwardsAssignment() {
        memberCaller();

        controller.setAgentSkills(AGENT_ID, httpRequest, BODY);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<SkillAssignment>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(skillService).setAgentSkills(eq(AGENT_ID), eq(TENANT_ID), eq(ORG_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).skillId()).isEqualTo(SKILL_ID);
    }

    @Test
    @DisplayName("VIEWER role short-circuits with 403 BEFORE the per-resource gate is even consulted")
    void viewerShortCircuitsBeforeGate() {
        when(tenantResolver.resolveOrNull(httpRequest)).thenReturn(TENANT_ID);
        when(tenantResolver.resolveOrgId(httpRequest)).thenReturn(ORG_ID);
        when(tenantResolver.resolveOrgRole(httpRequest)).thenReturn("VIEWER");

        ResponseEntity<Void> response = controller.setAgentSkills(AGENT_ID, httpRequest, BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(agentService, never()).assertCanWriteAgent(any(), any(), any());
        verify(skillService, never()).setAgentSkills(any(), any(), any(), anyList());
    }
}
