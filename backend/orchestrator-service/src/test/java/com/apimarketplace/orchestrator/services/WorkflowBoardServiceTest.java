package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.controllers.dto.WorkflowBoardCard;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowBoardResponse;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBoardService")
class WorkflowBoardServiceTest {

    @Mock
    private WorkflowManagementService workflowService;
    @Mock
    private WorkflowRunRepository runRepository;
    @Mock
    private SignalWaitRepository signalWaitRepository;
    /**
     * Parallel-agent change (2026-05): WorkflowBoardService now reads epoch
     * counts via WorkflowEpochRepository.getEpochCountByRunIds(...). Without
     * this @Mock, @InjectMocks leaves epochRepository null and every BuildBoard /
     * LoadColumn test NPEs.
     */
    @Mock
    private com.apimarketplace.orchestrator.repository.WorkflowEpochRepository epochRepository;
    /**
     * Applications board only - the service excludes APPLICATION rows whose source publication
     * is INACTIVE (unpublished) via this client. Unstubbed it returns an empty map (Mockito
     * default for a Map return), so apps with no/unknown publication are kept (fail-open).
     */
    @Mock
    private PublicationClient publicationClient;
    /**
     * Applications board only - drops apps the member is deny-listed from under the "application"
     * type (source publication id). Unstubbed it returns an empty set (Mockito default for a
     * Collection return), so no app is dropped and the existing board tests are unaffected.
     */
    @Mock
    private com.apimarketplace.auth.client.access.OrgAccessGuard orgAccessGuard;

    @InjectMocks
    private WorkflowBoardService boardService;

    private static final String TENANT = "test-tenant";

    @Nested
    @DisplayName("classifyPinnedWorkflow")
    class ClassifyPinnedWorkflow {

        @Test
        @DisplayName("null run → production")
        void nullRun() {
            assertThat(boardService.classifyPinnedWorkflow(null, Set.of()))
                    .isEqualTo("production");
        }

        @Test
        @DisplayName("run with pending approval → needsReview (highest priority)")
        void pendingApproval() {
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER);
            assertThat(boardService.classifyPinnedWorkflow(run, Set.of("run-1")))
                    .isEqualTo("needsReview");
        }

        @Test
        @DisplayName("CANCELLED run with pending approval → needsReview (approval wins)")
        void cancelledWithApproval() {
            WorkflowRunEntity run = mockRun("run-1", RunStatus.CANCELLED);
            assertThat(boardService.classifyPinnedWorkflow(run, Set.of("run-1")))
                    .isEqualTo("needsReview");
        }

        @Test
        @DisplayName("CANCELLED run without approval → paused")
        void cancelledNoApproval() {
            WorkflowRunEntity run = mockRun("run-1", RunStatus.CANCELLED);
            assertThat(boardService.classifyPinnedWorkflow(run, Set.of()))
                    .isEqualTo("paused");
        }

        @Test
        @DisplayName("WAITING_TRIGGER run → production")
        void waitingTrigger() {
            WorkflowRunEntity run = mockRun("run-1", RunStatus.WAITING_TRIGGER);
            assertThat(boardService.classifyPinnedWorkflow(run, Set.of()))
                    .isEqualTo("production");
        }

        @Test
        @DisplayName("RUNNING run → production")
        void running() {
            WorkflowRunEntity run = mockRun("run-1", RunStatus.RUNNING);
            assertThat(boardService.classifyPinnedWorkflow(run, Set.of()))
                    .isEqualTo("production");
        }

        @Test
        @DisplayName("COMPLETED run → production")
        void completed() {
            WorkflowRunEntity run = mockRun("run-1", RunStatus.COMPLETED);
            assertThat(boardService.classifyPinnedWorkflow(run, Set.of()))
                    .isEqualTo("production");
        }

        @Test
        @DisplayName("FAILED run → production")
        void failed() {
            WorkflowRunEntity run = mockRun("run-1", RunStatus.FAILED);
            assertThat(boardService.classifyPinnedWorkflow(run, Set.of()))
                    .isEqualTo("production");
        }

        @Test
        @DisplayName("TIMEOUT run → production")
        void timeout() {
            WorkflowRunEntity run = mockRun("run-1", RunStatus.TIMEOUT);
            assertThat(boardService.classifyPinnedWorkflow(run, Set.of()))
                    .isEqualTo("production");
        }
    }

    @Nested
    @DisplayName("buildBoard")
    class BuildBoard {

        @Test
        @DisplayName("empty workflow list → all columns empty")
        void emptyBoard() {
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null);

            assertThat(response.totalCount()).isZero();
            assertThat(response.columns().get("draft")).isEmpty();
            assertThat(response.columns().get("production")).isEmpty();
            assertThat(response.columns().get("needsReview")).isEmpty();
            assertThat(response.columns().get("paused")).isEmpty();
        }

        @Test
        @DisplayName("draft workflows go to draft column")
        void draftWorkflows() {
            WorkflowEntity draft = mockWorkflow("w1", null);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(draft));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null);

            assertThat(response.columns().get("draft")).hasSize(1);
            assertThat(response.columns().get("draft").get(0).workflowId()).isEqualTo(draft.getId());
            assertThat(response.columns().get("production")).isEmpty();
        }

        @Test
        @DisplayName("pinned workflow with WAITING_TRIGGER run → production")
        void pinnedProduction() {
            WorkflowEntity wf = mockWorkflow("w1", 3);
            WorkflowRunEntity run = mockRunWithWorkflow(wf, "run-1", RunStatus.WAITING_TRIGGER);

            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{wf.getId(), 5L}));
            when(runRepository.findProductionRunsBatch(anyCollection())).thenReturn(List.of(run));
            when(signalWaitRepository.findRunIdsWithPendingApprovals(anyCollection())).thenReturn(List.of());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null);

            assertThat(response.columns().get("production")).hasSize(1);
            WorkflowBoardCard card = response.columns().get("production").get(0);
            assertThat(card.productionRunId()).isEqualTo("run-1");
            assertThat(card.productionRunStatus()).isEqualTo("WAITING_TRIGGER");
            assertThat(card.runCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("pinned workflow with CANCELLED run → paused")
        void pinnedPaused() {
            WorkflowEntity wf = mockWorkflow("w1", 2);
            WorkflowRunEntity run = mockRunWithWorkflow(wf, "run-1", RunStatus.CANCELLED);

            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(runRepository.findProductionRunsBatch(anyCollection())).thenReturn(List.of(run));
            when(signalWaitRepository.findRunIdsWithPendingApprovals(anyCollection())).thenReturn(List.of());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null);

            assertThat(response.columns().get("paused")).hasSize(1);
        }

        @Test
        @DisplayName("pinned workflow with pending approval → needsReview")
        void pinnedNeedsReview() {
            WorkflowEntity wf = mockWorkflow("w1", 1);
            WorkflowRunEntity run = mockRunWithWorkflow(wf, "run-1", RunStatus.WAITING_TRIGGER);

            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(runRepository.findProductionRunsBatch(anyCollection())).thenReturn(List.of(run));
            when(signalWaitRepository.findRunIdsWithPendingApprovals(anyCollection())).thenReturn(List.of("run-1"));

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null);

            assertThat(response.columns().get("needsReview")).hasSize(1);
        }

        @Test
        @DisplayName("pinned workflow with no production run → production")
        void pinnedNoRun() {
            WorkflowEntity wf = mockWorkflow("w1", 1);

            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(runRepository.findProductionRunsBatch(anyCollection())).thenReturn(List.of());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null);

            assertThat(response.columns().get("production")).hasSize(1);
            WorkflowBoardCard card = response.columns().get("production").get(0);
            assertThat(card.productionRunId()).isNull();
            assertThat(card.productionRunStatus()).isNull();
        }

        @Test
        @DisplayName("mixed board - all columns populated correctly")
        void mixedBoard() {
            WorkflowEntity draft = mockWorkflow("draft", null);
            WorkflowEntity prod = mockWorkflow("prod", 1);
            WorkflowEntity paused = mockWorkflow("paused", 2);
            WorkflowEntity review = mockWorkflow("review", 3);

            WorkflowRunEntity prodRun = mockRunWithWorkflow(prod, "run-prod", RunStatus.WAITING_TRIGGER);
            WorkflowRunEntity pausedRun = mockRunWithWorkflow(paused, "run-paused", RunStatus.CANCELLED);
            WorkflowRunEntity reviewRun = mockRunWithWorkflow(review, "run-review", RunStatus.RUNNING);

            when(workflowService.listWorkflows(TENANT, null, null))
                    .thenReturn(List.of(draft, prod, paused, review));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(runRepository.findProductionRunsBatch(anyCollection()))
                    .thenReturn(List.of(prodRun, pausedRun, reviewRun));
            when(signalWaitRepository.findRunIdsWithPendingApprovals(anyCollection()))
                    .thenReturn(List.of("run-review"));

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null);

            assertThat(response.totalCount()).isEqualTo(4);
            assertThat(response.columns().get("draft")).hasSize(1);
            assertThat(response.columns().get("production")).hasSize(1);
            assertThat(response.columns().get("paused")).hasSize(1);
            assertThat(response.columns().get("needsReview")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("buildBoard pagination")
    class BuildBoardPagination {

        @Test
        @DisplayName("page 0 with size 2 → returns first 2 cards, totalCount reflects all")
        void firstPage() {
            List<WorkflowEntity> all = List.of(
                    mockWorkflow("w1", null),
                    mockWorkflow("w2", null),
                    mockWorkflow("w3", null),
                    mockWorkflow("w4", null),
                    mockWorkflow("w5", null)
            );
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(all);
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 2);

            assertThat(response.totalCount()).isEqualTo(5);
            assertThat(response.page()).isZero();
            assertThat(response.size()).isEqualTo(2);
            assertThat(response.columns().get("draft")).hasSize(2);
        }

        @Test
        @DisplayName("page 2 with size 2 → returns last (5th) card only")
        void lastPagePartial() {
            List<WorkflowEntity> all = List.of(
                    mockWorkflow("w1", null),
                    mockWorkflow("w2", null),
                    mockWorkflow("w3", null),
                    mockWorkflow("w4", null),
                    mockWorkflow("w5", null)
            );
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(all);
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 2, 2);

            assertThat(response.totalCount()).isEqualTo(5);
            assertThat(response.page()).isEqualTo(2);
            assertThat(response.columns().get("draft")).hasSize(1);
        }

        @Test
        @DisplayName("page beyond range → empty cards but correct totalCount")
        void pageBeyondRange() {
            List<WorkflowEntity> all = List.of(
                    mockWorkflow("w1", null),
                    mockWorkflow("w2", null)
            );
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(all);

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 10, 5);

            assertThat(response.totalCount()).isEqualTo(2);
            assertThat(response.columns().get("draft")).isEmpty();
        }

        @Test
        @DisplayName("size capped at 100 (server-side guard)")
        void sizeCappedAt100() {
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 5_000);

            assertThat(response.size()).isEqualTo(100);
        }

        @Test
        @DisplayName("negative page clamped to 0")
        void negativePageClamped() {
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, -3, 25);

            assertThat(response.page()).isZero();
        }
    }

    @Nested
    @DisplayName("loadColumn (per-column lazy loading)")
    class LoadColumn {

        @Test
        @DisplayName("draft column page 0 returns first slice + total")
        void draftFirstSlice() {
            List<WorkflowEntity> all = List.of(
                    mockWorkflow("d1", null),
                    mockWorkflow("d2", null),
                    mockWorkflow("d3", null),
                    mockWorkflow("d4", null)
            );
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(all);
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardService.WorkflowBoardColumnPage result =
                    boardService.loadColumn(TENANT, null, null, "draft", 0, 2);

            assertThat(result.column()).isEqualTo("draft");
            assertThat(result.totalCount()).isEqualTo(4);
            assertThat(result.items()).hasSize(2);
        }

        @Test
        @DisplayName("draft column page 1 returns next slice")
        void draftSecondSlice() {
            List<WorkflowEntity> all = List.of(
                    mockWorkflow("d1", null),
                    mockWorkflow("d2", null),
                    mockWorkflow("d3", null)
            );
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(all);
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardService.WorkflowBoardColumnPage result =
                    boardService.loadColumn(TENANT, null, null, "draft", 1, 2);

            assertThat(result.totalCount()).isEqualTo(3);
            assertThat(result.items()).hasSize(1);
        }

        @Test
        @DisplayName("production column only includes pinned + production-classified workflows")
        void productionColumn() {
            WorkflowEntity draft = mockWorkflow("draft", null);
            WorkflowEntity prod1 = mockWorkflow("prod1", 1);
            WorkflowEntity prod2 = mockWorkflow("prod2", 1);
            WorkflowEntity paused = mockWorkflow("paused", 2);

            WorkflowRunEntity prodRun1 = mockRunWithWorkflow(prod1, "run-prod1", RunStatus.WAITING_TRIGGER);
            WorkflowRunEntity prodRun2 = mockRunWithWorkflow(prod2, "run-prod2", RunStatus.RUNNING);
            WorkflowRunEntity pausedRun = mockRunWithWorkflow(paused, "run-paused", RunStatus.CANCELLED);

            when(workflowService.listWorkflows(TENANT, null, null))
                    .thenReturn(List.of(draft, prod1, prod2, paused));
            when(runRepository.findProductionRunsBatch(anyCollection()))
                    .thenReturn(List.of(prodRun1, prodRun2, pausedRun));
            when(signalWaitRepository.findRunIdsWithPendingApprovals(anyCollection())).thenReturn(List.of());
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardService.WorkflowBoardColumnPage result =
                    boardService.loadColumn(TENANT, null, null, "production", 0, 10);

            assertThat(result.totalCount()).isEqualTo(2);
            assertThat(result.items()).hasSize(2);
        }

        @Test
        @DisplayName("invalid column name throws")
        void invalidColumn() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> boardService.loadColumn(TENANT, null, null, "bogus", 0, 10));
        }

        @Test
        @DisplayName("page beyond range returns empty items + correct totalCount")
        void pageBeyondRange() {
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(
                    mockWorkflow("d1", null)
            ));

            WorkflowBoardService.WorkflowBoardColumnPage result =
                    boardService.loadColumn(TENANT, null, null, "draft", 10, 20);

            assertThat(result.totalCount()).isEqualTo(1);
            assertThat(result.items()).isEmpty();
        }
    }

    @Nested
    @DisplayName("applicationsOnly source (applications board)")
    class ApplicationsSource {

        @Test
        @DisplayName("buildBoard(applicationsOnly=true) sources acquired applications AND own published-as-app workflows")
        void buildBoardUsesApplications() {
            WorkflowEntity app = mockWorkflow("app1", null);
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of(app));
            // listWorkflows unstubbed → empty list (no own published apps), so only the acquired row shows.
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(1);
            assertThat(response.columns().get("draft")).hasSize(1);
            // Applications board now reads BOTH lists: acquired (APPLICATION-type) + the user's
            // own workflows, to surface their published-as-application rows here too.
            verify(workflowService).listApplications(TENANT, null, null);
            verify(workflowService).listWorkflows(TENANT, null, null);
        }

        @Test
        @DisplayName("applications classify identically - pinned + pending approval → needsReview")
        void applicationNeedsReview() {
            WorkflowEntity app = mockWorkflow("app1", 4);
            WorkflowRunEntity run = mockRunWithWorkflow(app, "run-app", RunStatus.WAITING_TRIGGER);
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of(app));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(runRepository.findProductionRunsBatch(anyCollection())).thenReturn(List.of(run));
            when(signalWaitRepository.findRunIdsWithPendingApprovals(anyCollection())).thenReturn(List.of("run-app"));

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 25, true);

            assertThat(response.columns().get("needsReview")).hasSize(1);
            assertThat(response.columns().get("production")).isEmpty();
        }

        @Test
        @DisplayName("applications board - all 4 columns classify identically to workflows")
        void applicationsMixedBoard() {
            WorkflowEntity draft = mockWorkflow("a-draft", null);
            WorkflowEntity prod = mockWorkflow("a-prod", 1);
            WorkflowEntity paused = mockWorkflow("a-paused", 2);
            WorkflowEntity review = mockWorkflow("a-review", 3);

            WorkflowRunEntity prodRun = mockRunWithWorkflow(prod, "run-prod", RunStatus.WAITING_TRIGGER);
            WorkflowRunEntity pausedRun = mockRunWithWorkflow(paused, "run-paused", RunStatus.CANCELLED);
            WorkflowRunEntity reviewRun = mockRunWithWorkflow(review, "run-review", RunStatus.RUNNING);

            when(workflowService.listApplications(TENANT, null, null))
                    .thenReturn(List.of(draft, prod, paused, review));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(runRepository.findProductionRunsBatch(anyCollection()))
                    .thenReturn(List.of(prodRun, pausedRun, reviewRun));
            when(signalWaitRepository.findRunIdsWithPendingApprovals(anyCollection()))
                    .thenReturn(List.of("run-review"));

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(4);
            assertThat(response.columns().get("draft")).hasSize(1);
            assertThat(response.columns().get("production")).hasSize(1);
            assertThat(response.columns().get("paused")).hasSize(1);
            assertThat(response.columns().get("needsReview")).hasSize(1);
        }

        @Test
        @DisplayName("loadColumn(applicationsOnly=true) sources acquired applications AND own published-as-app workflows")
        void loadColumnUsesApplications() {
            WorkflowEntity app1 = mockWorkflow("app1", null);
            WorkflowEntity app2 = mockWorkflow("app2", null);
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of(app1, app2));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardService.WorkflowBoardColumnPage result =
                    boardService.loadColumn(TENANT, null, null, "draft", 0, 10, true);

            assertThat(result.totalCount()).isEqualTo(2);
            assertThat(result.items()).hasSize(2);
            verify(workflowService).listApplications(TENANT, null, null);
            verify(workflowService).listWorkflows(TENANT, null, null);
        }

        @Test
        @DisplayName("default source (applicationsOnly=false) never touches listApplications")
        void defaultSourceExcludesApplications() {
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of());

            boardService.loadColumn(TENANT, null, null, "draft", 0, 10);

            verify(workflowService).listWorkflows(TENANT, null, null);
            verify(workflowService, never()).listApplications(anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("applicationsOnly - INACTIVE publication filter")
    class InactivePublicationFilter {

        private static final UUID PUB_ACTIVE = UUID.nameUUIDFromBytes("pub-active".getBytes());
        private static final UUID PUB_INACTIVE = UUID.nameUUIDFromBytes("pub-inactive".getBytes());

        @Test
        @DisplayName("drops an APPLICATION row whose source publication is INACTIVE")
        void dropsInactive() {
            WorkflowEntity live = mockApplication("live-app", null, PUB_ACTIVE);
            WorkflowEntity unpublished = mockApplication("unpublished-app", null, PUB_INACTIVE);
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of(live, unpublished));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(publicationClient.findStatusesByPublicationIds(anyCollection(), any()))
                    .thenReturn(Map.of(PUB_ACTIVE, "ACTIVE", PUB_INACTIVE, "INACTIVE"));

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(1);
            assertThat(response.columns().get("draft")).hasSize(1);
            assertThat(response.columns().get("draft").get(0).workflowId()).isEqualTo(live.getId());
        }

        @Test
        @DisplayName("keeps ACTIVE / PENDING_REVIEW / REJECTED - only INACTIVE is dropped")
        void keepsNonInactiveStatuses() {
            UUID pubReview = UUID.nameUUIDFromBytes("pub-review".getBytes());
            UUID pubRejected = UUID.nameUUIDFromBytes("pub-rejected".getBytes());
            WorkflowEntity active = mockApplication("active", null, PUB_ACTIVE);
            WorkflowEntity review = mockApplication("review", null, pubReview);
            WorkflowEntity rejected = mockApplication("rejected", null, pubRejected);
            WorkflowEntity inactive = mockApplication("inactive", null, PUB_INACTIVE);
            when(workflowService.listApplications(TENANT, null, null))
                    .thenReturn(List.of(active, review, rejected, inactive));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(publicationClient.findStatusesByPublicationIds(anyCollection(), any()))
                    .thenReturn(Map.of(PUB_ACTIVE, "ACTIVE", pubReview, "PENDING_REVIEW",
                            pubRejected, "REJECTED", PUB_INACTIVE, "INACTIVE"));

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(3);
            assertThat(response.columns().get("draft")).hasSize(3);
        }

        @Test
        @DisplayName("fail-open: empty status map (service down) keeps every app")
        void failOpenOnEmptyMap() {
            WorkflowEntity a = mockApplication("a", null, PUB_ACTIVE);
            WorkflowEntity b = mockApplication("b", null, PUB_INACTIVE);
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of(a, b));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(publicationClient.findStatusesByPublicationIds(anyCollection(), any()))
                    .thenReturn(Collections.emptyMap());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("apps with no source publication are kept and never queried")
        void noSourcePublicationKept() {
            WorkflowEntity legacy = mockWorkflow("legacy-app", null); // sourcePublicationId == null
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of(legacy));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(1);
            verify(publicationClient, never()).findStatusesByPublicationIds(anyCollection(), any());
        }

        @Test
        @DisplayName("loadColumn(applicationsOnly) also drops INACTIVE-publication apps")
        void loadColumnDropsInactive() {
            WorkflowEntity live = mockApplication("live", null, PUB_ACTIVE);
            WorkflowEntity unpublished = mockApplication("unpub", null, PUB_INACTIVE);
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of(live, unpublished));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(publicationClient.findStatusesByPublicationIds(anyCollection(), any()))
                    .thenReturn(Map.of(PUB_ACTIVE, "ACTIVE", PUB_INACTIVE, "INACTIVE"));

            WorkflowBoardService.WorkflowBoardColumnPage result =
                    boardService.loadColumn(TENANT, null, null, "draft", 0, 10, true);

            assertThat(result.totalCount()).isEqualTo(1);
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).workflowId()).isEqualTo(live.getId());
        }

        @Test
        @DisplayName("workflows board never runs the apps INACTIVE filter, but does enrich the published marker")
        void workflowsBoardSkipsFilter() {
            WorkflowEntity wf = mockWorkflow("wf", null);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            boardService.buildBoard(TENANT, null, null, 0, 25, false);

            // The apps-only INACTIVE filter (keyed by publication id) must NOT run for workflows.
            verify(publicationClient, never()).findStatusesByPublicationIds(anyCollection(), any());
            // But the published marker IS enriched (keyed by workflow id), like the /workflows list.
            verify(publicationClient).findPublicationStatusesByWorkflowIds(anyCollection(), any());
        }
    }

    @Nested
    @DisplayName("own published-as-application workflows on the applications board")
    class OwnPublishedApplications {

        private static final UUID OWN_PUB = UUID.nameUUIDFromBytes("own-pub".getBytes());
        private static final UUID OWN_IFACE = UUID.nameUUIDFromBytes("own-iface".getBytes());
        private static final String OWN_RUN = "run_own_showcase";

        @Test
        @DisplayName("a publisher's own published-as-app workflow appears on the applications board with its publication id + showcase ids")
        void includesOwnPublishedApp() {
            WorkflowEntity ownApp = mockWorkflow("own-app", null); // WORKFLOW-type, no sourcePublicationId of its own
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(ownApp));
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of());
            when(publicationClient.findApplicationPublicationsByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(ownApp.getId(),
                            new PublicationClient.ApplicationPublicationRef(OWN_PUB, "ACTIVE", OWN_IFACE, OWN_RUN)));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(1);
            WorkflowBoardCard card = response.columns().get("draft").get(0);
            assertThat(card.workflowId()).isEqualTo(ownApp.getId());
            // The card must open the application surface, so it carries the resolved publication id
            // even though the source WorkflowEntity has none of its own.
            assertThat(card.sourcePublicationId()).isEqualTo(OWN_PUB);
            // Own app carries the publication's showcase ids → the card renders the preview via the
            // authenticated per-run path (valid at any visibility), exactly like /app/applications.
            assertThat(card.showcaseInterfaceId()).isEqualTo(OWN_IFACE);
            assertThat(card.showcaseRunId()).isEqualTo(OWN_RUN);
        }

        @Test
        @DisplayName("an own published app WITHOUT a captured showcase run carries no showcase ids (card keeps the public showcase path)")
        void ownAppWithoutShowcaseRunHasNoShowcaseIds() {
            WorkflowEntity ownApp = mockWorkflow("own-app-no-run", null);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(ownApp));
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of());
            when(publicationClient.findApplicationPublicationsByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(ownApp.getId(),
                            new PublicationClient.ApplicationPublicationRef(OWN_PUB, "ACTIVE", OWN_IFACE, null)));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard card = boardService.buildBoard(TENANT, null, null, 0, 25, true)
                    .columns().get("draft").get(0);
            // Only set when BOTH ids are present - a half-populated ref must not drive the auth'd path.
            assertThat(card.showcaseInterfaceId()).isNull();
            assertThat(card.showcaseRunId()).isNull();
            assertThat(card.sourcePublicationId()).isEqualTo(OWN_PUB);
        }

        @Test
        @DisplayName("an own workflow NOT published as an application stays off the applications board")
        void excludesOwnNonApp() {
            WorkflowEntity plain = mockWorkflow("plain", null);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(plain));
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of());
            when(publicationClient.findApplicationPublicationsByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Collections.emptyMap());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 25, true);

            assertThat(response.totalCount()).isZero();
        }

        @Test
        @DisplayName("a pure ACQUIRED row keeps its publicationId and carries NO showcase ids (public showcase path - no regression)")
        void acquiredRowHasNoShowcaseIds() {
            UUID acquiredPub = UUID.nameUUIDFromBytes("acquired-pub".getBytes());
            WorkflowEntity acquired = mockApplication("acquired", null, acquiredPub);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of());
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of(acquired));
            // listWorkflows is empty → the own-app publication lookup is skipped; only the
            // acquired path's INACTIVE-filter status lookup runs.
            when(publicationClient.findStatusesByPublicationIds(anyCollection(), any()))
                    .thenReturn(Map.of(acquiredPub, "ACTIVE"));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard card = boardService.buildBoard(TENANT, null, null, 0, 25, true)
                    .columns().get("draft").get(0);
            // Acquired row → publication-scoped showcase path: publicationId comes from the entity,
            // and there are NO showcase ids (its run belongs to the publisher → cross-tenant auth'd
            // per-run render would 403). Pins the no-regression negative branch: showcase ids are
            // own-app-only.
            assertThat(card.sourcePublicationId()).isEqualTo(acquiredPub);
            assertThat(card.showcaseInterfaceId()).isNull();
            assertThat(card.showcaseRunId()).isNull();
            // Source publication resolves LOCALLY (present in the status map) → NOT remote: the card
            // reads its showcase through the local (receipt-gated authenticated) render.
            assertThat(card.remote()).isFalse();
        }

        @Test
        @DisplayName("a CLOUD-sourced ACQUIRED app (source publication absent locally) is flagged remote=true")
        void cloudAcquiredRowIsRemote() {
            UUID cloudPub = UUID.nameUUIDFromBytes("cloud-pub".getBytes());
            WorkflowEntity acquired = mockApplication("cloud-acquired", null, cloudPub);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of());
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of(acquired));
            // The cloud source publication lives on the cloud, not the local catalog: the by-id
            // status lookup returns a NON-EMPTY map that does NOT contain it (here, an unrelated
            // local pub) - so the row survives the INACTIVE filter (fail-open) and is flagged remote.
            when(publicationClient.findStatusesByPublicationIds(anyCollection(), any()))
                    .thenReturn(Map.of(UUID.nameUUIDFromBytes("other-local-pub".getBytes()), "ACTIVE"));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard card = boardService.buildBoard(TENANT, null, null, 0, 25, true)
                    .columns().get("draft").get(0);
            // The card still carries the (cloud) publication id, but is marked remote → the frontend
            // routes its showcase render to the cloud proxy instead of the local render (which 404s).
            assertThat(card.sourcePublicationId()).isEqualTo(cloudPub);
            assertThat(card.remote()).isTrue();
            assertThat(card.showcaseInterfaceId()).isNull();
            assertThat(card.showcaseRunId()).isNull();
        }

        @Test
        @DisplayName("dedup - own published app wins over an acquired clone of the same publication")
        void dedupOwnWinsOverAcquiredClone() {
            WorkflowEntity ownApp = mockWorkflow("own", null);
            WorkflowEntity acquiredClone = mockApplication("acquired-clone", null, OWN_PUB);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(ownApp));
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of(acquiredClone));
            when(publicationClient.findApplicationPublicationsByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(ownApp.getId(),
                            new PublicationClient.ApplicationPublicationRef(OWN_PUB, "ACTIVE", null, null)));
            when(publicationClient.findStatusesByPublicationIds(anyCollection(), any()))
                    .thenReturn(Map.of(OWN_PUB, "ACTIVE"));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, null, null, 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(1);
            assertThat(response.columns().get("draft").get(0).workflowId()).isEqualTo(ownApp.getId());
        }

        @Test
        @DisplayName("a published-as-app workflow appears on BOTH boards - builder on workflows, app surface on applications")
        void visibleOnBothBoards() {
            WorkflowEntity wf = mockWorkflow("dual", null);

            // Workflows board: card has no source publication (opens builder) + a published marker.
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            when(publicationClient.findPublicationStatusesByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(wf.getId(), "ACTIVE"));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard wfCard = boardService.buildBoard(TENANT, null, null, 0, 25, false)
                    .columns().get("draft").get(0);
            assertThat(wfCard.sourcePublicationId()).isNull();
            assertThat(wfCard.isPublished()).isTrue();

            // Applications board: the SAME workflow, now carrying the publication id (opens app surface).
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of());
            when(publicationClient.findApplicationPublicationsByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(wf.getId(),
                            new PublicationClient.ApplicationPublicationRef(OWN_PUB, "ACTIVE", null, null)));

            WorkflowBoardCard appCard = boardService.buildBoard(TENANT, null, null, 0, 25, true)
                    .columns().get("draft").get(0);
            assertThat(appCard.sourcePublicationId()).isEqualTo(OWN_PUB);
        }
    }

    @Nested
    @DisplayName("workflows board - published marker enrichment")
    class WorkflowsBoardPublishedMarker {

        @Test
        @DisplayName("ACTIVE publication → isPublished true + publicationStatus ACTIVE")
        void activePublication() {
            WorkflowEntity wf = mockWorkflow("shared", null);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            when(publicationClient.findPublicationStatusesByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(wf.getId(), "ACTIVE"));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard card = boardService.buildBoard(TENANT, null, null, 0, 25, false)
                    .columns().get("draft").get(0);
            assertThat(card.isPublished()).isTrue();
            assertThat(card.publicationStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("PENDING_REVIEW publication → isPublished false + publicationStatus PENDING_REVIEW")
        void pendingReviewPublication() {
            WorkflowEntity wf = mockWorkflow("in-review", null);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            when(publicationClient.findPublicationStatusesByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(wf.getId(), "PENDING_REVIEW"));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard card = boardService.buildBoard(TENANT, null, null, 0, 25, false)
                    .columns().get("draft").get(0);
            assertThat(card.isPublished()).isFalse();
            assertThat(card.publicationStatus()).isEqualTo("PENDING_REVIEW");
        }

        @Test
        @DisplayName("unpublished workflow → isPublished false + publicationStatus null")
        void unpublishedWorkflow() {
            WorkflowEntity wf = mockWorkflow("private", null);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            // findPublicationStatusesByWorkflowIds unstubbed → empty map (workflow not shared).
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard card = boardService.buildBoard(TENANT, null, null, 0, 25, false)
                    .columns().get("draft").get(0);
            assertThat(card.isPublished()).isFalse();
            assertThat(card.publicationStatus()).isNull();
        }
    }

    @Nested
    @DisplayName("visibility enrichment - public / private indicator + filter source")
    class VisibilityEnrichment {

        private static final UUID OWN_PUB = UUID.nameUUIDFromBytes("vis-own-pub".getBytes());

        @Test
        @DisplayName("workflows board - PUBLIC publication → card.visibility = PUBLIC")
        void workflowsBoardPublicVisibility() {
            WorkflowEntity wf = mockWorkflow("vis-public", null);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            when(publicationClient.findPublicationStatusesByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(wf.getId(), "ACTIVE"));
            when(publicationClient.findPublicationVisibilitiesByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(wf.getId(), "PUBLIC"));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard card = boardService.buildBoard(TENANT, null, null, 0, 25, false)
                    .columns().get("draft").get(0);
            assertThat(card.visibility()).isEqualTo("PUBLIC");
        }

        @Test
        @DisplayName("workflows board - PRIVATE publication → card.visibility = PRIVATE")
        void workflowsBoardPrivateVisibility() {
            WorkflowEntity wf = mockWorkflow("vis-private", null);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            when(publicationClient.findPublicationStatusesByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(wf.getId(), "ACTIVE"));
            when(publicationClient.findPublicationVisibilitiesByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(wf.getId(), "PRIVATE"));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard card = boardService.buildBoard(TENANT, null, null, 0, 25, false)
                    .columns().get("draft").get(0);
            assertThat(card.visibility()).isEqualTo("PRIVATE");
        }

        @Test
        @DisplayName("workflows board - unpublished workflow → card.visibility null (no marker / excluded when filtered)")
        void workflowsBoardUnpublishedNoVisibility() {
            WorkflowEntity wf = mockWorkflow("vis-unpublished", null);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(wf));
            // visibilities lookup unstubbed → empty map (Mockito default for a Map return).
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard card = boardService.buildBoard(TENANT, null, null, 0, 25, false)
                    .columns().get("draft").get(0);
            assertThat(card.visibility()).isNull();
        }

        @Test
        @DisplayName("applications board - own published app carries its publication visibility")
        void applicationsBoardOwnAppVisibility() {
            WorkflowEntity ownApp = mockWorkflow("vis-own-app", null);
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of(ownApp));
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of());
            when(publicationClient.findApplicationPublicationsByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(ownApp.getId(),
                            new PublicationClient.ApplicationPublicationRef(OWN_PUB, "ACTIVE", null, null)));
            when(publicationClient.findPublicationVisibilitiesByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(ownApp.getId(), "PRIVATE"));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard card = boardService.buildBoard(TENANT, null, null, 0, 25, true)
                    .columns().get("draft").get(0);
            assertThat(card.visibility()).isEqualTo("PRIVATE");
        }

        @Test
        @DisplayName("applications board - acquired row has NO visibility (publisher's visibility, not the viewer's)")
        void applicationsBoardAcquiredNoVisibility() {
            UUID acquiredPub = UUID.nameUUIDFromBytes("vis-acquired-pub".getBytes());
            WorkflowEntity acquired = mockApplication("vis-acquired", null, acquiredPub);
            // listWorkflows empty → own-app lookup (and its visibilities call) is skipped entirely.
            when(workflowService.listWorkflows(TENANT, null, null)).thenReturn(List.of());
            when(workflowService.listApplications(TENANT, null, null)).thenReturn(List.of(acquired));
            when(publicationClient.findStatusesByPublicationIds(anyCollection(), any()))
                    .thenReturn(Map.of(acquiredPub, "ACTIVE"));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());

            WorkflowBoardCard card = boardService.buildBoard(TENANT, null, null, 0, 25, true)
                    .columns().get("draft").get(0);
            assertThat(card.sourcePublicationId()).isEqualTo(acquiredPub);
            assertThat(card.visibility()).isNull();
        }
    }

    @Nested
    @DisplayName("applications board - org per-member \"application\" deny-list (publication id)")
    class ApplicationDenyListFilter {

        private static final UUID PUB_ALLOWED = UUID.nameUUIDFromBytes("pub-allowed".getBytes());
        private static final UUID PUB_RESTRICTED = UUID.nameUUIDFromBytes("pub-restricted".getBytes());
        private static final UUID OWN_PUB_RESTRICTED = UUID.nameUUIDFromBytes("own-pub-restricted".getBytes());

        @Test
        @DisplayName("drops an ACQUIRED app whose source publication is in the member's deny-list (sourcePublicationId branch)")
        void dropsRestrictedAcquiredApp() {
            WorkflowEntity allowed = mockApplication("allowed-app", null, PUB_ALLOWED);
            WorkflowEntity restricted = mockApplication("restricted-app", null, PUB_RESTRICTED);
            when(workflowService.listApplications(TENANT, "org-1", "MEMBER")).thenReturn(List.of(allowed, restricted));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(orgAccessGuard.getRestrictedResourceIds("org-1", TENANT, "application", "MEMBER"))
                    .thenReturn(Set.of(PUB_RESTRICTED.toString()));

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, "org-1", "MEMBER", 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(1);
            assertThat(response.columns().get("draft")).hasSize(1);
            assertThat(response.columns().get("draft").get(0).workflowId()).isEqualTo(allowed.getId());
        }

        @Test
        @DisplayName("drops an OWN published-as-app workflow whose publication is in the deny-list (publicationIdByWorkflow branch)")
        void dropsRestrictedOwnPublishedApp() {
            WorkflowEntity ownAllowed = mockWorkflow("own-allowed", null);
            WorkflowEntity ownRestricted = mockWorkflow("own-restricted", null);
            when(workflowService.listWorkflows(TENANT, "org-1", "MEMBER"))
                    .thenReturn(List.of(ownAllowed, ownRestricted));
            when(workflowService.listApplications(TENANT, "org-1", "MEMBER")).thenReturn(List.of());
            when(publicationClient.findApplicationPublicationsByWorkflowIds(anyCollection(), any()))
                    .thenReturn(Map.of(
                            ownAllowed.getId(),
                            new PublicationClient.ApplicationPublicationRef(PUB_ALLOWED, "ACTIVE", null, null),
                            ownRestricted.getId(),
                            new PublicationClient.ApplicationPublicationRef(OWN_PUB_RESTRICTED, "ACTIVE", null, null)));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(orgAccessGuard.getRestrictedResourceIds("org-1", TENANT, "application", "MEMBER"))
                    .thenReturn(Set.of(OWN_PUB_RESTRICTED.toString()));

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, "org-1", "MEMBER", 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(1);
            assertThat(response.columns().get("draft").get(0).workflowId()).isEqualTo(ownAllowed.getId());
        }

        @Test
        @DisplayName("a READ-restricted app stays visible - board reads the DENY-only set, never the write-restricted set")
        void readRestrictedAppStaysVisible() {
            // A READ-only restriction is NOT in getRestrictedResourceIds (DENY-only), so the app must
            // survive. Pinning that the board never consults getWriteRestrictedResourceIds guards
            // against a future swap that would wrongly hide READ-restricted apps from the list.
            WorkflowEntity app = mockApplication("readonly-app", null, PUB_ALLOWED);
            when(workflowService.listApplications(TENANT, "org-1", "MEMBER")).thenReturn(List.of(app));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(orgAccessGuard.getRestrictedResourceIds("org-1", TENANT, "application", "MEMBER"))
                    .thenReturn(Set.of());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, "org-1", "MEMBER", 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(1);
            verify(orgAccessGuard, never())
                    .getWriteRestrictedResourceIds(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("empty deny-list keeps every app (no-op - OWNER/ADMIN or no restriction)")
        void emptyDenyListKeepsAll() {
            WorkflowEntity a = mockApplication("a", null, PUB_ALLOWED);
            WorkflowEntity b = mockApplication("b", null, PUB_RESTRICTED);
            when(workflowService.listApplications(TENANT, "org-1", "OWNER")).thenReturn(List.of(a, b));
            when(runRepository.countByWorkflowIds(anyCollection())).thenReturn(Collections.emptyList());
            when(orgAccessGuard.getRestrictedResourceIds("org-1", TENANT, "application", "OWNER"))
                    .thenReturn(Set.of());

            WorkflowBoardResponse response = boardService.buildBoard(TENANT, "org-1", "OWNER", 0, 25, true);

            assertThat(response.totalCount()).isEqualTo(2);
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private static WorkflowRunEntity mockRun(String runIdPublic, RunStatus status) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class, withSettings().lenient());
        when(run.getRunIdPublic()).thenReturn(runIdPublic);
        when(run.getStatus()).thenReturn(status);
        return run;
    }

    private static WorkflowRunEntity mockRunWithWorkflow(WorkflowEntity wf, String runIdPublic, RunStatus status) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getRunIdPublic()).thenReturn(runIdPublic);
        when(run.getStatus()).thenReturn(status);
        when(run.getWorkflow()).thenReturn(wf);
        return run;
    }

    private static WorkflowEntity mockWorkflow(String name, Integer pinnedVersion) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setName(name);
        wf.setPinnedVersion(pinnedVersion);
        // WorkflowEntity.id is generated by JPA; we need a predictable UUID
        try {
            var idField = WorkflowEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(wf, UUID.nameUUIDFromBytes(name.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return wf;
    }

    /** An APPLICATION-type board row carrying a {@code sourcePublicationId} (acquired/published app). */
    private static WorkflowEntity mockApplication(String name, Integer pinnedVersion, UUID sourcePublicationId) {
        WorkflowEntity app = mockWorkflow(name, pinnedVersion);
        app.setSourcePublicationId(sourcePublicationId);
        return app;
    }
}
