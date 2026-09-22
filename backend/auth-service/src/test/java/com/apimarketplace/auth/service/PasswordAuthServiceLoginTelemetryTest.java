package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuthEventRecorder;
import com.apimarketplace.auth.bootstrap.FirstAdminBootstrap;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.RefreshTokenRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.security.JwtTokenProvider;
import com.apimarketplace.auth.validation.UsernameValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Self-hosted sign-ins leave the same trail as cloud ones.
 *
 * <p>They did not. This service held an injected {@code AuditLogger} it never once used,
 * and talked to {@code AuthMetrics} directly, so an embedded email+password sign-in moved
 * a counter and wrote NO audit row and NO product analytics, while the cloud path wrote
 * all three. A security review of a self-hosted install therefore had nothing to read,
 * and the difference was invisible because the metric everybody looks at did move.
 *
 * <p>Routing through {@link AuthEventRecorder} is what keeps metric, audit row and
 * analytics from drifting apart again: there is one call, so there is nothing to forget.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordAuthService - embedded sign-in telemetry")
class PasswordAuthServiceLoginTelemetryTest {

    private static final String EMAIL = "self@hosted.test";
    private static final String PASSWORD = "correct horse battery staple";

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private OrganizationService organizationService;
    @Mock private FirstAdminBootstrap firstAdminBootstrap;
    @Mock private AuthEventRecorder authEventRecorder;
    @Captor private ArgumentCaptor<User> userCaptor;

    private PasswordAuthService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordAuthService(userRepository, refreshTokenRepository, jwtTokenProvider);
        ReflectionTestUtils.setField(service, "organizationService", organizationService);
        ReflectionTestUtils.setField(service, "firstAdminBootstrap", firstAdminBootstrap);
        ReflectionTestUtils.setField(service, "usernameValidator", new UsernameValidator(userRepository));
        ReflectionTestUtils.setField(service, "authEventRecorder", authEventRecorder);

        user = new User();
        user.setId(7L);
        user.setEmail(EMAIL);
        user.setUsername("selfhoster");
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        user.setPasswordHash(new BCryptPasswordEncoder(12).encode(PASSWORD));

        // Assign an id on save, as the database does: the recorder is handed user.getId(),
        // and a null there would quietly become an audit row with no subject.
        lenient().when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(inv -> {
                    User saved = inv.getArgument(0);
                    if (saved.getId() == null) saved.setId(101L);
                    return saved;
                });
    }

    @Test
    @DisplayName("a successful sign-in records metric, audit row and analytics in one call")
    void successGoesThroughTheRecorder() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        service.login(EMAIL, PASSWORD);

        verify(authEventRecorder, times(1)).recordLoginSuccess(7L, "local");
    }

    @Test
    @DisplayName("a wrong password is audited, not only counted")
    void wrongPasswordIsAudited() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(EMAIL, "wrong"))
                .isInstanceOf(PasswordAuthService.AuthenticationException.class);

        verify(authEventRecorder).recordLoginFailure("local", "invalid_credentials");
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
    }

    @Test
    @DisplayName("an unknown address reports the same reason, so it leaks no account existence")
    void unknownAccountLooksIdentical() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD))
                .isInstanceOf(PasswordAuthService.AuthenticationException.class);

        // Same reason code as a wrong password on a real account: the audit trail must not
        // become an account-enumeration oracle for whoever can read it.
        verify(authEventRecorder).recordLoginFailure("local", "invalid_credentials");
    }

    @Test
    @DisplayName("a disabled account is audited with its own reason")
    void disabledAccountIsAudited() {
        user.setEnabled(false);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD))
                .isInstanceOf(PasswordAuthService.AuthenticationException.class);

        verify(authEventRecorder).recordLoginFailure("local", "disabled");
    }

    @Test
    @DisplayName("the rate limiter writes the audit event that was declared and never used")
    void rateLimitIsAudited() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        // Five refused attempts arm the limiter; the sixth is what it stops.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login(EMAIL, "wrong"))
                    .isInstanceOf(PasswordAuthService.AuthenticationException.class);
        }
        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD))
                .isInstanceOf(PasswordAuthService.AuthenticationException.class);

        // AuditEventTypes.LOGIN_RATE_LIMITED existed and nothing wrote it, so repeated
        // refusals against one account, the thing a security review looks for first, left
        // no trail at all.
        verify(authEventRecorder).recordLoginRateLimited("local");
        verify(authEventRecorder, never()).recordLoginSuccess(anyLong(), anyString());
    }

    @Test
    @DisplayName("a sign-in advances the authentication instant, not only the last-seen marker")
    void signInAdvancesTheAuthenticationInstant() {
        LocalDateTime seededAtUpgrade = LocalDateTime.of(2026, 1, 1, 0, 0);
        user.setLastAuthenticatedAt(seededAtUpgrade);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        service.login(EMAIL, PASSWORD);

        // Cloud infers this instant from a token auth_time claim; here it IS now. Leaving
        // the column alone would freeze it on self-hosted at whatever V495 seeded on upgrade
        // day, under a column comment promising it only ever moves forward.
        assertThat(user.getLastAuthenticatedAt()).isAfter(seededAtUpgrade);
        assertThat(user.getLastAuthenticatedAt()).isEqualTo(user.getLastLoginAt());
    }

    @Test
    @DisplayName("registration records a signup AND the first login, like the cloud path")
    void registrationRecordsSignupAndLogin() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(firstAdminBootstrap.claimFirstAdminSlot()).thenReturn(false);

        service.register("new@hosted.test", PASSWORD, "New", "Person");

        // Registration hands back a token pair straight away, so it IS a sign-in. Emitting
        // only the signup would leave the funnel with more registrations than logins.
        verify(authEventRecorder).recordSignupAndLogin(101L, "local", false);
    }

    @Test
    @DisplayName("registration stamps the authentication instant, not only the last-seen marker")
    void registrationStampsTheAuthenticationInstant() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(firstAdminBootstrap.claimFirstAdminSlot()).thenReturn(false);

        service.register("new@hosted.test", PASSWORD, "New", "Person");

        // Leaving it NULL would make this account's first ordinary resolve eligible to count
        // a phantom login, which is the spike the migration's seeding exists to prevent for
        // everyone else. Deleting the write leaves every other test in this class green.
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getLastAuthenticatedAt()).isNotNull();
        assertThat(userCaptor.getValue().getLastAuthenticatedAt())
                .isEqualTo(userCaptor.getValue().getLastLoginAt());
    }

    @Test
    @DisplayName("a flood of refused attempts writes ONE audit row, not one per request")
    void lockoutIsAuditedOncePerWindow() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login(EMAIL, "wrong"))
                    .isInstanceOf(PasswordAuthService.AuthenticationException.class);
        }
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> service.login(EMAIL, PASSWORD))
                    .isInstanceOf(PasswordAuthService.AuthenticationException.class);
        }

        // After the limiter arms, the attacker sets the rate. One HMAC-signed audit row per
        // refused request would be write amplification on the path meant to CONTAIN abuse,
        // and it would drown the trail a security review reads.
        verify(authEventRecorder, times(1)).recordLoginRateLimited("local");
        // The counters still see every refusal: they are cheap, and the ratio alerts need them.
        verify(authEventRecorder, times(19)).recordLoginFailure("local", "rate_limited");
    }
}
