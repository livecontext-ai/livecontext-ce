package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression test: CatalogToolsGateway.executeTool() must forward
 * X-Organization-ID to catalog-service so that downstream storage
 * indexing tags files with the correct org. Without this header,
 * image-generation files land in S3 but are invisible in the
 * StorageExplorer (the OrgScopedEntityListener throws on null org).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogToolsGateway - org header forwarding")
class CatalogToolsGatewayOrgHeaderTest {

    @Mock private RestTemplate restTemplate;
    @Mock private TypeCastingService typeCastingService;
    @Mock private CrudToolExecutor crudToolExecutor;

    private CatalogToolsGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new CatalogToolsGateway(
                restTemplate, "http://localhost:8081", typeCastingService, crudToolExecutor);
    }

    @Test
    @DisplayName("executeTool forwards X-Organization-ID from request context to catalog-service")
    void executeToolForwardsOrgHeader() {
        // Arrange - simulate a request with X-Organization-ID
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "tenant-42");
        request.addHeader("X-Organization-ID", "org-99");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        try {
            // Stub the catalog response - return null body to trigger the graceful
            // "null response" path, which is enough to verify header forwarding.
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(Class.class)))
                    .thenReturn(ResponseEntity.ok(null));

            // Act
            ToolRef tool = new ToolRef("openai/openai-create-image", 1);
            gateway.executeTool(tool, Map.of("prompt", "a bird"), "tenant-42", null);

            // Assert - capture the outbound request and verify org header
            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), any(Class.class));

            HttpEntity<Map<String, Object>> outbound = captor.getValue();
            assertThat(outbound.getHeaders().getFirst("X-User-ID")).isEqualTo("tenant-42");
            assertThat(outbound.getHeaders().getFirst("X-Organization-ID"))
                    .as("X-Organization-ID must be forwarded from request context")
                    .isEqualTo("org-99");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("executeTool gracefully omits X-Organization-ID when no org context exists")
    void executeToolNoOrgContextDoesNotBreak() {
        // Arrange - no request context at all (async thread)
        RequestContextHolder.resetRequestAttributes();

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(Class.class)))
                .thenReturn(ResponseEntity.ok(null));

        // Act - should not throw
        ToolRef tool = new ToolRef("openai/openai-create-image", 1);
        var result = gateway.executeTool(tool, Map.of("prompt", "a bird"), "tenant-42", null);

        // Assert - call succeeds, no X-Organization-ID header (no-op is safe)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), any(Class.class));

        HttpEntity<Map<String, Object>> outbound = captor.getValue();
        assertThat(outbound.getHeaders().getFirst("X-User-ID")).isEqualTo("tenant-42");
        assertThat(outbound.getHeaders().getFirst("X-Organization-ID")).isNull();
    }

    @Test
    @DisplayName("executeTool forwards selectedCredentialId only for user-sourced workflow calls")
    void executeToolForwardsSelectedUserCredentialId() {
        RequestContextHolder.resetRequestAttributes();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(Class.class)))
                .thenReturn(ResponseEntity.ok(null));

        ToolRef tool = new ToolRef("slack/send-message", 1);
        gateway.executeTool(tool, Map.of("text", "hello"), "tenant-42", Map.of(
                "__credentialSource__", "user",
                "__selectedCredentialId__", 55L,
                "__platformCredentialId__", 99L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), any(Class.class));

        Map<String, Object> body = captor.getValue().getBody();
        assertThat(body).containsEntry("credentialSource", "user");
        assertThat(body).containsEntry("selectedCredentialId", 55L);
        assertThat(body).doesNotContainKey("platformCredentialId");
    }
}
