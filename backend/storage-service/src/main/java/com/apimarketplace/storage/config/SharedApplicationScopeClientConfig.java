package com.apimarketplace.storage.config;

import com.apimarketplace.common.web.SharedApplicationScopeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Share-link scope checks for file serves (which files belong to a shared application),
 * answered by orchestrator-service. Conditional so a context that already wires one (the CE
 * monolith also scans interface-service's copy) keeps exactly one bean.
 */
@Configuration
public class SharedApplicationScopeClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public SharedApplicationScopeClient sharedApplicationScopeClient(
            @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        return new SharedApplicationScopeClient(orchestratorUrl);
    }
}
