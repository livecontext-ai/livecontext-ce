package com.apimarketplace.datasource.crud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The grouping rule on its own, including the shapes a full write path cannot easily produce.
 *
 * <p>This replaced a head-truncation, and the reason matters: a bulk write repeats one finding
 * hundreds of times, so keeping the first ten reports ten copies of whatever the FIRST rows
 * happened to say and silently drops the single line that came from row 300 - which is exactly the
 * line a caller needs, because the rare finding is the one that means the value is unusable. Every
 * case below is written against that: no distinct finding may ever be lost, however many
 * near-identical ones were produced before it.
 */
@DisplayName("CrudExecutorService.summariseWarnings")
class CrudExecutorServiceWarningSummaryTest {

    /**
     * Character for character what a path-only file ref produces: the column prefix comes from
     * CrudExecutorService, the body and the parenthesised storage key from ColumnValueCoercer. The
     * parenthesis matters here - it is one of the two things the grouping key cuts on.
     */
    private static final String UNUSABLE =
            "video: File reference has no id and no URL - it cannot be displayed "
                    + "(storage key: 1/wf/run/clip.mp4)";

    private static String date(String value) {
        return "due: Converted date format to ISO: '" + value + "' -> '2024-01-15'";
    }

    @Test
    @DisplayName("Null and empty are returned untouched")
    void nullAndEmptyPassThrough() {
        assertThat(CrudExecutorService.summariseWarnings(null)).isNull();
        assertThat(CrudExecutorService.summariseWarnings(List.of())).isEmpty();
    }

    @Test
    @DisplayName("A single warning is returned exactly as written, with no count appended")
    void singleWarningIsUntouched() {
        assertThat(CrudExecutorService.summariseWarnings(List.of(UNUSABLE)))
                .containsExactly(UNUSABLE);
    }

    @Test
    @DisplayName("Warnings that are all different are returned unchanged and in order")
    void allDistinctWarningsAreUntouched() {
        List<String> distinct = List.of(UNUSABLE, "price: Coercion error: not a number",
                "email: Value does not look like a valid email: 'x'");

        assertThat(CrudExecutorService.summariseWarnings(distinct))
                .containsExactlyElementsOf(distinct);
    }

    @Test
    @DisplayName("Messages differing only in the value they quote fold into one line with a count")
    void quotedValuesFoldTogether() {
        List<String> result = CrudExecutorService.summariseWarnings(
                List.of(date("15/01/2024"), date("16/02/2024"), date("17/03/2024")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .as("the surviving line stays a real, readable example")
                .startsWith(date("15/01/2024"))
                .endsWith("(and 2 more like it)");
    }

    /**
     * The case a quote-based key alone cannot see: this message interpolates its numbers bare, so
     * every row produces a different string and nothing groups. Five hundred clamped rows would be
     * five hundred lines, which is the flood this method exists to prevent.
     */
    @Test
    @DisplayName("Messages differing only in bare numbers fold too")
    void bareNumbersFoldTogether() {
        List<String> result = CrudExecutorService.summariseWarnings(List.of(
                "score: Clamped number from 500 to maximum 100",
                "score: Clamped number from 750 to maximum 100",
                "score: Clamped number from 9001 to maximum 100"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).endsWith("(and 2 more like it)");
    }

    /**
     * The regression: an earlier version of the grouping key flattened digit runs across the WHOLE
     * message, column name included, so {@code photo_1} and {@code photo_2} keyed identically. The
     * caller was told one media column was broken and never learned the second was too - a missing
     * finding, which is the one thing this method must never produce. Numbered columns are ordinary
     * (photo_1/photo_2, revenue_2023/revenue_2024) and are exactly the multi-media-column shape the
     * feature is for.
     */
    @Test
    @DisplayName("Two columns whose names differ only by a digit stay two findings")
    void columnNamesDifferingOnlyByADigitDoNotCollapse() {
        List<String> result = CrudExecutorService.summariseWarnings(List.of(
                "photo_1: File reference has no id and no URL - it cannot be displayed "
                        + "(storage key: a/1.jpg)",
                "photo_2: File reference has no id and no URL - it cannot be displayed "
                        + "(storage key: b/2.jpg)"));

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(w -> assertThat(w).startsWith("photo_1:"));
        assertThat(result).anySatisfy(w -> assertThat(w).startsWith("photo_2:"));
        assertThat(result).noneSatisfy(w -> assertThat(w).contains("more like it"));
    }

    /**
     * Column names are not just identifiers: SqlSanitizer allows letters, digits, underscore,
     * SPACE and HYPHEN, and accepts any unicode letter. The grouping must key on the whole name.
     */
    @Test
    @DisplayName("Column names with spaces, hyphens and accents each keep their own finding")
    void awkwardButLegalColumnNamesStaySeparate() {
        String body = ": File reference has no id and no URL - it cannot be displayed";
        List<String> result = CrudExecutorService.summariseWarnings(List.of(
                "cover photo" + body,
                "cover-photo" + body,
                "photo de couverture" + body));

        assertThat(result).hasSize(3);
        assertThat(result).noneSatisfy(w -> assertThat(w).contains("more like it"));
    }

    @Test
    @DisplayName("A numbered column still folds its OWN repeats")
    void aNumberedColumnStillFoldsItsOwnRepeats() {
        List<String> result = CrudExecutorService.summariseWarnings(List.of(
                "revenue_2023: Converted date format to ISO: '15/01/2024' -> '2024-01-15'",
                "revenue_2023: Converted date format to ISO: '16/02/2024' -> '2024-02-16'",
                "revenue_2024: Converted date format to ISO: '17/03/2024' -> '2024-03-17'"));

        assertThat(result)
                .as("the two columns are separate findings, and 2023's two cells are one line")
                .hasSize(2);
        assertThat(result).anySatisfy(w -> assertThat(w)
                .startsWith("revenue_2023:").endsWith("(and 1 more like it)"));
        assertThat(result).anySatisfy(w -> assertThat(w)
                .startsWith("revenue_2024:").doesNotContain("more like it"));
    }

    @Test
    @DisplayName("The same finding on two different columns stays two lines")
    void differentColumnsAreDifferentFindings() {
        List<String> result = CrudExecutorService.summariseWarnings(
                List.of(date("15/01/2024"), "shipped: Converted date format to ISO: '16/02/2024' -> '2024-02-16'"));

        assertThat(result)
                .as("a caller fixing a column must be told which columns are affected")
                .hasSize(2);
    }

    /**
     * The incident, as a list: the rare unusable value is written LAST, after a flood of benign
     * ones. A head-cap of any size drops it. Grouping cannot.
     */
    @Test
    @DisplayName("The one unusable value survives 300 benign findings written before it")
    void theRareFindingSurvivesAFlood() {
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            warnings.add(date("1" + i + "/01/2024"));
        }
        warnings.add(UNUSABLE);

        List<String> result = CrudExecutorService.summariseWarnings(warnings);

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(w -> assertThat(w).isEqualTo(UNUSABLE));
        assertThat(result).anySatisfy(w -> assertThat(w).endsWith("(and 299 more like it)"));
    }

    @Test
    @DisplayName("Two unusable refs in different rows fold, though their storage keys differ")
    void twoUnusableRefsWithDifferentKeysFold() {
        List<String> result = CrudExecutorService.summariseWarnings(List.of(
                UNUSABLE,
                "video: File reference has no id and no URL - it cannot be displayed "
                        + "(storage key: 1/wf/run/other.mp4)"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .as("the reader still gets one real storage key to go and look at")
                .startsWith(UNUSABLE)
                .endsWith("(and 1 more like it)");
    }

    @Test
    @DisplayName("Byte-identical warnings fold as well as near-identical ones")
    void identicalWarningsFold() {
        List<String> result = CrudExecutorService.summariseWarnings(
                List.of(UNUSABLE, UNUSABLE, UNUSABLE));

        assertThat(result).containsExactly(UNUSABLE + " (and 2 more like it)");
    }

    /**
     * Nothing the coercion writes opens with a quote - every message is prefixed with its column -
     * but a key computed from such a string would be empty and would swallow every other finding
     * with it, which is the one failure this method must never have.
     */
    @Test
    @DisplayName("A message that opens with a quote does not swallow the other findings")
    void aMessageStartingWithAQuoteKeepsItsOwnIdentity() {
        List<String> result = CrudExecutorService.summariseWarnings(
                List.of("'weird' leading quote", "'other' leading quote", UNUSABLE));

        assertThat(result).hasSize(3);
        assertThat(result).contains(UNUSABLE);
    }
}
