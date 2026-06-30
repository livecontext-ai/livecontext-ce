package com.apimarketplace.catalog.service.submission;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApiSlugService.
 *
 * ApiSlugService generates unique slugs for APIs within a user's scope.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiSlugService")
class ApiSlugServiceTest {

    @Mock
    private ApiRepository apiRepository;

    private ApiSlugService apiSlugService;

    @BeforeEach
    void setUp() {
        apiSlugService = new ApiSlugService(apiRepository);
    }

    // ========================================================================
    // generateUniqueSlug tests
    // ========================================================================

    @Nested
    @DisplayName("generateUniqueSlug")
    class GenerateUniqueSlugTests {

        @Test
        @DisplayName("should return base slug when no existing slugs")
        void shouldReturnBaseSlugWhenNoExistingSlugs() {
            // Arrange
            String baseSlug = "my-api";
            String userId = "user-123";
            when(apiRepository.findByCreatedBy(userId)).thenReturn(Collections.emptyList());

            // Act
            String result = apiSlugService.generateUniqueSlug(baseSlug, userId);

            // Assert
            assertEquals("my-api", result);
            verify(apiRepository).findByCreatedBy(userId);
        }

        @Test
        @DisplayName("should append suffix when slug exists")
        void shouldAppendSuffixWhenSlugExists() {
            // Arrange
            String baseSlug = "weather-api";
            String userId = "user-123";

            ApiEntity existingApi = new ApiEntity();
            existingApi.setApiSlug("weather-api");

            when(apiRepository.findByCreatedBy(userId)).thenReturn(List.of(existingApi));

            // Act
            String result = apiSlugService.generateUniqueSlug(baseSlug, userId);

            // Assert
            assertTrue(result.startsWith("weather-api-"));
            assertNotEquals("weather-api", result);
        }

        @Test
        @DisplayName("should handle multiple existing slugs with same prefix")
        void shouldHandleMultipleExistingSlugsWithSamePrefix() {
            // Arrange
            String baseSlug = "test-api";
            String userId = "user-123";

            ApiEntity api1 = new ApiEntity();
            api1.setApiSlug("test-api");

            ApiEntity api2 = new ApiEntity();
            api2.setApiSlug("test-api-1");

            when(apiRepository.findByCreatedBy(userId)).thenReturn(List.of(api1, api2));

            // Act
            String result = apiSlugService.generateUniqueSlug(baseSlug, userId);

            // Assert
            assertNotEquals("test-api", result);
            assertNotEquals("test-api-1", result);
        }

        @Test
        @DisplayName("should filter out null and empty slugs")
        void shouldFilterOutNullAndEmptySlugs() {
            // Arrange
            String baseSlug = "data-api";
            String userId = "user-123";

            ApiEntity api1 = new ApiEntity();
            api1.setApiSlug(null);

            ApiEntity api2 = new ApiEntity();
            api2.setApiSlug("");

            ApiEntity api3 = new ApiEntity();
            api3.setApiSlug("other-api");

            when(apiRepository.findByCreatedBy(userId)).thenReturn(List.of(api1, api2, api3));

            // Act
            String result = apiSlugService.generateUniqueSlug(baseSlug, userId);

            // Assert
            assertEquals("data-api", result);
        }

        @Test
        @DisplayName("should return base slug when repository throws exception")
        void shouldReturnBaseSlugWhenRepositoryThrowsException() {
            // Arrange
            String baseSlug = "fallback-api";
            String userId = "user-123";
            when(apiRepository.findByCreatedBy(userId)).thenThrow(new RuntimeException("Database error"));

            // Act
            String result = apiSlugService.generateUniqueSlug(baseSlug, userId);

            // Assert
            assertEquals("fallback-api", result);
        }
    }
}
