package com.apimarketplace.catalog.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LexicalRequest record.
 *
 * LexicalRequest is a DTO for lexical search requests.
 */
@DisplayName("LexicalRequest")
class LexicalRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {

        @Test
        @DisplayName("should create request with all fields")
        void shouldCreateRequestWithAllFields() {
            Map<String, String> hints = Map.of("action", "get", "resource", "weather");

            LexicalRequest request = new LexicalRequest("search query", 20, hints);

            assertEquals("search query", request.q());
            assertEquals(20, request.k());
            assertEquals(hints, request.hints());
        }

        @Test
        @DisplayName("should set default k value when null")
        void shouldSetDefaultKValueWhenNull() {
            LexicalRequest request = new LexicalRequest("query", null, Map.of());

            assertEquals(12, request.k());
        }

        @Test
        @DisplayName("should set empty hints when null")
        void shouldSetEmptyHintsWhenNull() {
            LexicalRequest request = new LexicalRequest("query", 10, null);

            assertNotNull(request.hints());
            assertTrue(request.hints().isEmpty());
        }
    }

    // ========================================================================
    // Validation tests
    // ========================================================================

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("should pass validation with valid data")
        void shouldPassValidationWithValidData() {
            LexicalRequest request = new LexicalRequest("valid query", 10, Map.of());

            Set<ConstraintViolation<LexicalRequest>> violations = validator.validate(request);

            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("should fail validation when query is blank")
        void shouldFailValidationWhenQueryIsBlank() {
            LexicalRequest request = new LexicalRequest("", 10, Map.of());

            Set<ConstraintViolation<LexicalRequest>> violations = validator.validate(request);

            assertFalse(violations.isEmpty());
            assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("q")));
        }

        @Test
        @DisplayName("should fail validation when k is less than 1")
        void shouldFailValidationWhenKIsLessThan1() {
            LexicalRequest request = new LexicalRequest("query", 0, Map.of());

            Set<ConstraintViolation<LexicalRequest>> violations = validator.validate(request);

            assertFalse(violations.isEmpty());
            assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("k")));
        }

        @Test
        @DisplayName("should fail validation when k is greater than 100")
        void shouldFailValidationWhenKIsGreaterThan100() {
            LexicalRequest request = new LexicalRequest("query", 101, Map.of());

            Set<ConstraintViolation<LexicalRequest>> violations = validator.validate(request);

            assertFalse(violations.isEmpty());
            assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("k")));
        }

        @Test
        @DisplayName("should pass validation with k at boundary values")
        void shouldPassValidationWithKAtBoundaryValues() {
            LexicalRequest request1 = new LexicalRequest("query", 1, Map.of());
            LexicalRequest request100 = new LexicalRequest("query", 100, Map.of());

            assertTrue(validator.validate(request1).isEmpty());
            assertTrue(validator.validate(request100).isEmpty());
        }
    }

    // ========================================================================
    // Hint accessor tests
    // ========================================================================

    @Nested
    @DisplayName("Hint accessors")
    class HintAccessorTests {

        @Test
        @DisplayName("should get hint by key")
        void shouldGetHintByKey() {
            Map<String, String> hints = Map.of("action", "search", "resource", "tools");
            LexicalRequest request = new LexicalRequest("query", 10, hints);

            assertEquals("search", request.getHint("action"));
            assertEquals("tools", request.getHint("resource"));
            assertNull(request.getHint("nonexistent"));
        }

        @Test
        @DisplayName("should get action hint")
        void shouldGetActionHint() {
            LexicalRequest request = new LexicalRequest("query", 10, Map.of("action", "get"));

            assertEquals("get", request.getAction());
        }

        @Test
        @DisplayName("should get resource hint")
        void shouldGetResourceHint() {
            LexicalRequest request = new LexicalRequest("query", 10, Map.of("resource", "weather"));

            assertEquals("weather", request.getResource());
        }

        @Test
        @DisplayName("should get provider hint")
        void shouldGetProviderHint() {
            LexicalRequest request = new LexicalRequest("query", 10, Map.of("provider", "openweather"));

            assertEquals("openweather", request.getProvider());
        }

        @Test
        @DisplayName("should return null for missing hints")
        void shouldReturnNullForMissingHints() {
            LexicalRequest request = new LexicalRequest("query", 10, Map.of());

            assertNull(request.getAction());
            assertNull(request.getResource());
            assertNull(request.getProvider());
        }
    }

    // ========================================================================
    // Record equality tests
    // ========================================================================

    @Nested
    @DisplayName("Record equality")
    class EqualityTests {

        @Test
        @DisplayName("should be equal for same values")
        void shouldBeEqualForSameValues() {
            Map<String, String> hints = Map.of("action", "get");

            LexicalRequest req1 = new LexicalRequest("query", 10, hints);
            LexicalRequest req2 = new LexicalRequest("query", 10, hints);

            assertEquals(req1, req2);
            assertEquals(req1.hashCode(), req2.hashCode());
        }
    }
}
