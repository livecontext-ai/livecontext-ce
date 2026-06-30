package com.apimarketplace.orchestrator.config;

import io.lettuce.core.ClientOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the websearch-service HTTP client.
 * Disabled in CE mode (websearch.enabled=false).
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
public class WebSearchConfig {

    @Value("${websearch.service.url:http://localhost:8085}")
    private String serviceUrl;

    /**
     * Public base URL the FRONTEND uses to upgrade
     * {@code wss://<base>/cdp/{sessionId}?token=...} for the live-view
     * panel. In prod the websearch service is reachable internally on a
     * VLAN IP (e.g. {@code http://10.0.0.0:8085}) which the user's browser
     * cannot reach - Caddy on app-host proxies {@code /cdp/*} to the
     * websearch host instead. This must therefore point at the public
     * site (e.g. {@code https://livecontext.ai}). When unset, falls back
     * to {@link #serviceUrl} so dev/local setups (where service URL = host
     * URL) keep working without extra config.
     */
    @Value("${websearch.public.ws-base:}")
    private String publicWsBase;

    @Value("${websearch.service.timeout.connect:5000}")
    private int connectTimeout;

    @Value("${websearch.service.timeout.read:15000}")
    private int readTimeout;

    @Value("${websearch.job.blpop-timeout:150}")
    private int blpopTimeout;

    /**
     * BLPOP timeout for {@code agent_browse} sessions, in seconds.
     * Intentionally shorter than the agent-side per-tool timeout (640 s on
     * {@code web_search}) so the orchestrator times out FIRST and can run
     * its cleanup path (auto-abort the runner session + LREM the per-user
     * concurrent slot + capture partial steps for the failure recap) BEFORE
     * the agent client gives up. Without this ordering the runner keeps
     * spinning on a session the agent has already abandoned, holding the
     * per-user {@code agent:browser:user:{uid}:concurrent} slot for the
     * runner's full internal timeout (~600 s) and locking the user out of
     * starting another browse session in the meantime.
     *
     * <p>Default 600 s aligns with the runner's hard wallclock cap
     * ({@code DEFAULT_TIMEOUT_S} in {@code runner.py}) so a successful
     * 5-min browse (e.g. multi-page booking flow) lands in the BLPOP
     * window. The 640 s ceiling on {@code web_search} gives a 40 s
     * cleanup window - enough for drain1 (1 s) + session_id resolve
     * (≤300 ms) + abort POST (≤15 s read timeout) + LREM/DEL (~50 ms) +
     * drain2 (10 s for the runner to react to ABORT and push_result the
     * recap blob) ≈ 26.4 s worst case, with ~13 s margin.
     */
    @Value("${websearch.job.browser-agent-blpop-timeout:600}")
    private int browserAgentBlpopTimeout;

    @Value("${websearch.fetch.max-parallel:2}")
    private int maxParallelFetches;

    @Value("${websearch.callback.base-url:http://localhost:8099}")
    private String callbackBaseUrl;

    @Value("${websearch.service.gateway-secret:}")
    private String gatewaySecret;

    @Value("${websearch.redis.auto-reconnect:true}")
    private boolean redisAutoReconnect;

    @Bean("webSearchRestTemplate")
    public RestTemplate webSearchRestTemplate() {
        log.info("[WebSearchConfig] Creating webSearchRestTemplate: url={}, gatewaySecret={}",
                serviceUrl, gatewaySecret != null && !gatewaySecret.isBlank() ? "SET (" + gatewaySecret.length() + " chars)" : "EMPTY");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        var restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(factory);

        // Inject X-Gateway-Secret header if configured
        if (gatewaySecret != null && !gatewaySecret.isBlank()) {
            ClientHttpRequestInterceptor secretInterceptor = (request, body, execution) -> {
                request.getHeaders().set("X-Gateway-Secret", gatewaySecret);
                return execution.execute(request, body);
            };
            restTemplate.setInterceptors(List.of(secretInterceptor));
        }

        return restTemplate;
    }

    /**
     * Dedicated StringRedisTemplate with a long command timeout for BLPOP.
     * The default Lettuce timeout (5s) is too short for blocking operations
     * that wait up to 150s for a job result.
     */
    @Bean("webSearchRedisTemplate")
    public StringRedisTemplate webSearchRedisTemplate(RedisConnectionFactory defaultFactory) {
        // Copy host/port/password from the default factory
        RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration();
        if (defaultFactory instanceof LettuceConnectionFactory lcf) {
            standaloneConfig.setHostName(lcf.getHostName());
            standaloneConfig.setPort(lcf.getPort());
            standaloneConfig.setDatabase(lcf.getDatabase());
            // Copy password from the default factory's standalone config
            if (lcf.getPassword() != null && !lcf.getPassword().isEmpty()) {
                standaloneConfig.setPassword(lcf.getPassword());
            }
        }

        // The Lettuce command timeout MUST exceed the longest BLPOP this
        // template handles. Two paths use this template:
        //   - generic web jobs (fetch/search/...) → blpopTimeout (150 s)
        //   - agent_browse → browserAgentBlpopTimeout (205 s, longer to
        //     match the bumped agent client ceiling at 240 s)
        // Without max(...) here, an agent_browse BLPOP at 205 s would
        // trip RedisCommandTimeoutException at the lower 180 s ceiling
        // BEFORE the natural timeout fires, defeating the cleanup ladder
        // (orchestrator would throw RedisCommandTimeoutException → outer
        // try/catch in submitAndAwait → failedError, NOT the structured
        // timeoutError that triggers onSubmitTimeout cleanup).
        int maxBlpop = Math.max(blpopTimeout, browserAgentBlpopTimeout);
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(maxBlpop + 30))
                .clientOptions(ClientOptions.builder()
                        .autoReconnect(redisAutoReconnect)
                        .build())
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        factory.afterPropertiesSet();

        return new StringRedisTemplate(factory);
    }

    @Bean("webSearchRedisConnectionFactoryShutdown")
    public DisposableBean webSearchRedisConnectionFactoryShutdown(
            @Qualifier("webSearchRedisTemplate") StringRedisTemplate redisTemplate) {
        return () -> {
            RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
            if (connectionFactory instanceof LettuceConnectionFactory lettuceConnectionFactory) {
                lettuceConnectionFactory.destroy();
            }
        };
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    /**
     * Base URL used to build the {@code wss://…/cdp/{sid}} live-view URL
     * for the frontend. Falls back to {@link #serviceUrl} when not
     * explicitly configured (dev/local), so prod is the only environment
     * that needs to set {@code websearch.public.ws-base}.
     */
    public String getPublicWsBase() {
        return (publicWsBase == null || publicWsBase.isBlank()) ? serviceUrl : publicWsBase;
    }

    public int getBlpopTimeout() {
        return blpopTimeout;
    }

    public int getBrowserAgentBlpopTimeout() {
        return browserAgentBlpopTimeout;
    }

    public int getMaxParallelFetches() {
        return maxParallelFetches;
    }

    public String getCallbackBaseUrl() {
        return callbackBaseUrl;
    }
}
