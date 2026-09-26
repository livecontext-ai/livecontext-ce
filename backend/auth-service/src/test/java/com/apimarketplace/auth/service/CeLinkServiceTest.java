package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.CeLinkAudit;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.CeLinkRegisterResponse;
import com.apimarketplace.auth.dto.CeLinkSummary;
import com.apimarketplace.auth.repository.CeLinkHeartbeatRepository;
import com.apimarketplace.auth.repository.CeLinkRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.plan.CeLinkAccess;
import com.apimarketplace.common.plan.CeLinkAccessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeLinkService")
class CeLinkServiceTest {

    @Mock private CeLinkRepository repository;
    @Mock private CeLinkHeartbeatRepository heartbeatRepository;
    @Mock private UserRepository userRepository;
    @Mock private CeLinkAuditService auditService;
    @Mock private CeLinkActiveRowCache activeRowCache;
    @Mock private CeLinkActiveRowCachePublisher cachePublisher;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private PlanResolutionService planResolutionService;

    private CeLinkService service;

    /** Synthetic audit context shared by all tests - keeps assertions readable. */
    private static final RequestAuditContext AUDIT =
            new RequestAuditContext("hash-abc", 1, "ua-test");

    private static final Long CALLER_ID = 42L;
    private static final Long OTHER_ID = 99L;
    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String CE_VERSION = "1.4.0";

    @BeforeEach
    void setUp() {
        service = newService(0);
        // Default: the governing plan of the caller is paid, so the pre-existing register tests
        // keep exercising the link logic. Lenient: collision / revoked paths never consult it.
        lenient().when(planResolutionService.resolveDefaultWorkspacePlan(CALLER_ID))
                .thenReturn(entitlement("PRO"));
    }

    private CeLinkService newService(long planCacheTtlSeconds) {
        return new CeLinkService(repository, heartbeatRepository, userRepository,
                auditService, activeRowCache, cachePublisher, eventPublisher,
                planResolutionService, planCacheTtlSeconds);
    }

    private static PlanResolutionService.DefaultWorkspacePlan entitlement(String planCode) {
        return PlanResolutionService.DefaultWorkspacePlan.of(planCode);
    }

    // ===== paid-plan gate: register + linkAccess =====

    @Nested
    @DisplayName("paid-plan gate")
    class PaidPlanGate {

        @Test
        @DisplayName("regression: a FREE account can no longer register a new install (403 plan required, nothing written)")
        void freeAccountCannotRegisterNewInstall() {
            when(repository.findById(INSTALL)).thenReturn(Optional.empty());
            when(userRepository.lockExistingForKeyShare(CALLER_ID)).thenReturn(Optional.of(CALLER_ID));
            when(planResolutionService.resolveDefaultWorkspacePlan(CALLER_ID)).thenReturn(entitlement("FREE"));

            CeLinkRegisterResponse response = service.register(CALLER_ID, INSTALL, CE_VERSION, "Mine", AUDIT);

            assertThat(response.registered()).isFalse();
            assertThat(response.isPlanRequired()).isTrue();
            assertThat(response.error()).isEqualTo("CLOUD_LINK_PLAN_REQUIRED");
            assertThat(response.planCode()).isEqualTo("FREE");
            verify(repository, never()).save(any());
            verifyNoInteractions(auditService, activeRowCache, cachePublisher);
        }

        @Test
        @DisplayName("CREDIT_PACK and an unknown plan code are not paid: register refused (fails closed)")
        void creditPackAndUnknownPlanAreRefused() {
            when(repository.findById(INSTALL)).thenReturn(Optional.empty());
            when(userRepository.lockExistingForKeyShare(CALLER_ID)).thenReturn(Optional.of(CALLER_ID));
            when(planResolutionService.resolveDefaultWorkspacePlan(CALLER_ID))
                    .thenReturn(entitlement("CREDIT_PACK"), entitlement("PLATINUM"));

            CeLinkRegisterResponse creditPack = service.register(CALLER_ID, INSTALL, CE_VERSION, "Mine", AUDIT);
            CeLinkRegisterResponse unknown = service.register(CALLER_ID, INSTALL, CE_VERSION, "Mine", AUDIT);

            assertThat(creditPack.isPlanRequired()).isTrue();
            assertThat(creditPack.planCode()).isEqualTo("CREDIT_PACK");
            assertThat(unknown.isPlanRequired()).isTrue();
            assertThat(unknown.planCode()).isEqualTo("PLATINUM");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("regression: an idempotent re-register by a FREE owner answers plan required, and the link is kept (not revoked)")
        void idempotentReRegisterRefusedWhenFree() {
            CeLink existing = new CeLink(INSTALL, CALLER_ID, "My laptop");
            when(repository.findById(INSTALL)).thenReturn(Optional.of(existing));
            when(planResolutionService.resolveDefaultWorkspacePlan(CALLER_ID)).thenReturn(entitlement("FREE"));

            CeLinkRegisterResponse response = service.register(CALLER_ID, INSTALL, CE_VERSION, "My laptop", AUDIT);

            assertThat(response.isPlanRequired()).isTrue();
            assertThat(existing.getStatus()).isEqualTo(CeLink.Status.ACTIVE);
            verify(repository, never()).save(any());
            verifyNoInteractions(auditService, eventPublisher);
        }

        @Test
        @DisplayName("a cross-user collision is still reported as ALREADY_BOUND + squat audit whatever the plan")
        void collisionTakesPrecedenceOverPlan() {
            when(repository.findById(INSTALL)).thenReturn(Optional.of(new CeLink(INSTALL, OTHER_ID, "Theirs")));

            CeLinkRegisterResponse response = service.register(CALLER_ID, INSTALL, CE_VERSION, "Mine", AUDIT);

            assertThat(response.error()).isEqualTo("ALREADY_BOUND");
            verifyNoInteractions(planResolutionService);
        }

        @Test
        @DisplayName("linkAccess: no active link row is NOT_LINKED and the plan is never consulted")
        void notLinkedDoesNotConsultPlan() {
            when(repository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.empty());

            assertThat(service.linkAccess(CALLER_ID, INSTALL)).isEqualTo(CeLinkAccessResult.notLinked());
            assertThat(service.linkAccess(null, INSTALL)).isEqualTo(CeLinkAccessResult.notLinked());
            verifyNoInteractions(planResolutionService);
        }

        @Test
        @DisplayName("linkAccess: a revoked link is NOT_LINKED")
        void revokedLinkIsNotLinked() {
            CeLink revoked = new CeLink(INSTALL, CALLER_ID, "X");
            revoked.revoke(CeLink.RevokeReason.USER, CALLER_ID);
            when(repository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.of(revoked));

            assertThat(service.linkAccess(CALLER_ID, INSTALL).access()).isEqualTo(CeLinkAccess.NOT_LINKED);
        }

        @Test
        @DisplayName("linkAccess: an active link on a paid plan is ACTIVE with that plan (a TEAM workspace member too)")
        void activeLinkOnPaidPlan() {
            when(repository.findByInstallIdAndUserId(INSTALL, CALLER_ID))
                    .thenReturn(Optional.of(new CeLink(INSTALL, CALLER_ID, "L")));
            // The resolver answers with the plan of the current workspace OWNER: a member of a
            // paid TEAM workspace gets TEAM here, which is what decides.
            when(planResolutionService.resolveDefaultWorkspacePlan(CALLER_ID)).thenReturn(entitlement("TEAM"));

            assertThat(service.linkAccess(CALLER_ID, INSTALL)).isEqualTo(CeLinkAccessResult.active("TEAM"));
        }

        @Test
        @DisplayName("linkAccess: suspended while FREE, restored by itself once the account pays (no re-link)")
        void suspendedThenRestoredAfterUpgrade() {
            when(repository.findByInstallIdAndUserId(INSTALL, CALLER_ID))
                    .thenReturn(Optional.of(new CeLink(INSTALL, CALLER_ID, "L")));
            when(planResolutionService.resolveDefaultWorkspacePlan(CALLER_ID))
                    .thenReturn(entitlement("FREE"), entitlement("STARTER"));

            assertThat(service.linkAccess(CALLER_ID, INSTALL)).isEqualTo(CeLinkAccessResult.planRequired("FREE"));
            assertThat(service.linkAccess(CALLER_ID, INSTALL)).isEqualTo(CeLinkAccessResult.active("STARTER"));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a blank plan code from the resolver reads as FREE (plan required)")
        void blankPlanReadsAsFree() {
            when(planResolutionService.resolveDefaultWorkspacePlan(CALLER_ID)).thenReturn(entitlement(null));

            assertThat(service.planAccess(CALLER_ID)).isEqualTo(CeLinkAccessResult.planRequired("FREE"));
        }

        @Test
        @DisplayName("the plan cache serves a repeat call without resolving again")
        void planCacheServesRepeatCalls() {
            CeLinkService cached = newService(30);

            cached.planAccess(CALLER_ID);
            cached.planAccess(CALLER_ID);

            verify(planResolutionService, times(1)).resolveDefaultWorkspacePlan(CALLER_ID);
        }

        @Test
        @DisplayName("regression: the decision ignores the request workspace - a paid non-default X-Organization-ID does not change it, one cache entry per user")
        void decisionIgnoresRequestWorkspace() {
            CeLinkService cached = newService(30);
            try {
                withWorkspace("11111111-0000-0000-0000-000000000001");
                cached.planAccess(CALLER_ID);
                withWorkspace("11111111-0000-0000-0000-000000000002");
                cached.planAccess(CALLER_ID);
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }

            // Old code keyed the cache by (user, workspace) and resolved the request workspace:
            // two lookups. The default-workspace rule makes the user the whole key.
            verify(planResolutionService, times(1)).resolveDefaultWorkspacePlan(CALLER_ID);
            verify(planResolutionService, never()).resolveActiveOrgEntitlement(any());
        }

        @Test
        @DisplayName("regression: a failed plan lookup refuses THIS call but is never cached - the next call is re-read and passes")
        void failedLookupIsRefusedButNotCached() {
            CeLinkService cached = newService(30);
            when(planResolutionService.resolveDefaultWorkspacePlan(CALLER_ID))
                    .thenReturn(PlanResolutionService.DefaultWorkspacePlan.failed(), entitlement("PRO"));

            CeLinkAccessResult first = cached.planAccess(CALLER_ID);
            CeLinkAccessResult second = cached.planAccess(CALLER_ID);

            assertThat(first.access()).isEqualTo(CeLinkAccess.PLAN_REQUIRED);
            assertThat(first.planCode()).isNull();
            assertThat(second).isEqualTo(CeLinkAccessResult.active("PRO"));
        }

        @Test
        @DisplayName("regression: an unpaid answer is never cached, so paying lands on the very next call (pay then continue)")
        void unpaidAnswerIsNotCachedSoPaymentLandsImmediately() {
            CeLinkService cached = newService(30);
            when(planResolutionService.resolveDefaultWorkspacePlan(CALLER_ID))
                    .thenReturn(entitlement("FREE"), entitlement("PRO"));

            assertThat(cached.planAccess(CALLER_ID)).isEqualTo(CeLinkAccessResult.planRequired("FREE"));
            assertThat(cached.planAccess(CALLER_ID)).isEqualTo(CeLinkAccessResult.active("PRO"));
        }

        private void withWorkspace(String orgId) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Organization-ID", orgId);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        }
    }

    // ===== register() =====

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("inserts a new row + emits REGISTER audit when install_id is free")
        void inserts_new_link_when_free() {
            when(repository.findById(INSTALL)).thenReturn(Optional.empty());
            when(userRepository.lockExistingForKeyShare(CALLER_ID)).thenReturn(Optional.of(CALLER_ID));

            CeLinkRegisterResponse response = service.register(CALLER_ID, INSTALL, CE_VERSION, "My laptop", AUDIT);

            assertThat(response.registered()).isTrue();
            assertThat(response.scopes()).isEqualTo("catalog,marketplace");
            assertThat(response.boundToEmail()).isNull();

            ArgumentCaptor<CeLink> saved = ArgumentCaptor.forClass(CeLink.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getInstallId()).isEqualTo(INSTALL);
            assertThat(saved.getValue().getUserId()).isEqualTo(CALLER_ID);
            assertThat(saved.getValue().getLabel()).isEqualTo("My laptop");
            assertThat(saved.getValue().getStatus()).isEqualTo(CeLink.Status.ACTIVE);

            verify(auditService).record(
                    eq(INSTALL), eq(CALLER_ID),
                    eq(CeLinkAudit.ActorRole.OWNER), eq(CeLinkAudit.Event.REGISTER),
                    eq(1), any(), any(), any(Map.class)
            );
            verify(activeRowCache).invalidate(CALLER_ID);
            verify(cachePublisher).broadcastInvalidate(CALLER_ID);   // sibling replicas drop their cache entry too
        }

        @Test
        @DisplayName("ce-link register for a deleted user: 401, nothing written, no audit, not a foreign-key 500")
        void register_for_deleted_user_is_unauthorized_and_writes_nothing() {
            // Same defect as POST /api/changelog/seen (prod 2026-09-22): the gateway's cached user
            // resolution outlives the account, the header names an id with no auth.users row, and
            // the insert trips ce_link's foreign key.
            when(repository.findById(INSTALL)).thenReturn(Optional.empty());
            when(userRepository.lockExistingForKeyShare(CALLER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.register(CALLER_ID, INSTALL, CE_VERSION, "My laptop", AUDIT))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

            verify(repository, never()).save(any());
            verifyNoInteractions(auditService);
            verifyNoInteractions(activeRowCache);
        }

        @Test
        @DisplayName("falls back to default label when caller sends blank")
        void default_label_when_blank() {
            when(repository.findById(INSTALL)).thenReturn(Optional.empty());
            when(userRepository.lockExistingForKeyShare(CALLER_ID)).thenReturn(Optional.of(CALLER_ID));

            service.register(CALLER_ID, INSTALL, CE_VERSION, "  ", AUDIT);

            ArgumentCaptor<CeLink> saved = ArgumentCaptor.forClass(CeLink.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getLabel()).isEqualTo("CE install");
        }

        @Test
        @DisplayName("returns 201 + no new audit row when same user retries an ACTIVE link (idempotent)")
        void idempotent_retry_does_not_audit() {
            CeLink existing = new CeLink(INSTALL, CALLER_ID, "My laptop");
            when(repository.findById(INSTALL)).thenReturn(Optional.of(existing));

            CeLinkRegisterResponse response = service.register(CALLER_ID, INSTALL, CE_VERSION, "My laptop", AUDIT);

            assertThat(response.registered()).isTrue();
            assertThat(response.scopes()).isEqualTo("catalog,marketplace");
            verify(repository, never()).save(any());
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("returns 409 ALREADY_BOUND with masked caller email when same user previously revoked")
        void same_user_revoked_returns_masked_email() {
            CeLink existing = new CeLink(INSTALL, CALLER_ID, "Old laptop");
            existing.revoke(CeLink.RevokeReason.USER, CALLER_ID);
            when(repository.findById(INSTALL)).thenReturn(Optional.of(existing));
            User caller = new User();
            caller.setEmail("user@example.com");
            when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(caller));

            CeLinkRegisterResponse response = service.register(CALLER_ID, INSTALL, CE_VERSION, "Reset", AUDIT);

            assertThat(response.registered()).isFalse();
            assertThat(response.error()).isEqualTo("ALREADY_BOUND");
            assertThat(response.boundToEmail()).isEqualTo("ad***@gmail.com");
            verify(repository, never()).save(any());
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("returns 409 ALREADY_BOUND with null email when install_id is owned by a different user (no info leak)")
        void cross_user_collision_does_not_leak_email() {
            CeLink existing = new CeLink(INSTALL, OTHER_ID, "Their laptop");
            when(repository.findById(INSTALL)).thenReturn(Optional.of(existing));

            CeLinkRegisterResponse response = service.register(CALLER_ID, INSTALL, CE_VERSION, "Mine", AUDIT);

            assertThat(response.registered()).isFalse();
            assertThat(response.error()).isEqualTo("ALREADY_BOUND");
            assertThat(response.boundToEmail()).isNull();
            verifyNoInteractions(userRepository);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("crossUserCollisionWritesSuspectedAudit - squat attempt is recorded as SUSPECTED_CROSS_USER_RESET (doc §7 threat)")
        void crossUserCollisionWritesSuspectedAudit() {
            CeLink existing = new CeLink(INSTALL, OTHER_ID, "Their laptop");
            when(repository.findById(INSTALL)).thenReturn(Optional.of(existing));

            service.register(CALLER_ID, INSTALL, CE_VERSION, "Mine", AUDIT);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> metadataCaptor =
                    (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
            verify(auditService).record(
                    eq(INSTALL), eq(CALLER_ID),
                    eq(CeLinkAudit.ActorRole.OWNER),
                    eq(CeLinkAudit.Event.SUSPECTED_CROSS_USER_RESET),
                    eq(1), any(), any(),
                    metadataCaptor.capture()
            );
            assertThat(metadataCaptor.getValue())
                    .containsEntry("owned_by_user_id", OTHER_ID)
                    .doesNotContainKey("ce_version")     // no PII / no caller info leak in metadata
                    .doesNotContainKey("label");
        }
    }

    // ===== revoke() =====

    @Nested
    @DisplayName("revoke")
    class Revoke {

        @Test
        @DisplayName("returns false (404) and writes nothing when install_id is not in caller's namespace")
        void unknown_install_returns_false() {
            when(repository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.empty());

            boolean ok = service.revoke(CALLER_ID, INSTALL, AUDIT);

            assertThat(ok).isFalse();
            verify(repository, never()).save(any());
            verifyNoInteractions(auditService);
            // 404 must NOT publish CeLinkRevokedEvent - otherwise KcAdminLogoutService
            // would fire a KC logout for a revoke that never happened.
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("idempotent - returns true + no audit row for already-revoked link")
        void already_revoked_does_not_audit() {
            CeLink revoked = new CeLink(INSTALL, CALLER_ID, "X");
            revoked.revoke(CeLink.RevokeReason.USER, CALLER_ID);
            when(repository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.of(revoked));

            boolean ok = service.revoke(CALLER_ID, INSTALL, AUDIT);

            assertThat(ok).isTrue();
            verify(repository, never()).save(any());
            verifyNoInteractions(auditService);
        }

        @Test
        @DisplayName("transitions ACTIVE -> REVOKED, persists, emits REVOKE audit with reason=USER")
        void active_to_revoked_with_audit() {
            CeLink active = new CeLink(INSTALL, CALLER_ID, "X");
            when(repository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.of(active));

            boolean ok = service.revoke(CALLER_ID, INSTALL, AUDIT);

            assertThat(ok).isTrue();
            assertThat(active.getStatus()).isEqualTo(CeLink.Status.REVOKED);
            assertThat(active.getRevokeReason()).isEqualTo(CeLink.RevokeReason.USER);
            assertThat(active.getRevokedByUserId()).isEqualTo(CALLER_ID);
            verify(repository).save(active);
            verify(auditService).record(
                    eq(INSTALL), eq(CALLER_ID),
                    eq(CeLinkAudit.ActorRole.OWNER), eq(CeLinkAudit.Event.REVOKE),
                    eq(1), any(), any(), any(Map.class)
            );
            verify(activeRowCache).invalidate(CALLER_ID);
            verify(cachePublisher).broadcastInvalidate(CALLER_ID);   // sibling replicas drop their cache entry too
            // KC session-logout side-effect rides on CeLinkRevokedEvent (AFTER_COMMIT)
            // so listeners never act on a rolled-back revoke.
            verify(eventPublisher).publishEvent(new CeLinkRevokedEvent(CALLER_ID, INSTALL));
        }

        @Test
        @DisplayName("doesNotPublishRevokedEventOnAlreadyRevokedLink - idempotent revoke must not fire KC logout twice")
        void doesNotPublishRevokedEventOnAlreadyRevokedLink() {
            CeLink revoked = new CeLink(INSTALL, CALLER_ID, "X");
            revoked.revoke(CeLink.RevokeReason.USER, CALLER_ID);
            when(repository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.of(revoked));

            service.revoke(CALLER_ID, INSTALL, AUDIT);

            verifyNoInteractions(eventPublisher);
        }
    }

    // ===== mine() =====

    @Test
    @DisplayName("mine returns paged ACTIVE links with null heartbeat fields when no heartbeat row exists yet")
    void mine_maps_to_summary_without_heartbeat() {
        Pageable page = PageRequest.of(0, 10);
        CeLink row = new CeLink(INSTALL, CALLER_ID, "Laptop");
        when(repository.findByUserIdAndStatus(CALLER_ID, CeLink.Status.ACTIVE, page))
                .thenReturn(new PageImpl<>(List.of(row), page, 1));
        when(heartbeatRepository.findAllById(List.of(INSTALL))).thenReturn(List.of());

        Page<CeLinkSummary> result = service.mine(CALLER_ID, page);

        assertThat(result.getContent()).hasSize(1);
        CeLinkSummary summary = result.getContent().get(0);
        assertThat(summary.installId()).isEqualTo(INSTALL);
        assertThat(summary.label()).isEqualTo("Laptop");
        assertThat(summary.status()).isEqualTo("ACTIVE");
        assertThat(summary.lastSeenAt()).isNull();
        assertThat(summary.lastSeenCeVersion()).isNull();
    }

    @Test
    @DisplayName("mineEnrichesSummariesWithHeartbeatRow - last_seen_at + last_seen_ce_version surface from the joined hot table")
    void mineEnrichesSummariesWithHeartbeatRow() {
        Pageable page = PageRequest.of(0, 10);
        CeLink row = new CeLink(INSTALL, CALLER_ID, "Laptop");
        java.time.Instant seenAt = java.time.Instant.parse("2026-05-19T14:00:00Z");
        com.apimarketplace.auth.domain.CeLinkHeartbeat hb =
                new com.apimarketplace.auth.domain.CeLinkHeartbeat(INSTALL, seenAt, "h", 1, "1.4.2");
        when(repository.findByUserIdAndStatus(CALLER_ID, CeLink.Status.ACTIVE, page))
                .thenReturn(new PageImpl<>(List.of(row), page, 1));
        when(heartbeatRepository.findAllById(List.of(INSTALL))).thenReturn(List.of(hb));

        Page<CeLinkSummary> result = service.mine(CALLER_ID, page);

        CeLinkSummary summary = result.getContent().get(0);
        assertThat(summary.lastSeenAt()).isEqualTo(seenAt);
        assertThat(summary.lastSeenCeVersion()).isEqualTo("1.4.2");
    }

    @Test
    @DisplayName("mineSkipsHeartbeatJoinOnEmptyPage - avoids a wasted bulk findAllById([]) round-trip")
    void mineSkipsHeartbeatJoinOnEmptyPage() {
        Pageable page = PageRequest.of(0, 10);
        when(repository.findByUserIdAndStatus(CALLER_ID, CeLink.Status.ACTIVE, page))
                .thenReturn(new PageImpl<>(List.of(), page, 0));

        Page<CeLinkSummary> result = service.mine(CALLER_ID, page);

        assertThat(result.getContent()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(heartbeatRepository);
    }

    // ===== userHasAnyActiveLink - mandatory-header gate (§1 #4) =====

    @Test
    @DisplayName("userHasAnyActiveLink reads through the Caffeine cache, falling back to the repo on cache miss")
    void userHasAnyActiveLinkReadsThroughCache() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Function<Long, Boolean>> loaderCaptor =
                (ArgumentCaptor<java.util.function.Function<Long, Boolean>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(java.util.function.Function.class);
        when(activeRowCache.get(eq(CALLER_ID), loaderCaptor.capture())).thenAnswer(invocation ->
                loaderCaptor.getValue().apply(CALLER_ID));
        when(repository.userHasAnyActiveLink(CALLER_ID)).thenReturn(true);

        assertThat(service.userHasAnyActiveLink(CALLER_ID)).isTrue();
        verify(repository).userHasAnyActiveLink(CALLER_ID);   // cache miss â†’ loader invoked
    }

    // ===== maskEmail - guards against information disclosure (§1 #16) =====

    @Test
    @DisplayName("userOwnsActiveLink returns true only for the caller's active install")
    void userOwnsActiveLinkRequiresMatchingActiveInstall() {
        CeLink active = new CeLink(INSTALL, CALLER_ID, "Laptop");
        when(repository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.of(active));

        assertThat(service.userOwnsActiveLink(CALLER_ID, INSTALL)).isTrue();
    }

    @Test
    @DisplayName("userOwnsActiveLink rejects revoked or non-owned installs")
    void userOwnsActiveLinkRejectsRevokedOrMissingInstall() {
        CeLink revoked = new CeLink(INSTALL, CALLER_ID, "Laptop");
        revoked.revoke(CeLink.RevokeReason.USER, CALLER_ID);
        when(repository.findByInstallIdAndUserId(INSTALL, CALLER_ID)).thenReturn(Optional.of(revoked));
        when(repository.findByInstallIdAndUserId(INSTALL, OTHER_ID)).thenReturn(Optional.empty());

        assertThat(service.userOwnsActiveLink(CALLER_ID, INSTALL)).isFalse();
        assertThat(service.userOwnsActiveLink(OTHER_ID, INSTALL)).isFalse();
        assertThat(service.userOwnsActiveLink(null, INSTALL)).isFalse();
        assertThat(service.userOwnsActiveLink(CALLER_ID, null)).isFalse();
    }

    @Nested
    @DisplayName("maskEmail")
    class MaskEmail {

        @Test
        @DisplayName("two-char local part is masked with 'xx***@domain' pattern")
        void typical_email() {
            assertThat(CeLinkService.maskEmail("user@example.com")).isEqualTo("ad***@gmail.com");
        }

        @Test
        @DisplayName("local part of length <= 2 has every char redacted (no leak of small prefix)")
        void short_local_part_redacted() {
            assertThat(CeLinkService.maskEmail("a@x.io")).isEqualTo("*@x.io");
            assertThat(CeLinkService.maskEmail("ab@x.io")).isEqualTo("**@x.io");
        }

        @Test
        @DisplayName("malformed input (no @) returns *** sentinel, never the raw value")
        void malformed_returns_sentinel() {
            assertThat(CeLinkService.maskEmail("noatsign")).isEqualTo("***");
            assertThat(CeLinkService.maskEmail("@leading")).isEqualTo("***");
        }

        @Test
        @DisplayName("null input returns null (caller treats as 'no boundToEmail in response')")
        void null_is_null() {
            assertThat(CeLinkService.maskEmail(null)).isNull();
        }
    }
}

