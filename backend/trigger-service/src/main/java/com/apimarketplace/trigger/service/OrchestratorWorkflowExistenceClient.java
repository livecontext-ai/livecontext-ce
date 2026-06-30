package com.apimarketplace.trigger.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cross-schema reconciliation client: asks the orchestrator-service which workflow IDs
 * still exist, so the {@link StandaloneTriggerReaperService} can prune trigger rows
 * whose foreign workflow row was deleted.
 *
 * <p>Mirrors the pattern used by publication-service's {@code PublicationCleanupService}
 * (see {@code OrchestratorInternalClient.getExistingWorkflowIds}). The endpoint
 * ({@code GET /api/internal/publication-support/workflows/exists?ids=...}) is shared
 * across all stale-FK sweepers in the codebase.
 *
 * <p><b>Fail-safe:</b> on any HTTP failure, returns the input set unchanged so the
 * reaper considers all workflows alive - the cost of a transient orchestrator outage
 * is one missed cleanup tick, NOT the wrongful deletion of live triggers.
 */
@Component
public class OrchestratorWorkflowExistenceClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorWorkflowExistenceClient.class);

    private final RestTemplate restTemplate;
    private final String orchestratorUrl;

    /**
     * Production constructor - explicitly marked {@code @Autowired} because the class
     * also exposes a package-private test constructor. With multiple candidate
     * constructors Spring cannot auto-detect which to use and falls back to looking
     * for a default constructor; annotating the real injection target forces the
     * correct choice. Same pattern as {@code RedisPendingAgentStore}.
     */
    @Autowired
    public OrchestratorWorkflowExistenceClient(
            @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        this.restTemplate = new RestTemplate();
        this.orchestratorUrl = orchestratorUrl;
    }

    /** Test constructor - allows injecting a mock RestTemplate. */
    OrchestratorWorkflowExistenceClient(RestTemplate restTemplate, String orchestratorUrl) {
        this.restTemplate = restTemplate;
        this.orchestratorUrl = orchestratorUrl;
    }

    /**
     * Returns the subset of {@code workflowIds} that still exist in
     * {@code orchestrator.workflows}. Empty input → empty output.
     *
     * <p>On HTTP failure, returns the input unchanged (fail-safe - see class javadoc).
     *
     * @param workflowIds set of workflow IDs to check
     * @return subset of input that exists in orchestrator
     */
    public Set<UUID> getExistingWorkflowIds(Set<UUID> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) {
            return Set.of();
        }
        String ids = workflowIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        String url = orchestratorUrl + "/api/internal/publication-support/workflows/exists?ids=" + ids;
        try {
            ResponseEntity<Set<UUID>> response = restTemplate.exchange(
                    url, HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});
            Set<UUID> body = response.getBody();
            return body != null ? body : Set.of();
        } catch (Exception e) {
            log.warn("[OrchestratorWorkflowExistenceClient] Failed to check workflow existence "
                    + "(input size={}): {} - failing safe (assume all exist)", workflowIds.size(), e.getMessage());
            return workflowIds;
        }
    }
}
