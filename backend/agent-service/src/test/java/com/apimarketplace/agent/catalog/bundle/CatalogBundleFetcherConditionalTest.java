package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.skill.bundle.SignedSkillBundle;
import com.apimarketplace.agent.skill.bundle.SkillBundleFetcher;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Conditional fetch + Retry-After for the two cloud-link-gated fetchers (model catalog and skills):
 * the checksum held is sent as If-None-Match, a 304 is NOT_MODIFIED however the client stack
 * surfaces it, and a refusal's Retry-After reaches the scheduler.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Model + skill bundle fetchers - If-None-Match, 304 and Retry-After")
class CatalogBundleFetcherConditionalTest {

    private static final CloudLlmRuntimeCredentials CREDS =
            new CloudLlmRuntimeCredentials("tok", "install-1", "https://cloud.example/api");
    private static final String SUM = "d".repeat(64);

    @Mock private RestTemplate restTemplate;

    @Test
    @DisplayName("model: the held checksum is sent quoted as If-None-Match")
    @SuppressWarnings("unchecked")
    void modelSendsIfNoneMatch() {
        ArgumentCaptor<HttpEntity<Void>> sent = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), sent.capture(), eq(SignedBundle.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.NOT_MODIFIED).build());

        CatalogBundleFetcher.FetchResult r =
                new CatalogBundleFetcher(restTemplate, "https://cloud.example").fetchLatest(CREDS, SUM);

        assertThat(r.status()).isEqualTo(CatalogBundleFetcher.Status.NOT_MODIFIED);
        assertThat(sent.getValue().getHeaders().getIfNoneMatch()).containsExactly("\"" + SUM + "\"");
    }

    @Test
    @DisplayName("model: no checksum (first sync) sends no validator")
    @SuppressWarnings("unchecked")
    void modelFirstSyncSendsNoValidator() {
        ArgumentCaptor<HttpEntity<Void>> sent = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), sent.capture(), eq(SignedBundle.class)))
                .thenReturn(ResponseEntity.ok(new SignedBundle(1L, 1, SUM, "s", "k", "i", 1, 1, "eA==")));

        new CatalogBundleFetcher(restTemplate, "https://cloud.example").fetchLatest(CREDS, null);

        assertThat(sent.getValue().getHeaders().containsKey(HttpHeaders.IF_NONE_MATCH)).isFalse();
    }

    @Test
    @DisplayName("model: a 304 surfaced as an exception is still NOT_MODIFIED")
    void modelNotModifiedAsException() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(SignedBundle.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_MODIFIED, "Not Modified", null, null, null));

        assertThat(new CatalogBundleFetcher(restTemplate, "https://cloud.example").fetchLatest(CREDS, SUM).status())
                .isEqualTo(CatalogBundleFetcher.Status.NOT_MODIFIED);
    }

    @Test
    @DisplayName("model: a 429 carries its Retry-After to the scheduler")
    void modelThrottledCarriesRetryAfter() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, "1800");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(SignedBundle.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many", h, null, null));

        CatalogBundleFetcher.FetchResult r =
                new CatalogBundleFetcher(restTemplate, "https://cloud.example").fetchLatest(CREDS, SUM);

        assertThat(r.status()).isEqualTo(CatalogBundleFetcher.Status.HTTP_ERROR);
        assertThat(r.detail()).contains("429");
        assertThat(r.retryAfter()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("model: a refusal without Retry-After leaves it null (the ladder applies)")
    void modelRefusalWithoutRetryAfter() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(SignedBundle.class)))
                .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", null, null, null));

        assertThat(new CatalogBundleFetcher(restTemplate, "https://cloud.example").fetchLatest(CREDS, SUM).retryAfter())
                .isNull();
    }

    @Test
    @DisplayName("skills: If-None-Match sent, 304 is NOT_MODIFIED")
    @SuppressWarnings("unchecked")
    void skillConditional() {
        ArgumentCaptor<HttpEntity<Void>> sent = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), sent.capture(), eq(SignedSkillBundle.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.NOT_MODIFIED).build());

        SkillBundleFetcher.FetchResult r = new SkillBundleFetcher(restTemplate, "https://cloud").fetchLatest(CREDS, SUM);

        assertThat(r.status()).isEqualTo(SkillBundleFetcher.Status.NOT_MODIFIED);
        assertThat(sent.getValue().getHeaders().getIfNoneMatch()).containsExactly("\"" + SUM + "\"");
    }

    @Test
    @DisplayName("skills: a 429 carries its Retry-After")
    void skillThrottledCarriesRetryAfter() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, "60");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(SignedSkillBundle.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many", h, null, null));

        assertThat(new SkillBundleFetcher(restTemplate, "https://cloud").fetchLatest(CREDS, SUM).retryAfter())
                .isEqualTo(Duration.ofMinutes(1));
    }
}
