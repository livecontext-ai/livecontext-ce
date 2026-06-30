package com.apimarketplace.interfaces;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ComponentScan(
    basePackages = {
        "com.apimarketplace.interfaces",
        "com.apimarketplace.common.storage",
        "com.apimarketplace.auth.client"
    }
)
@EnableJpaRepositories(basePackages = {
    "com.apimarketplace.interfaces.repository",
    "com.apimarketplace.common.storage.repository"
})
@EntityScan(basePackages = {
    "com.apimarketplace.interfaces.domain"
},
    basePackageClasses = {
        com.apimarketplace.common.storage.domain.StorageEntity.class,
        com.apimarketplace.common.storage.domain.TenantStorageQuota.class
    })
@EnableAsync
public class InterfaceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InterfaceServiceApplication.class, args);
    }
}
