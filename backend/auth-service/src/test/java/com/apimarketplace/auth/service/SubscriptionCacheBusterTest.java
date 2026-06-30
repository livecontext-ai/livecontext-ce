package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract test for the shared cache fan-out pipe (extracted from {@code WebhookController} "PR9"
 * so the Stripe-webhook flow and the admin comp-plan grant share ONE implementation).
 *
 * <p>{@link SubscriptionCacheBuster#fanOutForOwner(Long, String)} must:
 * <ol>
 *   <li>Bust the owner's own cache</li>
 *   <li>Iterate every org the owner owns and bust each member's cache</li>
 *   <li>Skip soft-deleted orgs entirely</li>
 *   <li>Skip members with null providerId</li>
 *   <li>Be null-tolerant when optional beans are unwired (CE mode)</li>
 *   <li>Swallow exceptions (best-effort - never fail the caller)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionCacheBuster.fanOutForOwner")
class SubscriptionCacheBusterTest {

    @Mock private GatewayCacheClient gatewayCacheClient;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository organizationMemberRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private SubscriptionCacheBuster buster;

    private User user(Long id, String providerId) {
        User u = new User("u" + id, "u" + id + "@test.com", AuthProvider.KEYCLOAK, providerId);
        u.setId(id);
        return u;
    }

    private Organization org(String name, User owner, boolean deleted) {
        Organization o = new Organization(name, name, false, owner);
        o.setId(UUID.randomUUID());
        if (deleted) o.setDeletedAt(java.time.LocalDateTime.now());
        return o;
    }

    @Test
    @DisplayName("busts owner + every member of every owned org - full fan-out happy path")
    void fullFanOutHappyPath() {
        User owner = user(1L, "kc-owner");
        User mem1 = user(2L, "kc-mem1");
        User mem2 = user(3L, "kc-mem2");
        Organization orgA = org("A", owner, false);
        Organization orgB = org("B", owner, false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(organizationRepository.findByOwnerId(1L)).thenReturn(List.of(orgA, orgB));
        when(organizationMemberRepository.findByOrganization_Id(orgA.getId())).thenReturn(List.of(
                new OrganizationMember(orgA, owner, OrganizationRole.OWNER, true),
                new OrganizationMember(orgA, mem1, OrganizationRole.MEMBER, false)));
        when(organizationMemberRepository.findByOrganization_Id(orgB.getId())).thenReturn(List.of(
                new OrganizationMember(orgB, owner, OrganizationRole.OWNER, false),
                new OrganizationMember(orgB, mem2, OrganizationRole.ADMIN, false)));

        buster.fanOutForOwner(1L, "subscription.upsert");

        verify(gatewayCacheClient).invalidateUserCache("kc-mem1");
        verify(gatewayCacheClient).invalidateUserCache("kc-mem2");
        verify(gatewayCacheClient, org.mockito.Mockito.atLeastOnce()).invalidateUserCache("kc-owner");
    }

    @Test
    @DisplayName("soft-deleted org is SKIPPED - members of deleted org don't get cache-busted")
    void softDeletedOrgIsSkipped() {
        User owner = user(1L, "kc-owner");
        User memberOfDeleted = user(99L, "kc-deleted-mem");
        Organization deletedOrg = org("Deleted", owner, true);
        Organization liveOrg = org("Live", owner, false);
        User liveMember = user(2L, "kc-live-mem");

        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(organizationRepository.findByOwnerId(1L)).thenReturn(List.of(deletedOrg, liveOrg));
        when(organizationMemberRepository.findByOrganization_Id(liveOrg.getId())).thenReturn(List.of(
                new OrganizationMember(liveOrg, liveMember, OrganizationRole.MEMBER, false)));

        buster.fanOutForOwner(1L, "subscription.deleted");

        verify(gatewayCacheClient).invalidateUserCache("kc-live-mem");
        verify(gatewayCacheClient, never()).invalidateUserCache("kc-deleted-mem");
        verify(organizationMemberRepository, never()).findByOrganization_Id(deletedOrg.getId());
    }

    @Test
    @DisplayName("member with null providerId is silently skipped (defensive)")
    void nullProviderIdMemberSkipped() {
        User owner = user(1L, "kc-owner");
        User mem = user(2L, null);  // null providerId - defensive case
        Organization o = org("O", owner, false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(organizationRepository.findByOwnerId(1L)).thenReturn(List.of(o));
        when(organizationMemberRepository.findByOrganization_Id(o.getId())).thenReturn(List.of(
                new OrganizationMember(o, mem, OrganizationRole.MEMBER, false)));

        buster.fanOutForOwner(1L, "test");

        verify(gatewayCacheClient).invalidateUserCache("kc-owner");
        verify(gatewayCacheClient, never()).invalidateUserCache(null);
    }

    @Test
    @DisplayName("no-op when GatewayCacheClient is null (CE mode / partial wiring)")
    void noOpWhenGatewayClientUnwired() {
        ReflectionTestUtils.setField(buster, "gatewayCacheClient", null);

        buster.fanOutForOwner(1L, "test");  // must not throw

        verify(organizationRepository, never()).findByOwnerId(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("no-op when UserRepository is null (CE mode)")
    void noOpWhenUserRepositoryUnwired() {
        ReflectionTestUtils.setField(buster, "userRepository", null);

        buster.fanOutForOwner(1L, "test");  // must not throw

        verify(organizationRepository, never()).findByOwnerId(any());
        verify(gatewayCacheClient, never()).invalidateUserCache(any());
    }

    @Test
    @DisplayName("transport exception swallowed: caller stays green even if gateway is down")
    void swallowsException() {
        when(userRepository.findById(1L)).thenThrow(new RuntimeException("DB hiccup"));

        buster.fanOutForOwner(1L, "test");  // must not throw - fan-out is best-effort
    }
}
