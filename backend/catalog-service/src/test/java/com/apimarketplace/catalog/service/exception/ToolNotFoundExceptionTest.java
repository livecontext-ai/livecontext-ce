package com.apimarketplace.catalog.service.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolNotFoundException.
 *
 * ToolNotFoundException is thrown when an API tool cannot be found.
 */
@DisplayName("ToolNotFoundException")
class ToolNotFoundExceptionTest {

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create exception with UUID")
        void shouldCreateExceptionWithUuid() {
            UUID toolId = UUID.randomUUID();
            ToolNotFoundException exception = new ToolNotFoundException(toolId);

            assertTrue(exception.getMessage().contains(toolId.toString()));
            assertEquals("TOOL_NOT_FOUND", exception.getErrorCode());
        }

        @Test
        @DisplayName("should create exception with string identifier")
        void shouldCreateExceptionWithStringIdentifier() {
            ToolNotFoundException exception = new ToolNotFoundException("my-tool-slug");

            assertTrue(exception.getMessage().contains("my-tool-slug"));
            assertEquals("TOOL_NOT_FOUND", exception.getErrorCode());
        }
    }

    // ========================================================================
    // Factory method tests
    // ========================================================================

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("should create exception by ID")
        void shouldCreateExceptionById() {
            UUID toolId = UUID.randomUUID();
            ToolNotFoundException exception = ToolNotFoundException.byId(toolId);

            assertTrue(exception.getMessage().contains(toolId.toString()));
            assertEquals("TOOL_NOT_FOUND", exception.getErrorCode());
        }

        @Test
        @DisplayName("should create exception by slug")
        void shouldCreateExceptionBySlug() {
            ToolNotFoundException exception = ToolNotFoundException.bySlug("weather-api");

            assertTrue(exception.getMessage().contains("weather-api"));
            assertEquals("TOOL_NOT_FOUND", exception.getErrorCode());
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
            ToolNotFoundException exception = new ToolNotFoundException("test");

            assertTrue(exception instanceof CatalogServiceException);
        }

        @Test
        @DisplayName("should be a RuntimeException")
        void shouldBeRuntimeException() {
            ToolNotFoundException exception = new ToolNotFoundException("test");

            assertTrue(exception instanceof RuntimeException);
        }
    }
}
