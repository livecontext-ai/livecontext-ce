package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.web.NoRedirectSimpleClientHttpRequestFactory;
import com.apimarketplace.orchestrator.services.http.PinnedRestTemplateFactory;
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

    /**
     * Read timeout for a generation call ({@code agent:generate}). The catalog waits for the
     * provider to finish before it answers, and an async video model legitimately takes
     * minutes. Kept separate so no other HTTP call inherits a window this long.
     *
     * <p>1500000 ms = 25 min, which is NOT a round number: catalog's own worst
     * case is a 10 min synchronous submit plus a 10 min poll loop
     * ({@code ToolExecutionManager.GENERATION_CALLER_BUDGET_MS}, the single
     * place that arithmetic lives), and this must not be shorter. At the
     * previous 600000 ms this node abandoned the call at 10 minutes while
     * catalog went on to finish it, store the asset and COMMIT the charge: the
     * customer was billed for a generation the run reported as unreachable, and
     * nothing released it because from catalog's side nothing had failed.
     */
    @Value("${http.client.timeout.generation-read:1500000}")
    private int generationReadTimeout;

    /** Read window for the node-usage push - see {@link #nodeUsageRestTemplate()}. */
    @Value("${orchestrator.node-usage.read-timeout-ms:10000}")
    private int nodeUsageReadTimeout;

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

    /**
     * Dedicated RestTemplate for the {@code agent:generate} node's call into catalog-service.
     *
     * <p><b>Built on the JDK HTTP client, NOT on {@code HttpURLConnection}, and
     * that is the whole point of this bean.</b> Every other template here uses
     * {@link NoRedirectSimpleClientHttpRequestFactory}, which wraps
     * {@code HttpURLConnection}: on an {@code IOException} from a pooled
     * connection the peer closed while idle (a rolling deploy, a pod restart,
     * an idle reaper) that client SILENTLY RE-SENDS the POST once, because
     * {@code sun.net.http.retryPost} defaults to true.
     *
     * <p>For an ordinary call a duplicate is noise. For this one it is money.
     * The billing layer mints a fresh call reference PER DISPATCH so that a
     * second dispatch is deliberately a second charge, on the stated reasoning
     * that one HTTP execute is one dispatch to the provider. That premise is
     * false for a transport that re-sends by itself: two submissions become two
     * reservations, two commits and two provider generations, of which the run
     * keeps one. The browser leg of this same feature already refuses to retry
     * for exactly this reason.
     *
     * <p>{@code java.net.http} does not retry a non-idempotent method
     * ({@code jdk.httpclient.enableAllMethodRetry} is false by default), so the
     * duplicate cannot originate in the transport. Redirects are disabled here
     * too, keeping the property the replaced factory existed to provide.
     *
     * @see #generationReadTimeout
     */
    /**
     * Dedicated RestTemplate for the node-usage push into catalog-service (V461).
     *
     * <p>Two properties the shared template cannot give it, both of which matter more
     * than the call itself does.
     *
     * <p><b>A short read window.</b> The push runs on a {@code @Scheduled} thread out of
     * a pool of three. Inheriting the shared 10-minute read timeout would let one
     * unresponsive catalog hold a scheduler thread for ten minutes, starving jobs that
     * do matter, to deliver a popularity counter.
     *
     * <p><b>No silent re-send.</b> {@code HttpURLConnection} re-sends a POST by itself on
     * an IOException from a connection the peer closed while idle
     * ({@code sun.net.http.retryPost}), which for this endpoint means one window counted
     * twice. The JDK client does not retry a non-idempotent method, so the caller's own
     * give-back-and-retry stays the only retry there is - see
     * {@code generationRestTemplate} above, which exists for the same reason with far
     * higher stakes.
     */
    @Bean(name = "nodeUsageRestTemplate")
    public RestTemplate nodeUsageRestTemplate() {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(java.time.Duration.ofMillis(connectTimeout))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(java.time.Duration.ofMillis(nodeUsageReadTimeout));
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(factory);
        return restTemplate;
    }

    @Bean(name = "generationRestTemplate")
    public RestTemplate generationRestTemplate() {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(java.time.Duration.ofMillis(connectTimeout))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(java.time.Duration.ofMillis(generationReadTimeout));
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(factory);
        return restTemplate;
    }

    /**
     * Dedicated RestTemplate for {@code core:http_request}'s DEFAULT (no per-node timeout
     * configured) call, pinned to the vetted address (LC-002 / LC-006, CASA readiness round 3 -
     * DNS rebinding). See {@link PinnedRestTemplateFactory} for why this is a SEPARATE bean from
     * the shared, unqualified {@code restTemplate()} above: that one also carries internal
     * service-to-service calls to private cluster addresses, which this pin would refuse.
     *
     * <p>{@code ServiceRegistry.getRestTemplate()} has exactly one consumer, {@code
     * HttpRequestNode}, so wiring THIS bean into {@code ExecutionServiceInjector}'s {@code
     * restTemplate} field (instead of the plain {@link #restTemplate()} bean) is a safe,
     * single-consumer swap - not a change to what every other by-type {@code RestTemplate}
     * injection in this service resolves to.
     */
    @Bean(name = "httpRequestNodeRestTemplate")
    public RestTemplate httpRequestNodeRestTemplate() {
        return PinnedRestTemplateFactory.build(connectTimeout, readTimeout);
    }
}
