package com.apimarketplace.publication.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the outbound contract of {@link OrchestratorInternalClient#deleteAcquiredWorkflow}:
 * the request MUST carry the acquirer's {@code X-Organization-ID}. The orchestrator's
 * canonical delete strict-scope-guards against the row's organization_id (NOT NULL
 * post-V263) and reads the caller org from this header - a header-less call is
 * silently refused for EVERY row, which is exactly how the original compensation
 * left orphan clones behind. This test fails on the header-less code.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrchestratorInternalClient.deleteAcquiredWorkflow - outbound org scope")
class OrchestratorInternalClientDeleteAcquiredTest {

    @Mock private RestTemplate restTemplate;
    @Captor private ArgumentCaptor<HttpEntity<Void>> entityCaptor;
    @Captor private ArgumentCaptor<String> urlCaptor;

    private OrchestratorInternalClient client;

    private static final UUID WORKFLOW_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID PUBLICATION_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @BeforeEach
    void setUp() {
        client = new OrchestratorInternalClient(restTemplate, "http://orchestrator:8099");
    }

    @Test
    @DisplayName("sends X-User-ID AND X-Organization-ID plus the pubId+tenant guard params (header-less call = every delete refused)")
    void sendsOrgScopedDeleteRequest() {
        boolean deleted = client.deleteAcquiredWorkflow(WORKFLOW_ID, PUBLICATION_ID, "buyer-7", "org-buyer");

        assertThat(deleted).isTrue();
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.DELETE), entityCaptor.capture(), eq(Void.class));
        assertThat(urlCaptor.getValue())
                .contains("/workflows/" + WORKFLOW_ID + "/acquired")
                .contains("pubId=" + PUBLICATION_ID)
                .contains("tenantId=buyer-7");
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("buyer-7");
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-buyer");
    }

    @Test
    @DisplayName("returns false instead of throwing when the orchestrator refuses (compensation is best-effort)")
    void returnsFalseOnRefusal() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.DELETE), any(), eq(Void.class)))
                .thenThrow(new RestClientException("403 Forbidden"));

        boolean deleted = client.deleteAcquiredWorkflow(WORKFLOW_ID, PUBLICATION_ID, "buyer-7", "org-buyer");

        assertThat(deleted).isFalse();
    }
}
