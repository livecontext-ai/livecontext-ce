package com.apimarketplace.auth.validation;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsernameValidator Tests")
class UsernameValidatorTest {

    @Mock
    private UserRepository userRepository;

    private UsernameValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UsernameValidator(userRepository);
    }

    @Nested
    @DisplayName("validate() method")
    class ValidateTests {

        @Test
        @DisplayName("should accept valid username")
        void shouldAcceptValidUsername() {
            when(userRepository.findByUsername("validuser")).thenReturn(Optional.empty());

            Optional<String> result = validator.validate("validuser", 1L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should accept username with numbers")
        void shouldAcceptUsernameWithNumbers() {
            when(userRepository.findByUsername("user123")).thenReturn(Optional.empty());

            Optional<String> result = validator.validate("user123", 1L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should accept username with hyphens and underscores")
        void shouldAcceptUsernameWithHyphensAndUnderscores() {
            when(userRepository.findByUsername("user-name_123")).thenReturn(Optional.empty());

            Optional<String> result = validator.validate("user-name_123", 1L);

            assertThat(result).isEmpty();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("should reject null, empty or blank username")
        void shouldRejectNullEmptyOrBlankUsername(String username) {
            Optional<String> result = validator.validate(username, 1L);

            assertThat(result).isPresent();
            assertThat(result.get()).contains("empty");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ab", "a", "xy"})
        @DisplayName("should reject username shorter than 3 characters")
        void shouldRejectShortUsername(String username) {
            Optional<String> result = validator.validate(username, 1L);

            assertThat(result).isPresent();
            assertThat(result.get()).contains("between 3 and 20");
        }

        @Test
        @DisplayName("should reject username longer than 20 characters")
        void shouldRejectLongUsername() {
            String longUsername = "a".repeat(21);

            Optional<String> result = validator.validate(longUsername, 1L);

            assertThat(result).isPresent();
            assertThat(result.get()).contains("between 3 and 20");
        }

        @ParameterizedTest
        @ValueSource(strings = {"user@name", "user.name", "user name", "user#name", "user$name"})
        @DisplayName("should reject username with invalid characters")
        void shouldRejectInvalidCharacters(String username) {
            Optional<String> result = validator.validate(username, 1L);

            assertThat(result).isPresent();
            assertThat(result.get()).contains("letters, numbers, hyphens, and underscores");
        }

        @ParameterizedTest
        @ValueSource(strings = {"admin", "root", "system", "support", "help", "moderator"})
        @DisplayName("should reject reserved usernames")
        void shouldRejectReservedUsernames(String username) {
            Optional<String> result = validator.validate(username, 1L);

            assertThat(result).isPresent();
            assertThat(result.get()).contains("reserved");
        }

        @Test
        @DisplayName("should reject already taken username by another user")
        void shouldRejectTakenUsername() {
            User existingUser = new User();
            existingUser.setId(2L);
            existingUser.setUsername("takenuser");
            when(userRepository.findByUsername("takenuser")).thenReturn(Optional.of(existingUser));

            Optional<String> result = validator.validate("takenuser", 1L);

            assertThat(result).isPresent();
            assertThat(result.get()).contains("already taken");
        }

        @Test
        @DisplayName("should allow same username for same user")
        void shouldAllowSameUsernameForSameUser() {
            User existingUser = new User();
            existingUser.setId(1L);
            existingUser.setUsername("myusername");
            when(userRepository.findByUsername("myusername")).thenReturn(Optional.of(existingUser));

            Optional<String> result = validator.validate("myusername", 1L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should trim whitespace from username")
        void shouldTrimWhitespace() {
            when(userRepository.findByUsername("validuser")).thenReturn(Optional.empty());

            Optional<String> result = validator.validate("  validuser  ", 1L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("normalize() method")
    class NormalizeTests {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNullInput() {
            String result = validator.normalize(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should convert to lowercase")
        void shouldConvertToLowercase() {
            String result = validator.normalize("UserName");

            assertThat(result).isEqualTo("username");
        }

        @Test
        @DisplayName("should remove diacritics")
        void shouldRemoveDiacritics() {
            String result = validator.normalize("café");

            assertThat(result).isEqualTo("cafe");
        }

        @Test
        @DisplayName("should replace invalid characters with underscore")
        void shouldReplaceInvalidCharacters() {
            String result = validator.normalize("user@name#test");

            assertThat(result).contains("_");
        }

        @Test
        @DisplayName("should collapse multiple underscores")
        void shouldCollapseMultipleUnderscores() {
            String result = validator.normalize("user___name");

            assertThat(result).isEqualTo("user_name");
        }

        @Test
        @DisplayName("should truncate to 32 characters")
        void shouldTruncateTo32Characters() {
            String longInput = "a".repeat(50);

            String result = validator.normalize(longInput);

            assertThat(result).hasSize(32);
        }
    }

    @Nested
    @DisplayName("generateUniqueUsername() method")
    class GenerateUniqueUsernameTests {

        @Test
        @DisplayName("should return base username if not taken")
        void shouldReturnBaseUsernameIfNotTaken() {
            when(userRepository.existsByUsername("testuser")).thenReturn(false);

            String result = validator.generateUniqueUsername("testuser");

            assertThat(result).isEqualTo("testuser");
        }

        @Test
        @DisplayName("should append suffix if username is taken")
        void shouldAppendSuffixIfTaken() {
            when(userRepository.existsByUsername("testuser")).thenReturn(true);
            when(userRepository.existsByUsername("testuser_1")).thenReturn(false);

            String result = validator.generateUniqueUsername("testuser");

            assertThat(result).isEqualTo("testuser_1");
        }

        @Test
        @DisplayName("should increment suffix until unique")
        void shouldIncrementSuffixUntilUnique() {
            when(userRepository.existsByUsername("testuser")).thenReturn(true);
            when(userRepository.existsByUsername("testuser_1")).thenReturn(true);
            when(userRepository.existsByUsername("testuser_2")).thenReturn(true);
            when(userRepository.existsByUsername("testuser_3")).thenReturn(false);

            String result = validator.generateUniqueUsername("testuser");

            assertThat(result).isEqualTo("testuser_3");
        }

        @Test
        @DisplayName("should use 'user' as default for null input")
        void shouldUseDefaultForNullInput() {
            when(userRepository.existsByUsername("user")).thenReturn(false);

            String result = validator.generateUniqueUsername(null);

            assertThat(result).isEqualTo("user");
        }

        @Test
        @DisplayName("should use 'user' as default for blank input")
        void shouldUseDefaultForBlankInput() {
            when(userRepository.existsByUsername(anyString())).thenReturn(false);

            String result = validator.generateUniqueUsername("   ");

            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("buildUsernameFromProviderId() method")
    class BuildUsernameFromProviderIdTests {

        @Test
        @DisplayName("should build username from Keycloak UUID provider ID")
        void shouldBuildFromKeycloakUuidProviderId() {
            String result = validator.buildUsernameFromProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(result).startsWith("kc_");
            assertThat(result).contains("f47ac10b");
        }

        @Test
        @DisplayName("should build username from short provider ID")
        void shouldBuildFromShortProviderId() {
            String result = validator.buildUsernameFromProviderId("abc123");

            assertThat(result).startsWith("kc_");
            assertThat(result).contains("abc123");
        }

        @Test
        @DisplayName("should build username with kc_ prefix for any provider ID")
        void shouldBuildWithKcPrefixForAnyProviderId() {
            String result = validator.buildUsernameFromProviderId("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

            assertThat(result).startsWith("kc_");
        }

        @Test
        @DisplayName("should return default for null provider ID")
        void shouldReturnDefaultForNullProviderId() {
            String result = validator.buildUsernameFromProviderId(null);

            assertThat(result).isEqualTo("kc_user");
        }

        @Test
        @DisplayName("should truncate long provider ID")
        void shouldTruncateLongProviderId() {
            String longId = "a".repeat(100);

            String result = validator.buildUsernameFromProviderId(longId);

            assertThat(result.length()).isLessThanOrEqualTo(32);
        }
    }

    @Nested
    @DisplayName("isUsernameTaken() method")
    class IsUsernameTakenTests {

        @Test
        @DisplayName("should return true if username is taken by another user")
        void shouldReturnTrueIfTakenByAnotherUser() {
            User existingUser = new User();
            existingUser.setId(2L);
            when(userRepository.findByUsername("takenuser")).thenReturn(Optional.of(existingUser));

            boolean result = validator.isUsernameTaken("takenuser", 1L);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false if username is not taken")
        void shouldReturnFalseIfNotTaken() {
            when(userRepository.findByUsername("freeuser")).thenReturn(Optional.empty());

            boolean result = validator.isUsernameTaken("freeuser", 1L);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false if username belongs to same user")
        void shouldReturnFalseForSameUser() {
            User existingUser = new User();
            existingUser.setId(1L);
            when(userRepository.findByUsername("myuser")).thenReturn(Optional.of(existingUser));

            boolean result = validator.isUsernameTaken("myuser", 1L);

            assertThat(result).isFalse();
        }
    }
}
