package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.http.CredentialHostBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Decides, at execution time, whether a user-created API may resolve the credential its key
 * names (LC-002 / LC-057, CASA readiness).
 *
 * <p>Credentials are resolved by KEY for the caller (then the org, then the PLATFORM pool), and
 * nothing on a stored secret records which API it was filled in for. A user-created API carrying
 * a key that belongs to somebody else therefore gets that secret stamped onto a request to the
 * base URL the user chose. Ownership is {@link ApiRepository#existsSharedIntegrationWithCredentialKey},
 * decided by {@code created_by}. Platform-owned APIs ({@link PlatformOwnership}) are not checked:
 * they own their keys.
 *
 * <p>Outcomes for a foreign key:
 * <ul>
 *   <li><b>Host-bound</b>: the key is owned by a shipped integration that targets concrete hosts.
 *       The call may use the credential only over https to exactly those hosts (or under that
 *       integration's declared {@code allowedUrlHostSuffixes}), enforced on the FINAL URL of every
 *       request ({@link CredentialHostBinding}). Hosts a shipped integration only reaches through a
 *       templated base URL ({@code {sub}.zendesk.com}, {@code {shop}.myshopify.com}) are tenant
 *       controlled and never qualify, nor does any suffix covering them.</li>
 *   <li><b>Without platform fallback</b>: only the API-level key is foreign and no tool requires a
 *       credential; the call runs without the fallback.</li>
 *   <li><b>Refused</b> ({@code credential_key_conflict}) otherwise, and
 *       ({@code credential_key_unverified}) when ownership cannot be established.</li>
 * </ul>
 */
@Component
@Slf4j
public class CustomApiCredentialGuard {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}]*\\}");

    private final ApiRepository apiRepository;
    private final ApiToolRepository apiToolRepository;
    private final ObjectMapper objectMapper;
    private final PlatformOwnership platformOwnership;

    public CustomApiCredentialGuard(ApiRepository apiRepository, ApiToolRepository apiToolRepository,
                                    ObjectMapper objectMapper, PlatformOwnership platformOwnership) {
        this.apiRepository = apiRepository;
        this.apiToolRepository = apiToolRepository;
        this.objectMapper = objectMapper;
        this.platformOwnership = platformOwnership;
    }

    /** The outcome; exactly one of refusal / binding / withoutPlatformFallback may be set. */
    public record Decision(Map<String, Object> refusal,
                           CredentialHostBinding.Binding binding,
                           boolean withoutPlatformFallback) {
        static Decision proceed() {
            return new Decision(null, null, false);
        }
    }

    public Decision decide(ApiEntity api, String toolKey, String toolName) {
        if (api == null || platformOwnership.isPlatformOwned(api)) {
            return Decision.proceed();
        }
        String owner = api.getCreatedBy();
        String apiKey = api.getPlatformCredentialName();
        try {
            boolean toolForeign = isForeign(toolKey, owner);
            boolean apiForeign = isForeign(apiKey, owner);
            if (!toolForeign && !apiForeign) {
                return Decision.proceed();
            }
            String key = toolForeign ? toolKey : apiKey;
            CredentialHostBinding.Binding binding = hostBinding(key, owner);
            if (binding != null && baseUrlCompatible(api.getBaseUrl(), binding)) {
                log.info("[CustomApiCredentialGuard] API {} uses '{}' host-bound to {} {}",
                        api.getId(), key, binding.exactHosts(), binding.hostSuffixes());
                return new Decision(null, binding, false);
            }
            if (!toolForeign && (toolKey == null || toolKey.isBlank())) {
                log.warn("[CustomApiCredentialGuard] API {} carries the foreign key '{}': running without the platform fallback",
                        api.getId(), apiKey);
                return new Decision(null, null, true);
            }
            return new Decision(conflict(key, toolName), null, false);
        } catch (RuntimeException e) {
            log.warn("[CustomApiCredentialGuard] Could not verify credential key ownership for API {}: {}",
                    api.getId(), e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "credential_key_unverified");
            error.put("tool_name", toolName);
            error.put("message", "Could not verify that this API owns its credential key, so no credential "
                    + "was sent. This is a temporary condition: retry the call.");
            return new Decision(error, null, false);
        }
    }

    public boolean isPlatformOwner(String userId) {
        return platformOwnership.isPlatformOwner(userId);
    }

    /**
     * Registration-time form of the same predicate: true when {@code key} belongs to anybody other
     * than {@code owner}. A lookup failure propagates, so callers fail closed.
     */
    public boolean isKeyTakenByAnother(String key, String owner) {
        return isForeign(key, owner);
    }

    private boolean isForeign(String key, String owner) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return owner == null || owner.isBlank()
                ? apiRepository.existsShippedIntegrationWithCredentialKey(key)
                : apiRepository.existsSharedIntegrationWithCredentialKey(key, owner);
    }

    /**
     * The hosts the SHIPPED integration owning {@code key} targets, or null when no platform-owned
     * integration owns it (a native template, another tenant's key) or it targets no concrete host.
     */
    CredentialHostBinding.Binding hostBinding(String key, String owner) {
        List<UUID> ids = apiRepository.findIdsHoldingCredentialKeyNotCreatedBy(key, owner == null ? "" : owner);
        Set<String> exact = new LinkedHashSet<>();
        Set<String> suffixes = new LinkedHashSet<>();
        Set<String> tenantControlled = new LinkedHashSet<>();
        boolean anyShipped = false;
        for (ApiEntity shipped : apiRepository.findAllById(ids)) {
            if (!platformOwnership.isPlatformOwned(shipped)) {
                // Another tenant's API holding the key: never a reason to share it.
                return null;
            }
            anyShipped = true;
            collectBaseHost(shipped.getBaseUrl(), exact, tenantControlled);
            for (ApiToolEntity tool : apiToolRepository.findByApiId(shipped.getId())) {
                collectDeclaredSuffixes(tool.getExecutionSpec(), suffixes);
            }
        }
        if (!anyShipped) {
            return null;
        }
        suffixes.removeIf(s -> tenantControlled.stream().anyMatch(t -> t.equals(s) || t.endsWith("." + s)));
        exact.removeIf(h -> tenantControlled.stream().anyMatch(t -> h.equals(t) || h.endsWith("." + t)));
        if (exact.isEmpty() && suffixes.isEmpty()) {
            return null;
        }
        return new CredentialHostBinding.Binding(key, Set.copyOf(exact), Set.copyOf(suffixes));
    }

    private static void collectBaseHost(String baseUrl, Set<String> exact, Set<String> tenantControlled) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        String trimmed = baseUrl.trim();
        if (PLACEHOLDER.matcher(authorityOf(trimmed)).find()) {
            // {sub}.zendesk.com: the part after the templated label is shared by every tenant.
            String hostTemplate = authorityOf(trimmed).replaceAll(":\\d+$", "").toLowerCase(Locale.ROOT);
            int lastPlaceholder = hostTemplate.lastIndexOf('}');
            String rest = lastPlaceholder >= 0 ? hostTemplate.substring(lastPlaceholder + 1) : hostTemplate;
            while (rest.startsWith(".") || rest.startsWith("-")) {
                rest = rest.substring(1);
            }
            if (!rest.isBlank()) {
                tenantControlled.add(rest);
            }
            return;
        }
        try {
            URI uri = URI.create(trimmed);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
                exact.add(uri.getHost().toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException ignored) {
            // unparseable base url: contributes nothing
        }
    }

    /** {@code scheme://authority} part of a possibly templated URL (without parsing it as a URI). */
    private static String authorityOf(String url) {
        int schemeEnd = url.indexOf("://");
        String rest = schemeEnd >= 0 ? url.substring(schemeEnd + 3) : url;
        int end = rest.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int i = rest.indexOf(c);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        return rest.substring(0, end);
    }

    private void collectDeclaredSuffixes(String executionSpec, Set<String> suffixes) {
        if (executionSpec == null || executionSpec.isBlank()) {
            return;
        }
        try {
            JsonNode list = objectMapper.readTree(executionSpec).path("request").path("allowedUrlHostSuffixes");
            if (list.isArray()) {
                for (JsonNode s : list) {
                    String v = s.asText("").trim().toLowerCase(Locale.ROOT);
                    while (v.startsWith(".")) {
                        v = v.substring(1);
                    }
                    if (!v.isEmpty() && v.contains(".")) {
                        suffixes.add(v);
                    }
                }
            }
        } catch (Exception ignored) {
            // malformed spec: contributes nothing
        }
    }

    /**
     * A concrete custom base URL must itself satisfy the binding, so the refusal happens before any
     * work. A templated or dynamic one is judged request by request instead (validatedTarget).
     */
    private static boolean baseUrlCompatible(String baseUrl, CredentialHostBinding.Binding binding) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        if (PLACEHOLDER.matcher(baseUrl).find()) {
            return true;
        }
        try {
            return binding.permits(URI.create(baseUrl.trim()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Map<String, Object> conflict(String key, String toolName) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("error", "credential_key_conflict");
        error.put("credential_name", key);
        error.put("tool_name", toolName);
        error.put("message", "This API is keyed to the credential '" + key + "', which belongs to another "
                + "integration on this installation, so it cannot use that credential with this base URL. "
                + "Update the API with catalog(action='update_api') under a distinct apiName (or iconSlug), "
                + "connect a credential for the new name, and call the tool again.");
        return error;
    }
}
