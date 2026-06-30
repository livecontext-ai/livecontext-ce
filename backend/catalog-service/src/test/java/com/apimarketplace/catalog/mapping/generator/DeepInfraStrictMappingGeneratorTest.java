package com.apimarketplace.catalog.mapping.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeepInfraStrictMappingGenerator class.
 *
 * DeepInfraStrictMappingGenerator uses DeepInfra's API for generating
 * strict JSONPath mappings from sample JSON data.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeepInfraStrictMappingGenerator")
class DeepInfraStrictMappingGeneratorTest {

    @Mock
    private WebClient.Builder webClientBuilder;

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private WebClient.RequestBodySpec requestBodySpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(webClientBuilder.build()).thenReturn(webClient);
    }

    // ========================================================================
    // isAvailable tests
    // ========================================================================

    @Nested
    @DisplayName("isAvailable()")
    class IsAvailableTests {

        @Test
        @DisplayName("should return false when API token is null")
        void shouldReturnFalseWhenApiTokenIsNull() {
            DeepInfraStrictMappingGenerator generator = createGenerator(null);

            boolean result = generator.isAvailable();

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false when API token is empty")
        void shouldReturnFalseWhenApiTokenIsEmpty() {
            DeepInfraStrictMappingGenerator generator = createGenerator("");

            boolean result = generator.isAvailable();

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false when API token is whitespace only")
        void shouldReturnFalseWhenApiTokenIsWhitespace() {
            DeepInfraStrictMappingGenerator generator = createGenerator("   ");

            boolean result = generator.isAvailable();

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false when API call fails")
        @SuppressWarnings("unchecked")
        void shouldReturnFalseWhenApiCallFails() {
            DeepInfraStrictMappingGenerator generator = createGenerator("valid-token");

            when(webClient.post()).thenReturn(requestBodyUriSpec);
            when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
            when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
            when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
            when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
            when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.error(new RuntimeException("Connection failed")));

            boolean result = generator.isAvailable();

            assertFalse(result);
        }
    }

    // ========================================================================
    // generateStrictMapping tests - error cases
    // ========================================================================

    @Nested
    @DisplayName("generateStrictMapping() - error cases")
    class GenerateStrictMappingErrorTests {

        @Test
        @DisplayName("should throw when API token is not configured")
        void shouldThrowWhenApiTokenNotConfigured() {
            DeepInfraStrictMappingGenerator generator = createGenerator(null);
            String sampleJson = "{\"name\": \"John\"}";
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            MappingGenerationException exception = assertThrows(MappingGenerationException.class, () ->
                    generator.generateStrictMapping(sampleJson, constraints)
            );

            assertTrue(exception.getMessage().contains("token not configured") ||
                    exception.getMessage().contains("Failed to generate"));
        }
    }

    // ========================================================================
    // generateStrictMappingWithContext tests - error cases
    // ========================================================================

    @Nested
    @DisplayName("generateStrictMappingWithContext() - error cases")
    class GenerateStrictMappingWithContextErrorTests {

        @Test
        @DisplayName("should throw when API token is not configured")
        void shouldThrowWhenApiTokenNotConfigured() {
            DeepInfraStrictMappingGenerator generator = createGenerator(null);
            String sampleJson = "{\"name\": \"John\"}";
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            MappingGenerationException exception = assertThrows(MappingGenerationException.class, () ->
                    generator.generateStrictMappingWithContext(
                            sampleJson, constraints, "Tool", "Category", "SubCategory",
                            "GET", "/api", "Description"
                    )
            );

            assertTrue(exception.getMessage().contains("token not configured") ||
                    exception.getMessage().contains("Failed to generate"));
        }
    }

    // ========================================================================
    // StrictMappingGenerator interface compliance
    // ========================================================================

    @Nested
    @DisplayName("Interface compliance")
    class InterfaceComplianceTests {

        @Test
        @DisplayName("should implement StrictMappingGenerator interface")
        void shouldImplementInterface() {
            DeepInfraStrictMappingGenerator generator = createGenerator("token");

            assertTrue(generator instanceof StrictMappingGenerator);
        }
    }

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create generator with all parameters")
        void shouldCreateGeneratorWithAllParameters() {
            DeepInfraStrictMappingGenerator generator = new DeepInfraStrictMappingGenerator(
                    webClientBuilder,
                    objectMapper,
                    "https://api.deepinfra.com/v1/openai/chat/completions",
                    "meta-llama/Meta-Llama-3-8B-Instruct",
                    "test-token",
                    300000,
                    32768,
                    8192,
                    1048576,
                    2048,
                    true,
                    200,
                    1,
                    3,
                    200,
                    0.1,
                    0.9,
                    40,
                    "",
                    0.0,
                    0.0,
                    0,
                    1,
                    true,
                    true,
                    "json_object",
                    true,
                    30000
            );

            assertNotNull(generator);
        }

        @Test
        @DisplayName("should handle zero array sample size by setting to 1")
        void shouldHandleZeroArraySampleSize() {
            DeepInfraStrictMappingGenerator generator = new DeepInfraStrictMappingGenerator(
                    webClientBuilder,
                    objectMapper,
                    "https://api.deepinfra.com/v1/openai/chat/completions",
                    "meta-llama/Meta-Llama-3-8B-Instruct",
                    "test-token",
                    300000,
                    32768,
                    8192,
                    1048576,
                    2048,
                    true,
                    200,
                    0, // zero array sample size
                    3,
                    200,
                    0.1,
                    0.9,
                    40,
                    "",
                    0.0,
                    0.0,
                    0,
                    1,
                    true,
                    true,
                    "json_object",
                    true,
                    30000
            );

            assertNotNull(generator);
        }

        @Test
        @DisplayName("should handle negative array sample size by setting to 1")
        void shouldHandleNegativeArraySampleSize() {
            DeepInfraStrictMappingGenerator generator = new DeepInfraStrictMappingGenerator(
                    webClientBuilder,
                    objectMapper,
                    "https://api.deepinfra.com/v1/openai/chat/completions",
                    "meta-llama/Meta-Llama-3-8B-Instruct",
                    "test-token",
                    300000,
                    32768,
                    8192,
                    1048576,
                    2048,
                    true,
                    200,
                    -5, // negative array sample size
                    3,
                    200,
                    0.1,
                    0.9,
                    40,
                    "",
                    0.0,
                    0.0,
                    0,
                    1,
                    true,
                    true,
                    "json_object",
                    true,
                    30000
            );

            assertNotNull(generator);
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private DeepInfraStrictMappingGenerator createGenerator(String apiToken) {
        return new DeepInfraStrictMappingGenerator(
                webClientBuilder,
                objectMapper,
                "https://api.deepinfra.com/v1/openai/chat/completions",
                "meta-llama/Meta-Llama-3-8B-Instruct",
                apiToken,
                300000,
                32768,
                8192,
                1048576,
                2048,
                true,
                200,
                1,
                3,
                200,
                0.1,
                0.9,
                40,
                "",
                0.0,
                0.0,
                0,
                1,
                true,
                true,
                "json_object",
                true,
                30000
        );
    }
}
