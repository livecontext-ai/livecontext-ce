package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PasswordResetToken;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-persistence tests for the reset-token queries, and above all for the ONE
 * predicate the single-use guarantee rests on.
 *
 * <p>Every service test hands this repository to Mockito, which certifies the
 * author's belief about the JPQL rather than the JPQL. Measured: dropping
 * {@code AND t.usedAt IS NULL} from {@link PasswordResetTokenRepository#markUsed}
 * left the whole reset suite green (62 tests), because
 * {@code concurrentRedemptionLoserIsRefused} STUBS the return value it then
 * asserts on. That is the shape AGENTS.md warns about: a unit test that
 * fabricates a derived value proves nothing about the thing that derives it.
 *
 * <p>So this boots a real EntityManager. It cannot reproduce a two-transaction
 * race (H2 in one transaction), but it proves the thing that makes the race
 * safe: the UPDATE is conditional, and a second claim of the same row affects
 * ZERO rows rather than succeeding again. The PostgreSQL-specific half, that a
 * concurrent transaction blocks on the row lock and then re-evaluates the
 * predicate, is documented on the repository method and is not testable here.
 */
@DataJpaTest
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
        replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = PasswordResetTokenRepositoryPersistenceTest.JpaOnly.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pwreset_tokens;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS auth",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("PasswordResetToken persistence - the single-use predicate against a real EntityManager")
class PasswordResetTokenRepositoryPersistenceTest {

    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = PasswordResetToken.class)
    @EnableJpaRepositories(basePackageClasses = PasswordResetTokenRepository.class)
    static class JpaOnly {
    }

    @Autowired
    private PasswordResetTokenRepository repository;

    @Autowired
    private EntityManager entityManager;

    private static final long USER = 7L;
    private static final long OTHER_USER = 99L;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        entityManager.flush();
        entityManager.clear();
    }

    private PasswordResetToken save(long userId, String hash, LocalDateTime expiresAt,
                                    LocalDateTime usedAt) {
        PasswordResetToken row = new PasswordResetToken();
        row.setUserId(userId);
        row.setTokenHash(hash);
        row.setCreatedAt(LocalDateTime.now());
        row.setExpiresAt(expiresAt);
        row.setUsedAt(usedAt);
        PasswordResetToken saved = repository.save(row);
        entityManager.flush();
        entityManager.clear();
        return saved;
    }

    private PasswordResetToken live(String hash) {
        return save(USER, hash, LocalDateTime.now().plusHours(1), null);
    }

    @Test
    @DisplayName("markUsed claims an unspent token and reports exactly one row")
    void markUsedClaimsALiveToken() {
        PasswordResetToken row = live("hash-live");

        int claimed = repository.markUsed(row.getId(), LocalDateTime.now());

        assertThat(claimed).isEqualTo(1);
        entityManager.clear();
        assertThat(repository.findById(row.getId()))
                .get()
                .extracting(PasswordResetToken::getUsedAt)
                .isNotNull();
    }

    @Test
    @DisplayName("THE single-use guarantee: claiming the SAME row a second time affects ZERO rows")
    void markUsedIsConditional() {
        PasswordResetToken row = live("hash-twice");

        int first = repository.markUsed(row.getId(), LocalDateTime.now());
        entityManager.clear();
        int second = repository.markUsed(row.getId(), LocalDateTime.now());

        assertThat(first).isEqualTo(1);
        // Drop `AND t.usedAt IS NULL` from the query and this becomes 1, which is
        // a reset link that works twice: two different people, each holding the
        // same forwarded e-mail, both get to set the password.
        assertThat(second).isZero();
    }

    @Test
    @DisplayName("markUsed does not touch an already-spent row's timestamp, so the audit trail keeps "
            + "the FIRST redemption")
    void markUsedLeavesTheOriginalTimestamp() {
        LocalDateTime firstUse = LocalDateTime.now().minusMinutes(30).withNano(0);
        PasswordResetToken spent = save(USER, "hash-spent", LocalDateTime.now().plusHours(1), firstUse);

        int claimed = repository.markUsed(spent.getId(), LocalDateTime.now());

        assertThat(claimed).isZero();
        entityManager.clear();
        assertThat(repository.findById(spent.getId()))
                .get()
                .extracting(PasswordResetToken::getUsedAt)
                .isEqualTo(firstUse);
    }

    @Test
    @DisplayName("markUsed on an id that does not exist reports zero rather than failing")
    void markUsedOnAMissingRow() {
        assertThat(repository.markUsed(4_242L, LocalDateTime.now())).isZero();
    }

    @Test
    @DisplayName("findByTokenHash resolves, and finds nothing for a hash that was never stored")
    void findByTokenHashResolves() {
        live("hash-findable");

        assertThat(repository.findByTokenHash("hash-findable")).isPresent();
        assertThat(repository.findByTokenHash("hash-never-issued")).isEmpty();
    }

    @Test
    @DisplayName("invalidateLiveTokens burns only THIS user's unspent rows, and leaves spent ones alone")
    void invalidateLiveTokensIsScoped() {
        PasswordResetToken mineLive = live("hash-mine-live");
        LocalDateTime alreadyUsed = LocalDateTime.now().minusHours(2).withNano(0);
        PasswordResetToken mineSpent = save(USER, "hash-mine-spent",
                LocalDateTime.now().plusHours(1), alreadyUsed);
        PasswordResetToken theirs = save(OTHER_USER, "hash-theirs",
                LocalDateTime.now().plusHours(1), null);

        int burned = repository.invalidateLiveTokens(USER, LocalDateTime.now());

        assertThat(burned).isEqualTo(1);
        entityManager.clear();
        assertThat(repository.findById(mineLive.getId()).orElseThrow().getUsedAt()).isNotNull();
        // Not re-stamped: the original redemption time is the useful one.
        assertThat(repository.findById(mineSpent.getId()).orElseThrow().getUsedAt())
                .isEqualTo(alreadyUsed);
        // Another account's pending reset must survive: this runs on every
        // issuance, so a cross-user leak here would cancel strangers' links.
        assertThat(repository.findById(theirs.getId()).orElseThrow().getUsedAt()).isNull();
    }

    @Test
    @DisplayName("countByUserSince counts this user's rows strictly after the cutoff, spent ones included")
    void countByUserSinceIsScopedAndInclusive() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        save(USER, "hash-recent-live", LocalDateTime.now().plusHours(1), null);
        // Spent rows still count: redeeming a link must not buy a fresh allowance.
        save(USER, "hash-recent-spent", LocalDateTime.now().plusHours(1), LocalDateTime.now());
        PasswordResetToken old = save(USER, "hash-old", LocalDateTime.now().plusHours(1), null);
        old.setCreatedAt(LocalDateTime.now().minusDays(2));
        repository.save(old);
        save(OTHER_USER, "hash-theirs-recent", LocalDateTime.now().plusHours(1), null);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.countByUserSince(USER, cutoff)).isEqualTo(2);
    }

    @Test
    @DisplayName("deleteExpiredBefore removes rows past the cutoff and keeps live ones")
    void deleteExpiredBeforeResolves() {
        PasswordResetToken stale = save(USER, "hash-stale",
                LocalDateTime.now().minusDays(30), null);
        PasswordResetToken current = live("hash-current");

        int deleted = repository.deleteExpiredBefore(LocalDateTime.now().minusDays(7));

        assertThat(deleted).isEqualTo(1);
        entityManager.clear();
        assertThat(repository.findById(stale.getId())).isEmpty();
        assertThat(repository.findById(current.getId())).isPresent();
    }

    /*
     * There is deliberately no test here for the token_hash UNIQUE index, and it
     * is worth saying why: this class runs on `ddl-auto=create-drop`, so the
     * schema comes from the ENTITY mapping, while the index is declared in
     * V487__ce_password_reset_tokens.sql with Flyway disabled. A test asserting
     * it would either fail (measured) or, worse, be "fixed" by adding
     * `unique = true` to the entity purely to satisfy the test, which would
     * assert the fixture rather than the shipped schema. The index is a migration
     * property and belongs to whatever verifies migrations against a real engine.
     */
}
