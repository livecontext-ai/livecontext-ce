package com.apimarketplace.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RequestAuditContext")
class RequestAuditContextTest {

    @Mock private HttpServletRequest request;
    @Mock private IpHashService ipHashService;

    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    @DisplayName("none() is a valid context for non-HTTP callers - keyVersion=1 matches V260 DEFAULT, never null")
    void none_is_valid_for_background_callers() {
        RequestAuditContext ctx = RequestAuditContext.none();

        assertThat(ctx.ipHash()).isNull();
        assertThat(ctx.keyVersion()).isEqualTo(1);
        assertThat(ctx.userAgent()).isNull();
    }

    @Test
    @DisplayName("from() hashes the X-Forwarded-For left-most IP and forwards key_version + user_agent")
    void from_builds_full_context() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("LiveContext-CE/1.4.0");
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));

        RequestAuditContext ctx = RequestAuditContext.from(request, ipHashService, INSTALL);

        assertThat(ctx.ipHash()).isEqualTo("hash-v1");
        assertThat(ctx.keyVersion()).isEqualTo(1);
        assertThat(ctx.userAgent()).isEqualTo("LiveContext-CE/1.4.0");
    }

    @Test
    @DisplayName("truncatesUserAgentToV260ColumnWidth - guards against constraint violation on long UAs")
    void truncates_user_agent_to_256() {
        String longUa = "X".repeat(300);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(request.getHeader("User-Agent")).thenReturn(longUa);
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));

        RequestAuditContext ctx = RequestAuditContext.from(request, ipHashService, INSTALL);

        assertThat(ctx.userAgent()).hasSize(256);
    }

    @Test
    @DisplayName("from() returns ipHash=null but keyVersion=1 when request has no resolvable IP - never a null keyVersion")
    void from_handles_missing_ip() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn(null);
        when(request.getHeader("User-Agent")).thenReturn("LiveContext-CE/1.4.0");

        RequestAuditContext ctx = RequestAuditContext.from(request, ipHashService, INSTALL);

        assertThat(ctx.ipHash()).isNull();
        assertThat(ctx.keyVersion()).isEqualTo(1);
        assertThat(ctx.userAgent()).isEqualTo("LiveContext-CE/1.4.0");
    }
}
