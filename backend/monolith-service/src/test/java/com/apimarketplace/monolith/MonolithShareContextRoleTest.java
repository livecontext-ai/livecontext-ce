package com.apimarketplace.monolith;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for the CE share-visitor divergence: a share-token request runs under the link
 * OWNER's identity, and the CE org filter injected that owner's REAL role, so an anonymous
 * visitor acted with OWNER/ADMIN rights (and a published app of a VIEWER owner was refused).
 * The cloud gateway sends no role in a share context; CE now does the same: org kept, role
 * dropped, and the thread-bound role is null too.
 */
@DisplayName("MonolithOrganizationContextFilter - share-context requests carry no org role")
class MonolithShareContextRoleTest {

    private final OrganizationMemberRepository memberRepository = mock(OrganizationMemberRepository.class);
    private final MonolithOrganizationContextFilter filter = new MonolithOrganizationContextFilter(memberRepository);

    private static OrganizationMember membership(UUID orgId, OrganizationRole role) {
        User user = new User();
        user.setId(42L);
        Organization organization = new Organization();
        organization.setId(orgId);
        organization.setName("CE Share Org");
        organization.setSlug("ce-share-org-" + orgId);
        organization.setOwner(user);
        return new OrganizationMember(organization, user, role, false);
    }

    private AtomicReference<ServletRequest> run(boolean share, AtomicReference<String> boundRole) throws Exception {
        UUID orgId = UUID.randomUUID();
        when(memberRepository.findActiveDefaultByUserId(42L))
                .thenReturn(Optional.of(membership(orgId, OrganizationRole.OWNER)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v2/workflows/runs/r/trigger/manual");
        request.addHeader("X-User-ID", "42");
        if (share) {
            request.addHeader("X-Share-Context", "true");
        }
        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, jakarta.servlet.ServletResponse res) {
                captured.set(req);
                boundRole.set(TenantResolver.currentRequestOrganizationRole());
            }
        });
        return captured;
    }

    @Test
    @DisplayName("share context: X-Organization-ID kept, X-Organization-Role absent (header AND thread-bound)")
    void shareContextDropsRole() throws Exception {
        AtomicReference<String> boundRole = new AtomicReference<>("unset");

        HttpServletRequest forwarded = (HttpServletRequest) run(true, boundRole).get();

        assertThat(forwarded.getHeader("X-Organization-ID")).isNotBlank();
        assertThat(forwarded.getHeader("X-Organization-Role")).isNull();
        assertThat(boundRole.get()).isNull();
    }

    @Test
    @DisplayName("normal request: the member's real role is still injected")
    void normalRequestKeepsRole() throws Exception {
        AtomicReference<String> boundRole = new AtomicReference<>();

        HttpServletRequest forwarded = (HttpServletRequest) run(false, boundRole).get();

        assertThat(forwarded.getHeader("X-Organization-Role")).isEqualTo("OWNER");
        assertThat(boundRole.get()).isEqualTo("OWNER");
    }
}
