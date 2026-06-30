package com.apimarketplace.common.credit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Dead-letter handler that sends failed credit consumption events to auth-service
 * via HTTP POST. Used by services that don't own the dead-letter table.
 *
 * Fire-and-forget with error logging - this is already a fallback mechanism,
 * so we don't retry the HTTP call itself.
 *
 * <p>Phase 6 MIGRATION_ORG_ID_NOT_NULL (CC-2, 2026-05-19): the 9-arg overload
 * forwards the explicit {@code organizationId} as an {@code X-Organization-ID}
 * header so the auth-service receiver can stamp the V261-NOT-NULL column even
 * though the call originates from a daemon thread with no servlet request bound.
 */
public class HttpCreditDeadLetterHandler implements CreditDeadLetterHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpCreditDeadLetterHandler.class);

    private final RestTemplate restTemplate;
    private final String deadLetterServiceUrl;

    public HttpCreditDeadLetterHandler(RestTemplate restTemplate, String deadLetterServiceUrl) {
        this.restTemplate = restTemplate;
        this.deadLetterServiceUrl = deadLetterServiceUrl;
    }

    @Override
    public void persistFailedConsumption(String tenantId, String sourceType, String sourceId,
                                          String provider, String model,
                                          Integer promptTokens, Integer completionTokens,
                                          String errorReason, String organizationId) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", tenantId);
            body.put("sourceType", sourceType);
            body.put("sourceId", sourceId);
            body.put("provider", provider != null ? provider : "");
            body.put("model", model != null ? model : "");
            body.put("promptTokens", promptTokens != null ? promptTokens : 0);
            body.put("completionTokens", completionTokens != null ? completionTokens : 0);
            body.put("errorReason", errorReason != null ? errorReason : "");
            // Carry orgId in the body too - the receiver reads from header first
            // and falls back to body for legacy callers that didn't set the header.
            if (organizationId != null && !organizationId.isBlank()) {
                body.put("organizationId", organizationId);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (organizationId != null && !organizationId.isBlank()) {
                headers.set("X-Organization-ID", organizationId);
            }
            restTemplate.postForEntity(
                    deadLetterServiceUrl + "/api/internal/auth/credit/dead-letter",
                    new HttpEntity<>(body, headers), Void.class);
            log.warn("Dead-letter entry forwarded to auth-service for tenant={}, source={}/{}, org={}",
                    tenantId, sourceType, sourceId, organizationId);
        } catch (Exception e) {
            log.error("Failed to forward dead-letter entry to auth-service for tenant {}: {}",
                    tenantId, e.getMessage());
        }
    }
}
