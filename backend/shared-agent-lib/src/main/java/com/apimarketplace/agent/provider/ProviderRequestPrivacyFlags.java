package com.apimarketplace.agent.provider;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Vendor-documented, request-level "do not keep this" flags, added to EVERY outgoing chat request
 * of the vendors that have one (CASA LC-004). In code rather than in a contract annex, so an
 * assessor can verify it from the source.
 *
 * <ul>
 *   <li>{@code openai}: {@code "store": false}. The completion is not persisted in OpenAI's
 *       stored-completions feature. Sent only when the provider still points at
 *       {@code api.openai.com}: the endpoint is operator-overridable, and an Azure OpenAI
 *       deployment or a proxy can reject a body field it does not know, which would fail every
 *       call. A request that no longer reaches OpenAI is not governed by OpenAI's {@code store}
 *       anyway.</li>
 *   <li>{@code openrouter}: {@code "provider": {"data_collection": "deny"}}. OpenRouter routes
 *       only to upstream providers that do not store or train on prompts. OpenRouter is not
 *       allowed to receive restricted data at all (see RestrictedDataPolicy); this protects the
 *       ordinary traffic it does carry.</li>
 * </ul>
 * Every other OpenAI-shaped provider gets nothing: none of them documents such a parameter, and
 * sending an unknown field to a strict server can fail the whole request. Anthropic, Google
 * (Gemini API) and Mistral have no per-request training or retention parameter; for them the
 * protection is the commercial API terms named in the privacy policy.
 */
public final class ProviderRequestPrivacyFlags {

    static final String OPENAI = "openai";
    static final String OPENROUTER = "openrouter";
    private static final String OPENAI_VENDOR_HOST = "api.openai.com";

    private ProviderRequestPrivacyFlags() {
    }

    /**
     * Adds the flags documented by {@code providerName}'s vendor to {@code body}, in place.
     *
     * @param apiUrl the endpoint the request goes to; null or blank means the vendor default
     */
    public static void apply(String providerName, String apiUrl, Map<String, Object> body) {
        if (body == null || providerName == null) {
            return;
        }
        String name = providerName.trim().toLowerCase(Locale.ROOT);
        if (OPENAI.equals(name)) {
            if (isOpenAiVendorEndpoint(apiUrl)) {
                body.put("store", false);
            }
        } else if (OPENROUTER.equals(name)) {
            body.put("provider", Map.of("data_collection", "deny"));
        }
    }

    /**
     * True when {@code apiUrl} still targets OpenAI. Absent means the vendor default (the bean's
     * default IS the vendor URL), so the default never silently drops the flag; an unparseable
     * URL is not evidence that the target is OpenAI.
     */
    static boolean isOpenAiVendorEndpoint(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(apiUrl.trim()).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.equals(OPENAI_VENDOR_HOST) || host.endsWith("." + OPENAI_VENDOR_HOST);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
