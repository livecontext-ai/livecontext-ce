package com.apimarketplace.orchestrator.services;

import com.apimarketplace.agent.cloud.CeWebSearchRelayGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Resolves the availability of the deployment's OPTIONAL heavy components so the
 * builder UI can tell the user "this toggle will do nothing on this install" instead
 * of silently no-oping.
 *
 * <p>Two optional components exist today (both off by default on CE self-hosted
 * installs, opt-in via the bundled docker env files):
 * <ul>
 *   <li><b>screenshotRenderer</b> - the Playwright/Chromium sidecar behind the
 *       interface node's {@code generateScreenshot} / {@code generatePdf} toggles.
 *       Available iff {@code services.screenshot-renderer-url} is configured
 *       (the {@code renderer} docker profile / helm {@code screenshotRenderer.enabled}
 *       set it). Deployment-global, not per-tenant.</li>
 *   <li><b>browserAgent / webSearch</b> - the browser-use + SearXNG stack behind
 *       {@code web_search} and the Browser Agent node. Availability is per-tenant:
 *       local engine enabled ({@code websearch.enabled=true}) OR the tenant is
 *       cloud-linked with the CLOUD LLM source so the calls relay to the linked
 *       cloud. Delegated to {@link CeWebSearchRelayGate} - the same gate that
 *       decides whether the {@code web_search} tool is exposed to agents, so the
 *       UI notice can never contradict actual tool exposure.</li>
 * </ul>
 */
@Service
public class OptionalFeatureCapabilityService {

    /**
     * Availability snapshot for one tenant. {@code browserAgent} and {@code webSearch}
     * are currently the same signal (one docker profile ships both) but stay separate
     * fields so the contract survives the day they diverge. The same applies to
     * {@code screenshotRenderer} and {@code mediaRenderer}: both live on the renderer
     * sidecar today (one URL), but the media endpoint could move to its own component.
     */
    public record FeatureCapabilities(boolean screenshotRenderer, boolean browserAgent, boolean webSearch,
                                      boolean mediaRenderer) {}

    private final String rendererBaseUrl;
    private final CeWebSearchRelayGate webSearchGate;

    public OptionalFeatureCapabilityService(
        @Value("${services.screenshot-renderer-url:}") String rendererBaseUrl,
        @Autowired CeWebSearchRelayGate webSearchGate
    ) {
        this.rendererBaseUrl = rendererBaseUrl;
        this.webSearchGate = webSearchGate;
    }

    public FeatureCapabilities resolve(String tenantId) {
        // Resolve browsing ONCE (it can cost an HTTP roundtrip) and reuse for both fields.
        boolean browsing = isBrowsingAvailable(tenantId);
        boolean renderer = isScreenshotRendererAvailable();
        return new FeatureCapabilities(renderer, browsing, browsing, renderer);
    }

    /** Deployment-global, pure property check - never a remote call. */
    public boolean isScreenshotRendererAvailable() {
        return rendererBaseUrl != null && !rendererBaseUrl.isBlank();
    }

    /**
     * Per-tenant: true when the local browser stack runs, or when this tenant's
     * cloud link relays browsing/search to the cloud. Fail-closed inside the gate.
     * Can cost an HTTP roundtrip on relay-wired CE installs - callers that only
     * need the renderer verdict should use {@link #isScreenshotRendererAvailable()}.
     */
    public boolean isBrowsingAvailable(String tenantId) {
        return webSearchGate.isWebSearchAvailable(tenantId);
    }
}
