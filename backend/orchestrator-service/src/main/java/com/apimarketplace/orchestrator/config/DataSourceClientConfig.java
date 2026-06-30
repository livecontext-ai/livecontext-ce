package com.apimarketplace.orchestrator.config;

import com.apimarketplace.datasource.client.DataSourceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the DataSource HTTP client.
 * Connects orchestrator-service to datasource-service via HTTP.
 */
@Configuration
public class DataSourceClientConfig {

    @Bean
    public DataSourceClient dataSourceClient(
            @Value("${services.datasource-url:http://localhost:8088}") String datasourceServiceUrl) {
        return new DataSourceClient(datasourceServiceUrl);
    }
}
