package com.apimarketplace.datasource.services;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceItem;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.apimarketplace.datasource.tools.datasource.DataSourceTableModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.KeyHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression review 2026-09-29 (CASA LC-066): {@code table(action='create')} with inline
 * {@code data} wrote its rows with no sensitivity at all, while {@code insert_rows} stamps them
 * RESTRICTED when the calling execution holds Gmail / Drive content. A restricted chat or agent
 * could therefore park mailbox content in a new table as ordinary rows, read back later without
 * the restricted tag, by any model.
 */
@DisplayName("A table created with inline data by a restricted execution stores restricted rows")
class DataSourceRestrictedRowsTest {

    private static final String TENANT = "tenant-1";
    private static final List<Map<String, Object>> ROWS = List.of(Map.of("subject", "wire 45000 EUR"));

    @Nested
    @DisplayName("DataSourceTableModule.create")
    class Module {

        private final DataSourceService dataSourceService = mock(DataSourceService.class);

        private ToolExecutionResult create(Map<String, Object> credentials) {
            DataSourceTableModule module = new DataSourceTableModule(dataSourceService, new ObjectMapper(),
                    new com.apimarketplace.datasource.config.DataSourceAgentDefaultsConfig(),
                    mock(com.apimarketplace.datasource.services.VectorFeatureGate.class));
            DataSource created = new DataSource(7L, TENANT, "t", "d", DataSourceType.INLINE, Map.of(),
                    DataSourceStatus.ACTIVE, null, null, TENANT, null, null, null, null, null, null);
            when(dataSourceService.createDataSource(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(DataSensitivity.class))).thenReturn(created);
            when(dataSourceService.createDataSource(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(created);
            ToolExecutionContext context = new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(),
                    null, null, null, null);
            return module.execute("create", Map.of("name", "t", "data", ROWS), TENANT, context).orElseThrow();
        }

        @Test
        @DisplayName("regression: a restricted caller's inline rows are created RESTRICTED")
        void restrictedCallerCreatesRestrictedRows() {
            ToolExecutionResult result = create(Map.of(DataSensitivity.CREDENTIAL_KEY, DataSensitivity.RESTRICTED.name()));

            assertThat(result.success()).isTrue();
            verify(dataSourceService).createDataSource(eq(TENANT), eq("t"), any(), eq(DataSourceType.INLINE), any(),
                    eq(ROWS), eq(TENANT), any(), any(), eq(DataSensitivity.RESTRICTED));
        }

        @Test
        @DisplayName("an ordinary caller keeps the exact create it always made")
        void ordinaryCallerIsUnchanged() {
            create(Map.of());

            verify(dataSourceService).createDataSource(eq(TENANT), eq("t"), any(), eq(DataSourceType.INLINE), any(),
                    eq(ROWS), eq(TENANT), any(), any());
            verify(dataSourceService, never()).createDataSource(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(DataSensitivity.class));
        }
    }

    @Nested
    @DisplayName("DataSourceService.addDataToSource")
    class Service {

        private final DataSourceItemRepository items = mock(DataSourceItemRepository.class);
        private final DataSourceRepository sources = mock(DataSourceRepository.class);

        private DataSourceService service() {
            org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
            env.setProperty("app.edition", "ce");
            return new DataSourceService(sources, items,
                    mock(StorageBreakdownService.class), new ObjectMapper(), mock(OrgAccessGuard.class),
                    mock(EntitlementGuard.class),
                    new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env), null),
                    mock(org.springframework.context.ApplicationEventPublisher.class),
                    mock(com.apimarketplace.publication.client.PublicationClient.class));
        }

        @Test
        @DisplayName("containsRestrictedItems asks about exactly the rows it was handed, and answers false without a query for none")
        void containsRestrictedItemsAsksAboutTheHandedRows() {
            DataSourceItem row = new DataSourceItem(5L, 7L, TENANT, Map.of("subject", "x"), 1, java.time.Instant.EPOCH);
            when(items.anyRestricted(7L, List.of(5L))).thenReturn(true);

            assertThat(service().containsRestrictedItems(7L, List.of(row))).isTrue();
            assertThat(service().containsRestrictedItems(7L, List.of())).isFalse();
            verify(items, org.mockito.Mockito.times(1)).anyRestricted(any(), any());
        }

        @Test
        @DisplayName("a failed sensitivity lookup answers false (the rows are still served, untagged: documented fail-open)")
        void containsRestrictedItemsFailsOpen() {
            DataSourceItem row = new DataSourceItem(5L, 7L, TENANT, Map.of("subject", "x"), 1, java.time.Instant.EPOCH);
            when(items.anyRestricted(any(), any())).thenThrow(new org.springframework.dao.QueryTimeoutException("slow"));

            assertThat(service().containsRestrictedItems(7L, List.of(row))).isFalse();
        }

        @Test
        @DisplayName("regression (copy paging): a publication copy reads the org scope through the copy query, cursor passed on")
        void publicationCopyUsesTheOrgCopyQuery() {
            DataSourceItem plain = new DataSourceItem(6L, 7L, TENANT, Map.of("subject", "hello"), 2, java.time.Instant.EPOCH);
            DataSourceItemRepository.CopyCursor after = new DataSourceItemRepository.CopyCursor(2, 5L);
            when(items.findCopyPageInOrgScope(7L, "org-1", 500, 500, after)).thenReturn(List.of(plain));

            assertThat(service().getDataSourceItemsForCopy(7, TENANT, "org-1", 500, 500, after)).containsExactly(plain);
            verify(items, never()).findCopyPageForTenant(any(), any(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any());
        }

        @Test
        @DisplayName("a tenant-scoped publication copy (no organization) uses the tenant copy query")
        void publicationCopyUsesTheTenantCopyQuery() {
            service().getDataSourceItemsForCopy(7, TENANT, null, 0, 500, null);

            verify(items).findCopyPageForTenant(7L, TENANT, 0, 500, null);
        }

        @Test
        @DisplayName("an invalid window or a missing scope reads nothing")
        void invalidCopyWindowReadsNothing() {
            assertThat(service().getDataSourceItemsForCopy(7, TENANT, null, -1, 500, null)).isEmpty();
            assertThat(service().getDataSourceItemsForCopy(7, TENANT, null, 0, 0, null)).isEmpty();
            assertThat(service().getDataSourceItemsForCopy(null, TENANT, null, 0, 500, null)).isEmpty();
            assertThat(service().getDataSourceItemsForCopy(7, null, null, 0, 500, null)).isEmpty();
            verify(items, never()).findCopyPageForTenant(any(), any(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any());
        }

        @Test
        @DisplayName("regression (fail closed): a failing copy query propagates, nothing unclassified is served")
        void failingCopyQueryPropagates() {
            when(items.findCopyPageInOrgScope(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt(), any()))
                    .thenThrow(new org.springframework.dao.QueryTimeoutException("slow"));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service().getDataSourceItemsForCopy(7, TENANT, "org-1", 0, 500, null))
                    .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        }

        @Test
        @DisplayName("regression: RESTRICTED rows are inserted stamped, never through the unstamped save")
        void restrictedRowsAreStampedAtInsert() {
            service().addDataToSource(7L, TENANT, ROWS, DataSensitivity.RESTRICTED);

            verify(items, times(1)).insert(any(DataSourceItem.class), eq(DataSensitivity.RESTRICTED));
            verify(items, never()).save(any());
        }

        @Test
        @DisplayName("NORMAL rows keep the existing save path, byte for byte")
        void normalRowsKeepTheSavePath() {
            service().addDataToSource(7L, TENANT, ROWS);

            verify(items, times(1)).save(any(DataSourceItem.class));
            verify(items, never()).insert(any(), any());
        }

        @Test
        @DisplayName("regression: createDataSource hands its sensitivity to the rows it adds (the create path, end to end)")
        void createDataSourcePassesTheSensitivityToItsRows() {
            when(sources.save(any(DataSource.class))).thenReturn(new DataSource(7L, TENANT, "t", "d",
                    DataSourceType.INLINE, Map.of(), DataSourceStatus.ACTIVE, null, null, TENANT,
                    null, null, null, null, null, null));

            service().createDataSource(TENANT, "t", "d", DataSourceType.INLINE, Map.of(), ROWS, TENANT,
                    null, null, DataSensitivity.RESTRICTED);

            verify(items, times(1)).insert(any(DataSourceItem.class), eq(DataSensitivity.RESTRICTED));
            verify(items, never()).save(any());
        }
    }

    @Nested
    @DisplayName("DataSourceItemRepository.insert")
    class Repository {

        @Test
        @DisplayName("writes the data_sensitivity column with the row, in the same INSERT")
        void insertWritesTheSensitivityColumn() {
            NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
            doAnswer(inv -> {
                ((KeyHolder) inv.getArgument(2)).getKeyList().add(Map.of("id", 11L));
                return 1;
            }).when(named).update(anyString(), any(SqlParameterSource.class), any(KeyHolder.class), any(String[].class));
            DataSourceItemRepository repo = new DataSourceItemRepository(mock(JdbcTemplate.class), named, new ObjectMapper());

            DataSourceItem saved = repo.insert(new DataSourceItem(null, 7L, TENANT, ROWS.get(0), 0, null),
                    DataSensitivity.RESTRICTED);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
            verify(named).update(sql.capture(), params.capture(), any(KeyHolder.class), any(String[].class));
            assertThat(sql.getValue()).contains("data_sensitivity").contains(":data_sensitivity");
            assertThat(((MapSqlParameterSource) params.getValue()).getValue("data_sensitivity")).isEqualTo("RESTRICTED");
            assertThat(saved.id()).isEqualTo(11L);
        }

        private ArgumentCaptor<Object[]> args;

        @SuppressWarnings("unchecked")
        private String capturedPageSql(JdbcTemplate jdbc) {
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            args = ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class), args.capture());
            return sql.getValue();
        }

        @Test
        @DisplayName("regression (copy paging): the first org-scoped copy page leaves RESTRICTED rows out BEFORE the page is cut")
        void orgScopedCopyFiltersBeforeTheLimit() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DataSourceItemRepository repo = new DataSourceItemRepository(jdbc, mock(NamedParameterJdbcTemplate.class), new ObjectMapper());

            repo.findCopyPageInOrgScope(7L, "org-1", 0, 500, null);

            String sql = capturedPageSql(jdbc);
            assertThat(sql).contains("AND i.data_sensitivity IS DISTINCT FROM 'RESTRICTED' ");
            assertThat(sql.indexOf("data_sensitivity")).isLessThan(sql.indexOf("ORDER BY"));
            assertThat(sql).endsWith("ORDER BY i.priority DESC, i.id ASC LIMIT ? OFFSET ?");
            assertThat(args.getValue()).containsExactly(7L, "org-1", 500, 0);
        }

        @Test
        @DisplayName("regression (copy paging): a next page resumes by keyset after the cursor row, with no offset")
        void nextCopyPageResumesByKeyset() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DataSourceItemRepository repo = new DataSourceItemRepository(jdbc, mock(NamedParameterJdbcTemplate.class), new ObjectMapper());

            repo.findCopyPageForTenant(7L, TENANT, 500, 500, new DataSourceItemRepository.CopyCursor(3, 42L));

            String sql = capturedPageSql(jdbc);
            assertThat(sql).contains("AND data_sensitivity IS DISTINCT FROM 'RESTRICTED' "
                    + "AND (priority < ? OR (priority = ? AND id > ?)) ORDER BY priority DESC, id ASC LIMIT ?");
            assertThat(sql).doesNotContain("OFFSET");
            assertThat(args.getValue()).containsExactly(7L, TENANT, 3, 3, 42L, 500);
        }

        @Test
        @DisplayName("the ordinary paged reads send exactly the SQL they always sent")
        void ordinaryPagedReadsAreUnchanged() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DataSourceItemRepository repo = new DataSourceItemRepository(jdbc, mock(NamedParameterJdbcTemplate.class), new ObjectMapper());

            repo.findByDataSourceIdInOrgScopePaginated(7L, "org-1", 0, 50);
            repo.findByDataSourceIdAndTenantIdPaginated(7L, TENANT, 0, 50);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc, times(2)).query(sql.capture(),
                    any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any(), any());
            assertThat(sql.getAllValues()).containsExactly(
                    "SELECT i.* FROM data_source_items i "
                            + "INNER JOIN data_sources ds ON ds.id = i.data_source_id "
                            + "WHERE i.data_source_id = ? AND ds.organization_id = ? "
                            + "ORDER BY i.priority DESC, i.id ASC LIMIT ? OFFSET ?",
                    "SELECT * FROM data_source_items WHERE data_source_id = ? AND tenant_id = ? "
                            + "ORDER BY priority DESC, id ASC LIMIT ? OFFSET ?");
        }

        @Test
        @DisplayName("save() of a new item still sends the INSERT without the column, so it keeps the NORMAL default")
        void saveKeepsTheUnstampedInsert() {
            NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
            doAnswer(inv -> {
                ((KeyHolder) inv.getArgument(2)).getKeyList().add(Map.of("id", 12L));
                return 1;
            }).when(named).update(anyString(), any(SqlParameterSource.class), any(KeyHolder.class), any(String[].class));
            DataSourceItemRepository repo = new DataSourceItemRepository(mock(JdbcTemplate.class), named, new ObjectMapper());

            DataSourceItem saved = repo.save(new DataSourceItem(null, 7L, TENANT, ROWS.get(0), 0, null));

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
            verify(named).update(sql.capture(), params.capture(), any(KeyHolder.class), any(String[].class));
            assertThat(sql.getValue()).startsWith("INSERT INTO data_source_items").doesNotContain("data_sensitivity");
            assertThat(params.getValue().hasValue("data_sensitivity")).isFalse();
            assertThat(saved.id()).isEqualTo(12L);
        }
    }
}
