package com.apimarketplace.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ComponentScan(
    basePackages = {
        "com.apimarketplace.agent",
        "com.apimarketplace.common.security",
        "com.apimarketplace.common.storage",
        "com.apimarketplace.auth.client"
    }
)
@EnableJpaRepositories(basePackages = {
    "com.apimarketplace.agent.repository",
    "com.apimarketplace.common.storage.repository"
})
@EntityScan(basePackages = {
    "com.apimarketplace.agent.domain"
},
    basePackageClasses = {
        com.apimarketplace.common.storage.domain.StorageEntity.class,
        com.apimarketplace.common.storage.domain.TenantStorageQuota.class
    })
@EnableAsync
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
