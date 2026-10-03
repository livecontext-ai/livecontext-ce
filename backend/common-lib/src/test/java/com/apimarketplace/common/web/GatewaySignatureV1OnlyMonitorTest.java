package com.apimarketplace.common.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CASA LC-035 cutover evidence. While {@code gateway.signature.accept-v1} is true, a request
 * carrying no v2 header is accepted without a trace, so "no v2 mismatch WARN for a day" never
 * proved that every caller signs v2. These tests pin that exactly the v1-ONLY accepted requests
 * are counted ({@code gateway_signature_v1_only_total}) and logged once per route and caller,
 * and that the monitor changes no decision.
 */
@DisplayName("GatewaySignatureV1OnlyMonitor (LC-035 cutover evidence)")
class GatewaySignatureV1OnlyMonitorTest {

    private static final String SECRET = "test-gateway-hmac-key-for-unit-tests";
    private static final String PATH = "/api/workflow-inspector/tools/deepseek-chat";

    private GatewayFilterProperties properties;
    private GatewaySignatureV1OnlyMonitor monitor;
    private final List<String> counted = new ArrayList<>();
    private Logger monitorLogger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        properties = new GatewayFilterProperties();
        properties.setSecretKey(SECRET);
        properties.setVerificationEnabled(true);
        properties.setPublicPaths(List.of("/health", "/api/internal/"));
        monitor = new GatewaySignatureV1OnlyMonitor();
        monitor.bindCounter((path, caller) -> () -> counted.add(path + "|" + caller));
        monitorLogger = (Logger) LoggerFactory.getLogger(GatewaySignatureV1OnlyMonitor.class);
        logs = new ListAppender<>();
        logs.start();
        monitorLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        monitorLogger.detachAppender(logs);
    }

    private static HttpHeaders v1Only(String provider) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-ID", "42");
        InternalGatewaySigner.stamp(h, provider, SECRET);
        return h;
    }

    private int run(boolean acceptV1, String method, String path, HttpHeaders headers) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        headers.forEach((name, values) -> values.forEach(v -> request.addHeader(name, v)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new GatewayAuthenticationFilter(properties, acceptV1, 60, monitor)
                .doFilter(request, response, mock(FilterChain.class));
        return response.getStatus();
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName("a v1-only request is still accepted, counted every time, and WARNed only once per route and caller")
    void v1OnlyIsAcceptedCountedAndLoggedOnce() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(run(true, "GET", PATH, v1Only("internal-orchestrator-tool-schema"))).isEqualTo(200);
        }

        assertThat(counted).containsExactly(
                "/api/workflow-inspector|internal-orchestrator-tool-schema",
                "/api/workflow-inspector|internal-orchestrator-tool-schema",
                "/api/workflow-inspector|internal-orchestrator-tool-schema");
        assertThat(warnings()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains("v1-only", "GET " + PATH, "caller=internal-orchestrator-tool-schema",
                        "gateway_signature_v1_only_total", "accept-v1=false"));
    }

    @Test
    @DisplayName("a request that carries v2 (valid) is not counted: only the v1-only callers are")
    void v2SignedIsNotCounted() throws Exception {
        HttpHeaders h = v1Only("internal-credential-client");
        InternalGatewaySigner.stampV2(h, "GET", URI.create("http://svc" + PATH), SECRET);

        assertThat(run(true, "GET", PATH, h)).isEqualTo(200);
        assertThat(run(false, "GET", PATH, h)).isEqualTo(200);
        assertThat(counted).isEmpty();
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a v2 mismatch that falls back to v1 is not counted here (the filter WARNs it already)")
    void v2MismatchFallbackIsNotCounted() throws Exception {
        HttpHeaders h = v1Only("internal-credential-client");
        h.set(GatewaySignatureV2.HEADER, GatewaySignatureV2.PREFIX + "forged");

        assertThat(run(true, "GET", PATH, h)).isEqualTo(200);
        assertThat(counted).isEmpty();
    }

    @Test
    @DisplayName("refused or unauthenticated requests are never counted: a bad v1, accept-v1=false, a public path")
    void onlyAcceptedV1OnlyRequestsCount() throws Exception {
        HttpHeaders bad = v1Only("internal-x");
        bad.set(InternalGatewaySigner.HEADER_SECRET, "gw_not-the-signature");
        assertThat(run(true, "GET", PATH, bad)).isEqualTo(401);
        assertThat(run(false, "GET", PATH, v1Only("internal-x"))).isEqualTo(401);
        assertThat(run(true, "GET", "/api/internal/credentials/x", new HttpHeaders())).isEqualTo(200);

        assertThat(counted).isEmpty();
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("each new (path, caller) pair is logged once; at the cap ONE 'cap reached' line, then silence, while counting goes on")
    void loggingIsBoundedPerRouteAndCaller() {
        for (int i = 0; i < GatewaySignatureV1OnlyMonitor.MAX_LOGGED_KEYS + 50; i++) {
            monitor.record("GET", "/api/route" + i + "/x", "internal-caller");
        }
        monitor.record("GET", "/api/route0/x", "internal-caller");

        List<ILoggingEvent> warnings = warnings();
        assertThat(warnings).hasSize(GatewaySignatureV1OnlyMonitor.MAX_LOGGED_KEYS + 1);
        assertThat(warnings).filteredOn(e -> e.getFormattedMessage().contains("pairs already logged"))
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains(String.valueOf(GatewaySignatureV1OnlyMonitor.MAX_LOGGED_KEYS),
                                "path=/api/route" + GatewaySignatureV1OnlyMonitor.MAX_LOGGED_KEYS));
        assertThat(counted).hasSize(GatewaySignatureV1OnlyMonitor.MAX_LOGGED_KEYS + 51);
    }

    @Test
    @DisplayName("audit: id-like provider ids fold into one 'other' pair, so they cannot use up the slots a new service caller needs")
    void dynamicProviderIdsCannotStarveANewServiceCaller() {
        for (int i = 0; i < GatewaySignatureV1OnlyMonitor.MAX_LOGGED_KEYS + 44; i++) {
            monitor.record("GET", PATH, java.util.UUID.randomUUID().toString());
        }
        monitor.record("GET", PATH, "internal-new-service");

        assertThat(warnings()).hasSize(2);
        assertThat(warnings().get(0).getFormattedMessage()).contains("caller=other");
        assertThat(warnings().get(1).getFormattedMessage())
                .contains("caller=internal-new-service");
        assertThat(warnings()).noneMatch(e -> e.getFormattedMessage().contains("pairs already logged"));
    }

    @Test
    @DisplayName("audit: the once-per-pair log and the cap are atomic under concurrent requests")
    void onceAndCapAreAtomicUnderConcurrency() throws Exception {
        monitor.bindCounter((path, caller) -> () -> { }); // the shared test list is not thread-safe
        int threads = 16;
        int perThread = 64;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // Every thread races on the same shared pair and on the same 320 route pairs
                        // (5 rounds x 64), so every new pair is contended and the total passes the cap.
                        monitor.record("GET", PATH, "internal-shared");
                        for (int round = 0; round < 5; round++) {
                            monitor.record("GET", "/api/r" + (round * perThread + i) + "/x", "internal-caller");
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> f : done) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 1 shared pair + 320 distinct route pairs = 321 > cap: exactly cap pair lines + 1 cap line.
        List<ILoggingEvent> warnings = warnings();
        assertThat(warnings).hasSize(GatewaySignatureV1OnlyMonitor.MAX_LOGGED_KEYS + 1);
        assertThat(warnings).filteredOn(e -> e.getFormattedMessage().contains("pairs already logged")).hasSize(1);
        assertThat(warnings).extracting(ILoggingEvent::getFormattedMessage).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the path tag is the route prefix: two segments, three under /api/internal, tokens masked")
    void routeIsABoundedPrefix() {
        assertThat(GatewaySignatureV1OnlyMonitor.route(PATH)).isEqualTo("/api/workflow-inspector");
        assertThat(GatewaySignatureV1OnlyMonitor.route("/api/internal/credentials/platform/by-name"))
                .isEqualTo("/api/internal/credentials");
        assertThat(GatewaySignatureV1OnlyMonitor.route("/api/credits/consume")).isEqualTo("/api/credits");
        assertThat(GatewaySignatureV1OnlyMonitor.route("/")).isEqualTo("/");
        assertThat(GatewaySignatureV1OnlyMonitor.route(null)).isEqualTo("/");

        monitor.record("POST", "/webhook/secret-token-value", "internal-caller");
        assertThat(counted).singleElement().asString().doesNotContain("secret-token-value");
        assertThat(warnings()).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).doesNotContain("secret-token-value"));
    }

    @Test
    @DisplayName("the caller tag keeps a fixed service name and folds anything id-like into 'other'")
    void callerTagIsBounded() {
        assertThat(GatewaySignatureV1OnlyMonitor.callerTag("internal-orchestrator-tool-schema"))
                .isEqualTo("internal-orchestrator-tool-schema");
        assertThat(GatewaySignatureV1OnlyMonitor.callerTag("internal")).isEqualTo("internal");
        assertThat(GatewaySignatureV1OnlyMonitor.callerTag("3f2c1a9e-8d7b-4c6a-9e5f-1b2c3d4e5f60")).isEqualTo("other");
        assertThat(GatewaySignatureV1OnlyMonitor.callerTag("share:cs_abc")).isEqualTo("other");
        assertThat(GatewaySignatureV1OnlyMonitor.callerTag(null)).isEqualTo("other");
    }

    @Test
    @DisplayName("a failing counter never fails the accepted request")
    void counterFailureIsSwallowed() throws Exception {
        monitor.bindCounter((path, caller) -> () -> { throw new IllegalStateException("registry down"); });

        assertThat(run(true, "GET", PATH, v1Only("internal-x"))).isEqualTo(200);
    }

    @Test
    @DisplayName("audit: a raw provider id (a user id from the v1 providerId query parameter) never reaches the log")
    void rawProviderIdIsNeverLogged() throws Exception {
        String userId = "3f2c1a9e-8d7b-4c6a-9e5f-1b2c3d4e5f60";
        HttpHeaders h = v1Only(userId);
        h.remove(InternalGatewaySigner.HEADER_PROVIDER_ID);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.setParameter("providerId", userId);
        h.forEach((name, values) -> values.forEach(v -> request.addHeader(name, v)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new GatewayAuthenticationFilter(properties, true, 60, monitor)
                .doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(warnings()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains("caller=other").doesNotContain(userId));
    }

    @Test
    @DisplayName("audit: past both caps a never-seen pair is counted and dropped without taking either lock")
    void pastTheCapsTheHotPathIsLockFree() throws Exception {
        java.util.concurrent.atomic.AtomicInteger overflowCount = new java.util.concurrent.atomic.AtomicInteger();
        monitor.bindCounter((path, caller) -> GatewaySignatureV1OnlyMonitor.OVERFLOW_TAG.equals(path)
                ? overflowCount::incrementAndGet : () -> { });
        // MAX distinct pairs fill both caps; one more writes the cap line and creates the overflow handle.
        for (int i = 0; i <= GatewaySignatureV1OnlyMonitor.MAX_SERIES; i++) {
            monitor.record("GET", "/api/route" + i + "/x", "internal-caller");
        }
        assertThat(overflowCount).hasValue(1);

        Object logLock = org.springframework.test.util.ReflectionTestUtils.getField(monitor, "logLock");
        Object counterLock = org.springframework.test.util.ReflectionTestUtils.getField(monitor, "counterLock");
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (logLock) {
                synchronized (counterLock) {
                    held.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        holder.start();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            held.await();
            java.util.concurrent.Future<?> done = pool.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    monitor.record("GET", "/api/never-seen-" + i + "/x", "internal-late-caller");
                }
            });
            // Would block until release if either lock were taken.
            done.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.join(5000);
            pool.shutdownNow();
        }

        assertThat(overflowCount).hasValue(101);
        assertThat(warnings()).hasSize(GatewaySignatureV1OnlyMonitor.MAX_LOGGED_KEYS + 1);
    }

    @Test
    @DisplayName("audit: the same series reuses its cached counter handle instead of building one per request")
    void sameSeriesReusesTheCachedHandle() {
        java.util.concurrent.atomic.AtomicInteger built = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger increments = new java.util.concurrent.atomic.AtomicInteger();
        monitor.bindCounter((path, caller) -> {
            built.incrementAndGet();
            return increments::incrementAndGet;
        });

        for (int i = 0; i < 5; i++) {
            monitor.record("GET", PATH, "internal-orchestrator-tool-schema");
        }
        monitor.record("GET", "/api/workflow-inspector/tools/other-tool", "internal-orchestrator-tool-schema");

        assertThat(built).hasValue(1);
        assertThat(increments).hasValue(6);
    }

    @Test
    @DisplayName("audit: past the cap new pairs fold into ONE path=other,caller=other series, so the registry stays bounded")
    void seriesCountStaysBoundedPastTheCap() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GatewayWebAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("gateway.filter.secret-key=" + SECRET,
                        "gateway.filter.verification-enabled=true")
                .run(context -> {
                    GatewaySignatureV1OnlyMonitor bean = context.getBean(GatewaySignatureV1OnlyMonitor.class);
                    int pairs = GatewaySignatureV1OnlyMonitor.MAX_SERIES + 50;
                    for (int i = 0; i < pairs; i++) {
                        bean.record("GET", "/api/route" + i + "/x", "internal-caller");
                    }
                    // A pair admitted before the cap keeps its own series afterwards.
                    bean.record("GET", "/api/route0/x", "internal-caller");

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find(GatewaySignatureV1OnlyMonitor.METRIC).counters())
                            .hasSize(GatewaySignatureV1OnlyMonitor.MAX_SERIES + 1);
                    assertThat(registry.get(GatewaySignatureV1OnlyMonitor.METRIC)
                            .tag("path", GatewaySignatureV1OnlyMonitor.OVERFLOW_TAG)
                            .tag("caller", GatewaySignatureV1OnlyMonitor.OVERFLOW_TAG)
                            .counter().count()).isEqualTo(50.0);
                    assertThat(registry.get(GatewaySignatureV1OnlyMonitor.METRIC)
                            .tag("path", "/api/route0").tag("caller", "internal-caller")
                            .counter().count()).isEqualTo(2.0);
                    double total = registry.find(GatewaySignatureV1OnlyMonitor.METRIC).counters().stream()
                            .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
                    assertThat(total).isEqualTo(pairs + 1.0);
                });
    }

    @Test
    @DisplayName("auto-configuration, CE monolith mode: neither the monitor, its metrics binder nor the gateway filter exists")
    void monolithModeHasNoMonitorNorFilter() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GatewayWebAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("deployment.mode=monolith")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GatewaySignatureV1OnlyMonitor.class);
                    assertThat(context).doesNotHaveBean(GatewayAuthenticationFilter.class);
                    assertThat(context).doesNotHaveBean("gatewaySignatureV1OnlyMetricsBinder");
                    assertThat(context).hasSingleBean(MonolithSecurityFilter.class);
                });
    }

    @Test
    @DisplayName("auto-configuration without a MeterRegistry: the filter starts and a v1-only request is still logged")
    void noRegistryStillStartsAndLogs() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GatewayWebAutoConfiguration.class))
                .withPropertyValues("gateway.filter.secret-key=" + SECRET,
                        "gateway.filter.verification-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MeterRegistry.class);
                    GatewayAuthenticationFilter filter = context.getBean(GatewayAuthenticationFilter.class);
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
                    v1Only("internal-orchestrator-tool-schema")
                            .forEach((name, values) -> values.forEach(v -> request.addHeader(name, v)));
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, mock(FilterChain.class));

                    assertThat(response.getStatus()).isEqualTo(200);
                    assertThat(warnings()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                            .contains("v1-only", "caller=internal-orchestrator-tool-schema"));
                });
    }

    @Test
    @DisplayName("auto-configuration: the filter reports to the monitor bean, bound to the Micrometer registry")
    void autoConfigurationBindsTheRegistry() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GatewayWebAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("gateway.filter.secret-key=" + SECRET,
                        "gateway.filter.verification-enabled=true")
                .run(context -> {
                    GatewayAuthenticationFilter filter = context.getBean(GatewayAuthenticationFilter.class);
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
                    v1Only("internal-orchestrator-tool-schema")
                            .forEach((name, values) -> values.forEach(v -> request.addHeader(name, v)));
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, mock(FilterChain.class));

                    assertThat(response.getStatus()).isEqualTo(200);
                    assertThat(context.getBean(MeterRegistry.class).get(GatewaySignatureV1OnlyMonitor.METRIC)
                            .tag("path", "/api/workflow-inspector")
                            .tag("caller", "internal-orchestrator-tool-schema")
                            .counter().count()).isEqualTo(1.0);
                });
    }
}
