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
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The platform no longer retries a provider's refusal, so the orchestrator no longer tells the
 * catalog how long to wait for one. The field used to be {@code providerRetryMaxWaitSeconds}; a
 * request still carrying it would describe a retry that does not exist.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogToolsGateway - no provider-retry budget on the wire")
class CatalogToolsGatewayNoProviderRetryTest {

    @Mock private RestTemplate restTemplate;
    @Mock private TypeCastingService typeCastingService;
    @Mock private CrudToolExecutor crudToolExecutor;

    private CatalogToolsGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new CatalogToolsGateway(
                restTemplate, "http://localhost:8081", typeCastingService, crudToolExecutor);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> forwardedPayload(Map<String, Object> markers) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(null));

        gateway.executeTool(new ToolRef("openai-admin-cost/get-costs", 1), Map.of("limit", "31"),
                "tenant-1", markers);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), any(Class.class));
        return captor.getValue().getBody();
    }

    @Test
    @DisplayName("the old internal marker, if anything still sets it, is not turned into a catalog field")
    void theRemovedBudgetIsNeverSent() {
        Map<String, Object> markers = new HashMap<>();
        markers.put("__providerRetryMaxWaitSec__", 0);

        Map<String, Object> payload = forwardedPayload(markers);

        assertThat(payload)
                .doesNotContainKey("providerRetryMaxWaitSeconds")
                .doesNotContainKey("__providerRetryMaxWaitSec__");
    }
}
