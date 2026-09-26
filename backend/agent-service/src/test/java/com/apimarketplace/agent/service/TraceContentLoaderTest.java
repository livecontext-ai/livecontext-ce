package com.apimarketplace.agent.service;

import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A long trace row keeps a 500-character excerpt and moves its full text to storage. Nothing
 * read it back, so the trace showed 514 of a classified email's 12,748 characters.
 */
@DisplayName("TraceContentLoader")
class TraceContentLoaderTest {

    private static final String EXCERPT = "x".repeat(500) + "...[truncated]";
    private static final UUID ID = UUID.randomUUID();

    private final StorageService storage = mock(StorageService.class);
    private final TraceContentLoader loader = new TraceContentLoader(storage);

    @Test
    @DisplayName("BUG: a row moved to storage returns its full text, not the excerpt")
    void movedRowReturnsTheFullText() {
        String full = "y".repeat(12_748);
        when(storage.getByIdReadOnly(ID, "tenant-1")).thenReturn(Optional.of(full));

        assertThat(loader.fullContent(EXCERPT, ID, "tenant-1", 12_748, Integer.MAX_VALUE)).isEqualTo(full);
    }

    @Test
    @DisplayName("a row never moved is returned as it is, without touching storage")
    void unmovedRowIsUntouched() {
        assertThat(loader.fullContent("short", null, "tenant-1", 5, Integer.MAX_VALUE)).isEqualTo("short");
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("a recorded length over the caller's limit keeps the excerpt and never loads the payload")
    void oversizedIsNeverLoaded() {
        assertThat(loader.fullContent(EXCERPT, ID, "tenant-1", 2_000_000, 256 * 1024)).isEqualTo(EXCERPT);
        verify(storage, never()).getByIdReadOnly(any(), any());
    }

    @Test
    @DisplayName("a stored text longer than the limit (no recorded length) keeps the excerpt")
    void storedTextOverTheLimitKeepsTheExcerpt() {
        when(storage.getByIdReadOnly(ID, "tenant-1")).thenReturn(Optional.of("z".repeat(1_000)));

        assertThat(loader.fullContent(EXCERPT, ID, "tenant-1", null, 999)).isEqualTo(EXCERPT);
    }

    @Test
    @DisplayName("a text gone from storage, or unreadable, falls back to the excerpt instead of failing the trace")
    void missingOrFailingStorageFallsBack() {
        when(storage.getByIdReadOnly(ID, "tenant-1")).thenReturn(Optional.empty());
        assertThat(loader.fullContent(EXCERPT, ID, "tenant-1", 12_000, Integer.MAX_VALUE)).isEqualTo(EXCERPT);

        when(storage.getByIdReadOnly(ID, "tenant-1")).thenThrow(new IllegalStateException("db down"));
        assertThat(loader.fullContent(EXCERPT, ID, "tenant-1", 12_000, Integer.MAX_VALUE)).isEqualTo(EXCERPT);
    }

    @Test
    @DisplayName("a stored value that is not text (a JSON map) is not rendered as Java's toString")
    void nonTextStoredValueKeepsTheExcerpt() {
        when(storage.getByIdReadOnly(ID, "tenant-1")).thenReturn(Optional.of(java.util.Map.of("a", 1)));

        assertThat(loader.fullContent(EXCERPT, ID, "tenant-1", 12_000, Integer.MAX_VALUE)).isEqualTo(EXCERPT);
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("reading back a page")
    class ReadBackPage {

        private final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
        private final TraceContentLoader pageLoader = new TraceContentLoader(storage, entityManager);

        private com.apimarketplace.agent.domain.AgentExecutionMessageEntity message(String content, UUID storageId, Integer length) {
            com.apimarketplace.agent.domain.AgentExecutionMessageEntity m = new com.apimarketplace.agent.domain.AgentExecutionMessageEntity();
            m.setTenantId("tenant-1");
            m.setContent(content);
            m.setContentStorageId(storageId);
            m.setContentLength(length);
            return m;
        }

        @Test
        @DisplayName("BUG: a long message gets its full text, detached FIRST so the row keeps its excerpt; a short one is untouched")
        void longMessageIsReadBackOnADetachedEntity() {
            UUID stored = UUID.randomUUID();
            var longOne = message(EXCERPT, stored, 12_748);
            var shortOne = message("hello", null, 5);
            when(entityManager.contains(longOne)).thenReturn(true);
            when(storage.getByIdReadOnly(stored, "tenant-1")).thenReturn(Optional.of("FULL TEXT"));

            pageLoader.readBackMessages(java.util.List.of(longOne, shortOne));

            assertThat(longOne.getContent()).isEqualTo("FULL TEXT");
            assertThat(shortOne.getContent()).isEqualTo("hello");
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(entityManager, storage);
            order.verify(storage).getByIdReadOnly(stored, "tenant-1");
            order.verify(entityManager).detach(longOne);
            verify(entityManager, never()).detach(shortOne);
        }

        @Test
        @DisplayName("a page reads back at most its budget: past it, the remaining long rows keep their excerpt")
        void pageBudgetBoundsOneResponse() {
            int big = TraceContentLoader.MAX_MESSAGE_CONTENT_CHARS;
            java.util.List<com.apimarketplace.agent.domain.AgentExecutionMessageEntity> rows = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                rows.add(message("excerpt-" + i + "...[truncated]", UUID.randomUUID(), big));
            }
            when(storage.getByIdReadOnly(any(), any())).thenReturn(Optional.of("y".repeat(big)));

            pageLoader.readBackMessages(rows);

            long readBack = rows.stream().filter(m -> m.getContent().length() == big).count();
            assertThat(readBack * big).isLessThanOrEqualTo(TraceContentLoader.MAX_PAGE_READ_BACK_CHARS);
            assertThat(rows.get(5).getContent()).isEqualTo("excerpt-5...[truncated]");
        }

        @Test
        @DisplayName("a row with no recorded length is read back within the per-row limit")
        void unknownLengthIsReadBack() {
            UUID stored = UUID.randomUUID();
            var legacy = message(EXCERPT, stored, null);
            when(storage.getByIdReadOnly(stored, "tenant-1")).thenReturn(Optional.of("LEGACY FULL"));

            pageLoader.readBackMessages(java.util.List.of(legacy));

            assertThat(legacy.getContent()).isEqualTo("LEGACY FULL");
        }

        @Test
        @DisplayName("a tool call is read back up to its own, lower, limit; past it the excerpt stays and nothing is loaded")
        void toolCallUsesItsOwnLimit() {
            UUID fits = UUID.randomUUID();
            UUID tooBig = UUID.randomUUID();
            var small = new com.apimarketplace.agent.domain.AgentExecutionToolCallEntity();
            small.setTenantId("tenant-1");
            small.setContent(EXCERPT);
            small.setContentStorageId(fits);
            small.setContentLength(20_000);
            var huge = new com.apimarketplace.agent.domain.AgentExecutionToolCallEntity();
            huge.setTenantId("tenant-1");
            huge.setContent(EXCERPT);
            huge.setContentStorageId(tooBig);
            huge.setContentLength(TraceContentLoader.MAX_TOOL_CALL_CONTENT_CHARS + 1);
            when(storage.getByIdReadOnly(fits, "tenant-1")).thenReturn(Optional.of("FULL RESULT"));

            pageLoader.readBackToolCalls(java.util.List.of(small, huge));

            assertThat(small.getContent()).isEqualTo("FULL RESULT");
            assertThat(huge.getContent()).isEqualTo(EXCERPT);
            verify(storage, never()).getByIdReadOnly(tooBig, "tenant-1");
        }

        @Test
        @DisplayName("with no persistence context the row is still read back (detaching is skipped, not failed)")
        void noEntityManagerStillReadsBack() {
            UUID stored = UUID.randomUUID();
            var row = message(EXCERPT, stored, 9_000);
            when(storage.getByIdReadOnly(stored, "tenant-1")).thenReturn(Optional.of("FULL"));

            loader.readBackMessages(java.util.List.of(row));

            assertThat(row.getContent()).isEqualTo("FULL");
        }
    }
}
