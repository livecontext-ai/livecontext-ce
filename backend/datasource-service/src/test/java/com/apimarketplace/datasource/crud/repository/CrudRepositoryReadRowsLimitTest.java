package com.apimarketplace.datasource.crud.repository;

import com.apimarketplace.datasource.crud.service.SqlSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Repository-layer regression test for the 2026-05-13 scalability fix.
 *
 * <p>Pre-fix the repository hard-clamped the SQL {@code LIMIT} parameter to 101
 * (legacy {@code limit+1}-trick default). Daily Email Digest workflows called
 * {@code crud-find} with {@code limit=10000} to count rows by label, but every
 * count plateaued at 101 the moment a category exceeded 100 - the dashboard
 * silently lied.
 *
 * <p>The {@code CrudExecutorServiceTest} regression test ({@code shouldNotSilentlyCapAtOneHundredAndOne})
 * pins the EXECUTOR contract (10 000 in → 10 001 sent to repo). This class
 * pins the REPOSITORY contract: whatever {@code limit} the service hands the
 * repo MUST land verbatim in the {@code MapSqlParameterSource} bound to the
 * SQL - no internal clamp. Pre-fix the {@code MapSqlParameterSource.getValue("limit")}
 * would have been {@code 101} regardless of the input; post-fix it equals the
 * caller's value.
 */
class CrudRepositoryReadRowsLimitTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private CrudRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        SqlSanitizer sqlSanitizer = new SqlSanitizer();
        ObjectMapper objectMapper = new ObjectMapper();
        repository = new CrudRepository(jdbcTemplate, sqlSanitizer, objectMapper);
        when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
            .thenReturn(List.of());
    }

    @Test
    @DisplayName("readRows binds the caller's limit verbatim - closes the 2026-05-13 silent 101-cap that truncated count dashboards")
    void capLimitVerbatimAtTenThousandOne() {
        // Caller (the executor's `limit + 1` for hasMore probe) requests
        // 10 001 rows. Pre-fix the repo would internally clamp via
        // Math.min(limit, 101) and bind LIMIT 101 to the SQL → SELECT
        // returned at most 101 rows regardless of input.
        repository.readRows(1L, "tenant-1", null, 10_001, 0);

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).queryForList(anyString(), captor.capture());

        Object boundLimit = captor.getValue().getValue("limit");
        assertThat(boundLimit)
            .as("Repo MUST bind the caller's limit verbatim to LIMIT :limit; " +
                "pre-fix this asserted 101 (Math.min(limit, 101)) and silently truncated")
            .isEqualTo(10_001);
    }

    @Test
    @DisplayName("readRows clamps limit UP to 1 when caller sends 0 or negative - Spring's PreparedStatement rejects LIMIT < 1")
    void clampNegativeLimitUpToOne() {
        repository.readRows(1L, "tenant-1", null, 0, 0);

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).queryForList(anyString(), captor.capture());

        assertThat(captor.getValue().getValue("limit"))
            .as("LIMIT 0 is rejected by Postgres prepared-statement binding; floor at 1")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("readRows preserves a small caller-driven limit (no implicit floor/ceiling beyond [1, ∞))")
    void preservesSmallLimits() {
        repository.readRows(1L, "tenant-1", null, 21, 0);

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).queryForList(anyString(), captor.capture());

        assertThat(captor.getValue().getValue("limit")).isEqualTo(21);
    }

    private static <T> T any(Class<T> clazz) {
        return org.mockito.ArgumentMatchers.any(clazz);
    }
}
