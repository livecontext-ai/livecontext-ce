package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.service.ModelCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Coverage of the V156 admin-write endpoints exposed for the per-category
 * ranking + enable/disable feature. The plain {@link ModelConfigController}
 * existing endpoints stay untouched - this test only exercises the new
 * branches:
 *   - PUT /overrides/rankings?category=… → forwards to bulkUpdateCategoryRankings
 *   - PUT /overrides/{provider}/{modelId}/category-enabled → setCategoryEnabled
 *   - 403 + 400 paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelConfigController - V156 admin endpoints")
class ModelConfigControllerCategoryTest {

    @Mock private ModelCatalogService service;
    private ModelConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new ModelConfigController(service);
    }

    @Test
    @DisplayName("rankings without ?category routes to global bulkUpdateRankings (legacy behaviour)")
    void rankingsWithoutCategoryUsesGlobalPath() {
        List<Map<String, Object>> body = List.of(
                Map.of("provider", "openai", "modelId", "gpt-5", "ranking", 1));

        ResponseEntity<?> resp = controller.bulkUpdateRankings("ADMIN", null, body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).bulkUpdateRankings(body);
        verify(service, never()).bulkUpdateCategoryRankings(anyString(), any());
    }

    @Test
    @DisplayName("rankings?category=browser_agent forwards to the per-category bulk method")
    void rankingsWithCategoryRoutesToCategoryPath() {
        List<Map<String, Object>> body = List.of(
                Map.of("provider", "openai", "modelId", "gpt-5", "ranking", 1));

        ResponseEntity<?> resp = controller.bulkUpdateRankings("ADMIN", "browser_agent", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).bulkUpdateCategoryRankings("browser_agent", body);
        verify(service, never()).bulkUpdateRankings(any());
    }

    @Test
    @DisplayName("rankings without ADMIN role is denied (403) before service is touched")
    void rankingsWithoutAdminDenied() {
        ResponseEntity<?> resp = controller.bulkUpdateRankings("USER", "chat", List.of());

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
        verify(service, never()).bulkUpdateCategoryRankings(anyString(), any());
        verify(service, never()).bulkUpdateRankings(any());
    }

    @Test
    @DisplayName("category-enabled with ADMIN delegates to setCategoryEnabled")
    void categoryEnabledHappyPath() {
        ResponseEntity<?> resp = controller.setCategoryEnabled(
                "ADMIN", "openai", "gpt-5",
                Map.of("category", "browser_agent", "enabled", false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).setCategoryEnabled("openai", "gpt-5", "browser_agent", false);
    }

    @Test
    @DisplayName("category-enabled rejects body missing 'category' or 'enabled' with 400")
    void categoryEnabledRejectsIncomplete() {
        ResponseEntity<?> missingCategory = controller.setCategoryEnabled(
                "ADMIN", "openai", "gpt-5", Map.of("enabled", true));
        assertThat(missingCategory.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<?> missingEnabled = controller.setCategoryEnabled(
                "ADMIN", "openai", "gpt-5", Map.of("category", "chat"));
        assertThat(missingEnabled.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        verify(service, never()).setCategoryEnabled(any(), any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("category-enabled rejects non-ADMIN before reaching the service")
    void categoryEnabledRequiresAdmin() {
        ResponseEntity<?> resp = controller.setCategoryEnabled(
                "USER", "openai", "gpt-5",
                Map.of("category", "chat", "enabled", true));

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
        verify(service, never()).setCategoryEnabled(any(), any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("saveOverride rejects a non-string defaultReasoningEffort with 400, not 500 (L1 ClassCastException regression)")
    void saveOverrideRejectsNonStringReasoningEffort() {
        // Pre-fix the controller did `(String) body.get(...)` → ClassCastException → 500.
        Map<String, Object> body = Map.<String, Object>of(
                "provider", "codex", "modelId", "gpt-5", "defaultReasoningEffort", 123);

        ResponseEntity<?> resp = controller.saveOverride("ADMIN", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).saveOverride(any());
    }

    @Test
    @DisplayName("saveOverride rejects an unknown defaultReasoningEffort level with 400")
    void saveOverrideRejectsUnknownReasoningEffort() {
        Map<String, Object> body = Map.<String, Object>of(
                "provider", "codex", "modelId", "gpt-5", "defaultReasoningEffort", "bogus");

        ResponseEntity<?> resp = controller.saveOverride("ADMIN", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).saveOverride(any());
    }

    @Test
    @DisplayName("saveOverride rejects a missing provider with 400 before service call")
    void saveOverrideRejectsMissingProvider() {
        Map<String, Object> body = Map.<String, Object>of(
                "modelId", "gpt-5", "enabled", true);

        ResponseEntity<?> resp = controller.saveOverride("ADMIN", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).saveOverride(any());
    }

    @Test
    @DisplayName("saveOverride rejects non-boolean flags with 400, not 500")
    void saveOverrideRejectsNonBooleanFlags() {
        Map<String, Object> body = Map.<String, Object>of(
                "provider", "codex", "modelId", "gpt-5", "enabled", "true");

        ResponseEntity<?> resp = controller.saveOverride("ADMIN", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).saveOverride(any());
    }

    @Test
    @DisplayName("saveOverride rejects non-numeric ranking with 400, not 500")
    void saveOverrideRejectsNonNumericRanking() {
        Map<String, Object> body = Map.<String, Object>of(
                "provider", "codex", "modelId", "gpt-5", "ranking", "first");

        ResponseEntity<?> resp = controller.saveOverride("ADMIN", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).saveOverride(any());
    }

    @Test
    @DisplayName("saveOverride rejects malformed price values with 400, not 500")
    void saveOverrideRejectsMalformedPrice() {
        Map<String, Object> body = Map.<String, Object>of(
                "provider", "codex", "modelId", "gpt-5", "priceInput", "not-a-decimal");

        ResponseEntity<?> resp = controller.saveOverride("ADMIN", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).saveOverride(any());
    }

    @Test
    @DisplayName("saveOverride rejects negative rate limits with 400, not 500")
    void saveOverrideRejectsNegativeRateLimit() {
        Map<String, Object> body = Map.<String, Object>of(
                "provider", "codex", "modelId", "gpt-5", "rateLimitRpm", -1);

        ResponseEntity<?> resp = controller.saveOverride("ADMIN", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).saveOverride(any());
    }

    @Test
    @DisplayName("global bulk rankings rejects malformed rows with 400 before service call")
    void globalBulkRankingsRejectMalformedRows() {
        List<Map<String, Object>> body = List.of(
                Map.<String, Object>of("provider", "codex", "modelId", "gpt-5", "ranking", "first"));

        ResponseEntity<?> resp = controller.bulkUpdateRankings("ADMIN", null, body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).bulkUpdateRankings(any());
        verify(service, never()).bulkUpdateCategoryRankings(anyString(), any());
    }

    @Test
    @DisplayName("global bulk rankings rejects rows missing ranking with 400 before service call")
    void globalBulkRankingsRejectMissingRankingRows() {
        List<Map<String, Object>> body = List.of(
                Map.<String, Object>of("provider", "codex", "modelId", "gpt-5"));

        ResponseEntity<?> resp = controller.bulkUpdateRankings("ADMIN", null, body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).bulkUpdateRankings(any());
        verify(service, never()).bulkUpdateCategoryRankings(anyString(), any());
    }

    @Test
    @DisplayName("category-enabled rejects non-boolean enabled with 400 before service call")
    void categoryEnabledRejectsNonBooleanEnabled() {
        ResponseEntity<?> resp = controller.setCategoryEnabled(
                "ADMIN", "openai", "gpt-5",
                Map.<String, Object>of("category", "chat", "enabled", "true"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).setCategoryEnabled(any(), any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("category-enabled maps service validation failures to 400")
    void categoryEnabledMapsServiceValidationFailures() {
        doThrow(new IllegalArgumentException("Invalid category key: bad/category"))
                .when(service).setCategoryEnabled("openai", "gpt-5", "bad/category", true);

        ResponseEntity<?> resp = controller.setCategoryEnabled(
                "ADMIN", "openai", "gpt-5",
                Map.of("category", "bad/category", "enabled", true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
