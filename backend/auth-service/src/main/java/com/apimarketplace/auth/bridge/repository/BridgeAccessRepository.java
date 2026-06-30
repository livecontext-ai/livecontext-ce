package com.apimarketplace.auth.bridge.repository;

import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.AccessMode;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.BridgeAccessAllowlistEntry;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.BridgeAccessPolicy;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.UsageStat;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access to {@code auth.bridge_access_policy},
 * {@code auth.bridge_access_allowlist}, and {@code auth.bridge_usage_log}.
 *
 * <p>No JPA - sticks to the pattern already used by
 * {@code PlatformCredentialRepository}.
 */
@Repository
public class BridgeAccessRepository {

    private final JdbcTemplate jdbc;

    public BridgeAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Policy
    // ---------------------------------------------------------------------

    private static final RowMapper<BridgeAccessPolicy> POLICY_MAPPER = (ResultSet rs, int rowNum) -> {
        Timestamp updated = rs.getTimestamp("updated_at");
        int dailyRaw = rs.getInt("max_requests_per_user_per_day");
        Integer daily = rs.wasNull() ? null : dailyRaw;
        return new BridgeAccessPolicy(
                rs.getLong("id"),
                rs.getString("bridge_provider"),
                AccessMode.fromDb(rs.getString("access_mode")),
                daily,
                updated != null ? updated.toInstant() : null,
                rs.getString("updated_by")
        );
    };

    public List<BridgeAccessPolicy> findAllPolicies() {
        return jdbc.query(
                "SELECT id, bridge_provider, access_mode, max_requests_per_user_per_day, " +
                        "updated_at, updated_by " +
                        "FROM auth.bridge_access_policy ORDER BY bridge_provider",
                POLICY_MAPPER);
    }

    public Optional<BridgeAccessPolicy> findByBridgeProvider(String bridgeProvider) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, bridge_provider, access_mode, max_requests_per_user_per_day, " +
                            "updated_at, updated_by " +
                            "FROM auth.bridge_access_policy WHERE bridge_provider = ?",
                    POLICY_MAPPER, bridgeProvider));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * UPSERT - V118 seeds the 4 known bridges, but custom bridges added by a
     * future factory can self-register with an admin-configurable default.
     */
    public void upsertPolicy(String bridgeProvider,
                             AccessMode accessMode,
                             Integer maxRequestsPerUserPerDay,
                             String updatedBy) {
        jdbc.update(
                "INSERT INTO auth.bridge_access_policy " +
                        "(bridge_provider, access_mode, max_requests_per_user_per_day, updated_by) " +
                        "VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT (bridge_provider) DO UPDATE SET " +
                        "  access_mode = EXCLUDED.access_mode, " +
                        "  max_requests_per_user_per_day = EXCLUDED.max_requests_per_user_per_day, " +
                        "  updated_by = EXCLUDED.updated_by, " +
                        "  updated_at = NOW()",
                bridgeProvider,
                accessMode.dbValue(),
                maxRequestsPerUserPerDay,
                updatedBy);
    }

    // ---------------------------------------------------------------------
    // Allowlist
    // ---------------------------------------------------------------------

    private static final RowMapper<BridgeAccessAllowlistEntry> ALLOWLIST_MAPPER = (ResultSet rs, int rowNum) -> {
        Timestamp granted = rs.getTimestamp("granted_at");
        return new BridgeAccessAllowlistEntry(
                rs.getLong("policy_id"),
                rs.getString("user_id"),
                granted != null ? granted.toInstant() : null,
                rs.getString("granted_by")
        );
    };

    public List<BridgeAccessAllowlistEntry> findAllowlist(long policyId) {
        return jdbc.query(
                "SELECT policy_id, user_id, granted_at, granted_by " +
                        "FROM auth.bridge_access_allowlist WHERE policy_id = ? " +
                        "ORDER BY granted_at",
                ALLOWLIST_MAPPER, policyId);
    }

    public boolean isUserAllowlisted(long policyId, String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM auth.bridge_access_allowlist " +
                        "WHERE policy_id = ? AND user_id = ?",
                Integer.class, policyId, userId);
        return count != null && count > 0;
    }

    public void addToAllowlist(long policyId, String userId, String grantedBy) {
        jdbc.update(
                "INSERT INTO auth.bridge_access_allowlist (policy_id, user_id, granted_by) " +
                        "VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                policyId, userId, grantedBy);
    }

    public boolean removeFromAllowlist(long policyId, String userId) {
        return jdbc.update(
                "DELETE FROM auth.bridge_access_allowlist WHERE policy_id = ? AND user_id = ?",
                policyId, userId) > 0;
    }

    // ---------------------------------------------------------------------
    // Usage log
    // ---------------------------------------------------------------------

    /**
     * Increment today's counter atomically. Returns the new total AFTER the
     * increment; callers compare against the daily cap to decide allow/deny.
     */
    public int incrementUsage(String userId, String bridgeProvider) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // UPSERT + RETURNING so the counter is read under the same row lock.
        Integer count = jdbc.queryForObject(
                "INSERT INTO auth.bridge_usage_log " +
                        "(user_id, bridge_provider, usage_date, requests_count, last_request_at) " +
                        "VALUES (?, ?, ?, 1, NOW()) " +
                        "ON CONFLICT (user_id, bridge_provider, usage_date) DO UPDATE SET " +
                        "  requests_count = bridge_usage_log.requests_count + 1, " +
                        "  last_request_at = NOW() " +
                        "RETURNING requests_count",
                Integer.class, userId, bridgeProvider, java.sql.Date.valueOf(today));
        return count == null ? 0 : count;
    }

    public int getUsageToday(String userId, String bridgeProvider) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT requests_count FROM auth.bridge_usage_log " +
                            "WHERE user_id = ? AND bridge_provider = ? AND usage_date = ?",
                    Integer.class, userId, bridgeProvider, java.sql.Date.valueOf(today));
            return count == null ? 0 : count;
        } catch (EmptyResultDataAccessException e) {
            return 0;
        }
    }

    public List<UsageStat> findRecentUsage(String bridgeProvider, int daysBack, int limit) {
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(daysBack);
        return jdbc.query(
                "SELECT user_id, SUM(requests_count)::int AS requests_today, MAX(last_request_at) AS last_at " +
                        "FROM auth.bridge_usage_log " +
                        "WHERE bridge_provider = ? AND usage_date >= ? " +
                        "GROUP BY user_id ORDER BY requests_today DESC LIMIT ?",
                (ResultSet rs, int rowNum) -> {
                    Timestamp t = rs.getTimestamp("last_at");
                    return new UsageStat(
                            rs.getString("user_id"),
                            rs.getInt("requests_today"),
                            t != null ? t.toInstant() : null
                    );
                },
                bridgeProvider, java.sql.Date.valueOf(since), limit);
    }
}
