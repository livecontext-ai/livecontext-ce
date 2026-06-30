package com.apimarketplace.orchestrator.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unused")
    private void headerFixture(@RequestHeader("X-User-ID") String userId,
                               @RequestHeader("X-Organization-ID") String organizationId) {
        // Reflection fixture for MissingRequestHeaderException.
    }

    private MissingRequestHeaderException missingHeader(String headerName, int parameterIndex) throws NoSuchMethodException {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("headerFixture", String.class, String.class);
        return new MissingRequestHeaderException(headerName, new MethodParameter(method, parameterIndex));
    }

    @Test
    @DisplayName("maps missing X-User-ID headers to 401 instead of the generic 500 handler")
    void mapsMissingUserHeaderToUnauthorized() throws Exception {
        ResponseEntity<Map<String, Object>> response = handler.handleMissingRequestHeader(missingHeader("X-User-ID", 0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("errorCode", "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("maps other missing required headers to 400")
    void mapsOtherMissingRequiredHeadersToBadRequest() throws Exception {
        ResponseEntity<Map<String, Object>> response = handler.handleMissingRequestHeader(missingHeader("X-Organization-ID", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("errorCode", "MISSING_REQUEST_HEADER");
    }

    @Test
    @DisplayName("maps missing static resources to 404 instead of the generic 500 handler")
    void mapsMissingStaticResourceToNotFound() {
        NoResourceFoundException exception =
                new NoResourceFoundException(HttpMethod.GET, "api/billing/config");

        ResponseEntity<Map<String, Object>> response = handler.handleSpringNotFound(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("errorCode", "NOT_FOUND");
    }

    @Test
    @DisplayName("maps a wrong HTTP method to 405 (with Allow header) instead of the generic 500 handler")
    void mapsWrongHttpMethodToMethodNotAllowed() {
        // A GET on a POST/DELETE-only route (e.g. /api/favorites/{type}/{id}) previously fell
        // through to handleGeneric and surfaced as a misleading 500 INTERNAL_ERROR.
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("GET", List.of("POST", "DELETE"));

        ResponseEntity<Map<String, Object>> response = handler.handleMethodNotSupported(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("errorCode", "METHOD_NOT_ALLOWED");
        // 405 must advertise the permitted methods (HTTP-correct Allow header).
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.POST, HttpMethod.DELETE);
    }
}
