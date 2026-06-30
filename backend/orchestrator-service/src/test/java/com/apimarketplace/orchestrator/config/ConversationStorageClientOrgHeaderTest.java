package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression test: ConversationStorageClient.buildHeaders() must forward
 * X-Organization-ID so conversation-service returns org-scoped storage usage.
 */
@DisplayName("ConversationStorageClient - org header forwarding")
class ConversationStorageClientOrgHeaderTest {

    @Test
    @DisplayName("getStorageUsage forwards X-Organization-ID from request context")
    void forwardsOrgHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "tenant-1");
        request.addHeader("X-Organization-ID", "org-abc");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        try {
            // Use a spy to capture the outbound HttpEntity
            RestTemplate mockRt = mock(RestTemplate.class);
            when(mockRt.exchange(anyString(), eq(HttpMethod.GET), any(), eq(StorageUsageDto.class)))
                    .thenReturn(ResponseEntity.ok(StorageUsageDto.zero()));

            ConversationStorageClient client = new ConversationStorageClient("http://localhost:8087");
            // Replace the internal restTemplate via reflection (it's final new RestTemplate())
            var field = ConversationStorageClient.class.getDeclaredField("restTemplate");
            field.setAccessible(true);
            field.set(client, mockRt);

            // Act
            client.getStorageUsage("tenant-1");

            // Assert
            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<Void>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(mockRt).exchange(anyString(), eq(HttpMethod.GET), captor.capture(), eq(StorageUsageDto.class));

            assertThat(captor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("tenant-1");
            assertThat(captor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .as("X-Organization-ID must be forwarded")
                    .isEqualTo("org-abc");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
