package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.version.CeReleaseController.LatestRelease;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient-backed {@link ReleaseFeedClient}: anonymous GET of the configured
 * cloud release feed with a short timeout. Created only in the embedded (CE)
 * edition, the only one that polls for updates.
 */
@Component
@ConditionalOnExpression("'${auth.mode:keycloak}' == 'embedded'")
public class HttpReleaseFeedClient implements ReleaseFeedClient {

    private final RestClient restClient;
    private final String feedUrl;

    public HttpReleaseFeedClient(
            @Value("${ce.version-check.url:https://livecontext.ai/api/ce/releases/latest}") String feedUrl,
            @Value("${ce.version-check.timeout-ms:5000}") int timeoutMs) {
        this.feedUrl = feedUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public LatestRelease fetchLatest(String currentVersion) {
        return restClient.get()
                .uri(feedUrl, uri -> uri.queryParam("current", currentVersion).build())
                .retrieve()
                .body(LatestRelease.class);
    }
}
