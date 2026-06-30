package com.apimarketplace.common.storage.service.mapping;

import com.apimarketplace.common.storage.dto.MappingSpec;
import com.apimarketplace.common.storage.dto.MappingSpec.FieldSpec;
import com.apimarketplace.common.storage.dto.MappingSpec.SourceSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MappingSpecNormalizer Tests")
class MappingSpecNormalizerTest {

    private MappingSpecNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new MappingSpecNormalizer();
    }

    @Nested
    @DisplayName("cleanSpec")
    class CleanSpecTests {

        @Test
        @DisplayName("should return null for null input")
        void returnNullForNullInput() {
            MappingSpec result = normalizer.cleanSpec(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should create deep copy of spec")
        void createDeepCopyOfSpec() {
            MappingSpec original = createSpecWithSource("$.data");
            original.getSource().setRootAlternatives(new ArrayList<>(List.of("$.items", "$.records")));

            MappingSpec copy = normalizer.cleanSpec(original);

            // Modify original
            original.getSource().setItemsPath("$.modified");
            original.getSource().getRootAlternatives().clear();

            // Copy should be unchanged
            assertThat(copy.getSource().getItemsPath()).isEqualTo("$.data");
            assertThat(copy.getSource().getRootAlternatives()).containsExactly("$.items", "$.records");
        }

        @Test
        @DisplayName("should copy fields deeply")
        void copyFieldsDeeply() {
            MappingSpec original = createSpecWithFields();

            MappingSpec copy = normalizer.cleanSpec(original);

            // Modify original
            original.getFields().get("name").setCandidates(List.of("$.modified"));

            // Copy should be unchanged
            assertThat(copy.getFields().get("name").getCandidates()).contains("@.name");
        }

        @Test
        @DisplayName("should copy globals deeply")
        void copyGlobalsDeeply() {
            MappingSpec original = createSpecWithGlobals();

            MappingSpec copy = normalizer.cleanSpec(original);

            assertThat(copy.getGlobals()).isNotNull();
            assertThat(copy.getGlobals()).containsKey("total");
        }
    }

    @Nested
    @DisplayName("normalizeSourceSpec")
    class NormalizeSourceSpecTests {

        @Test
        @DisplayName("should handle null spec")
        void handleNullSpec() {
            // Should not throw
            normalizer.normalizeSourceSpec(null);
        }

        @Test
        @DisplayName("should handle spec with null source")
        void handleSpecWithNullSource() {
            MappingSpec spec = new MappingSpec();

            // Should not throw
            normalizer.normalizeSourceSpec(spec);
        }

        @Test
        @DisplayName("should set default items path when null")
        void setDefaultItemsPathWhenNull() {
            MappingSpec spec = createSpecWithSource(null);

            normalizer.normalizeSourceSpec(spec);

            // "$" gets normalized to "$." by ensureJsonPathPrefix
            assertThat(spec.getSource().getItemsPath()).isIn("$", "$.");
        }

        @Test
        @DisplayName("should set default items path when blank")
        void setDefaultItemsPathWhenBlank() {
            MappingSpec spec = createSpecWithSource("   ");

            normalizer.normalizeSourceSpec(spec);

            // "$" gets normalized to "$." by ensureJsonPathPrefix
            assertThat(spec.getSource().getItemsPath()).isIn("$", "$.");
        }

        @Test
        @DisplayName("should add itemsPath to rootAlternatives")
        void addItemsPathToRootAlternatives() {
            MappingSpec spec = createSpecWithSource("$.data");

            normalizer.normalizeSourceSpec(spec);

            assertThat(spec.getSource().getRootAlternatives()).contains("$.data");
        }

        @Test
        @DisplayName("should preserve existing rootAlternatives")
        void preserveExistingRootAlternatives() {
            MappingSpec spec = createSpecWithSource("$.data");
            spec.getSource().setRootAlternatives(List.of("$.items", "$.records"));

            normalizer.normalizeSourceSpec(spec);

            assertThat(spec.getSource().getRootAlternatives())
                .contains("$.data", "$.items", "$.records");
        }

        @Test
        @DisplayName("should set format to json")
        void setFormatToJson() {
            MappingSpec spec = createSpecWithSource("$.data");

            normalizer.normalizeSourceSpec(spec);

            assertThat(spec.getSource().getFormat()).isEqualTo("json");
        }

        @Test
        @DisplayName("should remove parentheses from itemsPath")
        void removeParenthesesFromItemsPath() {
            MappingSpec spec = createSpecWithSource("($.data)");

            normalizer.normalizeSourceSpec(spec);

            assertThat(spec.getSource().getItemsPath()).isEqualTo("$.data");
        }
    }

    @Nested
    @DisplayName("normalizeCandidates")
    class NormalizeCandidatesTests {

        @Test
        @DisplayName("should handle null spec")
        void handleNullSpec() {
            // Should not throw
            normalizer.normalizeCandidates(null);
        }

        @Test
        @DisplayName("should normalize field candidates")
        void normalizeFieldCandidates() {
            MappingSpec spec = createSpecWithFields();
            spec.setSource(new SourceSpec());
            spec.getSource().setItemsPath("$.items");

            normalizer.normalizeCandidates(spec);

            List<String> candidates = spec.getFields().get("name").getCandidates();
            assertThat(candidates).isNotEmpty();
        }

        @Test
        @DisplayName("should add absolute path for relative candidates")
        void addAbsolutePathForRelativeCandidates() {
            MappingSpec spec = new MappingSpec();
            spec.setSource(new SourceSpec());
            spec.getSource().setItemsPath("$.data");

            Map<String, FieldSpec> fields = new LinkedHashMap<>();
            FieldSpec field = new FieldSpec();
            field.setCandidates(List.of("@.title"));
            fields.put("title", field);
            spec.setFields(fields);

            normalizer.normalizeCandidates(spec);

            List<String> candidates = spec.getFields().get("title").getCandidates();
            assertThat(candidates).contains("@.title", "$.data.title");
        }
    }

    @Nested
    @DisplayName("normalizeItemsPath")
    class NormalizeItemsPathTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("should return fallback for null/empty/blank values")
        void returnFallbackForNullEmptyBlank(String value) {
            String result = normalizer.normalizeItemsPath(value, "$");

            // Fallback "$" gets normalized to "$." by ensureJsonPathPrefix
            assertThat(result).isIn("$", "$.");
        }

        @ParameterizedTest
        @CsvSource({
            "data, $.data",
            ".data, $.data",
            "$.data, $.data",
            "$..items, $..items",
            "$[0], $[0]"
        })
        @DisplayName("should normalize path correctly")
        void normalizePathCorrectly(String input, String expected) {
            String result = normalizer.normalizeItemsPath(input, "$");

            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("should remove parentheses")
        void removeParentheses() {
            String result = normalizer.normalizeItemsPath("($.data)", "$");

            assertThat(result).isEqualTo("$.data");
        }
    }

    @Nested
    @DisplayName("normalizeAbsolutePath")
    class NormalizeAbsolutePathTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("should return null for null/empty/blank values")
        void returnNullForNullEmptyBlank(String value) {
            String result = normalizer.normalizeAbsolutePath(value);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for $.null")
        void returnNullForDollarNull() {
            String result = normalizer.normalizeAbsolutePath("$.null");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should normalize valid path")
        void normalizeValidPath() {
            String result = normalizer.normalizeAbsolutePath("data.items");

            assertThat(result).isEqualTo("$.data.items");
        }

        @Test
        @DisplayName("should handle path with parentheses")
        void handlePathWithParentheses() {
            String result = normalizer.normalizeAbsolutePath("($.data)");

            assertThat(result).isEqualTo("$.data");
        }
    }

    @Nested
    @DisplayName("normalizeCandidate")
    class NormalizeCandidateTests {

        @ParameterizedTest
        @CsvSource({
            "$..items, $..items",
            "$[0], $[0]",
            "$.data, $.data"
        })
        @DisplayName("should preserve special paths")
        void preserveSpecialPaths(String input, String expected) {
            String result = normalizer.normalizeCandidate(input, "$.base", true);

            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("should normalize relative path with @")
        void normalizeRelativePathWithAt() {
            String result = normalizer.normalizeCandidate("@.field", "$.base", true);

            assertThat(result).isEqualTo("@.field");
        }

        @Test
        @DisplayName("should convert relative path to absolute when not allowed")
        void convertRelativeToAbsoluteWhenNotAllowed() {
            String result = normalizer.normalizeCandidate("@.field", "$.base", false);

            assertThat(result).isEqualTo("$.base.field");
        }

        @Test
        @DisplayName("should prefix simple field name with @.")
        void prefixSimpleFieldNameWithAt() {
            String result = normalizer.normalizeCandidate("fieldName", "$.base", true);

            assertThat(result).isEqualTo("@.fieldName");
        }

        @Test
        @DisplayName("should handle parentheses in candidate")
        void handleParenthesesInCandidate() {
            String result = normalizer.normalizeCandidate("($.data)", "$.base", true);

            assertThat(result).isEqualTo("$.data");
        }
    }

    @Nested
    @DisplayName("normalizeComplete")
    class NormalizeCompleteTests {

        @Test
        @DisplayName("should return null for null input")
        void returnNullForNullInput() {
            MappingSpec result = normalizer.normalizeComplete(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should perform complete normalization pipeline")
        void performCompleteNormalizationPipeline() {
            MappingSpec original = createCompleteSpec();

            MappingSpec result = normalizer.normalizeComplete(original);

            assertThat(result).isNotNull();
            assertThat(result.getSource().getFormat()).isEqualTo("json");
            assertThat(result.getSource().getRootAlternatives()).isNotEmpty();
        }

        @Test
        @DisplayName("should not modify original spec")
        void notModifyOriginalSpec() {
            MappingSpec original = createSpecWithSource("data");
            String originalPath = original.getSource().getItemsPath();

            normalizer.normalizeComplete(original);

            assertThat(original.getSource().getItemsPath()).isEqualTo(originalPath);
        }
    }

    // Helper methods

    private MappingSpec createSpecWithSource(String itemsPath) {
        MappingSpec spec = new MappingSpec();
        SourceSpec source = new SourceSpec();
        source.setItemsPath(itemsPath);
        spec.setSource(source);
        return spec;
    }

    private MappingSpec createSpecWithFields() {
        MappingSpec spec = new MappingSpec();
        Map<String, FieldSpec> fields = new LinkedHashMap<>();

        FieldSpec nameField = new FieldSpec();
        nameField.setCandidates(new ArrayList<>(List.of("@.name", "@.title")));
        nameField.setTo("name");
        nameField.setRequired(true);
        fields.put("name", nameField);

        spec.setFields(fields);
        return spec;
    }

    private MappingSpec createSpecWithGlobals() {
        MappingSpec spec = new MappingSpec();
        Map<String, FieldSpec> globals = new LinkedHashMap<>();

        FieldSpec totalField = new FieldSpec();
        totalField.setCandidates(new ArrayList<>(List.of("$.meta.total")));
        totalField.setTo("total");
        globals.put("total", totalField);

        spec.setGlobals(globals);
        return spec;
    }

    private MappingSpec createCompleteSpec() {
        MappingSpec spec = createSpecWithSource("$.data.items");
        spec.getSource().setRootAlternatives(new ArrayList<>(List.of("$.results")));

        Map<String, FieldSpec> fields = new LinkedHashMap<>();
        FieldSpec field = new FieldSpec();
        field.setCandidates(new ArrayList<>(List.of("@.id", "@.identifier")));
        field.setTo("id");
        fields.put("id", field);
        spec.setFields(fields);

        Map<String, FieldSpec> globals = new LinkedHashMap<>();
        FieldSpec globalField = new FieldSpec();
        globalField.setCandidates(new ArrayList<>(List.of("$.meta.count")));
        globals.put("count", globalField);
        spec.setGlobals(globals);

        return spec;
    }
}
