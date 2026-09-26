package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    private final SubscriptionRepository subscriptionRepository;
    /** REQUIRES_NEW, for the one call that must not share the purge's transaction (Stripe). */
    private final TransactionOperations stripeTx;

    @PersistenceContext
    private EntityManager em;

    /** Busts the gateway's cached role of a member promoted to owner. Optional; null skips it. */
    @Autowired(required = false)
    private GatewayCacheClient gatewayCacheClient;

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

    @Autowired
    public AccountPurgeService(UserRepository userRepository,
                               OrganizationRepository organizationRepository,
                               Optional<StripeBillingService> stripeBillingService,
                               RestTemplate restTemplate,
                               WorkspaceDataPurger workspaceDataPurger,
                               SubscriptionRepository subscriptionRepository,
                               PlatformTransactionManager transactionManager) {
        this(userRepository, organizationRepository, stripeBillingService, restTemplate,
                workspaceDataPurger, subscriptionRepository, requiresNew(transactionManager));
    }

    AccountPurgeService(UserRepository userRepository,
                        OrganizationRepository organizationRepository,
                        Optional<StripeBillingService> stripeBillingService,
                        RestTemplate restTemplate,
                        WorkspaceDataPurger workspaceDataPurger,
                        SubscriptionRepository subscriptionRepository,
                        TransactionOperations stripeTx) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.stripeBillingService = stripeBillingService.orElse(null);
        this.restTemplate = restTemplate;
        this.workspaceDataPurger = workspaceDataPurger;
        this.subscriptionRepository = subscriptionRepository;
        this.stripeTx = stripeTx;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager tm) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
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

        // --- Order: every step that can fail runs before the one that cannot be undone ---
        // 1. Preflight (no side effect): can the identity be removed at all?
        // 2. Stripe, only when a Stripe subscription is linked. A failure aborts.
        // 3. Every database delete, all or nothing (see failIfAnyStatementFailed).
        // 4. The Keycloak identity. A failure aborts and rolls step 3 back.
        // 5. The purge_log outbox rows, then commit.
        // Deleting the application rows while the identity survives hands the person a fresh
        // account at their next sign-in (resolveUser bootstraps one); deleting the identity
        // while the rows survive (what a failed statement after an identity-first delete did)
        // locks them out of an account they can no longer restore. Nothing irreversible
        // happens until every database statement has succeeded, so an aborted pass leaves the
        // account exactly as it was (the Stripe cancellation aside: it is at period end, and
        // the person asked for the deletion) and the next nightly pass retries it.
        checkIdentityCanBeRemoved(user.getProviderId());
        cancelStripeSubscriptionOrThrow(userId);

        // Every statement below runs in its own savepoint and records its failure instead of
        // throwing, so one pass reports EVERY broken statement. Binding each parameter with the
        // Java type of its column is not cosmetic: the org ids are uuid and the user ids are
        // bigint, and Postgres refuses "uuid = varchar" / "bigint = varchar". A string-bound id
        // made the purge fail on every run from 2026-05-26 on, and because the old helper
        // swallowed the error without a savepoint, the aborted transaction then failed every
        // later statement.
        List<String> failures = new ArrayList<>();
        List<String> purgedOrgIds = new ArrayList<>();

        // --- Organization handling ---
        List<Organization> ownedOrgs = organizationRepository.findByOwnerId(userId);
        for (Organization org : ownedOrgs) {
            if (org.isPersonal()) {
                deleteOrganization(org.getId(), failures);
                purgedOrgIds.add(org.getId().toString());
            } else if (!handOverTeamOrg(org, userId, failures)) {
                // Empty team org (no other members) - delete it entirely
                deleteOrganization(org.getId(), failures);
                purgedOrgIds.add(org.getId().toString());
                logger.info("Account purge: deleted empty team org {}", org.getId());
            }
        }

        // Remove memberships from team orgs where user is NOT the owner
        exec(failures, "DELETE FROM auth.organization_member WHERE user_id = ?", userId);

        // The two tenant_id columns in this method are varchar (tenant id = the user id as text).
        String tenantId = userId.toString();
        exec(failures, "DELETE FROM auth.credentials WHERE tenant_id = ?", tenantId);

        // --- Auth schema cleanup (FK ordering: children before parents) ---
        // Every user_id column here is bigint, so the numeric id is bound.
        exec(failures, "DELETE FROM auth.user_onboarding WHERE user_id = ?", userId);
        exec(failures, "DELETE FROM auth.user_changelog_seen WHERE user_id = ?", userId);
        // V527 first-touch attribution. Also ON DELETE CASCADE; explicit so the purge reads complete.
        exec(failures, "DELETE FROM auth.user_acquisition WHERE user_id = ?", userId);
        exec(failures, "DELETE FROM auth.user_roles WHERE user_id = ?", userId);
        exec(failures, "DELETE FROM auth.refresh_tokens WHERE user_id = ?", userId);
        exec(failures, "DELETE FROM auth.email_verification_codes WHERE user_id = ?", userId);
        exec(failures, "DELETE FROM auth.credit_ledger WHERE user_id = ?", userId);
        exec(failures, "DELETE FROM auth.credit_reconciliation_log WHERE user_id = ?", userId);
        exec(failures, "DELETE FROM auth.credit_consumption_dead_letter WHERE tenant_id = ?", tenantId);

        // CE links (ON DELETE RESTRICT - must delete before user)
        // ce_link_audit is NOT deleted: V260 immutability trigger + design intent is audit survival
        exec(failures, "DELETE FROM auth.ce_link_heartbeat WHERE install_id IN "
                + "(SELECT install_id FROM auth.ce_link WHERE user_id = ?)", userId);
        exec(failures, "UPDATE auth.ce_link SET revoked_by_user_id = NULL WHERE revoked_by_user_id = ?", userId);
        exec(failures, "DELETE FROM auth.ce_link WHERE user_id = ?", userId);

        // Invitations created by this user in other orgs (invited_by is NOT NULL, so DELETE not UPDATE)
        exec(failures, "DELETE FROM auth.organization_invitation WHERE invited_by = ?", userId);

        // Org member quota limits referencing this user
        exec(failures, "DELETE FROM auth.org_member_quota_limit WHERE user_id = ?", userId);
        exec(failures, "UPDATE auth.org_member_quota_limit SET created_by_user_id = NULL WHERE created_by_user_id = ?", userId);

        // Billing chain: usage_cycle -> pending_credit_upgrade -> subscription -> billing_customer
        exec(failures, "DELETE FROM auth.usage_cycle WHERE subscription_id IN "
                + "(SELECT s.id FROM auth.subscription s JOIN auth.billing_customer bc ON s.billing_customer_id = bc.id WHERE bc.user_id = ?)", userId);
        exec(failures, "DELETE FROM auth.pending_credit_upgrade WHERE subscription_id IN "
                + "(SELECT s.id FROM auth.subscription s JOIN auth.billing_customer bc ON s.billing_customer_id = bc.id WHERE bc.user_id = ?)", userId);
        // pending_credit_upgrade.user_id also references auth.users with NO ACTION, so a row tied
        // to the user but not to one of their subscriptions would block the final user delete.
        exec(failures, "DELETE FROM auth.pending_credit_upgrade WHERE user_id = ?", userId);
        exec(failures, "DELETE FROM auth.subscription WHERE billing_customer_id IN "
                + "(SELECT id FROM auth.billing_customer WHERE user_id = ?)", userId);
        exec(failures, "DELETE FROM auth.billing_customer WHERE user_id = ?", userId);

        // Finally the user row
        exec(failures, "DELETE FROM auth.users WHERE id = ?", userId);

        failIfAnyStatementFailed(userId, failures);

        // --- Identity: every database statement succeeded, so now it may go ---
        deleteKeycloakUserOrThrow(user.getProviderId());

        // --- Outbox last: the followers act on these rows, and recordPurge wants nothing slow
        // after them. Other services' user-owned rows (publication.workflow_publications
        // owner_type=USER, agent.user_skill_overrides) and org-scoped rows are theirs to delete.
        for (String orgId : purgedOrgIds) {
            workspaceDataPurger.recordOrgPurge(orgId, WorkspaceDataPurger.SOURCE_ACCOUNT);
        }
        workspaceDataPurger.recordUserPurge(userId.toString());

        logger.info("Account purge: user {} fully deleted", userId);
        return true;
    }

    /**
     * For a team org: hand it to another member, promoted to owner, and return true; or return
     * false when nobody else is in it, so the caller deletes it. owner_id is NOT NULL, so it
     * cannot be nulled. Mirrors {@link OrganizationMemberService#transferOwnership}: the org row
     * AND the new owner's membership role both move, because every owner-only action (billing,
     * invitations, deleting the org) checks the membership role, not owner_id.
     */
    private boolean handOverTeamOrg(Organization org, Long departingUserId, List<String> failures) {
        UUID orgId = org.getId();

        // Find another member to transfer ownership to (prefer admin/owner, then any).
        // organization_member.organization_id is uuid: bind the UUID, never its string form.
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
        if (candidates.isEmpty()) {
            return false;
        }

        Long newOwnerId = ((Number) candidates.get(0)).longValue();
        // The departing membership goes FIRST: uq_organization_member_one_owner_per_org (V337) is
        // a non-deferrable partial unique index on (organization_id) WHERE role = 'owner', checked
        // per statement, so promoting while the departing row still says 'owner' is refused.
        // transferOwnership demotes the old owner before promoting for the same reason.
        exec(failures, "DELETE FROM auth.organization_member WHERE organization_id = ? AND user_id = ?",
                orgId, departingUserId);
        exec(failures, "UPDATE auth.organization_member SET role = 'owner' WHERE organization_id = ? AND user_id = ?",
                orgId, newOwnerId);
        exec(failures, "UPDATE auth.organization SET owner_id = ? WHERE id = ?", newOwnerId, orgId);
        bustGatewayCacheAfterCommit(newOwnerId);
        logger.info("Account purge: transferred team org {} ownership to user {}, removed user {} membership",
                orgId, newOwnerId, departingUserId);
        return true;
    }

    /**
     * The gateway caches each user's org roles for minutes; without this the promoted owner
     * keeps being sent as their old role until the entry expires. After commit only: a rolled
     * back purge promoted nobody.
     */
    private void bustGatewayCacheAfterCommit(Long userId) {
        if (gatewayCacheClient == null) return;
        String providerId = userRepository.findById(userId).map(User::getProviderId).orElse(null);
        if (providerId == null || providerId.isBlank()) return;
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            gatewayCacheClient.invalidateUserCache(providerId);
                        }
                    });
        }
    }

    /**
     * The org's rows, children first, all or nothing with the rest of the purge. The auth-side
     * operational rows go through the purger's strict variant (its failures land in the same
     * list); the org's outbox row is written by the caller once the whole purge has succeeded.
     */
    private void deleteOrganization(UUID orgId, List<String> failures) {
        workspaceDataPurger.deleteOperationalRows(orgId.toString(), failures);
        exec(failures, "DELETE FROM auth.organization_invitation WHERE organization_id = ?", orgId);
        exec(failures, "DELETE FROM auth.organization_member WHERE organization_id = ?", orgId);
        exec(failures, "DELETE FROM auth.organization WHERE id = ?", orgId);
    }

    /**
     * Cancels the Stripe subscription at period end, or aborts the purge.
     *
     * <p>Only when one is linked: an account with no active subscription, or an internal one
     * (every FREE account), has nothing to cancel, and that is decided here from the local row
     * rather than by calling Stripe and reading an exception message. When one IS linked, a
     * failure (Stripe down, a timeout) must abort: the purge deletes the local subscription
     * row, the only link to the Stripe subscription, and Stripe would then renew and charge a
     * deleted account with nothing left to find it by.
     *
     * <p>In its OWN transaction. {@link StripeBillingService} is {@code @Transactional} at class
     * level, so a plain call joins the purge's transaction and any exception it throws marks
     * that transaction rollback-only on its way out, even when caught; the commit then fails
     * with {@code UnexpectedRollbackException}. The separate transaction only writes
     * {@code auth.subscription}, never the {@code auth.users} row this purge holds locked, and
     * runs before this purge touches the subscription, so it cannot wait on its own caller.
     *
     * <p>Known gap: only {@code active}/{@code trialing} rows are looked at, the same set
     * {@code cancelSubscriptionAtPeriodEnd} itself can act on; a {@code past_due} Stripe
     * subscription is not cancelled here.
     */
    private void cancelStripeSubscriptionOrThrow(Long userId) {
        if (stripeBillingService == null) return; // CE: no Stripe
        String providerSubId = subscriptionRepository.findActiveByUserId(userId)
                .map(com.apimarketplace.auth.domain.Subscription::getProviderSubscriptionId)
                .orElse(null);
        if (providerSubId == null || providerSubId.isBlank()) {
            logger.info("Account purge: user {} has no linked Stripe subscription, nothing to cancel", userId);
            return;
        }
        try {
            stripeTx.executeWithoutResult(status -> {
                try {
                    stripeBillingService.cancelSubscriptionAtPeriodEnd(userId, "account_deleted", "User deleted their account");
                } catch (com.stripe.exception.StripeException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("Stripe cancellation failed for user " + userId + " (" + e.getMessage()
                    + "). Aborting the purge so the account is not deleted while Stripe keeps billing it.", e);
        }
    }

    /**
     * The side-effect-free half of the identity removal, run before anything else: when the
     * identity cannot possibly be removed, no step of the purge may start.
     */
    private void checkIdentityCanBeRemoved(String providerId) {
        if (!"keycloak".equals(authMode)) {
            return; // CE / embedded auth: there is no external identity to remove.
        }
        if (providerId == null || providerId.isBlank()) {
            // Not "nothing to delete": under keycloak mode the identity exists, we have simply lost
            // the handle to it. Carrying on would destroy the data and leave a realm identity keyed
            // by the same e-mail, so signing in again bootstraps a fresh account - the exact
            // outcome this method exists to prevent. Abort and let a human reconcile the id.
            throw new IllegalStateException(
                    "User has no provider_id while auth.mode=keycloak, so the Keycloak identity "
                    + "cannot be located. Refusing to delete application data while the identity "
                    + "would survive.");
        }
        if (kcServerUrl.isBlank() || kcClientSecret.isBlank()) {
            throw new IllegalStateException(
                    "Keycloak admin credentials are not configured (keycloak.admin.server-url / "
                    + "client-secret). Refusing to delete application data while the identity would "
                    + "survive - set KC_ADMIN_CLIENT_SECRET on auth-service and the purge will retry.");
        }
    }

    /**
     * Removes the Keycloak identity, or aborts the whole purge.
     *
     * <p>Previously this warned and carried on, which meant a missing {@code KC_ADMIN_CLIENT_SECRET}
     * (its exact state in production) silently downgraded "delete my account" to "delete my data,
     * keep my login". Two failure modes came out of that: the e-mail address stayed in the identity
     * provider after a deletion advertised as permanent, and the person could sign in again and be
     * bootstrapped a fresh account, so the deletion removed their work but not their access.
     *
     * <p>A 404 from Keycloak is success: the identity is already gone, which is the state we want.
     *
     * <p>Runs after every database statement has succeeded and before the outbox rows are
     * written, so a failure here rolls the database work back and the account stays whole.
     *
     * @throws IllegalStateException when the identity cannot be removed, aborting the transaction
     */
    private void deleteKeycloakUserOrThrow(String providerId) {
        if (!"keycloak".equals(authMode)) {
            return; // CE / embedded auth: there is no external identity to remove.
        }
        checkIdentityCanBeRemoved(providerId);
        try {
            String token = fetchKcAdminToken();
            String url = kcServerUrl + "/admin/realms/" + kcRealm + "/users/" + providerId;
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
            logger.info("Account purge: Keycloak user {} deleted", providerId);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound alreadyGone) {
            logger.info("Account purge: Keycloak user {} already absent, treating as deleted", providerId);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Keycloak user delete failed for " + providerId + " (" + e.getMessage()
                    + "). Aborting the purge so data and identity cannot diverge.", e);
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

    /**
     * Runs one statement inside its OWN savepoint and records a failure instead of throwing.
     *
     * <p>Postgres aborts the whole transaction on any error, so a plain try/catch that swallows
     * it (what this class did until 2026-09-25) turns the first failure into "current
     * transaction is aborted" for every later statement, and the log then names the wrong
     * cause for all but one of them. The savepoint keeps the transaction usable so the pass can
     * name every statement that fails; {@link #failIfAnyStatementFailed} then rolls the whole
     * purge back. Parameters are bound with {@code setObject}, so a {@code Long} reaches a
     * bigint column and a {@code UUID} a uuid column, with no cast in the SQL.
     */
    private void exec(List<String> failures, String sql, Object... params) {
        em.unwrap(org.hibernate.Session.class).doWork(conn -> {
            java.sql.Savepoint sp = conn.setSavepoint();
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                ps.executeUpdate();
                conn.releaseSavepoint(sp);
            } catch (java.sql.SQLException e) {
                conn.rollback(sp);
                try {
                    conn.releaseSavepoint(sp);
                } catch (java.sql.SQLException ignore) {
                    // savepoint already gone after the rollback - nothing to release
                }
                String statement = sql.substring(0, Math.min(sql.length(), 90));
                logger.warn("Account purge statement failed [{}]: {}", statement, e.getMessage());
                failures.add(statement + " -> " + e.getMessage());
            }
        });
    }

    /**
     * All or nothing. A purge that skipped a statement and still deleted the user row would
     * leave rows nothing can ever find again (the row that located them is gone), while the
     * account reads as deleted. Throwing rolls every delete back before the identity or the
     * outbox is touched, so the account stays queued, and signable-in, and the next nightly
     * pass retries the whole thing.
     */
    private void failIfAnyStatementFailed(Long userId, List<String> failures) {
        if (failures.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Account purge of user " + userId + " rolled back: "
                + failures.size() + " statement(s) failed, so no row was deleted and the "
                + "identity was not touched. " + String.join(" | ", failures));
    }
}
