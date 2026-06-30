package com.apimarketplace.catalog.tools.websearch;

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

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the websearch-service HTTP client.
 * Disabled in CE mode (websearch.enabled=false).
 */
@Configuration
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
public class WebSearchConfig {

    @Value("${websearch.service.url:http://localhost:8085}")
    private String serviceUrl;

    @Value("${websearch.service.timeout.connect:5000}")
    private int connectTimeout;

    @Value("${websearch.service.timeout.read:15000}")
    private int readTimeout;

    @Value("${websearch.job.blpop-timeout:150}")
    private int blpopTimeout;

    @Value("${websearch.fetch.max-parallel:2}")
    private int maxParallelFetches;

    @Value("${websearch.service.gateway-secret:}")
    private String gatewaySecret;

    @Bean("webSearchRestTemplate")
    public RestTemplate webSearchRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        var restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(factory);

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
     */
    @Bean("webSearchRedisTemplate")
    public StringRedisTemplate webSearchRedisTemplate(RedisConnectionFactory defaultFactory) {
        RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration();
        if (defaultFactory instanceof LettuceConnectionFactory lcf) {
            standaloneConfig.setHostName(lcf.getHostName());
            standaloneConfig.setPort(lcf.getPort());
            standaloneConfig.setDatabase(lcf.getDatabase());
            if (lcf.getPassword() != null && !lcf.getPassword().isEmpty()) {
                standaloneConfig.setPassword(lcf.getPassword());
            }
        }

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(blpopTimeout + 30))
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        factory.afterPropertiesSet();

        return new StringRedisTemplate(factory);
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    public int getBlpopTimeout() {
        return blpopTimeout;
    }

    public int getMaxParallelFetches() {
        return maxParallelFetches;
    }
}
