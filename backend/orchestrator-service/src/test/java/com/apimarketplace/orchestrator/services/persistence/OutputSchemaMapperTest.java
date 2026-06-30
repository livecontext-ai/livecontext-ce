package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutputSchemaMapper")
class OutputSchemaMapperTest {

    @Mock
    private GenericOutputSchemaMapper genericMapper;

    private OutputSchemaMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OutputSchemaMapper(genericMapper);
    }

    @Nested
    @DisplayName("transformToDbSchema()")
    class TransformToDbSchemaTests {

        @Test
        @DisplayName("Should transform using GenericOutputSchemaMapper when definition exists")
        void shouldTransformUsingGenericMapper() {
            Map<String, Object> input = Map.of("raw", "data");
            Map<String, Object> transformed = Map.of("db", "schema");

            when(genericMapper.transform("DECISION", input)).thenReturn(transformed);

            Map<String, Object> result = mapper.transformToDbSchema(input, "DECISION");

            assertEquals(transformed, result);
        }

        @Test
        @DisplayName("Should return original when no definition found")
        void shouldReturnOriginalWhenNoDefinition() {
            Map<String, Object> input = Map.of("raw", "data");

            when(genericMapper.transform("UNKNOWN", input)).thenReturn(null);

            Map<String, Object> result = mapper.transformToDbSchema(input, "UNKNOWN");

            assertSame(input, result);
        }

        @Test
        @DisplayName("Should return null for null backendOutput")
        void shouldReturnNullForNullOutput() {
            Map<String, Object> result = mapper.transformToDbSchema(null, "DECISION");
            assertNull(result);
        }

        @Test
        @DisplayName("Should return original for null nodeType")
        void shouldReturnOriginalForNullNodeType() {
            Map<String, Object> input = Map.of("raw", "data");
            Map<String, Object> result = mapper.transformToDbSchema(input, null);
            assertSame(input, result);
        }
    }

    @Nested
    @DisplayName("hasMapper()")
    class HasMapperTests {

        @Test
        @DisplayName("Should return true when definition exists")
        void shouldReturnTrueWhenExists() {
            when(genericMapper.canHandle("DECISION")).thenReturn(true);
            assertTrue(mapper.hasMapper("DECISION"));
        }

        @Test
        @DisplayName("Should return false when no definition")
        void shouldReturnFalseWhenNoDefinition() {
            when(genericMapper.canHandle("UNKNOWN")).thenReturn(false);
            assertFalse(mapper.hasMapper("UNKNOWN"));
        }
    }
}
