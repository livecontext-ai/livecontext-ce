package com.apimarketplace.orchestrator.services.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ResolvedValuePreview")
class ResolvedValuePreviewTest {

    @Test
    @DisplayName("a wrapper object is described by its KEYS, which is what makes the double-result trap visible")
    void describesAMapByItsKeys() {
        // The documented interface failure: a mapping written {"result": "{{core:x.output}}"}
        // resolves to {result: {...}}, the page reads __RESOLVED_DATA__.result.result and
        // renders its empty state. One line naming the key is the whole diagnosis.
        Map<String, Object> wrapped = Map.of("result", Map.of("listings", List.of()));

        assertThat(ResolvedValuePreview.describe(wrapped)).isEqualTo("Map(keys=[result])");
    }

    @Test
    @DisplayName("an empty collection is described as empty, not as absent")
    void describesAnEmptyCollectionAsEmpty() {
        // The question asked of a split that spawned nothing: was the array empty, or was
        // there no array? These must not render the same way.
        assertThat(ResolvedValuePreview.describe(List.of())).isEqualTo("List(size=0)");
        assertThat(ResolvedValuePreview.describe(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("a collection is described by its size, never by its contents")
    void describesACollectionBySize() {
        assertThat(ResolvedValuePreview.describe(List.of("a", "b", "c"))).isEqualTo("List(size=3)");
        assertThat(ResolvedValuePreview.describe(new int[]{1, 2})).isEqualTo("Array(size=2)");
    }

    @Test
    @DisplayName("a collection of 10 000 rows still describes in one short line (this is persisted per step row)")
    void boundsALargeCollection() {
        List<Object> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            rows.add(Map.of("id", i, "payload", "x".repeat(500)));
        }

        assertThat(ResolvedValuePreview.describe(rows)).isEqualTo("List(size=10000)");
    }

    @Test
    @DisplayName("a long string is truncated and its real length named, so the reader knows what was cut")
    void truncatesALongScalar() {
        String value = "y".repeat(500);

        String described = ResolvedValuePreview.describe(value);

        assertThat(described).hasSizeLessThan(200);
        assertThat(described).startsWith("\"yyy").contains("(500 chars)");
    }

    @Test
    @DisplayName("a short scalar keeps its own rendering, quoted for a string and bare for a number")
    void keepsShortScalarsReadable() {
        assertThat(ResolvedValuePreview.describe("ok")).isEqualTo("\"ok\"");
        assertThat(ResolvedValuePreview.describe("")).isEqualTo("\"\"");
        assertThat(ResolvedValuePreview.describe(42)).isEqualTo("42");
        assertThat(ResolvedValuePreview.describe(true)).isEqualTo("true");
    }

    @Test
    @DisplayName("a map with more keys than the cap names the cap's worth and counts the rest")
    void boundsTheKeyList() {
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i < 25; i++) {
            wide.put("key" + i, i);
        }

        String described = ResolvedValuePreview.describe(wide);

        assertThat(described).contains("key0").contains("key19").doesNotContain("key20");
        assertThat(described).contains("5 more");
    }

    @Test
    @DisplayName("long keys are capped too: 20 keys of any length is still one short line")
    void boundsTheKeyLength() {
        // Capping the key COUNT alone left the line unbounded, and it is persisted on every
        // step row. A JSON payload keyed by long identifiers is not a hypothetical shape.
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            wide.put("k".repeat(4000) + i, i);
        }

        String described = ResolvedValuePreview.describe(wide);

        // 20 keys, each capped, plus separators. Asserted against the cap rather than
        // against a round number the bound could double without failing.
        assertThat(described).hasSizeLessThan(
            ResolvedValuePreview.MAX_KEYS * (ResolvedValuePreview.MAX_KEY_CHARS + 24));
        assertThat(described).contains("4001 chars");
    }

    @Test
    @DisplayName("a number is capped like any other scalar: a BigInteger is as long as it wants to be")
    void boundsAHugeNumber() {
        java.math.BigInteger huge = java.math.BigInteger.TEN.pow(500);

        String described = ResolvedValuePreview.describe(huge);

        assertThat(described).hasSizeLessThan(300).contains("501 chars");
        assertThat(ResolvedValuePreview.describe(42)).isEqualTo("42");
    }

    @Test
    @DisplayName("a known size wins over the one the value happens to carry, because that value is a page")
    void prefersAKnownSize() {
        // The render loads one row and states the real count beside it. Describing the row
        // would report a 4 812-row variable as holding one.
        assertThat(ResolvedValuePreview.describeWithKnownSize(List.of(Map.of("id", 1)), 4812))
            .isEqualTo("List(size=4812)");
        assertThat(ResolvedValuePreview.describeWithKnownSize(new int[]{1}, 900))
            .isEqualTo("Array(size=900)");
    }

    @Test
    @DisplayName("a known size is ignored for anything that is not a collection: it belongs to something else")
    void ignoresAKnownSizeThatCannotBeOne() {
        assertThat(ResolvedValuePreview.describeWithKnownSize("Hello", 99)).isEqualTo("\"Hello\"");
        assertThat(ResolvedValuePreview.describeWithKnownSize(Map.of("a", 1), 99)).isEqualTo("Map(keys=[a])");
        assertThat(ResolvedValuePreview.describeWithKnownSize(null, 99)).isEqualTo("null");
    }

    @Test
    @DisplayName("a free-text reason is bounded the same way, and says how much was cut")
    void boundsAReason() {
        String shortened = ResolvedValuePreview.shorten("e".repeat(5000));

        assertThat(shortened).hasSizeLessThan(300).contains("(5000 chars)");
        assertThat(ResolvedValuePreview.shorten("short")).isEqualTo("short");
        assertThat(ResolvedValuePreview.shorten(null)).isNull();
    }

    @Test
    @DisplayName("a value whose toString throws is described by its type rather than breaking the run it explains")
    void survivesAHostileValue() {
        assertThat(ResolvedValuePreview.describe(new Unprintable())).isEqualTo("Unprintable");
    }

    @Test
    @DisplayName("an unknown type names itself, so a FileRef does not read as a piece of text")
    void namesAnUnknownType() {
        assertThat(ResolvedValuePreview.describe(new Printable()))
            .isEqualTo("Printable(a value)");
    }

    private static final class Unprintable {
        @Override
        public String toString() {
            throw new IllegalStateException("this type cannot render itself");
        }
    }

    private static final class Printable {
        @Override
        public String toString() {
            return "a value";
        }
    }
}
