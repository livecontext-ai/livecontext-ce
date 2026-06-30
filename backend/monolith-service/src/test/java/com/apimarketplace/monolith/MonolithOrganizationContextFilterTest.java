package com.apimarketplace.monolith;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.service.PlanResolutionService;
import com.apimarketplace.common.web.MonolithSecurityFilter;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("MonolithOrganizationContextFilter")
class MonolithOrganizationContextFilterTest {

    private final OrganizationMemberRepository memberRepository = mock(OrganizationMemberRepository.class);
    private final MonolithOrganizationContextFilter filter = new MonolithOrganizationContextFilter(memberRepository);

    @Test
    @DisplayName("runs after CE security filter strips forged identity headers")
    void runsAfterMonolithSecurityFilter() {
        Order securityOrder = MonolithSecurityFilter.class.getAnnotation(Order.class);
        Order organizationOrder = MonolithOrganizationContextFilter.class.getAnnotation(Order.class);

        assertThat(securityOrder).isNotNull();
        assertThat(organizationOrder).isNotNull();
        assertThat(securityOrder.value()).isLessThan(organizationOrder.value());
    }

    @Test
    @DisplayName("valid active organization claim injects org id and role")
    void validActiveOrganizationClaimInjectsOrgHeaders() throws Exception {
        UUID orgId = UUID.randomUUID();
        OrganizationMember membership = membership(orgId, OrganizationRole.ADMIN);
        when(memberRepository.findActiveByOrganizationIdAndUserId(orgId, 42L)).thenReturn(Optional.of(membership));
        MockHttpServletRequest request = authenticatedRequest();
        request.addHeader("X-Active-Organization-ID", orgId.toString());
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo(orgId.toString());
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("ADMIN");
        assertThat(forwarded.getHeader("X-Active-Organization-ID")).isNull();
        verify(memberRepository).findActiveByOrganizationIdAndUserId(orgId, 42L);
    }

    @Test
    @DisplayName("invalid active organization claim falls back to default membership")
    void invalidActiveOrganizationClaimFallsBackToDefaultMembership() throws Exception {
        UUID claimedOrgId = UUID.randomUUID();
        UUID defaultOrgId = UUID.randomUUID();
        when(memberRepository.findActiveByOrganizationIdAndUserId(claimedOrgId, 42L)).thenReturn(Optional.empty());
        when(memberRepository.findActiveDefaultByUserId(42L))
                .thenReturn(Optional.of(membership(defaultOrgId, OrganizationRole.OWNER)));
        MockHttpServletRequest request = authenticatedRequest();
        request.addHeader("X-Active-Organization-ID", claimedOrgId.toString());
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo(defaultOrgId.toString());
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("OWNER");
        assertThat(forwarded.getHeader("X-Active-Organization-ID")).isNull();
    }

    @Test
    @DisplayName("pre-injected org id is validated and role is re-injected from membership")
    void preInjectedOrgHeaderIsValidatedBeforeForwarding() throws Exception {
        UUID orgId = UUID.randomUUID();
        when(memberRepository.findActiveByOrganizationIdAndUserId(orgId, 42L))
                .thenReturn(Optional.of(membership(orgId, OrganizationRole.ADMIN)));
        MockHttpServletRequest request = authenticatedRequest();
        request.addHeader("X-Organization-ID", orgId.toString());
        request.addHeader("X-Organization-Role", "MEMBER");
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo(orgId.toString());
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("ADMIN");
        assertThat(forwarded.getHeader("X-Active-Organization-ID")).isNull();
        verify(memberRepository).findActiveByOrganizationIdAndUserId(orgId, 42L);
    }

    @Test
    @DisplayName("security handoff active org claim wins over stale pre-injected default org")
    void securityHandoffActiveClaimWinsOverStaleDefaultOrgHeader() throws Exception {
        UUID activeOrgId = UUID.randomUUID();
        UUID staleDefaultOrgId = UUID.randomUUID();
        when(memberRepository.findActiveByOrganizationIdAndUserId(activeOrgId, 42L))
                .thenReturn(Optional.of(membership(activeOrgId, OrganizationRole.MEMBER)));
        MockHttpServletRequest request = authenticatedRequest();
        request.addHeader(MonolithSecurityFilter.MONOLITH_ACTIVE_ORG_CLAIM_HEADER, activeOrgId.toString());
        request.addHeader("X-Organization-ID", staleDefaultOrgId.toString());
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo(activeOrgId.toString());
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("MEMBER");
        assertThat(forwarded.getHeader(MonolithSecurityFilter.MONOLITH_ACTIVE_ORG_CLAIM_HEADER)).isNull();
        verify(memberRepository).findActiveByOrganizationIdAndUserId(activeOrgId, 42L);
    }

    @Test
    @DisplayName("resolved organization scope is rebound for TenantResolver during the downstream chain")
    void resolvedOrganizationScopeIsReboundForTenantResolver() throws Exception {
        UUID orgId = UUID.randomUUID();
        when(memberRepository.findActiveByOrganizationIdAndUserId(orgId, 42L))
                .thenReturn(Optional.of(membership(orgId, OrganizationRole.ADMIN)));
        MockHttpServletRequest request = authenticatedRequest();
        request.addHeader(MonolithSecurityFilter.MONOLITH_ACTIVE_ORG_CLAIM_HEADER, orgId.toString());
        AtomicReference<String> resolvedOrg = new AtomicReference<>();
        AtomicReference<String> resolvedRole = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
            resolvedOrg.set(TenantResolver.currentRequestOrganizationId());
            resolvedRole.set(TenantResolver.currentRequestOrganizationRole());
        });

        assertThat(resolvedOrg.get()).isEqualTo(orgId.toString());
        assertThat(resolvedRole.get()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("forged pre-injected org id falls back to default membership")
    void forgedPreInjectedOrgHeaderFallsBackToDefaultMembership() throws Exception {
        UUID claimedOrgId = UUID.randomUUID();
        UUID defaultOrgId = UUID.randomUUID();
        when(memberRepository.findActiveByOrganizationIdAndUserId(claimedOrgId, 42L)).thenReturn(Optional.empty());
        when(memberRepository.findActiveDefaultByUserId(42L))
                .thenReturn(Optional.of(membership(defaultOrgId, OrganizationRole.MEMBER)));
        MockHttpServletRequest request = authenticatedRequest();
        request.addHeader("X-Organization-ID", claimedOrgId.toString());
        request.addHeader("X-Organization-Role", "OWNER");
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo(defaultOrgId.toString());
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("MEMBER");
        assertThat(forwarded.getHeader("X-Active-Organization-ID")).isNull();
    }

    @Test
    @DisplayName("missing user strips organization headers")
    void missingUserStripsOrganizationHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/storage/quota");
        request.addHeader("X-Organization-ID", "forged-org");
        request.addHeader("X-Organization-Role", "OWNER");
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isNull();
        assertThat(forwarded.getHeader("X-Organization-Role")).isNull();
        assertThat(forwarded.getHeader(MonolithSecurityFilter.MONOLITH_ACTIVE_ORG_CLAIM_HEADER)).isNull();
        verifyNoInteractions(memberRepository);
    }

    @Test
    @DisplayName("dormant active-org claim (owner downgraded below team) is rejected and falls back to default")
    void pausedActiveOrgClaimFallsBackToDefault() throws Exception {
        PlanResolutionService planResolution = mock(PlanResolutionService.class);
        ReflectionTestUtils.setField(filter, "planResolutionService", planResolution);
        UUID dormantOrgId = UUID.randomUUID();
        UUID defaultOrgId = UUID.randomUUID();
        OrganizationMember dormant = membership(dormantOrgId, OrganizationRole.MEMBER);
        OrganizationMember def = membership(defaultOrgId, OrganizationRole.OWNER);
        when(memberRepository.findActiveByOrganizationIdAndUserId(dormantOrgId, 42L)).thenReturn(Optional.of(dormant));
        when(memberRepository.findActiveDefaultByUserId(42L)).thenReturn(Optional.of(def));
        when(planResolution.canMemberActInOrg(dormant)).thenReturn(false); // dormant → blocked
        when(planResolution.canMemberActInOrg(def)).thenReturn(true);       // default is fine
        MockHttpServletRequest request = authenticatedRequest();
        request.addHeader("X-Active-Organization-ID", dormantOrgId.toString());
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo(defaultOrgId.toString());
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("when the default workspace is itself dormant, fall back to the personal org - member never stranded")
    void pausedDefaultFallsBackToPersonal() throws Exception {
        PlanResolutionService planResolution = mock(PlanResolutionService.class);
        ReflectionTestUtils.setField(filter, "planResolutionService", planResolution);
        UUID defaultOrgId = UUID.randomUUID();
        UUID personalOrgId = UUID.randomUUID();
        OrganizationMember def = membership(defaultOrgId, OrganizationRole.MEMBER);
        OrganizationMember personal = membership(personalOrgId, OrganizationRole.OWNER);
        when(memberRepository.findActiveDefaultByUserId(42L)).thenReturn(Optional.of(def));
        when(planResolution.canMemberActInOrg(def)).thenReturn(false);      // default dormant
        when(memberRepository.findPersonalByUserId(42L)).thenReturn(Optional.of(personal));
        MockHttpServletRequest request = authenticatedRequest(); // no active-org claim

        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(captured));

        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo(personalOrgId.toString());
        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("OWNER");
    }

    private static MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/storage/quota");
        request.addHeader("X-User-ID", "42");
        return request;
    }

    private static OrganizationMember membership(UUID orgId, OrganizationRole role) {
        User user = new User();
        user.setId(42L);
        Organization organization = new Organization();
        organization.setId(orgId);
        organization.setName("CE Test Organization");
        organization.setSlug("ce-test-organization-" + orgId);
        organization.setOwner(user);
        return new OrganizationMember(organization, user, role, false);
    }

    private static MockFilterChain capturingChain(AtomicReference<ServletRequest> captured) {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, jakarta.servlet.ServletResponse response) {
                captured.set(request);
            }
        };
    }
}
