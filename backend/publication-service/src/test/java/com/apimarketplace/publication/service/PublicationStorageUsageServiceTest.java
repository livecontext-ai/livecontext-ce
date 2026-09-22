package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PublicationStorageUsageService}.
 *
 * <p>These drive a REAL {@link JdbcTemplate} over a mocked JDBC stack rather than
 * stubbing {@code queryForObject} itself. That distinction is the whole point: the
 * previous suite stubbed {@code queryForObject(sql, Object[].class, ...)} to return
 * an {@code Object[]}, something Spring never does for a multi-column projection, so
 * all five tests passed green while the production path threw
 * {@code IncorrectResultSetColumnCountException(expected 1, actual 2)} on every
 * tenant, every night, and silently reconciled PUBLICATIONS to zero.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationStorageUsageService Unit Tests")
class PublicationStorageUsageServiceTest {

    private static final String TENANT_ID = "tenant-42";

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement preparedStatement;
    @Mock
    private ResultSet resultSet;
    @Mock
    private ResultSetMetaData metaData;

    private PublicationStorageUsageService service;

    @BeforeEach
    void setUp() throws SQLException {
        // Only what EVERY test needs; the statement outcome is chosen per test.
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        service = new PublicationStorageUsageService(new JdbcTemplate(dataSource));
    }

    /** The query returns one aggregate row, then is exhausted. */
    private void singleRow(long bytes, int count) throws SQLException {
        queryReturnsResultSet();
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong(1)).thenReturn(bytes);
        when(resultSet.getInt(2)).thenReturn(count);
    }

    /** The query succeeds but yields no row. */
    private void noRow() throws SQLException {
        queryReturnsResultSet();
        when(resultSet.next()).thenReturn(false);
    }

    private void queryReturnsResultSet() throws SQLException {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        // Lenient on purpose: a 2-column RowMapper never reads the metadata, so these
        // are unused on the FIXED path. They are here so that running this suite
        // against the pre-fix code reproduces the exact production failure
        // (SingleColumnRowMapper reading columnCount 2 and rejecting the result)
        // instead of an unrelated NPE on a null ResultSetMetaData.
        lenient().when(resultSet.getMetaData()).thenReturn(metaData);
        lenient().when(metaData.getColumnCount()).thenReturn(2);
    }

    @Test
    @DisplayName("getStorageUsage maps a TWO-column aggregate row (regression: the 1-vs-2 column bug)")
    void getStorageUsageMapsTwoColumnRow() throws SQLException {
        singleRow(2048L, 7);

        StorageUsageDto result = service.getStorageUsage(TENANT_ID);

        // Pre-fix this observed 0/0: SingleColumnRowMapper rejected the 2-column
        // result and the catch-all swallowed the exception into zero().
        assertThat(result.usedBytes()).isEqualTo(2048L);
        assertThat(result.itemCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("getStorageUsage reads column 1 as bytes and column 2 as the item count, not the reverse")
    void getStorageUsageDoesNotSwapColumns() throws SQLException {
        singleRow(999_999L, 3);

        StorageUsageDto result = service.getStorageUsage(TENANT_ID);

        // Byte sum and row count are both numbers, so a swap would still look
        // plausible in the UI. Pin the ordering explicitly.
        assertThat(result.usedBytes()).isEqualTo(999_999L);
        assertThat(result.itemCount()).isEqualTo(3);
        verify(resultSet).getLong(1);
        verify(resultSet).getInt(2);
    }

    @Test
    @DisplayName("getStorageUsage binds the tenant id to BOTH publisher_id placeholders")
    void getStorageUsageBindsTenantToEveryPlaceholder() throws SQLException {
        singleRow(10L, 1);

        service.getStorageUsage(TENANT_ID);

        // NOT a guard for the column-count bug: the binds happen before the row mapper
        // runs, so this test passes on the pre-fix code too. It guards a different and
        // worse failure - the SQL carries exactly two `publisher_id = ?` placeholders,
        // and a missing bind would scope the count to the whole table, reporting one
        // tenant's usage as every tenant's.
        verify(preparedStatement).setString(1, TENANT_ID);
        verify(preparedStatement).setString(2, TENANT_ID);
        verify(preparedStatement, never()).setString(eq(3), anyString());
    }

    @Test
    @DisplayName("getStorageUsage propagates when the query yields no row")
    void getStorageUsagePropagatesWhenNoRow() throws SQLException {
        noRow();

        // The SQL projects scalar subqueries, so exactly one row always comes back and
        // zero rows means something unexpected happened. Spring raises
        // EmptyResultDataAccessException and it must reach the caller for the same reason
        // as below: a zero here is not a measurement, and the reconciler writes absolutely.
        assertThatThrownBy(() -> service.getStorageUsage(TENANT_ID))
                .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    @DisplayName("getStorageUsage propagates a JDBC failure instead of reporting an erasing zero")
    void getStorageUsagePropagatesQueryFailure() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("db unavailable"));

        // THE defect this whole change exists for, at its source. Returning zero() here
        // does not report "this tenant stores nothing", it hands
        // StorageReconciliationService a number it writes through setUsage, an ABSOLUTE
        // set, erasing the tenant's real figure for the night. The reconciler can only
        // skip a category it is told about, and a swallowed exception tells it nothing.
        assertThatThrownBy(() -> service.getStorageUsage(TENANT_ID))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("getStorageUsage clamps negative aggregates to zero")
    void getStorageUsageClampsNegativeAggregatesToZero() throws SQLException {
        singleRow(-500L, -3);

        StorageUsageDto result = service.getStorageUsage(TENANT_ID);

        // Math.max(0, ...) guards against any negative pg_column_size / count.
        assertThat(result.usedBytes()).isZero();
        assertThat(result.itemCount()).isZero();
    }

    @Test
    @DisplayName("the SQL scopes every subquery to the tenant, and binds one id per placeholder")
    void sqlScopesEverySubqueryToTheTenant() throws SQLException {
        singleRow(10L, 1);

        service.getStorageUsage(TENANT_ID);

        // JdbcTemplate binds by ARGUMENT COUNT, never by parsing the SQL, so the
        // setString assertions in the test above cannot by themselves prove tenant
        // scoping: an `OR true` bolted onto a WHERE, or a subquery losing its filter,
        // keeps the placeholder count intact while reporting one tenant's usage as every
        // tenant's. Capture the statement and assert its shape too.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        String statement = sql.getValue();

        assertThat(statement.chars().filter(c -> c == '?').count())
                .as("one placeholder per tenant-scoped subquery")
                .isEqualTo(2L);
        assertThat(statement)
                .as("both subqueries must stay filtered on publisher_id")
                .containsSubsequence("publisher_id = ?", "publisher_id = ?")
                .doesNotContainIgnoringCase("or true")
                .doesNotContainIgnoringCase("or 1=1");

        // The mapper reads column 1 as bytes and column 2 as the count, so the SELECT has
        // to project them in that order, and NOTHING above tests the SQL itself: swap the
        // two expressions and every other assertion here still passes while production
        // reports the row count as bytes and the byte sum, narrowed to int, as the count.
        // That risk is not theoretical for this query - it threw for every tenant on every
        // run since it was written, so it has never once produced a verified row.
        assertThat(statement.indexOf("pg_column_size"))
                .as("the byte sum must be the FIRST projected column")
                .isLessThan(statement.indexOf("COUNT(*)"));
    }
}
