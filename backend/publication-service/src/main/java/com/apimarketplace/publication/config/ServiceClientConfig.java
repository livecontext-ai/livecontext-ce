package com.apimarketplace.publication.config;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for inter-service HTTP clients used by publication-service.
 */
@Configuration
public class ServiceClientConfig {

    @Value("${services.orchestrator-url:http://localhost:8099}")
    private String orchestratorUrl;

    @Value("${services.agent-url:http://localhost:8090}")
    private String agentUrl;

    @Value("${services.interface-url:http://localhost:8089}")
    private String interfaceUrl;

    @Value("${services.datasource-url:http://localhost:8088}")
    private String datasourceUrl;

    @Bean
    public OrchestratorInternalClient orchestratorInternalClient() {
        return new OrchestratorInternalClient(orchestratorUrl);
    }

    @Bean
    public AgentClient agentClient() {
        return new AgentClient(agentUrl);
    }

    @Bean
    public InterfaceClient interfaceClient() {
        return new InterfaceClient(interfaceUrl);
    }

    @Bean
    public DataSourceClient dataSourceClient() {
        return new DataSourceClient(datasourceUrl);
    }
}
