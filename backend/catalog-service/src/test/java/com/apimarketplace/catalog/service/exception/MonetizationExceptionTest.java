package com.apimarketplace.catalog.service.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MonetizationException.
 *
 * MonetizationException is thrown when monetization configuration is invalid or processing fails.
 */
@DisplayName("MonetizationException")
class MonetizationExceptionTest {

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create exception with message only")
        void shouldCreateExceptionWithMessageOnly() {
            MonetizationException exception = new MonetizationException("Monetization error");

            assertEquals("Monetization error", exception.getMessage());
            assertEquals("MONETIZATION_ERROR", exception.getErrorCode());
            assertNull(exception.getCause());
        }

        @Test
        @DisplayName("should create exception with message and cause")
        void shouldCreateExceptionWithMessageAndCause() {
            RuntimeException cause = new RuntimeException("Database error");
            MonetizationException exception = new MonetizationException("Config error", cause);

            assertEquals("Config error", exception.getMessage());
            assertEquals("MONETIZATION_ERROR", exception.getErrorCode());
            assertEquals(cause, exception.getCause());
        }
    }

    // ========================================================================
    // Factory method tests - invalidType
    // ========================================================================

    @Nested
    @DisplayName("invalidType()")
    class InvalidTypeTests {

        @Test
        @DisplayName("should create exception for invalid type")
        void shouldCreateExceptionForInvalidType() {
            MonetizationException exception = MonetizationException.invalidType("SUBSCRIPTION");

            assertTrue(exception.getMessage().contains("SUBSCRIPTION"));
            assertTrue(exception.getMessage().contains("Unsupported"));
            assertTrue(exception.getMessage().contains("FREEMIUM") || exception.getMessage().contains("PAID"));
            assertEquals("MONETIZATION_ERROR", exception.getErrorCode());
        }
    }

    // ========================================================================
    // Factory method tests - missingConfiguration
    // ========================================================================

    @Nested
    @DisplayName("missingConfiguration()")
    class MissingConfigurationTests {

        @Test
        @DisplayName("should create exception for missing configuration")
        void shouldCreateExceptionForMissingConfiguration() {
            MonetizationException exception = MonetizationException.missingConfiguration("weather-api");

            assertTrue(exception.getMessage().contains("weather-api"));
            assertTrue(exception.getMessage().toLowerCase().contains("configuration")
                    || exception.getMessage().toLowerCase().contains("config"));
            assertEquals("MONETIZATION_ERROR", exception.getErrorCode());
        }
    }

    // ========================================================================
    // Factory method tests - toolNotInPlan
    // ========================================================================

    @Nested
    @DisplayName("toolNotInPlan()")
    class ToolNotInPlanTests {

        @Test
        @DisplayName("should create exception for tool not in plan")
        void shouldCreateExceptionForToolNotInPlan() {
            MonetizationException exception = MonetizationException.toolNotInPlan("premium-tool");

            assertTrue(exception.getMessage().contains("premium-tool"));
            assertTrue(exception.getMessage().toLowerCase().contains("plan"));
            assertEquals("MONETIZATION_ERROR", exception.getErrorCode());
        }
    }

    // ========================================================================
    // Factory method tests - invalidPaidConfig
    // ========================================================================

    @Nested
    @DisplayName("invalidPaidConfig()")
    class InvalidPaidConfigTests {

        @Test
        @DisplayName("should create exception for invalid paid config")
        void shouldCreateExceptionForInvalidPaidConfig() {
            MonetizationException exception = MonetizationException.invalidPaidConfig();

            assertTrue(exception.getMessage().contains("PAID"));
            assertTrue(exception.getMessage().toLowerCase().contains("invalid")
                    || exception.getMessage().toLowerCase().contains("missing"));
            assertEquals("MONETIZATION_ERROR", exception.getErrorCode());
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
            MonetizationException exception = new MonetizationException("test");

            assertTrue(exception instanceof CatalogServiceException);
        }
    }
}
