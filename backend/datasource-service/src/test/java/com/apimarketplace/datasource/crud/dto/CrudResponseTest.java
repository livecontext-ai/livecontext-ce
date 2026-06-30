package com.apimarketplace.datasource.crud.dto;

import com.apimarketplace.datasource.crud.domain.CrudOperation;
import com.apimarketplace.datasource.crud.domain.CrudResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CrudResponse DTO.
 */
@DisplayName("CrudResponse")
class CrudResponseTest {

    @Nested
    @DisplayName("fromDomain")
    class FromDomainTests {

        @Test
        @DisplayName("Should convert successful CrudResult to CrudResponse")
        void shouldConvertSuccessResult() {
            CrudResult.ResultData data = new CrudResult.ResultData(
                List.of(Map.of("id", 1)), 1,
                null, null,
                null, null, null,
                false, 0, null
            );
            CrudResult result = CrudResult.success(CrudOperation.READ_ROW, "Read 1 row", data);

            CrudResponse response = CrudResponse.fromDomain(result);

            assertThat(response.success()).isTrue();
            assertThat(response.operation()).isEqualTo("read-row");
            assertThat(response.message()).isEqualTo("Read 1 row");
            assertThat(response.data()).isNotNull();
            assertThat(response.data().rows()).hasSize(1);
            assertThat(response.data().rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should convert failed CrudResult to CrudResponse")
        void shouldConvertFailureResult() {
            CrudResult result = CrudResult.failure(CrudOperation.DELETE_ROW, "Where condition required");

            CrudResponse response = CrudResponse.fromDomain(result);

            assertThat(response.success()).isFalse();
            assertThat(response.operation()).isEqualTo("delete-row");
            assertThat(response.message()).isEqualTo("Where condition required");
            assertThat(response.data()).isNull();
        }

        @Test
        @DisplayName("Should handle null data in CrudResult")
        void shouldHandleNullData() {
            CrudResult result = new CrudResult(CrudOperation.UPDATE_ROW, true, "OK", null);

            CrudResponse response = CrudResponse.fromDomain(result);

            assertThat(response.data()).isNull();
        }

        @Test
        @DisplayName("Should convert create-column result correctly")
        void shouldConvertCreateColumnResult() {
            CrudResult.ResultData data = new CrudResult.ResultData(
                null, null,
                null, null,
                null, null,
                List.of("email", "phone"),
                null, null, null
            );
            CrudResult result = CrudResult.success(CrudOperation.CREATE_COLUMN, "Created 2 columns", data);

            CrudResponse response = CrudResponse.fromDomain(result);

            assertThat(response.data().createdColumns()).containsExactly("email", "phone");
        }
    }

    @Nested
    @DisplayName("factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("success should create a successful response")
        void successShouldCreateSuccessfulResponse() {
            CrudResponse.ResultData data = new CrudResponse.ResultData(
                null, null, List.of(1L), 1, null, null, null, null, null, null
            );

            CrudResponse response = CrudResponse.success("create-row", "Created 1 row", data);

            assertThat(response.success()).isTrue();
            assertThat(response.operation()).isEqualTo("create-row");
        }

        @Test
        @DisplayName("error should create an error response")
        void errorShouldCreateErrorResponse() {
            CrudResponse response = CrudResponse.error("update-row", "Validation failed");

            assertThat(response.success()).isFalse();
            assertThat(response.operation()).isEqualTo("update-row");
            assertThat(response.message()).isEqualTo("Validation failed");
            assertThat(response.data()).isNull();
        }
    }
}
