package com.apimarketplace.agent.cloud;

import com.apimarketplace.common.credential.CloudPlatformCredentialInfoAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CE adapter: fetches the CLOUD's platform-credential public info over the install's cloud link.
 *
 * <p>Same gating as {@link PublicationCloudLlmRuntimeAccess} ({@code marketplace.mode=remote}) -
 * only a CE install ever delegates; on the cloud deployment the bean is absent and the
 * public-info endpoint stays purely local. Fail-closed: unlinked install, BYOK catalog source,
 * non-2xx, or any transport failure all yield empty (debug log only) so the caller falls back
 * to its legacy not-found response.
 */
@Component
@ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")
public class PublicationCloudPlatformInfoAccess implements CloudPlatformCredentialInfoAccess {

    private static final Logger log = LoggerFactory.getLogger(PublicationCloudPlatformInfoAccess.class);

    static final String PLATFORM_INFO_PATH = "/ce-catalog/platform-info/";
    static final String INSTALL_HEADER = "X-LiveContext-Install-Id";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final CloudLlmRuntimeAccess runtimeAccess;
    private final RestTemplate restTemplate;

    // Two constructors (the package-private one is for tests); mark the injection
    // ctor so Spring doesn't fall back to a non-existent default ctor.
    @org.springframework.beans.factory.annotation.Autowired
    public PublicationCloudPlatformInfoAccess(CloudLlmRuntimeAccess runtimeAccess) {
        this(runtimeAccess, defaultRestTemplate());
    }

    PublicationCloudPlatformInfoAccess(CloudLlmRuntimeAccess runtimeAccess, RestTemplate restTemplate) {
        this.runtimeAccess = runtimeAccess;
        this.restTemplate = restTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> fetchPlatformInfo(String integrationName, String apiToolId) {
        if (integrationName == null || integrationName.isBlank()) {
            return Optional.empty();
        }
        Optional<CloudLlmRuntimeCredentials> runtime = runtimeAccess.resolveActiveCatalogRuntime();
        if (runtime.isEmpty()) {
            return Optional.empty();
        }
        CloudLlmRuntimeCredentials credentials = runtime.get();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(credentials.accessToken());
        headers.set(INSTALL_HEADER, credentials.installId());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        String url = url(credentials.cloudApiUrl(), integrationName, apiToolId);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.debug("Cloud platform-info for '{}' returned {} - treating as unavailable",
                        integrationName, response.getStatusCode().value());
                return Optional.empty();
            }
            return Optional.of(new LinkedHashMap<String, Object>(response.getBody()));
        } catch (RuntimeException e) {
            log.debug("Cloud platform-info fetch failed for '{}': {} - treating as unavailable",
                    integrationName, e.getMessage());
            return Optional.empty();
        }
    }

    private static String url(String base, String integrationName, String apiToolId) {
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String url = cleanBase + PLATFORM_INFO_PATH
                + URLEncoder.encode(integrationName, StandardCharsets.UTF_8);
        if (apiToolId != null && !apiToolId.isBlank()) {
            url = url + "?apiToolId=" + URLEncoder.encode(apiToolId, StandardCharsets.UTF_8);
        }
        return url;
    }

    private static RestTemplate defaultRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        RestTemplate template = new RestTemplate();
        template.setRequestFactory(factory);
        return template;
    }
}
