package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.domain.DataSourceModels.*;
import com.apimarketplace.common.security.CredentialEncryptionService;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Repositories pour les DataSources
public final class DataSourceRepositories {
    private DataSourceRepositories() {}

    @Repository
    public static class DataSourceRepository {
        private final JdbcTemplate jdbc;
        private final NamedParameterJdbcTemplate namedJdbc;
        private final ObjectMapper objectMapper;
        private final CredentialEncryptionService encryptionService;

        public DataSourceRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc,
                                    ObjectMapper objectMapper, CredentialEncryptionService encryptionService) {
            this.jdbc = jdbc;
            this.namedJdbc = namedJdbc;
            this.objectMapper = objectMapper;
            this.encryptionService = encryptionService;
        }

        public DataSource save(DataSource dataSource) {
            try {
                // Encrypt sensitive fields in source_config before persisting
                Map<String, Object> encryptedConfig = encryptionService.encryptSensitiveFields(dataSource.sourceConfig());
                String sourceConfigJson = objectMapper.writeValueAsString(encryptedConfig);

                if (dataSource.id() == null) {
                    // Insert avec NamedParameterJdbcTemplate pour gerer le JSON
                    String sql = """
                        INSERT INTO data_sources (tenant_id, name, description, source_type, source_config, status, created_by, column_order, mapping_spec, source_workflow_id, source_publication_id, organization_id)
                        VALUES (:tenant_id, :name, :description, :source_type, :source_config::json, :status, :created_by, :column_order::json, :mapping_spec::json, :source_workflow_id, :source_publication_id, :organization_id)
                        """;

                    SqlParameterSource params = new MapSqlParameterSource()
                        .addValue("tenant_id", dataSource.tenantId())
                        .addValue("name", dataSource.name())
                        .addValue("description", dataSource.description())
                        .addValue("source_type", dataSource.sourceType().name())
                        .addValue("source_config", sourceConfigJson)
                        .addValue("status", dataSource.status().name())
                        .addValue("created_by", dataSource.createdBy())
                        .addValue("column_order", objectMapper.writeValueAsString(dataSource.columnOrder() != null ? dataSource.columnOrder() : List.of()))
                        .addValue("mapping_spec", objectMapper.writeValueAsString(dataSource.mappingSpec() != null ? dataSource.mappingSpec() : Map.of()))
                        .addValue("source_workflow_id", dataSource.sourceWorkflowId() != null ? dataSource.sourceWorkflowId().toString() : null, java.sql.Types.OTHER)
                        .addValue("source_publication_id", dataSource.sourcePublicationId() != null ? dataSource.sourcePublicationId().toString() : null, java.sql.Types.OTHER)
                        .addValue("organization_id", dataSource.organizationId());

                    KeyHolder keyHolder = new GeneratedKeyHolder();
                    namedJdbc.update(sql, params, keyHolder, new String[]{"id"});

                    Long id = keyHolder.getKeyAs(Long.class);
                    return dataSource.withId(id);
                } else {
                    // Update
                    String sql = """
                        UPDATE data_sources
                        SET name = :name, description = :description, source_config = :source_config::json, status = :status, column_order = :column_order::json, mapping_spec = :mapping_spec::json, updated_at = :updated_at
                        WHERE id = :id
                        """;

                    SqlParameterSource params = new MapSqlParameterSource()
                        .addValue("name", dataSource.name())
                        .addValue("description", dataSource.description())
                        .addValue("source_config", sourceConfigJson)
                        .addValue("status", dataSource.status().name())
                        .addValue("column_order", objectMapper.writeValueAsString(dataSource.columnOrder() != null ? dataSource.columnOrder() : List.of()))
                        .addValue("mapping_spec", objectMapper.writeValueAsString(dataSource.mappingSpec() != null ? dataSource.mappingSpec() : Map.of()))
                        .addValue("updated_at", OffsetDateTime.now(ZoneOffset.UTC))
                        .addValue("id", dataSource.id());

                    namedJdbc.update(sql, params);
                    return dataSource;
                }
            } catch (Exception e) {
                throw new RuntimeException("Erreur lors de la sauvegarde de la DataSource", e);
            }
        }

        public Optional<DataSource> findById(Long id) {
            String sql = "SELECT * FROM data_sources WHERE id = ?";
            return jdbc.query(sql, new DataSourceRowMapper(), id)
                .stream()
                .findFirst();
        }

        public List<DataSource> findAllByIds(List<Long> ids) {
            if (ids == null || ids.isEmpty()) return List.of();
            SqlParameterSource params = new MapSqlParameterSource("ids", ids);
            String sql = "SELECT * FROM data_sources WHERE id IN (:ids)";
            return namedJdbc.query(sql, params, new DataSourceRowMapper());
        }

        /** Bulk-fetch scoped to a single org workspace - closes the cross-org leak on bulk lookups. */
        public List<DataSource> findAllByIdsAndOrganizationId(List<Long> ids, String organizationId) {
            if (ids == null || ids.isEmpty() || organizationId == null) return List.of();
            SqlParameterSource params = new MapSqlParameterSource("ids", ids)
                    .addValue("orgId", organizationId);
            String sql = "SELECT * FROM data_sources WHERE id IN (:ids) AND organization_id = :orgId";
            return namedJdbc.query(sql, params, new DataSourceRowMapper());
        }

        /** Bulk-fetch scoped to a tenant - legacy back-compat for callers without org context. */
        public List<DataSource> findAllByIdsAndTenantId(List<Long> ids, String tenantId) {
            if (ids == null || ids.isEmpty() || tenantId == null) return List.of();
            SqlParameterSource params = new MapSqlParameterSource("ids", ids)
                    .addValue("tenantId", tenantId);
            String sql = "SELECT * FROM data_sources WHERE id IN (:ids) AND tenant_id = :tenantId";
            return namedJdbc.query(sql, params, new DataSourceRowMapper());
        }

        public List<DataSource> findByTenantId(String tenantId) {
            String sql = "SELECT * FROM data_sources WHERE tenant_id = ? ORDER BY created_at DESC";
            return jdbc.query(sql, new DataSourceRowMapper(), tenantId);
        }

        public Optional<DataSource> findByTenantIdAndName(String tenantId, String name) {
            String sql = "SELECT * FROM data_sources WHERE tenant_id = ? AND name = ?";
            return jdbc.query(sql, new DataSourceRowMapper(), tenantId, name)
                .stream()
                .findFirst();
        }
        
        public Optional<DataSource> findByIdAndTenantId(Integer id, String tenantId) {
            String sql = "SELECT * FROM data_sources WHERE id = ? AND tenant_id = ?";
            return jdbc.query(sql, new DataSourceRowMapper(), id, tenantId)
                .stream()
                .findFirst();
        }

        /**
         * Strict-org single fetch. Returns a data source only if it belongs
         * to {@code organizationId}, regardless of which member owns the
         * tenant_id slot. Post-V261 every {@code data_sources} row carries a
         * non-null {@code organization_id} (Phase 6 NOT NULL); callers
         * already holding the active workspace orgId from
         * {@code X-Organization-ID} should prefer this finder over the
         * legacy tenant-only one, which leaks cross-tenant within the same
         * personal-org fallback bucket.
         */
        public Optional<DataSource> findByIdAndOrganizationIdStrict(Integer id, String organizationId) {
            String sql = "SELECT * FROM data_sources WHERE id = ? AND organization_id = ?";
            return jdbc.query(sql, new DataSourceRowMapper(), id, organizationId)
                .stream()
                .findFirst();
        }

        public void deleteById(Long id) {
            String sql = "DELETE FROM data_sources WHERE id = ?";
            jdbc.update(sql, id);
        }

        public long countByTenantId(String tenantId) {
            String sql = "SELECT COUNT(*) FROM data_sources WHERE tenant_id = ?";
            Long count = jdbc.queryForObject(sql, Long.class, tenantId);
            return count != null ? count : 0L;
        }

        public List<DataSource> findByProjectId(UUID projectId) {
            String sql = "SELECT * FROM data_sources WHERE project_id = ?::uuid";
            return jdbc.query(sql, new DataSourceRowMapper(), projectId.toString());
        }

        public List<DataSource> findByProjectIdAndTenantId(UUID projectId, String tenantId) {
            String sql = "SELECT * FROM data_sources WHERE project_id = ?::uuid AND tenant_id = ?";
            return jdbc.query(sql, new DataSourceRowMapper(), projectId.toString(), tenantId);
        }

        public List<DataSource> findByProjectIdAndOrganizationId(UUID projectId, String organizationId) {
            String sql = "SELECT * FROM data_sources WHERE project_id = ?::uuid AND organization_id = ?";
            return jdbc.query(sql, new DataSourceRowMapper(), projectId.toString(), organizationId);
        }

        public long countByProjectId(UUID projectId) {
            String sql = "SELECT COUNT(*) FROM data_sources WHERE project_id = ?::uuid";
            Long count = jdbc.queryForObject(sql, Long.class, projectId.toString());
            return count != null ? count : 0L;
        }

        public long countByProjectIdAndTenantId(UUID projectId, String tenantId) {
            String sql = "SELECT COUNT(*) FROM data_sources WHERE project_id = ?::uuid AND tenant_id = ?";
            Long count = jdbc.queryForObject(sql, Long.class, projectId.toString(), tenantId);
            return count != null ? count : 0L;
        }

        public long countByProjectIdAndOrganizationId(UUID projectId, String organizationId) {
            String sql = "SELECT COUNT(*) FROM data_sources WHERE project_id = ?::uuid AND organization_id = ?";
            Long count = jdbc.queryForObject(sql, Long.class, projectId.toString(), organizationId);
            return count != null ? count : 0L;
        }

        public void updateProjectId(Long id, UUID projectId) {
            String sql = "UPDATE data_sources SET project_id = ?::uuid, updated_at = NOW() WHERE id = ?";
            jdbc.update(sql, projectId != null ? projectId.toString() : null, id);
        }

        public int updateProjectIdAndTenantId(Long id, UUID projectId, String tenantId) {
            String sql = "UPDATE data_sources SET project_id = ?::uuid, updated_at = NOW() WHERE id = ? AND tenant_id = ?";
            return jdbc.update(sql, projectId != null ? projectId.toString() : null, id, tenantId);
        }

        public int updateProjectIdAndOrganizationId(Long id, UUID projectId, String organizationId) {
            String sql = "UPDATE data_sources SET project_id = ?::uuid, updated_at = NOW() WHERE id = ? AND organization_id = ?";
            return jdbc.update(sql, projectId != null ? projectId.toString() : null, id, organizationId);
        }

        // Post-V261 (2026-05-19): every data_sources row carries a non-null
        // organization_id; legacy OR-ownership branch was dead + cross-workspace
        // bleed. Strict-org match closes the leak. userId param preserved
        // (ignored) for back-compat; Phase 11 will rename + drop it.
        public List<DataSource> findByOrganizationOrOwner(String orgId, String userId) {
            String sql = "SELECT * FROM data_sources WHERE organization_id = ? ORDER BY created_at DESC";
            return jdbc.query(sql, new DataSourceRowMapper(), orgId);
        }

        // ===== Recent-activity aggregator (V238 partial indexes back these) =====

        /**
         * Top-N data_sources in an org workspace ordered by last edit time.
         * Backed by the V238 partial index
         * {@code idx_data_sources_org_updated_at}.
         */
        public List<DataSource> findRecentByOrganizationIdStrict(String orgId, int limit) {
            String sql = "SELECT * FROM data_sources WHERE organization_id = ? "
                    + "ORDER BY updated_at DESC LIMIT ?";
            return jdbc.query(sql, new DataSourceRowMapper(), orgId, limit);
        }

        /** Tenant-scoped name/description ILIKE search - used by paginated list endpoint. */
        public List<DataSource> searchByTenant(String tenantId, String q) {
            String sql = "SELECT * FROM data_sources WHERE tenant_id = ? "
                    + "AND (name ILIKE ? OR COALESCE(description, '') ILIKE ?) "
                    + "ORDER BY created_at DESC";
            String pattern = "%" + q + "%";
            return jdbc.query(sql, new DataSourceRowMapper(), tenantId, pattern, pattern);
        }

        public List<DataSource> findBySourceWorkflowId(UUID sourceWorkflowId) {
            String sql = "SELECT * FROM data_sources WHERE source_workflow_id = ?::uuid";
            return jdbc.query(sql, new DataSourceRowMapper(), sourceWorkflowId.toString());
        }

        /** Workflow-source fetch scoped to an org workspace. */
        public List<DataSource> findBySourceWorkflowIdAndOrganizationId(UUID sourceWorkflowId, String organizationId) {
            String sql = "SELECT * FROM data_sources WHERE source_workflow_id = ?::uuid AND organization_id = ?";
            return jdbc.query(sql, new DataSourceRowMapper(), sourceWorkflowId.toString(), organizationId);
        }

        /** Workflow-source fetch scoped to a tenant - legacy back-compat. */
        public List<DataSource> findBySourceWorkflowIdAndTenantId(UUID sourceWorkflowId, String tenantId) {
            String sql = "SELECT * FROM data_sources WHERE source_workflow_id = ?::uuid AND tenant_id = ?";
            return jdbc.query(sql, new DataSourceRowMapper(), sourceWorkflowId.toString(), tenantId);
        }

        public void deleteBySourceWorkflowId(UUID sourceWorkflowId) {
            // First delete associated items
            String deleteItemsSql = "DELETE FROM data_source_items WHERE data_source_id IN (SELECT id FROM data_sources WHERE source_workflow_id = ?::uuid)";
            jdbc.update(deleteItemsSql, sourceWorkflowId.toString());
            // Then delete the data sources
            String sql = "DELETE FROM data_sources WHERE source_workflow_id = ?::uuid";
            jdbc.update(sql, sourceWorkflowId.toString());
        }

        /**
         * Org-scoped cascade delete. Only datasources whose {@code organization_id} matches the
         * caller's workspace are deleted; their items are removed in the same statement via the
         * IN-subquery. Returns the count of deleted parent rows so callers can detect zero-effect
         * cross-org requests.
         */
        public int deleteBySourceWorkflowIdAndOrganizationId(UUID sourceWorkflowId, String organizationId) {
            String deleteItemsSql = "DELETE FROM data_source_items WHERE data_source_id IN "
                    + "(SELECT id FROM data_sources WHERE source_workflow_id = ?::uuid AND organization_id = ?)";
            jdbc.update(deleteItemsSql, sourceWorkflowId.toString(), organizationId);
            String sql = "DELETE FROM data_sources WHERE source_workflow_id = ?::uuid AND organization_id = ?";
            return jdbc.update(sql, sourceWorkflowId.toString(), organizationId);
        }

        /** Tenant-scoped cascade delete - legacy back-compat. */
        public int deleteBySourceWorkflowIdAndTenantId(UUID sourceWorkflowId, String tenantId) {
            String deleteItemsSql = "DELETE FROM data_source_items WHERE data_source_id IN "
                    + "(SELECT id FROM data_sources WHERE source_workflow_id = ?::uuid AND tenant_id = ?)";
            jdbc.update(deleteItemsSql, sourceWorkflowId.toString(), tenantId);
            String sql = "DELETE FROM data_sources WHERE source_workflow_id = ?::uuid AND tenant_id = ?";
            return jdbc.update(sql, sourceWorkflowId.toString(), tenantId);
        }


        private class DataSourceRowMapper implements RowMapper<DataSource> {
            @Override
            public DataSource mapRow(ResultSet rs, int rowNum) throws SQLException {
                try {
                    String sourceWorkflowIdStr = rs.getString("source_workflow_id");
                    UUID sourceWorkflowId = sourceWorkflowIdStr != null ? UUID.fromString(sourceWorkflowIdStr) : null;
                    String sourcePublicationIdStr = rs.getString("source_publication_id");
                    UUID sourcePublicationId = sourcePublicationIdStr != null ? UUID.fromString(sourcePublicationIdStr) : null;
                    String projectIdStr = rs.getString("project_id");
                    UUID projectId = projectIdStr != null ? UUID.fromString(projectIdStr) : null;
                    // Decrypt sensitive fields in source_config
                    Map<String, Object> rawConfig = objectMapper.readValue(rs.getString("source_config"), new TypeReference<Map<String, Object>>() {});
                    Map<String, Object> decryptedConfig = encryptionService.decryptSensitiveFields(rawConfig);

                    return new DataSource(
                        rs.getLong("id"),
                        rs.getString("tenant_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        DataSourceType.valueOf(rs.getString("source_type")),
                        decryptedConfig,
                        DataSourceStatus.valueOf(rs.getString("status")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        rs.getString("created_by"),
                        objectMapper.readValue(rs.getString("column_order"), new TypeReference<List<Map<String, Object>>>() {}),
                        MappingSpecConverter.parse(rs.getString("mapping_spec"), objectMapper),
                        sourceWorkflowId,
                        sourcePublicationId,
                        projectId,
                        rs.getString("organization_id")
                    );
                } catch (Exception e) {
                    throw new RuntimeException("Error mapping DataSource", e);
                }
            }
        }
    }

    @Repository
    public static class DataSourceItemRepository {
        private final JdbcTemplate jdbc;
        private final NamedParameterJdbcTemplate namedJdbc;
        private final ObjectMapper objectMapper;

        public DataSourceItemRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, ObjectMapper objectMapper) {
            this.jdbc = jdbc;
            this.namedJdbc = namedJdbc;
            this.objectMapper = objectMapper;
        }

        public DataSourceItem save(DataSourceItem item) {
            try {
                if (item.id() == null) {
                    // Insert avec NamedParameterJdbcTemplate pour gerer le JSON
                    String sql = """
                        INSERT INTO data_source_items (data_source_id, tenant_id, data, priority)
                        VALUES (:data_source_id, :tenant_id, :data::json, :priority)
                        """;
                    
                    SqlParameterSource params = new MapSqlParameterSource()
                        .addValue("data_source_id", item.dataSourceId())
                        .addValue("tenant_id", item.tenantId())
                        .addValue("data", objectMapper.writeValueAsString(item.data()))
                        .addValue("priority", item.priority());
                    
                    KeyHolder keyHolder = new GeneratedKeyHolder();
                    namedJdbc.update(sql, params, keyHolder, new String[]{"id"});
                    
                    Long id = keyHolder.getKeyAs(Long.class);
                    return new DataSourceItem(id, item.dataSourceId(), item.tenantId(), item.data(), item.priority(), item.createdAt());
                } else {
                    // Update
                    String sql = """
                        UPDATE data_source_items 
                        SET data = :data::json, priority = :priority
                        WHERE id = :id
                        """;
                    
                    SqlParameterSource params = new MapSqlParameterSource()
                        .addValue("data", objectMapper.writeValueAsString(item.data()))
                        .addValue("priority", item.priority())
                        .addValue("id", item.id());
                    
                    namedJdbc.update(sql, params);
                    return item;
                }
            } catch (Exception e) {
                throw new RuntimeException("Erreur lors de la sauvegarde de l'item DataSource", e);
            }
        }

        public List<DataSourceItem> findByDataSourceId(Long dataSourceId) {
            String sql = "SELECT * FROM data_source_items WHERE data_source_id = ? ORDER BY priority DESC, id ASC";
            return jdbc.query(sql, new DataSourceItemRowMapper(), dataSourceId);
        }

        public List<DataSourceItem> findByDataSourceIdAndTenantId(Long dataSourceId, String tenantId) {
            String sql = "SELECT * FROM data_source_items WHERE data_source_id = ? AND tenant_id = ? ORDER BY priority DESC, id ASC";
            return jdbc.query(sql, new DataSourceItemRowMapper(), dataSourceId, tenantId);
        }

        public long countByDataSourceIdAndTenantId(Long dataSourceId, String tenantId) {
            String sql = "SELECT COUNT(*) FROM data_source_items WHERE data_source_id = ? AND tenant_id = ?";
            Long count = jdbc.queryForObject(sql, Long.class, dataSourceId, tenantId);
            return count != null ? count : 0;
        }

        /**
         * SQL-paginated tenant-scoped fetch. Used by the orchestrator's interface-render path
         * (capped by {@code OrchestratorLimitsConfig}) and any other caller that needs a bounded
         * slice rather than the full table. The non-paginated variant above stays for legacy
         * trigger-resolution callers that consume the full set.
         *
         * <p><b>Org-isolation caveat</b>: this finder filters by {@code tenant_id}, which is the
         * creator's tenantId. In org-mode where an admin's items (tenant=A) are read by a
         * teammate (tenant=B) within the same workspace, this query returns 0 rows. Callers in
         * an org context MUST prefer {@link #findByDataSourceIdInOrgScopePaginated}.
         */
        public List<DataSourceItem> findByDataSourceIdAndTenantIdPaginated(Long dataSourceId, String tenantId,
                                                                          int offset, int limit) {
            String sql = "SELECT * FROM data_source_items WHERE data_source_id = ? AND tenant_id = ? "
                    + "ORDER BY priority DESC, id ASC LIMIT ? OFFSET ?";
            return jdbc.query(sql, new DataSourceItemRowMapper(), dataSourceId, tenantId, limit, offset);
        }

        /**
         * Org-strict SQL-paginated fetch. Items don't carry {@code organization_id} themselves
         * (V9 schema, never backfilled), so isolation is enforced via the parent {@code data_sources}
         * row: an item is in-scope iff its parent datasource belongs to the active workspace org.
         * Teammates of the org see each other's items even when {@code tenant_id} differs - this
         * is the post-V261 contract for the render path.
         *
         * <p>The {@code INNER JOIN} adds a single-row PK lookup on {@code data_sources} (indexed),
         * cheap relative to the items scan that the LIMIT keeps bounded.
         */
        public List<DataSourceItem> findByDataSourceIdInOrgScopePaginated(Long dataSourceId, String organizationId,
                                                                          int offset, int limit) {
            String sql = "SELECT i.* FROM data_source_items i "
                    + "INNER JOIN data_sources ds ON ds.id = i.data_source_id "
                    + "WHERE i.data_source_id = ? AND ds.organization_id = ? "
                    + "ORDER BY i.priority DESC, i.id ASC LIMIT ? OFFSET ?";
            return jdbc.query(sql, new DataSourceItemRowMapper(), dataSourceId, organizationId, limit, offset);
        }

        /**
         * Org-strict count, same shape as {@link #findByDataSourceIdInOrgScopePaginated}.
         */
        public long countByDataSourceIdInOrgScope(Long dataSourceId, String organizationId) {
            String sql = "SELECT COUNT(*) FROM data_source_items i "
                    + "INNER JOIN data_sources ds ON ds.id = i.data_source_id "
                    + "WHERE i.data_source_id = ? AND ds.organization_id = ?";
            Long count = jdbc.queryForObject(sql, Long.class, dataSourceId, organizationId);
            return count != null ? count : 0L;
        }

        /**
         * Batch row-count for a page of datasources - ONE {@code GROUP BY} query for all
         * supplied ids, never N+1. The ids come from the already org-filtered paged list
         * (see {@code DataSourceService.getDataSourcesPaged}), so they are pre-authorized:
         * a plain {@code IN (:ids)} with no parent JOIN is both safe and cheaper, and is
         * served by the {@code data_source_items(data_source_id, ...)} index. Datasources
         * with zero items are simply absent from the map; callers default them to 0.
         */
        public Map<Long, Long> countByDataSourceIds(List<Long> dataSourceIds) {
            if (dataSourceIds == null || dataSourceIds.isEmpty()) return Map.of();
            SqlParameterSource params = new MapSqlParameterSource("ids", dataSourceIds);
            String sql = "SELECT data_source_id, COUNT(*) AS cnt FROM data_source_items "
                    + "WHERE data_source_id IN (:ids) GROUP BY data_source_id";
            Map<Long, Long> counts = new java.util.HashMap<>();
            namedJdbc.query(sql, params, (ResultSet rs) -> {
                counts.put(rs.getLong("data_source_id"), rs.getLong("cnt"));
            });
            return counts;
        }

        /**
         * Batch "first {@code perTable} rows" for a page of datasources - ONE window-function
         * query for all supplied ids, never N+1. Mirrors {@link #countByDataSourceIds}: the ids
         * come pre-authorized from the org-filtered paged list, so a plain {@code IN (:ids)} with
         * no parent JOIN is safe and cheap. Each datasource maps to the ordered {@code data}
         * payloads of its top {@code perTable} rows (same {@code priority DESC, id ASC} order as
         * the per-table fetch), so the card list can paint a mini-table preview without one
         * items request per card. Datasources with zero rows are simply absent from the map;
         * callers default them to an empty list.
         *
         * <p>Each row carries its FULL {@code data} payload (not a column subset): the card
         * picks which columns to show client-side from {@code mapping_spec}, which this query
         * doesn't have. Bounded by {@code perTable} (≤10) × the page size, so the payload stays
         * small; a server-side column projection would need that per-table column set and isn't
         * worth the coupling here.
         *
         * @param perTable rows kept per datasource, clamped to [1, 10].
         */
        public Map<Long, List<Map<String, Object>>> sampleRowsByDataSourceIds(List<Long> dataSourceIds, int perTable) {
            if (dataSourceIds == null || dataSourceIds.isEmpty()) return Map.of();
            int cap = Math.min(Math.max(perTable, 1), 10);
            SqlParameterSource params = new MapSqlParameterSource("ids", dataSourceIds)
                    .addValue("cap", cap);
            // ROW_NUMBER partitioned per datasource keeps the slice bounded server-side: the
            // outer filter (rn <= :cap) is what makes this O(perTable * ids) instead of a full scan.
            String sql = """
                    SELECT data_source_id, data FROM (
                        SELECT data_source_id, data,
                               ROW_NUMBER() OVER (PARTITION BY data_source_id ORDER BY priority DESC, id ASC) AS rn
                        FROM data_source_items
                        WHERE data_source_id IN (:ids)
                    ) ranked
                    WHERE rn <= :cap
                    ORDER BY data_source_id, rn
                    """;
            Map<Long, List<Map<String, Object>>> samples = new java.util.LinkedHashMap<>();
            namedJdbc.query(sql, params, (ResultSet rs) -> {
                long dsId = rs.getLong("data_source_id");
                Map<String, Object> data;
                try {
                    data = objectMapper.readValue(rs.getString("data"), new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    // `data` is JSONB NOT NULL so it is always valid JSON, but a row whose root is a
                    // non-object (array/scalar) can't bind to Map - degrade that one row to empty
                    // rather than sink the whole page preview.
                    data = Map.of();
                }
                samples.computeIfAbsent(dsId, k -> new java.util.ArrayList<>()).add(data);
            });
            return samples;
        }

        public List<DataSourceItem> findByDataSourceIdWithPagination(Long dataSourceId, Long lastKey, int limit) {
            if (lastKey == null) {
                String sql = """
                    SELECT * FROM data_source_items 
                    WHERE data_source_id = ? 
                    ORDER BY priority DESC, id ASC 
                    LIMIT ?
                    """;
                return jdbc.query(sql, new DataSourceItemRowMapper(), dataSourceId, limit);
            } else {
                String sql = """
                    SELECT * FROM data_source_items 
                    WHERE data_source_id = ? AND id > ? 
                    ORDER BY priority DESC, id ASC 
                    LIMIT ?
                    """;
                return jdbc.query(sql, new DataSourceItemRowMapper(), dataSourceId, lastKey, limit);
            }
        }

        public List<DataSourceItem> findByDataSourceIdWithPredicate(Long dataSourceId, String predicate, Long lastKey, int limit) {
            String sql = """
                SELECT * FROM data_source_items 
                WHERE data_source_id = ? AND id > ? 
                AND data->>'predicate' = ?
                ORDER BY priority DESC, id ASC 
                LIMIT ?
                """;
            return jdbc.query(sql, new DataSourceItemRowMapper(), dataSourceId, lastKey, predicate, limit);
        }

        public List<DataSourceItem> findByDataSourceIdWithOffset(Long dataSourceId, Long offset, int limit) {
            String sql = """
                SELECT * FROM data_source_items 
                WHERE data_source_id = ? 
                ORDER BY priority DESC, id ASC 
                OFFSET ? LIMIT ?
                """;
            return jdbc.query(sql, new DataSourceItemRowMapper(), dataSourceId, offset, limit);
        }

        public void deleteByDataSourceId(Long dataSourceId) {
            String sql = "DELETE FROM data_source_items WHERE data_source_id = ?";
            jdbc.update(sql, dataSourceId);
        }

        public void deleteById(Long id) {
            String sql = "DELETE FROM data_source_items WHERE id = ?";
            jdbc.update(sql, id);
        }

        private class DataSourceItemRowMapper implements RowMapper<DataSourceItem> {
            @Override
            public DataSourceItem mapRow(ResultSet rs, int rowNum) throws SQLException {
                try {
                    return new DataSourceItem(
                        rs.getLong("id"),
                        rs.getLong("data_source_id"),
                        rs.getString("tenant_id"),
                        objectMapper.readValue(rs.getString("data"), new TypeReference<Map<String, Object>>() {}),
                        rs.getInt("priority"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()
                    );
                } catch (Exception e) {
                    throw new RuntimeException("Error mapping DataSourceItem", e);
                }
            }
        }
    }
}
