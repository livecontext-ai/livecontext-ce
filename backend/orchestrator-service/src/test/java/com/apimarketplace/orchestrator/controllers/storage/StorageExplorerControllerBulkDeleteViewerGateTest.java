package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.service.StorageExplorerService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: the bulk-delete endpoints (batch-delete-by-date, batch-delete-by-virtual)
 * authorize via the write-restricted EXCLUSION set, not per-id {@code canWrite}. That set
 * cannot express the role-wide write block of a read-only VIEWER, so pre-fix a VIEWER
 * could bulk-delete every org file that was not individually deny-listed. The endpoints
 * now short-circuit with 403 on {@code OrgAccessGuard.isRoleWriteBlocked}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageExplorerController bulk deletes - VIEWER role gate")
class StorageExplorerControllerBulkDeleteViewerGateTest {

    private static final String TENANT = "user-1";
    private static final String ORG = "org-1";

    @Mock private StorageExplorerService explorerService;
    @Mock private StorageService storageService;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private WorkflowRepository workflowRepository;

    private StorageExplorerController controller;

    @BeforeEach
    void setUp() {
        controller = new StorageExplorerController(
                explorerService, storageService, orgAccessGuard, workflowRepository);
    }

    private static Map<String, String> dateRangeBody() {
        return Map.of("dateFrom", "2026-01-01T00:00:00Z", "dateTo", "2026-02-01T00:00:00Z");
    }

    @Test
    @DisplayName("batch-delete-by-date: VIEWER in an org workspace gets 403 and nothing is deleted")
    void viewerCannotBulkDeleteByDate() {
        ResponseEntity<Map<String, Object>> response =
                controller.deleteByDateRange(TENANT, ORG, "VIEWER", dateRangeBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(storageService, never()).deleteByDateRangeForScope(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("batch-delete-by-virtual: VIEWER in an org workspace gets 403 and nothing is deleted")
    void viewerCannotBulkDeleteByVirtualFolder() {
        ResponseEntity<Map<String, Object>> response = controller.deleteByVirtualFolder(
                TENANT, ORG, "viewer", Map.of("folderRef", "wf:" + UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(storageService, never()).deleteVirtualScopeForScope(any(), any(), any(), any());
    }

    @Test
    @DisplayName("batch-delete-by-date: MEMBER proceeds, with the deny-listed ids excluded from the wipe")
    void memberBulkDeleteByDateExcludesRestrictedIds() {
        UUID restricted = UUID.randomUUID();
        when(orgAccessGuard.getWriteRestrictedResourceIds(ORG, TENANT, "file", "MEMBER"))
                .thenReturn(Set.of(restricted.toString()));
        when(storageService.deleteByDateRangeForScope(eq(TENANT), eq(ORG), any(), any(), eq(Set.of(restricted))))
                .thenReturn(3);

        ResponseEntity<Map<String, Object>> response =
                controller.deleteByDateRange(TENANT, ORG, "MEMBER", dateRangeBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("deletedCount", 3);
    }

    @Test
    @DisplayName("VIEWER outside an org workspace (no org header) is not role-blocked")
    void viewerWithoutOrgIsNotBlocked() {
        when(storageService.deleteByDateRangeForScope(eq(TENANT), eq(null), any(), any(), eq(Set.of())))
                .thenReturn(0);

        ResponseEntity<Map<String, Object>> response =
                controller.deleteByDateRange(TENANT, null, "VIEWER", dateRangeBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
