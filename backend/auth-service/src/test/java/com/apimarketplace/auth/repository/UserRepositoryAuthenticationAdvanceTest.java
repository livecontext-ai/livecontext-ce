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

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-persistence tests for the one statement the whole login count rests on.
 *
 * <p>Every other test of this feature hands {@link UserRepository} to Mockito and stubs the
 * rowcount, which exercises the caller and proves nothing at all about the comparison
 * itself. The comparison is the feature: get the direction wrong, or write {@code <=}
 * instead of {@code <}, and either every request counts a login again or no request ever
 * does. Both failures are silent, and a mock returns whatever it was told either way.
 *
 * <p>Runs on H2 in PostgreSQL mode, not on Postgres. That is enough for the comparison
 * semantics under test and is NOT a substitute for the migration replay in
 * migration-service, which uses a real engine.
 */
@DataJpaTest
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
        replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = UserRepositoryAuthenticationAdvanceTest.JpaOnly.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_advance;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS auth",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("UserRepository - advancing the authentication instant")
class UserRepositoryAuthenticationAdvanceTest {

    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    static class JpaOnly {
    }

    @Autowired
    private UserRepository repository;

    @Autowired
    private EntityManager entityManager;

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 9, 17, 12, 0, 0);

    private User persistUser(LocalDateTime lastAuthenticatedAt) {
        User user = new User();
        user.setProviderId("kc-" + System.nanoTime());
        user.setEmail(System.nanoTime() + "@test.com");
        user.setUsername("u" + System.nanoTime());
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        user.setLastAuthenticatedAt(lastAuthenticatedAt);
        User saved = repository.saveAndFlush(user);
        entityManager.clear();
        return saved;
    }

    private LocalDateTime reload(Long id) {
        entityManager.clear();
        return repository.findById(id).orElseThrow().getLastAuthenticatedAt();
    }

    @Test
    @DisplayName("a never-seen account (NULL) accepts the first authentication")
    void nullAccepts() {
        User user = persistUser(null);

        assertThat(repository.recordAuthenticationIfNewer(user.getId(), NOON)).isEqualTo(1);
        assertThat(reload(user.getId())).isEqualTo(NOON);
    }

    @Test
    @DisplayName("a strictly newer authentication advances the column and reports one row")
    void newerAdvances() {
        User user = persistUser(NOON);

        assertThat(repository.recordAuthenticationIfNewer(user.getId(), NOON.plusHours(1))).isEqualTo(1);
        assertThat(reload(user.getId())).isEqualTo(NOON.plusHours(1));
    }

    @Test
    @DisplayName("the SAME instant is a no-op: re-presenting one token counts nothing")
    void equalIsANoOp() {
        User user = persistUser(NOON);

        // This is the refresh case and the reason the comparison is < and not <=. Every
        // request of a live session carries the same auth_time; if equality matched, the
        // fix would reproduce the exact bug it replaced, at request rate instead of
        // every ten minutes.
        assertThat(repository.recordAuthenticationIfNewer(user.getId(), NOON)).isZero();
        assertThat(reload(user.getId())).isEqualTo(NOON);
    }

    @Test
    @DisplayName("an OLDER authentication never moves the column backwards")
    void olderIsRejected() {
        User user = persistUser(NOON);

        // The monotonicity that makes two live sessions safe. A person signed in on a
        // laptop and a phone alternates between two tokens with two different auth_times;
        // if the older one could win, every alternation would look like a new sign-in.
        assertThat(repository.recordAuthenticationIfNewer(user.getId(), NOON.minusHours(1))).isZero();
        assertThat(reload(user.getId())).isEqualTo(NOON);
    }

    @Test
    @DisplayName("only the FIRST observer of a new session sees the transition, the rest see none")
    void onlyTheFirstObserverOfASessionSeesTheTransition() {
        User user = persistUser(NOON);
        LocalDateTime newSession = NOON.plusMinutes(30);

        // Several resolves can carry the same fresh token (two gateway replicas with cold
        // caches, or a cache entry dropped mid-page-load). Exactly one may report a login,
        // or one sign-in becomes several.
        int first = repository.recordAuthenticationIfNewer(user.getId(), newSession);
        int second = repository.recordAuthenticationIfNewer(user.getId(), newSession);
        int third = repository.recordAuthenticationIfNewer(user.getId(), newSession);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(third).isZero();
    }

    // WHAT THIS CLASS DOES NOT PROVE. The calls above are SEQUENTIAL and share one
    // transaction, so the test above is idempotence, not race safety: it would pass against
    // an implementation that reads and then writes in two statements. What actually makes
    // the advance race-free is that the comparison and the write are a single UPDATE, which
    // is a property of the statement rather than of an observation. Two threads here would
    // not help either, since @DataJpaTest wraps the test in one transaction and H2 locking
    // is not Postgres locking. Keep the single-statement form; a green run here is not
    // permission to split it.

    @Test
    @DisplayName("the advance leaves last_login_at alone: one column per concern")
    void doesNotTouchLastSeen() {
        User user = persistUser(NOON);
        user.setLastLoginAt(NOON.minusDays(2));
        repository.saveAndFlush(user);
        entityManager.clear();

        repository.recordAuthenticationIfNewer(user.getId(), NOON.plusHours(2));

        entityManager.clear();
        assertThat(repository.findById(user.getId()).orElseThrow().getLastLoginAt())
                .isEqualTo(NOON.minusDays(2));
    }

    @Test
    @DisplayName("the last-seen throttle still writes independently, and is not a login signal")
    void lastSeenThrottleStillWorks() {
        User user = persistUser(NOON);
        user.setLastLoginAt(NOON.minusHours(1));
        repository.saveAndFlush(user);
        entityManager.clear();

        int rows = repository.updateLastLoginIfStale(
                user.getId(), NOON.plusHours(5), NOON.plusHours(5).minusMinutes(10));

        assertThat(rows).isEqualTo(1);
        entityManager.clear();
        assertThat(repository.findById(user.getId()).orElseThrow().getLastAuthenticatedAt())
                .as("writing last_login_at must not silently advance the authentication instant")
                .isEqualTo(NOON);
    }
}
