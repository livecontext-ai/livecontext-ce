package com.apimarketplace.interfaces.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Thin HTTP client to the orchestrator's interface-cascade endpoint. Called
 * by {@code InterfaceService.deleteInterface} BEFORE the row delete so a
 * deleted interface can never leave dangling references in
 * {@code orchestrator.workflows.plan}.
 *
 * <p>Cross-schema queries are forbidden (CLAUDE.md), so the cascade lives in
 * orchestrator-service; this client is the only legal way for
 * interface-service to ask orchestrator to scrub plan references.
 *
 * <p>If the cascade call fails, the caller treats it as fatal - better to
 * abort the row delete than to half-delete and leave a broken plan.
 */
public class OrchestratorCascadeClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorCascadeClient.class);

    private final RestClient restClient;

    public OrchestratorCascadeClient(String orchestratorUrl) {
        // Bounded timeouts so a partial outage cannot wedge an interface
        // delete indefinitely - exactly when the cascade matters most.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
        this.restClient = RestClient.builder()
            .baseUrl(orchestratorUrl)
            .requestFactory(factory)
            .build();
    }

    /**
     * Synchronously asks orchestrator to remove every reference to
     * {@code interfaceId} from {@code plan.interfaces[]} and
     * {@code plan.edges[]} across all workflows in the given tenant.
     *
     * @return cascade summary (workflowsTouched, entriesRemoved, edgesRemoved)
     * @throws OrchestratorCascadeException on any HTTP/transport failure -
     *         the caller MUST treat this as a deletion abort signal
     */
    @SuppressWarnings("unchecked")
    public CascadeSummary stripInterfaceReferences(String tenantId, String interfaceId) {
        try {
            Map<String, Object> response = restClient.post()
                .uri(uriBuilder -> uriBuilder
                    .path("/api/internal/orchestrator/workflows/strip-interface/{interfaceId}")
                    .queryParam("tenantId", tenantId)
                    .build(interfaceId))
                .retrieve()
                .body(Map.class);

            if (response == null) {
                return new CascadeSummary(0, 0, 0);
            }
            int workflowsTouched = readInt(response, "workflowsTouched");
            int entriesRemoved = readInt(response, "interfaceEntriesRemoved");
            int edgesRemoved = readInt(response, "edgesRemoved");
            return new CascadeSummary(workflowsTouched, entriesRemoved, edgesRemoved);
        } catch (Exception e) {
            log.warn("[OrchestratorCascadeClient] Strip-interface failed tenant={} interface={} cause={}",
                tenantId, interfaceId, e.toString());
            throw new OrchestratorCascadeException(
                "Failed to scrub workflow plans for interface " + interfaceId + ": " + e.getMessage(), e);
        }
    }

    private static int readInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.intValue();
        return 0;
    }

    public record CascadeSummary(int workflowsTouched, int entriesRemoved, int edgesRemoved) {}

    public static class OrchestratorCascadeException extends RuntimeException {
        public OrchestratorCascadeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
