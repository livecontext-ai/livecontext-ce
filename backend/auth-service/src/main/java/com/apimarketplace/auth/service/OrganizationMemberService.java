package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.*;
import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for organization member management: invitations, role changes, removal.
 * Plan gating is based on the organization owner's subscription plan.
 */
@Service
@Transactional
public class OrganizationMemberService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationMemberService.class);

    private final OrganizationMemberRepository memberRepository;
    private final OrganizationInvitationRepository invitationRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final OnboardingService onboardingService;
    private final OrganizationAuditService auditService;
    private final OrganizationInvitationMailer invitationMailer;
    private final AppEditionProvider editionProvider;
    // Field-injected (optional) so the existing constructor + unit-test wiring are
    // untouched. When present, getOwnerPlan delegates to it (single source of truth);
    // when null (slim tests) it falls back to the local subscriptionRepository lookup.
    @Autowired(required = false)
    private PlanResolutionService planResolutionService;
    private static final String AUTH_MODE_EMBEDDED = "embedded";
    private static final String SUBJECT_TYPE_ORG_INVITATION = "ORG_INVITATION";
    private static final String CATEGORY_ORG_INVITATION_PENDING = "ORG_INVITATION_PENDING";

    /**
     * Best-effort notification client. Optional so legacy unit tests and small
     * auth-service slices do not need to wire orchestrator infrastructure.
     */
    @Autowired(required = false)
    private NotificationClient notificationClient;

    @Value("${auth.mode:keycloak}")
    private String authMode = "keycloak";

    // S-2 invite rate limit knobs - override via env in prod if needed. The
    // window is rolling-hour, counted on actually-persisted invitation rows
    // (rejected attempts that short-circuit before save don't pad the count).
    private final int invitesPerInviterPerHour;
    private final int invitesPerOrgPerHour;

    // Milestone-1 audit fix: every membership-mutating op must bust the
    // gateway's user-resolution cache for the affected user so the next
    // request reflects the new memberships list immediately (without waiting
    // up to 5min for the QuotaCacheService TTL).
    private final GatewayCacheClient gatewayCacheClient;

    public OrganizationMemberService(OrganizationMemberRepository memberRepository,
                                     OrganizationInvitationRepository invitationRepository,
                                     OrganizationRepository organizationRepository,
                                     UserRepository userRepository,
                                     SubscriptionRepository subscriptionRepository,
                                     OnboardingService onboardingService,
                                     OrganizationAuditService auditService,
                                     OrganizationInvitationMailer invitationMailer,
                                     AppEditionProvider editionProvider,
                                     GatewayCacheClient gatewayCacheClient,
                                     @Value("${org.invite.rate-limit.per-inviter-per-hour:20}") int invitesPerInviterPerHour,
                                     @Value("${org.invite.rate-limit.per-org-per-hour:50}") int invitesPerOrgPerHour) {
        this.memberRepository = memberRepository;
        this.invitationRepository = invitationRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.onboardingService = onboardingService;
        this.auditService = auditService;
        this.invitationMailer = invitationMailer;
        this.editionProvider = editionProvider;
        this.gatewayCacheClient = gatewayCacheClient;
        this.invitesPerInviterPerHour = invitesPerInviterPerHour;
        this.invitesPerOrgPerHour = invitesPerOrgPerHour;
    }

    /**
     * Milestone-1 audit fix: centralise the post-mutation cache bust so every
     * membership-changing op (accept, leave, remove, role change, ownership
     * transfer, soft-delete) keeps the gateway's user-resolution cache in
     * sync. Best-effort - the {@link GatewayCacheClient} logs+swallows so a
     * cache-bust hiccup never breaks the business mutation.
     *
     * <p>Milestone-1 audit round-2 unanime: defer the actual HTTP call to
     * AFTER the surrounding transaction commits, via
     * {@link org.springframework.transaction.support.TransactionSynchronizationManager}.
     * Otherwise the gateway re-resolves the user inside our open tx and
     * re-reads stale pre-commit state, repopulating the cache it was about
     * to bust - and worse, a tx rollback would still fire a useless cache
     * eviction. Same pattern as the trigger v3.5 P1 follow-up
     * (afterCommit hook / HTTP-in-tx fix, commit 8c4857e8c).
     *
     * <p>If we're not in a transaction (called from a manual context or
     * post-commit path), fall back to synchronous invocation so callers
     * don't silently lose the bust.
     */
    private void bustGatewayCacheFor(User user) {
        if (user == null) return;
        final String providerId = user.getProviderId();
        if (providerId == null || providerId.isBlank()) return;

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            gatewayCacheClient.invalidateUserCache(providerId);
                        }
                    });
        } else {
            // Not inside a tx - call directly (covers ad-hoc / test-bench paths).
            gatewayCacheClient.invalidateUserCache(providerId);
        }
    }

    /**
     * Invite a member to an organization by email. The invitee always accepts
     * explicitly; CE embedded auth delivers existing-user invites through the
     * in-app notification bell instead of SMTP.
     */
    public OrganizationInvitation inviteMember(UUID orgId, String email, OrganizationRole role, Long invitedByUserId) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found"));

        User inviter = userRepository.findById(invitedByUserId)
                .orElseThrow(() -> new IllegalArgumentException("Inviter user not found"));

        // Check inviter is OWNER or ADMIN
        OrganizationMember inviterMembership = memberRepository.findByOrganization_IdAndUser_Id(orgId, invitedByUserId)
                .orElseThrow(() -> new IllegalArgumentException("Inviter is not a member of this organization"));

        if (inviterMembership.getRole() != OrganizationRole.OWNER &&
                inviterMembership.getRole() != OrganizationRole.ADMIN) {
            throw new SecurityException("Only OWNER or ADMIN can invite members");
        }

        // Check plan supports team - CE gates teammates by the owner's (cloud-aware) plan,
        // exactly like cloud: adding members requires a TEAM/ENTERPRISE-capable plan. The owner
        // plan resolves to the bound cloud account's plan when the install is CLOUD-linked.
        Plan ownerPlan = getOwnerPlan(org);
        if (ownerPlan == null || !ownerPlan.supportsTeam()) {
            throw new UnsupportedOperationException("Current plan does not support team members. Upgrade to TEAM or ENTERPRISE.");
        }

        // Check member limit (current members + pending invitations)
        long currentMembers = memberRepository.countByOrganization_Id(orgId);
        long pendingInvitations = invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING);
        int maxMembers = ownerPlan.getMaxMembers() != null ? ownerPlan.getMaxMembers() : 1;

        if (currentMembers + pendingInvitations >= maxMembers) {
            throw new IllegalStateException("Member limit reached (" + maxMembers + "). Upgrade your plan for more members.");
        }

        // S-2 rate limit - applied AFTER auth/role AND plan/quota checks (so
        // a legitimate FREE-plan admin gets the actionable "upgrade your plan"
        // 403 instead of a 429), but BEFORE the email lookups (so the spammer
        // cannot use this endpoint as an existing-account oracle). Per-inviter
        // + per-org caps are rolling-hour windows on persisted invitations.
        enforceInviteRateLimit(orgId, invitedByUserId);

        // Check email not already a member
        Optional<User> existingUser = userRepository.findByEmail(email);
        if (existingUser.isPresent() && memberRepository.existsByOrganization_IdAndUser_Id(orgId, existingUser.get().getId())) {
            throw new IllegalStateException("User with this email is already a member of this organization");
        }

        // Check no pending invitation for this email
        if (invitationRepository.existsByOrganization_IdAndEmailAndStatus(orgId, email, InvitationStatus.PENDING)) {
            throw new IllegalStateException("A pending invitation already exists for this email");
        }

        // Cannot invite as OWNER
        if (role == OrganizationRole.OWNER) {
            throw new IllegalArgumentException("Cannot invite as OWNER. Use ownership transfer instead.");
        }

        // CE invite-by-link: a brand-new email (no local account yet) is now
        // allowed under embedded auth. The invitation is persisted PENDING with
        // its generated token; the admin shares the copyable accept link and the
        // invitee registers through it (EmbeddedAuthController.register with the
        // invitationToken bypasses the closed-registration door and auto-accepts).
        // Existing local users still get the in-app notification bell below.

        // Create invitation
        OrganizationInvitation invitation = new OrganizationInvitation(org, email, role, inviter);
        invitation = invitationRepository.save(invitation);

        log.info("Created invitation {} for email {} to org {} with role {}",
                invitation.getId(), email, orgId, role);

        // Audit BEFORE the auto-accept so the chronological order in the log
        // reads "invited then accepted" - both record() calls open separate
        // REQUIRES_NEW transactions, so swapping the order matters for the UX.
        auditService.record(orgId, invitedByUserId, OrganizationAuditEvent.Type.MEMBER_INVITED,
                java.util.Map.of(
                        "email", email,
                        "role", role.name()));

        // PR4 (Q2=a, explicit consent): EVERY invitation now follows the
        // PENDING-then-user-clicks-accept path, regardless of whether the
        // invitee already has an account. The previous silent auto-accept
        // path (existing-user branch) was a consent gap - users could find
        // themselves in an organization they never agreed to join. Now the
        // invitee receives the email + their notification bell and must
        // explicitly accept via /invitations/accept?token=... (or the new
        // /app/invitations inbox page shipped in PR4b).
        // Embedded auth overrides the delivery channel below: it has no
        // Keycloak/SMTP dependency, so existing local users get an in-app
        // notification instead of an email.
        String inviterDisplay = inviter.getUsername() != null && !inviter.getUsername().isBlank()
                ? inviter.getUsername()
                : inviter.getEmail();
        if (isEmbeddedAuthMode()) {
            emitCeInvitationNotificationIfPossible(invitation, existingUser.orElse(null), inviterDisplay);
        } else {
            invitationMailer.sendInvitationEmail(
                    email, org.getName(), inviterDisplay,
                    invitation.getToken(), role.name());
        }

        return invitation;
    }

    private boolean isEmbeddedAuthMode() {
        return AUTH_MODE_EMBEDDED.equalsIgnoreCase(authMode);
    }

    private void emitCeInvitationNotificationIfPossible(OrganizationInvitation invitation,
                                                        User invitee,
                                                        String inviterDisplay) {
        if (notificationClient == null || invitation == null || invitee == null) {
            return;
        }
        if (invitee.getId() == null || invitation.getId() == null || invitation.getOrganization() == null) {
            return;
        }

        Organization org = invitation.getOrganization();
        Instant now = Instant.now();
        NotificationEmitRequest req = new NotificationEmitRequest();
        req.setTenantId(String.valueOf(invitee.getId()));
        // Post-V261 - stamp the invitee's default personal org so the
        // notification surfaces in their personal sidebar regardless of the
        // inviter's active workspace. Without this, the row's
        // organization_id falls back to the inviter's request context (=
        // inviting org) and the invitee never sees the bell.
        var inviteeDefaultOrg = memberRepository.findDefaultPersonalByUserId(invitee.getId());
        if (inviteeDefaultOrg.isEmpty()) {
            log.warn("ORG_INVITATION_PENDING: invitee {} has no default-personal org - notification "
                    + "skipped (degenerate state, onboarding should always create one)",
                    invitee.getId());
            return;
        }
        req.setOrganizationId(inviteeDefaultOrg.get().getOrganization().getId().toString());
        req.setCategory(CATEGORY_ORG_INVITATION_PENDING);
        req.setSeverity("info");
        req.setSubjectType(SUBJECT_TYPE_ORG_INVITATION);
        req.setSubjectId(invitation.getId());
        req.setSourceId("org-invitation:" + invitation.getId());

        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "pending");
        payload.put("subjectName", org.getName());
        payload.put("organizationId", org.getId().toString());
        payload.put("organizationName", org.getName());
        payload.put("invitationId", invitation.getId().toString());
        payload.put("role", invitation.getRole().name());
        if (inviterDisplay != null && !inviterDisplay.isBlank()) {
            payload.put("inviterName", inviterDisplay);
        }
        req.setPayload(payload);
        req.setOccurredAt(now);

        Runnable emit = () -> {
            try {
                notificationClient.emit(req);
            } catch (Exception ex) {
                log.warn("ORG_INVITATION_PENDING emit threw for invitation {} target user {}: {}",
                        invitation.getId(), invitee.getId(), ex.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emit.run();
                }
            });
        } else {
            emit.run();
        }
    }

    /**
     * S-2 invite rate limit. Counted on rows actually persisted in the rolling
     * hour preceding now, so authorization failures and dedupe rejections don't
     * pad the count. Per-inviter cap protects against a rogue ADMIN/OWNER;
     * per-org cap protects against several admins on the same org colluding
     * to spam from the same domain (SPF reputation damage).
     */
    private void enforceInviteRateLimit(UUID orgId, Long inviterId) {
        LocalDateTime windowStart = LocalDateTime.now().minusHours(1);
        long inviterRecent = invitationRepository.countByInvitedBy_IdAndCreatedAtAfter(inviterId, windowStart);
        if (inviterRecent >= invitesPerInviterPerHour) {
            recordRateLimitAudit(orgId, inviterId, "per-inviter", inviterRecent, invitesPerInviterPerHour);
            throw new InvitationRateLimitException(
                    "Invitation rate limit reached for this user (" + invitesPerInviterPerHour
                            + "/hour). Please wait before sending more invitations.");
        }
        long orgRecent = invitationRepository.countByOrganization_IdAndCreatedAtAfter(orgId, windowStart);
        if (orgRecent >= invitesPerOrgPerHour) {
            recordRateLimitAudit(orgId, inviterId, "per-org", orgRecent, invitesPerOrgPerHour);
            throw new InvitationRateLimitException(
                    "Invitation rate limit reached for this organization (" + invitesPerOrgPerHour
                            + "/hour). Please wait before sending more invitations.");
        }
    }

    /**
     * Audit trail for rate-limited invites. Without this, a patient attacker
     * spamming under the cap forever leaves only stdout logs - the audit log
     * is where ops/security would look first. REQUIRES_NEW inside
     * OrganizationAuditService isolates the write from the failing TX.
     */
    private void recordRateLimitAudit(UUID orgId, Long inviterId, String dimension, long observed, int cap) {
        auditService.record(orgId, inviterId,
                OrganizationAuditEvent.Type.INVITE_RATE_LIMITED,
                java.util.Map.of(
                        "dimension", dimension,
                        "observed", observed,
                        "cap", cap,
                        "windowHours", 1));
    }

    /**
     * Accept an invitation by token.
     */
    public OrganizationInvitation acceptInvitation(String token, Long userId) {
        OrganizationInvitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invitation not found"));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new IllegalStateException("Invitation is no longer pending (status: " + invitation.getStatus() + ")");
        }

        if (invitation.isExpired()) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            invitationRepository.save(invitation);
            throw new IllegalStateException("Invitation has expired");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Verify email matches
        if (!invitation.getEmail().equalsIgnoreCase(user.getEmail())) {
            throw new SecurityException("Invitation email does not match user email");
        }

        acceptInvitationInternal(invitation, user);

        // PR-3 audit gap fix: the mail-flow path now emits INVITE_ACCEPTED.
        // The auto-accept path in inviteMember already emits it ; this is the
        // counterpart for the token-clicked path. Without it the audit log
        // would show MEMBER_INVITED with no matching INVITE_ACCEPTED.
        auditService.record(invitation.getOrganization().getId(), userId,
                OrganizationAuditEvent.Type.INVITE_ACCEPTED,
                java.util.Map.of(
                        "email", invitation.getEmail(),
                        "role", invitation.getRole().name(),
                        "autoAccepted", false));
        return invitation;
    }

    /**
     * PR4b - accept an invitation by its UUID for an authenticated user.
     * Email match is validated (the invitation MUST be addressed to this
     * user) - defence against an attacker who guesses an invitation UUID.
     * Counterpart of {@link #acceptInvitation(String, Long)} which validates
     * a token instead; this variant is used by the /app/invitations inbox
     * where the user is already signed in.
     */
    public OrganizationInvitation acceptInvitationById(UUID invitationId, Long userId) {
        OrganizationInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new IllegalArgumentException("Invitation not found"));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new IllegalStateException("Invitation is no longer pending (status: " + invitation.getStatus() + ")");
        }
        if (invitation.isExpired()) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            invitationRepository.save(invitation);
            throw new IllegalStateException("Invitation has expired");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getEmail() == null || !invitation.getEmail().equalsIgnoreCase(user.getEmail())) {
            // CRITICAL guard: a UUID-guessing attacker who learns an invitation
            // ID must NOT be able to redirect that membership to themselves.
            throw new SecurityException("Invitation email does not match user email");
        }

        acceptInvitationInternal(invitation, user);

        auditService.record(invitation.getOrganization().getId(), userId,
                OrganizationAuditEvent.Type.INVITE_ACCEPTED,
                java.util.Map.of(
                        "email", invitation.getEmail(),
                        "role", invitation.getRole().name(),
                        "autoAccepted", false,
                        "viaInbox", true));
        return invitation;
    }

    /**
     * Decline a pending invitation from the authenticated user's inbox.
     * Uses CANCELLED because the persisted enum has no separate DECLINED state.
     */
    public OrganizationInvitation declineInvitationById(UUID invitationId, Long userId) {
        OrganizationInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new IllegalArgumentException("Invitation not found"));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new IllegalStateException("Invitation is no longer pending (status: " + invitation.getStatus() + ")");
        }
        if (invitation.isExpired()) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            invitationRepository.save(invitation);
            throw new IllegalStateException("Invitation has expired");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (user.getEmail() == null || !invitation.getEmail().equalsIgnoreCase(user.getEmail())) {
            throw new SecurityException("Invitation email does not match user email");
        }

        invitation.setStatus(InvitationStatus.CANCELLED);
        invitationRepository.save(invitation);

        Organization org = invitation.getOrganization();
        if (org != null && org.getId() != null) {
            auditService.record(org.getId(), userId, OrganizationAuditEvent.Type.INVITE_CANCELLED,
                    Map.of(
                            "email", invitation.getEmail(),
                            "declinedByUserId", userId));
        }

        return invitation;
    }

    /**
     * Cancel a pending invitation.
     */
    public void cancelInvitation(UUID orgId, UUID invitationId, Long userId) {
        // Check requester is OWNER or ADMIN
        OrganizationMember requester = memberRepository.findByOrganization_IdAndUser_Id(orgId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Requester is not a member"));

        if (requester.getRole() != OrganizationRole.OWNER &&
                requester.getRole() != OrganizationRole.ADMIN) {
            throw new SecurityException("Only OWNER or ADMIN can cancel invitations");
        }

        OrganizationInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new IllegalArgumentException("Invitation not found"));

        if (!invitation.getOrganization().getId().equals(orgId)) {
            throw new IllegalArgumentException("Invitation does not belong to this organization");
        }

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new IllegalStateException("Can only cancel pending invitations");
        }

        invitation.setStatus(InvitationStatus.CANCELLED);
        invitationRepository.save(invitation);

        log.info("Cancelled invitation {} for email {} in org {}", invitationId, invitation.getEmail(), orgId);

        auditService.record(orgId, userId, OrganizationAuditEvent.Type.INVITE_CANCELLED,
                java.util.Map.of(
                        "invitationId", invitationId.toString(),
                        "email", invitation.getEmail(),
                        "role", invitation.getRole().name()));
    }

    /**
     * Remove a member from an organization. Target is identified by the user id of the
     * member to remove (the OrganizationMember row is resolved via {orgId, userId}).
     */
    public void removeMember(UUID orgId, Long targetUserId, Long requesterId) {
        OrganizationMember requester = memberRepository.findByOrganization_IdAndUser_Id(orgId, requesterId)
                .orElseThrow(() -> new IllegalArgumentException("Requester is not a member"));

        if (requester.getRole() != OrganizationRole.OWNER &&
                requester.getRole() != OrganizationRole.ADMIN) {
            throw new SecurityException("Only OWNER or ADMIN can remove members");
        }

        OrganizationMember target = memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Member not found"));

        if (target.getRole() == OrganizationRole.OWNER) {
            throw new IllegalStateException("Cannot remove the organization owner");
        }

        if (targetUserId.equals(requesterId)) {
            throw new IllegalStateException("Cannot remove yourself. Use leave instead.");
        }

        // ADMIN cannot remove other ADMINs
        if (requester.getRole() == OrganizationRole.ADMIN && target.getRole() == OrganizationRole.ADMIN) {
            throw new SecurityException("ADMIN cannot remove other ADMINs");
        }

        memberRepository.delete(target);
        log.info("Removed member (user {}) from org {}", targetUserId, orgId);

        // Milestone-1 audit fix: SECURITY-relevant cache-bust. Without it,
        // a removed member's cached `memberships` keeps their old role for
        // up to 5min - they could still claim the org via
        // X-Active-Organization-ID.
        bustGatewayCacheFor(target.getUser());

        auditService.record(orgId, requesterId, OrganizationAuditEvent.Type.MEMBER_REMOVED,
                java.util.Map.of(
                        "targetUserId", targetUserId,
                        "targetRole", target.getRole().name()));
    }

    /**
     * Member voluntarily leaves an organization. OWNER cannot leave (must
     * transferOwnership first - implemented in PR-4c).
     *
     * <p>If the leaving membership is the user's default org, the next-oldest
     * remaining membership (typically the personal org) is promoted to
     * {@code isDefault=true} so that {@code getCurrentOrganization} keeps
     * returning a valid result. Without this promotion the user becomes
     * orphan-of-default and the frontend home page 404s.
     *
     * @throws IllegalArgumentException if the user is not a member of the org
     * @throws IllegalStateException    if the user is the OWNER
     */
    public void leave(UUID orgId, Long userId) {
        OrganizationMember member = memberRepository.findByOrganization_IdAndUser_Id(orgId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Not a member of this organization"));

        if (member.getRole() == OrganizationRole.OWNER) {
            throw new IllegalStateException(
                    "OWNER cannot leave the organization. Transfer ownership first (PR-4c).");
        }

        boolean wasDefault = member.isDefault();
        OrganizationRole leftRole = member.getRole();
        User leavingUser = member.getUser();
        memberRepository.delete(member);
        log.info("User {} left org {}", userId, orgId);

        // Milestone-1 audit fix: bust the leaving user's gateway cache so
        // they can no longer claim the org via X-Active-Organization-ID.
        bustGatewayCacheFor(leavingUser);

        auditService.record(orgId, userId, OrganizationAuditEvent.Type.MEMBER_LEFT,
                java.util.Map.of(
                        "role", leftRole.name(),
                        "wasDefault", wasDefault));

        if (wasDefault) {
            // Promote the next-oldest remaining membership (personal org is
            // typically created at signup and ranks first by joinedAt). Skip
            // the one we just deleted (in case Hibernate hasn't flushed yet).
            memberRepository.findByUser_Id(userId).stream()
                    .filter(m -> !m.getOrganization().getId().equals(orgId))
                    .min(java.util.Comparator.comparing(OrganizationMember::getJoinedAt))
                    .ifPresent(promoted -> {
                        promoted.setDefault(true);
                        memberRepository.save(promoted);
                        log.info("Promoted membership in org {} to default for user {} after leave",
                                promoted.getOrganization().getId(), userId);
                    });
        }
    }

    /**
     * Transfer ownership of an organization from the current OWNER to an existing
     * member. The current OWNER is demoted to ADMIN (so they keep elevated rights
     * but are no longer the principal owner). The target member is promoted to
     * OWNER. PR-4c.
     *
     * <p>Constraints:
     * <ul>
     *   <li>{@code currentOwnerUserId} must be the current OWNER of the org.</li>
     *   <li>{@code newOwnerUserId} must already be a member of the org (cannot
     *       transfer to an outsider - they would need to be invited and accept
     *       first).</li>
     *   <li>{@code newOwnerUserId} must not be the current OWNER (no-op transfer
     *       is rejected to keep audit logs clean).</li>
     * </ul>
     *
     * <p><b>Atomic flip:</b> both {@code OrganizationMember.role} AND
     * {@code Organization.owner_id} are updated. The owner_id FK had to be
     * flipped in addition to the role because (a) {@code ON DELETE CASCADE}
     * on {@code organization.owner_id} would cascade-delete the whole org if
     * the OLD owner ever deletes their account, and (b) {@code getOwnerPlan}
     * (this same service) reads {@code org.getOwner()} to resolve the active
     * plan - leaving owner_id on the old user would keep TEAM features tied
     * to their plan instead of the new OWNER's.
     *
     * <p><b>Out-of-scope for this PR:</b> Stripe subscription migration. The
     * subscription remains on the OLD OWNER's user account, so billing keeps
     * coming from them. PR-4c.1 will migrate the subscription via the Stripe
     * API ; in the meantime the org keeps the OLD OWNER's plan tier because
     * {@code getOwnerPlan} now resolves to the NEW OWNER but their
     * subscription does not exist - fallback to FREE applies. If the
     * frontend wants to enforce "transfer only when subscription is portable",
     * a follow-up will add a guard here.
     *
     * <p><b>Concurrency:</b> the organization row is locked before reading
     * owner membership rows, and a partial unique index enforces at most one
     * OWNER membership per organization as defence in depth.
     *
     * @throws IllegalArgumentException if currentOwner or newOwner is not a member
     * @throws SecurityException        if currentOwnerUserId is not the OWNER
     * @throws IllegalStateException    if newOwnerUserId == currentOwnerUserId
     */
    public void transferOwnership(UUID orgId, Long currentOwnerUserId, Long newOwnerUserId) {
        if (currentOwnerUserId.equals(newOwnerUserId)) {
            throw new IllegalStateException("Cannot transfer ownership to the current owner");
        }

        Organization org = lockOrganizationForOwnershipTransfer(orgId);

        OrganizationMember currentOwner = memberRepository.findByOrganization_IdAndUser_Id(orgId, currentOwnerUserId)
                .orElseThrow(() -> new IllegalArgumentException("Current owner is not a member of this organization"));
        if (currentOwner.getRole() != OrganizationRole.OWNER) {
            throw new SecurityException("Only the current OWNER can transfer ownership");
        }

        OrganizationMember newOwner = memberRepository.findByOrganization_IdAndUser_Id(orgId, newOwnerUserId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Target user is not a member of this organization. Invite them first."));

        User newOwnerUser = userRepository.findById(newOwnerUserId)
                .orElseThrow(() -> new IllegalArgumentException("New owner user not found"));

        // Atomic flip of role + owner_id - see Javadoc for the rationale.
        currentOwner.setRole(OrganizationRole.ADMIN);
        newOwner.setRole(OrganizationRole.OWNER);
        org.setOwner(newOwnerUser);
        memberRepository.save(currentOwner);
        memberRepository.save(newOwner);
        organizationRepository.save(org);
        log.info("Ownership of org {} transferred from user {} to user {} (owner_id flipped)",
                orgId, currentOwnerUserId, newOwnerUserId);

        // Milestone-1 audit fix: both roles changed, both caches must bust.
        // Old OWNER → ADMIN: loses payment rights in active workspace.
        // New OWNER ← ADMIN: gains payment rights.
        bustGatewayCacheFor(currentOwner.getUser());
        bustGatewayCacheFor(newOwnerUser);

        auditService.record(orgId, currentOwnerUserId,
                OrganizationAuditEvent.Type.OWNERSHIP_TRANSFERRED,
                java.util.Map.of(
                        "previousOwnerUserId", currentOwnerUserId,
                        "newOwnerUserId", newOwnerUserId));
    }

    private Organization lockOrganizationForOwnershipTransfer(UUID orgId) {
        Optional<Organization> locked = organizationRepository.findByIdForUpdate(orgId);
        if (locked == null || locked.isEmpty()) {
            locked = organizationRepository.findById(orgId);
        }
        return locked.orElseThrow(() -> new IllegalArgumentException("Organization not found"));
    }

    /**
     * Soft-delete an organization. Only the OWNER can trigger this. The
     * {@code confirmName} must match {@code organization.name} exactly
     * (GitHub-style guard) to avoid accidental destructive clicks.
     *
     * <p>Sets {@code Organization.deletedAt = now}. The row stays in DB during
     * a 30-day grace period so a misclick can be undone by support. A future
     * cron (PR-cascade.1) will :
     * <ol>
     *   <li>Cancel the Stripe subscription tied to the OWNER if any</li>
     *   <li>Purge cross-service resources (workflows / agents / interfaces /
     *       datasources / credentials) via HTTP clients</li>
     *   <li>Hard-delete the row after the grace period expires</li>
     * </ol>
     *
     * <p>For now the org is just hidden - list endpoints / org-scoped reads
     * SHOULD filter on {@code deletedAt IS NULL} (follow-up).
     *
     * @throws IllegalArgumentException if org not found
     * @throws SecurityException        if caller is not the OWNER
     * @throws IllegalStateException    if confirmName doesn't match or org
     *                                  is already deleted
     */
    public void softDeleteOrganization(UUID orgId, Long requesterUserId, String confirmName) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found"));

        if (org.isDeleted()) {
            throw new IllegalStateException("Organization is already deleted");
        }
        // The personal/base workspace is the user's default-org fallback (set up at
        // signup) - deleting it would orphan the user from getCurrentOrganization.
        if (org.isPersonal()) {
            throw new IllegalStateException("Your personal workspace cannot be deleted");
        }

        OrganizationMember requester = memberRepository.findByOrganization_IdAndUser_Id(orgId, requesterUserId)
                .orElseThrow(() -> new IllegalArgumentException("Not a member of this organization"));
        if (requester.getRole() != OrganizationRole.OWNER) {
            throw new SecurityException("Only the OWNER can delete the workspace");
        }
        if (confirmName == null || !confirmName.equals(org.getName())) {
            throw new IllegalStateException("Confirmation name does not match the workspace name");
        }

        // Soft-delete: hidden from the /me switcher AND rejected as an active-org claim by
        // the gateway. Operational data is left fully intact during the grace window so a
        // restore is a no-op revert; the hard-purge cron deletes it after the grace expires.
        org.setDeletedAt(LocalDateTime.now());
        org.setDeletedBy(requesterUserId);
        organizationRepository.save(org);

        // Cancel PENDING invitations - they'd otherwise point at a tombstone.
        for (OrganizationInvitation inv :
                invitationRepository.findByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)) {
            inv.setStatus(InvitationStatus.CANCELLED);
            invitationRepository.save(inv);
        }

        // For every member whose DEFAULT was this org, CLEAR that flag first (freeing the
        // single-default partial unique index - organization_member(user_id) WHERE is_default=TRUE)
        // and flush, THEN promote a non-deleted fallback (their personal / next-oldest org) so
        // getCurrentOrganization keeps returning a valid result. Clearing before promoting is
        // mandatory: setting a second is_default=true for the same user before clearing the old
        // one violates the index and rolls back the whole delete. Also bust each member's gateway
        // cache so they can no longer claim this org as active.
        java.util.List<Long> usersNeedingDefault = new java.util.ArrayList<>();
        for (OrganizationMember m : memberRepository.findByOrganization_Id(orgId)) {
            if (m.isDefault()) {
                m.setDefault(false);
                memberRepository.save(m);
                usersNeedingDefault.add(m.getUser().getId());
            }
            bustGatewayCacheFor(m.getUser());
        }
        if (!usersNeedingDefault.isEmpty()) {
            memberRepository.flush(); // release the unique index before promoting replacements
            for (Long memberUserId : usersNeedingDefault) {
                memberRepository.findByUser_Id(memberUserId).stream()
                        .filter(other -> !other.getOrganization().getId().equals(orgId))
                        .filter(other -> !other.getOrganization().isDeleted())
                        .min(java.util.Comparator.comparing(OrganizationMember::getJoinedAt))
                        .ifPresent(promoted -> {
                            promoted.setDefault(true);
                            memberRepository.save(promoted);
                        });
            }
        }

        auditService.record(orgId, requesterUserId, OrganizationAuditEvent.Type.DELETED,
                java.util.Map.of("confirmName", confirmName, "deletedBy", requesterUserId));
        log.info("Workspace {} soft-deleted by user {} (grace window before hard-purge)", orgId, requesterUserId);
    }

    /**
     * Restore a soft-deleted workspace within the grace window (the operational data was
     * never touched, so this is a plain revert of {@code deleted_at}). OWNER-only. Fails if
     * the workspace was already hard-purged ({@code purged_at} set) - that data is gone.
     *
     * @throws IllegalArgumentException if org not found / requester not a member
     * @throws SecurityException        if caller is not the OWNER
     * @throws IllegalStateException    if the workspace is not deleted, or already purged
     */
    public void restoreOrganization(UUID orgId, Long requesterUserId) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found"));

        OrganizationMember requester = memberRepository.findByOrganization_IdAndUser_Id(orgId, requesterUserId)
                .orElseThrow(() -> new IllegalArgumentException("Not a member of this organization"));
        if (requester.getRole() != OrganizationRole.OWNER) {
            throw new SecurityException("Only the OWNER can restore the workspace");
        }
        if (!org.isDeleted()) {
            throw new IllegalStateException("Workspace is not deleted");
        }
        if (org.isPurged()) {
            throw new IllegalStateException("Workspace was already purged and cannot be restored");
        }

        org.setDeletedAt(null);
        org.setDeletedBy(null);
        organizationRepository.save(org);

        // Bust every member's gateway cache so the workspace becomes claimable again.
        for (OrganizationMember m : memberRepository.findByOrganization_Id(orgId)) {
            bustGatewayCacheFor(m.getUser());
        }

        auditService.record(orgId, requesterUserId, OrganizationAuditEvent.Type.RESTORED,
                java.util.Map.of("restoredBy", requesterUserId));
        log.info("Workspace {} restored by user {}", orgId, requesterUserId);
    }

    /**
     * Change a member's role. Target is identified by the user id of the member whose
     * role is being changed.
     */
    public OrganizationMember changeRole(UUID orgId, Long targetUserId, OrganizationRole newRole, Long requesterId) {
        OrganizationMember requester = memberRepository.findByOrganization_IdAndUser_Id(orgId, requesterId)
                .orElseThrow(() -> new IllegalArgumentException("Requester is not a member"));

        if (requester.getRole() != OrganizationRole.OWNER) {
            throw new SecurityException("Only OWNER can change member roles");
        }

        OrganizationMember target = memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Member not found"));

        if (targetUserId.equals(requesterId)) {
            throw new IllegalStateException("Cannot change your own role");
        }

        if (newRole == OrganizationRole.OWNER) {
            throw new IllegalArgumentException("Cannot set someone as OWNER. Use ownership transfer instead.");
        }

        OrganizationRole oldRole = target.getRole();
        target.setRole(newRole);
        target = memberRepository.save(target);

        log.info("Changed role of user {} to {} in org {}", targetUserId, newRole, orgId);

        // Milestone-1 audit fix: bust target's cache so the role change
        // shows up in their `memberships` and `X-Organization-Role` header
        // on the next request.
        bustGatewayCacheFor(target.getUser());

        auditService.record(orgId, requesterId, OrganizationAuditEvent.Type.ROLE_CHANGED,
                java.util.Map.of(
                        "targetUserId", targetUserId,
                        "oldRole", oldRole.name(),
                        "newRole", newRole.name()));
        return target;
    }

    /**
     * PR4b - return all PENDING invitations addressed to a given email. Used
     * by the /app/invitations inbox to show the current user their pending
     * invites. Filters expired rows lazily - the JPA query just matches by
     * email + status, and expired entries get caught on accept (existing
     * isExpired path).
     */
    @Transactional(readOnly = true)
    public List<OrganizationInvitation> getPendingInvitationsForEmail(String email) {
        if (email == null || email.isBlank()) {
            return java.util.Collections.emptyList();
        }
        return invitationRepository.findByEmailAndStatus(email, InvitationStatus.PENDING).stream()
                .filter(inv -> !inv.isExpired())
                .toList();
    }

    /**
     * Public, unauthenticated lookup of an invitation by its token, used by the
     * accept page to prefill the email and decide between register-vs-login.
     *
     * <p>Returns {@code valid=false} (with all other fields null) for any token
     * that is missing/blank, unknown, expired, cancelled, or already accepted -
     * the caller cannot distinguish those, so a bogus token leaks nothing. For a
     * PENDING, non-expired token it returns the invitee email, org name, role and
     * whether a local account already exists for that email ({@code hasAccount}).
     *
     * <p>Read-only: it does NOT flip an expired invitation's status (no write on
     * an unauthenticated path); the accept/register flow performs that transition.
     */
    @Transactional(readOnly = true)
    public InvitationInfo getInvitationInfo(String token) {
        if (token == null || token.isBlank()) {
            return InvitationInfo.invalid();
        }
        return invitationRepository.findByToken(token)
                .filter(inv -> inv.getStatus() == InvitationStatus.PENDING && !inv.isExpired())
                .map(inv -> new InvitationInfo(
                        true,
                        inv.getEmail(),
                        inv.getOrganization() != null ? inv.getOrganization().getName() : null,
                        inv.getRole(),
                        userRepository.findByEmail(inv.getEmail()).isPresent()))
                .orElseGet(InvitationInfo::invalid);
    }

    /**
     * Get pending invitations for an organization.
     */
    @Transactional(readOnly = true)
    public List<OrganizationInvitation> getPendingInvitations(UUID orgId, Long userId) {
        // Verify requester is a member
        if (!memberRepository.existsByOrganization_IdAndUser_Id(orgId, userId)) {
            throw new IllegalArgumentException("User is not a member of this organization");
        }

        return invitationRepository.findByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING);
    }

    /**
     * Check if the organization can invite more members based on plan limits.
     */
    @Transactional(readOnly = true)
    public TeamStatus getTeamStatus(UUID orgId) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found"));

        Plan ownerPlan = getOwnerPlan(org);
        boolean supportsTeam = ownerPlan != null && ownerPlan.supportsTeam();
        int maxMembers = (ownerPlan != null && ownerPlan.getMaxMembers() != null) ? ownerPlan.getMaxMembers() : 1;
        long currentMembers = memberRepository.countByOrganization_Id(orgId);
        long pendingInvitations = invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING);
        String planCode = ownerPlan != null ? ownerPlan.getCode() : "FREE";

        return new TeamStatus(supportsTeam, maxMembers, (int) currentMembers, (int) pendingInvitations, planCode);
    }

    // --- Internal helpers ---

    private void acceptInvitationInternal(OrganizationInvitation invitation, User user) {
        Organization org = invitation.getOrganization();

        // Milestone-1 audit fix B: refuse to accept an invitation whose org
        // has been soft-deleted. Otherwise an invitation issued before
        // soft-delete becomes a path to a tomb-stoned org - listUserMember-
        // shipsDto filters deletedAt-IS-NULL so the membership is invisible
        // anyway, but we'd leave an orphan row that confuses ops.
        // OrganizationService.softDeleteOrganization cancels PENDING
        // invitations on the org's behalf (companion fix); this guard is
        // belt-and-suspenders for races during the soft-delete tx.
        if (org.isDeleted()) {
            invitation.setStatus(InvitationStatus.CANCELLED);
            invitationRepository.save(invitation);
            throw new IllegalStateException("Cannot accept an invitation for a deleted organization");
        }

        // Check not already a member
        if (memberRepository.existsByOrganization_IdAndUser_Id(org.getId(), user.getId())) {
            invitation.setStatus(InvitationStatus.ACCEPTED);
            invitation.setAcceptedAt(LocalDateTime.now());
            invitationRepository.save(invitation);
            // Cache-bust still useful - the gateway's memberships list
            // already had this org but a stale `defaultOrganizationRole`
            // could otherwise stick if the user re-joined at a new role.
            bustGatewayCacheFor(user);
            return;
        }

        enforceTeamCapacityForAccept(org);

        // Add as member
        OrganizationMember member = new OrganizationMember(org, user, invitation.getRole(), false);
        member.setInvitedBy(invitation.getInvitedBy());
        memberRepository.save(member);

        // Update invitation
        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(LocalDateTime.now());
        invitationRepository.save(invitation);

        log.info("User {} joined org {} as {}", user.getId(), org.getId(), invitation.getRole());

        // Milestone-1 audit fix A: bust gateway cache so the next request
        // from this user sees the new membership in their memberships list
        // (otherwise X-Active-Organization-ID claims for this org are
        // silently rejected for up to 5 min).
        bustGatewayCacheFor(user);
    }

    private void enforceTeamCapacityForAccept(Organization org) {
        Plan ownerPlan = getOwnerPlan(org);
        if (ownerPlan == null || !ownerPlan.supportsTeam()) {
            throw new UnsupportedOperationException("Current plan does not support team members. Upgrade to TEAM or ENTERPRISE.");
        }

        long currentMembers = memberRepository.countByOrganization_Id(org.getId());
        int maxMembers = ownerPlan.getMaxMembers() != null ? ownerPlan.getMaxMembers() : 1;
        if (currentMembers >= maxMembers) {
            throw new IllegalStateException("Member limit reached (" + maxMembers + "). Upgrade your plan for more members.");
        }
    }

    /**
     * Resolve the plan of the organization owner via their active subscription.
     * Delegates to {@link PlanResolutionService#resolveOrgOwnerPlan} (the canonical
     * resolver, also used by the dormant-org gate) when wired, so there is a single
     * source of truth for owner-plan resolution. Falls back to the local lookup only
     * in slim unit tests where the resolver isn't injected.
     */
    private Plan getOwnerPlan(Organization org) {
        if (planResolutionService != null) {
            return planResolutionService.resolveOrgOwnerPlan(org);
        }
        User owner = org.getOwner();
        return subscriptionRepository.findActiveByUserId(owner.getId())
                .map(Subscription::getPlan)
                .orElse(null);
    }

    /**
     * Public view of an invitation resolved from its token, for the accept page.
     * {@code valid=false} carries no other detail (missing/unknown/expired/etc).
     */
    public record InvitationInfo(
            boolean valid,
            String email,
            String organizationName,
            OrganizationRole role,
            boolean hasAccount
    ) {
        public static InvitationInfo invalid() {
            return new InvitationInfo(false, null, null, null, false);
        }
    }

    /**
     * Team status information for an organization.
     */
    public record TeamStatus(
            boolean supportsTeam,
            int maxMembers,
            int currentMembers,
            int pendingInvitations,
            String planCode
    ) {
        public boolean canInvite() {
            return supportsTeam && (currentMembers + pendingInvitations) < maxMembers;
        }
    }
}
