package com.apimarketplace.common.storage.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides which breakdown category a {@code storage.storage} row is counted in.
 *
 * <p>Production evidence this test exists for (2026-09-18, busiest tenant): 21 GB of ACTIVE rows,
 * of which the breakdown reported 4.9 GB. 2105 {@code S3_FILE/STEP_OUTPUT} rows carrying 15 GB,
 * plus the interface video, screenshot and PDF rows, matched NEITHER category: the FILES predicate
 * tested {@code source_type IN ('S3_FILE', ...)} while {@code S3_FILE} is a STORAGE type. Nothing
 * errored, so the only signal was a gauge that disagreed with the bar under it.
 */
@DisplayName("StorageRowCategories - the FILES / STEP_OUTPUTS rule")
class StorageRowCategoriesTest {

    /** Every storage type a row can carry today, plus one nobody has invented yet. */
    private static final List<String> STORAGE_TYPES =
            Arrays.asList("JSON", "TEXT", "BINARY", "S3_FILE", "FOLDER", "SOME_FUTURE_TYPE", null);

    /** Every source type in {@link StorageSourceTypes}, plus the two that carry none. */
    private static final List<String> SOURCE_TYPES = Arrays.asList(
            StorageSourceTypes.S3_FILE,
            StorageSourceTypes.CHAT_ATTACHMENT,
            StorageSourceTypes.FOLDER,
            StorageSourceTypes.STEP_OUTPUT,
            StorageSourceTypes.INTERFACE_SCREENSHOT,
            StorageSourceTypes.INTERFACE_PDF,
            StorageSourceTypes.INTERFACE_VIDEO,
            "SKIPPED_NODE",
            "SIGNAL",
            "SOME_FUTURE_SOURCE",
            null);

    @Nested
    @DisplayName("the bug that hid 16 GB")
    class TheProductionBug {

        @ParameterizedTest(name = "S3-backed row with source_type={0} counts as FILES")
        @ValueSource(strings = {"STEP_OUTPUT", "INTERFACE_VIDEO", "INTERFACE_SCREENSHOT", "INTERFACE_PDF"})
        @DisplayName("a file produced by a node or an interface is a FILE, whatever produced it")
        void s3BackedRowsAreFilesWhateverTheirSourceType(String sourceType) {
            assertThat(StorageRowCategories.categoryFor("S3_FILE", sourceType))
                    .as("these are the rows that were reported in no category at all")
                    .isEqualTo(StorageRowCategories.FILES);
        }

        @Test
        @DisplayName("the FILES predicate tests the STORAGE type, not only the source type")
        void filesPredicateMatchesOnStorageType() {
            // The literal the pre-fix predicate was missing. Asserting on the rendered SQL keeps
            // the two spellings (Java set, SQL list) from drifting apart again.
            assertThat(StorageRowCategories.filesSqlPredicate("s"))
                    .contains("s.storage_type")
                    .contains("'S3_FILE'");
        }
    }

    @Nested
    @DisplayName("the two categories partition every row")
    class Partition {

        @Test
        @DisplayName("every storage_type x source_type combination lands in exactly one category")
        void everyCombinationIsClassifiedExactlyOnce() {
            List<String> unclassified = new ArrayList<>();
            for (String storageType : STORAGE_TYPES) {
                for (String sourceType : SOURCE_TYPES) {
                    String category = StorageRowCategories.categoryFor(storageType, sourceType);
                    if (!StorageRowCategories.FILES.equals(category)
                            && !StorageRowCategories.STEP_OUTPUTS.equals(category)) {
                        unclassified.add(storageType + "/" + sourceType + " -> " + category);
                    }
                }
            }
            assertThat(unclassified)
                    .as("a row in neither category is invisible on a billed dimension, which is the "
                            + "failure this class was written to end")
                    .isEmpty();
        }

        @Test
        @DisplayName("the STEP_OUTPUTS predicate is the exact negation of the FILES one")
        void stepOutputsPredicateIsTheComplement() {
            assertThat(StorageRowCategories.stepOutputsSqlPredicate("s"))
                    .isEqualTo("NOT " + StorageRowCategories.filesSqlPredicate("s"));
        }

        @Test
        @DisplayName("both columns are COALESCE'd so a NULL cannot fall out of both predicates")
        void predicateIsNullSafeOnBothColumns() {
            // Without this, `source_type IN (...)` is NULL for a row with no source type, `NOT NULL`
            // is NULL too, and the row is dropped by BOTH branches: a smaller copy of the same bug.
            String predicate = StorageRowCategories.filesSqlPredicate("s");
            assertThat(predicate).contains("COALESCE(s.storage_type, '')");
            assertThat(predicate).contains("COALESCE(s.source_type, '')");
        }

        @Test
        @DisplayName("an unknown storage type is counted, not dropped")
        void unknownTypesAreCounted() {
            assertThat(StorageRowCategories.categoryFor("A_TYPE_FROM_NEXT_YEAR", "A_SOURCE_FROM_NEXT_YEAR"))
                    .as("mislabelled but visible beats invisible: the total still adds up")
                    .isEqualTo(StorageRowCategories.STEP_OUTPUTS);
        }
    }

    @Nested
    @DisplayName("the classification itself")
    class Classification {

        @ParameterizedTest(name = "storage_type={0} is a file row")
        @ValueSource(strings = {"S3_FILE", "BINARY", "TEXT"})
        void fileStorageTypes(String storageType) {
            assertThat(StorageRowCategories.isFileRow(storageType, StorageSourceTypes.STEP_OUTPUT)).isTrue();
        }

        @ParameterizedTest(name = "source_type={0} is a file row even when stored inline")
        @ValueSource(strings = {"S3_FILE", "CHAT_ATTACHMENT"})
        void fileSourceTypes(String sourceType) {
            assertThat(StorageRowCategories.isFileRow("JSON", sourceType)).isTrue();
        }

        @ParameterizedTest(name = "JSON/{0} is journal, not a file")
        @CsvSource({"STEP_OUTPUT", "SKIPPED_NODE", "SIGNAL", "INTERFACE_ACTION", "FOLDER"})
        void jsonPayloadsStayStepOutputs(String sourceType) {
            assertThat(StorageRowCategories.categoryFor("JSON", sourceType))
                    .isEqualTo(StorageRowCategories.STEP_OUTPUTS);
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("a legacy row with neither type is a step output")
        void nullsAreStepOutputs(String nothing) {
            assertThat(StorageRowCategories.categoryFor(nothing, nothing))
                    .isEqualTo(StorageRowCategories.STEP_OUTPUTS);
        }
    }
}
