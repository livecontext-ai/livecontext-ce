package com.apimarketplace.storage.retention;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.storage.service.file.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LC-011: expired RESTRICTED storage rows are HARD-deleted together with their objects, the
 * object first, and the quota is debited only for rows this sweep actually deleted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestrictedStorageRetentionSweeper")
class RestrictedStorageRetentionSweeperTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private FileStorageService files;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private QuotaOperations quotaService;

    private RestrictedStorageRetentionSweeper sweeper;

    private final UUID jsonRow = UUID.randomUUID();
    private final UUID fileRow = UUID.randomUUID();

    /** Bind arguments of every DELETE statement the sweeper issued. */
    private final List<List<Object>> deleteArgs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sweeper = new RestrictedStorageRetentionSweeper(jdbc, files, breakdownService, quotaService, true, 500);
    }

    /** Stubs the candidate SELECT with the given (id, s3Key) pairs. */
    @SuppressWarnings("unchecked")
    private void candidates(Object[]... rows) throws Exception {
        List<Object> mapped = new ArrayList<>();
        when(jdbc.query(startsWith("SELECT id, s3_key, run_id, LEAST(expires_at, retention_expires_at) AS deadline FROM storage.storage"), any(RowMapper.class), any(), any()))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    mapped.clear();
                    for (Object[] row : rows) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getObject("id")).thenReturn(row[0]);
                        when(rs.getString("s3_key")).thenReturn((String) row[1]);
                        // Optional third element: the row's run id.
                        when(rs.getString("run_id")).thenReturn(row.length > 2 ? (String) row[2] : null);
                        mapped.add(mapper.mapRow(rs, 0));
                    }
                    return mapped;
                });
    }

    @SuppressWarnings("unchecked")
    private void deletedRows(Object[]... rows) {
        when(jdbc.query(startsWith("DELETE FROM storage.storage"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    Object[] all = inv.getArguments();
                    deleteArgs.add(List.of(java.util.Arrays.copyOfRange(all, 2, all.length)));
                    List<Object> out = new ArrayList<>();
                    for (Object[] row : rows) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("tenant_id")).thenReturn((String) row[0]);
                        when(rs.getString("organization_id")).thenReturn((String) row[1]);
                        when(rs.getLong("size_bytes")).thenReturn((Long) row[2]);
                        when(rs.getString("storage_type")).thenReturn((String) row[3]);
                        when(rs.getString("source_type")).thenReturn((String) row[4]);
                        when(rs.getString("status")).thenReturn((String) row[5]);
                        out.add(mapper.mapRow(rs, 0));
                    }
                    return out;
                });
    }

    @Test
    @DisplayName("deletes the object before the row, deletes the row, and debits the quota of ACTIVE rows")
    void deletesObjectThenRowAndDebits() throws Exception {
        candidates(new Object[] {jsonRow, null}, new Object[] {fileRow, "t1/gmail/att.pdf"});
        when(files.delete("t1/gmail/att.pdf")).thenReturn(true);
        deletedRows(
                new Object[] {"t1", "org1", 120L, "JSON", "STEP_OUTPUT", "ACTIVE"},
                new Object[] {"t1", "org1", 900L, "S3_FILE", "S3_FILE", "DELETED"});

        RestrictedStorageRetentionSweeper.SweepReport report = sweeper.sweep(Instant.now());

        assertThat(report.rowsDeleted()).isEqualTo(2);
        assertThat(report.objectsDeleted()).isEqualTo(1);
        InOrder order = inOrder(files, jdbc);
        order.verify(files).delete("t1/gmail/att.pdf");
        order.verify(jdbc).query(startsWith("DELETE FROM storage.storage"), any(RowMapper.class), any(Object[].class));
        // Only the ACTIVE row was ever counted in the quota; the soft-deleted one was already debited.
        verify(breakdownService).trackDelete(eq("t1"), anyString(), eq(120L), eq("org1"));
        verify(breakdownService, never()).trackDelete(any(), any(), eq(900L), any());
        verify(quotaService).updateUsage("t1");
        verify(quotaService).updateOrganizationUsage("org1");
    }

    @Test
    @DisplayName("a row whose object could not be deleted is KEPT so the next sweep retries it")
    void failedObjectKeepsRow() throws Exception {
        candidates(new Object[] {fileRow, "t1/gmail/att.pdf"});
        when(files.delete("t1/gmail/att.pdf")).thenReturn(false);
        when(files.exists("t1/gmail/att.pdf")).thenReturn(true);

        RestrictedStorageRetentionSweeper.SweepReport report = sweeper.sweep(Instant.now());

        assertThat(report.rowsDeleted()).isZero();
        assertThat(report.objectsFailed()).isEqualTo(1);
        verify(jdbc, never()).query(startsWith("DELETE FROM storage.storage"), any(RowMapper.class), any(Object[].class));
        verifyNoInteractions(breakdownService);
    }

    @Test
    @DisplayName("the delete only ever targets the ids the RESTRICTED + expired SELECT returned")
    void deleteTargetsSelectedIdsOnly() throws Exception {
        candidates(new Object[] {jsonRow, null});
        deletedRows(new Object[] {"t1", null, 10L, "JSON", "STEP_OUTPUT", "ACTIVE"});

        sweeper.sweep(Instant.now());

        ArgumentCaptor<String> selectSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(selectSql.capture(), any(RowMapper.class), any(), any());
        assertThat(selectSql.getValue()).contains("data_sensitivity = 'RESTRICTED'")
                .contains("LEAST(expires_at, retention_expires_at) < ?");
        verify(jdbc).query(startsWith("DELETE FROM storage.storage WHERE id IN (?)"), any(RowMapper.class),
                any(Object[].class));
        assertThat(deleteArgs).containsExactly(List.of(jsonRow));
        verify(quotaService, never()).updateOrganizationUsage(anyString());
    }

    @Test
    @DisplayName("a batch delete failure retries row by row, so one bad row does not block the others")
    void batchDeleteFailureRetriesRowByRowAndSkipsOnlyTheBadRow() throws Exception {
        UUID goodRow = UUID.randomUUID();
        UUID badRow = UUID.randomUUID();
        candidates(new Object[] {goodRow, null}, new Object[] {badRow, null});
        String returningSql = "DELETE FROM storage.storage WHERE id IN (%s) "
                + "RETURNING tenant_id, organization_id, size_bytes, storage_type, source_type, status";

        // The 2-row batch statement fails outright (a locked row, a transient DB error, ...).
        when(jdbc.query(eq(String.format(returningSql, "?,?")), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db hiccup"));

        // Row by row: JdbcTemplate.query's last parameter is `Object... args`, and a single-element
        // stub call binds one matcher PER vararg position (not one matcher for the whole array,
        // which is what `any(Object[].class)` above is special-cased for) - so `eq(goodRow)` here
        // matches the lone vararg element, exactly like the production call `deleteRowsAndDebit(List.of(id))`.
        // The good id succeeds and returns its row...
        when(jdbc.query(eq(String.format(returningSql, "?")), any(RowMapper.class), eq(goodRow)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("tenant_id")).thenReturn("t1");
                    when(rs.getString("organization_id")).thenReturn(null);
                    when(rs.getLong("size_bytes")).thenReturn(50L);
                    when(rs.getString("storage_type")).thenReturn("JSON");
                    when(rs.getString("source_type")).thenReturn("STEP_OUTPUT");
                    when(rs.getString("status")).thenReturn("ACTIVE");
                    return List.of(mapper.mapRow(rs, 0));
                });
        // ...the bad id keeps failing, and is simply skipped (retried on the NEXT sweep).
        when(jdbc.query(eq(String.format(returningSql, "?")), any(RowMapper.class), eq(badRow)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("still down"));

        RestrictedStorageRetentionSweeper.SweepReport report = sweeper.sweep(Instant.now());

        assertThat(report.rowsDeleted()).isEqualTo(1);
        verify(quotaService).updateUsage("t1");
    }

    @Test
    @DisplayName("disabled: touches nothing")
    void disabled() {
        RestrictedStorageRetentionSweeper off =
                new RestrictedStorageRetentionSweeper(jdbc, files, breakdownService, quotaService, false, 500);
        assertThat(off.sweep(Instant.now()).rowsDeleted()).isZero();
        verifyNoInteractions(jdbc, files, breakdownService, quotaService);
    }

    @Test
    @DisplayName("LC-011: disarmed, the scheduled tick deletes nothing, even far past every stamped expiry")
    void lc011DisarmedScheduledTickDeletesNothing() {
        RestrictedStorageRetentionSweeper off =
                new RestrictedStorageRetentionSweeper(jdbc, files, breakdownService, quotaService, false, 500);

        off.scheduledSweep();
        off.sweep(Instant.now().plus(java.time.Duration.ofDays(3650)));

        // No SELECT, no DELETE, no object delete, no quota write: the switch gates the whole step.
        verifyNoInteractions(jdbc, files, breakdownService, quotaService);
    }

    @Test
    @DisplayName("LC-066 retention skew: the runs' restricted-since is recorded BEFORE any object or row is deleted")
    void recordsRestrictedSinceBeforeDeleting() throws Exception {
        candidates(new Object[] {jsonRow, null, "run-a"}, new Object[] {fileRow, "t1/gmail/att.pdf", "run-a"});
        when(files.delete("t1/gmail/att.pdf")).thenReturn(true);
        deletedRows(new Object[] {"t1", "org1", 120L, "JSON", "STEP_OUTPUT", "ACTIVE"});

        sweeper.sweep(Instant.now());

        InOrder order = inOrder(jdbc, files);
        ArgumentCaptor<Object> runIds = ArgumentCaptor.forClass(Object.class);
        order.verify(jdbc).update(eq(RestrictedStorageRetentionSweeper.RECORD_RESTRICTED_SINCE), runIds.capture());
        order.verify(files).delete("t1/gmail/att.pdf");
        order.verify(jdbc).query(startsWith("DELETE FROM storage.storage"), any(RowMapper.class), any(Object[].class));
        // One entry per run, however many of its rows the batch holds.
        assertThat((String[]) runIds.getValue()).containsExactly("run-a");
    }

    @Test
    @DisplayName("LC-066: when the restricted-since cannot be recorded, nothing of the batch is deleted (retried next sweep)")
    void recordFailureKeepsTheBatch() throws Exception {
        candidates(new Object[] {fileRow, "t1/gmail/att.pdf", "run-a"});
        when(jdbc.update(eq(RestrictedStorageRetentionSweeper.RECORD_RESTRICTED_SINCE), any(Object.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        RestrictedStorageRetentionSweeper.SweepReport report = sweeper.sweep(Instant.now());

        assertThat(report.rowsDeleted()).isZero();
        verify(files, never()).delete(anyString());
        verify(jdbc, never()).query(startsWith("DELETE FROM storage.storage"), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @DisplayName("LC-066: rows without a run record nothing and are swept as before")
    void rowsWithoutRunRecordNothing() throws Exception {
        candidates(new Object[] {jsonRow, null});
        deletedRows(new Object[] {"t1", "org1", 120L, "JSON", "STEP_OUTPUT", "ACTIVE"});

        assertThat(sweeper.sweep(Instant.now()).rowsDeleted()).isEqualTo(1);
        verify(jdbc, never()).update(eq(RestrictedStorageRetentionSweeper.RECORD_RESTRICTED_SINCE), any(Object.class));
    }

}
