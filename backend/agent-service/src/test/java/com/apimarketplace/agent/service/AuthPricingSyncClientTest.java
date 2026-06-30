package com.apimarketplace.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Unit contract for {@link AuthPricingSyncClient}:
 * prices are POSTed to auth-service as raw <b>USD per 1M tokens</b>,
 * matching the {@code auth.model_pricing.input_rate/output_rate} V80 convention.
 *
 * <p>Prior to 2026-04-21 the original inline implementation in
 * {@code ModelCatalogService} divided by 1000 before sending, producing a
 * value 1000× too low. The bug never hit prod (diagnostic confirmed 0/50
 * rows drifted) but these tests pin the raw-forward behavior so a future
 * refactor cannot silently re-introduce a unit conversion.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthPricingSyncClient - USD/1M wire contract")
class AuthPricingSyncClientTest {

    private static final String AUTH_URL = "http://localhost:8083";
    private static final String SYNC_PATH = AUTH_URL + "/api/internal/auth/model-pricing/sync";

    @Mock private RestTemplate restTemplate;

    private AuthPricingSyncClient client;

    @BeforeEach
    void setUp() {
        client = new AuthPricingSyncClient(restTemplate, AUTH_URL);
    }

    @Test
    @DisplayName("sync forwards USD/1M prices unchanged to /model-pricing/sync")
    @SuppressWarnings("unchecked")
    void forwardsRawUsdPer1m() {
        when(restTemplate.postForEntity(eq(SYNC_PATH), any(), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        client.sync("openai", "gpt-5", new BigDecimal("1.25"), new BigDecimal("10.00"), null);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForEntity(eq(SYNC_PATH), body.capture(), eq(Void.class));

        assertThat(body.getValue()).containsEntry("provider",   "openai");
        assertThat(body.getValue()).containsEntry("model",      "gpt-5");
        assertThat(body.getValue()).containsEntry("inputRate",  new BigDecimal("1.25"));
        assertThat(body.getValue()).containsEntry("outputRate", new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("Null prices coerced to BigDecimal.ZERO in payload")
    @SuppressWarnings("unchecked")
    void nullPricesCoercedToZero() {
        when(restTemplate.postForEntity(eq(SYNC_PATH), any(), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        client.sync("anthropic", "claude-opus-4-6", new BigDecimal("5.00"), null, null);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForEntity(any(String.class), body.capture(), eq(Void.class));

        assertThat(body.getValue()).containsEntry("inputRate",  new BigDecimal("5.00"));
        assertThat(body.getValue()).containsEntry("outputRate", BigDecimal.ZERO);
    }

    @Test
    @DisplayName("RestTemplate failure is swallowed (fire-and-forget)")
    void restFailureSwallowed() {
        when(restTemplate.postForEntity(any(String.class), any(), eq(Void.class)))
                .thenThrow(new RuntimeException("auth-service down"));

        assertThatCode(() -> client.sync("openai", "gpt-5.4",
                new BigDecimal("2.50"), new BigDecimal("15.00"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Bridge providers are synced with providerKind propagated - V130 bills bridges at cloud rates")
    void bridgeProvidersAreSynced() {
        // Post-V130 contract (see CreditService.consumeForChat Javadoc): bridges
        // debit credits at the underlying cloud model's list price, so the sync
        // client must propagate the row verbatim. The providerKind field is
        // threaded through so auth.model_pricing.provider_kind stays 'bridge'
        // for reporting and admin-UI filtering.
        for (String bridge : new String[] {"claude-code", "codex", "gemini-cli", "mistral-vibe"}) {
            client.sync(bridge, "whatever-model",
                    new BigDecimal("3.00"), new BigDecimal("15.00"), "bridge");
        }

        verify(restTemplate, org.mockito.Mockito.times(4))
                .postForEntity(any(String.class), any(), eq(Void.class));
    }

    @Test
    @DisplayName("providerKind is included in request body when supplied, omitted when null/blank")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void providerKindInBody() {
        org.mockito.ArgumentCaptor<java.util.Map> captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);

        client.sync("claude-code", "claude-opus-4-10",
                new BigDecimal("5.00"), new BigDecimal("25.00"), "bridge");
        client.sync("openai", "gpt-5.4",
                new BigDecimal("2.50"), new BigDecimal("15.00"), null);

        verify(restTemplate, org.mockito.Mockito.times(2))
                .postForEntity(any(String.class), captor.capture(), eq(Void.class));

        java.util.List<java.util.Map> sent = captor.getAllValues();
        org.assertj.core.api.Assertions.assertThat((java.util.Map<String, Object>) sent.get(0))
                .containsEntry("providerKind", "bridge");
        org.assertj.core.api.Assertions.assertThat((java.util.Map<String, Object>) sent.get(1))
                .doesNotContainKey("providerKind");
    }
}
