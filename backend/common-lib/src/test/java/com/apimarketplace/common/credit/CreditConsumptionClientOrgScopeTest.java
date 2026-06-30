package com.apimarketplace.common.credit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreditConsumptionClient org scope propagation")
class CreditConsumptionClientOrgScopeTest {

    private static final String GATEWAY_SECRET = "test-gateway-secret";

    @Mock
    private RestTemplate restTemplate;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("consumeCredits forwards active workspace headers to auth-service")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void consumeCreditsForwardsActiveWorkspaceHeaders() {
        CreditConsumptionClient client = clientWithMockRestTemplate();
        bindWorkspace("org-acme", "MEMBER");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(Map.of("success", true), HttpStatus.OK));

        client.consumeCredits("99", "CHAT_CONVERSATION", "conv-1",
                "deepseek", "deepseek-chat", 100, 50);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-acme");
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-Role")).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("consumeCredits signs gateway HMAC with user and workspace headers")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void consumeCreditsSignsGatewayHmacWithUserAndWorkspaceHeaders() {
        CreditConsumptionClient client = clientWithMockRestTemplateAndGatewaySecret();
        bindWorkspace("org-acme", "MEMBER");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(Map.of("success", true), HttpStatus.OK));

        client.consumeCredits("99", "CHAT_CONVERSATION", "conv-1",
                "deepseek", "deepseek-chat", 100, 50);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));

        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        String timestamp = headers.getFirst("X-Gateway-Timestamp");
        assertThat(headers.getFirst("X-Provider-ID")).isEqualTo("internal-credit-client");
        assertThat(timestamp).isNotBlank();
        assertThat(headers.getFirst("X-Gateway-Secret"))
                .isEqualTo(signature("internal-credit-client", "99", "org-acme", timestamp));
    }

    @Test
    @DisplayName("scopeCommit signs gateway HMAC without user header")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void scopeCommitSignsGatewayHmacWithoutUserHeader() {
        CreditConsumptionClient client = clientWithMockRestTemplateAndGatewaySecret();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(Map.of("outcome", "COMMITTED"), HttpStatus.OK));

        String outcome = client.scopeCommit("reservation-1", BigDecimal.ONE, "openai", "gpt-4.1");

        assertThat(outcome).isEqualTo("COMMITTED");
        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));

        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        String timestamp = headers.getFirst("X-Gateway-Timestamp");
        assertThat(headers.getFirst("X-User-ID")).isNull();
        assertThat(headers.getFirst("X-Provider-ID")).isEqualTo("internal-credit-client");
        assertThat(headers.getFirst("X-Gateway-Secret"))
                .isEqualTo(signature("internal-credit-client", null, null, timestamp));
    }

    @Test
    @DisplayName("checkChatBudget cache is isolated by active workspace")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void checkChatBudgetCacheIsIsolatedByActiveWorkspace() {
        CreditConsumptionClient client = clientWithMockRestTemplate();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(Map.of("allowed", true), HttpStatus.OK))
                .thenThrow(new ResourceAccessException("auth-service down"));

        bindWorkspace("org-acme", "MEMBER");
        boolean acmeAllowed = client.checkChatBudget("99", "deepseek", "deepseek-chat", 100, 50);

        bindWorkspace("org-personal", "OWNER");
        boolean personalAllowed = client.checkChatBudget("99", "deepseek", "deepseek-chat", 100, 50);

        assertThat(acmeAllowed).isTrue();
        assertThat(personalAllowed).isFalse();
    }

    private CreditConsumptionClient clientWithMockRestTemplate() {
        CreditConsumptionClient client = new CreditConsumptionClient("http://auth:8083", true);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
        return client;
    }

    private CreditConsumptionClient clientWithMockRestTemplateAndGatewaySecret() {
        CreditConsumptionClient client = new CreditConsumptionClient("http://auth:8083", true, GATEWAY_SECRET);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
        return client;
    }

    private void bindWorkspace(String orgId, String orgRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Organization-ID", orgId);
        request.addHeader("X-Organization-Role", orgRole);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private String signature(String providerId, String userId, String organizationId, String timestamp) {
        try {
            String safeUser = userId != null ? userId : "";
            String safeOrg = organizationId != null ? organizationId : "";
            String data = providerId + "|" + safeUser + "|" + safeOrg + "|" + timestamp;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(GATEWAY_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return "gw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
