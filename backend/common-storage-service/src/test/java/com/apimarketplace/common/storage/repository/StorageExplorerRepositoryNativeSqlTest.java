package com.apimarketplace.common.storage.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the native SQL the real public methods build - proving the
 * {@code s3Only} restriction reaches the Files-page {@code search} path and
 * provably never reaches the agent {@code files} tool's {@code searchSlice} path.
 *
 * <p>The EntityManager is mocked and the count query is stubbed to return 0, so
 * each method returns early after building its (count) SQL - no DB needed. We
 * capture the SQL handed to {@code createNativeQuery} and assert on the predicate.</p>
 */
@DisplayName("StorageExplorerRepository - native SQL (s3Only reach & agent-path isolation)")
class StorageExplorerRepositoryNativeSqlTest {

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
        // total == 0 → both search() and searchSlice() return early, before the data query.
        when(q.getSingleResult()).thenReturn(0L);
        return em;
    }

    private static String capturedSql(EntityManager em) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, atLeastOnce()).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    @Test
    @DisplayName("Files-page search(s3Only=true) builds SQL admitting object-storage files OR chat attachments")
    void filesPageSearchAppliesS3KeyPredicate() {
        EntityManager em = emReturningEmpty();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.search("org-1", null, null, null, null, null, null, null,
                /* filesOnly */ true, /* s3Only */ true, null, List.of(), PageRequest.of(0, 20));

        // Regression: chat-uploaded images (CHAT_ATTACHMENT, no s3_key) must reach the Files page.
        assertThat(capturedSql(em))
                .contains("(s.s3_key IS NOT NULL OR s.source_type = 'CHAT_ATTACHMENT')");
    }

    @Test
    @DisplayName("Files-page search(s3Only=false) does NOT add the s3_key predicate (other surfaces keep DB-resident rows)")
    void filesPageSearchWithoutS3OnlyOmitsPredicate() {
        EntityManager em = emReturningEmpty();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.search("org-1", null, null, null, null, null, null, null,
                /* filesOnly */ true, /* s3Only */ false, null, List.of(), PageRequest.of(0, 20));

        assertThat(capturedSql(em)).doesNotContain("s3_key");
    }

    @Test
    @DisplayName("Agent files tool searchSlice is NEVER S3-only - keeps filesOnly (file_name) but never adds s3_key")
    void agentFilesSliceIsNeverS3Only() {
        EntityManager em = emReturningEmpty();
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        // The agent files tool always calls searchSlice with filesOnly=true; there is
        // no way for it to request s3Only - the repo hard-codes false internally.
        repo.searchSlice("org-1", null, null, null, null, null, null, null,
                /* filesOnly */ true, /* limit */ 20, /* offset */ 0);

        String sql = capturedSql(em);
        assertThat(sql).contains("s.file_name IS NOT NULL"); // filesOnly still honoured
        assertThat(sql).doesNotContain("s3_key");             // never restricted to S3 objects
    }

    @Test
    @DisplayName("listAllManualFolders builds is_folder=true WITHOUT the parent_folder_id IS NULL restriction (every depth) ordered by name")
    void listAllManualFoldersSpansEveryDepth() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        // No count query in this path - it's a single SELECT; return an empty row list.
        when(q.getResultList()).thenReturn(List.of());
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listAllManualFolders("org-1", List.of());

        String sql = capturedSql(em);
        assertThat(sql).contains("s.is_folder = true");
        // The whole-org picker must NOT pin to the root - that is the listRootManualFolders restriction.
        assertThat(sql).doesNotContain("parent_folder_id IS NULL");
        assertThat(sql).contains("ORDER BY s.file_name");
    }

    @Test
    @DisplayName("listAllManualFolders adds the NOT IN (:excludedIds) deny-list only when ids are supplied")
    void listAllManualFoldersAppliesExclusions() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        StorageExplorerRepository repo = repoWithStubbedEm(em);

        repo.listAllManualFolders("org-1", List.of(java.util.UUID.randomUUID()));

        assertThat(capturedSql(em)).contains("s.id NOT IN (:excludedIds)");
    }
}
