package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.PublicationReceiptEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * CE-side service for acquiring publications from the cloud marketplace.
 * Uses the linked cloud account's OAuth token for paid publications.
 *
 * Only active when marketplace.mode=remote (CE monolith).
 */
public class RemoteMarketplaceService {

    private static final Logger logger = LoggerFactory.getLogger(RemoteMarketplaceService.class);

    private final String cloudApiUrl;
    private final RestTemplate restTemplate;
    private final SnapshotCloneService snapshotCloneService;
    private final PublicationReceiptRepository receiptRepository;
    private final CloudLinkService cloudLinkService;
    private final ObjectMapper objectMapper;
    private final AuthClient authClient;
    private final AgentPublicationService agentPublicationService;
    private final ResourcePublicationService resourcePublicationService;
    private final OrchestratorInternalClient orchestratorClient;
    /** Nullable - mirrors the local acquire path's optional WORKFLOW-quota guard. */
    private final EntitlementGuard entitlementGuard;

    public RemoteMarketplaceService(String cloudApiUrl,
                                     SnapshotCloneService snapshotCloneService,
                                     PublicationReceiptRepository receiptRepository,
                                     CloudLinkService cloudLinkService,
                                     ObjectMapper objectMapper,
                                     AuthClient authClient,
                                     AgentPublicationService agentPublicationService,
                                     ResourcePublicationService resourcePublicationService,
                                     OrchestratorInternalClient orchestratorClient,
                                     EntitlementGuard entitlementGuard) {
        this(cloudApiUrl, snapshotCloneService, receiptRepository, cloudLinkService, objectMapper, authClient,
                agentPublicationService, resourcePublicationService, orchestratorClient, entitlementGuard,
                new RestTemplate());
    }

    RemoteMarketplaceService(String cloudApiUrl,
                             SnapshotCloneService snapshotCloneService,
                             PublicationReceiptRepository receiptRepository,
                             CloudLinkService cloudLinkService,
                             ObjectMapper objectMapper,
                             AuthClient authClient,
                             AgentPublicationService agentPublicationService,
                             ResourcePublicationService resourcePublicationService,
                             OrchestratorInternalClient orchestratorClient,
                             EntitlementGuard entitlementGuard,
                             RestTemplate restTemplate) {
        this.cloudApiUrl = cloudApiUrl;
        this.restTemplate = restTemplate;
        this.snapshotCloneService = snapshotCloneService;
        this.receiptRepository = receiptRepository;
        this.cloudLinkService = cloudLinkService;
        this.objectMapper = objectMapper;
        this.authClient = authClient;
        this.agentPublicationService = agentPublicationService;
        this.resourcePublicationService = resourcePublicationService;
        this.orchestratorClient = orchestratorClient;
        this.entitlementGuard = entitlementGuard;
    }

    /**
     * V261 - resolve a non-blank organization_id for the local CE receipt row.
     * The CE user's local auth-service default-personal membership is the right
     * fallback when the API client didn't carry X-Organization-ID (e.g.
     * background job, MCP bridge). Throws on degenerate state (no default org).
     */
    private String resolveAcquirerOrg(String tenantId, String organizationId, UUID publicationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            return organizationId;
        }
        String resolved = authClient.getDefaultOrganizationIdForUser(tenantId);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException(
                    "organizationId required after V261 (tenantId=" + tenantId
                            + ", publicationId=" + publicationId
                            + ") - user has no default organization");
        }
        return resolved;
    }

    /**
     * Unified acquire method: handles both free and paid publications.
     *
     * 1. Check local receipt → if exists, return already-acquired
     * 2. Fetch publication info from cloud (GET /snapshot)
     * 3. If free: snapshot is returned directly, clone it
     * 4. If paid: use cloud access token to POST /acquire-with-auth
     * 5. Clone snapshot, save receipt
     */
    public Map<String, Object> acquirePublication(UUID publicationId, String tenantId) {
        return acquirePublication(publicationId, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> acquirePublication(UUID publicationId, String tenantId, String organizationId) {
        // V261: receipt.organization_id is NOT NULL - resolve fallback from
        // user's default-personal org when request omitted X-Organization-ID.
        organizationId = resolveAcquirerOrg(tenantId, organizationId, publicationId);

        // Dedup / reinstall. A RECEIPT is the durable purchase entitlement; the local
        // CLONE is the installed copy. Deleting an installed app removes the clone but
        // KEEPS the receipt, so "My Purchases > Reinstall" must re-clone from the
        // existing entitlement instead of being rejected. Mirror the LOCAL acquire path,
        // which guards on the CLONE (existsApplicationInScope), not the receipt:
        //   - workflow clone exists -> genuinely installed -> reject "already acquired".
        //   - receipt + no clone    -> REINSTALL: re-clone, reuse the receipt, no re-charge.
        //   - no receipt            -> fresh acquire (paid charges the linked cloud account).
        String normOrg = normalizeScope(organizationId);
        boolean cloneExists = normOrg != null
                ? orchestratorClient.existsBySourcePublication(publicationId, tenantId, normOrg)
                : orchestratorClient.existsBySourcePublication(publicationId, tenantId);
        if (cloneExists) {
            throw new IllegalArgumentException("Publication already acquired");
        }
        boolean reinstall = hasReceiptInScope(tenantId, publicationId, organizationId);

        // Fetch snapshot/info from cloud. A REINSTALL fetches the snapshot WITHOUT
        // charging again (the user already owns it). A fresh acquire of a PAID
        // publication charges the LINKED CLOUD ACCOUNT via acquire-with-auth - a cloud
        // 402 surfaces here as InsufficientCreditsException (→ 402 to the CE caller) and
        // NO local receipt is written, so the user can retry after topping up.
        Map<String, Object> snapshotResponse = reinstall
                ? fetchSnapshotNoCharge(publicationId)
                : fetchFromCloud(publicationId, tenantId);

        String type = snapshotResponse.get("publicationType") instanceof String s ? s : "WORKFLOW";
        String title = (String) snapshotResponse.getOrDefault("title", "Acquired Publication");
        int creditsPaid = snapshotResponse.get("creditsPaid") instanceof Number
                ? ((Number) snapshotResponse.get("creditsPaid")).intValue() : 0;

        // Reinstall-from-receipt is currently wired for application WORKFLOWS only (the
        // clone check above keys on the workflow source-publication index). Agent / Table /
        // Interface / Skill re-clone is a follow-up; reject clearly rather than risk a
        // duplicate clone or a second receipt.
        if (reinstall && !"WORKFLOW".equals(type)) {
            throw new IllegalArgumentException("Reinstall is not yet supported for this item type");
        }

        // Dispatch the CLONE by publication type. The dedup check + cloud fetch
        // (incl. payment) above are shared; each branch clones the right payload
        // under the acquirer's tenant and writes its own remote receipt.
        Map<String, Object> result;
        switch (type) {
            case "AGENT" -> {
                Map<String, Object> agentSnapshot = snapshotResponse.get("agentSnapshot") instanceof Map
                        ? (Map<String, Object>) snapshotResponse.get("agentSnapshot") : null;
                result = agentPublicationService.acquireAgentFromCloudSnapshot(
                        agentSnapshot, tenantId, publicationId, organizationId, creditsPaid);
            }
            case "TABLE", "INTERFACE", "SKILL" -> {
                Map<String, Object> planSnapshot = requirePlanSnapshot(snapshotResponse, publicationId);
                result = resourcePublicationService.acquireResourceFromCloudSnapshot(
                        PublicationType.valueOf(type), planSnapshot, tenantId, publicationId, organizationId, creditsPaid);
            }
            default -> { // WORKFLOW (incl. APPLICATION display mode)
                Map<String, Object> planSnapshot = requirePlanSnapshot(snapshotResponse, publicationId);
                String description = (String) snapshotResponse.get("description");
                List<Map<String, Object>> nodeIcons = snapshotResponse.get("nodeIcons") instanceof List
                        ? (List<Map<String, Object>>) snapshotResponse.get("nodeIcons") : null;
                Map<String, Object> cloneResult = snapshotCloneService.cloneFromSnapshot(
                        planSnapshot, tenantId, publicationId, title, description, nodeIcons, organizationId);
                // Only write a receipt on a FRESH acquire. A reinstall reuses the existing
                // entitlement (the receipt is the durable purchase record), so writing one
                // here would duplicate it - and the user is never re-charged.
                if (!reinstall) {
                    PublicationReceiptEntity receipt = new PublicationReceiptEntity(
                            tenantId, publicationId, creditsPaid, normalizeScope(organizationId));
                    receipt.setRemoteAcquisition(true);
                    receiptRepository.save(receipt);
                }
                // #2a (CE parity with the local acquire path): AUTOMATICALLY create the
                // freely-editable, DECOUPLED WORKFLOW twin of the just-acquired application
                // so the user can customize it in /app/workflows while the APPLICATION
                // clone above stays run-only. Without this, a CE remote install produced a
                // run-only app with NO editable workflow at all (PUT /plan on an
                // APPLICATION is 409 by design - editing lives in the twin). Best-effort:
                // a duplicate failure NEVER fails the acquire (the application clone
                // already succeeded and the receipt is saved). Runs on reinstall too,
                // mirroring the local path (the twin is decoupled, so a delete of the app
                // never removes it and a reinstall legitimately mints a fresh one).
                String applicationWorkflowId = cloneResult.get("workflowId") != null
                        ? cloneResult.get("workflowId").toString() : null;
                duplicateAcquiredApplicationAsEditableWorkflow(planSnapshot, tenantId,
                        normalizeScope(organizationId), publicationId, title, description,
                        nodeIcons, applicationWorkflowId);
                result = new HashMap<>();
                result.put("workflowId", cloneResult.get("workflowId"));
            }
        }

        logger.info("CE tenant {} acquired remote {} publication {}", tenantId, type, publicationId);
        result.put("publicationId", publicationId.toString());
        result.put("title", title);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requirePlanSnapshot(Map<String, Object> snapshotResponse, UUID publicationId) {
        Map<String, Object> planSnapshot = snapshotResponse.get("planSnapshot") instanceof Map
                ? (Map<String, Object>) snapshotResponse.get("planSnapshot") : null;
        if (planSnapshot == null || planSnapshot.isEmpty()) {
            throw new RuntimeException("Cloud returned empty planSnapshot for publication " + publicationId);
        }
        return planSnapshot;
    }

    /**
     * CE mirror of the local acquire path's "#2a" step (see
     * {@code WorkflowPublicationService#duplicateAcquiredApplicationAsEditableWorkflow}):
     * clone the cloud {@code planSnapshot} again as a decoupled, editable {@code WORKFLOW}
     * ({@code source_publication_id = NULL}, lineage in
     * {@code metadata.duplicatedFromApplicationId}). {@code fileNamespaceId} is the cloud
     * publication id - the snapshot's embedded file refs live under
     * {@code _publications/{publicationId}/}, exactly like the application clone that just
     * succeeded through the same engine.
     *
     * <p>Quota: bills WORKFLOW quota only, NO credit (the application already paid).
     * Over-quota → skip the twin, keep the application. Clone failure → compensate ONLY the
     * twin's own rows via {@link OrchestratorInternalClient#deleteDecoupledDuplicateWorkflow}.
     * Either way the acquire is unaffected.
     */
    private void duplicateAcquiredApplicationAsEditableWorkflow(Map<String, Object> planSnapshot,
                                                                String tenantId,
                                                                String orgScope,
                                                                UUID publicationId,
                                                                String title,
                                                                String description,
                                                                List<Map<String, Object>> nodeIcons,
                                                                String applicationWorkflowId) {
        try {
            if (entitlementGuard != null && orgScope != null) {
                entitlementGuard.check(tenantId, ResourceType.WORKFLOW,
                        () -> orchestratorClient.countWorkflowsByOrg(orgScope));
            }
        } catch (RuntimeException quotaDenied) {
            logger.info("[RemoteAcquire/duplicate] WORKFLOW quota reached for tenant={} pub={} - "
                            + "skipping editable duplicate ({}); application clone is unaffected",
                    tenantId, publicationId, quotaDenied.getMessage());
            return;
        }

        try {
            Map<String, Object> duplicate = snapshotCloneService.duplicateToEditableWorkflow(
                    planSnapshot, tenantId, orgScope, title, description, nodeIcons,
                    publicationId, applicationWorkflowId);
            logger.info("[RemoteAcquire/duplicate] tenant {} pub {} -> editable WORKFLOW {} "
                            + "(decoupled from application {})",
                    tenantId, publicationId, duplicate.get("workflowId"), applicationWorkflowId);
        } catch (AcquireCloneFailedException dupFailure) {
            logger.warn("[RemoteAcquire/duplicate] editable duplicate failed for tenant={} pub={}: {} - "
                            + "compensating only its own rows; application clone is unaffected",
                    tenantId, publicationId, dupFailure.getMessage());
            compensateDuplicateFailure(dupFailure.getCreatedWorkflowIds(), tenantId, orgScope);
        } catch (RuntimeException e) {
            logger.warn("[RemoteAcquire/duplicate] editable duplicate errored for tenant={} pub={}: {} - "
                    + "application clone is unaffected", tenantId, publicationId, e.getMessage());
        }
    }

    /**
     * Best-effort compensation for a failed editable-duplicate clone: delete ONLY the rows
     * the duplicate created. Swallows failures - the acquire already succeeded and must not
     * be masked by a cleanup-side error.
     */
    private void compensateDuplicateFailure(Set<String> createdWorkflowIds, String tenantId, String orgScope) {
        if (createdWorkflowIds == null || createdWorkflowIds.isEmpty()) return;
        for (String idStr : createdWorkflowIds) {
            if (idStr == null) continue;
            try {
                orchestratorClient.deleteDecoupledDuplicateWorkflow(UUID.fromString(idStr), tenantId, orgScope);
            } catch (Exception e) {
                logger.warn("[RemoteAcquire/duplicate/compensate] cleanup failed for {}: {}", idStr, e.getMessage());
            }
        }
    }

    private static String normalizeScope(String organizationId) {
        return organizationId != null && !organizationId.isBlank() ? organizationId : null;
    }

    private boolean hasReceiptInScope(String tenantId, UUID publicationId, String organizationId) {
        // Post-V261: organizationId is always present (gateway injects X-Organization-ID;
        // personal-workspace users carry their personal org UUID). The IS-NULL branch is dead.
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException(
                    "organizationId required after V261 (tenantId=" + tenantId
                            + ", publicationId=" + publicationId + ")");
        }
        return receiptRepository.existsByOrganizationIdAndPublicationId(organizationId, publicationId);
    }

    /**
     * Fetch snapshot from cloud. For free publications, GET /snapshot returns data directly.
     * For paid publications, GET /snapshot returns 402 with creditsPerUse → we use
     * POST /acquire-with-auth with the linked cloud account's access token.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchFromCloud(UUID publicationId, String tenantId) {
        String snapshotUrl = cloudApiUrl + "/ce-marketplace/" + publicationId + "/snapshot";

        try {
            // Try free fetch first
            Map<String, Object> response = restTemplate.getForObject(snapshotUrl, Map.class);
            if (response == null) {
                throw new RuntimeException("Cloud returned null for publication " + publicationId);
            }

            // Check if cloud returned an error (e.g. payment required)
            if (response.containsKey("error")) {
                if (response.containsKey("acquireEndpoint") || response.containsKey("creditsPerUse")) {
                    // Paid publication → use acquire-with-auth
                    return acquireWithAuth(publicationId, tenantId);
                }
                throw new RuntimeException("Cloud error: " + response.get("error"));
            }

            return response;

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 402) {
                // Paid publication → use acquire-with-auth
                return acquireWithAuth(publicationId, tenantId);
            }
            logger.error("Cloud API error for publication {}: {} {}", publicationId, e.getStatusCode(), e.getMessage());
            throw new RuntimeException("Failed to fetch publication from cloud: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to fetch snapshot from cloud for publication {}: {}", publicationId, e.getMessage());
            throw new RuntimeException("Failed to fetch publication from cloud marketplace: " + e.getMessage(), e);
        }
    }

    /**
     * Re-fetch the frozen snapshot for a REINSTALL (the caller already holds a receipt).
     * Snapshot-only: it NEVER calls acquire-with-auth, so a paid publication is NEVER
     * re-charged. A free publication returns its snapshot; a paid one (402 / payment-
     * required marker) surfaces a clear, non-charging error - entitled paid re-download
     * is a follow-up that needs a cloud entitlement endpoint.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchSnapshotNoCharge(UUID publicationId) {
        String snapshotUrl = cloudApiUrl + "/ce-marketplace/" + publicationId + "/snapshot";
        try {
            Map<String, Object> response = restTemplate.getForObject(snapshotUrl, Map.class);
            if (response == null) {
                throw new RuntimeException("Cloud returned null for publication " + publicationId);
            }
            if (response.containsKey("error")) {
                if (response.containsKey("acquireEndpoint") || response.containsKey("creditsPerUse")) {
                    throw new IllegalArgumentException("Reinstall of a paid publication is not yet supported");
                }
                throw new RuntimeException("Cloud error: " + response.get("error"));
            }
            return response;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 402) {
                throw new IllegalArgumentException("Reinstall of a paid publication is not yet supported");
            }
            logger.error("Cloud snapshot re-fetch error for publication {}: {} {}", publicationId, e.getStatusCode(), e.getMessage());
            throw new RuntimeException("Failed to re-fetch publication from cloud: " + e.getMessage(), e);
        }
    }

    /**
     * Acquire a paid publication using the linked cloud account's access token.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> acquireWithAuth(UUID publicationId, String tenantId) {
        // Get tenant ID as Long for CloudLinkService
        Long tenantIdLong;
        try {
            tenantIdLong = Long.parseLong(tenantId);
        } catch (NumberFormatException e) {
            throw new CloudLinkService.CloudAccountNotLinkedException(
                    "Cloud account linking requires a numeric tenant ID");
        }

        // Get cloud access token from linked account
        String accessToken = cloudLinkService.getCloudAccessToken(tenantIdLong);

        String acquireUrl = cloudApiUrl + "/ce-marketplace/" + publicationId + "/acquire-with-auth";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    acquireUrl, HttpMethod.POST, entity, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Cloud acquire-with-auth failed: " + response.getStatusCode());
            }
            return response.getBody();

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 402) {
                throw new InsufficientCreditsException("Insufficient credits on cloud account");
            }
            if (e.getStatusCode().value() == 403) {
                throw new CloudLinkService.CloudAccountNotLinkedException(
                        "Cloud account access denied. Please re-link your account.");
            }
            logger.error("Cloud acquire-with-auth error for {}: {} {}", publicationId, e.getStatusCode(), e.getMessage());
            throw new RuntimeException("Failed to acquire paid publication: " + e.getMessage(), e);
        }
    }

    // =====================================================================
    // Cloud-parity read proxies (linked CE marketplace UI)
    //
    // A cloud-linked CE renders the SAME marketplace UI as cloud. The cloud's
    // listing/search/highlights read endpoints are public (anonymous), but the
    // CE frontend must not hardcode the cloud origin - `marketplace.cloud-api-url`
    // is the single source of the cloud URL - so the CE backend proxies these
    // reads. All three are FAIL-SOFT: an unreachable cloud degrades to an empty
    // payload (the UI shows its empty state / hides the highlights row) instead
    // of breaking the page.
    // =====================================================================

    /**
     * Proxy of the cloud's public marketplace listing
     * ({@code GET /publications/marketplace}). Fail-soft: empty page on upstream failure.
     */
    public Map<String, Object> fetchMarketplacePublications(int page, int size, String category) {
        try {
            return getCloudJson(cloudUri("/publications/marketplace", builder -> {
                builder.queryParam("page", page).queryParam("size", size);
                if (category != null && !category.isBlank()) {
                    builder.queryParam("category", category);
                }
            }));
        } catch (Exception e) {
            logger.warn("Cloud marketplace listing unavailable, returning empty page: {}", e.getMessage());
            Map<String, Object> empty = new HashMap<>();
            empty.put("publications", List.of());
            empty.put("count", 0);
            empty.put("totalPages", 0);
            empty.put("page", page);
            empty.put("size", size);
            return empty;
        }
    }

    /**
     * Proxy of the cloud's public marketplace search
     * ({@code GET /publications/search}). Fail-soft: empty result list on upstream failure.
     */
    public Map<String, Object> searchMarketplacePublications(String query, String category) {
        try {
            return getCloudJson(cloudUri("/publications/search", builder -> {
                builder.queryParam("q", query);
                if (category != null && !category.isBlank()) {
                    builder.queryParam("category", category);
                }
            }));
        } catch (Exception e) {
            logger.warn("Cloud marketplace search unavailable, returning empty result: {}", e.getMessage());
            return Map.of("publications", List.of(), "count", 0);
        }
    }

    /**
     * Proxy of the cloud's admin-curated highlights row
     * ({@code GET /publications/highlights/{displayMode}}). The caller (controller)
     * validates {@code displayMode} against the DisplayMode enum before this runs.
     * Fail-soft: empty highlights on upstream failure, so the UI hides the row.
     */
    public Map<String, Object> fetchHighlights(String displayMode) {
        try {
            return getCloudJson(cloudUri("/publications/highlights/" + displayMode, builder -> { }));
        } catch (Exception e) {
            logger.warn("Cloud highlights unavailable for {}, returning empty list: {}", displayMode, e.getMessage());
            return Map.of("displayMode", displayMode, "highlights", List.of());
        }
    }

    // =====================================================================
    // Cloud-parity per-publication read proxies (linked CE marketplace UI)
    //
    // The listing/search/highlights proxies above only populate the marketplace
    // GRID. Each rendered card (and the detail page reached by clicking one)
    // then reads PER-PUBLICATION public resources - the publication detail, the
    // landing-snapshot / showcase-render thumbnail, the agent fleet snapshot,
    // and the showcase run-state / aggregated-steps / per-epoch state - plus the
    // publisher's avatar. On a cloud-linked CE these all carry CLOUD ids that do
    // not exist in the local DB, so hitting the local `/publications/by-id/...`
    // endpoints 404s (broken thumbnails + a 404 detail page). These proxies
    // forward each read to the cloud public API via `marketplace.cloud-api-url`,
    // exactly like the grid above, so the CE never hardcodes the cloud origin.
    //
    // Unlike the grid proxies these are NOT fail-soft-to-empty: the cloud's
    // status + body are passed THROUGH so the marketplace UI's existing
    // per-resource handling fires (a real 404 shows the not-found / fallback
    // state, a 200 renders). A transport failure maps to 502 (JSON) / 404
    // (avatar) so the same fallback path runs.
    // =====================================================================

    /**
     * Proxy a cloud PUBLIC per-publication read under
     * {@code /publications/by-id/{publicationId}[/subPath]} (detail when
     * {@code subPath} is blank, else {@code landing-snapshot},
     * {@code showcase-render}, {@code agent-snapshot}, {@code run-state},
     * {@code aggregated-steps}, {@code epochs/{n}/state|signals}). The body is
     * forwarded verbatim as a JSON string. {@code subPath} is always a caller
     * (controller) literal or a typed path variable - never arbitrary input -
     * so no path traversal reaches the cloud.
     */
    public ResponseEntity<String> proxyPublicByIdJson(UUID publicationId, String subPath,
                                                      MultiValueMap<String, String> queryParams) {
        String path = "/publications/by-id/" + publicationId
                + (subPath == null || subPath.isBlank() ? "" : "/" + subPath);
        URI uri = cloudUri(path, builder -> {
            if (queryParams != null) {
                queryParams.forEach((key, values) ->
                        values.forEach(value -> builder.queryParam(key, value)));
            }
        });
        try {
            ResponseEntity<String> cloud = restTemplate.getForEntity(uri, String.class);
            return ResponseEntity.status(cloud.getStatusCode())
                    .contentType(responseContentType(cloud.getHeaders(), MediaType.APPLICATION_JSON))
                    .body(cloud.getBody());
        } catch (HttpStatusCodeException e) {
            // Pass the cloud's status + body straight through (e.g. a genuine 404
            // not-found), so the UI shows its not-found / fallback state.
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(responseContentType(e.getResponseHeaders(), MediaType.APPLICATION_JSON))
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.warn("Cloud by-id read unavailable for {} [{}]: {}", publicationId, subPath, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"cloud_unreachable\"}");
        }
    }

    /**
     * Proxy the cloud's PUBLIC user-avatar image ({@code GET /users/{userId}/avatar})
     * for publisher avatars on cloud-sourced marketplace cards. Streams the raw
     * image bytes + content type through. Any failure maps to 404 so the
     * frontend {@code <img onError>} falls back to client-side initials.
     * {@code userId} is appended as an ENCODED single path segment, so no path
     * traversal reaches the cloud.
     */
    public ResponseEntity<byte[]> proxyUserAvatar(String userId) {
        URI uri = UriComponentsBuilder.fromUriString(cloudApiUrl + "/users")
                .pathSegment(userId, "avatar")
                .build().encode().toUri();
        try {
            ResponseEntity<byte[]> cloud = restTemplate.getForEntity(uri, byte[].class);
            return ResponseEntity.status(cloud.getStatusCode())
                    .contentType(responseContentType(cloud.getHeaders(), MediaType.IMAGE_PNG))
                    .body(cloud.getBody());
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).build();
        } catch (Exception e) {
            logger.warn("Cloud avatar unavailable for user {}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * Proxy the cloud's PUBLIC user profile ({@code GET /users/public/by-id/{userId}})
     * so a cloud-linked CE can resolve a cloud publisher/reviewer's {@code @handle}
     * from the cloud user id - which is absent from the local auth DB, so the local
     * by-id read 404s. Used to deep-link "View profile" on a cloud-sourced card to
     * the cloud profile page. The JSON body is forwarded verbatim and the cloud's
     * status (e.g. a genuine 404 for an unknown / PRIVATE profile) is passed straight
     * through so the UI can no-op gracefully. {@code userId} is an ENCODED single path
     * segment, so no path traversal reaches the cloud.
     */
    public ResponseEntity<String> proxyUserProfile(String userId) {
        URI uri = UriComponentsBuilder.fromUriString(cloudApiUrl + "/users/public/by-id")
                .pathSegment(userId)
                .build().encode().toUri();
        try {
            ResponseEntity<String> cloud = restTemplate.getForEntity(uri, String.class);
            return ResponseEntity.status(cloud.getStatusCode())
                    .contentType(responseContentType(cloud.getHeaders(), MediaType.APPLICATION_JSON))
                    .body(cloud.getBody());
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(responseContentType(e.getResponseHeaders(), MediaType.APPLICATION_JSON))
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.warn("Cloud profile unavailable for user {}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"cloud_unreachable\"}");
        }
    }

    private static MediaType responseContentType(HttpHeaders headers, MediaType fallback) {
        MediaType contentType = headers != null ? headers.getContentType() : null;
        return contentType != null ? contentType : fallback;
    }

    private URI cloudUri(String path, Consumer<UriComponentsBuilder> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(cloudApiUrl + path);
        params.accept(builder);
        return builder.build().encode().toUri();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getCloudJson(URI uri) {
        Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
        if (response == null) {
            throw new RuntimeException("Cloud returned an empty body for " + uri.getPath());
        }
        return response;
    }

    /**
     * Exception indicating insufficient credits on the linked cloud account.
     */
    public static class InsufficientCreditsException extends RuntimeException {
        public InsufficientCreditsException(String message) {
            super(message);
        }
    }
}
