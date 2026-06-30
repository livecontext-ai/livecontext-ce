package com.apimarketplace.publication.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the PR5 query sanitizer.
 *
 * <p>The {@code to_tsquery('simple', X)} call would throw a Postgres syntax error
 * when {@code X} contains tsquery operators ({@code @ & | ! ( ) :}) or starts with
 * malformed tokens. {@link InternalPublicationController#buildCompactTsQuery(String)}
 * strips non-alphanumerics from each token, lowercases, drops empties, and OR-joins
 * with {@code " | "} - or returns {@code null} when no usable tokens remain so the
 * repo's SQL can guard the branch via {@code :compactQuery IS NOT NULL}.
 *
 * <p>Regression coverage targets the v0.2 audit B finding:
 * <em>"`query='@'`, `query='c++'`, `query='node.js'` will throw Postgres errors
 * and bubble a 500 to the agent"</em>.
 */
@DisplayName("InternalPublicationController.buildCompactTsQuery - PR5 sanitizer")
class InternalPublicationControllerCompactTsQueryTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {
        @Test @DisplayName("simple multi-word query OR-joins lowercased tokens")
        void multiWord() {
            assertThat(InternalPublicationController.buildCompactTsQuery("flights to thailand"))
                .isEqualTo("flights | to | thailand");
        }

        @Test @DisplayName("single word returns it as-is")
        void singleWord() {
            assertThat(InternalPublicationController.buildCompactTsQuery("gmail"))
                .isEqualTo("gmail");
        }

        @Test @DisplayName("uppercase tokens are lowercased")
        void lowercases() {
            assertThat(InternalPublicationController.buildCompactTsQuery("Gmail Slack"))
                .isEqualTo("gmail | slack");
        }

        @Test @DisplayName("unicode letters (accents, French, etc.) are preserved by \\p{L}")
        void unicodeLetters() {
            assertThat(InternalPublicationController.buildCompactTsQuery("générateur blagues"))
                .isEqualTo("générateur | blagues");
        }
    }

    @Nested
    @DisplayName("operator sanitization (regression: PR5 audit v0.2 footgun)")
    class OperatorSanitization {
        @Test @DisplayName("c++ strips operators to 'c'")
        void plusPlus() {
            assertThat(InternalPublicationController.buildCompactTsQuery("c++ tutorial"))
                .isEqualTo("c | tutorial");
        }

        @Test @DisplayName("node.js becomes 'nodejs' (dot stripped)")
        void dotInToken() {
            assertThat(InternalPublicationController.buildCompactTsQuery("node.js"))
                .isEqualTo("nodejs");
        }

        @Test @DisplayName("@-prefix handle stripped to alphanumerics")
        void atPrefix() {
            assertThat(InternalPublicationController.buildCompactTsQuery("@gmail-com"))
                .isEqualTo("gmailcom");
        }

        @Test @DisplayName("tsquery operators (& | ! :) inside tokens are stripped")
        void tsqueryOps() {
            assertThat(InternalPublicationController.buildCompactTsQuery("foo&bar test|x:1"))
                .isEqualTo("foobar | testx1");
        }
    }

    @Nested
    @DisplayName("empty / null-equivalent inputs")
    class EmptyInputs {
        @Test @DisplayName("null input → null result")
        void nullIn() {
            assertThat(InternalPublicationController.buildCompactTsQuery(null)).isNull();
        }

        @Test @DisplayName("empty string → null result")
        void emptyString() {
            assertThat(InternalPublicationController.buildCompactTsQuery("")).isNull();
        }

        @Test @DisplayName("whitespace-only → null result")
        void whitespaceOnly() {
            assertThat(InternalPublicationController.buildCompactTsQuery("   \t\n  ")).isNull();
        }

        @Test @DisplayName("query with only operators (@!#$) → null (no usable tokens)")
        void onlyOperators() {
            assertThat(InternalPublicationController.buildCompactTsQuery("@!#$")).isNull();
        }

        @Test @DisplayName("query with mix of operators and whitespace → null")
        void operatorsAndWhitespace() {
            assertThat(InternalPublicationController.buildCompactTsQuery("@ | & !")).isNull();
        }
    }

    /**
     * V273 showcase-epoch extraction from the internal publish Map. Pins the
     * "same pipe" contract: the agent's application(action='create') forwards
     * showcaseEpoch and the internal controller threads it to the service
     * (it used to hardcode null, dropping the epoch on the server-to-server path).
     */
    @Nested
    @DisplayName("parseShowcaseEpoch - internal publish epoch threading")
    class ParseShowcaseEpoch {
        @Test @DisplayName("Integer value is returned")
        void integerValue() {
            assertThat(InternalPublicationController.parseShowcaseEpoch(Map.of("showcaseEpoch", 3)))
                .isEqualTo(3);
        }

        @Test @DisplayName("Long value (JSON-deserialized Number subtype) is narrowed via intValue()")
        void longValue() {
            assertThat(InternalPublicationController.parseShowcaseEpoch(Map.of("showcaseEpoch", 5L)))
                .isEqualTo(5);
        }

        @Test @DisplayName("Missing key → null (no pin)")
        void missingKey() {
            assertThat(InternalPublicationController.parseShowcaseEpoch(Map.of("workflowId", "w")))
                .isNull();
        }

        @Test @DisplayName("Explicit null value → null")
        void explicitNull() {
            Map<String, Object> req = new HashMap<>();
            req.put("showcaseEpoch", null);
            assertThat(InternalPublicationController.parseShowcaseEpoch(req)).isNull();
        }

        @Test @DisplayName("Non-numeric value (string) → null, never throws")
        void nonNumeric() {
            assertThat(InternalPublicationController.parseShowcaseEpoch(Map.of("showcaseEpoch", "two")))
                .isNull();
        }

        @Test @DisplayName("Null map → null, never throws")
        void nullMap() {
            assertThat(InternalPublicationController.parseShowcaseEpoch(null)).isNull();
        }
    }
}
