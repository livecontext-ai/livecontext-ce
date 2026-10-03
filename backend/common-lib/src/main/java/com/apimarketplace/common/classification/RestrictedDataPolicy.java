package com.apimarketplace.common.classification;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The ONE place that decides what counts as Google restricted-scope data and who may receive it.
 *
 * <p>Every control that treats Gmail or Drive content differently from a public API response
 * (payload retention, hard deletion, full-text indexing, the LLM processor allow-list) reads its
 * answer from here, so the classification cannot drift between services.
 *
 * <h2>Which integrations are restricted</h2>
 * An integration is restricted when its catalog template requests (in {@code scopes},
 * {@code byokOnlyScopes} or a tool's {@code requiredScopes}) any scope of
 * {@link #RESTRICTED_GOOGLE_SCOPES}, the scopes Google lists as RESTRICTED in its OAuth scope
 * classification. The integration identifiers below are that derivation written out, because
 * the runtime identifies an integration by the {@code iconSlug} (one icon per API is a catalog
 * invariant) or the normalized API name, never by its scope list. The derivation is enforced,
 * not trusted: {@code RestrictedDataPolicyCatalogConsistencyTest} in catalog-service-import reads
 * every {@code scripts/api-migrations/*.json} and fails when a template requesting a restricted
 * scope is missing from {@link #RESTRICTED_INTEGRATIONS}, so adding such a scope to a catalog
 * JSON cannot silently leave its data unclassified; it also fails when an entry here no longer
 * names a template requesting one, so dropping the scope cannot leave the integration
 * over-classified.
 *
 * <h2>Who may receive it</h2>
 * {@link #RESTRICTED_DATA_LLM_PROVIDERS}: direct API access to Anthropic and OpenAI, whose API
 * terms exclude API content from model training for every account, whatever its tier or who holds
 * the key (the platform or the user, BYOK), plus the local {@code mock} provider, which transmits
 * nothing. Everything else is refused, deliberately including:
 * <ul>
 *   <li>{@code google} (Gemini API) and {@code mistral} (La Plateforme): their training exclusion
 *       depends on the account's TIER or an account-level opt-out (Gemini's free tier and
 *       Mistral's free plan may use content for training). Neither can be read from a request,
 *       and a user-supplied key's tier is unknowable, so the platform cannot guarantee it. Re-add
 *       one only together with a verified, enforced guarantee (e.g. platform key only, paid tier
 *       confirmed) and the privacy page;</li>
 *   <li>{@code openrouter}: an aggregator that forwards the prompt to an unnamed fourth party;</li>
 *   <li>{@code zai}, {@code qwen}, {@code moonshot}, {@code minimax}, {@code deepseek},
 *       {@code perplexity}, {@code cohere}, {@code xai} and any provider added later: not in
 *       the published sub-processor list;</li>
 *   <li>every CLI bridge ({@code claude-code}, {@code codex}, {@code gemini-cli},
 *       {@code mistral-vibe}, ...): they run on subscription accounts whose consumer terms can
 *       permit product and model improvement, and the platform cannot assert otherwise.</li>
 * </ul>
 * Deny-by-default: a provider name nobody reviewed is refused. Adding one here is a policy
 * change and must land together with the privacy page ({@code frontend/app/legal/privacy}).
 *
 * <p>Introduced for LC-066 and LC-004 (CASA remediation).
 */
public final class RestrictedDataPolicy {

    private RestrictedDataPolicy() {
    }

    /** Google OAuth scopes classified RESTRICTED (Gmail and Drive families). */
    public static final Set<String> RESTRICTED_GOOGLE_SCOPES = Set.of(
            "https://mail.google.com/",
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose",
            "https://www.googleapis.com/auth/gmail.insert",
            "https://www.googleapis.com/auth/gmail.modify",
            "https://www.googleapis.com/auth/gmail.metadata",
            "https://www.googleapis.com/auth/gmail.settings.basic",
            "https://www.googleapis.com/auth/gmail.settings.sharing",
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/drive.metadata",
            "https://www.googleapis.com/auth/drive.metadata.readonly",
            "https://www.googleapis.com/auth/drive.activity",
            "https://www.googleapis.com/auth/drive.activity.readonly",
            "https://www.googleapis.com/auth/drive.photos.readonly",
            "https://www.googleapis.com/auth/drive.scripts");

    /**
     * Normalized identifiers (iconSlug and API name) of the catalog integrations that request a
     * restricted scope. See the class javadoc for how this list is kept honest. Google Forms left
     * the list on 2026-10-01: its only restricted scope ({@code drive}) was required by no tool and
     * was dropped from {@code google_forms.json}, so it requests sensitive Forms scopes only.
     */
    public static final Set<String> RESTRICTED_INTEGRATIONS = Set.of(
            "gmail",
            "googledrive", "google_drive");

    /** LLM providers allowed to receive restricted data. See the class javadoc. */
    public static final Set<String> RESTRICTED_DATA_LLM_PROVIDERS =
            Set.of("anthropic", "openai", "mock");

    /**
     * Code token that starts every refusal message. The UI matches it to show a translated
     * explanation; the agent reads the English sentence that follows.
     */
    public static final String REFUSAL_CODE = "RESTRICTED_DATA_PROVIDER_NOT_ALLOWED";

    /**
     * Key of the sensitivity tag in an execution's credentials map and in tool-result metadata
     * (value {@code NORMAL} / {@code RESTRICTED}). Alias of {@link DataSensitivity#CREDENTIAL_KEY}
     * so callers need only this class.
     */
    public static final String SENSITIVITY_KEY = DataSensitivity.CREDENTIAL_KEY;

    private static final String GOOGLE_SCOPE_PREFIX = "https://www.googleapis.com/auth/";

    /** Default retention of a restricted payload, in days. Each sweeper can override it. */
    public static final int DEFAULT_RETENTION_DAYS = 30;

    /**
     * Tool-result metadata keys that name the producing integration, most specific first. A
     * catalog execution stamps {@code iconSlug}; workflow step metadata carries {@code apiName}.
     */
    static final List<String> INTEGRATION_METADATA_KEYS =
            List.of("iconSlug", "apiSlug", "apiName", "serviceType", "integration");

    /** True when data produced by this integration is restricted-scope data. */
    public static boolean isRestrictedIntegration(String integration) {
        String normalized = normalize(integration);
        return normalized != null && RESTRICTED_INTEGRATIONS.contains(normalized);
    }

    /**
     * True when this OAuth scope is a Google restricted scope. Accepts the full URL, the short
     * form ({@code gmail.readonly}, {@code drive}, {@code mail.google.com}), any case, and a
     * trailing slash.
     */
    public static boolean isRestrictedScope(String scope) {
        String normalized = normalizeScope(scope);
        return normalized != null && RESTRICTED_GOOGLE_SCOPES.contains(normalized);
    }

    /**
     * True when any of the granted scopes is restricted. Each entry may itself be a space- or
     * comma-separated scope string, as OAuth token responses store it. This is the tenant-level
     * question "does this account hold restricted Google access".
     */
    public static boolean grantedScopesIncludeRestricted(java.util.Collection<String> grantedScopes) {
        if (grantedScopes == null) {
            return false;
        }
        for (String entry : grantedScopes) {
            if (entry == null) {
                continue;
            }
            for (String token : entry.split("[\\s,]+")) {
                if (isRestrictedScope(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Canonical form of a Google scope: the full {@code https://www.googleapis.com/auth/...} URL
     * (or {@code https://mail.google.com/}), lower-case. Null for a blank input.
     */
    public static String normalizeScope(String scope) {
        if (scope == null) {
            return null;
        }
        String s = scope.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.equals("https://mail.google.com") || s.equals("mail.google.com")) {
            return "https://mail.google.com/";
        }
        if (s.startsWith("http://")) {
            s = "https://" + s.substring("http://".length());
        }
        if (s.startsWith(GOOGLE_SCOPE_PREFIX)) {
            return s;
        }
        if (s.contains("://")) {
            return s;
        }
        return GOOGLE_SCOPE_PREFIX + s;
    }

    /** Sensitivity of data produced by {@code integration}. */
    public static DataSensitivity forIntegration(String integration) {
        return isRestrictedIntegration(integration) ? DataSensitivity.RESTRICTED : DataSensitivity.NORMAL;
    }

    /**
     * Sensitivity of a catalog tool reference such as {@code gmail/list_messages}: the part
     * before the first {@code /} names the API.
     */
    public static DataSensitivity forToolReference(String toolReference) {
        if (toolReference == null || toolReference.isBlank()) {
            return DataSensitivity.NORMAL;
        }
        String candidate = toolReference.trim();
        int slash = candidate.indexOf('/');
        return forIntegration(slash > 0 ? candidate.substring(0, slash) : candidate);
    }

    /**
     * Metadata flag of a catalog result whose call never reached the provider for want of a
     * credential: the "connect Gmail" card, or no key in either pool. The catalog alone sets it,
     * on its own envelope metadata, never from a provider response.
     */
    public static final String CREDENTIAL_NEEDED_KEY = "credentialNeeded";

    /**
     * Sensitivity of a tool result from its metadata. Any key naming a restricted integration
     * wins: a classification must fail towards restricted, never away from it. The nested
     * {@code metadata} map of a flattened workflow step output is read as well.
     *
     * <p>One exception, where nothing was read: a result flagged {@link #CREDENTIAL_NEEDED_KEY}
     * names Gmail only so a card can be drawn, and classifying it restricted marked a whole
     * conversation for a mailbox it never opened. The flag exempts only the card's own integration
     * keys: an explicit restricted tag, and a nested {@code metadata} map naming Gmail, still win.
     */
    public static DataSensitivity fromToolMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return DataSensitivity.NORMAL;
        }
        if (DataSensitivity.parse(metadata.get(DataSensitivity.CREDENTIAL_KEY)).isRestricted()) {
            return DataSensitivity.RESTRICTED;
        }
        if (!Boolean.TRUE.equals(metadata.get(CREDENTIAL_NEEDED_KEY))) {
            for (String key : INTEGRATION_METADATA_KEYS) {
                Object value = metadata.get(key);
                if (value instanceof String text && isRestrictedIntegration(text)) {
                    return DataSensitivity.RESTRICTED;
                }
            }
        }
        if (metadata.get("metadata") instanceof Map<?, ?> nested && nested != metadata) {
            @SuppressWarnings("unchecked")
            Map<String, ?> typed = (Map<String, ?>) nested;
            return fromToolMetadata(typed);
        }
        return DataSensitivity.NORMAL;
    }

    /**
     * Whether the LLM allow-list is enforced. ON by default, and ON for every install until
     * {@code RestrictedDataPolicyConfig} says otherwise at startup: a class loaded before Spring
     * reads the safe value.
     *
     * <p>The one install where it is OFF by default is the self-hosted edition
     * ({@code auth.mode=embedded}): there the Google OAuth client is the operator's own, the
     * operator is the controller of the data, and refusing their own CLI or model the content of
     * their own mailbox would only break the product for them. The managed cloud, whose shared
     * Google client is the one Limited Use and the CASA assessment bind, always enforces it.
     * Either default can be overridden with {@code data-classification.restricted.enforce-llm-allowlist}.
     */
    private static volatile boolean llmAllowListEnforced = true;

    public static boolean isLlmAllowListEnforced() {
        return llmAllowListEnforced;
    }

    /** Only {@code RestrictedDataPolicyConfig} (startup) and tests call this. Public for tests in other modules. */
    public static void setLlmAllowListEnforced(boolean enforced) {
        llmAllowListEnforced = enforced;
    }

    /**
     * True when {@code providerName} may receive restricted data. Case-insensitive. Always true
     * when the allow-list is not enforced on this install (see {@link #isLlmAllowListEnforced}).
     */
    public static boolean mayReceiveRestricted(String providerName) {
        if (!llmAllowListEnforced) {
            return true;
        }
        return providerName != null
                && RESTRICTED_DATA_LLM_PROVIDERS.contains(providerName.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * True when content of this sensitivity may be sent to {@code providerName}. Normal content
     * may go anywhere.
     */
    public static boolean mayReceive(String providerName, DataSensitivity sensitivity) {
        return sensitivity == null || !sensitivity.isRestricted() || mayReceiveRestricted(providerName);
    }

    /**
     * The sentence shown to the agent or the user when restricted data is refused to a
     * provider. Written for the person who has to act on it.
     */
    public static String refusalMessage(String providerName) {
        return REFUSAL_CODE + ": Data from Gmail or Google Drive cannot be sent to the model provider '"
                + (providerName == null ? "unknown" : providerName)
                + "'. For privacy reasons this data may only be processed by Anthropic or OpenAI "
                + "models used through their direct API. Switch to one of those models and try again.";
    }

    /**
     * The refusal returned by a marketplace {@code publish} action called from a RESTRICTED
     * execution (CASA LC-066). A publication copies the resource (its text, and for a workflow,
     * an application or an interface the rows of the tables it uses) into a listing snapshot
     * that no restricted tag, retention window or model allow-list follows. Unpublishing and
     * reads are never refused.
     *
     * @param resource what the agent tried to publish, as the agent names it ("workflow",
     *                 "application", "interface", "table")
     */
    public static String publishRefusalMessage(String resource) {
        String what = resource == null || resource.isBlank() ? "resource" : resource;
        return REFUSAL_CODE + ": Content from Gmail or Google Drive seen earlier in this conversation cannot be "
                + "written to the marketplace: publishing copies this " + what + " into a listing snapshot "
                + "that is kept apart from this conversation, so the restricted-data controls stop following "
                + "it. In this conversation no " + what + " can be published (unpublishing and reads still "
                + "work). Tell the user: they can publish it from a conversation that has not read Gmail or "
                + "Google Drive.";
    }

    /**
     * Catalog-style normalization: lowercase, every run of non-alphanumeric characters collapsed
     * to {@code _}, edges trimmed. Null for a blank input.
     */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? null : normalized;
    }
}
