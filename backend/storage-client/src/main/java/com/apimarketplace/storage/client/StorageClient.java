package com.apimarketplace.storage.client;

import com.apimarketplace.storage.client.dto.FileRefDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

import java.util.Map;

/**
 * HTTP client for storage-service internal API.
 * Follows the same pattern as DataSourceClient and AgentClient.
 */
public class StorageClient {

    private static final Logger log = LoggerFactory.getLogger(StorageClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public StorageClient(String storageServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.baseUrl = storageServiceUrl;
    }

    public StorageClient(RestTemplate restTemplate, String storageServiceUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = storageServiceUrl;
    }

    /**
     * Upload a file with workflow context via byte array.
     */
    public FileRefDto upload(String tenantId, String workflowId, String runId,
                             String stepAlias, String fileName, String mimeType, byte[] content) {
        return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, content, null);
    }

    /**
     * Org-aware variant for file upload with workflow context.
     * Async callers (step execution threads, recovery workers) MUST pass
     * organizationId so the storage row lands in the correct workspace.
     *
     * <p>No-run-context overload - delegates with {@code epoch=0, spawn=0, itemIndex=null,
     * sourceType=null} (server falls back to {@code S3_FILE}). Used by generic / legacy callers.</p>
     */
    public FileRefDto upload(String tenantId, String workflowId, String runId,
                             String stepAlias, String fileName, String mimeType, byte[] content,
                             String organizationId) {
        return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, content,
                organizationId, 0, 0, null, null);
    }

    /**
     * Context-carrying org-aware upload for WORKFLOW file producers. Forwards the real run
     * coordinates ({@code epoch}, {@code spawn}, {@code itemIndex}) and a meaningful
     * {@code sourceType} as query params so the storage row can later be grouped by
     * workflow → epoch → spawn → iteration. {@code itemIndex}/{@code sourceType} are omitted
     * from the query string when null (server defaults apply).
     */
    public FileRefDto upload(String tenantId, String workflowId, String runId,
                             String stepAlias, String fileName, String mimeType, byte[] content,
                             String organizationId,
                             int epoch, int spawn, Integer itemIndex, String sourceType) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .path("/api/internal/storage/upload-bytes")
            .queryParam("workflowId", workflowId)
            .queryParam("runId", runId)
            .queryParam("stepAlias", stepAlias)
            .queryParam("fileName", fileName)
            .queryParam("mimeType", mimeType)
            .queryParam("epoch", epoch)
            .queryParam("spawn", spawn);
        if (itemIndex != null) {
            builder.queryParam("itemIndex", itemIndex);
        }
        if (sourceType != null && !sourceType.isBlank()) {
            builder.queryParam("sourceType", sourceType);
        }
        // build().toUri() (not toUriString()) so RestTemplate does NOT re-encode an
        // already-encoded String - a fileName with spaces/accents would otherwise be
        // double-encoded (%20 -> %2520) and the server-built key would be wrong.
        URI url = builder.encode().build().toUri();

        try {
            HttpHeaders headers = buildHeaders(tenantId, organizationId);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            HttpEntity<byte[]> entity = new HttpEntity<>(content, headers);
            ResponseEntity<FileRefDto> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, FileRefDto.class);

            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to upload file via storage-client: fileName={}, error={}", fileName, e.getMessage());
            return null;
        }
    }

    /**
     * Attach a workflow run context to files that were stored WITHOUT one.
     *
     * <p>A catalog API answering with binary is uploaded from catalog-service, which knows only the
     * tenant, so its {@code storage.storage} row has no workflow and the Files browser shows it at
     * the root rather than inside the run that produced it. The step that made the call knows the
     * context, and adopts its files through here once the call returns.</p>
     *
     * <p>Best-effort by construction: a failure returns 0 and is logged, never thrown. Where a file
     * is filed must not decide whether a workflow step succeeded, and the file itself is already
     * safely stored and already reachable through the step's own output.</p>
     *
     * @return how many rows were adopted; 0 on any failure
     */
    public int adoptRunContext(String tenantId, String organizationId, java.util.Collection<String> fileIds,
                               String workflowId, String runId, String stepKey,
                               int epoch, int spawn, Integer itemIndex) {
        if (fileIds == null || fileIds.isEmpty() || workflowId == null || workflowId.isBlank()) {
            return 0;
        }
        String url = baseUrl + "/api/internal/storage/adopt-run-context";
        try {
            HttpHeaders headers = buildHeaders(tenantId, organizationId);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // HashMap, not Map.of: runId / itemIndex are legitimately null.
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put(com.apimarketplace.common.storage.AdoptRunContextFields.IDS, new java.util.ArrayList<>(fileIds));
            body.put(com.apimarketplace.common.storage.AdoptRunContextFields.WORKFLOW_ID, workflowId);
            body.put(com.apimarketplace.common.storage.AdoptRunContextFields.RUN_ID, runId);
            body.put(com.apimarketplace.common.storage.AdoptRunContextFields.STEP_KEY, stepKey);
            body.put(com.apimarketplace.common.storage.AdoptRunContextFields.EPOCH, epoch);
            body.put(com.apimarketplace.common.storage.AdoptRunContextFields.SPAWN, spawn);
            body.put(com.apimarketplace.common.storage.AdoptRunContextFields.ITEM_INDEX, itemIndex);

            ResponseEntity<java.util.Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), java.util.Map.class);
            Object adopted = response.getBody() == null
                ? null
                : response.getBody().get(com.apimarketplace.common.storage.AdoptRunContextFields.ADOPTED);
            return (adopted instanceof Number n) ? n.intValue() : 0;
        } catch (Exception e) {
            log.warn("Failed to adopt run context for {} file(s): {}", fileIds.size(), e.getMessage());
            return 0;
        }
    }

    /**
     * Record on a generated asset the recipe it was made from.
     *
     * <p>A generation is dispatched from catalog-service, which knows the model, the prompt and the
     * parameters and gets a stored file back. Nothing on the file itself says where it came from,
     * so the recipe is stamped onto its {@code storage.storage} row here, once the asset exists.
     * That is what lets a reader recognise a generated asset among their files and run it again
     * with one word changed.</p>
     *
     * <p>Best-effort by construction, exactly like {@link #adoptRunContext}: a failure returns 0 and
     * is logged, never thrown. The generation has already run and been charged by the time this is
     * called, so whether its recipe was recorded must not decide whether the caller gets the asset
     * it paid for.</p>
     *
     * @return how many rows were stamped; 0 on any failure
     */
    public int stampGenerationProvenance(String tenantId, String organizationId,
                                         java.util.Collection<String> fileIds,
                                         java.util.Map<String, Object> provenance) {
        if (fileIds == null || fileIds.isEmpty() || provenance == null || provenance.isEmpty()) {
            return 0;
        }
        String url = baseUrl + "/api/internal/storage/generation-provenance";
        try {
            HttpHeaders headers = buildHeaders(tenantId, organizationId);
            headers.setContentType(MediaType.APPLICATION_JSON);

            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put(com.apimarketplace.common.storage.GenerationProvenanceFields.IDS,
                    new java.util.ArrayList<>(fileIds));
            body.put(com.apimarketplace.common.storage.GenerationProvenanceFields.PROVENANCE, provenance);

            ResponseEntity<java.util.Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), java.util.Map.class);
            Object stamped = response.getBody() == null
                ? null
                : response.getBody().get(com.apimarketplace.common.storage.GenerationProvenanceFields.STAMPED);
            return (stamped instanceof Number n) ? n.intValue() : 0;
        } catch (Exception e) {
            log.warn("Failed to stamp generation provenance on {} file(s): {}", fileIds.size(), e.getMessage());
            return 0;
        }
    }

    /**
     * Generic upload without workflow context.
     *
     * <p>Returns the same {@link FileRefDto} shape as {@link #upload}: the
     * server's response is a {@code FileRef} regardless of the upload path,
     * so both clients see {@code {_type, path, name, mimeType, size}}. Use
     * {@link #generateDownloadUrl(String, String, int)} to obtain a presigned
     * URL on top of the returned {@code path}.
     */
    public FileRefDto genericUpload(String tenantId, String category,
                                     String fileName, String mimeType, byte[] content) {
        return genericUpload(tenantId, category, fileName, mimeType, content, null);
    }

    /**
     * Org-aware variant for generic file upload.
     * Async callers MUST pass organizationId so the storage row lands in
     * the correct workspace.
     */
    public FileRefDto genericUpload(String tenantId, String category,
                                     String fileName, String mimeType, byte[] content,
                                     String organizationId) {
        String url = baseUrl + "/api/internal/storage/generic-upload";

        try {
            HttpHeaders headers = buildHeaders(tenantId, organizationId);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(content) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            });
            body.add("category", category);

            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<FileRefDto> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, FileRefDto.class);

            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to generic upload via storage-client: fileName={}, error={}", fileName, e.getMessage());
            return null;
        }
    }

    /**
     * Generate a presigned download URL.
     */
    public String generateDownloadUrl(String tenantId, String key, int expiryMinutes) {
        URI url = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .path("/api/internal/storage/presign")
            .queryParam("key", key)
            .queryParam("expiryMinutes", expiryMinutes)
            .encode().build().toUri();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, Map.class);

            if (response.getBody() != null) {
                return (String) response.getBody().get("url");
            }
            return null;
        } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
            // Same key-owner contract as download(): presign 403 means the wrong
            // tenant was presented for the key (org-shared file → pass the owner's).
            log.warn("Presign 403 (key-owner mismatch): key='{}' sent X-User-ID='{}'. "
                    + "Org-shared files must be presigned with the key OWNER's tenant.", key, tenantId);
            return null;
        } catch (Exception e) {
            log.error("Failed to generate download URL: key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Download file content by key.
     */
    public byte[] download(String tenantId, String key) {
        // URI (not toUriString) → RestTemplate must NOT re-encode: a key with spaces/
        // accents (e.g. "Capture d'écran.png") would double-encode to %2520 and 404.
        URI url = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .path("/api/internal/storage/download")
            .queryParam("key", key)
            .encode().build().toUri();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
            ResponseEntity<byte[]> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, byte[].class);

            return response.getBody();
        } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
            // The internal download authorizes by KEY-OWNER prefix. A 403 almost
            // always means the caller passed its own/current-request tenant for a
            // key owned by someone else (org-shared file). After authorizing the
            // entity upstream, pass the OWNER tenant (StorageEntity.getTenantId()).
            log.warn("Download 403 (key-owner mismatch): key='{}' sent X-User-ID='{}'. "
                    + "Org-shared files must be fetched with the key OWNER's tenant.", key, tenantId);
            return null;
        } catch (Exception e) {
            log.error("Failed to download file: key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Delete a file by key.
     */
    public boolean delete(String tenantId, String key) {
        URI url = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .path("/api/internal/storage/delete")
            .queryParam("key", key)
            .encode().build().toUri();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.DELETE, entity, Map.class);

            if (response.getBody() != null) {
                return Boolean.TRUE.equals(response.getBody().get("deleted"));
            }
            return false;
        } catch (Exception e) {
            log.error("Failed to delete file: key={}, error={}", key, e.getMessage());
            return false;
        }
    }

    /**
     * Delete all files for a specific workflow run.
     */
    public int deleteRunFiles(String tenantId, String workflowId, String runId) {
        return deleteRunFiles(tenantId, workflowId, runId, null);
    }

    /**
     * Org-aware variant for cascade run-file deletion.
     * Cascade deletes may run in async contexts where RequestContextHolder
     * is empty, so organizationId must be passed explicitly.
     */
    public int deleteRunFiles(String tenantId, String workflowId, String runId, String organizationId) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .path("/api/internal/storage/delete-run-files")
            .queryParam("workflowId", workflowId)
            .queryParam("runId", runId)
            .toUriString();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.DELETE, entity, Map.class);

            if (response.getBody() != null) {
                Object count = response.getBody().get("deletedCount");
                return count instanceof Number ? ((Number) count).intValue() : 0;
            }
            return 0;
        } catch (Exception e) {
            log.error("Failed to delete run files: workflowId={}, runId={}, error={}", workflowId, runId, e.getMessage());
            return 0;
        }
    }

    /**
     * Check if a file exists.
     */
    public boolean exists(String tenantId, String key) {
        URI url = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .path("/api/internal/storage/exists")
            .queryParam("key", key)
            .encode().build().toUri();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Map.class);

            if (response.getBody() != null) {
                return Boolean.TRUE.equals(response.getBody().get("exists"));
            }
            return false;
        } catch (Exception e) {
            log.error("Failed to check file existence: key={}, error={}", key, e.getMessage());
            return false;
        }
    }

    /**
     * Push a tenant's storage limit to storage-service (plan→quota sync). Best-effort: returns
     * {@code false} on any transport/HTTP error so the caller (an afterCommit hook) never throws.
     * storage-service performs the write in its OWN transaction (commits) and evicts its own
     * read cache - fixing the lost-write + cross-JVM-stale-cache problems of the old in-process path.
     *
     * @param tenantId  tenant (auth user id as string)
     * @param maxBytes  new max bytes (plan's included_storage_bytes)
     * @param softRatio soft-limit ratio (e.g. 0.8)
     * @return true if storage-service acknowledged the update
     */
    public boolean updateTenantStorageLimits(String tenantId, long maxBytes, double softRatio) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .path("/api/internal/storage/quota/tenant/{tenantId}/limits")
            .queryParam("maxBytes", maxBytes)
            .queryParam("softRatio", softRatio)
            .buildAndExpand(tenantId)
            .toUriString();
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
            restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            return true;
        } catch (Exception e) {
            log.error("Failed to update tenant storage limits via storage-client: tenant={}, error={}",
                tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * Ask whether {@code bytes} more could be stored in this scope, without writing anything.
     *
     * <p>For callers that SPEND before they store. Media generation charges the customer inside
     * the provider call and stores the asset afterwards, so an account with no room left pays for
     * something the quota gate then refuses. Asking first is the only point at which that is
     * preventable.
     *
     * <p><b>Fails OPEN.</b> An unreachable or erroring storage-service returns {@code true}, and
     * that is deliberate: this is an advisory pre-check, not the gate. The real gate still runs at
     * write time and still refuses. Failing closed would convert a storage-service blip into a
     * total generation outage for everyone, which is a far worse failure than the one this guards
     * against. A refusal is only ever reported when storage-service actually said "no room".
     *
     * @param bytes how much is about to be added. Probe with 1 to ask "is there ANY room left":
     *              a 0-byte probe passes even at exactly the ceiling, because the quota compares
     *              {@code used <= hardLimit}.
     */
    public boolean hasRoomFor(String tenantId, String organizationId, long bytes) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .path("/api/internal/storage/quota/check")
            .queryParam("tenantId", tenantId)
            .queryParam("bytes", bytes)
            .queryParamIfPresent("organizationId",
                java.util.Optional.ofNullable(organizationId).filter(s -> !s.isBlank()))
            .toUriString();
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Object allowed = response.getBody() == null ? null : response.getBody().get("allowed");
            // Anything other than an explicit false is treated as "there is room" - see the
            // fail-open contract above. A malformed body is a storage-service problem, not a
            // reason to refuse a paid call.
            return !Boolean.FALSE.equals(allowed);
        } catch (Exception e) {
            log.warn("Storage quota pre-check unavailable (treating as room available): tenant={}, org={}, error={}",
                tenantId, organizationId, e.getMessage());
            return true;
        }
    }

    /**
     * Push an organization's storage limit to storage-service. Best-effort (see
     * {@link #updateTenantStorageLimits}).
     */
    public boolean updateOrganizationStorageLimits(String organizationId, long maxBytes, double softRatio) {
        return updateOrganizationStorageLimits(organizationId, maxBytes, softRatio, null);
    }

    /**
     * Same, carrying the account that owns the workspace so the storage side can enforce one
     * allowance across every workspace that account owns. A null {@code accountId} is simply not
     * sent, and storage then leaves any existing attribution alone.
     */
    public boolean updateOrganizationStorageLimits(String organizationId, long maxBytes, double softRatio,
                                                   String accountId) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .path("/api/internal/storage/quota/org/{organizationId}/limits")
            .queryParam("maxBytes", maxBytes)
            .queryParam("softRatio", softRatio)
            .queryParamIfPresent("accountId",
                java.util.Optional.ofNullable(accountId).filter(s -> !s.isBlank()))
            .buildAndExpand(organizationId)
            .toUriString();
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null, organizationId));
            restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            return true;
        } catch (Exception e) {
            log.error("Failed to update org storage limits via storage-client: org={}, error={}",
                organizationId, e.getMessage());
            return false;
        }
    }

    private HttpHeaders buildHeaders(String tenantId) {
        return buildHeaders(tenantId, null);
    }

    private HttpHeaders buildHeaders(String tenantId, String organizationId) {
        HttpHeaders headers = new HttpHeaders();
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        // PR16 - forward X-Organization-ID / X-Organization-Role from the
        // inbound request to keep workspace context across cross-service hops.
        // When organizationId is provided explicitly (async/daemon callers),
        // use it directly; otherwise fall back to the request context forwarder.
        if (organizationId != null && !organizationId.isBlank()) {
            headers.set("X-Organization-ID", organizationId);
        } else {
            OrgContextHeaderForwarder.forward(headers);
        }
        return headers;
    }

}
