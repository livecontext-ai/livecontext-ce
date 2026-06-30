package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.*;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Repository for platform-level credentials.
 * Handles CRUD operations with encryption for sensitive data.
 */
@Repository
public class PlatformCredentialRepository {

    private static final Logger log = LoggerFactory.getLogger(PlatformCredentialRepository.class);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    public PlatformCredentialRepository(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            CredentialEncryptionService encryptionService,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
    }

    // ========== Tenant-Aware Query Helpers ==========
    // All queries filter: (tenant_id = ? OR tenant_id IS NULL) to show both
    // tenant-scoped and platform-wide credentials. Tenant-scoped takes priority.
    //
    // IMPORTANT: never concatenate these constants into a text block with the
    // pattern `""" + TENANT_FILTER + """` - Java text blocks strip trailing
    // whitespace on every content line (JEP 378), so `AND """` becomes `AND`
    // and the constant is fused onto the previous token (`AND(tenant_id...`).
    // Use `.formatted(TENANT_FILTER, TENANT_ORDER)` with explicit `%s` slots.
    private static final String TENANT_FILTER = "(tenant_id = ? OR tenant_id IS NULL)";
    // Tenant priority first, then prefer rows that actually carry admin-supplied
    // secrets over placeholder rows created by `setEnabledForVariant` (V103
    // per-variant toggle with no secret yet), then prefer enabled rows, then
    // stable tiebreak by id. Without this ordering, `findByIntegrationName`
    // could return a disabled/placeholder row when a legit enabled oauth2 row
    // also exists for the same `integration_name`, causing `getOAuth2Credentials`
    // to fail the `hasOAuth2Credentials()` gate and break OAuth2 at runtime.
    // Match the Java-side `hasCustomFields` definition: a non-null map with at
    // least one entry. The DB column defaults to `'{}'::jsonb`, so a bare
    // `custom_fields IS NOT NULL` check is vacuously true and would collapse
    // this predicate to `is_enabled DESC` - which fails in the
    // "disabled-oauth2-row + enabled-placeholder" scenario.
    private static final String SECRETS_PRESENT =
            "(client_secret IS NOT NULL OR api_key IS NOT NULL OR password IS NOT NULL "
            + "OR (custom_fields IS NOT NULL AND custom_fields <> '{}'::jsonb))";
    private static final String TENANT_ORDER =
            "tenant_id NULLS LAST, " + SECRETS_PRESENT + " DESC, is_enabled DESC, id ASC";
    private static final String PLATFORM_ORDER =
            SECRETS_PRESENT + " DESC, is_enabled DESC, id ASC";

    // ========== Org-Aware Resolution (V362) ==========
    // BYOK custom OAuth connections are scoped by (tenant_id, organization_id),
    // mirroring auth.credentials (V208) STRICT isolation. Resolution priority is:
    //   workspace BYOK   (tenant = me, organization_id matches the active scope)
    //     > platform-wide (tenant_id IS NULL -> global LiveContext app)
    //
    // The FILTER keeps ONLY my row in the EXACT active scope (org NULL-safe match
    // via IS NOT DISTINCT FROM, so it also serves personal scope where org is
    // null) OR a global platform-wide row. A BYOK tagged to a DIFFERENT workspace
    // is never returned - that is the strict isolation. `tenant_id NULLS LAST`
    // ranks my workspace row above the platform-wide fallback. The org `?` is
    // bound once (in the FILTER); ordering reuses TENANT_ORDER.
    private static final String ORG_TENANT_FILTER =
            "((tenant_id = ? AND organization_id IS NOT DISTINCT FROM ?) OR tenant_id IS NULL)";

    /**
     * Canonical variant names the importer and admin dialog are allowed to use.
     * Sourced from {@code ApiMigrationImporter}: the JSON {@code auth.type} /
     * {@code authType} values plus the {@code "primary"} legacy fallback. Any
     * variant outside this set on the admin toggle path is treated as a typo
     * and rejected (the UPSERT would otherwise silently create a garbage
     * placeholder row that the UI never lists).
     */
    private static final Set<String> KNOWN_VARIANTS = Set.of(
            "oauth2", "api_key", "bearer_token", "basic_auth", "custom", "none", "primary"
    );

    /**
     * Find a platform credential by integration name, tenant-aware.
     * Tenant-scoped credential takes priority over platform-wide.
     */
    public Optional<PlatformCredential> findByIntegrationName(String integrationName, String tenantId) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE LOWER(integration_name) = LOWER(?) AND %s
            ORDER BY %s
            LIMIT 1
            """.formatted(TENANT_FILTER, TENANT_ORDER);
        return jdbc.query(sql, new PlatformCredentialRowMapper(), integrationName, tenantId)
                .stream()
                .findFirst();
    }

    /**
     * Find a platform credential by primary key.
     */
    public Optional<PlatformCredential> findById(Long id) {
        String sql = "SELECT * FROM auth.platform_credentials WHERE id = ?";
        return jdbc.query(sql, new PlatformCredentialRowMapper(), id)
                .stream()
                .findFirst();
    }

    /**
     * Find a platform credential by integration name (no tenant filter - backwards compat for internal use).
     *
     * <p>After V103 an integration may have multiple rows (one per variant). The
     * {@link #PLATFORM_ORDER} hint guarantees that a row with actual secrets
     * wins over a placeholder created by {@link #setEnabledForVariant}, so
     * variant-agnostic callers like {@code getOAuth2Credentials} keep working
     * unchanged: the oauth2 row with {@code client_id / client_secret} will be
     * selected over a bearer_token placeholder sharing the same {@code
     * integration_name}.
     */
    public Optional<PlatformCredential> findByIntegrationName(String integrationName) {
        String sql = "SELECT * FROM auth.platform_credentials "
                + "WHERE LOWER(integration_name) = LOWER(?) AND tenant_id IS NULL "
                + "ORDER BY " + PLATFORM_ORDER + " "
                + "LIMIT 1";
        return jdbc.query(sql, new PlatformCredentialRowMapper(), integrationName)
                .stream()
                .findFirst();
    }

    /**
     * Find the OAuth2 credential row for an integration, tenant-aware.
     * Tenant-scoped credential takes priority over platform-wide.
     */
    public Optional<PlatformCredential> findOAuth2ByIntegrationName(String integrationName, String tenantId) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE LOWER(integration_name) = LOWER(?)
              AND auth_type = ?
              AND %s
            ORDER BY %s
            LIMIT 1
            """.formatted(TENANT_FILTER, TENANT_ORDER);
        return jdbc.query(sql, new PlatformCredentialRowMapper(), integrationName, AuthType.OAUTH2.getValue(), tenantId)
                .stream()
                .findFirst();
    }

    /**
     * Find the OAuth2 credential row for an integration, platform-wide only.
     */
    public Optional<PlatformCredential> findOAuth2ByIntegrationName(String integrationName) {
        String sql = "SELECT * FROM auth.platform_credentials "
                + "WHERE LOWER(integration_name) = LOWER(?) "
                + "AND auth_type = ? "
                + "AND tenant_id IS NULL "
                + "ORDER BY " + PLATFORM_ORDER + " "
                + "LIMIT 1";
        return jdbc.query(sql, new PlatformCredentialRowMapper(), integrationName, AuthType.OAUTH2.getValue())
                .stream()
                .findFirst();
    }

    // ========== Org-Aware Finders (V362) ==========

    /**
     * Find a platform credential by integration name, org-aware (V362).
     * Resolution priority: workspace-org BYOK &gt; personal BYOK (org NULL) &gt;
     * platform-wide. {@code organizationId} is the active workspace (may be null
     * for personal scope). See {@link #ORG_TENANT_FILTER}/{@link #ORG_TENANT_ORDER}.
     */
    public Optional<PlatformCredential> findByIntegrationName(String integrationName, String tenantId, String organizationId) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE LOWER(integration_name) = LOWER(?) AND %s
            ORDER BY %s
            LIMIT 1
            """.formatted(ORG_TENANT_FILTER, TENANT_ORDER);
        return jdbc.query(sql, new PlatformCredentialRowMapper(), integrationName, tenantId, organizationId)
                .stream()
                .findFirst();
    }

    /**
     * Find the OAuth2 credential row for an integration, org-aware (V362).
     * Same priority as {@link #findByIntegrationName(String, String, String)}.
     */
    public Optional<PlatformCredential> findOAuth2ByIntegrationName(String integrationName, String tenantId, String organizationId) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE LOWER(integration_name) = LOWER(?)
              AND auth_type = ?
              AND %s
            ORDER BY %s
            LIMIT 1
            """.formatted(ORG_TENANT_FILTER, TENANT_ORDER);
        return jdbc.query(sql, new PlatformCredentialRowMapper(),
                        integrationName, AuthType.OAUTH2.getValue(), tenantId, organizationId)
                .stream()
                .findFirst();
    }

    /**
     * Find the exact BYOK row owned by {@code tenantId} in scope
     * {@code organizationId} (NULL-safe match). Used by the save/upsert path so
     * editing a connection in one workspace never clobbers the same integration's
     * row in another workspace. Unlike the resolver above, this does NOT fall back
     * to personal/platform rows - it returns the row for that exact scope or none.
     */
    public Optional<PlatformCredential> findOwnedRow(String integrationName, String tenantId, String organizationId) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE LOWER(integration_name) = LOWER(?)
              AND tenant_id = ?
              AND organization_id IS NOT DISTINCT FROM ?
            ORDER BY id ASC
            LIMIT 1
            """;
        return jdbc.query(sql, new PlatformCredentialRowMapper(), integrationName, tenantId, organizationId)
                .stream()
                .findFirst();
    }

    /**
     * List BYOK rows owned by {@code tenantId} in scope {@code organizationId}
     * (strict isolation, NULL-safe match) - workspace rows in a workspace,
     * personal (org NULL) rows in personal scope. Mirrors the strict-isolation
     * listing of auth.credentials (V208). Capped at 100 (defense-in-depth).
     */
    public List<PlatformCredential> findOwnedByTenant(String tenantId, String organizationId) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE tenant_id = ? AND organization_id IS NOT DISTINCT FROM ?
            ORDER BY display_name, integration_name
            LIMIT 100
            """;
        return jdbc.query(sql, new PlatformCredentialRowMapper(), tenantId, organizationId);
    }

    /**
     * Count BYOK rows owned by {@code tenantId} in scope {@code organizationId}
     * (NULL-safe match). Used by the per-(tenant, workspace) insert cap so a
     * tenant maxed out in one workspace can still register a connection in
     * another. See {@code PlatformCredentialService.MAX_BYOK_PER_TENANT}.
     */
    public int countByTenantIdAndOrganizationId(String tenantId, String organizationId) {
        String sql = "SELECT COUNT(*) FROM auth.platform_credentials "
                + "WHERE tenant_id = ? AND organization_id IS NOT DISTINCT FROM ?";
        Integer count = jdbc.queryForObject(sql, Integer.class, tenantId, organizationId);
        return count != null ? count : 0;
    }

    /**
     * Delete the BYOK row owned by {@code tenantId} in scope {@code organizationId}
     * (strict, NULL-safe match). Never touches platform-wide rows or other
     * workspaces' rows.
     */
    public boolean deleteByIntegrationName(String integrationName, String tenantId, String organizationId) {
        String sql = "DELETE FROM auth.platform_credentials "
                + "WHERE LOWER(integration_name) = LOWER(?) AND tenant_id = ? "
                + "AND organization_id IS NOT DISTINCT FROM ?";
        int rows = jdbc.update(sql, integrationName, tenantId, organizationId);
        if (rows > 0) {
            log.info("Deleted tenant platform credential: {} (tenant={}, org={})",
                    integrationName, tenantId, organizationId);
        }
        return rows > 0;
    }

    /**
     * Find all platform credentials visible to a tenant.
     */
    public List<PlatformCredential> findAll(String tenantId) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE %s
            ORDER BY category, display_name
            """.formatted(TENANT_FILTER);
        return jdbc.query(sql, new PlatformCredentialRowMapper(), tenantId);
    }

    /**
     * Find all platform credentials (no tenant filter - admin/internal use).
     */
    public List<PlatformCredential> findAll() {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE tenant_id IS NULL
            ORDER BY category, display_name
            """;
        return jdbc.query(sql, new PlatformCredentialRowMapper());
    }

    /**
     * Find platform credentials owned strictly by a tenant - excludes
     * platform-wide ({@code tenant_id IS NULL}) rows. Used by the
     * "My Custom OAuth Connections" listing.
     *
     * <p>Capped at 100 rows to bound the response size; any tenant with more
     * than 100 BYOK rows is degenerate and likely indicates a broken loop or a
     * stale UI state. The per-tenant cap on inserts ({@code MAX_BYOK_PER_TENANT}
     * in the service) is the primary defense; this LIMIT is defense-in-depth.
     */
    public List<PlatformCredential> findOwnedByTenant(String tenantId) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE tenant_id = ?
            ORDER BY display_name, integration_name
            LIMIT 100
            """;
        return jdbc.query(sql, new PlatformCredentialRowMapper(), tenantId);
    }

    /**
     * Count platform credentials owned strictly by a tenant - excludes
     * platform-wide ({@code tenant_id IS NULL}) rows. Used by the
     * insert-time {@code MAX_BYOK_PER_TENANT} cap.
     */
    public int countByTenantId(String tenantId) {
        String sql = "SELECT COUNT(*) FROM auth.platform_credentials WHERE tenant_id = ?";
        Integer count = jdbc.queryForObject(sql, Integer.class, tenantId);
        return count != null ? count : 0;
    }

    /**
     * Find all enabled platform credentials visible to a tenant.
     */
    public List<PlatformCredential> findAllEnabled(String tenantId) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE is_enabled = true AND %s
            ORDER BY category, display_name
            """.formatted(TENANT_FILTER);
        return jdbc.query(sql, new PlatformCredentialRowMapper(), tenantId);
    }

    /**
     * Find all enabled platform credentials (platform-wide only).
     */
    public List<PlatformCredential> findAllEnabled() {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE is_enabled = true AND tenant_id IS NULL
            ORDER BY category, display_name
            """;
        return jdbc.query(sql, new PlatformCredentialRowMapper());
    }

    /**
     * Find platform credentials by category, tenant-aware.
     */
    public List<PlatformCredential> findByCategory(String category, String tenantId) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE LOWER(category) = LOWER(?) AND %s
            ORDER BY display_name
            """.formatted(TENANT_FILTER);
        return jdbc.query(sql, new PlatformCredentialRowMapper(), category, tenantId);
    }

    /**
     * Find platform credentials by category (platform-wide only).
     */
    public List<PlatformCredential> findByCategory(String category) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE LOWER(category) = LOWER(?) AND tenant_id IS NULL
            ORDER BY display_name
            """;
        return jdbc.query(sql, new PlatformCredentialRowMapper(), category);
    }

    /**
     * Find platform credentials by auth type, tenant-aware.
     */
    public List<PlatformCredential> findByAuthType(AuthType authType, String tenantId) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE auth_type = ? AND %s
            ORDER BY category, display_name
            """.formatted(TENANT_FILTER);
        return jdbc.query(sql, new PlatformCredentialRowMapper(), authType.getValue(), tenantId);
    }

    /**
     * Find platform credentials by auth type (platform-wide only).
     */
    public List<PlatformCredential> findByAuthType(AuthType authType) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE auth_type = ? AND tenant_id IS NULL
            ORDER BY category, display_name
            """;
        return jdbc.query(sql, new PlatformCredentialRowMapper(), authType.getValue());
    }

    /**
     * Get distinct categories, tenant-aware.
     */
    public List<String> findDistinctCategories(String tenantId) {
        String sql = """
            SELECT DISTINCT category FROM auth.platform_credentials
            WHERE category IS NOT NULL AND %s
            ORDER BY category
            """.formatted(TENANT_FILTER);
        return jdbc.queryForList(sql, String.class, tenantId);
    }

    /**
     * Get distinct categories (platform-wide only).
     */
    public List<String> findDistinctCategories() {
        String sql = """
            SELECT DISTINCT category FROM auth.platform_credentials
            WHERE category IS NOT NULL AND tenant_id IS NULL
            ORDER BY category
            """;
        return jdbc.queryForList(sql, String.class);
    }

    /**
     * Count credentials per category, tenant-aware.
     */
    public List<CategoryInfo> countByCategory(String tenantId) {
        String sql = """
            SELECT category, COUNT(*) as count
            FROM auth.platform_credentials
            WHERE category IS NOT NULL AND %s
            GROUP BY category
            ORDER BY category
            """.formatted(TENANT_FILTER);
        return jdbc.query(sql, (rs, rowNum) -> new CategoryInfo(
                rs.getString("category"),
                rs.getString("category"),
                null,
                rs.getInt("count")
        ), tenantId);
    }

    /**
     * Count credentials per category (platform-wide only).
     */
    public List<CategoryInfo> countByCategory() {
        String sql = """
            SELECT category, COUNT(*) as count
            FROM auth.platform_credentials
            WHERE category IS NOT NULL AND tenant_id IS NULL
            GROUP BY category
            ORDER BY category
            """;
        return jdbc.query(sql, (rs, rowNum) -> new CategoryInfo(
                rs.getString("category"),
                rs.getString("category"),
                null,
                rs.getInt("count")
        ));
    }

    /**
     * Save a platform credential (insert or update).
     * Encrypts sensitive data before saving.
     */
    public PlatformCredential save(PlatformCredential credential) {
        if (credential.id() == null) {
            return insert(credential);
        } else {
            return update(credential);
        }
    }

    private PlatformCredential insert(PlatformCredential credential) {
        String sql = """
            INSERT INTO auth.platform_credentials (
                integration_name, display_name, auth_type,
                client_id, client_secret, api_key, username, password,
                auth_url, token_url, default_scopes,
                icon_slug, category, description, show_unverified_app_warning, is_enabled, created_by, custom_fields,
                default_markup_credits, max_calls_per_run, tenant_id, variant, organization_id
            ) VALUES (
                :integration_name, :display_name, :auth_type,
                :client_id, :client_secret, :api_key, :username, :password,
                :auth_url, :token_url, :default_scopes,
                :icon_slug, :category, :description, :show_unverified_app_warning, :is_enabled, :created_by, :custom_fields,
                :default_markup_credits, :max_calls_per_run, :tenant_id, :variant, :organization_id
            )
            """;

        SqlParameterSource params = buildParams(credential);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedJdbc.update(sql, params, keyHolder, new String[]{"id"});

        Long id = keyHolder.getKeyAs(Long.class);
        log.info("Created platform credential: {} (id={})", credential.integrationName(), id);
        return credential.withId(id);
    }

    private PlatformCredential update(PlatformCredential credential) {
        String sql = """
            UPDATE auth.platform_credentials SET
                display_name = :display_name,
                auth_type = :auth_type,
                client_id = :client_id,
                client_secret = COALESCE(:client_secret, client_secret),
                api_key = COALESCE(:api_key, api_key),
                username = :username,
                password = COALESCE(:password, password),
                auth_url = :auth_url,
                token_url = :token_url,
                default_scopes = :default_scopes,
                icon_slug = :icon_slug,
                category = :category,
                description = :description,
                show_unverified_app_warning = :show_unverified_app_warning,
                is_enabled = :is_enabled,
                custom_fields = :custom_fields,
                default_markup_credits = :default_markup_credits,
                max_calls_per_run = :max_calls_per_run,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", credential.id())
                .addValue("display_name", credential.displayName())
                .addValue("auth_type", credential.authType().getValue())
                .addValue("client_id", credential.clientId())
                .addValue("client_secret", encryptIfNotNull(credential.clientSecret()))
                .addValue("api_key", encryptIfNotNull(credential.apiKey()))
                .addValue("username", credential.username())
                .addValue("password", encryptIfNotNull(credential.password()))
                .addValue("auth_url", credential.authUrl())
                .addValue("token_url", credential.tokenUrl())
                .addValue("default_scopes", credential.defaultScopes())
                .addValue("icon_slug", credential.iconSlug())
                .addValue("category", credential.category())
                .addValue("description", credential.description())
                .addValue("show_unverified_app_warning", credential.showUnverifiedAppWarning())
                .addValue("is_enabled", credential.isEnabled())
                .addValue("custom_fields", toJsonbPGobject(credential.customFields()))
                .addValue("default_markup_credits",
                        credential.defaultMarkupCredits() != null ? credential.defaultMarkupCredits() : BigDecimal.ZERO)
                .addValue("max_calls_per_run",
                        credential.maxCallsPerRun() != null ? credential.maxCallsPerRun() : 500);

        namedJdbc.update(sql, params);
        log.info("Updated platform credential: {} (id={})", credential.integrationName(), credential.id());
        return credential;
    }

    private SqlParameterSource buildParams(PlatformCredential credential) {
        // Defense-in-depth: the service layer already normalizes before calling into the
        // repository, but we normalize again here so any future direct caller can't slip an
        // unsanitized integration_name into the DB. We delegate to the service-level
        // normalizer (the canonical one, aligned with IconSlugNormalizer.normalizeForKey)
        // rather than keeping a local copy that can drift out of sync.
        return new MapSqlParameterSource()
                .addValue("integration_name",
                        PlatformCredentialService.normalizeIntegrationName(credential.integrationName()))
                .addValue("display_name", credential.displayName())
                .addValue("auth_type", credential.authType().getValue())
                .addValue("client_id", credential.clientId())
                .addValue("client_secret", encryptIfNotNull(credential.clientSecret()))
                .addValue("api_key", encryptIfNotNull(credential.apiKey()))
                .addValue("username", credential.username())
                .addValue("password", encryptIfNotNull(credential.password()))
                .addValue("auth_url", credential.authUrl())
                .addValue("token_url", credential.tokenUrl())
                .addValue("default_scopes", credential.defaultScopes())
                .addValue("icon_slug", credential.iconSlug())
                .addValue("category", credential.category())
                .addValue("description", credential.description())
                .addValue("show_unverified_app_warning", credential.showUnverifiedAppWarning())
                .addValue("is_enabled", credential.isEnabled())
                .addValue("created_by", credential.createdBy())
                .addValue("custom_fields", toJsonbPGobject(credential.customFields()))
                .addValue("default_markup_credits",
                        credential.defaultMarkupCredits() != null ? credential.defaultMarkupCredits() : BigDecimal.ZERO)
                .addValue("max_calls_per_run",
                        credential.maxCallsPerRun() != null ? credential.maxCallsPerRun() : 500)
                .addValue("tenant_id", credential.tenantId())
                .addValue("variant",
                        credential.variant() != null && !credential.variant().isBlank()
                                ? credential.variant()
                                : PlatformCredential.DEFAULT_VARIANT)
                .addValue("organization_id", credential.organizationId());
    }

    /**
     * Delete a tenant-scoped platform credential by integration name.
     * Only deletes the tenant's own credential, never platform-wide.
     */
    public boolean deleteByIntegrationName(String integrationName, String tenantId) {
        String sql = "DELETE FROM auth.platform_credentials WHERE LOWER(integration_name) = LOWER(?) AND tenant_id = ?";
        int rows = jdbc.update(sql, integrationName, tenantId);
        if (rows > 0) {
            log.info("Deleted tenant platform credential: {} (tenant={})", integrationName, tenantId);
        }
        return rows > 0;
    }

    /**
     * Delete a platform-wide credential by integration name (admin only).
     */
    public boolean deleteByIntegrationName(String integrationName) {
        String sql = "DELETE FROM auth.platform_credentials WHERE LOWER(integration_name) = LOWER(?) AND tenant_id IS NULL";
        int rows = jdbc.update(sql, integrationName);
        if (rows > 0) {
            log.info("Deleted platform credential: {}", integrationName);
        }
        return rows > 0;
    }

    /**
     * Delete a platform credential by ID, with tenant ownership check.
     */
    public boolean deleteById(Long id, String tenantId) {
        String sql = "DELETE FROM auth.platform_credentials WHERE id = ? AND tenant_id = ?";
        int rows = jdbc.update(sql, id, tenantId);
        return rows > 0;
    }

    /**
     * Delete a platform credential by ID (admin, no tenant check).
     */
    public boolean deleteById(Long id) {
        String sql = "DELETE FROM auth.platform_credentials WHERE id = ?";
        int rows = jdbc.update(sql, id);
        return rows > 0;
    }

    /**
     * Check if a platform credential exists, tenant-aware.
     */
    public boolean existsByIntegrationName(String integrationName, String tenantId) {
        String sql = "SELECT COUNT(*) FROM auth.platform_credentials WHERE LOWER(integration_name) = LOWER(?) AND " + TENANT_FILTER;
        Integer count = jdbc.queryForObject(sql, Integer.class, integrationName, tenantId);
        return count != null && count > 0;
    }

    /**
     * Check if a platform credential exists (platform-wide only).
     */
    public boolean existsByIntegrationName(String integrationName) {
        String sql = "SELECT COUNT(*) FROM auth.platform_credentials WHERE LOWER(integration_name) = LOWER(?) AND tenant_id IS NULL";
        Integer count = jdbc.queryForObject(sql, Integer.class, integrationName);
        return count != null && count > 0;
    }

    /**
     * Enable or disable a tenant-scoped platform credential.
     */
    public boolean setEnabled(String integrationName, boolean enabled, String tenantId) {
        String sql = """
            UPDATE auth.platform_credentials
            SET is_enabled = ?, updated_at = CURRENT_TIMESTAMP
            WHERE LOWER(integration_name) = LOWER(?) AND tenant_id = ?
            """;
        int rows = jdbc.update(sql, enabled, integrationName, tenantId);
        return rows > 0;
    }

    /**
     * Enable or disable a platform-wide credential (admin only).
     */
    public boolean setEnabled(String integrationName, boolean enabled) {
        String sql = """
            UPDATE auth.platform_credentials
            SET is_enabled = ?, updated_at = CURRENT_TIMESTAMP
            WHERE LOWER(integration_name) = LOWER(?) AND tenant_id IS NULL
            """;
        int rows = jdbc.update(sql, enabled, integrationName);
        return rows > 0;
    }

    /**
     * Toggle a single platform-wide variant row, upserting when the row does
     * not yet exist. The admin uses this to disable a catalog variant (e.g.
     * Airtable {@code bearer_token}) independently of whether any secrets have
     * been saved for it - so a user wiring their own PAT still respects the
     * admin's intent. The placeholder row carries NO secrets: {@code client_id},
     * {@code client_secret}, {@code api_key}, etc. stay NULL. This keeps the
     * runtime OAuth2 gate ({@code isEnabled && hasOAuth2Credentials}) behaving
     * identically to "no row at all" and preserves the existing
     * platform-key-fed-to-users flow unchanged when the admin later fills in
     * secrets via the edit dialog.
     *
     * <p>Implementation is UPDATE-then-INSERT-with-DO-NOTHING because a caught
     * PostgreSQL unique violation leaves the surrounding transaction aborted.
     * The final UPDATE keeps the {@code tenant_id IS NULL} filter load-bearing
     * when a concurrent writer created the platform-wide row first.
     */
    public boolean setEnabledForVariant(String integrationName, String variant, boolean enabled) {
        if (variant == null || variant.isBlank() || !KNOWN_VARIANTS.contains(variant)) {
            // Defense in depth: the controller path already validates against
            // the catalog, but a direct service caller with a typo must not
            // create a silent CUSTOM placeholder. Preserve the pre-upsert
            // "variant not found → 404" contract for unknown variants.
            return false;
        }
        // Re-normalize at the repo boundary so any future direct caller that
        // skipped the service layer still writes a canonical integration_name.
        String normalized = PlatformCredentialService.normalizeIntegrationName(integrationName);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }

        int updated = updateEnabledForVariantPlatformWide(normalized, variant, enabled);
        if (updated > 0) {
            return true;
        }

        AuthType authType = AuthType.fromValue(variant);
        int maxCalls = authType == AuthType.OAUTH2 ? 0 : 500;
        String insertSql = """
            INSERT INTO auth.platform_credentials (
                integration_name, display_name, auth_type, variant,
                is_enabled, default_markup_credits, max_calls_per_run, tenant_id
            ) VALUES (?, ?, ?, ?, ?, 0, ?, NULL)
            ON CONFLICT DO NOTHING
            """;
        int inserted = jdbc.update(insertSql,
                normalized, normalized, authType.getValue(), variant,
                enabled, maxCalls);
        if (inserted > 0) {
            log.info("Upserted platform credential placeholder for toggle: {} variant={} enabled={}",
                    normalized, variant, enabled);
            return true;
        }
        // A concurrent toggle may have inserted the row first. DO NOTHING keeps
        // the transaction usable, so this UPDATE can safely apply our value.
        int retried = updateEnabledForVariantPlatformWide(normalized, variant, enabled);
        log.info("Skipped placeholder insert for {} variant={}, retried UPDATE affected={} rows",
                normalized, variant, retried);
        return retried > 0;
    }

    private int updateEnabledForVariantPlatformWide(String integrationName, String variant, boolean enabled) {
        String sql = """
            UPDATE auth.platform_credentials
            SET is_enabled = ?, updated_at = CURRENT_TIMESTAMP
            WHERE LOWER(integration_name) = LOWER(?) AND variant = ? AND tenant_id IS NULL
            """;
        return jdbc.update(sql, enabled, integrationName, variant);
    }

    /**
     * Tenant-scoped counterpart of {@link #setEnabledForVariant(String, String, boolean)}.
     *
     * <p>Intentionally UPDATE-only: tenant variant rows are created by the
     * end-user override flow (saving one's own OAuth2 client) and never by an
     * admin toggle, so there's no scenario where we'd want to insert a
     * tenant-scoped placeholder. Because this path cannot create rows, the
     * whitelist/normalize hardening applied to the platform-wide overload is
     * not needed here - a typo simply matches 0 rows and returns false.
     */
    public boolean setEnabledForVariant(String integrationName, String variant, boolean enabled, String tenantId) {
        String sql = """
            UPDATE auth.platform_credentials
            SET is_enabled = ?, updated_at = CURRENT_TIMESTAMP
            WHERE LOWER(integration_name) = LOWER(?) AND variant = ? AND tenant_id = ?
            """;
        int rows = jdbc.update(sql, enabled, integrationName, variant, tenantId);
        return rows > 0;
    }

    /**
     * Find a platform credential by the V103 UNIQUE key
     * {@code (integration_name, variant)} - platform-wide only. Used by the
     * admin per-variant toggle to confirm the variant exists before responding
     * with 404 on an unknown variant name.
     */
    public Optional<PlatformCredential> findByIntegrationNameAndVariant(String integrationName, String variant) {
        String sql = """
            SELECT * FROM auth.platform_credentials
            WHERE LOWER(integration_name) = LOWER(?) AND variant = ? AND tenant_id IS NULL
            """;
        return jdbc.query(sql, new PlatformCredentialRowMapper(), integrationName, variant)
                .stream()
                .findFirst();
    }

    // ========== Endpoint Methods ==========

    /**
     * Find endpoints for a platform credential.
     */
    public List<PlatformCredentialEndpoint> findEndpointsByCredentialId(Long credentialId) {
        String sql = """
            SELECT * FROM auth.platform_credential_endpoints
            WHERE platform_credential_id = ?
            ORDER BY tool_name
            """;
        return jdbc.query(sql, new EndpointRowMapper(), credentialId);
    }

    /**
     * Save an endpoint status.
     */
    public PlatformCredentialEndpoint saveEndpoint(PlatformCredentialEndpoint endpoint) {
        String sql = """
            INSERT INTO auth.platform_credential_endpoints (
                platform_credential_id, tool_id, tool_name, is_enabled
            ) VALUES (?, ?, ?, ?)
            ON CONFLICT (platform_credential_id, tool_id)
            DO UPDATE SET is_enabled = EXCLUDED.is_enabled, updated_at = CURRENT_TIMESTAMP
            RETURNING id
            """;
        Long id = jdbc.queryForObject(sql, Long.class,
                endpoint.platformCredentialId(),
                endpoint.toolId(),
                endpoint.toolName(),
                endpoint.isEnabled()
        );
        return new PlatformCredentialEndpoint(
                id,
                endpoint.platformCredentialId(),
                endpoint.toolId(),
                endpoint.toolName(),
                endpoint.isEnabled(),
                endpoint.createdAt(),
                Instant.now()
        );
    }

    /**
     * Toggle endpoint status.
     */
    public boolean toggleEndpoint(Long credentialId, String toolId, boolean enabled) {
        String sql = """
            INSERT INTO auth.platform_credential_endpoints (
                platform_credential_id, tool_id, is_enabled
            ) VALUES (?, ?, ?)
            ON CONFLICT (platform_credential_id, tool_id)
            DO UPDATE SET is_enabled = EXCLUDED.is_enabled, updated_at = CURRENT_TIMESTAMP
            """;
        int rows = jdbc.update(sql, credentialId, toolId, enabled);
        return rows > 0;
    }

    /**
     * Check if an endpoint is enabled.
     */
    public boolean isEndpointEnabled(Long credentialId, String toolId) {
        String sql = """
            SELECT COALESCE(
                (SELECT is_enabled FROM auth.platform_credential_endpoints
                 WHERE platform_credential_id = ? AND tool_id = ?),
                true
            )
            """;
        Boolean enabled = jdbc.queryForObject(sql, Boolean.class, credentialId, toolId);
        return enabled != null && enabled;
    }

    // ========== Helper Methods ==========

    private String encryptIfNotNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return encryptionService.encrypt(value);
    }

    private String decryptIfNotNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(value);
    }

    private Map<String, String> parseCustomFields(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse custom_fields JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private PGobject toJsonbPGobject(Map<String, String> map) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(map != null && !map.isEmpty() ? objectMapper.writeValueAsString(map) : "{}");
            return pg;
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize custom_fields to JSONB", e);
        }
    }

    // ========== Row Mappers ==========

    private class PlatformCredentialRowMapper implements RowMapper<PlatformCredential> {
        @Override
        public PlatformCredential mapRow(ResultSet rs, int rowNum) throws SQLException {
            BigDecimal markup = rs.getBigDecimal("default_markup_credits");
            int maxCalls = rs.getInt("max_calls_per_run");
            // V103 variant column - NOT NULL DEFAULT 'primary' in schema, but legacy
            // rows or tests may still surface null; fall back to the default so the
            // record's invariants hold.
            String variant = rs.getString("variant");
            if (variant == null || variant.isBlank()) {
                variant = PlatformCredential.DEFAULT_VARIANT;
            }
            return new PlatformCredential(
                    rs.getLong("id"),
                    rs.getString("integration_name"),
                    rs.getString("display_name"),
                    AuthType.fromValue(rs.getString("auth_type")),
                    rs.getString("client_id"),
                    decryptIfNotNull(rs.getString("client_secret")),
                    decryptIfNotNull(rs.getString("api_key")),
                    rs.getString("username"),
                    decryptIfNotNull(rs.getString("password")),
                    rs.getString("auth_url"),
                    rs.getString("token_url"),
                    rs.getString("default_scopes"),
                    rs.getString("icon_slug"),
                    rs.getString("category"),
                    rs.getString("description"),
                    rs.getBoolean("show_unverified_app_warning"),
                    rs.getBoolean("is_enabled"),
                    parseCustomFields(rs.getString("custom_fields")),
                    markup != null ? markup : BigDecimal.ZERO,
                    maxCalls,
                    toInstant(rs.getObject("created_at")),
                    toInstant(rs.getObject("updated_at")),
                    rs.getString("created_by"),
                    rs.getString("tenant_id"),
                    variant,
                    rs.getString("organization_id")
            );
        }
    }

    private static class EndpointRowMapper implements RowMapper<PlatformCredentialEndpoint> {
        @Override
        public PlatformCredentialEndpoint mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PlatformCredentialEndpoint(
                    rs.getLong("id"),
                    rs.getLong("platform_credential_id"),
                    rs.getString("tool_id"),
                    rs.getString("tool_name"),
                    rs.getBoolean("is_enabled"),
                    toInstant(rs.getObject("created_at")),
                    toInstant(rs.getObject("updated_at"))
            );
        }
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        return null;
    }
}
