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

    private OrganizationService service;
    private User owner;

    @BeforeEach
    void setUp() {
        service = new OrganizationService(organizationRepository, memberRepository);
        ReflectionTestUtils.setField(service, "subscriptionRepository", subscriptionRepository);
        ReflectionTestUtils.setField(service, "planRepository", planRepository);
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
}
