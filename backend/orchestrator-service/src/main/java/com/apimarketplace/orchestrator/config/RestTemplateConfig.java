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
}
