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
 * Unit tests for CapabilityRequest record.
 *
 * CapabilityRequest is a DTO for capability search requests.
 */
@DisplayName("CapabilityRequest")
class CapabilityRequestTest {

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
            Map<String, String> hints = Map.of("action", "search");

            CapabilityRequest request = new CapabilityRequest("query", 25, hints, false);

            assertEquals("query", request.q());
            assertEquals(25, request.k());
            assertEquals(hints, request.hints());
            assertFalse(request.useOpenAI());
        }

        @Test
        @DisplayName("should set default k value when null")
        void shouldSetDefaultKValueWhenNull() {
            CapabilityRequest request = new CapabilityRequest("query", null, Map.of(), true);

            assertEquals(12, request.k());
        }

        @Test
        @DisplayName("should set empty hints when null")
        void shouldSetEmptyHintsWhenNull() {
            CapabilityRequest request = new CapabilityRequest("query", 10, null, true);

            assertNotNull(request.hints());
            assertTrue(request.hints().isEmpty());
        }

        @Test
        @DisplayName("should set default useOpenAI when null")
        void shouldSetDefaultUseOpenAIWhenNull() {
            CapabilityRequest request = new CapabilityRequest("query", 10, Map.of(), null);

            assertTrue(request.useOpenAI());
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
            CapabilityRequest request = new CapabilityRequest("valid query", 10, Map.of(), true);

            Set<ConstraintViolation<CapabilityRequest>> violations = validator.validate(request);

            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("should fail validation when query is blank")
        void shouldFailValidationWhenQueryIsBlank() {
            CapabilityRequest request = new CapabilityRequest("", 10, Map.of(), true);

            Set<ConstraintViolation<CapabilityRequest>> violations = validator.validate(request);

            assertFalse(violations.isEmpty());
            assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("q")));
        }

        @Test
        @DisplayName("should fail validation when k is less than 1")
        void shouldFailValidationWhenKIsLessThan1() {
            CapabilityRequest request = new CapabilityRequest("query", 0, Map.of(), true);

            Set<ConstraintViolation<CapabilityRequest>> violations = validator.validate(request);

            assertFalse(violations.isEmpty());
            assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("k")));
        }

        @Test
        @DisplayName("should fail validation when k is greater than 50")
        void shouldFailValidationWhenKIsGreaterThan50() {
            CapabilityRequest request = new CapabilityRequest("query", 51, Map.of(), true);

            Set<ConstraintViolation<CapabilityRequest>> violations = validator.validate(request);

            assertFalse(violations.isEmpty());
            assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("k")));
        }

        @Test
        @DisplayName("should pass validation with k at boundary values")
        void shouldPassValidationWithKAtBoundaryValues() {
            CapabilityRequest request1 = new CapabilityRequest("query", 1, Map.of(), true);
            CapabilityRequest request50 = new CapabilityRequest("query", 50, Map.of(), true);

            assertTrue(validator.validate(request1).isEmpty());
            assertTrue(validator.validate(request50).isEmpty());
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
            Map<String, String> hints = Map.of("action", "create", "resource", "post");
            CapabilityRequest request = new CapabilityRequest("query", 10, hints, true);

            assertEquals("create", request.getHint("action"));
            assertEquals("post", request.getHint("resource"));
            assertNull(request.getHint("nonexistent"));
        }

        @Test
        @DisplayName("should get action hint")
        void shouldGetActionHint() {
            CapabilityRequest request = new CapabilityRequest("query", 10, Map.of("action", "delete"), true);

            assertEquals("delete", request.getAction());
        }

        @Test
        @DisplayName("should get resource hint")
        void shouldGetResourceHint() {
            CapabilityRequest request = new CapabilityRequest("query", 10, Map.of("resource", "user"), true);

            assertEquals("user", request.getResource());
        }

        @Test
        @DisplayName("should get provider hint")
        void shouldGetProviderHint() {
            CapabilityRequest request = new CapabilityRequest("query", 10, Map.of("provider", "github"), true);

            assertEquals("github", request.getProvider());
        }

        @Test
        @DisplayName("should return null for missing hints")
        void shouldReturnNullForMissingHints() {
            CapabilityRequest request = new CapabilityRequest("query", 10, Map.of(), true);

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

            CapabilityRequest req1 = new CapabilityRequest("query", 10, hints, true);
            CapabilityRequest req2 = new CapabilityRequest("query", 10, hints, true);

            assertEquals(req1, req2);
            assertEquals(req1.hashCode(), req2.hashCode());
        }
    }
}
