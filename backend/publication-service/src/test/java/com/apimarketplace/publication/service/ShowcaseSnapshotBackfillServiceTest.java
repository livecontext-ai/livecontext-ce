package com.apimarketplace.publication.service;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShowcaseSnapshotBackfillService}.
 */
@ExtendWith(MockitoExtension.class)
class ShowcaseSnapshotBackfillServiceTest {

    @Mock
    private WorkflowPublicationRepository publicationRepository;

    @Mock
    private OrchestratorInternalClient orchestratorClient;

    @Mock
    private EntityManager entityManager;

    private ShowcaseSnapshotBackfillService service;

    @BeforeEach
    void setUp() {
        service = new ShowcaseSnapshotBackfillService(publicationRepository, orchestratorClient);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    private WorkflowPublicationEntity buildPub(String runId, Map<String, Object> existingSnapshot) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        pub.setPublisherId("pub-" + UUID.randomUUID());
        pub.setShowcaseRunId(runId);
        pub.setShowcaseSnapshot(existingSnapshot);
        return pub;
    }

    @Nested
    @DisplayName("backfillAll pagination")
    class BackfillAllPagination {

        @Test
        @DisplayName("Walks every page, advancing the pageable until hasNext() is false, and flushes/clears per batch")
        void advancesThroughMultiplePages() {
            // Two eligible pubs across two pages (page size is 50 in the service).
            WorkflowPublicationEntity pubPage0 = buildPub("showcase_run_a", null);
            WorkflowPublicationEntity pubPage1 = buildPub("showcase_run_b", null);

            Pageable firstPage = PageRequest.of(0, 50);
            Pageable secondPage = PageRequest.of(1, 50);

            // total=51 with size 50 => page 0 hasNext()=true, page 1 hasNext()=false.
            Page<WorkflowPublicationEntity> page0 = new PageImpl<>(List.of(pubPage0), firstPage, 51);
            Page<WorkflowPublicationEntity> page1 = new PageImpl<>(List.of(pubPage1), secondPage, 51);

            when(publicationRepository.findAll(firstPage)).thenReturn(page0);
            when(publicationRepository.findAll(secondPage)).thenReturn(page1);
            when(orchestratorClient.captureShowcaseSnapshot(any(), any()))
                    .thenReturn(Map.of("state", "done"));

            List<Map<String, Object>> results = service.backfillAll();

            // Both pages were queried: the loop advanced to page 1 and then stopped.
            verify(publicationRepository).findAll(firstPage);
            verify(publicationRepository).findAll(secondPage);
            // One eligible pub processed per page => two result rows.
            assertThat(results).hasSize(2);
            // flush + clear ran once per batch (two batches).
            verify(entityManager, times(2)).flush();
            verify(entityManager, times(2)).clear();
        }

        @Test
        @DisplayName("Skips pubs that already carry a snapshot or have a null/empty showcase run id")
        void filtersIneligiblePubs() {
            WorkflowPublicationEntity alreadyHasSnapshot = buildPub("showcase_run_x", Map.of("k", "v"));
            WorkflowPublicationEntity nullRunId = buildPub(null, null);
            WorkflowPublicationEntity emptyRunId = buildPub("", null);
            WorkflowPublicationEntity eligible = buildPub("showcase_run_ok", null);

            Pageable firstPage = PageRequest.of(0, 50);
            Page<WorkflowPublicationEntity> onlyPage = new PageImpl<>(
                    List.of(alreadyHasSnapshot, nullRunId, emptyRunId, eligible), firstPage, 4);

            when(publicationRepository.findAll(firstPage)).thenReturn(onlyPage);
            when(orchestratorClient.captureShowcaseSnapshot(any(), any()))
                    .thenReturn(Map.of("state", "done"));

            List<Map<String, Object>> results = service.backfillAll();

            // Only the single eligible pub reaches backfillOne / the orchestrator.
            assertThat(results).hasSize(1);
            verify(orchestratorClient, times(1)).captureShowcaseSnapshot(eq("showcase_run_ok"), any());
            verify(orchestratorClient, never()).captureShowcaseSnapshot(eq("showcase_run_x"), any());
            // Single batch => flush + clear exactly once, then the loop stops (hasNext()=false).
            verify(entityManager, times(1)).flush();
            verify(entityManager, times(1)).clear();
            verify(publicationRepository).findAll(firstPage);
        }
    }

    @Nested
    @DisplayName("backfillOne")
    class BackfillOne {

        @Test
        @DisplayName("Returns status=skipped_empty and saves nothing when the captured snapshot is null")
        void skipsWhenSnapshotNull() {
            WorkflowPublicationEntity pub = buildPub("showcase_run_null", null);
            when(orchestratorClient.captureShowcaseSnapshot(eq("showcase_run_null"), eq(pub.getPublisherId())))
                    .thenReturn(null);

            Map<String, Object> result = service.backfillOne(pub);

            assertThat(result).containsEntry("status", "skipped_empty");
            assertThat(result).containsEntry("oldRunId", "showcase_run_null");
            // No snapshot stored, no save, no clone deletion on the skip path.
            assertThat(pub.getShowcaseSnapshot()).isNull();
            verify(publicationRepository, never()).save(any());
            verify(orchestratorClient, never()).deleteClonedRun(any());
        }

        @Test
        @DisplayName("Returns status=skipped_empty when the captured snapshot is an empty map")
        void skipsWhenSnapshotEmpty() {
            WorkflowPublicationEntity pub = buildPub("showcase_run_empty", null);
            when(orchestratorClient.captureShowcaseSnapshot(eq("showcase_run_empty"), eq(pub.getPublisherId())))
                    .thenReturn(Map.of());

            Map<String, Object> result = service.backfillOne(pub);

            assertThat(result).containsEntry("status", "skipped_empty");
            assertThat(pub.getShowcaseSnapshot()).isNull();
            verify(publicationRepository, never()).save(any());
            verify(orchestratorClient, never()).deleteClonedRun(any());
        }

        @Test
        @DisplayName("Captures snapshot, persists it, and deletes the legacy showcase_ clone on the happy path")
        void capturesAndDeletesClone() {
            WorkflowPublicationEntity pub = buildPub("showcase_run_ok", null);
            Map<String, Object> snapshot = Map.of("state", "done");
            when(orchestratorClient.captureShowcaseSnapshot(eq("showcase_run_ok"), eq(pub.getPublisherId())))
                    .thenReturn(snapshot);

            Map<String, Object> result = service.backfillOne(pub);

            assertThat(result).containsEntry("status", "captured");
            assertThat(result).containsEntry("clonedRunDeleted", true);
            assertThat(pub.getShowcaseSnapshot()).isEqualTo(snapshot);
            assertThat(pub.getShowcaseSnapshotCapturedAt()).isNotNull();
            verify(publicationRepository).save(pub);
            verify(orchestratorClient).deleteClonedRun("showcase_run_ok");
        }

        @Test
        @DisplayName("Captures snapshot but does NOT delete a run id lacking the showcase_ prefix")
        void capturesWithoutDeletingNonShowcaseRun() {
            WorkflowPublicationEntity pub = buildPub("legacy_run_1", null);
            when(orchestratorClient.captureShowcaseSnapshot(eq("legacy_run_1"), eq(pub.getPublisherId())))
                    .thenReturn(Map.of("state", "done"));

            Map<String, Object> result = service.backfillOne(pub);

            assertThat(result).containsEntry("status", "captured");
            assertThat(result).doesNotContainKey("clonedRunDeleted");
            verify(publicationRepository).save(pub);
            verify(orchestratorClient, never()).deleteClonedRun(any());
        }

        @Test
        @DisplayName("Keeps status=captured when clone deletion throws (best-effort) and flags clonedRunDeleted=false")
        void cloneDeletionFailureIsBestEffort() {
            WorkflowPublicationEntity pub = buildPub("showcase_run_fail", null);
            when(orchestratorClient.captureShowcaseSnapshot(eq("showcase_run_fail"), eq(pub.getPublisherId())))
                    .thenReturn(Map.of("state", "done"));
            org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                    .when(orchestratorClient).deleteClonedRun("showcase_run_fail");

            Map<String, Object> result = service.backfillOne(pub);

            // Snapshot stays stored; the clone-delete failure does not invalidate it.
            assertThat(result).containsEntry("status", "captured");
            assertThat(result).containsEntry("clonedRunDeleted", false);
            verify(publicationRepository).save(pub);
        }

        @Test
        @DisplayName("Returns status=error with the message when snapshot capture throws")
        void capturesErrorWhenCaptureThrows() {
            WorkflowPublicationEntity pub = buildPub("showcase_run_err", null);
            when(orchestratorClient.captureShowcaseSnapshot(eq("showcase_run_err"), eq(pub.getPublisherId())))
                    .thenThrow(new RuntimeException("upstream exploded"));

            Map<String, Object> result = service.backfillOne(pub);

            assertThat(result).containsEntry("status", "error");
            assertThat(result).containsEntry("error", "upstream exploded");
            // Capture failed => nothing persisted and no clone touched.
            verify(publicationRepository, never()).save(any());
            verifyNoInteractions(entityManager);
        }
    }
}
