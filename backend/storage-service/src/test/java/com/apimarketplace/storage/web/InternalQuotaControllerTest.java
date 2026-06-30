package com.apimarketplace.storage.web;

import com.apimarketplace.common.storage.service.QuotaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalQuotaController")
class InternalQuotaControllerTest {

    @Mock private QuotaService quotaService;
    @InjectMocks private InternalQuotaController controller;

    @Test
    @DisplayName("tenant-limits delegates to QuotaService.updateLimits (own tx + own cache evict)")
    void setTenantLimitsDelegates() {
        ResponseEntity<Map<String, Object>> resp = controller.setTenantLimits("42", 10_737_418_240L, 0.8);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("tenantId", "42").containsEntry("maxBytes", 10_737_418_240L);
        verify(quotaService).updateLimits("42", 10_737_418_240L, 0.8);
    }

    @Test
    @DisplayName("org-limits delegates to QuotaService.updateOrganizationLimits")
    void setOrganizationLimitsDelegates() {
        ResponseEntity<Map<String, Object>> resp =
                controller.setOrganizationLimits("00000000", 10_737_418_240L, 0.8);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("organizationId", "00000000");
        verify(quotaService).updateOrganizationLimits("00000000", 10_737_418_240L, 0.8);
    }

    @Test
    @DisplayName("default soft ratio is 0.8 when not provided")
    void defaultSoftRatio() {
        controller.setTenantLimits("7", 1_073_741_824L, 0.8);
        verify(quotaService).updateLimits("7", 1_073_741_824L, 0.8);
    }

    @Test
    @DisplayName("maxBytes <= 0 is rejected (400) and never reaches QuotaService - can't nuke a quota to zero")
    void rejectsNonPositiveMaxBytes() {
        ResponseEntity<Map<String, Object>> tenantResp = controller.setTenantLimits("42", 0L, 0.8);
        ResponseEntity<Map<String, Object>> orgResp = controller.setOrganizationLimits("o1", -5L, 0.8);

        assertThat(tenantResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(orgResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(quotaService);
    }
}
