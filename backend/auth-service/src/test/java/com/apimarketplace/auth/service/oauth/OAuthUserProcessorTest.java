package com.apimarketplace.auth.service.oauth;

import com.apimarketplace.auth.bootstrap.FirstAdminBootstrap;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuthUserProcessor Tests")
class OAuthUserProcessorTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    @Mock
    private FirstAdminBootstrap firstAdminBootstrap;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    private OAuthUserProcessor processor;

    @BeforeEach
    void setUp() {
        // Default behavior: never auto-promote. Tests that exercise the first-admin
        // path override via `when(firstAdminBootstrap.claimFirstAdminSlot()).thenReturn(true)`.
        org.mockito.Mockito.lenient().when(firstAdminBootstrap.claimFirstAdminSlot()).thenReturn(false);
        processor = new OAuthUserProcessor(userRepository, userService, firstAdminBootstrap);
    }

    @Nested
    @DisplayName("upsertUser() - Create new user")
    class CreateNewUserTests {

        @Test
        @DisplayName("should create new user when not found by providerId or email")
        void shouldCreateNewUserWhenNotFound() {
            OAuthProfile profile = createProfile("google", "123", "new@example.com");
            when(userRepository.findByProviderId("123")).thenReturn(Optional.empty());
            when(userRepository.findByEmailAndProvider("new@example.com", AuthProvider.GOOGLE)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(1L);
                return user;
            });

            User result = processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();

            assertThat(savedUser.getProviderId()).isEqualTo("123");
            assertThat(savedUser.getEmail()).isEqualTo("new@example.com");
            assertThat(savedUser.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
            assertThat(savedUser.isEnabled()).isTrue();
            assertThat(savedUser.getCreatedAt()).isNotNull();
            assertThat(savedUser.getLastLoginAt()).isNotNull();
        }

        @Test
        @DisplayName("CE first user (bootstrap claims slot) → roles include ADMIN")
        void ceFirstUserGetsAdminRole() {
            when(firstAdminBootstrap.claimFirstAdminSlot()).thenReturn(true);
            OAuthProfile profile = createProfile("google", "ce-first", "admin@ce.local");
            when(userRepository.findByProviderId("ce-first")).thenReturn(Optional.empty());
            when(userRepository.findByEmailAndProvider("admin@ce.local", AuthProvider.GOOGLE)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getRoles()).containsExactlyInAnyOrder("USER", "ADMIN");
        }

        @Test
        @DisplayName("Cloud OAuth user (bootstrap returns false) → roles only USER, even on empty users table (sev-10 prod escalation guard)")
        void cloudOauthUserNeverGetsAdmin() {
            // Default mock: firstAdminBootstrap returns false (Cloud edition or
            // bootstrapped CE). This pins the I1 invariant - DB state is NOT a
            // security boundary; Cloud must not promote regardless of count.
            OAuthProfile profile = createProfile("google", "cloud-user", "u@cloud.example");
            when(userRepository.findByProviderId("cloud-user")).thenReturn(Optional.empty());
            when(userRepository.findByEmailAndProvider("u@cloud.example", AuthProvider.GOOGLE)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getRoles()).containsExactly("USER");
            assertThat(userCaptor.getValue().getRoles()).doesNotContain("ADMIN");
        }

        @Test
        @DisplayName("CE bootstrapped + empty users table → no ADMIN (I3: install-state is the gate, not row count)")
        void ceBootstrappedWithEmptyUsersDoesNotPromote() {
            // Helper returns false in this scenario (bootstrap state already true even if
            // count==0 - e.g. admin was deleted manually). Regression for invariant I3.
            when(firstAdminBootstrap.claimFirstAdminSlot()).thenReturn(false);
            OAuthProfile profile = createProfile("google", "after-bootstrap", "x@ce.local");
            when(userRepository.findByProviderId("after-bootstrap")).thenReturn(Optional.empty());
            when(userRepository.findByEmailAndProvider("x@ce.local", AuthProvider.GOOGLE)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getRoles()).containsExactly("USER");
        }

        @Test
        @DisplayName("should set all profile fields on new user")
        void shouldSetAllProfileFieldsOnNewUser() {
            OAuthProfile profile = OAuthProfile.builder()
                    .provider("google")
                    .providerId("456")
                    .email("full@example.com")
                    .emailVerified(true)
                    .firstName("John")
                    .lastName("Doe")
                    .displayName("John Doe")
                    .avatarUrl("https://example.com/avatar.jpg")
                    .build();

            when(userRepository.findByProviderId("456")).thenReturn(Optional.empty());
            when(userRepository.findByEmailAndProvider("full@example.com", AuthProvider.GOOGLE)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();

            assertThat(savedUser.getEmail()).isEqualTo("full@example.com");
            assertThat(savedUser.isEmailVerified()).isTrue();
            assertThat(savedUser.getFirstName()).isEqualTo("John");
            assertThat(savedUser.getLastName()).isEqualTo("Doe");
            assertThat(savedUser.getAvatarUrl()).isEqualTo("https://example.com/avatar.jpg");
        }

        @Test
        @DisplayName("should extract name from displayName when firstName is null")
        void shouldExtractNameFromDisplayName() {
            OAuthProfile profile = OAuthProfile.builder()
                    .provider("github")
                    .providerId("789")
                    .displayName("Jane Smith")
                    .build();

            when(userRepository.findByProviderId("789")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();

            assertThat(savedUser.getFirstName()).isEqualTo("Jane");
            assertThat(savedUser.getLastName()).isEqualTo("Smith");
        }
    }

    @Nested
    @DisplayName("upsertUser() - Update existing user")
    class UpdateExistingUserTests {

        @Test
        @DisplayName("should find existing user by providerId")
        void shouldFindExistingUserByProviderId() {
            OAuthProfile profile = createProfile("google", "existing-id", "existing@example.com");
            User existingUser = createExistingUser(1L, "existing-id");

            when(userRepository.findByProviderId("existing-id")).thenReturn(Optional.of(existingUser));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = processor.upsertUser(profile);

            assertThat(result.getId()).isEqualTo(1L);
            verify(userRepository).findByProviderId("existing-id");
            verify(userRepository, never()).findByEmail(anyString());
        }

        @Test
        @DisplayName("links a SAME-provider account by email when its providerId changed (provider-aware)")
        void shouldLinkSameProviderAccountByEmail() {
            OAuthProfile profile = createProfile("google", "new-provider-id", "existing@example.com");
            User existingUser = createExistingUser(2L, "old-provider-id");
            existingUser.setEmail("existing@example.com");
            existingUser.setAuthProvider(AuthProvider.GOOGLE);

            when(userRepository.findByProviderId("new-provider-id")).thenReturn(Optional.empty());
            when(userRepository.findByEmailAndProvider("existing@example.com", AuthProvider.GOOGLE))
                    .thenReturn(Optional.of(existingUser));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = processor.upsertUser(profile);

            assertThat(result.getId()).isEqualTo(2L);
            // Provider ID re-pointed onto the new sub for the SAME provider.
            assertThat(result.getProviderId()).isEqualTo("new-provider-id");
        }

        @Test
        @DisplayName("does NOT link across providers by email - a different provider yields a NEW account, no takeover")
        void shouldNotLinkAcrossProvidersByEmail() {
            // A GitHub login arrives for an email owned by a Google account.
            OAuthProfile profile = createProfile("github", "gh-sub", "victim@example.com");

            when(userRepository.findByProviderId("gh-sub")).thenReturn(Optional.empty());
            // Provider-aware lookup for GITHUB finds nothing (the existing row is GOOGLE).
            when(userRepository.findByEmailAndProvider("victim@example.com", AuthProvider.GITHUB))
                    .thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(99L);
                return u;
            });

            User result = processor.upsertUser(profile);

            // The Google account is NOT taken over: the lookup is provider-aware,
            // so the GitHub login does NOT resolve to the existing Google row - it
            // proceeds as a fresh GitHub identity (gh-sub), never the Google id.
            // (At the DB layer the global unique-email index is the backstop for
            // this dormant CE path; here we assert only the lookup behavior.)
            assertThat(result.getProviderId()).isEqualTo("gh-sub");
            assertThat(result.getAuthProvider()).isEqualTo(AuthProvider.GITHUB);
            // The provider-blind lookup must never be used.
            verify(userRepository, never()).findByEmail(anyString());
        }

        @Test
        @DisplayName("should update user fields on existing user")
        void shouldUpdateUserFieldsOnExistingUser() {
            OAuthProfile profile = OAuthProfile.builder()
                    .provider("google")
                    .providerId("update-id")
                    .email("updated@example.com")
                    .emailVerified(true)
                    .firstName("UpdatedFirst")
                    .lastName("UpdatedLast")
                    .avatarUrl("https://new-avatar.com/img.jpg")
                    .build();

            User existingUser = createExistingUser(3L, "update-id");
            existingUser.setFirstName("OldFirst");
            existingUser.setLastName("OldLast");

            when(userRepository.findByProviderId("update-id")).thenReturn(Optional.of(existingUser));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();

            assertThat(savedUser.getEmail()).isEqualTo("updated@example.com");
            assertThat(savedUser.isEmailVerified()).isTrue();
            assertThat(savedUser.getFirstName()).isEqualTo("UpdatedFirst");
            assertThat(savedUser.getLastName()).isEqualTo("UpdatedLast");
            assertThat(savedUser.getAvatarUrl()).isEqualTo("https://new-avatar.com/img.jpg");
        }

        @Test
        @DisplayName("should not overwrite storage UUID avatarUrl with provider HTTP URL on re-login")
        void shouldNotOverwriteStorageUuidWithProviderUrl() {
            OAuthProfile profile = OAuthProfile.builder()
                    .provider("google")
                    .providerId("relogin-id")
                    .email("relogin@example.com")
                    .avatarUrl("https://lh3.googleusercontent.com/a/new-photo")
                    .build();

            User existingUser = createExistingUser(10L, "relogin-id");
            // User previously uploaded an avatar - avatarUrl is a storage UUID
            existingUser.setAvatarUrl("550e8400-e29b-41d4-a716-446655440000");

            when(userRepository.findByProviderId("relogin-id")).thenReturn(Optional.of(existingUser));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();

            // The storage UUID must be preserved - NOT replaced by the provider URL
            assertThat(savedUser.getAvatarUrl()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        }

        @Test
        @DisplayName("should set provider URL when avatarUrl is null (new user or no avatar)")
        void shouldSetProviderUrlWhenAvatarUrlIsNull() {
            OAuthProfile profile = OAuthProfile.builder()
                    .provider("google")
                    .providerId("fresh-id")
                    .email("fresh@example.com")
                    .avatarUrl("https://lh3.googleusercontent.com/a/photo")
                    .build();

            User existingUser = createExistingUser(11L, "fresh-id");
            existingUser.setAvatarUrl(null);

            when(userRepository.findByProviderId("fresh-id")).thenReturn(Optional.of(existingUser));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getAvatarUrl()).isEqualTo("https://lh3.googleusercontent.com/a/photo");
        }

        @Test
        @DisplayName("should overwrite HTTP URL avatarUrl with new provider URL on re-login")
        void shouldOverwriteHttpUrlWithNewProviderUrl() {
            OAuthProfile profile = OAuthProfile.builder()
                    .provider("google")
                    .providerId("http-id")
                    .avatarUrl("https://lh3.googleusercontent.com/a/new-photo")
                    .build();

            User existingUser = createExistingUser(12L, "http-id");
            // avatarUrl is already an HTTP URL (not imported yet)
            existingUser.setAvatarUrl("https://lh3.googleusercontent.com/a/old-photo");

            when(userRepository.findByProviderId("http-id")).thenReturn(Optional.of(existingUser));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            // HTTP URLs are safe to overwrite with the newer provider URL
            assertThat(userCaptor.getValue().getAvatarUrl()).isEqualTo("https://lh3.googleusercontent.com/a/new-photo");
        }

        @Test
        @DisplayName("should update lastLoginAt on existing user")
        void shouldUpdateLastLoginAtOnExistingUser() {
            OAuthProfile profile = createProfile("google", "login-test", "login@example.com");
            User existingUser = createExistingUser(4L, "login-test");
            existingUser.setLastLoginAt(null);

            when(userRepository.findByProviderId("login-test")).thenReturn(Optional.of(existingUser));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getLastLoginAt()).isNotNull();
        }

        @Test
        @DisplayName("should not override existing firstName with null")
        void shouldNotOverrideExistingFirstNameWithNull() {
            OAuthProfile profile = OAuthProfile.builder()
                    .provider("google")
                    .providerId("no-override-id")
                    .firstName(null)
                    .build();

            User existingUser = createExistingUser(5L, "no-override-id");
            existingUser.setFirstName("KeepThis");

            when(userRepository.findByProviderId("no-override-id")).thenReturn(Optional.of(existingUser));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getFirstName()).isEqualTo("KeepThis");
        }
    }

    @Nested
    @DisplayName("upsertUser() - Auth provider resolution")
    class AuthProviderResolutionTests {

        @Test
        @DisplayName("should set GOOGLE provider for google")
        void shouldSetGoogleProvider() {
            OAuthProfile profile = createProfile("google", "g123", null);
            when(userRepository.findByProviderId("g123")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        }

        @Test
        @DisplayName("should set GITHUB provider for github")
        void shouldSetGithubProvider() {
            OAuthProfile profile = createProfile("github", "gh123", null);
            when(userRepository.findByProviderId("gh123")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getAuthProvider()).isEqualTo(AuthProvider.GITHUB);
        }

        @Test
        @DisplayName("should set KEYCLOAK provider for unknown provider")
        void shouldSetKeycloakProviderForUnknown() {
            OAuthProfile profile = createProfile("unknown", "u123", null);
            when(userRepository.findByProviderId("u123")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getAuthProvider()).isEqualTo(AuthProvider.KEYCLOAK);
        }

        @Test
        @DisplayName("should handle case-insensitive provider names")
        void shouldHandleCaseInsensitiveProviderNames() {
            OAuthProfile profile = createProfile("GOOGLE", "case123", null);
            when(userRepository.findByProviderId("case123")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            processor.upsertUser(profile);

            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        }
    }

    // Helper methods

    private OAuthProfile createProfile(String provider, String providerId, String email) {
        return OAuthProfile.builder()
                .provider(provider)
                .providerId(providerId)
                .email(email)
                .emailVerified(email != null)
                .build();
    }

    private User createExistingUser(Long id, String providerId) {
        User user = new User();
        user.setId(id);
        user.setProviderId(providerId);
        user.setEnabled(true);
        return user;
    }
}
