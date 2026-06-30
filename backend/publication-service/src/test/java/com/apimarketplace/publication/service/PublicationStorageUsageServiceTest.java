package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PublicationStorageUsageService}.
 */
@ExtendWith(MockitoExtension.class)
class PublicationStorageUsageServiceTest {

    private static final String TENANT_ID = "tenant-42";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private PublicationStorageUsageService service;

    @BeforeEach
    void setUp() {
        service = new PublicationStorageUsageService(jdbcTemplate);
    }

    @Test
    @DisplayName("getStorageUsage returns zero usage when the JDBC query throws")
    void getStorageUsageReturnsZeroWhenQueryThrows() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Object[].class), eq(TENANT_ID), eq(TENANT_ID)))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        StorageUsageDto result = service.getStorageUsage(TENANT_ID);

        // The catch-all branch must swallow the failure and degrade gracefully to zero,
        // never propagating the exception to the caller.
        assertThat(result).isEqualTo(StorageUsageDto.zero());
        assertThat(result.usedBytes()).isZero();
        assertThat(result.itemCount()).isZero();
    }

    @Test
    @DisplayName("getStorageUsage returns zero usage when the JDBC query yields null")
    void getStorageUsageReturnsZeroWhenResultNull() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Object[].class), eq(TENANT_ID), eq(TENANT_ID)))
                .thenReturn(null);

        StorageUsageDto result = service.getStorageUsage(TENANT_ID);

        // A null aggregate row maps to zero usage rather than an NPE.
        assertThat(result).isEqualTo(StorageUsageDto.zero());
    }

    @Test
    @DisplayName("getStorageUsage maps the aggregated bytes and count from a successful query")
    void getStorageUsageMapsBytesAndCountOnSuccess() {
        Object[] row = new Object[]{2048L, 7};
        when(jdbcTemplate.queryForObject(any(String.class), eq(Object[].class), eq(TENANT_ID), eq(TENANT_ID)))
                .thenReturn(row);

        StorageUsageDto result = service.getStorageUsage(TENANT_ID);

        // Numeric aggregates flow straight through to the DTO.
        assertThat(result.usedBytes()).isEqualTo(2048L);
        assertThat(result.itemCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("getStorageUsage clamps negative aggregates to zero")
    void getStorageUsageClampsNegativeAggregatesToZero() {
        Object[] row = new Object[]{-500L, -3};
        when(jdbcTemplate.queryForObject(any(String.class), eq(Object[].class), eq(TENANT_ID), eq(TENANT_ID)))
                .thenReturn(row);

        StorageUsageDto result = service.getStorageUsage(TENANT_ID);

        // Math.max(0, ...) guards against any negative pg_column_size / count.
        assertThat(result.usedBytes()).isZero();
        assertThat(result.itemCount()).isZero();
    }

    @Test
    @DisplayName("getStorageUsage treats non-numeric query columns as zero")
    void getStorageUsageTreatsNonNumericColumnsAsZero() {
        Object[] row = new Object[]{"not-a-number", null};
        when(jdbcTemplate.queryForObject(any(String.class), eq(Object[].class), eq(TENANT_ID), eq(TENANT_ID)))
                .thenReturn(row);

        StorageUsageDto result = service.getStorageUsage(TENANT_ID);

        // The instanceof Number guards default to zero when columns are not numeric.
        assertThat(result).isEqualTo(StorageUsageDto.zero());
    }
}
