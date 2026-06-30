package com.apimarketplace.catalog.domain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiBasicInfoUpdateRequest record.
 *
 * ApiBasicInfoUpdateRequest is a DTO for updating API basic information.
 */
@DisplayName("ApiBasicInfoUpdateRequest")
class ApiBasicInfoUpdateRequestTest {

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
            String description = "Updated API description";
            String category = "Data";
            String subcategory = "Weather";

            // Act
            ApiBasicInfoUpdateRequest request = new ApiBasicInfoUpdateRequest(
                description, category, subcategory
            );

            // Assert
            assertEquals(description, request.description());
            assertEquals(category, request.category());
            assertEquals(subcategory, request.subcategory());
        }

        @Test
        @DisplayName("should create request with null fields")
        void shouldCreateRequestWithNullFields() {
            // Act
            ApiBasicInfoUpdateRequest request = new ApiBasicInfoUpdateRequest(
                null, null, null
            );

            // Assert
            assertNull(request.description());
            assertNull(request.category());
            assertNull(request.subcategory());
        }

        @Test
        @DisplayName("should create request with only description")
        void shouldCreateRequestWithOnlyDescription() {
            // Arrange
            String description = "New description";

            // Act
            ApiBasicInfoUpdateRequest request = new ApiBasicInfoUpdateRequest(
                description, null, null
            );

            // Assert
            assertEquals(description, request.description());
            assertNull(request.category());
            assertNull(request.subcategory());
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
            ApiBasicInfoUpdateRequest request1 = new ApiBasicInfoUpdateRequest(
                "desc", "cat", "subcat"
            );
            ApiBasicInfoUpdateRequest request2 = new ApiBasicInfoUpdateRequest(
                "desc", "cat", "subcat"
            );

            // Assert
            assertEquals(request1, request2);
            assertEquals(request1.hashCode(), request2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different records")
        void shouldNotBeEqualForDifferentRecords() {
            // Arrange
            ApiBasicInfoUpdateRequest request1 = new ApiBasicInfoUpdateRequest(
                "desc1", "cat", "subcat"
            );
            ApiBasicInfoUpdateRequest request2 = new ApiBasicInfoUpdateRequest(
                "desc2", "cat", "subcat"
            );

            // Assert
            assertNotEquals(request1, request2);
        }

        @Test
        @DisplayName("should handle null values in equals")
        void shouldHandleNullValuesInEquals() {
            // Arrange
            ApiBasicInfoUpdateRequest request1 = new ApiBasicInfoUpdateRequest(
                null, null, null
            );
            ApiBasicInfoUpdateRequest request2 = new ApiBasicInfoUpdateRequest(
                null, null, null
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
            ApiBasicInfoUpdateRequest request = new ApiBasicInfoUpdateRequest(
                "Test description", "TestCategory", "TestSubcategory"
            );

            // Act
            String result = request.toString();

            // Assert
            assertNotNull(result);
            assertTrue(result.contains("Test description"));
            assertTrue(result.contains("TestCategory"));
            assertTrue(result.contains("TestSubcategory"));
        }
    }
}
