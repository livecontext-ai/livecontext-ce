package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.CeLinkEntitlements;
import com.apimarketplace.auth.dto.CeLinkHeartbeatRequest;
import com.apimarketplace.auth.dto.CeLinkRegisterRequest;
import com.apimarketplace.auth.dto.CeLinkRegisterResponse;
import com.apimarketplace.auth.dto.CeLinkSummary;
import com.apimarketplace.auth.service.CeLinkEntitlementsService;
import com.apimarketplace.auth.service.CeLinkHeartbeatService;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.IpHashService;
import com.apimarketplace.auth.service.RequestAuditContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Thin-controller test - exercises the HTTP-shape contract (status codes,
 * header pickup, request→service argument mapping). Business-logic branches
 * live in {@link com.apimarketplace.auth.service.CeLinkServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CeLinkController")
class CeLinkControllerTest {

    @Mock private CeLinkService service;
    @Mock private CeLinkHeartbeatService heartbeatService;
    @Mock private IpHashService ipHashService;
    @Mock private CeLinkEntitlementsService entitlementsService;
    @Mock private com.apimarketplace.auth.service.CeLinkRewardReadService rewardReadService;
    @Mock private HttpServletRequest httpRequest;

    private CeLinkController controller;

    private static final Long CALLER_ID = 42L;
    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        controller = new CeLinkController(service, heartbeatService, ipHashService, entitlementsService, rewardReadService);
    }

    @Test
    @DisplayName("GET /{installId}/entitlements returns 200 + the bound account's plan, scoped to the caller")
    void entitlements_returns_bound_plan() {
        when(entitlementsService.entitlementsForCaller(CALLER_ID, INSTALL))
                .thenReturn(new CeLinkEntitlements("PRO", CALLER_ID, 2, "monthly"));

        ResponseEntity<CeLinkEntitlements> resp = controller.entitlements(CALLER_ID, INSTALL);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().planCode()).isEqualTo("PRO");
        assertThat(resp.getBody().creditTierIndex()).isEqualTo(2);
        assertThat(resp.getBody().cadence()).isEqualTo("monthly");
        assertThat(resp.getBody().hasSubscription()).isTrue();
        verify(entitlementsService).entitlementsForCaller(CALLER_ID, INSTALL);
    }

    @Test
    @DisplayName("POST /register returns 201 + body when service reports registered=true")
    void register_returns_201_when_registered() {
        CeLinkRegisterRequest body = new CeLinkRegisterRequest(INSTALL, "1.4.0", "Laptop");
        CeLinkRegisterResponse ok = CeLinkRegisterResponse.ok("catalog,marketplace");
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(service.register(eq(CALLER_ID), eq(INSTALL), eq("1.4.0"), eq("Laptop"),
                any(RequestAuditContext.class))).thenReturn(ok);

        ResponseEntity<CeLinkRegisterResponse> response = controller.register(CALLER_ID, body, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(ok);
    }

    @Test
    @DisplayName("registerForwardsIpAuditContextToService - ip + key_version flow into the REGISTER audit row")
    void registerForwardsIpAuditContextToService() {
        CeLinkRegisterRequest body = new CeLinkRegisterRequest(INSTALL, "1.4.0", "Laptop");
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(httpRequest.getHeader("User-Agent")).thenReturn("LiveContext-CE/1.4.0");
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));
        when(service.register(eq(CALLER_ID), eq(INSTALL), eq("1.4.0"), eq("Laptop"),
                any(RequestAuditContext.class)))
                .thenReturn(CeLinkRegisterResponse.ok("catalog,marketplace"));

        controller.register(CALLER_ID, body, httpRequest);

        ArgumentCaptor<RequestAuditContext> auditCaptor = ArgumentCaptor.forClass(RequestAuditContext.class);
        verify(service).register(eq(CALLER_ID), eq(INSTALL), eq("1.4.0"), eq("Laptop"), auditCaptor.capture());
        assertThat(auditCaptor.getValue().ipHash()).isEqualTo("hash-v1");
        assertThat(auditCaptor.getValue().keyVersion()).isEqualTo(1);
        assertThat(auditCaptor.getValue().userAgent()).isEqualTo("LiveContext-CE/1.4.0");
    }

    @Test
    @DisplayName("POST /register returns 409 + ALREADY_BOUND body when install_id is taken")
    void register_returns_409_when_already_bound() {
        CeLinkRegisterRequest body = new CeLinkRegisterRequest(INSTALL, "1.4.0", null);
        CeLinkRegisterResponse alreadyBound = CeLinkRegisterResponse.alreadyBound("lu***@gmail.com");
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(service.register(eq(CALLER_ID), eq(INSTALL), eq("1.4.0"), eq(null),
                any(RequestAuditContext.class))).thenReturn(alreadyBound);

        ResponseEntity<CeLinkRegisterResponse> response = controller.register(CALLER_ID, body, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(alreadyBound);
        assertThat(response.getBody().error()).isEqualTo("ALREADY_BOUND");
        assertThat(response.getBody().boundToEmail()).isEqualTo("lu***@gmail.com");
    }

    @Test
    @DisplayName("GET /mine forwards pageable to service and returns its Page")
    void mine_forwards_pageable() {
        Pageable page = PageRequest.of(2, 5);
        Page<CeLinkSummary> servicePage = new PageImpl<>(
                List.of(new CeLinkSummary(INSTALL, "Laptop", "ACTIVE", "catalog,marketplace",
                        java.time.Instant.now(), null, null)),
                page, 1);
        when(service.mine(CALLER_ID, page)).thenReturn(servicePage);

        ResponseEntity<Page<CeLinkSummary>> response = controller.mine(CALLER_ID, page);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(servicePage);
    }

    @Test
    @DisplayName("DELETE /{installId} returns 204 when service revoked the link")
    void revoke_returns_204_when_revoked() {
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(service.revoke(eq(CALLER_ID), eq(INSTALL), any(RequestAuditContext.class))).thenReturn(true);

        ResponseEntity<Void> response = controller.revoke(CALLER_ID, INSTALL, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).revoke(eq(CALLER_ID), eq(INSTALL), any(RequestAuditContext.class));
    }

    @Test
    @DisplayName("revokeForwardsIpAuditContextToService - ip + ua + key_version flow into the REVOKE audit row")
    void revokeForwardsIpAuditContextToService() {
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(httpRequest.getHeader("User-Agent")).thenReturn("LiveContext-CE/1.4.0");
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));
        when(service.revoke(eq(CALLER_ID), eq(INSTALL), any(RequestAuditContext.class))).thenReturn(true);

        controller.revoke(CALLER_ID, INSTALL, httpRequest);

        ArgumentCaptor<RequestAuditContext> auditCaptor = ArgumentCaptor.forClass(RequestAuditContext.class);
        verify(service).revoke(eq(CALLER_ID), eq(INSTALL), auditCaptor.capture());
        assertThat(auditCaptor.getValue().ipHash()).isEqualTo("hash-v1");
        assertThat(auditCaptor.getValue().keyVersion()).isEqualTo(1);
        assertThat(auditCaptor.getValue().userAgent()).isEqualTo("LiveContext-CE/1.4.0");
    }

    @Test
    @DisplayName("DELETE /{installId} returns 404 when install_id is not in caller's namespace")
    void revoke_returns_404_when_unknown() {
        when(ipHashService.hashWithCurrent(INSTALL, "203.0.113.5"))
                .thenReturn(new IpHashService.HashResult("hash-v1", 1));
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(service.revoke(eq(CALLER_ID), eq(INSTALL), any(RequestAuditContext.class))).thenReturn(false);

        ResponseEntity<Void> response = controller.revoke(CALLER_ID, INSTALL, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /heartbeat returns 204 when service reports OK")
    void heartbeat_returns_204_when_ok() {
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(heartbeatService.heartbeat(CALLER_ID, INSTALL, "1.4.0", "203.0.113.5"))
                .thenReturn(CeLinkHeartbeatService.Outcome.OK);

        ResponseEntity<Void> response = controller.heartbeat(
                CALLER_ID, INSTALL, new CeLinkHeartbeatRequest("1.4.0"), httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("POST /heartbeat returns 404 when install_id is not in caller's namespace")
    void heartbeat_returns_404_when_not_found() {
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(heartbeatService.heartbeat(CALLER_ID, INSTALL, "1.4.0", "203.0.113.5"))
                .thenReturn(CeLinkHeartbeatService.Outcome.NOT_FOUND);

        ResponseEntity<Void> response = controller.heartbeat(
                CALLER_ID, INSTALL, new CeLinkHeartbeatRequest("1.4.0"), httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("heartbeatReturns410WhenRevoked - signals the CE to STOP heartbeating (doc §3.5)")
    void heartbeatReturns410WhenRevoked() {
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(heartbeatService.heartbeat(CALLER_ID, INSTALL, "1.4.0", "203.0.113.5"))
                .thenReturn(CeLinkHeartbeatService.Outcome.REVOKED);

        ResponseEntity<Void> response = controller.heartbeat(
                CALLER_ID, INSTALL, new CeLinkHeartbeatRequest("1.4.0"), httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }
}
