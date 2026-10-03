package com.apimarketplace.auth.service;

import com.apimarketplace.auth.util.EmailNormalizer;
import org.springframework.beans.factory.ObjectProvider;

import com.apimarketplace.common.security.token.TokenAtRest;
import com.apimarketplace.common.security.CredentialEncryptionService;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.*;
import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationMemberService Tests")
class OrganizationMemberServiceTest {

    /** Token columns are hashed through TokenAtRest; a unit test must install the material itself. */
    @org.junit.jupiter.api.BeforeAll
    static void installTokenAtRest() {
        TokenAtRest.install(new CredentialEncryptionService("test-password-123", "0123456789abcdef"));
    }

    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationInvitationRepository invitationRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private UserRepository userRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private OnboardingService onboardingService;
    @Mock private AppEditionProvider editionProvider;

    @Captor private ArgumentCaptor<OrganizationInvitation> invitationCaptor;
    @Captor private ArgumentCaptor<OrganizationMember> memberCaptor;

    private OrganizationMemberService service;

    private User owner;
    private User inviter;
    private User targetUser;
    private Organization org;
    private UUID orgId;
    private Plan teamPlan;
    private Plan freePlan;

    private OrganizationAuditService auditService;
    private OrganizationInvitationMailer invitationMailer;
    private GatewayCacheClient gatewayCacheClient;

    @BeforeEach
    void setUp() {
        // PR-4b: real OrganizationAuditService bean but with a Mockito-spy so
        // we can verify(...).record(...) in dedicated tests while keeping the
        // existing tests untouched (they don't assert on audit writes - the
        // fire-and-forget contract guarantees they pass through silently).
        auditService = spy(new OrganizationAuditService(
                mock(com.apimarketplace.auth.repository.OrganizationAuditEventRepository.class)));
        invitationMailer = mock(OrganizationInvitationMailer.class);
        gatewayCacheClient = mock(GatewayCacheClient.class);
        // S-2: pass generous limits so the existing test corpus doesn't trip
        // the rate guard. Dedicated tests below override by spinning a fresh
        // service with tighter limits.
        service = new OrganizationMemberService(
                memberRepository, invitationRepository, organizationRepository,
                userRepository, subscriptionRepository, onboardingService, auditService,
                invitationMailer, editionProvider, gatewayCacheClient,
                /* perInviterPerHour */ 1000, /* perOrgPerHour */ 1000);
        lenient().when(editionProvider.isCeFree()).thenReturn(false);

        orgId = UUID.randomUUID();
        owner = createUser(1L, "owner@test.com");
        inviter = createUser(2L, "admin@test.com");
        targetUser = createUser(3L, "target@test.com");

        org = new Organization("Test Org", "test-org", false, owner);
        org.setId(orgId);

        teamPlan = new Plan("TEAM", "Team", "Team plan");
        teamPlan.setMaxMembers(10);

        freePlan = new Plan("FREE", "Free", "Free plan");
        freePlan.setMaxMembers(1);
    }

    // ===== Helpers =====

    private User createUser(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        // S-1: default-verified for the happy-path corpus. Dedicated security
        // regressions below opt-out via setEmailVerified(false).
        user.setEmailVerified(true);
        return user;
    }

    private OrganizationMember createMembership(Organization org, User user, OrganizationRole role) {
        OrganizationMember m = new OrganizationMember(org, user, role, false);
        m.setId(user.getId() * 100);
        return m;
    }

    private Subscription createSubscription(Plan plan) {
        Subscription sub = new Subscription();
        sub.setPlan(plan);
        sub.setStatus("active");
        return sub;
    }

    private void setupTeamPlanForOwner() {
        when(subscriptionRepository.findActiveByUserId(owner.getId()))
                .thenReturn(Optional.of(createSubscription(teamPlan)));
    }

    private void setupFreePlanForOwner() {
        when(subscriptionRepository.findActiveByUserId(owner.getId()))
                .thenReturn(Optional.of(createSubscription(freePlan)));
    }

    private NotificationClient enableCeEmbeddedMode() {
        NotificationClient notificationClient = mock(NotificationClient.class);
        ReflectionTestUtils.setField(service, "authMode", "embedded");
        ReflectionTestUtils.setField(service, "notificationClient", notificationClient);
        return notificationClient;
    }

    // ===== inviteMember =====

    @Nested
    @DisplayName("inviteMember")
    class InviteMember {

        /** Stubs a fully successful invite path for the given inviter membership. */
        private void stubInvitableBy(User who, OrganizationRole whoRole) {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(who.getId())).thenReturn(Optional.of(who));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, who.getId()))
                    .thenReturn(Optional.of(createMembership(org, who, whoRole)));
            lenient().when(subscriptionRepository.findActiveByUserId(owner.getId()))
                    .thenReturn(Optional.of(createSubscription(teamPlan)));
            lenient().when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            lenient().when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
            lenient().when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(a -> a.getArgument(0));
        }

        @Test
        @DisplayName("an ADMIN cannot invite someone as ADMIN (OWNER-only, like changeRole): 403 code, nothing persisted or sent")
        void adminCannotInviteAdmin() {
            stubInvitableBy(inviter, OrganizationRole.ADMIN);

            assertThatThrownBy(() -> service.inviteMember(orgId, "new@test.com", OrganizationRole.ADMIN, inviter.getId()))
                    .isInstanceOf(AdminInviteRequiresOwnerException.class)
                    .hasMessageContaining("Only the OWNER");

            verify(invitationRepository, never()).save(any(OrganizationInvitation.class));
            verifyNoInteractions(invitationMailer);
        }

        @Test
        @DisplayName("an ADMIN can invite MEMBER and VIEWER")
        void adminCanInviteMemberAndViewer() {
            stubInvitableBy(inviter, OrganizationRole.ADMIN);

            assertThat(service.inviteMember(orgId, "m@test.com", OrganizationRole.MEMBER, inviter.getId()).getRole())
                    .isEqualTo(OrganizationRole.MEMBER);
            assertThat(service.inviteMember(orgId, "v@test.com", OrganizationRole.VIEWER, inviter.getId()).getRole())
                    .isEqualTo(OrganizationRole.VIEWER);
        }

        @Test
        @DisplayName("the OWNER can invite someone as ADMIN")
        void ownerCanInviteAdmin() {
            stubInvitableBy(owner, OrganizationRole.OWNER);

            OrganizationInvitation inv = service.inviteMember(orgId, "a@test.com", OrganizationRole.ADMIN, owner.getId());

            assertThat(inv.getRole()).isEqualTo(OrganizationRole.ADMIN);
            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.PENDING);
        }

        @Test
        @DisplayName("should invite member successfully for TEAM plan owner")
        void shouldInviteSuccessfully() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(0L);
            when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
            when(invitationRepository.existsByOrganization_IdAndEmailAndStatus(orgId, "new@test.com", InvitationStatus.PENDING)).thenReturn(false);
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(inv -> {
                OrganizationInvitation i = inv.getArgument(0);
                i.setId(UUID.randomUUID());
                return i;
            });

            OrganizationInvitation result = service.inviteMember(orgId, "new@test.com", OrganizationRole.MEMBER, inviter.getId());

            assertThat(result).isNotNull();
            assertThat(result.getEmail()).isEqualTo("new@test.com");
            assertThat(result.getRole()).isEqualTo(OrganizationRole.MEMBER);
            assertThat(result.getStatus()).isEqualTo(InvitationStatus.PENDING);
        }

        @Test
        @DisplayName("should fail for FREE plan owner")
        void shouldFailForFreePlan() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupFreePlanForOwner();

            assertThatThrownBy(() -> service.inviteMember(orgId, "new@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("does not support team members");
        }

        @Test
        @DisplayName("Self-Hosted Enterprise embedded auth still enforces team plan gates")
        void selfHostedEnterpriseEmbeddedAuthDoesNotBypassTeamPlanGate() {
            ReflectionTestUtils.setField(service, "authMode", "embedded");
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupFreePlanForOwner();

            assertThatThrownBy(() -> service.inviteMember(orgId, "new@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("does not support team members");

            verify(invitationRepository, never()).save(any(OrganizationInvitation.class));
        }

        @Test
        @DisplayName("should fail when member limit reached")
        void shouldFailWhenLimitReached() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(9L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(1L);

            assertThatThrownBy(() -> service.inviteMember(orgId, "new@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Member limit reached");
        }

        @Test
        @DisplayName("PR-3 - mailer fired when invitee is NOT yet on the platform")
        void mailerCalledForUnknownEmail() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(0L);
            when(userRepository.findByEmail("newcomer@test.com")).thenReturn(Optional.empty());
            when(invitationRepository.existsByOrganization_IdAndEmailAndStatus(orgId, "newcomer@test.com", InvitationStatus.PENDING)).thenReturn(false);
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(inv -> {
                OrganizationInvitation i = inv.getArgument(0);
                i.setId(UUID.randomUUID());
                return i;
            });

            service.inviteMember(orgId, "newcomer@test.com", OrganizationRole.MEMBER, inviter.getId());

            verify(invitationMailer).sendInvitationEmail(
                    eq("newcomer@test.com"),
                    eq(org.getName()),
                    anyString(),               // inviter display name
                    anyString(),               // token
                    eq("MEMBER"),
                    // No account owns this address yet, so there is no row to read a language off
                    // and the mailer still looks it up. Asserted as null rather than any(): passing
                    // something here for an invitee who does not exist would mean the service had
                    // found a row it should not have.
                    isNull());
        }

        @Test
        @DisplayName("PR4 (Q2=a consent): mailer fires for EXISTING user too - they must "
                + "explicitly accept via the link, no silent auto-add")
        void mailerCalledForExistingUserToo() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(0L);
            // A language on the row, and not English: the mailer falls back to English when it is
            // handed nothing, so an English invitee cannot tell "passed" from "dropped".
            targetUser.setLocale("de");
            when(userRepository.findByEmail("target@test.com")).thenReturn(Optional.of(targetUser));
            when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, targetUser.getId())).thenReturn(false);
            when(invitationRepository.existsByOrganization_IdAndEmailAndStatus(orgId, "target@test.com", InvitationStatus.PENDING)).thenReturn(false);
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(inv -> {
                OrganizationInvitation i = inv.getArgument(0);
                i.setId(UUID.randomUUID());
                return i;
            });

            service.inviteMember(orgId, "target@test.com", OrganizationRole.MEMBER, inviter.getId());

            // PR4 (Q2=a): ALL invites take the email-send path now - existing
            // users were previously silently auto-added, which was a consent
            // violation. Pre-fix, this assertion would FAIL (mailer never
            // fired for existing users).
            // An EXISTING user, so the service holds their row and passes the language off it rather
            // than making the mailer query for it again. This is the case the parameter was added
            // for, and the last of the five mailers to get it.
            verify(invitationMailer).sendInvitationEmail(
                    eq("target@test.com"), eq(org.getName()), anyString(), anyString(), eq("MEMBER"),
                    eq("de"));
        }

        @Test
        @DisplayName("CE embedded invite on a team-capable plan skips email and emits an in-app notification")
        void ceEmbeddedInviteNotifiesExistingUserWithoutEmail() {
            NotificationClient notificationClient = enableCeEmbeddedMode();
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(1L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(0L);
            when(userRepository.findByEmail("target@test.com")).thenReturn(Optional.of(targetUser));
            when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, targetUser.getId())).thenReturn(false);
            when(invitationRepository.existsByOrganization_IdAndEmailAndStatus(orgId, "target@test.com", InvitationStatus.PENDING)).thenReturn(false);
            // Post-V261: emitCeInvitationNotificationIfPossible needs the invitee's default-personal
            // org to stamp the notification's organization_id, otherwise it logs warn + skips emit.
            Organization targetPersonalOrg = new Organization();
            targetPersonalOrg.setId(UUID.randomUUID());
            targetPersonalOrg.setName("target personal");
            when(memberRepository.findDefaultPersonalByUserId(targetUser.getId()))
                    .thenReturn(Optional.of(createMembership(targetPersonalOrg, targetUser, OrganizationRole.OWNER)));
            UUID invitationId = UUID.randomUUID();
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(inv -> {
                OrganizationInvitation i = inv.getArgument(0);
                i.setId(invitationId);
                return i;
            });

            OrganizationInvitation result = service.inviteMember(orgId, "target@test.com", OrganizationRole.MEMBER, inviter.getId());

            assertThat(result.getStatus()).isEqualTo(InvitationStatus.PENDING);
            verify(invitationMailer, never()).sendInvitationEmail(
                    any(), any(), any(), any(), any(), any());
            ArgumentCaptor<NotificationEmitRequest> notificationCaptor =
                    ArgumentCaptor.forClass(NotificationEmitRequest.class);
            verify(notificationClient).emit(notificationCaptor.capture());
            NotificationEmitRequest req = notificationCaptor.getValue();
            assertThat(req.getTenantId()).isEqualTo(String.valueOf(targetUser.getId()));
            assertThat(req.getCategory()).isEqualTo("ORG_INVITATION_PENDING");
            assertThat(req.getSubjectType()).isEqualTo("ORG_INVITATION");
            assertThat(req.getSubjectId()).isEqualTo(invitationId);
            assertThat(req.getPayload())
                    .containsEntry("status", "pending")
                    .containsEntry("organizationId", orgId.toString())
                    .containsEntry("organizationName", org.getName())
                    .containsEntry("role", "MEMBER");
            verify(memberRepository, never()).save(any(OrganizationMember.class));
        }

        @Test
        @DisplayName("CE invite-by-link: a brand-new email (no local account) is now ALLOWED - "
                + "invitation persisted PENDING, no email, no notification (no invitee user)")
        void ceEmbeddedInviteForUnknownEmailIsAllowedAndPersistedPending() {
            // Pre-fix this threw "existing local user account" before persistence. The
            // CE invite-by-link feature removed that throw: a brand-new email is
            // persisted PENDING with a generated token so the admin can share an
            // accept link. There is no local user to notify and embedded auth has no
            // SMTP, so neither the mailer nor the notification client fires.
            NotificationClient notificationClient = enableCeEmbeddedMode();
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(1L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(0L);
            when(userRepository.findByEmail("newcomer@test.com")).thenReturn(Optional.empty());
            when(invitationRepository.existsByOrganization_IdAndEmailAndStatus(orgId, "newcomer@test.com", InvitationStatus.PENDING)).thenReturn(false);
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(inv -> {
                OrganizationInvitation i = inv.getArgument(0);
                i.setId(UUID.randomUUID());
                return i;
            });

            OrganizationInvitation result = service.inviteMember(orgId, "newcomer@test.com", OrganizationRole.MEMBER, inviter.getId());

            assertThat(result.getStatus()).isEqualTo(InvitationStatus.PENDING);
            assertThat(result.getEmail()).isEqualTo("newcomer@test.com");
            assertThat(result.getToken()).isNotBlank();
            verify(invitationRepository).save(any(OrganizationInvitation.class));
            verify(invitationMailer, never()).sendInvitationEmail(
                    any(), any(), any(), any(), any(), any());
            verify(notificationClient, never()).emit(any());
            verify(memberRepository, never()).save(any(OrganizationMember.class));
        }

        @Test
        @DisplayName("PR4 (Q2=a consent): existing user invite stays PENDING - NO silent "
                + "membership creation, even though the user has an account")
        void existingUserStaysPendingUntilExplicitAccept() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(0L);
            when(userRepository.findByEmail("target@test.com")).thenReturn(Optional.of(targetUser));
            when(invitationRepository.existsByOrganization_IdAndEmailAndStatus(orgId, "target@test.com", InvitationStatus.PENDING)).thenReturn(false);
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(inv -> {
                OrganizationInvitation i = inv.getArgument(0);
                i.setId(UUID.randomUUID());
                return i;
            });

            OrganizationInvitation result = service.inviteMember(orgId, "target@test.com", OrganizationRole.MEMBER, inviter.getId());

            // PR4 pre-fix repro: pre-fix asserted ACCEPTED + memberRepository.save(member).
            // Post-fix: PENDING + no membership. Pre-fix code FAILS this assertion.
            assertThat(result.getStatus()).isEqualTo(InvitationStatus.PENDING);
            verify(memberRepository, never()).save(any(OrganizationMember.class));
            // Audit MEMBER_INVITED records but NOT INVITE_ACCEPTED - there's no accept yet.
            verify(auditService).record(eq(orgId), eq(inviter.getId()),
                    eq(OrganizationAuditEvent.Type.MEMBER_INVITED), any());
            verify(auditService, never()).record(any(), any(),
                    eq(OrganizationAuditEvent.Type.INVITE_ACCEPTED), any());
        }

        @Test
        @DisplayName("should fail for non-OWNER/ADMIN inviter")
        void shouldFailForMemberRole() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.MEMBER)));

            assertThatThrownBy(() -> service.inviteMember(orgId, "new@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("should fail when already a member")
        void shouldFailWhenAlreadyMember() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(0L);
            when(userRepository.findByEmail("target@test.com")).thenReturn(Optional.of(targetUser));
            when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, targetUser.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.inviteMember(orgId, "target@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already a member");
        }

        @Test
        @DisplayName("should fail when pending invitation exists")
        void shouldFailWhenPendingExists() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(0L);
            when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
            when(invitationRepository.existsByOrganization_IdAndEmailAndStatus(orgId, "new@test.com", InvitationStatus.PENDING)).thenReturn(true);

            assertThatThrownBy(() -> service.inviteMember(orgId, "new@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("pending invitation already exists");
        }

        @Test
        @DisplayName("should fail when trying to invite as OWNER")
        void shouldFailWhenInvitingAsOwner() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(0L);
            when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
            when(invitationRepository.existsByOrganization_IdAndEmailAndStatus(orgId, "new@test.com", InvitationStatus.PENDING)).thenReturn(false);

            assertThatThrownBy(() -> service.inviteMember(orgId, "new@test.com", OrganizationRole.OWNER, inviter.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot invite as OWNER");
        }
    }

    // ===== removeMember =====

    @Nested
    @DisplayName("removeMember")
    class RemoveMember {

        @Test
        @DisplayName("should remove member successfully for OWNER - target resolved by user_id, not membership PK")
        void shouldRemoveSuccessfully() {
            OrganizationMember ownerMembership = createMembership(org, owner, OrganizationRole.OWNER);
            OrganizationMember targetMembership = createMembership(org, targetUser, OrganizationRole.MEMBER);

            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(ownerMembership));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(targetMembership));

            service.removeMember(orgId, targetUser.getId(), owner.getId());

            verify(memberRepository).delete(targetMembership);
        }

        @Test
        @DisplayName("should fail for non-OWNER/ADMIN")
        void shouldFailForMemberRole() {
            OrganizationMember requesterMembership = createMembership(org, inviter, OrganizationRole.MEMBER);
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(requesterMembership));

            assertThatThrownBy(() -> service.removeMember(orgId, targetUser.getId(), inviter.getId()))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("should not allow removing OWNER")
        void shouldNotRemoveOwner() {
            OrganizationMember adminMembership = createMembership(org, inviter, OrganizationRole.ADMIN);
            OrganizationMember ownerMembership = createMembership(org, owner, OrganizationRole.OWNER);

            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(adminMembership));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(ownerMembership));

            assertThatThrownBy(() -> service.removeMember(orgId, owner.getId(), inviter.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot remove the organization owner");
        }

        @Test
        @DisplayName("should not allow ADMIN removing themselves")
        void shouldNotRemoveSelf() {
            // ADMIN trying to remove themselves
            User adminUser = createUser(5L, "admin5@test.com");
            OrganizationMember adminMembership = createMembership(org, adminUser, OrganizationRole.ADMIN);

            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, adminUser.getId()))
                    .thenReturn(Optional.of(adminMembership));

            assertThatThrownBy(() -> service.removeMember(orgId, adminUser.getId(), adminUser.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot remove yourself");
        }

        @Test
        @DisplayName("BUG-2 regression: target is the user_id, never the OrganizationMember.id")
        void shouldResolveTargetByUserIdNotMembershipPk() {
            // User 3's membership PK is 300 (per createMembership helper: userId * 100).
            // If someone regresses the contract and passes a memberId (300) as targetUserId,
            // the repo.findByOrganization_IdAndUser_Id(orgId, 300) should return empty and
            // the service must throw "Member not found" - NOT accidentally succeed.
            OrganizationMember ownerMembership = createMembership(org, owner, OrganizationRole.OWNER);
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(ownerMembership));
            // 300L (a membership PK value) does not correspond to any user, so the lookup
            // must return empty.
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 300L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeMember(orgId, 300L, owner.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Member not found");
            verify(memberRepository, never()).delete(any());
        }
    }

    // ===== changeRole =====

    @Nested
    @DisplayName("changeRole")
    class ChangeRole {

        @Test
        @DisplayName("should change role successfully for OWNER - target resolved by user_id")
        void shouldChangeRoleSuccessfully() {
            OrganizationMember ownerMembership = createMembership(org, owner, OrganizationRole.OWNER);
            OrganizationMember targetMembership = createMembership(org, targetUser, OrganizationRole.MEMBER);

            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(ownerMembership));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(targetMembership));
            when(memberRepository.save(any(OrganizationMember.class))).thenAnswer(inv -> inv.getArgument(0));

            OrganizationMember result = service.changeRole(orgId, targetUser.getId(), OrganizationRole.ADMIN, owner.getId());

            assertThat(result.getRole()).isEqualTo(OrganizationRole.ADMIN);
        }

        @Test
        @DisplayName("should fail for non-OWNER requester")
        void shouldFailForNonOwner() {
            OrganizationMember adminMembership = createMembership(org, inviter, OrganizationRole.ADMIN);
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(adminMembership));

            assertThatThrownBy(() -> service.changeRole(orgId, targetUser.getId(), OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("should not allow setting someone as OWNER")
        void shouldNotSetOwnerRole() {
            OrganizationMember ownerMembership = createMembership(org, owner, OrganizationRole.OWNER);
            OrganizationMember targetMembership = createMembership(org, targetUser, OrganizationRole.MEMBER);

            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(ownerMembership));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(targetMembership));

            assertThatThrownBy(() -> service.changeRole(orgId, targetUser.getId(), OrganizationRole.OWNER, owner.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot set someone as OWNER");
        }

        @Test
        @DisplayName("should not allow changing own role")
        void shouldNotChangeOwnRole() {
            OrganizationMember ownerMembership = createMembership(org, owner, OrganizationRole.OWNER);

            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(ownerMembership));

            assertThatThrownBy(() -> service.changeRole(orgId, owner.getId(), OrganizationRole.ADMIN, owner.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot change your own role");
        }
    }

    // ===== acceptInvitationById =====

    @Nested
    @DisplayName("acceptInvitationById")
    class AcceptInvitationById {

        @Test
        @DisplayName("accept re-checks team plan support so downgraded org cannot add members")
        void acceptRechecksTeamPlanSupport() {
            UUID invId = UUID.randomUUID();
            OrganizationInvitation invitation = new OrganizationInvitation(org, "target@test.com", OrganizationRole.MEMBER, owner);
            invitation.setId(invId);

            when(invitationRepository.findById(invId)).thenReturn(Optional.of(invitation));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, targetUser.getId())).thenReturn(false);
            setupFreePlanForOwner();

            assertThatThrownBy(() -> service.acceptInvitationById(invId, targetUser.getId()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("does not support team members");

            verify(memberRepository, never()).save(any(OrganizationMember.class));
            verify(invitationRepository, never()).save(any(OrganizationInvitation.class));
        }

        @Test
        @DisplayName("accept re-checks current member count so pending invites cannot exceed plan cap")
        void acceptRechecksCurrentMemberCount() {
            UUID invId = UUID.randomUUID();
            OrganizationInvitation invitation = new OrganizationInvitation(org, "target@test.com", OrganizationRole.MEMBER, owner);
            invitation.setId(invId);

            when(invitationRepository.findById(invId)).thenReturn(Optional.of(invitation));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, targetUser.getId())).thenReturn(false);
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(10L);

            assertThatThrownBy(() -> service.acceptInvitationById(invId, targetUser.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Member limit reached");

            verify(memberRepository, never()).save(any(OrganizationMember.class));
            verify(invitationRepository, never()).save(any(OrganizationInvitation.class));
        }
    }

    // ===== CE inbox mailbox proof (CASA LC-084) =====

    @Nested
    @DisplayName("CE inbox: an account created AFTER the invitation must use the link (LC-084)")
    class CeInboxMailboxProof {

        private OrganizationInvitation invitationCreatedAt(java.time.LocalDateTime createdAt) {
            OrganizationInvitation invitation = new OrganizationInvitation(org, "target@test.com", OrganizationRole.MEMBER, owner);
            invitation.setId(UUID.randomUUID());
            ReflectionTestUtils.setField(invitation, "createdAt", createdAt);
            when(invitationRepository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            return invitation;
        }

        @Test
        @DisplayName("CE: a self-registered squatter of the invited address cannot accept from the inbox")
        void squatterCannotAcceptFromInbox() {
            ReflectionTestUtils.setField(service, "authMode", "embedded");
            targetUser.setCreatedAt(java.time.LocalDateTime.now());
            OrganizationInvitation invitation = invitationCreatedAt(java.time.LocalDateTime.now().minusHours(1));

            assertThatThrownBy(() -> service.acceptInvitationById(invitation.getId(), targetUser.getId()))
                    .isInstanceOf(InvitationRequiresLinkException.class);
            verify(memberRepository, never()).save(any(OrganizationMember.class));
        }

        @Test
        @DisplayName("CE: ... nor decline it (which would silently cancel the real invitee's invitation)")
        void squatterCannotDeclineFromInbox() {
            ReflectionTestUtils.setField(service, "authMode", "embedded");
            targetUser.setCreatedAt(java.time.LocalDateTime.now());
            OrganizationInvitation invitation = invitationCreatedAt(java.time.LocalDateTime.now().minusHours(1));

            assertThatThrownBy(() -> service.declineInvitationById(invitation.getId(), targetUser.getId()))
                    .isInstanceOf(InvitationRequiresLinkException.class);
            verify(invitationRepository, never()).save(any(OrganizationInvitation.class));
        }

        @Test
        @DisplayName("CE: an account that existed before the invitation can decline it from the inbox")
        void existingAccountCanStillUseInbox() {
            ReflectionTestUtils.setField(service, "authMode", "embedded");
            targetUser.setCreatedAt(java.time.LocalDateTime.now().minusDays(3));
            OrganizationInvitation invitation = invitationCreatedAt(java.time.LocalDateTime.now().minusHours(1));
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThat(service.declineInvitationById(invitation.getId(), targetUser.getId()).getStatus())
                    .isEqualTo(InvitationStatus.CANCELLED);
        }

        @Test
        @DisplayName("cloud (keycloak mode): the rule does not apply, the IdP and onboarding code verify the address")
        void cloudIsUnaffected() {
            targetUser.setCreatedAt(java.time.LocalDateTime.now());
            OrganizationInvitation invitation = invitationCreatedAt(java.time.LocalDateTime.now().minusHours(1));
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThat(service.declineInvitationById(invitation.getId(), targetUser.getId()).getStatus())
                    .isEqualTo(InvitationStatus.CANCELLED);
        }
    }

    // ===== declineInvitationById =====

    @Nested
    @DisplayName("declineInvitationById")
    class DeclineInvitationById {

        @Test
        @DisplayName("matching invitee can decline a pending inbox invitation")
        void matchingInviteeCanDecline() {
            UUID invId = UUID.randomUUID();
            OrganizationInvitation invitation = new OrganizationInvitation(org, "target@test.com", OrganizationRole.MEMBER, owner);
            invitation.setId(invId);
            when(invitationRepository.findById(invId)).thenReturn(Optional.of(invitation));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

            OrganizationInvitation result = service.declineInvitationById(invId, targetUser.getId());

            assertThat(result.getStatus()).isEqualTo(InvitationStatus.CANCELLED);
            verify(auditService).record(eq(orgId), eq(targetUser.getId()),
                    eq(OrganizationAuditEvent.Type.INVITE_CANCELLED), any());
            verify(memberRepository, never()).save(any(OrganizationMember.class));
        }

        @Test
        @DisplayName("mismatched invitee cannot decline another user's invitation")
        void mismatchedInviteeCannotDecline() {
            UUID invId = UUID.randomUUID();
            OrganizationInvitation invitation = new OrganizationInvitation(org, "someone-else@test.com", OrganizationRole.MEMBER, owner);
            invitation.setId(invId);
            when(invitationRepository.findById(invId)).thenReturn(Optional.of(invitation));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));

            assertThatThrownBy(() -> service.declineInvitationById(invId, targetUser.getId()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("email does not match");

            verify(invitationRepository, never()).save(any(OrganizationInvitation.class));
        }
    }

    // ===== cancelInvitation =====

    @Nested
    @DisplayName("cancelInvitation")
    class CancelInvitation {

        @Test
        @DisplayName("should cancel pending invitation")
        void shouldCancelSuccessfully() {
            UUID invId = UUID.randomUUID();
            OrganizationMember adminMembership = createMembership(org, inviter, OrganizationRole.ADMIN);
            OrganizationInvitation invitation = new OrganizationInvitation(org, "new@test.com", OrganizationRole.MEMBER, owner);
            invitation.setId(invId);

            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(adminMembership));
            when(invitationRepository.findById(invId)).thenReturn(Optional.of(invitation));
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

            service.cancelInvitation(orgId, invId, inviter.getId());

            verify(invitationRepository).save(invitationCaptor.capture());
            assertThat(invitationCaptor.getValue().getStatus()).isEqualTo(InvitationStatus.CANCELLED);
        }
    }

    // ===== getPendingInvitations =====

    @Nested
    @DisplayName("getPendingInvitations")
    class GetPendingInvitations {

        private void stubPendingList() {
            OrganizationInvitation inv1 = new OrganizationInvitation(org, "a@test.com", OrganizationRole.MEMBER, owner);
            OrganizationInvitation inv2 = new OrganizationInvitation(org, "b@test.com", OrganizationRole.ADMIN, owner);
            when(invitationRepository.findByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING))
                    .thenReturn(List.of(inv1, inv2));
        }

        @Test
        @DisplayName("OWNER lists the organization's pending invitations")
        void shouldReturnPendingInvitations() {
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(createMembership(org, owner, OrganizationRole.OWNER)));
            stubPendingList();

            List<OrganizationInvitation> result = service.getPendingInvitations(orgId, owner.getId());

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("ADMIN lists the organization's pending invitations")
        void adminCanListPendingInvitations() {
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            stubPendingList();

            assertThat(service.getPendingInvitations(orgId, inviter.getId())).hasSize(2);
        }

        @Test
        @DisplayName("F2: a MEMBER cannot list pending invitations (invitee emails and CE accept tokens)")
        void memberCannotListPendingInvitations() {
            // Both membership lookups answer "member" so the pre-fix code (membership-only
            // check) returns the list and this test fails on it.
            lenient().when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(createMembership(org, targetUser, OrganizationRole.MEMBER)));
            lenient().when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, targetUser.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.getPendingInvitations(orgId, targetUser.getId()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("OWNER or ADMIN");
            verify(invitationRepository, never()).findByOrganization_IdAndStatus(any(), any());
        }

        @Test
        @DisplayName("F2: a VIEWER cannot list pending invitations (invitee emails and CE accept tokens)")
        void viewerCannotListPendingInvitations() {
            lenient().when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(createMembership(org, targetUser, OrganizationRole.VIEWER)));
            lenient().when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, targetUser.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.getPendingInvitations(orgId, targetUser.getId()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("OWNER or ADMIN");
            verify(invitationRepository, never()).findByOrganization_IdAndStatus(any(), any());
        }

        @Test
        @DisplayName("a non-member is refused")
        void nonMemberIsRefused() {
            lenient().when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPendingInvitations(orgId, 99L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ===== F1: verified email required on every invitee path =====

    @Nested
    @DisplayName("F1: invitee must own a VERIFIED matching email")
    class InviteeVerifiedEmailRegressions {

        private OrganizationInvitation pendingFor(String email, String token) {
            OrganizationInvitation inv = new OrganizationInvitation(org, email, OrganizationRole.ADMIN, owner);
            inv.setId(UUID.randomUUID());
            if (token != null) {
                lenient().when(invitationRepository.findByTokenHash(TokenAtRest.hash(token))).thenReturn(Optional.of(inv));
            }
            lenient().when(invitationRepository.findById(inv.getId())).thenReturn(Optional.of(inv));
            return inv;
        }

        private void stubSuccessfulJoin() {
            lenient().when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, targetUser.getId())).thenReturn(false);
            lenient().when(subscriptionRepository.findActiveByUserId(owner.getId()))
                    .thenReturn(Optional.of(createSubscription(teamPlan)));
            lenient().when(memberRepository.countByOrganization_Id(orgId)).thenReturn(1L);
            lenient().when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(a -> a.getArgument(0));
        }

        @Test
        @DisplayName("S-1 regression: an UNVERIFIED account with the invitee email cannot accept by token")
        void unverifiedCannotAcceptByToken() {
            targetUser.setEmailVerified(false);
            OrganizationInvitation inv = pendingFor("target@test.com", "tok-unverified");
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            stubSuccessfulJoin();

            assertThatThrownBy(() -> service.acceptInvitation("tok-unverified", targetUser.getId()))
                    .isInstanceOf(InvitationEmailNotVerifiedException.class);

            verify(memberRepository, never()).save(any(OrganizationMember.class));
            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.PENDING);
        }

        @Test
        @DisplayName("S-1 regression: an UNVERIFIED account with the invitee email cannot accept by id")
        void unverifiedCannotAcceptById() {
            targetUser.setEmailVerified(false);
            OrganizationInvitation inv = pendingFor("target@test.com", null);
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            stubSuccessfulJoin();

            assertThatThrownBy(() -> service.acceptInvitationById(inv.getId(), targetUser.getId()))
                    .isInstanceOf(InvitationEmailNotVerifiedException.class);

            verify(memberRepository, never()).save(any(OrganizationMember.class));
            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.PENDING);
        }

        @Test
        @DisplayName("S-1 regression: an UNVERIFIED account with the invitee email cannot decline by id")
        void unverifiedCannotDeclineById() {
            targetUser.setEmailVerified(false);
            OrganizationInvitation inv = pendingFor("target@test.com", null);
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            lenient().when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(a -> a.getArgument(0));

            assertThatThrownBy(() -> service.declineInvitationById(inv.getId(), targetUser.getId()))
                    .isInstanceOf(InvitationEmailNotVerifiedException.class);

            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.PENDING);
        }

        @Test
        @DisplayName("S-1 regression: an UNVERIFIED account with the invitee email cannot decline by token")
        void unverifiedCannotDeclineByToken() {
            targetUser.setEmailVerified(false);
            OrganizationInvitation inv = pendingFor("target@test.com", "tok-decline-unverified");
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            lenient().when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(a -> a.getArgument(0));

            assertThatThrownBy(() -> service.declineInvitation("tok-decline-unverified", targetUser.getId()))
                    .isInstanceOf(InvitationEmailNotVerifiedException.class);

            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.PENDING);
        }

        @Test
        @DisplayName("the inbox of a VERIFIED account lists its invitations, looked up by the normalized email")
        void verifiedInboxUsesNormalizedEmail() {
            targetUser.setEmail("  Target@Test.COM ");
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            OrganizationInvitation inv = new OrganizationInvitation(org, "target@test.com", OrganizationRole.MEMBER, owner);
            when(invitationRepository.findByEmailAndStatus("target@test.com", InvitationStatus.PENDING))
                    .thenReturn(List.of(inv));

            assertThat(service.getPendingInvitationsForUser(targetUser.getId())).containsExactly(inv);
        }

        @Test
        @DisplayName("a VERIFIED invitee whose stored email differs only in ASCII case can accept by token")
        void verifiedInviteeWithDifferentAsciiCaseCanAccept() {
            targetUser.setEmail("Target@Test.com");
            pendingFor("target@test.com", "tok-ok");
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            stubSuccessfulJoin();

            OrganizationInvitation result = service.acceptInvitation("tok-ok", targetUser.getId());

            assertThat(result.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
            verify(memberRepository).save(memberCaptor.capture());
            assertThat(memberCaptor.getValue().getRole()).isEqualTo(OrganizationRole.ADMIN);
        }

        @Test
        @DisplayName("a VERIFIED invitee can decline by token (accept page Decline button)")
        void verifiedInviteeCanDeclineByToken() {
            pendingFor("target@test.com", "tok-decline");
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(a -> a.getArgument(0));

            OrganizationInvitation result = service.declineInvitation("tok-decline", targetUser.getId());

            assertThat(result.getStatus()).isEqualTo(InvitationStatus.CANCELLED);
            verify(memberRepository, never()).save(any(OrganizationMember.class));
        }

        @Test
        @DisplayName("Unicode look-alike U+017F (long s) no longer matches an ASCII 's' invitation email")
        void longSLookalikeDoesNotMatch() {
            targetUser.setEmail("admin\u017F@test.com");
            OrganizationInvitation inv = pendingFor("admins@test.com", "tok-long-s");
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            stubSuccessfulJoin();

            assertThatThrownBy(() -> service.acceptInvitation("tok-long-s", targetUser.getId()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("email does not match");
            verify(memberRepository, never()).save(any(OrganizationMember.class));
            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.PENDING);
        }

        @Test
        @DisplayName("Unicode look-alike U+212A (Kelvin sign) no longer matches an ASCII 'k' invitation email")
        void kelvinLookalikeDoesNotMatch() {
            targetUser.setEmail("\u212Aate@test.com");
            OrganizationInvitation inv = pendingFor("kate@test.com", null);
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            stubSuccessfulJoin();

            assertThatThrownBy(() -> service.acceptInvitationById(inv.getId(), targetUser.getId()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("email does not match");
            verify(memberRepository, never()).save(any(OrganizationMember.class));
        }

        @Test
        @DisplayName("normalizeInvitationEmail trims and lowercases ASCII only")
        void normalizeLowercasesAsciiOnly() {
            assertThat(EmailNormalizer.normalize("  Ada@Example.COM ")).isEqualTo("ada@example.com");
            assertThat(EmailNormalizer.normalize("\u212Aate@x.io")).isEqualTo("\u212Aate@x.io");
            assertThat(EmailNormalizer.normalize(null)).isNull();
            assertThat(EmailNormalizer.matches(null, null)).isFalse();
            assertThat(EmailNormalizer.matches("", "")).isFalse();
        }
    }

    // ===== Audit round 2 =====

    @Nested
    @DisplayName("audit round 2: decline by token, CE by-id narrowing, expired re-invite, public inviter name")
    class AuditRoundTwo {

        private OrganizationInvitation pendingWithToken(String email, String token) {
            OrganizationInvitation inv = new OrganizationInvitation(org, email, OrganizationRole.MEMBER, owner);
            inv.setId(UUID.randomUUID());
            lenient().when(invitationRepository.findByTokenHash(TokenAtRest.hash(token))).thenReturn(Optional.of(inv));
            lenient().when(invitationRepository.findById(inv.getId())).thenReturn(Optional.of(inv));
            return inv;
        }

        // --- decline by token: error paths

        @Test
        @DisplayName("decline by token: a verified account with ANOTHER email is refused and the invitation stays PENDING")
        void declineByTokenEmailMismatch() {
            OrganizationInvitation inv = pendingWithToken("someone-else@test.com", "tok-mismatch");
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));

            assertThatThrownBy(() -> service.declineInvitation("tok-mismatch", targetUser.getId()))
                    .isInstanceOf(SecurityException.class)
                    .isNotInstanceOf(InvitationEmailNotVerifiedException.class)
                    .hasMessageContaining("email does not match");
            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.PENDING);
            verify(invitationRepository, never()).save(any(OrganizationInvitation.class));
        }

        @Test
        @DisplayName("decline by token: an expired invitation is flipped to EXPIRED and refused")
        void declineByTokenExpired() {
            OrganizationInvitation inv = pendingWithToken("target@test.com", "tok-expired");
            inv.setExpiresAt(java.time.LocalDateTime.now().minusDays(1));
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(a -> a.getArgument(0));

            assertThatThrownBy(() -> service.declineInvitation("tok-expired", targetUser.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expired");
            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.EXPIRED);
        }

        @Test
        @DisplayName("decline by token: an invitation that is no longer PENDING is refused unchanged")
        void declineByTokenNotPending() {
            OrganizationInvitation inv = pendingWithToken("target@test.com", "tok-accepted");
            inv.setStatus(InvitationStatus.ACCEPTED);

            assertThatThrownBy(() -> service.declineInvitation("tok-accepted", targetUser.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no longer pending");
            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
            verify(invitationRepository, never()).save(any(OrganizationInvitation.class));
        }

        @Test
        @DisplayName("decline by token: an unknown token is refused")
        void declineByTokenUnknown() {
            when(invitationRepository.findByTokenHash(TokenAtRest.hash("nope"))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.declineInvitation("nope", targetUser.getId()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // --- inbox of an unverified account

        @Test
        @DisplayName("the inbox of an UNVERIFIED account is refused with EMAIL_NOT_VERIFIED (UI tells the user to verify)")
        void unverifiedInboxIsRefused() {
            targetUser.setEmailVerified(false);
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));

            assertThatThrownBy(() -> service.getPendingInvitationsForUser(targetUser.getId()))
                    .isInstanceOf(InvitationEmailNotVerifiedException.class);
            verify(invitationRepository, never()).findByEmailAndStatus(any(), any());
        }

        // --- CE (embedded auth): by-id paths only for accounts older than the invitation

        @Test
        @DisplayName("CE: an account registered AFTER the invitation cannot accept it by id (open-registration squatting)")
        void ceAccountCreatedAfterInvitationCannotAcceptById() {
            enableCeEmbeddedMode();
            OrganizationInvitation inv = pendingWithToken("target@test.com", "tok-ce-1");
            inv.setCreatedAt(java.time.LocalDateTime.now().minusHours(2));
            targetUser.setCreatedAt(java.time.LocalDateTime.now().minusHours(1));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            lenient().when(subscriptionRepository.findActiveByUserId(owner.getId()))
                    .thenReturn(Optional.of(createSubscription(teamPlan)));
            lenient().when(memberRepository.countByOrganization_Id(orgId)).thenReturn(1L);

            assertThatThrownBy(() -> service.acceptInvitationById(inv.getId(), targetUser.getId()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("invitation link");
            verify(memberRepository, never()).save(any(OrganizationMember.class));
            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.PENDING);
        }

        @Test
        @DisplayName("CE: an account registered AFTER the invitation cannot decline it by id either")
        void ceAccountCreatedAfterInvitationCannotDeclineById() {
            enableCeEmbeddedMode();
            OrganizationInvitation inv = pendingWithToken("target@test.com", "tok-ce-2");
            inv.setCreatedAt(java.time.LocalDateTime.now().minusHours(2));
            targetUser.setCreatedAt(java.time.LocalDateTime.now().minusHours(1));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));

            assertThatThrownBy(() -> service.declineInvitationById(inv.getId(), targetUser.getId()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("invitation link");
            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.PENDING);
        }

        @Test
        @DisplayName("CE: the inbox hides invitations older than the account, lists those sent to an existing account")
        void ceInboxOnlyListsInvitationsSentToAnExistingAccount() {
            enableCeEmbeddedMode();
            targetUser.setCreatedAt(java.time.LocalDateTime.now().minusHours(1));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            OrganizationInvitation before = new OrganizationInvitation(org, "target@test.com", OrganizationRole.ADMIN, owner);
            before.setCreatedAt(java.time.LocalDateTime.now().minusHours(2));
            OrganizationInvitation after = new OrganizationInvitation(org, "target@test.com", OrganizationRole.MEMBER, owner);
            after.setCreatedAt(java.time.LocalDateTime.now().minusMinutes(5));
            when(invitationRepository.findByEmailAndStatus("target@test.com", InvitationStatus.PENDING))
                    .thenReturn(List.of(before, after));

            assertThat(service.getPendingInvitationsForUser(targetUser.getId())).containsExactly(after);
        }

        @Test
        @DisplayName("CE: the TOKEN path still works for an account registered after the invitation")
        void ceTokenPathStillWorksForNewAccount() {
            enableCeEmbeddedMode();
            OrganizationInvitation inv = pendingWithToken("target@test.com", "tok-ce-3");
            inv.setCreatedAt(java.time.LocalDateTime.now().minusHours(2));
            targetUser.setCreatedAt(java.time.LocalDateTime.now().minusHours(1));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, targetUser.getId())).thenReturn(false);
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(1L);
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(a -> a.getArgument(0));

            assertThat(service.acceptInvitation("tok-ce-3", targetUser.getId()).getStatus())
                    .isEqualTo(InvitationStatus.ACCEPTED);
        }

        @Test
        @DisplayName("CE: an account or invitation with NO creation time cannot prove the account predates the invite: token required")
        void ceMissingCreatedAtRequiresToken() {
            enableCeEmbeddedMode();
            OrganizationInvitation inv = pendingWithToken("target@test.com", "tok-ce-null");
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));

            targetUser.setCreatedAt(null);
            inv.setCreatedAt(java.time.LocalDateTime.now().minusHours(2));
            assertThatThrownBy(() -> service.acceptInvitationById(inv.getId(), targetUser.getId()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("invitation link");

            targetUser.setCreatedAt(java.time.LocalDateTime.now().minusDays(30));
            inv.setCreatedAt(null);
            assertThatThrownBy(() -> service.declineInvitationById(inv.getId(), targetUser.getId()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("invitation link");

            when(invitationRepository.findByEmailAndStatus("target@test.com", InvitationStatus.PENDING))
                    .thenReturn(List.of(inv));
            assertThat(service.getPendingInvitationsForUser(targetUser.getId())).isEmpty();

            verify(memberRepository, never()).save(any(OrganizationMember.class));
            assertThat(inv.getStatus()).isEqualTo(InvitationStatus.PENDING);
        }

        @Test
        @DisplayName("cloud: the by-id path does not depend on account age (a verified email is the proof)")
        void cloudByIdIgnoresAccountAge() {
            OrganizationInvitation inv = pendingWithToken("target@test.com", "tok-cloud");
            inv.setCreatedAt(java.time.LocalDateTime.now().minusHours(2));
            targetUser.setCreatedAt(java.time.LocalDateTime.now().minusHours(1));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(a -> a.getArgument(0));

            assertThat(service.declineInvitationById(inv.getId(), targetUser.getId()).getStatus())
                    .isEqualTo(InvitationStatus.CANCELLED);
        }

        // --- F6: expired PENDING must not block a re-invite

        @Test
        @DisplayName("an EXPIRED pending invitation is expired first and no longer blocks a re-invite")
        void expiredPendingDoesNotBlockReInvite() {
            OrganizationInvitation stale = new OrganizationInvitation(org, "new@test.com", OrganizationRole.MEMBER, owner);
            stale.setExpiresAt(java.time.LocalDateTime.now().minusDays(1));
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(invitationRepository.findByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING))
                    .thenReturn(List.of(stale));
            // The repository answers from the row's CURRENT status, like the database would.
            lenient().when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING))
                    .thenAnswer(a -> stale.getStatus() == InvitationStatus.PENDING ? 1L : 0L);
            when(invitationRepository.existsByOrganization_IdAndEmailAndStatus(orgId, "new@test.com", InvitationStatus.PENDING))
                    .thenAnswer(a -> stale.getStatus() == InvitationStatus.PENDING);
            when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
            when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(a -> a.getArgument(0));

            OrganizationInvitation fresh = service.inviteMember(orgId, "new@test.com", OrganizationRole.MEMBER, inviter.getId());

            assertThat(stale.getStatus()).isEqualTo(InvitationStatus.EXPIRED);
            assertThat(fresh.getStatus()).isEqualTo(InvitationStatus.PENDING);
            assertThat(fresh).isNotSameAs(stale);
        }

        // --- public inviter name

        @Test
        @DisplayName("/invitations/info never falls back to the inviter's EMAIL (public endpoint)")
        void publicInfoOmitsInviterEmail() {
            OrganizationInvitation inv = pendingWithToken("newcomer@test.com", "tok-info-email");
            when(userRepository.findByEmail("newcomer@test.com")).thenReturn(Optional.empty());
            when(onboardingService.resolveDisplayName(owner.getId())).thenReturn("owner@test.com");

            OrganizationMemberService.InvitationInfo info = service.getInvitationInfo("tok-info-email");

            assertThat(info.valid()).isTrue();
            assertThat(info.inviterName()).isNull();
        }

        @Test
        @DisplayName("/invitations/info drops any email-shaped name, keeps a real display name")
        void publicInfoKeepsDisplayNameOnly() {
            pendingWithToken("newcomer@test.com", "tok-info-name");
            when(userRepository.findByEmail("newcomer@test.com")).thenReturn(Optional.empty());
            when(onboardingService.resolveDisplayName(owner.getId())).thenReturn("other@elsewhere.io", "Olivia Owner");

            assertThat(service.getInvitationInfo("tok-info-name").inviterName()).isNull();
            assertThat(service.getInvitationInfo("tok-info-name").inviterName()).isEqualTo("Olivia Owner");
        }
    }

    // ===== F5: soft-deleted organization =====

    @Nested
    @DisplayName("F5: inviteMember on a soft-deleted organization")
    class InviteIntoDeletedOrganization {

        @Test
        @DisplayName("refuses to invite into a soft-deleted organization and persists nothing")
        void refusesDeletedOrganization() {
            org.setDeletedAt(java.time.LocalDateTime.now());
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            // Everything else would let the invite through, so the pre-fix code saves it.
            lenient().when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            lenient().when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            lenient().when(subscriptionRepository.findActiveByUserId(owner.getId()))
                    .thenReturn(Optional.of(createSubscription(teamPlan)));
            lenient().when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
            lenient().when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(a -> a.getArgument(0));

            assertThatThrownBy(() -> service.inviteMember(orgId, "new@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("deleted organization");

            verify(invitationRepository, never()).save(any(OrganizationInvitation.class));
            verifyNoInteractions(invitationMailer);
        }
    }

    // ===== getTeamStatus =====

    @Nested
    @DisplayName("getTeamStatus")
    class GetTeamStatus {

        @Test
        @DisplayName("should return correct team status for TEAM plan")
        void shouldReturnTeamStatus() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(3L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(1L);

            OrganizationMemberService.TeamStatus status = service.getTeamStatus(orgId);

            assertThat(status.supportsTeam()).isTrue();
            assertThat(status.maxMembers()).isEqualTo(10);
            assertThat(status.currentMembers()).isEqualTo(3);
            assertThat(status.pendingInvitations()).isEqualTo(1);
            assertThat(status.canInvite()).isTrue();
            assertThat(status.planCode()).isEqualTo("TEAM");
        }

        @Test
        @DisplayName("should return false canInvite for FREE plan")
        void shouldNotSupportTeamForFreePlan() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            setupFreePlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(1L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(0L);

            OrganizationMemberService.TeamStatus status = service.getTeamStatus(orgId);

            assertThat(status.supportsTeam()).isFalse();
            assertThat(status.canInvite()).isFalse();
        }

        @Test
        @DisplayName("CE embedded mode no longer grants unlimited team - it follows the plan (FREE → no team), like cloud")
        void ceEmbeddedModeFollowsThePlanNotUnlimited() {
            enableCeEmbeddedMode();
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            setupFreePlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(1L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING)).thenReturn(0L);

            OrganizationMemberService.TeamStatus status = service.getTeamStatus(orgId);

            // Pre-change this returned supportsTeam=true / maxMembers=MAX_VALUE; now CE gates by the
            // owner's plan exactly like cloud - a FREE owner has no team and cannot invite.
            assertThat(status.supportsTeam()).isFalse();
            assertThat(status.canInvite()).isFalse();
        }
    }

    @Nested
    @DisplayName("leave (PR-4a)")
    class Leave {

        @Test
        @DisplayName("MEMBER leaves successfully - membership deleted")
        void memberLeavesSuccessfully() {
            OrganizationMember membership = createMembership(org, targetUser, OrganizationRole.MEMBER);
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(membership));

            service.leave(orgId, targetUser.getId());

            verify(memberRepository).delete(membership);
        }

        @Test
        @DisplayName("ADMIN can leave (only OWNER is blocked)")
        void adminCanLeave() {
            OrganizationMember adminMembership = createMembership(org, targetUser, OrganizationRole.ADMIN);
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(adminMembership));

            service.leave(orgId, targetUser.getId());

            verify(memberRepository).delete(adminMembership);
        }

        @Test
        @DisplayName("OWNER cannot leave - must transferOwnership first")
        void ownerCannotLeave() {
            OrganizationMember ownerMembership = createMembership(org, owner, OrganizationRole.OWNER);
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(ownerMembership));

            assertThatThrownBy(() -> service.leave(orgId, owner.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OWNER cannot leave");

            verify(memberRepository, never()).delete(any());
        }

        @Test
        @DisplayName("PR-4b - leave records ORG_MEMBER_LEFT with role + wasDefault")
        void recordsAuditEventOnLeave() {
            OrganizationMember membership = createMembership(org, targetUser, OrganizationRole.ADMIN);
            membership.setDefault(false);
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(membership));

            service.leave(orgId, targetUser.getId());

            verify(auditService).record(eq(orgId), eq(targetUser.getId()),
                    eq(com.apimarketplace.auth.domain.OrganizationAuditEvent.Type.MEMBER_LEFT),
                    argThat(data -> "ADMIN".equals(data.get("role"))
                            && Boolean.FALSE.equals(data.get("wasDefault"))));
        }

        @Test
        @DisplayName("Non-member triggers IllegalArgumentException - distinguished from OWNER block")
        void notAMemberThrows() {
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.leave(orgId, targetUser.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Not a member");
        }

        @Test
        @DisplayName("Leaving default org promotes the next-oldest remaining membership")
        void leavingDefaultOrgPromotesNext() {
            // The org being left is the user's current default ; another membership
            // (personal org, older joinedAt) must take over as default after delete.
            OrganizationMember leaving = createMembership(org, targetUser, OrganizationRole.MEMBER);
            leaving.setDefault(true);
            leaving.setJoinedAt(java.time.LocalDateTime.now());

            com.apimarketplace.auth.domain.Organization personalOrg =
                    new com.apimarketplace.auth.domain.Organization();
            java.lang.reflect.Field idField;
            try {
                idField = com.apimarketplace.auth.domain.Organization.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(personalOrg, java.util.UUID.randomUUID());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            OrganizationMember personalMembership = createMembership(personalOrg, targetUser, OrganizationRole.OWNER);
            personalMembership.setDefault(false);
            personalMembership.setJoinedAt(java.time.LocalDateTime.now().minusDays(30));

            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(leaving));
            when(memberRepository.findByUser_Id(targetUser.getId()))
                    .thenReturn(java.util.List.of(personalMembership));
            when(memberRepository.save(any(OrganizationMember.class))).thenAnswer(inv -> inv.getArgument(0));

            service.leave(orgId, targetUser.getId());

            verify(memberRepository).delete(leaving);
            verify(memberRepository).save(argThat(m ->
                    m == personalMembership && m.isDefault()));
        }

        @Test
        @DisplayName("Leaving a non-default org does not touch the default flag of any other membership")
        void leavingNonDefaultOrgNoPromotion() {
            OrganizationMember leaving = createMembership(org, targetUser, OrganizationRole.MEMBER);
            leaving.setDefault(false);

            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(leaving));

            service.leave(orgId, targetUser.getId());

            verify(memberRepository).delete(leaving);
            verify(memberRepository, never()).save(any());
            verify(memberRepository, never()).findByUser_Id(any());
        }
    }

    @Nested
    @DisplayName("transferOwnership (PR-4c)")
    class TransferOwnership {

        @Test
        @DisplayName("OWNER → MEMBER : role flip + Organization.owner_id flipped to new user (atomic)")
        void successfulTransferFlipsBothRoleAndOwnerId() {
            OrganizationMember currentOwner = createMembership(org, owner, OrganizationRole.OWNER);
            OrganizationMember targetMember = createMembership(org, targetUser, OrganizationRole.MEMBER);
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(currentOwner));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(targetMember));
            when(organizationRepository.findByIdForUpdate(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
            when(memberRepository.save(any(OrganizationMember.class))).thenAnswer(inv -> inv.getArgument(0));
            when(organizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            @SuppressWarnings("unchecked")
            ObjectProvider<OrganizationSamlService> provider =
                    mock(ObjectProvider.class);
            OrganizationSamlService saml = mock(OrganizationSamlService.class);
            when(provider.getIfAvailable()).thenReturn(saml);
            ReflectionTestUtils.setField(service, "organizationSamlServiceProvider", provider);

            service.transferOwnership(orgId, owner.getId(), targetUser.getId());

            // The workspace plan is now the new owner's: its SAML IdP state is re-applied.
            verify(saml).syncIdentityProviderEnabled(orgId);

            assertThat(currentOwner.getRole()).isEqualTo(OrganizationRole.ADMIN);
            assertThat(targetMember.getRole()).isEqualTo(OrganizationRole.OWNER);
            // The OWNER FK on Organization MUST flip too - otherwise ON DELETE CASCADE
            // on owner_id would tie the org's lifecycle to the old owner, and
            // getOwnerPlan() would resolve to their plan instead of the new one.
            assertThat(org.getOwner()).isEqualTo(targetUser);
            verify(memberRepository).save(currentOwner);
            verify(memberRepository).save(targetMember);
            verify(organizationRepository).save(org);
        }

        @Test
        @DisplayName("locks the organization row before reading owner membership")
        void locksOrganizationBeforeReadingOwnerMembership() {
            OrganizationMember currentOwner = createMembership(org, owner, OrganizationRole.OWNER);
            OrganizationMember targetMember = createMembership(org, targetUser, OrganizationRole.MEMBER);
            when(organizationRepository.findByIdForUpdate(orgId)).thenReturn(Optional.of(org));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(currentOwner));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(targetMember));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));

            service.transferOwnership(orgId, owner.getId(), targetUser.getId());

            var order = inOrder(organizationRepository, memberRepository);
            order.verify(organizationRepository).findByIdForUpdate(orgId);
            order.verify(memberRepository).findByOrganization_IdAndUser_Id(orgId, owner.getId());
        }

        @Test
        @DisplayName("ADMIN requester is rejected - only OWNER can transfer")
        void adminCannotTransfer() {
            OrganizationMember adminRequester = createMembership(org, inviter, OrganizationRole.ADMIN);
            when(organizationRepository.findByIdForUpdate(orgId)).thenReturn(Optional.of(org));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(adminRequester));

            assertThatThrownBy(() -> service.transferOwnership(orgId, inviter.getId(), targetUser.getId()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Only the current OWNER");

            verify(memberRepository, never()).save(any());
        }

        @Test
        @DisplayName("Target is not a member - IllegalArgumentException (invite them first)")
        void targetNotMember() {
            OrganizationMember currentOwner = createMembership(org, owner, OrganizationRole.OWNER);
            when(organizationRepository.findByIdForUpdate(orgId)).thenReturn(Optional.of(org));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(currentOwner));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.transferOwnership(orgId, owner.getId(), targetUser.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Target user is not a member");
        }

        @Test
        @DisplayName("No-op transfer (same user) - rejected to keep audit clean")
        void cannotTransferToSelf() {
            assertThatThrownBy(() -> service.transferOwnership(orgId, owner.getId(), owner.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("current owner");

            verify(memberRepository, never()).findByOrganization_IdAndUser_Id(any(), any());
        }

        @Test
        @DisplayName("PR-4b - transferOwnership records ORG_OWNERSHIP_TRANSFERRED with prev+new owner")
        void recordsAuditEventOnTransfer() {
            OrganizationMember currentOwner = createMembership(org, owner, OrganizationRole.OWNER);
            OrganizationMember targetMember = createMembership(org, targetUser, OrganizationRole.MEMBER);
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(currentOwner));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUser.getId()))
                    .thenReturn(Optional.of(targetMember));
            when(organizationRepository.findByIdForUpdate(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));

            service.transferOwnership(orgId, owner.getId(), targetUser.getId());

            verify(auditService).record(eq(orgId), eq(owner.getId()),
                    eq(com.apimarketplace.auth.domain.OrganizationAuditEvent.Type.OWNERSHIP_TRANSFERRED),
                    argThat(data -> owner.getId().equals(data.get("previousOwnerUserId"))
                            && targetUser.getId().equals(data.get("newOwnerUserId"))));
        }

        @Test
        @DisplayName("Current OWNER is not a member of the org - IllegalArgumentException")
        void currentOwnerNotMember() {
            when(organizationRepository.findByIdForUpdate(orgId)).thenReturn(Optional.of(org));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.transferOwnership(orgId, owner.getId(), targetUser.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Current owner is not a member");
        }
    }

    @Nested
    @DisplayName("softDeleteOrganization (v10 - delete re-enabled with 30-day grace)")
    class SoftDeleteOrganization {

        // Workspace delete was re-enabled: the OWNER soft-deletes a non-personal workspace
        // (30-day restorable grace, then a hard-purge that RETAINS the financial ledger).
        // Full coverage lives in OrganizationMemberServiceWorkspaceDeleteTest; these guard
        // the in-file path.

        @Test
        @DisplayName("Owner + correct confirmName → soft-deletes (deleted_at set, audited DELETED)")
        void ownerWithCorrectConfirmNameSoftDeletes() {
            org.setName("Acme Corp");
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()))
                    .thenReturn(Optional.of(createMembership(org, owner, OrganizationRole.OWNER)));
            when(invitationRepository.findByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING))
                    .thenReturn(List.of());
            when(memberRepository.findByOrganization_Id(orgId))
                    .thenReturn(List.of(createMembership(org, owner, OrganizationRole.OWNER)));

            service.softDeleteOrganization(orgId, owner.getId(), "Acme Corp");

            assertThat(org.isDeleted()).isTrue();
            verify(organizationRepository).save(org);
            verify(auditService).record(eq(orgId), eq(owner.getId()),
                    eq(com.apimarketplace.auth.domain.OrganizationAuditEvent.Type.DELETED), any());
        }

        @Test
        @DisplayName("Personal workspace → rejected (the base workspace is never deletable)")
        void personalOrgRejected() {
            org.setName("Personal");
            org.setPersonal(true);
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

            assertThatThrownBy(() -> service.softDeleteOrganization(orgId, owner.getId(), "Personal"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("personal workspace");

            assertThat(org.isDeleted()).isFalse();
        }

        @Test
        @DisplayName("Org not found → IllegalArgumentException")
        void orgNotFound() {
            when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.softDeleteOrganization(orgId, owner.getId(), "anything"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Organization not found");
        }
    }

    // ===== Invitation security pass (S-1, S-2, S-5) - regression guards =====

    /**
     * Regression guards for the invitation security pass. Each test is a 1:1
     * pin on a specific finding from the 3-Opus audit - name encodes the
     * vulnerability so a future revert is obvious from the test report.
     */
    @Nested
    @DisplayName("invitation security pass - S-1 / S-2 / S-5 regressions")
    class InvitationSecurityRegressions {

        // -------- S-2: per-inviter / per-org rate limit --------

        @Test
        @DisplayName("S-2: per-inviter rate limit (rolling hour) - 429 when exceeded")
        void s2RateLimitPerInviter() {
            OrganizationMemberService tight = new OrganizationMemberService(
                    memberRepository, invitationRepository, organizationRepository,
                    userRepository, subscriptionRepository, onboardingService, auditService,
                    invitationMailer, editionProvider, gatewayCacheClient, /* perInviterPerHour */ 3, /* perOrgPerHour */ 1000);

            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();  // round-2: rate limit now fires AFTER plan/quota
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING))
                    .thenReturn(0L);
            when(invitationRepository.countByInvitedBy_IdAndCreatedAtAfter(eq(inviter.getId()), any()))
                    .thenReturn(3L);  // at cap

            assertThatThrownBy(() ->
                    tight.inviteMember(orgId, "spam@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(InvitationRateLimitException.class)
                    .hasMessageContaining("rate limit reached for this user");

            // Email lookup must NOT fire - that's the existing-account oracle
            // we're closing. Plan/quota fired before the rate-limit gate (audit C
            // SHOULD-FIX round-2 reorder), so we no longer verify those nevers.
            verify(userRepository, never()).findByEmail(any());
        }

        @Test
        @DisplayName("S-2: per-org rate limit (rolling hour) - 429 when exceeded "
                + "(catches multiple admins colluding on the same org)")
        void s2RateLimitPerOrg() {
            OrganizationMemberService tight = new OrganizationMemberService(
                    memberRepository, invitationRepository, organizationRepository,
                    userRepository, subscriptionRepository, onboardingService, auditService,
                    invitationMailer, editionProvider, gatewayCacheClient, /* perInviterPerHour */ 1000, /* perOrgPerHour */ 5);

            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();  // round-2: rate limit now fires AFTER plan check
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING))
                    .thenReturn(0L);
            when(invitationRepository.countByInvitedBy_IdAndCreatedAtAfter(eq(inviter.getId()), any()))
                    .thenReturn(0L);
            when(invitationRepository.countByOrganization_IdAndCreatedAtAfter(eq(orgId), any()))
                    .thenReturn(5L);  // at cap

            assertThatThrownBy(() ->
                    tight.inviteMember(orgId, "new@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(InvitationRateLimitException.class)
                    .hasMessageContaining("rate limit reached for this organization");

            // Per-org rate-limit path must also not leak via the email-lookup oracle.
            verify(userRepository, never()).findByEmail(any());
        }

        @Test
        @DisplayName("S-2 (round-2 fix): reorder - FREE-plan admin who hits the cap gets the "
                + "informative \"upgrade your plan\" 403, NOT a confusing 429")
        void s2RateLimitDoesNotMaskFreePlanError() {
            // The rate-limit gate fires AFTER plan/quota, so a misclicking
            // FREE-plan owner doesn't see a 429 instead of the more useful
            // "upgrade your plan" error. Rate limit infra still active (rogue
            // admin on a paid plan still gets stopped - see s2RateLimitPerInviter).
            OrganizationMemberService tight = new OrganizationMemberService(
                    memberRepository, invitationRepository, organizationRepository,
                    userRepository, subscriptionRepository, onboardingService, auditService,
                    invitationMailer, editionProvider, gatewayCacheClient, /* perInviterPerHour */ 1, /* perOrgPerHour */ 1);

            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupFreePlanForOwner();

            // Plan gate fires first → UnsupportedOperationException, not InvitationRateLimitException.
            assertThatThrownBy(() ->
                    tight.inviteMember(orgId, "new@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("does not support team members");

            verify(invitationRepository, never()).countByInvitedBy_IdAndCreatedAtAfter(any(), any());
            verify(invitationRepository, never()).countByOrganization_IdAndCreatedAtAfter(any(), any());
        }

        @Test
        @DisplayName("S-2 (round-2 fix): rate-limit trip MUST emit ORG_INVITE_RATE_LIMITED audit "
                + "event - otherwise a patient under-cap spammer leaves no trace")
        void s2RateLimitEmitsAuditEvent() {
            OrganizationMemberService tight = new OrganizationMemberService(
                    memberRepository, invitationRepository, organizationRepository,
                    userRepository, subscriptionRepository, onboardingService, auditService,
                    invitationMailer, editionProvider, gatewayCacheClient, /* perInviterPerHour */ 3, /* perOrgPerHour */ 1000);

            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.ADMIN)));
            setupTeamPlanForOwner();
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(invitationRepository.countByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING))
                    .thenReturn(0L);
            when(invitationRepository.countByInvitedBy_IdAndCreatedAtAfter(eq(inviter.getId()), any()))
                    .thenReturn(3L);  // at cap

            assertThatThrownBy(() ->
                    tight.inviteMember(orgId, "spam@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(InvitationRateLimitException.class);

            verify(auditService).record(eq(orgId), eq(inviter.getId()),
                    eq(OrganizationAuditEvent.Type.INVITE_RATE_LIMITED),
                    argThat(data -> "per-inviter".equals(data.get("dimension"))
                            && Integer.valueOf(3).equals(data.get("cap"))
                            && Integer.valueOf(1).equals(data.get("windowHours"))));
        }

        @Test
        @DisplayName("S-2: rate limit applies AFTER auth check - non-admin probes do not "
                + "pollute the counter (countBy* must not be queried)")
        void s2RateLimitNotConsultedForUnauthorizedRequester() {
            // A plain MEMBER tries to invite - they're rejected by the OWNER/ADMIN
            // guard. The rate-limit DB queries must not fire for them, otherwise
            // an unprivileged user could probe whether the org has hit its cap.
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, inviter.getId()))
                    .thenReturn(Optional.of(createMembership(org, inviter, OrganizationRole.MEMBER)));

            assertThatThrownBy(() ->
                    service.inviteMember(orgId, "new@test.com", OrganizationRole.MEMBER, inviter.getId()))
                    .isInstanceOf(SecurityException.class);

            verify(invitationRepository, never()).countByInvitedBy_IdAndCreatedAtAfter(any(), any());
            verify(invitationRepository, never()).countByOrganization_IdAndCreatedAtAfter(any(), any());
        }

    }

    // ===== getInvitationInfo (CE invite-by-link accept-page prefill) =====

    @Nested
    @DisplayName("getInvitationInfo")
    class GetInvitationInfo {

        @Test
        @DisplayName("PENDING token → valid + email + org + role + hasAccount=false when no local user")
        void pendingTokenForBrandNewEmail() {
            OrganizationInvitation inv = new OrganizationInvitation(org, "newcomer@test.com", OrganizationRole.ADMIN, owner);
            when(invitationRepository.findByTokenHash(TokenAtRest.hash("tok-1"))).thenReturn(Optional.of(inv));
            when(userRepository.findByEmail("newcomer@test.com")).thenReturn(Optional.empty());
            when(onboardingService.resolveDisplayName(owner.getId())).thenReturn("Olivia Owner");

            OrganizationMemberService.InvitationInfo info = service.getInvitationInfo("tok-1");

            assertThat(info.valid()).isTrue();
            assertThat(info.email()).isEqualTo("newcomer@test.com");
            assertThat(info.organizationName()).isEqualTo(org.getName());
            assertThat(info.role()).isEqualTo(OrganizationRole.ADMIN);
            assertThat(info.hasAccount()).isFalse();
            // F4: the accept page shows who invited the user before asking for consent.
            assertThat(info.inviterName()).isEqualTo("Olivia Owner");
        }

        @Test
        @DisplayName("PENDING token with an existing local user → hasAccount=true")
        void pendingTokenWhenAccountExists() {
            OrganizationInvitation inv = new OrganizationInvitation(org, "target@test.com", OrganizationRole.MEMBER, owner);
            when(invitationRepository.findByTokenHash(TokenAtRest.hash("tok-2"))).thenReturn(Optional.of(inv));
            when(userRepository.findByEmail("target@test.com")).thenReturn(Optional.of(targetUser));

            OrganizationMemberService.InvitationInfo info = service.getInvitationInfo("tok-2");

            assertThat(info.valid()).isTrue();
            assertThat(info.hasAccount()).isTrue();
        }

        @Test
        @DisplayName("null / blank token → invalid, no repository lookup")
        void nullOrBlankTokenIsInvalid() {
            assertThat(service.getInvitationInfo(null).valid()).isFalse();
            assertThat(service.getInvitationInfo("   ").valid()).isFalse();
            verify(invitationRepository, never()).findByTokenHash(any());
        }

        @Test
        @DisplayName("unknown token → invalid (leaks nothing)")
        void unknownTokenIsInvalid() {
            when(invitationRepository.findByTokenHash(TokenAtRest.hash("nope"))).thenReturn(Optional.empty());

            OrganizationMemberService.InvitationInfo info = service.getInvitationInfo("nope");

            assertThat(info.valid()).isFalse();
            assertThat(info.email()).isNull();
        }

        @Test
        @DisplayName("expired PENDING token → invalid, and is NOT mutated on this read-only path")
        void expiredTokenIsInvalidAndNotMutated() {
            OrganizationInvitation inv = new OrganizationInvitation(org, "stale@test.com", OrganizationRole.MEMBER, owner);
            inv.setExpiresAt(java.time.LocalDateTime.now().minusDays(1));
            when(invitationRepository.findByTokenHash(TokenAtRest.hash("expired"))).thenReturn(Optional.of(inv));

            OrganizationMemberService.InvitationInfo info = service.getInvitationInfo("expired");

            assertThat(info.valid()).isFalse();
            // Read-only: the unauthenticated info lookup must not flip the status.
            verify(invitationRepository, never()).save(any(OrganizationInvitation.class));
        }

        @Test
        @DisplayName("non-PENDING (ACCEPTED / CANCELLED) token → invalid")
        void nonPendingTokenIsInvalid() {
            OrganizationInvitation accepted = new OrganizationInvitation(org, "a@test.com", OrganizationRole.MEMBER, owner);
            accepted.setStatus(InvitationStatus.ACCEPTED);
            when(invitationRepository.findByTokenHash(TokenAtRest.hash("acc"))).thenReturn(Optional.of(accepted));

            assertThat(service.getInvitationInfo("acc").valid()).isFalse();
        }
    }
}
