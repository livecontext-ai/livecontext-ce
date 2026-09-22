package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseSnapshotBackfillService;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@code nodeTypes} on the internal summary map.
 *
 * <p>This projection is what the agent-facing {@code application} tool reads
 * through {@code PublicationClient}. Its {@code node_types} filter has nothing
 * else to match on in the personal-scope branch, so an omission here does not
 * surface as an error - the tool answers success with "you have 0 applications"
 * for every filtered call. That is exactly what happened before this test
 * existed, which is why the key is pinned rather than assumed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationController - node types in the summary map")
class InternalPublicationControllerNodeTypesTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private ShowcaseSnapshotBackfillService backfillService;

    private InternalPublicationController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        controller = new InternalPublicationController(
                publicationRepository, publicationService, agentPublicationService,
                resourcePublicationService, orchestratorClient, backfillService,
                org.mockito.Mockito.mock(com.apimarketplace.publication.service.ShowcaseFileNamespaceRepairService.class),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    private WorkflowPublicationEntity pub(Map<String, Object> planSnapshot) {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity(
                UUID.randomUUID(), "An App", Map.of(), "user-42");
        p.setId(UUID.randomUUID());
        p.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PUBLIC);
        p.setStatus(WorkflowPublicationEntity.PublicationStatus.ACTIVE);
        p.setPlanSnapshot(planSnapshot);
        return p;
    }

    private Map<String, Object> summaryOf(WorkflowPublicationEntity publication) {
        UUID projectId = UUID.randomUUID();
        when(publicationRepository.findByProjectId(projectId)).thenReturn(List.of(publication));
        ResponseEntity<List<Map<String, Object>>> response =
                controller.findByProjectId(projectId, null, null);
        return response.getBody().get(0);
    }

    @Test
    @DisplayName("carries the publication's node types, derived from its plan snapshot")
    void carriesNodeTypes() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("mcps", List.of(Map.of("id", "gmail/send")));
        plan.put("cores", List.of(Map.of("type", "loop")));

        assertThat(summaryOf(pub(plan)))
                .containsEntry("nodeTypes", List.of("core:loop", "mcp:gmail"));
    }

    @Test
    @DisplayName("emits an EMPTY list rather than omitting the key, so 'no nodes' is distinguishable from 'not reported'")
    void emitsEmptyListRatherThanOmittingTheKey() {
        // Unlike ceExclusive, absence here cannot mean anything useful: a filter
        // reading a missing key and a filter reading an empty list behave the
        // same way (no match), so the only difference is whether a reader can
        // tell the row was considered at all.
        assertThat(summaryOf(pub(Map.of())))
                .containsEntry("nodeTypes", List.of());
    }
}
