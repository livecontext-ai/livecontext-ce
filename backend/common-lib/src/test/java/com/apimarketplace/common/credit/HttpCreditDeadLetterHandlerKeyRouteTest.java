package com.apimarketplace.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * The HTTP dead-letter handler forwards the key route to auth-service, where the replay
 * bills the turn under it. Without the field an own-key turn that failed to debit would be
 * replayed at the platform token rate: the user pays their provider AND full credits.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpCreditDeadLetterHandler - key route on the wire")
class HttpCreditDeadLetterHandlerKeyRouteTest {

    @Mock
    private RestTemplate restTemplate;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> postedBody() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://auth:8083/api/internal/auth/credit/dead-letter"),
                captor.capture(), eq(Void.class));
        return captor.getValue().getBody();
    }

    @Test
    @DisplayName("forwards keyRoute in the body when given")
    void forwardsTheKeyRoute() {
        HttpCreditDeadLetterHandler handler = new HttpCreditDeadLetterHandler(restTemplate, "http://auth:8083");

        handler.persistFailedConsumption("42", "AGENT_EXECUTION", "exec-1", "openai", "gpt-4",
                100, 50, "Connection refused", "org-1", "OWN_KEY");

        Map<String, Object> body = postedBody();
        assertThat(body).containsEntry("keyRoute", "OWN_KEY");
        assertThat(body).containsEntry("tenantId", "42");
        assertThat(body).containsEntry("organizationId", "org-1");
    }

    @Test
    @DisplayName("the route-less overload posts the pre-V506 body: no keyRoute field at all")
    void routeLessOverloadOmitsTheField() {
        HttpCreditDeadLetterHandler handler = new HttpCreditDeadLetterHandler(restTemplate, "http://auth:8083");

        handler.persistFailedConsumption("42", "AGENT_EXECUTION", "exec-1", "openai", "gpt-4",
                100, 50, "Connection refused", "org-1");

        assertThat(postedBody()).doesNotContainKey("keyRoute");
    }

    @Test
    @DisplayName("a blank route is not forwarded either")
    void blankRouteIsOmitted() {
        HttpCreditDeadLetterHandler handler = new HttpCreditDeadLetterHandler(restTemplate, "http://auth:8083");

        handler.persistFailedConsumption("42", "AGENT_EXECUTION", "exec-1", "openai", "gpt-4",
                100, 50, "Connection refused", "org-1", " ");

        assertThat(postedBody()).doesNotContainKey("keyRoute");
        verify(restTemplate).postForEntity(any(String.class), any(HttpEntity.class), eq(Void.class));
    }
}
