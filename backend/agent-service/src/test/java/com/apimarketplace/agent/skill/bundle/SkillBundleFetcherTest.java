package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * CE-side fetcher contract: presents the cloud-link bearer + install header, maps 200 to
 * FETCHED, 404 to NO_ACTIVE (cloud just hasn't activated), other HTTP codes to HTTP_ERROR,
 * transport failures to NETWORK_ERROR, and an empty cloud-url to NOT_CONFIGURED.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillBundleFetcher - fetchLatest")
class SkillBundleFetcherTest {

    @Mock private RestTemplate restTemplate;

    private final CloudLlmRuntimeCredentials creds =
            new CloudLlmRuntimeCredentials("the-token", "install-1", "https://cloud");

    private SkillBundleFetcher fetcher() {
        return new SkillBundleFetcher(restTemplate, "https://cloud");
    }

    @Test
    @DisplayName("200 -> FETCHED, presenting the bearer token + X-LiveContext-Install-Id")
    void fetched() {
        SignedSkillBundle body = new SignedSkillBundle(1, 1, "c", "s", "k", "i", 1, 10, "p");
        when(restTemplate.exchange(eq("https://cloud/api/skill-bundles/latest"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(SignedSkillBundle.class)))
                .thenReturn(ResponseEntity.ok(body));

        SkillBundleFetcher.FetchResult r = fetcher().fetchLatest(creds);

        assertThat(r.status()).isEqualTo(SkillBundleFetcher.Status.FETCHED);
        assertThat(r.bundle()).isEqualTo(body);

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(any(String.class), eq(HttpMethod.GET),
                entity.capture(), eq(SignedSkillBundle.class));
        assertThat(entity.getValue().getHeaders().getFirst("Authorization")).isEqualTo("Bearer the-token");
        assertThat(entity.getValue().getHeaders().getFirst("X-LiveContext-Install-Id")).isEqualTo("install-1");
    }

    @Test
    @DisplayName("404 -> NO_ACTIVE (cloud has not activated a bundle yet, not an error)")
    void noActive() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SignedSkillBundle.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        assertThat(fetcher().fetchLatest(creds).status()).isEqualTo(SkillBundleFetcher.Status.NO_ACTIVE);
    }

    @Test
    @DisplayName("403 (unlinked / inactive install) -> HTTP_ERROR")
    void httpError() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SignedSkillBundle.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));

        assertThat(fetcher().fetchLatest(creds).status()).isEqualTo(SkillBundleFetcher.Status.HTTP_ERROR);
    }

    @Test
    @DisplayName("transport failure -> NETWORK_ERROR")
    void networkError() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SignedSkillBundle.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertThat(fetcher().fetchLatest(creds).status()).isEqualTo(SkillBundleFetcher.Status.NETWORK_ERROR);
    }

    @Test
    @DisplayName("200 with an empty body -> HTTP_ERROR (cloud answered but gave us nothing to verify)")
    void emptyBodyIsHttpError() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(SignedSkillBundle.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThat(fetcher().fetchLatest(creds).status()).isEqualTo(SkillBundleFetcher.Status.HTTP_ERROR);
    }

    @Test
    @DisplayName("empty cloud-url -> NOT_CONFIGURED (no HTTP attempt)")
    void notConfigured() {
        SkillBundleFetcher noUrl = new SkillBundleFetcher(restTemplate, "");

        assertThat(noUrl.fetchLatest(creds).status()).isEqualTo(SkillBundleFetcher.Status.NOT_CONFIGURED);
        org.mockito.Mockito.verifyNoInteractions(restTemplate);
    }
}
