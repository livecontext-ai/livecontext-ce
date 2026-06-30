package com.apimarketplace.monolith;

import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.service.PlanResolutionService;
import com.apimarketplace.common.web.MonolithSecurityFilter;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * CE monolith equivalent of the cloud gateway's active organization resolver.
 */
@Component
@Order(1)
public class MonolithOrganizationContextFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(MonolithOrganizationContextFilter.class);

    private static final String HEADER_USER_ID = "X-User-ID";
    private static final String HEADER_ACTIVE_ORG_ID = "X-Active-Organization-ID";
    private static final String HEADER_ORGANIZATION_ID = "X-Organization-ID";
    private static final String HEADER_ORGANIZATION_ROLE = "X-Organization-Role";
    private static final String HEADER_MONOLITH_ACTIVE_ORG_CLAIM =
            MonolithSecurityFilter.MONOLITH_ACTIVE_ORG_CLAIM_HEADER;
    private static final Set<String> ORG_CONTEXT_HEADERS = Set.of(
            HEADER_ACTIVE_ORG_ID,
            HEADER_ORGANIZATION_ID,
            HEADER_ORGANIZATION_ROLE,
            HEADER_MONOLITH_ACTIVE_ORG_CLAIM
    );

    private final OrganizationMemberRepository memberRepository;

    // Field-injected (optional) - the "dormant org" gate. In CE-free embedded mode
    // canMemberActInOrg() is a no-op (team is unlimited), so this only restricts
    // Self-Hosted Enterprise on a non-team license. Null in slim tests → guard skipped.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlanResolutionService planResolutionService;

    public MonolithOrganizationContextFilter(OrganizationMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /** True if the member may currently enter this org (null guard → allow when unwired). */
    private boolean canActIn(OrganizationMember member) {
        return planResolutionService == null || planResolutionService.canMemberActInOrg(member);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        Long userId = parseUserId(httpRequest.getHeader(HEADER_USER_ID));
        if (userId == null) {
            chain.doFilter(new OrganizationHeadersRequestWrapper(
                    httpRequest,
                    Map.of(),
                    ORG_CONTEXT_HEADERS),
                    response);
            return;
        }

        String requestedOrgId = firstNonBlank(
                httpRequest.getHeader(HEADER_MONOLITH_ACTIVE_ORG_CLAIM),
                httpRequest.getHeader(HEADER_ACTIVE_ORG_ID),
                httpRequest.getHeader(HEADER_ORGANIZATION_ID)
        );

        Optional<OrganizationMember> resolvedMembership = resolveMembership(userId, requestedOrgId);
        // Dormant-org guard: a "paused" team org (owner no longer on a team plan,
        // this user not the owner) cannot be entered - reject the claim and fall
        // back to the default workspace, then to the personal one if the default
        // itself is dormant. CE-free embedded mode is a no-op (see canActIn).
        if (resolvedMembership.isPresent() && !canActIn(resolvedMembership.get())) {
            log.debug("Active org claim '{}' paused for user {} - falling back", requestedOrgId, userId);
            resolvedMembership = Optional.empty();
        }
        if (resolvedMembership.isEmpty()) {
            Optional<OrganizationMember> def = memberRepository.findActiveDefaultByUserId(userId);
            if (def.isPresent() && !canActIn(def.get())) {
                resolvedMembership = memberRepository.findPersonalByUserId(userId);
            } else {
                resolvedMembership = def;
            }
        }

        if (resolvedMembership.isEmpty()) {
            doFilterWithBoundRequest(new OrganizationHeadersRequestWrapper(
                    httpRequest,
                    Map.of(),
                    ORG_CONTEXT_HEADERS),
                    response,
                    chain,
                    null,
                    null);
            return;
        }

        OrganizationMember membership = resolvedMembership.get();
        Map<String, String> injectedHeaders = new LinkedHashMap<>();
        injectedHeaders.put(HEADER_ORGANIZATION_ID, membership.getOrganization().getId().toString());
        injectedHeaders.put(HEADER_ORGANIZATION_ROLE, membership.getRole().name());
        doFilterWithBoundRequest(
                new OrganizationHeadersRequestWrapper(httpRequest, injectedHeaders, ORG_CONTEXT_HEADERS),
                response,
                chain,
                injectedHeaders.get(HEADER_ORGANIZATION_ID),
                injectedHeaders.get(HEADER_ORGANIZATION_ROLE));
    }

    private Optional<OrganizationMember> resolveMembership(Long userId, String activeOrgId) {
        if (isBlank(activeOrgId)) {
            return Optional.empty();
        }
        try {
            UUID organizationId = UUID.fromString(activeOrgId);
            Optional<OrganizationMember> membership =
                    memberRepository.findActiveByOrganizationIdAndUserId(organizationId, userId);
            if (membership.isEmpty()) {
                log.debug("Organization claim '{}' rejected for user {}", activeOrgId, userId);
            }
            return membership;
        } catch (IllegalArgumentException e) {
            log.debug("Invalid organization claim '{}' for user {}", activeOrgId, userId);
            return Optional.empty();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static void doFilterWithBoundRequest(HttpServletRequest request,
                                                 ServletResponse response,
                                                 FilterChain chain,
                                                 String organizationId,
                                                 String organizationRole) throws IOException, ServletException {
        RequestAttributes previousAttributes = RequestContextHolder.getRequestAttributes();
        ServletRequestAttributes currentAttributes = new ServletRequestAttributes(request);
        FilterChainExceptionHolder exceptionHolder = new FilterChainExceptionHolder();
        RequestContextHolder.setRequestAttributes(currentAttributes);
        try {
            TenantResolver.runWithOrgScope(organizationId, organizationRole, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException e) {
                    exceptionHolder.ioException = e;
                } catch (ServletException e) {
                    exceptionHolder.servletException = e;
                }
            });
            exceptionHolder.rethrowIfPresent();
        } finally {
            currentAttributes.requestCompleted();
            if (previousAttributes != null) {
                RequestContextHolder.setRequestAttributes(previousAttributes);
            } else {
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    private static Long parseUserId(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean matchesHeader(String candidate, String header) {
        return candidate != null && header != null && candidate.equalsIgnoreCase(header);
    }

    private static final class FilterChainExceptionHolder {
        private IOException ioException;
        private ServletException servletException;

        private void rethrowIfPresent() throws IOException, ServletException {
            if (ioException != null) {
                throw ioException;
            }
            if (servletException != null) {
                throw servletException;
            }
        }
    }

    private static final class OrganizationHeadersRequestWrapper extends HttpServletRequestWrapper {

        private final Map<String, String> injectedHeaders;
        private final Set<String> strippedHeaders;

        private OrganizationHeadersRequestWrapper(HttpServletRequest request,
                                                  Map<String, String> injectedHeaders,
                                                  Set<String> strippedHeaders) {
            super(request);
            this.injectedHeaders = injectedHeaders;
            this.strippedHeaders = strippedHeaders;
        }

        @Override
        public String getHeader(String name) {
            String injected = getInjectedHeader(name);
            if (injected != null) {
                return injected;
            }
            if (isStripped(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String injected = getInjectedHeader(name);
            if (injected != null) {
                return Collections.enumeration(java.util.List.of(injected));
            }
            if (isStripped(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(injectedHeaders.keySet());
            Enumeration<String> original = super.getHeaderNames();
            while (original != null && original.hasMoreElements()) {
                String name = original.nextElement();
                if (!isStripped(name)) {
                    names.add(name);
                }
            }
            return Collections.enumeration(names);
        }

        private String getInjectedHeader(String name) {
            return injectedHeaders.entrySet().stream()
                    .filter(entry -> matchesHeader(entry.getKey(), name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        private boolean isStripped(String name) {
            return strippedHeaders.stream().anyMatch(header -> matchesHeader(header, name));
        }
    }
}
