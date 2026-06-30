package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.WorkspacePurgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspacePurgeInternalControllerTest {

    @Mock private WorkspacePurgeService purgeService;
    @InjectMocks private WorkspacePurgeInternalController controller;

    @Test
    @DisplayName("delegates to purgeWorkspace and reports purged=true")
    void delegatesAndReportsPurged() {
        UUID id = UUID.randomUUID();
        when(purgeService.purgeWorkspace(id)).thenReturn(true);

        ResponseEntity<Map<String, Object>> r = controller.purge(id);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody())
                .containsEntry("orgId", id.toString())
                .containsEntry("purged", true);
        verify(purgeService).purgeWorkspace(id);
    }

    @Test
    @DisplayName("reports purged=false when the purge is skipped (not deleted / personal / already purged / missing)")
    void reportsSkipped() {
        UUID id = UUID.randomUUID();
        when(purgeService.purgeWorkspace(id)).thenReturn(false);

        ResponseEntity<Map<String, Object>> r = controller.purge(id);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("purged", false);
        verify(purgeService).purgeWorkspace(id);
    }
}
