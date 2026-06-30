package com.apimarketplace.catalog.service.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiAuthenticationException.
 *
 * ApiAuthenticationException is thrown when an external API returns 401 Unauthorized or 403 Forbidden.
 */
@DisplayName("ApiAuthenticationException")
class ApiAuthenticationExceptionTest {

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create exception with message, status and service")
        void shouldCreateExceptionWithMessageStatusAndService() {
            ApiAuthenticationException exception = new ApiAuthenticationException(
                "Auth failed", HttpStatus.UNAUTHORIZED, "weather-api"
            );

            assertEquals("Auth failed", exception.getMessage());
            assertEquals("API_AUTHENTICATION_ERROR", exception.getErrorCode());
            assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
            assertEquals("weather-api", exception.getService());
            assertNull(exception.getCause());
        }

        @Test
        @DisplayName("should create exception with message, status, service and cause")
        void shouldCreateExceptionWithMessageStatusServiceAndCause() {
            RuntimeException cause = new RuntimeException("Token expired");
            ApiAuthenticationException exception = new ApiAuthenticationException(
                "Auth failed", HttpStatus.FORBIDDEN, "secure-api", cause
            );

            assertEquals("Auth failed", exception.getMessage());
            assertEquals("API_AUTHENTICATION_ERROR", exception.getErrorCode());
            assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
            assertEquals("secure-api", exception.getService());
            assertEquals(cause, exception.getCause());
        }
    }

    // ========================================================================
    // Factory method tests - unauthorized
    // ========================================================================

    @Nested
    @DisplayName("unauthorized()")
    class UnauthorizedTests {

        @Test
        @DisplayName("should create 401 Unauthorized exception")
        void shouldCreate401UnauthorizedException() {
            ApiAuthenticationException exception = ApiAuthenticationException.unauthorized(
                "payment-api", "Invalid API key"
            );

            assertEquals("Invalid API key", exception.getMessage());
            assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
            assertEquals("payment-api", exception.getService());
            assertEquals("API_AUTHENTICATION_ERROR", exception.getErrorCode());
        }

        @Test
        @DisplayName("should create 401 Unauthorized exception with cause")
        void shouldCreate401UnauthorizedExceptionWithCause() {
            RuntimeException cause = new RuntimeException("JWT validation failed");
            ApiAuthenticationException exception = ApiAuthenticationException.unauthorized(
                "auth-service", "Token invalid", cause
            );

            assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
            assertEquals("auth-service", exception.getService());
            assertEquals(cause, exception.getCause());
        }
    }

    // ========================================================================
    // Factory method tests - forbidden
    // ========================================================================

    @Nested
    @DisplayName("forbidden()")
    class ForbiddenTests {

        @Test
        @DisplayName("should create 403 Forbidden exception")
        void shouldCreate403ForbiddenException() {
            ApiAuthenticationException exception = ApiAuthenticationException.forbidden(
                "admin-api", "Insufficient permissions"
            );

            assertEquals("Insufficient permissions", exception.getMessage());
            assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
            assertEquals("admin-api", exception.getService());
            assertEquals("API_AUTHENTICATION_ERROR", exception.getErrorCode());
        }

        @Test
        @DisplayName("should create 403 Forbidden exception with cause")
        void shouldCreate403ForbiddenExceptionWithCause() {
            RuntimeException cause = new RuntimeException("Role check failed");
            ApiAuthenticationException exception = ApiAuthenticationException.forbidden(
                "role-service", "Access denied", cause
            );

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
            assertEquals("role-service", exception.getService());
            assertEquals(cause, exception.getCause());
        }
    }

    // ========================================================================
    // Getter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters")
    class GetterTests {

        @Test
        @DisplayName("should return HTTP status")
        void shouldReturnHttpStatus() {
            ApiAuthenticationException exception = ApiAuthenticationException.unauthorized(
                "api", "Error"
            );

            assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        }

        @Test
        @DisplayName("should return service name")
        void shouldReturnServiceName() {
            ApiAuthenticationException exception = ApiAuthenticationException.forbidden(
                "my-service", "Error"
            );

            assertEquals("my-service", exception.getService());
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
            ApiAuthenticationException exception = ApiAuthenticationException.unauthorized("api", "error");

            assertTrue(exception instanceof CatalogServiceException);
        }
    }
}
