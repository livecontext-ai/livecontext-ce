package com.apimarketplace.agent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A key is checked against the vendor BEFORE it is saved. Only a vendor rejection (401/403)
 * makes a key invalid; a vendor that cannot be asked never blocks saving.
 */
@DisplayName("AbstractLLMProvider.validateApiKey")
class AbstractLLMProviderValidateApiKeyTest {

    private static DeepSeekProvider providerWith(RestTemplate rest) {
        DeepSeekProvider provider = new DeepSeekProvider();
        ReflectionTestUtils.setField(provider, "apiUrl", "https://api.deepseek.com/v1/chat/completions");
        ReflectionTestUtils.setField(provider, "discoveryRestTemplate", rest);
        return provider;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RestTemplate restAnswering(ResponseEntity<Map> response) {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class))).thenReturn(response);
        return rest;
    }

    private static RestTemplate restThrowing(RuntimeException e) {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class))).thenThrow(e);
        return rest;
    }

    @Test
    @DisplayName("a 2xx from the vendor listing accepts the key, sent as the ONLY credential (never the configured one)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void twoHundredAccepts() {
        RestTemplate rest = restAnswering((ResponseEntity) ResponseEntity.ok(Map.of("data", java.util.List.of())));
        DeepSeekProvider provider = providerWith(rest);
        ReflectionTestUtils.setField(provider, "apiKey", "sk-configured");

        LLMProvider.KeyCheck check = provider.validateApiKey("  sk-candidate \n");

        assertThat(check.valid()).isTrue();
        assertThat(check.verified()).isTrue();
        assertThat(check.error()).isNull();
        org.mockito.ArgumentCaptor<HttpEntity<?>> sent = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).exchange(eq("https://api.deepseek.com/v1/models"), eq(HttpMethod.GET), sent.capture(), eq(Map.class));
        assertThat(sent.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-candidate");
    }

    @Test
    @DisplayName("a 401 or 403 is a rejection: the key is wrong, the user must not save it")
    void unauthorizedRejects() {
        DeepSeekProvider provider = providerWith(restThrowing(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8)));

        LLMProvider.KeyCheck check = provider.validateApiKey("sk-bad");

        assertThat(check.valid()).isFalse();
        assertThat(check.verified()).isTrue();
        assertThat(check.error()).contains("401");
    }

    @Test
    @DisplayName("a vendor that cannot be asked (network, 5xx, no listing endpoint) never blocks saving: valid but unverified")
    void unreachableIsUnverifiedNotInvalid() {
        assertThat(providerWith(restThrowing(new ResourceAccessException("connect timed out")))
                .validateApiKey("sk-x")).satisfies(c -> {
                    assertThat(c.valid()).isTrue();
                    assertThat(c.verified()).isFalse();
                    assertThat(c.error()).contains("could not reach");
                });
        assertThat(providerWith(restThrowing(HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8)))
                .validateApiKey("sk-x")).satisfies(c -> {
                    assertThat(c.valid()).isTrue();
                    assertThat(c.verified()).isFalse();
                    assertThat(c.error()).contains("502");
                });

        DeepSeekProvider noEndpoint = providerWith(mock(RestTemplate.class));
        ReflectionTestUtils.setField(noEndpoint, "apiUrl", null);
        LLMProvider.KeyCheck check = noEndpoint.validateApiKey("sk-x");
        assertThat(check.valid()).isTrue();
        assertThat(check.verified()).isFalse();
    }

    @Test
    @DisplayName("an empty key is rejected without asking anyone")
    void blankKeyIsRejectedLocally() {
        RestTemplate rest = mock(RestTemplate.class);

        LLMProvider.KeyCheck check = providerWith(rest).validateApiKey("   ");

        assertThat(check.valid()).isFalse();
        verify(rest, never()).exchange(anyString(), any(HttpMethod.class), any(), eq(Map.class));
    }

    @Test
    @DisplayName("vendors with their own auth header send the candidate key under it: Anthropic x-api-key, Google x-goog-api-key")
    void vendorHeadersCarryTheCandidateKey() {
        assertThat(new ClaudeProvider().discoveryHeaders("sk-ant-candidate").getFirst("x-api-key")).isEqualTo("sk-ant-candidate");
        assertThat(new GeminiProvider().discoveryHeaders("AIza-candidate").getFirst("x-goog-api-key")).isEqualTo("AIza-candidate");
        assertThat(new DeepSeekProvider().discoveryHeaders("sk-candidate").getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-candidate");
    }
}
