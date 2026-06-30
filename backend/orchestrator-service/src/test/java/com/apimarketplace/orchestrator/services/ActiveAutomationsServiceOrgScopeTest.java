package com.apimarketplace.orchestrator.services;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the post-V261 strict-org behaviour of
 * {@link ActiveAutomationsService}.
 *
 * <p>Three contracts are pinned here:
 * <ol>
 *   <li>With a non-blank {@code orgId}, schedules are pulled via
 *       {@link TriggerClient#getSchedulesByOrganization} (NOT the tenant-only
 *       finder). Pre-V220 the tenant path silently dropped teammate-created
 *       schedules whose {@code tenant_id} differed from the caller's user id.</li>
 *   <li>With a non-blank {@code orgId}, workflows are pulled via
 *       {@link WorkflowRepository#findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc}
 *       (NOT a tenant-only finder). Pre-V261 the tenant path leaked
 *       cross-workspace rows for users belonging to multiple orgs.</li>
 *   <li>With a null {@code orgId} (defensive fallback for any pre-V261 caller),
 *       the service returns an empty list rather than falling back to
 *       tenant-scope finders - V261 NOT NULL means production traffic always
 *       has an org, and a defensive empty list is safer than silently leaking
 *       legacy personal-scope rows.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActiveAutomationsService - post-V261 strict-org routing")
class ActiveAutomationsServiceOrgScopeTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerClient triggerClient;
    @Mock private AgentClient agentClient;

    private ActiveAutomationsService service;

    private static final String TENANT_ID = "tenant-77";
    private static final String CALLER_ORG_ID = "org-1234-abcd";
    private static final String CALLER_ORG_ROLE = "MEMBER";

    @BeforeEach
    void setUp() {
        service = new ActiveAutomationsService(workflowRepository, runRepository,
                triggerClient, agentClient);
    }

    @Test
    @DisplayName("orgId present routes schedules via getSchedulesByOrganization (NOT getSchedulesByTenant) - pre-V220 leak")
    void orgScopeUsesOrgScopedScheduleFinder() {
        when(triggerClient.getSchedulesByOrganization(eq(CALLER_ORG_ID)))
                .thenReturn(Collections.emptyList());
        when(workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(CALLER_ORG_ID)))
                .thenReturn(Collections.emptyList());
        when(agentClient.getAgents(eq(TENANT_ID), eq(CALLER_ORG_ID), eq(CALLER_ORG_ROLE)))
                .thenReturn(Collections.emptyList());

        List<ActiveAutomationDto> result = service.getActiveAutomations(
                TENANT_ID, CALLER_ORG_ID, CALLER_ORG_ROLE);

        assertThat(result).isEmpty();
        // Regression pin: org-scoped finder, NOT tenant-scoped.
        verify(triggerClient).getSchedulesByOrganization(eq(CALLER_ORG_ID));
        verify(triggerClient, never()).getSchedulesByTenant(any());
    }

    @Test
    @DisplayName("orgId present pulls workflows via strict-org finder (NOT tenant-scope)")
    void orgScopeUsesStrictOrgWorkflowFinder() {
        when(triggerClient.getSchedulesByOrganization(eq(CALLER_ORG_ID)))
                .thenReturn(Collections.emptyList());
        when(workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(CALLER_ORG_ID)))
                .thenReturn(Collections.emptyList());
        when(agentClient.getAgents(eq(TENANT_ID), eq(CALLER_ORG_ID), eq(CALLER_ORG_ROLE)))
                .thenReturn(Collections.emptyList());

        service.getActiveAutomations(TENANT_ID, CALLER_ORG_ID, CALLER_ORG_ROLE);

        // Regression pin: strict-org finder is the only repository surface.
        verify(workflowRepository).findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(CALLER_ORG_ID));
    }

    @Test
    @DisplayName("orgId null (defensive fallback) skips the strict-org workflow finder; returns empty list (no cross-tenant leak)")
    void nullOrgIdReturnsEmptyWithoutCallingTenantFinder() {
        // In production, gateway always injects X-Organization-ID - this branch
        // is purely defensive against pre-V261 callers still passing null. The
        // legacy code path called a tenant-only finder which leaked cross-org
        // workflows when the user belonged to multiple orgs. Empty list is the
        // safe default.
        when(triggerClient.getSchedulesByTenant(eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());
        when(agentClient.getAgents(eq(TENANT_ID), eq(null), eq(CALLER_ORG_ROLE)))
                .thenReturn(Collections.emptyList());

        List<ActiveAutomationDto> result = service.getActiveAutomations(
                TENANT_ID, /* orgId */ null, CALLER_ORG_ROLE);

        assertThat(result).isEmpty();
        // Defensive empty-list path MUST NOT call the strict-org workflow finder
        // with null (would NPE on the param check) nor a legacy tenant-only
        // finder (would leak cross-workspace rows).
        verify(workflowRepository, never())
                .findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("orgId present forwards orgId+role to agentClient.getAgents")
    void orgScopeForwardsBothOrgIdAndRoleToAgentClient() {
        when(triggerClient.getSchedulesByOrganization(eq(CALLER_ORG_ID)))
                .thenReturn(Collections.emptyList());
        when(workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(CALLER_ORG_ID)))
                .thenReturn(Collections.emptyList());
        when(agentClient.getAgents(eq(TENANT_ID), eq(CALLER_ORG_ID), eq(CALLER_ORG_ROLE)))
                .thenReturn(Collections.emptyList());

        service.getActiveAutomations(TENANT_ID, CALLER_ORG_ID, CALLER_ORG_ROLE);

        // The 3-arg overload threads orgId AND orgRole - both are required by
        // the server-side org access filter (role determines which deny-list
        // restrictions apply to the caller).
        verify(agentClient).getAgents(eq(TENANT_ID), eq(CALLER_ORG_ID), eq(CALLER_ORG_ROLE));
    }

    @Test
    @DisplayName("Empty agent fleet skips the webhook-token batch call (DB-load savings)")
    void emptyAgentFleetSkipsWebhookTokenLookup() {
        when(triggerClient.getSchedulesByOrganization(eq(CALLER_ORG_ID)))
                .thenReturn(Collections.emptyList());
        when(workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(eq(CALLER_ORG_ID)))
                .thenReturn(Collections.emptyList());
        when(agentClient.getAgents(eq(TENANT_ID), eq(CALLER_ORG_ID), eq(CALLER_ORG_ROLE)))
                .thenReturn(Collections.emptyList());

        service.getActiveAutomations(TENANT_ID, CALLER_ORG_ID, CALLER_ORG_ROLE);

        // Optimization pin: agent_webhook_tokens FK-CASCADEs with agents, so an
        // empty fleet implies no tokens - skipping the call avoids a wasted
        // round-trip on first-page load for new tenants.
        verify(agentClient, never()).getActiveAgentWebhookTokens(any());
        verify(runRepository, never()).findProductionRunsBatch(anyList());
    }
}
