package com.apimarketplace.auth.service;

import com.apimarketplace.auth.bootstrap.FirstAdminBootstrap;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.RefreshTokenRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.security.JwtTokenProvider;
import com.apimarketplace.auth.validation.UsernameValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordAuthService")
class PasswordAuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private OrganizationService organizationService;

    @Mock
    private FirstAdminBootstrap firstAdminBootstrap;

    @Mock
    private com.apimarketplace.auth.repository.PasswordResetTokenRepository passwordResetTokenRepository;

    private PasswordAuthService service;

    @BeforeEach
    void setUp() {
        service = new PasswordAuthService(userRepository, refreshTokenRepository, jwtTokenProvider);
        // Inject @Autowired(required=false) fields via reflection (mirrors production wiring).
        ReflectionTestUtils.setField(service, "organizationService", organizationService);
        ReflectionTestUtils.setField(service, "firstAdminBootstrap", firstAdminBootstrap);
        ReflectionTestUtils.setField(service, "usernameValidator", new UsernameValidator(userRepository));
        ReflectionTestUtils.setField(service, "passwordResetTokenRepository", passwordResetTokenRepository);

        // Default: no auto-admin. Tests that exercise the first-admin path override
        // via `when(firstAdminBootstrap.claimFirstAdminSlot()).thenReturn(true)`.
        lenient().when(firstAdminBootstrap.claimFirstAdminSlot()).thenReturn(false);

        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(1L);
            return u;
        });
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("Should set normalized unique username from firstName + lastName")
        void shouldSetUsernameFromFirstAndLastName() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);

            User result = service.register("test@example.com", "password123", "John", "Doe");

            assertThat(result.getUsername()).isEqualTo("john_doe");
        }

        @Test
        @DisplayName("Should set normalized username from firstName only when lastName is null")
        void shouldSetUsernameFromFirstNameOnly() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);

            User result = service.register("test@example.com", "password123", "Alice", null);

            assertThat(result.getUsername()).isEqualTo("alice");
        }

        @Test
        @DisplayName("Should set normalized username from lastName only when firstName is blank")
        void shouldSetUsernameFromLastNameOnly() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);

            User result = service.register("test@example.com", "password123", "  ", "Smith");

            assertThat(result.getUsername()).isEqualTo("smith");
        }

        @Test
        @DisplayName("Should fallback to email local part when both names are blank")
        void shouldFallbackToEmailLocalPart() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);

            User result = service.register("alice@example.com", "password123", null, null);

            assertThat(result.getUsername()).isEqualTo("alice");
        }

        @Test
        @DisplayName("Duplicate display name should not reuse an existing username during registration")
        void duplicateDisplayNameDoesNotReuseUsernameDuringRegistration() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(userRepository.existsByUsername("e2e_tester")).thenReturn(true);
            when(userRepository.existsByUsername("e2e_tester_1")).thenReturn(false);

            User result = service.register("unique@example.com", "password123", "E2E", "Tester");

            assertThat(result.getUsername()).isEqualTo("e2e_tester_1");
        }

        @Test
        @DisplayName("Should create personal organization after registration")
        void shouldCreatePersonalOrganization() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);

            service.register("test@example.com", "password123", "John", "Doe");

            verify(organizationService).createPersonalOrganization(any(User.class), eq("John Doe"));
        }

        @Test
        @DisplayName("Should not fail registration if org creation fails")
        void shouldNotFailIfOrgCreationFails() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            doThrow(new RuntimeException("DB error"))
                    .when(organizationService).createPersonalOrganization(any(), anyString());

            User result = service.register("test@example.com", "password123", "John", "Doe");

            assertThat(result).isNotNull();
            assertThat(result.getUsername()).isEqualTo("john_doe");
        }

        @Test
        @DisplayName("Should work when OrganizationService is not available")
        void shouldWorkWithoutOrganizationService() {
            // Simulate @Autowired(required=false) - null
            ReflectionTestUtils.setField(service, "organizationService", null);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);

            User result = service.register("test@example.com", "password123", "John", "Doe");

            assertThat(result).isNotNull();
            assertThat(result.getUsername()).isEqualTo("john_doe");
        }

        @Test
        @DisplayName("Should assign ADMIN role when bootstrap helper claims the slot (CE first user)")
        void shouldAssignAdminToFirstUser() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(firstAdminBootstrap.claimFirstAdminSlot()).thenReturn(true);

            User result = service.register("admin@example.com", "password123", "Admin", "User");

            assertThat(result.getRoles()).contains("ADMIN", "USER");
        }

        @Test
        @DisplayName("Should assign USER role only when bootstrap helper declines (Cloud or post-bootstrap CE)")
        void shouldAssignUserToSubsequentUsers() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            // Helper default in setUp returns false - pinning the I3 invariant:
            // post-bootstrap CE (or Cloud) never promotes regardless of user-count.

            User result = service.register("user@example.com", "password123", "Regular", "User");

            assertThat(result.getRoles()).contains("USER");
            assertThat(result.getRoles()).doesNotContain("ADMIN");
        }

        @Test
        @DisplayName("Should NOT call userRepository.count() directly - gate flows through FirstAdminBootstrap")
        void shouldDelegateFirstUserDecisionToBootstrapHelper() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);

            service.register("u@example.com", "password123", "U", "ser");

            verify(firstAdminBootstrap).claimFirstAdminSlot();
            verify(userRepository, never()).count();
        }

        @Test
        @DisplayName("Should reject duplicate email")
        void shouldRejectDuplicateEmail() {
            when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

            assertThatThrownBy(() -> service.register("taken@example.com", "password123", "John", "Doe"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email already registered");
        }

        @Test
        @DisplayName("Should reject short password")
        void shouldRejectShortPassword() {
            assertThatThrownBy(() -> service.register("test@example.com", "short", "John", "Doe"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 8 characters");
        }

        @Test
        @DisplayName("Should reject blank email")
        void shouldRejectBlankEmail() {
            assertThatThrownBy(() -> service.register("  ", "password123", "John", "Doe"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email is required");
        }

        @Test
        @DisplayName("Should set correct auth provider and providerId")
        void shouldSetCorrectAuthProviderAndProviderId() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);

            User result = service.register("test@example.com", "password123", "John", "Doe");

            assertThat(result.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
            assertThat(result.getProviderId()).isEqualTo("local:test@example.com");
        }

        @Test
        @DisplayName("CE embedded registration should mark local accounts email verified")
        void ceEmbeddedRegistrationMarksLocalAccountsEmailVerified() {
            when(userRepository.existsByEmail(anyString())).thenReturn(false);

            User result = service.register("test@example.com", "password123", "John", "Doe");

            assertThat(result.isEmailVerified()).isTrue();
        }

        @Test
        @DisplayName("Should trim and lowercase email")
        void shouldTrimAndLowercaseEmail() {
            when(userRepository.existsByEmail("test@example.com")).thenReturn(false);

            User result = service.register("  Test@Example.COM  ", "password123", "John", "Doe");

            assertThat(result.getEmail()).isEqualTo("test@example.com");
        }
    }

    /**
     * The minimum length, pinned as a VALUE and across all three writers.
     *
     * <p>Neither was pinned before, and both gaps were measured: setting
     * MIN_PASSWORD_LENGTH to 4 left 56 tests green, because every assertion
     * derived its boundary from the constant under test
     * ({@code "x".repeat(MIN_PASSWORD_LENGTH - 1)}) and the one literal
     * assertion exercised register, which carried its own hardcoded 8.
     */
    @Nested
    @DisplayName("the one strength rule")
    class TheOneStrengthRule {

        @Test
        @DisplayName("is 8 characters, a number that cannot be derived from itself")
        void theMinimumIsEight() {
            // Deliberately a literal. Asserting against the constant would pass
            // for any value, and this number is mirrored by hand in
            // frontend/app/[locale]/reset-password/page.tsx, which checks it
            // before spending a single-use link. Changing it here is a decision
            // that has to be taken on the frontend too, so it should not be
            // possible to do quietly.
            assertThat(PasswordAuthService.MIN_PASSWORD_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("register refuses a password one character short, on the shared rule and not "
                + "on a literal of its own")
        void registerUsesTheSharedRule() {
            String tooShort = "x".repeat(PasswordAuthService.MIN_PASSWORD_LENGTH - 1);

            assertThatThrownBy(() -> service.register("new@example.com", tooShort, "Ada", "Lovelace"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(PasswordAuthService.MIN_PASSWORD_LENGTH));

            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("all THREE writers refuse at exactly the same boundary, so the rule cannot drift "
                + "between signing up, changing and resetting")
        void everyWriterSharesTheBoundary() {
            String tooShort = "x".repeat(PasswordAuthService.MIN_PASSWORD_LENGTH - 1);
            String justLongEnough = "x".repeat(PasswordAuthService.MIN_PASSWORD_LENGTH);
            User existing = new User();
            existing.setId(7L);
            existing.setEmail("owner@example.com");
            existing.setPasswordHash(
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                            .encode("current-password"));
            lenient().when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(existing));
            lenient().when(userRepository.existsByEmail(anyString())).thenReturn(false);

            assertThatThrownBy(() -> service.register("a@example.com", tooShort, "A", "B"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.changePassword(7L, "current-password", tooShort))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.resetPasswordTo(7L, tooShort))
                    .isInstanceOf(IllegalArgumentException.class);

            // And the boundary itself is accepted, so the rule is "shorter than",
            // not "shorter than or equal to", in all three.
            service.validateNewPassword(justLongEnough);
            service.resetPasswordTo(7L, justLongEnough);
        }
    }

    /**
     * The write side of the reset-by-e-mail flow.
     *
     * <p>These were missing, and their absence was not visible: PasswordResetService
     * mocks this class, so deleting the revoke-every-session line or the strength
     * check left the whole reset test suite green while the guarantee its javadoc
     * calls load-bearing was gone.
     */
    @Nested
    @DisplayName("resetPasswordTo (password reset by e-mail, no current password)")
    class ResetPasswordTo {

        private User existing() {
            User user = new User();
            user.setId(7L);
            user.setEmail("owner@example.com");
            user.setPasswordHash("$2a$10$theOldHashThatMustNotSurvive");
            return user;
        }

        @Test
        @DisplayName("writes a hash of the NEW password, and does not keep the old one")
        void writesTheNewHash() {
            User user = existing();
            String oldHash = user.getPasswordHash();
            when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(user));

            service.resetPasswordTo(7L, "a-good-password");

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            String written = saved.getValue().getPasswordHash();
            assertThat(written).isNotEqualTo(oldHash);
            // Hashed, never stored in the clear, and it really is THIS password.
            assertThat(written).isNotEqualTo("a-good-password").startsWith("$2");
            assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                    .matches("a-good-password", written)).isTrue();
        }

        @Test
        @DisplayName("revokes EVERY refresh token: someone resetting a password is often doing it "
                + "because a live session is not theirs any more")
        void revokesEverySession() {
            when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(existing()));

            service.resetPasswordTo(7L, "a-good-password");

            // Delete the revokeAllByUserId call and this is the only test that notices.
            verify(refreshTokenRepository).revokeAllByUserId(eq(7L), any(java.time.LocalDateTime.class));
        }

        @Test
        @DisplayName("changing a password BURNS any pending reset link, so one minted just before "
                + "cannot still open the account after")
        void changePasswordBurnsPendingResetLinks() {
            User user = existing();
            user.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                    .encode("current-password"));
            when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(user));

            service.changePassword(7L, "current-password", "a-good-password");

            // The realistic case: someone notices a stranger in their mailbox and
            // changes their password from a live session. Without this the
            // stranger's link keeps working for the rest of the hour.
            verify(passwordResetTokenRepository)
                    .invalidateLiveTokens(eq(7L), any(java.time.LocalDateTime.class));
        }

        @Test
        @DisplayName("refuses a password below the shared minimum, and writes nothing at all")
        void refusesTooShort() {
            // The lookup happens before the rule, so the row has to be there for
            // the refusal under test to be the one about the password.
            when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(existing()));
            String tooShort = "x".repeat(PasswordAuthService.MIN_PASSWORD_LENGTH - 1);

            assertThatThrownBy(() -> service.resetPasswordTo(7L, tooShort))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(PasswordAuthService.MIN_PASSWORD_LENGTH));

            verify(userRepository, never()).save(any(User.class));
            verify(refreshTokenRepository, never()).revokeAllByUserId(any(), any());
        }

        @Test
        @DisplayName("refuses a null password rather than hashing one")
        void refusesNull() {
            when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(existing()));

            assertThatThrownBy(() -> service.resetPasswordTo(7L, null))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("refuses a SUSPENDED account, so a link minted before the suspension cannot "
                + "still rewrite its password")
        void refusesADisabledAccount() {
            User suspended = existing();
            suspended.setEnabled(false);
            when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(suspended));

            assertThatThrownBy(() -> service.resetPasswordTo(7L, "a-good-password"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(userRepository, never()).save(any(User.class));
            verify(refreshTokenRepository, never()).revokeAllByUserId(any(), any());
        }

        @Test
        @DisplayName("refuses a user id that no longer exists")
        void refusesUnknownUser() {
            when(userRepository.findById(404L)).thenReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> service.resetPasswordTo(404L, "a-good-password"))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(refreshTokenRepository, never()).revokeAllByUserId(any(), any());
        }

        @Test
        @DisplayName("validateNewPassword is the SAME rule, callable without writing, which is what "
                + "lets the reset flow check a password before it spends the token")
        void validateIsTheSameRuleWithoutAWrite() {
            String tooShort = "x".repeat(PasswordAuthService.MIN_PASSWORD_LENGTH - 1);

            assertThatThrownBy(() -> service.validateNewPassword(tooShort))
                    .isInstanceOf(IllegalArgumentException.class);
            service.validateNewPassword("x".repeat(PasswordAuthService.MIN_PASSWORD_LENGTH));

            verifyNoInteractions(userRepository, refreshTokenRepository);
        }
    }
}
