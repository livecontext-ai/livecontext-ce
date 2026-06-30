package com.apimarketplace.catalog.config;

import com.apimarketplace.catalog.service.exception.AccessDeniedException;
import com.apimarketplace.catalog.service.exception.ApiAuthenticationException;
import com.apimarketplace.catalog.service.exception.ToolNotFoundException;
import com.apimarketplace.catalog.service.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Nested
    @DisplayName("handleAccessDenied")
    class HandleAccessDenied {

        @Test
        @DisplayName("should return 403 Forbidden with proper error structure")
        void shouldReturn403_withProperErrorStructure() {
            // Given
            AccessDeniedException ex = AccessDeniedException.forApi("user-123", "api-456");

            // When
            ResponseEntity<Map<String, Object>> response = handler.handleAccessDenied(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).containsEntry("success", false);
            assertThat(response.getBody()).containsEntry("error", "ACCESS_DENIED");
            assertThat(response.getBody()).containsKey("message");
            assertThat(response.getBody()).containsKey("timestamp");
            assertThat(response.getBody()).containsKey("details");

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) response.getBody().get("details");
            assertThat(details).containsEntry("resourceType", "API");
            assertThat(details).containsEntry("resourceId", "api-456");
        }
    }

    @Nested
    @DisplayName("handleApiAuthentication")
    class HandleApiAuthentication {

        @Test
        @DisplayName("should return 401 Unauthorized for unauthorized exception")
        void shouldReturn401_forUnauthorized() {
            // Given
            ApiAuthenticationException ex = ApiAuthenticationException.unauthorized("slack", "Token expired");

            // When
            ResponseEntity<Map<String, Object>> response = handler.handleApiAuthentication(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).containsEntry("error", "API_AUTHENTICATION_ERROR");
            assertThat(response.getBody().get("details")).isNotNull();
        }

        @Test
        @DisplayName("should return 403 Forbidden for forbidden exception")
        void shouldReturn403_forForbidden() {
            // Given
            ApiAuthenticationException ex = ApiAuthenticationException.forbidden("github", "Insufficient permissions");

            // When
            ResponseEntity<Map<String, Object>> response = handler.handleApiAuthentication(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("handleToolNotFound")
    class HandleToolNotFound {

        @Test
        @DisplayName("should return 404 Not Found")
        void shouldReturn404() {
            // Given
            ToolNotFoundException ex = new ToolNotFoundException("Tool with ID xyz not found");

            // When
            ResponseEntity<Map<String, Object>> response = handler.handleToolNotFound(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("error", "TOOL_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("handleValidation")
    class HandleValidation {

        @Test
        @DisplayName("should return 400 Bad Request")
        void shouldReturn400() {
            // Given
            ValidationException ex = new ValidationException("Invalid input: name is required");

            // When
            ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "VALIDATION_ERROR");
        }
    }

    @Nested
    @DisplayName("handleGeneric")
    class HandleGeneric {

        @Test
        @DisplayName("should return 500 Internal Server Error for unexpected exceptions")
        void shouldReturn500_forUnexpectedExceptions() {
            // Given
            Exception ex = new RuntimeException("Unexpected error");

            // When
            ResponseEntity<Map<String, Object>> response = handler.handleGeneric(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).containsEntry("error", "INTERNAL_ERROR");
            assertThat(response.getBody()).containsEntry("message", "An unexpected error occurred");
        }
    }
}
