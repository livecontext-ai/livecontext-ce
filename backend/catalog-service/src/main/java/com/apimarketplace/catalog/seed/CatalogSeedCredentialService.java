package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
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

        linkCredentials(apiId, spec.getCredentialName(), spec.getAuthType(), spec.getIconSlug());
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
        if (credentialName == null || credentialName.isBlank()) {
            log.debug("No credential name for API {}, skipping credential linking", apiId);
            return;
        }
        if (authType == null || authType.isBlank()) {
            log.debug("No auth type for API {}, skipping credential linking", apiId);
            return;
        }

        String properties = buildPropertiesJson(authType);
        String displayName = buildDisplayName(credentialName);

        // Upsert credential schema
        UUID credentialId = upsertCredential(credentialName, displayName, authType, properties, iconSlug, iconUrl);

        // Link all tools for this API
        List<ApiToolEntity> tools = apiToolRepository.findByApiId(apiId);
        for (ApiToolEntity tool : tools) {
            linkToolCredential(tool.getId(), credentialId, credentialName, authType);
        }

        log.info("Linked {} tools to credential '{}' for API {}", tools.size(), credentialName, apiId);
    }

    private UUID upsertCredential(String credentialName, String displayName, String authType, String properties, String iconSlug, String iconUrl) {
        // Custom-API registration is single-variant; align with the post-V103 schema where
        // catalog.credentials uniqueness is (credential_name, variant). Hard-code variant='primary'
        // and target the matching unique constraint so re-registers upsert cleanly.
        String sql = """
                INSERT INTO catalog.credentials (credential_name, variant, display_name, auth_type, properties, icon_slug, icon_url, created_at, updated_at)
                VALUES (?, 'primary', ?, ?, ?::jsonb, ?, ?, EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000)
                ON CONFLICT (credential_name, variant) DO UPDATE
                SET display_name = EXCLUDED.display_name,
                    auth_type = EXCLUDED.auth_type,
                    properties = EXCLUDED.properties,
                    icon_slug = EXCLUDED.icon_slug,
                    icon_url = EXCLUDED.icon_url,
                    updated_at = EXTRACT(EPOCH FROM NOW()) * 1000
                RETURNING id
                """;

        return jdbcTemplate.queryForObject(sql, UUID.class,
                credentialName, displayName, authType, properties, iconSlug, iconUrl);
    }

    private void linkToolCredential(UUID toolId, UUID credentialId, String credentialName, String authType) {
        String metadata = buildInjectionMetadata(authType);
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
        int toolCredDeleted = jdbcTemplate.update(
                "DELETE FROM catalog.tool_credentials WHERE credential_name = ?", credentialName);
        int deleted = jdbcTemplate.update(
                "DELETE FROM catalog.credentials WHERE credential_name = ?", credentialName);
        if (deleted > 0 || toolCredDeleted > 0) {
            log.info("Deleted credential template '{}' ({} tool_credentials removed)", credentialName, toolCredDeleted);
        }
    }

    /**
     * Build the injection metadata JSON that HttpExecutionService expects.
     * Must match the structure: {"field": "...", "injection": {"type": "...", "key": "..."}}
     *
     * <p>HttpExecutionService.prepareHeadersWithCredentials auto-prepends "Bearer " when
     * the injection key is "Authorization" - so bearer/oauth2 correctly use that key.
     *
     * <p>Note: "basic" auth falls through to default (X-API-Key header) because
     * HttpExecutionService only supports Bearer prefix on Authorization header today.
     * A dedicated Basic auth injection path would require HttpExecutionService changes.
     */
    private String buildInjectionMetadata(String authType) {
        return switch (authType.toLowerCase()) {
            case "bearer", "oauth2" -> """
                    {"field": "access_token", "injection": {"type": "header", "key": "Authorization"}}""";
            default -> """
                    {"field": "api_key", "injection": {"type": "header", "key": "X-API-Key"}}""";
        };
    }

    private String buildPropertiesJson(String authType) {
        return switch (authType.toLowerCase()) {
            case "apikey" -> """
                    {"api_key": {"type": "string", "displayName": "API Key", "required": true}}""";
            case "bearer" -> """
                    {"access_token": {"type": "string", "displayName": "Bearer Token", "required": true}}""";
            case "oauth2" -> """
                    {"client_id": {"type": "string", "displayName": "Client ID", "required": true}, "client_secret": {"type": "string", "displayName": "Client Secret", "required": true}}""";
            case "basic" -> """
                    {"api_key": {"type": "string", "displayName": "API Key", "required": true}}""";
            default -> """
                    {"api_key": {"type": "string", "displayName": "API Key", "required": true}}""";
        };
    }

    private String buildDisplayName(String credentialName) {
        // Convert camelCase/snake_case to human-readable
        return credentialName
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace("_", " ")
                .replace("-", " ");
    }
}
