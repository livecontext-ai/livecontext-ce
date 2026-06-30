package com.apimarketplace.catalog.mapping.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MappingGenerationException class.
 *
 * MappingGenerationException is thrown when mapping generation fails.
 */
@DisplayName("MappingGenerationException")
class MappingGenerationExceptionTest {

    // ========================================================================
    // Constructor tests - message only
    // ========================================================================

    @Nested
    @DisplayName("Constructor with message only")
    class MessageOnlyConstructorTests {

        @Test
        @DisplayName("should create exception with message")
        void shouldCreateExceptionWithMessage() {
            MappingGenerationException exception = new MappingGenerationException("Generation failed");

            assertEquals("Generation failed", exception.getMessage());
            assertNull(exception.getCause());
        }

        @Test
        @DisplayName("should create exception with detailed message")
        void shouldCreateExceptionWithDetailedMessage() {
            MappingGenerationException exception = new MappingGenerationException(
                "Failed to generate mapping for field 'name': invalid path"
            );

            assertTrue(exception.getMessage().contains("name"));
            assertTrue(exception.getMessage().contains("invalid path"));
        }
    }

    // ========================================================================
    // Constructor tests - message and cause
    // ========================================================================

    @Nested
    @DisplayName("Constructor with message and cause")
    class MessageAndCauseConstructorTests {

        @Test
        @DisplayName("should create exception with message and cause")
        void shouldCreateExceptionWithMessageAndCause() {
            RuntimeException cause = new RuntimeException("Root cause");

            MappingGenerationException exception = new MappingGenerationException(
                "Generation failed", cause
            );

            assertEquals("Generation failed", exception.getMessage());
            assertEquals(cause, exception.getCause());
            assertEquals("Root cause", exception.getCause().getMessage());
        }

        @Test
        @DisplayName("should chain nested exceptions")
        void shouldChainNestedExceptions() {
            IllegalArgumentException rootCause = new IllegalArgumentException("Invalid argument");
            RuntimeException intermediateCause = new RuntimeException("Processing error", rootCause);

            MappingGenerationException exception = new MappingGenerationException(
                "Mapping failed", intermediateCause
            );

            assertEquals(intermediateCause, exception.getCause());
            assertEquals(rootCause, exception.getCause().getCause());
        }

        @Test
        @DisplayName("should allow null cause")
        void shouldAllowNullCause() {
            MappingGenerationException exception = new MappingGenerationException(
                "Generation failed", null
            );

            assertEquals("Generation failed", exception.getMessage());
            assertNull(exception.getCause());
        }
    }

    // ========================================================================
    // Exception hierarchy tests
    // ========================================================================

    @Nested
    @DisplayName("Exception hierarchy")
    class ExceptionHierarchyTests {

        @Test
        @DisplayName("should extend Exception")
        void shouldExtendException() {
            MappingGenerationException exception = new MappingGenerationException("test");

            assertTrue(exception instanceof Exception);
        }

        @Test
        @DisplayName("should be catchable as Exception")
        void shouldBeCatchableAsException() {
            Exception caught = null;

            try {
                throw new MappingGenerationException("test");
            } catch (Exception e) {
                caught = e;
            }

            assertNotNull(caught);
            assertTrue(caught instanceof MappingGenerationException);
        }
    }

    // ========================================================================
    // Stack trace tests
    // ========================================================================

    @Nested
    @DisplayName("Stack trace")
    class StackTraceTests {

        @Test
        @DisplayName("should have stack trace")
        void shouldHaveStackTrace() {
            MappingGenerationException exception = new MappingGenerationException("test");

            assertNotNull(exception.getStackTrace());
            assertTrue(exception.getStackTrace().length > 0);
        }

        @Test
        @DisplayName("should reference test method in stack trace")
        void shouldReferenceTestMethodInStackTrace() {
            MappingGenerationException exception = new MappingGenerationException("test");

            StackTraceElement[] stackTrace = exception.getStackTrace();
            boolean foundTestMethod = false;
            for (StackTraceElement element : stackTrace) {
                if (element.getMethodName().contains("shouldReferenceTestMethodInStackTrace")) {
                    foundTestMethod = true;
                    break;
                }
            }

            assertTrue(foundTestMethod);
        }
    }
}
