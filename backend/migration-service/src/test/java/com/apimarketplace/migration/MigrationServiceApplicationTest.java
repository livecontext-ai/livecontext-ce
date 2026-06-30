package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MigrationServiceApplicationTest {

    @Test
    @DisplayName("ensureMigrationSourceTimezoneGuc executes ALTER DATABASE with the catalog name on the happy path")
    void happyPathExecutesAlterDatabaseWithCatalogName() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(conn.getCatalog()).thenReturn("apimarketplace");

        assertThatNoException().isThrownBy(() ->
            MigrationServiceApplication.ensureMigrationSourceTimezoneGuc(ds));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(stmt, times(1)).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
            .isEqualTo("ALTER DATABASE \"apimarketplace\" SET lc.migration.source_timezone TO 'UTC'");
        verify(stmt).close();
        verify(conn).close();
    }

    @Test
    @DisplayName("ensureMigrationSourceTimezoneGuc escapes embedded double-quotes in the database name (full SQL match)")
    void escapesEmbeddedDoubleQuotesInDatabaseName() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(conn.getCatalog()).thenReturn("weird\"name");

        MigrationServiceApplication.ensureMigrationSourceTimezoneGuc(ds);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(stmt).execute(sqlCaptor.capture());
        // Pin the FULL canonical SQL - substring asserts can pass on buggy implementations
        // that produce structurally-broken SQL (e.g. strip-quotes-instead-of-double-them).
        assertThat(sqlCaptor.getValue())
            .isEqualTo("ALTER DATABASE \"weird\"\"name\" SET lc.migration.source_timezone TO 'UTC'");
    }

    @Test
    @DisplayName("ensureMigrationSourceTimezoneGuc handles a catalog name with no special chars cleanly (regression for the common case)")
    void handlesPlainCatalogNameCleanly() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(conn.getCatalog()).thenReturn("postgres-test_DB.123");

        MigrationServiceApplication.ensureMigrationSourceTimezoneGuc(ds);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(stmt).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
            .isEqualTo("ALTER DATABASE \"postgres-test_DB.123\" SET lc.migration.source_timezone TO 'UTC'");
    }

    @Test
    @DisplayName("ensureMigrationSourceTimezoneGuc swallows SQLException and does not propagate - best-effort contract")
    void swallowsSqlExceptionInsteadOfPropagating() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(conn.getCatalog()).thenReturn("apimarketplace");
        SQLException sqlException = new SQLException("permission denied", "42501");
        when(stmt.execute(anyString())).thenThrow(sqlException);

        assertThatNoException().isThrownBy(() ->
            MigrationServiceApplication.ensureMigrationSourceTimezoneGuc(ds));
    }

    @Test
    @DisplayName("ensureMigrationSourceTimezoneGuc skips ALTER DATABASE when catalog is null - defensive guard")
    void skipsAlterDatabaseWhenCatalogIsNull() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(conn.getCatalog()).thenReturn(null);

        MigrationServiceApplication.ensureMigrationSourceTimezoneGuc(ds);

        verify(stmt, never()).execute(anyString());
    }

    @Test
    @DisplayName("ensureMigrationSourceTimezoneGuc skips ALTER DATABASE when catalog is blank - defensive guard")
    void skipsAlterDatabaseWhenCatalogIsBlank() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(conn.getCatalog()).thenReturn("   ");

        MigrationServiceApplication.ensureMigrationSourceTimezoneGuc(ds);

        verify(stmt, never()).execute(anyString());
    }

    @Test
    @DisplayName("ensureMigrationSourceTimezoneGuc swallows SQLException thrown from dataSource.getConnection() - best-effort contract at acquire stage")
    void swallowsSqlExceptionAtAcquireStage() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection refused", "08006"));

        assertThatNoException().isThrownBy(() ->
            MigrationServiceApplication.ensureMigrationSourceTimezoneGuc(ds));
    }
}
