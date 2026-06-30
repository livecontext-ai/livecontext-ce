package com.apimarketplace.common.storage.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the native SQL built by {@link StorageExplorerRepository#listFolderScope} (V313
 * folder-aware listing) - the EntityManager is mocked and the count query stubbed to 0 so each
 * call returns before the data query; we capture the (count) SQL and assert the predicate/ordering.
 */
@DisplayName("StorageExplorerRepository.listFolderScope - native SQL")
class StorageExplorerFolderScopeSqlTest {

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

    private static EntityManager emReturningEmpty() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.getSingleResult()).thenReturn(0L); // total==0 → returns before the data query
        return em;
    }

    private static String capturedSql(EntityManager em) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, atLeastOnce()).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    private static void listRoot(StorageExplorerRepository repo, boolean filesOnly, boolean s3Only,
                                 String search, String fileCategory) {
        repo.listFolderScope("org-1", /* parentFolderId */ null, search, null, null, null, null,
                null, null, filesOnly, s3Only, fileCategory, List.of(),
                PageRequest.of(0, 20).getPageSize(), 0);
    }

    @Test
    @DisplayName("root listing scopes to parent_folder_id IS NULL and ORs (folder) with (file)")
    void rootListingPredicate() {
        EntityManager em = emReturningEmpty();
        listRoot(repoWithStubbedEm(em), false, false, null, null);

        String sql = capturedSql(em);
        assertThat(sql).contains("s.organization_id = :orgId");
        assertThat(sql).contains("s.status = 'ACTIVE'");
        assertThat(sql).contains("s.parent_folder_id IS NULL");
        assertThat(sql).contains("s.is_folder = true");   // folder branch
        assertThat(sql).contains("s.is_folder = false");  // file branch
    }

    @Test
    @DisplayName("inside-a-folder listing scopes to parent_folder_id = :parentFolderId (not IS NULL)")
    void insideFolderPredicate() {
        EntityManager em = emReturningEmpty();
        StorageExplorerRepository repo = repoWithStubbedEm(em);
        repo.listFolderScope("org-1", UUID.randomUUID(), null, null, null, null, null,
                null, null, false, false, null, List.of(), 20, 0);

        String sql = capturedSql(em);
        assertThat(sql).contains("s.parent_folder_id = :parentFolderId");
        assertThat(sql).doesNotContain("s.parent_folder_id IS NULL");
    }

    /** Stub an EM whose count query returns >0 so the DATA query (the one carrying ORDER BY) is built. */
    private static EntityManager emReturningRows() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.getSingleResult()).thenReturn(1L);
        when(q.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        return em;
    }

    @Test
    @DisplayName("ordering puts folders first (is_folder DESC) then by folder LAST ACTIVITY (MAX child created_at, fallback own)")
    void orderingFoldersFirstByLastActivity() {
        EntityManager em = emReturningRows();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listFolderScope("org-1", null, null, null, null, null, null, null, null,
                false, false, null, List.of(), 20, 0);

        // The data query is the last SQL built.
        String sql = capturedSql(em);
        // Folders still come first.
        assertThat(sql).contains("ORDER BY s.is_folder DESC,");
        // Folders now sort by the date the LAST element was added - MAX of the active children's
        // created_at, correlated to the folder row, falling back to the folder's own created_at.
        assertThat(sql).contains("CASE WHEN s.is_folder THEN COALESCE(");
        assertThat(sql).contains("SELECT MAX(c.created_at) FROM storage.storage c");
        assertThat(sql).contains("c.parent_folder_id = s.id AND c.status = 'ACTIVE'");
        assertThat(sql).contains("s.created_at) ELSE s.created_at END DESC");
        // Without a deny-list the child-date sub-query carries no exclusion guard.
        assertThat(sql).doesNotContain("c.id NOT IN (:excludedIds)");
    }

    @Test
    @DisplayName("ordering: a non-empty deny-list folds 'c.id NOT IN (:excludedIds)' INTO the folder-activity sub-query")
    void orderingActivitySubqueryHonoursExclusions() {
        EntityManager em = emReturningRows();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listFolderScope("org-1", null, null, null, null, null, null, null, null,
                false, false, null, List.of(UUID.randomUUID()), 20, 0);

        String sql = capturedSql(em);
        // A restricted child must not advance a folder's position / leak its recency - the guard rides
        // inside the MAX(child) sub-query, not only on the outer WHERE.
        assertThat(sql).contains("c.parent_folder_id = s.id AND c.status = 'ACTIVE' AND c.id NOT IN (:excludedIds)");
    }

    @Test
    @DisplayName("latestChildCreatedAtByParent: empty folder set returns empty without querying")
    void latestChildShortCircuitsOnEmptyInput() {
        EntityManager em = mock(EntityManager.class);
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        assertThat(repo.latestChildCreatedAtByParent("org-1", List.of(), List.of())).isEmpty();
        verify(em, org.mockito.Mockito.never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("latestChildCreatedAtByParent: MAX(created_at) grouped per parent, org+ACTIVE scoped, no guard when deny-list empty")
    void latestChildQueryPredicateNoExclusions() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.latestChildCreatedAtByParent("org-1", List.of(UUID.randomUUID()), List.of());

        String sql = capturedSql(em);
        assertThat(sql).contains("MAX(s.created_at)");
        assertThat(sql).contains("s.organization_id = :orgId");
        assertThat(sql).contains("s.status = 'ACTIVE'");
        assertThat(sql).contains("s.parent_folder_id IN (:folderIds)");
        assertThat(sql).contains("GROUP BY s.parent_folder_id");
        assertThat(sql).doesNotContain("s.id NOT IN (:excludedIds)");
    }

    @Test
    @DisplayName("latestChildCreatedAtByParent: a non-empty deny-list wires 's.id NOT IN (:excludedIds)'")
    void latestChildQueryAppliesExclusions() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.latestChildCreatedAtByParent("org-1", List.of(UUID.randomUUID()), List.of(UUID.randomUUID()));

        assertThat(capturedSql(em)).contains("s.id NOT IN (:excludedIds)");
    }

    @Test
    @DisplayName("s3Only / fileCategory constrain ONLY the file branch - folder branch never gets them")
    void fileFiltersOnlyOnFileBranch() {
        EntityManager em = emReturningEmpty();
        listRoot(repoWithStubbedEm(em), /* filesOnly */ true, /* s3Only */ true, null, "images");

        String sql = capturedSql(em);
        // File branch carries the file-only predicates.
        assertThat(sql).contains("s.s3_key IS NOT NULL");
        assertThat(sql).contains("s.file_name IS NOT NULL");
        assertThat(sql).contains("s.mime_type ILIKE 'image/%'");
        // The folder branch (the part before " OR (s.is_folder = false") must NOT contain s3_key -
        // a folder is not an object-storage file.
        String folderBranch = sql.substring(sql.indexOf("s.is_folder = true"),
                sql.indexOf("OR (s.is_folder = false"));
        assertThat(folderBranch).doesNotContain("s3_key");
        assertThat(folderBranch).doesNotContain("mime_type");
    }

    @Test
    @DisplayName("search text filters BOTH branches: folder names by file_name, files by name/content/step")
    void searchFiltersBothBranches() {
        EntityManager em = emReturningEmpty();
        listRoot(repoWithStubbedEm(em), false, false, "report", null);

        String sql = capturedSql(em);
        String folderBranch = sql.substring(sql.indexOf("s.is_folder = true"),
                sql.indexOf("OR (s.is_folder = false"));
        // Folder branch filters the folder NAME only.
        assertThat(folderBranch).contains("s.file_name ILIKE :search");
        // File branch keeps the broader content/step search.
        assertThat(sql).contains("s.content_type ILIKE :search");
    }

    @Test
    @DisplayName("countChildrenByParent / findPreviewFilesByParent return empty for an empty folder set without querying")
    void batchHelpersShortCircuitOnEmptyInput() {
        EntityManager em = mock(EntityManager.class);
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        assertThat(repo.countChildrenByParent("org-1", List.of(), List.of())).isEmpty();
        assertThat(repo.findPreviewFilesByParent("org-1", List.of(), List.of())).isEmpty();
        verify(em, org.mockito.Mockito.never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("findPreviewFilesByParent selects id+mime+name for non-folder children of ANY type, newest first, org-scoped")
    void previewQueryPredicate() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.findPreviewFilesByParent("org-1", List.of(UUID.randomUUID()), List.of());

        String sql = capturedSql(em);
        assertThat(sql).contains("s.organization_id = :orgId"); // NIT: org-scope pinned
        assertThat(sql).contains("s.is_folder = false");
        // The tile now carries the file type so the frontend can pick a thumbnail or a format icon.
        assertThat(sql).contains("s.mime_type");
        assertThat(sql).contains("s.file_name");
        // Every file type is a preview candidate now - the old image/video/pdf mime filter is gone.
        assertThat(sql).doesNotContain("ILIKE 'image/%'");
        assertThat(sql).doesNotContain("ILIKE '%pdf%'");
        assertThat(sql).contains("ORDER BY s.parent_folder_id, s.created_at DESC");
        // No deny-list passed → the exclusion guard must NOT be wired.
        assertThat(sql).doesNotContain("s.id NOT IN (:excludedIds)");
    }

    @Test
    @DisplayName("countChildrenByParent is org-scoped and applies NO exclusion guard when the deny-list is empty")
    void countQueryPredicateNoExclusions() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.countChildrenByParent("org-1", List.of(UUID.randomUUID()), List.of());

        String sql = capturedSql(em);
        assertThat(sql).contains("s.organization_id = :orgId"); // NIT: org-scope pinned
        assertThat(sql).contains("s.parent_folder_id IN (:folderIds)");
        assertThat(sql).doesNotContain("s.id NOT IN (:excludedIds)");
    }

    @Test
    @DisplayName("FIX 2: a non-empty excludedIds deny-list wires 's.id NOT IN (:excludedIds)' into BOTH batch helpers")
    void batchHelpersApplyExclusionGuardWhenDenyListPresent() {
        // countChildrenByParent
        EntityManager countEm = mock(EntityManager.class);
        Query countQ = mock(Query.class);
        when(countEm.createNativeQuery(anyString())).thenReturn(countQ);
        when(countQ.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(countQ);
        when(countQ.getResultList()).thenReturn(List.of());
        repoWithStubbedEm(countEm).countChildrenByParent("org-1", List.of(UUID.randomUUID()), List.of(UUID.randomUUID()));
        assertThat(capturedSql(countEm)).contains("s.id NOT IN (:excludedIds)");

        // findPreviewFilesByParent
        EntityManager previewEm = mock(EntityManager.class);
        Query previewQ = mock(Query.class);
        when(previewEm.createNativeQuery(anyString())).thenReturn(previewQ);
        when(previewQ.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(previewQ);
        when(previewQ.getResultList()).thenReturn(List.of());
        repoWithStubbedEm(previewEm).findPreviewFilesByParent("org-1", List.of(UUID.randomUUID()), List.of(UUID.randomUUID()));
        assertThat(capturedSql(previewEm)).contains("s.id NOT IN (:excludedIds)");
    }

    @Test
    @DisplayName("FIX 1: the legacy flat appendWhere path excludes folder rows (s.is_folder = false)")
    void legacyFlatPathExcludesFolders() {
        StringBuilder where = new StringBuilder();
        StorageExplorerRepository.appendWhere(where, /* filesOnly */ false, /* s3Only */ false,
                null, null, null, null, null, null, null, List.of());
        assertThat(where.toString()).contains("s.is_folder = false");
    }

    @Test
    @DisplayName("FIX 1: getStats aggregation excludes folder rows (is_folder = false) so folders don't inflate the flat stats")
    void getStatsExcludesFolders() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.getStats("org-1");

        String sql = capturedSql(em);
        assertThat(sql).contains("organization_id = :orgId");
        assertThat(sql).contains("is_folder = false");
    }
}
