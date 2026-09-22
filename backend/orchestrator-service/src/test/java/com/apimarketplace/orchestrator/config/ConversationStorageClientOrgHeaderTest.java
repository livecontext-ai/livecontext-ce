package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.storage.StorageUsageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Two contracts of {@code ConversationStorageClient}, neither observable anywhere else.
 *
 * <p>The header half: {@code buildHeaders()} must forward X-Organization-ID so
 * conversation-service returns org-scoped storage usage.
 *
 * <p>The return half: the method answers {@code Optional} precisely so "conversation
 * -service could not be measured" is distinguishable from "this tenant measured zero".
 * Its single caller, {@code StorageReconciliationService.reconcileConversations}, writes
 * the answer through {@code setUsage}, an ABSOLUTE set, so a failure reported as
 * {@code zero()} does not degrade the figure, it erases it. Nothing else exercises the
 * catch or the null-body branch, so a revert to {@code Optional.of(zero())} would be
 * invisible without the cases below.
 */
@DisplayName("ConversationStorageClient - org header and return contract")
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

    /** Swaps in a stubbed RestTemplate the way the header test above does. */
    private static ConversationStorageClient clientWith(RestTemplate restTemplate) throws Exception {
        ConversationStorageClient client = new ConversationStorageClient("http://localhost:8087");
        var field = ConversationStorageClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        field.set(client, restTemplate);
        return client;
    }

    @Test
    @DisplayName("a 200 with a body is a measurement")
    void bodyIsAMeasurement() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(), eq(StorageUsageDto.class)))
                .thenReturn(ResponseEntity.ok(new StorageUsageDto(4096L, 7)));

        assertThat(clientWith(rt).getStorageUsage("tenant-1"))
                .contains(new StorageUsageDto(4096L, 7));
    }

    @Test
    @DisplayName("a transport failure is NOT a measurement of zero")
    void transportFailureIsNotAZero() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(), eq(StorageUsageDto.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        // Returning zero() here is the exact defect: the reconciler would write it
        // absolutely and erase the tenant's CONVERSATIONS figure for the night.
        assertThat(clientWith(rt).getStorageUsage("tenant-1")).isEmpty();
    }

    @Test
    @DisplayName("a 200 with no body is NOT a measurement of zero either")
    void nullBodyIsNotAZero() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(), eq(StorageUsageDto.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThat(clientWith(rt).getStorageUsage("tenant-1")).isEmpty();
    }
}
