package com.apimarketplace.publication.service;

import com.apimarketplace.publication.utils.AvatarUrlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Copies a publisher's uploaded/AI-generated avatar file into the ACQUIRER's tenant at
 * acquisition time and returns the acquirer-owned public URL.
 *
 * <p>Snapshot philosophy: an acquired app must not depend on the publisher keeping
 * anything alive. Presets travel as strings (nothing to copy) and external http URLs are
 * the publisher's explicit choice, but a {@code /api/proxy/files/avatar/{id}} URL points
 * at a file in the PUBLISHER's storage - so the clone re-uploads the bytes under the
 * acquirer and rewrites the URL, exactly like {@code DataSourceFileCloneService} does for
 * datasource files.
 *
 * <p>Transport: the PUBLIC avatar serve ({@code GET /api/files/avatar/{id}}) + the
 * regular generic upload ({@code POST /api/files/generic-upload}, category {@code avatar})
 * on the storage base URL. Both endpoints are mounted by cloud storage-service AND the CE
 * monolith with identical shapes, which is what keeps this service deployment-agnostic -
 * the internal {@code /api/internal/storage} surface is cloud-only.
 *
 * <p>Best-effort: any failure returns {@code null} (the clone falls back to the default
 * preset) and never aborts the acquisition.
 */
@Service
public class AvatarFileCloneService {

    private static final Logger logger = LoggerFactory.getLogger(AvatarFileCloneService.class);

    private final RestTemplate restTemplate;
    private final String storageBaseUrl;

    // Two constructors: mark the DI one @Autowired so Spring doesn't require a no-arg
    // constructor (the package-private one below is for tests). Without this, Spring cannot
    // pick a constructor and fails context startup with "No default constructor found".
    @Autowired
    public AvatarFileCloneService(@Value("${services.storage-url:http://localhost:8082}") String storageBaseUrl) {
        this(new RestTemplate(), storageBaseUrl);
    }

    AvatarFileCloneService(RestTemplate restTemplate, String storageBaseUrl) {
        this.restTemplate = restTemplate;
        this.storageBaseUrl = storageBaseUrl;
    }

    /**
     * @param avatarUrl the SNAPSHOT's avatar value (already filtered by {@link AvatarUrlPolicy})
     * @return the value to store on the cloned agent: presets/http pass through unchanged;
     *         a public-avatar URL becomes the acquirer's own copy; anything uncopyable → null
     */
    public String cloneForTenant(String avatarUrl, String acquirerTenantId, String acquirerOrganizationId) {
        String publishable = AvatarUrlPolicy.publishable(avatarUrl);
        if (publishable == null || !publishable.startsWith(AvatarUrlPolicy.PUBLIC_AVATAR_PATH)) {
            return publishable;
        }
        UUID sourceId = parseId(publishable);
        if (sourceId == null || acquirerTenantId == null || acquirerTenantId.isBlank()) {
            return null;
        }
        try {
            ResponseEntity<byte[]> source = restTemplate.getForEntity(
                    storageBaseUrl + "/api/files/avatar/" + sourceId, byte[].class);
            byte[] bytes = source.getBody();
            if (!source.getStatusCode().is2xxSuccessful() || bytes == null || bytes.length == 0) {
                logger.warn("Avatar clone: source {} not servable ({})", sourceId, source.getStatusCode());
                return null;
            }
            MediaType contentType = source.getHeaders().getContentType();
            String mimeType = contentType != null ? contentType.toString() : "image/svg+xml";
            String fileName = "avatar" + extensionFor(mimeType);

            String newId = uploadForTenant(bytes, fileName, mimeType, acquirerTenantId, acquirerOrganizationId);
            if (newId == null) {
                return null;
            }
            logger.info("Avatar clone: {} -> {} (tenant {})", sourceId, newId, acquirerTenantId);
            return AvatarUrlPolicy.PUBLIC_AVATAR_PATH + newId;
        } catch (Exception e) {
            logger.warn("Avatar clone failed for {} (tenant {}): {} - falling back to preset",
                    sourceId, acquirerTenantId, e.getMessage());
            return null;
        }
    }

    private String uploadForTenant(byte[] bytes, String fileName, String mimeType,
                                   String tenantId, String organizationId) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(mimeType));
        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }, fileHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);
        body.add("category", "avatar");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-User-ID", tenantId);
        if (organizationId != null && !organizationId.isBlank()) {
            headers.set("X-Organization-ID", organizationId);
        }

        ResponseEntity<Map> response = restTemplate.exchange(
                storageBaseUrl + "/api/files/generic-upload",
                HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        Object id = response.getBody() != null ? response.getBody().get("id") : null;
        return id != null ? id.toString() : null;
    }

    private static UUID parseId(String publicAvatarUrl) {
        try {
            return UUID.fromString(publicAvatarUrl.substring(AvatarUrlPolicy.PUBLIC_AVATAR_PATH.length()));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static String extensionFor(String mimeType) {
        return switch (mimeType.toLowerCase()) {
            case "image/svg+xml" -> ".svg";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpeg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }
}
