package com.apimarketplace.publication.utils;

import java.util.Locale;
import java.util.Set;

/**
 * Detects credential-shaped key names. Single source of truth shared by
 * the three scrub paths in publication-service:
 * <ul>
 *   <li>{@code WorkflowPublicationService.stripSensitiveCredentials} - plan
 *       snapshot at publish time;</li>
 *   <li>{@code AgentPublicationService.scrubAgentSnapshotForCredentials} -
 *       agent snapshot at publish time;</li>
 *   <li>{@code SnapshotCloneService.stripSensitiveCredentials} - defense-in-
 *       depth at acquire time.</li>
 * </ul>
 *
 * <p>The orchestrator-side {@code ShowcaseSnapshotBuilder.looksSensitive}
 * keeps an inlined copy because the orchestrator-service module has no
 * dependency on publication-service. Both copies must stay in sync - see
 * the JavaDoc on that method for the cross-reference.
 *
 * <p>Matching strategy: split the key on case boundaries ({@code maxTokens}
 * → {@code [max,tokens]}) and non-alphanumeric separators ({@code X-Api-Key}
 * → {@code [x,api,key]}, {@code client_secret} → {@code [client,secret]}),
 * then check each token against the allow-list. A second pass checks the
 * fully-normalized form (lowercase, alphanumerics only) against compound
 * phrases like {@code apikey} / {@code accesstoken} / {@code clientsecret}
 * so it still flags {@code googleIdToken} → {@code googleidtoken}.
 *
 * <p>Bare {@code token}, {@code secret}, {@code session}, {@code cookie}
 * are intentionally excluded - they collide with legitimate keys
 * ({@code maxTokens}, {@code totalTokens}, {@code sessionId},
 * {@code cookieDomain}). Only well-bounded compound forms qualify.
 */
public final class CredentialKeyDetector {

    private CredentialKeyDetector() {}

    private static final Set<String> CREDENTIAL_KEY_TOKENS = Set.of(
            "password", "passphrase", "apikey", "authorization",
            "bearer", "credential", "credentials", "credentialid",
            "privatekey", "clientsecret", "appsecret",
            "accesstoken", "refreshtoken", "idtoken", "authtoken", "sessiontoken",
            "xapikey");

    private static final Set<String> CREDENTIAL_PHRASES = Set.of(
            "apikey", "privatekey", "clientsecret", "accesstoken",
            "refreshtoken", "authtoken", "idtoken");

    public static final String REDACTED = "[redacted]";

    /**
     * @return true when the given key looks like a credential field name.
     */
    public static boolean looksSensitive(String key) {
        if (key == null || key.isEmpty()) return false;
        String[] parts = key.split("(?<=[a-z])(?=[A-Z])|[^A-Za-z0-9]+");
        for (String part : parts) {
            if (CREDENTIAL_KEY_TOKENS.contains(part.toLowerCase(Locale.ROOT))) return true;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String phrase : CREDENTIAL_PHRASES) {
            if (normalized.contains(phrase)) return true;
        }
        return false;
    }
}
