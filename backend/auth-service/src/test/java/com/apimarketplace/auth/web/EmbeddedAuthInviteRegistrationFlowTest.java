package com.apimarketplace.auth.web;

import com.apimarketplace.auth.ce.CeInstallStateService;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.InvitationStatus;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationInvitation;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationInvitationRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.RefreshTokenRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.security.JwtTokenProvider;
import com.apimarketplace.auth.service.GatewayCacheClient;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.OrganizationAuditService;
import com.apimarketplace.auth.service.OrganizationInvitationMailer;
import com.apimarketplace.auth.service.OrganizationMemberService;
import com.apimarketplace.auth.service.PasswordAuthService;
import com.apimarketplace.auth.service.PasswordResetService;
import com.apimarketplace.auth.validation.UsernameValidator;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.security.token.TokenAtRest;
import com.apimarketplace.common.web.AppEditionProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CE invite-by-link registration through the REAL {@link PasswordAuthService#register} and
 * the REAL {@link OrganizationMemberService#acceptInvitation}, only the repositories mocked.
 * Pins that the verified-email gate and the CE by-id narrowing leave this path working: the
 * account is created verified by registration itself and joins through the token.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddedAuthController - CE invite-link registration joins through the real services")
class EmbeddedAuthInviteRegistrationFlowTest {

    @BeforeAll
    static void installTokenAtRest() {
        TokenAtRest.install(new CredentialEncryptionService("test-password-123", "0123456789abcdef"));
    }

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationInvitationRepository invitationRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private OnboardingService onboardingService;
    @Mock private AppEditionProvider editionProvider;
    @Mock private CeInstallStateService installStateService;
    @Mock private PasswordResetService passwordResetService;
    @Mock private HttpServletRequest request;

    private EmbeddedAuthController controller;
    private OrganizationInvitation invitation;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        PasswordAuthService passwordAuthService = spy(
                new PasswordAuthService(userRepository, refreshTokenRepository, jwtTokenProvider));
        ReflectionTestUtils.setField(passwordAuthService, "usernameValidator", new UsernameValidator(userRepository));
        doReturn(new PasswordAuthService.TokenPair("access", "refresh", 900L, 86400L))
                .when(passwordAuthService).generateTokenPair(any(User.class), any(), any());

        OrganizationMemberService memberService = new OrganizationMemberService(
                memberRepository, invitationRepository, organizationRepository,
                userRepository, subscriptionRepository, onboardingService,
                new OrganizationAuditService(mock(com.apimarketplace.auth.repository.OrganizationAuditEventRepository.class)),
                mock(OrganizationInvitationMailer.class), editionProvider, mock(GatewayCacheClient.class),
                1000, 1000);
        ReflectionTestUtils.setField(memberService, "authMode", "embedded");

        controller = new EmbeddedAuthController(
                passwordAuthService, installStateService, memberService, passwordResetService);

        User owner = new User("owner", "owner@ce.test", AuthProvider.LOCAL, "local:owner@ce.test");
        owner.setId(1L);
        orgId = UUID.randomUUID();
        Organization org = new Organization("CE Team", "ce-team", false, owner);
        org.setId(orgId);
        Plan team = new Plan("TEAM", "Team", "Team plan");
        team.setMaxMembers(10);
        Subscription sub = new Subscription();
        sub.setPlan(team);
        sub.setStatus("active");
        lenient().when(subscriptionRepository.findActiveByUserId(1L)).thenReturn(Optional.of(sub));

        // The invitation exists BEFORE the account (the invite-by-link case).
        invitation = new OrganizationInvitation(org, "newbie@ce.test", OrganizationRole.MEMBER, owner);
        invitation.setId(UUID.randomUUID());
        invitation.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        when(invitationRepository.findByTokenHash(TokenAtRest.hash("invite-token"))).thenReturn(Optional.of(invitation));
        lenient().when(invitationRepository.save(any(OrganizationInvitation.class))).thenAnswer(a -> a.getArgument(0));

        lenient().when(userRepository.existsByEmail(anyString())).thenReturn(false);
        lenient().when(userRepository.save(any(User.class))).thenAnswer(a -> {
            User u = a.getArgument(0);
            if (u.getId() == null) {
                u.setId(42L);
            }
            return u;
        });
        lenient().when(userRepository.findById(42L)).thenAnswer(a -> Optional.ofNullable(savedUser));
        lenient().when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, 42L)).thenReturn(false);
        lenient().when(memberRepository.countByOrganization_Id(orgId)).thenReturn(1L);
    }

    private User savedUser;

    @Test
    @DisplayName("a valid token + matching email: registration creates a VERIFIED account and joins the org at the invited role")
    void inviteLinkRegistrationJoinsTheOrganization() {
        when(userRepository.save(any(User.class))).thenAnswer(a -> {
            User u = a.getArgument(0);
            if (u.getId() == null) {
                u.setId(42L);
            }
            savedUser = u;
            return u;
        });

        Map<String, String> body = new HashMap<>();
        body.put("email", "Newbie@CE.test");
        body.put("password", "a-strong-password");
        body.put("firstName", "New");
        body.put("lastName", "Bie");
        body.put("invitationToken", "invite-token");

        ResponseEntity<Map<String, Object>> response = controller.register(body, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Closed door never consulted: the matching token is the bypass.
        verify(installStateService, never()).isRegistrationOpen();
        assertThat(savedUser.getEmail()).isEqualTo("newbie@ce.test");
        assertThat(savedUser.isEmailVerified()).isTrue();

        ArgumentCaptor<OrganizationMember> member = ArgumentCaptor.forClass(OrganizationMember.class);
        verify(memberRepository).save(member.capture());
        assertThat(member.getValue().getUser().getId()).isEqualTo(42L);
        assertThat(member.getValue().getRole()).isEqualTo(OrganizationRole.MEMBER);
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
    }
}
