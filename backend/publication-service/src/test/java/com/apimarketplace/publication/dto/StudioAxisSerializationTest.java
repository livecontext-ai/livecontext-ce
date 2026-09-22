package com.apimarketplace.publication.dto;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The studio axis, from the row to the JSON a client actually reads.
 *
 * <p><b>Why this needs its own suite.</b> Selecting the column and mapping it into a DTO is only
 * half the journey: the value still has to be WRITTEN into the response map, and a highlight row has
 * to carry it too. Both of those steps are a single line that no assertion touched - deleting either
 * one left every publication reading `studio: false` at the client, with HTTP 200, a correct
 * database and a Studio shelf that is simply, permanently empty. That is the failure mode this whole
 * feature is most exposed to: nothing throws, nothing logs, and the page looks like a fresh install.
 *
 * <p>Both directions are asserted every time. A serializer hardcoded to `false` passes any test that
 * only checks the false case, and a serializer hardcoded to `true` passes any test that only checks
 * the true one.
 */
@DisplayName("The studio axis survives serialization")
class StudioAxisSerializationTest {

    @Nested
    @DisplayName("PublicationListItem.toResponseMap")
    class ListItem {

        @Test
        @DisplayName("reports a studio application as one")
        void carriesTrue() {
            assertThat(itemWithStudio(true).toResponseMap()).containsEntry("studio", true);
        }

        @Test
        @DisplayName("reports an ordinary application as not one")
        void carriesFalse() {
            // The other direction, so a hardcoded `true` cannot pass: it would put every
            // application on the studio shelf, which is the same defect wearing the other hat.
            assertThat(itemWithStudio(false).toResponseMap()).containsEntry("studio", false);
        }

        @Test
        @DisplayName("says so explicitly rather than omitting the key")
        void isAlwaysPresent() {
            // An absent key and `false` are the same thing to a JavaScript client reading
            // `card.studio`, but not to one that distinguishes "not a studio app" from "this build
            // does not know about the axis" - which is exactly what a self-hosted install talking to
            // a cloud of another version has to do.
            Map<String, Object> response = itemWithStudio(false).toResponseMap();
            assertThat(response).containsKey("studio");
        }

        private PublicationListItem itemWithStudio(boolean studio) {
            return new PublicationListItem(
                    UUID.randomUUID(), "WORKFLOW", null, null, "A title", "A description",
                    null, null, "APPLICATION", 0,
                    "publisher-1", "Publisher", null, null,
                    "ACTIVE", "PUBLIC", "USER", "publisher-1", 0, 0,
                    1, null, 0, 0, 0, 0, 0, 0.0, 0,
                    null, null,
                    null, "content", "Content", null, null,
                    null, null, null, null, null, null,
                    null, null, false, null, studio, null);
        }
    }

    @Nested
    @DisplayName("PublicHighlightItem.from")
    class Highlight {

        @Test
        @DisplayName("reads the axis off the row instead of assuming it")
        void carriesTrue() {
            assertThat(PublicHighlightItem.from(entityWithStudio(true)).studio()).isTrue();
        }

        @Test
        @DisplayName("reports an ordinary publication as not a studio one")
        void carriesFalse() {
            assertThat(PublicHighlightItem.from(entityWithStudio(false)).studio()).isFalse();
        }

        private WorkflowPublicationEntity entityWithStudio(boolean studio) {
            WorkflowPublicationEntity entity = new WorkflowPublicationEntity();
            entity.setTitle("A title");
            entity.setStudio(studio);
            return entity;
        }
    }

    @Nested
    @DisplayName("the entity default")
    class EntityDefault {

        @Test
        @DisplayName("a new publication is NOT a studio one until somebody says so")
        void defaultsToFalse() {
            // The column is NOT NULL with a false default, and the field mirrors it. Dropping the
            // field initialiser is invisible for a row loaded from the database and wrong for every
            // row built in memory - which includes every publication created before the axis
            // existed, and every one a test constructs.
            assertThat(new WorkflowPublicationEntity().isStudio()).isFalse();
        }
    }
}
