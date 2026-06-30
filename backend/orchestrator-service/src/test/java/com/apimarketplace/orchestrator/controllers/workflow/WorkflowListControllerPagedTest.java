package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowSummary;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowBoardService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Server-paged listing of {@code GET /api/workflows}: the controller applies the visibility filter +
 * sort over the WHOLE tenant set (not just one page) and inlines each row's publication status, so
 * the browser loads a single page instead of looping every page. Pins:
 *  - sort = name (A->Z), lastModified (updatedAt desc, nulls last), lastExecuted, runCount (batch
 *    count desc) - matching the frontend listSort.processList order;
 *  - visibility = public keeps only shared (ACTIVE) workflows, private keeps the rest, and the
 *    publication status is fetched ONCE (reused for the page badges).
 */
@DisplayName("WorkflowListController.listWorkflows - server-paged sort + visibility")
class WorkflowListControllerPagedTest {

    private static final String TENANT = "tenant-1";

    private WorkflowRepository workflowRepository;
    private WorkflowRunRepository workflowRunRepository;
    private PublicationClient publicationClient;
    private WorkflowManagementService workflowService;
    private WorkflowListController controller;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        workflowRunRepository = mock(WorkflowRunRepository.class);
        publicationClient = mock(PublicationClient.class);
        workflowService = mock(WorkflowManagementService.class);
        controller = new WorkflowListController(
                workflowRepository,
                workflowRunRepository,
                mock(SignalWaitRepository.class),
                mock(TriggerClient.class),
                publicationClient,
                workflowService,
                mock(WorkflowBoardService.class),
                mock(OrgAccessGuard.class));
        // Page badges are always batched; default to "nothing shared" so the non-visibility paths
        // never NPE on a null map. Specific tests override this with explicit statuses.
        lenient().when(publicationClient.findPublicationStatusesByWorkflowIds(any(), any()))
                .thenReturn(Map.of());
    }

    private WorkflowEntity wf(String name, Instant updatedAt) {
        WorkflowEntity e = new WorkflowEntity(TENANT, name, TENANT);
        e.setId(UUID.randomUUID());
        e.setUpdatedAt(updatedAt);
        return e;
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowSummary> listWith(String sort, String visibility) {
        ResponseEntity<Map<String, Object>> resp = controller.listWorkflows(
                TENANT, null, null, null, null, 25, 0, null, sort, visibility);
        return (List<WorkflowSummary>) resp.getBody().get("workflows");
    }

    private List<String> names(List<WorkflowSummary> rows) {
        return rows.stream().map(WorkflowSummary::name).toList();
    }

    @Test
    @DisplayName("sort=name orders the page case-insensitively A->Z")
    void sortsByName() {
        WorkflowEntity zed = wf("Zed", Instant.parse("2026-06-18T00:00:00Z"));
        WorkflowEntity ann = wf("ann", Instant.parse("2026-06-10T00:00:00Z"));
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(zed, ann));

        assertThat(names(listWith("name", null))).containsExactly("ann", "Zed");
    }

    @Test
    @DisplayName("default sort is lastModified (updatedAt) most-recent first")
    void sortsByLastModifiedByDefault() {
        WorkflowEntity older = wf("A", Instant.parse("2026-06-10T00:00:00Z"));
        WorkflowEntity newer = wf("B", Instant.parse("2026-06-18T00:00:00Z"));
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(older, newer));

        assertThat(names(listWith(null, null))).containsExactly("B", "A");
    }

    @Test
    @DisplayName("lastModified sorts workflows with a null updatedAt LAST (not first)")
    void lastModifiedNullsSortLast() {
        WorkflowEntity dated = wf("Dated", Instant.parse("2026-06-10T00:00:00Z"));
        WorkflowEntity undated = wf("Undated", null);
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(undated, dated));

        // nullsLast(reverseOrder()): the dated workflow leads, the null-updatedAt one trails.
        assertThat(names(listWith("lastModified", null))).containsExactly("Dated", "Undated");
    }

    @Test
    @DisplayName("visibility=public keeps only shared (ACTIVE) workflows, with ONE batched status call")
    void visibilityPublicKeepsOnlyShared() {
        WorkflowEntity shared = wf("Shared", Instant.parse("2026-06-01T00:00:00Z"));
        WorkflowEntity priv = wf("Private", Instant.parse("2026-06-01T00:00:00Z"));
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(shared, priv));
        when(publicationClient.findPublicationStatusesByWorkflowIds(any(), eq(TENANT)))
                .thenReturn(Map.of(shared.getId(), "ACTIVE"));

        List<WorkflowSummary> rows = listWith(null, "public");

        assertThat(names(rows)).containsExactly("Shared");
        // Exactly ONE batched status call: the page badges reuse the visibility-filter statuses
        // instead of re-fetching - the central efficiency guarantee of this change.
        verify(publicationClient, times(1)).findPublicationStatusesByWorkflowIds(any(), eq(TENANT));
    }

    @Test
    @DisplayName("visibility=private keeps only the non-shared workflows")
    void visibilityPrivateKeepsOnlyRest() {
        WorkflowEntity shared = wf("Shared", Instant.parse("2026-06-01T00:00:00Z"));
        WorkflowEntity priv = wf("Private", Instant.parse("2026-06-01T00:00:00Z"));
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(shared, priv));
        when(publicationClient.findPublicationStatusesByWorkflowIds(any(), eq(TENANT)))
                .thenReturn(Map.of(shared.getId(), "ACTIVE"));

        assertThat(names(listWith(null, "private"))).containsExactly("Private");
    }

    @Test
    @DisplayName("sort=lastExecuted orders by lastExecutedAt desc, with never-executed (null) workflows LAST")
    void sortsByLastExecutedNullsLast() {
        WorkflowEntity recent = wf("Recent", Instant.parse("2026-06-01T00:00:00Z"));
        recent.setLastExecutedAt(Instant.parse("2026-06-20T00:00:00Z"));
        WorkflowEntity older = wf("Older", Instant.parse("2026-06-01T00:00:00Z"));
        older.setLastExecutedAt(Instant.parse("2026-06-12T00:00:00Z"));
        WorkflowEntity never = wf("Never", Instant.parse("2026-06-01T00:00:00Z")); // never executed
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(older, never, recent));

        // Most-recently-executed first; the never-executed workflow trails (nulls last), matching
        // the frontend compareDateDesc semantics.
        assertThat(names(listWith("lastExecuted", null))).containsExactly("Recent", "Older", "Never");
    }

    @Test
    @DisplayName("sort=runCount orders by the batch run-count, most runs first")
    void sortsByRunCountDesc() {
        WorkflowEntity few = wf("Few", Instant.parse("2026-06-10T00:00:00Z"));
        WorkflowEntity many = wf("Many", Instant.parse("2026-06-10T00:00:00Z"));
        when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(few, many));
        when(workflowRunRepository.countByWorkflowIds(any())).thenReturn(List.<Object[]>of(
                new Object[]{few.getId(), 3L},
                new Object[]{many.getId(), 9L}));

        // Many (9 runs) leads Few (3 runs).
        assertThat(names(listWith("runCount", null))).containsExactly("Many", "Few");
    }
}
