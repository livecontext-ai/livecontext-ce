package com.apimarketplace.orchestrator.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the catalog's `mcp` icon-slug sentinel.
 *
 * <p>Every WorkflowInspectorService query selects `COALESCE(a.icon_slug, 'mcp')`,
 * so an API with no icon of its own reports the literal slug `mcp`. That value
 * is non-blank, so the extractor's "prefer the explicit iconSlug" branch used to
 * accept it and bake it into `publication.node_icons`. The marketplace card then
 * resolved /icons/services/mcp.svg (a generic "API" circle) as a SUCCESS, so the
 * MCP-logo fallback never fired either and the brand logo was unreachable.
 *
 * <p>Twin of the publication-service test of the same name; both extractors must
 * behave identically (see WorkflowIconExtractorParityTest).
 */
@DisplayName("WorkflowIconExtractor - catalog 'mcp' icon-slug sentinel")
class WorkflowIconExtractorMcpSlugTest {

    private static Map<String, Object> planWithMcp(String id, String iconSlug) {
        Map<String, Object> mcp = new java.util.HashMap<>();
        mcp.put("id", id);
        if (iconSlug != null) mcp.put("iconSlug", iconSlug);
        return Map.of("mcps", List.of(mcp));
    }

    @SuppressWarnings("unchecked")
    private static String firstIconSlug(Map<String, Object> plan) {
        List<Map<String, Object>> icons = WorkflowIconExtractor.extractNodeIcons(plan);
        assertThat(icons).hasSize(1);
        return (String) icons.get(0).get("iconSlug");
    }

    @Test
    @DisplayName("Sentinel 'mcp' is ignored in favour of the apiSlug parsed from the tool id")
    void sentinelFallsBackToApiSlug() {
        assertThat(firstIconSlug(planWithMcp("slack/post_message", "mcp"))).isEqualTo("slack");
    }

    @Test
    @DisplayName("Sentinel detection is case- and whitespace-insensitive")
    void sentinelDetectionIsLenient() {
        assertThat(firstIconSlug(planWithMcp("notion/create_page", "MCP"))).isEqualTo("notion");
        assertThat(firstIconSlug(planWithMcp("notion/create_page", " mcp "))).isEqualTo("notion");
    }

    @Test
    @DisplayName("A real explicit iconSlug still wins over the apiSlug")
    void realExplicitSlugWins() {
        assertThat(firstIconSlug(planWithMcp("google/sheets_append", "googlesheets")))
                .isEqualTo("googlesheets");
    }

    @Test
    @DisplayName("A slug that merely contains 'mcp' is NOT treated as the sentinel")
    void similarSlugIsNotTheSentinel() {
        assertThat(firstIconSlug(planWithMcp("acme/run", "mcpserver"))).isEqualTo("mcpserver");
    }

    @Test
    @DisplayName("Missing or blank iconSlug keeps the existing apiSlug fallback")
    void blankSlugFallsBackToApiSlug() {
        assertThat(firstIconSlug(planWithMcp("stripe/create_charge", null))).isEqualTo("stripe");
        assertThat(firstIconSlug(planWithMcp("stripe/create_charge", "  "))).isEqualTo("stripe");
    }

    @Test
    @DisplayName("A tool id with no slash uses the whole id as the apiSlug")
    void idWithoutSlashUsesWholeId() {
        assertThat(firstIconSlug(planWithMcp("github", "mcp"))).isEqualTo("github");
    }
}
