package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress.Level;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository.VirtualFileFilter;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository.VirtualGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REAL-binding persistence test for the virtual workflow-folder grouping (H2 in PostgreSQL mode).
 *
 * <p>The {@code StorageExplorerRepositoryVirtualSqlTest} asserts the generated SQL <em>text</em> against a
 * stubbed {@code EntityManager} - {@code Query.setParameter} is a no-op there, so a param-binding mismatch
 * (a {@code :param} referenced in the WHERE but never bound, or vice-versa) would NOT surface. This test
 * runs the native grouping query against a real (H2) database so the binding is actually exercised, AND it
 * asserts the behavioural contract end-to-end: a workflow/epoch whose only rows are machine step-output JSON
 * (no {@code file_name}/{@code s3_key}) must yield NO virtual folder under the Files browser's
 * {@code filesOnly + s3Only} filter - the bug this guards (empty folders) - while {@link VirtualFileFilter#NONE}
 * keeps the pre-fix behaviour (every workflow row counts).</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StorageExplorerRepository.class)
@DisplayName("StorageExplorerRepository - virtual grouping file filter (real H2 binding)")
class StorageExplorerRepositoryVirtualPersistenceTest {

    private static final String ORG = "org-vf-1";
    private static final String WF = "wf-vf-1";

    /** The Files browser's filter: only real downloadable/uploaded files count toward a folder. */
    private static final VirtualFileFilter FILES_ONLY =
            new VirtualFileFilter(true, true, null, null, null, null, null, null);

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private StorageExplorerRepository explorerRepository;

    @BeforeEach
    void seed() {
        // epoch 0: a REAL file (file_name + s3_key) ...
        persist(realFile(0, "report_e0.pdf", "k/report_e0.pdf"));
        // ... alongside a machine step-output JSON in the SAME epoch (no file_name/s3_key).
        persist(stepOutput(0));
        // epoch 1: ONLY a step-output JSON - no real file → must NOT yield a folder under FILES_ONLY.
        persist(stepOutput(1));
        // epoch 2: a REAL file → must yield a folder.
        persist(realFile(2, "shot_e2.png", "k/shot_e2.png"));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("filesOnly+s3Only → only epochs that produced a REAL file yield a group (step-output-only epoch absent)")
    void onlyEpochsWithRealFilesYieldGroups() {
        List<VirtualGroup> groups = explorerRepository.listVirtualGroups(
                ORG, Level.EPOCH, WF, null, null, null, List.of(), FILES_ONLY);

        // epoch 0 and epoch 2 have a real file; epoch 1 (step-output only) is GONE.
        assertThat(groups).extracting(VirtualGroup::key).containsExactlyInAnyOrder("0", "2");
        // epoch 0's count is 1 (the real file only - the step-output JSON in epoch 0 does NOT inflate it).
        assertThat(groups).filteredOn(g -> g.key().equals("0")).singleElement()
                .extracting(VirtualGroup::count).isEqualTo(1L);
    }

    @Test
    @DisplayName("VirtualFileFilter.NONE → every workflow row counts (pre-fix back-compat: all 3 epochs)")
    void noneFilterCountsEveryRow() {
        List<VirtualGroup> groups = explorerRepository.listVirtualGroups(
                ORG, Level.EPOCH, WF, null, null, null, List.of(), VirtualFileFilter.NONE);

        // Without the file filter, the step-output-only epoch 1 ALSO yields a group.
        assertThat(groups).extracting(VirtualGroup::key).containsExactlyInAnyOrder("0", "1", "2");
        // epoch 0 now counts BOTH rows (real file + step-output JSON).
        assertThat(groups).filteredOn(g -> g.key().equals("0")).singleElement()
                .extracting(VirtualGroup::count).isEqualTo(2L);
    }

    @Test
    @DisplayName("the WORKFLOW root grouping hides a workflow whose only rows are step outputs (filesOnly)")
    void workflowWithOnlyStepOutputsAbsentFromRoot() {
        // A second workflow with ONLY a step-output JSON - no real file.
        StorageEntity jsonOnly = stepOutput(0);
        jsonOnly.setWorkflowId("wf-json-only");
        persist(jsonOnly);
        entityManager.flush();
        entityManager.clear();

        List<VirtualGroup> groups = explorerRepository.listVirtualGroups(
                ORG, Level.WORKFLOW, null, null, null, null, List.of(), FILES_ONLY);

        // Only WF (has real files) appears; the step-output-only workflow is absent.
        assertThat(groups).extracting(VirtualGroup::key).containsExactly(WF);
    }

    @Test
    @DisplayName("RUN grouping returns a group per distinct run_id and EXCLUDES null-run rows")
    void runGroupingExcludesNullRun() {
        // The seed gave WF three runs (run-e0/run-e1/run-e2). Add a real file with NO run_id.
        StorageEntity noRun = realFile(0, "no_run.png", "k/no_run.png");
        noRun.setRunId(null);
        persist(noRun);
        entityManager.flush();
        entityManager.clear();

        List<VirtualGroup> runs = explorerRepository.listVirtualGroups(
                ORG, Level.RUN, WF, null, null, null, List.of(), VirtualFileFilter.NONE);

        // Three real runs; the null-run row is NOT its own "run" folder (it is excluded).
        assertThat(runs).extracting(VirtualGroup::key)
                .containsExactlyInAnyOrder("run-e0", "run-e1", "run-e2");
    }

    @Test
    @DisplayName("run-pinned EPOCH isolates each run; collapsed (run-unconstrained) MERGES them - the bug+fix")
    void runPinnedEpochIsolatesVsCollapsed() {
        // A workflow whose TWO runs both produced a file in epoch 0 - pre-run-level these merged.
        String wf2 = "wf-collision";
        persist(runRow(wf2, "run-A", 0, "a.png"));
        persist(runRow(wf2, "run-B", 0, "b.png"));
        entityManager.flush();
        entityManager.clear();

        // RUN level → the two runs.
        assertThat(explorerRepository.listVirtualGroups(ORG, Level.RUN, wf2, null, null, null, List.of(), VirtualFileFilter.NONE))
                .extracting(VirtualGroup::key).containsExactlyInAnyOrder("run-A", "run-B");

        // EPOCH pinned to run-A → epoch 0 with COUNT 1 (run-A only) - isolated from run-B.
        assertThat(explorerRepository.listVirtualGroups(ORG, Level.EPOCH, wf2, "run-A", null, null, List.of(), VirtualFileFilter.NONE))
                .singleElement().satisfies(g -> {
                    assertThat(g.key()).isEqualTo("0");
                    assertThat(g.count()).isEqualTo(1L);
                });

        // Collapsed (runId null) → epoch 0 with COUNT 2 (both runs merged) - the pre-run-level behaviour.
        assertThat(explorerRepository.listVirtualGroups(ORG, Level.EPOCH, wf2, null, null, null, List.of(), VirtualFileFilter.NONE))
                .singleElement().satisfies(g -> {
                    assertThat(g.key()).isEqualTo("0");
                    assertThat(g.count()).isEqualTo(2L);
                });
    }

    // ---- helpers ----

    private void persist(StorageEntity e) {
        entityManager.persist(e);
    }

    /** A real workflow file row pinned to a specific {@code wf}/{@code runId}/{@code epoch}. */
    private static StorageEntity runRow(String wf, String runId, int epoch, String fileName) {
        StorageEntity e = realFile(epoch, fileName, "k/" + fileName);
        e.setWorkflowId(wf);
        e.setRunId(runId);
        return e;
    }

    /** A real workflow file row (file_name + s3_key present) in the given epoch. */
    private static StorageEntity realFile(int epoch, String fileName, String s3Key) {
        StorageEntity e = base(epoch);
        e.setFileName(fileName);
        e.setS3Key(s3Key);
        e.setMimeType("application/octet-stream");
        return e;
    }

    /** A machine step-output JSON row (no file_name / no s3_key) in the given epoch. */
    private static StorageEntity stepOutput(int epoch) {
        return base(epoch); // file_name + s3_key left null
    }

    private static StorageEntity base(int epoch) {
        StorageEntity e = new StorageEntity();
        e.setTenantId(ORG);
        e.setOrganizationId(ORG);
        e.setContentType("application/json");
        e.setData("{}");
        e.setStorageType("S3_FILE");
        e.setSourceType("STEP_OUTPUT");
        e.setSizeBytes(10);
        e.setStatus(StorageStatus.ACTIVE);
        e.setCreatedAt(Instant.now());
        e.setAccessedAt(Instant.now());
        e.setWorkflowId(WF);
        e.setRunId("run-e" + epoch);
        e.setEpoch(epoch);
        e.setSpawn(0);
        e.setItemIndex(0);
        e.setIsFolder(false);
        // parent_folder_id left null → a virtual-root row (participates in the virtual tree)
        return e;
    }
}
