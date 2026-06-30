package com.apimarketplace.agent.cloud;

import com.apimarketplace.agent.domain.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runtime availability gate for the {@code web_search} core tool.
 *
 * <p>Three deployment shapes:
 * <ul>
 *   <li><b>Cloud</b> - {@code websearch.enabled=true} (default): web search is local,
 *       always available; this gate is a constant-true no-op.</li>
 *   <li><b>CE, cloud-linked with CLOUD LLM source</b> - {@code websearch.enabled=false}
 *       but a {@link CloudLlmRuntimeAccess} bean is wired ({@code marketplace.mode=remote}):
 *       web search relays to the cloud install link, so the tool is exposable; per-tenant
 *       availability is resolved at runtime via {@link CloudLlmRuntimeAccess#isCloudSelected}
 *       (the link can appear/disappear at runtime - same posture as the CE→cloud LLM relay
 *       in {@code RuntimeLlmProviderResolver}).</li>
 *   <li><b>CE unlinked / BYOK</b> - the tenant did not select the CLOUD source: web search
 *       stays absent, exactly like before the relay existed.</li>
 * </ul>
 *
 * <p>Fail-closed: if the link state cannot be resolved (publication-service unreachable),
 * the tool is treated as unavailable rather than exposing a tool that would fail.
 */
@Slf4j
@Component
public class CeWebSearchRelayGate {

    public static final String WEB_SEARCH_TOOL_NAME = "web_search";

    private final boolean localWebSearchEnabled;
    private final CloudLlmRuntimeAccess runtimeAccess;

    public CeWebSearchRelayGate(
            @Value("${websearch.enabled:true}") boolean localWebSearchEnabled,
            @Autowired(required = false) CloudLlmRuntimeAccess runtimeAccess) {
        this.localWebSearchEnabled = localWebSearchEnabled;
        this.runtimeAccess = runtimeAccess;
    }

    /** True when web search is disabled locally but the CE→cloud relay wiring exists. */
    public boolean isRelayWired() {
        return !localWebSearchEnabled && runtimeAccess != null;
    }

    /**
     * Tenant-less check used by tool-definition caches: should {@code web_search}
     * be cached/registered at all? True when the local engine is enabled OR the
     * relay wiring exists (some tenant may be cloud-linked; per-tenant exposure
     * is decided later by {@link #isWebSearchAvailable}).
     */
    public boolean isWebSearchExposable() {
        return localWebSearchEnabled || isRelayWired();
    }

    /**
     * Per-tenant runtime check: can THIS tenant use web search right now?
     * Local engine enabled → always. Relay-wired → only when the tenant's
     * effective LLM source is CLOUD. Fail-closed on resolution errors.
     */
    public boolean isWebSearchAvailable(String tenantId) {
        if (localWebSearchEnabled) {
            return true;
        }
        if (runtimeAccess == null || tenantId == null || tenantId.isBlank()) {
            return false;
        }
        try {
            return runtimeAccess.isCloudSelected(tenantId);
        } catch (RuntimeException e) {
            log.warn("[CE_WEBSEARCH_GATE] Could not resolve cloud-link state for tenant {} - "
                    + "web_search stays hidden (fail-closed): {}", tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * Remove the {@code web_search} tool definition from an agent's tool list when
     * it is not available for this tenant. No-op (returns the same list) when web
     * search is available or the list does not contain it.
     */
    public List<ToolDefinition> filterExposedTools(List<ToolDefinition> tools, String tenantId) {
        if (tools == null || tools.isEmpty()) {
            return tools;
        }
        boolean containsWebSearch = tools.stream()
                .anyMatch(t -> t != null && WEB_SEARCH_TOOL_NAME.equals(t.name()));
        if (!containsWebSearch || isWebSearchAvailable(tenantId)) {
            return tools;
        }
        return tools.stream()
                .filter(t -> t == null || !WEB_SEARCH_TOOL_NAME.equals(t.name()))
                .toList();
    }
}
