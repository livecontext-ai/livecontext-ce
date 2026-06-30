package com.apimarketplace.orchestrator.config;

import com.apimarketplace.interfaces.client.InterfaceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InterfaceClientConfig {

    @Bean
    public InterfaceClient interfaceClient(
            @Value("${services.interface-url:http://localhost:8089}") String interfaceServiceUrl) {
        return new InterfaceClient(interfaceServiceUrl);
    }
}
