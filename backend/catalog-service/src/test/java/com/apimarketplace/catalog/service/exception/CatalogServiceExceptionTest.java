package com.apimarketplace.catalog.service.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CatalogServiceException.
 *
 * CatalogServiceException is the base exception class for all catalog service exceptions.
 */
@DisplayName("CatalogServiceException")
class CatalogServiceExceptionTest {

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create exception with message only")
        void shouldCreateExceptionWithMessageOnly() {
            CatalogServiceException exception = new CatalogServiceException("Test error");

            assertEquals("Test error", exception.getMessage());
            assertEquals("CATALOG_ERROR", exception.getErrorCode());
            assertNull(exception.getCause());
        }

        @Test
        @DisplayName("should create exception with message and error code")
        void shouldCreateExceptionWithMessageAndErrorCode() {
            CatalogServiceException exception = new CatalogServiceException("Error occurred", "CUSTOM_CODE");

            assertEquals("Error occurred", exception.getMessage());
            assertEquals("CUSTOM_CODE", exception.getErrorCode());
        }

        @Test
        @DisplayName("should create exception with message and cause")
        void shouldCreateExceptionWithMessageAndCause() {
            RuntimeException cause = new RuntimeException("Root cause");
            CatalogServiceException exception = new CatalogServiceException("Wrapped error", cause);

            assertEquals("Wrapped error", exception.getMessage());
            assertEquals("CATALOG_ERROR", exception.getErrorCode());
            assertEquals(cause, exception.getCause());
        }

        @Test
        @DisplayName("should create exception with message, error code and cause")
        void shouldCreateExceptionWithMessageErrorCodeAndCause() {
            RuntimeException cause = new RuntimeException("Original error");
            CatalogServiceException exception = new CatalogServiceException("Error", "SPECIFIC_CODE", cause);

            assertEquals("Error", exception.getMessage());
            assertEquals("SPECIFIC_CODE", exception.getErrorCode());
            assertEquals(cause, exception.getCause());
        }
    }

    // ========================================================================
    // Inheritance tests
    // ========================================================================

    @Nested
    @DisplayName("Inheritance")
    class InheritanceTests {

        @Test
        @DisplayName("should be a RuntimeException")
        void shouldBeRuntimeException() {
            CatalogServiceException exception = new CatalogServiceException("Test");

            assertTrue(exception instanceof RuntimeException);
        }

        @Test
        @DisplayName("should be throwable without declaration")
        void shouldBeThrowableWithoutDeclaration() {
            assertThrows(CatalogServiceException.class, () -> {
                throw new CatalogServiceException("Test");
            });
        }
    }

    // ========================================================================
    // getErrorCode tests
    // ========================================================================

    @Nested
    @DisplayName("getErrorCode()")
    class GetErrorCodeTests {

        @Test
        @DisplayName("should return default error code")
        void shouldReturnDefaultErrorCode() {
            CatalogServiceException exception = new CatalogServiceException("Error");

            assertEquals("CATALOG_ERROR", exception.getErrorCode());
        }

        @Test
        @DisplayName("should return custom error code")
        void shouldReturnCustomErrorCode() {
            CatalogServiceException exception = new CatalogServiceException("Error", "MY_ERROR");

            assertEquals("MY_ERROR", exception.getErrorCode());
        }
    }
}
