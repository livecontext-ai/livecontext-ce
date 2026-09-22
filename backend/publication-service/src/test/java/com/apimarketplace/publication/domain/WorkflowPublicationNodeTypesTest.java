package com.apimarketplace.publication.domain;

import com.apimarketplace.publication.dto.PublicationListItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a publication's node types reach the applications list.
 *
 * <p>Two hops, each able to fail silently: the entity has to derive the tokens
 * from its plan snapshot (three call sites write that snapshot and only one of
 * them ever wrote {@code nodeIcons}), and the list DTO has to parse the raw
 * JSONB text back into a list the browser can filter on. A break in either hop
 * shows up as an application quietly missing from a filtered page, never as an
 * error.
 */
@DisplayName("Publication node types")
class WorkflowPublicationNodeTypesTest {

    private static Map<String, Object> planSnapshot() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("mcps", List.of(Map.of("id", "gmail/send")));
        plan.put("cores", List.of(Map.of("type", "loop")));
        plan.put("interfaces", List.of(Map.of("id", "interface:page")));
        return plan;
    }

    @Nested
    @DisplayName("entity")
    class Entity {

        @Test
        @DisplayName("derives its tokens from the plan snapshot")
        void derivesFromPlanSnapshot() {
            WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
            publication.setPlanSnapshot(planSnapshot());

            assertThat(publication.getNodeTypes())
                    .containsExactly("core:loop", "interface", "mcp:gmail");
        }

        @Test
        @DisplayName("reports no tokens rather than null when there is no snapshot")
        void noSnapshotIsEmptyNotNull() {
            assertThat(new WorkflowPublicationEntity().getNodeTypes()).isEmpty();
        }

        @Test
        @DisplayName("re-derives after the snapshot is replaced, so a re-publish cannot leave it stale")
        void followsAReplacedSnapshot() {
            WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
            publication.setPlanSnapshot(planSnapshot());

            Map<String, Object> replacement = new HashMap<>();
            replacement.put("mcps", List.of(Map.of("id", "slack/post")));
            publication.setPlanSnapshot(replacement);

            assertThat(publication.getNodeTypes()).containsExactly("mcp:slack");
        }
    }

    @Nested
    @DisplayName("list DTO")
    class ListDto {

        private PublicationListItem itemWithNodeTypes(String rawJson) {
            return new PublicationListItem(
                    UUID.randomUUID(), "WORKFLOW", UUID.randomUUID(), null,
                    "T", "D", null, null, "WORKFLOW",
                    0, "publisher-x", "Pub", null, null,
                    "ACTIVE", "PUBLIC", "USER", "publisher-x", 0, 0, null,
                    null, 0, 0, 0, 0, 0, null, 0,
                    Instant.now(), Instant.now(),
                    null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, false, null,
                    false, rawJson);
        }

        @Test
        @DisplayName("parses the raw JSONB text into the list the browser filters on")
        void parsesRawJson() {
            Map<String, Object> response = itemWithNodeTypes("[\"mcp:gmail\",\"core:loop\"]").toResponseMap();

            assertThat(response).containsEntry("nodeTypes", List.of("mcp:gmail", "core:loop"));
        }

        @Test
        @DisplayName("a row with no tokens answers an empty list, never null")
        void nullBecomesEmptyList() {
            // Null would force every reader to guard a collection, and one that
            // forgot would crash the applications page on a legacy row.
            assertThat(itemWithNodeTypes(null).toResponseMap()).containsEntry("nodeTypes", List.of());
        }

        @Test
        @DisplayName("unparseable JSON degrades to an empty list instead of breaking the page")
        void malformedJsonDegrades() {
            assertThat(itemWithNodeTypes("{not json").toResponseMap())
                    .containsEntry("nodeTypes", List.of());
        }
    }
}
