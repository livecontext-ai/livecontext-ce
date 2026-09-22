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
 * <p><b>2026-06-18 - added LANDING.</b> LANDING is NOT a publishable type (no
 * resource strategy emits it; the publish wizard's type union is unchanged). It
 * exists only as a highlight-bucket key for the curated public-landing-page row
 * - see {@code PublicationHighlightService} and the V347 migration that extends
 * the CHECK constraint with 'LANDING'.
 *
 * <p><b>2026-09-16 - added the six persona buckets.</b> LANDING_OPS,
 * LANDING_CREATOR, LANDING_SUPPORT, LANDING_SALES, LANDING_MARKETING and
 * LANDING_RECRUITING bring the enum to 13 values. Same nature as LANDING: bucket
 * keys, never a publication's own type, each holding APPLICATION publications so
 * one /for/&lt;persona&gt; page can be curated apart from the home page. V490 extends
 * the CHECK constraint with the six and seeds them from the LANDING row.
 */
@DisplayName("DisplayMode enum - EXPERIENCE removal regression")
class DisplayModeEnumTest {

    @Test
    @DisplayName("Enum contains exactly 13 modes - the 6 publishable types + the 7 landing buckets - no EXPERIENCE")
    void enumContainsExactlyExpectedModesWithoutExperience() {
        // The 6 publishable types (one per resource strategy), plus the bucket-only keys:
        // LANDING for the home page's curated row and one per persona page (V490).
        String[] expected = { "WORKFLOW", "INTERFACE", "APPLICATION", "AGENT", "TABLE", "SKILL", "LANDING",
                "LANDING_OPS", "LANDING_CREATOR", "LANDING_SUPPORT", "LANDING_SALES",
                "LANDING_MARKETING", "LANDING_RECRUITING" };

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
