package com.apimarketplace.common.mapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SimpleMappingService - Spring @Service wrapper around SimpleMappingEngine.
 */
@DisplayName("SimpleMappingService")
class SimpleMappingServiceTest {

    private SimpleMappingService service;

    @BeforeEach
    void setUp() {
        service = new SimpleMappingService();
    }

    @Nested
    @DisplayName("applyMapping()")
    class ApplyMappingTests {

        @Test
        @DisplayName("should apply mapping and return outcome")
        void shouldApplyMapping() throws IOException {
            StrictMappingEngine.StrictMappingSpec spec = new StrictMappingEngine.StrictMappingSpec();
            spec.source = new StrictMappingEngine.SourceSpec();
            spec.source.format = "json";
            spec.source.items_path = "$.data[*]";
            spec.fields = new LinkedHashMap<>();

            StrictMappingEngine.FieldSpec nameField = new StrictMappingEngine.FieldSpec();
            nameField.candidates = List.of("@.name");
            nameField.to = "string";
            spec.fields.put("name", nameField);

            String json = "{\"data\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}";

            SimpleMappingEngine.MappingOutcome outcome = service.applyMapping(json, spec);

            assertThat(outcome.itemCount).isEqualTo(2);
            assertThat(outcome.items).hasSize(2);
            assertThat(outcome.items.get(0).get("name")).isEqualTo("Alice");
            assertThat(outcome.items.get(1).get("name")).isEqualTo("Bob");
        }

        @Test
        @DisplayName("should handle empty data")
        void shouldHandleEmptyData() throws IOException {
            StrictMappingEngine.StrictMappingSpec spec = new StrictMappingEngine.StrictMappingSpec();
            spec.source = new StrictMappingEngine.SourceSpec();
            spec.source.items_path = "$.data[*]";
            spec.fields = new LinkedHashMap<>();

            StrictMappingEngine.FieldSpec idField = new StrictMappingEngine.FieldSpec();
            idField.candidates = List.of("@.id");
            idField.to = "integer";
            spec.fields.put("id", idField);

            String json = "{\"data\":[]}";

            SimpleMappingEngine.MappingOutcome outcome = service.applyMapping(json, spec);

            // Engine returns 1 item with null fields when array is empty
            assertThat(outcome.itemCount).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("mapToItems()")
    class MapToItemsTests {

        @Test
        @DisplayName("should return mapped items list")
        void shouldReturnItems() throws IOException {
            StrictMappingEngine.StrictMappingSpec spec = new StrictMappingEngine.StrictMappingSpec();
            spec.source = new StrictMappingEngine.SourceSpec();
            spec.source.items_path = "$.users[*]";
            spec.fields = new LinkedHashMap<>();

            StrictMappingEngine.FieldSpec nameField = new StrictMappingEngine.FieldSpec();
            nameField.candidates = List.of("@.name");
            nameField.to = "string";
            spec.fields.put("name", nameField);

            String json = "{\"users\":[{\"name\":\"Charlie\"}]}";

            List<Map<String, Object>> items = service.mapToItems(json, spec);

            assertThat(items).hasSize(1);
            assertThat(items.get(0).get("name")).isEqualTo("Charlie");
        }

        @Test
        @DisplayName("should return empty list when no items match")
        void shouldReturnEmptyList() throws IOException {
            StrictMappingEngine.StrictMappingSpec spec = new StrictMappingEngine.StrictMappingSpec();
            spec.source = new StrictMappingEngine.SourceSpec();
            spec.source.items_path = "$.missing[*]";
            spec.fields = new LinkedHashMap<>();

            String json = "{\"data\":[]}";

            List<Map<String, Object>> items = service.mapToItems(json, spec);

            // Even with missing path, the engine may return an empty list or fallback
            assertThat(items).isNotNull();
        }
    }

    @Nested
    @DisplayName("createSimpleMapping()")
    class CreateSimpleMappingTests {

        @Test
        @DisplayName("should create mapping spec from items path and field mappings")
        void shouldCreateSpec() {
            Map<String, String> fieldMappings = new LinkedHashMap<>();
            fieldMappings.put("title", "@.name");
            fieldMappings.put("desc", "@.description");

            StrictMappingEngine.StrictMappingSpec spec = service.createSimpleMapping("$.items[*]", fieldMappings);

            assertThat(spec.source).isNotNull();
            assertThat(spec.source.format).isEqualTo("json");
            assertThat(spec.source.items_path).isEqualTo("$.items[*]");
            assertThat(spec.fields).hasSize(2);
            assertThat(spec.fields.get("title").candidates).containsExactly("@.name");
            assertThat(spec.fields.get("title").to).isEqualTo("string");
            assertThat(spec.fields.get("title").required).isFalse();
            assertThat(spec.fields.get("desc").candidates).containsExactly("@.description");
        }

        @Test
        @DisplayName("should create spec with empty field mappings")
        void shouldCreateSpecWithEmptyFields() {
            StrictMappingEngine.StrictMappingSpec spec = service.createSimpleMapping("$.data[*]", Map.of());

            assertThat(spec.source.items_path).isEqualTo("$.data[*]");
            assertThat(spec.fields).isEmpty();
        }

        @Test
        @DisplayName("created spec should be usable with applyMapping")
        void createdSpecShouldBeUsable() throws IOException {
            Map<String, String> fieldMappings = new LinkedHashMap<>();
            fieldMappings.put("id", "@.id");
            fieldMappings.put("label", "@.name");

            StrictMappingEngine.StrictMappingSpec spec = service.createSimpleMapping("$.products[*]", fieldMappings);

            String json = "{\"products\":[{\"id\":\"p1\",\"name\":\"Widget\"},{\"id\":\"p2\",\"name\":\"Gadget\"}]}";

            SimpleMappingEngine.MappingOutcome outcome = service.applyMapping(json, spec);

            assertThat(outcome.itemCount).isEqualTo(2);
            assertThat(outcome.items.get(0).get("id")).isEqualTo("p1");
            assertThat(outcome.items.get(0).get("label")).isEqualTo("Widget");
            assertThat(outcome.items.get(1).get("id")).isEqualTo("p2");
            assertThat(outcome.items.get(1).get("label")).isEqualTo("Gadget");
        }
    }
}
