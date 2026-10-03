package com.apimarketplace.publication.service;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.TableCopyException;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: a publication copy that failed (an error, a missing confirmation, a stalled paging)
 * answered an empty list, so a transient failure published an EMPTY table silently. A publish now
 * refuses with a retryable error; only the moderation view keeps the lenient empty copy.
 */
@DisplayName("PublicationTableCopies - strict for a publish, lenient for the review")
class PublicationTableCopiesTest {

    private final DataSourceClient client = mock(DataSourceClient.class);
    private final PublicationSnapshotBudget budget =
            new PublicationSnapshotBudget(new ObjectMapper(), PublicationSnapshotBudget.DEFAULT_MAX_BYTES, 2);

    private static List<DataSourceItemDto> rows(int count) {
        List<DataSourceItemDto> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(new DataSourceItemDto((long) i, 9L, "t", Map.of("n", i), 0, null));
        return rows;
    }

    private List<DataSourceItemDto> copy(PublicationSnapshotBudget.Listing listing) {
        return PublicationTableCopies.copy(client, budget, listing, 9L, "Orders", "t", "org");
    }

    @Test
    @DisplayName("regression (silent empty publish): a failed copy refuses the publish with a retryable TABLE_COPY_FAILED")
    void failedCopyRefusesThePublish() {
        when(client.copyAllItems(9L, "t", "org")).thenThrow(new TableCopyException(9L, "down", null));

        assertThatThrownBy(() -> copy(PublicationSnapshotBudget.Listing.TABLE))
                .isInstanceOfSatisfying(PublicationValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PublicationValidationException.TABLE_COPY_FAILED);
                    assertThat(e.getDetails()).containsEntry("retryable", true).containsEntry("tableId", "9")
                            .containsEntry("tableName", "Orders");
                    assertThat(e.getMessage()).isEqualTo("The rows of table 'Orders' (id 9) could not be read just "
                            + "now, so nothing was published. This is temporary: publish again in a moment.");
                });
    }

    @Test
    @DisplayName("a table that IS empty copies as empty: not a failure")
    void emptyTableIsAnEmptyCopy() {
        when(client.copyAllItems(9L, "t", "org")).thenReturn(List.of());

        assertThat(copy(PublicationSnapshotBudget.Listing.TABLE)).isEmpty();
    }

    @Test
    @DisplayName("the row budget is checked at copy time, with the table's real id")
    void rowBudgetIsCheckedWithTheRealId() {
        when(client.copyAllItems(9L, "t", "org")).thenReturn(rows(3));

        assertThatThrownBy(() -> copy(PublicationSnapshotBudget.Listing.INTERFACE))
                .isInstanceOfSatisfying(PublicationValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PublicationValidationException.PUBLICATION_SNAPSHOT_TOO_LARGE);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> breakdown = (List<Map<String, Object>>) e.getDetails().get("breakdown");
                    assertThat(breakdown.get(0)).containsEntry("id", "9").containsEntry("items", 3);
                });
    }

    @Test
    @DisplayName("a forListing scope decides the refusal code of every copy inside it (an agent's embedded workflow)")
    void listingScopeOverridesTheDefault() {
        when(client.copyAllItems(9L, "t", "org")).thenReturn(rows(3));

        assertThatThrownBy(() -> PublicationTableCopies.forListing(PublicationSnapshotBudget.Listing.AGENT,
                () -> copy(PublicationSnapshotBudget.Listing.WORKFLOW)))
                .isInstanceOfSatisfying(PublicationValidationException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PublicationValidationException.AGENT_SNAPSHOT_TOO_LARGE));
    }

    @Test
    @DisplayName("the review keeps the lenient copy (empty on failure) and does not enforce the budget")
    void reviewIsLenientAndUnbudgeted() {
        when(client.getAllItems(9L, "t", "org")).thenReturn(rows(3));

        List<DataSourceItemDto> copied = PublicationTableCopies.forReview(() -> copy(PublicationSnapshotBudget.Listing.TABLE));

        assertThat(copied).hasSize(3);
        verify(client, never()).copyAllItems(any(), any(), any());
    }

    @Test
    @DisplayName("a scope ends with its body, even when the body throws: the next publish is strict again")
    void scopeIsRestoredAfterAnException() {
        when(client.copyAllItems(9L, "t", "org")).thenThrow(new TableCopyException(9L, "down", null));

        assertThatThrownBy(() -> PublicationTableCopies.forReview(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> copy(PublicationSnapshotBudget.Listing.TABLE))
                .isInstanceOf(PublicationValidationException.class);
    }
}
