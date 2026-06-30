package com.apimarketplace.common.storage.service.mapping;

import com.apimarketplace.common.mapping.StrictMappingEngine;
import com.apimarketplace.common.storage.dto.MappingSpec;
import com.apimarketplace.common.storage.dto.MappingSpec.FieldSpec;
import com.apimarketplace.common.storage.dto.MappingSpec.SourceSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MappingSpecConverter Tests")
class MappingSpecConverterTest {

    private MappingSpecConverter converter;

    @BeforeEach
    void setUp() {
        converter = new MappingSpecConverter();
    }

    @Nested
    @DisplayName("fromMap")
    class FromMapTests {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNullInput() {
            MappingSpec result = converter.fromMap(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should convert source from map")
        void shouldConvertSourceFromMap() {
            Map<String, Object> specMap = new LinkedHashMap<>();
            Map<String, Object> sourceMap = new LinkedHashMap<>();
            sourceMap.put("format", "json");
            sourceMap.put("root", "$.response");
            sourceMap.put("items_path", "$.data.items");
            sourceMap.put("root_alternatives", List.of("$.results", "$.records"));
            specMap.put("source", sourceMap);

            MappingSpec result = converter.fromMap(specMap);

            assertThat(result).isNotNull();
            assertThat(result.getSource()).isNotNull();
            assertThat(result.getSource().getFormat()).isEqualTo("json");
            assertThat(result.getSource().getRoot()).isEqualTo("$.response");
            assertThat(result.getSource().getItemsPath()).isEqualTo("$.data.items");
            assertThat(result.getSource().getRootAlternatives()).containsExactly("$.results", "$.records");
        }

        @Test
        @DisplayName("should convert fields from map")
        void shouldConvertFieldsFromMap() {
            Map<String, Object> specMap = new LinkedHashMap<>();
            Map<String, Object> fieldsMap = new LinkedHashMap<>();

            Map<String, Object> nameField = new LinkedHashMap<>();
            nameField.put("candidates", List.of("@.name", "@.title"));
            nameField.put("to", "display_name");
            nameField.put("default", "Unknown");
            nameField.put("required", true);
            fieldsMap.put("name", nameField);

            specMap.put("fields", fieldsMap);

            MappingSpec result = converter.fromMap(specMap);

            assertThat(result).isNotNull();
            assertThat(result.getFields()).isNotNull();
            assertThat(result.getFields()).containsKey("name");

            FieldSpec nameSpec = result.getFields().get("name");
            assertThat(nameSpec.getCandidates()).containsExactly("@.name", "@.title");
            assertThat(nameSpec.getTo()).isEqualTo("display_name");
            assertThat(nameSpec.getDefaultValue()).isEqualTo("Unknown");
            assertThat(nameSpec.getRequired()).isTrue();
        }

        @Test
        @DisplayName("should convert globals from map")
        void shouldConvertGlobalsFromMap() {
            Map<String, Object> specMap = new LinkedHashMap<>();
            Map<String, Object> globalsMap = new LinkedHashMap<>();

            Map<String, Object> totalField = new LinkedHashMap<>();
            totalField.put("candidates", List.of("$.meta.total_count"));
            totalField.put("to", "total");
            globalsMap.put("total", totalField);

            specMap.put("globals", globalsMap);

            MappingSpec result = converter.fromMap(specMap);

            assertThat(result).isNotNull();
            assertThat(result.getGlobals()).isNotNull();
            assertThat(result.getGlobals()).containsKey("total");
        }

        @Test
        @DisplayName("should handle empty map")
        void shouldHandleEmptyMap() {
            MappingSpec result = converter.fromMap(new LinkedHashMap<>());

            assertThat(result).isNotNull();
            assertThat(result.getSource()).isNull();
            assertThat(result.getFields()).isNull();
            assertThat(result.getGlobals()).isNull();
        }

        @Test
        @DisplayName("should skip non-map source values")
        void shouldSkipNonMapSourceValues() {
            Map<String, Object> specMap = new LinkedHashMap<>();
            specMap.put("source", "not a map");

            MappingSpec result = converter.fromMap(specMap);

            assertThat(result.getSource()).isNull();
        }

        @Test
        @DisplayName("should skip non-map field entries")
        void shouldSkipNonMapFieldEntries() {
            Map<String, Object> specMap = new LinkedHashMap<>();
            Map<String, Object> fieldsMap = new LinkedHashMap<>();
            fieldsMap.put("name", "not a map");
            specMap.put("fields", fieldsMap);

            MappingSpec result = converter.fromMap(specMap);

            assertThat(result.getFields()).isNotNull();
            assertThat(result.getFields()).isEmpty();
        }

        @Test
        @DisplayName("should handle field without candidates")
        void shouldHandleFieldWithoutCandidates() {
            Map<String, Object> specMap = new LinkedHashMap<>();
            Map<String, Object> fieldsMap = new LinkedHashMap<>();
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("to", "output");
            fieldsMap.put("result", field);
            specMap.put("fields", fieldsMap);

            MappingSpec result = converter.fromMap(specMap);

            FieldSpec resultField = result.getFields().get("result");
            assertThat(resultField.getCandidates()).isNull();
            assertThat(resultField.getTo()).isEqualTo("output");
        }

        @Test
        @DisplayName("should handle source without root_alternatives")
        void shouldHandleSourceWithoutRootAlternatives() {
            Map<String, Object> specMap = new LinkedHashMap<>();
            Map<String, Object> sourceMap = new LinkedHashMap<>();
            sourceMap.put("format", "json");
            specMap.put("source", sourceMap);

            MappingSpec result = converter.fromMap(specMap);

            assertThat(result.getSource().getRootAlternatives()).isNull();
        }
    }

    @Nested
    @DisplayName("toStrictSpec")
    class ToStrictSpecTests {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNullInput() {
            StrictMappingEngine.StrictMappingSpec result = converter.toStrictSpec(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should convert source spec")
        void shouldConvertSourceSpec() {
            MappingSpec spec = createMappingSpecWithSource("json", "$.data.items", "$.root", List.of("$.alt1"));

            StrictMappingEngine.StrictMappingSpec result = converter.toStrictSpec(spec);

            assertThat(result).isNotNull();
            assertThat(result.source).isNotNull();
            assertThat(result.source.format).isEqualTo("json");
            assertThat(result.source.items_path).isEqualTo("$.data.items");
            assertThat(result.source.root).isEqualTo("$.root");
            assertThat(result.source.root_alternatives).containsExactly("$.alt1");
        }

        @Test
        @DisplayName("should convert fields")
        void shouldConvertFields() {
            MappingSpec spec = new MappingSpec();
            spec.setSource(new SourceSpec());
            Map<String, FieldSpec> fields = new LinkedHashMap<>();
            FieldSpec field = new FieldSpec();
            field.setCandidates(List.of("@.name", "@.title"));
            field.setTo("display_name");
            field.setRequired(true);
            field.setDefaultValue("N/A");
            fields.put("name", field);
            spec.setFields(fields);

            StrictMappingEngine.StrictMappingSpec result = converter.toStrictSpec(spec);

            assertThat(result.fields).containsKey("name");
            StrictMappingEngine.FieldSpec strictField = result.fields.get("name");
            assertThat(strictField.candidates).containsExactly("@.name", "@.title");
            assertThat(strictField.to).isEqualTo("display_name");
            assertThat(strictField.required).isTrue();
            assertThat(strictField.defaultValue).isEqualTo("N/A");
        }

        @Test
        @DisplayName("should handle spec with null source")
        void shouldHandleSpecWithNullSource() {
            MappingSpec spec = new MappingSpec();

            StrictMappingEngine.StrictMappingSpec result = converter.toStrictSpec(spec);

            assertThat(result).isNotNull();
            assertThat(result.source).isNotNull();
        }

        @Test
        @DisplayName("should handle spec with null fields")
        void shouldHandleSpecWithNullFields() {
            MappingSpec spec = new MappingSpec();
            spec.setSource(new SourceSpec());

            StrictMappingEngine.StrictMappingSpec result = converter.toStrictSpec(spec);

            assertThat(result.fields).isNotNull();
            assertThat(result.fields).isEmpty();
        }
    }

    @Nested
    @DisplayName("toGlobalsStrictSpec")
    class ToGlobalsStrictSpecTests {

        @Test
        @DisplayName("should return null for null spec")
        void shouldReturnNullForNullSpec() {
            StrictMappingEngine.StrictMappingSpec result = converter.toGlobalsStrictSpec(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null when globals are null")
        void shouldReturnNullWhenGlobalsNull() {
            MappingSpec spec = new MappingSpec();

            StrictMappingEngine.StrictMappingSpec result = converter.toGlobalsStrictSpec(spec);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null when globals are empty")
        void shouldReturnNullWhenGlobalsEmpty() {
            MappingSpec spec = new MappingSpec();
            spec.setGlobals(Collections.emptyMap());

            StrictMappingEngine.StrictMappingSpec result = converter.toGlobalsStrictSpec(spec);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should convert globals to strict spec")
        void shouldConvertGlobalsToStrictSpec() {
            MappingSpec spec = new MappingSpec();
            Map<String, FieldSpec> globals = new LinkedHashMap<>();
            FieldSpec totalField = new FieldSpec();
            totalField.setCandidates(List.of("$.meta.total"));
            totalField.setTo("total");
            globals.put("total", totalField);
            spec.setGlobals(globals);

            StrictMappingEngine.StrictMappingSpec result = converter.toGlobalsStrictSpec(spec);

            assertThat(result).isNotNull();
            assertThat(result.source.format).isEqualTo("json");
            assertThat(result.source.items_path).isEqualTo("$");
            assertThat(result.source.root).isNull();
            assertThat(result.source.root_alternatives).containsExactly("$");
            assertThat(result.fields).containsKey("total");
        }
    }

    // ========== Helper methods ==========

    private MappingSpec createMappingSpecWithSource(String format, String itemsPath, String root,
                                                    List<String> rootAlternatives) {
        MappingSpec spec = new MappingSpec();
        SourceSpec source = new SourceSpec();
        source.setFormat(format);
        source.setItemsPath(itemsPath);
        source.setRoot(root);
        source.setRootAlternatives(rootAlternatives);
        spec.setSource(source);
        return spec;
    }
}
