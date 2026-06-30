package com.apimarketplace.catalog.mapping.runner;

import com.apimarketplace.catalog.mapping.generator.MappingGenerationException;
import com.apimarketplace.catalog.mapping.generator.StrictMappingConstraints;
import com.apimarketplace.catalog.mapping.service.MappingGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MappingGeneratorTestRunner")
class MappingGeneratorTestRunnerTest {

    @Mock
    private MappingGeneratorService mappingGeneratorService;

    private MappingGeneratorTestRunner runner;

    @BeforeEach
    void setUp() {
        runner = new MappingGeneratorTestRunner();
        ReflectionTestUtils.setField(runner, "mappingGeneratorService", mappingGeneratorService);
    }

    @Nested
    @DisplayName("run")
    class RunTests {

        @Test
        @DisplayName("should generate mappings successfully with default and specific constraints")
        void generatesMappingsSuccessfully() throws Exception {
            when(mappingGeneratorService.generateStrictMapping(anyString()))
                    .thenReturn("{\"source\":{\"format\":\"json\"}}");
            when(mappingGeneratorService.generateStrictMapping(anyString(), any(StrictMappingConstraints.class)))
                    .thenReturn("{\"source\":{\"format\":\"json\",\"items_path\":\"$.data\"}}");

            assertDoesNotThrow(() -> runner.run());

            verify(mappingGeneratorService).generateStrictMapping(anyString());
            verify(mappingGeneratorService).generateStrictMapping(anyString(), any(StrictMappingConstraints.class));
        }

        @Test
        @DisplayName("should handle MappingGenerationException gracefully")
        void handlesMappingGenerationException() throws Exception {
            when(mappingGeneratorService.generateStrictMapping(anyString()))
                    .thenThrow(new MappingGenerationException("AI model unavailable"));

            assertDoesNotThrow(() -> runner.run());
        }

        @Test
        @DisplayName("should handle unexpected exceptions gracefully")
        void handlesUnexpectedException() throws Exception {
            when(mappingGeneratorService.generateStrictMapping(anyString()))
                    .thenThrow(new RuntimeException("Unexpected error"));

            assertDoesNotThrow(() -> runner.run());
        }
    }
}
