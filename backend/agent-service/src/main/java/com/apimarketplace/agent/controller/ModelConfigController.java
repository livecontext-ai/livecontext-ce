package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.common.web.AdminRoleGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/model-config")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelCatalogService service;

    /**
     * Get the effective model list (yml defaults merged with DB overrides).
     * Used by the admin UI to display the model management table.
     *
     * <p>Optional {@code ?category=<key>} (V156) overlays the per-category
     * sidecar so the panel renders the effective state for the active tab -
     * e.g. a model with {@code enabled=true} globally but {@code enabled=false}
     * in the {@code browser_agent} sidecar reports {@code enabled=false} when
     * the admin is on the browser_agent tab. Without it, the admin can't see
     * whether a model is sidecar-disabled.
     */
    @GetMapping
    public ResponseEntity<?> getEffectiveModels(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestParam(value = "category", required = false) String category) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        if (category != null && !com.apimarketplace.agent.domain.ModelCategory.isValidShape(category)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid category key '" + category + "'"));
        }
        return ResponseEntity.ok(service.getEffectiveModelList(category));
    }

    /**
     * Create or update a model override.
     */
    @PutMapping("/overrides")
    public ResponseEntity<?> saveOverride(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestBody Map<String, Object> body) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        ModelConfigOverrideEntity entity = new ModelConfigOverrideEntity();
        try {
            entity.setProvider(requireNonBlankString(body, "provider"));
            entity.setModelId(requireNonBlankString(body, "modelId"));

            if (body.containsKey("enabled")) entity.setEnabled(optionalBoolean(body, "enabled"));
            if (body.containsKey("displayName")) entity.setDisplayName(optionalString(body, "displayName"));
            if (body.containsKey("tier")) entity.setTier(optionalString(body, "tier"));
            if (body.containsKey("ranking")) entity.setRanking(optionalInteger(body, "ranking", 0, 100_000));
            if (body.containsKey("recommended")) entity.setRecommended(optionalBoolean(body, "recommended"));
            if (body.containsKey("priceInput") && body.get("priceInput") != null)
                entity.setPriceInput(optionalBigDecimal(body, "priceInput"));
            if (body.containsKey("priceOutput") && body.get("priceOutput") != null)
                entity.setPriceOutput(optionalBigDecimal(body, "priceOutput"));
            if (body.containsKey("isCustom")) entity.setCustom(Boolean.TRUE.equals(optionalBoolean(body, "isCustom")));
            if (body.containsKey("defaultReasoningEffort")) {
                // Tolerate any JSON scalar: a non-string value stringifies and then fails
                // validation cleanly (400) rather than throwing ClassCastException (500).
                Object effortRaw = body.get("defaultReasoningEffort");
                String effort = effortRaw == null ? null : effortRaw.toString();
                if (!com.apimarketplace.agent.domain.ReasoningEffort.isValidOrBlank(effort)) {
                    return badRequest("Invalid defaultReasoningEffort '" + effort
                            + "'. Expected one of: minimal, low, medium, high, xhigh (or empty to clear).");
                }
                // Empty string is allowed through (saveOverride normalizes blank -> clear).
                entity.setDefaultReasoningEffort(effort);
            }
            if (body.containsKey("rateLimitTpm") || body.containsKey("rateLimitRpm")
                    || body.containsKey("rateLimitTpmPerTenant") || body.containsKey("rateLimitRpmPerTenant")) {
                entity.setRateLimitsExplicitlySet(true);
                entity.setRateLimitTpm(parseNonNegativeInt(body.get("rateLimitTpm")));
                entity.setRateLimitRpm(parseNonNegativeInt(body.get("rateLimitRpm")));
                entity.setRateLimitTpmPerTenant(parseNonNegativeInt(body.get("rateLimitTpmPerTenant")));
                entity.setRateLimitRpmPerTenant(parseNonNegativeInt(body.get("rateLimitRpmPerTenant")));
            }
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        ModelConfigOverrideEntity saved = service.saveOverride(entity);
        log.info("Saved model config override: provider={}, modelId={}, ranking={}",
                saved.getProvider(), saved.getModelId(), saved.getRanking());

        return ResponseEntity.ok(Map.of("id", saved.getId(), "provider", saved.getProvider(), "modelId", saved.getModelId()));
    }

    /**
     * Bulk update rankings (for drag-and-drop reordering).
     * Body: [{ provider, modelId, ranking }, ...]
     *
     * <p>Optional {@code ?category=<key>} (V156) targets the per-category sidecar
     * instead of the global ranking column - used by the admin UI when an
     * admin re-orders models inside a category-specific tab (chat /
     * browser_agent / image_generation / future categories).
     */
    @PutMapping("/overrides/rankings")
    public ResponseEntity<?> bulkUpdateRankings(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestParam(value = "category", required = false) String category,
            @RequestBody List<Map<String, Object>> rankings) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        try {
            validateRankingBatch(rankings);
            if (category == null) {
                service.bulkUpdateRankings(rankings);
                log.info("Bulk updated {} model rankings (global)", rankings.size());
            } else {
                service.bulkUpdateCategoryRankings(category, rankings);
                log.info("Bulk updated {} model rankings (category={})", rankings.size(), category);
            }
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        return ResponseEntity.ok(Map.of("updated", rankings.size()));
    }

    /**
     * Per-category enable/disable for a single model. V156. Body shape:
     * {@code { "category": "browser_agent", "enabled": false }}.
     */
    @PutMapping("/overrides/{provider}/{modelId}/category-enabled")
    public ResponseEntity<?> setCategoryEnabled(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String provider, @PathVariable String modelId,
            @RequestBody Map<String, Object> body) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        String category;
        Boolean enabled;
        try {
            category = requireNonBlankString(body, "category");
            enabled = optionalBoolean(body, "enabled");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (category == null || enabled == null) {
            return badRequest("category and enabled fields are required");
        }

        try {
            service.setCategoryEnabled(provider, modelId, category, enabled);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        log.info("Set category enabled: provider={}, modelId={}, category={}, enabled={}",
                provider, modelId, category, enabled);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Delete an override (revert to yml default).
     */
    @DeleteMapping("/overrides/{provider}/{modelId}")
    public ResponseEntity<?> deleteOverride(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String provider, @PathVariable String modelId) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        service.deleteOverride(provider, modelId);
        log.info("Deleted model config override: provider={}, modelId={}", provider, modelId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reset all overrides to yml defaults.
     */
    @PostMapping("/reset")
    public ResponseEntity<?> resetAll(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        service.resetAll();
        log.info("Reset all model config overrides");
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Parse a rate limit value: null input → null, non-null → integer >= 0.
     * Negative values are rejected (they have special meaning in ProviderLimit
     * but are not valid for per-model overrides set via admin UI).
     */
    private static Integer parseNonNegativeInt(Object value) {
        if (value == null) return null;
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Rate limit value must be a number, got: " + value);
        }
        int parsed = number.intValue();
        if (parsed < 0) {
            throw new IllegalArgumentException("Rate limit values must be >= 0, got: " + parsed);
        }
        return parsed;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static String requireNonBlankString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-blank string");
        }
        return stringValue;
    }

    private static String optionalString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return null;
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(key + " must be a string or null");
        }
        return stringValue;
    }

    private static Boolean optionalBoolean(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return null;
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(key + " must be a boolean or null");
        }
        return booleanValue;
    }

    private static Integer optionalInteger(Map<String, Object> body, String key, int min, int max) {
        Object value = body.get(key);
        if (value == null) return null;
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be a number or null");
        }
        int parsed = number.intValue();
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(
                    key + " out of range (got " + parsed + ", expected " + min + ".." + max + ")");
        }
        return parsed;
    }

    private static BigDecimal optionalBigDecimal(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a decimal number");
        }
    }

    private static void validateRankingBatch(List<Map<String, Object>> rankings) {
        if (rankings == null) {
            throw new IllegalArgumentException("rankings must not be null");
        }
        for (int i = 0; i < rankings.size(); i++) {
            Map<String, Object> item = rankings.get(i);
            if (item == null) {
                throw new IllegalArgumentException("rankings[" + i + "] is null");
            }
            requireNonBlankString(item, "rankings[" + i + "].provider", "provider");
            requireNonBlankString(item, "rankings[" + i + "].modelId", "modelId");
            requiredInteger(item, "rankings[" + i + "].ranking", "ranking", 0, 100_000);
        }
    }

    private static int requiredInteger(Map<String, Object> body, String label, String key, int min, int max) {
        Integer value = optionalInteger(body, label, key, min, max);
        if (value == null) {
            throw new IllegalArgumentException(label + " must be a number");
        }
        return value;
    }

    private static String requireNonBlankString(Map<String, Object> body, String label, String key) {
        Object value = body.get(key);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException(label + " must be a non-blank string");
        }
        return stringValue;
    }

    private static Integer optionalInteger(Map<String, Object> body, String label, String key, int min, int max) {
        Object value = body.get(key);
        if (value == null) return null;
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(label + " must be a number");
        }
        int parsed = number.intValue();
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(
                    label + " out of range (got " + parsed + ", expected " + min + ".." + max + ")");
        }
        return parsed;
    }
}
