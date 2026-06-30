package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.domain.UserProfileEntity;
import com.apimarketplace.auth.dto.AvatarInfo;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.dto.UserProfile;
import com.apimarketplace.auth.dto.UserProfileUpdateRequest;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserProfileRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.validation.AgeValidator;
import com.apimarketplace.auth.validation.UsernameValidator;
import com.apimarketplace.common.storage.domain.StorageEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Tests")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserOnboardingRepository onboardingRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private UsernameValidator usernameValidator;

    @Mock
    private AgeValidator ageValidator;

    @Mock
    private com.apimarketplace.common.storage.service.StorageService storageService;

    @Mock
    private AccountDeactivationMailer deactivationMailer;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, onboardingRepository, userProfileRepository, usernameValidator, ageValidator, storageService, deactivationMailer);
    }

    @Nested
    @DisplayName("findByUsername() method")
    class FindByUsernameTests {

        @Test
        @DisplayName("should return user when found")
        void shouldReturnUserWhenFound() {
            User user = createUser(1L, "testuser");
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

            Optional<User> result = userService.findByUsername("testuser");

            assertThat(result).isPresent();
            assertThat(result.get().getUsername()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("should return empty when user not found")
        void shouldReturnEmptyWhenNotFound() {
            when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

            Optional<User> result = userService.findByUsername("unknown");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findById() method")
    class FindByIdTests {

        @Test
        @DisplayName("should return user when found")
        void shouldReturnUserWhenFound() {
            User user = createUser(1L, "testuser");
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            Optional<User> result = userService.findById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should return empty when user not found")
        void shouldReturnEmptyWhenNotFound() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            Optional<User> result = userService.findById(999L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("save() method")
    class SaveTests {

        @Test
        @DisplayName("should save user successfully")
        void shouldSaveUserSuccessfully() {
            User user = createUser(null, "newuser");
            User savedUser = createUser(1L, "newuser");
            when(userRepository.save(user)).thenReturn(savedUser);

            User result = userService.save(user);

            assertThat(result.getId()).isEqualTo(1L);
            verify(userRepository).save(user);
        }
    }

    @Nested
    @DisplayName("updateProfile() method")
    class UpdateProfileTests {

        @Test
        @DisplayName("should update basic info")
        void shouldUpdateBasicInfo() {
            User user = createUser(1L, "existinguser");
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setFirstName("NewFirst");
            request.setLastName("NewLast");
            request.setAvatarUrl("https://new-avatar.com/img.jpg");

            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = userService.updateProfile(user, request);

            assertThat(result.getFirstName()).isEqualTo("NewFirst");
            assertThat(result.getLastName()).isEqualTo("NewLast");
            assertThat(result.getAvatarUrl()).isEqualTo("https://new-avatar.com/img.jpg");
        }

        @Test
        @DisplayName("should update username when valid")
        void shouldUpdateUsernameWhenValid() {
            User user = createUser(1L, "oldusername");
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setUsername("newusername");

            when(usernameValidator.validate("newusername", 1L)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = userService.updateProfile(user, request);

            assertThat(result.getUsername()).isEqualTo("newusername");
            verify(usernameValidator).validate("newusername", 1L);
        }

        @Test
        @DisplayName("should throw exception when username validation fails")
        void shouldThrowExceptionWhenUsernameValidationFails() {
            User user = createUser(1L, "oldusername");
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setUsername("invalid@user");

            when(usernameValidator.validate("invalid@user", 1L))
                    .thenReturn(Optional.of("Username can only contain letters, numbers, hyphens, and underscores"));

            assertThatThrownBy(() -> userService.updateProfile(user, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("letters, numbers, hyphens, and underscores");
        }

        @Test
        @DisplayName("should not update username when same as current")
        void shouldNotUpdateUsernameWhenSameAsCurrent() {
            User user = createUser(1L, "sameusername");
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setUsername("sameusername");

            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.updateProfile(user, request);

            verify(usernameValidator, never()).validate(anyString(), anyLong());
        }

        @Test
        @DisplayName("should not update username when null")
        void shouldNotUpdateUsernameWhenNull() {
            User user = createUser(1L, "keepusername");
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setUsername(null);

            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = userService.updateProfile(user, request);

            assertThat(result.getUsername()).isEqualTo("keepusername");
            verify(usernameValidator, never()).validate(anyString(), anyLong());
        }

        @Test
        @DisplayName("should update age when valid")
        void shouldUpdateAgeWhenValid() {
            User user = createUser(1L, "user");
            LocalDateTime birthDate = LocalDateTime.now().minusYears(25);
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setAge(birthDate);

            when(ageValidator.validate(birthDate)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = userService.updateProfile(user, request);

            assertThat(result.getAge()).isEqualTo(birthDate);
            verify(ageValidator).validate(birthDate);
        }

        @Test
        @DisplayName("should throw exception when age validation fails")
        void shouldThrowExceptionWhenAgeValidationFails() {
            User user = createUser(1L, "user");
            LocalDateTime birthDate = LocalDateTime.now().minusYears(15);
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setAge(birthDate);

            when(ageValidator.validate(birthDate))
                    .thenReturn(Optional.of("Sorry, you need to be at least 18 years old"));

            assertThatThrownBy(() -> userService.updateProfile(user, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("18 years old");
        }

        @Test
        @DisplayName("should update Keycloak data")
        void shouldUpdateKeycloakData() {
            User user = createUser(1L, "user");
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setEmail("new@email.com");
            request.setPicture("https://new-picture.com/img.jpg");
            request.setGivenName("OidcFirst");
            request.setFamilyName("OidcLast");
            request.setEmailVerified(true);

            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = userService.updateProfile(user, request);

            assertThat(result.getEmail()).isEqualTo("new@email.com");
            assertThat(result.getAvatarUrl()).isEqualTo("https://new-picture.com/img.jpg");
            assertThat(result.getFirstName()).isEqualTo("OidcFirst");
            assertThat(result.getLastName()).isEqualTo("OidcLast");
            assertThat(result.isEmailVerified()).isTrue();
        }

        @Test
        @DisplayName("should not override with empty strings")
        void shouldNotOverrideWithEmptyStrings() {
            User user = createUser(1L, "user");
            user.setEmail("existing@email.com");
            user.setFirstName("ExistingFirst");

            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setEmail("   ");
            request.setGivenName("   ");

            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = userService.updateProfile(user, request);

            assertThat(result.getEmail()).isEqualTo("existing@email.com");
            assertThat(result.getFirstName()).isEqualTo("ExistingFirst");
        }
    }

    @Nested
    @DisplayName("deactivateUser() method")
    class DeactivateUserTests {

        @Test
        @DisplayName("should deactivate user")
        void shouldDeactivateUser() {
            User user = createUser(1L, "activeuser");
            user.setEnabled(true);

            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = userService.deactivateUser(user);

            assertThat(result.isEnabled()).isFalse();
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("mapToUserProfile() method")
    class MapToUserProfileTests {

        @Test
        @DisplayName("should map user to UserProfile with storage-backed avatar")
        void shouldMapUserToUserProfileCorrectly() {
            UUID avatarStorageId = UUID.randomUUID();
            User user = createUser(1L, "mappeduser");
            user.setEmail("mapped@email.com");
            user.setFirstName("Mapped");
            user.setLastName("User");
            user.setAvatarUrl(avatarStorageId.toString());
            user.setAuthProvider(AuthProvider.GOOGLE);
            user.setEmailVerified(true);
            user.setEnabled(true);
            user.setCreatedAt(LocalDateTime.of(2023, 1, 1, 0, 0));
            user.setLastLoginAt(LocalDateTime.of(2024, 6, 15, 10, 30));
            user.setAge(LocalDateTime.of(1990, 5, 15, 0, 0));

            UserProfile result = userService.mapToUserProfile(user);

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getUsername()).isEqualTo("mappeduser");
            assertThat(result.getEmail()).isEqualTo("mapped@email.com");
            assertThat(result.getFirstName()).isEqualTo("Mapped");
            assertThat(result.getLastName()).isEqualTo("User");
            assertThat(result.getAvatarUrl()).isEqualTo("/api/users/1/avatar");
            assertThat(result.getAuthProvider()).isEqualTo("google");
            assertThat(result.isEmailVerified()).isTrue();
            assertThat(result.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("should return null avatarUrl when stored value is HTTP URL (not storage-backed)")
        void shouldReturnNullAvatarUrlForHttpUrl() {
            User user = createUser(1L, "oauthuser");
            user.setAvatarUrl("https://lh3.googleusercontent.com/a/photo");

            UserProfile result = userService.mapToUserProfile(user);

            assertThat(result.getAvatarUrl()).isNull();
        }

        @Test
        @DisplayName("should handle null authProvider")
        void shouldHandleNullAuthProvider() {
            User user = createUser(1L, "nullprovider");
            user.setAuthProvider(null);

            UserProfile result = userService.mapToUserProfile(user);

            assertThat(result.getAuthProvider()).isNull();
        }
    }

    @Nested
    @DisplayName("getAvatarEntity() method - self-heal for OAuth avatar overwrite bug")
    class GetAvatarEntityTests {

        @Test
        @DisplayName("should return storage entity when avatarUrl is a valid UUID")
        void shouldReturnStorageEntityWhenAvatarUrlIsUuid() {
            User user = createUser(1L, "user");
            UUID storageId = UUID.randomUUID();
            user.setAvatarUrl(storageId.toString());

            StorageEntity entity = new StorageEntity();
            entity.setId(storageId);
            entity.setDataBinary(new byte[]{1, 2, 3});

            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(storageService.getEntityById(storageId, "1")).thenReturn(Optional.of(entity));

            Optional<StorageEntity> result = userService.getAvatarEntity(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(storageId);
        }

        @Test
        @DisplayName("should self-heal when avatarUrl is HTTP URL but stored avatars exist")
        void shouldSelfHealWhenHttpUrlButStoredAvatarsExist() {
            User user = createUser(1L, "user");
            // avatarUrl was overwritten by OAuth re-login (the bug)
            user.setAvatarUrl("https://lh3.googleusercontent.com/a/photo");

            UUID storedAvatarId = UUID.randomUUID();
            StorageEntity storedAvatar = new StorageEntity();
            storedAvatar.setId(storedAvatarId);
            storedAvatar.setDataBinary(new byte[]{1, 2, 3});

            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(storageService.listByTenantAndSourceType("1", "USER_AVATAR"))
                    .thenReturn(List.of(storedAvatar));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            Optional<StorageEntity> result = userService.getAvatarEntity(1L);

            // Should return the stored avatar
            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(storedAvatarId);

            // Should self-heal: avatarUrl restored to storage UUID
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getAvatarUrl()).isEqualTo(storedAvatarId.toString());
        }

        @Test
        @DisplayName("should return empty when avatarUrl is HTTP URL and no stored avatars exist")
        void shouldReturnEmptyWhenHttpUrlAndNoStoredAvatars() {
            User user = createUser(1L, "user");
            user.setAvatarUrl("https://lh3.googleusercontent.com/a/photo");

            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(storageService.listByTenantAndSourceType("1", "USER_AVATAR"))
                    .thenReturn(Collections.emptyList());

            Optional<StorageEntity> result = userService.getAvatarEntity(1L);

            assertThat(result).isEmpty();
            // No self-heal needed - no stored avatars to restore
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should return empty when avatarUrl is null")
        void shouldReturnEmptyWhenAvatarUrlIsNull() {
            User user = createUser(1L, "user");
            user.setAvatarUrl(null);

            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            Optional<StorageEntity> result = userService.getAvatarEntity(1L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when user not found")
        void shouldReturnEmptyWhenUserNotFound() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            Optional<StorageEntity> result = userService.getAvatarEntity(999L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("uploadAvatar() method - gallery mode")
    class UploadAvatarGalleryTests {

        @Test
        @DisplayName("should not delete old avatar when uploading new one and keep old as active")
        void shouldNotDeleteOldAvatarWhenUploadingNew() {
            User user = createUser(1L, "user");
            UUID oldAvatarId = UUID.randomUUID();
            user.setAvatarUrl(oldAvatarId.toString());

            UUID newStorageId = UUID.randomUUID();
            when(storageService.listByTenantAndSourceType("1", "USER_AVATAR"))
                    .thenReturn(Collections.emptyList());
            when(storageService.saveBinary(eq("1"), any(byte[].class), anyString(), anyString(), any(), eq("USER_AVATAR")))
                    .thenReturn(newStorageId);

            String result = userService.uploadAvatar(user, new byte[]{1, 2, 3}, "image/png", "avatar.png");

            assertThat(result).isEqualTo(newStorageId.toString());
            // Old avatar preserved in storage
            verify(storageService, never()).deleteById(any(UUID.class), anyString());
            // Active avatar unchanged - user must explicitly select new one
            verify(userRepository, never()).save(any(User.class));
            assertThat(user.getAvatarUrl()).isEqualTo(oldAvatarId.toString());
        }

        @Test
        @DisplayName("should reject upload when 10 avatars already exist")
        void shouldRejectWhenMaxAvatarsReached() {
            User user = createUser(1L, "user");

            List<StorageEntity> tenAvatars = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                StorageEntity e = new StorageEntity();
                tenAvatars.add(e);
            }
            when(storageService.listByTenantAndSourceType("1", "USER_AVATAR"))
                    .thenReturn(tenAvatars);

            assertThatThrownBy(() -> userService.uploadAvatar(user, new byte[]{1}, "image/png", "avatar.png"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Maximum number of avatars reached");
        }
    }

    @Nested
    @DisplayName("listAvatars() method")
    class ListAvatarsTests {

        @Test
        @DisplayName("should return AvatarInfo list with active flag")
        void shouldReturnCorrectAvatarInfoList() {
            User user = createUser(1L, "user");
            UUID activeId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            user.setAvatarUrl(activeId.toString());

            StorageEntity active = new StorageEntity();
            active.setId(activeId);
            active.setMimeType("image/png");
            active.setCreatedAt(Instant.now());

            StorageEntity other = new StorageEntity();
            other.setId(otherId);
            other.setMimeType("image/jpeg");
            other.setCreatedAt(Instant.now());

            when(storageService.listByTenantAndSourceType("1", "USER_AVATAR"))
                    .thenReturn(List.of(active, other));

            List<AvatarInfo> result = userService.listAvatars(user);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).active()).isTrue();
            assertThat(result.get(0).id()).isEqualTo(activeId.toString());
            assertThat(result.get(1).active()).isFalse();
            assertThat(result.get(1).id()).isEqualTo(otherId.toString());
        }
    }

    @Nested
    @DisplayName("selectAvatar() method")
    class SelectAvatarTests {

        @Test
        @DisplayName("should update user avatarUrl to selected storage ID")
        void shouldUpdateAvatarUrl() {
            User user = createUser(1L, "user");
            UUID storageId = UUID.randomUUID();

            StorageEntity entity = new StorageEntity();
            entity.setId(storageId);
            when(storageService.getEntityById(storageId, "1")).thenReturn(Optional.of(entity));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            userService.selectAvatar(user, storageId.toString());

            assertThat(user.getAvatarUrl()).isEqualTo(storageId.toString());
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("should throw when avatar not found")
        void shouldThrowWhenAvatarNotFound() {
            User user = createUser(1L, "user");
            UUID storageId = UUID.randomUUID();

            when(storageService.getEntityById(storageId, "1")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.selectAvatar(user, storageId.toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Avatar not found");
        }
    }

    @Nested
    @DisplayName("deleteAvatar() method")
    class DeleteAvatarTests {

        @Test
        @DisplayName("should delete and select next avatar when active was deleted")
        void shouldSelectNextWhenActiveDeleted() {
            User user = createUser(1L, "user");
            UUID activeId = UUID.randomUUID();
            UUID nextId = UUID.randomUUID();
            user.setAvatarUrl(activeId.toString());

            when(storageService.deleteById(activeId, "1")).thenReturn(true);
            StorageEntity next = new StorageEntity();
            next.setId(nextId);
            when(storageService.listByTenantAndSourceType("1", "USER_AVATAR"))
                    .thenReturn(List.of(next));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            userService.deleteAvatar(user, activeId.toString());

            assertThat(user.getAvatarUrl()).isEqualTo(nextId.toString());
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("should clear avatarUrl when last avatar deleted")
        void shouldClearAvatarUrlWhenLastDeleted() {
            User user = createUser(1L, "user");
            UUID onlyId = UUID.randomUUID();
            user.setAvatarUrl(onlyId.toString());

            when(storageService.deleteById(onlyId, "1")).thenReturn(true);
            when(storageService.listByTenantAndSourceType("1", "USER_AVATAR"))
                    .thenReturn(Collections.emptyList());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            userService.deleteAvatar(user, onlyId.toString());

            assertThat(user.getAvatarUrl()).isNull();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("should throw when avatar not found for deletion")
        void shouldThrowWhenAvatarNotFoundForDeletion() {
            User user = createUser(1L, "user");
            UUID storageId = UUID.randomUUID();

            when(storageService.deleteById(storageId, "1")).thenReturn(false);

            assertThatThrownBy(() -> userService.deleteAvatar(user, storageId.toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Avatar not found");
        }
    }

    @Nested
    @DisplayName("updateDisplayName via updateProfile()")
    class UpdateDisplayNameTests {

        @Test
        @DisplayName("should update display name when no previous change")
        void shouldUpdateDisplayNameWhenNoPreviousChange() {
            User user = createUser(1L, "user");
            UserOnboarding onboarding = new UserOnboarding(user, "Old Name");

            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setDisplayName("New Name");

            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));
            when(onboardingRepository.save(any(UserOnboarding.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            userService.updateProfile(user, request);

            verify(onboardingRepository).save(any(UserOnboarding.class));
            assertThat(onboarding.getDisplayName()).isEqualTo("New Name");
            assertThat(onboarding.getDisplayNameChangedAt()).isNotNull();
        }

        @Test
        @DisplayName("should update display name when last change was more than 7 days ago")
        void shouldUpdateDisplayNameWhenCooldownExpired() {
            User user = createUser(1L, "user");
            UserOnboarding onboarding = new UserOnboarding(user, "Old Name");
            onboarding.setDisplayNameChangedAt(LocalDateTime.now().minusDays(8));

            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setDisplayName("New Name");

            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));
            when(onboardingRepository.save(any(UserOnboarding.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            userService.updateProfile(user, request);

            assertThat(onboarding.getDisplayName()).isEqualTo("New Name");
        }

        @Test
        @DisplayName("should throw when last change was less than 7 days ago")
        void shouldThrowWhenCooldownNotExpired() {
            User user = createUser(1L, "user");
            UserOnboarding onboarding = new UserOnboarding(user, "Old Name");
            onboarding.setDisplayNameChangedAt(LocalDateTime.now().minusDays(3));

            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setDisplayName("New Name");

            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));

            assertThatThrownBy(() -> userService.updateProfile(user, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("once per week");
        }

        @Test
        @DisplayName("should skip when displayName is same as current")
        void shouldSkipWhenSameName() {
            User user = createUser(1L, "user");
            UserOnboarding onboarding = new UserOnboarding(user, "Same Name");

            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setDisplayName("Same Name");

            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            userService.updateProfile(user, request);

            verify(onboardingRepository, never()).save(any(UserOnboarding.class));
        }

        @Test
        @DisplayName("should skip when displayName is null in request")
        void shouldSkipWhenDisplayNameNull() {
            User user = createUser(1L, "user");

            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setDisplayName(null);

            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            userService.updateProfile(user, request);

            verify(onboardingRepository, never()).save(any(UserOnboarding.class));
        }
    }

    @Nested
    @DisplayName("getDisplayNameStatus() method")
    class GetDisplayNameStatusTests {

        @Test
        @DisplayName("should return canChange=true when no previous change")
        void shouldReturnCanChangeWhenNoPreviousChange() {
            User user = createUser(1L, "user");
            UserOnboarding onboarding = new UserOnboarding(user, "Name");

            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));

            java.util.Map<String, Object> result = userService.getDisplayNameStatus(user);

            assertThat(result.get("canChange")).isEqualTo(true);
            assertThat(result.get("nextChangeDate")).isNull();
        }

        @Test
        @DisplayName("should return canChange=false when cooldown active")
        void shouldReturnCannotChangeWhenCooldownActive() {
            User user = createUser(1L, "user");
            UserOnboarding onboarding = new UserOnboarding(user, "Name");
            onboarding.setDisplayNameChangedAt(LocalDateTime.now().minusDays(2));

            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));

            java.util.Map<String, Object> result = userService.getDisplayNameStatus(user);

            assertThat(result.get("canChange")).isEqualTo(false);
            assertThat(result.get("nextChangeDate")).isNotNull();
        }

        @Test
        @DisplayName("should return canChange=true when cooldown expired")
        void shouldReturnCanChangeWhenCooldownExpired() {
            User user = createUser(1L, "user");
            UserOnboarding onboarding = new UserOnboarding(user, "Name");
            onboarding.setDisplayNameChangedAt(LocalDateTime.now().minusDays(10));

            when(onboardingRepository.findByUserId(1L)).thenReturn(Optional.of(onboarding));

            java.util.Map<String, Object> result = userService.getDisplayNameStatus(user);

            assertThat(result.get("canChange")).isEqualTo(true);
            assertThat(result.get("nextChangeDate")).isNull();
        }
    }

    @Nested
    @DisplayName("importProviderAvatar() method")
    class ImportProviderAvatarTests {

        @Test
        @DisplayName("should self-heal and return storageId when avatarUrl is HTTP but stored avatars exist")
        void shouldSelfHealWhenHttpUrlButStoredAvatarsExist() {
            User user = createUser(1L, "user");
            user.setAvatarUrl("https://lh3.googleusercontent.com/a/photo");

            UUID storedAvatarId = UUID.randomUUID();
            StorageEntity storedAvatar = new StorageEntity();
            storedAvatar.setId(storedAvatarId);

            when(storageService.listByTenantAndSourceType("1", "USER_AVATAR"))
                    .thenReturn(List.of(storedAvatar));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            try (var tenantResolverMock = mockStatic(com.apimarketplace.common.web.TenantResolver.class)) {
                tenantResolverMock.when(com.apimarketplace.common.web.TenantResolver::currentRequestOrganizationId)
                        .thenReturn("org-123");

                Optional<String> result = userService.importProviderAvatar(user);

                assertThat(result).isPresent();
                assertThat(result.get()).isEqualTo(storedAvatarId.toString());

                verify(userRepository).save(userCaptor.capture());
                assertThat(userCaptor.getValue().getAvatarUrl()).isEqualTo(storedAvatarId.toString());
            }
        }

        @Test
        @DisplayName("should skip import when no org context available")
        void shouldSkipWhenNoOrgContext() {
            User user = createUser(1L, "user");
            user.setAvatarUrl("https://lh3.googleusercontent.com/a/photo");

            try (var tenantResolverMock = mockStatic(com.apimarketplace.common.web.TenantResolver.class)) {
                tenantResolverMock.when(com.apimarketplace.common.web.TenantResolver::currentRequestOrganizationId)
                        .thenReturn(null);

                Optional<String> result = userService.importProviderAvatar(user);

                assertThat(result).isEmpty();
                verify(userRepository, never()).save(any(User.class));
            }
        }

        @Test
        @DisplayName("should return empty when avatarUrl is already a UUID")
        void shouldReturnEmptyWhenAlreadyUuid() {
            User user = createUser(1L, "user");
            user.setAvatarUrl(UUID.randomUUID().toString());

            try (var tenantResolverMock = mockStatic(com.apimarketplace.common.web.TenantResolver.class)) {
                tenantResolverMock.when(com.apimarketplace.common.web.TenantResolver::currentRequestOrganizationId)
                        .thenReturn("org-123");

                Optional<String> result = userService.importProviderAvatar(user);

                assertThat(result).isEmpty();
                verify(userRepository, never()).save(any(User.class));
            }
        }

        @Test
        @DisplayName("should return empty when avatarUrl is null")
        void shouldReturnEmptyWhenAvatarUrlIsNull() {
            User user = createUser(1L, "user");
            user.setAvatarUrl(null);

            Optional<String> result = userService.importProviderAvatar(user);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("updateProfile() - public profile fields (bio / website / social links / visibility)")
    class UpdatePublicProfileTests {

        @Test
        @DisplayName("creates the user_profiles row on first edit and stores a trimmed bio")
        void createsProfileRowAndStoresBio() {
            User user = createUser(1L, "user");
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setBio("  Hi, I build automations.  ");
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

            userService.updateProfile(user, request);

            ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(captor.capture());
            UserProfileEntity saved = captor.getValue();
            assertThat(saved.getUserId()).isEqualTo(1L);
            assertThat(saved.getBio()).isEqualTo("Hi, I build automations.");
        }

        @Test
        @DisplayName("blank bio clears the field (stored as null)")
        void blankBioStoredAsNull() {
            User user = createUser(1L, "user");
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setBio("   ");
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

            userService.updateProfile(user, request);

            ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(captor.capture());
            assertThat(captor.getValue().getBio()).isNull();
        }

        @Test
        @DisplayName("visibility accepts PRIVATE (case-insensitive)")
        void visibilityValidated() {
            User user = createUser(1L, "user");
            UserProfileEntity existing = new UserProfileEntity(1L); // starts PUBLIC
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setProfileVisibility("private");
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

            userService.updateProfile(user, request);

            ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(captor.capture());
            assertThat(captor.getValue().getProfileVisibility()).isEqualTo("PRIVATE");
        }

        @Test
        @DisplayName("garbage visibility is ignored (existing value kept)")
        void garbageVisibilityIgnored() {
            User user = createUser(1L, "user");
            UserProfileEntity existing = new UserProfileEntity(1L);
            existing.setProfileVisibility("PUBLIC");
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setProfileVisibility("hacker");
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

            userService.updateProfile(user, request);

            ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(captor.capture());
            assertThat(captor.getValue().getProfileVisibility()).isEqualTo("PUBLIC");
        }

        @Test
        @DisplayName("when no public-profile field is present the user_profiles row is left untouched")
        void noPublicFieldsNoSave() {
            User user = createUser(1L, "user");
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setFirstName("Just a name");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            userService.updateProfile(user, request);

            verify(userProfileRepository, never()).save(any(UserProfileEntity.class));
            verify(userProfileRepository, never()).findByUserId(anyLong());
        }
    }

    @Nested
    @DisplayName("updateProfile() - @handle 1-change-per-week cooldown (same rule as display name)")
    class HandleCooldownTests {

        private UserProfileUpdateRequest handleRequest(String handle) {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setHandle(handle);
            return request;
        }

        @Test
        @DisplayName("first explicit change is allowed and stamps handle_changed_at (starts the cooldown)")
        void firstChangeAllowedAndStamped() {
            User user = createUser(1L, "user");
            UserProfileEntity existing = new UserProfileEntity(1L);
            existing.setHandle("old_handle"); // auto-generated → handleChangedAt is null
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));
            when(usernameValidator.normalize("new_handle")).thenReturn("new_handle");
            when(userProfileRepository.findByHandle("new_handle")).thenReturn(Optional.empty());

            userService.updateProfile(user, handleRequest("new_handle"));

            ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(captor.capture());
            assertThat(captor.getValue().getHandle()).isEqualTo("new_handle");
            assertThat(captor.getValue().getHandleChangedAt()).isNotNull();
        }

        @Test
        @DisplayName("regression: a second change within 7 days is rejected (handle used to be always editable)")
        void changeWithinCooldownRejected() {
            User user = createUser(1L, "user");
            UserProfileEntity existing = new UserProfileEntity(1L);
            existing.setHandle("current");
            existing.setHandleChangedAt(LocalDateTime.now().minusDays(2));
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));
            when(usernameValidator.normalize("another")).thenReturn("another");
            when(userProfileRepository.findByHandle("another")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.updateProfile(user, handleRequest("another")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("once per week");
            verify(userProfileRepository, never()).save(any(UserProfileEntity.class));
        }

        @Test
        @DisplayName("a change after the 7-day window is allowed and refreshes handle_changed_at")
        void changeAfterCooldownAllowed() {
            User user = createUser(1L, "user");
            UserProfileEntity existing = new UserProfileEntity(1L);
            existing.setHandle("current");
            LocalDateTime oldStamp = LocalDateTime.now().minusDays(8);
            existing.setHandleChangedAt(oldStamp);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));
            when(usernameValidator.normalize("another")).thenReturn("another");
            when(userProfileRepository.findByHandle("another")).thenReturn(Optional.empty());

            userService.updateProfile(user, handleRequest("another"));

            ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(captor.capture());
            assertThat(captor.getValue().getHandle()).isEqualTo("another");
            assertThat(captor.getValue().getHandleChangedAt()).isAfter(oldStamp);
        }

        @Test
        @DisplayName("re-sending the CURRENT handle during cooldown is a no-op (auto-save card sends all fields)")
        void sameHandleDuringCooldownIsNoOp() {
            User user = createUser(1L, "user");
            UserProfileEntity existing = new UserProfileEntity(1L);
            existing.setHandle("current");
            LocalDateTime stamp = LocalDateTime.now().minusDays(1);
            existing.setHandleChangedAt(stamp);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));
            when(usernameValidator.normalize("current")).thenReturn("current");
            when(userProfileRepository.findByHandle("current")).thenReturn(Optional.of(existing));

            userService.updateProfile(user, handleRequest("current")); // must not throw

            ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(captor.capture());
            assertThat(captor.getValue().getHandleChangedAt()).isEqualTo(stamp); // not refreshed
        }

        @Test
        @DisplayName("a taken handle is ignored and does NOT consume the weekly change")
        void takenHandleIgnoredDoesNotConsumeCooldown() {
            User user = createUser(1L, "user");
            UserProfileEntity existing = new UserProfileEntity(1L);
            existing.setHandle("current");
            UserProfileEntity other = new UserProfileEntity(99L);
            other.setHandle("taken");
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));
            when(usernameValidator.normalize("taken")).thenReturn("taken");
            when(userProfileRepository.findByHandle("taken")).thenReturn(Optional.of(other));

            userService.updateProfile(user, handleRequest("taken"));

            ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
            verify(userProfileRepository).save(captor.capture());
            assertThat(captor.getValue().getHandle()).isEqualTo("current");
            assertThat(captor.getValue().getHandleChangedAt()).isNull();
        }

        @Test
        @DisplayName("lazy auto-generation (getPublicProfile) does NOT start the cooldown")
        void lazyGenerationDoesNotStartCooldown() {
            User user = createUser(7L, "alice");
            UserOnboarding onboarding = new UserOnboarding(user, "Alice A.");
            UserProfileEntity profile = new UserProfileEntity(7L);
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(profile));
            when(onboardingRepository.findByUserId(7L)).thenReturn(Optional.of(onboarding));
            when(usernameValidator.normalize("Alice A.")).thenReturn("alice_a");
            when(userProfileRepository.existsByHandle("alice_a")).thenReturn(false);

            userService.getPublicProfile(user);

            assertThat(profile.getHandle()).isEqualTo("alice_a");
            assertThat(profile.getHandleChangedAt()).isNull();
        }

        @Test
        @DisplayName("getHandleStatus: never changed → canChange=true, no nextChangeDate")
        void statusNeverChanged() {
            User user = createUser(1L, "user");
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

            java.util.Map<String, Object> status = userService.getHandleStatus(user);

            assertThat(status.get("canChange")).isEqualTo(true);
            assertThat(status.get("nextChangeDate")).isNull();
        }

        @Test
        @DisplayName("getHandleStatus: changed 2 days ago → canChange=false with nextChangeDate = change+7d")
        void statusInCooldown() {
            User user = createUser(1L, "user");
            UserProfileEntity existing = new UserProfileEntity(1L);
            LocalDateTime changed = LocalDateTime.now().minusDays(2);
            existing.setHandleChangedAt(changed);
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

            java.util.Map<String, Object> status = userService.getHandleStatus(user);

            assertThat(status.get("canChange")).isEqualTo(false);
            assertThat(status.get("nextChangeDate")).isEqualTo(changed.plusDays(7).toString());
        }

        @Test
        @DisplayName("getHandleStatus: changed 8 days ago → canChange=true again")
        void statusAfterCooldown() {
            User user = createUser(1L, "user");
            UserProfileEntity existing = new UserProfileEntity(1L);
            existing.setHandleChangedAt(LocalDateTime.now().minusDays(8));
            when(userProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

            java.util.Map<String, Object> status = userService.getHandleStatus(user);

            assertThat(status.get("canChange")).isEqualTo(true);
            assertThat(status.get("nextChangeDate")).isNull();
        }
    }

    @Nested
    @DisplayName("getPublicProfile() method")
    class GetPublicProfileTests {

        @Test
        @DisplayName("returns a public DTO with displayName, a generated @handle, bio and joinedAt (no email/roles)")
        void returnsPublicDto() {
            User user = createUser(7L, "alice");
            user.setEmail("alice@secret.com");
            user.setCreatedAt(LocalDateTime.of(2024, 3, 1, 0, 0));

            UserOnboarding onboarding = new UserOnboarding(user, "Alice A.");
            UserProfileEntity profile = new UserProfileEntity(7L);
            profile.setBio("Builder");
            profile.setProfileVisibility("PUBLIC");

            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(profile));
            when(onboardingRepository.findByUserId(7L)).thenReturn(Optional.of(onboarding));
            when(usernameValidator.normalize("Alice A.")).thenReturn("alice_a");
            when(userProfileRepository.existsByHandle("alice_a")).thenReturn(false);

            Optional<PublicProfileDto> result = userService.getPublicProfile(user);

            assertThat(result).isPresent();
            PublicProfileDto dto = result.get();
            assertThat(dto.userId()).isEqualTo(7L);
            assertThat(dto.displayName()).isEqualTo("Alice A.");
            // The public @handle is a slug of the display name - never the raw account username.
            assertThat(dto.handle()).isEqualTo("alice_a");
            assertThat(dto.bio()).isEqualTo("Builder");
            assertThat(dto.joinedAt()).isEqualTo(LocalDateTime.of(2024, 3, 1, 0, 0));
            verify(userProfileRepository).save(profile); // generated handle is persisted
        }

        @Test
        @DisplayName("a generated @handle is de-duped with a numeric suffix when the slug is taken")
        void handleDedupedWhenTaken() {
            User user = createUser(7L, "alice");
            UserOnboarding onboarding = new UserOnboarding(user, "Alice A.");
            UserProfileEntity profile = new UserProfileEntity(7L);
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(profile));
            when(onboardingRepository.findByUserId(7L)).thenReturn(Optional.of(onboarding));
            when(usernameValidator.normalize("Alice A.")).thenReturn("alice_a");
            when(userProfileRepository.existsByHandle("alice_a")).thenReturn(true);
            when(userProfileRepository.existsByHandle("alice_a_1")).thenReturn(false);

            String handle = userService.getPublicProfile(user).orElseThrow().handle();

            assertThat(handle).isEqualTo("alice_a_1");
        }

        @Test
        @DisplayName("an already-set @handle is kept, not regenerated")
        void existingHandleKept() {
            User user = createUser(7L, "alice");
            UserProfileEntity profile = new UserProfileEntity(7L);
            profile.setHandle("custom_handle");
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(profile));
            when(onboardingRepository.findByUserId(7L)).thenReturn(Optional.empty());

            String handle = userService.getPublicProfile(user).orElseThrow().handle();

            assertThat(handle).isEqualTo("custom_handle");
            verify(userProfileRepository, never()).save(any(UserProfileEntity.class));
            verifyNoInteractions(usernameValidator);
        }

        @Test
        @DisplayName("findByHandle resolves the user owning that handle (case-insensitive)")
        void findByHandleResolvesUser() {
            UserProfileEntity profile = new UserProfileEntity(7L);
            User user = createUser(7L, "alice");
            when(userProfileRepository.findByHandle("alice_a")).thenReturn(Optional.of(profile));
            when(userRepository.findById(7L)).thenReturn(Optional.of(user));

            assertThat(userService.findByHandle("Alice_A")).contains(user);
        }

        @Test
        @DisplayName("PRIVATE profile is not exposed publicly (empty → controller 404)")
        void privateProfileHidden() {
            User user = createUser(7L, "alice");
            UserProfileEntity profile = new UserProfileEntity(7L);
            profile.setProfileVisibility("PRIVATE");
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.of(profile));

            Optional<PublicProfileDto> result = userService.getPublicProfile(user);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("disabled / deactivated account is not browsable (checked before any lookup)")
        void disabledAccountHidden() {
            User user = createUser(7L, "alice");
            user.setEnabled(false);

            Optional<PublicProfileDto> result = userService.getPublicProfile(user);

            assertThat(result).isEmpty();
            verify(userProfileRepository, never()).findByUserId(anyLong());
        }

        @Test
        @DisplayName("works with no user_profiles row yet (default-public, empty bio)")
        void worksWithoutProfileRow() {
            User user = createUser(7L, "alice");
            user.setCreatedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.empty());
            when(onboardingRepository.findByUserId(7L)).thenReturn(Optional.empty());

            Optional<PublicProfileDto> result = userService.getPublicProfile(user);

            assertThat(result).isPresent();
            assertThat(result.get().bio()).isNull();
            // No handle/real-name field on the public DTO; displayName falls back to the handle.
            assertThat(result.get().displayName()).isEqualTo("alice");
        }

        @Test
        @DisplayName("a Keycloak auto-generated handle (kc_xxxx) never surfaces - the display name is shown")
        void kcUsernameReplacedByDisplayName() {
            User user = createUser(7L, "kc_2d4cc1b2");
            UserOnboarding onboarding = new UserOnboarding(user, "Alice A.");
            when(userProfileRepository.findByUserId(7L)).thenReturn(Optional.empty());
            when(onboardingRepository.findByUserId(7L)).thenReturn(Optional.of(onboarding));

            Optional<PublicProfileDto> result = userService.getPublicProfile(user);

            assertThat(result).isPresent();
            assertThat(result.get().displayName()).isEqualTo("Alice A.");
        }
    }

    // Helper methods

    private User createUser(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEnabled(true);
        return user;
    }
}
