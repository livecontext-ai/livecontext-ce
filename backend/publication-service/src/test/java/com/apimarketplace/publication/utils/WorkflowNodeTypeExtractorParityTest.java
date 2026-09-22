package com.apimarketplace.publication.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-service parity guard for the node-type token grammar.
 *
 * <p>{@code WorkflowNodeTypeExtractor} exists twice - here (writing
 * {@code workflow_publications.node_types} at publish time) and in
 * orchestrator-service (deriving them from the plan the workflows list has
 * already loaded, with no column of its own). The two must
 * produce byte-identical tokens: the applications list filters publications
 * while the workflows list filters workflows, and the SAME picker option has to
 * mean the same thing on both pages. Drift would show up as an application that
 * a filter finds on one page and not the other, with no error anywhere.
 *
 * <p>The orchestrator-service twin runs the same fixture against its own copy.
 * Changing the grammar means changing this fixture, both extractors, and the
 * frontend token helper in {@code lib/workflows/nodeTypeTokens.ts}.
 */
@DisplayName("Node-type token grammar parity (publication copy)")
class WorkflowNodeTypeExtractorParityTest {

    /**
     * One plan exercising every branch and every naming quirk of the grammar:
     * the iconSlug/apiSlug fallback, the rejected 'mcp' placeholder, the
     * "crud-" strip, the implicit agent type, and the subtype-less interface.
     */
    static Map<String, Object> canonicalPlan() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of(
                Map.of("type", "webhook"),
                Map.of("type", "schedule")));
        plan.put("mcps", List.of(
                Map.of("id", "gmail_api/send", "iconSlug", "gmail"),
                Map.of("id", "slack/post_message"),
                Map.of("id", "notion/query", "iconSlug", "mcp")));
        plan.put("cores", List.of(
                Map.of("type", "loop"),
                Map.of("type", "code")));
        plan.put("agents", List.of(
                Map.of("type", "guardrail", "provider", "openai"),
                Map.of("provider", "anthropic")));
        plan.put("tables", List.of(
                Map.of("type", "crud-create-row")));
        plan.put("interfaces", List.of(
                Map.of("id", "interface:page")));
        return plan;
    }

    /** The tokens both copies must produce, sorted as the extractors sort them. */
    static final List<String> CANONICAL_TOKENS = List.of(
            "agent:agent",
            "agent:guardrail",
            "core:code",
            "core:loop",
            "interface",
            "mcp:gmail",
            "mcp:notion",
            "mcp:slack",
            "table:create-row",
            "trigger:schedule",
            "trigger:webhook");

    @Test
    @DisplayName("publication extractor produces the canonical tokens")
    void publicationMatchesCanonical() {
        assertThat(WorkflowNodeTypeExtractor.extractNodeTypes(canonicalPlan()))
                .as("If this fails, the publication token grammar drifted. Update this fixture, "
                  + "the orchestrator-service twin AND its parity test, and the frontend helper "
                  + "lib/workflows/nodeTypeTokens.ts which maps tokens back to labels and icons.")
                .isEqualTo(CANONICAL_TOKENS);
    }

    private static final Path THIS_COPY = Path.of(
            "src/main/java/com/apimarketplace/publication/utils/WorkflowNodeTypeExtractor.java");
    private static final Path TWIN_COPY = Path.of(
            "../orchestrator-service/src/main/java/com/apimarketplace/orchestrator/services/WorkflowNodeTypeExtractor.java");

    @Test
    @DisplayName("the two copies of the extractor have a byte-identical class body")
    void bothCopiesShareTheSameBody() throws IOException {
        // The mirror of the orchestrator-side check, and the reason it is
        // duplicated rather than left in one module: each side's fixture test can
        // only prove its OWN copy, so a build that runs just this module would
        // otherwise never compare the two at all.
        assumeTrue(Files.exists(TWIN_COPY),
                "orchestrator-service sources not in this checkout - skipping cross-module comparison");

        assertThat(classBody(TWIN_COPY))
                .as("The two WorkflowNodeTypeExtractor copies diverged. They must stay identical "
                  + "below the class declaration: a token one produces and the other does not means "
                  + "the same node is filterable on one list and invisible on the other, with no "
                  + "error anywhere. Copy the body across.")
                .isEqualTo(classBody(THIS_COPY));
    }

    /**
     * The source from the class declaration onward: everything but the package
     * line and the file javadoc, which legitimately differ between the copies.
     */
    private static String classBody(Path path) throws IOException {
        String source = Files.readString(path).replace("\r\n", "\n");
        int start = source.indexOf("public final class WorkflowNodeTypeExtractor {");
        assertThat(start).as("class declaration not found in %s", path).isNotNegative();
        return source.substring(start);
    }
}
