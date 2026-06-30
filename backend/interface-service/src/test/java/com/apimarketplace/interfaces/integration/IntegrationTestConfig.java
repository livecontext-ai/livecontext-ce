package com.apimarketplace.interfaces.integration;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.interfaces.client.OrchestratorCascadeClient;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test configuration that mocks beans using native PostgreSQL SQL
 * that won't work with H2.
 */
@TestConfiguration
public class IntegrationTestConfig {

    @Bean
    @Primary
    public StorageBreakdownService storageBreakdownService() {
        return mock(StorageBreakdownService.class);
    }

    @Bean
    @Primary
    public OrgAccessGuard orgAccessService() {
        OrgAccessGuard mock = mock(OrgAccessGuard.class);
        // any() instead of anyString() because tests don't always send X-Org-Role
        // header → orgRole arg is null and anyString() doesn't match null → mock returns
        // default empty list → listInterfaces returns [] (shouldListInterfaces regression).
        when(mock.filterAccessible(anyList(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(mock.getRestrictedResourceIds(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(java.util.Set.of());
        // canAccess gates update/delete/clone in InterfaceService. Default Mockito
        // returns false → 403 on every mutating endpoint. any() instead of anyString()
        // because tests don't always set X-Org-Role → orgRole arg may be null.
        when(mock.canAccess(any(), any(), any(), any(), any()))
                .thenReturn(true);
        when(mock.canWrite(any(), any(), any(), any(), any()))
                .thenReturn(true);
        return mock;
    }

    /**
     * Cascade-scrub callback to orchestrator-service is fire-and-forget from
     * the integration test's perspective - the orchestrator isn't running in
     * the test harness so we mock the client to return a no-op success
     * (workflowsTouched=0). Without this the real client throws when it
     * cannot reach the orchestrator and {@code InterfaceService.deleteInterface}
     * aborts with IllegalStateException (verified CI failure 2026-05-10).
     */
    @Bean
    @Primary
    public OrchestratorCascadeClient orchestratorCascadeClient() {
        OrchestratorCascadeClient mock = mock(OrchestratorCascadeClient.class);
        when(mock.stripInterfaceReferences(anyString(), anyString()))
                .thenReturn(new OrchestratorCascadeClient.CascadeSummary(0, 0, 0));
        return mock;
    }
}
