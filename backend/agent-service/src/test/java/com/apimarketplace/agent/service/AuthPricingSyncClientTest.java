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
import static org.mockito.Mockito.never;
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
    @DisplayName("Skips the sync (no POST) for a negative sentinel rate (openrouter/auto's -1000000)")
    void skipsNegativeRate() {
        client.sync("openrouter", "openrouter/auto",
                new BigDecimal("-1000000"), new BigDecimal("-1000000"), "byok");
        // Pre-fix this POSTed -1000000 -> auth model_pricing NUMERIC(10,6) numeric overflow (500).
        // Post-fix it is guarded out so the billing mirror never sees a non-billable rate.
        verify(restTemplate, never()).postForEntity(any(String.class), any(), eq(Map.class));
    }

    @Test
    @DisplayName("Skips the sync (no POST) for a rate above the NUMERIC(10,6) ceiling")
    void skipsRateAboveCeiling() {
        client.sync("vendor", "huge", new BigDecimal("10000.0"), new BigDecimal("1.0"), "byok");
        verify(restTemplate, never()).postForEntity(any(String.class), any(), eq(Map.class));
    }

    @Test
    @DisplayName("Still syncs an in-range rate and a zero rate (0 is billable/in range)")
    void syncsInRangeRates() {
        when(restTemplate.postForEntity(eq(SYNC_PATH), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));
        client.sync("openai", "gpt-5", new BigDecimal("1.25"), new BigDecimal("10.00"), "byok");
        client.sync("vendor", "free-in", BigDecimal.ZERO, new BigDecimal("2.0"), "byok");
        verify(restTemplate, org.mockito.Mockito.times(2)).postForEntity(eq(SYNC_PATH), any(), eq(Map.class));
    }

    @Test
    @DisplayName("sync forwards USD/1M prices unchanged to /model-pricing/sync")
    @SuppressWarnings("unchecked")
    void forwardsRawUsdPer1m() {
        when(restTemplate.postForEntity(eq(SYNC_PATH), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

        client.sync("openai", "gpt-5", new BigDecimal("1.25"), new BigDecimal("10.00"), null);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForEntity(eq(SYNC_PATH), body.capture(), eq(Map.class));

        assertThat(body.getValue()).containsEntry("provider",   "openai");
        assertThat(body.getValue()).containsEntry("model",      "gpt-5");
        assertThat(body.getValue()).containsEntry("inputRate",  new BigDecimal("1.25"));
        assertThat(body.getValue()).containsEntry("outputRate", new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("Null prices coerced to BigDecimal.ZERO in payload")
    @SuppressWarnings("unchecked")
    void nullPricesCoercedToZero() {
        when(restTemplate.postForEntity(eq(SYNC_PATH), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

        client.sync("anthropic", "claude-opus-4-6", new BigDecimal("5.00"), null, null);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForEntity(any(String.class), body.capture(), eq(Map.class));

        assertThat(body.getValue()).containsEntry("inputRate",  new BigDecimal("5.00"));
        assertThat(body.getValue()).containsEntry("outputRate", BigDecimal.ZERO);
    }

    @Test
    @DisplayName("RestTemplate failure is swallowed (fire-and-forget)")
    void restFailureSwallowed() {
        when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
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
                .postForEntity(any(String.class), any(), eq(Map.class));
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
                .postForEntity(any(String.class), captor.capture(), eq(Map.class));

        java.util.List<java.util.Map> sent = captor.getAllValues();
        org.assertj.core.api.Assertions.assertThat((java.util.Map<String, Object>) sent.get(0))
                .containsEntry("providerKind", "bridge");
        org.assertj.core.api.Assertions.assertThat((java.util.Map<String, Object>) sent.get(1))
                .doesNotContainKey("providerKind");
    }
    @Test
    @DisplayName("V493: a mirror that ECHOES the flag back counts as mirrored")
    void echoedFlagCountsAsMirrored() {
        when(restTemplate.postForEntity(eq(SYNC_PATH), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("freeTier", true)));

        assertThat(client.sync("anthropic", "haiku",
                new BigDecimal("1.00"), new BigDecimal("5.00"), "byok", null, null, true)).isTrue();
    }

    @Test
    @DisplayName("V493: a 200 with NO echo is not proof - an old pod ignores the key and still answers OK")
    void missingEchoIsNotMirrored() {
        // The realistic failure, and the one a status code cannot catch: during a rolling
        // deploy an auth-service pod that predates the free_tier column drops the key and
        // returns 200. Believing it would commit a catalog row saying "open" while the
        // gate keeps refusing every free turn, with nothing anywhere to show for it.
        when(restTemplate.postForEntity(eq(SYNC_PATH), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

        assertThat(client.sync("anthropic", "haiku",
                new BigDecimal("1.00"), new BigDecimal("5.00"), "byok", null, null, true)).isFalse();
    }

    @Test
    @DisplayName("V493: an echo that DISAGREES is not mirrored either")
    void disagreeingEchoIsNotMirrored() {
        when(restTemplate.postForEntity(eq(SYNC_PATH), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("freeTier", false)));

        assertThat(client.sync("anthropic", "haiku",
                new BigDecimal("1.00"), new BigDecimal("5.00"), "byok", null, null, true)).isFalse();
    }

    @Test
    @DisplayName("V493: closing a model needs the echo too, in the other direction")
    void closingChecksTheEchoAsWell() {
        when(restTemplate.postForEntity(eq(SYNC_PATH), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("freeTier", false)));

        assertThat(client.sync("anthropic", "haiku",
                new BigDecimal("1.00"), new BigDecimal("5.00"), "byok", null, null, false)).isTrue();
    }

    @Test
    @DisplayName("V493: a caller that does not move the flag is not held to an echo")
    void rateOnlySyncNeedsNoEcho() {
        // Rates are recoverable and the bundle-apply path sends no flag at all; demanding
        // an echo there would turn every ordinary price sync into a false failure.
        when(restTemplate.postForEntity(eq(SYNC_PATH), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

        assertThat(client.sync("openai", "gpt-5",
                new BigDecimal("1.25"), new BigDecimal("10.00"), "byok")).isTrue();
    }

    @Test
    @DisplayName("V491: the model's own cache prices are forwarded raw, in the same USD/1M units as input/output")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cacheRatesForwardedRaw() {
        when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);

        client.sync("anthropic", "claude-fable-5-1",
                new BigDecimal("10.00"), new BigDecimal("50.00"), "byok",
                new BigDecimal("0.25"), new BigDecimal("12.50"));

        verify(restTemplate).postForEntity(eq(SYNC_PATH), captor.capture(), eq(Map.class));
        Map<String, Object> body = (Map<String, Object>) captor.getValue();
        assertThat(body).containsEntry("cacheReadRate", new BigDecimal("0.25"));
        assertThat(body).containsEntry("cacheWriteRate", new BigDecimal("12.50"));
    }

    @Test
    @DisplayName("V491: an unknown, zero or out-of-range cache price is OMITTED, so auth keeps its family fallback instead of billing a free cache")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void unknownCacheRatesAreOmitted() {
        when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);

        client.sync("anthropic", "a", new BigDecimal("10"), new BigDecimal("50"), null, null, null);
        client.sync("anthropic", "b", new BigDecimal("10"), new BigDecimal("50"), null,
                BigDecimal.ZERO, new BigDecimal("-1"));
        client.sync("anthropic", "c", new BigDecimal("10"), new BigDecimal("50"), null,
                new BigDecimal("99999.999999"), null);

        verify(restTemplate, org.mockito.Mockito.times(3))
                .postForEntity(any(String.class), captor.capture(), eq(Map.class));
        for (Map body : captor.getAllValues()) {
            assertThat((Map<String, Object>) body).doesNotContainKey("cacheReadRate");
            assertThat((Map<String, Object>) body).doesNotContainKey("cacheWriteRate");
        }
    }

    @Test
    @DisplayName("V491: the 5-arg overload still posts, carrying no cache price - existing callers are unaffected")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void legacyOverloadStillWorks() {
        when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);

        client.sync("openai", "gpt-5.4", new BigDecimal("2.50"), new BigDecimal("15.00"), "byok");

        verify(restTemplate).postForEntity(eq(SYNC_PATH), captor.capture(), eq(Map.class));
        Map<String, Object> body = (Map<String, Object>) captor.getValue();
        assertThat(body).containsEntry("inputRate", new BigDecimal("2.50"));
        assertThat(body).doesNotContainKey("cacheReadRate");
    }
}
