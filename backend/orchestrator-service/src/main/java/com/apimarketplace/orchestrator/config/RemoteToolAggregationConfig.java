package com.apimarketplace.orchestrator.config;

import com.apimarketplace.orchestrator.services.mcp.AggregatedToolCatalog;
import com.apimarketplace.orchestrator.services.mcp.RemoteToolGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Wires the cloud MCP tool aggregation: {@link AggregatedToolCatalog} (discovers the
 * sibling services' tools) and {@link RemoteToolGateway} (executes them).
 *
 * <p>Gated on {@code deployment.mode=microservice} (default in cloud). In the CE
 * monolith ({@code deployment.mode=monolith}) every provider is already registered
 * in the single in-process registry, so these beans are absent and
 * {@code McpProtocolService} serves the local registry directly.
 */
@Configuration
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class RemoteToolAggregationConfig {

    @Bean
    public AggregatedToolCatalog aggregatedToolCatalog(
            @Value("${services.agent-url:http://localhost:8090}") String agentUrl,
            @Value("${services.datasource-url:http://localhost:8088}") String datasourceUrl,
            @Value("${services.interface-url:http://localhost:8089}") String interfaceUrl,
            @Value("${orchestrator.catalog.base-url:http://localhost:8081}") String catalogUrl) {
        List<String> siblingUrls = List.of(agentUrl, datasourceUrl, interfaceUrl, catalogUrl);
        return new AggregatedToolCatalog(siblingUrls, discoveryRestTemplate());
    }

    @Bean
    public RemoteToolGateway remoteToolGateway(AggregatedToolCatalog aggregatedToolCatalog,
                                               ObjectMapper objectMapper) {
        return new RemoteToolGateway(aggregatedToolCatalog, executionRestTemplate(), objectMapper);
    }

    /** Short timeouts: a slow/dead sibling degrades to serve-stale instead of blocking tools/list. */
    private static RestTemplate discoveryRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        return new RestTemplate(factory);
    }

    /**
     * Long read timeout: an aggregated tools/call can invoke a long-blocking tool
     * (wait, workflow run). Mirrors RemoteToolExecutionService's 12 min ceiling.
     */
    private static RestTemplate executionRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofMinutes(12).toMillis());
        return new RestTemplate(factory);
    }
}
