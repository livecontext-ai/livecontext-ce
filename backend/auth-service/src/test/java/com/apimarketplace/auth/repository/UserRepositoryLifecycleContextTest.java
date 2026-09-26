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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-persistence tests of the conditional UPDATEs behind the lifecycle context (V527).
 * The service tests stub the rowcounts; these prove the WHERE clauses themselves: explicit
 * locale precedence, write-once country and IP, activation once, the 12-month IP purge.
 * H2 in PostgreSQL mode, like {@link UserRepositoryAuthenticationAdvanceTest}.
 */
@DataJpaTest
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
        replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = UserRepositoryLifecycleContextTest.JpaOnly.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_lifecycle;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS auth",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("UserRepository - lifecycle context conditional writes")
class UserRepositoryLifecycleContextTest {

    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    static class JpaOnly {
    }

    @Autowired
    private UserRepository repository;

    @Autowired
    private EntityManager entityManager;

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    private Long persistUser() {
        User user = new User();
        user.setProviderId("kc-" + System.nanoTime());
        user.setEmail(System.nanoTime() + "@test.com");
        user.setUsername("u" + System.nanoTime());
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        Long id = repository.saveAndFlush(user).getId();
        entityManager.clear();
        return id;
    }

    private User reload(Long id) {
        entityManager.clear();
        return repository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("an implicit locale is written while nothing was picked, and then never overrides a pick")
    void implicitNeverOverridesExplicit() {
        Long id = persistUser();

        assertThat(repository.updateLocaleImplicit(id, "fr")).isEqualTo(1);
        assertThat(repository.updateLocaleExplicit(id, "de")).isEqualTo(1);
        assertThat(repository.updateLocaleImplicit(id, "es")).isZero();

        User user = reload(id);
        assertThat(user.getLocale()).isEqualTo("de");
        assertThat(user.isLocaleExplicit()).isTrue();
    }

    @Test
    @DisplayName("an explicit pick of the SAME locale still pins it, and repeating it changes nothing")
    void explicitSameLocalePins() {
        Long id = persistUser();
        repository.updateLocaleImplicit(id, "fr");

        assertThat(repository.updateLocaleExplicit(id, "fr")).isEqualTo(1);
        assertThat(repository.updateLocaleExplicit(id, "fr")).isZero();
        assertThat(reload(id).isLocaleExplicit()).isTrue();
    }

    @Test
    @DisplayName("an unchanged implicit locale or time zone reports no change, so no sync follows")
    void unchangedValuesReportZero() {
        Long id = persistUser();
        repository.updateLocaleImplicit(id, "fr");
        repository.updateTimeZone(id, "Europe/Paris");

        assertThat(repository.updateLocaleImplicit(id, "fr")).isZero();
        assertThat(repository.updateTimeZone(id, "Europe/Paris")).isZero();
        assertThat(repository.updateTimeZone(id, "Asia/Tokyo")).isEqualTo(1);
    }

    @Test
    @DisplayName("the signup country is write-once")
    void countryWriteOnce() {
        Long id = persistUser();

        assertThat(repository.captureSignupCountry(id, "FR")).isEqualTo(1);
        assertThat(repository.captureSignupCountry(id, "US")).isZero();
        assertThat(reload(id).getSignupCountry()).isEqualTo("FR");
    }

    @Test
    @DisplayName("the signup IP is write-once, and a purged IP is never captured again")
    void ipWriteOnceEvenAfterPurge() {
        Long id = persistUser();
        Instant capturedAt = NOW.minus(400, ChronoUnit.DAYS);

        assertThat(repository.captureSignupIp(id, "203.0.113.7", capturedAt)).isEqualTo(1);
        assertThat(repository.captureSignupIp(id, "198.51.100.1", NOW)).isZero();

        assertThat(repository.purgeSignupIpsCapturedBefore(NOW.minus(365, ChronoUnit.DAYS))).isEqualTo(1);
        User purged = reload(id);
        assertThat(purged.getSignupIp()).isNull();
        assertThat(purged.getSignupIpCapturedAt()).isEqualTo(capturedAt);

        assertThat(repository.captureSignupIp(id, "198.51.100.1", NOW)).isZero();
        assertThat(reload(id).getSignupIp()).isNull();
    }

    @Test
    @DisplayName("the purge leaves an IP captured inside the retention window")
    void purgeKeepsRecentIps() {
        Long id = persistUser();
        repository.captureSignupIp(id, "203.0.113.7", NOW.minus(30, ChronoUnit.DAYS));

        assertThat(repository.purgeSignupIpsCapturedBefore(NOW.minus(365, ChronoUnit.DAYS))).isZero();
        assertThat(reload(id).getSignupIp()).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("activation reports one row the first time only")
    void activationOnce() {
        Long id = persistUser();

        assertThat(repository.markActivatedIfFirst(id, NOW)).isEqualTo(1);
        assertThat(repository.markActivatedIfFirst(id, NOW.plusSeconds(60))).isZero();
        assertThat(reload(id).getActivatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Regression (double welcome): the signup stamp is write-once, a second or racing claim updates no row")
    void signupStampWriteOnce() {
        Long id = persistUser();

        assertThat(repository.markSignupEmittedIfFirst(id, NOW)).isEqualTo(1);
        assertThat(repository.markSignupEmittedIfFirst(id, NOW.plusSeconds(1))).isZero();
        assertThat(reload(id).getLifecycleSignupEmittedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Regression (welcome lost): a release clears the stamp only while it still holds the releaser's own value")
    void signupReleaseClearsOnlyItsOwnStamp() {
        Long id = persistUser();
        Instant mine = NOW.plusNanos(123_456_000);
        assertThat(repository.markSignupEmittedIfFirst(id, mine)).isEqualTo(1);

        assertThat(repository.releaseSignupEmitted(id, NOW)).as("another attempt's stamp").isZero();
        assertThat(reload(id).getLifecycleSignupEmittedAt()).isEqualTo(mine);

        assertThat(repository.releaseSignupEmitted(id, mine)).isEqualTo(1);
        assertThat(reload(id).getLifecycleSignupEmittedAt()).isNull();
        assertThat(repository.markSignupEmittedIfFirst(id, NOW.plusSeconds(60)))
                .as("a later attempt can claim it again").isEqualTo(1);
    }

    @Test
    @DisplayName("a stale whole-row save never rewinds the signup stamp (column mapped read-only)")
    void staleSaveNeverRewindsSignupStamp() {
        Long id = persistUser();
        User stale = reload(id);
        entityManager.detach(stale);
        repository.markSignupEmittedIfFirst(id, NOW);

        repository.saveAndFlush(stale);

        assertThat(reload(id).getLifecycleSignupEmittedAt()).isEqualTo(NOW);
        assertThat(repository.markSignupEmittedIfFirst(id, NOW.plusSeconds(60))).isZero();
    }

    @Test
    @DisplayName("marketing consent is stored with its instant")
    void consentStored() {
        Long id = persistUser();

        assertThat(repository.updateMarketingConsent(id, true, NOW)).isEqualTo(1);
        User user = reload(id);
        assertThat(user.isMarketingConsent()).isTrue();
        assertThat(user.getMarketingConsentAt()).isEqualTo(NOW);
    }
    @Test
    @DisplayName("a stale whole-row save after a consent change does NOT revert the consent (regression)")
    void staleWholeRowSaveNeverRevertsConsent() {
        Long id = persistUser();
        // A request loads the row BEFORE the consent change (consent still off)...
        User stale = reload(id);
        entityManager.detach(stale);

        // ...the person opts in meanwhile...
        assertThat(repository.updateMarketingConsent(id, true, NOW)).isEqualTo(1);

        // ...then the first request writes its whole (stale) row back, e.g. login bookkeeping.
        stale.setFirstName("Renamed");
        repository.saveAndFlush(stale);

        User after = reload(id);
        assertThat(after.getFirstName()).isEqualTo("Renamed");
        assertThat(after.isMarketingConsent()).isTrue();
        assertThat(after.getMarketingConsentAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a stale whole-row save never reverts locale, time zone, country, IP or activation either")
    void staleWholeRowSaveNeverRevertsOtherLifecycleColumns() {
        Long id = persistUser();
        User stale = reload(id);
        entityManager.detach(stale);

        repository.updateLocaleExplicit(id, "fr");
        repository.updateTimeZone(id, "Europe/Paris");
        repository.captureSignupCountry(id, "FR");
        repository.captureSignupIp(id, "203.0.113.7", NOW);
        repository.markActivatedIfFirst(id, NOW);

        // Even a stale copy whose in-memory lifecycle fields were set is never written.
        stale.setLocale("de");
        stale.setMarketingConsent(true);
        repository.saveAndFlush(stale);

        User after = reload(id);
        assertThat(after.getLocale()).isEqualTo("fr");
        assertThat(after.isLocaleExplicit()).isTrue();
        assertThat(after.getTimeZone()).isEqualTo("Europe/Paris");
        assertThat(after.getSignupCountry()).isEqualTo("FR");
        assertThat(after.getSignupIp()).isEqualTo("203.0.113.7");
        assertThat(after.getActivatedAt()).isEqualTo(NOW);
        assertThat(after.isMarketingConsent()).isFalse();
    }

    @Test
    @DisplayName("a new user is created with the database defaults: no locale, consent off, not activated")
    void newUserGetsDatabaseDefaults() {
        User user = new User();
        user.setProviderId("kc-" + System.nanoTime());
        user.setEmail(System.nanoTime() + "@test.com");
        user.setUsername("u" + System.nanoTime());
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        user.setMarketingConsent(true); // in-memory only: never inserted
        Long id = repository.saveAndFlush(user).getId();

        User created = reload(id);
        assertThat(created.getLocale()).isNull();
        assertThat(created.isLocaleExplicit()).isFalse();
        assertThat(created.isMarketingConsent()).isFalse();
        assertThat(created.getActivatedAt()).isNull();
    }
}
