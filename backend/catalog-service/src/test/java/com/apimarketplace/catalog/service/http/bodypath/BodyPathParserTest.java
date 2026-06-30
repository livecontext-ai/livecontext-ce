package com.apimarketplace.catalog.service.http.bodypath;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BodyPathParserTest {

    @Nested
    @DisplayName("isStructuredPath")
    class IsStructuredPath {
        @Test
        void flatKeyIsNotStructured() {
            assertThat(BodyPathParser.isStructuredPath("title")).isFalse();
        }

        @Test
        void dottedPathIsStructured() {
            assertThat(BodyPathParser.isStructuredPath("info.title")).isTrue();
        }

        @Test
        void indexedPathIsStructuredEvenWithoutDot() {
            assertThat(BodyPathParser.isStructuredPath("requests[0]")).isTrue();
        }

        @Test
        void arrayMapPathIsStructuredEvenWithoutDot() {
            assertThat(BodyPathParser.isStructuredPath("items[]")).isTrue();
        }

        @Test
        void nullAndBlankAreNotStructured() {
            assertThat(BodyPathParser.isStructuredPath(null)).isFalse();
            assertThat(BodyPathParser.isStructuredPath("")).isFalse();
            assertThat(BodyPathParser.isStructuredPath("   ")).isFalse();
        }
    }

    @Nested
    @DisplayName("parse - valid grammar")
    class ValidGrammar {
        @Test
        void singleLiteral() {
            assertThat(BodyPathParser.parse("title"))
                    .containsExactly(new BodyPathToken.Literal("title"));
        }

        @Test
        void dottedLiterals() {
            assertThat(BodyPathParser.parse("info.title"))
                    .containsExactly(
                            new BodyPathToken.Literal("info"),
                            new BodyPathToken.Literal("title"));
        }

        @Test
        void deeplyNested() {
            assertThat(BodyPathParser.parse("configuration.query.destinationTable.tableId"))
                    .hasSize(4)
                    .allMatch(t -> t instanceof BodyPathToken.Literal);
        }

        @Test
        void indexedArrayAtRoot() {
            assertThat(BodyPathParser.parse("requests[0].addSheet.properties.title"))
                    .containsExactly(
                            new BodyPathToken.IndexedArray("requests", 0),
                            new BodyPathToken.Literal("addSheet"),
                            new BodyPathToken.Literal("properties"),
                            new BodyPathToken.Literal("title"));
        }

        @Test
        void indexedArrayDeepIndex() {
            assertThat(BodyPathParser.parse("buckets[5].name"))
                    .containsExactly(
                            new BodyPathToken.IndexedArray("buckets", 5),
                            new BodyPathToken.Literal("name"));
        }

        @Test
        void mappedArrayAtRoot() {
            assertThat(BodyPathParser.parse("items[].id"))
                    .containsExactly(
                            new BodyPathToken.MappedArray("items"),
                            new BodyPathToken.Literal("id"));
        }

        @Test
        void mappedArrayDeep() {
            assertThat(BodyPathParser.parse("message.toRecipients[].emailAddress.address"))
                    .containsExactly(
                            new BodyPathToken.Literal("message"),
                            new BodyPathToken.MappedArray("toRecipients"),
                            new BodyPathToken.Literal("emailAddress"),
                            new BodyPathToken.Literal("address"));
        }

        @Test
        void mappedArrayTerminal() {
            assertThat(BodyPathParser.parse("tags[]"))
                    .containsExactly(new BodyPathToken.MappedArray("tags"));
        }

        @Test
        void underscoreAndDigitsInKey() {
            assertThat(BodyPathParser.parse("_x9.y_2"))
                    .containsExactly(
                            new BodyPathToken.Literal("_x9"),
                            new BodyPathToken.Literal("y_2"));
        }
    }

    @Nested
    @DisplayName("parse - grammar violations")
    class GrammarViolations {
        @Test
        void nullPathRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse(null))
                    .isInstanceOf(BodyPathException.Grammar.class)
                    .hasMessageContaining("null or blank");
        }

        @Test
        void blankPathRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse(""))
                    .isInstanceOf(BodyPathException.Grammar.class);
            assertThatThrownBy(() -> BodyPathParser.parse("   "))
                    .isInstanceOf(BodyPathException.Grammar.class);
        }

        @Test
        void emptySegmentRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse("a..b"))
                    .isInstanceOf(BodyPathException.Grammar.class)
                    .hasMessageContaining("empty segment");
        }

        @Test
        void leadingDotRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse(".a"))
                    .isInstanceOf(BodyPathException.Grammar.class);
        }

        @Test
        void trailingDotRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse("a."))
                    .isInstanceOf(BodyPathException.Grammar.class);
        }

        @Test
        void keyStartingWithDigitRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse("9foo"))
                    .isInstanceOf(BodyPathException.Grammar.class)
                    .hasMessageContaining("invalid segment");
        }

        @Test
        void specialCharsRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse("foo-bar"))
                    .isInstanceOf(BodyPathException.Grammar.class);
            assertThatThrownBy(() -> BodyPathParser.parse("foo$bar"))
                    .isInstanceOf(BodyPathException.Grammar.class);
        }

        @Test
        void negativeIndexRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse("foo[-1]"))
                    .isInstanceOf(BodyPathException.Grammar.class);
        }

        @Test
        void nonNumericIndexRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse("foo[abc]"))
                    .isInstanceOf(BodyPathException.Grammar.class);
        }

        @Test
        void doubleArrayMapRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse("a[].b[]"))
                    .isInstanceOf(BodyPathException.Grammar.class)
                    .hasMessageContaining("only one '[]'");
        }

        @Test
        void doubleArrayMapWithLiteralBetweenRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse("a[].b.c[]"))
                    .isInstanceOf(BodyPathException.Grammar.class);
        }

        @Test
        void unclosedBracketRejected() {
            assertThatThrownBy(() -> BodyPathParser.parse("a[0"))
                    .isInstanceOf(BodyPathException.Grammar.class);
            assertThatThrownBy(() -> BodyPathParser.parse("a]"))
                    .isInstanceOf(BodyPathException.Grammar.class);
        }

        @Test
        void mappedArrayAllowedWithIndexedArrayInSamePath() {
            List<BodyPathToken> tokens =
                    BodyPathParser.parse("requests[0].items[].name");
            assertThat(tokens).hasSize(3);
            assertThat(tokens.get(0)).isInstanceOf(BodyPathToken.IndexedArray.class);
            assertThat(tokens.get(1)).isInstanceOf(BodyPathToken.MappedArray.class);
            assertThat(tokens.get(2)).isInstanceOf(BodyPathToken.Literal.class);
        }
    }
}
