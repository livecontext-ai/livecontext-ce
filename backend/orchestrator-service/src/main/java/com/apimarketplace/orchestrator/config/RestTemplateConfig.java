package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.web.NoRedirectSimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration pour RestTemplate
 * Respecte le principe Single Responsibility (SOLID)
 */
@Configuration
public class RestTemplateConfig {
    
    @Value("${http.client.timeout.connect:5000}")
    private int connectTimeout;

    @Value("${http.client.timeout.read:30000}")
    private int readTimeout;

    /**
     * Read timeout for the renderer sidecar's VIDEO endpoint. A video render legitimately takes
     * up to the recording duration (<= 120s) plus load (<= 10s + 30s clamp headroom) and the
     * ffmpeg transcode (<= 60s sidecar-side) - far beyond the default 30s read timeout, which
     * would abort every recording midway. 210s covers the worst case end to end.
     */
    @Value("${http.client.timeout.video-read:210000}")
    private int videoReadTimeout;

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(clientHttpRequestFactory());
        return restTemplate;
    }

    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /**
     * Dedicated RestTemplate for long-running renderer video calls (see
     * {@code InterfaceScreenshotServiceImpl}). Same no-redirect factory, longer read timeout.
     * Kept separate so no other HTTP call inherits a 3-minute read window.
     */
    @Bean(name = "videoRenderRestTemplate")
    public RestTemplate videoRenderRestTemplate() {
        NoRedirectSimpleClientHttpRequestFactory factory = new NoRedirectSimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(videoReadTimeout);
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(factory);
        return restTemplate;
    }
}
