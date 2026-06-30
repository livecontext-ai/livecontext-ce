package com.apimarketplace.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the cache-aware billing wire format (2026-06-11): the
 * consume body must carry the cache/reasoning counters so auth-service can bill
 * cache reads/writes at the provider's true relative price. Pre-fix, only
 * prompt/completion were posted and claude-code cache reads were billed at full
 * input rate.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditConsumptionClient cache token wire fields")
class CreditConsumptionClientCacheTokensTest {

    @Mock
    private RestTemplate restTemplate;

    private CreditConsumptionClient clientWithMockRestTemplate() {
        CreditConsumptionClient client = new CreditConsumptionClient("http://auth:8083", true);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
        return client;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> capturedBody() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
        return entityCaptor.getValue().getBody();
    }

    @Test
    @DisplayName("consumeCredits posts cache write/read, cached and reasoning counters when provided")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void consumeCreditsPostsCacheCounters() {
        CreditConsumptionClient client = clientWithMockRestTemplate();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(Map.of("success", true), HttpStatus.OK));

        client.consumeCredits("42", "CHAT_CONVERSATION", "conv-1",
                "claude-code", "claude-opus-4-6", 131195, 677,
                new LlmCacheTokens(45390, 85800, 0, 0));

        Map<String, Object> body = capturedBody();
        assertThat(body)
                .containsEntry("promptTokens", 131195)
                .containsEntry("completionTokens", 677)
                .containsEntry("cacheCreationTokens", 45390)
                .containsEntry("cacheReadTokens", 85800)
                .containsEntry("cachedTokens", 0)
                .containsEntry("reasoningTokens", 0);
    }

    @Test
    @DisplayName("consumeCredits omits cache fields entirely when the breakdown is null (legacy callers)")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void consumeCreditsOmitsCacheFieldsWhenNull() {
        CreditConsumptionClient client = clientWithMockRestTemplate();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(Map.of("success", true), HttpStatus.OK));

        client.consumeCredits("42", "AGENT_EXECUTION", "exec-1",
                "openai", "gpt-4o", 100, 50);

        Map<String, Object> body = capturedBody();
        assertThat(body)
                .containsEntry("promptTokens", 100)
                .containsEntry("completionTokens", 50)
                .doesNotContainKeys("cacheCreationTokens", "cacheReadTokens", "cachedTokens", "reasoningTokens");
    }

    @Test
    @DisplayName("consumeCredits omits cache fields when the breakdown has no positive counter")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void consumeCreditsOmitsCacheFieldsWhenAllZero() {
        CreditConsumptionClient client = clientWithMockRestTemplate();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(Map.of("success", true), HttpStatus.OK));

        client.consumeCredits("42", "AGENT_EXECUTION", "exec-2",
                "openai", "gpt-4o", 100, 50,
                new LlmCacheTokens(0, 0, 0, 0));

        Map<String, Object> body = capturedBody();
        assertThat(body)
                .doesNotContainKeys("cacheCreationTokens", "cacheReadTokens", "cachedTokens", "reasoningTokens");
    }
}
