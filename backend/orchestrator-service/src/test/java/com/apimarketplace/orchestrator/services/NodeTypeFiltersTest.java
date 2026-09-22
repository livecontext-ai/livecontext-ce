package com.apimarketplace.orchestrator.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Request parsing, matching and faceting for the node-type filter.
 *
 * <p>The REST list and the MCP list action share this class precisely so that a
 * user ticking "Gmail" and an agent passing {@code node_types=['mcp:gmail']}
 * select the same rows - the parsing tests below pin both entry shapes.
 */
@DisplayName("NodeTypeFilters")
class NodeTypeFiltersTest {

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("reads the REST shape: a comma-separated string")
        void parsesCsv() {
            assertThat(NodeTypeFilters.parse("mcp:gmail,core:loop"))
                    .containsExactly("mcp:gmail", "core:loop");
        }

        @Test
        @DisplayName("reads the MCP shape: a list of strings")
        void parsesList() {
            assertThat(NodeTypeFilters.parse(List.of("mcp:gmail", "core:loop")))
                    .containsExactly("mcp:gmail", "core:loop");
        }

        @Test
        @DisplayName("lowercases, so MCP:Gmail and mcp:gmail select the same rows")
        void lowercases() {
            assertThat(NodeTypeFilters.parse("MCP:Gmail")).containsExactly("mcp:gmail");
        }

        @Test
        @DisplayName("trims and drops blank entries left by a trailing comma")
        void dropsBlanks() {
            assertThat(NodeTypeFilters.parse(" mcp:gmail , , core:loop ,"))
                    .containsExactly("mcp:gmail", "core:loop");
        }

        @Test
        @DisplayName("deduplicates repeated tokens")
        void deduplicates() {
            assertThat(NodeTypeFilters.parse("mcp:gmail,mcp:gmail")).containsExactly("mcp:gmail");
        }

        @Test
        @DisplayName("null means no filter")
        void nullIsNoFilter() {
            assertThat(NodeTypeFilters.parse(null)).isEmpty();
        }

        @Test
        @DisplayName("an empty string means no filter, not a filter on nothing")
        void emptyStringIsNoFilter() {
            assertThat(NodeTypeFilters.parse("")).isEmpty();
        }

        @Test
        @DisplayName("caps the token count so a hand-rolled query cannot blow up the comparison")
        void capsTokenCount() {
            String many = IntStream.range(0, NodeTypeFilters.MAX_TOKENS + 20)
                    .mapToObj(i -> "core:t" + i)
                    .reduce((a, b) -> a + "," + b)
                    .orElseThrow();

            assertThat(NodeTypeFilters.parse(many)).hasSize(NodeTypeFilters.MAX_TOKENS);
        }
    }

    @Nested
    @DisplayName("tokensOf - reading a ROW's own list")
    class TokensOf {

        @Test
        @DisplayName("reads the list as stored")
        void readsTheList() {
            assertThat(NodeTypeFilters.tokensOf(List.of("mcp:gmail", "core:loop")))
                    .containsExactly("mcp:gmail", "core:loop");
        }

        @Test
        @DisplayName("does NOT apply the request cap - a row's own tail must never be dropped")
        void doesNotTruncate() {
            // parse() caps at MAX_TOKENS, which bounds what a CALLER can ask for.
            // Applying it to data would cut the tail of a sorted list - the table:
            // and trigger: tokens - and the row would stop matching a filter it
            // genuinely satisfies, with no error to show for it.
            List<String> many = IntStream.range(0, NodeTypeFilters.MAX_TOKENS + 20)
                    .mapToObj(i -> "core:t" + i)
                    .toList();

            assertThat(NodeTypeFilters.tokensOf(many)).hasSize(many.size());
        }

        @Test
        @DisplayName("a missing or non-list value reads as no tokens rather than throwing")
        void missingValueIsEmpty() {
            assertThat(NodeTypeFilters.tokensOf(null)).isEmpty();
            assertThat(NodeTypeFilters.tokensOf("mcp:gmail")).isEmpty();
        }

        @Test
        @DisplayName("skips null and blank entries")
        void skipsBlanks() {
            List<String> raw = new java.util.ArrayList<>();
            raw.add("mcp:gmail");
            raw.add(null);
            raw.add("  ");
            assertThat(NodeTypeFilters.tokensOf(raw)).containsExactly("mcp:gmail");
        }
    }

    @Nested
    @DisplayName("matches")
    class Matches {

        @Test
        @DisplayName("an empty request matches everything, so callers can apply it unconditionally")
        void emptyRequestMatchesAll() {
            assertThat(NodeTypeFilters.matches(List.of("core:loop"), Set.of())).isTrue();
            assertThat(NodeTypeFilters.matches(List.of(), Set.of())).isTrue();
        }

        @Test
        @DisplayName("ANY-of, not all-of: one token in common is a match")
        void matchesOnAnyToken() {
            assertThat(NodeTypeFilters.matches(
                    List.of("core:loop", "mcp:slack"),
                    Set.of("mcp:gmail", "mcp:slack"))).isTrue();
        }

        @Test
        @DisplayName("a row sharing no token does not match")
        void noSharedTokenIsNoMatch() {
            assertThat(NodeTypeFilters.matches(List.of("core:loop"), Set.of("mcp:gmail"))).isFalse();
        }

        @Test
        @DisplayName("a row with no tokens never matches an active filter")
        void emptyRowNeverMatchesActiveFilter() {
            assertThat(NodeTypeFilters.matches(List.of(), Set.of("mcp:gmail"))).isFalse();
            assertThat(NodeTypeFilters.matches(null, Set.of("mcp:gmail"))).isFalse();
        }

        @Test
        @DisplayName("row tokens are compared case-insensitively")
        void rowTokensAreCaseInsensitive() {
            assertThat(NodeTypeFilters.matches(List.of("MCP:Gmail"), Set.of("mcp:gmail"))).isTrue();
        }
    }

    @Nested
    @DisplayName("facets")
    class Facets {

        @Test
        @DisplayName("counts how many ROWS carry each token, not how many nodes")
        void countsRowsNotNodes() {
            // The first row holds the same token twice: a workflow with three Gmail
            // steps must still count as one result behind the "Gmail" option.
            List<Map<String, Object>> facets = NodeTypeFilters.facets(List.of(
                    List.of("mcp:gmail", "mcp:gmail", "core:loop"),
                    List.of("mcp:gmail")));

            assertThat(facets).containsExactly(
                    Map.of("value", "mcp:gmail", "count", 2),
                    Map.of("value", "core:loop", "count", 1));
        }

        @Test
        @DisplayName("orders by descending count, then alphabetically for a stable answer")
        void ordersByCountThenName() {
            List<Map<String, Object>> facets = NodeTypeFilters.facets(List.of(
                    List.of("core:loop", "mcp:zendesk", "mcp:asana"),
                    List.of("core:loop")));

            assertThat(facets.stream().map(f -> f.get("value")))
                    .containsExactly("core:loop", "mcp:asana", "mcp:zendesk");
        }

        @Test
        @DisplayName("a row with no tokens contributes no option")
        void emptyRowsContributeNothing() {
            assertThat(NodeTypeFilters.facets(List.of(List.of(), List.of()))).isEmpty();
        }

        @Test
        @DisplayName("no rows at all yields no options rather than failing")
        void noRows() {
            assertThat(NodeTypeFilters.facets(List.of())).isEmpty();
        }
    }
}
