package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.util.CredentialTypeNormalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Post-import service that upserts credential schemas into catalog.credentials
 * and links tools via catalog.tool_credentials.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogSeedCredentialService {

    private final JdbcTemplate jdbcTemplate;
    private final ApiToolRepository apiToolRepository;

    /** Serializer for the small credential JSON blobs. Stateless, so a shared instance is safe. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Declared placement of an API's credential, mirroring the seed schema's
     * {@code auth[].apiKeyConfig} block (scripts/api-migrations/SCHEMA.md). Every field is
     * optional, and a {@code null} config means "use the default placement for this auth type",
     * which is exactly what every caller did before a custom API could declare one.
     *
     * @param location       {@code header} (default) or {@code query}
     * @param headerName     header carrying the credential when {@code location=header}
     * @param queryParamName query parameter carrying it when {@code location=query}
     * @param keyName        credential field holding the secret (defaults per auth type)
     * @param prefix         value prefix such as {@code "Bearer "}. {@code null} means NOT
     *                       DECLARED, and the engine then defaults to {@code "Bearer "} on the
     *                       Authorization header; {@code ""} means declared-empty, i.e. send the
     *                       credential raw, which several providers require.
     */
    public record ApiKeyConfig(String location, String headerName, String queryParamName,
                               String keyName, String prefix) {

        /** True when nothing usable was declared, so the caller can pass null instead. */
        public boolean isBlank() {
            return blank(location) && blank(headerName) && blank(queryParamName)
                    && blank(keyName) && prefix == null;
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    /**
     * Creates or updates a credential schema and links all tools of the given API to it.
     */
    public void linkCredentials(UUID apiId, SeedManifest.SeedSpec spec) {
        if (spec.getCredentialName() == null || spec.getCredentialName().isBlank()) {
            log.debug("No credential name for seed {}, skipping credential linking", spec.getId());
            return;
        }
        if (spec.getAuthType() == null || spec.getAuthType().isBlank()) {
            log.debug("No auth type for seed {}, skipping credential linking", spec.getId());
            return;
        }

        // authIn / authHeaderName have been in the manifest all along and were read by nobody, so
        // the seed's declared placement was discarded and every seeded credential went out as an
        // X-API-Key header. The one shipped seed says authIn "query", authHeaderName "appid" -
        // which is exactly how OpenWeatherMap takes its key, and exactly what it was NOT getting.
        linkCredentials(apiId, spec.getCredentialName(), spec.getAuthType(), spec.getIconSlug(), null,
                apiKeyConfigOf(spec), null);
    }

    /** The placement a seed spec declares, or {@code null} when it declares none. */
    private static ApiKeyConfig apiKeyConfigOf(SeedManifest.SeedSpec spec) {
        String location = spec.getAuthIn();
        String name = spec.getAuthHeaderName();
        boolean query = location != null && "query".equalsIgnoreCase(location.trim());
        ApiKeyConfig config = new ApiKeyConfig(
                location,
                query ? null : name,
                query ? name : null,
                null,
                null);
        return config.isBlank() ? null : config;
    }

    /**
     * Creates or updates a credential schema and links all tools of the given API to it.
     * Direct-parameter overload used by custom API registration.
     *
     * @param apiId          the API whose tools should be linked
     * @param credentialName unique credential key (e.g. "stripepayments")
     * @param authType       authentication type (bearer, apikey, oauth2, etc.)
     * @param iconSlug       brand icon identifier (nullable)
     */
    public void linkCredentials(UUID apiId, String credentialName, String authType, String iconSlug) {
        linkCredentials(apiId, credentialName, authType, iconSlug, null);
    }

    /**
     * Creates or updates a credential schema and links all tools of the given API to it.
     *
     * @param apiId          the API whose tools should be linked
     * @param credentialName unique credential key (e.g. "stripepayments")
     * @param authType       authentication type (bearer, apikey, oauth2, etc.)
     * @param iconSlug       brand icon identifier (nullable)
     * @param iconUrl        dynamic icon URL (S3 proxy) for custom API icons (nullable)
     */
    public void linkCredentials(UUID apiId, String credentialName, String authType, String iconSlug, String iconUrl) {
        linkCredentials(apiId, credentialName, authType, iconSlug, iconUrl, null);
    }

    /**
     * Creates or updates a credential schema and links all tools of the given API to it, with an
     * explicitly declared credential placement.
     *
     * @param apiKeyConfig where the credential goes on the wire, or {@code null} for the default
     *                     placement of {@code authType}
     */
    public void linkCredentials(UUID apiId, String credentialName, String authType, String iconSlug,
                                String iconUrl, ApiKeyConfig apiKeyConfig) {
        linkCredentials(apiId, credentialName, authType, iconSlug, iconUrl, apiKeyConfig, null);
    }

    /**
     * Creates or updates a credential schema and links all tools of the given API to it, with an
     * explicit placement and, for OAuth2, the provider endpoints the connection needs.
     *
     * @param oauth2Config the provider's {@code {authorizationUrl, tokenUrl, scopes, ...}} block,
     *                     stored under {@code metadata.oauth2Config} exactly as the catalogue
     *                     importer stores it, which is where the OAuth engine reads it back from.
     *                     {@code null} for every non-OAuth auth type.
     */
    public void linkCredentials(UUID apiId, String credentialName, String authType, String iconSlug,
                                String iconUrl, ApiKeyConfig apiKeyConfig,
                                com.fasterxml.jackson.databind.JsonNode oauth2Config) {
        if (credentialName == null || credentialName.isBlank()) {
            log.debug("No credential name for API {}, skipping credential linking", apiId);
            return;
        }
        if (authType == null || authType.isBlank()) {
            log.debug("No auth type for API {}, skipping credential linking", apiId);
            return;
        }

        // An auth type this method has no placement rule for still gets the historical X-API-Key
        // default, because the seed path has always relied on it. Say so out loud: silently
        // defaulting is how a bearer token ends up in X-API-Key, answered 401 by the provider and
        // success by us. Callers that accept user input (custom-API registration) refuse an
        // unknown type up front rather than reaching this line.
        String canonical = CredentialTypeNormalizer.normalize(authType);
        if (!KNOWN_AUTH_TYPES.contains(canonical)) {
            log.warn("Unknown auth type '{}' for credential '{}' (API {}) - falling back to the "
                    + "X-API-Key header. If this API expects another placement, declare it.",
                    authType, credentialName, apiId);
        }

        String properties = buildPropertiesJson(authType, apiKeyConfig);
        String displayName = buildDisplayName(credentialName);
        String metadata = buildCredentialMetadata(oauth2Config, apiKeyConfig);

        // Upsert credential schema
        UUID credentialId = upsertCredential(credentialName, displayName, authType, properties, iconSlug,
                iconUrl, metadata);

        // Link all tools for this API
        List<ApiToolEntity> tools = apiToolRepository.findByApiId(apiId);
        for (ApiToolEntity tool : tools) {
            linkToolCredential(tool.getId(), credentialId, credentialName, authType, apiKeyConfig);
        }

        log.info("Linked {} tools to credential '{}' for API {}", tools.size(), credentialName, apiId);
    }

    private UUID upsertCredential(String credentialName, String displayName, String authType, String properties,
                                  String iconSlug, String iconUrl, String metadata) {
        // Custom-API registration is single-variant; align with the post-V103 schema where
        // catalog.credentials uniqueness is (credential_name, variant). Hard-code variant='primary'
        // and target the matching unique constraint so re-registers upsert cleanly.
        String sql = """
                INSERT INTO catalog.credentials (credential_name, variant, display_name, auth_type, properties, icon_slug, icon_url, metadata, created_at, updated_at)
                VALUES (?, 'primary', ?, ?, ?::jsonb, ?, ?, ?::jsonb, EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000)
                ON CONFLICT (credential_name, variant) DO UPDATE
                SET display_name = EXCLUDED.display_name,
                    auth_type = EXCLUDED.auth_type,
                    properties = EXCLUDED.properties,
                    icon_slug = EXCLUDED.icon_slug,
                    icon_url = EXCLUDED.icon_url,
                    -- MERGE, never replace. This class emits only oauth2Config and apiKeyConfig,
                    -- while a row written by another path carries keys it depends on: the
                    -- importer's source marker, which the CE bootstrap uses as its DELETE
                    -- predicate, plus fakeAuth, rateLimits, authVariants and the stableCredential*
                    -- set. Assigning EXCLUDED.metadata drops every one of them on a name
                    -- collision, silently and with nothing gained.
                    metadata = catalog.credentials.metadata || EXCLUDED.metadata,
                    updated_at = EXTRACT(EPOCH FROM NOW()) * 1000
                RETURNING id
                """;

        return jdbcTemplate.queryForObject(sql, UUID.class,
                credentialName, displayName, authType, properties, iconSlug, iconUrl, metadata);
    }

    private void linkToolCredential(UUID toolId, UUID credentialId, String credentialName, String authType,
                                    ApiKeyConfig apiKeyConfig) {
        String metadata = buildInjectionMetadata(authType, apiKeyConfig);
        // Custom API registration (this path) is single-variant by design; align
        // with the post-V107 (api_tool_id, credential_name, variant) uniqueness
        // by tagging these links as 'primary'.
        String variant = "primary";

        String sql = """
                INSERT INTO catalog.tool_credentials (api_tool_id, credential_id, credential_name, variant, is_required, usage, metadata, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, 'authentication', ?::jsonb, EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000)
                ON CONFLICT (api_tool_id, credential_name, variant) DO UPDATE
                SET credential_id = EXCLUDED.credential_id,
                    metadata = EXCLUDED.metadata,
                    updated_at = EXTRACT(EPOCH FROM NOW()) * 1000
                """;

        jdbcTemplate.update(sql, toolId, credentialId, credentialName, variant, metadata);
    }

    /**
     * Delete the credential template and associated tool_credentials by name.
     * tool_credentials.credential_id FK is ON DELETE SET NULL, so deleting
     * the credentials row would leave orphaned tool_credentials rows. We
     * explicitly delete them first by credential_name.
     */
    public void deleteCredentialByName(String credentialName) {
        if (credentialName == null || credentialName.isBlank()) return;
        // No variant filter, deliberately. Filtering on 'primary' looks protective and is not:
        // the native templates actually at risk (imap, smtp) are THEMSELVES 'primary', so it
        // guarded nothing, while it stopped removing a custom API's own credential when that row
        // predates V103 and therefore sits under its auth type rather than 'primary'. Ownership,
        // not variant, is the question, and the caller answers it before calling.
        int toolCredDeleted = jdbcTemplate.update(
                "DELETE FROM catalog.tool_credentials WHERE credential_name = ?", credentialName);
        int deleted = jdbcTemplate.update(
                "DELETE FROM catalog.credentials WHERE credential_name = ?", credentialName);
        if (deleted > 0 || toolCredDeleted > 0) {
            log.info("Deleted credential template '{}' ({} tool_credentials removed)", credentialName, toolCredDeleted);
        }
    }

    /** Auth types this service knows a placement rule for, in canonical spelling. */
    private static final java.util.Set<String> KNOWN_AUTH_TYPES =
            java.util.Set.of("api_key", "bearer_token", "oauth2", "basic_auth");

    /**
     * Build the injection metadata JSON that HttpExecutionService expects:
     * {@code {"field": "...", "injection": {"type": "...", "key": "...", "prefix": "..."}}}.
     *
     * <p>The auth type is CANONICALISED first ({@link CredentialTypeNormalizer}), because one
     * mechanism reaches this method under several spellings: the seeds and the catalog say
     * {@code bearer_token} or {@code apiKey}, the custom-API form says {@code bearer} or
     * {@code apikey}, and {@code catalog(action='schema')} reports {@code bearer_token} back to
     * agents. Matching the RAW string sent every spelling but the exact word {@code bearer} into
     * the X-API-Key default, so a bearer token went out in the wrong header while registration
     * reported success and the provider answered 401 with nothing on our side explaining why.
     *
     * <p>A declared {@link ApiKeyConfig} wins over the defaults, so an API can place its
     * credential where its provider actually expects it (any header name, a query parameter, a
     * non-Bearer prefix) rather than the two shapes this method used to hard-code. The engine has
     * supported all of these placements for a long time; only the way to declare one was missing.
     */
    private String buildInjectionMetadata(String authType, ApiKeyConfig config) {
        String canonical = CredentialTypeNormalizer.normalize(authType);
        ObjectNode root = JSON.createObjectNode();
        ObjectNode injection = JSON.createObjectNode();

        // basic_auth carries no header name or prefix of its own: HttpExecutionService builds
        // "Authorization: Basic base64(username:password)" from the credential data map itself.
        if ("basic_auth".equals(canonical)) {
            root.put("field", "username");
            injection.put("type", "basic_auth");
            root.set("injection", injection);
            return writeJson(root);
        }

        root.put("field", resolveCredentialField(canonical, config));

        String location = config != null && !isBlank(config.location())
                ? config.location().trim().toLowerCase(Locale.ROOT)
                : "header";

        if ("query".equals(location)) {
            injection.put("type", "query");
            injection.put("key", firstNonBlank(config == null ? null : config.queryParamName(), "api_key"));
        } else {
            injection.put("type", "header");
            injection.put("key", firstNonBlank(config == null ? null : config.headerName(),
                    defaultHeaderFor(canonical)));
            // Emit "prefix" ONLY when the author declared one. Absent means "let the engine
            // decide", and it defaults to "Bearer " on the Authorization header. A declared
            // EMPTY string is a different instruction ("send the credential raw") that several
            // providers require, so it must survive as an explicit "".
            if (config != null && config.prefix() != null) {
                injection.put("prefix", config.prefix());
            }
        }

        root.set("injection", injection);
        return writeJson(root);
    }

    /**
     * The request header the credential will occupy at runtime, lowercased, or {@code null} when
     * it does not travel in a header at all (query injection).
     *
     * <p>Public because the registration path must RESERVE that name, so a static header an author
     * declares can never take the name the runtime injects the secret into. That reservation used
     * to be a SECOND, independent copy of the switch in this class, and the two drifted exactly as
     * you would expect: a {@code bearer_token} API reserved {@code X-API-Key} while its credential
     * went into {@code Authorization}, leaving the real auth header unguarded. One derivation now,
     * two readers.
     */
    public static String credentialHeaderName(String authType, ApiKeyConfig config) {
        String canonical = CredentialTypeNormalizer.normalize(authType);
        if ("basic_auth".equals(canonical)) {
            return "authorization";
        }
        if (config != null && !isBlank(config.location())
                && "query".equals(config.location().trim().toLowerCase(Locale.ROOT))) {
            return null;
        }
        return firstNonBlank(config == null ? null : config.headerName(), defaultHeaderFor(canonical))
                .toLowerCase(Locale.ROOT);
    }

    /** Default header for an auth type with no declared placement. */
    private static String defaultHeaderFor(String canonicalAuthType) {
        return switch (canonicalAuthType) {
            case "bearer_token", "oauth2" -> "Authorization";
            default -> "X-API-Key";
        };
    }

    /**
     * Credential field holding the secret. The injection metadata and the credential properties
     * MUST agree on this name, or the engine looks up a field the connection form never asked
     * for and sends no credential at all.
     */
    private static String resolveCredentialField(String canonicalAuthType, ApiKeyConfig config) {
        if (config != null && !isBlank(config.keyName())) {
            return config.keyName().trim();
        }
        return switch (canonicalAuthType) {
            case "bearer_token", "oauth2" -> "access_token";
            default -> "api_key";
        };
    }

    private String buildPropertiesJson(String authType, ApiKeyConfig config) {
        String canonical = CredentialTypeNormalizer.normalize(authType);
        String field = resolveCredentialField(canonical, config);
        ObjectNode properties = JSON.createObjectNode();

        switch (canonical) {
            case "oauth2" -> {
                properties.set("client_id", property("Client ID"));
                properties.set("client_secret", property("Client Secret"));
            }
            // The engine reads username/password out of the credential data map to build the
            // Basic header, so the connection form has to ask for exactly those two fields.
            // Asking for a single "API Key" (what this branch used to do) produced a form whose
            // value the basic_auth path could never find: it logged "username/password missing"
            // and sent NO auth header at all.
            case "basic_auth" -> {
                properties.set("username", property("Username"));
                properties.set("password", property("Password"));
            }
            case "bearer_token" -> properties.set(field, property("Bearer Token"));
            default -> properties.set(field, property("API Key"));
        }
        return writeJson(properties);
    }

    /**
     * The credential template's {@code metadata} column.
     *
     * <p>Only OAuth2 needs anything here today, under the key {@code oauth2Config}, which is the
     * shape and the key the catalogue importer writes and the OAuth engine reads. Without this
     * column an oauth2 credential has no authorization or token endpoint and its connection can
     * never start - which is precisely why oauth2 was unusable on this path while working for the
     * 176 catalogue APIs that authenticate the same way.
     */
    private String buildCredentialMetadata(com.fasterxml.jackson.databind.JsonNode oauth2Config,
                                          ApiKeyConfig apiKeyConfig) {
        ObjectNode metadata = JSON.createObjectNode();
        if (oauth2Config != null && oauth2Config.isObject() && !oauth2Config.isEmpty()) {
            metadata.set("oauth2Config", oauth2Config.deepCopy());
        }
        // The placement is also kept here, verbatim and unused by the engine, purely so the
        // declaration can be READ BACK. Without it, the only record of "this API sends its key as
        // X-Api-Token" is the derived injection blob, and the obvious read-modify-write round trip
        // (load the API, change a description, save) silently resets the placement to the default
        // because the editor never saw it. A declaration nobody can read back is a declaration the
        // next edit destroys.
        if (apiKeyConfig != null && !apiKeyConfig.isBlank()) {
            ObjectNode declared = JSON.createObjectNode();
            putIfPresent(declared, "location", apiKeyConfig.location());
            putIfPresent(declared, "headerName", apiKeyConfig.headerName());
            putIfPresent(declared, "queryParamName", apiKeyConfig.queryParamName());
            putIfPresent(declared, "keyName", apiKeyConfig.keyName());
            if (apiKeyConfig.prefix() != null) {
                declared.put("prefix", apiKeyConfig.prefix());
            }
            metadata.set("apiKeyConfig", declared);
        }
        return writeJson(metadata);
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (!isBlank(value)) {
            node.put(field, value.trim());
        }
    }

    /**
     * The auth declaration stored for this credential, as {@code {apiKeyConfig?, oauth2Config?}},
     * or an empty node when there is none. Read by the details projection so an editor can hand
     * the declaration back unchanged instead of dropping it.
     */
    public com.fasterxml.jackson.databind.JsonNode readDeclaredAuth(String credentialName) {
        if (credentialName == null || credentialName.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            String raw = jdbcTemplate.queryForObject(
                    "SELECT metadata::text FROM catalog.credentials WHERE credential_name = ? AND variant = 'primary'",
                    String.class, credentialName);
            if (raw == null || raw.isBlank()) {
                return JSON.createObjectNode();
            }
            return JSON.readTree(raw);
        } catch (Exception e) {
            // Best effort: the declaration is an editor convenience, never a condition of reading
            // the API. Returning empty degrades to the previous behaviour for this one field.
            log.debug("No stored auth declaration for credential '{}': {}", credentialName, e.getMessage());
            return JSON.createObjectNode();
        }
    }

    /** One required string field of a credential form. */
    private static ObjectNode property(String displayName) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "string");
        node.put("displayName", displayName);
        node.put("required", true);
        return node;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static String writeJson(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // Every node here is built from strings this class controls, so this cannot happen
            // in practice; failing loudly beats persisting a malformed metadata blob that would
            // make the engine silently skip credential injection.
            throw new IllegalStateException("Failed to serialize credential metadata", e);
        }
    }

    private String buildDisplayName(String credentialName) {
        // Convert camelCase/snake_case to human-readable
        return credentialName
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace("_", " ")
                .replace("-", " ");
    }
}
