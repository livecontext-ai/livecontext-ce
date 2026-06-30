package com.apimarketplace.orchestrator.trigger;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public controller for form endpoints (no auth required, token-based).
 */
@RestController
@RequestMapping("/api/internal/form")
public class PublicFormController {

    private static final Logger logger = LoggerFactory.getLogger(PublicFormController.class);

    private final FormDispatchService formDispatchService;
    private final PublicFormRenderer formRenderer;

    public PublicFormController(FormDispatchService formDispatchService,
                                PublicFormRenderer formRenderer) {
        this.formDispatchService = formDispatchService;
        this.formRenderer = formRenderer;
    }

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> renderFormPage(@PathVariable String token) {
        try {
            Map<String, Object> config = formDispatchService.getFormConfig(token);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(formRenderer.renderPage(token, config));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_HTML)
                    .body(formRenderer.renderNotFound());
        } catch (Exception e) {
            logger.error("Error rendering form page for token {}: {}", token, e.getMessage());
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_HTML)
                    .body(formRenderer.renderNotFound());
        }
    }

    @GetMapping("/{token}/config")
    public ResponseEntity<?> getFormConfig(@PathVariable String token) {
        try {
            return ResponseEntity.ok(formDispatchService.getFormConfig(token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{token}")
    public ResponseEntity<?> submitForm(
            @PathVariable String token,
            @RequestBody Map<String, Object> formData,
            HttpServletRequest request) {
        try {
            String ipAddress = getClientIp(request);
            Map<String, Object> result = formDispatchService.submitForm(token, formData, ipAddress);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error processing form submission for token {}: {}", token, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to process submission"));
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
