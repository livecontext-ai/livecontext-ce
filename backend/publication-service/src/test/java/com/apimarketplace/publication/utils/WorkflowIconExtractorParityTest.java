package com.apimarketplace.publication.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity guard for the publication-service copy of
 * {@code WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID}.
 *
 * <p>The orchestrator-service has a byte-identical twin of this map and a
 * sibling test (also named {@code WorkflowIconExtractorParityTest}) pinning
 * the same canonical mapping. Both tests live in their own modules so a drift
 * in either copy fails its own build. If you change one, you MUST change the
 * other AND the frontend mirror in {@code triggerNodeIcons.ts}
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
                  + "KIND_TO_NODE_ICON_KEY in triggerNodeIcons.ts together.")
                .isEqualTo(CANONICAL_TRIGGER_TYPE_TO_NODE_ID);
    }

    /**
     * The five AI node types and the glyph each one draws.
     *
     * <p>Canonical, and asserted against BOTH copies of the extractor (this
     * module and the twin in the other service). The map decides the icon on
     * every workflow card and every marketplace listing, and a type it does not
     * name falls back to "ai-agent" rather than failing: a generation step drew
     * itself as an LLM agent for as long as the entry was missing, and nothing
     * anywhere said so.
     *
     * <p>Adding a sixth AI node means adding a row here and in both extractors.
     */
    private static final Map<String, String> CANONICAL_AGENT_TYPE_TO_NODE_ID = Map.of(
            "agent", "ai-agent",
            "browser_agent", "browser_agent",
            "classify", "classify",
            "guardrail", "guardrail",
            "generate", "generate"
    );

    @Test
    @DisplayName("The agent-type icon map names all five AI nodes, each with its own glyph")
    void agentTypeIconMapMatchesCanonical() {
        assertThat(WorkflowIconExtractor.AGENT_TYPE_TO_NODE_ID)
                .as("A type missing here silently draws the ai-agent glyph, so the card is "
                  + "wrong and nothing fails. Update this map, this extractor AND the twin "
                  + "in the other service.")
                .isEqualTo(CANONICAL_AGENT_TYPE_TO_NODE_ID);
    }

    @Test
    @DisplayName("No two AI node types share a glyph, which would make them indistinguishable on a card")
    void everyAiNodeHasItsOwnGlyph() {
        assertThat(Set.copyOf(WorkflowIconExtractor.AGENT_TYPE_TO_NODE_ID.values()))
                .hasSize(WorkflowIconExtractor.AGENT_TYPE_TO_NODE_ID.size());
    }
}
