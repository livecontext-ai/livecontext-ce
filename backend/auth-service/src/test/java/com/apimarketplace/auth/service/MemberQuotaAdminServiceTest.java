package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationMemberQuotaLimit;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberQuotaLimitRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.service.MemberQuotaAdminService.Outcome;
import com.apimarketplace.auth.service.MemberQuotaAdminService.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR11c - pin authorization matrix, write semantics, and audit-event
 * shape of {@link MemberQuotaAdminService}. The boundary is the most
 * security-sensitive surface in PR11: a misconfigured check lets a
 * MEMBER raise their own cap. Every branch gets a test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberQuotaAdminService - CRUD + auth matrix")
class MemberQuotaAdminServiceTest {

    @Mock private OrganizationMemberQuotaLimitRepository quotaRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationAuditService auditService;

    @Captor private ArgumentCaptor<Map<String, Object>> auditDataCaptor;

    private MemberQuotaAdminService service;

    private static final Long ACTOR_OWNER_ID = 7L;
    private static final Long ACTOR_ADMIN_ID = 8L;
    private static final Long ACTOR_MEMBER_ID = 9L;
    private static final Long TARGET_ID = 42L;
    private static final UUID ORG_ID = UUID.randomUUID();

    private Organization teamOrg;
    private User ownerUser;
    private User adminUser;
    private User memberUser;
    private User targetUser;

    @BeforeEach
    void setUp() {
        service = new MemberQuotaAdminService(quotaRepository, memberRepository,
                organizationRepository, auditService);
        ownerUser = u(ACTOR_OWNER_ID);
        adminUser = u(ACTOR_ADMIN_ID);
        memberUser = u(ACTOR_MEMBER_ID);
        targetUser = u(TARGET_ID);
        teamOrg = new Organization("team", "team", false, ownerUser);
        teamOrg.setId(ORG_ID);

        lenient().when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(teamOrg));
        // Default memberships - tests override as needed.
        lenient().when(memberRepository.findByOrganization_IdAndUser_Id(ORG_ID, ACTOR_OWNER_ID))
                .thenReturn(Optional.of(new OrganizationMember(teamOrg, ownerUser, OrganizationRole.OWNER, false)));
        lenient().when(memberRepository.findByOrganization_IdAndUser_Id(ORG_ID, ACTOR_ADMIN_ID))
                .thenReturn(Optional.of(new OrganizationMember(teamOrg, adminUser, OrganizationRole.ADMIN, false)));
        lenient().when(memberRepository.findByOrganization_IdAndUser_Id(ORG_ID, ACTOR_MEMBER_ID))
                .thenReturn(Optional.of(new OrganizationMember(teamOrg, memberUser, OrganizationRole.MEMBER, false)));
        lenient().when(memberRepository.findByOrganization_IdAndUser_Id(ORG_ID, TARGET_ID))
                .thenReturn(Optional.of(new OrganizationMember(teamOrg, targetUser, OrganizationRole.MEMBER, false)));
    }

    private User u(Long id) {
        User x = new User("u" + id, "u" + id + "@t.com", AuthProvider.KEYCLOAK, "kc-" + id);
        x.setId(id);
        return x;
    }

    // =====================================================================
    // AUTH MATRIX
    // =====================================================================

    @Test
    @DisplayName("OWNER actor → upsert succeeds")
    void ownerCanUpsert() {
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, TARGET_ID)).thenReturn(Optional.empty());
        when(quotaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Result<?> r = service.upsert(ACTOR_OWNER_ID, ORG_ID, TARGET_ID,
                new BigDecimal("100.0000"), null, null);
        assertThat(r.success()).isTrue();
        verify(auditService).record(eq(ORG_ID), eq(ACTOR_OWNER_ID),
                eq(OrganizationAuditEvent.Type.QUOTA_CAP_SET), any());
    }

    @Test
    @DisplayName("ADMIN actor → upsert succeeds (same authority as OWNER for quota mgmt)")
    void adminCanUpsert() {
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, TARGET_ID)).thenReturn(Optional.empty());
        when(quotaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Result<?> r = service.upsert(ACTOR_ADMIN_ID, ORG_ID, TARGET_ID,
                new BigDecimal("100.0000"), null, null);
        assertThat(r.success()).isTrue();
    }

    @Test
    @DisplayName("MEMBER actor → FORBIDDEN (cannot raise their own cap or others')")
    void memberCannotUpsert() {
        Result<?> r = service.upsert(ACTOR_MEMBER_ID, ORG_ID, TARGET_ID,
                new BigDecimal("9999.0000"), null, null);
        assertThat(r.success()).isFalse();
        assertThat(r.outcome()).isEqualTo(Outcome.FORBIDDEN);
        verify(quotaRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Non-member actor → FORBIDDEN (no membership row in target org)")
    void nonMemberCannotUpsert() {
        Long strangerId = 999L;
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_ID, strangerId))
                .thenReturn(Optional.empty());

        Result<?> r = service.upsert(strangerId, ORG_ID, TARGET_ID,
                new BigDecimal("100"), null, null);
        assertThat(r.outcome()).isEqualTo(Outcome.FORBIDDEN);
    }

    @Test
    @DisplayName("Null actor → FORBIDDEN (defensive - gateway must inject X-User-ID)")
    void nullActorIsForbidden() {
        assertThat(service.upsert(null, ORG_ID, TARGET_ID, BigDecimal.ONE, null, null).outcome())
                .isEqualTo(Outcome.FORBIDDEN);
    }

    // =====================================================================
    // ORG STATE
    // =====================================================================

    @Test
    @DisplayName("Unknown org → ORG_NOT_FOUND")
    void unknownOrgFails() {
        UUID ghost = UUID.randomUUID();
        when(organizationRepository.findById(ghost)).thenReturn(Optional.empty());
        when(memberRepository.findByOrganization_IdAndUser_Id(ghost, ACTOR_OWNER_ID))
                .thenReturn(Optional.of(new OrganizationMember(teamOrg, ownerUser, OrganizationRole.OWNER, false)));

        Result<?> r = service.upsert(ACTOR_OWNER_ID, ghost, TARGET_ID, BigDecimal.ONE, null, null);
        assertThat(r.outcome()).isEqualTo(Outcome.ORG_NOT_FOUND);
    }

    @Test
    @DisplayName("Soft-deleted org → ORG_NOT_FOUND (caps cannot be set on tombstoned orgs)")
    void softDeletedOrgFails() {
        teamOrg.setDeletedAt(LocalDateTime.now());

        Result<?> r = service.upsert(ACTOR_OWNER_ID, ORG_ID, TARGET_ID, BigDecimal.ONE, null, null);
        assertThat(r.outcome()).isEqualTo(Outcome.ORG_NOT_FOUND);
    }

    // =====================================================================
    // TARGET STATE
    // =====================================================================

    @Test
    @DisplayName("Target is not a member → TARGET_NOT_MEMBER")
    void unknownTargetFails() {
        Long strangerTarget = 1234L;
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_ID, strangerTarget))
                .thenReturn(Optional.empty());

        Result<?> r = service.upsert(ACTOR_OWNER_ID, ORG_ID, strangerTarget, BigDecimal.ONE, null, null);
        assertThat(r.outcome()).isEqualTo(Outcome.TARGET_NOT_MEMBER);
    }

    @Test
    @DisplayName("Target IS the org owner on UPSERT → CONFLICT (caps are for members)")
    void targetIsOwnerOnUpsertFails() {
        // Re-use the owner as the upsert target.
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_ID, ACTOR_OWNER_ID))
                .thenReturn(Optional.of(new OrganizationMember(teamOrg, ownerUser, OrganizationRole.OWNER, false)));

        Result<?> r = service.upsert(ACTOR_OWNER_ID, ORG_ID, ACTOR_OWNER_ID, BigDecimal.ONE, null, null);
        assertThat(r.outcome()).isEqualTo(Outcome.CONFLICT_TARGET_IS_OWNER);
        verify(quotaRepository, never()).save(any());
    }

    @Test
    @DisplayName("Target IS the org owner on GET → OK (reading an owner's empty cap is allowed)")
    void targetIsOwnerOnGetSucceeds() {
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, ACTOR_OWNER_ID)).thenReturn(Optional.empty());

        Result<Optional<OrganizationMemberQuotaLimit>> r =
                service.get(ACTOR_OWNER_ID, ORG_ID, ACTOR_OWNER_ID);
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEmpty();
    }

    // =====================================================================
    // WRITE SEMANTICS
    // =====================================================================

    @Test
    @DisplayName("Upsert NEW row → audit data carries isNew=true + null old values")
    void upsertNewCarriesIsNewFlag() {
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, TARGET_ID)).thenReturn(Optional.empty());
        when(quotaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsert(ACTOR_OWNER_ID, ORG_ID, TARGET_ID, new BigDecimal("100"), 50L, null);

        verify(auditService).record(eq(ORG_ID), eq(ACTOR_OWNER_ID),
                eq(OrganizationAuditEvent.Type.QUOTA_CAP_SET), auditDataCaptor.capture());
        Map<String, Object> data = auditDataCaptor.getValue();
        assertThat(data.get("isNew")).isEqualTo(true);
        assertThat(data.get("targetUserId")).isEqualTo(TARGET_ID);
        assertThat(data.get("oldPeriodCredits")).isNull();
        assertThat(data.get("newPeriodCredits")).isEqualTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("Upsert EXISTING row → audit data carries old+new diff + isNew=false")
    void upsertExistingCarriesDiff() {
        OrganizationMemberQuotaLimit existing =
                new OrganizationMemberQuotaLimit(ORG_ID, TARGET_ID, ACTOR_OWNER_ID);
        existing.setPeriodCredits(new BigDecimal("100"));
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, TARGET_ID)).thenReturn(Optional.of(existing));
        when(quotaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsert(ACTOR_OWNER_ID, ORG_ID, TARGET_ID, new BigDecimal("1000"), null, null);

        verify(auditService).record(any(), any(), any(), auditDataCaptor.capture());
        Map<String, Object> data = auditDataCaptor.getValue();
        assertThat(data.get("isNew")).isEqualTo(false);
        assertThat(data.get("oldPeriodCredits")).isEqualTo(new BigDecimal("100"));
        assertThat(data.get("newPeriodCredits")).isEqualTo(new BigDecimal("1000"));
    }

    // =====================================================================
    // REMOVE
    // =====================================================================

    @Test
    @DisplayName("Remove existing row → audit ORG_QUOTA_CAP_REMOVED with deletedCount=1")
    void removeExistingEmitsAudit() {
        when(quotaRepository.deleteByOrgIdAndUserId(ORG_ID, TARGET_ID)).thenReturn(1);

        Result<Integer> r = service.remove(ACTOR_OWNER_ID, ORG_ID, TARGET_ID);
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo(1);
        verify(auditService).record(eq(ORG_ID), eq(ACTOR_OWNER_ID),
                eq(OrganizationAuditEvent.Type.QUOTA_CAP_REMOVED), any());
    }

    @Test
    @DisplayName("Remove idempotent - no-op deletion writes NO audit event")
    void removeIdempotentSkipsAudit() {
        when(quotaRepository.deleteByOrgIdAndUserId(ORG_ID, TARGET_ID)).thenReturn(0);

        Result<Integer> r = service.remove(ACTOR_OWNER_ID, ORG_ID, TARGET_ID);
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo(0);
        // No audit row for a no-op - avoids noise on retry loops.
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    // =====================================================================
    // LIST
    // =====================================================================

    @Test
    @DisplayName("List by OWNER → returns all org cap rows")
    void listReturnsAllRows() {
        OrganizationMemberQuotaLimit a = new OrganizationMemberQuotaLimit(ORG_ID, 1L, ACTOR_OWNER_ID);
        OrganizationMemberQuotaLimit b = new OrganizationMemberQuotaLimit(ORG_ID, 2L, ACTOR_OWNER_ID);
        when(quotaRepository.findByOrgId(ORG_ID)).thenReturn(List.of(a, b));

        Result<List<OrganizationMemberQuotaLimit>> r = service.list(ACTOR_OWNER_ID, ORG_ID);
        assertThat(r.success()).isTrue();
        assertThat(r.value()).hasSize(2);
    }

    @Test
    @DisplayName("List by MEMBER → FORBIDDEN")
    void listByMemberForbidden() {
        Result<?> r = service.list(ACTOR_MEMBER_ID, ORG_ID);
        assertThat(r.outcome()).isEqualTo(Outcome.FORBIDDEN);
    }

    // =====================================================================
    // ROUND-2 AUDIT COVERAGE FIXES (SHOULD-FIX #4 + #5 + #1 symmetry)
    // =====================================================================

    @Test
    @DisplayName("GET by MEMBER → FORBIDDEN (read access also requires OWNER/ADMIN)")
    void getByMemberForbidden() {
        Result<?> r = service.get(ACTOR_MEMBER_ID, ORG_ID, TARGET_ID);
        assertThat(r.outcome()).isEqualTo(Outcome.FORBIDDEN);
    }

    @Test
    @DisplayName("GET against soft-deleted org → ORG_NOT_FOUND")
    void getAgainstSoftDeletedOrgFails() {
        teamOrg.setDeletedAt(LocalDateTime.now());
        Result<?> r = service.get(ACTOR_OWNER_ID, ORG_ID, TARGET_ID);
        assertThat(r.outcome()).isEqualTo(Outcome.ORG_NOT_FOUND);
    }

    @Test
    @DisplayName("REMOVE by MEMBER → FORBIDDEN")
    void removeByMemberForbidden() {
        Result<?> r = service.remove(ACTOR_MEMBER_ID, ORG_ID, TARGET_ID);
        assertThat(r.outcome()).isEqualTo(Outcome.FORBIDDEN);
    }

    @Test
    @DisplayName("Round-2 fix #1 - REMOVE on owner is REFUSED (symmetric with UPSERT)")
    void removeOnOwnerIsRefused() {
        // Use ownerId as the remove target - symmetric with the UPSERT rejection path.
        Result<Integer> r = service.remove(ACTOR_OWNER_ID, ORG_ID, ACTOR_OWNER_ID);
        assertThat(r.outcome())
                .as("REMOVE on org owner must be symmetric with UPSERT (CONFLICT_TARGET_IS_OWNER)")
                .isEqualTo(Outcome.CONFLICT_TARGET_IS_OWNER);
        verify(quotaRepository, never()).deleteByOrgIdAndUserId(any(), any());
    }

    @Test
    @DisplayName("Round-2 fix #5 - null targetUserId → TARGET_NOT_MEMBER (no NPE)")
    void nullTargetUserIdIsHandled() {
        // Programmatic-call path: HTTP layer wouldn't reach here with null, but
        // service is public and must be NPE-safe.
        Result<?> r = service.upsert(ACTOR_OWNER_ID, ORG_ID, null,
                new BigDecimal("100"), null, null);
        assertThat(r.outcome()).isEqualTo(Outcome.TARGET_NOT_MEMBER);
    }
}
