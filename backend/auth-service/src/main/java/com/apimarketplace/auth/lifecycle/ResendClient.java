package com.apimarketplace.auth.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Minimal Resend client for the cloud lifecycle emails: contact upsert, contact delete and
 * named events ({@code POST /events/send}). The email sequences themselves are Resend
 * Automations, this side only feeds them.
 *
 * <p><b>Performance contract</b>, copied from {@code PostHogAnalyticsClient}: callers never
 * do I/O on their own thread. {@link #submit} hands a task to ONE bounded background thread
 * with a DROP-on-overflow policy, so a Resend outage or burst drops work and never queues
 * unboundedly or back-pressures a login, a checkout or a credit debit. A single thread is on
 * purpose: tasks for one user run in submission order, so a contact sync submitted before an
 * event is applied before it. Short HTTP timeouts (2 s connect, 3 s read).
 *
 * <p><b>Never throws.</b> Every public method swallows and logs at DEBUG/WARN.
 *
 * <p><b>Inert unless active</b>: {@code enabled=false} or a blank api key makes every method
 * a no-op without any HTTP call. That is the CE (self-hosted) default.
 */
public class ResendClient {

    private static final Logger log = LoggerFactory.getLogger(ResendClient.class);

    static final String DEFAULT_BASE_URL = "https://api.resend.com";
    /** Capacity of the single worker's queue. */
    static final int QUEUE_CAPACITY = 1000;
    /** Free queue slots kept for single events: {@link #submitBulk} refuses below this. */
    static final int BULK_HEADROOM = QUEUE_CAPACITY / 2;
    /** Wait before the single retry of a request Resend answered with HTTP 429. */
    private static final long RATE_LIMIT_RETRY_WAIT_MS = 1_000L;

    private final boolean active;
    private final String apiKey;
    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final Executor executor;

    public ResendClient(boolean enabled, String apiKey, String baseUrl) {
        this(enabled, apiKey, baseUrl, null, null);
    }

    /** Test seam: inject the template (bound to a mock server) and a same-thread executor. */
    ResendClient(boolean enabled, String apiKey, String baseUrl, RestTemplate restTemplate, Executor executor) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.active = enabled && !this.apiKey.isEmpty();
        String b = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        this.baseUrl = b;
        if (active) {
            this.restTemplate = restTemplate != null ? restTemplate : defaultRestTemplate();
            this.executor = executor != null ? executor : defaultExecutor();
            log.info("[lifecycle] Resend lifecycle emails enabled (base={})", b);
        } else {
            this.restTemplate = null;
            this.executor = null;
        }
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Fire-and-forget: runs {@code task} on the single background thread, dropped on overflow.
     *
     * @return true when the task was queued, false when it was dropped (inactive client, full queue)
     */
    public boolean submit(Runnable task) {
        if (!active || task == null) return false;
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.debug("[lifecycle] task failed (dropped): {}", e.toString());
                }
            });
            return true;
        } catch (RejectedExecutionException dropped) {
            // Queue full: intentionally dropped, never block the caller.
            return false;
        } catch (Exception e) {
            log.debug("[lifecycle] enqueue failed (dropped): {}", e.toString());
            return false;
        }
    }

    /**
     * Like {@link #submit}, for BULK work (the monthly recap): refused while fewer than
     * {@link #BULK_HEADROOM} queue slots are free. A bulk sender that fills the queue would make
     * the next sign-up or checkout event the one that gets dropped; refusing early instead tells
     * the bulk sender to slow down (it retries later) and keeps that headroom for single events.
     *
     * @return true when queued; false when refused for headroom, dropped, or the client is inactive
     */
    public boolean submitBulk(Runnable task) {
        if (!active || task == null) return false;
        if (executor instanceof ThreadPoolExecutor pool && pool.getQueue().remainingCapacity() < BULK_HEADROOM) {
            return false;
        }
        return submit(task);
    }

    /**
     * Creates or updates the contact keyed by email: {@code PATCH /contacts/{email}}, and on
     * 404 {@code POST /contacts}. Synchronous, meant to run inside a {@link #submit} task.
     * The contact's {@code unsubscribed} flag is never sent, so a click on an unsubscribe
     * link is never undone by a sync.
     *
     * @return true when Resend accepted the write
     */
    public boolean upsertContact(String email, String firstName, Map<String, String> properties) {
        if (!active || isBlank(email)) return false;
        Map<String, Object> body = new LinkedHashMap<>();
        if (!isBlank(firstName)) body.put("first_name", firstName);
        if (properties != null && !properties.isEmpty()) body.put("properties", properties);
        try {
            HttpStatusCode status = exchange(HttpMethod.PATCH, "/contacts/{email}", body, email);
            if (status.is2xxSuccessful()) return true;
            if (status.value() != 404) {
                log.warn("[lifecycle] contact update refused by Resend: HTTP {}", status.value());
                return false;
            }
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("email", email);
            create.putAll(body);
            HttpStatusCode created = exchange(HttpMethod.POST, "/contacts", create);
            if (!created.is2xxSuccessful()) {
                log.warn("[lifecycle] contact create refused by Resend: HTTP {}", created.value());
            }
            return created.is2xxSuccessful();
        } catch (Exception e) {
            log.debug("[lifecycle] contact upsert failed (dropped): {}", e.toString());
            return false;
        }
    }

    /**
     * {@code POST /events/send} with {@code {event, email, payload}}. Synchronous, meant to run
     * inside a {@link #submit} task.
     */
    public boolean sendEvent(String email, String event, Map<String, Object> payload) {
        if (!active || isBlank(email) || isBlank(event)) return false;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", event);
        body.put("email", email);
        body.put("payload", payload != null ? payload : Map.of());
        try {
            HttpStatusCode status = exchange(HttpMethod.POST, "/events/send", body);
            if (!status.is2xxSuccessful()) {
                log.warn("[lifecycle] event {} refused by Resend: HTTP {}", event, status.value());
            }
            return status.is2xxSuccessful();
        } catch (Exception e) {
            log.debug("[lifecycle] event {} failed (dropped): {}", event, e.toString());
            return false;
        }
    }

    /** {@code DELETE /contacts/{email}}. A 404 (no such contact) counts as done. */
    public boolean deleteContact(String email) {
        if (!active || isBlank(email)) return false;
        try {
            HttpStatusCode status = exchange(HttpMethod.DELETE, "/contacts/{email}", null, email);
            return status.is2xxSuccessful() || status.value() == 404;
        } catch (Exception e) {
            log.debug("[lifecycle] contact delete failed (dropped): {}", e.toString());
            return false;
        }
    }

    /**
     * One request, returning the status instead of throwing on 4xx/5xx. A 429 is retried
     * once after a one-second wait (Resend's limit is per second), because Resend rate-limits per team and a
     * burst of sign-ups is exactly when a dropped event costs the most.
     */
    private HttpStatusCode exchange(HttpMethod method, String path, Object body, Object... uriVars) {
        HttpStatusCode status = exchangeOnce(method, path, body, uriVars);
        if (status.value() == 429) {
            sleepQuietly(RATE_LIMIT_RETRY_WAIT_MS);
            status = exchangeOnce(method, path, body, uriVars);
        }
        return status;
    }

    private HttpStatusCode exchangeOnce(HttpMethod method, String path, Object body, Object... uriVars) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        try {
            return restTemplate.exchange(baseUrl + path, method, new HttpEntity<>(body, headers), String.class, uriVars)
                    .getStatusCode();
        } catch (HttpStatusCodeException e) {
            return e.getStatusCode();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static RestTemplate defaultRestTemplate() {
        // JDK client, not SimpleClientHttpRequestFactory: HttpURLConnection cannot send PATCH.
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        return new RestTemplate(factory);
    }

    private static Executor defaultExecutor() {
        return new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "lifecycle-resend");
                    t.setDaemon(true);
                    return t;
                },
                // Abort (not Discard) so submit() can report the drop; it catches the rejection.
                new ThreadPoolExecutor.AbortPolicy());
    }
}
