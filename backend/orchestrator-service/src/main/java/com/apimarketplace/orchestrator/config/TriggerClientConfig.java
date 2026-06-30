package com.apimarketplace.orchestrator.config;

import com.apimarketplace.trigger.client.TriggerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class TriggerClientConfig {

    @Bean
    public TriggerClient triggerClient(
            @Value("${services.trigger-url:http://localhost:8091}") String triggerServiceUrl) {
        // JdkClientHttpRequestFactory supports HTTP PATCH; the default
        // SimpleClientHttpRequestFactory does not and breaks endpoint back-link calls.
        RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());
        return new TriggerClient(restTemplate, triggerServiceUrl);
    }
}
