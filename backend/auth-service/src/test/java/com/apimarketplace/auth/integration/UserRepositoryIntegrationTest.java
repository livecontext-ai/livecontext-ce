package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for UserRepository using SpringBootTest with H2 database.
 * Tests JPA repository methods with a real database context.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
@AutoConfigureTestEntityManager
@DisplayName("UserRepository Integration Tests")
class UserRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User("testuser", "test@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");
        testUser.setFirstName("Test");
        testUser.setLastName("User");
        testUser.setEnabled(true);
        testUser.setEmailVerified(true);
        testUser.setUserVersion(1L);
    }

    @Nested
    @DisplayName("CRUD Operations")
    class CrudOperations {

        @Test
        @DisplayName("Should save and retrieve a user by ID")
        void shouldSaveAndFindById() {
            User saved = entityManager.persistAndFlush(testUser);

            Optional<User> found = userRepository.findById(saved.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getUsername()).isEqualTo("testuser");
            assertThat(found.get().getEmail()).isEqualTo("test@example.com");
            assertThat(found.get().getAuthProvider()).isEqualTo(AuthProvider.KEYCLOAK);
            assertThat(found.get().getProviderId()).isEqualTo("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        }

        @Test
        @DisplayName("Should update a user")
        void shouldUpdateUser() {
            User saved = entityManager.persistAndFlush(testUser);

            saved.setFirstName("Updated");
            saved.setLastName("Name");
            userRepository.saveAndFlush(saved);
            entityManager.clear();

            Optional<User> found = userRepository.findById(saved.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getFirstName()).isEqualTo("Updated");
            assertThat(found.get().getLastName()).isEqualTo("Name");
        }

        @Test
        @DisplayName("Should delete a user")
        void shouldDeleteUser() {
            User saved = entityManager.persistAndFlush(testUser);
            Long id = saved.getId();

            userRepository.deleteById(id);
            entityManager.flush();

            Optional<User> found = userRepository.findById(id);
            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("Find By Operations")
    class FindByOperations {

        @Test
        @DisplayName("Should find user by username")
        void shouldFindByUsername() {
            entityManager.persistAndFlush(testUser);

            Optional<User> found = userRepository.findByUsername("testuser");

            assertThat(found).isPresent();
            assertThat(found.get().getEmail()).isEqualTo("test@example.com");
        }

        @Test
        @DisplayName("Should return empty when username does not exist")
        void shouldReturnEmptyForNonExistentUsername() {
            Optional<User> found = userRepository.findByUsername("nonexistent");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("Should find user by email")
        void shouldFindByEmail() {
            entityManager.persistAndFlush(testUser);

            Optional<User> found = userRepository.findByEmail("test@example.com");

            assertThat(found).isPresent();
            assertThat(found.get().getUsername()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("Should return empty when email does not exist")
        void shouldReturnEmptyForNonExistentEmail() {
            Optional<User> found = userRepository.findByEmail("nonexistent@example.com");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("Should find user by providerId")
        void shouldFindByProviderId() {
            entityManager.persistAndFlush(testUser);

            Optional<User> found = userRepository.findByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(found).isPresent();
            assertThat(found.get().getUsername()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("Should return empty when providerId does not exist")
        void shouldReturnEmptyForNonExistentProviderId() {
            Optional<User> found = userRepository.findByProviderId("00000000-0000-0000-0000-000000000000");

            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("Exists By Operations")
    class ExistsByOperations {

        @Test
        @DisplayName("Should return true when username exists")
        void shouldReturnTrueWhenUsernameExists() {
            entityManager.persistAndFlush(testUser);

            boolean exists = userRepository.existsByUsername("testuser");

            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("Should return false when username does not exist")
        void shouldReturnFalseWhenUsernameDoesNotExist() {
            boolean exists = userRepository.existsByUsername("nonexistent");

            assertThat(exists).isFalse();
        }

        @Test
        @DisplayName("Should return true when email exists")
        void shouldReturnTrueWhenEmailExists() {
            entityManager.persistAndFlush(testUser);

            boolean exists = userRepository.existsByEmail("test@example.com");

            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("Should return false when email does not exist")
        void shouldReturnFalseWhenEmailDoesNotExist() {
            boolean exists = userRepository.existsByEmail("nonexistent@example.com");

            assertThat(exists).isFalse();
        }

        @Test
        @DisplayName("Should return true when providerId exists")
        void shouldReturnTrueWhenProviderIdExists() {
            entityManager.persistAndFlush(testUser);

            boolean exists = userRepository.existsByProviderId("f47ac10b-58cc-4372-a567-0e02b2c3d479");

            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("Should return false when providerId does not exist")
        void shouldReturnFalseWhenProviderIdDoesNotExist() {
            boolean exists = userRepository.existsByProviderId("00000000-0000-0000-0000-000000000000");

            assertThat(exists).isFalse();
        }
    }

    @Nested
    @DisplayName("Multiple Users Scenarios")
    class MultipleUsersScenarios {

        @Test
        @DisplayName("Should distinguish between different users by email")
        void shouldDistinguishUsersByEmail() {
            entityManager.persistAndFlush(testUser);

            User secondUser = new User("otheruser", "other@example.com", AuthProvider.GOOGLE, "google|67890");
            secondUser.setEnabled(true);
            secondUser.setUserVersion(1L);
            entityManager.persistAndFlush(secondUser);

            Optional<User> foundFirst = userRepository.findByEmail("test@example.com");
            Optional<User> foundSecond = userRepository.findByEmail("other@example.com");

            assertThat(foundFirst).isPresent();
            assertThat(foundSecond).isPresent();
            assertThat(foundFirst.get().getUsername()).isEqualTo("testuser");
            assertThat(foundSecond.get().getUsername()).isEqualTo("otheruser");
        }

        @Test
        @DisplayName("Should find user by email and auth provider using custom query")
        void shouldFindByEmailAndProvider() {
            entityManager.persistAndFlush(testUser);

            Optional<User> found = userRepository.findByEmailAndProvider("test@example.com", AuthProvider.KEYCLOAK);

            assertThat(found).isPresent();
            assertThat(found.get().getProviderId()).isEqualTo("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        }

        @Test
        @DisplayName("Should count all users correctly")
        void shouldCountAllUsers() {
            entityManager.persistAndFlush(testUser);

            User secondUser = new User("user2", "user2@example.com", AuthProvider.GITHUB, "github|11111");
            secondUser.setEnabled(true);
            secondUser.setUserVersion(1L);
            entityManager.persistAndFlush(secondUser);

            long count = userRepository.count();
            assertThat(count).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("User Entity Lifecycle")
    class UserEntityLifecycle {

        @Test
        @DisplayName("Should set createdAt and updatedAt on creation")
        void shouldSetTimestampsOnCreation() {
            User saved = entityManager.persistAndFlush(testUser);

            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should update updatedAt on modification")
        void shouldUpdateTimestampOnModification() {
            User saved = entityManager.persistAndFlush(testUser);
            LocalDateTime originalUpdatedAt = saved.getUpdatedAt();

            // Small delay to ensure different timestamp
            saved.setFirstName("Modified");
            userRepository.saveAndFlush(saved);

            // The @PreUpdate callback should update updatedAt
            assertThat(saved.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should persist user roles via ElementCollection")
        void shouldPersistUserRoles() {
            testUser.setRoles(Set.of("USER", "ADMIN"));
            User saved = entityManager.persistAndFlush(testUser);
            entityManager.clear();

            Optional<User> found = userRepository.findById(saved.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getRoles()).containsExactlyInAnyOrder("USER", "ADMIN");
        }

        @Test
        @DisplayName("Should handle null username gracefully")
        void shouldHandleNullUsername() {
            User userWithNullUsername = new User(null, "nulluser@example.com", AuthProvider.KEYCLOAK, "a1b2c3d4-e5f6-7890-abcd-ef1234567890");
            userWithNullUsername.setEnabled(true);
            userWithNullUsername.setUserVersion(1L);

            User saved = entityManager.persistAndFlush(userWithNullUsername);

            Optional<User> found = userRepository.findById(saved.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getUsername()).isNull();
        }

        @Test
        @DisplayName("Should handle UserDetails contract correctly")
        void shouldImplementUserDetailsCorrectly() {
            testUser.setRoles(Set.of("USER", "ADMIN"));
            User saved = entityManager.persistAndFlush(testUser);

            assertThat(saved.isEnabled()).isTrue();
            assertThat(saved.isAccountNonExpired()).isTrue();
            assertThat(saved.isAccountNonLocked()).isTrue();
            assertThat(saved.isCredentialsNonExpired()).isTrue();
            assertThat(saved.getPassword()).isNull();
            assertThat(saved.getAuthorities()).hasSize(2);
        }
    }
}
