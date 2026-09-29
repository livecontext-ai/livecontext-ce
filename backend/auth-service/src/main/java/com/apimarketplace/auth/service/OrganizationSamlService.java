package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.OrganizationSamlConnection;
import com.apimarketplace.auth.dto.OrganizationSamlConnectionDto;
import com.apimarketplace.auth.dto.UpsertOrganizationSamlConnectionRequest;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.OrganizationSamlConnectionRepository;
import com.apimarketplace.auth.repository.OrganizationSsoDomainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class OrganizationSamlService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationSamlService.class);

    private static final int MAX_ERROR_LENGTH = 2000;

    private final OrganizationSamlConnectionRepository samlRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationMemberService memberService;
    private final OrganizationAuditService auditService;
    private final OrganizationSsoDomainRepository domainRepository;
    private final KeycloakSamlIdentityProviderClient keycloakClient;
    private final String keycloakIssuerUri;

    public OrganizationSamlService(
            OrganizationSamlConnectionRepository samlRepository,
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository memberRepository,
            OrganizationMemberService memberService,
            OrganizationAuditService auditService,
            OrganizationSsoDomainRepository domainRepository,
            ObjectProvider<KeycloakSamlIdentityProviderClient> keycloakClientProvider,
            @Value("${keycloak.issuer-uri:http://localhost:8180/realms/livecontext}") String keycloakIssuerUri
    ) {
        this.samlRepository = samlRepository;
        this.organizationRepository = organizationRepository;
        this.memberRepository = memberRepository;
        this.memberService = memberService;
        this.auditService = auditService;
        this.domainRepository = domainRepository;
        this.keycloakClient = keycloakClientProvider.getIfAvailable();
        this.keycloakIssuerUri = trimTrailingSlash(keycloakIssuerUri);
    }

    @Transactional(readOnly = true)
    public OrganizationSamlConnectionDto get(UUID orgId, Long actorUserId) {
        authorizeOwnerOrAdmin(orgId, actorUserId);
        return samlRepository.findByOrganization_Id(orgId)
                .map(this::toDto)
                .orElseGet(() -> notConfiguredDto(orgId));
    }

    @Transactional(noRollbackFor = SamlProvisioningException.class)
    public OrganizationSamlConnectionDto upsert(
            UUID orgId,
            Long actorUserId,
            UpsertOrganizationSamlConnectionRequest request
    ) {
        authorizeOwnerOrAdmin(orgId, actorUserId);
        enforcePlanSupportsSso(orgId);

        Organization organization = organizationRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found"));

        String alias = aliasFor(orgId);
        OrganizationSamlConnection connection = samlRepository.findByOrganization_Id(orgId)
                .orElseGet(() -> new OrganizationSamlConnection(organization, alias));
        boolean existingConnection = connection.getId() != null;

        connection.setOrganization(organization);
        connection.setIdpAlias(alias);
        connection.setDisplayName(requiredTrimmed(request.displayName(), "displayName"));
        connection.setIdpEntityId(requiredTrimmed(request.idpEntityId(), "idpEntityId"));
        connection.setSsoUrl(validateHttpsUrl(requiredTrimmed(request.ssoUrl(), "ssoUrl")));
        if (request.x509Certificate() != null && !request.x509Certificate().trim().isEmpty()) {
            connection.setX509Certificate(normalizeCertificate(request.x509Certificate()));
        } else if (!existingConnection) {
            throw new IllegalArgumentException("x509Certificate is required");
        }
        // Not a workspace choice any more: a workspace IdP is never listed on the platform login
        // page (see KeycloakSamlIdentityProviderClient). The column is kept, pinned to true.
        connection.setHideOnLoginPage(true);
        connection.setStatus(OrganizationSamlConnection.Status.DRAFT);
        connection.setLastError(null);

        provisionKeycloak(connection, hasVerifiedDomain(orgId));

        auditService.record(orgId, actorUserId, OrganizationAuditEvent.Type.SAML_SSO_CONFIGURED,
                Map.of(
                        "idpAlias", connection.getIdpAlias(),
                        "displayName", connection.getDisplayName(),
                        "status", connection.getStatus().name()));

        return toDto(connection);
    }

    /**
     * Re-applies whether the workspace's Keycloak IdP may broker logins: only when its connection
     * is ACTIVE, the workspace is live and still on a Team plan, and it has proven at least one
     * domain. Called when a domain is verified or removed, when the owner's plan changes, and on
     * ownership transfer, soft delete and restore.
     *
     * <p>The decision is taken now, inside the caller's transaction (it reads that transaction's
     * own writes); the Keycloak call runs only AFTER COMMIT, so a rolled-back caller cannot leave
     * Keycloak enabled (or disabled) for a state that never happened. Outside a transaction it
     * runs at once. Best effort: a Keycloak failure is logged, never thrown (the SAML admission
     * re-checks the plan and the domain on every login anyway).
     */
    public void syncIdentityProviderEnabled(UUID orgId) {
        if (keycloakClient == null || orgId == null) {
            return;
        }
        samlRepository.findByOrganization_Id(orgId).ifPresent(connection -> {
            String alias = connection.getIdpAlias();
            boolean enabled;
            try {
                enabled = shouldBrokerLogins(connection, orgId);
            } catch (RuntimeException e) {
                log.error("[saml] could not decide IdP {} state for org {}: {}", alias, orgId, e.toString());
                return;
            }
            Runnable apply = () -> {
                try {
                    keycloakClient.setEnabled(alias, enabled);
                } catch (RuntimeException e) {
                    log.error("[saml] could not set IdP {} enabled={} for org {}: {}", alias, enabled, orgId, e.toString());
                }
            };
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                apply.run();
                            }
                        });
            } else {
                apply.run();
            }
        });
    }

    private boolean shouldBrokerLogins(OrganizationSamlConnection connection, UUID orgId) {
        if (connection.getStatus() != OrganizationSamlConnection.Status.ACTIVE) {
            return false;
        }
        Organization organization = connection.getOrganization();
        if (organization == null || organization.isDeleted()) {
            return false;
        }
        return memberService.getTeamStatus(orgId).supportsTeam() && hasVerifiedDomain(orgId);
    }

    private boolean hasVerifiedDomain(UUID orgId) {
        return domainRepository.existsByOrganization_IdAndVerifiedAtIsNotNull(orgId);
    }

    @Transactional
    public void delete(UUID orgId, Long actorUserId) {
        authorizeOwnerOrAdmin(orgId, actorUserId);
        samlRepository.findByOrganization_Id(orgId).ifPresent(connection -> {
            if (keycloakClient != null) {
                keycloakClient.delete(connection.getIdpAlias());
            }
            samlRepository.delete(connection);
            auditService.record(orgId, actorUserId, OrganizationAuditEvent.Type.SAML_SSO_DELETED,
                    Map.of("idpAlias", connection.getIdpAlias()));
        });
    }

    private void provisionKeycloak(OrganizationSamlConnection connection, boolean enabled) {
        if (keycloakClient == null) {
            connection.setStatus(OrganizationSamlConnection.Status.ERROR);
            connection.setLastError("SAML SSO requires auth.mode=keycloak.");
            samlRepository.save(connection);
            throw new SamlProvisioningException(connection.getLastError());
        }

        try {
            keycloakClient.upsert(connection, enabled);
            connection.setStatus(OrganizationSamlConnection.Status.ACTIVE);
            connection.setLastSyncedAt(Instant.now());
            connection.setLastError(null);
            samlRepository.save(connection);
        } catch (RuntimeException e) {
            connection.setStatus(OrganizationSamlConnection.Status.ERROR);
            connection.setLastError(abbreviate(e.getMessage()));
            samlRepository.save(connection);
            throw new SamlProvisioningException(connection.getLastError(), e);
        }
    }

    /** Shared with {@link OrganizationSsoDomainService}: the same people manage the IdP and its domains. */
    void authorizeOwnerOrAdmin(UUID orgId, Long userId) {
        if (userId == null) {
            throw new SecurityException("X-User-ID header required");
        }
        OrganizationMember membership = memberRepository.findActiveByOrganizationIdAndUserId(orgId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found"));
        OrganizationRole role = membership.getRole();
        if (role != OrganizationRole.OWNER && role != OrganizationRole.ADMIN) {
            throw new SecurityException("Only OWNER or ADMIN can manage SAML SSO");
        }
    }

    void enforcePlanSupportsSso(UUID orgId) {
        OrganizationMemberService.TeamStatus status = memberService.getTeamStatus(orgId);
        if (!status.supportsTeam()) {
            throw new UnsupportedOperationException("SAML SSO requires a Team or Enterprise plan");
        }
    }

    private OrganizationSamlConnectionDto toDto(OrganizationSamlConnection connection) {
        String alias = connection.getIdpAlias();
        return OrganizationSamlConnectionDto.fromEntity(
                connection,
                ssoStartPath(connection.getOrganization().getId(), alias),
                serviceProviderEntityId(),
                assertionConsumerServiceUrl(alias),
                serviceProviderMetadataUrl(alias),
                certificateFingerprint(connection.getX509Certificate()));
    }

    private OrganizationSamlConnectionDto notConfiguredDto(UUID orgId) {
        String alias = aliasFor(orgId);
        return OrganizationSamlConnectionDto.notConfigured(
                orgId,
                alias,
                ssoStartPath(orgId, alias),
                serviceProviderEntityId(),
                assertionConsumerServiceUrl(alias),
                serviceProviderMetadataUrl(alias));
    }

    private String ssoStartPath(UUID orgId, String alias) {
        return "/auth/sso?org=" + URLEncoder.encode(orgId.toString(), StandardCharsets.UTF_8)
                + "&hint=" + URLEncoder.encode(alias, StandardCharsets.UTF_8);
    }

    private String serviceProviderEntityId() {
        return keycloakIssuerUri;
    }

    private String assertionConsumerServiceUrl(String alias) {
        return keycloakIssuerUri + "/broker/" + alias + "/endpoint";
    }

    private String serviceProviderMetadataUrl(String alias) {
        return assertionConsumerServiceUrl(alias) + "/descriptor";
    }

    public static String aliasFor(UUID orgId) {
        return "org-" + orgId.toString().replace("-", "") + "-saml";
    }

    /** True for a Keycloak alias produced by {@link #aliasFor(UUID)}. */
    public static boolean isOrganizationSamlAlias(String alias) {
        return alias != null && alias.matches("^org-[0-9a-fA-F]{32}-saml$");
    }

    private static String requiredTrimmed(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String validateHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                throw new IllegalArgumentException("ssoUrl must be an absolute URL");
            }
            boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
            if (!"https".equalsIgnoreCase(scheme) && !(local && "http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("ssoUrl must use https");
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("ssoUrl is invalid");
        }
    }

    private static String normalizeCertificate(String raw) {
        String value = requiredTrimmed(raw, "x509Certificate")
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s+", "");
        if (!value.matches("[A-Za-z0-9+/=]+")) {
            throw new IllegalArgumentException("x509Certificate must be PEM or base64 DER");
        }
        try {
            Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("x509Certificate is not valid base64 DER");
        }
        return value;
    }

    private static String certificateFingerprint(String normalizedCertificate) {
        if (normalizedCertificate == null || normalizedCertificate.isBlank()) {
            return null;
        }
        try {
            byte[] der = Base64.getDecoder().decode(normalizedCertificate);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            return HexFormat.of().withUpperCase().formatHex(digest).replaceAll("..(?!$)", "$0:");
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String abbreviate(String message) {
        if (message == null || message.isBlank()) {
            return "Keycloak SAML provisioning failed";
        }
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }
}
