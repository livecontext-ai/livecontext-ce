package com.apimarketplace.storage.web;

import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.service.QuotaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The pre-spend probe. Media generation charges the customer inside the provider call and stores
 * the asset afterwards, so this read is the only moment at which "charged for an asset we then
 * refuse to keep" can be prevented.
 *
 * <p>What these tests really guard is PARITY with {@code S3FileStorageService.validateQuota}. A
 * probe that answers about a different bucket than the write is worse than no probe: it reports
 * success and the write still refuses. So each test below pins one rule the write also follows.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalQuotaCheckController")
class InternalQuotaCheckControllerTest {

    @Mock private QuotaService quotaService;

    private InternalQuotaCheckController controller() {
        return new InternalQuotaCheckController(quotaService);
    }

    /** Put a request carrying the active workspace on the thread, as a servlet call would. */
    private void requestInWorkspace(String orgId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Organization-ID", orgId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequestScope() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("a full scope answers allowed=false and names the status")
    void refusesWhenFull() {
        when(quotaService.checkQuotaForScope("42", null, 1L)).thenReturn(QuotaStatus.HARD_LIMIT_REACHED);

        ResponseEntity<Map<String, Object>> resp = controller().check("42", null, 1L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("allowed", false)
                .containsEntry("status", "HARD_LIMIT_REACHED");
    }

    @Test
    @DisplayName("a scope with room answers allowed=true")
    void allowsWhenRoom() {
        when(quotaService.checkQuotaForScope("42", null, 1L)).thenReturn(QuotaStatus.OK);

        assertThat(controller().check("42", null, 1L).getBody()).containsEntry("allowed", true);
    }

    @Test
    @DisplayName("with no explicit workspace, the ACTIVE one on the request is probed, not the personal quota")
    void fallsBackToTheRequestWorkspace() {
        // The trap: probing the personal quota while the upload lands in the workspace would wave
        // through a caller whose workspace is full, which is the exact case this feature exists for.
        requestInWorkspace("org-from-request");
        when(quotaService.checkQuotaForScope("42", "org-from-request", 1L))
                .thenReturn(QuotaStatus.HARD_LIMIT_REACHED);

        ResponseEntity<Map<String, Object>> resp = controller().check("42", null, 1L);

        verify(quotaService).checkQuotaForScope("42", "org-from-request", 1L);
        assertThat(resp.getBody()).containsEntry("allowed", false)
                .containsEntry("organizationId", "org-from-request");
    }

    @Test
    @DisplayName("an explicit workspace wins over the request, for callers that carry no request context")
    void explicitWorkspaceWins() {
        requestInWorkspace("org-from-request");
        when(quotaService.checkQuotaForScope("42", "org-explicit", 1L)).thenReturn(QuotaStatus.OK);

        controller().check("42", "org-explicit", 1L);

        verify(quotaService).checkQuotaForScope("42", "org-explicit", 1L);
    }

    @Test
    @DisplayName("a blank workspace is treated as absent, not as a workspace named ''")
    void blankWorkspaceFallsBack() {
        requestInWorkspace("org-from-request");
        when(quotaService.checkQuotaForScope("42", "org-from-request", 1L)).thenReturn(QuotaStatus.OK);

        controller().check("42", "   ", 1L);

        verify(quotaService).checkQuotaForScope("42", "org-from-request", 1L);
    }

    @Test
    @DisplayName("a system tenant is allowed without consulting the quota, because the write never checks it either")
    void systemTenantIsAllowedUnchecked() {
        // validateQuota returns early for ids starting with "_" (e.g. _publications). Refusing
        // here would block a path the write would have accepted.
        ResponseEntity<Map<String, Object>> resp = controller().check("_publications", null, 1L);

        assertThat(resp.getBody()).containsEntry("allowed", true);
        verifyNoInteractions(quotaService);
    }

    // No soft-limit case here on purpose. Both branches of checkQuotaForScope
    // (QuotaService.checkQuota / checkOrganizationQuota) return only OK or HARD_LIMIT_REACHED;
    // SOFT_LIMIT_REACHED comes from getQuotaStatus(), which no check path calls. A test stubbing
    // it would assert its own stubbing and quietly imply a state this endpoint can never see.

    @Test
    @DisplayName("the byte count travels through, so a caller that knows its size can size its own probe")
    void passesTheByteCountThrough() {
        when(quotaService.checkQuotaForScope("42", null, 50_000_000L)).thenReturn(QuotaStatus.HARD_LIMIT_REACHED);

        assertThat(controller().check("42", null, 50_000_000L).getBody()).containsEntry("allowed", false);
        verify(quotaService).checkQuotaForScope("42", null, 50_000_000L);
    }

    @Test
    @DisplayName("a negative byte count is clamped to 0 rather than credited as free space")
    void negativeBytesAreClamped() {
        when(quotaService.checkQuotaForScope("42", null, 0L)).thenReturn(QuotaStatus.OK);

        controller().check("42", null, -500L);

        verify(quotaService).checkQuotaForScope("42", null, 0L);
    }
}
