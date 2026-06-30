package com.apimarketplace.datasource.controllers.crud;

import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.datasource.crud.domain.CrudOperation;
import com.apimarketplace.datasource.crud.domain.CrudResult;
import com.apimarketplace.datasource.crud.dto.CreateRowRequest;
import com.apimarketplace.datasource.crud.dto.CrudResponse;
import com.apimarketplace.datasource.crud.dto.DeleteRowRequest;
import com.apimarketplace.datasource.crud.dto.ReadRowRequest;
import com.apimarketplace.datasource.crud.dto.UpdateRowRequest;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.services.DataSourceService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for CrudController.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CrudController")
class CrudControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";
    private static final Long DS_ID = 1L;

    @Mock
    private CrudExecutorService crudExecutorService;

    @Mock
    private TenantResolver tenantResolver;

    @Mock
    private DataSourceService dataSourceService;

    @Mock
    private OrgAccessGuard orgAccessGuard;

    @Mock
    private HttpServletRequest httpRequest;

    private CrudController controller;

    @BeforeEach
    void setUp() {
        controller = new CrudController(crudExecutorService, tenantResolver, dataSourceService, orgAccessGuard);
    }

    /** Build an org-scoped datasource so the write gate has an org to enforce against. */
    private DataSource orgScopedDataSource() {
        return new DataSource(
                DS_ID, TENANT, "Table", null, DataSourceType.INLINE, Map.of(),
                DataSourceStatus.ACTIVE, null, null, TENANT,
                List.of(), Map.of(), null, null, null, ORG);
    }

    @Nested
    @DisplayName("execute")
    class ExecuteTests {

        @Test
        @DisplayName("Should return 200 on successful operation")
        void shouldReturn200OnSuccess() {
            when(tenantResolver.resolve(httpRequest)).thenReturn(TENANT);

            CrudResult successResult = CrudResult.success(
                CrudOperation.READ_ROW, "Read 2 rows",
                CrudResult.ResultData.forRead(
                    List.of(Map.of("id", 1), Map.of("id", 2)), false, 0
                )
            );
            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getDataSourceId()).thenReturn(DS_ID);
            when(crudExecutorService.execute(any(), eq(TENANT))).thenReturn(successResult);

            ResponseEntity<CrudResponse> response = controller.execute(request, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isTrue();
        }

        @Test
        @DisplayName("Should return 400 on failed operation")
        void shouldReturn400OnFailure() {
            when(tenantResolver.resolve(httpRequest)).thenReturn(TENANT);

            CrudResult failureResult = CrudResult.failure(CrudOperation.DELETE_ROW, "WHERE required");
            DeleteRowRequest request = mock(DeleteRowRequest.class);
            when(request.getOperation()).thenReturn(CrudOperation.DELETE_ROW);
            when(request.getDataSourceId()).thenReturn(DS_ID);
            // Write op on an org-scoped DS - gate must pass so we reach the executor.
            when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(orgScopedDataSource()));
            when(orgAccessGuard.canWrite(eq(ORG), eq(TENANT), eq("datasource"), eq(DS_ID.toString()), any()))
                    .thenReturn(true);
            when(crudExecutorService.execute(any(), eq(TENANT))).thenReturn(failureResult);

            ResponseEntity<CrudResponse> response = controller.execute(request, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isFalse();
        }
    }

    @Nested
    @DisplayName("dedicated endpoints")
    class DedicatedEndpointTests {

        @Test
        @DisplayName("readRow should delegate to executeOperation")
        void readRowShouldDelegate() {
            when(tenantResolver.resolve(httpRequest)).thenReturn(TENANT);

            CrudResult result = CrudResult.success(
                CrudOperation.READ_ROW, "OK",
                CrudResult.ResultData.forRead(List.of(), false, 0)
            );
            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getDataSourceId()).thenReturn(DS_ID);
            when(crudExecutorService.execute(any(), eq(TENANT))).thenReturn(result);

            ResponseEntity<CrudResponse> response = controller.readRow(request, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(crudExecutorService).execute(request, TENANT);
        }
    }

    @Nested
    @DisplayName("org per-resource write gate")
    class OrgWriteGateTests {

        @Test
        @DisplayName("Member with canWrite=false on a write op is refused with OrgAccessDeniedException (no execution)")
        void writeBlockedWhenCanWriteFalse() {
            when(tenantResolver.resolve(httpRequest)).thenReturn(TENANT);
            when(tenantResolver.resolveOrgRole(httpRequest)).thenReturn("MEMBER");

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getDataSourceId()).thenReturn(DS_ID);
            when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(orgScopedDataSource()));
            when(orgAccessGuard.canWrite(ORG, TENANT, "datasource", DS_ID.toString(), "MEMBER"))
                    .thenReturn(false);

            assertThatThrownBy(() -> controller.createRow(request, httpRequest))
                    .isInstanceOf(OrgAccessDeniedException.class);

            // The restriction short-circuits BEFORE the executor runs - no write happens.
            verify(crudExecutorService, never()).execute(any(), any());
        }

        @Test
        @DisplayName("Member with canWrite=true on a write op proceeds to execution")
        void writeAllowedWhenCanWriteTrue() {
            when(tenantResolver.resolve(httpRequest)).thenReturn(TENANT);
            when(tenantResolver.resolveOrgRole(httpRequest)).thenReturn("MEMBER");

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getDataSourceId()).thenReturn(DS_ID);
            when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(orgScopedDataSource()));
            when(orgAccessGuard.canWrite(ORG, TENANT, "datasource", DS_ID.toString(), "MEMBER"))
                    .thenReturn(true);
            when(crudExecutorService.execute(any(), eq(TENANT))).thenReturn(
                    CrudResult.success(CrudOperation.CREATE_ROW, "Created 1 row(s)",
                            CrudResult.ResultData.forCreate(List.of(99L), null)));

            ResponseEntity<CrudResponse> response = controller.createRow(request, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(crudExecutorService).execute(request, TENANT);
        }

        @Test
        @DisplayName("READ op proceeds when the member is NOT deny-restricted (canAccess=true - read-only members still read)")
        void readAllowedWhenCanAccessTrue() {
            when(tenantResolver.resolve(httpRequest)).thenReturn(TENANT);
            when(tenantResolver.resolveOrgRole(httpRequest)).thenReturn("MEMBER");

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getDataSourceId()).thenReturn(DS_ID);
            when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(orgScopedDataSource()));
            when(orgAccessGuard.canAccess(ORG, TENANT, "datasource", DS_ID.toString(), "MEMBER")).thenReturn(true);
            when(crudExecutorService.execute(any(), eq(TENANT))).thenReturn(
                    CrudResult.success(CrudOperation.READ_ROW, "OK",
                            CrudResult.ResultData.forRead(List.of(), false, 0)));

            ResponseEntity<CrudResponse> response = controller.readRow(request, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(crudExecutorService).execute(request, TENANT);
        }

        @Test
        @DisplayName("READ op IS now gated: a DENY-restricted member (canAccess=false) is refused before execution - closes the row-data read leak (#10)")
        void readBlockedWhenCanAccessFalse() {
            when(tenantResolver.resolve(httpRequest)).thenReturn(TENANT);
            when(tenantResolver.resolveOrgRole(httpRequest)).thenReturn("MEMBER");

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getDataSourceId()).thenReturn(DS_ID);
            when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(orgScopedDataSource()));
            when(orgAccessGuard.canAccess(ORG, TENANT, "datasource", DS_ID.toString(), "MEMBER")).thenReturn(false);

            assertThatThrownBy(() -> controller.readRow(request, httpRequest))
                    .isInstanceOf(OrgAccessDeniedException.class);

            // Deny short-circuits BEFORE the executor - no row data is read.
            verify(crudExecutorService, never()).execute(any(), any());
        }

        @Test
        @DisplayName("UPDATE write op is gated (canWrite=false → refused)")
        void updateWriteBlockedWhenCanWriteFalse() {
            when(tenantResolver.resolve(httpRequest)).thenReturn(TENANT);
            when(tenantResolver.resolveOrgRole(httpRequest)).thenReturn("MEMBER");

            UpdateRowRequest request = mock(UpdateRowRequest.class);
            when(request.getOperation()).thenReturn(CrudOperation.UPDATE_ROW);
            when(request.getDataSourceId()).thenReturn(DS_ID);
            when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(orgScopedDataSource()));
            when(orgAccessGuard.canWrite(ORG, TENANT, "datasource", DS_ID.toString(), "MEMBER"))
                    .thenReturn(false);

            assertThatThrownBy(() -> controller.updateRow(request, httpRequest))
                    .isInstanceOf(OrgAccessDeniedException.class);
            verify(crudExecutorService, never()).execute(any(), any());
        }

        @Test
        @DisplayName("Datasource with no org context skips the gate (personal/legacy row)")
        void writeSkippedWhenNoOrgScope() {
            when(tenantResolver.resolve(httpRequest)).thenReturn(TENANT);

            DataSource personalDs = new DataSource(
                    DS_ID, TENANT, "Table", null, DataSourceType.INLINE, Map.of(),
                    DataSourceStatus.ACTIVE, null, null, TENANT,
                    List.of(), Map.of(), null, null, null, null); // organizationId = null

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getDataSourceId()).thenReturn(DS_ID);
            when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(personalDs));
            when(crudExecutorService.execute(any(), eq(TENANT))).thenReturn(
                    CrudResult.success(CrudOperation.CREATE_ROW, "Created 1 row(s)",
                            CrudResult.ResultData.forCreate(List.of(99L), null)));

            ResponseEntity<CrudResponse> response = controller.createRow(request, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // No org → guard not consulted; the executor still runs (tenant scope enforced downstream).
            verifyNoInteractions(orgAccessGuard);
            verify(crudExecutorService).execute(request, TENANT);
        }
    }
}
