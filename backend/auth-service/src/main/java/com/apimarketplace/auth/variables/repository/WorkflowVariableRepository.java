package com.apimarketplace.auth.variables.repository;

import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.ValueType;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.WorkflowVariable;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@code auth.workflow_variables}. Values are encrypted at rest
 * via {@link CredentialEncryptionService} ("ENC:" prefix) - encryption happens
 * at the SQL boundary so the rest of the service only sees plaintext.
 *
 * <p>Scope filtering is STRICT (no platform-wide fallback, unlike
 * platform_credentials): a workspace scope only sees the workspace rows, the
 * personal scope only sees the tenant's personal rows. All queries go through
 * {@link #scopeFilter(String)} to keep the two branches consistent.
 */
@Repository
public class WorkflowVariableRepository {

    private final JdbcTemplate jdbc;
    private final CredentialEncryptionService encryptionService;

    public WorkflowVariableRepository(JdbcTemplate jdbc, CredentialEncryptionService encryptionService) {
        this.jdbc = jdbc;
        this.encryptionService = encryptionService;
    }

    // Workspace scope shares rows across members (keyed by org alone); personal
    // scope is keyed by tenant with no org.
    private static String scopeFilter(String organizationId) {
        return organizationId != null
                ? "organization_id = ?"
                : "tenant_id = ? AND organization_id IS NULL";
    }

    private static Object scopeKey(String tenantId, String organizationId) {
        return organizationId != null ? organizationId : tenantId;
    }

    public List<WorkflowVariable> findAllForScope(String tenantId, String organizationId) {
        String sql = "SELECT * FROM auth.workflow_variables WHERE " + scopeFilter(organizationId)
                + " ORDER BY name ASC";
        return jdbc.query(sql, new WorkflowVariableRowMapper(), scopeKey(tenantId, organizationId));
    }

    public Optional<WorkflowVariable> findByName(String name, String tenantId, String organizationId) {
        String sql = "SELECT * FROM auth.workflow_variables WHERE name = ? AND " + scopeFilter(organizationId);
        return jdbc.query(sql, new WorkflowVariableRowMapper(), name, scopeKey(tenantId, organizationId))
                .stream().findFirst();
    }

    public Optional<WorkflowVariable> findByIdForScope(long id, String tenantId, String organizationId) {
        String sql = "SELECT * FROM auth.workflow_variables WHERE id = ? AND " + scopeFilter(organizationId);
        return jdbc.query(sql, new WorkflowVariableRowMapper(), id, scopeKey(tenantId, organizationId))
                .stream().findFirst();
    }

    public int countForScope(String tenantId, String organizationId) {
        String sql = "SELECT COUNT(*) FROM auth.workflow_variables WHERE " + scopeFilter(organizationId);
        Integer count = jdbc.queryForObject(sql, Integer.class, scopeKey(tenantId, organizationId));
        return count != null ? count : 0;
    }

    /** Inserts a new variable and returns it with its generated id. */
    public WorkflowVariable insert(WorkflowVariable variable) {
        String sql = """
                INSERT INTO auth.workflow_variables
                    (tenant_id, organization_id, name, value, value_type, is_secret, description, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, variable.tenantId());
            ps.setString(2, variable.organizationId());
            ps.setString(3, variable.name());
            ps.setString(4, encryptionService.encrypt(variable.value()));
            ps.setString(5, variable.valueType().name());
            ps.setBoolean(6, variable.secret());
            ps.setString(7, variable.description());
            ps.setString(8, variable.createdBy());
            return ps;
        }, keyHolder);
        Number id = (Number) keyHolder.getKeys().get("id");
        return new WorkflowVariable(
                id.longValue(), variable.tenantId(), variable.organizationId(), variable.name(),
                variable.value(), variable.valueType(), variable.secret(), variable.description(),
                variable.createdBy(), variable.createdAt(), variable.updatedAt());
    }

    /** Updates name/value/type/secret/description of a scope-owned row. Returns true when a row matched. */
    public boolean update(long id, String tenantId, String organizationId,
                          String name, String value, ValueType valueType, boolean secret, String description) {
        String sql = "UPDATE auth.workflow_variables SET name = ?, value = ?, value_type = ?, "
                + "is_secret = ?, description = ?, updated_at = now() WHERE id = ? AND " + scopeFilter(organizationId);
        return jdbc.update(sql, name, encryptionService.encrypt(value), valueType.name(),
                secret, description, id, scopeKey(tenantId, organizationId)) > 0;
    }

    /** Deletes a scope-owned row. Returns true when a row matched. */
    public boolean deleteByIdForScope(long id, String tenantId, String organizationId) {
        String sql = "DELETE FROM auth.workflow_variables WHERE id = ? AND " + scopeFilter(organizationId);
        return jdbc.update(sql, id, scopeKey(tenantId, organizationId)) > 0;
    }

    private class WorkflowVariableRowMapper implements RowMapper<WorkflowVariable> {
        @Override
        public WorkflowVariable mapRow(ResultSet rs, int rowNum) throws SQLException {
            ValueType type = ValueType.fromValue(rs.getString("value_type"));
            return new WorkflowVariable(
                    rs.getLong("id"),
                    rs.getString("tenant_id"),
                    rs.getString("organization_id"),
                    rs.getString("name"),
                    encryptionService.decrypt(rs.getString("value")),
                    type != null ? type : ValueType.STRING,
                    rs.getBoolean("is_secret"),
                    rs.getString("description"),
                    rs.getString("created_by"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at"))
            );
        }

        private Instant toInstant(Timestamp ts) {
            return ts != null ? ts.toInstant() : null;
        }
    }
}
