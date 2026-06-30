package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Pins the call-site contract of {@code AgentPublicationService.cloneWorkflowSnapshot}:
 * agent-publication workflow clones must be created as standard WORKFLOW rows
 * ({@link SnapshotCloneService#CLONE_TYPE_WORKFLOW}). An AGENT publication has no
 * application root; stamping its workflows APPLICATION used to collide on the V268
 * unique index as soon as the snapshot carried 2+ workflows (or one workflow whose
 * plan nests a sub-workflow). The stamp tests on {@link SnapshotCloneService} cover
 * the overload itself - this test covers the caller, so reverting the call site to
 * the APPLICATION-default overload turns the suite red.
 */
@DisplayName("AgentPublicationService - workflow clones are stamped WORKFLOW (V268 invariant)")
class AgentPublicationServiceWorkflowTypeTest {

    private SnapshotCloneService snapshotCloneService;
    private AgentPublicationService service;
    private Method cloneWorkflowSnapshot;

    @BeforeEach
    void setUp() throws Exception {
        snapshotCloneService = mock(SnapshotCloneService.class);
        service = new AgentPublicationService(
                mock(WorkflowPublicationRepository.class),
                mock(PublicationReceiptRepository.class),
                mock(AgentClient.class),
                mock(InterfaceClient.class),
                mock(DataSourceClient.class),
                mock(OrchestratorInternalClient.class),
                mock(StorageBreakdownService.class),
                snapshotCloneService,
                new ObjectMapper(),
                mock(WorkflowPublicationService.class),
                mock(EntitlementGuard.class),
                mock(DataSourceFileCloneService.class),
                mock(LandingInterfaceSnapshotter.class),
                mock(AuthClient.class));
        cloneWorkflowSnapshot = AgentPublicationService.class.getDeclaredMethod(
                "cloneWorkflowSnapshot", Map.class, String.class, UUID.class, Map.class, String.class);
        cloneWorkflowSnapshot.setAccessible(true);
    }

    @Test
    @DisplayName("org-scoped agent workflow clone delegates with CLONE_TYPE_WORKFLOW (pre-fix: APPLICATION default → V268 duplicate key)")
    void orgScopedCloneIsStampedWorkflow() throws Exception {
        UUID publicationId = UUID.randomUUID();
        Map<String, Object> plan = new HashMap<>(Map.of("cores", java.util.List.of()));
        Map<String, Object> workflowData = new HashMap<>(Map.of("name", "Agent helper workflow"));

        cloneWorkflowSnapshot.invoke(service, plan, "buyer", publicationId, workflowData, "org-1");

        verify(snapshotCloneService).cloneFromSnapshot(
                any(), eq("buyer"), eq(publicationId), eq("Agent helper workflow"), isNull(), isNull(),
                eq("org-1"), eq(SnapshotCloneService.CLONE_TYPE_WORKFLOW));
    }

    @Test
    @DisplayName("personal-scope agent workflow clone also delegates with CLONE_TYPE_WORKFLOW")
    void personalScopeCloneIsStampedWorkflow() throws Exception {
        UUID publicationId = UUID.randomUUID();
        Map<String, Object> plan = new HashMap<>(Map.of("cores", java.util.List.of()));
        Map<String, Object> workflowData = new HashMap<>(Map.of("name", "Agent helper workflow"));

        cloneWorkflowSnapshot.invoke(service, plan, "buyer", publicationId, workflowData, null);

        verify(snapshotCloneService).cloneFromSnapshot(
                any(), eq("buyer"), eq(publicationId), eq("Agent helper workflow"), isNull(), isNull(),
                isNull(), eq(SnapshotCloneService.CLONE_TYPE_WORKFLOW));
    }
}
