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
 * The wire joint for the node's provider-retry budget: the ONE line where the orchestrator's
 * internal marker becomes a field name the catalog reads.
 *
 * <p><b>Why this needs its own test.</b> The name changes here, from
 * {@code __providerRetryMaxWaitSec__} to {@code providerRetryMaxWaitSeconds}, and both halves are
 * plain strings in two different services. Mistype or rename either and the field is simply absent
 * from the request: the catalog applies its own budget, the platform keeps re-sending underneath a
 * node that asked it not to, and the run is green. That is the same shape as the credential-selector
 * markers next door, which are pinned for the same reason.
 *
 * <p>This file pins the SENDING half by capturing the real request body. The receiving half is
 * pinned in catalog-service by
 * {@code ToolExecutionManagerProviderRetriesTest.theRequestFieldBindsFromTheWireName}, which binds
 * that literal JSON name through real Jackson. Neither module can see the other's class, so a
 * rename that touches one and not the other fails whichever one it missed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogToolsGateway - the provider-retry budget on the wire")
class CatalogToolsGatewayProviderRetryTest {

    /** What StepNode writes. */
    private static final String MARKER = "__providerRetryMaxWaitSec__";

    /** What ToolExecutionRequest reads. */
    private static final String WIRE_FIELD = "providerRetryMaxWaitSeconds";

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

        gateway.executeTool(new ToolRef("instagram/publish", 1), Map.of("caption", "hi"),
                "tenant-1", markers);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), any(Class.class));
        return captor.getValue().getBody();
    }

    private static Map<String, Object> markers(Object... kv) {
        Map<String, Object> markers = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            markers.put((String) kv[i], kv[i + 1]);
        }
        return markers;
    }

    @Test
    @DisplayName("a zero budget reaches the catalog under the name it reads")
    void zeroBudgetIsForwarded() {
        // The case the whole feature exists for: the node paces itself, so the platform must not
        // re-send underneath it. A dropped field here restores the very multiplication it prevents.
        Map<String, Object> payload = forwardedPayload(markers(MARKER, 0));

        assertThat(payload).containsEntry(WIRE_FIELD, 0);
    }

    @Test
    @DisplayName("a positive budget is forwarded as an int, not as the marker's boxed type")
    void positiveBudgetIsForwardedAsInt() {
        Map<String, Object> payload = forwardedPayload(markers(MARKER, 45));

        assertThat(payload).containsEntry(WIRE_FIELD, 45);
        assertThat(payload.get(WIRE_FIELD)).isInstanceOf(Integer.class);
    }

    @Test
    @DisplayName("no marker means no field, so the catalog applies its own budget")
    void absentMarkerSendsNothing() {
        // Absent and 0 are different instructions. Sending 0 whenever the node said nothing would
        // disable the platform retry for every workflow ever written.
        Map<String, Object> payload = forwardedPayload(markers("__credentialSource__", "user"));

        assertThat(payload).doesNotContainKey(WIRE_FIELD);
    }

    @Test
    @DisplayName("the internal marker itself never reaches the catalog")
    void theMarkerNameIsNotForwarded() {
        Map<String, Object> payload = forwardedPayload(markers(MARKER, 0));

        assertThat(payload)
                .as("the double-underscore names are the orchestrator's own convention; the "
                        + "catalog's request DTO has no such field and would ignore it")
                .doesNotContainKey(MARKER);
    }

    @Test
    @DisplayName("a non-numeric marker is ignored rather than forwarded as junk")
    void nonNumericMarkerIsIgnored() {
        Map<String, Object> payload = forwardedPayload(markers(MARKER, "soon"));

        assertThat(payload).doesNotContainKey(WIRE_FIELD);
    }
}
