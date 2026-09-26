package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.common.web.AdminRoleGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestParam(value = "category", required = false) String category) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        if (category != null && !com.apimarketplace.agent.domain.ModelCategory.isValidShape(category)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid category key '" + category + "'"));
        }
        // The admin panel lists the FULL catalog (every provider, keyed or not);
        // tenantId only drives the per-row `available` flag (would the picker
        // offer it?), not visibility. See ModelCatalogService.getEffectiveModelList.
        return ResponseEntity.ok(service.getEffectiveModelList(category, tenantId));
    }

    /**
     * GET /api/model-config/providers-disabled - the providers switched off entirely.
     *
     * <p>Only the exceptions travel: anything absent is on. The panel already holds the full
     * provider list from the catalogue it just rendered, so sending both would be two sources
     * for one fact.
     */
    @GetMapping("/providers-disabled")
    public ResponseEntity<?> listDisabledProviders(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        return ResponseEntity.ok(service.disabledProviderNames());
    }

    /**
     * PUT /api/model-config/providers/{provider}/enabled - switch a whole provider on or off.
     * Body: {@code {"enabled": false}}.
     *
     * <p>The one move that makes a feed-filled provider manageable: OpenRouter alone carries
     * 438 models, and taking it out of the pickers used to mean 438 clicks. Each model's own
     * flag is left untouched, so switching the provider back on restores the selection the
     * admin had curated instead of turning everything on.
     */
    @PutMapping("/providers/{provider}/enabled")
    public ResponseEntity<?> setProviderEnabled(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable String provider,
            @RequestBody Map<String, Object> body) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        Boolean enabled;
        try {
            enabled = optionalBoolean(body, "enabled");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (enabled == null) {
            return badRequest("enabled field is required");
        }
        try {
            service.setProviderEnabled(provider, enabled);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        }
        log.info("Set provider enabled: provider={}, enabled={}", provider, enabled);
        return ResponseEntity.ok(Map.of("success", true, "provider", provider, "enabled", enabled));
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
            if (body.containsKey("bundleEnabled")) {
                // Cloud-admin-only fine control over what the CE bundle ships
                // (V381): true/false override the row's enabled in the payload,
                // explicit null resets to inherit. Admin-gated like every field
                // here; harmless on CE (nothing builds bundles there).
                entity.setBundleEnabledExplicitlySet(true);
                entity.setBundleEnabled(optionalBoolean(body, "bundleEnabled"));
            }
            if (body.containsKey("freeTierEnabled")) {
                // V493: opens the model to FREE-plan grants. Admin-gated like every
                // field here. Absent key leaves the stored value alone; a null value
                // reads as false (the column is NOT NULL, and "not on the free tier"
                // is the safe meaning of an unset flag).
                entity.setFreeTierEnabledExplicitlySet(true);
                entity.setFreeTierEnabled(Boolean.TRUE.equals(optionalBoolean(body, "freeTierEnabled")));
            }
            if (body.containsKey("displayName")) entity.setDisplayName(optionalString(body, "displayName"));
            if (body.containsKey("tier")) entity.setTier(optionalString(body, "tier"));
            if (body.containsKey("ranking")) entity.setRanking(optionalInteger(body, "ranking", 0, 100_000));
            if (body.containsKey("recommended")) entity.setRecommended(optionalBoolean(body, "recommended"));
            if (body.containsKey("priceInput") && body.get("priceInput") != null)
                entity.setPriceInput(optionalBigDecimal(body, "priceInput"));
            if (body.containsKey("priceOutput") && body.get("priceOutput") != null)
                entity.setPriceOutput(optionalBigDecimal(body, "priceOutput"));
            // Cache prices decide what a cached token costs since V491, so they are
            // editable like the other two rather than feed-only. Accepted on the same
            // terms: present-and-non-null overwrites, absent leaves the feed's value.
            if (body.containsKey("priceCacheRead") && body.get("priceCacheRead") != null)
                entity.setPriceCacheRead(optionalBigDecimal(body, "priceCacheRead"));
            if (body.containsKey("priceCacheWrite") && body.get("priceCacheWrite") != null)
                entity.setPriceCacheWrite(optionalBigDecimal(body, "priceCacheWrite"));
            if (body.containsKey("isCustom")) entity.setCustom(Boolean.TRUE.equals(optionalBoolean(body, "isCustom")));
            if (body.containsKey("defaultReasoningEffort")) {
                // Tolerate any JSON scalar: a non-string value stringifies and then fails
                // validation cleanly (400) rather than throwing ClassCastException (500).
                Object effortRaw = body.get("defaultReasoningEffort");
                String effort = effortRaw == null ? null : effortRaw.toString();
                if (!com.apimarketplace.agent.domain.ReasoningEffort.isValidOrBlank(effort)) {
                    return badRequest("Invalid defaultReasoningEffort '" + effort
                            + "'. Expected one of: "
                            + com.apimarketplace.agent.domain.ReasoningEffort.validValuesCsv()
                            + " (or empty to clear).");
                }
                // Empty string is allowed through (saveOverride normalizes blank -> clear).
                entity.setDefaultReasoningEffort(effort);
            }
            if (body.containsKey("replacementProvider") || body.containsKey("replacementModel")) {
                // V515: the model this one is replaced by at execution time while disabled.
                // Blank/null on both clears it; the service validates the pair.
                entity.setReplacementExplicitlySet(true);
                entity.setReplacementProvider(optionalString(body, "replacementProvider"));
                entity.setReplacementModel(optionalString(body, "replacementModel"));
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

        ModelConfigOverrideEntity saved;
        try {
            saved = service.saveOverride(entity);
        } catch (IllegalArgumentException e) {
            // The price guard refuses to enable an unpriced model, and says why. Thrown from
            // the service, so it used to land outside the try above and reach the browser as a
            // bodyless 500 - which the panel could only render as "Failed to save changes".
            return badRequest(e.getMessage());
        }
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
     * admin re-orders models inside a category-specific tab (chat,
     * browser_agent, or a future category).
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

        try {
            service.deleteOverride(provider, modelId);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
        log.info("Deleted model config override: provider={}, modelId={}", provider, modelId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The retired models (V533), most recently retired first.
     */
    @GetMapping("/retired")
    public ResponseEntity<?> listRetired(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        return ResponseEntity.ok(service.listRetiredModels());
    }

    /**
     * Retire models for good. Body: {@code {"models": [{"provider": "...", "modelId": "..."}]}}.
     * Returns {@code {"retired": <count>}}; a model already retired is not counted.
     */
    @PostMapping("/retired")
    public ResponseEntity<?> retire(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestBody Map<String, Object> body) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        List<ModelCatalogService.ModelRef> models;
        try {
            models = parseModelRefs(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        int retired;
        try {
            retired = service.retireModels(models, userId);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        return ResponseEntity.ok(Map.of("retired", retired));
    }

    /**
     * Bring retired models back into the catalog, disabled. Same body as {@link #retire}.
     * Returns {@code {"restored": <count>}}; a model that is not retired is not counted.
     */
    @PostMapping("/retired/restore")
    public ResponseEntity<?> restore(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestBody Map<String, Object> body) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        List<ModelCatalogService.ModelRef> models;
        try {
            models = parseModelRefs(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        int restored = service.restoreModels(models);
        return ResponseEntity.ok(Map.of("restored", restored));
    }

    /** Upper bound on one retire/restore request: the whole catalog is under a thousand rows. */
    static final int MAX_MODELS_PER_REQUEST = 2_000;

    private static List<ModelCatalogService.ModelRef> parseModelRefs(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("models");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("models must be a non-empty list of {provider, modelId}");
        }
        if (list.size() > MAX_MODELS_PER_REQUEST) {
            throw new IllegalArgumentException("at most " + MAX_MODELS_PER_REQUEST + " models per request");
        }
        List<ModelCatalogService.ModelRef> refs = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("each model must be an object {provider, modelId}");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) m;
            String provider = requireNonBlankString(entry, "provider");
            String modelId = requireNonBlankString(entry, "modelId");
            // The column widths: an over-long id cannot name a model, and would be a 500 on insert.
            if (provider.length() > 50 || modelId.length() > 150) {
                throw new IllegalArgumentException("provider is at most 50 characters and modelId at most 150");
            }
            refs.add(new ModelCatalogService.ModelRef(provider, modelId));
        }
        // A pair named twice would create the same new tombstone row twice (unique violation at
        // flush, a 500): the request means each model once.
        return refs.stream().distinct().toList();
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
