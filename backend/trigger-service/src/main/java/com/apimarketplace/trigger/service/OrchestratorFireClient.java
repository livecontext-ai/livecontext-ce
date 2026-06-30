package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.client.dto.DatasourceEventDispatchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Thin client for calling orchestrator's datasource trigger fire endpoint.
 *
 * Kept small on purpose: every subscription match becomes one HTTP call.
 * Failures are logged but swallowed so one slow/down workflow cannot block
 * fan-out to its siblings (the caller already runs off the DB commit, so
 * there is no user-facing request to fail anyway).
 */
@Component
public class OrchestratorFireClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorFireClient.class);

    private final RestTemplate restTemplate;
    private final String orchestratorUrl;

    public OrchestratorFireClient(
            @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        this.restTemplate = new RestTemplate();
        this.orchestratorUrl = orchestratorUrl;
    }

    /**
     * Call orchestrator to fire a datasource trigger on the matching subscription's workflow.
     * Fire-and-forget: never throws.
     *
     * <p>Carries {@code organizationId} explicitly through the body (and as a
     * gateway-injected header so downstream guards can read either source).
     * Forwarded from {@link DatasourceEventDispatchRequest#getOrganizationId()}
     * which is stamped at subscription-creation time from the datasource's own
     * workspace.
     */
    public void fire(UUID workflowId, String triggerId, String tenantId,
                     DatasourceEventDispatchRequest event) {
        String url = orchestratorUrl + "/api/internal/triggers/datasource/fire";
        Map<String, Object> body = new HashMap<>();
        body.put("workflowId", workflowId);
        body.put("triggerId", triggerId);
        body.put("tenantId", tenantId);
        body.put("organizationId", event.getOrganizationId());
        body.put("eventType", event.getEventType() != null ? event.getEventType().name() : null);
        body.put("dataSourceId", event.getDataSourceId());
        body.put("rowId", event.getRowId());
        body.put("row", event.getRow());
        body.put("previousRow", event.getPreviousRow());
        body.put("triggeredAt", event.getTriggeredAt() != null
                ? event.getTriggeredAt().toString()
                : Instant.now().toString());

        // 2026-05-18 - forward identity + scope headers so the orchestrator
        // internal endpoint sees the same context as a gateway-fronted call.
        // Prior to this fix, fire() built bare Content-Type headers only, so
        // the @Async @TransactionalEventListener boundary made the entire
        // datasource → trigger → orchestrator fan-out org-scope blind.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null && !tenantId.isBlank()) {
            headers.set("X-User-ID", tenantId);
        }
        if (event.getOrganizationId() != null && !event.getOrganizationId().isBlank()) {
            headers.set("X-Organization-ID", event.getOrganizationId());
        }
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
            log.debug("Fired datasource trigger workflow={} trigger={} event={} org={}",
                    workflowId, triggerId, event.getEventType(), event.getOrganizationId());
        } catch (Exception e) {
            log.warn("Failed to fire datasource trigger workflow={} trigger={}: {}",
                    workflowId, triggerId, e.getMessage());
        }
    }
}
