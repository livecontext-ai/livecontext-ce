package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Postgres proof of observation and single-winner claims across two service instances. */
class PersonalOfferLifecycleRepositoryTest {
    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG", "it verifies the durable personal-offer email claim across pods");
    private static final Instant T0 = Instant.parse("2026-09-29T12:00:00Z");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void schema() throws Exception {
        DB.require();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.url(), DB.user(), DB.password()));
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.personal_offer_lifecycle_claim_test");
        jdbc.execute("""
                CREATE TABLE auth.personal_offer_lifecycle_claim_test (
                    user_id BIGINT NOT NULL, campaign_key VARCHAR(64) NOT NULL,
                    exhausted_at TIMESTAMPTZ, offer_code_id BIGINT,
                    initial_status VARCHAR(16) NOT NULL DEFAULT 'pending',
                    initial_claimed_at TIMESTAMPTZ, initial_accepted_at TIMESTAMPTZ,
                    reminder_status VARCHAR(16) NOT NULL DEFAULT 'pending',
                    reminder_claimed_at TIMESTAMPTZ, reminder_accepted_at TIMESTAMPTZ,
                    stopped_reason VARCHAR(32), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (user_id, campaign_key))
                """);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE auth.personal_offer_lifecycle_claim_test");
    }

    private PersonalOfferLifecycleRepository pod() {
        return new PersonalOfferLifecycleRepository(new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                return super.update(sql.replace("auth.personal_offer_lifecycle",
                        "auth.personal_offer_lifecycle_claim_test"), args);
            }
        });
    }

    @Test
    void firstObservationSurvivesLaterScansAndResetStartsNewWait() {
        var first = pod();
        first.observe(7L, T0);
        first.observe(7L, T0.plusSeconds(900));
        assertThat(jdbc.queryForObject("SELECT exhausted_at FROM auth.personal_offer_lifecycle_claim_test WHERE user_id=7",
                java.sql.Timestamp.class).toInstant()).isEqualTo(T0);

        first.resetUnissued(7L, T0.plusSeconds(1200));
        first.observe(7L, T0.plusSeconds(1500));
        assertThat(jdbc.queryForObject("SELECT exhausted_at FROM auth.personal_offer_lifecycle_claim_test WHERE user_id=7",
                java.sql.Timestamp.class).toInstant()).isEqualTo(T0.plusSeconds(1500));
    }

    @Test
    void twoPodsCannotClaimSameEventAndAmbiguousOutcomeIsHeld() throws Exception {
        var a = pod();
        var b = pod();
        a.observe(7L, T0);
        a.attachIssued(7L, 14L, T0);
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> { gate.await(); return a.claim(7L,
                    PersonalOfferLifecycleRepository.Step.INITIAL, T0); });
            var two = workers.submit(() -> { gate.await(); return b.claim(7L,
                    PersonalOfferLifecycleRepository.Step.INITIAL, T0); });
            gate.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS) ^ two.get(10, TimeUnit.SECONDS)).isTrue();
        }
        a.finish(7L, PersonalOfferLifecycleRepository.Step.INITIAL, T0,
                ResendClient.EventResult.UNKNOWN, T0.plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT initial_status FROM auth.personal_offer_lifecycle_claim_test WHERE user_id=7",
                String.class)).isEqualTo("unknown");
        assertThat(b.claim(7L, PersonalOfferLifecycleRepository.Step.INITIAL, T0.plusSeconds(2))).isFalse();
    }
}
