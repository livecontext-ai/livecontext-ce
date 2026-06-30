package com.apimarketplace.agent.factory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

/**
 * Shared filter that removes "bridge providers" (claude-code / codex /
 * gemini-cli / mistral-vibe) from a model list whose CLI is not actually
 * runnable on the bridge host.
 *
 * <p>Lives in {@code shared-agent-lib} so BOTH the cloud agent-service AND
 * the CE monolith call the same code path. Without this, the monolith stub
 * controller used to bypass the filter and advertise CLI providers that were
 * not installed (the 2026-04-09 incident). The filter is intentionally a
 * tiny instance with its own short TTL cache so it can be wired by
 * either deployment without dragging in the agent-service @Service graph.
 *
 * <p><b>CLI providers differ from API providers.</b> An API model is usable as
 * soon as its key is present; a CLI model needs a live, authenticated CLI on
 * the bridge host. So a CLI provider whose availability we cannot VERIFY (the
 * bridge URL is unset, the bridge is unreachable, or {@code /cli-status} is
 * malformed) must be treated as unavailable and HIDDEN in {@link #strict}
 * mode (the default for the user-facing picker), rather than left in the list.
 * A CLI that is installed but NOT authenticated is likewise hidden - it would
 * only fail at run time with "please log in".
 */
public class BridgeAvailabilityFilter {

    private static final Logger log = LoggerFactory.getLogger(BridgeAvailabilityFilter.class);

    /**
     * Maps the bridge provider name returned by {@code LLMProviderFactory}
     * to the camelCase CLI id reported by {@code mcp/bridge/cli-detector.mjs}.
     */
    public static final Map<String, String> BRIDGE_PROVIDER_TO_CLI_ID = Map.of(
            "claude-code",  "claudeCode",
            "codex",        "codex",
            "gemini-cli",   "geminiCli",
            "mistral-vibe", "mistralVibe"
    );

    private static final long DEFAULT_CACHE_TTL_MS = 60_000L;

    private final String bridgeUrl;
    private final long cacheTtlMs;
    /**
     * When true (default), CLI providers whose availability cannot be verified
     * (bridge unreachable / URL unset / malformed status) are DROPPED. When
     * false, the filter degrades to a no-op in that case (legacy lenient
     * behaviour - keep showing CLI providers when the bridge is briefly down).
     */
    private final boolean strict;
    /** Keyed by CLI id - the bridge reports the binary as present. */
    private volatile Map<String, Boolean> cachedInstalled = Collections.emptyMap();
    /** Keyed by CLI id - the binary is present AND reported authenticated (runnable). */
    private volatile Map<String, Boolean> cachedAvailable = Collections.emptyMap();
    private volatile long cachedAt = 0L;

    public BridgeAvailabilityFilter(String bridgeUrl) {
        this(bridgeUrl, DEFAULT_CACHE_TTL_MS, true);
    }

    public BridgeAvailabilityFilter(String bridgeUrl, boolean strict) {
        this(bridgeUrl, DEFAULT_CACHE_TTL_MS, strict);
    }

    public BridgeAvailabilityFilter(String bridgeUrl, long cacheTtlMs) {
        this(bridgeUrl, cacheTtlMs, true);
    }

    public BridgeAvailabilityFilter(String bridgeUrl, long cacheTtlMs, boolean strict) {
        this.bridgeUrl = bridgeUrl;
        this.cacheTtlMs = cacheTtlMs;
        this.strict = strict;
    }

    /**
     * Drop bridge providers from a {@code base} models map (the shape returned
     * by {@link LLMProviderFactory#getAllModelsInfo()}) when their CLI is not
     * runnable on the bridge host. Mutates {@code base} in place.
     *
     * <p>A CLI provider is kept only when the bridge reports it installed AND
     * authenticated. When availability cannot be verified (bridge unreachable /
     * URL unset / malformed status) the per-CLI map is empty: in {@link #strict}
     * mode EVERY bridge provider is dropped (treat "unknown" as "unavailable" -
     * a CLI model must not be offered if we cannot confirm it can run); in
     * lenient mode the filter is a no-op so the picker degrades gracefully.
     * Non-bridge (API) providers are never touched.
     */
    @SuppressWarnings("unchecked")
    public void filter(Map<String, Object> base) {
        if (base == null) return;
        List<Map<String, Object>> providers = (List<Map<String, Object>>) base.get("providers");
        if (providers == null || providers.isEmpty()) return;

        Map<String, Boolean> available = getAvailableMap();
        boolean verified = !available.isEmpty();

        if (!verified && !strict) {
            // Lenient: bridge unreachable / URL unset -> keep CLI providers
            // rather than empty the list while the bridge is briefly down.
            return;
        }

        providers.removeIf(p -> {
            String providerName = (String) p.get("name");
            String cliId = BRIDGE_PROVIDER_TO_CLI_ID.get(providerName);
            if (cliId == null) return false; // not a CLI/bridge provider -> never touched
            // Strict + unverified: `available` is empty so every CLI provider
            // is removed. Verified: keep only CLIs reported installed AND authed.
            return !Boolean.TRUE.equals(available.get(cliId));
        });
    }

    /** Force-clear the in-memory cache (for tests / admin "refresh" actions). */
    public void invalidate() {
        cachedInstalled = Collections.emptyMap();
        cachedAvailable = Collections.emptyMap();
        cachedAt = 0L;
    }

    /**
     * Return the current "installed" map (keyed by CLI id: claudeCode,
     * codex, geminiCli, mistralVibe) for callers that want to annotate
     * rows rather than drop them. Reflects the raw {@code installed} flag from
     * {@code /cli-status} (NOT auth), preserving the admin "bridgeAvailable"
     * annotation semantics. Same TTL + bridge-unreachable fallback as
     * {@link #filter}. Empty map means "can't tell" (bridge server unreachable)
     * - callers should treat that as "unknown availability", typically rendering
     * an "unknown" badge rather than "not installed".
     */
    public Map<String, Boolean> installedMap() {
        return Collections.unmodifiableMap(getInstalledMap());
    }

    private Map<String, Boolean> getInstalledMap() {
        refreshIfStale();
        return cachedInstalled;
    }

    private Map<String, Boolean> getAvailableMap() {
        refreshIfStale();
        return cachedAvailable;
    }

    @SuppressWarnings("unchecked")
    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (cachedAt != 0L && now - cachedAt < cacheTtlMs) {
            return;
        }
        if (bridgeUrl == null || bridgeUrl.isBlank()) {
            cachedInstalled = Collections.emptyMap();
            cachedAvailable = Collections.emptyMap();
            cachedAt = now;
            return;
        }
        try {
            RestTemplate rt = new RestTemplateBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .readTimeout(Duration.ofSeconds(5))
                    .build();
            Map<String, Object> body = rt.getForObject(bridgeUrl + "/cli-status", Map.class);
            Map<String, Boolean> installed = new HashMap<>();
            Map<String, Boolean> available = new HashMap<>();
            if (body != null) {
                Object clisObj = body.get("clis");
                if (clisObj instanceof Map<?, ?> clis) {
                    for (var entry : clis.entrySet()) {
                        if (entry.getValue() instanceof Map<?, ?> cli) {
                            String key = String.valueOf(entry.getKey());
                            boolean isInstalled = Boolean.TRUE.equals(cli.get("installed"));
                            // The bridge reports a best-effort `authenticated`
                            // flag (CLI logged in / API key present). Installed
                            // but NOT authed cannot actually run, so it's not
                            // "available". A MISSING field (older bridge that
                            // predates the auth probe) means "don't know" -> fall
                            // back to installed so we don't over-hide.
                            boolean authReported = cli.containsKey("authenticated");
                            boolean isAuthed = !authReported || Boolean.TRUE.equals(cli.get("authenticated"));
                            installed.put(key, isInstalled);
                            available.put(key, isInstalled && isAuthed);
                        }
                    }
                }
            }
            cachedInstalled = installed;
            cachedAvailable = available;
            cachedAt = now;
        } catch (Exception e) {
            log.debug("Bridge /cli-status unreachable, treating CLI availability as unknown: {}", e.getMessage());
            cachedInstalled = Collections.emptyMap();
            cachedAvailable = Collections.emptyMap();
            cachedAt = now;
        }
    }
}
