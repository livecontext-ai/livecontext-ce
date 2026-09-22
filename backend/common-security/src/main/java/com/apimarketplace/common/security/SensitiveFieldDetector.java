package com.apimarketplace.common.security;

import java.util.Locale;
import java.util.Set;

/**
 * Decides which keys of a credential-shaped map hold a secret and must be encrypted at rest.
 *
 * <p>Until 2026-09-17 this decision was an exact allow-list of 15 names
 * ({@link #LEGACY_EXACT_NAMES}). The catalogue's custom-auth integrations write the template
 * field name as the JSON key, and 42 of those names were not in the list: {@code secret_access_key}
 * (every AWS API), {@code private_key} (GCP service accounts, Box, DocuSign), {@code api_secret},
 * {@code session_token}, {@code sas_token}, {@code consumer_secret}... all landed in
 * {@code auth.credentials.credential_data} in clear, with no error anywhere. The detector below
 * matches on the SHAPE of the name so a field a seed author invents tomorrow is covered on the
 * day it is written.
 *
 * <p>Two properties matter more than recall:
 * <ul>
 *   <li><b>Superset of the legacy list.</b> Every value encrypted under the old rule must still be
 *       recognised as sensitive, or {@code decryptSensitiveFields} stops decrypting it and the
 *       ciphertext reaches the caller as if it were the secret. The legacy names are kept as an
 *       exact match, ahead of every other rule.</li>
 *   <li><b>Never a key the SQL reads.</b> {@code expires_at}, {@code refresh_mode},
 *       {@code refresh_cooldown_until} are compared inside queries
 *       ({@code CredentialRepository} eligibility scans); encrypting them would silently empty
 *       the refresh scheduler. Descriptive suffixes ({@code _at}, {@code _id}, {@code _mode},
 *       {@code _type}, {@code _url}...) and the {@code template} / {@code public} tokens are
 *       negative rules evaluated BEFORE any positive one, and the test pins every key that exists
 *       in production today with its expected verdict.</li>
 * </ul>
 * Over-encrypting a harmless key (say {@code consumer_key}, which is really an API key) costs
 * nothing: the read path decrypts through the same predicate. Under-encrypting a secret is the
 * defect this class exists to close, so ties go to "sensitive".
 */
public final class SensitiveFieldDetector {

    private SensitiveFieldDetector() {}

    /**
     * The exact names the previous implementation encrypted. Kept verbatim so the new rule is
     * provably a superset: every one of them is sensitive whatever the token rules say.
     */
    static final Set<String> LEGACY_EXACT_NAMES = Set.of(
            "access_token", "refresh_token", "client_secret", "oauth_client_secret",
            "api_key", "apiKey", "password", "secret", "secretKey", "bearer_token",
            "token", "connectionString", "basicPassword", "authHeaderValue", "jwtSecretKey"
    );

    /**
     * A key ending in one of these is a descriptor of a secret, never the secret itself:
     * {@code refresh_token_issued_at}, {@code oauth_client_id}, {@code token_type},
     * {@code client_secret_masked}, {@code api_key_hint}, {@code expires_at}.
     */
    private static final Set<String> DESCRIPTOR_LAST_TOKENS = Set.of(
            "id", "at", "type", "mode", "masked", "url", "uri", "name", "hint", "hash",
            "count", "length", "prefix", "header", "location", "field", "version", "enabled",
            "until", "issued", "expires", "expiry", "ttl", "scope", "scopes", "label",
            "format", "source", "status", "kind", "path", "region", "host", "email", "issuer",
            "algorithm", "alg", "provider", "hostname", "domain", "endpoint"
    );

    /**
     * A key carrying one of these tokens anywhere is never a secret: a public key, a template
     * identifier ({@code credential_template_key}), a fingerprint of a key.
     */
    private static final Set<String> NEVER_SECRET_TOKENS = Set.of(
            "public", "template", "fingerprint", "thumbprint"
    );

    /** A key carrying one of these tokens anywhere IS a secret. */
    private static final Set<String> SECRET_TOKENS = Set.of(
            "secret", "secrets", "password", "passwd", "pwd", "pass", "passphrase",
            "token", "apikey", "privatekey", "credential", "credentials", "authorization",
            "bearer", "key", "jwt", "cert", "certificate", "pem", "signature", "dsn"
    );

    /**
     * A URL/URI-shaped key is a descriptor ({@code grafana_url}, {@code token_url},
     * {@code endpoint_url}) UNLESS it names one of these: a {@code database_url}, a
     * {@code connection_string}, a Slack/Discord {@code webhook_url} or an AMQP/Redis URI
     * carries the whole credential inside the value.
     */
    private static final Set<String> CAPABILITY_BEARING_URL_TOKENS = Set.of(
            "webhook", "webhooks", "hook", "hooks", "database", "db", "connection", "dsn",
            "redis", "mongo", "mongodb", "postgres", "postgresql", "mysql", "amqp", "broker",
            "smtp", "bootstrap"
    );

    /**
     * Compound forms found inside a single camel-case or squashed token
     * ({@code teamsecret}, {@code sessionToken}, {@code googleIdToken}).
     */
    private static final Set<String> SECRET_PHRASES = Set.of(
            "secret", "password", "passphrase", "apikey", "privatekey", "accesstoken",
            "refreshtoken", "authtoken", "idtoken", "sessiontoken", "bearertoken"
    );

    /**
     * @param key a JSON key of a credential / datasource-config map
     * @return true when the value under that key must be encrypted at rest
     */
    public static boolean isSensitive(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (LEGACY_EXACT_NAMES.contains(key)) {
            return true;
        }
        String[] rawParts = key.split("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+");
        java.util.List<String> parts = new java.util.ArrayList<>(rawParts.length);
        for (String p : rawParts) {
            if (!p.isEmpty()) {
                parts.add(p.toLowerCase(Locale.ROOT));
            }
        }
        if (parts.isEmpty()) {
            return false;
        }
        for (String p : parts) {
            if (NEVER_SECRET_TOKENS.contains(p)) {
                return false;
            }
        }
        String last = parts.get(parts.size() - 1);
        if (parts.size() > 1 && ("url".equals(last) || "uri".equals(last) || "string".equals(last))) {
            // database_url, webhook_url, connection_string: the value IS the credential.
            for (String p : parts) {
                if (CAPABILITY_BEARING_URL_TOKENS.contains(p)) {
                    return true;
                }
            }
        }
        // A descriptor suffix wins over any secret token before it: token_type, key_id,
        // client_secret_masked, refresh_token_issued_at.
        if (parts.size() > 1 && DESCRIPTOR_LAST_TOKENS.contains(last)) {
            return false;
        }
        for (String p : parts) {
            if (SECRET_TOKENS.contains(p)) {
                return true;
            }
        }
        String squashed = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String phrase : SECRET_PHRASES) {
            if (squashed.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
