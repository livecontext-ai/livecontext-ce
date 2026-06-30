package com.apimarketplace.common.storage.dto;

import com.apimarketplace.common.storage.dto.MappingSpec.FieldSpec;
import com.apimarketplace.common.storage.dto.MappingSpec.SourceSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MappingSpec DTO Tests")
class MappingSpecTest {

    @Nested
    @DisplayName("MappingSpec")
    class MappingSpecMainTests {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefaultConstructor() {
            MappingSpec spec = new MappingSpec();

            assertThat(spec.getSource()).isNull();
            assertThat(spec.getFields()).isNull();
            assertThat(spec.getGlobals()).isNull();
        }

        @Test
        @DisplayName("should create with all-args constructor")
        void shouldCreateWithAllArgsConstructor() {
            SourceSpec source = new SourceSpec();
            Map<String, FieldSpec> fields = Map.of("name", new FieldSpec());
            Map<String, FieldSpec> globals = Map.of("total", new FieldSpec());

            MappingSpec spec = new MappingSpec(source, fields, globals);

            assertThat(spec.getSource()).isSameAs(source);
            assertThat(spec.getFields()).hasSize(1);
            assertThat(spec.getGlobals()).hasSize(1);
        }

        @Test
        @DisplayName("should set and get source")
        void shouldSetAndGetSource() {
            MappingSpec spec = new MappingSpec();
            SourceSpec source = new SourceSpec();
            source.setFormat("json");
            spec.setSource(source);

            assertThat(spec.getSource()).isSameAs(source);
            assertThat(spec.getSource().getFormat()).isEqualTo("json");
        }

        @Test
        @DisplayName("should set and get fields")
        void shouldSetAndGetFields() {
            MappingSpec spec = new MappingSpec();
            FieldSpec field = new FieldSpec();
            field.setTo("name");
            Map<String, FieldSpec> fields = Map.of("name", field);
            spec.setFields(fields);

            assertThat(spec.getFields()).containsKey("name");
            assertThat(spec.getFields().get("name").getTo()).isEqualTo("name");
        }

        @Test
        @DisplayName("should set and get globals")
        void shouldSetAndGetGlobals() {
            MappingSpec spec = new MappingSpec();
            FieldSpec global = new FieldSpec();
            global.setTo("total");
            Map<String, FieldSpec> globals = Map.of("total", global);
            spec.setGlobals(globals);

            assertThat(spec.getGlobals()).containsKey("total");
        }
    }

    @Nested
    @DisplayName("SourceSpec")
    class SourceSpecTests {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefaultConstructor() {
            SourceSpec source = new SourceSpec();

            assertThat(source.getFormat()).isNull();
            assertThat(source.getRoot()).isNull();
            assertThat(source.getItemsPath()).isNull();
            assertThat(source.getRootAlternatives()).isNull();
        }

        @Test
        @DisplayName("should set and get format")
        void shouldSetAndGetFormat() {
            SourceSpec source = new SourceSpec();
            source.setFormat("json");

            assertThat(source.getFormat()).isEqualTo("json");
        }

        @Test
        @DisplayName("should set and get root")
        void shouldSetAndGetRoot() {
            SourceSpec source = new SourceSpec();
            source.setRoot("$.data");

            assertThat(source.getRoot()).isEqualTo("$.data");
        }

        @Test
        @DisplayName("should set and get itemsPath")
        void shouldSetAndGetItemsPath() {
            SourceSpec source = new SourceSpec();
            source.setItemsPath("$.data.items");

            assertThat(source.getItemsPath()).isEqualTo("$.data.items");
        }

        @Test
        @DisplayName("should set and get rootAlternatives")
        void shouldSetAndGetRootAlternatives() {
            SourceSpec source = new SourceSpec();
            List<String> alternatives = List.of("$.results", "$.items");
            source.setRootAlternatives(alternatives);

            assertThat(source.getRootAlternatives()).containsExactly("$.results", "$.items");
        }
    }

    @Nested
    @DisplayName("FieldSpec")
    class FieldSpecTests {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefaultConstructor() {
            FieldSpec field = new FieldSpec();

            assertThat(field.getCandidates()).isNull();
            assertThat(field.getTo()).isNull();
            assertThat(field.getDefaultValue()).isNull();
            assertThat(field.getRequired()).isNull();
        }

        @Test
        @DisplayName("should set and get candidates")
        void shouldSetAndGetCandidates() {
            FieldSpec field = new FieldSpec();
            List<String> candidates = List.of("@.name", "@.title", "$.data.name");
            field.setCandidates(candidates);

            assertThat(field.getCandidates()).containsExactly("@.name", "@.title", "$.data.name");
        }

        @Test
        @DisplayName("should set and get to")
        void shouldSetAndGetTo() {
            FieldSpec field = new FieldSpec();
            field.setTo("display_name");

            assertThat(field.getTo()).isEqualTo("display_name");
        }

        @Test
        @DisplayName("should set and get defaultValue")
        void shouldSetAndGetDefaultValue() {
            FieldSpec field = new FieldSpec();
            field.setDefaultValue("N/A");

            assertThat(field.getDefaultValue()).isEqualTo("N/A");
        }

        @Test
        @DisplayName("should set and get defaultValue as number")
        void shouldSetAndGetDefaultValueAsNumber() {
            FieldSpec field = new FieldSpec();
            field.setDefaultValue(0);

            assertThat(field.getDefaultValue()).isEqualTo(0);
        }

        @Test
        @DisplayName("should set and get required")
        void shouldSetAndGetRequired() {
            FieldSpec field = new FieldSpec();
            field.setRequired(true);

            assertThat(field.getRequired()).isTrue();
        }

        @Test
        @DisplayName("should allow required to be null (optional)")
        void shouldAllowRequiredToBeNull() {
            FieldSpec field = new FieldSpec();

            assertThat(field.getRequired()).isNull();
        }
    }
}
