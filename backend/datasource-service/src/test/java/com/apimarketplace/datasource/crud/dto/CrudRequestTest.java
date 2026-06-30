package com.apimarketplace.datasource.crud.dto;

import com.apimarketplace.datasource.crud.domain.CrudOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CrudRequest sealed hierarchy and its subclasses.
 */
@DisplayName("CrudRequest sealed hierarchy")
class CrudRequestTest {

    @Nested
    @DisplayName("ReadRowRequest")
    class ReadRowRequestTests {

        @Test
        @DisplayName("Should return READ_ROW operation")
        void shouldReturnReadRowOperation() {
            ReadRowRequest request = new ReadRowRequest();
            assertThat(request.getOperation()).isEqualTo(CrudOperation.READ_ROW);
        }

        @Test
        @DisplayName("Should have default limit of 20")
        void shouldHaveDefaultLimit() {
            ReadRowRequest request = new ReadRowRequest();
            assertThat(request.getLimit()).isEqualTo(20);
        }

        @Test
        @DisplayName("Should set and get where condition")
        void shouldSetAndGetWhere() {
            ReadRowRequest request = new ReadRowRequest();
            WhereConditionDto where = new WhereConditionDto("id", "=", 1);
            request.setWhere(where);
            assertThat(request.getWhere()).isSameAs(where);
        }

        @Test
        @DisplayName("Should set and get limit")
        void shouldSetAndGetLimit() {
            ReadRowRequest request = new ReadRowRequest();
            request.setLimit(50);
            assertThat(request.getLimit()).isEqualTo(50);
        }

        @Test
        @DisplayName("Should inherit dataSourceId from CrudRequest")
        void shouldInheritDataSourceId() {
            ReadRowRequest request = new ReadRowRequest();
            request.setDataSourceId(42L);
            assertThat(request.getDataSourceId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("Should inherit stepLabel from CrudRequest")
        void shouldInheritStepLabel() {
            ReadRowRequest request = new ReadRowRequest();
            request.setStepLabel("my-step");
            assertThat(request.getStepLabel()).isEqualTo("my-step");
        }
    }

    @Nested
    @DisplayName("CreateRowRequest")
    class CreateRowRequestTests {

        @Test
        @DisplayName("Should return CREATE_ROW operation")
        void shouldReturnCreateRowOperation() {
            CreateRowRequest request = new CreateRowRequest();
            assertThat(request.getOperation()).isEqualTo(CrudOperation.CREATE_ROW);
        }

        @Test
        @DisplayName("Should set and get rows")
        void shouldSetAndGetRows() {
            CreateRowRequest request = new CreateRowRequest();
            List<CreateRowRequest.RowData> rows = List.of(
                new CreateRowRequest.RowData("row1", Map.of("name", "Alice")),
                new CreateRowRequest.RowData("row2", Map.of("name", "Bob"))
            );
            request.setRows(rows);
            assertThat(request.getRows()).hasSize(2);
            assertThat(request.getRows().get(0).id()).isEqualTo("row1");
            assertThat(request.getRows().get(0).columns()).containsEntry("name", "Alice");
        }

        @Test
        @DisplayName("RowData record should store id and columns")
        void rowDataShouldStoreIdAndColumns() {
            CreateRowRequest.RowData rowData = new CreateRowRequest.RowData("r1", Map.of("col1", "val1"));
            assertThat(rowData.id()).isEqualTo("r1");
            assertThat(rowData.columns()).containsEntry("col1", "val1");
        }
    }

    @Nested
    @DisplayName("UpdateRowRequest")
    class UpdateRowRequestTests {

        @Test
        @DisplayName("Should return UPDATE_ROW operation")
        void shouldReturnUpdateRowOperation() {
            UpdateRowRequest request = new UpdateRowRequest();
            assertThat(request.getOperation()).isEqualTo(CrudOperation.UPDATE_ROW);
        }

        @Test
        @DisplayName("Should set and get where condition")
        void shouldSetAndGetWhere() {
            UpdateRowRequest request = new UpdateRowRequest();
            WhereConditionDto where = new WhereConditionDto("id", "=", 1);
            request.setWhere(where);
            assertThat(request.getWhere()).isSameAs(where);
        }

        @Test
        @DisplayName("Should set and get set values")
        void shouldSetAndGetSetValues() {
            UpdateRowRequest request = new UpdateRowRequest();
            Map<String, Object> set = Map.of("name", "Updated");
            request.setSet(set);
            assertThat(request.getSet()).containsEntry("name", "Updated");
        }
    }

    @Nested
    @DisplayName("DeleteRowRequest")
    class DeleteRowRequestTests {

        @Test
        @DisplayName("Should return DELETE_ROW operation")
        void shouldReturnDeleteRowOperation() {
            DeleteRowRequest request = new DeleteRowRequest();
            assertThat(request.getOperation()).isEqualTo(CrudOperation.DELETE_ROW);
        }

        @Test
        @DisplayName("Should set and get where condition")
        void shouldSetAndGetWhere() {
            DeleteRowRequest request = new DeleteRowRequest();
            WhereConditionDto where = new WhereConditionDto("id", "=", 1);
            request.setWhere(where);
            assertThat(request.getWhere()).isSameAs(where);
        }
    }

    @Nested
    @DisplayName("CreateColumnRequest")
    class CreateColumnRequestTests {

        @Test
        @DisplayName("Should return CREATE_COLUMN operation")
        void shouldReturnCreateColumnOperation() {
            CreateColumnRequest request = new CreateColumnRequest();
            assertThat(request.getOperation()).isEqualTo(CrudOperation.CREATE_COLUMN);
        }

        @Test
        @DisplayName("Should set and get columns")
        void shouldSetAndGetColumns() {
            CreateColumnRequest request = new CreateColumnRequest();
            List<CreateColumnRequest.ColumnDefinition> columns = List.of(
                new CreateColumnRequest.ColumnDefinition("name", "text", null, null),
                new CreateColumnRequest.ColumnDefinition("age", "number", "0", null)
            );
            request.setColumns(columns);
            assertThat(request.getColumns()).hasSize(2);
            assertThat(request.getColumns().get(0).name()).isEqualTo("name");
            assertThat(request.getColumns().get(0).type()).isEqualTo("text");
            assertThat(request.getColumns().get(0).defaultValue()).isNull();
            assertThat(request.getColumns().get(1).defaultValue()).isEqualTo("0");
        }

        @Test
        @DisplayName("ColumnDefinition record should store all fields")
        void columnDefinitionShouldStoreAllFields() {
            CreateColumnRequest.ColumnDefinition col =
                new CreateColumnRequest.ColumnDefinition("email", "text", "none", null);
            assertThat(col.name()).isEqualTo("email");
            assertThat(col.type()).isEqualTo("text");
            assertThat(col.defaultValue()).isEqualTo("none");
        }
    }
}
