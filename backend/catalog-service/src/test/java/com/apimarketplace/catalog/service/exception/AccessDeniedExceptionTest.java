package com.apimarketplace.catalog.service.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AccessDeniedException.
 *
 * AccessDeniedException is thrown when a user attempts to access or modify a resource they don't own.
 */
@DisplayName("AccessDeniedException")
class AccessDeniedExceptionTest {

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create exception with all parameters")
        void shouldCreateExceptionWithAllParameters() {
            AccessDeniedException exception = new AccessDeniedException(
                "Access denied", "user123", "API", "api-456"
            );

            assertEquals("Access denied", exception.getMessage());
            assertEquals("ACCESS_DENIED", exception.getErrorCode());
            assertEquals("user123", exception.getUserId());
            assertEquals("API", exception.getResourceType());
            assertEquals("api-456", exception.getResourceId());
        }
    }

    // ========================================================================
    // Factory method tests - forApi
    // ========================================================================

    @Nested
    @DisplayName("forApi()")
    class ForApiTests {

        @Test
        @DisplayName("should create exception for API access")
        void shouldCreateExceptionForApiAccess() {
            AccessDeniedException exception = AccessDeniedException.forApi("user123", "api-456");

            assertTrue(exception.getMessage().contains("user123"));
            assertTrue(exception.getMessage().contains("api-456"));
            assertEquals("ACCESS_DENIED", exception.getErrorCode());
            assertEquals("user123", exception.getUserId());
            assertEquals("API", exception.getResourceType());
            assertEquals("api-456", exception.getResourceId());
        }
    }

    // ========================================================================
    // Factory method tests - forTool
    // ========================================================================

    @Nested
    @DisplayName("forTool()")
    class ForToolTests {

        @Test
        @DisplayName("should create exception for Tool access")
        void shouldCreateExceptionForToolAccess() {
            AccessDeniedException exception = AccessDeniedException.forTool("user789", "tool-123");

            assertTrue(exception.getMessage().contains("user789"));
            assertTrue(exception.getMessage().contains("tool-123"));
            assertEquals("ACCESS_DENIED", exception.getErrorCode());
            assertEquals("user789", exception.getUserId());
            assertEquals("Tool", exception.getResourceType());
            assertEquals("tool-123", exception.getResourceId());
        }
    }

    // ========================================================================
    // Factory method tests - forResource
    // ========================================================================

    @Nested
    @DisplayName("forResource()")
    class ForResourceTests {

        @Test
        @DisplayName("should create exception for generic resource access")
        void shouldCreateExceptionForGenericResourceAccess() {
            AccessDeniedException exception = AccessDeniedException.forResource(
                "user999", "Category", "cat-001"
            );

            assertTrue(exception.getMessage().contains("user999"));
            assertTrue(exception.getMessage().contains("Category"));
            assertTrue(exception.getMessage().contains("cat-001"));
            assertEquals("ACCESS_DENIED", exception.getErrorCode());
            assertEquals("user999", exception.getUserId());
            assertEquals("Category", exception.getResourceType());
            assertEquals("cat-001", exception.getResourceId());
        }
    }

    // ========================================================================
    // Getter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters")
    class GetterTests {

        @Test
        @DisplayName("should return user ID")
        void shouldReturnUserId() {
            AccessDeniedException exception = AccessDeniedException.forApi("test-user", "api-id");

            assertEquals("test-user", exception.getUserId());
        }

        @Test
        @DisplayName("should return resource type")
        void shouldReturnResourceType() {
            AccessDeniedException exception = AccessDeniedException.forTool("user", "tool");

            assertEquals("Tool", exception.getResourceType());
        }

        @Test
        @DisplayName("should return resource ID")
        void shouldReturnResourceId() {
            AccessDeniedException exception = AccessDeniedException.forResource("user", "Type", "resource-123");

            assertEquals("resource-123", exception.getResourceId());
        }
    }

    // ========================================================================
    // Inheritance tests
    // ========================================================================

    @Nested
    @DisplayName("Inheritance")
    class InheritanceTests {

        @Test
        @DisplayName("should extend CatalogServiceException")
        void shouldExtendCatalogServiceException() {
            AccessDeniedException exception = AccessDeniedException.forApi("user", "api");

            assertTrue(exception instanceof CatalogServiceException);
        }
    }
}
