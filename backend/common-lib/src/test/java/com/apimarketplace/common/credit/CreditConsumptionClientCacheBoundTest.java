package com.apimarketplace.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The verdict cache stays bounded (V494).
 *
 * <p>Every answered credit check is remembered so a later auth-service outage can be
 * ridden out instead of blocking the user, and nothing ever removes an entry: they
 * expire for READING after 30s but stay resident for the life of the pod. The keys
 * are high-cardinality by construction (the chat-budget key carries the estimated
 * token counts, which differ on nearly every turn; V494 added the model to the
 * generic key too), so on a busy pod the map grows all day. That is a slow leak with
 * no symptom until the heap is gone, which is exactly the kind of thing no test
 * catches unless one is written for it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditConsumptionClient - the verdict cache is bounded")
class CreditConsumptionClientCacheBoundTest {

    @Mock
    private RestTemplate restTemplate;

    private CreditConsumptionClient client() {
        CreditConsumptionClient client = new CreditConsumptionClient("http://auth:8083", true, null);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
        return client;
    }

    @Test
    @DisplayName("a distinct model on every call does not grow the cache without end")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void perModelKeysStayBounded() {
        CreditConsumptionClient client = client();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class), anyMap()))
                .thenReturn(new ResponseEntity(Map.of("allowed", true), HttpStatus.OK));

        // 12_000 > the 10_000 cap, so the sweep has to fire at least once. Each entry
        // is created fresh here, so none is expired: this exercises the branch where
        // every resident entry is still live and the map is dropped wholesale.
        for (int i = 0; i < 12_000; i++) {
            assertThat(client.checkCredits("42", "AGENT_EXECUTION", "anthropic", "model-" + i)).isTrue();
        }

        assertThat(client.cachedCheckCount())
                .as("an unbounded map here is a leak with no symptom until the heap is gone")
                .isLessThanOrEqualTo(10_000);
    }

    @Test
    @DisplayName("the entry written right after a sweep survives it, so the outage fallback still works")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void theLatestVerdictIsStillCachedAfterASweep() {
        // Deliberately asserted on the entry written at the EXACT moment the cache was
        // dropped (the 10 000th), not on one written long after. A cap that cleared the
        // map and then forgot to store the verdict it was called with would leave that
        // one key missing and every other assertion here still green - and the account
        // that hit the cap would be the one refused during the next outage.
        CreditConsumptionClient client = client();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class), anyMap()))
                .thenReturn(new ResponseEntity(Map.of("allowed", true), HttpStatus.OK));

        for (int i = 0; i < 10_001; i++) {
            client.checkCredits("42", "AGENT_EXECUTION", "anthropic", "model-" + i);
        }
        assertThat(client.cachedCheckCount())
                .as("the sweep has fired, so this is the post-clear state")
                .isLessThan(10_000);

        // auth-service goes down. The verdict written as the cache was dropped must
        // still be there, or capping it would have turned an outage into a refusal for
        // exactly the request that triggered the cap.
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class), anyMap()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("auth-service down"));

        assertThat(client.checkCredits("42", "AGENT_EXECUTION", "anthropic", "model-10000"))
                .as("the verdict stored on the sweeping call rides out the outage")
                .isTrue();
    }

    @Test
    @DisplayName("one caller filling the cache does NOT flush another caller's verdicts")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void oneCallerCannotFlushAnother() {
        // The model is part of the key (V494) and arrives in a request body, so an
        // authenticated user can mint distinct keys at will. If hitting the cap cleared
        // the whole map, that user would flush every other tenant's verdict and the next
        // auth-service blip would fail closed for all of them - a denial of service on
        // the outage fallback, from an ordinary account.
        CreditConsumptionClient client = client();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class), anyMap()))
                .thenReturn(new ResponseEntity(Map.of("allowed", true), HttpStatus.OK));

        // A quiet neighbour, one entry.
        assertThat(client.checkCredits("7", "AGENT_EXECUTION", "anthropic", "haiku")).isTrue();

        // The noisy caller pushes the cache past its cap on its own.
        for (int i = 0; i < 10_001; i++) {
            client.checkCredits("42", "AGENT_EXECUTION", "anthropic", "model-" + i);
        }

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class), anyMap()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("auth-service down"));

        assertThat(client.checkCredits("7", "AGENT_EXECUTION", "anthropic", "haiku"))
                .as("the neighbour's verdict must survive someone else's burst")
                .isTrue();
    }

    @Test
    @DisplayName("a model the cache never held is refused while auth-service is down")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void unknownKeyFailsClosedDuringAnOutage() {
        CreditConsumptionClient client = client();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class), anyMap()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("auth-service down"));

        assertThat(client.checkCredits("42", "AGENT_EXECUTION", "anthropic", "never-seen"))
                .as("capping the cache must not weaken fail-closed")
                .isFalse();
    }
}
