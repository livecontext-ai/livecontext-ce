package com.apimarketplace.testsupport;

import org.junit.jupiter.api.Assumptions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;

/**
 * Resolves the scratch Postgres a SQL test runs against, and decides what happens when there is
 * none.
 *
 * <p><b>Why this is not a Testcontainers call.</b> The {@code arc-build} CI runners expose no
 * Docker socket. A class that starts its own container therefore either errors there (the
 * Testcontainers default, {@code disabledWithoutDocker = false}) or, when a class sets
 * {@code disabledWithoutDocker = true}, SKIPS - and a skipped test reports the same green as a
 * passing one. CI instead provides a {@code postgres} service container per job and names its URL
 * in an environment variable; this class reads that.
 *
 * <p><b>Why the gate is asymmetric between a laptop and CI, deliberately.</b> With no URL and no
 * {@code CI}, it aborts the test as skipped: a dev machine with no scratch database is not a
 * failure. With {@code CI} set it REFUSES to skip, because a test that skips on CI is the same as
 * no test, and every class using this is the only executable proof of some statement H2 cannot
 * run. Deleting the {@code env:} block from a workflow step, or moving such a class to a job with
 * no {@code services: postgres}, then breaks the build instead of quietly returning the file to
 * being compiled and never executed. A URL that is set but unreachable always fails, so a broken
 * service container cannot pass either.
 *
 * <p><b>Why the database name is checked.</b> Classes using this TRUNCATE or DROP the tables they
 * assert on. A URL pointing at a dev or, worse, a shared database would destroy real rows, and
 * that mistake is one copy-paste away, so anything not visibly a scratch database is refused.
 *
 * <p>One hole this does NOT close, and it is worth knowing: none of this fires if the class is
 * never selected. Surefire is told to ignore unmatched patterns, so dropping a class name from a
 * step's {@code -Dtest} list leaves the step green with less coverage than it claims. That is the
 * test-wiring gate's job, not this one's.
 *
 * <p>Typical use, from a {@code @BeforeAll}:
 * <pre>{@code
 * private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
 *         "CREDENTIAL_TEST_PG",
 *         "it is the only proof that a catalog re-import stops using a credential "
 *                 + "instead of deleting it");
 *
 * @BeforeAll
 * void setUp() {
 *     DB.require();
 *     DataSource ds = new DriverManagerDataSource(DB.url(), DB.user(), DB.password());
 *     ...
 * }
 * }</pre>
 */
public final class ScratchPostgres {

    /** How long {@link #require()} waits for a service container that is still starting. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String url;
    private final String user;
    private final String password;
    private final String envPrefix;
    private final String whyItMustRun;
    private final Duration timeout;
    private final Environment environment;

    /**
     * The ambient process state this class reads. Broken out so its own tests can drive every
     * branch without setting real environment variables, which Java cannot do portably.
     */
    public interface Environment {
        String get(String name);

        /** Sleep between connection attempts. Tests supply a no-op. */
        default void pause(Duration duration) throws InterruptedException {
            Thread.sleep(duration.toMillis());
        }

        /**
         * Open a connection, or throw. Tests may supply a stub, but the default body is what
         * actually runs in every consumer, so it is exercised directly by this class's own tests.
         */
        default void probe(String url, String user, String password) throws Exception {
            try (Connection ignored = DriverManager.getConnection(url, connectionProperties(user, password))) {
                // Reaching here is the whole assertion: the database answers.
            }
        }
    }

    private static final Environment SYSTEM = System::getenv;

    /**
     * The real process environment, the one every consumer gets.
     *
     * <p>Exposed so a test can prove it reads something. Swapping it for a stub that always
     * returns null leaves this class's whole suite green - every test injects its own
     * environment - while each consumer silently reports "Tests run: 0, BUILD SUCCESS" with the
     * URL correctly set. That is the exact green-by-absence failure this helper exists to
     * remove, reproduced one level up.
     */
    static Environment systemEnvironment() {
        return SYSTEM;
    }

    /** Seconds pgjdbc may spend on one connect attempt. See {@link #connectionProperties}. */
    static final String CONNECT_TIMEOUT_SECONDS = "2";

    /**
     * The properties every probe connects with.
     *
     * <p>Its own method so a test can assert it, because the value that matters here is invisible
     * from the outside: pgjdbc's default {@code connectTimeout} is 10s and its
     * {@code socketTimeout} is unbounded, so a single unroutable or silently-accepting host would
     * overrun the caller's entire retry budget and turn a documented 30s wait into minutes. A
     * deleted line here would leave every assertion in this class green.
     */
    static java.util.Properties connectionProperties(String user, String password) {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS);
        return props;
    }

    private ScratchPostgres(String envPrefix, String whyItMustRun, Duration timeout,
                            Environment environment) {
        this.envPrefix = envPrefix;
        this.whyItMustRun = whyItMustRun;
        this.timeout = timeout;
        this.environment = environment;
        this.url = environment.get(envPrefix + "_URL");
        String configuredUser = environment.get(envPrefix + "_USER");
        String configuredPassword = environment.get(envPrefix + "_PASSWORD");
        this.user = configuredUser == null || configuredUser.isBlank() ? "postgres" : configuredUser;
        this.password = configuredPassword == null || configuredPassword.isBlank()
                ? "postgres" : configuredPassword;
    }

    /**
     * @param envPrefix   the variable prefix a workflow step sets, without the trailing
     *                    {@code _URL} / {@code _USER} / {@code _PASSWORD}, e.g.
     *                    {@code CREDENTIAL_TEST_PG}
     * @param whyItMustRun a fragment naming what is lost if this class stops running, used in the
     *                    failure raised on CI. Write it so someone who deleted the env block
     *                    learns what they disarmed.
     */
    public static ScratchPostgres forPrefix(String envPrefix, String whyItMustRun) {
        return new ScratchPostgres(envPrefix, whyItMustRun, DEFAULT_TIMEOUT, SYSTEM);
    }

    /** Same, with the ambient state injected. For this class's own tests. */
    public static ScratchPostgres forPrefix(String envPrefix, String whyItMustRun,
                                            Duration timeout, Environment environment) {
        return new ScratchPostgres(envPrefix, whyItMustRun, timeout, environment);
    }

    public String url() {
        return url;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    /**
     * Abort as skipped (local dev, no URL), fail loudly (CI, no URL), refuse a non-scratch
     * database, or wait for the database to answer and return normally.
     */
    public void require() {
        if (url == null || url.isBlank()) {
            String ci = environment.get("CI");
            if (ci != null && !ci.isBlank()) {
                throw new IllegalStateException(
                        envPrefix + "_URL is unset on CI. This class must execute there: "
                                + whyItMustRun + ". Restore the env block on the workflow step "
                                + "that runs it, and keep that step in a job carrying the postgres "
                                + "service.");
            }
            Assumptions.abort("no scratch Postgres: set " + envPrefix + "_URL to run this locally "
                    + "(CI always sets it)");
        }
        requireScratchDatabase();
        await();
    }

    /**
     * Refuse a URL that does not visibly name a scratch database. Package-private so the tests can
     * reach it without going through the environment branches.
     */
    void requireScratchDatabase() {
        String overriding = overridingParameter(url);
        if (overriding != null) {
            throw new IllegalStateException(
                    envPrefix + "_URL carries the JDBC parameter '" + overriding + "', which "
                            + "overrides what the rest of the URL says. pgjdbc applies URL "
                            + "arguments AFTER the path and over the properties we pass, so "
                            + "?PGDBNAME=live makes this guard read the scratch name in the path "
                            + "while the driver connects somewhere else, and ?connectTimeout "
                            + "undoes the bound on each attempt. Put the database in the path and "
                            + "nothing else: " + url);
        }
        String database = databaseName(url);
        if (!isScratchName(database)) {
            throw new IllegalStateException(
                    envPrefix + "_URL must point at a scratch database with 'test' as a separate "
                            + "part of its name, such as auth_test (these tests DROP and TRUNCATE "
                            + "the tables they assert on), got: " + database);
        }
    }

    /**
     * The database segment of a JDBC URL: the last path segment, ignoring any query string.
     *
     * <p><b>The query string is removed FIRST, and that order is the whole point.</b> Taking the
     * last {@code /} before stripping it lets an ordinary JDBC parameter whose value is a path
     * decide the answer: {@code jdbc:postgresql://db.prod:5432/livecontext?sslrootcert=
     * /opt/certs/test-ca.pem} would read as {@code test-ca.pem} and be waved through, and the
     * callers then DROP tables on that production database. {@code sslcert}, {@code sslkey},
     * {@code sslrootcert} and {@code loggerFile} are all standard pgjdbc parameters, so this is
     * an ordinary URL rather than a contrived one. A URL with no path segment yields the empty
     * string, which fails the scratch check: the safe direction.
     */
    static String databaseName(String jdbcUrl) {
        String withoutQuery = jdbcUrl.split("\\?", 2)[0];
        int authority = withoutQuery.indexOf("//");
        if (authority >= 0) {
            // Host form. The database is what follows the FIRST slash after the authority; with
            // no such slash there is no database at all. Falling back to the last slash would
            // return the host instead, so jdbc:postgresql://test-db.prod:5432 would read as a
            // scratch name and wave a production host through. pgjdbc happens to reject that URL
            // for its own reasons, but a destructive guard must not rely on a driver's parser.
            int slash = withoutQuery.indexOf('/', authority + 2);
            return slash < 0 ? "" : withoutQuery.substring(slash + 1);
        }
        int lastSlash = withoutQuery.lastIndexOf('/');
        return lastSlash < 0 ? "" : withoutQuery.substring(lastSlash + 1);
    }

    /**
     * Whether a database name says "scratch" loudly enough to DROP tables in it.
     *
     * <p>{@code test} must be a delimited part of the name, not a substring: a plain
     * {@code contains("test")} also accepts {@code latest} and {@code contest_prod}. Erring
     * strict is right for a destructive guard - a refused scratch database costs one rename and
     * says exactly why, while an accepted production one costs the data.
     */
    static boolean isScratchName(String database) {
        return database != null
                && SCRATCH_NAME.matcher(database.toLowerCase(java.util.Locale.ROOT)).find();
    }

    /**
     * {@code test} as its own part: {@code auth_test}, {@code test}, {@code test-db}, not
     * {@code latest}. Anchored with {@code \z} rather than {@code $}, which in Java matches
     * before a final line terminator and so accepted {@code "auth_test\n"}.
     */
    private static final java.util.regex.Pattern SCRATCH_NAME =
            java.util.regex.Pattern.compile("(^|[_-])test([_-]|\\z)");

    /**
     * JDBC parameters that move the target or undo the connect bound, and so make every other
     * check in this class a statement about the wrong database.
     *
     * <p>pgjdbc reads URL arguments after the path and lets them win over the {@code Properties}
     * handed to {@code getConnection}. Verified against its own parser: with
     * {@code …/auth_test?PGDBNAME=livecontext} the driver connects to {@code livecontext} while
     * the path still reads as a scratch name, and {@code ?connectTimeout=600} restores the
     * ten-minute attempt this class exists to bound. Rather than out-parse the driver, refuse
     * the shape: a scratch URL has no business carrying any of these.
     */
    private static final java.util.Set<String> OVERRIDING_PARAMETERS = java.util.Set.of(
            "pgdbname", "pghost", "pgport", "connecttimeout", "sockettimeout", "logintimeout");

    /** The first target- or timeout-overriding parameter in the URL's query string, or null. */
    static String overridingParameter(String jdbcUrl) {
        int question = jdbcUrl.indexOf('?');
        if (question < 0) {
            return null;
        }
        for (String pair : jdbcUrl.substring(question + 1).split("[&;]")) {
            String key = pair.split("=", 2)[0].trim();
            if (OVERRIDING_PARAMETERS.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                return key;
            }
        }
        return null;
    }

    /**
     * Retry until the database answers or {@link #timeout} has actually elapsed.
     *
     * <p>The budget is WALL CLOCK, not an attempt count. Counting attempts made the documented
     * timeout a fiction the moment a probe was slow: pgjdbc's default connect timeout is 10s, so
     * an unroutable host turned "30 seconds" into 30 x (10s + 1s), about five and a half minutes
     * of a CI job spent proving the same thing. Probing with a deadline also means no sleep after
     * the last attempt, and a sub-second timeout still gets its one try.
     */
    private void await() {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        while (true) {
            try {
                environment.probe(url, user, password);
                return;
            } catch (Exception e) {
                last = new IllegalStateException(
                        envPrefix + "_URL is set but the database is unreachable within "
                                + timeout.toSeconds() + "s: " + url, e);
            }
            if (System.nanoTime() >= deadline) {
                throw last;
            }
            try {
                environment.pause(Duration.ofSeconds(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw last;
            }
        }
    }
}
