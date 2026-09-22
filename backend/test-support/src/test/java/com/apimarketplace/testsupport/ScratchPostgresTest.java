package com.apimarketplace.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ScratchPostgres} decides whether a SQL test runs, skips, or fails. Every consumer of it
 * is the only executable proof of some statement, so a regression here disarms all of them at
 * once and nothing else turns red: the consumers would simply report green having executed
 * nothing. That is the failure this class exists to catch, and it is why the helper was given a
 * seam for the environment rather than reading {@code System.getenv} directly - Java cannot set
 * environment variables portably, so an untestable helper is what the previous per-class copies
 * were.
 */
@DisplayName("ScratchPostgres - the gate that decides whether a SQL test runs at all")
class ScratchPostgresTest {

    private static final String PREFIX = "CREDENTIAL_TEST_PG";
    private static final String REASON = "it is the only proof of the never-destroy guarantee";

    /** An environment whose values are supplied per test, recording what was probed. */
    private static final class FakeEnvironment implements ScratchPostgres.Environment {
        private final Map<String, String> values = new HashMap<>();
        private final List<String> probed = new ArrayList<>();
        private int failuresRemaining;
        private int pauses;
        /** Makes a probe cost real time, the way an unroutable host does. */
        private long probeCostMillis;

        @Override
        public String get(String name) {
            return values.get(name);
        }

        @Override
        public void pause(Duration duration) throws InterruptedException {
            pauses++;
            // Sleep a little rather than not at all. A pause that returns instantly turns any
            // wall-clock retry loop into a busy wait: the two unreachable-host tests below used
            // to issue over a million probes in three seconds and churn ~140 MB of heap each,
            // to assert one property. 2 ms keeps the loop honest without slowing the suite.
            Thread.sleep(2);
        }

        @Override
        public void probe(String url, String user, String password) throws Exception {
            probed.add(url + "|" + user + "|" + password);
            if (probeCostMillis > 0) {
                Thread.sleep(probeCostMillis);
            }
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IllegalStateException("connection refused");
            }
        }
    }

    /** A short budget for the tests that deliberately never reach a database. */
    private static ScratchPostgres shortGate(FakeEnvironment env) {
        return ScratchPostgres.forPrefix(PREFIX, REASON, Duration.ofMillis(200), env);
    }

    private static ScratchPostgres gate(FakeEnvironment env) {
        return ScratchPostgres.forPrefix(PREFIX, REASON, Duration.ofSeconds(3), env);
    }

    @Nested
    @DisplayName("with no URL configured")
    class NoUrl {

        @Test
        @DisplayName("on a dev machine it aborts as skipped, because no scratch database is not a failure")
        void abortsLocally() {
            FakeEnvironment env = new FakeEnvironment();

            assertThatThrownBy(() -> gate(env).require())
                    .isInstanceOf(TestAbortedException.class)
                    .hasMessageContaining("CREDENTIAL_TEST_PG_URL");
        }

        @Test
        @DisplayName("on CI it FAILS instead, because a test that skips on CI is the same as no test")
        void failsOnCi() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put("CI", "true");

            assertThatThrownBy(() -> gate(env).require())
                    .as("this is the whole point: deleting the env block must break the build")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CREDENTIAL_TEST_PG_URL is unset on CI")
                    .hasMessageContaining(REASON);
        }

        @Test
        @DisplayName("a blank CI value is not CI, so a laptop exporting CI= still skips")
        void blankCiIsNotCi() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put("CI", "   ");

            assertThatThrownBy(() -> gate(env).require()).isInstanceOf(TestAbortedException.class);
        }

        @Test
        @DisplayName("a blank URL is treated as absent, not as a URL that fails to parse")
        void blankUrlIsAbsent() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "  ");

            assertThatThrownBy(() -> gate(env).require()).isInstanceOf(TestAbortedException.class);
        }
    }

    @Nested
    @DisplayName("the scratch-database guard")
    class ScratchGuard {

        @Test
        @DisplayName("refuses a database whose name does not contain 'test', because these tests truncate")
        void refusesNonScratch() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql://db.internal:5432/production");

            assertThatThrownBy(() -> gate(env).require())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must point at a scratch database")
                    .hasMessageContaining("production");
            assertThat(env.probed)
                    .as("it must refuse BEFORE connecting: a probe against production is already too far")
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts a scratch name and ignores the query string while reading it")
        void acceptsScratchName() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL",
                    "jdbc:postgresql://localhost:5432/lc_auth_test?loggerLevel=OFF");

            assertThatCode(() -> gate(env).require()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a JDBC parameter whose value is a path cannot smuggle a production URL through")
        void queryStringCannotForgeTheDatabaseName() {
            // Regression. The guard used to take the last '/' and only then strip the query
            // string, so a standard pgjdbc parameter decided the answer:
            // ...?sslrootcert=/opt/certs/test-ca.pem read as "test-ca.pem" and was accepted,
            // after which the callers DROP tables on that production database.
            for (String parameter : new String[] {
                    "sslrootcert=/opt/certs/test-ca.pem",
                    "sslcert=/etc/ssl/test.crt",
                    "loggerFile=/var/log/test.log",
            }) {
                FakeEnvironment env = new FakeEnvironment();
                env.values.put(PREFIX + "_URL", "jdbc:postgresql://db.prod:5432/livecontext?" + parameter);

                assertThatThrownBy(() -> gate(env).require())
                        .as("the database is livecontext, whatever the parameter looks like: %s", parameter)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("must point at a scratch database")
                        .hasMessageContaining("livecontext");
                assertThat(env.probed).as("it must refuse before connecting").isEmpty();
            }
        }

        @Test
        @DisplayName("a parameter that moves the target is refused, whatever the path says")
        void refusesTargetOverridingParameters() {
            // pgjdbc applies URL arguments after the path and over the Properties we pass, so
            // .../auth_test?PGDBNAME=livecontext reads as scratch here and connects to
            // livecontext there. Verified against the driver's own parser.
            for (String parameter : new String[] {
                    "PGDBNAME=livecontext", "PGHOST=db.prod", "PGPORT=6432",
                    "connectTimeout=600", "socketTimeout=0", "loginTimeout=600",
            }) {
                FakeEnvironment env = new FakeEnvironment();
                env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/auth_test?" + parameter);

                assertThatThrownBy(() -> gate(env).require())
                        .as("the path says auth_test, the parameter says otherwise: %s", parameter)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("overrides what the rest of the URL says");
                assertThat(env.probed).as("refused before connecting").isEmpty();
            }
        }

        @Test
        @DisplayName("harmless parameters are still allowed, so the rule is not a blanket ban")
        void allowsParametersThatChangeNothingImportant() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL",
                    "jdbc:postgresql://localhost:5432/auth_test?loggerLevel=OFF&ApplicationName=ci");

            assertThatCode(() -> gate(env).require()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a trailing newline does not sneak a name past the anchor")
        void refusesTrailingNewline() {
            // Java's $ matches before a final line terminator, so "auth_test\n" used to pass.
            assertThat(ScratchPostgres.isScratchName("auth_test\n")).isFalse();
            assertThat(ScratchPostgres.isScratchName("auth_test")).isTrue();
        }

        @Test
        @DisplayName("'test' must be a part of the name, so latest and contest_prod are refused")
        void refusesNamesThatMerelyContainTest() {
            for (String database : new String[] {"latest", "contest_prod", "greatest"}) {
                FakeEnvironment env = new FakeEnvironment();
                env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/" + database);

                assertThatThrownBy(() -> gate(env).require())
                        .as("a substring match would wave through %s", database)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("must point at a scratch database");
            }
        }

        @Test
        @DisplayName("the shapes the CI jobs and the local instructions actually use are accepted")
        void acceptsTheRealScratchNames() {
            for (String database : new String[] {"auth_test", "lc_auth_test", "test", "test-db", "orch_test"}) {
                FakeEnvironment env = new FakeEnvironment();
                env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/" + database);

                assertThatCode(() -> gate(env).require())
                        .as("%s is what the workflow and the class javadocs tell people to use", database)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("a URL with no path segment is refused rather than read as a scratch name")
        void refusesPathlessUrl() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql:auth_test");

            assertThatThrownBy(() -> gate(env).require())
                    .as("no slash means no database segment; guessing here would defeat the guard")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must point at a scratch database");
        }

        @Test
        @DisplayName("the name check is case-insensitive, so AUTH_TEST is a scratch database too")
        void nameCheckIsCaseInsensitive() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/AUTH_TEST");

            assertThatCode(() -> gate(env).require()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("reaching the database")
    class Reachability {

        @Test
        @DisplayName("waits for a service container that is still starting, then proceeds")
        void waitsForASlowContainer() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/auth_test");
            env.failuresRemaining = 2;

            assertThatCode(() -> gate(env).require()).doesNotThrowAnyException();
            assertThat(env.probed).hasSize(3);
            assertThat(env.pauses).isEqualTo(2);
        }

        @Test
        @DisplayName("a URL that never answers FAILS, so a broken service container cannot pass")
        void failsWhenUnreachable() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/auth_test");
            env.failuresRemaining = Integer.MAX_VALUE;

            assertThatThrownBy(() -> shortGate(env).require())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("set but the database is unreachable")
                    .cause().hasMessageContaining("connection refused");
        }

        @Test
        @DisplayName("the budget is wall clock, so a slow probe cannot multiply the timeout")
        void theBudgetIsWallClockNotAttempts() {
            // Regression, counted in PROBES rather than milliseconds. An elapsed-time bound was
            // too loose to kill the shape this test names: with `attempts = timeout.toSeconds()`
            // a 3s budget and 1.2s probes finishes in 3.6s, under any bound generous enough not
            // to flake on a loaded runner. Probe count separates the two implementations exactly.
            //
            // 4s budget, 1.5s probes. Wall clock: probe (1.5s) -> pause -> probe (3.0s) -> pause
            // -> probe (4.5s, past the deadline) = 3 probes. Attempt count would run 4, and with
            // pgjdbc's old 10s default probe it ran its full count however long that took.
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql://unroutable:5432/auth_test");
            env.failuresRemaining = Integer.MAX_VALUE;
            env.probeCostMillis = 1_500;

            assertThatThrownBy(() -> ScratchPostgres
                    .forPrefix(PREFIX, REASON, Duration.ofSeconds(4), env).require())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("within 4s");

            assertThat(env.probed.size())
                    .as("a wall-clock budget stops when the time is gone, not after N tries")
                    .isLessThanOrEqualTo(3);
        }

        @Test
        @DisplayName("a sub-second budget still gets one attempt rather than none")
        void subSecondBudgetStillTriesOnce() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/auth_test");

            assertThatCode(() -> ScratchPostgres
                    .forPrefix(PREFIX, REASON, Duration.ofMillis(200), env).require())
                    .doesNotThrowAnyException();
            assertThat(env.probed).hasSize(1);
        }

        @Test
        @DisplayName("an exhausted budget still tries once, then fails without pausing")
        void exhaustedBudgetTriesOnceAndDoesNotPause() {
            // ZERO rather than a tiny duration: this fake's pause() does not actually sleep, so
            // any non-zero budget would spin for however many no-op iterations fit inside it -
            // 15 of them for one millisecond, when this was written - and the count would depend
            // on the machine. Zero pins the property without pinning a number.
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/auth_test");
            env.failuresRemaining = Integer.MAX_VALUE;

            assertThatThrownBy(() -> ScratchPostgres
                    .forPrefix(PREFIX, REASON, Duration.ZERO, env).require())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unreachable");
            assertThat(env.probed).as("a spent budget must still buy one attempt").hasSize(1);
            assertThat(env.pauses).as("the budget is gone; sleeping again buys nothing").isZero();
        }

        @Test
        @DisplayName("an unreachable database is never mistaken for an absent one and skipped")
        void unreachableIsNotSkipped() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/auth_test");
            env.failuresRemaining = Integer.MAX_VALUE;

            assertThatThrownBy(() -> shortGate(env).require()).isNotInstanceOf(TestAbortedException.class);
        }
    }

    @Nested
    @DisplayName("credentials")
    class Credentials {

        @Test
        @DisplayName("defaults to postgres/postgres, which is what the service containers use")
        void defaultsToPostgres() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/auth_test");
            ScratchPostgres db = gate(env);

            assertThat(db.user()).isEqualTo("postgres");
            assertThat(db.password()).isEqualTo("postgres");
        }

        @Test
        @DisplayName("a configured user and password are used, and reach the connection attempt")
        void usesConfiguredCredentials() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/auth_test");
            env.values.put(PREFIX + "_USER", "someone");
            env.values.put(PREFIX + "_PASSWORD", "secret");
            ScratchPostgres db = gate(env);

            db.require();

            assertThat(db.user()).isEqualTo("someone");
            assertThat(env.probed).containsExactly(
                    "jdbc:postgresql://localhost:5432/auth_test|someone|secret");
        }

        @Test
        @DisplayName("a blank configured user falls back to the default rather than authenticating as nobody")
        void blankUserFallsBack() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:5432/auth_test");
            env.values.put(PREFIX + "_USER", "");

            assertThat(gate(env).user()).isEqualTo("postgres");
        }
    }

    @Nested
    @DisplayName("the environment prefix")
    class Prefix {

        @Test
        @DisplayName("each consumer reads its own job's variables, not a single shared one")
        void prefixIsPerConsumer() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put("ORCHESTRATOR_TEST_PG_URL", "jdbc:postgresql://localhost:5432/orch_test");
            ScratchPostgres db = ScratchPostgres.forPrefix(
                    "ORCHESTRATOR_TEST_PG", REASON, Duration.ofSeconds(3), env);

            assertThat(db.url()).isEqualTo("jdbc:postgresql://localhost:5432/orch_test");
            assertThatCode(db::require).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the failure names the prefix that is missing, so the fix needs no guessing")
        void failureNamesThePrefix() {
            FakeEnvironment env = new FakeEnvironment();
            env.values.put("CI", "true");

            assertThatThrownBy(() -> ScratchPostgres.forPrefix(
                    "ORCHESTRATOR_TEST_PG", REASON, Duration.ofSeconds(3), env).require())
                    .hasMessageContaining("ORCHESTRATOR_TEST_PG_URL is unset on CI");
        }
    }

    @Nested
    @DisplayName("the default Environment, which is what actually runs in every consumer")
    class DefaultEnvironment {

        /** Overrides only `get`, so `probe` and `pause` run their real bodies. */
        private ScratchPostgres.Environment realProbeAndPause(Map<String, String> values) {
            return values::get;
        }

        @Test
        @DisplayName("the probe really opens a connection, so a refused port is a failure not a pass")
        void theProbeActuallyConnects() throws Exception {
            // Every other test in this file stubs probe(), so the default body - the only place a
            // connection is ever opened - was executed by nothing. Gutting it would have left the
            // whole suite green while the helper proved nothing about reachability.
            int closedPort;
            try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
                closedPort = socket.getLocalPort();
            } // closed here, so nothing is listening on that port

            Map<String, String> values = new HashMap<>();
            values.put(PREFIX + "_URL", "jdbc:postgresql://localhost:" + closedPort + "/auth_test");

            assertThatThrownBy(() -> ScratchPostgres
                    .forPrefix(PREFIX, REASON, Duration.ofSeconds(1), realProbeAndPause(values)).require())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unreachable");
        }

        @Test
        @DisplayName("the default pause really sleeps, so the retry loop cannot spin a core")
        void theDefaultPauseSleeps() throws Exception {
            long startedAt = System.nanoTime();
            ScratchPostgres.Environment environment = name -> null;
            environment.pause(Duration.ofMillis(120));
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(elapsedMillis)
                    .as("a pause that returns immediately turns the retry loop into a busy wait")
                    .isGreaterThanOrEqualTo(100);
        }

        @Test
        @DisplayName("the real process environment is read, not a stub that answers nothing")
        void theProductionSeamReadsTheRealEnvironment() {
            // THE test this class was missing. Every other test injects an Environment, so
            // replacing SYSTEM with `name -> null` left all of them green while each consumer
            // reported "Tests run: 0, BUILD SUCCESS" with its URL correctly set: the exact
            // green-by-absence failure this helper exists to remove, one level up.
            ScratchPostgres.Environment system = ScratchPostgres.systemEnvironment();
            String path = system.get("PATH");
            String fallback = system.get("Path");

            assertThat(path != null ? path : fallback)
                    .as("PATH exists in every process on every OS this builds on")
                    .isNotBlank();
        }

        @Test
        @DisplayName("the default budget is long enough to outlast a cold service container")
        void theDefaultBudgetIsUsable() {
            // Nothing exercised DEFAULT_TIMEOUT, so shrinking it to a millisecond survived the
            // suite while "waits for a service container that is still starting" became false.
            assertThat(ScratchPostgres.DEFAULT_TIMEOUT)
                    .as("a postgres service container can take seconds to accept connections")
                    .isGreaterThanOrEqualTo(Duration.ofSeconds(10))
                    .isLessThanOrEqualTo(Duration.ofMinutes(2));
        }

        @Test
        @DisplayName("the two-argument factory, the one every consumer calls, is wired to it")
        void theTwoArgumentFactoryUsesTheRealEnvironment() {
            // The nine consumers all construct through forPrefix(prefix, reason). No test
            // reached it, so nothing noticed which environment it was handed.
            ScratchPostgres viaProduction = ScratchPostgres.forPrefix("LC_ABSENT_TEST_PG", REASON);
            ScratchPostgres viaSeam = ScratchPostgres.forPrefix(
                    "LC_ABSENT_TEST_PG", REASON, Duration.ofSeconds(1),
                    ScratchPostgres.systemEnvironment());

            assertThat(viaProduction.url()).isEqualTo(viaSeam.url());
            assertThat(viaProduction.user()).isEqualTo(viaSeam.user()).isEqualTo("postgres");
        }

        @Test
        @DisplayName("every probe carries an explicit connectTimeout, which pgjdbc otherwise leaves at 10s")
        void probesBoundTheirOwnConnectAttempt() {
            java.util.Properties props = ScratchPostgres.connectionProperties("someone", "secret");

            // Asserting equality with the constant alone is tautological: raising the constant
            // to 600 would keep such a test green while restoring the ten-minute attempt this
            // bound exists to prevent. Pin the VALUE against the budget it has to fit inside.
            assertThat(Integer.parseInt(props.getProperty("connectTimeout")))
                    .as("one attempt must fit well inside the default %s budget, or the wall clock "
                            + "is spent before the second try", ScratchPostgres.DEFAULT_TIMEOUT)
                    .isPositive()
                    .isLessThanOrEqualTo((int) ScratchPostgres.DEFAULT_TIMEOUT.toSeconds() / 4);
            assertThat(props.getProperty("user")).isEqualTo("someone");
            assertThat(props.getProperty("password")).isEqualTo("secret");
        }
    }

    @Nested
    @DisplayName("databaseName")
    class DatabaseName {

        @Test
        @DisplayName("reads the segment after the last slash, stripping any query string")
        void readsTheSegment() {
            assertThat(ScratchPostgres.databaseName("jdbc:postgresql://host:5432/db")).isEqualTo("db");
            assertThat(ScratchPostgres.databaseName("jdbc:postgresql://host:5432/db?a=1&b=2"))
                    .isEqualTo("db");
        }

        @Test
        @DisplayName("a slash INSIDE the query string does not become the database name")
        void slashInQueryStringIsIgnored() {
            assertThat(ScratchPostgres.databaseName(
                    "jdbc:postgresql://host:5432/livecontext?sslrootcert=/opt/certs/test-ca.pem"))
                    .as("the query string must be removed before the last slash is taken")
                    .isEqualTo("livecontext");
        }

        @Test
        @DisplayName("isScratchName wants test as a part of the name, not as a substring")
        void scratchNameIsTokenBased() {
            assertThat(ScratchPostgres.isScratchName("auth_test")).isTrue();
            assertThat(ScratchPostgres.isScratchName("test")).isTrue();
            assertThat(ScratchPostgres.isScratchName("TEST_DB")).isTrue();
            assertThat(ScratchPostgres.isScratchName("latest")).isFalse();
            assertThat(ScratchPostgres.isScratchName("contest_prod")).isFalse();
            assertThat(ScratchPostgres.isScratchName("")).isFalse();
            assertThat(ScratchPostgres.isScratchName(null)).isFalse();
        }

        @Test
        @DisplayName("yields the empty string when there is no segment, which fails the scratch check")
        void emptyWhenAbsent() {
            assertThat(ScratchPostgres.databaseName("jdbc:postgresql:db")).isEmpty();
            assertThat(ScratchPostgres.databaseName("jdbc:postgresql://host:5432/")).isEmpty();
        }

        @Test
        @DisplayName("a host with no path never becomes the database name, whatever the host is called")
        void hostIsNeverReadAsTheDatabase() {
            // Taking the LAST slash returns the authority when there is no path, so a host that
            // merely has 'test' in its name would satisfy the scratch check and the callers would
            // DROP tables on it. pgjdbc rejects this URL shape for unrelated reasons; a guard in
            // front of a destructive statement must not depend on that.
            assertThat(ScratchPostgres.databaseName("jdbc:postgresql://test-db.prod.internal:5432"))
                    .isEmpty();
            assertThat(ScratchPostgres.isScratchName(
                    ScratchPostgres.databaseName("jdbc:postgresql://test-db.prod.internal:5432")))
                    .isFalse();
        }

        @Test
        @DisplayName("the host is ignored even when the database itself is a scratch one")
        void hostDoesNotLeakIntoTheName() {
            assertThat(ScratchPostgres.databaseName("jdbc:postgresql://prod-host/auth_test"))
                    .isEqualTo("auth_test");
            assertThat(ScratchPostgres.databaseName("jdbc:postgresql://h1:5432,h2:5432/auth_test"))
                    .as("multi-host failover URLs keep one authority section")
                    .isEqualTo("auth_test");
        }
    }
}
