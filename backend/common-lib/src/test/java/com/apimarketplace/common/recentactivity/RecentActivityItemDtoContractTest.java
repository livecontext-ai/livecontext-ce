package com.apimarketplace.common.recentactivity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link RecentActivityItemDto} - enforces the
 * semantic-invariant promised in the DTO javadoc that prevents Activity-tab
 * rows from being accidentally fed by {@code WorkflowEntity.lastExecutedAt}
 * (Part 1's "last fire" concept, used by the SCHEDULE row dual-label, NOT
 * Part 2's "last edit" feed).
 *
 * <p>If a future developer copies code from the SCHEDULE-row path that calls
 * {@code .lastExecutedAt(instant)} on the builder, that line MUST fail to
 * compile. This test pins the contract with reflection so the failure shows
 * up at test time (with an actionable message) instead of as a silent
 * IDE-completion miss months later.
 */
class RecentActivityItemDtoContractTest {

    @Test
    @DisplayName("Builder exposes .lastEditedAt(Instant) (the only allowed timestamp setter)")
    void builderHasLastEditedAtSetter() throws Exception {
        Method m = RecentActivityItemDto.Builder.class.getMethod("lastEditedAt", Instant.class);
        assertThat(m).isNotNull();
        assertThat(m.getReturnType()).isEqualTo(RecentActivityItemDto.Builder.class);
    }

    @Test
    @DisplayName("Builder does NOT expose .lastExecutedAt() - prevents semantic drift with Part 1's lastExecutedAt concept")
    void builderRejectsLastExecutedAtSetter() {
        // Reflection lookup: if a developer ever adds a .lastExecutedAt(Instant)
        // setter (e.g. by copying from the SCHEDULE-row path), this test
        // fails LOUDLY with a message naming the invariant.
        assertThatThrownBy(() ->
                RecentActivityItemDto.Builder.class.getMethod("lastExecutedAt", Instant.class))
                .isInstanceOf(NoSuchMethodException.class)
                .as("RecentActivityItemDto.Builder must NOT carry a lastExecutedAt setter - "
                        + "use lastEditedAt(updated_at) instead. See DTO javadoc invariant.");
    }

    @Test
    @DisplayName("DTO record field name is lastEditedAt (NOT updatedAt) - pins the JSON wire shape")
    void recordFieldIsNamedLastEditedAt() {
        boolean hasLastEditedAt = Arrays.stream(RecentActivityItemDto.class.getRecordComponents())
                .anyMatch(c -> c.getName().equals("lastEditedAt"));
        assertThat(hasLastEditedAt).isTrue();

        boolean hasUpdatedAt = Arrays.stream(RecentActivityItemDto.class.getRecordComponents())
                .anyMatch(c -> c.getName().equals("updatedAt"));
        assertThat(hasUpdatedAt)
                .as("DTO record component MUST be named lastEditedAt - "
                        + "renaming to updatedAt would re-introduce the semantic drift with "
                        + "WorkflowEntity.lastExecutedAt that the rename was designed to prevent.")
                .isFalse();
    }

    @Test
    @DisplayName("Build() rejects null kind / resourceId / lastEditedAt - partial-DTO contract")
    void builderRejectsMissingRequired() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> RecentActivityItemDto.builder()
                .resourceId("r").lastEditedAt(now).build())
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> RecentActivityItemDto.builder()
                .kind(ResourceKind.WORKFLOW).lastEditedAt(now).build())
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> RecentActivityItemDto.builder()
                .kind(ResourceKind.WORKFLOW).resourceId("r").build())
                .isInstanceOf(NullPointerException.class);

        // Sanity: complete builder produces a valid record.
        RecentActivityItemDto ok = RecentActivityItemDto.builder()
                .kind(ResourceKind.WORKFLOW).resourceId("r").lastEditedAt(now).build();
        assertThat(ok.kind()).isEqualTo(ResourceKind.WORKFLOW);
        assertThat(ok.resourceId()).isEqualTo("r");
        assertThat(ok.lastEditedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("publicationId carries through the builder and defaults to null (APPLICATION-only routing id)")
    void publicationIdCarriesThrough() {
        Instant now = Instant.now();

        // APPLICATION row carries the publication id used to route to
        // /app/applications/{publicationId}.
        RecentActivityItemDto app = RecentActivityItemDto.builder()
                .kind(ResourceKind.APPLICATION).resourceId("wf-1").lastEditedAt(now)
                .publicationId("pub-1").build();
        assertThat(app.publicationId()).isEqualTo("pub-1");

        // Every other kind leaves it null (the builder default).
        RecentActivityItemDto wf = RecentActivityItemDto.builder()
                .kind(ResourceKind.WORKFLOW).resourceId("wf-2").lastEditedAt(now).build();
        assertThat(wf.publicationId()).isNull();
    }
}
