package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin list the two-factor enforcement sweeps: every enabled platform admin Keycloak
 * knows, each once. A disabled admin or one without a provider id cannot be armed in
 * Keycloak, and an admin counted twice (one row per role in the join) would be armed twice.
 * H2 in PostgreSQL mode, like {@link UserRepositoryLifecycleContextTest}.
 */
@DataJpaTest
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
        replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = UserRepositoryEnabledAdminsTest.JpaOnly.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_enabled_admins;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS auth",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("UserRepository - enabled admins for the two-factor sweep")
class UserRepositoryEnabledAdminsTest {

    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    static class JpaOnly {
    }

    @Autowired
    private UserRepository repository;

    @Autowired
    private EntityManager entityManager;

    private Long persist(String providerId, boolean enabled, String... roles) {
        User user = new User();
        user.setProviderId(providerId);
        user.setEmail(System.nanoTime() + "@test.com");
        user.setUsername("u" + System.nanoTime());
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        user.setEnabled(enabled);
        user.setRoles(Set.of(roles));
        Long id = repository.saveAndFlush(user).getId();
        entityManager.clear();
        return id;
    }

    @Test
    @DisplayName("returns each enabled admin with a provider id once, and nobody else")
    void onlyEnabledAdminsKnownToKeycloak() {
        Long admin = persist("kc-admin", true, "USER", "ADMIN");
        persist("kc-disabled", false, "ADMIN");
        persist(null, true, "ADMIN");
        persist("kc-user", true, "USER");

        assertThat(repository.findEnabledAdminsWithProviderId())
                .extracting(User::getId)
                .containsExactly(admin);
    }
}
