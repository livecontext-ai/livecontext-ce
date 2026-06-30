package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationMemberQuotaLimit;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.OrganizationMemberQuotaLimitRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.MemberQuotaService.CapDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * PR11 - pin {@link MemberQuotaService#checkCreditsCap} against the cap
 * contract. The decision matrix has 9 distinct branches; each gets a test.
 *
 * <p>Hot-path safety is non-negotiable here: a NPE or DB hiccup in the
 * cap check MUST allow the consume through, never bubble up. The cap is
 * a soft guard layered on top of the existing balance check, not a
 * security boundary.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberQuotaService.checkCreditsCap")
class MemberQuotaServiceTest {

    @Mock private OrganizationMemberQuotaLimitRepository quotaRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private CreditLedgerRepository ledgerRepository;

    private MemberQuotaService service;

    private static final Long MEMBER_ID = 42L;
    private static final Long OWNER_ID = 7L;
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final LocalDateTime PERIOD_START = LocalDateTime.now().minusDays(15);

    private User member;
    private User owner;
    private Organization teamOrg;
    private OrganizationMember teamMembership;

    @BeforeEach
    void setUp() {
        service = new MemberQuotaService(quotaRepository, memberRepository,
                subscriptionRepository, ledgerRepository);
        member = new User("m", "m@test.com", AuthProvider.KEYCLOAK, "kc-m");
        member.setId(MEMBER_ID);
        owner = new User("o", "o@test.com", AuthProvider.KEYCLOAK, "kc-o");
        owner.setId(OWNER_ID);
        teamOrg = new Organization("team", "team", false, owner);
        teamOrg.setId(ORG_ID);
        teamMembership = new OrganizationMember(teamOrg, member, OrganizationRole.MEMBER, true);

        Subscription ownerSub = new Subscription();
        ownerSub.setCurrentPeriodStart(PERIOD_START);
        lenient().when(subscriptionRepository.findActiveByUserId(OWNER_ID))
                .thenReturn(Optional.of(ownerSub));
    }

    @Test
    @DisplayName("null executor → allow (defensive - caller may pass null on unauth path)")
    void nullExecutorAllows() {
        assertThat(service.checkCreditsCap(null, BigDecimal.ONE).allowed()).isTrue();
        verify(memberRepository, never()).findByUser_IdAndIsDefaultTrue(any());
    }

    @Test
    @DisplayName("null / zero / negative additionalCost → allow (no cost to gate)")
    void zeroCostAllows() {
        assertThat(service.checkCreditsCap(MEMBER_ID, null).allowed()).isTrue();
        assertThat(service.checkCreditsCap(MEMBER_ID, BigDecimal.ZERO).allowed()).isTrue();
        assertThat(service.checkCreditsCap(MEMBER_ID, new BigDecimal("-1")).allowed()).isTrue();
        verify(memberRepository, never()).findByUser_IdAndIsDefaultTrue(any());
    }

    @Test
    @DisplayName("no default org → allow (degenerate state, can't determine quota scope)")
    void noDefaultOrgAllows() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID)).thenReturn(Optional.empty());
        assertThat(service.checkCreditsCap(MEMBER_ID, BigDecimal.ONE).allowed()).isTrue();
    }

    @Test
    @DisplayName("personal org → still proceeds to quota lookup (ADR-009: caps apply on any org, owner-is-executor still short-circuits)")
    void personalOrgOwnedByExecutorAllowsViaOwnerShortcut() {
        // ADR-009: the isPersonal() short-circuit was dropped - caps apply
        // to any org. But the executor owns this personal org, so the
        // owner-is-executor branch still fires and skips the quota query.
        Organization personal = new Organization("personal", "personal", true, member);
        personal.setId(UUID.randomUUID());
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID))
                .thenReturn(Optional.of(new OrganizationMember(personal, member, OrganizationRole.OWNER, true)));

        assertThat(service.checkCreditsCap(MEMBER_ID, BigDecimal.ONE).allowed()).isTrue();
        verify(quotaRepository, never()).findByOrgIdAndUserId(any(), any());
        verify(subscriptionRepository, never()).findActiveByUserId(any());
    }

    @Test
    @DisplayName("personal-flagged org owned by someone else → cap STILL applies (ADR-009 removed the personal-org bypass)")
    void personalOrgOwnedByOtherEnforcesCap() {
        // Synthetic case: personal-flagged org but owner != executor (rare,
        // but possible after a re-org). Pre-ADR-009, isPersonal() would
        // allow through unconditionally; post-ADR-009 the cap must fire.
        Organization personalOfOther = new Organization("personal-of-other", "personal-of-other", true, owner);
        personalOfOther.setId(ORG_ID);
        OrganizationMember membership = new OrganizationMember(personalOfOther, member, OrganizationRole.MEMBER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID)).thenReturn(Optional.of(membership));
        OrganizationMemberQuotaLimit q = new OrganizationMemberQuotaLimit(ORG_ID, MEMBER_ID, OWNER_ID);
        q.setPeriodCredits(new BigDecimal("10.0000"));
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, MEMBER_ID)).thenReturn(Optional.of(q));
        when(ledgerRepository.sumDebitedByExecutorSince(MEMBER_ID, PERIOD_START))
                .thenReturn(new BigDecimal("9.0000"));

        // 9 already consumed + 2 → 11 > 10 cap → MUST refuse
        CapDecision decision = service.checkCreditsCap(MEMBER_ID, new BigDecimal("2.0000"));
        assertThat(decision.allowed()).as("ADR-009: personal-flag no longer bypasses caps").isFalse();
        assertThat(decision.dimension()).isEqualTo("credits");
    }

    @Test
    @DisplayName("executor IS the org owner → allow AND quotaRepository never queried (pinpointing the owner==executor short-circuit)")
    void ownerIsExecutorAllows() {
        Organization ownTeam = new Organization("mine", "mine", false, member);
        ownTeam.setId(UUID.randomUUID());
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID))
                .thenReturn(Optional.of(new OrganizationMember(ownTeam, member, OrganizationRole.OWNER, true)));

        assertThat(service.checkCreditsCap(MEMBER_ID, BigDecimal.ONE).allowed()).isTrue();
        // Discriminator: this branch evaluates AFTER isPersonal() (org is non-personal in this fixture)
        // and BEFORE quota lookup - so quotaRepository must stay silent while membership lookup fired.
        verify(memberRepository).findByUser_IdAndIsDefaultTrue(MEMBER_ID);
        verify(quotaRepository, never()).findByOrgIdAndUserId(any(), any());
    }

    @Test
    @DisplayName("no quota row → allow (no cap configured for this member)")
    void noQuotaRowAllows() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID)).thenReturn(Optional.of(teamMembership));
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, MEMBER_ID)).thenReturn(Optional.empty());
        assertThat(service.checkCreditsCap(MEMBER_ID, BigDecimal.ONE).allowed()).isTrue();
    }

    @Test
    @DisplayName("quota row with NULL period_credits → allow (no cap on this dim)")
    void nullPeriodCreditsAllows() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID)).thenReturn(Optional.of(teamMembership));
        OrganizationMemberQuotaLimit q = new OrganizationMemberQuotaLimit(ORG_ID, MEMBER_ID, OWNER_ID);
        q.setPeriodCredits(null);
        q.setPeriodLlmTokens(1000L); // tokens cap set, credits NULL → credits dim allows
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, MEMBER_ID)).thenReturn(Optional.of(q));
        assertThat(service.checkCreditsCap(MEMBER_ID, BigDecimal.ONE).allowed()).isTrue();
    }

    @Test
    @DisplayName("consumed + additional <= cap → allow (under the limit)")
    void underCapAllows() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID)).thenReturn(Optional.of(teamMembership));
        OrganizationMemberQuotaLimit q = new OrganizationMemberQuotaLimit(ORG_ID, MEMBER_ID, OWNER_ID);
        q.setPeriodCredits(new BigDecimal("100.0000"));
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, MEMBER_ID)).thenReturn(Optional.of(q));
        when(ledgerRepository.sumDebitedByExecutorSince(MEMBER_ID, PERIOD_START))
                .thenReturn(new BigDecimal("50.0000"));

        // 50 already consumed + 49 → 99 ≤ 100 cap
        assertThat(service.checkCreditsCap(MEMBER_ID, new BigDecimal("49.0000")).allowed()).isTrue();
    }

    @Test
    @DisplayName("consumed + additional > cap → REFUSE with credits dimension + snapshot")
    void overCapRefuses() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID)).thenReturn(Optional.of(teamMembership));
        OrganizationMemberQuotaLimit q = new OrganizationMemberQuotaLimit(ORG_ID, MEMBER_ID, OWNER_ID);
        q.setPeriodCredits(new BigDecimal("100.0000"));
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, MEMBER_ID)).thenReturn(Optional.of(q));
        when(ledgerRepository.sumDebitedByExecutorSince(MEMBER_ID, PERIOD_START))
                .thenReturn(new BigDecimal("99.0000"));

        // 99 consumed + 2 → 101 > 100 cap
        CapDecision decision = service.checkCreditsCap(MEMBER_ID, new BigDecimal("2.0000"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.dimension()).isEqualTo("credits");
        assertThat(decision.consumed()).isEqualByComparingTo("99.0000");
        assertThat(decision.cap()).isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("owner has no subscription → allow (degenerate state, never block over config glitch)")
    void noOwnerSubAllows() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID)).thenReturn(Optional.of(teamMembership));
        OrganizationMemberQuotaLimit q = new OrganizationMemberQuotaLimit(ORG_ID, MEMBER_ID, OWNER_ID);
        q.setPeriodCredits(new BigDecimal("1.0000"));
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, MEMBER_ID)).thenReturn(Optional.of(q));
        when(subscriptionRepository.findActiveByUserId(OWNER_ID)).thenReturn(Optional.empty());

        assertThat(service.checkCreditsCap(MEMBER_ID, new BigDecimal("100.0000")).allowed()).isTrue();
        verify(ledgerRepository, never()).sumDebitedByExecutorSince(any(), any());
    }

    @Test
    @DisplayName("soft-deleted org → allow (pinpointing the isDeleted() short-circuit, NOT the personal/owner paths)")
    void softDeletedOrgAllows() {
        teamOrg.setDeletedAt(LocalDateTime.now());
        // Sanity: this org is NEITHER personal NOR owned by the executor - only
        // the isDeleted() branch can be the reason for the allow result.
        assertThat(teamOrg.isPersonal()).as("fixture must be non-personal").isFalse();
        assertThat(teamOrg.getOwner().getId())
                .as("fixture: owner must not equal executor - leaves only isDeleted to short-circuit")
                .isNotEqualTo(MEMBER_ID);

        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID)).thenReturn(Optional.of(teamMembership));

        assertThat(service.checkCreditsCap(MEMBER_ID, new BigDecimal("100.0000")).allowed()).isTrue();
        verify(quotaRepository, never()).findByOrgIdAndUserId(any(), any());
    }

    @Test
    @DisplayName("repository throws → allow (hot-path safety, never block consume on a lookup glitch)")
    void exceptionAllows() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID))
                .thenThrow(new RuntimeException("DB hiccup"));
        assertThat(service.checkCreditsCap(MEMBER_ID, BigDecimal.ONE).allowed()).isTrue();
    }

    @Test
    @DisplayName("ledger sum returns null → treated as zero → under cap → allow")
    void nullConsumedAllows() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(MEMBER_ID)).thenReturn(Optional.of(teamMembership));
        OrganizationMemberQuotaLimit q = new OrganizationMemberQuotaLimit(ORG_ID, MEMBER_ID, OWNER_ID);
        q.setPeriodCredits(new BigDecimal("100.0000"));
        when(quotaRepository.findByOrgIdAndUserId(ORG_ID, MEMBER_ID)).thenReturn(Optional.of(q));
        when(ledgerRepository.sumDebitedByExecutorSince(MEMBER_ID, PERIOD_START)).thenReturn(null);

        assertThat(service.checkCreditsCap(MEMBER_ID, new BigDecimal("50.0000")).allowed()).isTrue();
    }
}
