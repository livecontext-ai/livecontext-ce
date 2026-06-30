package com.apimarketplace.common.recentactivity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;

/**
 * One row in the /app/activity feed - a single resource (workflow, interface,
 * agent, skill, table, application) most-recently edited.
 *
 * <p>Produced by each downstream service's
 * {@code /api/internal/.../recent-activity} endpoint, deserialized by the
 * matching client jar in orchestrator-service, merged + sorted in
 * {@code RecentActivityAggregatorService}.
 *
 * <p><b>SEMANTIC INVARIANT - DO NOT MAP FROM {@code lastExecutedAt}:</b>
 * The {@link #lastEditedAt} field is the entity row's {@code updated_at}
 * column - last edit timestamp. It is <b>NOT</b> the same as
 * {@code WorkflowEntity.lastExecutedAt} which is Part 1's "last fire"
 * concept (used in the Triggers tab SCHEDULE row dual-label, not here).
 * Mixing the two breaks the Activity tab semantics (rows would jump
 * to the top on every run instead of only on edits).
 *
 * <p>The {@link Builder#lastEditedAt(Instant)} setter is named for this
 * contract - the {@code RecentActivityItemDtoContractTest} asserts no
 * {@code lastExecutedAt} setter exists to prevent silent swap by a future
 * dev who copies code from the SCHEDULE-row path.
 *
 * <p>{@code actorDisplayName} + {@code actorAvatarUrl} are <b>resolved
 * lazily</b> by the aggregator via
 * {@code AuthClient.batchResolveUsers(actorIds)} after merge, then
 * enriched in-place before the Redis cache write. Downstream services
 * emit only {@code actorId} (the {@code created_by} column - see note
 * on {@code updated_by} below). When the auth lookup fails or the user
 * was deleted, both fields stay null and the UI falls back to "Unknown".
 *
 * <p><b>Note on {@code created_by} vs {@code updated_by}:</b> none of the
 * 5 target entity tables (workflows, interfaces, agents, skills,
 * data_sources) carry an {@code updated_by} column today. The aggregator
 * surfaces {@code created_by} as the attribution - accurate for the
 * dominant case (single-author resources) but imprecise for collab
 * resources (the row says "Created by Alice" even when Bob just edited
 * it). A follow-up may add {@code updated_by} columns + UPDATE-time
 * stamping per service; until then, the frontend chip is labelled
 * "Created by me" not "Modified by me" to match the data we have.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecentActivityItemDto(
        ResourceKind kind,
        String resourceId,
        String name,
        Instant lastEditedAt,
        String actorId,
        String actorDisplayName,
        String actorAvatarUrl,
        /**
         * APPLICATION-only: the {@code source_publication_id} of the backing
         * workflow. The application page is keyed by PUBLICATION id
         * ({@code /app/applications/{publicationId}}), NOT workflow id, so the
         * frontend needs this to route an APPLICATION row correctly - without
         * it the row links to {@code /app/applications/{workflowId}} which
         * fails to load. Null for every non-APPLICATION kind (and for legacy
         * APPLICATION rows with no publication, where the UI falls back to the
         * workflow editor). Mirrors {@code ActiveAutomationDto.publicationId}
         * on the Triggers tab.
         */
        String publicationId
) {

    /**
     * Builder for {@link RecentActivityItemDto}. The setter is deliberately
     * named {@code lastEditedAt} (not {@code updatedAt}) so a future copy-paste
     * from the SCHEDULE-row path that calls {@code .lastExecutedAt(...)} fails
     * to compile. Pinned by {@code RecentActivityItemDtoContractTest}.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ResourceKind kind;
        private String resourceId;
        private String name;
        private Instant lastEditedAt;
        private String actorId;
        private String actorDisplayName;
        private String actorAvatarUrl;
        private String publicationId;

        private Builder() {
        }

        public Builder kind(ResourceKind kind) {
            this.kind = kind;
            return this;
        }

        public Builder resourceId(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * MUST be the entity row's {@code updated_at} (last-edit time).
         * NEVER pass {@code WorkflowEntity.lastExecutedAt} here - that's
         * Part 1's "last fire" concept, used by the SCHEDULE row dual-label,
         * not the Activity tab feed. Mixing them re-orders the tab on every
         * trigger fire instead of only on edits.
         */
        public Builder lastEditedAt(Instant lastEditedAt) {
            this.lastEditedAt = lastEditedAt;
            return this;
        }

        public Builder actorId(String actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder actorDisplayName(String actorDisplayName) {
            this.actorDisplayName = actorDisplayName;
            return this;
        }

        public Builder actorAvatarUrl(String actorAvatarUrl) {
            this.actorAvatarUrl = actorAvatarUrl;
            return this;
        }

        /**
         * APPLICATION-only publication id used to route the row to
         * {@code /app/applications/{publicationId}}. Leave unset (null) for
         * every other {@link ResourceKind}.
         */
        public Builder publicationId(String publicationId) {
            this.publicationId = publicationId;
            return this;
        }

        public RecentActivityItemDto build() {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(lastEditedAt, "lastEditedAt");
            return new RecentActivityItemDto(
                    kind, resourceId, name, lastEditedAt,
                    actorId, actorDisplayName, actorAvatarUrl, publicationId);
        }
    }
}
