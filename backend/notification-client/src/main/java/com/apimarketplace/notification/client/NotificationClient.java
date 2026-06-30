package com.apimarketplace.notification.client;

import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.notification.client.dto.NotificationEmitResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * HTTP client for cross-service notification emission to orchestrator-service.
 *
 * <p><b>Mandatory tight timeouts</b> (2s connect, 5s read): producers
 * (auth-service {@code OAuth2RefreshScheduler}, trigger-service
 * {@code TriggerLifecycleManager}, agent-service {@code AgentTaskService},
 * catalog-service billing) run in scheduled / hot-path contexts. If the
 * orchestrator hangs (OOM, GC pause), default {@code RestTemplate} blocks
 * indefinitely and propagates the stall back into producer pools - observed
 * cascade failure pattern from {@code project_oom_2026_05_06} memory.
 *
 * <p><b>Failure semantics</b>: any HTTP / network error is logged and
 * swallowed. Notifications are best-effort - the producer's primary work
 * (refresh token, suspend trigger, complete agent task) MUST NOT roll back
 * because the bell row failed to write. Producers always check the boolean
 * return; {@code false} means notification not delivered, but the producer
 * proceeds.
 *
 * <p><b>Idempotency</b>: orchestrator endpoint uses
 * {@code INSERT ON CONFLICT (tenant_id, category, source_id) DO NOTHING}.
 * Retries with the same {@code sourceId} are safe.
 */
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    /** Connect timeout - orchestrator pod local, ~10ms typical, 2s is generous. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** Read timeout - endpoint is one DB write + Redis publish, ~50ms typical. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public NotificationClient(String orchestratorBaseUrl) {
        this(buildRestTemplate(), orchestratorBaseUrl);
    }

    public NotificationClient(RestTemplate restTemplate, String orchestratorBaseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = orchestratorBaseUrl;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return new RestTemplate(factory);
    }

    /**
     * Emit one notification row in orchestrator. Returns {@code true} if the
     * server reports an insert (or any 2xx response - server idempotency is
     * authoritative). Returns {@code false} on any error; caller MUST tolerate
     * missing notifications.
     */
    public boolean emit(NotificationEmitRequest request) {
        if (request == null || request.getTenantId() == null || request.getCategory() == null) {
            log.warn("[notification-client] Skipping emit - missing required fields");
            return false;
        }
        String url = baseUrl + "/api/internal/notifications/emit";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // PR16 - forward org context.
            OrgContextHeaderForwarder.forward(headers);
            HttpEntity<NotificationEmitRequest> entity = new HttpEntity<>(request, headers);
            ResponseEntity<NotificationEmitResponse> response =
                    restTemplate.postForEntity(url, entity, NotificationEmitResponse.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            // Best-effort: swallow + log. Orchestrator OOM / network blip / 4xx
            // validation error MUST NOT propagate back into the producer's
            // hot path.
            log.warn("[notification-client] Emit failed for category={} tenant={}: {}",
                    request.getCategory(), request.getTenantId(), e.getMessage());
            return false;
        }
    }

}
