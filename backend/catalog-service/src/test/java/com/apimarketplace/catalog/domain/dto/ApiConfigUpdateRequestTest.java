package com.apimarketplace.catalog.domain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiConfigUpdateRequest record.
 *
 * ApiConfigUpdateRequest is a DTO for updating API configuration.
 */
@DisplayName("ApiConfigUpdateRequest")
class ApiConfigUpdateRequestTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class RecordConstructionTests {

        @Test
        @DisplayName("should create request with all fields")
        void shouldCreateRequestWithAllFields() {
            // Arrange
            String baseUrl = "https://api.example.com";
            String healthcheckEndpoint = "/health";
            String visibility = "public";
            String authType = "bearer";
            String authHeaderName = "Authorization";
            String authHeaderValue = "Bearer token123";

            // Act
            ApiConfigUpdateRequest request = new ApiConfigUpdateRequest(
                baseUrl, healthcheckEndpoint, visibility, authType, authHeaderName, authHeaderValue
            );

            // Assert
            assertEquals(baseUrl, request.baseUrl());
            assertEquals(healthcheckEndpoint, request.healthcheckEndpoint());
            assertEquals(visibility, request.visibility());
            assertEquals(authType, request.authType());
            assertEquals(authHeaderName, request.authHeaderName());
            assertEquals(authHeaderValue, request.authHeaderValue());
        }

        @Test
        @DisplayName("should create request with null fields")
        void shouldCreateRequestWithNullFields() {
            // Act
            ApiConfigUpdateRequest request = new ApiConfigUpdateRequest(
                null, null, null, null, null, null
            );

            // Assert
            assertNull(request.baseUrl());
            assertNull(request.healthcheckEndpoint());
            assertNull(request.visibility());
            assertNull(request.authType());
            assertNull(request.authHeaderName());
            assertNull(request.authHeaderValue());
        }

        @Test
        @DisplayName("should create request with partial fields")
        void shouldCreateRequestWithPartialFields() {
            // Arrange
            String baseUrl = "https://api.test.com";
            String authType = "api-key";

            // Act
            ApiConfigUpdateRequest request = new ApiConfigUpdateRequest(
                baseUrl, null, null, authType, null, null
            );

            // Assert
            assertEquals(baseUrl, request.baseUrl());
            assertNull(request.healthcheckEndpoint());
            assertNull(request.visibility());
            assertEquals(authType, request.authType());
            assertNull(request.authHeaderName());
            assertNull(request.authHeaderValue());
        }
    }

    // ========================================================================
    // Equals and hashCode tests
    // ========================================================================

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("should be equal for identical records")
        void shouldBeEqualForIdenticalRecords() {
            // Arrange
            ApiConfigUpdateRequest request1 = new ApiConfigUpdateRequest(
                "https://api.com", "/health", "public", "bearer", "Auth", "token"
            );
            ApiConfigUpdateRequest request2 = new ApiConfigUpdateRequest(
                "https://api.com", "/health", "public", "bearer", "Auth", "token"
            );

            // Assert
            assertEquals(request1, request2);
            assertEquals(request1.hashCode(), request2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different records")
        void shouldNotBeEqualForDifferentRecords() {
            // Arrange
            ApiConfigUpdateRequest request1 = new ApiConfigUpdateRequest(
                "https://api1.com", "/health", "public", "bearer", "Auth", "token"
            );
            ApiConfigUpdateRequest request2 = new ApiConfigUpdateRequest(
                "https://api2.com", "/health", "public", "bearer", "Auth", "token"
            );

            // Assert
            assertNotEquals(request1, request2);
        }

        @Test
        @DisplayName("should handle all null values in equals")
        void shouldHandleAllNullValuesInEquals() {
            // Arrange
            ApiConfigUpdateRequest request1 = new ApiConfigUpdateRequest(
                null, null, null, null, null, null
            );
            ApiConfigUpdateRequest request2 = new ApiConfigUpdateRequest(
                null, null, null, null, null, null
            );

            // Assert
            assertEquals(request1, request2);
            assertEquals(request1.hashCode(), request2.hashCode());
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("should generate meaningful toString")
        void shouldGenerateMeaningfulToString() {
            // Arrange
            ApiConfigUpdateRequest request = new ApiConfigUpdateRequest(
                "https://api.example.com", "/health", "private", "api-key", "X-API-Key", "secret123"
            );

            // Act
            String result = request.toString();

            // Assert
            assertNotNull(result);
            assertTrue(result.contains("https://api.example.com"));
            assertTrue(result.contains("/health"));
            assertTrue(result.contains("private"));
        }
    }
}
