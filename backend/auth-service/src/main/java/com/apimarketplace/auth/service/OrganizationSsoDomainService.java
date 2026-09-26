package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import com.apimarketplace.auth.domain.OrganizationSamlConnection;
import com.apimarketplace.auth.domain.OrganizationSsoDomain;
import com.apimarketplace.auth.dto.OrganizationSsoDomainDto;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.OrganizationSamlConnectionRepository;
import com.apimarketplace.auth.repository.OrganizationSsoDomainRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Email domains a workspace owns for SAML SSO: claim, DNS verification, discovery by email,
 * and the admission check a SAML login must pass.
 *
 * <p>Ownership is what makes the two public-facing uses safe. Discovery routes whoever types
 * an address on the domain to this workspace's identity provider, and the IdP asserts any
 * email it likes; without proof of the domain, a workspace could route gmail.com users to a
 * look-alike login page, or mint an account in someone else's name. Neither happens for a
 * domain whose DNS the workspace does not control.
 *
 * <p>Known limits, by design for this first version: a match is on the exact domain (a
 * verified {@code acme.com} does not cover {@code eng.acme.com}; list each one), a domain must
 * be entered in its ASCII (punycode) form, and verification is not re-checked afterwards, so a
 * domain that later changes hands stays verified until an admin removes it. Removing the SAML
 * connection keeps the domains, so it can be reconfigured without re-verifying; discovery
 * already ignores a workspace whose connection is not ACTIVE.
 */
@Service
public class OrganizationSsoDomainService {

    /** Keeps the list and the DNS work per workspace bounded. */
    static final int MAX_DOMAINS_PER_ORGANIZATION = 20;

    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrganizationSsoDomainRepository domainRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationSamlConnectionRepository samlRepository;
    private final OrganizationSamlService samlService;
    private final OrganizationAuditService auditService;
    private final SsoDomainDnsVerifier dnsVerifier;

    public OrganizationSsoDomainService(
            OrganizationSsoDomainRepository domainRepository,
            OrganizationRepository organizationRepository,
            OrganizationSamlConnectionRepository samlRepository,
            OrganizationSamlService samlService,
            OrganizationAuditService auditService,
            SsoDomainDnsVerifier dnsVerifier
    ) {
        this.domainRepository = domainRepository;
        this.organizationRepository = organizationRepository;
        this.samlRepository = samlRepository;
        this.samlService = samlService;
        this.auditService = auditService;
        this.dnsVerifier = dnsVerifier;
    }

    /** Where a verified email domain sends its users: the workspace and its Keycloak alias. */
    public record Discovery(UUID organizationId, String idpHint) {
    }

    @Transactional(readOnly = true)
    public List<OrganizationSsoDomainDto> list(UUID orgId, Long actorUserId) {
        samlService.authorizeOwnerOrAdmin(orgId, actorUserId);
        return domainRepository.findByOrganization_IdOrderByCreatedAtAsc(orgId).stream().map(this::toDto).toList();
    }

    @Transactional
    public OrganizationSsoDomainDto add(UUID orgId, Long actorUserId, String rawDomain) {
        samlService.authorizeOwnerOrAdmin(orgId, actorUserId);
        samlService.enforcePlanSupportsSso(orgId);
        String domain = normalizeDomain(rawDomain);
        if (domainRepository.existsByOrganization_IdAndDomain(orgId, domain)) {
            throw new IllegalArgumentException("This domain is already listed for the workspace");
        }
        if (domainRepository.countByOrganization_Id(orgId) >= MAX_DOMAINS_PER_ORGANIZATION) {
            throw new IllegalArgumentException(
                    "A workspace can list at most " + MAX_DOMAINS_PER_ORGANIZATION + " domains");
        }
        Organization organization = organizationRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found"));
        OrganizationSsoDomain saved = domainRepository.save(
                new OrganizationSsoDomain(organization, domain, newToken()));
        auditService.record(orgId, actorUserId, OrganizationAuditEvent.Type.SSO_DOMAIN_ADDED,
                Map.of("domain", domain));
        return toDto(saved);
    }

    /**
     * Looks the TXT record up and marks the domain verified when it is there. Not finding it is
     * an ordinary answer ({@code verified=false}); a domain another workspace already verified is
     * refused with {@link DomainClaimedException}.
     */
    @Transactional
    public OrganizationSsoDomainDto verify(UUID orgId, Long actorUserId, UUID domainId) {
        samlService.authorizeOwnerOrAdmin(orgId, actorUserId);
        samlService.enforcePlanSupportsSso(orgId);
        OrganizationSsoDomain d = domainRepository.findByIdAndOrganization_Id(domainId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Domain not found"));
        if (d.isVerified()) {
            return toDto(d);
        }
        if (domainRepository.findVerifiedByDomain(d.getDomain()).isPresent()) {
            throw new DomainClaimedException("This domain is already verified by another workspace");
        }
        boolean found = dnsVerifier.isVerified(d.getDomain(), d.getVerificationToken());
        d.setLastCheckedAt(Instant.now());
        if (!found) {
            domainRepository.save(d);
            return toDto(d);
        }
        d.setVerifiedAt(Instant.now());
        // Two workspaces verifying the same domain at once: the partial unique index lets one win
        // and the other's flush throws DataIntegrityViolationException. It is NOT caught here: the
        // violation has already doomed this transaction, so it propagates, rolls back, and the
        // controller answers it as DOMAIN_CLAIMED.
        domainRepository.saveAndFlush(d);
        auditService.record(orgId, actorUserId, OrganizationAuditEvent.Type.SSO_DOMAIN_VERIFIED,
                Map.of("domain", d.getDomain()));
        return toDto(d);
    }

    @Transactional
    public void delete(UUID orgId, Long actorUserId, UUID domainId) {
        samlService.authorizeOwnerOrAdmin(orgId, actorUserId);
        OrganizationSsoDomain d = domainRepository.findByIdAndOrganization_Id(domainId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Domain not found"));
        domainRepository.delete(d);
        auditService.record(orgId, actorUserId, OrganizationAuditEvent.Type.SSO_DOMAIN_REMOVED,
                Map.of("domain", d.getDomain(), "wasVerified", d.isVerified()));
    }

    /**
     * The workspace a login for {@code email} should be sent to: its domain is verified by a live
     * workspace whose SAML connection is ACTIVE. Empty otherwise, with no reason given, because
     * the caller is anonymous.
     */
    @Transactional(readOnly = true)
    public Optional<Discovery> discover(String email) {
        String domain = emailDomain(email);
        if (domain == null) {
            return Optional.empty();
        }
        return domainRepository.findVerifiedByDomain(domain)
                .map(OrganizationSsoDomain::getOrganization)
                .filter(org -> !org.isDeleted())
                .flatMap(org -> samlRepository.findByOrganization_Id(org.getId()))
                .filter(c -> c.getStatus() == OrganizationSamlConnection.Status.ACTIVE)
                .map(c -> new Discovery(c.getOrganization().getId(), c.getIdpAlias()));
    }

    /** Admission rule for a SAML login: the asserted email must sit on a domain this workspace verified. */
    @Transactional(readOnly = true)
    public boolean isEmailOnVerifiedDomain(UUID orgId, String email) {
        String domain = emailDomain(email);
        return domain != null && domainRepository.isVerifiedForOrganization(orgId, domain);
    }

    static String emailDomain(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            return null;
        }
        String domain = trimmed.substring(at + 1).toLowerCase(Locale.ROOT);
        return DOMAIN.matcher(domain).matches() ? domain : null;
    }

    static String normalizeDomain(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("domain is required");
        }
        String domain = raw.trim().toLowerCase(Locale.ROOT);
        if (domain.startsWith("@")) {
            domain = domain.substring(1);
        }
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (!DOMAIN.matcher(domain).matches()) {
            throw new IllegalArgumentException("Enter a domain such as example.com");
        }
        return domain;
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private OrganizationSsoDomainDto toDto(OrganizationSsoDomain d) {
        return OrganizationSsoDomainDto.of(d, dnsVerifier.recordName(d.getDomain()),
                dnsVerifier.expectedValue(d.getVerificationToken()));
    }

    /** The domain is verified by another workspace; the controller answers 409. */
    public static class DomainClaimedException extends IllegalStateException {
        public DomainClaimedException(String message) {
            super(message);
        }
    }
}
