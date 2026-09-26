package com.apimarketplace.auth.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("ResendClient - request shapes and the never-throw, no-op-when-off contract")
class ResendClientTest {

    private static final String BASE = "https://resend.test";
    private static final Executor SAME_THREAD = Runnable::run;

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ResendClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new ResendClient(true, "re_test_key", BASE + "/", restTemplate, SAME_THREAD);
    }

    private static Map<String, String> props() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("locale", "fr");
        p.put("plan", "free");
        return p;
    }

    @Test
    @DisplayName("upsert PATCHes the contact by email with the bearer key, first_name and string properties")
    void upsertPatchesExistingContact() {
        server.expect(requestTo(BASE + "/contacts/ada@example.com"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer re_test_key"))
                .andExpect(content().json("{\"first_name\":\"Ada\",\"properties\":{\"locale\":\"fr\",\"plan\":\"free\"}}", true))
                .andRespond(withSuccess("{\"object\":\"contact\",\"id\":\"c1\"}", MediaType.APPLICATION_JSON));

        boolean ok = client.upsertContact("ada@example.com", "Ada", props());

        assertThat(ok).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("upsert falls back to POST /contacts with the email when the PATCH answers 404")
    void upsertCreatesOn404() {
        server.expect(requestTo(BASE + "/contacts/new@example.com"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/contacts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"email\":\"new@example.com\",\"first_name\":\"Neo\","
                        + "\"properties\":{\"locale\":\"fr\",\"plan\":\"free\"}}", true))
                .andRespond(withSuccess("{\"object\":\"contact\",\"id\":\"c2\"}", MediaType.APPLICATION_JSON));

        boolean ok = client.upsertContact("new@example.com", "Neo", props());

        assertThat(ok).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("upsert never sends the unsubscribed flag, so a sync cannot undo an unsubscribe")
    void upsertNeverSendsUnsubscribed() {
        server.expect(requestTo(BASE + "/contacts/ada@example.com"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(request -> assertThat(request.getBody().toString()).doesNotContain("unsubscribed"))
                .andRespond(withSuccess());

        client.upsertContact("ada@example.com", null, props());

        server.verify();
    }

    @Test
    @DisplayName("a non-404 refusal of the PATCH is not followed by a create and does not throw")
    void upsertOtherErrorDoesNotCreate() {
        server.expect(requestTo(BASE + "/contacts/ada@example.com"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        boolean ok = client.upsertContact("ada@example.com", "Ada", props());

        assertThat(ok).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("sendEvent POSTs {event, email, payload} to /events/send")
    void sendEventShape() {
        server.expect(requestTo(BASE + "/events/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_test_key"))
                .andExpect(content().json("{\"event\":\"user.returned\",\"email\":\"ada@example.com\","
                        + "\"payload\":{\"days_away\":42}}", true))
                .andRespond(withSuccess("{\"object\":\"event\"}", MediaType.APPLICATION_JSON));

        boolean ok = client.sendEvent("ada@example.com", "user.returned", Map.of("days_away", 42));

        assertThat(ok).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("sendEvent with no payload still sends an empty object")
    void sendEventEmptyPayload() {
        server.expect(requestTo(BASE + "/events/send"))
                .andExpect(content().json("{\"event\":\"user.signed_up\",\"email\":\"ada@example.com\",\"payload\":{}}", true))
                .andRespond(withSuccess());

        client.sendEvent("ada@example.com", "user.signed_up", null);

        server.verify();
    }

    @Test
    @DisplayName("an HTTP 429 is retried once, and the retry's success is reported")
    void rateLimitedIsRetriedOnce() {
        server.expect(requestTo(BASE + "/events/send")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(BASE + "/events/send")).andRespond(withSuccess());

        boolean ok = client.sendEvent("ada@example.com", "user.activated", Map.of());

        assertThat(ok).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("a 500 from Resend is swallowed: false, never an exception")
    void serverErrorNeverThrows() {
        server.expect(requestTo(BASE + "/events/send")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        boolean ok = client.sendEvent("ada@example.com", "user.activated", Map.of());

        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("deleteContact DELETEs by email and treats 404 as already gone")
    void deleteContactTreats404AsDone() {
        server.expect(requestTo(BASE + "/contacts/gone@example.com"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.deleteContact("gone@example.com")).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("a blank email is refused locally, with no HTTP call")
    void blankEmailNoCall() {
        assertThat(client.upsertContact(" ", "x", props())).isFalse();
        assertThat(client.sendEvent(null, "user.signed_up", Map.of())).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("disabled: every method is a no-op and the HTTP template is never touched")
    void disabledClientMakesNoCall() {
        RestTemplate template = mock(RestTemplate.class);
        Executor executor = mock(Executor.class);
        ResendClient off = new ResendClient(false, "re_key", BASE, template, executor);

        off.submit(() -> { });
        off.upsertContact("ada@example.com", "Ada", props());
        off.sendEvent("ada@example.com", "user.signed_up", Map.of());
        off.deleteContact("ada@example.com");

        assertThat(off.isActive()).isFalse();
        verifyNoInteractions(template, executor);
    }

    @Test
    @DisplayName("enabled with a blank key is inactive too")
    void blankKeyIsInactive() {
        RestTemplate template = mock(RestTemplate.class);
        ResendClient noKey = new ResendClient(true, "  ", BASE, template, SAME_THREAD);

        noKey.sendEvent("ada@example.com", "user.signed_up", Map.of());

        assertThat(noKey.isActive()).isFalse();
        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("submit runs tasks in order on the executor and swallows a task failure")
    void submitRunsInOrderAndSwallows() {
        List<String> ran = new java.util.ArrayList<>();

        client.submit(() -> ran.add("contact"));
        client.submit(() -> { throw new IllegalStateException("boom"); });
        client.submit(() -> ran.add("event"));

        assertThat(ran).containsExactly("contact", "event");
    }

    @Test
    @DisplayName("a rejected enqueue (queue full) is dropped without throwing, and reported as not queued")
    void rejectedEnqueueIsDropped() {
        Executor full = r -> { throw new java.util.concurrent.RejectedExecutionException("full"); };
        ResendClient c = new ResendClient(true, "re_key", BASE, restTemplate, full);

        assertThat(c.submit(() -> { })).isFalse();
        assertThat(c.submitBulk(() -> { })).isFalse();
        assertThat(c.isActive()).isTrue();
    }

    @Test
    @DisplayName("submit reports a queued task; an inactive client queues nothing")
    void submitReportsQueued() {
        assertThat(client.submit(() -> { })).isTrue();
        assertThat(new ResendClient(false, "re_key", BASE, restTemplate, SAME_THREAD).submit(() -> { })).isFalse();
    }

    /** A real single-thread pool whose one worker is parked, so the queue fills up for real. */
    private static java.util.concurrent.ThreadPoolExecutor parkedPool(java.util.concurrent.CountDownLatch gate) {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 1,
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(ResendClient.QUEUE_CAPACITY),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        pool.execute(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return pool;
    }

    @Test
    @DisplayName("bulk work stops at half the queue, keeping the other half for single events (sign-ups, checkouts)")
    void bulkKeepsHeadroomForSingleEvents() throws Exception {
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ThreadPoolExecutor pool = parkedPool(gate);
        try {
            ResendClient c = new ResendClient(true, "re_key", BASE, restTemplate, pool);
            int bulkQueued = 0;
            while (c.submitBulk(() -> { })) bulkQueued++;

            assertThat(bulkQueued).isEqualTo(ResendClient.QUEUE_CAPACITY - ResendClient.BULK_HEADROOM + 1);
            // Single events still get in: the headroom is theirs.
            assertThat(c.submit(() -> { })).isTrue();
            assertThat(pool.getQueue().remainingCapacity()).isGreaterThan(0);
        } finally {
            gate.countDown();
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("a full queue is reported as not queued instead of discarded silently")
    void fullQueueIsReported() throws Exception {
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ThreadPoolExecutor pool = parkedPool(gate);
        try {
            ResendClient c = new ResendClient(true, "re_key", BASE, restTemplate, pool);
            for (int i = 0; i < ResendClient.QUEUE_CAPACITY; i++) {
                assertThat(c.submit(() -> { })).isTrue();
            }

            assertThat(c.submit(() -> { })).isFalse();
        } finally {
            gate.countDown();
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("the production queue ABORTS on overflow: a discarding one would make submit() report a dropped task as queued")
    void productionQueueAbortsOnOverflow() throws Exception {
        ResendClient real = new ResendClient(true, "re_key", BASE);
        java.lang.reflect.Field f = ResendClient.class.getDeclaredField("executor");
        f.setAccessible(true);
        java.util.concurrent.ThreadPoolExecutor pool = (java.util.concurrent.ThreadPoolExecutor) f.get(real);

        assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class);
        assertThat(pool.getQueue().remainingCapacity()).isEqualTo(ResendClient.QUEUE_CAPACITY);
        pool.shutdown();
    }
}
