package com.apimarketplace.publication.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity guard for the publication-service copy of
 * {@code WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID}.
 *
 * <p>The orchestrator-service has a byte-identical twin of this map and a
 * sibling test (also named {@code WorkflowIconExtractorParityTest}) pinning
 * the same canonical mapping. Both tests live in their own modules so a drift
 * in either copy fails its own build. If you change one, you MUST change the
 * other AND the frontend mirror in {@code dashboard.service.ts}
 * ({@code KIND_TO_NODE_ICON_KEY}).
 */
@DisplayName("Publication-service WorkflowIconExtractor twin parity")
class WorkflowIconExtractorParityTest {

    private static final Map<String, String> CANONICAL_TRIGGER_TYPE_TO_NODE_ID = Map.of(
            "manual", "manual-trigger",
            "webhook", "webhook-trigger",
            "schedule", "schedule-trigger",
            "datasource", "tables-trigger",
            "chat", "chat-trigger",
            "form", "form-trigger",
            "workflow", "workflows-trigger",
            "error", "error-trigger"
    );

    @Test
    @DisplayName("Publication-service WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID matches canonical 8-entry map")
    void publicationExtractorMatchesCanonical() {
        assertThat(WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID)
                .as("If this fails, the publication-service extractor drifted from the canonical 8-kind map. "
                  + "Update both extractor twins (orchestrator + publication) AND the frontend "
                  + "KIND_TO_NODE_ICON_KEY in dashboard.service.ts together.")
                .isEqualTo(CANONICAL_TRIGGER_TYPE_TO_NODE_ID);
    }
}
