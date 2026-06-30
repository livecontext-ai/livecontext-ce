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

    private PasswordAuthService service;

    @BeforeEach
    void setUp() {
        service = new PasswordAuthService(userRepository, refreshTokenRepository, jwtTokenProvider);
        // Inject @Autowired(required=false) fields via reflection (mirrors production wiring).
        ReflectionTestUtils.setField(service, "organizationService", organizationService);
        ReflectionTestUtils.setField(service, "firstAdminBootstrap", firstAdminBootstrap);
        ReflectionTestUtils.setField(service, "usernameValidator", new UsernameValidator(userRepository));

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
}
