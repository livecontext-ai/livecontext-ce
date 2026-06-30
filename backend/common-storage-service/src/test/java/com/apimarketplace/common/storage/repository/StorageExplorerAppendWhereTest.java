package com.apimarketplace.common.storage.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared WHERE-clause builder, focused on the {@code s3Only}
 * predicate. The full-page Files browser passes {@code s3Only=true} so real
 * user-facing files surface - object-storage files PLUS chat attachments (saved as
 * DB BINARY with {@code source_type='CHAT_ATTACHMENT'} and no {@code s3_key}). Agent
 * observability TEXT blobs ({@code tool_call_result.txt}) and avatars
 * ({@code USER_AVATAR}/{@code ORG_AVATAR}) carry neither an {@code s3_key} nor that
 * source type, so they stay excluded.
 * Tested at the SQL-fragment level (mirrors {@link StorageExplorerFileCategoryClauseTest}).
 */
@DisplayName("StorageExplorerRepository.appendWhere - s3Only predicate")
class StorageExplorerAppendWhereTest {

    private static String where(boolean filesOnly, boolean s3Only) {
        StringBuilder sb = new StringBuilder();
        StorageExplorerRepository.appendWhere(
                sb, filesOnly, s3Only,
                /* search */ null, /* sourceType */ null, /* storageType */ null,
                /* workflowId */ null, /* runId */ null, /* dateFrom */ null, /* dateTo */ null,
                /* excludedIds */ List.of());
        return sb.toString();
    }

    @Test
    @DisplayName("s3Only=true keeps real object-storage files (s3_key IS NOT NULL)")
    void s3OnlyTrueAddsPredicate() {
        assertThat(where(false, true)).contains("s.s3_key IS NOT NULL");
    }

    @Test
    @DisplayName("s3Only=true ALSO admits chat attachments (CHAT_ATTACHMENT, no s3_key) - regression: chat-uploaded images were hidden from Files")
    void s3OnlyTrueIncludesChatAttachments() {
        String sql = where(false, true);
        // The whole predicate must be an OR so a CHAT_ATTACHMENT row with a null s3_key still matches.
        assertThat(sql).contains("(s.s3_key IS NOT NULL OR s.source_type = 'CHAT_ATTACHMENT')");
    }

    @Test
    @DisplayName("s3Only=false leaves the predicate off (legacy behaviour, DB-resident rows still visible)")
    void s3OnlyFalseOmitsPredicate() {
        // Neither half of the s3Only OR-predicate leaks when the flag is off.
        assertThat(where(false, false)).doesNotContain("s.s3_key").doesNotContain("CHAT_ATTACHMENT");
        assertThat(where(true, false)).doesNotContain("s.s3_key").doesNotContain("CHAT_ATTACHMENT");
    }

    @Test
    @DisplayName("filesOnly and s3Only compose - both predicates present (AND)")
    void filesOnlyAndS3OnlyCompose() {
        String sql = where(true, true);
        assertThat(sql).contains("s.file_name IS NOT NULL");
        assertThat(sql).contains("(s.s3_key IS NOT NULL OR s.source_type = 'CHAT_ATTACHMENT')");
    }

    @Test
    @DisplayName("base org + ACTIVE scope is always present regardless of s3Only")
    void baseScopeAlwaysPresent() {
        assertThat(where(false, true))
                .contains("s.organization_id = :orgId")
                .contains("s.status = 'ACTIVE'");
    }
}
