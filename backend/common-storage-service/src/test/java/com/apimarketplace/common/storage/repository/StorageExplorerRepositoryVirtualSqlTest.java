package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.dto.VirtualFolderAddress.Level;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the native SQL the Phase 2b virtual-folder queries build - the GROUP-BY column and the
 * level-scope predicates are CONSTANTS selected by the {@link Level} enum (no user input in the SQL
 * string), and scalar coordinates are bound parameters. The EntityManager is mocked; the count query
 * returns 0 so {@code listVirtualLeafFiles} stops after the count SQL (no DB needed).
 */
@DisplayName("StorageExplorerRepository - virtual folder native SQL (injection-safe, level-correct)")
class StorageExplorerRepositoryVirtualSqlTest {

    private static StorageExplorerRepository repoWithStubbedEm(EntityManager em) {
        StorageExplorerRepository repo = new StorageExplorerRepository();
        try {
            Field f = StorageExplorerRepository.class.getDeclaredField("em");
            f.setAccessible(true);
            f.set(repo, em);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return repo;
    }

    private static EntityManager emReturningEmptyList() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        when(q.getSingleResult()).thenReturn(0L); // count=0 → leaf-files returns early
        return em;
    }

    private static String capturedSql(EntityManager em) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, atLeastOnce()).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    // ============================ listVirtualGroups - GROUP BY column per level ============================

    @Test
    @DisplayName("WORKFLOW level groups by workflow_id, filters workflow_id IS NOT NULL (root: no parent filter)")
    void workflowGroupsByWorkflowId() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listVirtualGroups("org-1", Level.WORKFLOW, null, null, null, null, List.of(), StorageExplorerRepository.VirtualFileFilter.NONE);

        String sql = capturedSql(em);
        assertThat(sql).contains("GROUP BY s.workflow_id");
        assertThat(sql).contains("s.workflow_id IS NOT NULL");
        assertThat(sql).contains("s.parent_folder_id IS NULL"); // only virtual-root rows
        assertThat(sql).contains("s.is_folder = false");
        assertThat(sql).contains("ORDER BY MAX(s.created_at) DESC");
        // root level pins no parent coordinate
        assertThat(sql).doesNotContain(":wf");
        assertThat(sql).doesNotContain(":epoch");
    }

    @Test
    @DisplayName("EPOCH level groups by epoch, pins workflow_id = :wf")
    void epochGroupsByEpoch() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listVirtualGroups("org-1", Level.EPOCH, "wf-1", null, null, null, List.of(), StorageExplorerRepository.VirtualFileFilter.NONE);

        String sql = capturedSql(em);
        assertThat(sql).contains("GROUP BY s.epoch");
        assertThat(sql).contains("s.workflow_id = :wf");
        assertThat(sql).doesNotContain("s.epoch = :epoch");
    }

    @Test
    @DisplayName("SPAWN level groups by spawn, pins workflow_id + epoch")
    void spawnGroupsBySpawn() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listVirtualGroups("org-1", Level.SPAWN, "wf-1", null, 0, null, List.of(), StorageExplorerRepository.VirtualFileFilter.NONE);

        String sql = capturedSql(em);
        assertThat(sql).contains("GROUP BY s.spawn");
        assertThat(sql).contains("s.workflow_id = :wf AND s.epoch = :epoch");
    }

    @Test
    @DisplayName("ITERATION level groups by item_index, pins workflow_id + epoch + spawn")
    void iterationGroupsByItemIndex() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listVirtualGroups("org-1", Level.ITERATION, "wf-1", null, 0, 0, List.of(), StorageExplorerRepository.VirtualFileFilter.NONE);

        String sql = capturedSql(em);
        assertThat(sql).contains("GROUP BY s.item_index");
        assertThat(sql).contains("s.workflow_id = :wf AND s.epoch = :epoch AND s.spawn = :spawn");
    }

    @Test
    @DisplayName("RUN level groups by run_id, pins workflow_id, excludes NULL run_id, orders oldest-first (Run 1 first)")
    void runGroupsByRunId() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listVirtualGroups("org-1", Level.RUN, "wf-1", null, null, null, List.of(), StorageExplorerRepository.VirtualFileFilter.NONE);

        String sql = capturedSql(em);
        assertThat(sql).contains("GROUP BY s.run_id");
        assertThat(sql).contains("s.workflow_id = :wf");
        assertThat(sql).contains("s.run_id IS NOT NULL");
        // Runs are numbered chronologically → oldest run first.
        assertThat(sql).contains("ORDER BY MIN(s.created_at) ASC");
        assertThat(sql).doesNotContain("s.run_id = :run"); // listing runs, not pinning one
    }

    @Test
    @DisplayName("EPOCH pinned to a run (runId != null) adds AND s.run_id = :run; collapsed (runId null) does not")
    void epochPinnedToRun() {
        EntityManager pinned = emReturningEmptyList();
        repoWithStubbedEm(pinned).listVirtualGroups("org-1", Level.EPOCH, "wf-1", "run-x", null, null,
                List.of(), StorageExplorerRepository.VirtualFileFilter.NONE);
        assertThat(capturedSql(pinned)).contains("s.workflow_id = :wf AND s.run_id = :run");

        EntityManager collapsed = emReturningEmptyList();
        repoWithStubbedEm(collapsed).listVirtualGroups("org-1", Level.EPOCH, "wf-1", null, null, null,
                List.of(), StorageExplorerRepository.VirtualFileFilter.NONE);
        assertThat(capturedSql(collapsed)).doesNotContain(":run");
    }

    @Test
    @DisplayName("excludedIds non-empty adds the deny-list NOT IN clause; empty omits it")
    void excludedIdsHonoured() {
        EntityManager withEx = emReturningEmptyList();
        repoWithStubbedEm(withEx).listVirtualGroups("org-1", Level.WORKFLOW, null, null, null, null, List.of(UUID.randomUUID()), StorageExplorerRepository.VirtualFileFilter.NONE);
        assertThat(capturedSql(withEx)).contains("s.id NOT IN (:excludedIds)");

        EntityManager noEx = emReturningEmptyList();
        repoWithStubbedEm(noEx).listVirtualGroups("org-1", Level.WORKFLOW, null, null, null, null, List.of(), StorageExplorerRepository.VirtualFileFilter.NONE);
        assertThat(capturedSql(noEx)).doesNotContain("excludedIds");
    }

    @Test
    @DisplayName("the file filter (filesOnly+s3Only+sourceType) is applied to the GROUP query - only real "
            + "files count toward a folder, so an epoch of step-output rows yields NO folder")
    void groupingAppliesFileFilter() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        // The Files browser passes filesOnly+s3Only: a folder must be computed over real files only.
        repo.listVirtualGroups("org-1", Level.EPOCH, "wf-1", null, null, null, List.of(),
                new StorageExplorerRepository.VirtualFileFilter(true, true, null, "STEP_OUTPUT", null, null, null, null));

        String sql = capturedSql(em);
        assertThat(sql).contains("s.file_name IS NOT NULL");   // filesOnly
        assertThat(sql).contains("s.s3_key IS NOT NULL");       // s3Only
        assertThat(sql).contains("s.source_type = :sourceType");
        assertThat(sql).contains("GROUP BY s.epoch");
    }

    @Test
    @DisplayName("VirtualFileFilter.NONE applies no file predicate (back-compat: counts every workflow row)")
    void noneFilterAppliesNoPredicate() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listVirtualGroups("org-1", Level.EPOCH, "wf-1", null, null, null, List.of(),
                StorageExplorerRepository.VirtualFileFilter.NONE);

        String sql = capturedSql(em);
        assertThat(sql).doesNotContain("s.file_name IS NOT NULL");
        assertThat(sql).doesNotContain("s.s3_key IS NOT NULL");
    }

    @Test
    @DisplayName("the file filter is also applied to the PREVIEW query (tiles only from real files)")
    void previewAppliesFileFilter() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.previewFilesForVirtualGroups("org-1", Level.EPOCH, "wf-1", null, null, null, List.of(),
                new StorageExplorerRepository.VirtualFileFilter(true, true, null, null, null, null, null, null));

        String sql = capturedSql(em);
        assertThat(sql).contains("s.file_name IS NOT NULL");
        assertThat(sql).contains("s.s3_key IS NOT NULL");
    }

    // ============================ previewFilesForVirtualGroups - all file types, typed tile ============================

    @Test
    @DisplayName("preview query selects id+mime+name for EVERY file type (no mime filter) and orders by group col then created_at DESC")
    void previewQuerySelectsTypeForAllFiles() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.previewFilesForVirtualGroups("org-1", Level.EPOCH, "wf-1", null, null, null, List.of(), StorageExplorerRepository.VirtualFileFilter.NONE);

        String sql = capturedSql(em);
        // The tile now carries the file type, so the frontend renders a thumbnail or a format icon.
        assertThat(sql).contains("s.mime_type");
        assertThat(sql).contains("s.file_name");
        // Every file type is a preview candidate now - the old image/video/pdf mime filter is gone.
        assertThat(sql).doesNotContain("ILIKE 'image/%'");
        assertThat(sql).doesNotContain("ILIKE '%pdf%'");
        assertThat(sql).contains("ORDER BY s.epoch, s.created_at DESC, s.id DESC");
    }

    // ============================ listVirtualLeafFiles - workflow scope ============================

    @Test
    @DisplayName("leaf files with workflowId == null scope to workflow_id IS NULL (root loose files)")
    void leafFilesNullWorkflowScopesToIsNull() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listVirtualLeafFiles("org-1", null, null, null, null, null, null, null, null,
                true, true, null, null, null, List.of(), false, 20, 0);

        String sql = capturedSql(em);
        assertThat(sql).contains("s.workflow_id IS NULL");
        assertThat(sql).doesNotContain("s.workflow_id = :wf");
        assertThat(sql).contains("s.parent_folder_id IS NULL");
        assertThat(sql).contains("s.s3_key IS NOT NULL");   // s3Only
        assertThat(sql).contains("s.file_name IS NOT NULL"); // filesOnly
    }

    @Test
    @DisplayName("leaf files with a workflowId scope to workflow_id = :wf and each non-null coordinate")
    void leafFilesScopedWorkflowAndCoordinates() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listVirtualLeafFiles("org-1", "wf-1", null, 2, 3, 4, null, null, null,
                false, false, null, null, null, List.of(), false, 20, 0);

        String sql = capturedSql(em);
        assertThat(sql).contains("s.workflow_id = :wf");
        assertThat(sql).contains("s.epoch = :epoch");
        assertThat(sql).contains("s.spawn = :spawn");
        assertThat(sql).contains("s.item_index = :itemIndex");
        assertThat(sql).doesNotContain("s.workflow_id IS NULL");
    }

    @Test
    @DisplayName("leaf files with a non-null runId pin the run (AND s.run_id = :run); a null runId does not")
    void leafFilesRunPinned() {
        EntityManager pinned = emReturningEmptyList();
        repoWithStubbedEm(pinned).listVirtualLeafFiles("org-1", "wf-1", "run-x", 0, 0, null, null, null, null,
                false, false, null, null, null, List.of(), false, 20, 0);
        assertThat(capturedSql(pinned)).contains("s.run_id = :run");

        EntityManager collapsed = emReturningEmptyList();
        repoWithStubbedEm(collapsed).listVirtualLeafFiles("org-1", "wf-1", null, 0, 0, null, null, null, null,
                false, false, null, null, null, List.of(), false, 20, 0);
        assertThat(capturedSql(collapsed)).doesNotContain(":run");
    }

    @Test
    @DisplayName("leaf files: a null coordinate is unconstrained (collapse: spawn set, itemIndex null → no item_index filter)")
    void leafFilesNullCoordinateUnconstrained() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listVirtualLeafFiles("org-1", "wf-1", null, 2, 3, null, null, null, null,
                false, false, null, null, null, List.of(), false, 20, 0);

        String sql = capturedSql(em);
        assertThat(sql).contains("s.spawn = :spawn");
        assertThat(sql).doesNotContain("s.item_index = :itemIndex");
    }

    @Test
    @DisplayName("leaf files nullItemOnly=true → item_index IS NULL (the spawn's non-split files), no equality")
    void leafFilesNullItemOnlyScopesToIsNull() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        // itemIndex arg is ignored when nullItemOnly=true - the spawn's files produced OUTSIDE a split.
        repo.listVirtualLeafFiles("org-1", "wf-1", null, 2, 3, 4, null, null, null,
                false, false, null, null, null, List.of(), /* nullItemOnly */ true, 20, 0);

        String sql = capturedSql(em);
        assertThat(sql).contains("s.spawn = :spawn");
        assertThat(sql).contains("s.item_index IS NULL");
        assertThat(sql).doesNotContain("s.item_index = :itemIndex");
    }

    // ============================ listRootManualFolders ============================

    @Test
    @DisplayName("root manual folders: is_folder = true AND parent_folder_id IS NULL, newest first")
    void rootManualFolders() {
        EntityManager em = emReturningEmptyList();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listRootManualFolders("org-1", null, List.of());

        String sql = capturedSql(em);
        assertThat(sql).contains("s.is_folder = true");
        assertThat(sql).contains("s.parent_folder_id IS NULL");
        assertThat(sql).contains("ORDER BY s.created_at DESC");
    }

    @Test
    @DisplayName("root manual folders: a search term adds a file_name ILIKE filter")
    void rootManualFoldersSearch() {
        EntityManager withSearch = emReturningEmptyList();
        repoWithStubbedEm(withSearch).listRootManualFolders("org-1", "rep", List.of());
        assertThat(capturedSql(withSearch)).contains("s.file_name ILIKE :search");

        EntityManager noSearch = emReturningEmptyList();
        repoWithStubbedEm(noSearch).listRootManualFolders("org-1", "  ", List.of());
        assertThat(capturedSql(noSearch)).doesNotContain("file_name ILIKE");
    }
}
