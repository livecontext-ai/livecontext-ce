package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invariant the whole backoff rests on, on a real JPA stack: the backoff columns are written
 * ONLY by {@code updateBackoff}. The applier's success status and the scheduler's failure
 * bookkeeping both save the whole row from an in-memory copy; if that save wrote the backoff
 * columns back, it would silently undo the wait armed a moment earlier, and a broken install
 * would download on every tick again. Mocks cannot show this, only Hibernate's generated UPDATE.
 *
 * <p>Runs with no surrounding transaction, like the scheduler.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        // The app's default_schema is `catalog`: create it up front, as the sibling Tx test does.
        // TIME ZONE=UTC: the service writes timestamps as UTC (hibernate.jdbc.time_zone), and
        // Postgres receives an explicit offset; H2 instead re-reads a zoneless value in its
        // SESSION zone, which on a non-UTC dev machine shifts every instant by that offset.
        "spring.datasource.url=jdbc:h2:mem:backoffjpa;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                + "TIME ZONE=UTC;INIT=CREATE SCHEMA IF NOT EXISTS catalog",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("bundle-backoff-slice")
@DisplayName("api_catalog_bundle_sync_status - backoff columns survive whole-row saves")
class ApiCatalogBundleSyncStatusBackoffJpaTest {

    @SpringBootConfiguration
    @Profile("bundle-backoff-slice")
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ApiCatalogBundleEntity.class)
    @EnableJpaRepositories(
            basePackageClasses = ApiCatalogBundleSyncStatusRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = ApiCatalogBundleSyncStatusRepository.class))
    static class SliceConfig {
    }

    @Autowired private ApiCatalogBundleSyncStatusRepository repo;

    @BeforeEach
    void seedRow() {
        repo.deleteAll();
        // The same singleton V331 seeds; a fresh insert must get the column default (0, NULL).
        repo.save(new ApiCatalogBundleSyncStatusEntity());
    }

    @Test
    @DisplayName("a fresh row reads level 0 and no wait (the insert never writes the backoff columns)")
    void freshRowHasNoBackoff() {
        ApiCatalogBundleSyncStatusEntity row = load();
        assertThat(row.getBackoffLevel()).isZero();
        assertThat(row.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("the wait instant reads back EXACTLY whatever the JVM default time zone (a CE container may set TZ)")
    void nextAttemptSurvivesANonUtcJvm() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));   // +05:30
            Instant next = Instant.parse("2026-09-26T12:00:00Z");

            repo.updateBackoff(2, next);

            assertThat(load().getNextAttemptAt()).isEqualTo(next);
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("updateBackoff writes exactly the singleton row and reads back")
    void updateBackoffRoundTrips() {
        Instant next = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

        int updated = repo.updateBackoff(4, next);

        assertThat(updated).isEqualTo(1);
        ApiCatalogBundleSyncStatusEntity row = load();
        assertThat(row.getBackoffLevel()).isEqualTo(4);
        assertThat(row.getNextAttemptAt()).isEqualTo(next);
    }

    @Test
    @DisplayName("Regression: a whole-row save from a STALE copy (what the applier and recordFailure do) never undoes the backoff")
    void staleWholeRowSaveKeepsBackoff() {
        ApiCatalogBundleSyncStatusEntity staleCopy = load();          // level 0, no wait
        Instant next = Instant.now().plus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        repo.updateBackoff(6, next);                                   // the scheduler arms the wait

        staleCopy.setLastFetchStatus("APPLY_FAILED");                  // the bookkeeping saves its copy
        staleCopy.setConsecutiveFailures(9);
        staleCopy.setBackoffLevel(0);                                  // even an explicit in-memory reset
        staleCopy.setNextAttemptAt(null);
        repo.save(staleCopy);

        ApiCatalogBundleSyncStatusEntity row = load();
        assertThat(row.getLastFetchStatus()).isEqualTo("APPLY_FAILED");
        assertThat(row.getConsecutiveFailures()).isEqualTo(9);
        assertThat(row.getBackoffLevel()).isEqualTo(6);
        assertThat(row.getNextAttemptAt()).isEqualTo(next);
    }

    @Test
    @DisplayName("clearing (0, NULL) is honoured")
    void clearRoundTrips() {
        repo.updateBackoff(3, Instant.now().plus(1, ChronoUnit.HOURS));

        repo.updateBackoff(0, null);

        ApiCatalogBundleSyncStatusEntity row = load();
        assertThat(row.getBackoffLevel()).isZero();
        assertThat(row.getNextAttemptAt()).isNull();
    }

    private ApiCatalogBundleSyncStatusEntity load() {
        return repo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID).orElseThrow();
    }
}
