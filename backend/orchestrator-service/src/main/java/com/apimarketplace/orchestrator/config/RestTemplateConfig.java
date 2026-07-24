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
     * Read timeout for the renderer sidecar's VIDEO endpoint, far beyond the default 30s which
     * would abort every recording midway.
     *
     * <p>This MUST stay above the sidecar's WORST-CASE total, or a read timeout aborts the call
     * client-side and throws away a clip the sidecar was about to return (best-effort =&gt; the
     * node then emits NO video at all, strictly worse than a short one). The sidecar's budget is
     * not its total: {@code wallDeadline} is armed only AFTER {@code page.setContent}, which is
     * separately capped at {@code MAX_TIMEOUT_MS} = 30s. Worst case therefore = 30s load + 450s
     * wall budget + the ffmpeg finalise and body transfer, and the sidecar pool can queue on top.
     * 540s leaves ~60s of real margin over that 480s floor.
     */
    @Value("${http.client.timeout.video-read:540000}")
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
