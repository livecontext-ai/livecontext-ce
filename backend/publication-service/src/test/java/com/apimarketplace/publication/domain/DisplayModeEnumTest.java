package com.apimarketplace.publication.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard for the 2026-05-21 removal of {@code DisplayMode.EXPERIENCE}
 * (workstream B / V272). EXPERIENCE was a cross-tenant runtime path
 * (publisher's tenantId + participant's display name, free participation with
 * daily-limit gate) confirmed at 0 production usage before removal. The enum
 * value, the {@code ExperienceController}, the publisher-paid credit bypass,
 * and every UI surface were collapsed into APPLICATION mode.
 *
 * <p>This test pins the enum's exact value set so a future re-introduction of
 * "EXPERIENCE" or any rename of the remaining modes surfaces here loudly
 * rather than silently breaking the {@code pub_highlights_displaymode_check}
 * CHECK constraint or the V272 migration's reverse rollback path.
 *
 * <p><b>2026-06-18 - added LANDING.</b> LANDING is the 7th value but it is NOT a
 * publishable type (no resource strategy emits it; the publish wizard's type
 * union is unchanged). It exists only as a highlight-bucket key for the curated
 * public-landing-page row - see {@code PublicationHighlightService} and the V347
 * migration that extends the CHECK constraint with 'LANDING'.
 */
@DisplayName("DisplayMode enum - EXPERIENCE removal regression")
class DisplayModeEnumTest {

    @Test
    @DisplayName("Enum contains exactly 7 modes - the 6 publishable types + LANDING (bucket-only) - no EXPERIENCE")
    void enumContainsExactlyExpectedModesWithoutExperience() {
        // The 6 publishable types (one per resource strategy) + LANDING, the
        // highlight-bucket-only key for the curated public landing page.
        String[] expected = { "WORKFLOW", "INTERFACE", "APPLICATION", "AGENT", "TABLE", "SKILL", "LANDING" };

        String[] actual = Arrays.stream(WorkflowPublicationEntity.DisplayMode.values())
                .map(Enum::name)
                .toArray(String[]::new);

        assertThat(actual)
                .as("DisplayMode must match the pub_highlights_displaymode_check CHECK constraint "
                    + "exactly. Any drift here breaks publication_highlights INSERTs and the publish "
                    + "wizard's type union (frontend/lib/api/orchestrator/types.ts).")
                .containsExactlyInAnyOrder(expected);
    }

    @Test
    @DisplayName("Resolving EXPERIENCE by name throws IllegalArgumentException - proves the value is fully gone")
    void resolvingExperienceByNameThrows() {
        assertThatThrownBy(() -> WorkflowPublicationEntity.DisplayMode.valueOf("EXPERIENCE"))
                .as("If this assertion ever fails, EXPERIENCE has been silently re-added to "
                    + "the enum. The V272 migration and ExperienceController removal assume "
                    + "the value cannot resurface; a Hibernate @PostLoad of a stray "
                    + "display_mode='EXPERIENCE' row from a partial rollback would throw on "
                    + "every publication read otherwise.")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
