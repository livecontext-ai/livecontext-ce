package com.apimarketplace.catalog.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiScopedSearchParser")
class ApiScopedSearchParserTest {

    @Nested
    @DisplayName("explicit API parameters")
    class ExplicitApiParameters {

        @Test
        @DisplayName("Keeps the keyword query and accepts one api filter")
        void keepsKeywordQueryAndAcceptsOneApiFilter() {
            ApiScopedSearchParser.ParsedSearch parsed =
                ApiScopedSearchParser.parse("list messages", "gmail", null);

            assertThat(parsed.query()).isEqualTo("list messages");
            assertThat(parsed.apiFilters()).containsExactly("gmail");
            assertThat(parsed.inlineScope()).isFalse();
        }

        @Test
        @DisplayName("Accepts api filters from arrays or comma-separated strings")
        void acceptsApiFiltersFromArraysOrCommaSeparatedStrings() {
            ApiScopedSearchParser.ParsedSearch parsed =
                ApiScopedSearchParser.parse("send message", "gmail, slack", List.of("google sheets"));

            assertThat(parsed.query()).isEqualTo("send message");
            assertThat(parsed.apiFilters()).containsExactly("gmail", "slack", "google sheets");
        }

        @Test
        @DisplayName("Bug B8: api given with no query lists the API (keyword = scope names) instead of an empty query")
        void apiWithoutQueryListsTheApiInsteadOfEmptyQuery() {
            ApiScopedSearchParser.ParsedSearch parsed =
                ApiScopedSearchParser.parse("", "composio", null);

            assertThat(parsed.query()).isEqualTo("composio");
            assertThat(parsed.apiFilters()).containsExactly("composio");
            assertThat(parsed.keywordFromScope()).isTrue();
            assertThat(parsed.inlineScope()).isFalse();
        }

        @Test
        @DisplayName("Bug B8: a blank query with several apis joins their names")
        void blankQueryWithSeveralApisJoinsTheirNames() {
            ApiScopedSearchParser.ParsedSearch parsed =
                ApiScopedSearchParser.parse("   ", null, List.of("telegram", "Ghost CMS"));

            assertThat(parsed.query()).isEqualTo("telegram Ghost CMS");
            assertThat(parsed.keywordFromScope()).isTrue();
        }

        @Test
        @DisplayName("A real keyword with an api is not flagged as scope-derived")
        void realKeywordIsNotScopeDerived() {
            assertThat(ApiScopedSearchParser.parse("list messages", "gmail", null).keywordFromScope()).isFalse();
        }

        @Test
        @DisplayName("No api and no query stays an empty query (the caller still refuses it)")
        void noApiNoQueryStaysEmpty() {
            ApiScopedSearchParser.ParsedSearch parsed = ApiScopedSearchParser.parse("", null, null);

            assertThat(parsed.query()).isEmpty();
            assertThat(parsed.apiFilters()).isEmpty();
            assertThat(parsed.keywordFromScope()).isFalse();
        }
    }

    @Nested
    @DisplayName("inline API scope")
    class InlineApiScope {

        @Test
        @DisplayName("Parses bracket API scope before the keyword query")
        void parsesBracketApiScopeBeforeKeywordQuery() {
            ApiScopedSearchParser.ParsedSearch parsed =
                ApiScopedSearchParser.parse("[gmail, slack] send message", null, null);

            assertThat(parsed.query()).isEqualTo("send message");
            assertThat(parsed.apiFilters()).containsExactly("gmail", "slack");
            assertThat(parsed.inlineScope()).isTrue();
            assertThat(parsed.keywordFromScope()).isFalse();
        }

        @Test
        @DisplayName("A bare bracket scope searches by the scope names and is flagged as scope-derived")
        void bareBracketScopeIsScopeDerived() {
            ApiScopedSearchParser.ParsedSearch parsed =
                ApiScopedSearchParser.parse("[composio]", null, null);

            assertThat(parsed.query()).isEqualTo("composio");
            assertThat(parsed.apiFilters()).containsExactly("composio");
            assertThat(parsed.keywordFromScope()).isTrue();
        }

        @Test
        @DisplayName("Parses comma API scope before the keyword query")
        void parsesCommaApiScopeBeforeKeywordQuery() {
            ApiScopedSearchParser.ParsedSearch parsed =
                ApiScopedSearchParser.parse("gmail, list messages", null, null);

            assertThat(parsed.query()).isEqualTo("list messages");
            assertThat(parsed.apiFilters()).containsExactly("gmail");
            assertThat(parsed.inlineScope()).isTrue();
        }

        @Test
        @DisplayName("Leaves normal queries unscoped")
        void leavesNormalQueriesUnscoped() {
            ApiScopedSearchParser.ParsedSearch parsed =
                ApiScopedSearchParser.parse("gmail list messages", null, null);

            assertThat(parsed.query()).isEqualTo("gmail list messages");
            assertThat(parsed.apiFilters()).isEmpty();
            assertThat(parsed.inlineScope()).isFalse();
        }

        @Test
        @DisplayName("Does not treat natural comma phrasing as API scope")
        void doesNotTreatNaturalCommaPhrasingAsApiScope() {
            ApiScopedSearchParser.ParsedSearch parsed =
                ApiScopedSearchParser.parse("send email, gmail", null, null);

            assertThat(parsed.query()).isEqualTo("send email, gmail");
            assertThat(parsed.apiFilters()).isEmpty();
        }
    }

    @Nested
    @DisplayName("normalization")
    class Normalization {

        @Test
        @DisplayName("Deduplicates filters by compact API identifier")
        void deduplicatesFiltersByCompactApiIdentifier() {
            ApiScopedSearchParser.ParsedSearch parsed =
                ApiScopedSearchParser.parse("list", List.of("Google Sheets", "google-sheets"), null);

            assertThat(parsed.apiFilters()).containsExactly("Google Sheets");
        }

        @Test
        @DisplayName("Compacts identifiers for SQL and in-memory matching")
        void compactsIdentifiersForMatching() {
            assertThat(ApiScopedSearchParser.compactIdentifier("Google Sheets API"))
                .isEqualTo("googlesheetsapi");
        }

    }
}
