package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Transactional service that hard-deletes all data for a deactivated user.
 * Separated from {@link AccountPurgeScheduler} so Spring AOP proxies the
 * {@code @Transactional} correctly (no self-invocation bypass).
 *
 * <p>SAFETY: only purges personal orgs ({@code is_personal=true}). For team
 * orgs, ownership is transferred to another member. Empty team orgs are
 * deleted entirely (no members = no data to preserve).
 */
@Service
public class AccountPurgeService {

    private static final Logger logger = LoggerFactory.getLogger(AccountPurgeService.class);

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final StripeBillingService stripeBillingService; // null in CE mode (no Stripe)
    private final RestTemplate restTemplate;
    private final WorkspaceDataPurger workspaceDataPurger;

    @PersistenceContext
    private EntityManager em;

    @Value("${keycloak.admin.server-url:}")
    private String kcServerUrl;

    @Value("${keycloak.admin.realm:livecontext}")
    private String kcRealm;

    @Value("${keycloak.admin.client-id:livecontext-admin-api}")
    private String kcClientId;

    @Value("${keycloak.admin.client-secret:}")
    private String kcClientSecret;

    @Value("${auth.mode:embedded}")
    private String authMode;

    public AccountPurgeService(UserRepository userRepository,
                               OrganizationRepository organizationRepository,
                               Optional<StripeBillingService> stripeBillingService,
                               RestTemplate restTemplate,
                               WorkspaceDataPurger workspaceDataPurger) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.stripeBillingService = stripeBillingService.orElse(null);
        this.restTemplate = restTemplate;
        this.workspaceDataPurger = workspaceDataPurger;
    }

    @Transactional
    public boolean purgeUser(Long userId) {
        User user = em.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
        if (user == null) {
            logger.warn("Account purge: user {} not found, skipping", userId);
            return false;
        }
        if (user.isEnabled() || user.getDeactivatedAt() == null) {
            logger.info("Account purge: user {} was reactivated, skipping", userId);
            return false;
        }

        // --- External service calls (best-effort, before DB mutations) ---
        cancelStripeSubscription(userId);
        deleteKeycloakUser(user.getProviderId());

        // --- Organization handling ---
        List<Organization> ownedOrgs = organizationRepository.findByOwnerId(userId);
        for (Organization org : ownedOrgs) {
            String orgId = org.getId().toString();
            if (org.isPersonal()) {
                purgeOrganizationData(orgId);
                nativeExec("DELETE FROM auth.organization_invitation WHERE organization_id = ?1", orgId);
                nativeExec("DELETE FROM auth.organization_member WHERE organization_id = ?1", orgId);
                nativeExec("DELETE FROM auth.organization WHERE id = ?1::uuid", orgId);
            } else {
                handleTeamOrgBeforeUserDelete(org, userId);
            }
        }

        // Remove memberships from team orgs where user is NOT the owner
        nativeExec("DELETE FROM auth.organization_member WHERE user_id = ?1", userId);

        // --- User-scoped data across schemas ---
        purgeUserDirectData(userId);

        // --- Auth schema cleanup (FK ordering: children before parents) ---
        String uid = userId.toString();
        nativeExec("DELETE FROM agent.user_skill_overrides WHERE user_id = ?1", uid);
        nativeExec("DELETE FROM auth.user_onboarding WHERE user_id = ?1", uid);
        nativeExec("DELETE FROM auth.user_roles WHERE user_id = ?1", uid);
        nativeExec("DELETE FROM auth.refresh_tokens WHERE user_id = ?1", uid);
        nativeExec("DELETE FROM auth.email_verification_codes WHERE user_id = ?1", userId);
        nativeExec("DELETE FROM auth.credit_ledger WHERE user_id = ?1", uid);
        nativeExec("DELETE FROM auth.credit_reconciliation_log WHERE user_id = ?1", uid);
        nativeExec("DELETE FROM auth.credit_consumption_dead_letter WHERE tenant_id = ?1", uid);

        // CE links (ON DELETE RESTRICT - must delete before user)
        // ce_link_audit is NOT deleted: V260 immutability trigger + design intent is audit survival
        nativeExec("DELETE FROM auth.ce_link_heartbeat WHERE install_id IN " +
                "(SELECT install_id FROM auth.ce_link WHERE user_id = ?1)", userId);
        nativeExec("UPDATE auth.ce_link SET revoked_by_user_id = NULL WHERE revoked_by_user_id = ?1", userId);
        nativeExec("DELETE FROM auth.ce_link WHERE user_id = ?1", userId);

        // Invitations created by this user in other orgs (invited_by is NOT NULL, so DELETE not UPDATE)
        nativeExec("DELETE FROM auth.organization_invitation WHERE invited_by = ?1", userId);

        // Org member quota limits referencing this user
        nativeExec("DELETE FROM auth.org_member_quota_limit WHERE user_id = ?1", userId);
        nativeExec("UPDATE auth.org_member_quota_limit SET created_by_user_id = NULL WHERE created_by_user_id = ?1", userId);

        // Billing chain: usage_cycle → pending_credit_upgrade → subscription → billing_customer
        nativeExec("DELETE FROM auth.usage_cycle WHERE subscription_id IN " +
                "(SELECT s.id FROM auth.subscription s JOIN auth.billing_customer bc ON s.billing_customer_id = bc.id WHERE bc.user_id = ?1)", userId);
        nativeExec("DELETE FROM auth.pending_credit_upgrade WHERE subscription_id IN " +
                "(SELECT s.id FROM auth.subscription s JOIN auth.billing_customer bc ON s.billing_customer_id = bc.id WHERE bc.user_id = ?1)", userId);
        nativeExec("DELETE FROM auth.subscription WHERE billing_customer_id IN " +
                "(SELECT id FROM auth.billing_customer WHERE user_id = ?1)", userId);
        nativeExec("DELETE FROM auth.billing_customer WHERE user_id = ?1", userId);

        // Finally the user row
        em.createNativeQuery("DELETE FROM auth.users WHERE id = ?1")
                .setParameter(1, userId)
                .executeUpdate();

        logger.info("Account purge: user {} fully deleted", userId);
        return true;
    }

    /**
     * For team orgs: transfer ownership to another member, or delete entirely
     * if the org is empty. owner_id is NOT NULL, so we cannot nullify it.
     */
    private void handleTeamOrgBeforeUserDelete(Organization org, Long departingUserId) {
        String orgId = org.getId().toString();

        // Find another member to transfer ownership to (prefer admin/owner, then any)
        @SuppressWarnings("unchecked")
        List<Object> candidates = em.createNativeQuery(
                "SELECT om.user_id FROM auth.organization_member om " +
                "WHERE om.organization_id = ?1 AND om.user_id != ?2 AND om.role IN ('owner', 'admin') " +
                "ORDER BY om.joined_at ASC LIMIT 1")
                .setParameter(1, orgId)
                .setParameter(2, departingUserId)
                .getResultList();

        if (candidates.isEmpty()) {
            // No admin - try any member
            candidates = em.createNativeQuery(
                    "SELECT om.user_id FROM auth.organization_member om " +
                    "WHERE om.organization_id = ?1 AND om.user_id != ?2 " +
                    "ORDER BY om.joined_at ASC LIMIT 1")
                    .setParameter(1, orgId)
                    .setParameter(2, departingUserId)
                    .getResultList();
        }

        if (!candidates.isEmpty()) {
            Object newOwnerId = candidates.get(0);
            em.createNativeQuery("UPDATE auth.organization SET owner_id = ?1 WHERE id = ?2")
                    .setParameter(1, newOwnerId)
                    .setParameter(2, org.getId())
                    .executeUpdate();
            // Remove departing user's membership
            nativeExec2("DELETE FROM auth.organization_member WHERE organization_id = ?1 AND user_id = ?2", orgId, departingUserId);
            logger.info("Account purge: transferred team org {} ownership to user {}, removed user {} membership",
                    org.getId(), newOwnerId, departingUserId);
        } else {
            // Empty team org (no other members) - delete it entirely
            purgeOrganizationData(orgId);
            nativeExec("DELETE FROM auth.organization_invitation WHERE organization_id = ?1", orgId);
            nativeExec("DELETE FROM auth.organization_member WHERE organization_id = ?1", orgId);
            nativeExec("DELETE FROM auth.organization WHERE id = ?1::uuid", orgId);
            logger.info("Account purge: deleted empty team org {}", org.getId());
        }
    }

    private void purgeOrganizationData(String orgId) {
        // Single source of truth (also used by the workspace-delete flow). Purges all
        // operational org-scoped data across every schema; never the financial ledger.
        workspaceDataPurger.purgeOperationalData(orgId);
    }

    private void purgeUserDirectData(Long userId) {
        String uid = userId.toString();
        nativeExec("DELETE FROM publication.workflow_publications WHERE owner_type = 'USER' AND owner_id = ?1", uid);
        // credentials scoped by tenant_id (= userId string)
        nativeExec("DELETE FROM auth.credentials WHERE tenant_id = ?1", uid);
    }

    private void cancelStripeSubscription(Long userId) {
        if (stripeBillingService == null) return;
        try {
            stripeBillingService.cancelSubscriptionAtPeriodEnd(userId, "account_deleted", "User deleted their account");
        } catch (Exception e) {
            logger.warn("Account purge: Stripe cancellation failed for user {}: {}", userId, e.getMessage());
        }
    }

    private void deleteKeycloakUser(String providerId) {
        if (!"keycloak".equals(authMode) || providerId == null || providerId.isBlank()) {
            return;
        }
        if (kcServerUrl.isBlank() || kcClientSecret.isBlank()) {
            logger.warn("Account purge: KC admin config missing, skipping KC user delete for {}", providerId);
            return;
        }
        try {
            String token = fetchKcAdminToken();
            String url = kcServerUrl + "/admin/realms/" + kcRealm + "/users/" + providerId;
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
            logger.info("Account purge: Keycloak user {} deleted", providerId);
        } catch (Exception e) {
            logger.warn("Account purge: KC user delete failed for {}: {}", providerId, e.getMessage());
        }
    }

    private String fetchKcAdminToken() {
        String tokenUrl = kcServerUrl + "/realms/" + kcRealm + "/protocol/openid-connect/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "client_credentials");
        params.add("client_id", kcClientId);
        params.add("client_secret", kcClientSecret);
        ResponseEntity<Map> resp = restTemplate.exchange(tokenUrl, HttpMethod.POST,
                new HttpEntity<>(params, headers), Map.class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new IllegalStateException("KC admin token returned " + resp.getStatusCode());
        }
        return (String) resp.getBody().get("access_token");
    }

    private void nativeExec(String sql, Object param) {
        try {
            em.createNativeQuery(sql).setParameter(1, param).executeUpdate();
        } catch (Exception e) {
            logger.warn("Purge SQL failed [{}] param={}: {}", sql.substring(0, Math.min(sql.length(), 60)), param, e.getMessage());
        }
    }

    private void nativeExec2(String sql, Object param1, Object param2) {
        try {
            em.createNativeQuery(sql).setParameter(1, param1).setParameter(2, param2).executeUpdate();
        } catch (Exception e) {
            logger.warn("Purge SQL failed [{}]: {}", sql.substring(0, Math.min(sql.length(), 60)), e.getMessage());
        }
    }

    private int nativeExecCount(String sql, String param) {
        try {
            return em.createNativeQuery(sql).setParameter(1, param).executeUpdate();
        } catch (Exception e) {
            logger.warn("Purge SQL failed [{}] param={}: {}", sql.substring(0, Math.min(sql.length(), 60)), param, e.getMessage());
            return 0;
        }
    }
}
