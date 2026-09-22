package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-plan workspace cap at creation time (shared-wallet model). Mirrors the field-injection
 * wiring OrganizationService uses for its optional deps.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceCreateTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PlanStorageQuotaSyncer planStorageQuotaSyncer;

    private OrganizationService service;
    private User owner;

    @BeforeEach
    void setUp() {
        service = new OrganizationService(organizationRepository, memberRepository);
        ReflectionTestUtils.setField(service, "subscriptionRepository", subscriptionRepository);
        ReflectionTestUtils.setField(service, "planRepository", planRepository);
        ReflectionTestUtils.setField(service, "planStorageQuotaSyncer", planStorageQuotaSyncer);
        owner = new User();
        owner.setId(1L);
    }

    private void planWithCap(String code, Integer maxWorkspaces) {
        Plan p = new Plan(code, code, "");
        p.setMaxWorkspaces(maxWorkspaces);
        Subscription s = new Subscription();
        s.setPlan(p);
        s.setStatus("active");
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(s));
    }

    private void stubSave() {
        lenient().when(organizationRepository.existsBySlug(anyString())).thenReturn(false);
        lenient().when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0);
            if (o.getId() == null) o.setId(UUID.randomUUID());
            return o;
        });
    }

    @Test
    @DisplayName("blank name -> IllegalArgumentException, nothing created")
    void blankNameRejected() {
        assertThatThrownBy(() -> service.createOrganization(owner, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(organizationRepository, never()).save(any());
    }

    @Test
    @DisplayName("at the plan cap -> IllegalStateException, nothing created")
    void atCapRejected() {
        planWithCap("PRO", 3);
        when(organizationRepository.countByOwnerIdAndDeletedAtIsNull(1L)).thenReturn(3L);
        assertThatThrownBy(() -> service.createOrganization(owner, "Client A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limit");
        verify(organizationRepository, never()).save(any());
    }

    @Test
    @DisplayName("under the cap -> creates a NON-personal org + OWNER membership (not default)")
    void underCapCreates() {
        planWithCap("PRO", 3);
        when(organizationRepository.countByOwnerIdAndDeletedAtIsNull(1L)).thenReturn(1L);
        stubSave();

        Organization org = service.createOrganization(owner, "Client A");

        assertThat(org.isPersonal()).isFalse();
        assertThat(org.getName()).isEqualTo("Client A");
        assertThat(org.getSlug()).isNotBlank();
        verify(organizationRepository).save(org);
        verify(memberRepository).save(argThat(m ->
                m.getRole() == OrganizationRole.OWNER && !m.isDefault()
                        && m.getUser() == owner && m.getOrganization() == org));
    }

    @Test
    @DisplayName("unlimited plan (max_workspaces NULL) -> creates without consulting the count")
    void unlimitedCreatesRegardless() {
        planWithCap("ENTERPRISE_STANDARD", null);
        stubSave();

        service.createOrganization(owner, "WS");

        verify(organizationRepository, never()).countByOwnerIdAndDeletedAtIsNull(anyLong());
        verify(organizationRepository).save(any(Organization.class));
    }

    @Test
    @DisplayName("no subscription -> FREE fallback (cap 1); personal already owned -> rejected")
    void freeFallbackAtCap() {
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.empty());
        Plan free = new Plan("FREE", "Free", "");
        free.setMaxWorkspaces(1);
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(free));
        when(organizationRepository.countByOwnerIdAndDeletedAtIsNull(1L)).thenReturn(1L); // personal only

        assertThatThrownBy(() -> service.createOrganization(owner, "Extra"))
                .isInstanceOf(IllegalStateException.class);
        verify(organizationRepository, never()).save(any());
    }

    // ===== storage allowance at workspace birth =====
    // Regression cover for the prod bug where a workspace created AFTER its owner's last plan
    // change kept the FREE 100 MB default while a sibling workspace sat on the plan's real
    // allowance. Nothing pushed the plan at creation, so the two "seeds" tests below fail on the
    // pre-fix code; the remaining ones pin the guard rails that keep the fix from over-reaching.

    private static final long PRO_BYTES = 10_737_418_240L;  // 10 GB
    private static final long FREE_BYTES = 104_857_600L;    // 100 MB

    private Plan planWithStorage(String code, Long includedStorageBytes) {
        Plan p = new Plan(code, code, "");
        p.setMaxWorkspaces(3);
        p.setIncludedStorageBytes(includedStorageBytes);
        Subscription s = new Subscription();
        s.setPlan(p);
        s.setStatus("active");
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(s));
        return p;
    }

    @Test
    @DisplayName("new workspace is seeded with the owner's CURRENT plan storage allowance")
    void newWorkspaceSeededWithCurrentPlanAllowance() {
        planWithStorage("PRO", PRO_BYTES);
        when(organizationRepository.countByOwnerIdAndDeletedAtIsNull(1L)).thenReturn(1L);
        stubSave();

        Organization created = service.createOrganization(owner, "Client A");

        // The allowance itself is asserted, not just the plan code: a syncer handed the wrong
        // plan would still satisfy a code-only check.
        verify(planStorageQuotaSyncer).syncOrgAfterCommit(
                eq(1L), eq(created.getId()), argThat(p -> PRO_BYTES == p.getIncludedStorageBytes()));
    }

    @Test
    @DisplayName("personal workspace is seeded with the owner's plan storage allowance too")
    void personalWorkspaceSeededWithPlanAllowance() {
        planWithStorage("PRO", PRO_BYTES);
        when(organizationRepository.findByOwnerIdAndIsPersonalTrue(1L)).thenReturn(Optional.empty());
        stubSave();

        Organization created = service.createPersonalOrganization(owner, "Jane Doe");

        verify(planStorageQuotaSyncer).syncOrgAfterCommit(
                eq(1L), eq(created.getId()), argThat(p -> PRO_BYTES == p.getIncludedStorageBytes()));
    }

    @Test
    @DisplayName("seeding targets ONLY the new workspace, never the owner's existing ones")
    void seedingNeverSweepsExistingWorkspaces() {
        planWithStorage("PRO", PRO_BYTES);
        when(organizationRepository.countByOwnerIdAndDeletedAtIsNull(1L)).thenReturn(1L);
        stubSave();

        service.createOrganization(owner, "Client A");

        // syncAfterCommit rewrites the tenant quota AND every workspace findByOwnerId returns.
        // Calling it here would let a narrower resolved plan (a past_due owner resolves to FREE)
        // shrink healthy workspaces to 100 MB, so creation must never reach for it.
        verify(planStorageQuotaSyncer, never()).syncAfterCommit(any(), any());
    }

    @Test
    @DisplayName("FREE owner -> new workspace seeded at the FREE allowance, not at a paid one")
    void freeOwnerSeedsFreeAllowance() {
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.empty());
        Plan free = new Plan("FREE", "Free", "");
        free.setMaxWorkspaces(null); // unlimited, so the cap never masks the seeding assertion
        free.setIncludedStorageBytes(FREE_BYTES);
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(free));
        stubSave();

        Organization created = service.createOrganization(owner, "Extra");

        verify(planStorageQuotaSyncer).syncOrgAfterCommit(
                eq(1L), eq(created.getId()), argThat(p -> FREE_BYTES == p.getIncludedStorageBytes()));
    }

    @Test
    @DisplayName("no plan row resolvable at all -> nothing seeded (no bogus allowance invented)")
    void unresolvablePlanSeedsNothing() {
        when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.empty());
        when(planRepository.findByCode("FREE")).thenReturn(Optional.empty());
        when(organizationRepository.findByOwnerIdAndIsPersonalTrue(1L)).thenReturn(Optional.empty());
        stubSave();

        service.createPersonalOrganization(owner, "Jane Doe");

        verify(planStorageQuotaSyncer, never()).syncOrgAfterCommit(any(), any(), any());
    }

    @Test
    @DisplayName("creation rejected at the cap -> nothing seeded")
    void rejectedCreationSeedsNothing() {
        planWithCap("PRO", 3);
        when(organizationRepository.countByOwnerIdAndDeletedAtIsNull(1L)).thenReturn(3L);

        assertThatThrownBy(() -> service.createOrganization(owner, "Client A"))
                .isInstanceOf(IllegalStateException.class);

        verify(planStorageQuotaSyncer, never()).syncOrgAfterCommit(any(), any(), any());
    }

    @Test
    @DisplayName("no quota syncer wired (module absent) -> the workspace is still fully created")
    void missingQuotaSyncerDoesNotBreakCreation() {
        ReflectionTestUtils.setField(service, "planStorageQuotaSyncer", null);
        planWithCap("PRO", 3);
        when(organizationRepository.countByOwnerIdAndDeletedAtIsNull(1L)).thenReturn(1L);
        stubSave();

        Organization org = service.createOrganization(owner, "Client A");

        // Asserting the whole creation, not just a non-null return: the null guard sits after the
        // org and its OWNER membership are persisted, so a guard that short-circuited early (or
        // threw) would leave one of them unwritten.
        assertThat(org.getName()).isEqualTo("Client A");
        verify(organizationRepository).save(org);
        verify(memberRepository).save(argThat(m ->
                m.getRole() == OrganizationRole.OWNER && m.getOrganization() == org));
    }
}
