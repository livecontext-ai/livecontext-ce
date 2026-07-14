package com.apimarketplace.datasource.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DataSourceEnhancedModels")
class DataSourceEnhancedModelsTest {

    @Nested
    @DisplayName("PaginationRequest")
    class PaginationRequestTests {

        @Test
        @DisplayName("Should default startRow to 0 when null")
        void shouldDefaultStartRow() {
            var req = new DataSourceEnhancedModels.PaginationRequest(null, null, null, null, null, null, null);
            assertEquals(0, req.startRow());
        }

        @Test
        @DisplayName("Should default endRow to 100 when null")
        void shouldDefaultEndRow() {
            var req = new DataSourceEnhancedModels.PaginationRequest(null, null, null, null, null, null, null);
            assertEquals(100, req.endRow());
        }

        @Test
        @DisplayName("Should calculate limit from range when null")
        void shouldCalculateLimit() {
            var req = new DataSourceEnhancedModels.PaginationRequest(0, 50, null, null, null, null, null);
            assertEquals(50, req.limit());
        }

        @Test
        @DisplayName("Should cap limit at 500")
        void shouldCapLimitAt500() {
            var req = new DataSourceEnhancedModels.PaginationRequest(0, 1000, null, null, null, null, null);
            assertEquals(500, req.limit());
        }

        @Test
        @DisplayName("Should cap explicit limit at 500")
        void shouldCapExplicitLimit() {
            var req = new DataSourceEnhancedModels.PaginationRequest(0, 100, 999, null, null, null, null);
            assertEquals(500, req.limit());
        }

        @Test
        @DisplayName("Should preserve valid explicit limit")
        void shouldPreserveValidLimit() {
            var req = new DataSourceEnhancedModels.PaginationRequest(0, 100, 25, null, null, null, null);
            assertEquals(25, req.limit());
        }
    }

    @Nested
    @DisplayName("SortRequest")
    class SortRequestTests {

        @Test
        @DisplayName("Should create valid SortRequest")
        void shouldCreateValid() {
            var req = new DataSourceEnhancedModels.SortRequest("name", DataSourceEnhancedModels.SortDirection.ASC);
            assertEquals("name", req.colId());
            assertEquals(DataSourceEnhancedModels.SortDirection.ASC, req.sort());
        }

        @Test
        @DisplayName("Should throw on null colId")
        void shouldThrowOnNullColId() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.SortRequest(null, DataSourceEnhancedModels.SortDirection.ASC));
        }

        @Test
        @DisplayName("Should throw on null sort direction")
        void shouldThrowOnNullSort() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.SortRequest("name", null));
        }
    }

    @Nested
    @DisplayName("SortDirection")
    class SortDirectionTests {

        @Test
        @DisplayName("ASC should have value 'asc'")
        void ascShouldHaveCorrectValue() {
            assertEquals("asc", DataSourceEnhancedModels.SortDirection.ASC.getValue());
        }

        @Test
        @DisplayName("DESC should have value 'desc'")
        void descShouldHaveCorrectValue() {
            assertEquals("desc", DataSourceEnhancedModels.SortDirection.DESC.getValue());
        }

        @Test
        @DisplayName("fromValue should parse case-insensitively")
        void fromValueShouldParseCaseInsensitive() {
            assertEquals(DataSourceEnhancedModels.SortDirection.ASC, DataSourceEnhancedModels.SortDirection.fromValue("ASC"));
            assertEquals(DataSourceEnhancedModels.SortDirection.DESC, DataSourceEnhancedModels.SortDirection.fromValue("Desc"));
        }

        @Test
        @DisplayName("fromValue should throw for invalid value")
        void fromValueShouldThrowForInvalid() {
            assertThrows(IllegalArgumentException.class, () ->
                DataSourceEnhancedModels.SortDirection.fromValue("invalid"));
        }
    }

    @Nested
    @DisplayName("PaginationResponse")
    class PaginationResponseTests {

        @Test
        @DisplayName("Should create valid response")
        void shouldCreateValid() {
            var resp = new DataSourceEnhancedModels.PaginationResponse<>(
                List.of("a", "b"), 2, null, false, 1
            );
            assertEquals(2, resp.rowData().size());
            assertEquals(2, resp.rowCount());
        }

        @Test
        @DisplayName("Should default rowCount to data size when null")
        void shouldDefaultRowCount() {
            var resp = new DataSourceEnhancedModels.PaginationResponse<>(List.of("a", "b"), null, null, null, null);
            assertEquals(2, resp.rowCount());
        }

        @Test
        @DisplayName("Should default hasMore based on nextCursor presence")
        void shouldDefaultHasMore() {
            var withCursor = new DataSourceEnhancedModels.PaginationResponse<>(List.of(), null, "cursor", null, null);
            assertTrue(withCursor.hasMore());

            var withoutCursor = new DataSourceEnhancedModels.PaginationResponse<>(List.of(), null, null, null, null);
            assertFalse(withoutCursor.hasMore());
        }

        @Test
        @DisplayName("Should default totalPages to 1 when null")
        void shouldDefaultTotalPages() {
            var resp = new DataSourceEnhancedModels.PaginationResponse<>(List.of(), null, null, null, null);
            assertEquals(1, resp.totalPages());
        }

        @Test
        @DisplayName("Should throw on null rowData")
        void shouldThrowOnNullRowData() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.PaginationResponse<>(null, null, null, null, null));
        }
    }

    @Nested
    @DisplayName("KeysetCursor")
    class KeysetCursorTests {

        @Test
        @DisplayName("Should encode cursor correctly")
        void shouldEncode() {
            var cursor = new DataSourceEnhancedModels.KeysetCursor(1234567890L, 42L);
            assertEquals("1234567890_42", cursor.encode());
        }

        @Test
        @DisplayName("Should decode valid cursor")
        void shouldDecode() {
            var cursor = DataSourceEnhancedModels.KeysetCursor.decode("1234567890_42");
            assertNotNull(cursor);
            assertEquals(1234567890L, cursor.createdAtUs());
            assertEquals(42L, cursor.id());
        }

        @Test
        @DisplayName("of() should preserve MICROSECOND precision (millis truncation dropped bulk-insert rows)")
        void ofShouldPreserveMicrosecondPrecision() {
            // Regression: rows of a bulk insert share one created_at down to the microsecond.
            // A millis-truncated cursor never equals their created_at, so the next page was
            // empty and every remaining row was silently dropped.
            Instant createdAt = Instant.ofEpochSecond(1_700_000_000L, 123_456_000L); // .123456 s
            var cursor = DataSourceEnhancedModels.KeysetCursor.of(createdAt, 42L);

            // round-trips through encode/decode without losing the microseconds
            var decoded = DataSourceEnhancedModels.KeysetCursor.decode(cursor.encode());
            assertEquals(createdAt, decoded.createdAtInstant());
            assertEquals(1_700_000_000_123_456L, decoded.createdAtUs());
        }

        @Test
        @DisplayName("createdAtInstant() should round-trip nanos truncated to micros")
        void createdAtInstantTruncatesToMicros() {
            // Postgres timestamp is microsecond-precision: sub-microsecond nanos are dropped,
            // which is exactly what the DB column stores, so comparison stays exact.
            Instant withNanos = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L);
            var cursor = DataSourceEnhancedModels.KeysetCursor.of(withNanos, 7L);
            assertEquals(Instant.ofEpochSecond(1_700_000_000L, 123_456_000L), cursor.createdAtInstant());
        }

        @Test
        @DisplayName("Round-trip encode/decode should preserve values")
        void shouldRoundTrip() {
            var original = new DataSourceEnhancedModels.KeysetCursor(999L, 123L);
            var decoded = DataSourceEnhancedModels.KeysetCursor.decode(original.encode());
            assertEquals(original, decoded);
        }

        @Test
        @DisplayName("Should return null for null cursor")
        void shouldReturnNullForNull() {
            assertNull(DataSourceEnhancedModels.KeysetCursor.decode(null));
        }

        @Test
        @DisplayName("Should return null for empty cursor")
        void shouldReturnNullForEmpty() {
            assertNull(DataSourceEnhancedModels.KeysetCursor.decode(""));
        }

        @Test
        @DisplayName("Should throw for invalid format")
        void shouldThrowForInvalidFormat() {
            assertThrows(IllegalArgumentException.class, () ->
                DataSourceEnhancedModels.KeysetCursor.decode("invalid"));
        }

        @Test
        @DisplayName("Should throw for non-numeric cursor")
        void shouldThrowForNonNumeric() {
            assertThrows(IllegalArgumentException.class, () ->
                DataSourceEnhancedModels.KeysetCursor.decode("abc_def"));
        }

        @Test
        @DisplayName("Should throw on null createdAtUs")
        void shouldThrowOnNullCreatedAt() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.KeysetCursor(null, 1L));
        }

        @Test
        @DisplayName("Should throw on null id")
        void shouldThrowOnNullId() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.KeysetCursor(1L, null));
        }
    }

    @Nested
    @DisplayName("JsonPatchOperation")
    class JsonPatchOperationTests {

        @Test
        @DisplayName("Should create valid patch operation")
        void shouldCreateValid() {
            var op = new DataSourceEnhancedModels.JsonPatchOperation(
                DataSourceEnhancedModels.PatchOperation.REPLACE, "/name", "New Name", null
            );
            assertEquals(DataSourceEnhancedModels.PatchOperation.REPLACE, op.op());
            assertEquals("/name", op.path());
            assertEquals("New Name", op.value());
        }

        @Test
        @DisplayName("Should throw on null op")
        void shouldThrowOnNullOp() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.JsonPatchOperation(null, "/path", "val", null));
        }

        @Test
        @DisplayName("Should throw on null path")
        void shouldThrowOnNullPath() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.JsonPatchOperation(
                    DataSourceEnhancedModels.PatchOperation.ADD, null, "val", null));
        }
    }

    @Nested
    @DisplayName("PatchOperation")
    class PatchOperationTests {

        @Test
        @DisplayName("Should have 6 operations")
        void shouldHave6Operations() {
            assertEquals(6, DataSourceEnhancedModels.PatchOperation.values().length);
        }

        @ParameterizedTest
        @DisplayName("All values should round-trip through fromValue")
        @EnumSource(DataSourceEnhancedModels.PatchOperation.class)
        void allShouldRoundTrip(DataSourceEnhancedModels.PatchOperation op) {
            assertEquals(op, DataSourceEnhancedModels.PatchOperation.fromValue(op.getValue()));
        }

        @Test
        @DisplayName("fromValue should throw for invalid")
        void fromValueShouldThrowForInvalid() {
            assertThrows(IllegalArgumentException.class, () ->
                DataSourceEnhancedModels.PatchOperation.fromValue("invalid"));
        }
    }

    @Nested
    @DisplayName("BulkOperationRequest")
    class BulkOperationRequestTests {

        @Test
        @DisplayName("Should create valid request")
        void shouldCreateValid() {
            var req = new DataSourceEnhancedModels.BulkOperationRequest(
                DataSourceEnhancedModels.BulkOperationType.DELETE, List.of(1L, 2L), null
            );
            assertEquals(DataSourceEnhancedModels.BulkOperationType.DELETE, req.op());
            assertEquals(List.of(1L, 2L), req.ids());
        }

        @Test
        @DisplayName("Should throw on null op")
        void shouldThrowOnNullOp() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.BulkOperationRequest(null, List.of(1L), null));
        }

        @Test
        @DisplayName("Should throw on null ids")
        void shouldThrowOnNullIds() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.BulkOperationRequest(
                    DataSourceEnhancedModels.BulkOperationType.DELETE, null, null));
        }

        @Test
        @DisplayName("Should throw on empty ids")
        void shouldThrowOnEmptyIds() {
            assertThrows(IllegalArgumentException.class, () ->
                new DataSourceEnhancedModels.BulkOperationRequest(
                    DataSourceEnhancedModels.BulkOperationType.DELETE, List.of(), null));
        }
    }

    @Nested
    @DisplayName("BulkOperationResult")
    class BulkOperationResultTests {

        @Test
        @DisplayName("Should default null fields")
        void shouldDefaultNullFields() {
            var result = new DataSourceEnhancedModels.BulkOperationResult(true, null, null, null, null);
            assertEquals(0, result.processedCount());
            assertEquals(0, result.failedCount());
            assertTrue(result.errors().isEmpty());
            assertTrue(result.results().isEmpty());
        }

        @Test
        @DisplayName("Should throw on null success")
        void shouldThrowOnNullSuccess() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.BulkOperationResult(null, null, null, null, null));
        }
    }

    @Nested
    @DisplayName("ColumnDefinition")
    class ColumnDefinitionTests {

        @Test
        @DisplayName("Should default optional fields")
        void shouldDefaultOptionalFields() {
            var col = new DataSourceEnhancedModels.ColumnDefinition(
                "col1", "Name", "name", ColumnType.TEXT,
                null, null, null, null, null, null, null, null
            );
            assertTrue(col.editable());
            assertTrue(col.sortable());
            assertTrue(col.filterable());
            assertEquals(ColumnStructure.SCALAR, col.structure());
            assertTrue(col.display().isEmpty());
        }

        @Test
        @DisplayName("Should throw on null colId")
        void shouldThrowOnNullColId() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.ColumnDefinition(
                    null, "Name", "name", ColumnType.TEXT,
                    null, null, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("Should throw on null headerName")
        void shouldThrowOnNullHeaderName() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.ColumnDefinition(
                    "col1", null, "name", ColumnType.TEXT,
                    null, null, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("Should throw on null field")
        void shouldThrowOnNullField() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.ColumnDefinition(
                    "col1", "Name", null, ColumnType.TEXT,
                    null, null, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("Should throw on null type")
        void shouldThrowOnNullType() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.ColumnDefinition(
                    "col1", "Name", "name", null,
                    null, null, null, null, null, null, null, null));
        }
    }

    @Nested
    @DisplayName("FilterOperator")
    class FilterOperatorTests {

        @Test
        @DisplayName("Should have 14 operators")
        void shouldHave14Operators() {
            assertEquals(14, DataSourceEnhancedModels.FilterOperator.values().length);
        }

        @ParameterizedTest
        @DisplayName("All values should round-trip through fromValue")
        @EnumSource(DataSourceEnhancedModels.FilterOperator.class)
        void allShouldRoundTrip(DataSourceEnhancedModels.FilterOperator op) {
            assertEquals(op, DataSourceEnhancedModels.FilterOperator.fromValue(op.getValue()));
        }
    }

    @Nested
    @DisplayName("ColumnOperation")
    class ColumnOperationTests {

        @Test
        @DisplayName("Should have 4 operations (drop, rename, set_default, update_display)")
        void shouldHave4Operations() {
            assertEquals(4, DataSourceEnhancedModels.ColumnOperation.values().length);
        }

        @ParameterizedTest
        @DisplayName("All values should round-trip through fromValue")
        @EnumSource(DataSourceEnhancedModels.ColumnOperation.class)
        void allShouldRoundTrip(DataSourceEnhancedModels.ColumnOperation op) {
            assertEquals(op, DataSourceEnhancedModels.ColumnOperation.fromValue(op.getValue()));
        }
    }

    @Nested
    @DisplayName("ErrorResponse")
    class ErrorResponseTests {

        @Test
        @DisplayName("Should auto-set timestamp when null")
        void shouldAutoSetTimestamp() {
            var resp = new DataSourceEnhancedModels.ErrorResponse("ERROR", "Something failed", null, null);
            assertNotNull(resp.timestamp());
        }

        @Test
        @DisplayName("Should preserve explicit timestamp")
        void shouldPreserveTimestamp() {
            Instant ts = Instant.parse("2024-01-01T00:00:00Z");
            var resp = new DataSourceEnhancedModels.ErrorResponse("ERROR", "msg", null, ts);
            assertEquals(ts, resp.timestamp());
        }

        @Test
        @DisplayName("Should throw on null error")
        void shouldThrowOnNullError() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.ErrorResponse(null, "msg", null, null));
        }

        @Test
        @DisplayName("Should throw on null message")
        void shouldThrowOnNullMessage() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.ErrorResponse("ERROR", null, null, null));
        }
    }

    @Nested
    @DisplayName("DataSourceItemRow")
    class DataSourceItemRowTests {

        @Test
        @DisplayName("Should create valid row")
        void shouldCreateValid() {
            Instant now = Instant.now();
            var row = new DataSourceEnhancedModels.DataSourceItemRow(
                1L, 10L, "tenant-1", Map.of("name", "Test"), 0, now, null
            );

            assertEquals(1L, row.id());
            assertEquals(10L, row.dataSourceId());
            assertEquals("tenant-1", row.tenantId());
            assertEquals(Map.of("name", "Test"), row.data());
            assertEquals(0, row.priority());
            assertEquals(now, row.createdAt());
            assertNull(row.updatedAt());
        }

        @Test
        @DisplayName("Should throw on null id")
        void shouldThrowOnNullId() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.DataSourceItemRow(null, 1L, "t", Map.of(), 0, Instant.now(), null));
        }

        @Test
        @DisplayName("Should throw on null dataSourceId")
        void shouldThrowOnNullDataSourceId() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.DataSourceItemRow(1L, null, "t", Map.of(), 0, Instant.now(), null));
        }

        @Test
        @DisplayName("Should throw on null tenantId")
        void shouldThrowOnNullTenantId() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.DataSourceItemRow(1L, 1L, null, Map.of(), 0, Instant.now(), null));
        }

        @Test
        @DisplayName("Should throw on null data")
        void shouldThrowOnNullData() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.DataSourceItemRow(1L, 1L, "t", null, 0, Instant.now(), null));
        }

        @Test
        @DisplayName("Should throw on null priority")
        void shouldThrowOnNullPriority() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.DataSourceItemRow(1L, 1L, "t", Map.of(), null, Instant.now(), null));
        }

        @Test
        @DisplayName("Should throw on null createdAt")
        void shouldThrowOnNullCreatedAt() {
            assertThrows(NullPointerException.class, () ->
                new DataSourceEnhancedModels.DataSourceItemRow(1L, 1L, "t", Map.of(), 0, null, null));
        }
    }
}
