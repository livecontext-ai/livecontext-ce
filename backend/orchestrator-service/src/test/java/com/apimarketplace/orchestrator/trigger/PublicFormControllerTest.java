package com.apimarketplace.orchestrator.trigger;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicFormController")
class PublicFormControllerTest {

    @Mock
    private FormDispatchService formDispatchService;

    @Mock
    private PublicFormRenderer formRenderer;

    @Mock
    private HttpServletRequest request;

    private PublicFormController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicFormController(formDispatchService, formRenderer);
    }

    @Test
    @DisplayName("GET /{token} returns 200 text/html with rendered page when token is valid")
    void getReturnsRenderedHtmlOnSuccess() {
        Map<String, Object> config = Map.of("name", "F", "isActive", true, "formConfig", java.util.List.of());
        when(formDispatchService.getFormConfig("tok")).thenReturn(config);
        when(formRenderer.renderPage("tok", config)).thenReturn("<html>rendered</html>");

        ResponseEntity<String> response = controller.renderFormPage("tok");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(response.getBody()).isEqualTo("<html>rendered</html>");
    }

    @Test
    @DisplayName("GET /{token} returns 404 text/html with not-found page when token is unknown")
    void getReturnsNotFoundHtmlOnInvalidToken() {
        when(formDispatchService.getFormConfig("bad"))
                .thenThrow(new IllegalArgumentException("not found"));
        when(formRenderer.renderNotFound()).thenReturn("<html>not found</html>");

        ResponseEntity<String> response = controller.renderFormPage("bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(response.getBody()).isEqualTo("<html>not found</html>");
    }

    @Test
    @DisplayName("GET /{token} returns 500 text/html with not-found page when downstream throws unexpected error")
    void getReturnsFallbackHtmlOnUnexpectedError() {
        when(formDispatchService.getFormConfig("tok"))
                .thenThrow(new RuntimeException("downstream exploded"));
        when(formRenderer.renderNotFound()).thenReturn("<html>not found</html>");

        ResponseEntity<String> response = controller.renderFormPage("tok");

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(response.getBody()).isEqualTo("<html>not found</html>");
    }

    @Test
    @DisplayName("GET /{token}/config returns JSON config when token is valid")
    void getConfigReturnsJsonOnSuccess() {
        Map<String, Object> config = Map.of("name", "F", "isActive", true);
        when(formDispatchService.getFormConfig("tok")).thenReturn(config);

        ResponseEntity<?> response = controller.getFormConfig("tok");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(config);
    }

    @Test
    @DisplayName("GET /{token}/config returns 404 when token is unknown")
    void getConfigReturns404OnInvalidToken() {
        when(formDispatchService.getFormConfig("bad"))
                .thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> response = controller.getFormConfig("bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("POST /{token} uses X-Forwarded-For when present and returns dispatch result")
    void postUsesForwardedForAndReturnsOk() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10, 10.0.0.1");
        Map<String, Object> payload = Map.of("message", "hi");
        Map<String, Object> result = Map.of("status", "ok");
        when(formDispatchService.submitForm("tok", payload, "203.0.113.10")).thenReturn(result);

        ResponseEntity<?> response = controller.submitForm("tok", payload, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(result);
    }

    @Test
    @DisplayName("POST /{token} falls back to remoteAddr when X-Forwarded-For is absent")
    void postFallsBackToRemoteAddr() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("198.51.100.7");
        when(formDispatchService.submitForm(anyString(), any(), any())).thenReturn(Map.of("status", "ok"));

        ResponseEntity<?> response = controller.submitForm("tok", Map.of(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("POST /{token} returns 404 when token is unknown")
    void postReturns404OnInvalidToken() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("198.51.100.7");
        when(formDispatchService.submitForm(anyString(), any(), any()))
                .thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> response = controller.submitForm("tok", Map.of(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("POST /{token} returns 409 when form is inactive")
    void postReturns409OnInactiveForm() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("198.51.100.7");
        when(formDispatchService.submitForm(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("Form is inactive"));

        ResponseEntity<?> response = controller.submitForm("tok", Map.of(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }
}
