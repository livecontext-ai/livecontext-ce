package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.credential.CachedLlmCredentialResolver;
import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.factory.BridgeAvailabilityFilter;
import com.apimarketplace.agent.factory.LLMProviderFactory;
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

    @Value("${conversation.bridge.url:}")
    private String bridgeUrl;

    /** Allowlist of CLI ids the bridge knows about. Mirrors mcp/bridge/cli-detector.mjs CLI_IDS. */
    static final Set<String> SUPPORTED_CLIS = Set.of("claudeCode", "codex", "geminiCli", "mistralVibe");

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
     * Invalidate cached credentials (called after save/delete in admin UI).
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
