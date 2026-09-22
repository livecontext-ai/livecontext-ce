package com.apimarketplace.interfaces.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InterfaceStorageUsageService}, driving a REAL {@link JdbcTemplate}
 * over a mocked JDBC stack so the two-column projection is actually exercised.
 *
 * <p>This service had no test at all. It matters because
 * {@code StorageReconciliationService} is its only consumer and writes the answer through
 * {@code setUsage}, an ABSOLUTE set: a failure reported as {@code StorageUsageDto.zero()}
 * does not degrade the INTERFACES figure, it erases it for every tenant the nightly pass
 * touches. Its two siblings (datasource, publication) shipped exactly that defect for
 * months without anything going red.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceStorageUsageService Unit Tests")
class InterfaceStorageUsageServiceTest {

    private static final String TENANT_ID = "tenant-42";

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement preparedStatement;
    @Mock
    private ResultSet resultSet;

    private InterfaceStorageUsageService service;

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        service = new InterfaceStorageUsageService(new JdbcTemplate(dataSource));
    }

    private void singleRow(long bytes, int count) throws SQLException {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong(1)).thenReturn(bytes);
        when(resultSet.getInt(2)).thenReturn(count);
    }

    @Test
    @DisplayName("getStorageUsage maps the two-column aggregate row")
    void mapsTwoColumnRow() throws SQLException {
        singleRow(8192L, 11);

        StorageUsageDto result = service.getStorageUsage(TENANT_ID);

        assertThat(result.usedBytes()).isEqualTo(8192L);
        assertThat(result.itemCount()).isEqualTo(11);
        // Bytes and count are both numbers, so a swapped mapper would still look
        // plausible in the UI. Pin the ordering.
        verify(resultSet).getLong(1);
        verify(resultSet).getInt(2);
    }

    @Test
    @DisplayName("getStorageUsage binds the tenant id to all three placeholders")
    void bindsTenantToEveryPlaceholder() throws SQLException {
        singleRow(1L, 1);

        service.getStorageUsage(TENANT_ID);

        // interfaces bytes, interface_run_snapshots bytes, interfaces count.
        verify(preparedStatement).setString(1, TENANT_ID);
        verify(preparedStatement).setString(2, TENANT_ID);
        verify(preparedStatement).setString(3, TENANT_ID);
    }

    @Test
    @DisplayName("getStorageUsage clamps negative aggregates to zero")
    void clampsNegativeAggregates() throws SQLException {
        singleRow(-7L, -2);

        StorageUsageDto result = service.getStorageUsage(TENANT_ID);

        assertThat(result).isEqualTo(StorageUsageDto.zero());
    }

    @Test
    @DisplayName("getStorageUsage propagates a JDBC failure instead of reporting an erasing zero")
    void propagatesQueryFailure() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("db unavailable"));

        // Returning zero() here hands StorageReconciliationService a number it writes
        // absolutely. The reconciler can only skip a category it is told about, and a
        // swallowed exception tells it nothing.
        assertThatThrownBy(() -> service.getStorageUsage(TENANT_ID))
                .isInstanceOf(DataAccessException.class);
    }
}
