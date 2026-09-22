package com.apimarketplace.auth.metrics;

import com.apimarketplace.auth.repository.UserRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prometheus requires every meter sharing a NAME to share the same tag KEYS. Micrometer
 * enforces it at registration, and the refusal is quiet in the worst way: the second shape
 * gets a WARN, and the caller is handed a live counter that increments in process and stays
 * in {@code getMeters()}. Every in-JVM assertion about it passes. It is only never EXPORTED.
 *
 * <p>{@link AuthMetricsTest} cannot see that, and not by oversight: {@link SimpleMeterRegistry}
 * accepts every shape, so a test written against it certifies the shape the author intended
 * rather than the one production exports. That is how {@code auth_login_total{result="failure"}}
 * reached prod as an unexported meter and stayed there: tagged {provider, reason, result}
 * against a success series registered first as {provider, result}, it was dropped by every auth
 * pod at startup while its unit tests stayed green. Five alert rules select that series
 * ({@code AuthLoginFailureSpike}, {@code AuthLoginFailureRatioHigh},
 * {@code AuthLoginFailureRatioCritical}, {@code AuthInvalidCredentialsBurst},
 * {@code AuthDisabledAccountAccessAttempt}) and so do two dashboard panels, so all seven were
 * quietly unreachable.
 *
 * <p>The rule is checked in the two places it can be checked: on the INTENT, over every shape
 * AuthMetrics can produce (a survivors-only check would pass precisely because the loser is
 * already gone), and against a real {@link PrometheusMeterRegistry}, whose scrape is what
 * Prometheus actually reads.
 */
@DisplayName("AuthMetrics - one tag-key set per metric name")
class AuthMetricsTagKeyParityTest {

    private static UserRepository userRepository() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.count()).thenReturn(0L);
        return repo;
    }

    /**
     * Exercises every recording method AuthMetrics exposes, so the registry ends up holding
     * every shape the class can emit and not only the pre-registered ones. A new method left
     * out here is a shape these guards cannot see, which is why it is written from the public
     * API rather than from the pre-registration block.
     */
    private static void recordEveryShape(AuthMetrics metrics) {
        metrics.loginSuccess("keycloak");
        metrics.loginFailure("keycloak", "invalid_credentials");
        metrics.authTimeClaimMissing("keycloak", AuthMetrics.AUTH_TIME_ABSENT);
        metrics.rateLimitHit("login");
        metrics.signup("google", true);
        metrics.signup("google", false);
        metrics.tokenRefreshed("success");
        metrics.tokenRefreshed("failure");
        metrics.tokenReuseDetected();
        metrics.logout("single");
        metrics.passwordChanged("success");
    }

    @Test
    @DisplayName("every metric name carries exactly one set of tag keys")
    void oneTagKeySetPerMetricName() {
        // SimpleMeterRegistry on purpose: it accepts every shape, so this sees what AuthMetrics
        // MEANT to register. Asking the Prometheus registry the same question would only ever
        // return the shapes that won, which is the failure mode itself.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthMetrics metrics = new AuthMetrics(registry, userRepository());
        recordEveryShape(metrics);

        Map<String, Set<String>> firstSeen = new LinkedHashMap<>();
        Map<String, Set<Set<String>>> divergent = new LinkedHashMap<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (!name.startsWith("auth_")) {
                continue;
            }
            Set<String> keys = meter.getId().getTags().stream()
                    .map(Tag::getKey)
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> first = firstSeen.putIfAbsent(name, keys);
            if (first != null && !first.equals(keys)) {
                Set<Set<String>> shapes = divergent.computeIfAbsent(name, k -> new LinkedHashSet<>());
                shapes.add(first);
                shapes.add(keys);
            }
        }

        assertThat(divergent)
                .as("a metric name registered under two tag-key sets loses one of them to a WARN "
                        + "at startup: the losing series never reaches Prometheus, so every alert "
                        + "and panel selecting it resolves to no data instead of to zero")
                .isEmpty();
    }

    @Test
    @DisplayName("every registered auth meter reaches the scrape, none is silently dropped")
    void everyAuthMeterReachesTheScrape() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AuthMetrics metrics = new AuthMetrics(registry, userRepository());
        recordEveryShape(metrics);

        // The general form of the defect, and the reason this assertion is worth more than any
        // count of a particular series: a refused meter is still IN the registry, so registry
        // and scrape disagree exactly when something has been dropped. Anything that diverges
        // in future - a new counter, a new tag on an old one - fails here without anyone having
        // to think of it.
        Map<String, Long> registered = new TreeMap<>();
        for (Meter meter : registry.getMeters()) {
            String exposed = expositionName(meter);
            if (exposed.startsWith("auth_")) {
                registered.merge(exposed, 1L, Long::sum);
            }
        }
        Map<String, Long> scraped = new TreeMap<>();
        for (String name : registered.keySet()) {
            scraped.put(name, (long) samplesOf(registry, name).size());
        }

        assertThat(scraped)
                .as("one exported sample per registered meter; a name whose scrape count is "
                        + "lower has had series refused at registration and exports nothing for "
                        + "them, which reads as 'no data' rather than as an error")
                .isEqualTo(registered);
    }

    @Test
    @DisplayName("the users gauge exports as auth_users, which is the name the dashboard queries")
    void usersGaugeExportsWithoutTheTotalSuffix() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new AuthMetrics(registry, userRepository());

        // The meter is declared auth_users_total and the client strips the counter suffix from
        // a GAUGE, so the exported name is auth_users. The dashboard's "Total users" panel and
        // every future query depend on that, and it is not visible anywhere in our source.
        assertThat(samplesOf(registry, "auth_users")).hasSize(1);
        assertThat(samplesOf(registry, AuthMetrics.USERS_TOTAL_GAUGE)).isEmpty();
    }

    @Test
    @DisplayName("a failed login reaches a real Prometheus scrape, not only SimpleMeterRegistry")
    void failedLoginIsExported() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AuthMetrics metrics = new AuthMetrics(registry, userRepository());

        metrics.loginFailure("keycloak", "invalid_credentials");
        metrics.loginSuccess("keycloak");

        List<String> loginSamples = samplesOf(registry, AuthMetrics.LOGIN_TOTAL);

        // The selector of AuthInvalidCredentialsBurst and of the "Login failures by reason" panel.
        assertThat(loginSamples)
                .as("auth_login_total with result=failure is what five alert rules select on")
                .anyMatch(line -> line.contains("result=\"failure\"")
                        && line.contains("reason=\"invalid_credentials\"")
                        && line.contains("provider=\"keycloak\""));
        // And the success side keeps its own series, so the failure RATIO rules have a denominator.
        assertThat(loginSamples)
                .anyMatch(line -> line.contains("result=\"success\"")
                        && line.contains("provider=\"keycloak\""));
    }

    @Test
    @DisplayName("a successful login is tagged reason=none, so the tag key is never absent")
    void successCarriesTheReasonTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthMetrics metrics = new AuthMetrics(registry, userRepository());
        metrics.loginSuccess("google");

        // A success has no reason; it carries the KEY anyway because Prometheus keys the shape,
        // not the meaning. Dropping the tag here is exactly what broke the failure side.
        assertThat(registry.find(AuthMetrics.LOGIN_TOTAL)
                .tags("result", "success", "provider", "google", "reason", AuthMetrics.LOGIN_REASON_NONE)
                .counter())
                .isNotNull();
    }

    /** Matches a reason literal handed to any of the three sign-in refusal recorders. */
    private static final Pattern REASON_LITERAL = Pattern.compile(
            "(?:recordLoginFailure|loginFailure|recordFailure)\\((?:[^()\"]|\\([^()]*\\))*\"([a-z_]+)\"\\s*\\)");

    @Test
    @DisplayName("the pre-registered reasons are exactly the reasons a producer can emit")
    void preRegisteredReasonsMatchTheProducers() throws IOException {
        Set<String> emitted = reasonsProducersEmit();
        Set<String> preRegistered = preRegisteredReasons();

        // Both directions, and both are the same defect wearing opposite hats.
        //
        // A reason a producer emits but nobody pre-registers has no series until it first
        // happens, so the panel that would show it says "no data" precisely while nothing is
        // wrong, and says nothing different once something is. cross_provider_conflict was in
        // that state.
        //
        // A reason pre-registered with no producer is the mirror: a permanently flat zero, which
        // an operator reads as "watched and healthy" rather than as "nothing can ever move
        // this". invalid_jwt, integrity_violation and provisioning_race were in that state.
        assertThat(emitted)
                .as("a reason no pre-registration covers is absent from Prometheus until it "
                        + "first occurs, which is the one moment it needed to already be there")
                .isSubsetOf(preRegistered);
        assertThat(preRegistered)
                .as("a reason no producer emits is a flat zero an operator reads as healthy")
                .isSubsetOf(emitted);
    }

    @Test
    @DisplayName("the alert rules spell reason=none exactly as AuthMetrics does")
    void alertRulesAgreeOnTheSuccessSentinel() throws IOException {
        Path rules = repoFile("deploy", "config", "alert-rules.yml");
        String text = Files.readString(rules, StandardCharsets.UTF_8);

        // The two ratio rules put successes in their denominator by matching this sentinel. The
        // string is the whole coupling: rename the constant and the Java tests stay green (they
        // use the constant), the promtool tests stay green (they hardcode the string), and in
        // production both denominators silently lose the entire success population, which pins
        // the ratio at 1.0 and pages AuthLoginFailureRatioCritical on a healthy install.
        String selector = "|" + AuthMetrics.LOGIN_REASON_NONE + "\"}";
        long matches = text.lines().filter(line -> line.contains(selector)).count();
        assertThat(matches)
                .as("both failure-ratio denominators in %s must select the success sentinel "
                        + "%s; a rename of AuthMetrics.LOGIN_REASON_NONE that stops here makes "
                        + "them page on a healthy system", rules, AuthMetrics.LOGIN_REASON_NONE)
                .isEqualTo(2L);
    }

    /**
     * Scans the service sources for every reason literal a refusal recorder is handed.
     *
     * <p>Two limits worth knowing before trusting this further than it goes. It reads
     * auth-service only, which is where every producer lives today but is not a repo-wide
     * guarantee. And it matches a literal in the LAST argument position, so a reason passed
     * through a constant or a variable is invisible to it. The asymmetry that leaves is the
     * safe one: a pre-registered reason no scan can attribute to a producer FAILS the build and
     * has to be justified, while a missed producer only means the guard did not help that day.
     */
    private static Set<String> reasonsProducersEmit() throws IOException {
        Path main = Path.of("src", "main", "java").toAbsolutePath();
        assertThat(Files.isDirectory(main))
                .as("the producer scan resolves its sources relative to the module directory, so "
                        + "it must be run from backend/auth-service (surefire does); looked in "
                        + main)
                .isTrue();
        Set<String> reasons = new TreeSet<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                if (file.getFileName().toString().equals("AuthMetrics.java")) {
                    continue; // the registry itself, not a producer
                }
                Matcher m = REASON_LITERAL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    reasons.add(m.group(1));
                }
            }
        }
        assertThat(reasons).as("the producer scan found nothing, so it is measuring nothing")
                .isNotEmpty();
        return reasons;
    }

    /** The reasons AuthMetrics actually pre-registers, read back from a live registry. */
    private static Set<String> preRegisteredReasons() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AuthMetrics(registry, userRepository());
        return registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(AuthMetrics.LOGIN_TOTAL))
                .map(m -> m.getId().getTag("reason"))
                .filter(r -> r != null && !r.equals(AuthMetrics.LOGIN_REASON_NONE))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * The name a meter is exported under, which is not always its declared name: the Prometheus
     * client strips a {@code _total} suffix from anything that is not a counter.
     */
    private static String expositionName(Meter meter) {
        String name = meter.getId().getName();
        if (meter.getId().getType() != Meter.Type.COUNTER && name.endsWith("_total")) {
            return name.substring(0, name.length() - "_total".length());
        }
        return name;
    }

    /** A path under the repository root, resolved from this module directory. */
    private static Path repoFile(String... parts) {
        Path root = Path.of("").toAbsolutePath().getParent().getParent();
        Path file = root;
        for (String part : parts) {
            file = file.resolve(part);
        }
        assertThat(Files.isRegularFile(file))
                .as("expected to find %s from the auth-service module directory", file)
                .isTrue();
        return file;
    }

    /** The exported sample lines for one metric name, labelled or not. */
    private static List<String> samplesOf(PrometheusMeterRegistry registry, String name) {
        return Arrays.stream(registry.scrape().split("\n"))
                .map(String::trim)
                .filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
                .collect(Collectors.toList());
    }
}
