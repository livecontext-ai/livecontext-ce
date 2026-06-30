package com.apimarketplace.datasource.services;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnManagementRequest;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnOperation;
import com.apimarketplace.datasource.crud.repository.VectorRepository;
import com.apimarketplace.datasource.events.DatasourceRowEventPublisher;
import com.apimarketplace.datasource.persistence.DataSourceEnhancedRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that {@code DataSourceEnhancedService.manageColumn} rejects RENAME
 * operations whose new key collides with a reserved physical column name on
 * {@code data_source_items}. Without this guard, a rename to "id" / "data" /
 * "tenant_id" silently shadows the user's value on read.
 *
 * <p>Pin-test for the chokepoint: validation runs <strong>before</strong> the
 * existence check and the repository call.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DataSourceEnhancedService - manageColumn RENAME validation")
class DataSourceEnhancedServiceRenameValidationTest {

    @Mock private DataSourceEnhancedRepositories repositories;
    @Mock private VectorRepository vectorRepository;
    @Mock private DataSourceService dataSourceService;
    @Mock private DatasourceRowEventPublisher rowEventPublisher;

    private DataSourceEnhancedService service;

    @BeforeEach
    void setUp() {
        service = new DataSourceEnhancedService(repositories, vectorRepository, dataSourceService, rowEventPublisher, ceVectorGate(), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("RENAME to reserved name 'id' rejected before repo call")
    void renameToReservedIdRejected() {
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.RENAME, "title", "id", null, null);
        assertThatThrownBy(() -> service.manageColumn(1L, "t1", req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved");
        verify(repositories, never()).manageColumn(anyLong(), anyString(), any());
        verify(repositories, never()).dataSourceExists(anyLong(), anyString());
    }

    @Test
    @DisplayName("RENAME to reserved name 'data' rejected (the JSONB blob column itself)")
    void renameToReservedDataRejected() {
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.RENAME, "title", "data", null, null);
        assertThatThrownBy(() -> service.manageColumn(1L, "t1", req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved");
        verify(repositories, never()).manageColumn(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("RENAME to reserved name 'tenant_id' rejected")
    void renameToReservedTenantIdRejected() {
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.RENAME, "owner", "tenant_id", null, null);
        assertThatThrownBy(() -> service.manageColumn(1L, "t1", req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved");
    }

    @Test
    @DisplayName("RENAME to reserved with 'data.' prefix still rejected (sanitizer strips prefix first)")
    void renameToPrefixedReservedRejected() {
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.RENAME, "title", "data.id", null, null);
        assertThatThrownBy(() -> service.manageColumn(1L, "t1", req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved");
    }

    @Test
    @DisplayName("RENAME with empty new_key rejected with explicit message")
    void renameWithEmptyNewKeyRejected() {
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.RENAME, "title", "  ", null, null);
        assertThatThrownBy(() -> service.manageColumn(1L, "t1", req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("new_key");
    }

    @Test
    @DisplayName("RENAME with null new_key rejected with explicit message")
    void renameWithNullNewKeyRejected() {
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.RENAME, "title", null, null, null);
        assertThatThrownBy(() -> service.manageColumn(1L, "t1", req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("new_key");
    }

    @Test
    @DisplayName("RENAME to a non-reserved name proceeds to existence check + repo")
    void renameToValidNamePassesValidation() {
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.RENAME, "title", "headline", null, null);
        when(repositories.dataSourceExists(1L, "t1")).thenReturn(true);
        when(repositories.manageColumn(1L, "t1", req))
            .thenReturn(new com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnManagementResult(
                Boolean.TRUE, Integer.valueOf(1), null));
        // Must not throw - rename name is valid.
        service.manageColumn(1L, "t1", req);
        verify(repositories).manageColumn(1L, "t1", req);
    }

    @Test
    @DisplayName("DROP operation skips the new_key validation entirely")
    void dropOperationDoesNotRequireNewKey() {
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.DROP, "title", null, null, null);
        when(repositories.dataSourceExists(1L, "t1")).thenReturn(true);
        when(repositories.manageColumn(1L, "t1", req))
            .thenReturn(new com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnManagementResult(
                Boolean.TRUE, Integer.valueOf(1), null));
        service.manageColumn(1L, "t1", req);
        verify(repositories).manageColumn(1L, "t1", req);
    }

    // ──────────── UPDATE_DISPLAY (regression for the missing-edit-column UX) ────────────

    @Test
    @DisplayName("UPDATE_DISPLAY with no rename dispatches straight to repo (no reserved-name check)")
    void updateDisplayWithoutRenameDispatchesDirectly() {
        java.util.Map<String, Object> display = java.util.Map.of(
            "options", java.util.List.of(
                java.util.Map.of("label", "Pending", "value", "Pending", "color", "#f97316"),
                java.util.Map.of("label", "Urgent",  "value", "Urgent",  "color", "#ef4444")
            )
        );
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.UPDATE_DISPLAY, "label", null, null, display);
        when(repositories.dataSourceExists(1L, "t1")).thenReturn(true);
        when(repositories.manageColumn(1L, "t1", req))
            .thenReturn(new com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnManagementResult(
                Boolean.TRUE, Integer.valueOf(0), null));

        service.manageColumn(1L, "t1", req);
        verify(repositories).manageColumn(1L, "t1", req);
    }

    @Test
    @DisplayName("UPDATE_DISPLAY with rename to reserved name 'id' rejected before repo call")
    void updateDisplayWithRenameToReservedRejected() {
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.UPDATE_DISPLAY, "label", "id", null, java.util.Map.of("max", 5));
        assertThatThrownBy(() -> service.manageColumn(1L, "t1", req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved");
        verify(repositories, never()).manageColumn(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("UPDATE_DISPLAY with rename to a valid name passes validation and reaches repo")
    void updateDisplayWithValidRenamePasses() {
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.UPDATE_DISPLAY, "label", "priority_label", null,
            java.util.Map.of("max", 10));
        when(repositories.dataSourceExists(1L, "t1")).thenReturn(true);
        when(repositories.manageColumn(1L, "t1", req))
            .thenReturn(new com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnManagementResult(
                Boolean.TRUE, Integer.valueOf(7), null));

        service.manageColumn(1L, "t1", req);
        verify(repositories).manageColumn(1L, "t1", req);
    }

    @Test
    @DisplayName("UPDATE_DISPLAY with new_key=key (no real rename) skips reserved-name validation")
    void updateDisplayWithSameKeyAsNewKeySkipsValidation() {
        // Edge case: frontend sends new_key just because the user re-typed the same name.
        // No actual rename happens; reserved-name guard must not fire.
        ColumnManagementRequest req = new ColumnManagementRequest(
            ColumnOperation.UPDATE_DISPLAY, "id_label", "id_label", null,
            java.util.Map.of("options", java.util.List.of()));
        when(repositories.dataSourceExists(1L, "t1")).thenReturn(true);
        when(repositories.manageColumn(1L, "t1", req))
            .thenReturn(new com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnManagementResult(
                Boolean.TRUE, Integer.valueOf(0), null));

        service.manageColumn(1L, "t1", req);
        verify(repositories).manageColumn(1L, "t1", req);
    }

    private static com.apimarketplace.datasource.services.VectorFeatureGate ceVectorGate() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("app.edition", "ce");
        return new com.apimarketplace.datasource.services.VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env));
    }
}
