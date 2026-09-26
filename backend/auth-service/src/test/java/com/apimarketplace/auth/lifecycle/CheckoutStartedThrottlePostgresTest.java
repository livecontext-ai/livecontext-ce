package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checkout.started throttle on a real Postgres, with the columns added by V529 itself:
 * several throttle instances stand for several auth-service pods sharing one database.
 */
@DisplayName("CheckoutStartedThrottle (real Postgres, several pods)")
class CheckoutStartedThrottlePostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only proof that opening the checkout on several pods starts ONE recovery "
                    + "sequence per 24 hours, not one per pod");

    private static final Instant T0 = Instant.parse("2026-09-24T10:00:00Z");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void schema() throws Exception {
        DB.require();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.url(), DB.user(), DB.password()));
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.lifecycle_throttle_users CASCADE");
        // A private copy of auth.users (only the key), renamed so this class never drops the
        // users table another test class of the same scratch database relies on.
        jdbc.execute("CREATE TABLE auth.lifecycle_throttle_users (id BIGINT PRIMARY KEY)");
        String v529 = Files.readString(Path.of("..", "migration-service", "src", "main", "resources", "db",
                "migration", "V529__lifecycle_trophies_recap_checkout_throttle.sql"), StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*--.*$", "");
        String ddl = null;
        for (String s : v529.split(";")) {
            if (s.contains("ALTER TABLE auth.users")) ddl = s.trim();
        }
        assertThat(ddl).as("V529 adds the throttle columns to auth.users").isNotNull();
        jdbc.execute(ddl.replace("auth.users", "auth.lifecycle_throttle_users"));
        // The caller's own table, and a users table WITHOUT the V529 columns (V529 not applied).
        jdbc.execute("DROP TABLE IF EXISTS auth.lifecycle_throttle_checkouts CASCADE");
        jdbc.execute("CREATE TABLE auth.lifecycle_throttle_checkouts (id BIGINT PRIMARY KEY)");
        jdbc.execute("DROP TABLE IF EXISTS auth.lifecycle_throttle_users_pre_v529 CASCADE");
        jdbc.execute("CREATE TABLE auth.lifecycle_throttle_users_pre_v529 (id BIGINT PRIMARY KEY)");
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE auth.lifecycle_throttle_users, auth.lifecycle_throttle_checkouts, "
                + "auth.lifecycle_throttle_users_pre_v529");
        jdbc.update("INSERT INTO auth.lifecycle_throttle_users_pre_v529 (id) VALUES (7)");
        jdbc.update("INSERT INTO auth.lifecycle_throttle_users (id) VALUES (7), (8)");
    }

    /** One pod: the production throttle, its SQL pointed at the private copy of the table. */
    private static CheckoutStartedThrottle pod(Instant now) {
        return pod(now, "auth.lifecycle_throttle_users");
    }

    private static CheckoutStartedThrottle pod(Instant now, String usersTable) {
        return new CheckoutStartedThrottle(redirected(usersTable),
                CheckoutStartedThrottle.requiresNew(new DataSourceTransactionManager(jdbc.getDataSource())),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static JdbcTemplate redirected(String usersTable) {
        return new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                return super.update(sql.replace("auth.users", usersTable), args);
            }
        };
    }

    private static TransactionTemplate callerTx() {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private static int checkoutsCommitted() {
        return jdbc.queryForObject("SELECT count(*) FROM auth.lifecycle_throttle_checkouts", Integer.class);
    }

    @Test
    @DisplayName("Regression (checkout lost): a failing claim inside the caller's transaction cannot roll it back")
    void failingClaimNeverAbortsTheCallersTransaction() {
        CheckoutStartedThrottle preV529 = pod(T0, "auth.lifecycle_throttle_users_pre_v529");

        Boolean granted = callerTx().execute(status -> {
            jdbc.update("INSERT INTO auth.lifecycle_throttle_checkouts (id) VALUES (1)");
            return preV529.tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION);
        });

        assertThat(granted).as("a failing claim fails closed").isFalse();
        assertThat(checkoutsCommitted()).as("the caller's write committed").isEqualTo(1);
    }

    @Test
    @DisplayName("control: the same failing UPDATE run IN the caller's transaction does lose the caller's commit")
    void sameUpdateInTheCallersTransactionLosesIt() {
        JdbcTemplate sameTx = redirected("auth.lifecycle_throttle_users_pre_v529");

        assertThatThrownBy(() -> callerTx().execute(status -> {
            jdbc.update("INSERT INTO auth.lifecycle_throttle_checkouts (id) VALUES (1)");
            try {
                sameTx.update("UPDATE auth.users SET last_checkout_subscription_at = now() WHERE id = 7");
            } catch (Exception swallowed) {
                // what a fail-closed catch without its own transaction would do
            }
            return jdbc.update("INSERT INTO auth.lifecycle_throttle_checkouts (id) VALUES (2)");
        })).isInstanceOf(Exception.class);
        assertThat(checkoutsCommitted()).isZero();
    }

    @Test
    @DisplayName("a granted claim commits on its own, even when the caller then rolls back")
    void grantedClaimIsIndependentOfTheCaller() {
        callerTx().execute(status -> {
            assertThat(pod(T0).tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION)).isTrue();
            status.setRollbackOnly();
            return null;
        });

        assertThat(pod(T0.plusSeconds(60)).tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION)).isFalse();
    }

    @Test
    @DisplayName("Regression (spam): pod B refuses what pod A let through in the same 24 hours")
    void holdsAcrossPods() {
        assertThat(pod(T0).tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION)).isTrue();

        assertThat(pod(T0.plusSeconds(60)).tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION)).isFalse();
        assertThat(pod(T0.plusSeconds(23 * 3600)).tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION)).isFalse();
    }

    @Test
    @DisplayName("the window reopens 24 hours after the last event let through, not after the last attempt")
    void reopensAfterTheWindow() {
        pod(T0).tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION);
        pod(T0.plusSeconds(3600)).tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION); // refused, records nothing

        assertThat(pod(T0.plusSeconds(24 * 3600)).tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION)).isTrue();
    }

    @Test
    @DisplayName("kinds and users are separate windows: a credits top-up never swallows a plan checkout")
    void kindsAndUsersAreSeparate() {
        assertThat(pod(T0).tryAcquire(7L, LifecycleEvents.KIND_CREDITS)).isTrue();
        assertThat(pod(T0).tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION)).isTrue();
        assertThat(pod(T0).tryAcquire(8L, LifecycleEvents.KIND_SUBSCRIPTION)).isTrue();
    }

    @Test
    @DisplayName("Regression (recovery email lost): a released window reopens, but a release never clears another pod's newer claim")
    void releaseClearsOnlyItsOwnWindow() {
        // Nanoseconds on the clock: the stamp is truncated to what TIMESTAMPTZ keeps, or the release never matches.
        LifecycleEmailService.Claim first = pod(T0.plusNanos(123_456_789)).claim(7L, LifecycleEvents.KIND_SUBSCRIPTION);
        assertThat(first.getAsBoolean()).isTrue();

        first.release();

        assertThat(jdbc.queryForObject("SELECT last_checkout_subscription_at IS NULL FROM auth.lifecycle_throttle_users "
                + "WHERE id = 7", Boolean.class)).as("the send failed: the window is free again").isTrue();
        LifecycleEmailService.Claim second = pod(T0.plusSeconds(60)).claim(7L, LifecycleEvents.KIND_SUBSCRIPTION);
        assertThat(second.getAsBoolean()).as("a later checkout can send").isTrue();

        first.release(); // late or repeated release of the OLD grant: the column now holds another stamp

        assertThat(pod(T0.plusSeconds(120)).tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION))
                .as("the second grant still holds the window").isFalse();
    }

    @Test
    @DisplayName("an unknown user claims nothing")
    void unknownUser() {
        assertThat(pod(T0).tryAcquire(999L, LifecycleEvents.KIND_SUBSCRIPTION)).isFalse();
    }

    @Test
    @DisplayName("Regression (spam): 8 pods racing on the same checkout let exactly ONE event through")
    void racingPodsLetOneThrough() throws Exception {
        int pods = 8;
        ExecutorService threads = Executors.newFixedThreadPool(pods);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < pods; i++) {
                CheckoutStartedThrottle throttle = pod(T0);
                Callable<Boolean> race = () -> {
                    start.await();
                    return throttle.tryAcquire(7L, LifecycleEvents.KIND_SUBSCRIPTION);
                };
                results.add(threads.submit(race));
            }
            start.countDown();
            int granted = 0;
            for (Future<Boolean> r : results) {
                if (r.get()) granted++;
            }

            assertThat(granted).isEqualTo(1);
        } finally {
            threads.shutdownNow();
        }
    }
}
