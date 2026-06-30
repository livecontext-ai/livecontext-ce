package com.apimarketplace.orchestrator.stepdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link StepStatusFilter}, the helper that maps the
 * canonical {@code StatusType} value the frontend sends (see
 * {@code StatusBadge.tsx#mapBackendStatusToStatusType}) to the raw DB
 * {@code WorkflowStepDataEntity.status} values that should match.
 */
@DisplayName("StepStatusFilter")
class StepStatusFilterTest {

    @Nested
    @DisplayName("when filterValue is null/blank")
    class NullOrBlankFilter {

        @Test
        @DisplayName("Returns true so callers can pass an unspecified filter through unchanged")
        void nullFilterAcceptsAllStatuses() {
            assertThat(StepStatusFilter.matches("completed", null)).isTrue();
            assertThat(StepStatusFilter.matches("anything", null)).isTrue();
            assertThat(StepStatusFilter.matches(null, null)).isTrue();
        }

        @Test
        @DisplayName("Treats blank/whitespace-only filter the same as null")
        void blankFilterAcceptsAllStatuses() {
            assertThat(StepStatusFilter.matches("completed", "")).isTrue();
            assertThat(StepStatusFilter.matches("completed", "   ")).isTrue();
        }
    }

    @Nested
    @DisplayName("when filterValue is set")
    class ActiveFilter {

        @Test
        @DisplayName("Rejects a null dbStatus when an explicit filter is requested")
        void nullDbStatusRejectedUnderActiveFilter() {
            assertThat(StepStatusFilter.matches(null, "completed")).isFalse();
        }

        @Test
        @DisplayName("Canonical 'completed' matches both 'completed' and 'success' raw values")
        void completedExpandsToSuccess() {
            assertThat(StepStatusFilter.matches("completed", "completed")).isTrue();
            assertThat(StepStatusFilter.matches("success", "completed")).isTrue();
        }

        @Test
        @DisplayName("Canonical 'failed' matches both 'failed' and 'error' raw values")
        void failedExpandsToError() {
            assertThat(StepStatusFilter.matches("failed", "failed")).isTrue();
            assertThat(StepStatusFilter.matches("error", "failed")).isTrue();
        }

        @Test
        @DisplayName("Other canonical values match only themselves (no expansion)")
        void otherStatusesMatchExactly() {
            assertThat(StepStatusFilter.matches("running", "running")).isTrue();
            assertThat(StepStatusFilter.matches("pending", "pending")).isTrue();
            assertThat(StepStatusFilter.matches("skipped", "skipped")).isTrue();
            assertThat(StepStatusFilter.matches("cancelled", "cancelled")).isTrue();
            assertThat(StepStatusFilter.matches("timeout", "timeout")).isTrue();
            assertThat(StepStatusFilter.matches("partial_success", "partial_success")).isTrue();
        }

        @Test
        @DisplayName("Rejects raw values that don't belong to the canonical bucket")
        void rejectsUnrelatedRawValues() {
            assertThat(StepStatusFilter.matches("running", "completed")).isFalse();
            assertThat(StepStatusFilter.matches("success", "failed")).isFalse();
            assertThat(StepStatusFilter.matches("error", "completed")).isFalse();
            assertThat(StepStatusFilter.matches("pending", "running")).isFalse();
        }

        @Test
        @DisplayName("Match is case-insensitive on both filter and dbStatus")
        void caseInsensitive() {
            assertThat(StepStatusFilter.matches("COMPLETED", "completed")).isTrue();
            assertThat(StepStatusFilter.matches("Success", "Completed")).isTrue();
            assertThat(StepStatusFilter.matches("ERROR", "FAILED")).isTrue();
        }

        @Test
        @DisplayName("Whitespace around filterValue is trimmed")
        void trimsFilterWhitespace() {
            assertThat(StepStatusFilter.matches("completed", "  completed  ")).isTrue();
            assertThat(StepStatusFilter.matches("success", " completed ")).isTrue();
        }

        @Test
        @DisplayName("Pending bucket matches frontend mapBackendStatusToStatusType fallback bucket - pending/ready/awaiting_signal/waiting_trigger/collecting all roundtrip")
        void pendingBucketMatchesFrontendFallbackBucket() {
            // The frontend's mapBackendStatusToStatusType collapses these raw
            // values to canonical "pending" (default fallback). Without
            // expansion, picking "pending" in the dropdown returns 0 hits for
            // any row whose raw status is e.g. "awaiting_signal" - silent UX
            // failure where the dropdown lists a value that won't roundtrip.
            assertThat(StepStatusFilter.matches("pending", "pending")).isTrue();
            assertThat(StepStatusFilter.matches("ready", "pending")).isTrue();
            assertThat(StepStatusFilter.matches("awaiting_signal", "pending")).isTrue();
            assertThat(StepStatusFilter.matches("waiting_trigger", "pending")).isTrue();
            assertThat(StepStatusFilter.matches("collecting", "pending")).isTrue();
        }
    }

    @Nested
    @DisplayName("expandToRawList")
    class ExpandToRawListTests {

        @Test
        @DisplayName("Returns empty list for null/blank filter so callers can short-circuit the IN clause")
        void emptyOnNullOrBlank() {
            assertThat(StepStatusFilter.expandToRawList(null)).isEmpty();
            assertThat(StepStatusFilter.expandToRawList("")).isEmpty();
            assertThat(StepStatusFilter.expandToRawList("   ")).isEmpty();
        }

        @Test
        @DisplayName("Canonical 'completed' expands to both 'completed' and 'success'")
        void completedExpansion() {
            assertThat(StepStatusFilter.expandToRawList("completed"))
                .containsExactlyInAnyOrder("completed", "success");
        }

        @Test
        @DisplayName("Canonical 'failed' expands to both 'failed' and 'error'")
        void failedExpansion() {
            assertThat(StepStatusFilter.expandToRawList("failed"))
                .containsExactlyInAnyOrder("failed", "error");
        }

        @Test
        @DisplayName("Canonical 'pending' expands to all five fallback raw values")
        void pendingExpansion() {
            assertThat(StepStatusFilter.expandToRawList("pending"))
                .containsExactlyInAnyOrder("pending", "ready", "awaiting_signal", "waiting_trigger", "collecting");
        }

        @Test
        @DisplayName("Other values return a single-element list (no expansion)")
        void singletonExpansion() {
            assertThat(StepStatusFilter.expandToRawList("running")).containsExactly("running");
            assertThat(StepStatusFilter.expandToRawList("skipped")).containsExactly("skipped");
        }

        @Test
        @DisplayName("Trims whitespace and lowercases before expansion (matches matches() contract)")
        void trimsAndLowercases() {
            assertThat(StepStatusFilter.expandToRawList("  COMPLETED  "))
                .containsExactlyInAnyOrder("completed", "success");
        }

        @Test
        @DisplayName("Returned list is immutable so callers cannot mutate the cached singleton sets")
        void returnedListIsImmutable() {
            List<String> result = StepStatusFilter.expandToRawList("completed");
            assertThatThrownBy(() -> result.add("hacked"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
