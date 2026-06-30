package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiEntity class.
 *
 * ApiEntity represents an API configuration stored in the database.
 * Tests cover:
 * - Constructor behavior and default values
 * - Getters and setters
 * - Enum definitions (ApiStatus)
 */
@DisplayName("ApiEntity Tests")
class ApiEntityTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entity with no-arg constructor")
        void shouldCreateEntityWithNoArgConstructor() {
            // Act
            ApiEntity entity = new ApiEntity();

            // Assert - check default values
            assertNull(entity.getId());
            assertNull(entity.getApiName());
            assertFalse(entity.getIsActive());
            assertFalse(entity.getIsLocal());
            assertEquals("1.0.0", entity.getVersion());
        }

        @Test
        @DisplayName("Should create entity with parameterized constructor")
        void shouldCreateEntityWithParameterizedConstructor() {
            // Arrange
            UUID id = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();
            Long createdAt = System.currentTimeMillis();
            Long updatedAt = System.currentTimeMillis();

            // Act
            ApiEntity entity = new ApiEntity(
                id,
                "Test API",
                "Test Description",
                "https://api.test.com",
                categoryId,
                subcategoryId,
                true,
                createdAt,
                updatedAt,
                "user123"
            );

            // Assert
            assertEquals(id, entity.getId());
            assertEquals("Test API", entity.getApiName());
            assertEquals("Test Description", entity.getDescription());
            assertEquals("https://api.test.com", entity.getBaseUrl());
            assertEquals(categoryId, entity.getCategoryId());
            assertEquals(subcategoryId, entity.getSubcategoryId());
            assertTrue(entity.getIsActive());
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
            assertEquals("user123", entity.getCreatedBy());
        }

        @Test
        @DisplayName("Should allow null values in parameterized constructor")
        void shouldAllowNullValuesInParameterizedConstructor() {
            // Act
            ApiEntity entity = new ApiEntity(
                null, null, null, null, null, null, null, null, null, null
            );

            // Assert
            assertNull(entity.getId());
            assertNull(entity.getApiName());
            assertNull(entity.getIsActive());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEFAULT VALUES TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Default Values Tests")
    class DefaultValuesTests {

        @Test
        @DisplayName("Should have isActive default to false")
        void shouldHaveIsActiveDefaultToFalse() {
            // Act
            ApiEntity entity = new ApiEntity();

            // Assert
            assertFalse(entity.getIsActive());
        }

        @Test
        @DisplayName("Should have isLocal default to false")
        void shouldHaveIsLocalDefaultToFalse() {
            // Act
            ApiEntity entity = new ApiEntity();

            // Assert
            assertFalse(entity.getIsLocal());
        }

        @Test
        @DisplayName("Should have version default to 1.0.0")
        void shouldHaveVersionDefaultTo100() {
            // Act
            ApiEntity entity = new ApiEntity();

            // Assert
            assertEquals("1.0.0", entity.getVersion());
        }

    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS AND SETTERS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters and Setters Tests")
    class GettersSettersTests {

        @Test
        @DisplayName("Should set and get id")
        void shouldSetAndGetId() {
            // Arrange
            ApiEntity entity = new ApiEntity();
            UUID id = UUID.randomUUID();

            // Act
            entity.setId(id);

            // Assert
            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("Should set and get apiName")
        void shouldSetAndGetApiName() {
            // Arrange
            ApiEntity entity = new ApiEntity();

            // Act
            entity.setApiName("My API");

            // Assert
            assertEquals("My API", entity.getApiName());
        }

        @Test
        @DisplayName("Should set and get apiSlug")
        void shouldSetAndGetApiSlug() {
            // Arrange
            ApiEntity entity = new ApiEntity();

            // Act
            entity.setApiSlug("my-api");

            // Assert
            assertEquals("my-api", entity.getApiSlug());
        }

        @Test
        @DisplayName("Should set and get description")
        void shouldSetAndGetDescription() {
            // Arrange
            ApiEntity entity = new ApiEntity();

            // Act
            entity.setDescription("A test API description");

            // Assert
            assertEquals("A test API description", entity.getDescription());
        }

        @Test
        @DisplayName("Should set and get baseUrl")
        void shouldSetAndGetBaseUrl() {
            // Arrange
            ApiEntity entity = new ApiEntity();

            // Act
            entity.setBaseUrl("https://api.example.com");

            // Assert
            assertEquals("https://api.example.com", entity.getBaseUrl());
        }

        @Test
        @DisplayName("Should set and get healthcheckEndpoint")
        void shouldSetAndGetHealthcheckEndpoint() {
            // Arrange
            ApiEntity entity = new ApiEntity();

            // Act
            entity.setHealthcheckEndpoint("/health");

            // Assert
            assertEquals("/health", entity.getHealthcheckEndpoint());
        }

        @Test
        @DisplayName("Should set and get auth fields")
        void shouldSetAndGetAuthFields() {
            // Arrange
            ApiEntity entity = new ApiEntity();

            // Act
            entity.setAuthType("bearer");
            entity.setAuthHeaderName("Authorization");
            entity.setAuthHeaderValue("Bearer token123");

            // Assert
            assertEquals("bearer", entity.getAuthType());
            assertEquals("Authorization", entity.getAuthHeaderName());
            assertEquals("Bearer token123", entity.getAuthHeaderValue());
        }

        @Test
        @DisplayName("Should set and get visibility fields")
        void shouldSetAndGetVisibilityFields() {
            // Arrange
            ApiEntity entity = new ApiEntity();

            // Act
            entity.setVisibility("public");
            entity.setIsPublic(true);

            // Assert
            assertEquals("public", entity.getVisibility());
            assertTrue(entity.getIsPublic());
        }

        @Test
        @DisplayName("Should set and get status and pricingModel")
        void shouldSetAndGetStatusAndPricingModel() {
            // Arrange
            ApiEntity entity = new ApiEntity();

            // Act
            entity.setStatus("APPROVED");
            entity.setPricingModel("freemium");

            // Assert
            assertEquals("APPROVED", entity.getStatus());
            assertEquals("freemium", entity.getPricingModel());
        }

        @Test
        @DisplayName("Should set and get iconSlug")
        void shouldSetAndGetIconSlug() {
            // Arrange
            ApiEntity entity = new ApiEntity();

            // Act
            entity.setIconSlug("weather-icon");

            // Assert
            assertEquals("weather-icon", entity.getIconSlug());
        }

        @Test
        @DisplayName("Should set and get platform credential name")
        void shouldSetAndGetPlatformCredentialName() {
            // Arrange
            ApiEntity entity = new ApiEntity();

            // Act
            entity.setPlatformCredentialName("openai-key");

            // Assert
            assertEquals("openai-key", entity.getPlatformCredentialName());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENUM TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Enum Tests")
    class EnumTests {

        @Test
        @DisplayName("ApiStatus enum should have all expected values")
        void apiStatusEnumShouldHaveAllExpectedValues() {
            // Assert
            assertEquals(5, ApiEntity.ApiStatus.values().length);
            assertNotNull(ApiEntity.ApiStatus.REVIEWING);
            assertNotNull(ApiEntity.ApiStatus.SUBMITTED);
            assertNotNull(ApiEntity.ApiStatus.APPROVED);
            assertNotNull(ApiEntity.ApiStatus.REJECTED);
            assertNotNull(ApiEntity.ApiStatus.SUSPENDED);
        }

        @Test
        @DisplayName("ApiStatus enum valueOf should work correctly")
        void apiStatusEnumValueOfShouldWork() {
            // Assert
            assertEquals(ApiEntity.ApiStatus.APPROVED, ApiEntity.ApiStatus.valueOf("APPROVED"));
            assertEquals(ApiEntity.ApiStatus.REJECTED, ApiEntity.ApiStatus.valueOf("REJECTED"));
        }

        // CredentialMode enum removed (V154 - credential resolution is now
        // driven by per-step credentialSource toggle for workflow nodes and
        // implicit user→platform fallback for agentic paths).
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NULL HANDLING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Null Handling Tests")
    class NullHandlingTests {

        @Test
        @DisplayName("Should handle null values in setters")
        void shouldHandleNullValuesInSetters() {
            // Arrange
            ApiEntity entity = new ApiEntity();

            // Act
            entity.setApiName(null);
            entity.setDescription(null);
            entity.setBaseUrl(null);
            entity.setIsActive(null);
            entity.setVersion(null);

            // Assert
            assertNull(entity.getApiName());
            assertNull(entity.getDescription());
            assertNull(entity.getBaseUrl());
            assertNull(entity.getIsActive());
            assertNull(entity.getVersion());
        }

        @Test
        @DisplayName("Should override default values when set to null")
        void shouldOverrideDefaultValuesWhenSetToNull() {
            // Arrange
            ApiEntity entity = new ApiEntity();
            assertEquals("1.0.0", entity.getVersion()); // default

            // Act
            entity.setVersion(null);

            // Assert
            assertNull(entity.getVersion());
        }
    }
}
