package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.storage.StorageUsageDto;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Lightweight HTTP client for conversation-service storage usage.
 * No dedicated conversation-client module exists, so this is a simple inline client.
 */
@Component
public class ConversationStorageClient {

    private static final Logger log = LoggerFactory.getLogger(ConversationStorageClient.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;

    public ConversationStorageClient(
            // Standard `services.conversation-url` (helm-injected SERVICES_CONVERSATION_URL), with the
            // legacy `orchestrator.conversation.base-url` fallback for docker-compose / systemd. See
            // ConversationClientConfig for why the non-standard name broke on the k3s migration.
            @Value("${services.conversation-url:${orchestrator.conversation.base-url:http://localhost:8087}}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** The resolved conversation-service base URL (visible for wiring tests). */
    String getBaseUrl() {
        return baseUrl;
    }

    public StorageUsageDto getStorageUsage(String tenantId) {
        String url = baseUrl + "/api/internal/conversation/storage/usage";
        try {
            ResponseEntity<StorageUsageDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(tenantId)), StorageUsageDto.class);
            return response.getBody() != null ? response.getBody() : StorageUsageDto.zero();
        } catch (Exception e) {
            log.warn("Failed to get conversation storage usage for tenant {}: {}", tenantId, e.getMessage());
            return StorageUsageDto.zero();
        }
    }

    private HttpHeaders buildHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-User-ID", tenantId);
        OrgContextHeaderForwarder.forward(headers);
        return headers;
    }
}
