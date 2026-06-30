package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.auth.credential.domain.CredentialModels.*;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.web.TenantResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Repository for user credentials. Uses JdbcTemplate for direct SQL access
 * to the auth.credentials table with encryption of sensitive fields.
 */
@Repository
public class CredentialRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;
    private final CredentialEncryptionService encryptionService;

    public CredentialRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc,
                                ObjectMapper objectMapper, CredentialEncryptionService encryptionService) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.objectMapper = objectMapper;
        this.encryptionService = encryptionService;
    }

    public Credential save(Credential credential) {
        try {
            // Encrypt sensitive fields before persisting
            Map<String, Object> rawData = credential.credentialData() != null ? credential.credentialData() : Map.of();
            Map<String, Object> encryptedData = encryptionService.encryptSensitiveFields(rawData);
            String credentialDataJson = objectMapper.writeValueAsString(encryptedData);

            if (credential.id() == null) {
                // Insert
                String sql = """
                    INSERT INTO auth.credentials (tenant_id, organization_id, name, integration, type, environment, status, description, credential_data, scopes, tags, owner, icon_url, is_default, last_used, created_at, updated_at)
                    VALUES (:tenant_id, :organization_id, :name, :integration, :type, :environment, :status, :description, :credential_data::jsonb, :scopes, :tags, :owner, :icon_url, :is_default, :last_used, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """;

                SqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenant_id", credential.tenantId())
                    .addValue("organization_id", credential.organizationId())
                    .addValue("name", credential.name())
                    .addValue("integration", credential.integration())
                    .addValue("type", credential.type().getDisplayName())
                    .addValue("environment", credential.environment().name())
                    .addValue("status", credential.status().name())
                    .addValue("description", credential.description())
                    .addValue("credential_data", credentialDataJson)
                    .addValue("scopes", credential.scopes() != null ? credential.scopes().toArray(new String[0]) : new String[0])
                    .addValue("tags", credential.tags() != null ? credential.tags().toArray(new String[0]) : new String[0])
                    .addValue("owner", credential.owner())
                    .addValue("icon_url", credential.iconUrl())
                    .addValue("is_default", credential.isDefault())
                    .addValue("last_used", credential.lastUsed() != null ? Timestamp.from(credential.lastUsed()) : null);

                KeyHolder keyHolder = new GeneratedKeyHolder();
                namedJdbc.update(sql, params, keyHolder, new String[]{"id"});

                Long id = keyHolder.getKeyAs(Long.class);
                return credential.withId(id);
            } else {
                // Update
                String sql = """
                    UPDATE auth.credentials
                    SET name = :name, integration = :integration, type = :type, environment = :environment,
                        status = :status, description = :description, credential_data = :credential_data::jsonb,
                        scopes = :scopes, tags = :tags, owner = :owner, icon_url = :icon_url, is_default = :is_default, last_used = :last_used, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :id AND tenant_id = :tenant_id
                    """;

                SqlParameterSource params = new MapSqlParameterSource()
                    .addValue("id", credential.id())
                    .addValue("tenant_id", credential.tenantId())
                    .addValue("name", credential.name())
                    .addValue("integration", credential.integration())
                    .addValue("type", credential.type().getDisplayName())
                    .addValue("environment", credential.environment().name())
                    .addValue("status", credential.status().name())
                    .addValue("description", credential.description())
                    .addValue("credential_data", credentialDataJson)
                    .addValue("scopes", credential.scopes() != null ? credential.scopes().toArray(new String[0]) : new String[0])
                    .addValue("tags", credential.tags() != null ? credential.tags().toArray(new String[0]) : new String[0])
                    .addValue("owner", credential.owner())
                    .addValue("icon_url", credential.iconUrl())
                    .addValue("is_default", credential.isDefault())
                    .addValue("last_used", credential.lastUsed() != null ? Timestamp.from(credential.lastUsed()) : null);

                namedJdbc.update(sql, params);
                return credential;
            }
        } catch (Exception e) {
            throw new RuntimeException("Error saving credential", e);
        }
    }

    /**
     * Stamp {@code last_used = now()} on a credential without re-encrypting its
     * data or moving {@code updated_at}. Recorded at execution time so the
     * settings "Last used" column reflects real workflow/agent use - not only the
     * OAuth2-refresh path. A targeted single-column UPDATE on purpose: routing this
     * through {@link #save} would re-encrypt {@code credential_data} and bump
     * {@code updated_at}, making a mere use look like an edit. {@code last_used} is
     * informational only (no WHERE clause / business logic keys on it), so a bare
     * write here has no side effects.
     *
     * @return rows updated (0 when the id no longer exists)
     */
    public int touchLastUsed(Long id) {
        if (id == null) {
            return 0;
        }
        return jdbc.update("UPDATE auth.credentials SET last_used = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public Optional<Credential> findById(Long id) {
        String sql = "SELECT * FROM auth.credentials WHERE id = ?";
        return jdbc.query(sql, new CredentialRowMapper(), id)
            .stream()
            .findFirst();
    }

    public List<Credential> findByTenantId(String tenantId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        String sql = """
            SELECT * FROM auth.credentials
            WHERE tenant_id = ?
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;
        return jdbc.query(sql, new CredentialRowMapper(), tenantId, pageSize, offset);
    }

    public int countByTenantId(String tenantId) {
        String sql = "SELECT COUNT(*) FROM auth.credentials WHERE tenant_id = ?";
        return jdbc.queryForObject(sql, Integer.class, tenantId);
    }

    public List<Credential> findByTenantIdAndStatus(String tenantId, CredentialStatus status, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        String sql = """
            SELECT * FROM auth.credentials
            WHERE tenant_id = ? AND status = ?
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;
        return jdbc.query(sql, new CredentialRowMapper(), tenantId, status.name(), pageSize, offset);
    }

    public int countByTenantIdAndStatus(String tenantId, CredentialStatus status) {
        String sql = "SELECT COUNT(*) FROM auth.credentials WHERE tenant_id = ? AND status = ?";
        return jdbc.queryForObject(sql, Integer.class, tenantId, status.name());
    }

    public Optional<Credential> findByTenantIdAndName(String tenantId, String name) {
        String sql = "SELECT * FROM auth.credentials WHERE tenant_id = ? AND name = ?";
        return jdbc.query(sql, new CredentialRowMapper(), tenantId, name)
            .stream()
            .findFirst();
    }

    public void deleteById(Long id) {
        String sql = "DELETE FROM auth.credentials WHERE id = ?";
        jdbc.update(sql, id);
    }

    /**
     * Distinct {@code integration} values across ACTIVE credentials for one tenant.
     *
     * <p>Agent-facing tools call this once per list-action and intersect locally with
     * each workflow's required-integration set. Cheap (single DISTINCT scan), avoids
     * the N+1 trap of calling {@code findByTenantIdAndIntegration} per integration
     * per item.
     *
     * <p><b>Status filter:</b> {@code 'active'} only - {@code 'expiring'} is intentionally
     * excluded. This answers the agent-facing "is the integration configured AND currently
     * usable" question; an expiring token is one refresh away from working but is degraded
     * right now. Sibling cascade-revoke queries use {@code IN ('active','expiring')} because
     * their question is "which rows must be transitioned"; do not widen this method to match
     * - it would silently flip {@code requirements.integrations[].configured} semantics.
     */
    public java.util.Set<String> findActiveIntegrationsByTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return java.util.Set.of();
        String sql = """
            SELECT DISTINCT LOWER(integration)
            FROM auth.credentials
            WHERE tenant_id = ? AND status = 'active' AND integration IS NOT NULL
            """;
        return new java.util.HashSet<>(jdbc.queryForList(sql, String.class, tenantId));
    }

    /**
     * Org-scoped counterpart of {@link #findActiveIntegrationsByTenantId}: distinct
     * ACTIVE integration names across ALL members of the workspace. Used so the
     * agent-facing {@code requirements.integrations[].configured} flag reflects
     * workspace-shared credentials (a workflow running in the org can use any
     * member's shared credential - same model as the org-aware token resolution).
     *
     * <p>Same {@code status='active'} filter as the tenant variant - keep them in
     * lock-step so "configured" means the same thing in both scopes.
     */
    public java.util.Set<String> findActiveIntegrationsByOrganizationId(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) return java.util.Set.of();
        String sql = """
            SELECT DISTINCT LOWER(integration)
            FROM auth.credentials
            WHERE organization_id = ? AND status = 'active' AND integration IS NOT NULL
            """;
        return new java.util.HashSet<>(jdbc.queryForList(sql, String.class, organizationId));
    }

    public List<Credential> findByTenantIdAndIntegration(String tenantId, String integration) {
        String sql = """
            SELECT * FROM auth.credentials
            WHERE tenant_id = ? AND LOWER(integration) = LOWER(?)
            ORDER BY is_default DESC, created_at DESC
            """;
        return jdbc.query(sql, new CredentialRowMapper(), tenantId, integration);
    }

    public List<Credential> findAllByTenantId(String tenantId) {
        String sql = """
            SELECT * FROM auth.credentials
            WHERE tenant_id = ?
            ORDER BY integration, is_default DESC, name
            """;
        return jdbc.query(sql, new CredentialRowMapper(), tenantId);
    }

    /**
     * Find the default credential for a specific integration.
     */
    public Optional<Credential> findDefaultByTenantIdAndIntegration(String tenantId, String integration) {
        String sql = """
            SELECT * FROM auth.credentials
            WHERE tenant_id = ? AND LOWER(integration) = LOWER(?) AND is_default = TRUE
            LIMIT 1
            """;
        return jdbc.query(sql, new CredentialRowMapper(), tenantId, integration)
            .stream()
            .findFirst();
    }

    /**
     * Set a credential as default, clearing any other defaults for the same integration.
     */
    public void setAsDefault(String tenantId, Long credentialId) {
        // First, get the integration of this credential
        Optional<Credential> credOpt = findById(credentialId);
        if (credOpt.isEmpty()) {
            throw new IllegalArgumentException("Credential not found: " + credentialId);
        }
        Credential cred = credOpt.get();
        if (!cred.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Credential does not belong to tenant");
        }

        // Clear all defaults for this integration
        String clearSql = """
            UPDATE auth.credentials
            SET is_default = FALSE, updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ? AND integration = ? AND is_default = TRUE
            """;
        jdbc.update(clearSql, tenantId, cred.integration());

        // Set this credential as default
        String setSql = """
            UPDATE auth.credentials
            SET is_default = TRUE, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND tenant_id = ?
            """;
        jdbc.update(setSql, credentialId, tenantId);
    }

    /**
     * Clear default status for a credential.
     */
    public void clearDefault(String tenantId, Long credentialId) {
        String sql = """
            UPDATE auth.credentials
            SET is_default = FALSE, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND tenant_id = ?
            """;
        jdbc.update(sql, credentialId, tenantId);
    }

    /**
     * PR19 - scope-aware default-clear. Updates the row identified by
     * {@code credentialId} only when its scope matches the caller's active
     * workspace, preventing cross-scope mutation.
     *
     * <p>Post-V261: every user-scoped row has a non-null {@code organization_id}
     * (gateway always injects {@code X-Organization-ID}; personal workspace
     * resolves to the user's {@code is_default=true} personal org). The
     * previous {@code organization_id IS NULL} branch is removed.
     */
    public void clearDefaultInScope(String tenantId, String organizationId, Long credentialId) {
        TenantResolver.requireOrgId(organizationId);
        String sql = "UPDATE auth.credentials SET is_default = FALSE, updated_at = CURRENT_TIMESTAMP "
                   + "WHERE id = ? AND organization_id = ?";
        jdbc.update(sql, credentialId, organizationId);
    }

    /**
     * PR19 - scope-aware set-as-default. Clears existing defaults in the SAME
     * scope (not across scopes - that was the audit-1 finding L1), then sets
     * the target as default. Org-scope defaults stay org-scope.
     *
     * <p>Post-V261: every user-scoped row has a non-null {@code organization_id}
     * (gateway always injects {@code X-Organization-ID}; personal workspace
     * resolves to the user's {@code is_default=true} personal org). The
     * previous {@code organization_id IS NULL} branch is removed.
     */
    public void setAsDefaultInScope(String tenantId, String organizationId, Long credentialId) {
        TenantResolver.requireOrgId(organizationId);
        Optional<Credential> credOpt = findById(credentialId);
        if (credOpt.isEmpty()) {
            throw new IllegalArgumentException("Credential not found: " + credentialId);
        }
        Credential cred = credOpt.get();
        // Defense in depth - the service already gated on matchesScope, but
        // re-assert here so a future caller that bypasses the service can't
        // mutate cross-scope.
        if (!organizationId.equals(cred.organizationId())) {
            throw new IllegalArgumentException("Credential not in active org scope");
        }

        // Clear sibling defaults - narrowed to the active scope.
        String clearSql = """
            UPDATE auth.credentials
            SET is_default = FALSE, updated_at = CURRENT_TIMESTAMP
            WHERE organization_id = ? AND integration = ? AND is_default = TRUE
            """;
        jdbc.update(clearSql, organizationId, cred.integration());

        String setSql = """
            UPDATE auth.credentials
            SET is_default = TRUE, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;
        int updated = jdbc.update(setSql, credentialId);
        if (updated == 0) {
            // The row passed the findById guard above but was deleted by a
            // concurrent request before this UPDATE ran (e.g. a parallel
            // bulk-delete of the same scope). Surface it as not-found so the
            // caller can pick a different fallback instead of silently leaving
            // the scope without a default.
            throw new IllegalArgumentException("Credential not found: " + credentialId);
        }
    }

    /**
     * PR19 - scope-isolated count for the "only credential for integration"
     * guard. Counts ONLY rows in the same scope. The legacy tenant-only
     * exact-match counter was deleted (zero callers across backend);
     * this scope-aware variant is the single supported path.
     *
     * <p>Post-V261: every user-scoped row has a non-null {@code organization_id}.
     * The previous {@code organization_id IS NULL} branch is removed.
     */
    public int countByScopeAndIntegrationExact(String tenantId, String organizationId, String integration) {
        TenantResolver.requireOrgId(organizationId);
        String sql = "SELECT COUNT(*) FROM auth.credentials WHERE organization_id = ? AND integration = ?";
        Integer count = jdbc.queryForObject(sql, Integer.class, organizationId, integration);
        return count != null ? count : 0;
    }

    /**
     * Delete all credentials for a given integration name (across all tenants).
     * Used when an API's auth type changes during catalog reimport.
     *
     * @return the number of deleted rows
     */
    public int deleteByIntegration(String integration) {
        String sql = "DELETE FROM auth.credentials WHERE integration = ?";
        return jdbc.update(sql, integration);
    }

    /**
     * Find ACTIVE/EXPIRING credentials by tenant whose {@code integration} field, when
     * normalized to {@code [a-z0-9]} only, equals the supplied {@code normalizedIntegration}.
     *
     * <p>Required by the BYOK cascade-revoke flow: {@code auth.platform_credentials.integration_name}
     * is stored normalized (no separators, no case), but {@code auth.credentials.integration}
     * is stored RAW (the iconSlug from the catalog template, e.g. {@code "audit-tracking"} or
     * {@code "azure_translator"}). An EXACT tenant_id+integration match would silently miss every
     * dependent row for an integration whose iconSlug contains a separator. The SQL strips
     * non-alphanumeric and lowercases the stored value so it matches the BYOK row's normalized form.
     *
     * <p>Filters to {@code active}/{@code expiring} so the cascade only counts what will
     * actually transition; rows already in {@code error} or {@code needs_reauth} are
     * skipped (idempotent - they're already in the desired terminal state).
     */
    public List<Credential> findActiveByTenantIdAndIntegrationNormalized(String tenantId, String normalizedIntegration) {
        String sql = """
            SELECT * FROM auth.credentials
            WHERE tenant_id = ?
              AND lower(regexp_replace(integration, '[^a-zA-Z0-9]', '', 'g')) = ?
              AND status IN ('active', 'expiring')
            ORDER BY is_default DESC, created_at DESC
            """;
        return jdbc.query(sql, new CredentialRowMapper(), tenantId, normalizedIntegration);
    }

    /**
     * Count ACTIVE/EXPIRING credentials by tenant matching the same normalized-integration
     * predicate as {@link #findActiveByTenantIdAndIntegrationNormalized}. Used by
     * {@code GET /my/{name}/delete-impact} so the displayed count matches what the cascade
     * will actually transition (no over-reporting on already-terminal rows).
     */
    public int countActiveByTenantIdAndIntegrationNormalized(String tenantId, String normalizedIntegration) {
        String sql = """
            SELECT COUNT(*) FROM auth.credentials
            WHERE tenant_id = ?
              AND lower(regexp_replace(integration, '[^a-zA-Z0-9]', '', 'g')) = ?
              AND status IN ('active', 'expiring')
            """;
        Integer count = jdbc.queryForObject(sql, Integer.class, tenantId, normalizedIntegration);
        return count != null ? count : 0;
    }

    /**
     * Find OAuth2 credentials whose access token expires before {@code threshold} and which
     * are actually eligible for a proactive refresh right now.
     *
     * <p>Eligibility rules (all server-side so the scheduler never decrypts rows it would
     * skip anyway):
     * <ul>
     *   <li>{@code type = 'OAuth2'} - only credentials owned by this refresh pipeline.</li>
     *   <li>{@code status NOT IN ('error','needs_reauth')} - terminal buckets are frozen.
     *       {@code error} = TERMINAL_CONFIG (admin must fix template/secret/scope);
     *       {@code needs_reauth} = TERMINAL_USER (user must re-OAuth). Retrying either bucket
     *       before human intervention would just burn provider quota and risk account flags.
     *       Only {@code active} and {@code expiring} pass.</li>
     *   <li>{@code expires_at} and {@code refresh_token} both present - cannot proactively
     *       refresh without an expiry hint or without a refresh_token.</li>
     *   <li>{@code refresh_cooldown_until} is either absent OR in the past - a recent
     *       transient failure (5xx, 429, Retry-After) put the credential in a jitter-scheduled
     *       cooldown window; skipping it prevents the scheduler from stepping on the
     *       per-credential backoff written by {@code OAuth2Service.releaseTransient}.</li>
     * </ul>
     */
    /**
     * Aggregate count of OAuth2 credentials stuck in a terminal bucket - used by the
     * {@code oauth2_credentials_in_error_state} Prometheus gauge so dashboards can alert on
     * "N users with broken integrations" without having to enumerate them.
     *
     * <p>Cheap: single indexed COUNT(*), no JSONB parsing, no decrypt. The
     * {@code idx_auth_credentials_active_status} partial index on status covers the inverse
     * (active + expiring) - but the set of rows NOT in that index is exactly what we want,
     * and Postgres will still prefer a bitmap heap scan on the status predicate because the
     * column is LOW CARDINALITY.
     */
    public long countOAuth2CredentialsInTerminalState() {
        String sql = """
            SELECT COUNT(*) FROM auth.credentials
            WHERE type = 'OAuth2'
              AND status IN ('error', 'needs_reauth')
            """;
        Long count = jdbc.queryForObject(sql, Long.class);
        return count != null ? count : 0L;
    }

    // ============================================================
    // PR19 - Strict-isolation org-scope finders.
    //
    // Post-V261 sweep: every user-scoped credential row has a non-null
    // organization_id. Personal workspace resolves to the user's
    // is_default=true personal org UUID; gateway always injects
    // X-Organization-ID. The legacy "organization_id IS NULL" personal-scope
    // finders are removed - single org-scope path:
    //   - Org scope: WHERE organization_id = :orgId  (cross-tenant within the org)
    //
    // Org membership is enforced upstream by the gateway (X-Organization-ID is
    // only injected if the JWT-validated user is a member of the claimed org).
    // The repository trusts the org_id it receives.
    // ============================================================

    /**
     * Strict org-scope page listing. Returns rows belonging to {@code orgId}
     * regardless of which member created them.
     */
    public List<Credential> findByOrganizationIdStrict(String organizationId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        String sql = """
            SELECT * FROM auth.credentials
            WHERE organization_id = ?
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;
        return jdbc.query(sql, new CredentialRowMapper(), organizationId, pageSize, offset);
    }

    public int countByOrganizationIdStrict(String organizationId) {
        String sql = "SELECT COUNT(*) FROM auth.credentials WHERE organization_id = ?";
        Integer count = jdbc.queryForObject(sql, Integer.class, organizationId);
        return count != null ? count : 0;
    }

    public List<Credential> findByOrganizationIdAndStatusStrict(String organizationId,
                                                                CredentialStatus status,
                                                                int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        String sql = """
            SELECT * FROM auth.credentials
            WHERE organization_id = ? AND status = ?
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;
        return jdbc.query(sql, new CredentialRowMapper(), organizationId, status.name(), pageSize, offset);
    }

    public int countByOrganizationIdAndStatusStrict(String organizationId, CredentialStatus status) {
        String sql = "SELECT COUNT(*) FROM auth.credentials WHERE organization_id = ? AND status = ?";
        Integer count = jdbc.queryForObject(sql, Integer.class, organizationId, status.name());
        return count != null ? count : 0;
    }

    /**
     * Scope-aware {@code findByIntegration}. Filters
     * {@code organization_id = orgScope} only.
     *
     * <p>Used by {@link com.apimarketplace.auth.credential.service.CredentialService}
     * when deciding whether a new credential should become the default for its
     * integration - defaults are scope-isolated.
     *
     * <p>Post-V261: {@code orgScope} is required (every user-scoped row has a
     * non-null {@code organization_id}; personal workspace resolves to the
     * user's {@code is_default=true} personal org). The previous
     * {@code organization_id IS NULL} branch is removed.</p>
     */
    public List<Credential> findByScopeAndIntegration(String tenantId, String orgScope, String integration) {
        TenantResolver.requireOrgId(orgScope);
        String sql = """
            SELECT * FROM auth.credentials
            WHERE organization_id = ? AND LOWER(integration) = LOWER(?)
            ORDER BY is_default DESC, created_at DESC
            """;
        return jdbc.query(sql, new CredentialRowMapper(), orgScope, integration);
    }

    /**
     * Opaque version of the credential rows visible to one execution context:
     * the user's own rows (any scope) plus the active workspace's shared rows -
     * the exact superset {@code InternalCredentialService.findCredential} can
     * resolve from. Computed as {@code "<count>:<maxUpdatedAtEpochMillis>"}.
     *
     * <p>Any mutation that can change credential resolution moves the version:
     * connect (count+max), delete (count), set-as-default / clear-default
     * ({@code setAsDefaultInScope}/{@code clearDefaultInScope} stamp
     * {@code updated_at} on every touched row), credential edits and OAuth
     * token refreshes (row save stamps {@code updated_at} - busts slightly
     * more often than strictly needed, which is safe).
     *
     * <p>Consumed by catalog-service's agent response cache so a cached tool
     * response can never outlive a credential switch (the "set as default
     * ignored by the chat agent" bug). The value is a cache-key component,
     * never parsed.
     */
    public String computeStateVersion(String tenantId, String organizationId) {
        boolean hasOrg = organizationId != null && !organizationId.isBlank();
        String sql = hasOrg
                ? """
                  SELECT COUNT(*) AS cnt,
                         COALESCE((EXTRACT(EPOCH FROM MAX(updated_at)) * 1000)::bigint, 0) AS max_ts
                  FROM auth.credentials
                  WHERE tenant_id = ? OR organization_id = ?
                  """
                : """
                  SELECT COUNT(*) AS cnt,
                         COALESCE((EXTRACT(EPOCH FROM MAX(updated_at)) * 1000)::bigint, 0) AS max_ts
                  FROM auth.credentials
                  WHERE tenant_id = ?
                  """;
        Object[] args = hasOrg
                ? new Object[]{tenantId, organizationId}
                : new Object[]{tenantId};
        return jdbc.queryForObject(sql,
                (rs, rowNum) -> rs.getLong("cnt") + ":" + rs.getLong("max_ts"),
                args);
    }

    public List<Credential> findOAuth2CredentialsExpiringBefore(Instant threshold) {
        // NOTE: JSONB key-existence operator "?" collides with JDBC parameter placeholders.
        // Use jsonb_exists(...) instead - equivalent semantics, no escaping required.
        String sql = """
            SELECT * FROM auth.credentials
            WHERE type = 'OAuth2'
              AND status NOT IN ('error', 'needs_reauth')
              AND jsonb_exists(credential_data, 'expires_at')
              AND jsonb_exists(credential_data, 'refresh_token')
              AND (credential_data->>'expires_at')::timestamptz < ?
              AND coalesce(
                    (credential_data->>'refresh_cooldown_until')::timestamptz,
                    '1970-01-01 00:00:00+00'::timestamptz
                  ) < now()
            ORDER BY (credential_data->>'expires_at')::timestamptz ASC
            """;
        return jdbc.query(sql, new CredentialRowMapper(), Timestamp.from(threshold));
    }

    private class CredentialRowMapper implements RowMapper<Credential> {
        @Override
        public Credential mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                // Parse arrays (handle null arrays)
                List<String> scopes = List.of();
                java.sql.Array scopesArray = rs.getArray("scopes");
                if (scopesArray != null) {
                    Object[] scopesObj = (Object[]) scopesArray.getArray();
                    if (scopesObj != null) {
                        scopes = Arrays.stream(scopesObj)
                            .map(obj -> obj != null ? obj.toString() : "")
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                    }
                }

                List<String> tags = List.of();
                java.sql.Array tagsArray = rs.getArray("tags");
                if (tagsArray != null) {
                    Object[] tagsObj = (Object[]) tagsArray.getArray();
                    if (tagsObj != null) {
                        tags = Arrays.stream(tagsObj)
                            .map(obj -> obj != null ? obj.toString() : "")
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                    }
                }

                // Parse JSONB and decrypt sensitive fields
                Map<String, Object> credentialData = Map.of();
                if (rs.getString("credential_data") != null) {
                    Map<String, Object> rawData = objectMapper.readValue(rs.getString("credential_data"), new TypeReference<Map<String, Object>>() {});
                    credentialData = encryptionService.decryptSensitiveFields(rawData);
                }

                // Parse type (handle spaces)
                String typeStr = rs.getString("type");
                CredentialType type = CredentialType.fromString(typeStr);

                // Parse timestamps
                OffsetDateTime lastUsed = rs.getObject("last_used", OffsetDateTime.class);
                OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);

                return new Credential(
                    rs.getLong("id"),
                    rs.getString("tenant_id"),
                    rs.getString("organization_id"),
                    rs.getString("name"),
                    rs.getString("integration"),
                    type,
                    CredentialEnvironment.valueOf(rs.getString("environment")),
                    CredentialStatus.valueOf(rs.getString("status")),
                    rs.getString("description"),
                    credentialData,
                    scopes,
                    tags,
                    rs.getString("owner"),
                    rs.getString("icon_url"),
                    rs.getBoolean("is_default"),
                    lastUsed != null ? lastUsed.toInstant() : null,
                    createdAt != null ? createdAt.toInstant() : Instant.now(),
                    updatedAt != null ? updatedAt.toInstant() : Instant.now()
                );
            } catch (Exception e) {
                throw new RuntimeException("Error mapping Credential", e);
            }
        }
    }
}
