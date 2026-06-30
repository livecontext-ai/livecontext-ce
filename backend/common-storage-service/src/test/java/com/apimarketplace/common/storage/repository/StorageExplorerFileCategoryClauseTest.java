package com.apimarketplace.common.storage.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the server-side file-type category predicate. The Files page
 * filters by type over the FULL DB set (not the loaded page), so the SQL clause
 * must (a) map each category to the same mime/extension rules as the frontend
 * matchesFileType, and (b) never echo caller input into SQL (injection-safe).
 */
@DisplayName("StorageExplorerRepository.fileCategoryClause")
class StorageExplorerFileCategoryClauseTest {

    @Test
    @DisplayName("returns empty (no predicate) for null / blank / _all / unknown categories")
    void emptyForNoFilter() {
        assertThat(StorageExplorerRepository.fileCategoryClause(null)).isEmpty();
        assertThat(StorageExplorerRepository.fileCategoryClause("")).isEmpty();
        assertThat(StorageExplorerRepository.fileCategoryClause("   ")).isEmpty();
        assertThat(StorageExplorerRepository.fileCategoryClause("_all")).isEmpty();
        assertThat(StorageExplorerRepository.fileCategoryClause("totally-unknown")).isEmpty();
    }

    @Test
    @DisplayName("each known category yields a leading ' AND' predicate with the right mime/extension tokens")
    void knownCategoriesMapToExpectedClauses() {
        assertThat(StorageExplorerRepository.fileCategoryClause("images"))
                .startsWith(" AND").contains("image/%");
        assertThat(StorageExplorerRepository.fileCategoryClause("pdf"))
                .startsWith(" AND").contains("%pdf%");
        assertThat(StorageExplorerRepository.fileCategoryClause("video"))
                .contains("video/%");
        assertThat(StorageExplorerRepository.fileCategoryClause("audio"))
                .contains("audio/%");
        assertThat(StorageExplorerRepository.fileCategoryClause("text"))
                .contains("%plain%");
        assertThat(StorageExplorerRepository.fileCategoryClause("documents"))
                .contains("%.docx").contains("wordprocessingml");
        assertThat(StorageExplorerRepository.fileCategoryClause("spreadsheets"))
                .contains("%.csv").contains("%spreadsheet%");
        assertThat(StorageExplorerRepository.fileCategoryClause("presentations"))
                .contains("%.pptx").contains("%presentation%");
        assertThat(StorageExplorerRepository.fileCategoryClause("archives"))
                .contains("%.zip").contains("%gzip%");
        assertThat(StorageExplorerRepository.fileCategoryClause("code"))
                .contains("%.py").contains("%json%");
    }

    @Test
    @DisplayName("never echoes caller input into the SQL fragment (injection-safe constant mapping)")
    void injectionSafe() {
        // A malicious 'category' is not a known key → maps to empty, so nothing
        // attacker-controlled ever reaches the SQL string.
        String malicious = "images'); DROP TABLE storage.storage; --";
        assertThat(StorageExplorerRepository.fileCategoryClause(malicious)).isEmpty();
        // Known categories produce only fixed fragments - no caller text inside.
        assertThat(StorageExplorerRepository.fileCategoryClause("images")).doesNotContain("DROP");
    }
}
