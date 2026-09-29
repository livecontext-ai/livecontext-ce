package com.apimarketplace.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Asks orchestrator-service whether a row served by ANOTHER service belongs to the application a
 * share link exposes. Orchestrator owns the workflows and runs that define that application, and
 * cross-schema reads are forbidden, so interface-service and storage-service ask it over HTTP
 * ({@code /api/internal/orchestrator/share-scope/*}).
 *
 * <p>Fail-closed: any transport error, non-2xx or unexpected body answers {@code false}. A share
 * visitor then gets a 404 for that one row, never the owner's data.
 */
public class SharedApplicationScopeClient {

    private static final Logger log = LoggerFactory.getLogger(SharedApplicationScopeClient.class);

    private static final String BASE_PATH = "/api/internal/orchestrator/share-scope";

    private final RestClient restClient;

    public SharedApplicationScopeClient(String orchestratorUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(orchestratorUrl)
                .requestFactory(factory)
                .build();
    }

    /** Test seam: a pre-built client (e.g. bound to a MockRestServiceServer). */
    public SharedApplicationScopeClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * True when {@code interfaceId} is referenced by a workflow of the shared application
     * ({@code source_publication_id == publicationId}) in the owner's workspace. The workspace
     * id alone is the scope: every workflow row carries one, and the lookup is org-strict.
     */
    public boolean interfaceBelongsToApplication(UUID publicationId, String organizationId, UUID interfaceId) {
        if (publicationId == null || interfaceId == null || isBlank(organizationId)) {
            return false;
        }
        return ask(BASE_PATH + "/interfaces/{interfaceId}?publicationId={pub}&organizationId={org}",
                interfaceId, publicationId, organizationId);
    }

    /**
     * True when a file row tagged with {@code runId} / {@code workflowId} was produced by the shared
     * application: the run was started for the publication, the workflow is one of its clones,
     * or the file id is referenced by an output of one of the application's runs (files a tool
     * uploaded without run tags).
     */
    public boolean fileBelongsToApplication(UUID publicationId, String tenantId, String organizationId,
                                            String runId, String workflowId, UUID fileId) {
        if (publicationId == null || (isBlank(runId) && isBlank(workflowId) && fileId == null)) {
            return false;
        }
        return ask(BASE_PATH + "/files?publicationId={pub}&tenantId={tenant}&organizationId={org}"
                        + "&runId={run}&workflowId={wf}&fileId={file}",
                publicationId, nullToEmpty(tenantId), nullToEmpty(organizationId), nullToEmpty(runId),
                nullToEmpty(workflowId), fileId == null ? "" : fileId.toString());
    }

    @SuppressWarnings("unchecked")
    private boolean ask(String uriTemplate, Object... vars) {
        try {
            Map<String, Object> body = restClient.get().uri(uriTemplate, vars).retrieve().body(Map.class);
            return body != null && Boolean.TRUE.equals(body.get("allowed"));
        } catch (Exception e) {
            log.warn("[SharedApplicationScope] scope check failed, denying: {}", e.toString());
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
