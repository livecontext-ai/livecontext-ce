package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.credential.CachedLlmCredentialResolver;
import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.factory.BridgeAvailabilityFilter;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.common.web.AdminRoleGuard;
import com.apimarketplace.agent.provider.LLMProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * REST controller for LLM provider credential status.
 * Used by the admin UI to show which providers have DB-stored keys vs env var keys.
 */
@RestController
@RequestMapping("/api/llm-providers")
@RequiredArgsConstructor
public class LlmProviderStatusController {

    private final LLMProviderFactory llmProviderFactory;
    private final LlmCredentialRepository credentialRepository;
    private final CachedLlmCredentialResolver credentialResolver;
    private final ModelCatalogService modelCatalogService;

    @Value("${conversation.bridge.url:}")
    private String bridgeUrl;

    /** Allowlist of CLI ids the bridge knows about. Mirrors mcp/bridge/cli-detector.mjs CLI_IDS. */
    static final Set<String> SUPPORTED_CLIS = Set.of("claudeCode", "codex", "geminiCli", "mistralVibe");

    /**
     * Per-user budget for {@code POST /validate}: a signed-in user pasting their own keys
     * needs a handful of checks an hour; a script testing stolen keys through the platform
     * needs thousands. Sliding window, in memory (per replica, which is enough to blunt it).
     */
    static final int KEY_CHECKS_PER_HOUR = 30;
    private final KeyCheckBudget keyCheckBudget = new KeyCheckBudget(KEY_CHECKS_PER_HOUR, Duration.ofHours(1));

    static final class KeyCheckBudget {
        private final int limit;
        private final long windowMs;
        private final Map<String, java.util.ArrayDeque<Long>> stamps = new java.util.concurrent.ConcurrentHashMap<>();

        KeyCheckBudget(int limit, Duration window) {
            this.limit = limit;
            this.windowMs = window.toMillis();
        }

        /**
         * Memory is bounded by (distinct users per replica) x limit stamps, and a user's
         * stamps age out of the deque as the window slides; the map entry itself is kept
         * (a few bytes per user who ever pasted a key on this replica).
         */
        boolean tryAcquire(String userId) {
            long now = System.currentTimeMillis();
            java.util.ArrayDeque<Long> window = stamps.computeIfAbsent(userId, k -> new java.util.ArrayDeque<>());
            synchronized (window) {
                while (!window.isEmpty() && now - window.peekFirst() > windowMs) {
                    window.pollFirst();
                }
                if (window.size() >= limit) {
                    return false;
                }
                window.addLast(now);
                return true;
            }
        }
    }

    /**
     * GET /api/llm-providers/offering-models - the providers worth bringing a key for.
     *
     * <p>Deliberately NOT admin-gated, unlike {@code /status} beside it: it carries no
     * credential state whatever, only which providers this install exposes models for. The
     * own-keys panel needs exactly that, and needs it for providers the caller holds no key
     * for, which is precisely what the picker catalogue hides from them. A provider whose
     * models an admin has all switched off is absent here, so the panel never invites a key
     * that would serve nothing and never names a provider the user cannot use. CLI bridges are
     * never listed.
     */
    @GetMapping("/offering-models")
    public ResponseEntity<List<String>> getProvidersOfferingModels() {
        return ResponseEntity.ok(modelCatalogService.providersOfferingModels());
    }

    /**
     * Get status of all LLM providers: configured state, source (db/env/none).
     */
    @GetMapping("/status")
    public ResponseEntity<?> getProviderStatus(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        List<Map<String, Object>> statuses = new ArrayList<>();

        // Derive the API-provider list from LLMProviderFactory rather than
        // hardcoding it - adding a new API provider only requires registering
        // it as a Spring bean. Bridge providers (claude-code/codex/etc.) are
        // excluded here because they have no API key concept; the admin UI
        // shows them via the separate /bridge-status endpoint.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allProviders = (List<Map<String, Object>>) llmProviderFactory
                .getAllModelsInfoAdmin().getOrDefault("providers", List.of());
        List<String> providerNames = allProviders.stream()
                .map(p -> (String) p.get("name"))
                .filter(Objects::nonNull)
                .filter(name -> !BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.containsKey(name))
                .toList();

        for (String providerName : providerNames) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("providerName", providerName);
            status.put("integrationName", LlmCredentialRepository.toIntegrationName(providerName));

            boolean configured = false;
            boolean hasDbKey = false;
            try {
                LLMProvider provider = llmProviderFactory.findProvider(providerName).orElse(null);
                hasDbKey = credentialRepository.hasDbKey(providerName);
                configured = provider != null && provider.isConfigured();
            } catch (Exception e) {
                // Graceful degradation: provider not available, show as unconfigured
            }

            status.put("configured", configured);
            status.put("hasDbKey", hasDbKey);

            if (hasDbKey) {
                status.put("source", "database");
            } else if (configured) {
                status.put("source", "environment");
            } else {
                status.put("source", "none");
            }

            statuses.add(status);
        }

        return ResponseEntity.ok(statuses);
    }

    /**
     * Self-scoped invalidation: drops the CALLER's own cached key and hasDbKey slot for one
     * provider, after they saved, removed or switched their own key. No admin role needed
     * because it can only touch the caller's slots (the user id is the gateway-injected
     * header). Without this a toggle lies for the cache TTL on this replica.
     */
    /**
     * Whether a key the caller is about to save is accepted by its vendor. Any signed-in
     * user may ask (it is THEIR key); the key is neither stored nor logged here. "Rejected"
     * is a 401/403 from the vendor; "unverified" means the vendor could not be asked, which
     * the caller must not treat as a bad key.
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validateKey(@RequestHeader(value = "X-User-ID", required = false) String userId,
                                         @RequestBody Map<String, Object> body) {
        String providerName = body.get("provider") instanceof String s ? s.trim().toLowerCase() : "";
        String apiKey = body.get("apiKey") instanceof String s ? s : "";
        if (providerName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "'provider' is required", "code", "provider_required"));
        }
        if (apiKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "'apiKey' is required", "code", "api_key_required"));
        }
        if (!keyCheckBudget.tryAcquire(userId == null || userId.isBlank() ? "anonymous" : userId)) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Too many key checks, try again later", "code", "rate_limited"));
        }
        return llmProviderFactory.findProvider(providerName)
                .<ResponseEntity<?>>map(provider -> {
                    LLMProvider.KeyCheck check = provider.validateApiKey(apiKey);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("valid", check.valid());
                    result.put("verified", check.verified());
                    result.put("error", check.error() == null ? "" : check.error());
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "Unknown provider: " + providerName, "code", "unknown_provider")));
    }

    @PostMapping("/invalidate-cache/mine")
    public ResponseEntity<?> invalidateMyCache(
            @RequestHeader("X-User-ID") String userId,
            @RequestParam String provider) {
        credentialResolver.invalidate(userId, provider);
        credentialRepository.clearHasDbKeyCache(userId, provider);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Invalidate cached credentials platform-wide (called after save/delete in the admin UI).
     */
    @PostMapping("/invalidate-cache")
    public ResponseEntity<?> invalidateCache(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestParam(required = false) String provider) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        if (provider != null) {
            credentialResolver.invalidate(provider);
            credentialRepository.clearHasDbKeyCache(provider);
        } else {
            credentialResolver.invalidateAll();
            credentialRepository.clearHasDbKeyCacheAll();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Check bridge reachability AND per-CLI availability (Claude Code, Codex,
     * Gemini CLI, Mistral Vibe). Proxies GET to the bridge /cli-status endpoint
     * which actually probes each CLI binary on the bridge host (cross-OS).
     *
     * Query params:
     *   cli - optional, one of claudeCode|codex|geminiCli|mistralVibe to check
     *         a single CLI. When omitted the response contains all four.
     *   force - optional, "1" to bypass the bridge's 30s in-memory cache.
     *
     * Response shape (multi-CLI):
     *   {
     *     "connected": true,            // bridge reachable AND at least one CLI installed
     *     "bridgeReachable": true,
     *     "platform": "linux"|"darwin"|"win32",
     *     "clis": {
     *       "claudeCode":  { installed, binary, version, error },
     *       "codex":       { ... },
     *       "geminiCli":   { ... },
     *       "mistralVibe": { ... }
     *     }
     *   }
     *
     * Response shape with ?cli= filter:
     *   {
     *     "connected": <installed>,    // true iff that specific CLI is installed
     *     "bridgeReachable": true,
     *     "platform": "...",
     *     "cli": { id, label, installed, binary, version, error }
     *   }
     *
     * Falls back to a {connected:false, bridgeReachable:false, error:"..."} body
     * (still HTTP 200) when the bridge is not configured or not reachable, so
     * the UI can render an actionable message instead of a stack trace.
     */
    @GetMapping("/bridge-status")
    public ResponseEntity<?> getBridgeStatus(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestParam(required = false) String cli,
            @RequestParam(required = false) String force) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        if (bridgeUrl == null || bridgeUrl.isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "connected", false,
                    "bridgeReachable", false,
                    "error", "Bridge not configured"
            ));
        }

        // Reject unknown cli ids server-side too - defence in depth so a
        // malformed query string can't be reflected into the bridge URL
        // (defends against log-injection / cache-key pollution / SSRF on a
        // future bridge that might have unrelated routes).
        String safeCli = (cli != null && !cli.isBlank()) ? cli : null;
        if (safeCli != null && !SUPPORTED_CLIS.contains(safeCli)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "connected", false,
                    "bridgeReachable", false,
                    "error", "Unknown cli '" + safeCli + "'. Expected one of: " + SUPPORTED_CLIS
            ));
        }

        StringBuilder url = new StringBuilder(bridgeUrl).append("/cli-status");
        boolean first = true;
        if (safeCli != null) {
            url.append('?').append("cli=")
               .append(URLEncoder.encode(safeCli, StandardCharsets.UTF_8));
            first = false;
        }
        if ("1".equals(force) || "true".equalsIgnoreCase(force)) {
            url.append(first ? '?' : '&').append("force=1");
        }

        try {
            RestTemplate rt = new RestTemplateBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .readTimeout(Duration.ofSeconds(8))
                    .build();
            @SuppressWarnings("unchecked")
            Map<String, Object> body = rt.getForObject(url.toString(), Map.class);
            if (body == null) {
                return ResponseEntity.ok(Map.of(
                        "connected", false,
                        "bridgeReachable", true,
                        "error", "Bridge returned empty body"
                ));
            }

            Map<String, Object> result = new LinkedHashMap<>(body);
            result.put("bridgeReachable", true);
            result.put("connected", computeConnected(body, cli));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "connected", false,
                    "bridgeReachable", false,
                    "error", "Bridge not reachable: " + e.getClass().getSimpleName()
            ));
        }
    }

    /**
     * "connected" semantics:
     *   - With ?cli=foo → true iff that specific CLI reports installed=true.
     *   - Without filter → true iff at least one CLI is installed (i.e. the
     *     bridge can actually do something useful for this customer).
     */
    /** Visible for testing. */
    @SuppressWarnings("unchecked")
    boolean computeConnected(Map<String, Object> body, String cliFilter) {
        if (cliFilter != null && !cliFilter.isBlank()) {
            Object cliEntry = body.get("cli");
            if (cliEntry instanceof Map<?, ?> m) {
                return Boolean.TRUE.equals(m.get("installed"));
            }
            return false;
        }
        Object clisObj = body.get("clis");
        if (clisObj instanceof Map<?, ?> clis) {
            for (Object v : clis.values()) {
                if (v instanceof Map<?, ?> entry && Boolean.TRUE.equals(entry.get("installed"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
