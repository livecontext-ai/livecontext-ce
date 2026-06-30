package com.apimarketplace.catalog.integration;

import com.apimarketplace.catalog.mapping.dsl.FieldSpec;
import com.apimarketplace.catalog.mapping.dsl.MappingSpec;
import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the mapping DSL model classes: MappingSpec, SourceSpec, FieldSpec.
 * Tests JSON serialization/deserialization, default values, and mapping spec composition.
 */
@DisplayName("MappingDslIntegrationTest - Mapping DSL model tests")
class MappingDslIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // FieldSpec
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("FieldSpec")
    class FieldSpecTests {

        @Test
        @DisplayName("should have sensible default values")
        void shouldHaveDefaults() {
            FieldSpec spec = new FieldSpec();
            assertThat(spec.getRequired()).isFalse();
            assertThat(spec.getMaxFallbacks()).isEqualTo(3);
            assertThat(spec.getCandidates()).isNull();
            assertThat(spec.getTo()).isNull();
            assertThat(spec.getDefaultValue()).isNull();
            assertThat(spec.getMap()).isNull();
        }

        @Test
        @DisplayName("should create with candidates and type")
        void shouldCreateWithCandidatesAndType() {
            FieldSpec spec = new FieldSpec(List.of("@.name", "@.fullName"), "string");
            assertThat(spec.getCandidates()).containsExactly("@.name", "@.fullName");
            assertThat(spec.getTo()).isEqualTo("string");
        }

        @Test
        @DisplayName("should create with all parameters")
        void shouldCreateWithAllParams() {
            FieldSpec spec = new FieldSpec(
                    List.of("@.status"),
                    "string",
                    "unknown",
                    true
            );
            assertThat(spec.getCandidates()).containsExactly("@.status");
            assertThat(spec.getTo()).isEqualTo("string");
            assertThat(spec.getDefaultValue()).isEqualTo("unknown");
            assertThat(spec.getRequired()).isTrue();
        }

        @Test
        @DisplayName("should serialize to JSON and back")
        void shouldSerializeAndDeserialize() throws Exception {
            FieldSpec spec = new FieldSpec(List.of("@.id", "@.code"), "integer");
            spec.setRequired(true);
            spec.setDefaultValue(0);
            spec.setMaxFallbacks(2);

            String json = objectMapper.writeValueAsString(spec);
            FieldSpec restored = objectMapper.readValue(json, FieldSpec.class);

            assertThat(restored.getCandidates()).containsExactly("@.id", "@.code");
            assertThat(restored.getTo()).isEqualTo("integer");
            assertThat(restored.getRequired()).isTrue();
            assertThat(restored.getMaxFallbacks()).isEqualTo(2);
        }

        @Test
        @DisplayName("should support map property for collection fields")
        void shouldSupportMapProperty() {
            FieldSpec spec = new FieldSpec();
            spec.setMap(Map.of("key", "@.field_key", "value", "@.field_value"));

            assertThat(spec.getMap()).hasSize(2);
            assertThat(spec.getMap().get("key")).isEqualTo("@.field_key");
        }

        @Test
        @DisplayName("toString should include all fields")
        void toStringShouldIncludeAllFields() {
            FieldSpec spec = new FieldSpec(List.of("@.name"), "string");
            String str = spec.toString();
            assertThat(str).contains("candidates");
            assertThat(str).contains("string");
        }
    }

    // -------------------------------------------------------------------------
    // SourceSpec
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("SourceSpec")
    class SourceSpecTests {

        @Test
        @DisplayName("should have null defaults")
        void shouldHaveNullDefaults() {
            SourceSpec spec = new SourceSpec();
            assertThat(spec.getFormat()).isNull();
            assertThat(spec.getRoot()).isNull();
            assertThat(spec.getItemsPath()).isNull();
            assertThat(spec.getStrictMode()).isTrue(); // strict mode defaults to true
        }

        @Test
        @DisplayName("should create with format and root")
        void shouldCreateWithFormatAndRoot() {
            SourceSpec spec = new SourceSpec("json", "$.data");
            assertThat(spec.getFormat()).isEqualTo("json");
            assertThat(spec.getRoot()).isEqualTo("$.data");
        }

        @Test
        @DisplayName("should support root alternatives")
        void shouldSupportRootAlternatives() {
            SourceSpec spec = new SourceSpec();
            spec.setRootAlternatives(List.of("$.data.items[*]", "$.results[*]"));

            assertThat(spec.getRootAlternatives()).containsExactly("$.data.items[*]", "$.results[*]");
        }

        @Test
        @DisplayName("getRootAlternatives should fall back to rootAnyOf for compatibility")
        void shouldFallBackToRootAnyOf() {
            SourceSpec spec = new SourceSpec();
            spec.setRootAnyOf(List.of("$.legacy_path[*]"));
            // rootAlternatives not set, so getRootAlternatives falls back to rootAnyOf
            assertThat(spec.getRootAlternatives()).containsExactly("$.legacy_path[*]");
        }

        @Test
        @DisplayName("should serialize to JSON and back")
        void shouldSerializeAndDeserialize() throws Exception {
            SourceSpec spec = new SourceSpec("json", "$.data");
            spec.setItemsPath("$.data.items[*]");
            spec.setRootAlternatives(List.of("$.results[*]"));
            spec.setStrictMode(false);

            String json = objectMapper.writeValueAsString(spec);
            SourceSpec restored = objectMapper.readValue(json, SourceSpec.class);

            assertThat(restored.getFormat()).isEqualTo("json");
            assertThat(restored.getRoot()).isEqualTo("$.data");
            assertThat(restored.getItemsPath()).isEqualTo("$.data.items[*]");
            assertThat(restored.getStrictMode()).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // MappingSpec
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("MappingSpec")
    class MappingSpecTests {

        @Test
        @DisplayName("should compose source and fields")
        void shouldComposeSourceAndFields() {
            MappingSpec spec = new MappingSpec();

            SourceSpec source = new SourceSpec("json", "$.data");
            source.setItemsPath("$.data.users[*]");
            spec.setSource(source);

            Map<String, FieldSpec> fields = new HashMap<>();
            fields.put("name", new FieldSpec(List.of("@.name", "@.fullName"), "string"));
            fields.put("age", new FieldSpec(List.of("@.age"), "integer"));
            spec.setFields(fields);

            assertThat(spec.getSource().getFormat()).isEqualTo("json");
            assertThat(spec.getSource().getItemsPath()).isEqualTo("$.data.users[*]");
            assertThat(spec.getFields()).hasSize(2);
            assertThat(spec.getFields().get("name").getCandidates()).contains("@.name");
        }

        @Test
        @DisplayName("should support globals section")
        void shouldSupportGlobals() {
            MappingSpec spec = new MappingSpec();

            Map<String, FieldSpec> globals = new HashMap<>();
            globals.put("apiVersion", new FieldSpec(List.of("$.meta.version"), "string"));
            spec.setGlobals(globals);

            assertThat(spec.getGlobals()).hasSize(1);
            assertThat(spec.getGlobals().get("apiVersion").getCandidates())
                    .containsExactly("$.meta.version");
        }

        @Test
        @DisplayName("should serialize complete mapping spec to JSON and back")
        void shouldSerializeComplete() throws Exception {
            MappingSpec spec = new MappingSpec();

            SourceSpec source = new SourceSpec("json", "$.root");
            source.setItemsPath("$.root.items[*]");
            spec.setSource(source);

            Map<String, FieldSpec> fields = new HashMap<>();
            FieldSpec nameField = new FieldSpec(List.of("@.name"), "string");
            nameField.setRequired(true);
            fields.put("name", nameField);

            FieldSpec idField = new FieldSpec(List.of("@.id"), "integer");
            idField.setRequired(true);
            fields.put("id", idField);
            spec.setFields(fields);

            String json = objectMapper.writeValueAsString(spec);
            MappingSpec restored = objectMapper.readValue(json, MappingSpec.class);

            assertThat(restored.getSource().getFormat()).isEqualTo("json");
            assertThat(restored.getSource().getItemsPath()).isEqualTo("$.root.items[*]");
            assertThat(restored.getFields()).hasSize(2);
            assertThat(restored.getFields().get("name").getRequired()).isTrue();
            assertThat(restored.getFields().get("id").getTo()).isEqualTo("integer");
        }
    }
}
